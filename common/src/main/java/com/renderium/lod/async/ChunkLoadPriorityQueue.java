// Renderium v6 Phase 3: 异步区块加载器系统
// ChunkLoadPriorityQueue.java - 区块加载优先级队列
// 功能: 基于多因子的线程安全优先级队列，支持动态优先级更新

package com.renderium.lod.async;

import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 区块加载优先级队列。
 *
 * <p>为异步区块加载器提供基于多因子（距离、视角、陈旧度）的智能排序，
 * 确保最重要的区块优先被加载。</p>
 *
 * <h2>优先级计算公式：</h2>
 * <pre>
 * priority = distanceWeight × (distance / maxDistance)
 *          + angleWeight × (angleFromCenter / maxAngle)
 *          + staleWeight × (ageInSeconds / maxAge)
 *
 * 其中：
 *   - distance 越小 → 优先级越高（数值越小越优先）
 *   - angle 越接近相机朝向 → 优先级越高
 *   - age 越大（越陈旧）→ 优先级越高（需要刷新）
 * </pre>
 *
 * <h2>数据结构：</h2>
 * <pre>
 * ┌─────────────────────────────────────┐
 * │       PriorityBlockingQueue         │
 * │  ┌─────────────────────────────┐    │
 * │  │  LoadTask (priority=0)  ←───┼────┤ 最高优先级
 * │  │  LoadTask (priority=1)      │    │
 * │  │  LoadTask (priority=2)      │    │
 * │  │  ...                        │    │
 * │  │  LoadTask (priority=N)  ────┼────┤ 最低优先级
 * │  └─────────────────────────────┘    │
 * └─────────────────────────────────────┘
 *           ↑ 并发安全的 take()/poll()
 * </pre>
 *
 * <h3>动态更新机制：</h3>
 * <p>当相机移动时，需要重新计算所有任务的优先级。
 * 由于 {@link PriorityBlockingQueue} 不支持原地更新，
 * 采用"移除-修改-重插"策略：</p>
 * <ol>
 *   <li>从队列中移除任务</li>
 *   <li>更新任务的优先级字段</li>
 *   <li>将任务重新插入队列（自动排序）</li>
 * </ol>
 *
 * @see AsyncChunkLoader 主使用者
 * @since 6.0.0
 */
public final class ChunkLoadPriorityQueue {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(ChunkLoadPriorityQueue.class.getName());

    /** 默认队列容量 */
    public static final int DEFAULT_CAPACITY = 10000;

    /** 默认最大距离（用于归一化），单位：区块 */
    public static final float DEFAULT_MAX_DISTANCE = 256.0f;

    /** 默认最大角度（弧度） */
    public static final float DEFAULT_MAX_ANGLE = (float) Math.PI;

    /** 默认最大年龄（秒） */
    public static final float DEFAULT_MAX_AGE = 30.0f;

    // ==================== 内部数据结构 ====================

    /**
     * 加载任务。
     *
     * <p>表示一个待加载的区块及其元数据。</p>
     */
    public static class LoadTask {
        /** 区块 X 坐标 */
        public final int chunkX;
        /** 区块 Z 坐标 */
        public final int chunkZ;
        /** 当前优先级（越小越优先，volatile 保证可见性） */
        public volatile int priority;
        /** 任务状态 */
        public volatile LoadState state;
        /** 创建时间戳（纳秒） */
        public final long createdTime;
        /** 开始处理时间戳（纳秒） */
        public volatile long startTime;
        /** 完成时间戳（纳秒） */
        public volatile long completedTime;
        /** 到相机的距离（用于优先级计算） */
        private volatile float distanceToCamera;
        /** 与相机朝向的夹角（弧度） */
        private volatile float angleFromCamera;
        /** 缓存的哈希码（用于快速查找） */
        private final int hashKey;

        /**
         * 加载状态枚举。
         */
        public enum LoadState {
            /** 待处理（在队列中等待） */
            PENDING,
            /** 处理中（正在被工作线程执行） */
            PROCESSING,
            /** 已完成（成功加载） */
            COMPLETED,
            /** 失败（加载出错） */
            FAILED,
            /** 已取消 */
            CANCELLED
        }

        /**
         * 创建加载任务。
         *
         * @param chunkX 区块 X 坐标
         * @param chunkZ 区块 Z 坐标
         * @param priority 初始优先级
         */
        public LoadTask(int chunkX, int chunkZ, int priority) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.priority = priority;
            this.state = LoadState.PENDING;
            this.createdTime = System.nanoTime();
            this.startTime = 0;
            this.completedTime = 0;
            this.distanceToCamera = Float.MAX_VALUE;
            this.angleFromCamera = Float.MAX_VALUE;
            // 预计算哈希码：使用坐标组合
            this.hashKey = chunkX * 31 + chunkZ;
        }

        /**
         * 更新距离和角度信息。
         *
         * @param distance 到相机的距离（区块单位）
         * @param angle 与相机朝向的夹角（弧度）
         */
        public void updateDistanceAndAngle(float distance, float angle) {
            this.distanceToCamera = distance;
            this.angleFromCamera = angle;
        }

        /**
         * 获取到相机的距离。
         *
         * @return 距离（区块单位）
         */
        public float getDistanceToCamera() {
            return distanceToCamera;
        }

        /**
         * 获取与相机朝向的夹角。
         *
         * @return 夹角（弧度）
         */
        public float getAngleFromCamera() {
            return angleFromCamera;
        }

        /**
         * 获取任务年龄（秒）。
         *
         * @return 从创建到现在的时间（秒）
         */
        public float getAgeSeconds() {
            return (System.nanoTime() - createdTime) / 1_000_000_000.0f;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof LoadTask)) return false;
            LoadTask other = (LoadTask) obj;
            return this.chunkX == other.chunkX && this.chunkZ == other.chunkZ;
        }

        @Override
        public int hashCode() {
            return hashKey;
        }

        @Override
        public String toString() {
            return String.format("LoadTask[%d,%d] priority=%d state=%s age=%.1fs",
                chunkX, chunkZ, priority, state, getAgeSeconds());
        }
    }

    // ==================== 配置 ====================

    /**
     * 队列配置参数。
     */
    public static class QueueConfig {
        /** 距离权重（默认 1.0） */
        public float distanceWeight = 1.0f;
        /** 视角权重（默认 0.5） */
        public float angleWeight = 0.5f;
        /** 陈旧度权重（默认 0.3） */
        public float staleWeight = 0.3f;
        /** 最大归一化距离 */
        public float maxDistance = DEFAULT_MAX_DISTANCE;
        /** 最大归一化角度（弧度） */
        public float maxAngle = DEFAULT_MAX_ANGLE;
        /** 最大归一化年龄（秒） */
        public float maxAge = DEFAULT_MAX_AGE;

        /**
         * 构建器模式。
         */
        public static class Builder {
            private final QueueConfig config = new QueueConfig();

            public Builder distanceWeight(float weight) { config.distanceWeight = weight; return this; }
            public Builder angleWeight(float weight) { config.angleWeight = weight; return this; }
            public Builder staleWeight(float weight) { config.staleWeight = weight; return this; }
            public Builder maxDistance(float dist) { config.maxDistance = dist; return this; }
            public Builder maxAngle(float angle) { config.maxAngle = angle; return this; }
            public Builder maxAge(float age) { config.maxAge = age; return this; }

            public QueueConfig build() {
                validate();
                return config;
            }

            private void validate() {
                if (config.distanceWeight < 0 || config.angleWeight < 0 || config.staleWeight < 0) {
                    throw new IllegalArgumentException("权重不能为负数");
                }
                if (config.maxDistance <= 0 || config.maxAngle <= 0 || config.maxAge <= 0) {
                    throw new IllegalArgumentException("归一化参数必须大于 0");
                }
            }
        }

        public static Builder builder() { return new Builder(); }
    }

    // ==================== 核心字段 ====================

    /** 优先级队列（线程安全） */
    private final PriorityBlockingQueue<LoadTask> queue;

    /** 任务索引表（坐标 → 任务，用于快速查找和去重） */
    private final ConcurrentHashMap<String, LoadTask> taskIndex;

    /** 配置参数 */
    private final QueueConfig config;

    /** 提交的任务总数 */
    private final AtomicLong totalSubmitted;

    /** 已完成的任务总数 */
    private final AtomicLong totalCompleted;

    /** 失败的任务总数 */
    private final AtomicLong totalFailed;

    /** 是否已关闭 */
    private volatile boolean shutdown = false;

    // ==================== 构造函数 ====================

    /**
     * 使用默认配置创建优先级队列。
     */
    public ChunkLoadPriorityQueue() {
        this(DEFAULT_CAPACITY, new QueueConfig());
    }

    /**
     * 使用自定义容量和配置创建优先级队列。
     *
     * @param capacity 初始容量
     * @param config 队列配置
     */
    public ChunkLoadPriorityQueue(int capacity, QueueConfig config) {
        // 创建带自定义比较器的优先级队列
        this.queue = new PriorityBlockingQueue<>(capacity, createComparator(config));
        this.taskIndex = new ConcurrentHashMap<>();
        this.config = config != null ? config : new QueueConfig();
        this.totalSubmitted = new AtomicLong(0);
        this.totalCompleted = new AtomicLong(0);
        this.totalFailed = new AtomicLong(0);

        LOGGER.fine(String.format(
            "ChunkLoadPriorityQueue 初始化完成 - 容量: %d, 权重: [dist=%.2f angle=%.2f stale=%.2f]",
            capacity, config.distanceWeight, config.angleWeight, config.staleWeight));
    }

    // ==================== 公共方法 ====================

    /**
     * 提交新的加载任务。
     *
     * <p>如果相同坐标的任务已存在且仍在队列中，则更新其优先级。</p>
     *
     * @param task 要提交的加载任务
     * @return true 如果成功加入队列，false 如果任务已存在且正在处理或已完成
     */
    public boolean submit(LoadTask task) {
        if (shutdown) {
            LOGGER.warning("队列已关闭，拒绝新任务");
            return false;
        }
        if (task == null) {
            throw new NullPointerException("任务不能为 null");
        }

        String key = makeKey(task.chunkX, task.chunkZ);

        // 检查是否已存在
        LoadTask existing = taskIndex.get(key);
        if (existing != null) {
            // 如果已存在但还在队列中，更新优先级并重新插入
            if (existing.state == LoadTask.LoadState.PENDING) {
                existing.priority = Math.min(existing.priority, task.priority); // 取更优优先级
                reinsert(existing);
                LOGGER.fine(String.format("更新已存在任务 [%d,%d] 的优先级为 %d",
                    task.chunkX, task.chunkZ, existing.priority));
                return true;
            } else {
                // 正在处理、已完成或失败，不重复添加
                LOGGER.fine(String.format("忽略重复任务 [%d,%d]，当前状态: %s",
                    task.chunkX, task.chunkZ, existing.state));
                return false;
            }
        }

        // 新任务：加入索引和队列
        taskIndex.put(key, task);
        queue.offer(task);
        totalSubmitted.incrementAndGet();

        LOGGER.finest(String.format("提交新任务 [%d,%d] 优先级=%d", task.chunkX, task.chunkZ, task.priority));
        return true;
    }

    /**
     * 获取下一个最高优先级的任务（阻塞）。
     *
     * @return 下一个要处理的任务，如果队列为空则阻塞等待
     * @throws InterruptedException 如果线程在等待时被中断
     */
    public LoadTask take() throws InterruptedException {
        LoadTask task = queue.take();

        // 更新状态为处理中
        if (task != null && task.state == LoadTask.LoadState.PENDING) {
            task.state = LoadTask.LoadState.PROCESSING;
            task.startTime = System.nanoTime();
        }

        return task;
    }

    /**
     * 获取下一个最高优先级的任务（非阻塞）。
     *
     * @return 下一个任务，如果没有可用任务返回 null
     */
    public LoadTask poll() {
        LoadTask task = queue.poll();

        if (task != null && task.state == LoadTask.LoadState.PENDING) {
            task.state = LoadTask.LoadState.PROCESSING;
            task.startTime = System.nanoTime();
        }

        return task;
    }

    /**
     * 取消指定坐标的任务。
     *
     * <p>只能取消状态为 PENDING 的任务。</p>
     *
     * @param chunkX 区块 X 坐标
     * @param chunkZ 区块 Z 坐标
     * @return true 如果成功取消
     */
    public boolean cancel(int chunkX, int chunkZ) {
        String key = makeKey(chunkX, chunkZ);
        LoadTask task = taskIndex.get(key);

        if (task == null || task.state != LoadTask.LoadState.PENDING) {
            return false;
        }

        // 从队列移除并标记为取消
        boolean removed = queue.remove(task);
        if (removed) {
            task.state = LoadTask.LoadState.CANCELLED;
            task.completedTime = System.nanoTime();
            taskIndex.remove(key);
            LOGGER.fine(String.format("取消任务 [%d,%d]", chunkX, chunkZ));
        }

        return removed;
    }

    /**
     * 清空所有待处理任务。
     *
     * @return 被清除的任务数量
     */
    public int clearAllPending() {
        int count = 0;
        for (LoadTask task : queue) {
            if (task.state == LoadTask.LoadState.PENDING) {
                task.state = LoadTask.LoadState.CANCELLED;
                count++;
            }
        }
        queue.clear();
        // 只清除 PENDING 状态的任务索引
        taskIndex.entrySet().removeIf(entry ->
            entry.getValue().state == LoadTask.LoadState.CANCELLED);

        if (count > 0) {
            LOGGER.info(String.format("清空了 %d 个待处理任务", count));
        }
        return count;
    }

    /**
     * 标记任务为已完成。
     *
     * @param task 已完成的任务
     */
    public void markCompleted(LoadTask task) {
        if (task != null) {
            task.state = LoadTask.LoadState.COMPLETED;
            task.completedTime = System.nanoTime();
            totalCompleted.incrementAndGet();
        }
    }

    /**
     * 标记任务为失败。
     *
     * @param task 失败的任务
     */
    public void markFailed(LoadTask task) {
        if (task != null) {
            task.state = LoadTask.LoadState.FAILED;
            task.completedTime = System.nanoTime();
            totalFailed.incrementAndGet();
        }
    }

    /**
     * 根据相机位置和方向重新计算所有任务的优先级。
     *
     * <p><b>性能说明：</b>此操作需要遍历所有队列中的任务，
     * 对于 1000 个任务应在 &lt;0.1ms 内完成。</p>
     *
     * @param cameraPosition 相机位置 [x, y, z]（世界坐标）
     * @param cameraDirection 相机朝向向量（归一化的方向向量）
     * @return 更新的任务数量
     */
    public int updatePriorities(float[] cameraPosition, float[] cameraDirection) {
        if (shutdown || cameraPosition == null || cameraDirection == null) {
            return 0;
        }

        // 每个区块的大小（假设 16×16）
        final float CHUNK_SIZE = 16.0f;
        int updatedCount = 0;
        long startTime = System.nanoTime();

        // 收集所有需要更新的任务
        java.util.List<LoadTask> tasksToUpdate = new java.util.ArrayList<>();
        for (LoadTask task : queue) {
            if (task.state == LoadTask.LoadState.PENDING) {
                tasksToUpdate.add(task);
            }
        }

        // 批量更新优先级
        for (LoadTask task : tasksToUpdate) {
            // 计算区块中心的世界坐标
            float chunkCenterX = task.chunkX * CHUNK_SIZE + CHUNK_SIZE / 2.0f;
            float chunkCenterZ = task.chunkZ * CHUNK_SIZE + CHUNK_SIZE / 2.0f;

            // 计算到相机的距离（只考虑 XZ 平面）
            float dx = chunkCenterX - cameraPosition[0];
            float dz = chunkCenterZ - cameraPosition[2];
            float distance = (float) Math.sqrt(dx * dx + dz * dz) / CHUNK_SIZE; // 转换为区块单位

            // 计算与相机朝向的夹角
            float angle = calculateAngle(dx, dz, cameraDirection[0], cameraDirection[2]);

            // 更新任务的距离和角度信息
            task.updateDistanceAndAngle(distance, angle);

            // 计算新的优先级
            int newPriority = calculatePriority(task);

            // 如果优先级发生变化，重新插入
            if (newPriority != task.priority) {
                task.priority = newPriority;
                reinsert(task);
                updatedCount++;
            }
        }

        long elapsed = System.nanoTime() - startTime;
        if (updatedCount > 0) {
            LOGGER.fine(String.format(
                "优先级更新完成 - 更新 %d/%d 个任务，耗时 %.3f ms",
                updatedCount, tasksToUpdate.size(), elapsed / 1_000_000.0));
        }

        return updatedCount;
    }

    // ==================== 状态查询 ====================

    /**
     * 获取队列大小（包含所有状态的任务）。
     *
     * @return 队列中的任务总数
     */
    public int size() {
        return queue.size();
    }

    /**
     * 判断队列是否为空。
     *
     * @return true 如果队列为空
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * 获取待处理任务数量。
     *
     * @return PENDING 状态的任务数
     */
    public int getPendingCount() {
        int count = 0;
        for (LoadTask task : queue) {
            if (task.state == LoadTask.LoadState.PENDING) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取统计信息。
     *
     * @return 队列统计快照
     */
    public QueueStatus getStatus() {
        return new QueueStatus(
            size(),
            getPendingCount(),
            totalSubmitted.get(),
            totalCompleted.get(),
            totalFailed.get()
        );
    }

    /**
     * 队列状态记录。
     */
    public record QueueStatus(
        int totalCount,
        int pendingCount,
        long totalSubmitted,
        long totalCompleted,
        long totalFailed
    ) {}

    // ==================== 生命周期 ====================

    /**
     * 关闭队列。
     *
     * <p>关闭后拒绝新任务，但不影响已在队列中的任务。</p>
     */
    public void shutdown() {
        this.shutdown = true;
        LOGGER.info(String.format("ChunkLoadPriorityQueue 已关闭 - 剩余任务: %d", size()));
    }

    // ==================== 内部方法 ====================

    /**
     * 创建优先级比较器。
     *
     * @param config 队列配置
     * @return 比较器实例
     */
    private static Comparator<LoadTask> createComparator(QueueConfig config) {
        return (t1, t2) -> Integer.compare(t1.priority, t2.priority);
    }

    /**
     * 生成任务键（用于索引）。
     *
     * @param chunkX 区块 X
     * @param chunkZ 区块 Z
     * @格式化的键字符串
     */
    private static String makeKey(int chunkX, int chunkZ) {
        return chunkX + "," + chunkZ;
    }

    /**
     * 重新插入任务到队列（触发重新排序）。
     *
     * @param task 要重新插入的任务
     */
    private void reinsert(LoadTask task) {
        if (queue.remove(task)) {
            queue.offer(task);
        }
    }

    /**
     * 计算两个向量之间的夹角。
     *
     * @param dx 第一个向量的 X 分量
     * @param dz 第一个向量的 Z 分量
     * @param dirX 第二个向量的 X 分量（相机方向）
     * @param dirZ 第二个向量的 Z 分量（相机方向）
     * @return 夹角（弧度，0 ~ π）
     */
    private static float calculateAngle(float dx, float dz, float dirX, float dirZ) {
        // 归一化区块方向向量
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001f) return 0.0f; // 避免除零

        float nx = dx / len;
        float nz = dz / len;

        // 点积求夹角
        float dot = nx * dirX + nz * dirZ;
        // 限制范围 [-1, 1] 以避免浮点误差导致的 NaN
        dot = Math.max(-1.0f, Math.min(1.0f, dot));

        return (float) Math.acos(dot);
    }

    /**
     * 计算任务的复合优先级值。
     *
     * <p>优先级值越小表示越应该优先处理。</p>
     *
     * @param task 要计算的任务
     * @return 优先级值（整数）
     */
    private int calculatePriority(LoadTask task) {
        // 归一化各因子到 [0, 1] 范围
        float normalizedDist = Math.min(1.0f, task.distanceToCamera / config.maxDistance);
        float normalizedAngle = Math.min(1.0f, task.angleFromCamera / config.maxAngle);
        float normalizedAge = Math.min(1.0f, task.getAgeSeconds() / config.maxAge);

        // 加权求和得到浮点优先级（越小越优）
        float weightedPriority =
            config.distanceWeight * normalizedDist +
            config.angleWeight * normalizedAngle +
            config.staleWeight * normalizedAge;

        // 转换为整数（乘以 1000 保持精度）
        return (int) (weightedPriority * 1000);
    }
}
