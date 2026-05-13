// 迁移自: snapshot-1.1.0: com\ranecc\renderium\domain\service\algorithm\impl\NativeBfsStrategy.java
// 迁移目标: com.ranecc.renderium.domain.service.algorithm
// 迁移规则: 优先使用 snapshot 1.1.0 版本，修改 package/import，保持业务逻辑不变
// v1.1.0 修复: Object → BfsFFIAdapter (类型安全)

package com.ranecc.renderium.domain.service.algorithm.impl;

import com.ranecc.renderium.domain.service.algorithm.AlgorithmStrategy;
import com.ranecc.renderium.domain.service.algorithm.BfsInput;
import com.ranecc.renderium.domain.service.algorithm.BfsFFIAdapter;
import java.util.logging.Logger;

/**
 * Native BFS 遮挡剔除代理策略
 *
 * <p>作为 C++ 原生实现的 Java 代理，
 * 将算法调用委托给底层 FFI 适配器。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>构造时接受 {@link BfsFFIAdapter} 接口（类型安全，避免循环依赖）</li>
 *   <li>内部委托调用，自身不包含任何 JNI/FFI 代码</li>
 *   <li>若适配器为 null 或调用失败，返回原始输入（降级安全）</li>
 * </ul>
 *
 * <h3>架构位置</h3>
 * <pre>
 * Domain Layer (本类)
 *     ↓ 依赖接口 (编译时)
 * Infrastructure Layer (BfsOcclusionFFIAdapter 实现)
 *     ↓ 调用 Native
 * C++ BFS Algorithm
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // DI 容器注入具体实现
 * BfsFFIAdapter adapter = new BfsOcclusionFFIAdapter(loader);
 * NativeBfsStrategy strategy = new NativeBfsStrategy(adapter, 256);
 * BfsInput result = strategy.execute(input);
 * }</pre>
 *
 * @see BfsFFIAdapter
 * @see com.ranecc.renderium.infrastructure.nativeLib.ffi.BfsOcclusionFFIAdapter
 * @since 1.1.0 (v1.1.0: 类型安全重构)
 */
public final class NativeBfsStrategy implements AlgorithmStrategy<BfsInput> {

    private static final Logger LOGGER = Logger.getLogger(NativeBfsStrategy.class.getName());

    /** FFI 适配器引用（类型安全接口） */
    private final BfsFFIAdapter ffiAdapter;

    /** 最大区块数（用于 Native 内存预分配） */
    private final int maxSections;

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    /**
     * 创建 Native BFS 策略（类型安全版本）
     *
     * @param ffiAdapter  FFI 适配器实例（{@link BfsFFIAdapter} 接口），可为 null 但 execute 将降级
     * @param maxSections 最大区块数
     */
    public NativeBfsStrategy(BfsFFIAdapter ffiAdapter, int maxSections) {
        this.ffiAdapter = ffiAdapter;
        this.maxSections = maxSections;
    }

    /**
     * 初始化 Native 策略
     *
     * <p>验证 FFI 适配器可用性并标记初始化状态。
     *
     * @return true 如果初始化成功
     */
    public boolean initialize() {
        if (ffiAdapter == null) {
            LOGGER.warning("[NativeBfs] 初始化失败: ffiAdapter 为 null");
            return false;
        }

        if (!ffiAdapter.isAvailable()) {
            LOGGER.warning("[NativeBfs] 初始化失败: FFI adapter 不可用");
            return false;
        }

        this.initialized = true;
        LOGGER.fine("[NativeBfs] 初始化成功, maxSections=" + maxSections);
        return true;
    }

    /**
     * 执行 Native BFS 可见性计算
     *
     * <p>委托给 FFI 适配器执行实际计算。
     * 若未初始化或适配器不可用，返回原输入并记录警告。
     *
     * @param input BfsInput - 算法输入
     * @return BfsInput - 计算结果（可能由 FFI 层直接修改输入缓冲区）
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

            BfsInput result = ffiAdapter.execute(input);

            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000L;
            LOGGER.fine(String.format("[NativeBfs] 完成: time=%dms", elapsedMs));

            return result;

        } catch (Exception e) {
            LOGGER.warning(String.format("[NativeBfs] 执行异常: %s", e.getMessage()));
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000L;
            LOGGER.fine(String.format("[NativeBfs] 异常耗时: time=%dms", elapsedMs));
            return input;
        }
    }

    /** 检查是否已初始化 */
    public boolean isInitialized() { return initialized; }

    /** 获取最大区块数 */
    public int getMaxSections() { return maxSections; }

    /** 获取 FFI 适配器实例（供测试使用） */
    public BfsFFIAdapter getFfiAdapter() { return ffiAdapter; }
}
