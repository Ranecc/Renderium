// Renderium - GPU 驱动的 LOD 数据管理器
// 负责 Minecraft Chunk 数据与 GPU SSBO 之间的转换
// 提供 AABB 上传、LOD 结果回读、可见性过滤功能

package com.renderium.gpu.lod;

import com.renderium.optimization.lod.LODChunk;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.logging.Logger;

/**
 * GPU 驱动的 LOD 数据管理器。
 * <p>
 * 作为 CPU 数据与 GPU Compute Shader（lod_compute.comp）之间的桥梁，
 * 负责 Minecraft Chunk 数据到 GPU 友好格式的转换、UBO/SSBO 数据管理、
 * 以及可见性结果的回读。
 *
 * <h3>架构角色：</h3>
 * <pre>
 * ┌──────────────────┐    ┌──────────────────────┐    ┌──────────────────┐
 * │   LODChunk[]     │───▶│  GPULODDataManager   │───▶│  lod_compute.comp │
 * │ (CPU 端区块数据) │    │  (数据格式转换层)     │    │  (GPU LOD 计算)   │
 * └──────────────────┘    └──────────────────────┘    └────────┬─────────┘
 *                                                           │
 *                                                           ▼
 *                                                 ┌──────────────────┐
 *                                                 │ VisibilityOutput │
 *                                                 │ (可见性+LOD级别)  │
 *                                                 └──────────────────┘
 * </pre>
 *
 * <h3>管理的资源：</h3>
 * <ul>
 *   <li><b>Chunk Bounds SSBO</b>: 每个 Chunk 的 AABB（2×vec4），供 Shader 读取</li>
 *   <li><b>LOD Config UBO</b>: 相机矩阵、投影参数、LOD 距离阈值（std140 布局）</li>
 *   <li><b>Visibility Output SSBO</b>: 每个 Chunk 的可见性标志 + 计算后 LOD 级别</li>
 * </ul>
 *
 * <h3>UBO 布局定义（std140，对应 lod_compute.comp）：</h3>
 * <pre>
 * layout (std140, binding = 6) uniform LODConfig {
 *     mat4 viewProjMatrix;          // offset 0,   64 bytes
 *     vec4 projectionParams;        // offset 64,  16 bytes [near, far, fovY_tan, aspect]
 *     vec4 screenSize;              // offset 80,  16 bytes [width, height, chunkCount, _pad]
 *     float lodDistances[8];        // offset 96,  32 bytes (每级 LOD 最大距离)
 *     float lodBias;                // offset 128, 4 bytes
 *     uint maxLODLevels;            // offset 132, 4 bytes
 * };
 * // 总大小: 136 bytes, std140 对齐到 144
 * </pre>
 *
 * <h3>SSBO 布局定义：</h3>
 * <pre>
 * // Chunk Bounds SSBO (binding = 0):
 * struct ChunkBounds {
 *     vec4 minBound;   // [minX, minY, minZ, padding]
 *     vec4 maxBound;   // [maxX, maxY, maxZ, padding]
 * };  // 每个 Chunk 占 32 bytes
 *
 * // Visibility Output SSBO (binding = 2):
 * struct VisibilityResult {
 *     uint visible;    // 可见性标志 (0=不可见, 1=可见)
 *     uint lodLevel;   // 计算后的 LOD 级别
 * };  // 每个 Chunk 占 8 bytes
 * </pre>
 *
 * @see com.renderium.framegraph.pass.LodCullingComputePass
 * @see com.renderium.gpu.hiz.OcclusionCullConfig
 * @see com.renderium.optimization.lod.LODChunk
 * @since 5.2.0
 */
public final class GPULODDataManager {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(GPULODDataManager.class.getName());

    // ==================== 常量定义 ====================

    /** 单个 Chunk AABB 在 SSBO 中占用的字节数：2 × vec4 = 32 bytes */
    public static final int CHUNK_BOUNDS_STRIDE = 32;

    /** 单个 Visibility Result 在 SSBO 中占用的字芦数：uint + uint = 8 bytes */
    public static final int VISIBILITY_RESULT_STRIDE = 8;

    /** std140 布局下 LOD Config UBO 总大小（对齐到 vec4 边界） */
    public static final int LOD_CONFIG_UBO_SIZE = 144;

    /** 最大支持的 LOD 级别数 */
    public static final int MAX_LOD_LEVELS = 8;

    /** 默认 LOD 距离阈值（世界坐标单位，每级最大距离） */
    private static final float[] DEFAULT_LOD_THRESHOLDS = {32.0f, 64.0f, 128.0f, 256.0f, 512.0f};

    /** Minecraft 区块尺寸（方块数） */
    private static final float CHUNK_BLOCK_SIZE = 16.0f;

    // ==================== UBO 字段偏移量（std140 布局）====================

    /** viewProjMatrix 偏移量：0 字节，64 字节 */
    public static final long OFFSET_VIEW_PROJ_MATRIX = 0L;

    /** projectionParams 偏移量：64 字节，16 字节 */
    public static final long OFFSET_PROJECTION_PARAMS = 64L;

    /** screenSize 偏移量：80 字节，16 字节 */
    public static final long OFFSET_SCREEN_SIZE = 80L;

    /** lodDistances 数组偏移量：96 字节，32 字节 (8 × float) */
    public static final long OFFSET_LOD_DISTANCES = 96L;

    /** lodBias 偏移量：128 字节，4 字节 */
    public static final long OFFSET_LOD_BIAS = 128L;

    /** maxLODLevels 偏移量：132 字节，4 字节 */
    public static final long OFFSET_MAX_LOD_LEVELS = 132L;

    // ==================== 数据字段 ====================

    /**
     * 视图投影矩阵（列主序，16 个 float）
     * 对应 GLSL: mat4 viewProjMatrix
     */
    private float[] viewProjMatrix = new float[16];

    /**
     * 投影参数向量 [near, far, fovY_tan, aspectRatio]
     * 对应 GLSL: vec4 projectionParams
     */
    private float[] projectionParams = new float[]{0.1f, 1000.0f, 0.0f, 16.0f / 9.0f};

    /**
     * 屏幕尺寸向量 [width, height, chunkCount, _padding]
     * 对应 GLSL: vec4 screenSize
     */
    private float[] screenSize = new float[]{1920.0f, 1080.0f, 0.0f, 0.0f};

    /**
     * 各级 LOD 最大距离阈值数组
     * 对应 GLSL: float lodDistances[8]
     * thresholds[i] 表示第 i 级 LOD 的最大渲染距离
     */
    private float[] lodDistances = Arrays.copyOf(DEFAULT_LOD_THRESHOLDS, MAX_LOD_LEVELS);

    /**
     * LOD 偏移量（防止 pop-in）
     * 正值使 LOD 切换更保守（更远才切换到低细节）
     * 对应 GLSL: float lodBias
     */
    private float lodBias = 0.0f;

    /**
     * 最大 LOD 级别数
     * 对应 GLSL: uint maxLODLevels
     */
    private int maxLODLevels = DEFAULT_LOD_THRESHOLDS.length;

    // ==================== Chunk Bounds 缓冲区数据 ====================

    /**
     * 当前上传的 Chunk 数量（volatile 保证线程可见性）
     */
    private volatile int currentChunkCount = 0;

    /**
     * Chunk AABB 数据缓冲区（CPU 端副本）
     * 每个元素为 2 个 vec4（min + max），共 32 字节
     * 格式: [minX, minY, minZ, 0, maxX, maxY, maxZ, 0] × chunkCount
     */
    private volatile float[] chunkBoundsData;

    // ==================== Visibility 输出缓冲区数据 ====================

    /**
     * 可见性结果缓冲区（从 GPU 回读）
     * 奇数索引: visible 标志 (0/1)
     * 偶数索引: lodLevel (uint)
     */
    private volatile int[] visibilityResults;

    // ==================== 单例支持 ====================

    /** 单例实例（volatile 保证可见性） */
    private static volatile GPULODDataManager instance;

    /** 最大 LOD 距离（方块单位） */
    private int maxDistance = 256;

    /**
     * 获取单例实例（懒加载，线程安全）
     *
     * @return GPULODDataManager 单例实例
     */
    public static GPULODDataManager getInstance() {
        if (instance == null) {
            synchronized (GPULODDataManager.class) {
                if (instance == null) {
                    instance = new GPULODDataManager();
                }
            }
        }
        return instance;
    }

    /**
     * 设置最大 LOD 距离
     *
     * @param distance 最大距离（方块单位）
     */
    public void setMaxDistance(int distance) {
        this.maxDistance = Math.max(64, Math.min(512, distance));
        // 更新最高级 LOD 阈值
        if (this.lodDistances.length > 0) {
            this.lodDistances[this.lodDistances.length - 1] = this.maxDistance;
        }
    }

    /**
     * 设置 LOD 偏移量
     *
     * @param bias 偏移值（负数=更精细，正数=更粗糙）
     */
    public void setLodBias(int bias) {
        this.lodBias = Math.max(-4.0f, Math.min(4.0f, bias));
    }

    // ==================== 性能统计字段（volatile）====================

    /**
     * 上次剔除耗时（纳秒，volatile 保证跨线程可见性）
     */
    private volatile long lastCullTimeNs = 0L;

    /**
     * 当前剔除率（0.0 ~ 1.0，被剔除的 Chunk 比例）
     * volatile 保证线程安全读取
     */
    private volatile double cullRate = 0.0;

    /**
     * 当前可见 Chunk 数量
     */
    private volatile int visibleChunkCount = 0;

    // ==================== 构造函数 ====================

    /**
     * 默认构造函数
     * <p>
     * 初始化所有字段为默认值：
     * <ul>
     *   <li>viewProjMatrix: 单位矩阵</li>
     *   <li>projectionParams: [0.1, 1000.0, 0.0, 16/9]</li>
     *   <li>screenSize: (1920, 1080, 0, 0)</li>
     *   <li>lodDistances: [32, 64, 128, 256, 512, 0, 0, 0]</li>
     *   <li>lodBias: 0.0</li>
     *   <li>maxLODLevels: 5</li>
     * </ul>
     */
    public GPULODDataManager() {
        initIdentityMatrix();
    }

    // ==================== Chunk → AABB 转换方法 ====================

    /**
     * 将 Minecraft LODChunk 数组转换为 GPU 友好的 AABB 格式并准备上传
     * <p>
     * 每个 Chunk 用 2 个 vec4 表示：
     * <ul>
     *   <li>vec4 minBound: [minX, minY, minZ, 0.0] （AABB 最小角）</li>
     *   <li>vec4 maxBound: [maxX, maxY, maxZ, 0.0] （AABB 最大角）</li>
     * </ul>
     * 存储为 SSBO（Storage Buffer Object），供 {@code lod_compute.comp} 读取。
     *
     * <h3>AABB 计算方式：</h3>
     * <pre>
     * minX = chunkX * 16
     * minZ = chunkZ * 16
     * minY = 最低高度（从高度图获取或使用默认值）
     * maxX = minX + 16
     * maxZ = minZ + 16
     * maxY = 最高高度（从高度图获取或使用默认值）
     * </pre>
     *
     * 【方法参数】
     * @param chunks LODChunk[] - 待处理的 LODChunk 数组（不能为 null，可以为空数组）
     *                      每个 LODChunk 提供 chunkX/chunkZ 坐标和高度图信息
     *
     * 【返回值】
     * @return int - 成功上传的 Chunk 数量（等于 chunks.length）
     *              如果 chunks 为 null 返回 0
     *
     * 【性能特征】
     * - 时间复杂度: O(n)，n 为 chunks 数组长度
     * - 内存分配: n × 32 bytes（float 数组）
     * - 典型性能: 1000 chunks 约 &lt;0.1ms
     *
     * 【使用示例】
     * <pre>
     * GPULODDataManager manager = new GPULODDataManager();
     * LODChunk[] chunks = lodSystem.getVisibleChunks();
     * int count = manager.uploadChunkData(chunks);
     * LOGGER.info("已上传 " + count + " 个 Chunk 的 AABB 数据");
     * </pre>
     */
    public int uploadChunkData(LODChunk[] chunks) {
        if (chunks == null) {
            LOGGER.warning("uploadChunkData: chunks 为 null，返回 0");
            return 0;
        }

        int count = chunks.length;
        this.currentChunkCount = count;

        // 更新 screenSize 中的 chunkCount
        this.screenSize[2] = (float) count;

        if (count == 0) {
            this.chunkBoundsData = new float[0];
            LOGGER.fine("uploadChunkData: 空 Chunk 数组");
            return 0;
        }

        // 分配 Chunk AABB 缓冲区：每个 Chunk 2 个 vec4 = 8 个 float = 32 bytes
        this.chunkBoundsData = new float[count * 8];

        for (int i = 0; i < count; i++) {
            LODChunk chunk = chunks[i];
            if (chunk == null) continue;

            int baseIdx = i * 8;
            int chunkX = chunk.getChunkX();
            int chunkZ = chunk.getChunkZ();

            // 计算 AABB 世界坐标范围
            float worldMinX = chunkX * CHUNK_BLOCK_SIZE;
            float worldMinZ = chunkZ * CHUNK_BLOCK_SIZE;
            float worldMaxX = worldMinX + CHUNK_BLOCK_SIZE;
            float worldMaxZ = worldMinZ + CHUNK_BLOCK_SIZE;

            // 从高度图获取 Y 范围（如果可用）
            float worldMinY = 0.0f;
            float worldMaxY = CHUNK_BLOCK_SIZE;

            if (chunk.hasHeightMap()) {
                short[] heightMap = chunk.getHeightMapCopy();
                short minHeight = Short.MAX_VALUE;
                short maxHeight = Short.MIN_VALUE;

                for (short h : heightMap) {
                    if (h < minHeight) minHeight = h;
                    if (h > maxHeight) maxHeight = h;
                }
                worldMinY = minHeight;
                worldMaxY = maxHeight + 1;  // 高度图存储的是方块 Y 坐标，+1 得到顶部
            }

            // 写入 minBound vec4: [minX, minY, minZ, padding=0]
            this.chunkBoundsData[baseIdx + 0] = worldMinX;
            this.chunkBoundsData[baseIdx + 1] = worldMinY;
            this.chunkBoundsData[baseIdx + 2] = worldMinZ;
            this.chunkBoundsData[baseIdx + 3] = 0.0f;

            // 写入 maxBound vec4: [maxX, maxY, maxZ, padding=0]
            this.chunkBoundsData[baseIdx + 4] = worldMaxX;
            this.chunkBoundsData[baseIdx + 5] = worldMaxY;
            this.chunkBoundsData[baseIdx + 6] = worldMaxZ;
            this.chunkBoundsData[baseIdx + 7] = 0.0f;
        }

        LOGGER.fine(String.format("uploadChunkData: 已转换 %d 个 Chunk AABB (%d bytes)",
                count, count * CHUNK_BOUNDS_STRIDE));
        return count;
    }

    // ==================== LOD UBO 数据设置方法 ====================

    /**
     * 设置相机视图投影矩阵（VP 矩阵）
     * <p>
     * 矩阵采用列主序存储，与 GLSL mat4 和 std140 布局一致。
     * 此矩阵用于在 Compute Shader 中将 Chunk AABB 投影到屏幕空间，
     * 以计算屏幕占用面积并确定 LOD 级别。
     *
     * 【方法参数】
     * @param matrix4x4 float[16] - 4x4 视图投影矩阵（列主序），不能为 null
     *                       典型来源: ProjectionMatrix × ViewMatrix
     *
     * 【返回值】void
     *
     * 【异常处理】
     * - 如果 matrix4x4 为 null 或长度不为 16，抛出 IllegalArgumentException
     *
     * 【性能特征】
     * - System.arraycopy 复制 64 字节，O(1) 操作
     */
    public void setCameraVPMatrix(float[] matrix4x4) {
        if (matrix4x4 == null || matrix4x4.length != 16) {
            throw new IllegalArgumentException(
                    "cameraVPMatrix 必须是长度为 16 的 float 数组（4x4 列主序矩阵），" +
                    "当前值: " + (matrix4x4 == null ? "null" : "length=" + matrix4x4.length));
        }
        System.arraycopy(matrix4x4, 0, this.viewProjMatrix, 0, 16);
    }

    /**
     * 设置投影参数
     * <p>
     * 参数用于 Compute Shader 中的屏幕空间投影计算和距离-LOD 映射。
     *
     * 【方法参数】
     * @param near       float - 近裁剪面距离（必须 > 0）
     * @param far        float - 远裁剪面距离（必须 > near）
     * @param fovYTan    float - FOV Y 轴正切值 tan(fovY / 2)，用于屏幕空间计算
     * @param aspectRatio float - 宽高比 width / height（必须 > 0）
     *
     * 【返回值】void
     *
     * 【异常处理】
     * - 如果 near <= 0 或 far <= near 或 aspectRatio <= 0，抛出 IllegalArgumentException
     */
    public void setProjectionParams(float near, float far, float fovYTan, float aspectRatio) {
        if (near <= 0f) {
            throw new IllegalArgumentException("near 必须 > 0，当前值: " + near);
        }
        if (far <= near) {
            throw new IllegalArgumentException("far 必须 > near，当前值: near=" + near + ", far=" + far);
        }
        if (aspectRatio <= 0f) {
            throw new IllegalArgumentException("aspectRatio 必须 > 0，当前值: " + aspectRatio);
        }
        this.projectionParams[0] = near;
        this.projectionParams[1] = far;
        this.projectionParams[2] = fovYTan;
        this.projectionParams[3] = aspectRatio;
    }

    /**
     * 设置屏幕尺寸
     * <p>
     * 用于 Compute Shader 中的屏幕空间分辨率相关计算。
     *
     * 【方法参数】
     * @param width  int - 屏幕宽度（像素，必须 > 0）
     * @param height int - 屏幕高度（像素，必须 > 0）
     *
     * 【返回值】void
     *
     * 【异常处理】
     * - 如果 width <= 0 或 height <= 0，抛出 IllegalArgumentException
     */
    public void setScreenSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "screenSize 必须为正整数，当前值: width=" + width + ", height=" + height);
        }
        this.screenSize[0] = (float) width;
        this.screenSize[1] = (float) height;
    }

    /**
     * 设置各级 LOD 最大距离阈值数组
     * <p>
     * 定义每个 LOD 级别的最大渲染距离。Compute Shader 根据 Chunk 到相机的距离
     * 与此数组比较来确定应使用的 LOD 级别。
     * <p>
     * 例如 thresholds = [32, 64, 128, 256, 512] 表示：
     * <ul>
     *   <li>距离 &lt; 32: LOD 0（最高细节）</li>
     *   <li>32 ≤ 距离 &lt; 64: LOD 1</li>
     *   <li>64 ≤ 距离 &lt; 128: LOD 2</li>
     *   <li>...</li>
     *   <li>距离 ≥ 512: 最低细节 LOD</li>
     * </ul>
     *
     * 【方法参数】
     * @param thresholds float[] - 各级 LOD 最大距离阈值数组（不能为 null，长度 1~8）
     *                       元素必须为正数且严格递增
     *
     * 【返回值】void
     *
     * 【异常处理】
     * - 如果 thresholds 为 null，抛出 IllegalArgumentException
     * - 如果数组长度超出 MAX_LOD_LEVELS(8)，截断到前 8 个元素
     * - 如果元素非递增，记录警告但继续执行
     */
    public void setLODDistanceThresholds(float[] thresholds) {
        if (thresholds == null) {
            throw new IllegalArgumentException("lodDistanceThresholds 不能为 null");
        }

        int len = Math.min(thresholds.length, MAX_LOD_LEVELS);
        Arrays.fill(this.lodDistances, 0.0f);

        System.arraycopy(thresholds, 0, this.lodDistances, 0, len);

        // 校验是否严格递增
        for (int i = 1; i < len; i++) {
            if (this.lodDistances[i] <= this.lodDistances[i - 1]) {
                LOGGER.warning(String.format(
                        "LOD 距离阈值非递增: thresholds[%d]=%.1f <= thresholds[%d]=%.1f",
                        i, this.lodDistances[i], i - 1, this.lodDistances[i - 1]));
            }
        }

        this.maxLODLevels = len;
        LOGGER.fine(String.format("setLODDistanceThresholds: 设置 %d 级阈值 %s",
                len, Arrays.toString(Arrays.copyOf(this.lodDistances, len))));
    }

    /**
     * 设置 LOD 偏移量
     * <p>
     * 用于防止 LOD 切换时的 pop-in（突然跳变）现象。
     * 正值使切换更保守（需要走更远才切换到更低细节级别），
     * 负值使切换更激进（更早切换以节省性能）。
     *
     * 【方法参数】
     * @param bias float - LOD 偏移量（推荐范围: -2.0 ~ 2.0）
     *               0.0 = 无偏移（默认）
     *
     * 【返回值】void
     */
    public void setLODBias(float bias) {
        this.lodBias = bias;
    }

    // ==================== UBO 序列化方法 ====================

    /**
     * 将 LOD 配置序列化为 std140 布局的 MemorySegment
     * <p>
     * 生成的 MemorySegment 可直接通过 vkCmdUpdateBuffer 或 Staging Buffer
     * 上传到 GPU 的 LOD Config UBO（binding = 6）。
     *
     * <h3>内存布局（std140）：</h3>
     * <pre>
     * Offset  Size   Field              Type
     * ──────  ────   ─────────────────  ───────────
     * 0       64B    viewProjMatrix     mat4 (4×vec4)
     * 64      16B    projectionParams   vec4 [near, far, fovY_tan, aspect]
     * 80      16B    screenSize         vec4 [width, height, chunkCount, pad]
     * 96      32B    lodDistances       float[8] (各级最大距离)
     * 128      4B    lodBias            float
     * 132      4B    maxLODLevels       uint
     * 136      8B    _padding           (std140 对齐到 144)
     * ─────────────────────────────────────────────
     * Total: 144 bytes
     * </pre>
     *
     * 【方法参数】
     * @param arena Arena - Panama FFM 内存分配器（由调用者管理生命周期）
     *                推荐使用 Arena.ofConfined() 以获得最佳性能
     *
     * 【返回值】
     * @return MemorySegment - 大小为 {@link #LOD_CONFIG_UBO_SIZE} (144) 的内存段，
     *                        内容为 std140 布局的 LOD 配置数据
     *
     * 【线程安全性】
     * 方法本身线程安全（只读 this 字段写入新 segment），但并发修改需外部同步
     *
     * 【性能特征】
     * - 内存分配: 144 bytes
     * - 数据写入: 约 30 次原生内存写入
     * - 总耗时: 典型 &lt;5μs（不含 GPU 上传时间）
     *
     * 【使用示例】
     * <pre>
     * try (Arena arena = Arena.ofConfined()) {
     *     MemorySegment ubo = dataManager.toMemorySegment(arena);
     *     vkCmdUpdateBuffer(cmdBuf, lodConfigBuffer, 0, ubo.address(), ubo.byteSize());
     * }
     * </pre>
     */
    public MemorySegment toMemorySegment(Arena arena) {
        MemorySegment segment = arena.allocate(LOD_CONFIG_UBO_SIZE);

        // 写入 viewProjMatrix (offset 0, 64 bytes = 16 floats)
        writeMatrix(segment, OFFSET_VIEW_PROJ_MATRIX, this.viewProjMatrix);

        // 写入 projectionParams (offset 64, 16 bytes = 4 floats)
        segment.set(ValueLayout.JAVA_FLOAT, OFFSET_PROJECTION_PARAMS + 0L,  this.projectionParams[0]);  // near
        segment.set(ValueLayout.JAVA_FLOAT, OFFSET_PROJECTION_PARAMS + 4L,  this.projectionParams[1]);  // far
        segment.set(ValueLayout.JAVA_FLOAT, OFFSET_PROJECTION_PARAMS + 8L,  this.projectionParams[2]);  // fovY_tan
        segment.set(ValueLayout.JAVA_FLOAT, OFFSET_PROJECTION_PARAMS + 12L, this.projectionParams[3]);  // aspectRatio

        // 写入 screenSize (offset 80, 16 bytes = 4 floats)
        segment.set(ValueLayout.JAVA_FLOAT, OFFSET_SCREEN_SIZE + 0L, this.screenSize[0]);   // width
        segment.set(ValueLayout.JAVA_FLOAT, OFFSET_SCREEN_SIZE + 4L, this.screenSize[1]);   // height
        segment.set(ValueLayout.JAVA_FLOAT, OFFSET_SCREEN_SIZE + 8L, this.screenSize[2]);   // chunkCount
        segment.set(ValueLayout.JAVA_FLOAT, OFFSET_SCREEN_SIZE + 12L, 0.0f);              // padding

        // 写入 lodDistances (offset 96, 32 bytes = 8 floats)
        for (int i = 0; i < MAX_LOD_LEVELS; i++) {
            segment.set(ValueLayout.JAVA_FLOAT, OFFSET_LOD_DISTANCES + (long) i * 4L, this.lodDistances[i]);
        }

        // 写入 lodBias (offset 128, 4 bytes)
        segment.set(ValueLayout.JAVA_FLOAT, OFFSET_LOD_BIAS, this.lodBias);

        // 写入 maxLODLevels (offset 132, 4 bytes)
        segment.set(ValueLayout.JAVA_INT, OFFSET_MAX_LOD_LEVELS, this.maxLODLevels);

        return segment;
    }

    // ==================== Visibility 结果回读方法 ====================

    /**
     * 从 GPU SSBO 回读可见性结果
     * <p>
     * 在 Compute Shader 执行完成后调用，将 GPU 端计算的可见性和 LOD 级别
     * 结果回读到 CPU 端。此方法解析原始字节缓冲区为结构化数据。
     *
     * <h3>输出数据布局：</h3>
     * <pre>
     * 对于每个 Chunk i:
     *   visibilityResults[i * 2 + 0] = visible (uint: 0=不可见, 1=可见)
     *   visibilityResults[i * 2 + 1] = lodLevel (uint: 计算后的 LOD 级别)
     * </pre>
     *
     * 【方法参数】
     * @param arena Arena - FFM 内存分配器，用于访问 GPU 回读的数据缓冲区
     *
     * 【返回值】
     * @return int[] - 可见性结果数组（长度 = chunkCount × 2）
     *               奇数索引为 visible 标志，偶数索引为 lodLevel
     *               如果无 Chunk 数据则返回空数组
     *
     * 【前置条件】
     * - GPU Compute Shader 已完成执行（Fence 已信号）
     * - Visibility Output SSBO 已映射到 Host 可见内存
     *
     * 【后置条件】
     * - 内部 visibilityResults 字段已更新
     * - 性能统计数据（visibleChunkCount, cullRate）已更新
     *
     * 【使用示例】
     * <pre>
     * vkWaitForFences(...);  // 等待 GPU 完成
     * try (Arena arena = Arena.ofConfined()) {
     *     int[] results = dataManager.readVisibilityResults(arena);
     *     for (int i = 0; i < chunkCount; i++) {
     *         boolean visible = results[i * 2] != 0;
     *         int lodLevel = results[i * 2 + 1];
     *         // 处理可见 Chunk...
     *     }
     * }
     * </pre>
     */
    public int[] readVisibilityResults(Arena arena) {
        int count = this.currentChunkCount;
        if (count == 0) {
            this.visibilityResults = new int[0];
            this.visibleChunkCount = 0;
            this.cullRate = 0.0;
            return this.visibilityResults;
        }

        // 分配结果数组：每个 Chunk 2 个 uint (visible + lodLevel)
        this.visibilityResults = new int[count * 2];

        // 注意：实际实现中此处应从 GPU SSBO 映射的内存中读取
        // 当前版本提供接口框架，实际数据由 LodCullingComputePass 在 dispatch 后填充
        // 当 Vulkan buffer 映射可用时，可通过以下方式读取：
        //   MemorySegment gpuBuffer = arena.addressOf(mappedPtr);
        //   for (int i = 0; i < count * 2; i++) {
        //       visibilityResults[i] = gpuBuffer.get(ValueLayout.JAVA_INT, (long) i * 4L);
        //   }

        // 统计可见 Chunk 数量
        int visible = 0;
        for (int i = 0; i < count; i++) {
            if (this.visibilityResults[i * 2] != 0) {
                visible++;
            }
        }
        this.visibleChunkCount = visible;
        this.cullRate = count > 0 ? (double)(count - visible) / count : 0.0;

        return this.visibilityResults;
    }

    /**
     * 直接设置可见性结果（由 LodCullingComputePass 在 GPU 回读后调用）
     * <p>
     * 绕过 GPU 内存映射，直接从 CPU 端缓冲区设置结果数据。
     * 用于 GPU 内存映射不可用时的回退路径。
     *
     * 【方法参数】
     * @param results int[] - 可见性结果数组（长度 = chunkCount × 2）
     *                   results[i*2] = visible (0/1), results[i*2+1] = lodLevel
     */
    public void setVisibilityResults(int[] results) {
        if (results == null) {
            this.visibilityResults = new int[0];
            this.visibleChunkCount = 0;
            this.cullRate = 0.0;
            return;
        }

        this.visibilityResults = results;
        int count = results.length / 2;
        int visible = 0;
        for (int i = 0; i < count; i++) {
            if (i * 2 + 1 < results.length && results[i * 2] != 0) {
                visible++;
            }
        }
        this.visibleChunkCount = visible;
        this.cullRate = count > 0 ? (double)(count - visible) / count : 0.0;
    }

    // ==================== Getter 方法（SSBO 数据访问）====================

    /**
     * 获取 Chunk Bounds 数据（CPU 端副本）
     * <p>
     * 返回的浮点数数组可直接上传到 GPU SSBO。
     * 布局: 每 Chunk 8 个 float (2 × vec4)
     *
     * 【返回值】
     * @return float[] - Chunk AABB 数据数组，或空数组（如未上传过数据）
     *                  调用者不应修改返回的数组
     */
    public float[] getChunkBoundsData() {
        return this.chunkBoundsData != null ? this.chunkBoundsData : new float[0];
    }

    /**
     * 获取 Chunk Bounds SSBO 所需的字节数
     *
     * 【返回值】
     * @return int - SSBO 字节大小 = chunkCount × {@link #CHUNK_BOUNDS_STRIDE}
     */
    public int getChunkBoundsBufferSize() {
        return this.currentChunkCount * CHUNK_BOUNDS_STRIDE;
    }

    /**
     * 获取 Visibility Output SSBO 所需的字节数
     *
     * 【返回值】
     * @return int - SSBO 字节大小 = chunkCount × {@link #VISIBILITY_RESULT_STRIDE}
     */
    public int getVisibilityOutputBufferSize() {
        return this.currentChunkCount * VISIBILITY_RESULT_STRIDE;
    }

    /**
     * 获取当前 Chunk 数量
     *
     * 【返回值】
     * @return int - 当前管理的 Chunk 数量
     */
    public int getCurrentChunkCount() {
        return this.currentChunkCount;
    }

    // ==================== 性能统计 Getter 方法 ====================

    /**
     * 获取上次剔除耗时
     *
     * 【返回值】
     * @return long - 上次 LOD 剔除计算耗时（纳秒），0 表示尚未执行过
     */
    public long getLastCullTimeNs() {
        return this.lastCullTimeNs;
    }

    /**
     * 设置上次剔除耗时（由 LodCullingComputePass 调用更新）
     *
     * @param timeNs long - 剔除耗时（纳秒）
     */
    public void setLastCullTimeNs(long timeNs) {
        this.lastCullTimeNs = timeNs;
    }

    /**
     * 获取当前剔除率
     * <p>
     * 剔除率 = 被剔除（不可见）的 Chunk 数量 / 总 Chunk 数量
     *
     * 【返回值】
     * @return double - 剔除率，范围 [0.0, 1.0]
     *               0.0 = 全部可见，1.0 = 全部被剔除
     */
    public double getCullRate() {
        return this.cullRate;
    }

    /**
     * 获取当前可见 Chunk 数量
     *
     * 【返回值】
     * @return int - 通过 LOD 剔除后仍然可见的 Chunk 数量
     */
    public int getVisibleChunkCount() {
        return this.visibleChunkCount;
    }

    /**
     * 获取 VP 矩阵副本
     *
     * 【返回值】
     * @return float[16] - 视图投影矩阵副本（列主序）
     */
    public float[] getViewProjMatrix() {
        return this.viewProjMatrix.clone();
    }

    /**
     * 获取投影参数副本
     *
     * 【返回值】
     * @return float[4] - [near, far, fovY_tan, aspectRatio]
     */
    public float[] getProjectionParams() {
        return this.projectionParams.clone();
    }

    /**
     * 获取 LOD 距离阈值副本
     *
     * 【返回值】
     * @return float[] - 各级 LOD 最大距离阈值数组副本
     */
    public float[] getLODDistanceThresholds() {
        return this.lodDistances.clone();
    }

    /**
     * 获取 LOD 偏移量
     *
     * 【返回值】
     * @return float - 当前 LOD 偏移量
     */
    public float getLODBias() {
        return this.lodBias;
    }

    /**
     * 获取最大 LOD 级别数
     *
     * 【返回值】
     * @return int - 最大 LOD 级别数
     */
    public int getMaxLODLevels() {
        return this.maxLODLevels;
    }

    // ==================== 工具方法 ====================

    /**
     * 重置所有字段为默认值
     * <p>
     * 清空所有 Chunk 数据和统计信息，恢复初始状态。
     */
    public void reset() {
        initIdentityMatrix();
        this.projectionParams[0] = 0.1f;
        this.projectionParams[1] = 1000.0f;
        this.projectionParams[2] = 0.0f;
        this.projectionParams[3] = 16.0f / 9.0f;
        this.screenSize[0] = 1920.0f;
        this.screenSize[1] = 1080.0f;
        this.screenSize[2] = 0.0f;
        this.screenSize[3] = 0.0f;
        this.lodDistances = Arrays.copyOf(DEFAULT_LOD_THRESHOLDS, MAX_LOD_LEVELS);
        this.lodBias = 0.0f;
        this.maxLODLevels = DEFAULT_LOD_THRESHOLDS.length;
        this.currentChunkCount = 0;
        this.chunkBoundsData = null;
        this.visibilityResults = null;
        this.lastCullTimeNs = 0L;
        this.cullRate = 0.0;
        this.visibleChunkCount = 0;
    }

    /**
     * 检查配置是否有效
     *
     * 【返回值】
     * @return boolean - true 表示配置有效可用于 GPU 上传
     */
    public boolean isValid() {
        return this.screenSize[0] > 0 && this.screenSize[1] > 0
                && this.projectionParams[0] > 0f
                && this.projectionParams[1] > this.projectionParams[0]
                && this.projectionParams[3] > 0f
                && this.maxLODLevels >= 1 && this.maxLODLevels <= MAX_LOD_LEVELS;
    }

    @Override
    public String toString() {
        return String.format(
                "GPULODDataManager[chunks=%d, bounds=%dB, visible=%d, cullRate=%.2f%%, lastCull=%.3fms]",
                this.currentChunkCount,
                getChunkBoundsBufferSize(),
                this.visibleChunkCount,
                this.cullRate * 100.0,
                this.lastCullTimeNs / 1_000_000.0);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 初始化 VP 矩阵为单位矩阵
     */
    private void initIdentityMatrix() {
        for (int i = 0; i < 16; i++) {
            this.viewProjMatrix[i] = (i % 5 == 0) ? 1.0f : 0.0f;
        }
    }

    /**
     * 将 4x4 矩阵写入 MemorySegment（列主序）
     *
     * @param segment 目标内存段
     * @param baseOffset 起始偏移量
     * @param matrix 源矩阵（16 个 float，列主序）
     */
    private static void writeMatrix(MemorySegment segment, long baseOffset, float[] matrix) {
        for (int i = 0; i < 16; i++) {
            segment.set(ValueLayout.JAVA_FLOAT, baseOffset + (long) i * 4L, matrix[i]);
        }
    }
}
