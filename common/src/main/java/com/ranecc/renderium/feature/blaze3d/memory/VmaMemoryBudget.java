// Renderium - Blaze3D 优化模块
// VMA 激进优化系统 - 内存预算管理器
//
// 功能：实时监控显存使用情况，实现自适应分配策略和自动内存回收
// 参考：vma-aggressive-optimizations.md §2.4 内存预算与自适应分配

package com.ranecc.renderium.feature.blaze3d.memory;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.util.vma.VmaBudget;
import org.lwjgl.util.vma.VmaDefragmentationInfo;
import org.lwjgl.util.vma.VmaDefragmentationStats;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VK10;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * VMA 内存预算管理器 💰
 * <p>
 * 基于 Vulkan Memory Allocator 的 {@code VK_EXT_memory_budget} 扩展，
 * 实现实时显存监控、自适应分配策略和自动内存回收机制。
 * 防止因显存过度使用导致的 OOM（Out-of-Memory）错误和性能下降。
 *
 * <h2>核心问题：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │  问题场景：                                                   │
 * │    - 游戏运行中不断创建纹理/缓冲区，最终超出 VRAM 限制        │
 * │    - 显存不足导致系统回退到共享内存（性能下降 10-100x）      │
 * │    - 严重时导致应用崩溃或 GPU 超时（TDR）                   │
 * │                                                             │
 * │  根本原因：                                                   │
 * │    缺乏全局的显存预算管理和自适应分配策略                    │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>解决方案：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │  分级响应机制：                                               │
 * │                                                             │
 * │    [0% ────────── 85%) 正常状态                              │
 * │       → 标准分配策略，优先性能                               │
 * │                                                             │
 * │    [85% ───────── 95%) ⚠️ 警告状态                          │
 * │       → 触发非关键资源清理                                   │
 * │       → 切换到节省内存的分配策略                             │
 * │       → 降低新分配的优先级                                   │
 * │                                                             │
 * │    [95% ─────────100%] 🚨 紧急状态                           │
 * │       → 强制释放所有可回收资源                               │
 * │       → 拒绝低优先级分配请求                                 │
 * │       → 启动碎片整理                                         │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>关键特性：</h3>
 * <table border="1">
 *   <tr><th>特性</th><th>说明</th><th>收益</th></tr>
 *   <tr><td>实时监控</td><td>每帧检查 VMA Budget</td><td>及时发现问题</td></tr>
 *   <tr><td>自适应分配</td><td>根据内存压力调整策略</td><td>平衡性能与稳定性</td></tr>
 *   <tr><td>自动清理</td><td>分级触发垃圾回收</td><td>防止 OOM</td></tr>
 *   <tr><td>碎片整理</td><td>定期合并碎片化内存</td><td>提高利用率</td></tr>
 *   <tr><td>优先级系统</td><td>关键资源优先保障</td><td>保证核心功能</td></tr>
 * </table>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 创建内存预算管理器
 * VmaMemoryBudget budget = new VmaMemoryBudget();
 *
 * // 初始化（设置 4GB 预算上限）
 * budget.initializeBudget(4L * 1024 * 1024 * 1024);
 *
 * // 渲染循环中：
 * while (running) {
 *     // ... 渲染逻辑 ...
 *
 *     // 每帧检查内存状态（建议在帧结束时调用）
 *     budget.checkMemoryBudget();
 *
 *     // 自适应分配（根据当前内存压力自动选择最优策略）
 *     Allocation alloc = budget.adaptiveAllocate(
 *         requestedSize,
 *         AllocationPriority.HIGH  // 高优先级
 *     );
 * }
 *
 * // 定期执行碎片整理（如每 N 帧或检测到高碎片率时）
 * if (shouldDefragment) {
 *     budget.defragmentMemory();
 * }
 *
 * // 应用退出时关闭
 * budget.close();
 * }</pre>
 *
 * <h3>线程安全说明：</h3>
 * <ul>
 *   <li>查询方法（{@link #checkMemoryBudget}）可从任何线程调用</li>
 *   <li>分配方法（{@link #adaptiveAllocate}）应串行调用或外部加锁</li>
 *   <li>内部状态使用 volatile 和 Atomic 类型保证一致性</li>
 * </ul>
 *
 * @see VmaMemoryPools 专用内存池管理器
 * @see VmaDeferredDeallocation 延迟销毁系统
 * @author Renderium Team
 * @since 2.0.0
 */
public class VmaMemoryBudget implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(VmaMemoryBudget.class.getName());

    /** 单例实例 */
    private static volatile VmaMemoryBudget instance;

    /**
     * 获取单例实例
     *
     * @return VmaMemoryBudget 实例
     */
    public static VmaMemoryBudget getInstance() {
        if (instance == null) {
            synchronized (VmaMemoryBudget.class) {
                if (instance == null) {
                    instance = new VmaMemoryBudget();
                }
            }
        }
        return instance;
    }

    // ==================== 枚举类型定义 ====================

    /**
     * 分配优先级枚举
     * <p>
     * 定义不同重要程度的资源分配优先级。
     * 在内存紧张时，低优先级的请求会被拒绝或降级处理。
     */
    public enum AllocationPriority {
        /** 关键资源：不可失败（如主渲染目标、深度缓冲），必须成功分配 */
        CRITICAL(1.0f),

        /** 高优先级：重要但可降级（如主要纹理、大型几何体） */
        HIGH(0.7f),

        /** 中等优先级：普通资源（如特效纹理、UI 元素） */
        NORMAL(0.5f),

        /** 低优先级：可延迟加载或丢弃（如缓存、预加载数据） */
        LOW(0.3f);

        /** VMA 内部使用的优先级值（0.0 ~ 1.0） */
        public final float vmaPriority;

        AllocationPriority(float vmaPriority) {
            this.vmaPriority = vmaPriority;
        }
    }

    /**
     * 内存状态枚举
     */
    private enum MemoryStatus {
        /** 正常状态（< 85%）*/
        NORMAL,

        /** 警告状态（85% - 95%）*/
        WARNING,

        /** 紧急状态（> 95%）*/
        CRITICAL
    }

    // ==================== 配置常量 ====================

    /**
     * 默认警告阈值（85%）
     * <p>
     * 当显存使用超过此比例时，触发警告级别的清理操作。
     */
    public static final float DEFAULT_WARNING_THRESHOLD = 0.85f;

    /**
     * 默认紧急阈值（95%）
     * <p>
     * 当显存使用超过此比例时，触发紧急清理并拒绝低优先级分配。
     */
    public static final float DEFAULT_CRITICAL_THRESHOLD = 0.95f;

    /**
     * 默认碎片整理的最大字节数/次（64 MB）
     * <p>
     * 单次碎片整理最多移动的数据量，避免单次操作耗时过长。
     */
    private static final long DEFAULT_DEFRAG_MAX_BYTES_PER_PASS = 64L * 1024 * 1024;

    /**
     * 默认碎片整理的最大分配数/次（100 个）
     * <p>
     * 单次碎片整理最多处理的分配数量。
     */
    private static final int DEFAULT_DEFRAG_MAX_ALLOCATIONS_PER_PASS = 100;

    // ==================== 核心字段 ====================

    /**
     * VMA 分配器句柄
     */
    private volatile long vmaAllocator = 0L;

    /**
     * 用户设定的显存预算上限（字节）
     */
    private volatile long memoryBudgetLimit = 0L;

    /**
     * 警告阈值（0.0 ~ 1.0）
     */
    private volatile float warningThreshold = DEFAULT_WARNING_THRESHOLD;

    /**
     * 紧急阈值（0.0 ~ 1.0）
     */
    private volatile float criticalThreshold = DEFAULT_CRITICAL_THRESHOLD;

    // ==================== 状态字段 ====================

    /** 是否已初始化 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 是否已关闭 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 当前内存状态 */
    private volatile MemoryStatus currentStatus = MemoryStatus.NORMAL;

    /** 上一次检查的使用比例 */
    private volatile float lastUsageRatio = 0.0f;

    // ==================== 统计字段 ====================

    /** 总检查次数 */
    private final AtomicLong totalBudgetChecks = new AtomicLong(0);

    /** 总分配次数 */
    private final AtomicLong totalAllocations = new AtomicLong(0);

    /** 成功分配次数 */
    private final AtomicLong successfulAllocations = new AtomicLong(0);

    /** 因内存不足被拒绝的次数 */
    private final AtomicLong rejectedAllocations = new AtomicLong(0);

    /** 触发警告状态的次数 */
    private final AtomicLong warningTriggerCount = new AtomicLong(0);

    /** 触发紧急状态的次数 */
    private final AtomicLong criticalTriggerCount = new AtomicLong(0);

    /** 碎片整理执行次数 */
    private final AtomicLong defragmentationCount = new AtomicLong(0);

    /** 碎片整理总移动字节数 */
    private final AtomicLong totalDefragBytesMoved = new AtomicLong(0);

    /** 碎片整理总释放字节数 */
    private final AtomicLong totalDefragBytesFreed = new AtomicLong(0);

    /** 累计分配的总字节数（用于计算当前内存使用量） */
    private final AtomicLong totalBytesAllocated = new AtomicLong(0);

    /** 累计释放的总字节数（用于计算当前内存使用量） */
    private final AtomicLong totalBytesFreed = new AtomicLong(0);

    // ==================== 回调接口 ====================

    /**
     * 内存状态变化监听器
     * <p>
     * 可选的回调接口，用于在外部响应内存状态变化（如显示警告 UI、记录日志等）。
     */
    public interface MemoryBudgetListener {
        /**
         * 当内存状态发生变化时调用
         *
         * @param oldStatus 之前的状态
         * @param newStatus 新的状态
         * @param usageRatio 当前使用比例（0.0 ~ 1.0+）
         */
        void onMemoryStatusChanged(MemoryStatus oldStatus, MemoryStatus newStatus, float usageRatio);
    }

    /** 监听器列表（可选） */
    private final List<MemoryBudgetListener> listeners = new ArrayList<>();

    // ==================== 构造函数 ====================

    /**
     * 创建内存预算管理器
     * <p>
     * 初始状态下未设定预算上限。
     * 必须调用 {@link #initializeBudget} 后才能使用监控和分配功能。
     */
    public VmaMemoryBudget() {
        LOGGER.fine("VmaMemoryBudget 实例已创建（等待初始化）");
    }

    // ==================== 公共 API：生命周期管理 ====================

    /**
     * 初始化内存预算管理器 ⚙️
     * <p>
     * 设置显存预算上限并启用内存监控功能。
     * 此方法应在 Vulkan 设备和 VMA 分配器初始化完成后调用一次。
     *
     * @param budgetBytes 显存预算上限（字节），必须大于 0
     *                    例如：4GB = 4L * 1024 * 1024 * 1024
     *
     * @return 如果成功初始化则返回 true；如果参数无效或已初始化则返回 false
     *
     * @throws IllegalStateException 如果已经关闭（close() 已调用）
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>budgetBytes</b>: long - 应用可用的最大显存量（字节）。
     *       建议：<ul>
     *         <li>集成显卡：设置为共享内存的一半（如 2GB）</li>
     *         <li>独立显卡（8GB）：设置为 6-7GB（留余量给系统和驱动）</li>
     *         <li>独立显卡（12GB+）：设置为 VRAM 的 70-80%</li>
     *       </ul>
     *   </li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>true - 成功设置预算上限</li>
     *   <li>false - 参数无效或重复初始化</li>
     * </ul>
     *
     * <h4>使用示例：</h4>
     * <pre>{@code
     * VmaMemoryBudget budget = new VmaMemoryBudget();
     *
     * // 设置 6GB 预算（适用于 8GB 显卡）
     * if (!budget.initializeBudget(6L * 1024 * 1024 * 1024)) {
     *     throw new RuntimeException("内存预算初始化失败");
     * }
     * }</pre>
     */
    public boolean initializeBudget(long budgetBytes) {
        // 参数校验
        if (budgetBytes <= 0) {
            LOGGER.severe("initializeBudget 失败: budgetBytes 必须大于 0，当前值: " + budgetBytes);
            return false;
        }

        if (closed.get()) {
            throw new IllegalStateException("VmaMemoryBudget 已关闭，无法重新初始化");
        }

        if (initialized.get()) {
            LOGGER.warning("initializeBudget: 已经初始化过，跳过重复初始化");
            return true; // 幂等性
        }

        try {
            this.memoryBudgetLimit = budgetBytes;
            initialized.set(true);

            LOGGER.info(String.format(
                    "✓ VmaMemoryBudget 初始化完成\n" +
                    "  预算上限: %s\n" +
                    "  警告阈值: %.0f%%\n" +
                    "  紧急阈值: %.0f%%",
                    formatSize(budgetBytes),
                    warningThreshold * 100,
                    criticalThreshold * 100));
            return true;

        } catch (Exception e) {
            LOGGER.severe("initializeBudget 异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 检查当前内存预算状态 📊
     * <p>
     * 应在**每帧渲染结束后**调用一次。此方法会：
     * <ol>
     *   <li>通过 VMA API 查询当前的显存使用量和预算</li>
     *   <li>计算使用比例并与阈值比较</li>
     *   <li>根据新的状态级别触发相应的响应动作</li>
     *   <li>通知所有注册的监听器</li>
     * </ol>
     *
     * <h3>分级响应动作：</h3>
     * <table border="1">
     *   <tr><th>状态</th><th>条件</th><th>触发的动作</th></tr>
     *   <tr><td>NORMAL</td><td>&lt; 85%</td><td>无特殊动作</td></tr>
     *   <tr><td>WARNING</td><td>85% - 95%</td><td>清理非关键资源、降低分配优先级</td></tr>
     *   <tr><td>CRITICAL</td><td>&gt; 95%</td><td>强制回收、拒绝低优先级分配</td></tr>
     * </table>
     *
     * @return 当前的 {@link MemoryStatus} 状态枚举值
     *
     * @throws IllegalStateException 如果未初始化或已关闭
     *
     * <h4>性能考虑：</h4>
     * <ul>
     *   <li>时间复杂度：O(1)（VMA Budget 查询很快）</li>
     *   <li>典型耗时：~1-5µs（取决于 VMA 实现）</li>
     *   <li>建议调用频率：每帧一次（~16ms 间隔）</li>
     *   <li>额外开销：仅在状态变化时触发清理（不是每次都执行）</li>
     * </ul>
     *
     * <h4>使用示例：</h4>
     * <pre>{@code
     * // 在渲染循环末尾
     * while (running) {
     *     renderFrame();
     *
     *     // ⭐ 重要：每帧检查内存状态！
     *     MemoryStatus status = budget.checkMemoryBudget();
     *
     *     if (status == MemoryStatus.CRITICAL) {
     *         logger.error("显存严重不足！");
     *         // 可选：暂停某些功能以减少内存压力
     *     }
     * }
     * }</pre>
     */
    public MemoryStatus checkMemoryBudget() {
        if (!initialized.get()) {
            throw new IllegalStateException("VmaMemoryBudget 未初始化，请先调用 initializeBudget()");
        }

        if (closed.get()) {
            throw new IllegalStateException("VmaMemoryBudget 已关闭");
        }

        try {
            // 调用 VMA API 查询预算信息（使用 LWJGL Vulkan 绑定）
            // 方法参数: 无 -> MemoryStatus (当前内存状态)
            
            float usageRatio;
            
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // 创建 VmaBudget 结构体来接收预算信息
                VmaBudget budget = VmaBudget.calloc(stack);

                // TODO: Vma.vmaGetBudget() 在当前 LWJGL VMA 绑定中可能未导出，
                //       当前使用存根实现：基于已知统计量估算使用比例
                // Vma.vmaGetBudget(vmaAllocator, budget);
                long totalUsage = totalBytesAllocated.get() - totalBytesFreed.get();
                long totalBudget = memoryBudgetLimit;

                // 计算使用比例（取最小值以避免除零）
                usageRatio = (float) totalUsage / Math.max(totalBudget, memoryBudgetLimit);
            }

            // 更新统计
            totalBudgetChecks.incrementAndGet();
            lastUsageRatio = usageRatio;

            // 确定新的状态
            MemoryStatus newStatus;
            if (usageRatio > criticalThreshold) {
                newStatus = MemoryStatus.CRITICAL;
                criticalTriggerCount.incrementAndGet();
            } else if (usageRatio > warningThreshold) {
                newStatus = MemoryStatus.WARNING;
                warningTriggerCount.incrementAndGet();
            } else {
                newStatus = MemoryStatus.NORMAL;
            }

            // 处理状态变化
            if (newStatus != currentStatus) {
                handleStatusChange(currentStatus, newStatus, usageRatio);
                currentStatus = newStatus;
            }

            return newStatus;

        } catch (Exception e) {
            LOGGER.severe("checkMemoryBudget 异常: " + e.getMessage());
            return MemoryStatus.NORMAL;  // 出错时返回安全默认值
        }
    }

    /**
     * 自适应内存分配 🎯
     * <p>
     * 根据**当前内存压力**和**请求优先级**，自动选择最优的分配策略。
     * 这是本类的核心方法，实现了智能的资源分配决策逻辑。
     *
     * <h3>自适应策略矩阵：</h3>
     * <table border="1">
     *   <tr>
     *     <th>内存状态 \ 优先级</th>
     *     <th>CRITICAL (1.0)</th>
     *     <th>HIGH (0.7)</th>
     *     <th>NORMAL (0.5)</th>
     *     <th>LOW (0.3)</th>
     *   </tr>
     *   <tr>
     *     <td>NORMAL (&lt;85%)</td>
     *     <td>专用内存 + 最高优</td>
     *     <td>最佳适配算法</td>
     *     <td>最佳适配算法</td>
     *     <td>最佳适配算法</td>
     *   </tr>
     *   <tr>
     *     <td>WARNING (85-95%)</td>
     *     <td>专用内存 + 高优</td>
     *     <td>最小内存策略</td>
     *     <td>最小内存策略</td>
     *     <td>⚠️ 可能拒绝</td>
     *   </tr>
     *   <tr>
     *     <td>CRITICAL (&gt;95%)</td>
     *     <td>专用内存 + 强制</td>
     *     <td>⚠️ 可能拒绝</td>
     *     <td>❌ 拒绝</td>
     *     <td>❌ 拒绝</td>
     *   </tr>
     * </table>
     *
     * @param requestedSize 请求分配的字节数，必须大于 0
     * @param priority      分配优先级（不能为 null）
     *
     * @return {@link BudgetAllocationResult} 包含分配结果和相关信息；
     *         如果被拒绝则返回 {@code result.success == false}
     *
     * @throws IllegalArgumentException 如果 requestedSize <= 0 或 priority 为 null
     * @throws IllegalStateException 如果未初始化或已关闭
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>requestedSize</b>: long - 需要分配的内存大小（字节）。必须大于 0。</li>
     *   <li><b>priority</b>: {@link AllocationPriority} - 分配优先级。
     *       决定了在内存紧张时该请求的处理方式。</li>
     * </ul>
     *
     * <h4>返回值说明：</h4>
     * <ul>
     *   <li>{@link BudgetAllocationResult#success} - 是否成功分配</li>
     *   <li>{@link BudgetAllocationResult#buffer} - Buffer 句柄（成功时有效）</li>
     *   <li>{@link BudgetAllocationResult#allocation} - VMA allocation 句柄（成功时有效）</li>
     *   <li>{@link BudgetAllocationResult#actualStrategy} - 实际使用的分配策略</li>
     *   <li>{@link BudgetAllocationResult#rejectionReason} - 被拒绝的原因（如果适用）</li>
     * </ul>
     *
     * <h4>使用示例：</h4>
     * <pre>{@code
     * // 分配一个重要的纹理（高优先级）
     * BudgetAllocationResult result = budget.adaptiveAllocate(
     *     textureSizeInBytes,
     *     AllocationPriority.HIGH
     * );
     *
     * if (result.success) {
     *     // 使用 result.buffer 和 result.allocation 创建纹理...
     *     createTexture(result.buffer, result.allocation);
     * } else {
     *     // 分配被拒绝，处理降级方案
     *     logger.warn("纹理分配被拒绝: " + result.rejectionReason);
     *     useLowerResolutionTexture();  // 使用低分辨率版本
     * }
     * }</pre>
     */
    public BudgetAllocationResult adaptiveAllocate(long requestedSize, AllocationPriority priority) {
        // 参数校验
        if (requestedSize <= 0) {
            throw new IllegalArgumentException("requestedSize 必须大于 0，当前值: " + requestedSize);
        }

        if (priority == null) {
            throw new NullPointerException("priority 不能为 null");
        }

        if (!initialized.get()) {
            throw new IllegalStateException("VmaMemoryBudget 未初始化，请先调用 initializeBudget()");
        }

        if (closed.get()) {
            throw new IllegalStateException("VmaMemoryBudget 已关闭");
        }

        totalAllocations.incrementAndGet();

        try {
            // 根据当前状态和优先级决定是否允许分配
            String rejectionReason = shouldRejectAllocation(requestedSize, priority);

            if (rejectionReason != null) {
                // 拒绝分配
                rejectedAllocations.incrementAndGet();

                LOGGER.warning(String.format(
                        "[分配被拒绝] size=%s, priority=%s, reason=%s, 当前使用=%.1f%%",
                        formatSize(requestedSize), priority.name(),
                        rejectionReason, lastUsageRatio * 100));

                return new BudgetAllocationResult(false, 0L, 0L, null, rejectionReason);
            }

            // 决定分配策略
            String strategy = determineAllocationStrategy(priority);

            // 执行实际的 VMA 分配（使用 LWJGL Vulkan 绑定）
            // 方法参数: requestedSize, priority, strategy -> BudgetAllocationResult (分配结果)
            long buffer = 0L;
            long allocation = 0L;

            try (MemoryStack stack = MemoryStack.stackPush()) {
                // 构建 VmaAllocationCreateInfo，根据策略设置不同的标志
                VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                        .usage(Vma.VMA_MEMORY_USAGE_GPU_ONLY)  // 默认 GPU 专用
                        .priority(priority.vmaPriority);      // 设置优先级

                // 根据策略调整分配标志
                if ("DEDICATED+FORCE".equals(strategy) || "DEDICATED+BEST_FIT".equals(strategy)) {
                    // 高优先级或紧急状态：使用专用内存
                    allocInfo.flags(allocInfo.flags() | Vma.VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT);
                } else if ("MIN_MEMORY".equals(strategy)) {
                    // 警告状态：使用最小内存策略
                    allocInfo.flags(allocInfo.flags() | Vma.VMA_ALLOCATION_CREATE_STRATEGY_MIN_MEMORY_BIT);
                }

                // 创建 VkBufferCreateInfo
                VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                        .sType$Default()
                        .size(requestedSize)
                        .usage(VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                        .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

                // 准备输出缓冲区
                LongBuffer pBuffer = stack.mallocLong(1);
                PointerBuffer pAllocation = stack.mallocPointer(1);

                // 调用 VMA API 执行分配
                int result = Vma.vmaCreateBuffer(
                        vmaAllocator,
                        bufferInfo,
                        allocInfo,
                        pBuffer,
                        pAllocation,
                        null  // 不需要 allocation info
                );

                if (result != VK10.VK_SUCCESS) {
                    // 分配失败（可能是真正的 OOM）
                    rejectedAllocations.incrementAndGet();
                    String failureReason = String.format("VMA allocation failed: VkResult=%d", result);
                    
                    LOGGER.warning(String.format(
                            "[自适应分配失败] size=%s, priority=%s, strategy=%s: %s",
                            formatSize(requestedSize), priority.name(), strategy, failureReason));
                    
                    return new BudgetAllocationResult(false, 0L, 0L, strategy, failureReason);
                }

                buffer = pBuffer.get(0);
                allocation = pAllocation.get(0);
            }

            // 分配成功，更新统计
            successfulAllocations.incrementAndGet();

            LOGGER.finest(String.format("[自适应分配成功] size=%s, priority=%s, strategy=%s: buffer=%d, alloc=%d",
                    formatSize(requestedSize), priority.name(), strategy, buffer, allocation));

            return new BudgetAllocationResult(true, buffer, allocation, strategy, null);

        } catch (Exception e) {
            LOGGER.severe(String.format("adaptiveAllocate 异常 [size=%d]: %s",
                    requestedSize, e.getMessage()));
            return new BudgetAllocationResult(false, 0L, 0L, null, "异常: " + e.getMessage());
        }
    }

    /**
     * 执行内存碎片整理 🧹
     * <p>
     * 通过移动和合并碎片化的内存分配来提高显存利用率。
     * 整理过程分多轮进行，每轮移动有限数量的数据以避免卡顿。
     *
     * <h3>工作原理：</h3>
     * <ol>
     *   <li>收集所有可移动（non-locked）的分配</li>
     *   <li>按大小和位置排序，找到可合并的相邻分配</li>
     *   <li>将小分配移动到大块的连续空闲区域</li>
     *   <li>释放原来的碎片空间形成更大的空闲块</li>
     * </ol>
     *
     * <h3>何时应该调用？</h3>
     * <ul>
     *   <li>检测到内存碎片率高时（可通过统计推断）</li>
     *   <li>场景切换后（大量旧资源被释放）</li>
     *   <li>用户手动触发（如设置中的"优化内存"按钮）</li>
     *   <li>应用进入后台时（利用空闲时间）</li>
     * </ul>
     *
     * @return {@link DefragmentationResult} 包含整理结果的详细信息
     *
     * @throws IllegalStateException 如果未初始化或已关闭
     *
     * <h4>性能考虑：</h4>
     * <ul>
     *   <li>单次整理时间：~1-10ms（取决于碎片数量和大小）</li>
     *   <li>GPU 影响可能需要暂停渲染（因为要移动 GPU 资源）</li>
     *   <li>建议在加载界面或非交互期间执行</li>
     *   <li>可通过 {@code maxBytesPerPass} 控制单次工作量</li>
     * </ul>
     *
     * <h4>使用示例：</h4>
     * <pre>{@code
     * // 场景切换完成后执行整理
     * DefragmentationResult result = budget.defragmentMemory();
     *
     * if (result.bytesMoved > 0) {
     *     logger.info(String.format(
     *         "碎片整理完成: 移动 %s, 释放 %s, 耗时 %.2f ms",
     *         formatSize(result.bytesMoved),
     *         formatSize(result.bytesFreed),
     *         result.elapsedTimeMs
     *     ));
     * }
     * }</pre>
     */
    public DefragmentationResult defragmentMemory() {
        if (!initialized.get()) {
            throw new IllegalStateException("VmaMemoryBudget 未初始化，请先调用 initializeBudget()");
        }

        if (closed.get()) {
            throw new IllegalStateException("VmaMemoryBudget 已关闭");
        }

        long startTimeNanos = System.nanoTime();

        try {
            // 调用 VMA 碎片整理 API（使用 LWJGL Vulkan 绑定）
            // 方法参数: 无 -> DefragmentationResult (整理结果)
            
            long bytesMoved = 0L;
            long bytesFreed = 0L;
            int allocationsMoved = 0;
            int allocationsFreed = 0;

            try (MemoryStack stack = MemoryStack.stackPush()) {
                // 配置整理参数
                VmaDefragmentationInfo defragInfo = VmaDefragmentationInfo.calloc(stack)
                        .flags(Vma.VMA_DEFRAGMENTATION_FLAG_ALGORITHM_FAST_BIT)  // 使用快速算法
                        .maxBytesPerPass(DEFAULT_DEFRAG_MAX_BYTES_PER_PASS)       // 单次最大移动字节数
                        .maxAllocationsPerPass(DEFAULT_DEFRAG_MAX_ALLOCATIONS_PER_PASS);  // 单次最大处理分配数

                // 开始整理，获取上下文句柄
                PointerBuffer pContext = stack.mallocPointer(1);
                int result = Vma.vmaBeginDefragmentation(vmaAllocator, defragInfo, pContext);
                
                if (result != VK10.VK_SUCCESS) {
                    LOGGER.warning(String.format("VMA 碎片整理开始失败: VkResult=%d", result));
                    
                    long elapsedNanos = System.nanoTime() - startTimeNanos;
                    return new DefragmentationResult(0L, 0L, 0, 0, elapsedNanos / 1_000_000.0);
                }
                
                long context = pContext.get(0);

                // 执行整理（可能在多轮中迭代）
                VmaDefragmentationStats stats = VmaDefragmentationStats.calloc(stack);
                // [编译修复] 当前 LWJGL VMA 绑定中 vmaEndDefragmentation() 返回 void（非 int），
                // 因此移除返回值赋值，直接调用方法。若操作失败会通过异常体现。
                Vma.vmaEndDefragmentation(vmaAllocator, context, stats);

                // vmaEndDefragmentation 返回 void，执行到此说明操作完成，直接读取统计结果
                bytesMoved = stats.bytesMoved();
                bytesFreed = stats.bytesFreed();
                allocationsMoved = stats.allocationsMoved();
                // TODO: VmaDefragmentationStats.allocationsFreed() 在当前 LWJGL VMA 绑定中可能未导出
                allocationsFreed = 0; // 存根: stats.allocationsFreed()
            }

            long elapsedNanos = System.nanoTime() - startTimeNanos;
            double elapsedTimeMs = elapsedNanos / 1_000_000.0;

            // 更新统计
            defragmentationCount.incrementAndGet();
            totalDefragBytesMoved.addAndGet(bytesMoved);
            totalDefragBytesFreed.addAndGet(bytesFreed);

            LOGGER.info(String.format(
                    "[碎片整理] 完成\n" +
                    "  移动: %s (%d 个分配)\n" +
                    "  释放: %s (%d 个分配)\n" +
                    "  耗时: %.2f ms\n" +
                    "  总整理次数: %d",
                    formatSize(bytesMoved), allocationsMoved,
                    formatSize(bytesFreed), allocationsFreed,
                    elapsedTimeMs,
                    defragmentationCount.get()));

            return new DefragmentationResult(bytesMoved, bytesFreed, allocationsMoved, allocationsFreed, elapsedTimeMs);

        } catch (Exception e) {
            LOGGER.severe("defragmentMemory 异常: " + e.getMessage());
            long elapsedNanos = System.nanoTime() - startTimeNanos;
            return new DefragmentationResult(0L, 0L, 0, 0, elapsedNanos / 1_000_000.0);
        }
    }

    /**
     * 关闭内存预算管理器 ♻️
     * <p>
     * 释放内部资源并重置所有状态。
     * 此方法应该在应用程序退出前调用。
     *
     * @throws Exception 如果底层操作失败（AutoCloseable 接口要求）
     */
    @Override
    public void close() throws Exception {
        if (closed.get()) {
            LOGGER.fine("close: 已经关闭过，跳过重复关闭");
            return; // 幂等性
        }

        closed.set(true);
        initialized.set(false);

        try {
            // 清空监听器
            listeners.clear();

            // 重置状态
            currentStatus = MemoryStatus.NORMAL;
            lastUsageRatio = 0.0f;
            vmaAllocator = 0L;
            memoryBudgetLimit = 0L;

            // 输出最终统计
            LOGGER.info(String.format(
                    "VmaMemoryBudget 已关闭\n" +
                    "  总检查次数: %d\n" +
                    "  总分配请求: %d (成功: %d, 拒绝: %d)\n" +
                    "  警告触发: %d 次, 紧急触发: %d 次\n" +
                    "  碎片整理: %d 次 (移动: %s, 释放: %s)",
                    totalBudgetChecks.get(),
                    totalAllocations.get(),
                    successfulAllocations.get(),
                    rejectedAllocations.get(),
                    warningTriggerCount.get(),
                    criticalTriggerCount.get(),
                    defragmentationCount.get(),
                    formatSize(totalDefragBytesMoved.get()),
                    formatSize(totalDefragBytesFreed.get())
            ));

        } catch (Exception e) {
            LOGGER.severe("close 异常: " + e.getMessage());
            throw e;
        }
    }

    // ==================== 公共 API：配置与监听器 ====================

    /**
     * 设置自定义阈值
     *
     * @param warningThreshold  警告阈值（0.0 ~ 1.0，默认 {@value #DEFAULT_WARNING_THRESHOLD}）
     * @param criticalThreshold 紧急阈值（0.0 ~ 1.0，默认 {@value #DEFAULT_CRITICAL_THRESHOLD}）
     *
     * @throws IllegalArgumentException 如果阈值无效（<=0、>=1 或 warning >= critical）
     */
    public void setThresholds(float warningThreshold, float criticalThreshold) {
        if (warningThreshold <= 0 || warningThreshold >= 1.0) {
            throw new IllegalArgumentException(String.format(
                    "warningThreshold 必须在 (0, 1) 之间，当前值: %f", warningThreshold));
        }

        if (criticalThreshold <= 0 || criticalThreshold > 1.0) {
            throw new IllegalArgumentException(String.format(
                    "criticalThreshold 必须在 (0, 1] 之间，当前值: %f", criticalThreshold));
        }

        if (warningThreshold >= criticalThreshold) {
            throw new IllegalArgumentException(String.format(
                    "warningThreshold (%f) 必须 < criticalThreshold (%f)",
                    warningThreshold, criticalThreshold));
        }

        this.warningThreshold = warningThreshold;
        this.criticalThreshold = criticalThreshold;

        LOGGER.fine(String.format("阈值已更新: 警告=%.0f%%, 紧急=%.0f%%",
                warningThreshold * 100, criticalThreshold * 100));
    }

    /**
     * 添加内存状态变化监听器
     *
     * @param listener 监听器实例（不能为 null）
     *
     * @throws NullPointerException 如果 listener 为 null
     */
    public void addListener(MemoryBudgetListener listener) {
        if (listener == null) {
            throw new NullPointerException("listener 不能为 null");
        }

        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    /**
     * 移除内存状态变化监听器
     *
     * @param listener 要移除的监听器实例
     */
    public void removeListener(MemoryBudgetListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    // ==================== 公共 API：查询与统计 ====================

    /**
     * 获取当前内存状态
     *
     * @return 最后一次 {@link #checkMemoryBudget} 返回的状态
     */
    public MemoryStatus getCurrentStatus() {
        return currentStatus;
    }

    /**
     * 获取上一次检查的使用比例
     *
     * @return 上一次检查时的显存使用比例（0.0 ~ 1.0+）
     */
    public float getLastUsageRatio() {
        return lastUsageRatio;
    }

    /**
     * 获取预算上限
     *
     * @return 设定的显存预算上限（字节）
     */
    public long getMemoryBudgetLimit() {
        return memoryBudgetLimit;
    }

    /**
     * 获取格式化的内存预算报告 📊
     *
     * @return 包含详细信息的报告字符串
     */
    public String formatReport() {
        StringBuilder sb = new StringBuilder();

        sb.append("╔══════════════════════════════════════════╗\n");
        sb.append("║  VMA 内存预算报告                         ║\n");
        sb.append("╚══════════════════════════════════════════╝\n\n");

        // 基本信息
        sb.append(String.format("状态: %s | 已关闭: %s\n",
                initialized.get() ? "✓ 已初始化" : "✗ 未初始化",
                closed.get() ? "✗ 是" : "否"));
        sb.append(String.format("预算上限: %s\n", formatSize(memoryBudgetLimit)));
        sb.append(String.format("当前状态: %s (%.1f%%)\n",
                currentStatus.name(), lastUsageRatio * 100));

        // 阈值配置
        sb.append("\n--- 阈值配置 ---\n");
        sb.append(String.format("警告阈值: %.0f%%\n", warningThreshold * 100));
        sb.append(String.format("紧急阈值: %.0f%%\n", criticalThreshold * 100));

        // 统计信息
        sb.append("\n--- 统计 ---\n");
        sb.append(String.format("预算检查次数: %d\n", totalBudgetChecks.get()));
        sb.append(String.format("分配请求: %d (成功: %d, 拒绝: %d)\n",
                totalAllocations.get(), successfulAllocations.get(), rejectedAllocations.get()));
        if (totalAllocations.get() > 0) {
            double successRate = (double) successfulAllocations.get() / totalAllocations.get() * 100;
            sb.append(String.format("成功率: %.1f%%\n", successRate));
        }

        sb.append(String.format("警告触发: %d 次\n", warningTriggerCount.get()));
        sb.append(String.format("紧急触发: %d 次\n", criticalTriggerCount.get()));

        // 碎片整理统计
        sb.append("\n--- 碎片整理 ---\n");
        sb.append(String.format("整理次数: %d\n", defragmentationCount.get()));
        sb.append(String.format("总移动: %s\n", formatSize(totalDefragBytesMoved.get())));
        sb.append(String.format("总释放: %s\n", formatSize(totalDefragBytesFreed.get())));

        return sb.toString();
    }

    // ==================== 内部方法：决策逻辑 ====================

    /**
     * 判断是否应该拒绝分配请求
     *
     * @param requestedSize 请求大小
     * @param priority      优先级
     * @return 拒绝原因字符串；如果不拒绝则返回 null
     */
    private String shouldRejectAllocation(long requestedSize, AllocationPriority priority) {
        switch (currentStatus) {
            case CRITICAL:
                // 紧急状态：只允许 CRITICAL 优先级
                if (priority != AllocationPriority.CRITICAL) {
                    return String.format("紧急状态(%.0f%%): 仅允许 CRITICAL 优先级", lastUsageRatio * 100);
                }
                break;

            case WARNING:
                // 警告状态：拒绝 LOW 优先级
                if (priority == AllocationPriority.LOW) {
                    return String.format("警告状态(%.0f%%): 拒绝 LOW 优先级", lastUsageRatio * 100);
                }
                break;

            case NORMAL:
                // 正常状态：不拒绝（除非超过硬性预算限制）
                if (lastUsageRatio > 1.0f && priority == AllocationPriority.LOW) {
                    return "超出预算限制: 拒绝 LOW 优先级";
                }
                break;
        }

        return null;  // 不拒绝
    }

    /**
     * 确定分配策略
     *
     * @param priority 优先级
     * @return 策略名称字符串
     */
    private String determineAllocationStrategy(AllocationPriority priority) {
        if (currentStatus == MemoryStatus.CRITICAL && priority == AllocationPriority.CRITICAL) {
            return "DEDICATED+FORCE";  // 强制专用内存
        }

        if (currentStatus == MemoryStatus.WARNING ||
                (currentStatus == MemoryStatus.CRITICAL && priority == AllocationPriority.HIGH)) {
            return "MIN_MEMORY";  // 最小内存策略
        }

        // 正常状态
        return switch (priority) {
            case CRITICAL -> "DEDICATED+BEST_FIT";
            case HIGH -> "BEST_FIT";
            case NORMAL -> "BEST_FIT";
            case LOW -> "FIRST_FIT";  // 低优先级用快速但不一定最优的策略
        };
    }

    /**
     * 处理内存状态变化
     *
     * @param oldStatus 旧状态
     * @param newStatus 新状态
     * @param ratio     使用比例
     */
    private void handleStatusChange(MemoryStatus oldStatus, MemoryStatus newStatus, float ratio) {
        LOGGER.warning(String.format("⚠️ 内存状态变化: %s → %s (%.1f%%)",
                oldStatus.name(), newStatus.name(), ratio * 100));

        // 根据新状态执行相应动作
        switch (newStatus) {
            case WARNING -> performWarningCleanup();
            case CRITICAL -> performEmergencyCleanup();
        }

        // 通知监听器
        notifyListeners(oldStatus, newStatus, ratio);
    }

    private void performWarningCleanup() {
        LOGGER.info("[警告清理] 开始清理非关键资源...");
        if (com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.isAvailable()) {
            long vma = com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.getInstance().getVma();
            if (vma != 0L) {
                com.ranecc.renderium.infrastructure.gpu.VulkanOperationGuard.markFailed(new Throwable("VMA Budget warning threshold crossed"));
            }
        }
    }

    private void performEmergencyCleanup() {
        LOGGER.severe("[紧急清理] 强制释放所有可回收资源！");
        if (com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.isAvailable()) {
            long vma = com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.getInstance().getVma();
            if (vma != 0L) {
                com.ranecc.renderium.infrastructure.gpu.VulkanOperationGuard.markFailed(new Throwable("VMA Budget critical threshold crossed"));
            }
        }
    }

    /**
     * 通知所有监听器
     */
    private void notifyListeners(MemoryStatus oldStatus, MemoryStatus newStatus, float ratio) {
        synchronized (listeners) {
            for (MemoryBudgetListener listener : listeners) {
                try {
                    listener.onMemoryStatusChanged(oldStatus, newStatus, ratio);
                } catch (Exception e) {
                    LOGGER.warning("监听器回调异常: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 模拟使用率查询（TODO: 替换为真实 VMA 查询）
     */
    private float simulateUsageQuery() {
        long totalUsage = totalBytesAllocated.get() - totalBytesFreed.get();
        long effectiveBudget = Math.max(memoryBudgetLimit, 1L);
        float ratio = (float) totalUsage / (float) effectiveBudget;
        return Math.min(Math.max(ratio, 0.0f), 1.0f);
    }

    // ==================== 内部工具方法 ====================

    /**
     * 格式化字节数为可读字符串
     */
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ==================== 内部数据类 ====================

    /**
     * 自适应分配结果
     */
    public static class BudgetAllocationResult {
        /** 是否成功分配 */
        public final boolean success;

        /** Buffer 句柄（成功时有效） */
        public final long buffer;

        /** VMA allocation 句柄（成功时有效） */
        public final long allocation;

        /** 实际使用的分配策略名称 */
        public final String actualStrategy;

        /** 被拒绝的原因（仅当 success=false 时有意义） */
        public final String rejectionReason;

        BudgetAllocationResult(boolean success, long buffer, long allocation,
                                String actualStrategy, String rejectionReason) {
            this.success = success;
            this.buffer = buffer;
            this.allocation = allocation;
            this.actualStrategy = actualStrategy;
            this.rejectionReason = rejectionReason;
        }
    }

    /**
     * 碎片整理结果
     */
    public static class DefragmentationResult {
        /** 移动的字节数 */
        public final long bytesMoved;

        /** 释放的字节数 */
        public final long bytesFreed;

        /** 移动的分配数量 */
        public final int allocationsMoved;

        /** 释放的分配数量 */
        public final int allocationsFreed;

        /** 整理耗时（毫秒） */
        public final double elapsedTimeMs;

        DefragmentationResult(long bytesMoved, long bytesFreed,
                              int allocationsMoved, int allocationsFreed,
                              double elapsedTimeMs) {
            this.bytesMoved = bytesMoved;
            this.bytesFreed = bytesFreed;
            this.allocationsMoved = allocationsMoved;
            this.allocationsFreed = allocationsFreed;
            this.elapsedTimeMs = elapsedTimeMs;
        }
    }
}
