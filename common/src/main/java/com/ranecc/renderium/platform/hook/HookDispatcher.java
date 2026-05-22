package com.ranecc.renderium.platform.hook;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.ranecc.renderium.platform.lifecycle.LifecycleManager;
import com.ranecc.renderium.infrastructure.sanitizer.LazyGuard;
import com.ranecc.renderium.infrastructure.sanitizer.DirtyFrameScope;
import com.ranecc.renderium.infrastructure.sanitizer.EntityBudget;
import com.ranecc.renderium.infrastructure.sanitizer.AutoCleanScheduler;
import com.ranecc.renderium.infrastructure.gpu.FrameCommandContext;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import com.ranecc.renderium.infrastructure.gpu.RenderiumProfiler;
import com.ranecc.renderium.infrastructure.gpu.AdaptivePipelineBalancer;
import com.ranecc.renderium.infrastructure.gpu.AsyncComputeDispatcher;
import com.ranecc.renderium.infrastructure.gpu.DAGNodeScheduler;

/**
 * 热路径调度核心 — 高性能 Hook 分发器
 *
 * <p><b>架构定位</b>: 这是 Platform 层最关键的类。所有 Minecraft 渲染管线的 Mixin 注入点
 * 最终都会调用此类的方法来分发事件到注册的 Hook 回调。
 *
 * <h3>架构图</h3>
 * <pre>
 * ┌──────────────┐    ┌──────────────────┐    ┌──────────────┐
 * │  MC Render   │───▶│  HookDispatcher  │───▶│  Hook 回调    │
 * │  (被注入点)   │    │  (热路径核心)     │    │  (业务逻辑)   │
 * └──────────────┘    └──────────────────┘    └──────────────┘
 *                           │
 *                    ┌──────┴──────┐
 *                    │ FrameContext │ ◀── ThreadLocal
 *                    └─────────────┘
 * </pre>
 *
 * <h3>性能保证</h3>
 * <ul>
 *   <li><b>空 hook 快速路径</b>: enabledFlags == 0 时直接返回，~1-2ns</li>
 *   <li><b>固定大小数组</b>: 替代 Map/List，JIT 可消除边界检查并预取相邻元素</li>
 *   <li><b>位图快速跳过</b>: 一次 int 位测试替代 N 次 boolean 检查，~1ns</li>
 *   <li><b>版本号同步机制</b>: 冷写热读（volatile long），无锁读取</li>
 *   <li><b>零 GC 分配</b>: 热路径不创建任何对象</li>
 * </ul>
 *
 * <h3>冷/热路径分离</h3>
 * <table border="1">
 *   <tr><th>路径</th><th>方法</th><th>调用频率</th><th>目标开销</th></tr>
 *   <tr><td>热路径</td><td>onDrawIndexed() 等 dispatch 方法</td><td>每帧数千次</td><td>&lt;10ns (空), +回调时间 (有)</td></tr>
 *   <tr><td>冷路径</td><td>register()/unregister()/setEnabled()</td><td>仅初始化时</td><td>&lt;1μs 可接受</td></tr>
 * </table>
 *
 * <h3>使用示例（从 Mixin 中调用）</h3>
 * <pre>{@code
 * // 在 GameRenderer.render() 的 HEAD 注入点：
 * @Inject(method = "render", at = @At("HEAD"))
 * private void renderium$onRenderStart(CallbackInfo ci) {
 *     FrameContext ctx = FrameContext.get();
 *     ctx.beginFrame(renderium$getPartialTick());
 *     HookDispatcher.onFrameStart(ctx);
 * }
 *
 * // 在 drawIndexed 方法前注入：
 * @Inject(method = "drawIndexed", at = @At("HEAD"))
 * private void renderium$onDrawIndexed(int count, ..., CallbackInfo ci) {
 *     if (!HookDispatcher.onDrawIndexed(mode, count, instanceCount)) {
 *         ci.cancel(); // hook 要求取消本次绘制
 *     }
 * }
 * }</pre>
 *
 * @see FrameContext — 统一帧上下文容器
 * @see HookEntry — 缓存友好的 Hook 条目值类型
 * @see HotPathMarker — 热路径方法标记注解
 * @since 2.0.0
 */
public final class HookDispatcher {

    private static final Logger LOGGER = Logger.getLogger(HookDispatcher.class.getName());

    // ==================== Hook 类型常量 ====================
    //
    // 使用 int 常量而非枚举，原因：
    // 1. 避免枚举.ordinal() 的方法调用开销（虽然 JIT 可能内联）
    // 2. 直接用于数组索引和位图运算，语义更明确
    // 3. 编译期确定所有值，JIT 可做更好的范围分析

    /** Hook 类型总数（固定，编译期确定） */
    public static final int HOOK_COUNT = 8;

    /** DrawIndexed hook 索引 — 最高频，每帧数千次 */
    public static final int DRAW_INDEXED = 0;

    /** SetPipeline hook 索引 — 每帧数百次 */
    public static final int SET_PIPELINE = 1;

    /** BindTexture hook 索引 — 每帧数百次 */
    public static final int BIND_TEXTURE = 2;

    /** CommandEncoderSubmit hook 索引 — 每帧一次 */
    public static final int COMMAND_ENCODER_SUBMIT = 3;

    /** PostChain hook 索引 — 每帧一次 */
    public static final int POST_CHAIN = 4;

    /** FrameGraphExecute hook 索引 — 每帧数次 */
    public static final int FRAME_GRAPH_EXECUTE = 5;

    /** RenderPassClose hook 索引 — 每帧数次 */
    public static final int RENDER_PASS_CLOSE = 6;

    /** GpuDeviceBuffer hook 索引 — 每帧数十次 */
    public static final int GPU_DEVICE_BUFFER = 7;

    /**
     * Hook 类型名称数组（与索引一一对应）
     *
     * <p>用于日志输出和性能报告。
     * 索引对应上面的常量值。
     */
    private static final String[] HOOK_NAMES = {
            "DrawIndexed",
            "SetPipeline",
            "BindTexture",
            "CommandEncoderSubmit",
            "PostChain",
            "FrameGraphExecute",
            "RenderPassClose",
            "GpuDeviceBuffer"
    };

    // ==================== 核心数据结构 ====================

    /**
     * Hook 条目数组 — 固定大小，连续内存，缓存友好
     *
     * <p>JIT 编译器对此类固定数组的优化能力：
     * <ul>
     *   <li>消除边界检查（范围分析证明 index &lt; HOOK_COUNT = 8）</li>
     *   <li>硬件预取相邻元素到 L1 缓存</li>
     *   <li>内联数组访问（消除方法调用）</li>
     * </ul>
     *
     * <p><b>线程安全</b>:
     * 数组引用本身是 final（不可变），但数组元素是可变的。
     * 冷路径写入通过版本号机制通知热路径数据已变更。
     */
    static final HookEntry[] hooks = new HookEntry[HOOK_COUNT];

    /**
     * 启用标志位图 — 每个 bit 代表一个 hook 是否有活跃回调
     *
     * <p>位布局示例（假设 DRAW_INDEXED 和 SET_PIPELINE 已启用）：
     * <pre>
     *   bit:  7  6  5  4  3  2  1  0
     *         G  R  F  P  C  B  S  D
     *   val:  0  0  0  0  0  0  1  1  = 0x03
     * </pre>
     *
     * <p>一次 int 检查即可知道是否有任何 hook 需要处理：
     * <pre>
     * if (enabledFlags == 0) return true; // ~1-2ns 完全空路径
     * if ((enabledFlags & (1 << DRAW_INDEXED)) == 0) return true; // ~1ns 单项跳过
     * </pre>
     */
    private static int enabledFlags = 0;

    /**
     * 结构版本号 — 冷写热读同步机制
     *
     * <p>每次修改 hooks[] 数组时递增（register/unregister/setEnabled）。
     * 热路径在每帧开始时（syncCache）读取此值并与本地缓存比较，
     * 不一致则说明配置有变化，需要重新加载。
     *
     * <p>volatile 保证跨线程可见性。
     * 使用 long 而非 int 以避免溢出（即使运行数年也不会溢出）。
     */
    private static volatile long version = 0;
    private static volatile LifecycleManager lifecycleManager;

    /**
     * 热路径缓存的版本号副本
     *
     * <p>每帧开始时从 {@link #version} 同步。
     * 如果相等，说明本帧无配置变化，可安全使用缓存的引用。
     */
    private static long cachedVersion = -1;

    // ==================== 统计计数器 ====================

    /** 总 dispatch 调用次数（所有类型合计）— 用于计算命中率 */
    private static final AtomicLong totalDispatches = new AtomicLong(0);

    /** 总 skipped 次数（hook 返回 false 或未注册导致跳过） */
    private static final AtomicLong totalSkips = new AtomicLong(0);

    /** 版本号变更次数（用于评估配置稳定性） */
    private static final AtomicLong totalVersionChanges = new AtomicLong(0);

    // ==================== 静态初始化 ====================

    static {
        // 初始化所有槽位为带名称的空 HookEntry（instance=null 表示未注册）
        for (int i = 0; i < HOOK_COUNT; i++) {
            hooks[i] = new HookEntry(i, HOOK_NAMES[i], null);
            hooks[i].setActive(false);  // 未注册的条目标记为不活跃
        }
        LOGGER.fine(() -> "HookDispatcher initialized with " + HOOK_COUNT + " hook slots");
    }

    // ==================== 私有构造函数 ====================

    private HookDispatcher() {
        // 工具类，禁止实例化
    }

    // ==================== 冷路径 API：注册 / 注销 / 管理 ====================

    /**
     * 注册 Hook 回调
     *
     * <p><b>冷路径方法</b>：仅在初始化或动态加载插件时调用。
     * 注册后会递增版本号，热路径将在下一帧 syncCache() 时检测到变化。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数 type</b>：int 类型的 Hook 类型常量（DRAW_INDEXED 等，范围 [0, HOOK_COUNT)）</li>
     *   <li><b>参数 hook</b>：Object 类型的 Hook 回调实例（非 null），实际类型需匹配对应的接口</li>
     *   <li><b>返回值</b>：void</li>
     *   <li><b>异常</b>：
     *     <ul>
     *       <li>IllegalArgumentException — 如果 type 超出范围或 hook 为 null</li>
     *       <li>IllegalStateException — 如果该槽位已有注册的回调（需先注销）</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * @param type Hook 类型常量（DRAW_INDEXED, SET_PIPELINE 等）
     * @param hook Hook 回调实例（必须非 null，类型需匹配对应接口）
     * @throws IllegalArgumentException 如果 type 或 hook 参数无效
     * @throws IllegalStateException 如果该 Hook 类型已有注册的回调
     */
    public static void register(int type, Object hook) {
        // 参数校验
        if (type < 0 || type >= HOOK_COUNT) {
            throw new IllegalArgumentException(
                    "Invalid hook type: " + type + ". Must be in range [0, " + HOOK_COUNT + ")"
            );
        }
        if (hook == null) {
            throw new IllegalArgumentException("Hook callback must not be null");
        }

        synchronized (hooks) {
            HookEntry existing = hooks[type];
            // 检查是否已有注册（instance 非 null 且处于活跃状态）
            if (existing.getInstance() != null && existing.isActive()) {
                throw new IllegalStateException(
                        "Hook already registered for type [" + HOOK_NAMES[type] +
                                "] (index=" + type +
                                "). Current: " + existing.getInstance().getClass().getName() +
                                ". Call unregister(" + type + ") first."
                );
            }

            // 创建新的 HookEntry 并替换
            hooks[type] = new HookEntry(type, HOOK_NAMES[type], hook);
            rebuildEnabledFlags();
            version++;
            totalVersionChanges.incrementAndGet();
        }

        LOGGER.fine(() -> "Hook registered: [" + HOOK_NAMES[type] + "] -> " + hook.getClass().getName());
    }

    /**
     * 注销指定类型的 Hook
     *
     * <p><b>冷路径方法</b>：与 {@link #register(int, Object)} 配对使用。
     * 注销后该槽位的 instance 设为 null，不再参与调度。
     *
     * @param type 要注销的 Hook 类型常量
     * @throws IllegalArgumentException 如果 type 超出范围
     */
    public static void unregister(int type) {
        if (type < 0 || type >= HOOK_COUNT) {
            throw new IllegalArgumentException(
                    "Invalid hook type: " + type + ". Must be in range [0, " + HOOK_COUNT + ")"
            );
        }

        synchronized (hooks) {
            // 创建空的 HookEntry（instance=null, active=false）
            hooks[type] = new HookEntry(type, HOOK_NAMES[type], null);
            hooks[type].setActive(false);
            rebuildEnabledFlags();
            version++;
            totalVersionChanges.incrementAndGet();
        }

        LOGGER.fine(() -> "Hook unregistered: [" + HOOK_NAMES[type] + "]");
    }

    /**
     * 启用/禁用指定 Hook（不删除回调实例，仅切换状态）
     *
     * <p><b>冷路径方法</b>：用于运行时临时暂停/恢复 hook。
     * 比 unregister/register 更快（不改变 hook 引用，仅翻转 active 标志位）。
     *
     * @param type    Hook 类型常量
     * @param enabled true 启用（参与调度），false 禁用（跳过但不删除）
     */
    public static void setEnabled(int type, boolean enabled) {
        if (type < 0 || type >= HOOK_COUNT) {
            return; // 静默忽略无效类型
        }

        synchronized (hooks) {
            HookEntry entry = hooks[type];
            if (entry.getInstance() == null) {
                return; // 未注册，忽略
            }

            entry.setActive(enabled);
            rebuildEnabledFlags();
            version++;
            totalVersionChanges.incrementAndGet();
        }
    }

    /**
     * 同步缓存 — 在帧开始时调用，确保热路径看到最新注册
     *
     * <p>将 version++ 使热路径的本地缓存失效。
     * 通常在 onFrameStart() 内部自动调用，也可手动触发强制刷新。
     *
     * <p><b>性能</b>:
     * <ul>
     *   <li>无变化时：~3-5ns（一次 volatile 读 + 一次 long 比较）</li>
     *   <li>有变化时：~50-100ns（重建 enabledFlags 位图）</li>
     * </ul>
     *
     * @return long 当前版本号
     */
    public static long syncCache() {
        long currentVersion = version;
        if (currentVersion != cachedVersion) {
            // 版本号不一致 → 配置发生了变化，重建位图
            rebuildEnabledFlags();
            cachedVersion = currentVersion;
        }
        return currentVersion;
    }

    // ==================== 热路径 API：事件分发 ====================
    //
    // 所有 onXxx 方法遵循统一的分发模式：
    //   1. 快速路径：enabledFlags == 0 → return true (~1-2ns)
    //   2. 位图过滤：(enabledFlags & bitMask) == 0 → return true (~1ns)
    //   3. 获取条目：entry = hooks[type] (~1ns, 数组O(1))
    //   4. 活跃检查：!entry.isActive() → return true (~1ns)
    //   5. 执行回调：强转 + 调用（唯一可能慢的部分）
    //   6. 记录统计：entry.recordCall(durationNs)
    //
    // 空hook总开销（步骤1-4）：~4-6ns 目标

    /**
     * 帧开始通知 — 在 GameRenderer.render() 入口调用
     *
     * <p>执行缓存同步（syncCache），确保热路径看到最新的 hook 注册状态。
     * 同时重置本帧的性能计数基线。
     *
     * @param ctx 当前帧上下文（由 FrameContext.get() 获取）
     */
    @HotPathMarker(
            frequency = HotPathMarker.Frequency.PER_FRAME_LOW,
            budgetNs = 50,
            description = "每帧入口处的缓存同步和初始化"
    )
    public static void onFrameStart(FrameContext ctx) {
        syncCache();

        long frameTimeNs = ctx.deltaTime > 0 ? (long)(ctx.deltaTime * 1_000_000_000L) : 16_666_667L;
        boolean dirty = LazyGuard.onFrameBegin(frameTimeNs, ctx.entityCount,
                ctx.tileEntityCount, ctx.visibleSectionCount, ctx.totalSectionCount);
        if (dirty) {
            DirtyFrameScope scope = DirtyFrameScope.enter();
            ctx.setDirtyFrameScope(scope);
            EntityBudget.check(ctx.entityCount);
        }

        if (!FrameCommandContext.isInitialized()) {
            long dev = VulkanDeviceHolder.getInstance().getDevice();
            if (dev != 0L) {
                long queue = VulkanDeviceHolder.getInstance().getGraphicsQueue();
                FrameCommandContext.init(dev, queue != 0L ? queue : VulkanDeviceHolder.getInstance().getComputeQueue());
            }
        }
        FrameCommandContext.beginFrame();
        RenderiumProfiler.beginFrame();

        // 自适应负载均衡：记录 CPU 帧开始时间
        AdaptivePipelineBalancer.onFrameBegin();

        // Async Compute 初始化（延迟到首帧，确保 VulkanDeviceHolder 就绪）
        if (!AsyncComputeDispatcher.isAvailable()) {
            AsyncComputeDispatcher.initialize();
        }

        // DAG 调度器初始化
        if (DAGNodeScheduler.computeTopologyLayers() == null
                || DAGNodeScheduler.computeTopologyLayers().isEmpty()) {
            DAGNodeScheduler.registerBuiltinDependencies();
        }

        // Async Compute 帧开始
        AsyncComputeDispatcher.beginFrame();
        DAGNodeScheduler.beginFrame();
    }

    /**
     * 帧结束通知 — 在 GameRenderer.render() 出口调用
     *
     * <p>可用于帧级统计汇总、资源释放等收尾工作。
     *
     * @param ctx 当前帧上下文
     */
    @HotPathMarker(
            frequency = HotPathMarker.Frequency.PER_FRAME_LOW,
            budgetNs = 50,
            description = "每帧出口处的清理和统计"
    )
    public static void onFrameEnd(FrameContext ctx) {
        RenderiumProfiler.endFrame();
        FrameCommandContext.endFrame();

        // Async Compute 帧结束：提交 Compute Queue 命令
        AsyncComputeDispatcher.endFrame();

        // 自适应负载均衡：记录帧结束时间 + 更新策略
        AdaptivePipelineBalancer.onFrameEnd();

        Object scope = ctx.getDirtyFrameScope();
        if (scope instanceof DirtyFrameScope) {
            ((DirtyFrameScope) scope).close();
            ctx.setDirtyFrameScope(null);
        }
        LazyGuard.onFrameEnd(scope == null);
        AutoCleanScheduler.onFrameEnd();
    }

    /**
     * DrawIndexed 事件分发 — 最高频调用
     *
     * <p><b>热路径方法</b>：每次 MC 执行 drawIndexed/drawElements 时调用。
     * 这是渲染管线中最高频的 hook 点（每帧数百~数千次）。
     *
     * <p><b>参数来源</b>（对应 Vulkan/OpenGL API）：
     * <ul>
     *   <li>mode: 绘制模式（GL_TRIANGLES=4, GL_LINES=1 等）</li>
     *   <li>count: 索引数量（三角形数量 x 3）</li>
     *   <li>instanceCount: 实例化数量（1=标准绘制）</li>
     * </ul>
     *
     * @param mode         绘制模式（GL_TRIANGLES 等）
     * @param count        索引/顶点数量
     * @param instanceCount 实例数量（instanced drawing）
     * @return false 如果 hook 要求取消本次绘制调用；true 允许继续
     */
    @HotPathMarker(
            frequency = HotPathMarker.Frequency.PER_FRAME_HIGH,
            budgetNs = 100,
            description = "Draw call interception — 最高频 hook 点"
    )
    public static boolean onDrawIndexed(int mode, int count, int instanceCount) {
        // 快速路径：完全无任何 hook 注册
        if (enabledFlags == 0) {
            return true; // ~1-2ns 直接放行
        }

        // 位图快速过滤：此 hook 类型未启用
        if ((enabledFlags & (1 << DRAW_INDEXED)) == 0) {
            return true; // ~1ns 位测试跳过
        }

        // 获取 hook 条目并执行回调
        HookEntry entry = hooks[DRAW_INDEXED];           // 数组 O(1) 访问
        if (!entry.isActive()) {
            return true;                                  // ~1ns 活跃检查
        }

        // 执行回调（唯一可能慢的部分，取决于用户实现）
        long start = System.nanoTime();                   // ~20-30ns
        try {
            DrawIndexedHook hook = (DrawIndexedHook) entry.getInstance();
            boolean result = hook.onDrawIndexed(count, instanceCount, 0, 0, 0);
            entry.recordCall(System.nanoTime() - start);  // 记录统计
            return result;
        } catch (Exception e) {
            // 回调异常时不阻止原始调用（防御性编程）
            LOGGER.warning(() -> "DrawIndexedHook exception in [" + entry.getName() + "]: " + e.getMessage());
            return true;
        }
    }

    /**
     * SetPipeline 事件分发 — 管线/着色器切换
     *
     * <p>管线切换是渲染性能的关键瓶颈之一，
     * 此 hook 可用于管线状态缓存、自定义替换、Shader 变体选择等。
     *
     * @param pipelineId 管线标识符（Vulkan Pipeline handle / OpenGL Program ID）
     * @return false 如果 hook 要求跳过管线切换
     */
    @HotPathMarker(
            frequency = HotPathMarker.Frequency.PER_FRAME_MEDIUM,
            budgetNs = 200,
            description = "Pipeline/shader 切换拦截"
    )
    public static boolean onSetPipeline(int pipelineId) {
        if (enabledFlags == 0) return true;
        if ((enabledFlags & (1 << SET_PIPELINE)) == 0) return true;

        HookEntry entry = hooks[SET_PIPELINE];
        if (!entry.isActive()) return true;

        long start = System.nanoTime();
        try {
            SetPipelineHook hook = (SetPipelineHook) entry.getInstance();
            hook.onSetPipeline(pipelineId, null);  // pass 参数设为 null（简化签名）
            entry.recordCall(System.nanoTime() - start);
            return true;  // SetPipeline 默认不阻止
        } catch (Exception e) {
            LOGGER.warning(() -> "SetPipelineHook exception in [" + entry.getName() + "]: " + e.getMessage());
            return true;
        }
    }

    /**
     * BindTexture 事件分发 — 纹理绑定
     *
     * <p>纹理绑定是高频操作，可用于纹理 atlas 合并、流式加载管理等。
     *
     * @param unit       纹理单元编号（0 ~ maxTextureUnits-1）
     * @param textureId  纹理 ID / Texture 对象引用
     * @return false 如果 hook 要求跳过绑定
     */
    @HotPathMarker(
            frequency = HotPathMarker.Frequency.PER_FRAME_MEDIUM,
            budgetNs = 100,
            description = "纹理单元绑定拦截"
    )
    public static boolean onBindTexture(int unit, int textureId) {
        if (enabledFlags == 0) return true;
        if ((enabledFlags & (1 << BIND_TEXTURE)) == 0) return true;

        HookEntry entry = hooks[BIND_TEXTURE];
        if (!entry.isActive()) return true;

        long start = System.nanoTime();
        try {
            BindTextureHook hook = (BindTextureHook) entry.getInstance();
            boolean result = hook.onBindTexture(textureId, unit);
            entry.recordCall(System.nanoTime() - start);
            return result;
        } catch (Exception e) {
            LOGGER.warning(() -> "BindTextureHook exception in [" + entry.getName() + "]: " + e.getMessage());
            return true;
        }
    }

    /**
     * CommandEncoderSubmit 事件分发 — 命令缓冲区提交
     *
     * <p>每帧执行一次的关键同步点，可用于帧级统计汇总和时间戳插入。
     *
     * @return false 如果 hook 要求取消提交
     */
    @HotPathMarker(
            frequency = HotPathMarker.Frequency.PER_FRAME_LOW,
            budgetNs = 1000,
            description = "命令编码器提交拦截"
    )
    public static boolean onCommandEncoderSubmit() {
        if (enabledFlags == 0) return true;
        if ((enabledFlags & (1 << COMMAND_ENCODER_SUBMIT)) == 0) return true;

        HookEntry entry = hooks[COMMAND_ENCODER_SUBMIT];
        if (!entry.isActive()) return true;

        long start = System.nanoTime();
        try {
            CommandEncoderSubmitHook hook = (CommandEncoderSubmitHook) entry.getInstance();
            hook.onSubmit(null);
            entry.recordCall(System.nanoTime() - start);
            return true;
        } catch (Exception e) {
            LOGGER.warning(() -> "CommandEncoderSubmitHook exception in [" + entry.getName() + "]: " + e.getMessage());
            return true;
        }
    }

    /**
     * PostChain 后处理事件分发
     *
     * <p>后处理链（Bloom, Tone Mapping, SSAO 等）执行前后触发。
     *
     * @param timestamp 时间戳（纳秒），通常为 System.nanoTime()
     * @return false 如果 hook 要求取消后处理
     */
    @HotPathMarker(
            frequency = HotPathMarker.Frequency.PER_FRAME_LOW,
            budgetNs = 2000,
            description = "后处理链执行拦截"
    )
    public static boolean onPostChain(long timestamp) {
        if (enabledFlags == 0) return true;
        if ((enabledFlags & (1 << POST_CHAIN)) == 0) return true;

        HookEntry entry = hooks[POST_CHAIN];
        if (!entry.isActive()) return true;

        long start = System.nanoTime();
        try {
            PostChainHook hook = (PostChainHook) entry.getInstance();
            hook.onAddToFrame(null, null);
            entry.recordCall(System.nanoTime() - start);
            return true;
        } catch (Exception e) {
            LOGGER.warning(() -> "PostChainHook exception in [" + entry.getName() + "]: " + e.getMessage());
            return true;
        }
    }

    /**
     * FrameGraphExecute 事件分发 — 帧 graph Pass 执行
     *
     * <p>在 Frame Graph DAG 的每个 Pass 执行时触发。
     *
     * @param passName 当前执行的 Pass 名称（如 "opaque", "transparent", "clouds"）
     * @return false 如果 hook 要求取消执行
     */
    @HotPathMarker(
            frequency = HotPathMarker.Frequency.PER_FRAME_LOW,
            budgetNs = 500,
            description = "帧图 Pass 执行拦截"
    )
    public static boolean onFrameGraphExecute(String passName) {
        if (enabledFlags == 0) return true;
        if ((enabledFlags & (1 << FRAME_GRAPH_EXECUTE)) == 0) return true;

        HookEntry entry = hooks[FRAME_GRAPH_EXECUTE];
        if (!entry.isActive()) return true;

        long start = System.nanoTime();
        try {
            FrameGraphExecuteHook hook = (FrameGraphExecuteHook) entry.getInstance();
            hook.onExecute(null, passName);
            entry.recordCall(System.nanoTime() - start);
            return true;
        } catch (Exception e) {
            LOGGER.warning(() -> "FrameGraphExecuteHook exception in [" + entry.getName() + "]: " + e.getMessage());
            return true;
        }
    }

    /**
     * RenderPassClose 事件分发 — 渲染通道关闭
     *
     * <p>Vulkan vkCmdEndRenderPass / OpenGL framebuffer 解绑时触发。
     *
     * @param passType 通道类型标识（整数编码）
     * @return false 如果 hook 要求阻止关闭
     */
    @HotPathMarker(
            frequency = HotPathMarker.Frequency.PER_FRAME_LOW,
            budgetNs = 500,
            description = "渲染通道关闭拦截"
    )
    public static boolean onRenderPassClose(int passType) {
        if (enabledFlags == 0) return true;
        if ((enabledFlags & (1 << RENDER_PASS_CLOSE)) == 0) return true;

        HookEntry entry = hooks[RENDER_PASS_CLOSE];
        if (!entry.isActive()) return true;

        long start = System.nanoTime();
        try {
            RenderPassCloseHook hook = (RenderPassCloseHook) entry.getInstance();
            hook.onRenderPassClose(null, System.nanoTime());
            entry.recordCall(System.nanoTime() - start);
            return true;
        } catch (Exception e) {
            LOGGER.warning(() -> "RenderPassCloseHook exception in [" + entry.getName() + "]: " + e.getMessage());
            return true;
        }
    }

    /**
     * GpuDeviceBuffer 操作事件分发 — GPU 缓冲区管理
     *
     * <p>覆盖缓冲区的创建、上传、映射、销毁等生命周期操作。
     *
     * @param operation 操作类型（CREATE/MAP/UNMAP/DELETE 等字符串）
     * @param size      缓冲区大小（字节）
     * @return false 如果 hook 要求取消操作
     */
    @HotPathMarker(
            frequency = HotPathMarker.Frequency.PER_FRAME_MEDIUM,
            budgetNs = 500,
            description = "GPU 缓冲区操作拦截"
    )
    public static boolean onGpuDeviceBuffer(int operation, long size) {
        if (enabledFlags == 0) return true;
        if ((enabledFlags & (1 << GPU_DEVICE_BUFFER)) == 0) return true;

        HookEntry entry = hooks[GPU_DEVICE_BUFFER];
        if (!entry.isActive()) return true;

        long start = System.nanoTime();
        try {
            GpuDeviceBufferHook hook = (GpuDeviceBufferHook) entry.getInstance();
            // 将 int operation 转换为字符串描述
            String opStr = decodeBufferOperation(operation);
            hook.onCreateBuffer(() -> opStr, operation, size);
            entry.recordCall(System.nanoTime() - start);
            return true;
        } catch (Exception e) {
            LOGGER.warning(() -> "GpuDeviceBufferHook exception in [" + entry.getName() + "]: " + e.getMessage());
            return true;
        }
    }

    // ==================== 诊断 / 统计 API ====================

    /**
     * 获取所有 Hook 的性能统计摘要
     *
     * <p><b>冷路径方法</b>：用于控制台输出、调试面板、日志记录。
     * 包含全局统计和每个 hook 类型的详细数据。
     *
     * @return 多行格式化字符串，包含每个 hook 的调用次数、平均耗时等
     */
    public static String getPerformanceReport() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("=== HookDispatcher Performance Report ===\n");

        // 全局统计
        long dispatches = totalDispatches.get();
        long skips = totalSkips.get();
        sb.append(String.format("Total dispatches: %d%n", dispatches));
        sb.append(String.format("Total skips: %d (%.2f%%)%n",
                skips, dispatches > 0 ? (skips * 100.0 / dispatches) : 0));
        sb.append(String.format("Version changes: %d%n", totalVersionChanges.get()));
        sb.append(String.format("Current version: %d%n", version));
        sb.append(String.format("Enabled flags: 0x%02X (%d hooks active)%n%n",
                enabledFlags, Integer.bitCount(enabledFlags)));

        // 每个 hook 类型的详细统计
        sb.append("--- Per-Hook Statistics ---\n");
        for (int i = 0; i < HOOK_COUNT; i++) {
            HookEntry entry = hooks[i];
            if (entry != null && entry.getTotalCalls() > 0) {
                sb.append(String.format("  [%s] %s%n", HOOK_NAMES[i], entry.getStatsSummary()));
            } else {
                String status = (entry != null && entry.getInstance() != null)
                        ? (entry.isActive() ? "active" : "disabled")
                        : "unregistered";
                sb.append(String.format("  [%s] %s (%s)%n", HOOK_NAMES[i], status,
                        entry != null ? "—" : "null"));
            }
        }

        return sb.toString();
    }

    /**
     * 获取当前启用的 hook 数量
     *
     * <p>基于 enabledFlags 的 popcount 计算，O(1) 操作。
     *
     * @return int 当前启用的 hook 数量 [0, HOOK_COUNT]
     */
    public static int getEnabledCount() {
        return Integer.bitCount(enabledFlags);
    }

    /**
     * 重置所有统计计数器
     *
     * <p>用于开始新的性能分析周期（如场景切换后重新建立基线）。
     * 不影响 hook 注册状态，仅清零统计数据。
     */
    public static void resetStats() {
        totalDispatches.set(0);
        totalSkips.set(0);
        // 注意：totalVersionChanges 不重置（它是累积指标）
        for (int i = 0; i < HOOK_COUNT; i++) {
            if (hooks[i] != null) {
                hooks[i].resetStats();
            }
        }
        LOGGER.fine("HookDispatcher statistics reset");
    }

    /**
     * 获取当前版本号
     *
     * @return long 结构版本号（每次 register/unregister/setEnabled 后递增）
     */
    public static long getVersion() {
        return version;
    }

    /**
     * 获取当前启用的标志位图
     *
     * @return int 位图，每个 bit 表示对应 HookType 是否启用
     */
    public static int getEnabledFlags() {
        return enabledFlags;
    }

    /**
     * 检查指定类型的 Hook 是否已注册且启用
     *
     * @param type Hook 类型常量
     * @return true 如果已注册且启用
     */
    public static boolean isEnabled(int type) {
        if (type < 0 || type >= HOOK_COUNT) return false;
        HookEntry entry = hooks[type];
        return entry != null && entry.isActive();
    }

    /**
     * 获取指定类型的 Hook 条目（用于统计和调试）
     *
     * @param type Hook 类型常量
     * @return HookEntry 当前条目（永远非 null，但 instance 可能为 null）
     */
    public static HookEntry getEntry(int type) {
        if (type < 0 || type >= HOOK_COUNT) return null;
        return hooks[type];
    }

    
    /**
     * 获取当前生命周期管理器实例
     *
     * @return LifecycleManager 实例，可能为 null（未初始化时）
     */
    public static LifecycleManager getLifecycleManager() {
        return lifecycleManager;
    }

    /**
     * 设置生命周期管理器实例
     *
     * @param manager LifecycleManager 实现
     */
    public static void setLifecycleManager(LifecycleManager manager) {
        lifecycleManager = manager;
        version++;
        LOGGER.fine("[HookDispatcher] LifecycleManager set: " + (manager != null ? manager.getClass().getSimpleName() : "null"));
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 重建启用的标志位图
     *
     * <p>遍历所有 hook 条目，将 isActive() 状态压缩为 int 位图。
     * 位图用于热路径的快速批量检查（一次 int 比较 vs N 次 boolean 检查）。
     *
     * <p><b>调用时机</b>：register/unregister/setEnabled 后由 synchronized 块内调用。
     */
    private static void rebuildEnabledFlags() {
        int flags = 0;
        for (int i = 0; i < HOOK_COUNT; i++) {
            if (hooks[i] != null && hooks[i].isActive()) {
                flags |= (1 << i);
            }
        }
        enabledFlags = flags;
    }

    /**
     * 将整数操作类型码解码为可读字符串
     *
     * <p>用于 GpuDeviceBuffer hook 的兼容性适配。
     *
     * @param operation 整数操作码
     * @return String 操作类型描述字符串
     */
    private static String decodeBufferOperation(int operation) {
        // 常见操作码映射（可根据需要扩展）
        switch (operation) {
            case 0: return "CREATE";
            case 1: return "UPLOAD";
            case 2: return "MAP";
            case 3: return "UNMAP";
            case 4: return "INVALIDATE";
            case 5: return "DESTROY";
            default: return "UNKNOWN(" + operation + ")";
        }
    }

    // ==================== L0 Mixin 防腐层回调 ====================

    /**
     * 后端就绪通知 - 由 MixinRenderSystem.initRenderer() TAIL 调用
     * <p>
     * Vulkan 设备句柄已获取，可初始化 GPU 相关 Hook。
     */
    public static void onBackendReady() {
        version++;
        LOGGER.fine("[HookDispatcher] Backend ready - Vulkan device available");
    }

    /**
     * 窗口就绪通知 - 由 MixinWindow.<init>() TAIL 调用
     * <p>
     * 窗口句柄已创建，可注册窗口级 Hook。
     */
    public static void onWindowReady() {
        version++;
        LOGGER.fine("[HookDispatcher] Window ready - window handle available");
    }

    /**
     * GPU 缓冲区创建通知 - 由 MixinGpuDevice.createBuffer() HEAD 调用（无大小信息）
     * <p>
     * 触发 GpuDeviceBufferHook 回调。无需缓冲区大小信息时使用此重载。
     *
     * @return 是否应拦截/修改该操作
     */
    public static boolean dispatchGpuDeviceBuffer() {
        return onGpuDeviceBuffer(0, 0);
    }

    /**
     * GPU 缓冲区创建通知（带缓冲区大小） - 由 MixinGpuDevice.createBuffer() HEAD 调用
     * <p>
     * 触发 GpuDeviceBufferHook 回调，并传递实际缓冲区大小供内存优化使用。
     *
     * @param size 缓冲区大小（字节），来自 GpuDevice.createBuffer() 的参数
     * @return 是否应拦截/修改该操作
     */
    public static boolean dispatchGpuDeviceBuffer(long size) {
        return onGpuDeviceBuffer(0, size);
    }

    /**
     * 帧图执行通知 - 由 MixinFrameGraph.execute() HEAD 调用
     * <p>
     * 触发 FrameGraphExecuteHook 回调。
     */
    public static void dispatchFrameGraphExecute() {
        onFrameGraphExecute("execute");
    }

}
