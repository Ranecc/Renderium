package com.ranecc.renderium.feature.intercept.handler;

/**
 * TODO [REVIEW] 桩接口 - 第三方模组输出处理器接口
 * 用于处理来自其他模组的输出数据
 */
public interface ModOutputHandler {
    /**
     * 处理模组输出
     * @param output 输出数据对象
     */
    void handleOutput(Object output);

    /**
     * 检查是否支持给定类型的输出
     * @param type 输出数据类型
     * @return 是否支持
     */
    boolean supportsType(Class<?> type);
}
