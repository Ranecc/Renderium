// Renderium - Blaze3D 优化器插件系统
// 插件安全管理器 - 确保插件失败不影响核心功能

package com.ranecc.renderium.presentation.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * 插件安全管理器
 * <p>
 * 确保 Blaze3D 优化插件的加载和应用不会影响核心模组稳定性。
 * 是整个插件系统的"守门员"，在关键节点进行安全验证。
 *
 * <h2>核心职责：</h2>
 * <ul>
 *   <li><b>预飞检查</b>: 加载前验证环境和依赖</li>
 *   <li><b>检查点管理</b>: 创建系统状态快照</li>
 *   <li><b>稳定性验证</b>: 应用后确认系统正常</li>
 *   <li><b>回滚协调</b>: 失败时恢复到安全状态</li>
 * </ul>
 *
 * <h2>工作流程：</h2>
 * <pre>
 * [插件请求加载]
 *      ↓
 * preFlightCheck() → 通过/拒绝
 *      ↓ (通过)
 * createCheckpoint() → 保存当前状态
 *      ↓
 * [应用优化]
 *      ↓
 * verifyStability() → 稳定/不稳定
 *      ↓ (不稳定)
 * rollback() → 恢复到检查点
 * </pre>
 *
 * @see SafetyCheck
 * @see RollbackManager
 * @author Renderium Team
 * @since 1.0.0
 */
public final class PluginSafetyManager {

    private static final Logger LOGGER = Logger.getLogger(PluginSafetyManager.class.getName());

    // ==================== 配置常量 ====================

    /** 默认稳定性验证帧数 */
    public static final int DEFAULT_STABILITY_FRAMES = 100;

    /** 允许的最大崩溃率阈值 (0.0 - 1.0) */
    public static final float MAX_CRASH_RATE = 0.01f;

    /** 允许的最大帧时间波动 (0.0 - 1.0) */
    public static final float MAX_FRAME_TIME_VARIANCE = 0.3f;

    /** 允许的最大内存增长率 (MB/帧) */
    public static final float MAX_MEMORY_GROWTH_RATE = 1.0f;

    // ==================== 状态字段 ====================

    /** 已注册的安全检查列表 */
    private final List<SafetyCheck> safetyChecks = new ArrayList<>();

    /** 回滚管理器引用 */
    private final RollbackManager rollbackManager;

    /** 当前是否处于检查点保护中 */
    private volatile boolean checkpointActive = false;

    /** 是否启用严格模式（所有 WARNING 也视为失败） */
    private boolean strictMode = false;

    // ==================== 构造函数 ====================

    /**
     * 创建安全管理器
     *
     * @param rollbackManager 回滚管理器实例
     */
    public PluginSafetyManager(RollbackManager rollbackManager) {
        this.rollbackManager = Objects.requireNonNull(rollbackManager, "RollbackManager cannot be null");
        registerDefaultChecks();
    }

    /**
     * 使用默认回滚管理器创建安全管理器
     */
    public PluginSafetyManager() {
        this(new RollbackManager());
    }

    // ==================== 检查注册方法 ====================

    /**
     * 注册默认的安全检查项
     * <p>包括：
     * <ul>
     *   <li>Mixin 完整性检查</li>
     *   <li>渲染循环状态检查</li>
     *   <li>内存泄漏检测</li>
     *   <li>崩溃率评估</li>
     * </ul>
     */
    private void registerDefaultChecks() {
        // TODO: 实现具体的 SafetyCheck 实现
        // safetyChecks.add(new MixinIntegrityCheck());
        // safetyChecks.add(new RenderLoopCheck());
        // safetyChecks.add(new MemoryLeakCheck());
        // safetyChecks.add(new CrashRateCheck());

        LOGGER.fine("Default safety checks registered: " + safetyChecks.size());
    }

    /**
     * 添加自定义安全检查
     *
     * @param check 检查项实例
     * @return this，支持链式调用
     */
    public PluginSafetyManager addCheck(SafetyCheck check) {
        Objects.requireNonNull(check, "Safety check cannot be null");
        safetyChecks.add(check);
        return this;
    }

    /**
     * 移除指定名称的检查项
     *
     * @param name 检查项名称
     * @return 是否找到并移除
     */
    public boolean removeCheck(String name) {
        return safetyChecks.removeIf(c -> c.getName().equals(name));
    }

    /**
     * 清除所有已注册的检查项
     */
    public void clearChecks() {
        safetyChecks.clear();
    }

    // ==================== 核心安全方法 ====================

    /**
     * 执行预飞检查
     * <p>在插件应用优化之前进行全方位安全验证。
     * 所有已注册的检查项都必须通过（或达到配置的严重性级别）。
     *
     * @param plugin 待检查的插件
     * @return 全部通过返回 true
     */
    public boolean preFlightCheck(Blaze3DOptimizerPlugin plugin) {
        Objects.requireNonNull(plugin, "Plugin cannot be null");

        LOGGER.info("Running pre-flight checks for: " + plugin.getMetadata().id());
        LOGGER.info("Registered checks: " + safetyChecks.size());

        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (SafetyCheck check : safetyChecks) {
            try {
                if (!check.check(plugin)) {
                    SafetyCheck.Severity severity = check.getSeverity();

                    String message = String.format("✗ [%s] %s: %s",
                            severity.name(),
                            check.getName(),
                            check.getFailureAdvice()
                    );

                    if (severity == SafetyCheck.Severity.WARNING && !strictMode) {
                        warnings.add(message);
                        LOGGER.warning(message);
                    } else {
                        failures.add(message);
                        LOGGER.severe(message);
                    }
                } else {
                    LOGGER.fine("✓ Check passed: " + check.getName());
                }
            } catch (Exception e) {
                String error = "✗ [ERROR] Check '" + check.getName() + "' threw exception: " + e.getMessage();
                failures.add(error);
                LOGGER.severe(error);
            }
        }

        // 输出结果摘要
        if (!warnings.isEmpty()) {
            LOGGER.warning("Pre-flight warnings (" + warnings.size() + "): " + String.join("; ", warnings));
        }

        if (!failures.isEmpty()) {
            LOGGER.severe("Pre-flight FAILED with " + failures.size() + " failure(s)");
            for (String f : failures) {
                LOGGER.severe("  → " + f);
            }
            return false;
        }

        LOGGER.info("✓ Pre-flight checks passed successfully");
        return true;
    }

    /**
     * 创建安全检查点
     * <p>保存当前系统状态快照，
     * 以便在后续操作失败时能够完全恢复。
     *
     * @throws IllegalStateException 如果已有活跃检查点
     */
    public void createCheckpoint() {
        if (checkpointActive) {
            LOGGER.warning("Checkpoint already active, skipping");
            return;
        }

        rollbackManager.createCheckpoint();
        checkpointActive = true;

        LOGGER.info("Safety checkpoint created");
    }

    /**
     * 验证系统稳定性
     * <p>在优化应用后运行一段时间，
     * 收集性能指标并判断系统是否稳定。
     *
     * @return 系统稳定返回 true
     */
    public boolean verifyStability() {
        LOGGER.info("Verifying system stability...");

        StabilityMetrics metrics = collectStabilityMetrics(DEFAULT_STABILITY_FRAMES);

        // 评估各项指标
        boolean crashRateOk = metrics.crashRate() <= MAX_CRASH_RATE;
        boolean frameTimeOk = metrics.frameTimeVariance() <= MAX_FRAME_TIME_VARIANCE;
        boolean memoryOk = metrics.memoryGrowthRate() <= MAX_MEMORY_GROWTH_RATE;

        LOGGER.info(String.format(
                "Stability metrics: crash=%.3f (limit=%.3f), frameVar=%.3f (limit=%.3f), memGrowth=%.2f (limit=%.2f)",
                metrics.crashRate(), MAX_CRASH_RATE,
                metrics.frameTimeVariance(), MAX_FRAME_TIME_VARIANCE,
                metrics.memoryGrowthRate(), MAX_MEMORY_GROWTH_RATE
        ));

        if (!crashRateOk) {
            LOGGER.warning("Stability check failed: crash rate too high");
        }
        if (!frameTimeOk) {
            LOGGER.warning("Stability check failed: frame time variance too high");
        }
        if (!memoryOk) {
            LOGGER.warning("Stability check failed: memory growth rate too high");
        }

        boolean stable = crashRateOk && frameTimeOk && memoryOk;

        if (stable) {
            LOGGER.info("✓ System stability verified");
        } else {
            LOGGER.severe("✗ System stability verification FAILED");
        }

        return stable;
    }

    /**
     * 执行回滚操作
     * <p>将系统恢复到最后一个检查点的状态。
     * 如果没有检查点则不执行任何操作。
     */
    public void rollback() {
        if (!checkpointActive) {
            LOGGER.warning("Rollback requested but no active checkpoint");
            return;
        }

        try {
            LOGGER.warning("Executing safety rollback...");
            rollbackManager.rollback();
            checkpointActive = false;
            LOGGER.info("✓ Rollback completed successfully");
        } catch (Exception e) {
            LOGGER.severe("Rollback failed: " + e.getMessage());
        }
    }

    // ==================== 配置方法 ====================

    /**
     * 设置严格模式
     * <p>严格模式下，WARNING 级别的检查失败也会导致预检不通过。
     *
     * @param strict true 启用严格模式
     */
    public void setStrictMode(boolean strict) {
        this.strictMode = strict;
        LOGGER.info("Strict mode: " + strict);
    }

    /**
     * 检查是否有活跃的检查点
     *
     * @return true 如果存在未消耗的检查点
     */
    public boolean isCheckpointActive() { return checkpointActive; }

    /**
     * 获取已注册的检查项数量
     *
     * @return 数量
     */
    public int getCheckCount() { return safetyChecks.size(); }

    /**
     * 获取所有已注册检查项的不可变视图
     *
     * @return 检查项列表
     */
    public List<SafetyCheck> getChecks() {
        return Collections.unmodifiableList(safetyChecks);
    }

    // ==================== 内部方法 ====================

    /**
     * 收集稳定性指标
     *
     * @param frameCount 采样帧数
     * @return 稳定性指标对象
     */
    private StabilityMetrics collectStabilityMetrics(int frameCount) {
        // TODO: 实现实际的指标收集逻辑
        // 包括：
        // 1. 监控帧时间变化
        // 2. 检测内存使用趋势
        // 3. 统计异常/崩溃事件
        // 4. 计算 GPU 利用率等

        return new StabilityMetrics(
                0f,           // crashRate
                0f,           // frameTimeVariance
                0f,           // memoryGrowthRate
                frameCount    // sampleCount
        );
    }

    // ==================== 内部数据类 ====================

    /**
     * 稳定性指标
     * @record 封装系统稳定性相关数据
     */
    record StabilityMetrics(
            /** 崩溃率 (0.0 - 1.0) */
            float crashRate,
            /** 帧时间波动系数 (0.0 - 1.0+) */
            float frameTimeVariance,
            /** 内存增长速率 (MB/帧) */
            float memoryGrowthRate,
            /** 采样帧数 */
            int sampleCount
    ) {}
}
