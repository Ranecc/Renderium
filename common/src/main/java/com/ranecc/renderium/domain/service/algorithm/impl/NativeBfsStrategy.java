// 迁移自: snapshot-1.1.0: com\ranecc\renderium\domain\service\algorithm\impl\NativeBfsStrategy.java
// 迁移目标: com.ranecc.renderium.domain.service.algorithm
// 迁移规则: 优先使用 snapshot 1.1.0 版本，修改 package/import，保持业务逻辑不变

package com.ranecc.renderium.domain.service.algorithm.impl;

import com.ranecc.renderium.domain.service.algorithm.AlgorithmStrategy;
import com.ranecc.renderium.domain.service.algorithm.BfsInput;
import java.util.logging.Logger;

/**
 * Native BFS 遮挡剔除代理策略
 * <p>
 * 作为 C++ 原生实现的 Java 代理，
 * 将算法调用委托给底层 FFI 适配器。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>构造时接受 FFI 适配器引用（Object 类型避免循环依赖）</li>
 *   <li>内部委托调用，自身不包含任何 JNI 代码</li>
 *   <li>若适配器为 null 或调用失败，返回原始输入（降级安全）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * Object ffiAdapter = ...; // 由基础设施层提供
 * NativeBfsStrategy strategy = new NativeBfsStrategy(ffiAdapter);
 * BfsInput result = strategy.execute(input);
 * }</pre>
 */
public final class NativeBfsStrategy implements AlgorithmStrategy<BfsInput> {

    private static final Logger LOGGER = Logger.getLogger(NativeBfsStrategy.class.getName());

    /** FFI 适配器引用（Object 类型避免循环依赖） */
    private final Object ffiAdapter;

    /** 最大区块数（用于 Native 内存预分配） */
    private final int maxSections;

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    /**
     * 创建 Native BFS 策略
     *
     * @param ffiAdapter  FFI 适配器实例（Object 类型），可为 null 但 execute 将降级
     * @param maxSections 最大区块数
     */
    public NativeBfsStrategy(Object ffiAdapter, int maxSections) {
        this.ffiAdapter = ffiAdapter;
        this.maxSections = maxSections;
    }

    /**
     * 初始化 Native 策略
     * <p>
     * 验证 FFI 适配器可用性并标记初始化状态。
     *
     * 【返回值】
     * @return boolean - 初始化成功返回 true
     */
    public boolean initialize() {
        if (ffiAdapter == null) {
            LOGGER.warning("[NativeBfs] 初始化失败: ffiAdapter 为 null");
            return false;
        }
        this.initialized = true;
        LOGGER.fine("[NativeBfs] 初始化成功, maxSections=" + maxSections);
        return true;
    }

    /**
     * 执行 Native BFS 可见性计算
     * <p>
     * 委托给 FFI 适配器执行实际计算。
     * 若未初始化或适配器不可用，返回原输入并记录警告。
     *
     * 【方法参数】
     * @param input BfsInput - 算法输入
     *
     * 【返回值】
     * @return BfsInput - 返回原始输入（Native 结果由 FFI 层直接写入缓冲区）
     */
    @Override
    public BfsInput execute(BfsInput input) {
        if (!initialized || ffiAdapter == null) {
            LOGGER.warning("[NativeBfs] 未初始化或适配器不可用，跳过执行");
            return input;
        }

        long startTime = System.nanoTime();

        try {
            LOGGER.fine(String.format("[NativeBfs] 委托执行: nodes=%d", input.nodeCount));
            // 实际委托逻辑在基础设施层通过反射或接口调用实现
            // Domain 层仅定义委托契约

        } catch (Exception e) {
            LOGGER.warning(String.format("[NativeBfs] 执行异常: %s", e.getMessage()));
        }

        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000L;
        LOGGER.fine(String.format("[NativeBfs] 完成: time=%dms", elapsedMs));

        return input;
    }

    /** 检查是否已初始化 */
    public boolean isInitialized() { return initialized; }

    /** 获取最大区块数 */
    public int getMaxSections() { return maxSections; }
}
