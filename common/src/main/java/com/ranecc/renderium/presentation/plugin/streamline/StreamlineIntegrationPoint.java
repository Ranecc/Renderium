// Renderium - Blaze3D 优化器插件系统
// Streamline 集成点接口 - 定义具体的注入逻辑

package com.ranecc.renderium.presentation.plugin.streamline;
import com.ranecc.renderium.presentation.plugin.Blaze3DOptimization;
import com.ranecc.renderium.presentation.plugin.PluginContext;

/**
 * Streamline 集成点接口
 * <p>
 * 定义 Streamline SDK 与 Blaze3D 渲染管线的具体集成逻辑。
 * 每个 {@link StreamlineIntegrationStrategy} 对应一个或多个此接口的实现。
 *
 * <h2>职责划分：</h2>
 * <ul>
 *   <li>确定注入位置（Mixin 目标）</li>
 *   <li>管理输入资源（颜色、深度、运动向量）</li>
 *   <li>调用 Streamline SDK 进行评估</li>
 *   <li>处理输出重定向</li>
 * </ul>
 *
 * <h2>生命周期：</h2>
 * <ol>
 *   <li>{@link #getStrategy()} - 返回使用的集成策略</li>
 *   <li>{@link #initialize(PluginContext)} - 初始化资源和 SDK 连接</li>
 *   <li>{@link #inject()} - 执行 Mixin 注入或 Hook 安装</li>
 *   <li>{@link #evaluateFrame()} - 每帧调用 Streamline 评估</li>
 *   <li>{@link #remove()} - 移除所有注入，恢复原始状态</li>
 * </ol>
 *
 * @see StreamlineIntegrationStrategy
 * @see Blaze3DOptimization
 * @author Renderium Team
 * @since 1.0.0
 */
public interface StreamlineIntegrationPoint extends Blaze3DOptimization {

    /**
     * 获取此集成点使用的策略
     *
     * @return StreamlineIntegrationStrategy 枚举值
     */
    StreamlineIntegrationStrategy getStrategy();

    /**
     * 获取所需的输入类型集合
     * <p>告诉调用者此集成点需要哪些输入缓冲区。
     *
     * @return 输入类型数组，如 COLOR, DEPTH, MOTION_VECTORS
     */
    default StreamlineInputType[] getRequiredInputs() {
        // 默认：基础超分辨率所需的最小输入集
        return new StreamlineInputType[]{
                StreamlineInputType.COLOR,
                StreamlineInputType.DEPTH,
                StreamlineInputType.MOTION_VECTORS
        };
    }

    /**
     * 注入到渲染管线中
     * <p>执行实际的 Mixin 注入或方法替换操作。
     * 此方法在 {@link #initialize(PluginContext)} 之后调用。
     *
     * @return 注入成功返回 true
     */
    boolean inject();

    /**
     * 评估当前帧
     * <p>每帧由渲染循环调用，执行 Streamline SDK 的评估逻辑。
     *
     * @param frameData 当前帧的数据上下文
     * @return 评估成功返回 true
     */
    boolean evaluateFrame(StreamlineFrameData frameData);

    /**
     * 移除所有注入
     * <p>完全恢复原始渲染管线状态，
     * 包括移除 Mixin、恢复原始方法等。
     */
    void remove();
}
