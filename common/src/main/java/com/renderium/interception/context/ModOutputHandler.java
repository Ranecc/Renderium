// Renderium - Blaze3D 拦截层系统
// 模组输出处理器接口 - 处理第三方模组的渲染输出

package com.renderium.interception.context;

/**
 * 模组输出处理器接口
 * <p>
 * 定义处理第三方模组（如 Sodium、Iris、Oculus）渲染输出的策略。
 * 当 {@link PreBlaze3DInterceptor} 检测到模组输出时，
 * 将调用对应的处理器进行处理。
 *
 * <h3>设计模式：</h3>
 * <p>使用策略模式（Strategy Pattern），将不同模组的处理逻辑
 * 封装到独立的实现中，符合开放封闭原则（OCP）。
 *
 * <h3>使用示例：</h3>
 * <pre>
 * // Sodium 输出处理器
 * public class SodiumModHandler implements ModOutputHandler {
 *     &#64;Override
 *     public void handleOutput(ModOutputContext context) {
 *         // 处理 Sodium 的自定义 FBO 输出
 *         long fbo = context.getFboHandle();
 *         interceptSodiumFBO(fbo);
 *     }
 * }
 *
 * // 注册到前拦截层
 * preInterceptor.registerModHandler("sodium", new SodiumModHandler());
 * </pre>
 *
 * @see PreBlaze3DInterceptor#registerModHandler(String, ModOutputHandler)
 * @see ModOutputContext
 * @since 5.1.0
 */
public interface ModOutputHandler {

    /**
     * 处理模组的渲染输出
     * <p>
     * 当检测到模组的渲染输出时调用此方法。
     * 实现类应根据模组类型执行相应的处理逻辑：
     * <ul>
     *   <li><b>Sodium</b>：拦截自定义 FBO，读取颜色/深度缓冲</li>
     *   <li><b>Iris</b>：处理光影着色器的输出，兼容后处理管线</li>
     *   <li><b>Oculus</b>：处理 VR 双眼渲染输出</li>
     * </ul>
     *
     * @param context 模组输出上下文（包含 FBO 句柄、纹理、分辨率等信息）
     */
    void handleOutput(ModOutputContext context);

    /**
     * 获取处理器支持的模组 ID
     * <p>
     * 返回此处理器能够处理的模组标识符。
     * 用于验证注册时的模组匹配性。
     *
     * @return 模组 ID 字符串（如 "sodium"、"iris"、"oculus"）
     */
    String getSupportedModId();
}
