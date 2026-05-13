package com.ranecc.renderium.platform.hook;

import com.ranecc.renderium.domain.model.CameraContext;
import org.joml.Matrix4fc;

/**
 * 统一帧上下文 — 热路径数据传递容器
 *
 * <p><b>设计原则</b>:
 * <ul>
 *   <li><b>ThreadLocal 分配</b>：每线程一份，零竞争，ThreadLocal.get() ~5-10ns</li>
 *   <li><b>缓存友好的字段布局</b>：高频字段在前 64 字节（第一缓存行）</li>
 *   <li><b>基本类型优先</b>：避免装箱拆箱，减少缓存压力</li>
 *   <li><b>帧间复用</b>：beginFrame()/endFrame() 配对使用，reset() 而非 new</li>
 * </ul>
 *
 * <p><b>与 FrameDataSnapshot 的关系</b>:
 * {@code FrameDataSnapshot} 是 domain 层的完整帧数据模型（含矩阵数组、历史视图等），
 * 而 {@code FrameContext} 是 platform 层的轻量级热路径视图。
 * FrameContext 可以持有 FrameDataSnapshot 的引用或直接包含原始类型字段，
 * 优先使用原始类型以避免间接寻址开销。
 *
 * <h3>内存布局（按访问频率排序，目标：热点数据落入第一缓存行）</h3>
 * <pre>
 * [缓存行 0: 高频热路径数据 ~32B]
 *   deltaTime(4) | frameIndex(4) | cameraX/Y/Z(12) | yaw/pitch(8) | fov(4)
 *
 * [缓存行 1: 中频矩阵/视锥数据]
 *   projectionMatrix ref(4-8) | viewMatrix ref(4-8) | nearPlane(4) | farPlane(4)
 *
 * [缓存行 2: 可见性/窗口数据]
 *   visibleSectionCount(4) | totalSectionCount(4) | drawCallCount(4)
 *   windowWidth(4) | windowHeight(4)
 *
 * [缓存行 3: 低频雾效/扩展数据]
 *   fogStart(4) | fogEnd(4) | fogR/G/B(12)
 *   renderDistance(4) | gameTick(8)
 * </pre>
 *
 * <h3>生命周期</h3>
 * <pre>
 *  get() → beginFrame(deltaTime) → [热路径读写] → endFrame() → [可选 reset()]
 * </pre>
 *
 * @see HookDispatcher — 热路径调度核心，接收 FrameContext 作为参数
 * @see com.ranecc.renderium.domain.model.FrameDataSnapshot — domain 层完整帧数据模型
 * @see CameraContext — domain 层相机上下文
 * @since 2.0.0
 */
public final class FrameContext {

    // ==================== ThreadLocal 实例 ====================

    /**
     * 当前线程的帧上下文实例（ThreadLocal 存储）
     *
     * <p>每个渲染线程持有独立的 FrameContext 实例，
     * 避免多线程竞争和伪共享（false sharing）。
     * 通过 withInitial 延迟创建，首次调用 get() 时初始化。
     */
    private static final ThreadLocal<FrameContext> INSTANCE =
            ThreadLocal.withInitial(FrameContext::new);

    // ==================== 高频字段（缓存行 0, 热路径每帧必读）====================

    /**
     * 帧间隔时间（秒）
     *
     * <p>例如 60 FPS 时约为 0.0167，144 FPS 时约为 0.0069。
     * 用于时间相关的插值、物理模拟和动画步进。
     * 由 GameRenderer.render(partialTick) 在 beginFrame 时设置。
     */
    public float deltaTime;

    /**
     * 当前帧序号（单调递增，从 0 开始）
     *
     * <p>用途：
     * <ul>
     *   <li>帧数据关联（调试时追踪特定帧的问题）</li>
     *   <li>周期性操作（如每 N 帧执行一次统计输出）</li>
     *   <li>双缓冲检测（通过 isOddFrame() 判断奇偶帧）</li>
     * </ul>
     */
    public int frameIndex;

    /** 相机 X 坐标（世界空间） */
    public float cameraX;

    /** 相机 Y 坐标（世界空间，通常为玩家眼睛高度） */
    public float cameraY;

    /** 相机 Z 坐标（世界空间） */
    public float cameraZ;

    /** 相机偏航角（弧度，水平旋转，范围 -π ~ π） */
    public float yaw;

    /** 相机俯仰角（弧度，垂直旋转，范围 -π/2 ~ π/2） */
    public float pitch;

    /** 视场角（度数，例如 70.0（默认）、90.0（宽视角）） */
    public float fov;

    // ==================== 中频字段（缓存行 1-2, 矩阵和可见性）====================

    /**
     * 投影矩阵引用（JOML Matrix4fc，4x4 列主序）
     *
     * <p>存储为引用而非拷贝（16 个 float = 64B），避免每次访问的数据拷贝开销。
     * 热路径中只读，不应修改。
     * 可为 null（表示尚未设置或无需投影变换）。
     */
    public Matrix4fc projectionMatrix;

    /**
     * 视图矩阵引用（JOML Matrix4fc，4x4 列主序）
     *
     * <p>组合了相机位置和旋转的 4x4 变换矩阵。
     * 用于将世界坐标转换到视图（相机）空间。
     */
    public Matrix4fc viewMatrix;

    /** 近裁剪面距离（世界单位，通常 0.05） */
    public float nearPlane;

    /** 远裁剪面距离（世界单位，通常与渲染距离关联） */
    public float farPlane;

    /**
     * 当前帧可见的区块数量
     *
     * <p>经过遮挡剔除、视锥剔除后剩余的可渲染区块数。
     * 用于评估剔除效果和 LOD 分配策略。
     */
    public int visibleSectionCount;

    /** 总区块数量（加载范围内的区块总数） */
    public int totalSectionCount;

    /**
     * 当前帧的 Draw Call 数量
     *
     * <p>包括所有类型的绘制调用（terrain, entities, GUI, transparency 等）。
     * 是性能优化的核心指标。由 dispatchDrawIndexed 时 incrementDrawCall() 递增。
     */
    public int drawCallCount;

    /** 窗口宽度（像素） */
    public int windowWidth;

    /** 窗口高度（像素） */
    public int windowHeight;

    // ==================== 低频字段（缓存行 3, 雾效和扩展）====================

    /** 雾效起始距离（世界单位） */
    public float fogStart;

    /** 零效结束距离（世界单位） */
    public float fogEnd;

    /** 雾效颜色 R 分量（0.0 ~ 1.0） */
    public float fogR;

    /** 雾效颜色 G 分量（0.0 ~ 1.0） */
    public float fogG;

    /** 雾效颜色 B 分量（0.0 ~ 1.0） */
    public float fogB;

    /** 渲染距离（世界单位，例如 16 chunks = 256 blocks） */
    public float renderDistance;

    /**
     * 当前游戏刻度（game tick）
     *
     * <p>Minecraft 的内部时间单位，20 ticks = 1 秒。
     * 用于与游戏逻辑同步（如昼夜循环、随机数种子等）。
     */
    public long gameTick;

    // ==================== 内部状态 ====================

    /** 是否已初始化（beginFrame 已调用且 endFrame 尚未调用） */
    private boolean initialized;

    /** 帧开始时间（纳秒），用于计算本帧总耗时 */
    private long frameStartTimeNs;

    // ==================== 构造函数 ====================

    /**
     * 私有构造函数 — 仅通过 {@link #get()} 工厂方法获取实例
     *
     * <p>构造时自动调用 reset() 设置合理的默认值。
     */
    private FrameContext() {
        this.reset();
    }

    // ==================== 工厂方法 ====================

    /**
     * 获取当前线程的帧上下文
     *
     * <p><b>热路径方法</b>：ThreadLocal.get() 开销约 5-10ns。
     * 返回值保证非 null（首次访问时自动创建）。
     *
     * @return FrameContext 当前线程的帧上下文实例（永远非 null）
     */
    public static FrameContext get() {
        return INSTANCE.get();
    }

    // ==================== 生命周期方法 ====================

    /**
     * 开始新帧 — 必须在渲染循环入口调用
     *
     * <p>在 GameRenderer.render() 的 HEAD 注入点处调用。
     * 记录帧间隔时间和起始时间戳，递增帧索引。
     * 必须在每帧开始时且仅调用一次。
     *
     * @param deltaTime 帧间隔时间（秒），来自 MC 的 partialTick 或实际 delta time
     * @throws IllegalStateException 如果上一帧未正确结束（beginFrame/endFrame 不匹配）
     */
    public void beginFrame(float deltaTime) {
        if (initialized) {
            throw new IllegalStateException(
                    "beginFrame called without previous endFrame. Current frame index: " + frameIndex"
                            + ". Ensure endFrame() is called before the next beginFrame()."
            );
        }

        this.deltaTime = deltaTime;
        this.frameIndex++;
        this.frameStartTimeNs = System.nanoTime();
        this.drawCallCount = 0;
        this.initialized = true;
    }

    /**
     * 结束当前帧 — 必须在渲染循环出口调用
     *
     * <p>在 GameRenderer.render() 的 RETURN 注入点处调用。
     * 标记帧结束，返回本帧总耗时用于性能统计。
     *
     * @return long 本帧总耗时（纳秒），可用于 PerformanceProfiler 记录
     */
    public long endFrame() {
        if (!initialized) {
            throw new IllegalStateException(
                    "endFrame called without beginFrame. Call beginFrame(float) first."
            );
        }

        long elapsed = System.nanoTime() - frameStartTimeNs;
        this.initialized = false;
        return elapsed;
    }

    /**
     * 重置所有字段为默认值
     *
     * <p>用于帧间复用，避免创建新对象产生 GC 压力。
     * 通常在 endFrame 后隐式完成（下一帧 beginFrame 会覆盖关键字段），
     *或在异常恢复、场景切换时手动调用。
     */
    public void reset() {
        // 第一组：高频数据
        this.deltaTime = 0.0f;
        this.frameIndex = -1;  // -1 表示尚未开始第一帧
        this.cameraX = 0.0f;
        this.cameraY = 0.0f;
        this.cameraZ = 0.0f;
        this.yaw = 0.0f;
        this.pitch = 0.0f;
        this.fov = 70.0f;  // MC 默认 FOV

        // 第二组：矩阵和可见性
        this.projectionMatrix = null;
        this.viewMatrix = null;
        this.nearPlane = 0.05f;  // 标准近平面
        this.farPlane = 1000.0f; // 标准远平面
        this.visibleSectionCount = 0;
        this.totalSectionCount = 0;
        this.drawCallCount = 0;
        this.windowWidth = 1920;   // 默认分辨率
        this.windowHeight = 1080;

        // 第三组：低频数据
        this.fogStart = 0.0f;
        this.fogEnd = 0.0f;
        this.fogR = 0.0f;
        this.fogG = 0.0f;
        this.fogB = 0.0f;
        this.renderDistance = 256.0f;  // 默认 16 chunks
        this.gameTick = 0;

        // 内部状态
        this.initialized = false;
        this.frameStartTimeNs = 0;
    }

    // ==================== 数据填充方法（冷路径 / Bridge 层调用）====================

    /**
     * 从 domain 层 CameraContext 同步相机数据
     *
     * <p>将 {@link CameraContext} 中的位置和旋转变拷贝到本上下文中。
     * 通常在 MCRenderBridge 或 Mixin 注入点中调用（每帧一次）。
     *
     * <p><b>注意</b>: 此操作涉及 5 次 float 赋值（~5ns 总开销），
     * 属于可接受的冷路径开销。
     *
     * @param camera domain 层相机上下文（不应为 null）
     * @throws IllegalArgumentException 如果 camera 为 null
     */
    public void syncFromCamera(CameraContext camera) {
        if (camera == null) {
            throw new IllegalArgumentException("CameraContext must not be null");
        }
        this.cameraX = camera.x;
        this.cameraY = camera.y;
        this.cameraZ = camera.z;
        this.yaw = camera.yaw;
        this.pitch = camera.pitch;
    }

    /**
     * 设置相机参数（直接赋值方式，避免依赖 CameraContext 对象）
     *
     * <p>当无法获取 CameraContext 实例时（如直接从 MC 内部对象读取），
     * 可使用此方法逐个设置相机参数。
     *
     * @param x 相机 X 坐标
     * @param y 相机 Y 坐标
     * @param z 相机 Z 坐标
     * @param yaw 偏航角（弧度）
     * @param pitch 俯仰角（弧度）
     */
    public void setCamera(float x, float y, float z, float yaw, float pitch) {
        this.cameraX = x;
        this.cameraY = y;
        this.cameraZ = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /**
     * 设置投影矩阵和相关参数
     *
     * @param projection 投影矩阵（JOML Matrix4fc 引用，不为 null）
     * @param fovDeg     视场角（度数）
     * @param near       近裁剪面距离
     * @param far        远裁剪面距离
     */
    public void setProjection(Matrix4fc projection, float fovDeg, float near, float far) {
        this.projectionMatrix = projection;
        this.fov = fovDeg;
        this.nearPlane = near;
        this.farPlane = far;
    }

    /**
     * 设置视图矩阵
     *
     * @param view 视图矩阵（JOML Matrix4fc 引用）
     */
    public void setViewMatrix(Matrix4fc view) {
        this.viewMatrix = view;
    }

    /**
     * 设置可见性统计数据
     *
     * @param visible 可见区块数量
     * @param total   总区块数量
     */
    public void setVisibility(int visible, int total) {
        this.visibleSectionCount = visible;
        this.totalSectionCount = total;
    }

    /**
     * 设置窗口尺寸
     *
     * @param width  窗口宽度（像素）
     * @param height 窗口高度（像素）
     */
    public void setWindowSize(int width, int height) {
        this.windowWidth = width;
        this.windowHeight = height;
    }

    /**
     * 设置雾效参数
     *
     * @param start 起始距离
     * @param end   结束距离
     * @param r     R 分量 (0.0~1.0)
     * @param g     G 分量 (0.0~1.0)
     * @param b     B 分量 (0.0~1.0)
     */
    public void setFog(float start, float end, float r, float g, float b) {
        this.fogStart = start;
        this.fogEnd = end;
        this.fogR = r;
        this.fogG = g;
        this.fogB = b;
    }

    // ==================== 热路径查询方法 ====================

    /**
     * 计算到相机的欧几里得距离平方（避免 sqrt 开销）
     *
     * <p><b>热路径方法</b>：用于 LOD 计算、遮挡剔除等场景。
     * 返回距离平方，比较时无需开方（保持大小关系不变）。
     *
     * <p><b>性能</b>: 3 次减法 + 3 次乘法 + 2 次加法 ≈ 2-3ns
     *
     * @param x 目标 X 坐标
     * @param y 目标 Y 坐标
     * @param z 目标 Z 坐标
     * @return float 到相机的距离平方（世界单位^2）
     */
    public float distanceSquaredToCamera(float x, float y, float z) {
        float dx = x - cameraX;
        float dy = y - cameraY;
        float dz = z - cameraZ;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 计算到相机的欧几里得距离
     *
     * <p>需要 Math.sqrt 开销（~15-25ns），仅在确实需要线性距离时使用。
     * 优先使用 {@link #distanceSquaredToCamera(float, float, float)} 进行比较。
     *
     * @param x 目标 X 坐标
     * @param y 目标 Y 坐标
     * @param z 目标 Z 坐标
     * @return float 到相机的距离（世界单位）
     */
    public float distanceToCamera(float x, float y, float z) {
        return (float) Math.sqrt(distanceSquaredToCamera(x, y, z));
    }

    /**
     * 增加 Draw Call 计数
     *
     * <p><b>热路径方法</b>：每次 drawIndexed dispatch 时调用。
     * 用于跟踪当前帧的总绘制调用次数。
     */
    public void incrementDrawCall() {
        this.drawCallCount++;
    }

    /**
     * 检查当前帧是否已初始化（beginFrame 已调用且 endFrame 尚未调用）
     *
     * @return true 如果当前处于活跃帧内
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 获取帧起始以来的已过时间（纳秒）
     *
     * <p>用于热路径中的超时检查或阶段性计时。
     *
     * @return long 自 beginFrame 以来的纳秒数
     */
    public long getElapsedNs() {
        return System.nanoTime() - frameStartTimeNs;
    }

    /**
     * 获取宽高比
     *
     * @return float 宽高比（width / height），高度为 0 时返回 1.0 防止除零
     */
    public float getAspectRatio() {
        return windowHeight != 0 ? (float) windowWidth / windowHeight : 1.0f;
    }

    /**
     * 获取当前帧是否为奇数帧（用于双缓冲检测）
     *
     * <p>基于 frameIndex 的奇偶性判断：
     * <ul>
     *   <li>奇数帧（frameIndex % 2 == 1）：后缓冲区写入</li>
     *   <li>偶数帧（frameIndex % 2 == 0）：前缓冲区显示</li>
     * </ul>
     *
     * <p><b>性能</b>: 一次位与运算 (~0.5ns)，极快。
     *
     * @return true 如果是奇数帧，false 如果是偶数帧
     */
    public boolean isOddFrame() {
        return (frameIndex & 1) != 0;
    }
}
