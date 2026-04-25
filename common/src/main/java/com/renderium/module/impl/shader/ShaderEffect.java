// Renderium - 光影模块
// 着色器效果接口

package com.renderium.module.impl.shader;

/**
 * 着色器效果接口
 * <p>
 * 定义单个后处理效果的契约。
 *
 * @author Renderium Team
 * @since 2.0.0
 */
public interface ShaderEffect {

    /**
     * 获取效果名称
     */
    String getName();

    /**
     * 执行效果
     *
     * @param inputTexture  输入纹理
     * @param outputTexture 输出纹理
     */
    void execute(long inputTexture, long outputTexture);

    /**
     * 是否启用
     */
    default boolean isEnabled() { return true; }
}
