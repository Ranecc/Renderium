// Renderium - 遮挡剔除配置 UBO 数据结构
// 对应 GLSL shader 中的 CameraAndHiZData uniform block
// std140 布局，通过 MemorySegment 直接映射到 GPU

package com.renderium.gpu.hiz;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.logging.Logger;

/**
 * 遮挡剔除配置 UBO 数据结构（std140 布局）
 * <p>
 * 对应 {@code hiz_occlusion_culling.comp} 中的 CameraAndHiZData uniform block。
 * 使用 std140 布局规则，确保 CPU 端与 GPU 端的内存布局完全一致。
 *
 * <h3>GLSL 定义：</h3>
 * <pre>
 * layout (std140, binding = 5) uniform CameraAndHiZData {
 *     mat4 viewProjMatrix;      // offset 0,   64 bytes (4×vec4)
 *     mat4 viewMatrixInverse;  // offset 64,  64 bytes (4×vec4)
 *     vec4 projectionParams;    // offset 128, 16 bytes [near, far, fovY_tan, aspectRatio]
 *     uvec2 screenSize;         // offset 144,  8 bytes [width, height]
 *     uvec2 hiZSize;            // offset 156,  8 bytes [Level 0 分辨率]
 *     uint enableHiZCull;       // offset 164,  4 bytes
 *     uint totalChunkCount;      // offset 168,  4 bytes
 *     uint maxHiZLOD;           // offset 172,  4 bytes
 *     uint _padding;             // offset 176,  4 bytes
 * };
 * // 总大小: 180 bytes, std140 对齐到 192 (vec4 边界)
 * </pre>
 *
 * <h3>使用示例：</h3>
 * <pre>
 * OcclusionCullConfig config = new OcclusionCullConfig();
 * config.setViewProjMatrix(viewProjection);
 * config.setScreenSize(1920, 1080);
 * config.setEnableHiZCull(1);
 *
 * try (Arena arena = Arena.ofConfined()) {
 *     MemorySegment segment = config.toMemorySegment(arena);
 *     // 将 segment 上传到 GPU UBO...
 * }
 * </pre>
 *
 * @see HiZBufferManager
 * @see com.renderium.framegraph.pass.LodCullingComputePass
 * @since 5.2.0
 */
public final class OcclusionCullConfig {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(OcclusionCullConfig.class.getName());

    // ==================== 常量定义 ====================

    /** std140 布局下 UBO 总大小（字节）- 对齐到 vec4 边界 */
    public static final int UBO_SIZE_BYTES = 192;

    /** Hi-Z 最大 Mipmap 层数 */
    public static final int HIZ_MAX_MIP_LEVELS = 10;

    /** 默认屏幕宽度 */
    public static final int DEFAULT_SCREEN_WIDTH = 1920;

    /** 默认屏幕高度 */
    public static final int DEFAULT_SCREEN_HEIGHT = 1080;

    // ==================== 字段偏移量（std140 布局）====================

    /** viewProjMatrix 偏移量：0 字节 */
    public static final long OFFSET_VIEW_PROJ_MATRIX = 0L;

    /** viewMatrixInverse 偏移量：64 字节 */
    public static final long OFFSET_VIEW_MATRIX_INVERSE = 64L;

    /** projectionParams 偏移量：128 字节 */
    public static final long OFFSET_PROJECTION_PARAMS = 128L;

    /** screenSize 偏移量：144 字节 */
    public static final long OFFSET_SCREEN_SIZE = 144L;

    /** hiZSize 偏移量：156 字节 */
    public static final long OFFSET_HIZ_SIZE = 156L;

    /** enableHiZCull 偏移量：164 字节 */
    public static final long OFFSET_ENABLE_HIZ_CULL = 164L;

    /** totalChunkCount 偏移量：168 字节 */
    public static final long OFFSET_TOTAL_CHUNK_COUNT = 168L;

    /** maxHiZLOD 偏移量：172 字节 */
    public static final long OFFSET_MAX_HIZ_LOD = 172L;

    /** padding 偏移量：176 字节 */
    public static final long OFFSET_PADDING = 176L;

    // ==================== 数据字段 ====================

    /**
     * 视图投影矩阵（列主序，16 个 float）
     * 对应 GLSL: mat4 viewProjMatrix
     */
    private float[] viewProjMatrix = new float[16];

    /**
     * 视图逆矩阵（列主序，16 个 float）
     * 对应 GLSL: mat4 viewMatrixInverse
     */
    private float[] viewMatrixInverse = new float[16];

    /**
     * 投影参数向量 [near, far, fovY_tan, aspectRatio]
     * 对应 GLSL: vec4 projectionParams
     */
    private float[] projectionParams = new float[]{0.1f, 1000.0f, 0.0f, 16.0f / 9.0f};

    /**
     * 屏幕分辨率 [width, height]
     * 对应 GLSL: uvec2 screenSize
     * 默认值: (1920, 1080)
     */
    private int[] screenSize = new int[]{DEFAULT_SCREEN_WIDTH, DEFAULT_SCREEN_HEIGHT};

    /**
     * Hi-Z Level 0 分辨率 [width, height]
     * 对应 GLSL: uvec2 hiZSize
     * 通常为 screenSize 向上取整到 2 的幂次
     */
    private int[] hiZSize = new int[]{DEFAULT_SCREEN_WIDTH, DEFAULT_SCREEN_HEIGHT};

    /**
     * 是否启用 Hi-Z 遮挡剔除（0=禁用，1=启用）
     * 对应 GLSL: uint enableHiZCull
     * 默认值: 1（启用）
     */
    private int enableHiZCull = 1;

    /**
     * 待处理的总 Chunk 数量
     * 对应 GLSL: uint totalChunkCount
     * 用于 Compute Shader 调度
     */
    private int totalChunkCount = 0;

    /**
     * 最大 Hi-Z LOD 层级
     * 对应 GLSL: uint maxHiZLOD
     * 默认值: HIZ_MAX_MIP_LEVELS (10)
     */
    private int maxHiZLOD = HIZ_MAX_MIP_LEVELS;

    // ==================== 构造函数 ====================

    /**
     * 默认构造函数
     * <p>
     * 初始化所有字段为默认值：
     * <ul>
     *   <li>viewProjMatrix / viewMatrixInverse: 单位矩阵</li>
     *   <li>projectionParams: [0.1, 1000.0, 0.0, 16/9]</li>
     *   <li>screenSize: (1920, 1080)</li>
     *   <li>hiZSize: (1920, 1080)</li>
     *   <li>enableHiZCull: 1</li>
     *   <li>totalChunkCount: 0</li>
     *   <li>maxHiZLOD: 10</li>
     * </ul>
     */
    public OcclusionCullConfig() {
        initIdentityMatrices();
    }

    // ==================== Setter 方法 ====================

    /**
     * 设置视图投影矩阵
     * <p>
     * 矩阵采用列主序存储，与 GLSL mat4 布局一致。
     *
     * 【方法参数】
     * @param matrix float[16] - 4x4 视图投影矩阵（列主序），不能为 null
     *
     * 【返回值】void
     *
     * 【异常处理】
     * - 如果 matrix 为 null 或长度不为 16，抛出 IllegalArgumentException
     *
     * 【性能特征】
     * - System.arraycopy 复制 64 字节，O(1) 操作
     */
    public void setViewProjMatrix(float[] matrix) {
        if (matrix == null || matrix.length != 16) {
            throw new IllegalArgumentException("viewProjMatrix 必须是长度为 16 的 float 数组");
        }
        System.arraycopy(matrix, 0, this.viewProjMatrix, 0, 16);
    }

    /**
     * 设置视图逆矩阵
     * <p>
     * 矩阵采用列主序存储，用于在 Shader 中将 NDC 坐标转换回世界坐标。
     *
     * 【方法参数】
     * @param matrix float[16] - 4x4 视图逆矩阵（列主序），不能为 null
     *
     * 【返回值】void
     *
     * 【异常处理】
     * - 如果 matrix 为 null 或长度不为 16，抛出 IllegalArgumentException
     */
    public void setViewMatrixInverse(float[] matrix) {
        if (matrix == null || matrix.length != 16) {
            throw new IllegalArgumentException("viewMatrixInverse 必须是长度为 16 的 float 数组");
        }
        System.arraycopy(matrix, 0, this.viewMatrixInverse, 0, 16);
    }

    /**
     * 设置投影参数
     * <p>
     * 参数顺序: [near, far, fovY_tan, aspectRatio]
     *
     * 【方法参数】
     * @param near       float - 近裁剪面距离（必须 > 0）
     * @param far        float - 远裁剪面距离（必须 > near）
     * @param fovYTan    float - FOV Y 轴正切值（tan(fov/2)）
     * @param aspectRatio float - 宽高比（width / height，必须 > 0）
     *
     * 【返回值】void
     *
     * 【异常处理】
     * - 如果 near <= 0 或 far <= near 或 aspectRatio <= 0，抛出 IllegalArgumentException
     *
     * 【性能特征】
     * - O(1) 操作，直接数组赋值
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
     * 设置屏幕分辨率
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
        this.screenSize[0] = width;
        this.screenSize[1] = height;
    }

    /**
     * 设置 Hi-Z Level 0 分辨率
     * <p>
     * 通常为屏幕分辨率向上取整到 2 的幂次。
     * 例如: 1920 → 2048, 1080 → 2048
     *
     * 【方法参数】
     * @param width  int - Hi-Z 宽度（像素，必须 > 0）
     * @param height int - Hi-Z 高度（像素，必须 > 0）
     *
     * 【返回值】void
     *
     * 【异常处理】
     * - 如果 width <= 0 或 height <= 0，抛出 IllegalArgumentException
     */
    public void setHiZSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                "hiZSize 必须为正整数，当前值: width=" + width + ", height=" + height);
        }
        this.hiZSize[0] = width;
        this.hiZSize[1] = height;
    }

    /**
     * 设置是否启用 Hi-Z 遮挡剔除
     *
     * 【方法参数】
     * @param enable int - 启用标志（0=禁用，1=启用）
     *
     * 【返回值】void
     */
    public void setEnableHiZCull(int enable) {
        this.enableHiZCull = enable;
    }

    /**
     * 设置待处理的 Chunk 总数
     * <p>
     * 用于 Compute Shader 计算工作组数量。
     *
     * 【方法参数】
     * @param count int - Chunk 总数（必须 >= 0）
     *
     * 【返回值】void
     *
     * 【异常处理】
     * - 如果 count < 0，抛出 IllegalArgumentException
     */
    public void setTotalChunkCount(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("totalChunkCount 必须 >= 0，当前值: " + count);
        }
        this.totalChunkCount = count;
    }

    /**
     * 设置最大 Hi-Z LOD 层级
     *
     * 【方法参数】
     * @param maxLOD int - 最大 LOD 层级（1 ~ HIZ_MAX_MIP_LEVELS）
     *
     * 【返回值】void
     *
     * 【异常处理】
     * - 如果 maxLOD < 1 或 maxLOD > HIZ_MAX_MIP_LEVELS，抛出 IllegalArgumentException
     */
    public void setMaxHiZLOD(int maxLOD) {
        if (maxLOD < 1 || maxLOD > HIZ_MAX_MIP_LEVELS) {
            throw new IllegalArgumentException(
                "maxHiZLOD 必须在 1 ~ " + HIZ_MAX_MIP_LEVELS + " 范围内，当前值: " + maxLOD);
        }
        this.maxHiZLOD = maxLOD;
    }

    // ==================== Getter 方法 ====================

    /**
     * 获取视图投影矩阵副本
     *
     * 【返回值】
     * @return float[16] - 视图投影矩阵（列主序），调用者可安全修改
     */
    public float[] getViewProjMatrix() {
        float[] copy = new float[16];
        System.arraycopy(this.viewProjMatrix, 0, copy, 0, 16);
        return copy;
    }

    /**
     * 获取视图逆矩阵副本
     *
     * 【返回值】
     * @return float[16] - 视图逆矩阵（列主序），调用者可安全修改
     */
    public float[] getViewMatrixInverse() {
        float[] copy = new float[16];
        System.arraycopy(this.viewMatrixInverse, 0, copy, 0, 16);
        return copy;
    }

    /**
     * 获取投影参数副本
     *
     * 【返回值】
     * @return float[4] - 投影参数 [near, far, fovY_tan, aspectRatio]
     */
    public float[] getProjectionParams() {
        return this.projectionParams.clone();
    }

    /**
     * 获取屏幕宽度
     *
     * 【返回值】
     * @return int - 屏幕宽度（像素）
     */
    public int getScreenWidth() { return this.screenSize[0]; }

    /**
     * 获取屏幕高度
     *
     * 【返回值】
     * @return int - 屏幕高度（像素）
     */
    public int getScreenHeight() { return this.screenSize[1]; }

    /**
     * 获取 Hi-Z Level 0 宽度
     *
     * 【返回值】
     * @return int - Hi-Z 宽度（像素）
     */
    public int getHiZWidth() { return this.hiZSize[0]; }

    /**
     * 获取 Hi-Z Level 0 高度
     *
     * 【返回值】
     * @return int - Hi-Z 高度（像素）
     */
    public int getHiZHeight() { return this.hiZSize[1]; }

    /**
     * 获取是否启用 Hi-Z 遮挡剔除
     *
     * 【返回值】
     * @return int - 启用标志（0=禁用，1=启用）
     */
    public int getEnableHiZCull() { return this.enableHiZCull; }

    /**
     * 获取待处理的 Chunk 总数
     *
     * 【返回值】
     * @return int - Chunk 总数
     */
    public int getTotalChunkCount() { return this.totalChunkCount; }

    /**
     * 获取最大 Hi-Z LOD 层级
     *
     * 【返回值】
     * @return int - 最大 LOD 层级
     */
    public int getMaxHiZLOD() { return this.maxHiZLOD; }

    // ==================== 核心序列化方法 ====================

    /**
     * 将配置序列化为 std140 布局的 MemorySegment
     * <p>
     * 生成的 MemorySegment 可直接上传到 GPU UBO，
     * 无需额外的内存拷贝或格式转换。
     *
     * <h3>内存布局（std140）：</h3>
     * <pre>
     * Offset  Size   Field              Type
     * ──────  ────   ─────────────────  ───────────
     * 0       64B    viewProjMatrix     mat4 (4×vec4)
     * 64      64B    viewMatrixInverse  mat4 (4×vec4)
     * 128     16B    projectionParams   vec4
     * 144      8B    screenSize         uvec2 (+8B padding)
     * 156      8B    hiZSize            uvec2 (+8B padding)
     * 164      4B    enableHiZCull      uint
     * 168      4B    totalChunkCount    uint
     * 172      4B    maxHiZLOD          uint
     * 176      4B    _padding           uint
     * ─────────────────────────────────────────────
     * Total: 192 bytes (对齐到 vec4 边界)
     * </pre>
     *
     * 【方法参数】
     * @param arena Arena - Panama FFM 内存分配器（由调用者管理生命周期）
     *                推荐使用 Arena.ofConfined() 以获得最佳性能
     *
     * 【返回值】
     * @return MemorySegment - 大小为 {@link #UBO_SIZE_BYTES} (192) 的内存段，
     *                        内容为 std140 布局的 UBO 数据。
     *                        内存所有权属于 arena，调用者需确保 arena 在 GPU 使用期间保持有效
     *
     * 【线程安全性】
     * 此方法本身是线程安全的（只读取 this 字段并写入新分配的 segment），
     * 但如果多线程并发修改此对象的状态，需外部同步
     *
     * 【性能特征】
     * - 内存分配: 192 bytes（Arena 分配，通常 &lt;1μs）
     * - 数据写入: 约 50 次原生内存写入操作
     * - 总耗时: 典型 &lt;5μs（不包含 GPU 上传时间）
     *
     * 【使用示例】
     * <pre>
     * try (Arena arena = Arena.ofConfined()) {
     *     MemorySegment uboSegment = config.toMemorySegment(arena);
     *     // 通过 vkCmdUpdateBuffer 或 Staging Buffer 上传到 GPU
     *     vkCmdUpdateBuffer(cmdBuffer, uboBuffer, 0, uboSegment);
     * } // arena 自动释放
     * </pre>
     */
    public MemorySegment toMemorySegment(Arena arena) {
        MemorySegment segment = arena.allocate(UBO_SIZE_BYTES);

        // 写入 viewProjMatrix (offset 0, 64 bytes)
        writeMatrix(segment, OFFSET_VIEW_PROJ_MATRIX, this.viewProjMatrix);

        // 写入 viewMatrixInverse (offset 64, 64 bytes)
        writeMatrix(segment, OFFSET_VIEW_MATRIX_INVERSE, this.viewMatrixInverse);

        // 写入 projectionParams (offset 128, 16 bytes = 4 floats)
        segment.set(ValueLayout.JAVA_FLOAT, OFFSET_PROJECTION_PARAMS + 0L, this.projectionParams[0]);  // near
        segment.set(ValueLayout.JAVA_FLOAT, OFFSET_PROJECTION_PARAMS + 4L, this.projectionParams[1]);  // far
        segment.set(ValueLayout.JAVA_FLOAT, OFFSET_PROJECTION_PARAMS + 8L, this.projectionParams[2]);  // fovY_tan
        segment.set(ValueLayout.JAVA_FLOAT, OFFSET_PROJECTION_PARAMS + 12L, this.projectionParams[3]); // aspectRatio

        // 写入 screenSize (offset 144, uvec2: 8 bytes)
        segment.set(ValueLayout.JAVA_INT, OFFSET_SCREEN_SIZE + 0L, this.screenSize[0]);  // width
        segment.set(ValueLayout.JAVA_INT, OFFSET_SCREEN_SIZE + 4L, this.screenSize[1]);  // height

        // 写入 hiZSize (offset 156, uvec2: 8 bytes)
        segment.set(ValueLayout.JAVA_INT, OFFSET_HIZ_SIZE + 0L, this.hiZSize[0]);  // width
        segment.set(ValueLayout.JAVA_INT, OFFSET_HIZ_SIZE + 4L, this.hiZSize[1]);  // height

        // 写入标量字段 (offset 164~176)
        segment.set(ValueLayout.JAVA_INT, OFFSET_ENABLE_HIZ_CULL, this.enableHiZCull);   // 164
        segment.set(ValueLayout.JAVA_INT, OFFSET_TOTAL_CHUNK_COUNT, this.totalChunkCount); // 168
        segment.set(ValueLayout.JAVA_INT, OFFSET_MAX_HIZ_LOD, this.maxHiZLOD);          // 172
        segment.set(ValueLayout.JAVA_INT, OFFSET_PADDING, 0);                            // 176: 显式填充

        return segment;
    }

    // ==================== 工具方法 ====================

    /**
     * 重置所有字段为默认值
     * <p>
     * 重置后状态：
     * <ul>
     *   <li>viewProjMatrix / viewMatrixInverse: 单位矩阵</li>
     *   <li>projectionParams: [0.1, 1000.0, 0.0, 16/9]</li>
     *   <li>screenSize: (1920, 1080)</li>
     *   <li>hiZSize: (1920, 1080)</li>
     *   <li>enableHiZCull: 1</li>
     *   <li>totalChunkCount: 0</li>
     *   <li>maxHiZLOD: 10</li>
     * </ul>
     *
     * 【返回值】void
     *
     * 【性能特征】
     * - O(1) 操作，重置约 180 字节数据
     */
    public void reset() {
        initIdentityMatrices();
        this.projectionParams[0] = 0.1f;
        this.projectionParams[1] = 1000.0f;
        this.projectionParams[2] = 0.0f;
        this.projectionParams[3] = 16.0f / 9.0f;
        this.screenSize[0] = DEFAULT_SCREEN_WIDTH;
        this.screenSize[1] = DEFAULT_SCREEN_HEIGHT;
        this.hiZSize[0] = DEFAULT_SCREEN_WIDTH;
        this.hiZSize[1] = DEFAULT_SCREEN_HEIGHT;
        this.enableHiZCull = 1;
        this.totalChunkCount = 0;
        this.maxHiZLOD = HIZ_MAX_MIP_LEVELS;
    }

    /**
     * 检查配置是否有效
     * <p>
     * 验证所有关键字段的合法性。
     *
     * 【返回值】
     * @return boolean - true 表示配置有效且可用于 GPU 上传
     */
    public boolean isValid() {
        return this.screenSize[0] > 0 && this.screenSize[1] > 0
            && this.hiZSize[0] > 0 && this.hiZSize[1] > 0
            && this.projectionParams[0] > 0f
            && this.projectionParams[1] > this.projectionParams[0]
            && this.projectionParams[3] > 0f
            && this.totalChunkCount >= 0
            && this.maxHiZLOD >= 1 && this.maxHiZLOD <= HIZ_MAX_MIP_LEVELS;
    }

    @Override
    public String toString() {
        return String.format(
            "OcclusionCullConfig[screen=%dx%d, hiZ=%dx%d, enable=%d, chunks=%d, maxLOD=%d]",
            this.screenSize[0], this.screenSize[1],
            this.hiZSize[0], this.hiZSize[1],
            this.enableHiZCull,
            this.totalChunkCount,
            this.maxHiZLOD
        );
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 初始化矩阵为单位矩阵
     */
    private void initIdentityMatrices() {
        for (int i = 0; i < 16; i++) {
            this.viewProjMatrix[i] = (i % 5 == 0) ? 1.0f : 0.0f;
            this.viewMatrixInverse[i] = (i % 5 == 0) ? 1.0f : 0.0f;
        }
    }

    /**
     * 将 4x4 矩阵写入 MemorySegment（列主序）
     * <p>
     * std140 下 mat4 由 4 个 vec4 组成，每个 vec4 占 16 字节。
     * 列主序存储时，第 i 列的第 j 行元素位于 offset = (i*4 + j) * 4
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
