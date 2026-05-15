// Renderium - Effect Pipeline Interface
// 效果管线接口定义 - 用于管理后处理效果链

package com.ranecc.renderium.platform.backend;

import com.ranecc.renderium.None;
import com.ranecc.renderium.domain.enums.EffectType;
import java.util.List;
import java.util.logging.Logger;

/**
 * 效果管线接口
 *
 * <p>定义后处理效果的管理接口，用于：
 * <ul>
 *   <li>效果的注册与注销</li>
 *   <li>效果的启用与禁用</li>
 *   <li>效果参数的动态调整</li>
 *   <li>渲染帧数据的传递</li>
 * </ul>
 *
 * <p><b>实现说明：</b>
 * 此接口为存根实现，具体的管线逻辑应在 Vulkan/OpenGL 后端中实现。
 * 目前仅提供基本的接口定义，确保编译通过。
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see EffectType
 * @see EffectParameters
 */
public interface EffectPipeline {

    /**
     * 注册效果到管线
     *
     * @param effectType 效果类型
     * @return true 如果注册成功
     */
    boolean registerEffect(EffectType effectType);

    /**
     * 从管线注销效果
     *
     * @param effectType 效果类型
     * @return true 如果注销成功
     */
    boolean unregisterEffect(EffectType effectType);

    /**
     * 启用指定效果
     *
     * @param effectType 效果类型
     * @return true 如果启用成功
     */
    boolean enableEffect(EffectType effectType);

    /**
     * 禁用指定效果
     *
     * @param effectType 效果类型
     * @return true 如果禁用成功
     */
    boolean disableEffect(EffectType effectType);

    /**
     * 检查效果是否已启用
     *
     * @param effectType 效果类型
     * @return true 如果效果已启用
     */
    boolean isEffectEnabled(EffectType effectType);

    /**
     * 设置效果参数
     *
     * @param effectType  效果类型
     * @param parameters  效果参数
     * @return true 如果设置成功
     */
    boolean setEffectParameters(EffectType effectType, EffectParameters parameters);

    /**
     * 获取效果参数
     *
     * @param effectType 效果类型
     * @return 效果参数，如果效果不存在则返回 null
     */
    EffectParameters getEffectParameters(EffectType effectType);

    /**
     * 处理渲染帧数据
     *
     * <p>将当前帧的数据传递给效果管线进行处理。
     * 这是每帧调用的核心方法。
     *
     * @param frameData 渲染帧数据
     * @return true 如果处理成功
     */
    boolean processFrame(FrameData frameData);

    /**
     * 重置管线状态
     *
     * <p>清除所有已注册的效果和状态，恢复到初始状态。
     */
    void reset();

    /**
     * 检查管线是否可用
     *
     * @return true 如果管线已初始化且可用
     */
    boolean isAvailable();

    /**
     * 检查管线是否为空（没有注册任何效果）
     *
     * @return true 如果没有注册任何效果
     */
    default boolean isEmpty() {
        // 默认实现：通过 /* TODO: getRegisteredEffects 待实现 */ java.util.Collections.emptyList().isEmpty() 判断
        return /* TODO: getRegisteredEffects 待实现 */ java.util.Collections.emptyList().isEmpty();
    }

    /**
     * 获取所有已注册的效果列表
     *
     * @return 已注册的效果列表（不可变）
     */
    default List<EffectType> getRegisteredEffects() {
        // TODO: getRegisteredEffects 待实现 - 当前返回空列表
        // 默认实现：返回空列表
        return List.of();
    }

    /**
     * 执行后处理管线（使用 PostProcessor.Context）
     *
     * @param context 后处理上下文（包含帧数据、输出缓冲区和运行模式）
     * @return true 如果执行成功
     */
    default boolean execute(Object context) {
        // TODO: PostProcessor.Context 待实现 - 使用 Object 作为通用上下文
        // 默认实现：记录警告并返回 false
        Logger.getLogger(EffectPipeline.class.getName())
            .warning("execute() not implemented in this EffectPipeline");
        return false;
    }
}
