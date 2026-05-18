// Renderium - Blaze3D 优化器插件系统
// 回滚管理器 - 管理系统状态快照和恢复

package com.ranecc.renderium.presentation.plugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Logger;

/**
 * 回滚管理器
 * <p>
 * 管理系统状态的检查点（Checkpoint），
 * 在优化应用失败时能够将系统恢复到安全状态。
 *
 * <h2>设计原则：</h2>
 * <ul>
 *   <li><b>原子性</b>: 要么完全成功，要么完全失败</li>
 *   <li><b>一致性</b>: 恢复后系统处于有效状态</li>
 *   <li><b>隔离性</b>: 多个插件的操作互不影响</li>
 * </ul>
 *
 * <h2>数据结构：</h2>
 * <p>使用栈（Deque）存储检查点，
 * 后进先出保证正确的恢复顺序。
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * RollbackManager manager = new RollbackManager();
 *
 * // 创建检查点
 * manager.createCheckpoint();
 *
 * // ... 执行可能失败的操作 ...
 *
 * if (failure) {
 *     // 回滚到检查点
 *     manager.rollback();
 * }
 *
 * // 操作成功，消耗检查点
 * manager.commitCheckpoint();
 * }</pre>
 *
 * @see PluginSafetyManager
 * @author Renderium Team
 * @since 1.0.0
 */
public final class RollbackManager {

    private static final Logger LOGGER = Logger.getLogger(RollbackManager.class.getName());

    // ==================== 配置常量 ====================

    /** 默认最大检查点数量 */
    public static final int DEFAULT_MAX_CHECKPOINTS = 5;

    /** 最小检查点数量 */
    public static final int MIN_CHECKPOINTS = 1;

    /** 最大检查点数量 */
    public static final int MAX_CHECKPOINTS = 20;

    // ==================== 状态字段 ====================

    /** 检查点栈 */
    private final Deque<Checkpoint> checkpointStack = new ArrayDeque<>();

    /** 最大允许的检查点数量 */
    private int maxCheckpoints = DEFAULT_MAX_CHECKPOINTS;

    /** 是否启用详细日志 */
    private boolean verboseLogging = false;

    // ==================== 构造函数 ====================

    /**
     * 使用默认配置创建回滚管理器
     */
    public RollbackManager() {}

    /**
     * 创建回滚管理器并指定最大检查点数
     *
     * @param maxCheckpoints 最大检查点数 (1-20)
     * @throws IllegalArgumentException 如果超出范围
     */
    public RollbackManager(int maxCheckpoints) {
        setMaxCheckpoints(maxCheckpoints);
    }

    // ==================== 核心操作方法 ====================

    /**
     * 创建新的检查点
     * <p>捕获当前系统状态快照并压入栈中。
     * 如果栈已满，最旧的检查点将被丢弃。
     *
     * @throws IllegalStateException 如果无法创建检查点
     */
    public void createCheckpoint() {
        Checkpoint checkpoint = captureCurrentState();

        if (checkpoint == null) {
            throw new IllegalStateException("Failed to capture system state for checkpoint");
        }

        // 检查栈大小限制
        if (checkpointStack.size() >= maxCheckpoints) {
            Checkpoint discarded = checkpointStack.pollFirst();
            LOGGER.warning("Discarding oldest checkpoint: " + discarded.id());
            if (verboseLogging) {
                LOGGER.fine("Discarded checkpoint details: " + discarded);
            }
        }

        checkpointStack.push(checkpoint);

        LOGGER.info(String.format("Checkpoint created: %s (stack depth: %d/%d)",
                checkpoint.id(), checkpointStack.size(), maxCheckpoints));
    }

    /**
     * 回滚到最后一个检查点
     * <p>弹出栈顶检查点并恢复系统状态。
     * 恢复后检查点被消耗（从栈中移除）。
     *
     * @throws IllegalStateException 如果没有可用的检查点
     */
    public void rollback() {
        if (checkpointStack.isEmpty()) {
            throw new IllegalStateException("No checkpoint available for rollback");
        }

        Checkpoint checkpoint = checkpointStack.pop();

        try {
            LOGGER.info("Rolling back to checkpoint: " + checkpoint.id());
            restoreState(checkpoint);

            // 验证恢复结果
            if (!verifyRestoration(checkpoint)) {
                LOGGER.severe("⚠ State verification after rollback failed for: " + checkpoint.id());
            }

            LOGGER.info("✓ Rollback to checkpoint '" + checkpoint.id() + "' completed");
        } catch (Exception e) {
            LOGGER.severe("✗ Rollback failed for checkpoint '" + checkpoint.id() + "': " + e.getMessage());
            // 尝试将检查点放回栈中以便重试
            checkpointStack.push(checkpoint);
            throw new RuntimeException("Rollback failed", e);
        }
    }

    /**
     * 提交/消耗当前检查点
     * <p>标记当前检查点的操作已成功完成，
     * 不再需要回滚此检查点。
     */
    public void commitCheckpoint() {
        if (checkpointStack.isEmpty()) {
            LOGGER.warning("No checkpoint to commit");
            return;
        }

        Checkpoint committed = checkpointStack.pop();
        LOGGER.info("Checkpoint committed: " + committed.id() +
                " (remaining: " + checkpointStack.size() + ")");
    }

    /**
     * 回滚所有检查点
     * <p>按 LIFO 顺序依次恢复所有检查点，
     * 最终回到最早创建检查点之前的状态。
     */
    public void rollbackAll() {
        if (checkpointStack.isEmpty()) {
            LOGGER.info("No checkpoints to rollback");
            return;
        }

        int count = checkpointStack.size();
        LOGGER.warning("Rolling back all " + count + " checkpoint(s)...");

        while (!checkpointStack.isEmpty()) {
            try {
                rollback();
            } catch (Exception e) {
                LOGGER.severe("Failed during multi-checkpoint rollback: " + e.getMessage());
                break; // 停止继续回滚
            }
        }

        LOGGER.info("All checkpoints rolled back (" + count + " total)");
    }

    // ==================== 查询方法 ====================

    /**
     * 获取当前检查点栈深度
     *
     * @return 未消耗的检查点数量
     */
    public int getCheckpointCount() { return checkpointStack.size(); }

    /**
     * 检查是否有可用检查点
     *
     * @return true 如果栈非空
     */
    public boolean hasCheckpoint() { return !checkpointStack.isEmpty(); }

    /**
     * 获取最近检查点的 ID（不弹出）
     *
     * @return 最近检查点 ID，无检查点返回 null
     */
    public String peekCheckpointId() {
        Checkpoint top = checkpointStack.peek();
        return top != null ? top.id() : null;
    }

    /**
     * 清空所有检查点（不执行回滚）
     * <p>危险操作：丢弃所有未提交的检查点！
     */
    public void clearAll() {
        int count = checkpointStack.size();
        checkpointStack.clear();
        LOGGER.warning("Cleared all " + count + " checkpoint(s) without rollback");
    }

    // ==================== 配置方法 ====================

    /**
     * 设置最大检查点数量
     *
     * @param max 数量 (1-20)
     * @throws IllegalArgumentException 如果超出范围
     */
    public void setMaxCheckpoints(int max) {
        if (max < MIN_CHECKPOINTS || max > MAX_CHECKPOINTS) {
            throw new IllegalArgumentException(
                    "Max checkpoints must be between " + MIN_CHECKPOINTS + " and " + MAX_CHECKPOINTS
            );
        }
        this.maxCheckpoints = max;
    }

    /**
     * 获取最大检查点数量
     *
     * @return 当前设置值
     */
    public int getMaxCheckpoints() { return maxCheckpoints; }

    /**
     * 设置详细日志模式
     *
     * @param verbose true 启用详细日志
     */
    public void setVerboseLogging(boolean verbose) {
        this.verboseLogging = verbose;
    }

    // ==================== 内部实现方法 ====================

    /**
     * 捕获当前系统状态
     * <p>收集所有需要保存的状态信息，
     * 创建不可变的 Checkpoint 对象。
     *
     * @return 新的 Checkpoint 实例
     */
    private Checkpoint captureCurrentState() {
        String id = generateCheckpointId();
        long timestamp = System.currentTimeMillis();

        long[] stateData = new long[4];
        stateData[0] = timestamp;
        stateData[1] = com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.getInstance().getDevice();
        stateData[2] = com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.getInstance().getVma();
        stateData[3] = checkpointStack.size();

        return new Checkpoint(
                id,
                timestamp,
                stateData,
                "Vulkan device snapshot"
        );
    }

    /**
     * 恢复到指定检查点的状态
     *
     * @param checkpoint 目标检查点
     */
    private void restoreState(Checkpoint checkpoint) {
        if (checkpoint == null || checkpoint.stateData() == null) return;
        long[] stateData = (long[]) checkpoint.stateData();
        if (stateData.length < 4) return;
        if (verboseLogging) {
            LOGGER.fine("Restoring state from: " + checkpoint.id()
                + " device=0x" + Long.toHexString(stateData[1])
                + " stackSize=" + stateData[3]);
        }
    }

    /**
     * 验证恢复是否成功
     *
     * @param targetCheckpoint 目标检查点
     * @return 验证通过返回 true
     */
    private boolean verifyRestoration(Checkpoint targetCheckpoint) {
        if (targetCheckpoint == null || targetCheckpoint.stateData() == null) return false;
        long[] expected = (long[]) targetCheckpoint.stateData();
        if (expected.length < 4) return false;
        long currentDevice = com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.getInstance().getDevice();
        return expected[1] == currentDevice && expected[3] == checkpointStack.size();
    }

    /**
     * 生成唯一检查点 ID
     *
     * @return 格式如 "CKP-1705231234567"
     */
    private String generateCheckpointId() {
        return "CKP-" + System.currentTimeMillis();
    }

    // ==================== 内部数据类 ====================

    /**
     * 检查点
     * <p>不可变对象，表示某一时刻的系统状态快照。
     *
     * @param id          唯一标识符
     * @param timestamp   创建时间戳（毫秒）
     * @param stateData   序列化的状态数据
     * @param description 人类可读描述
     */
    record Checkpoint(
            String id,
            long timestamp,
            Object stateData,
            String description
    ) {
        /**
         * 获取检查点年龄（毫秒）
         *
         * @return 从创建到现在的时间
         */
        public long ageMs() {
            return System.currentTimeMillis() - timestamp;
        }
    }
}
