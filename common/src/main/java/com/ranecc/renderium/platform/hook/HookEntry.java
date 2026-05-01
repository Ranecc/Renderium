package com.ranecc.renderium.platform.hook;

/**
 * Hook 条目值类型 — 缓存友好的热路径数据结构
 *
 * <p><b>设计原则</b>:
 * <ul>
 *   <li>不可变引用（hook 实例 + 元数据）+ 可变统计（计数器）</li>
 *   <li>紧凑内存布局：一个 HookEntry 约 40-48 字节（对象头 16B + 字段 ~32B）</li>
 *   <li>{@link #isActive()} 是热路径中最频繁调用的方法，必须极快 (&lt; 1ns）</li>
 * </ul>
 *
 * <h3>内存布局</h3>
 * <pre>
 * ┌─────────────────────────────────────────────┐
 * │ [不可变字段 — 冷路径设置，热路径只读]        │
 * │   index (int, 4B)     — 数组索引            │
 * │   name (ref, 4-8B)    — 调试名称            │
 * │   instance (ref, 4-8B) — hook 回调实例       │
 * │   bitMask (int, 4B)   — 启用位掩码           │
 * ├─────────────────────────────────────────────┤
 * │ [可变字段 — volatile 保证跨线程可见性]        │
 * │   active (boolean volatile, 1B+padding)      │
 * │   totalCalls (long volatile, 8B)             │
 * │   totalNanos (long volatile, 8B)             │
 * └─────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>线程安全模型</h3>
 * <ul>
 *   <li>{@code active} 使用 volatile 写保证冷→热路径的可见性</li>
 *   <li>{@code totalCalls} / {@code totalNanos} 使用 volatile 语义，
 *       但不保证原子性（统计计数器允许少量丢失，近似值即可接受）</li>
 *   <li>不可变字段（index/name/instance/bitMask）由构造函数设置后永不改变，
 *       通过 {@link HookDispatcher} 的版本号机制保证发布安全</li>
 * </ul>
 *
 * @see HookDispatcher — 持有 HookEntry[] 数组的调度核心
 * @since 2.0.0
 */
public final class HookEntry {

    // ==================== 不可变字段（冷路径设置，热路径只读）====================

    /**
     * Hook 唯一标识符（在 {@link HookDispatcher#hooks} 数组中的索引）
     *
     * <p>取值范围 [0, HOOK_COUNT)，由 {@link HookDispatcher} 在注册时分配。
     * 用于位图运算：(1 &lt;&lt; index) 快速判断是否启用。
     */
    private final int index;

    /**
     * Hook 名称（用于日志、调试和性能报告）
     *
     * <p>例如 "DrawIndexed"、"SetPipeline" 等。
     * 仅在冷路径（日志输出、调试界面）中读取，热路径不访问此字段。
     */
    private final String name;

    /**
     * Hook 实例（实际回调对象）
     *
     * <p>具体类型取决于 hook 点，使用 Object 类型避免泛型擦除后的类型转换开销。
     * 热路径中通过强转调用具体接口方法：
     * <ul>
     *   <li>DRAW_INDEXED → {@link DrawIndexedHook}</li>
     *   <li>SET_PIPELINE → {@link SetPipelineHook}</li>
     *   <li>BIND_TEXTURE → {@link BindTextureHook}</li>
     *   <li>... 等等</li>
     * </ul>
     */
    private final Object instance;

    /**
     * 启用位掩码位置 (1 &lt;&lt; index)
     *
     * <p>预计算值，避免热路径中重复计算位移操作。
     * 用于 {@link HookDispatcher#enabledFlags} 的快速位测试：
     * <pre>
     * if ((enabledFlags & entry.bitMask) == 0) return true; // ~1ns 快速跳过
     * </pre>
     */
    private final int bitMask;

    // ==================== 可变字段（volatile — 热路径读取，冷路径写入）====================

    /**
     * 是否激活（启用状态）
     *
     * <p>false 时 dispatcher 直接跳过，不调用回调。
     * volatile 保证冷路径的 setActive() 写入对热路径的 isActive() 读取立即可见。
     *
     * <p><b>热路径访问模式</b>：每次 dispatch 前先检查此字段（~1ns）。
     */
    private volatile boolean active = true;

    /**
     * 总调用次数（性能统计用）
     *
     * <p>热路径中通过 {@link #recordCall(long)} 递增。
     * long 类型的非原子递增可能导致撕裂读（torn read），
     * 但对于统计用途可接受（近似值即可）。
     *
     * <p><b>非 volatile 优化</b>：统计数据不需要实时精确，
     * 冷路径读取时容忍短暂的过期值。
     */
    private volatile long totalCalls = 0;

    /**
     * 总耗时纳秒（性能统计用）
     *
     * <p>与 {@link #totalCalls} 配合计算平均耗时：
     * avgNs = totalNanos / totalCalls
     */
    private volatile long totalNanos = 0;

    // ==================== 构造函数 ====================

    /**
     * 创建 Hook 条目
     *
     * <p>构造后不可变字段（index/name/instance/bitMask）即固定，
     * 仅 active/totalCalls/totalNanos 可在生命周期内变更。
     *
     * @param index   Hook 在调度数组中的索引（必须 >= 0）
     * @param name    Hook 名称（用于日志和调试，不应为 null）
     * @param instance Hook 回调实例（可以为 null 表示空槽位）
     * @throws IllegalArgumentException 如果 index 为负或 name 为 null
     */
    public HookEntry(int index, String name, Object instance) {
        if (index < 0) {
            throw new IllegalArgumentException("Hook index must be >= 0, got: " + index);
        }
        if (name == null) {
            throw new IllegalArgumentException("Hook name must not be null");
        }

        this.index = index;
        this.name = name;
        this.instance = instance;
        // 预计算位掩码：(1 << index)
        this.bitMask = 1 << index;
    }

    // ==================== 热路径 API（每帧数百~数千次调用）====================

    /**
     * 快速判断是否激活 — 热路径核心方法
     *
     * <p><b>性能特征</b>: 目标 &lt; 1ns（一次 volatile boolean 读 + 一次 null 检查）。
     * 这是整个 dispatch 路径上最频繁的条件判断。
     *
     * <p>返回 false 时 dispatcher 应立即跳过本次调用，不做任何额外操作。
     *
     * @return true 如果当前 hook 已启用且实例非 null；false 表示应跳过
     */
    public boolean isActive() {
        return active && instance != null;
    }

    /**
     * 获取 hook 实例（需要强转到具体 Hook 接口类型）
     *
     * <p><b>调用方责任</b>：调用者需知道此条目对应的 hook 类型并正确强转。
     * 类型安全由 {@link HookDispatcher} 的注册逻辑保证。
     *
     * @return hook 回调对象，可能为 null（空槽位）
     */
    public Object getInstance() {
        return instance;
    }

    /**
     * 获取索引位置
     *
     * @return 在 {@link HookDispatcher#hooks} 调度数组中的索引 [0, HOOK_COUNT)
     */
    public int getIndex() {
        return index;
    }

    /**
     * 获取预计算的位掩码 (1 &lt;&lt; index)
     *
     * <p>用于快速位图测试，避免重复位移计算。
     *
     * @return int 位掩码值
     */
    public int getBitMask() {
        return bitMask;
    }

    // ==================== 冷路径管理 API（初始化/配置时调用）====================

    /**
     * 设置激活状态
     *
     * <p><b>冷路径方法</b>：用于运行时动态禁用/启用 hook（无需注销/重新注册）。
     * 比 unregister/register 更快（不改变 hook 引用，仅翻转标志位）。
     *
     * <p>volatile 写保证热路径下次读取时立即可见新值。
     *
     * @param enabled 是否启用（true=启用并参与调度，false=暂停但保留注册）
     */
    public void setActive(boolean enabled) {
        this.active = enabled;
    }

    /**
     * 记录一次调用（由 Dispatcher 在回调执行后自动调用）
     *
     * <p><b>热路径方法</b>：每次回调执行完毕后调用。
     * 接受非原子更新的精度损失（统计近似值即可）。
     *
     * @param durationNs 本次回调执行的耗时（纳秒），由 System.nanoTime() 差值获得
     */
    public void recordCall(long durationNs) {
        this.totalCalls++;
        this.totalNanos += durationNs;
    }

    // ==================== 统计查询 API（冷路径：报告和调试）====================

    /**
     * 获取总调用次数
     *
     * @return long 累计调用次数（近似值，可能因非原子更新有少量偏差）
     */
    public long getTotalCalls() {
        return totalCalls;
    }

    /**
     * 获取总执行耗时
     *
     * @return long 累计耗时（纳秒，近似值）
     */
    public long getTotalNanos() {
        return totalNanos;
    }

    /**
     * 获取平均单次调用耗时
     *
     * @return double 平均耗时（纳秒），如果无调用记录返回 0.0
     */
    public double getAverageNs() {
        if (totalCalls == 0) {
            return 0.0;
        }
        return (double) totalNanos / totalCalls;
    }

    /**
     * 获取性能统计摘要
     *
     * <p><b>冷路径方法</b>：用于控制台输出、调试面板、日志记录。
     *
     * @return 格式化字符串 "name: N calls, avg Xns, total Yμs"
     */
    public String getStatsSummary() {
        if (totalCalls == 0) {
            return name + ": 0 calls";
        }
        double avgNs = getAverageNs();
        double totalUs = totalNanos / 1000.0;
        return String.format("%s: %d calls, avg %.1f ns, total %.1f μs",
                name, totalCalls, avgNs, totalUs);
    }

    /**
     * 获取 Hook 名称
     *
     * @return String hook 名称
     */
    public String getName() {
        return name;
    }

    /**
     * 重置所有统计计数器
     *
     * <p>用于开始新的性能分析周期（如切换场景后重置基线）。
     */
    public void resetStats() {
        this.totalCalls = 0;
        this.totalNanos = 0;
    }

    @Override
    public String toString() {
        return String.format("HookEntry[%d] name=%s active=%s calls=%d avg=%.1fns",
                index, name, active, totalCalls, getAverageNs());
    }
}
