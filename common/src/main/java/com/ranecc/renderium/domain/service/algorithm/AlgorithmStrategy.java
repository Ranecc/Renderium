// 迁移自: snapshot-1.1.0: com\ranecc\renderium\domain\service\algorithm\AlgorithmStrategy.java
// 迁移目标: com.ranecc.renderium.domain.service.algorithm
// 迁移规则: 优先使用 snapshot 1.1.0 版本，修改 package/import，保持业务逻辑不变

package com.ranecc.renderium.domain.service.algorithm;
import com.ranecc.renderium.feature.pipeline.strategy.BfsInput;
import com.ranecc.renderium.feature.pipeline.strategy.BfsInput;

/**
 * 算法策略泛型接口
 * <p>
 * 定义算法执行的统一契约，支持策略模式切换不同实现。
 * 典型应用场景：Java 实现与 Native 实现的动态切换。
 *
 * <h3>类型参数</h3>
 * @param <T> 算法输入输出类型（通常为特定领域的值对象）
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * AlgorithmStrategy<BfsInput> strategy = ...;
 * BfsInput result = strategy.execute(input);
 * }</pre>
 */
public interface AlgorithmStrategy<T> {

    /**
     * 执行算法
     *
     * 【方法参数】
     * @param input T - 算法输入数据
     *
     * 【返回值】
     * @return T - 算法执行结果（可能修改原输入或返回新实例）
     */
    T execute(T input);
}
