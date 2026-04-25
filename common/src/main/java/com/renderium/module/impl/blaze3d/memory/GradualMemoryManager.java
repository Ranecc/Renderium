// Renderium - Blaze3D VMA 渐进式清理模块
// 渐进式内存管理器 - 多级预警，平滑处理

package com.renderium.module.impl.blaze3d.memory;

import java.util.logging.Logger;

/**
 * 渐进式内存管理器 (顶层入口)。
 * <p>
 * 多级预警系统，根据显存使用率自动选择清理策略，
 * 避免激进清理导致的 GC 压力和重加载卡顿。</p>
 *
 * <h2>四级预警水位线:</h2>
 * <pre>
 * ┌──────────┬────────┬─────────────────────────────────────┐
 * │ 级别     │ 水位   │ 行为                              │
 * ├──────────┼────────┼─────────────────────────────────────┤
 * │ NORMAL  │ &lt;75%  │ 正常运行                          │
 * │ WARNING │ 75-85% │ 软清理: 过期资源驱逐               │
 * │ SOFT     │ 85-92% │ 渐进清理: 10MB/帧, LRU 冷数据      │
 * │ HARD     │ 92-97% │ 加速清理: 30MB/帧, 扩大范围       │
 * │ CRITICAL│ ≥97%  │ 强制清理: 分批 + 最终 GC           │
 * └──────────┴────────┴─────────────────────────────────────┘
 * </pre>
 *
 * <h3>使用方式:</h3>
 * <pre>{@code
 * // 初始化 (在 Vulkan 设备就绪后)
 * GradualMemoryManager manager = GradualMemoryManager.getInstance();
 * manager.initialize();
 *
 * // 每帧调用 (在渲染循环末尾)
 * manager.updateMemoryPressure(); // 检查压力 + 执行清理
 *
 * // 可选: 手动触发状态报告
 * if (manager.getCurrentLevel() != MemoryPressureLevel.NORMAL) {
 *     LOGGER.info(manager.getStatusReport());
 * }
 * }</pre>
 *
 * <h3>集成点:</h3>
 * <ul>
 *   <li>在 {@code MemoryOptimizer} 的 {@code perFrameCleanup()} 中调用</li>
 *   <li>或作为独立定时任务运行</li>
 * </ul>
 *
 * @see MemoryPressureLevel 压力级别定义
 * @see ResourceTracker LRU 追踪器
 * @see GradualCleanupStrategy 渐进清理策略
 * @see SoftCleanupStrategy 软清理策略
 * @see EmergencyCleanupStrategy 紧急清理策略
 * @since 2.0.0
 */
public final class GradualMemoryManager {

    private static final Logger LOGGER = Logger.getLogger("Renderium-GradualMemMgr");

    /** 单例实例 */
    private static volatile GradualMemoryManager instance;

    /** 当前压力级别 */
    private volatile MemoryPressureLevel currentLevel = MemoryPressureLevel.NORMAL;

    /** 当前活跃的清理策略 */
    private volatile CleanupStrategy cleanupStrategy;

    /** 资源追踪器 */
    private final ResourceTracker resourceTracker;

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    /** 是否启用 */
    private volatile boolean enabled = true;

    /** 配置参数 */
    private final GradualMemoryConfig config;

    private GradualMemoryManager() {
        this.resourceTracker = ResourceTracker.getInstance();
        this.config = new GradualMemoryConfig();
    }

    public static synchronized GradualMemoryManager getInstance() {
        if (instance == null) {
            instance = new GradualMemoryManager();
        }
        return instance;
    }

    // ==================== 生命周期 ====================

    /**
     * 初始化渐进式内存管理器。
     * <p>应在 Vulkan 设备和 VMA 分配器就绪后调用。</p>
     */
    public void initialize() {
        if (initialized) return;

        LOGGER.info("初始化渐进式内存管理器...");
        LOGGER.info(String.format("  水位线: WARNING=%.0f%% SOFT=%.0f%% HARD=%.0f%% CRITICAL=%.0f%%",
                config.warningLevel * 100,
                config.softLimit * 100,
                config.hardLimit * 100,
                config.criticalLimit * 100));
        LOGGER.info(String.format("  清理速率: SOFT=%dMB/帧 HARD=%dMB/帧",
                config.softCleanupRate / (1024 * 1024),
                config.hardCleanupRate / (1024 * 1024)));

        initialized = true;
        enabled = true;
        currentLevel = MemoryPressureLevel.NORMAL;

        LOGGER.info("✓ GradualMemoryManager 初始化完成");
    }

    /**
     * 关闭管理器, 释放所有资源
     */
    public void shutdown() {
        enabled = false;
        AsyncResourceLoader loader = AsyncResourceLoader.getInstance();
        loader.shutdown();

        resourceTracker.clear();
        currentLevel = MemoryPressureLevel.NORMAL;
        cleanupStrategy = null;
        initialized = false;

        LOGGER.info("GradualMemoryManager 已关闭");
    }

    // ==================== 核心公共 API ====================

    /**
     * 每帧更新: 检查内存压力并执行对应清理。
     * <p>应在每帧渲染结束后调用。</p>
     */
    public void updateMemoryPressure() {
        if (!initialized || !enabled) return;

        try {
            // Step 1: 获取当前使用率
            float usageRatio = getCurrentUsageRatio();

            // Step 2: 判断新的压力级别
            MemoryPressureLevel newLevel = MemoryPressureLevel.fromUsageRatio(usageRatio);

            // Step 3: 如果级别变化, 切换策略
            if (newLevel != currentLevel) {
                onPressureLevelChanged(currentLevel, newLevel);
                currentLevel = newLevel;
            }

            // Step 4: 根据当前级别执行清理
            executeGradualCleanup();

        } catch (Exception e) {
            LOGGER.warning("内存压力更新异常: " + e.getMessage());
        }
    }

    /**
     * 手动触发一次完整清理 (忽略当前级别)
     *
     * @return 清理的资源数
     */
    public int forceCleanup() {
        if (!initialized) return 0;

        LOGGER.info("⚡ 手动触发强制清理...");
        int count = 0;

        try {
            // 使用 HARD 级别的策略执行一次完整清理
            GradualCleanupStrategy strategy =
                    new GradualCleanupStrategy(config.hardCleanupRate);
            strategy.execute();
            count = strategy.getCleanedItemsThisFrame();

        } catch (Exception e) {
            LOGGER.warning("强制清理异常: " + e.getMessage());
        }

        LOGGER.info("✓ 强制清理完成: " + count + " 个资源");
        return count;
    }

    // ==================== 查询 API ====================

    /** 获取当前压力级别 */
    public MemoryPressureLevel getCurrentLevel() { return currentLevel; }

    /** 是否处于正常状态 */
    public boolean isNormal() { return currentLevel == MemoryPressureLevel.NORMAL; }

    /** 是否需要关注 (WARNING 及以上) */
    public boolean needsAttention() {
        return currentLevel.ordinal() >= MemoryPressureLevel.WARNING.ordinal();
    }

    /** 是否处于紧急状态 */
    public boolean isCritical() { return currentLevel == MemoryPressureLevel.CRITICAL; }

    /** 获取资源追踪器引用 */
    public ResourceTracker getResourceTracker() { return resourceTracker; }

    /** 获取配置 */
    public GradualMemoryConfig getConfig() { return config; }

    /** 启用/禁用 */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }

    /**
     * 获取详细状态报告 (用于调试/监控)
     */
    public String getStatusReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Gradual Memory Manager Status ===\n");
        sb.append(String.format("Enabled: %s\n", enabled));
        sb.append(String.format("Initialized: %s\n", initialized));
        sb.append(String.format("Current Level: %s (%s)\n",
                currentLevel.name(), currentLevel.description()));
        sb.append(String.format("Active Strategy: %s\n",
                cleanupStrategy != null ? cleanupStrategy.getName() : "None"));
        sb.append(String.format("Usage Ratio: %.1f%%\n", getCurrentUsageRatio() * 100));

        sb.append("\n--- Resource Tracker ---\n");
        sb.append(resourceTracker.getStatusReport());

        // AsyncResourceLoader 统计
        AsyncResourceLoader loader = AsyncResourceLoader.getInstance();
        sb.append(String.format("--- Async Loader: cache=%d, paused=%s ---\n",
                loader.getCacheSize(), loader.isPaused()));

        return sb.toString();
    }

    // ==================== 内部实现 ====================

    /** 压力级别变化时的回调: 切换清理策略 */
    private void onPressureLevelChanged(MemoryPressureLevel oldLevel,
                                           MemoryPressureLevel newLevel) {

        LOGGER.info(String.format("📊 内存压力变化: %s → %s (usage: %.1f%%)",
                oldLevel.name(), newLevel.name(),
                getCurrentUsageRatio() * 100));

        switch (newLevel) {
            case WARNING -> {
                cleanupStrategy = new SoftCleanupStrategy();
                ((SoftCleanupStrategy) cleanupStrategy).setExpiredCacheThresholdMs(30_000);
            }
            case SOFT -> {
                cleanupStrategy = new GradualCleanupStrategy(config.softCleanupRate);
            }
            case HARD -> {
                cleanupStrategy = new GradualCleanupStrategy(config.hardCleanupRate);
                ((GradualCleanupStrategy) cleanupStrategy).setMaxItemsPerFrame(20);
            }
            case CRITICAL -> {
                cleanupStrategy = new EmergencyCleanupStrategy();
            }
            default -> {
                cleanupStrategy = null;
            }
        }
    }

    /** 执行当前级别的清理策略 */
    private void executeGradualCleanup() {
        if (cleanupStrategy == null) return;

        long startTime = System.nanoTime();
        cleanupStrategy.execute();
        long elapsed = System.nanoTime() - startTime;

        if (elapsed > 5_000_000L) { // 超过 5ms
            LOGGER.warning("清理耗时过长: " +
                    (elapsed / 1_000_000.0) + "ms (" +
                    cleanupStrategy.getName() + ")");
        }
    }

    /** 获取当前显存使用率 (占位实现) */
    private float getCurrentUsageRatio() {
        try {
            var budgetClass = Class.forName(
                    "com.renderium.module.impl.blaze3d.memory.VmaMemoryBudget");
            Object budget = null;
            for (var field : budgetClass.getDeclaredFields()) {
                if (field.getType().equals(budgetClass)) {
                    field.setAccessible(true);
                    budget = field.get(null);
                    break;
                }
            }
            if (budget != null) {
                var method = budget.getClass().getMethod("getUsageRatio");
                Object result = method.invoke(budget);
                if (result instanceof Number) {
                    return ((Number) result).floatValue();
                }
            }
        } catch (Exception ignored) {}

        // 占位值: 基于当前级别返回一个合理的模拟值
        return switch (currentLevel) {
            case NORMAL -> 0.65f;
            case WARNING -> 0.80f;
            case SOFT -> 0.88f;
            case HARD -> 0.94f;
            case CRITICAL -> 0.98f;
        };
    }

    // ==================== 内部配置类 ====================

    /**
     * 渐进式内存管理配置
     */
    public static class GradualMemoryConfig {

        /** 预警水位线 (默认 75%) */
        public float warningLevel = 0.75f;

        /** 软限制水位线 (默认 85%) */
        public float softLimit = 0.85f;

        /** 硬限制水位线 (default 92%) */
        public float hardLimit = 0.92f;

        /** 临界水位线 (默认 97%) */
        public float criticalLimit = 0.97f;

        /** 软限制时每帧清理量 (默认 10MB) */
        public long softCleanupRate = 10L * 1024 * 1024;

        /** 硬限制时每帧清理量 (默认 30MB) */
        public long hardCleanupRate = 30L * 1024 * 1024;

        /** 每帧最多清理资源数 (默认 10) */
        public int maxItemsPerFrame = 10;

        /** 热数据时间阈值 (默认 5000ms) */
        public long hotDataThresholdMs = 5000L;

        /** 高频访问次数阈值 (默认 100) */
        public int highFrequencyThreshold = 100;

        /** 预加载线程数 (默认 2) */
        public int preloadThreads = 2;

        /** 最大预加载缓存大小 (默认 128MB) */
        public long maxPreloadCacheSizeBytes = 128L * 1024 * 1024;

        /** 紧急清理批次数 (默认 5) */
        public int emergencyBatches = 5;
    }
}
