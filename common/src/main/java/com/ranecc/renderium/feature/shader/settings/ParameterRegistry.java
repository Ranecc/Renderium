package com.ranecc.renderium.feature.shader.settings;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.ranecc.renderium.feature.pipeline.parameter.ParameterKnob;

/**
 * 着色器参数注册表
 * 管理运行时着色器参数的存取
 */
public class ParameterRegistry {
    private static final ParameterRegistry INSTANCE = new ParameterRegistry();

    private final ConcurrentHashMap<String, Object> params = new ConcurrentHashMap<>();

    public static ParameterRegistry getInstance() {
        return INSTANCE;
    }

    /** 注册参数旋钮 */
    public void register(ParameterKnob knob) {
        params.put(knob.getId(), knob);
    }

    /** 根据ID获取参数 */
    public ParameterKnob get(String id) {
        return (ParameterKnob) params.get(id);
    }

    /** 根据ID获取参数值 */
    public Object getParam(String id) { return params.get(id); }

    /** 设置参数值 */
    public void setParam(String id, Object value) { params.put(id, value); }

    /** 检查参数是否存在 */
    public boolean hasParam(String id) { return params.containsKey(id); }

    /** 获取所有参数的不可变快照 */
    public Map<String, Object> getAllParams() { return Map.copyOf(params); }

    /** 清空所有参数 */
    public void clear() { params.clear(); }
}
