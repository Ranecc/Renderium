package com.ranecc.renderium.domain.service.algorithm;

/**
 * FFI 适配器接口（Domain Layer 抽象）
 *
 * <p>定义 Domain 层与 Native 层交互的契约，
 * 避免直接依赖 Infrastructure 层的具体实现。
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li><b>接口隔离</b>：Domain 层仅依赖此接口，不依赖具体实现</li>
 *   <li><b>类型安全</b>：编译时检查方法签名，替代 Object + 反射</li>
 *   <li><b>可替换性</b>：可 Mock 测试，可切换实现（JNI/Panama/FFI）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // Domain 层使用
 * BfsFFIAdapter adapter = ...; // 由 DI 容器注入
 * long ctx = adapter.createContext(64, 16, 256.0f);
 * int[] visible = adapter.compute(ctx, cameraPos, viewProj, chunkData);
 * adapter.destroyContext(ctx);
 * }</pre>
 *
 * @param <T> 算法输入类型
 * @since 1.1.0
 */
public interface FFIAdapter<T> {

    /**
     * 初始化 FFI 适配器
     *
     * @return true 如果初始化成功
     */
    boolean initialize();

    /**
     * 检查适配器是否可用
     *
     * @return true 如果已初始化且可用
     */
    boolean isAvailable();

    /**
     * 执行算法计算
     *
     * @param input 算法输入数据
     * @return 计算结果（可能修改原输入或返回新对象）
     */
    T execute(T input);

    /**
     * 释放资源
     */
    void dispose();
}
