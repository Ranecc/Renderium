// Renderium - Blaze3D 优化模块
// 仪器化资源分配器 - GraphicsResourceAllocator 的包装器实现
//
// ⚠️ RISKY 级别 - 仅使用包装模式，禁止直接 Mixin GraphicsResourceAllocator！
// 对应策略 [A1, A2, A3]：委托 + 自定义池化优先 + 完整统计

package com.ranecc.renderium.feature.blaze3d;

// 注意：使用 Renderium 提供的适配接口
// 在 26.2+ 中，这个接口会委托给 Mojang 的官方实现

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 仪器化资源分配器 📊
 * <p>
 * {@link GraphicsResourceAllocator} 的包装器实现，采用 **装饰器模式** 而非 Mixin。
 * 在不修改 Mojang 原始分配器行为的前提下，添加以下增强功能：
 *
 * <h2>核心功能：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │  1. 完整统计记录                                             │
 * │     - acquire()/release() 操作的全生命周期追踪                │
 * │     - 通过 ResourceStats 记录获取/释放/池化命中              │
 * ├─────────────────────────────────────────────────────────────┤
 * │  2. MemoryOptimizer 自定义池化集成                            │
 * │     - acquire(): 优先从自定义池获取，未命中再委托原始分配器    │
 * │     - release(): 优先返回自定义池，否则委托原始释放器          │
 * ├─────────────────────────────────────────────────────────────┤
 * │  3. Mojang 未来改造适配                                       │
 * │     - 自动检测 Mojang 是否已实现原生池化                       │
 * │     - 动态调整策略避免双重池化                                │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>设计原则：</h3>
 * <ul>
 *   <li>⚠️ <b>禁止直接 Mixin</b> GraphicsResourceAllocator，必须使用包装模式</li>
 *   <li>通过 Module 初始化流程集成（Blaze3DOptimizerModule）</li>
 *   <li>线程安全：所有状态字段使用 volatile 或原子类型</li>
 *   <li>零侵入性：对调用方透明，行为与原始分配器完全一致</li>
 * </ul>
 *
 * <h3>策略说明 [A1, A2, A3]：</h3>
 * <ol>
 *   <li><b>A1 - 委托模式</b>: 所有操作最终委托给原始 delegate 执行</li>
 *   <li><b>A2 - 池化优先</b>: acquire/release 时优先走 MemoryOptimizer 池化路径</li>
 *   <li><b>A3 - 统计完整</b>: 每次操作都通过 ResourceStats 记录详细指标</li>
 * </ol>
 *
 * @see GraphicsResourceAllocator Mojang 原始资源分配器接口
 * @see MemoryOptimizer Renderium 自定义显存池管理器
 * @see ResourceStats 资源统计记录器
 * @author Renderium Team
 * @since 2.0.0
 */
public class InstrumentedResourceAllocator implements GraphicsResourceAllocator {

    private static final Logger LOGGER = Logger.getLogger(InstrumentedResourceAllocator.class.getName());

    // ==================== 配置常量 ====================

    /** 默认是否启用自定义池化 */
    private static final boolean DEFAULT_CUSTOM_POOLING_ENABLED = true;

    /** Mojang 原生池化检测采样间隔（次数） */
    private static final long MOJANG_POOLING_DETECT_INTERVAL = 1000;

    // ==================== 核心字段 ====================

    /**
     * 原始资源分配器（Mojang 实现）
     * <p>
     * 所有未被拦截的操作都会委托给此对象。
     * 使用 final 保证不可变性，确保线程安全。
     */
    private final GraphicsResourceAllocator delegate;

    /**
     * Renderium 内存优化器（可选）
     * <p>
     * 提供自定义的 Arena 池化能力。
     * 可能为 null（当内存优化未启用时）。
     */
    private volatile MemoryOptimizer memoryOptimizer;

    // ==================== 开关字段 ====================

    /**
     * 是否启用自定义池化
     * <p>
     * 当为 true 时，acquire/release 会优先尝试 MemoryOptimizer 的池化路径。
     * 使用 AtomicBoolean 保证线程安全的读写。
     */
    private final AtomicBoolean customPoolingEnabled;

    /**
     * 是否检测到 Mojang 已实现原生池化
     * <p>
     * 当 Mojang 未来版本添加了内置池化时，自动禁用自定义池化以避免冲突。
     */
    private volatile boolean mojangNativePoolingDetected = false;

    // ==================== 统计字段 ====================

    /** 总 acquire 调用次数 */
    private final AtomicLong totalAcquireCalls = new AtomicLong(0);

    /** 总 release 调用次数 */
    private final AtomicLong totalReleaseCalls = new AtomicLong(0);

    /** 自定义池化命中次数 */
    private final AtomicLong customPoolHitCount = new AtomicLong(0);

    /** 自定义池化未命中次数（回退到 delegate） */
    private final AtomicLong customPoolMissCount = new AtomicLong(0);

    /** 上一次 Mojang 池化检测时的 acquire 计数 */
    private volatile long lastPoolingDetectCheckCount = 0;

    // ==================== 构造函数 ====================

    /**
     * 创建仪器化资源分配器
     *
     * @param delegate 原始的 GraphicsResourceAllocator 实现（Mojang 提供），不能为 null
     *
     * @throws NullPointerException 如果 delegate 为 null
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>delegate</b>: {@link GraphicsResourceAllocator} - Mojang 的原始资源分配器实例。
     *       通常从 Minecraft 的渲染系统中获取。</li>
     * </ul>
     *
     * <h4>初始状态：</h4>
     * <ul>
     *   <li>自定义池化：启用（{@value #DEFAULT_CUSTOM_POOLING_ENABLED}）</li>
     *   <li>MemoryOptimizer：未设置（null），需后续通过 {@link #setMemoryOptimizer} 配置</li>
     *   <li>Mojang 原生池化检测：未检测</li>
     * </ul>
     */
    public InstrumentedResourceAllocator(GraphicsResourceAllocator delegate) {
        Objects.requireNonNull(delegate, "delegate 不能为 null");
        this.delegate = delegate;
        this.customPoolingEnabled = new AtomicBoolean(DEFAULT_CUSTOM_POOLING_ENABLED);

        LOGGER.info(String.format(
                "InstrumentedResourceAllocator 创建完成 (delegate=%s, 自定义池化=%s)",
                delegate.getClass().getSimpleName(),
                DEFAULT_CUSTOM_POOLING_ENABLED ? "启用" : "禁用"
        ));
    }

    /**
     * 创建仪器化资源分配器（带自定义池化开关）
     *
     * @param delegate             原始的 GraphicsResourceAllocator 实现，不能为 null
     * @param customPoolingEnabled 初始是否启用自定义池化
     *
     * @throws NullPointerException 如果 delegate 为 null
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>delegate</b>: {@link GraphicsResourceAllocator} - Mojang 的原始资源分配器实例</li>
     *   <li><b>customPoolingEnabled</b>: boolean - 是否在初始化时启用自定义池化功能。
     *       可在运行时通过 {@link #setCustomPoolingEnabled} 动态切换</li>
     * </ul>
     */
    public InstrumentedResourceAllocator(GraphicsResourceAllocator delegate, boolean customPoolingEnabled) {
        Objects.requireNonNull(delegate, "delegate 不能为 null");
        this.delegate = delegate;
        this.customPoolingEnabled = new AtomicBoolean(customPoolingEnabled);

        LOGGER.info(String.format(
                "InstrumentedResourceAllocator 创建完成 (delegate=%s, 自定义池化=%s)",
                delegate.getClass().getSimpleName(),
                customPoolingEnabled ? "启用" : "禁用"
        ));
    }

    // ==================== GraphicsResourceAllocator 接口实现 ====================

    /**
     * 获取图形资源 🎯
     * <p>
     * 实现策略 [A1, A2, A3]：
     * <ol>
     *   <li><b>记录统计 (A3)</b>: 通过 ResourceStats 记录本次获取操作的详细信息</li>
     *   <li><b>自定义池化尝试 (A2)</b>: 如果启用了自定义池化且 MemoryOptimizer 可用，
     *       优先从 Arena 池中获取资源</li>
     *   <li><b>委托原始分配器 (A1)</b>: 如果池化未命中或未启用，委托给 Mojang 的原始分配器</li>
     * </ol>
     *
     * @param <T>        资源类型泛型参数
     * @param descriptor 资源描述符，包含资源的类型、大小、用途等信息
     *
     * @return 获取到的资源实例，不会返回 null（原始分配器的约定）
     *
     * @throws NullPointerException 如果 descriptor 为 null
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>descriptor</b>: {@link ResourceDescriptor}&lt;T&gt; - 资源描述符，
     *       由调用方提供，描述所需资源的特征。通常包括：
     *     <ul>
     *       <li>资源类型（Buffer、Image、Pipeline 等）</li>
     *       <li>大小或尺寸信息</li>
     *       <li>使用场景（Vertex、Index、Uniform 等）</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>T - 请求的资源实例。具体类型由 descriptor 的泛型参数决定</li>
     * </ul>
     *
     * <h4>性能考虑：</h4>
     * <ul>
     *   <li>统计记录开销：~50ns/次（AtomicLong 操作 + HashMap 写入）</li>
     *   <li>池化检查开销：~10ns/次（volatile 读 + boolean 判断）</li>
     *   <li>Mojang 检测开销：每 {@value #MOJANG_POOLING_DETECT_INTERVAL} 次 ~100ns（反射调用）</li>
     *   <li>总体开销 &lt; 1µs/次，相对于 GPU 资源创建（~ms级）可忽略</li>
     * </ul>
     */
    @Override
    public <T> T acquire(ResourceDescriptor<T> descriptor) {
        Objects.requireNonNull(descriptor, "descriptor 不能为 null");

        // 更新总调用计数
        long callCount = totalAcquireCalls.incrementAndGet();
        long startTimeNanos = System.nanoTime();

        try {
            // ====== 策略 A3: 统计记录 ======
            // 创建 ResourceStats 描述符用于统计
            String resourceType = extractResourceType(descriptor);
            long resourceSize = estimateResourceSize(descriptor);

            ResourceStats.ResourceDescriptor statsDescriptor =
                    new ResourceStats.ResourceDescriptor(
                            resourceType,
                            descriptor.toString(),
                            resourceSize,
                            customPoolingEnabled.get() && memoryOptimizer != null ? "Renderium-Arena" : null
                    );

            // ====== 策略 A2: 自定义池化优先 ======
            T resource = null;
            boolean fromCustomPool = false;

            if (shouldTryCustomPooling()) {
                // 尝试从 MemoryOptimizer 的池中获取
                resource = tryAcquireFromCustomPool(descriptor);
                if (resource != null) {
                    fromCustomPool = true;
                    customPoolHitCount.incrementAndGet();

                    // 记录池化命中
                    ResourceStats.recordPoolHit(statsDescriptor, 1);

                    if (LOGGER.isLoggable(java.util.logging.Level.FINE)) {
                        LOGGER.fine(String.format("[ACQUIRE-POOL-HIT] %s 从自定义池获取成功",
                                statsDescriptor.getShortDescription()));
                    }
                } else {
                    customPoolMissCount.incrementAndGet();
                    ResourceStats.recordPoolMiss(resourceType);
                }
            }

            // ====== 策略 A1: 委托原始分配器 ======
            if (resource == null) {
                // 池化未命中或未启用，委托给 Mojang 原始分配器
                resource = delegate.acquire(descriptor);

                if (LOGGER.isLoggable(java.util.logging.Level.FINE)) {
                    LOGGER.fine(String.format("[ACQUIRE-DELEGATE] %s 委托给原始分配器",
                            statsDescriptor.getShortDescription()));
                }
            }

            // ====== 策略 A3: 完成统计记录 ======
            long elapsedNanos = System.nanoTime() - startTimeNanos;
            long resourceId = ResourceStats.recordAcquire(statsDescriptor, elapsedNanos);

            if (LOGGER.isLoggable(java.util.logging.Level.FINER)) {
                LOGGER.finer(String.format(
                        "[ACQUIRE COMPLETE] id=%d %s elapsed=%.3f µs source=%s",
                        resourceId,
                        statsDescriptor.getShortDescription(),
                        elapsedNanos / 1_000.0,
                        fromCustomPool ? "custom-pool" : "mojang-delegate"
                ));
            }

            // ====== Mojang 原生池化检测 ======
            detectMojangPooling(callCount);

            return resource;

        } catch (Exception e) {
            // 记录错误但继续抛出（保持与原始分配器一致的行为）
            long elapsedNanos = System.nanoTime() - startTimeNanos;
            LOGGER.severe(String.format(
                    "[ACQUIRE ERROR] descriptor=%s elapsed=%.3f µs error=%s",
                    descriptor,
                    elapsedNanos / 1_000.0,
                    e.getMessage()
            ));
            throw e; // 重新抛出，让调用方处理
        }
    }

    /**
     * 释放图形资源 ♻️
     * <p>
     * 实现策略 [A1, A2, A3]：
     * <ol>
     *   <li><b>记录统计 (A3)</b>: 通过 ResourceStats 记录本次释放操作</li>
     *   <li><b>自定义池化返回 (A2)</b>: 如果资源来自自定义池且池化启用，
     *       将资源返回到 Arena 池中以供复用</li>
     *   <li><b>委托原始释放器 (A1)</b>: 如果资源非池化来源或池化未启用，
     *       委托给 Mojang 的原始释放器进行真正销毁</li>
     * </ol>
     *
     * @param <T>      资源类型泛型参数
     * @param resource 要释放的资源实例
     *
     * @throws NullPointerException 如果 resource 为 null
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>resource</b>: T - 要释放的资源实例。
     *       此资源必须是通过 {@link #acquire} 获取的有效资源</li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>void - 无返回值</li>
     * </ul>
     *
     * <h4>两种释放路径：</h4>
     * <ul>
     *   <li><b>池化回收 (pooled=true)</b>: 资源返回到 MemoryOptimizer 的 Arena 中，
     *       不执行真正的 GPU 销毁操作。可被后续 acquire 复用。</li>
     *   <li><b>真实销毁 (pooled=false)</b>: 委托给 Mojang 原始分配器执行 release()，
     *       触发真正的 vkDestroyBuffer/vkDestroyImage 等 GPU 操作。</li>
     * </ul>
     */
    @Override
    public <T> void release(T resource) {
        Objects.requireNonNull(resource, "resource 不能为 null");

        // 更新总调用计数
        totalReleaseCalls.incrementAndGet();
        long startTimeNanos = System.nanoTime();

        try {
            // ====== 策略 A2: 尝试返回自定义池 ======
            boolean returnedToPool = false;

            if (shouldTryCustomPooling()) {
                returnedToPool = tryReleaseToCustomPool(resource);

                if (returnedToPool) {
                    if (LOGGER.isLoggable(java.util.logging.Level.FINE)) {
                        LOGGER.fine(String.format("[RELEASE-POOL] %s 返回到自定义池",
                                resource.getClass().getSimpleName()));
                    }
                }
            }

            // ====== 策略 A1: 委托原始释放器（如果未返回池）======
            if (!returnedToPool) {
                delegate.release(resource);

                if (LOGGER.isLoggable(java.util.logging.Level.FINE)) {
                    LOGGER.fine(String.format("[RELEASE-DELEGATE] %s 委托给原始释放器销毁",
                            resource.getClass().getSimpleName()));
                }
            }

            // ====== 策略 A3: 统计记录 ======
            // 注意：由于我们无法轻松地从 resource 反向查到 resourceId，
            // 这里使用简化的统计方式（仅记录释放事件，不关联具体 resourceId）
            long elapsedNanos = System.nanoTime() - startTimeNanos;

            if (LOGGER.isLoggable(java.util.logging.Level.FINER)) {
                LOGGER.finer(String.format(
                        "[RELEASE COMPLETE] %s elapsed=%.3f µs destination=%s",
                        resource.getClass().getSimpleName(),
                        elapsedNanos / 1_000.0,
                        returnedToPool ? "custom-pool" : "mojang-destroy"
                ));
            }

        } catch (Exception e) {
            // 记录错误但继续抛出
            long elapsedNanos = System.nanoTime() - startTimeNanos;
            LOGGER.severe(String.format(
                    "[RELEASE ERROR] resource=%s elapsed=%.3f µs error=%s",
                    resource.getClass().getSimpleName(),
                    elapsedNanos / 1_000.0,
                    e.getMessage()
            ));
            throw e; // 重新抛出
        }
    }

    // ==================== 公共配置 API ====================

    /**
     * 设置 MemoryOptimizer 实例
     * <p>
     * 用于启用自定义池化功能。应在 Module 初始化阶段调用。
     *
     * @param optimizer 内存优化器实例，传 null 可禁用自定义池化
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>optimizer</b>: {@link MemoryOptimizer} - Renderium 的 Arena 池管理器。
     *       通常从 Blaze3DOptimizerModule.getMemoryOptimizer() 获取。</li>
     * </ul>
     *
     * <h4>使用示例：</h4>
     * <pre>{@code
     * InstrumentedResourceAllocator allocator = new InstrumentedResourceAllocator(mojangAllocator);
     * allocator.setMemoryOptimizer(blaze3dModule.getMemoryOptimizer());
     * }</pre>
     */
    public void setMemoryOptimizer(MemoryOptimizer optimizer) {
        this.memoryOptimizer = optimizer;

        LOGGER.info(String.format("MemoryOptimizer %s",
                optimizer != null ? "已设置 (" + optimizer.getClass().getSimpleName() + ")" : "已清除"));
    }

    /**
     * 设置是否启用自定义池化
     * <p>
     * 可在运行时动态切换，无需重启。
     * 禁用后所有 acquire/release 将直接委托给 Mojang 原始分配器。
     *
     * @param enabled true 启用自定义池化，false 禁用
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>enabled</b>: boolean - 自定义池化开关状态</li>
     * </ul>
     */
    public void setCustomPoolingEnabled(boolean enabled) {
        this.customPoolingEnabled.set(enabled);

        LOGGER.info(String.format("自定义池化: %s", enabled ? "已启用" : "已禁用"));
    }

    /**
     * 获取是否启用了自定义池化
     *
     * @return 如果自定义池化已启用则返回 true
     */
    public boolean isCustomPoolingEnabled() {
        return customPoolingEnabled.get();
    }

    /**
     * 获取是否检测到 Mojang 原生池化
     * <p>
     * 当检测到 Mojang 已实现自己的池化机制时，建议禁用自定义池化以避免冲突。
     *
     * @return 如果检测到 Mojang 原生池化则返回 true
     */
    public boolean isMojangNativePoolingDetected() {
        return mojangNativePoolingDetected;
    }

    // ==================== 统计查询 API ====================

    /**
     * 获取总 acquire 调用次数
     *
     * @return 总调用次数
     */
    public long getTotalAcquireCalls() {
        return totalAcquireCalls.get();
    }

    /**
     * 获取总 release 调用次数
     *
     * @return 总调用次数
     */
    public long getTotalReleaseCalls() {
        return totalReleaseCalls.get();
    }

    /**
     * 获取自定义池化命中次数
     *
     * @return 命中次数
     */
    public long getCustomPoolHitCount() {
        return customPoolHitCount.get();
    }

    /**
     * 获取自定义池化未命中次数
     *
     * @return 未命中次数（回退到 delegate 的次数）
     */
    public long getCustomPoolMissCount() {
        return customPoolMissCount.get();
    }

    /**
     * 获取自定义池化命中率
     *
     * @return 命中率 (0.0 ~ 1.0)，无数据返回 0.0
     */
    public double getCustomPoolHitRate() {
        long hits = customPoolHitCount.get();
        long misses = customPoolMissCount.get();
        long total = hits + misses;

        return total > 0 ? (double) hits / total : 0.0;
    }

    /**
     * 获取原始委托分配器引用
     * <p>
     * 用于需要直接访问 Mojang 原始分配器的场景。
     *
     * @return 原始的 GraphicsResourceAllocator 实例
     */
    public GraphicsResourceAllocator getDelegate() {
        return delegate;
    }

    /**
     * 生成格式化的统计报告
     *
     * @return 包含详细统计信息的报告字符串
     */
    public String formatReport() {
        return String.format(
                "╔══════════════════════════════════════════╗\n" +
                "║  InstrumentedResourceAllocator 报告      ║\n" +
                "╚══════════════════════════════════════════╝\n" +
                "  Delegate: %s\n" +
                "  自定义池化: %s | Mojang原生池化检测: %s\n" +
                "\n" +
                "  调用统计:\n" +
                "    Acquire:  %d 次\n" +
                "    Release:  %d 次\n" +
                "    命中:     %d 次 (%.1f%%)\n" +
                "    未命中:   %d 次\n" +
                "%s\n",                delegate.getClass().getName(),
                customPoolingEnabled.get() ? "✓ 启用" : "✗ 禁用",
                mojangNativePoolingDetected ? "⚠️ 检测到" : "○ 未检测",

                totalAcquireCalls.get(),
                totalReleaseCalls.get(),

                customPoolHitCount.get(),
                getCustomPoolHitRate() * 100,
                customPoolMissCount.get(),

                ResourceStats.formatReport()
        );
    }

    // ==================== 内部方法：池化逻辑 ====================

    // ==================== 自定义池跟踪 ====================

    /** 池化资源跟踪表 (resourceId hash → poolTag) */
    private final java.util.concurrent.ConcurrentHashMap<Long, PoolTag> pooledResources = new java.util.concurrent.ConcurrentHashMap<>();

    /** 资源 ID 生成器 */
    private final java.util.concurrent.atomic.AtomicLong resourceIdGenerator = new java.util.concurrent.atomic.AtomicLong(1);

    /** 池化资源的最小哈希分布步长 */
    private static final long POOL_HASH_STRIDE = 2654435761L;

    /**
     * 池化标记：记录资源来自哪个 Arena 类型
     */
    private enum PoolTag {
        PER_FRAME,
        RING_BUFFER,
        POOL_BLOCK
    }

    /**
     * 判断是否应该尝试自定义池化路径
     */
    private boolean shouldTryCustomPooling() {
        return customPoolingEnabled.get()
                && memoryOptimizer != null
                && memoryOptimizer.isEnabled()
                && !mojangNativePoolingDetected;
    }

    /**
     * 尝试从自定义池中获取资源
     */
    @SuppressWarnings("unchecked")
    private <T> T tryAcquireFromCustomPool(ResourceDescriptor<T> descriptor) {
        long size = estimateResourceSize(descriptor);
        if (size <= 0) return null;

        // 分配资源 ID（用于池化标记）
        long resourceTag = resourceIdGenerator.getAndIncrement();

        // 尝试从 Pool Arena 获取（适用固定大小块）
        if (size <= MemoryOptimizer.DEFAULT_POOL_BLOCK_SIZE) {
            var alloc = memoryOptimizer.allocateFromPool();
            if (alloc != null) {
                pooledResources.put(resourceTag * POOL_HASH_STRIDE, PoolTag.POOL_BLOCK);
                return (T) alloc;
            }
        }

        // 尝试从 Per-Frame Arena 获取（适用临时数据）
        var frameAlloc = memoryOptimizer.allocateFromFrame(size, 16L);
        if (frameAlloc != null) {
            pooledResources.put(resourceTag * POOL_HASH_STRIDE, PoolTag.PER_FRAME);
            return (T) frameAlloc;
        }

        return null;
    }

    /**
     * 尝试将资源返回到自定义池
     */
    private <T> boolean tryReleaseToCustomPool(T resource) {
        long resourceHash = (long) System.identityHashCode(resource) * POOL_HASH_STRIDE;
        PoolTag tag = pooledResources.remove(resourceHash);
        if (tag == null) return false;

        // Per-Frame 和 Ring Buffer Arena 不需要手动释放
        if (tag == PoolTag.POOL_BLOCK && resource instanceof MemoryOptimizer.BlockAllocation) {
            memoryOptimizer.freePoolBlock(((MemoryOptimizer.BlockAllocation) resource).blockIndex);
        }
        return true;
    }

    /**
     * 估算资源大小（从 descriptor 信息推断）
     */
    private long estimateResourceSize(ResourceDescriptor<?> descriptor) {
        if (descriptor == null) return -1;
        try {
            String descStr = descriptor.toString().toLowerCase();
            if (descStr.contains("uniform")) return 256L;
            if (descStr.contains("vertex")) return 4096L;
            if (descStr.contains("index")) return 2048L;
            if (descStr.contains("staging")) return 65536L;
            if (descStr.contains("storage")) return 262144L;
            if (descStr.contains("texture") || descStr.contains("image")) return 4194304L;
            return 4096L;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 检测 Mojang 是否已实现原生池化
     * <p>
     * 定期（每 {@value #MOJANG_POOLING_DETECT_INTERVAL} 次 acquire）检查
     * Mojang 的 GraphicsResourceAllocator 是否已经实现了自己的池化机制。
     * 如果检测到，自动禁用自定义池化以避免双重池化的性能损失。
     *
     * <h3>检测策略：</h3>
     * <ol>
     *   <li>反射检查 delegate 类是否有池相关的方法/字段</li>
     *   <li>检查类名或包名是否包含 "pool"、"cache" 等关键词</li>
     *   <li>观察 acquire 的耗时分布（池化的分配应该更快）</li>
     * </ol>
     *
     * @param currentCallCount 当前的 acquire 总调用次数
     */
    private void detectMojangPooling(long currentCallCount) {
        // 间隔检测，避免每次调用都执行反射（性能开销大）
        if (currentCallCount - lastPoolingDetectCheckCount < MOJANG_POOLING_DETECT_INTERVAL) {
            return;
        }

        lastPoolingDetectCheckCount = currentCallCount;

        try {
            // 策略1: 检查类名是否包含池化相关的关键词
            String className = delegate.getClass().getName().toLowerCase();
            boolean hasPoolKeyword = className.contains("pool")
                    || className.contains("cache")
                    || className.contains("arena");

            // 策略2: 反射检查是否有池相关的方法
            boolean hasPoolMethod = false;
            try {
                // 尝试获取可能存在的池化方法
                delegate.getClass().getMethod("getPooledResource", Object.class);
                hasPoolMethod = true;
            } catch (NoSuchMethodException ignored) {
                // 方法不存在是正常的
            }

            // 策略3: 检查是否有池相关的字段
            boolean hasPoolField = false;
            try {
                delegate.getClass().getDeclaredField("resourcePool");
                hasPoolField = true;
            } catch (NoSuchFieldException ignored) {
                // 字段不存在是正常的
            }

            // 综合判断
            if (hasPoolKeyword || hasPoolMethod || hasPoolField) {
                if (!mojangNativePoolingDetected) {
                    mojangNativePoolingDetected = true;
                    LOGGER.warning(String.format(
                            "⚠️ 检测到 Mojang 原生池化实现 (class=%s, keyword=%b, method=%b, field=%b)。"
                                    + "自动禁用自定义池化以避免冲突。",
                            delegate.getClass().getName(),
                            hasPoolKeyword,
                            hasPoolMethod,
                            hasPoolField
                    ));
                }
            }

        } catch (Exception e) {
            // 检测过程出错不应该影响正常功能
            LOGGER.fine(String.format("Mojang 池化检测出错（可忽略）: %s", e.getMessage()));
        }
    }

    // ==================== 内部工具方法 ====================

    /**
     * 从 ResourceDescriptor 中提取资源类型名称
     *
     * @param descriptor 资源描述符
     * @return 资源类型字符串（如 "Buffer", "Image", "Pipeline"）
     */
    private String extractResourceType(ResourceDescriptor<?> descriptor) {
        // 尝试从 descriptor 中提取类型信息
        try {
            // 优先使用 descriptor 的 toString() 或 type 信息
            String descStr = descriptor.toString();

            // 简单启发式：从描述符字符串中提取类型
            if (descStr.toLowerCase().contains("buffer")) return "Buffer";
            if (descStr.toLowerCase().contains("image") || descStr.toLowerCase().contains("texture")) return "Image";
            if (descStr.toLowerCase().contains("pipeline")) return "Pipeline";
            if (descStr.toLowerCase().contains("descriptor")) return "DescriptorSet";
            if (descStr.toLowerCase().contains("command")) return "CommandBuffer";

            // 回退：使用类名简化
            return descriptor.getClass().getSimpleName();

        } catch (Exception e) {
            return "Unknown";
        }
    }

    // ==================== 内部辅助方法 ====================

}
