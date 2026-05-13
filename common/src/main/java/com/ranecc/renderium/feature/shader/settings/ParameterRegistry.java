package com.ranecc.renderium.feature.shader.settings;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TODO [REVIEW] 桩类 - 着色器参数注册表
 * 管理运行时着色器参数的存取
 */
public class ParameterRegistry {
    private final ConcurrentHashMap<String, Object> params = new ConcurrentHashMap<>();

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
