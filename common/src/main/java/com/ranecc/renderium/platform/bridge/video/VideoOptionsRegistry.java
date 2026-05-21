package com.ranecc.renderium.platform.bridge.video;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 视频选项注册表（线程安全存储）
 *
 * <p>存储和管理所有视频设置选项的注册信息。
 * 提供全局变更检测，供 UI 层判断是否有未保存的修改。
 *
 * <h3>线程安全：</h3>
 * <ul>
 *   <li>changeListeners 使用 ConcurrentHashMap，支持并发注册和查询</li>
 *   <li>dirty 标记使用 volatile，保证多线程可见性</li>
 * </ul>
 *
 * <h3>方法签名：</h3>
 * <ul>
 *   <li>{@link #registerChangeListener(String, Consumer)} - 注册选项变更监听器</li>
 *   <li>{@link #notifyChanged(String, Object)} - 通知选项变更</li>
 *   <li>{@link #isDirty()} - 检查是否有任何选项被修改</li>
 *   <li>{@link #clearDirty()} - 清除脏标记</li>
 * </ul>
 */
public final class VideoOptionsRegistry {

    /** 选项变更监听器列表（线程安全） */
    private final Map<String, Consumer<Object>> changeListeners = new ConcurrentHashMap<>();

    /** 是否有任何选项被修改（volatile 保证线程可见性） */
    private volatile boolean dirty;

    /**
     * 默认构造函数
     */
    public VideoOptionsRegistry() {}

    /**
     * 注册选项变更监听器
     *
     * @param key 选项键（非 null）
     * @param listener 变更回调（非 null）
     */
    public void registerChangeListener(String key, Consumer<Object> listener) {
        if (key != null && listener != null) {
            changeListeners.put(key, listener);
        }
    }

    /**
     * 通知选项变更
     *
     * <p>设置脏标记并触发对应选项的变更监听器。
     *
     * @param key 选项键
     * @param value 新值
     */
    public void notifyChanged(String key, Object value) {
        dirty = true;
        Consumer<Object> listener = changeListeners.get(key);
        if (listener != null) {
            listener.accept(value);
        }
    }

    /**
     * 检查是否有任何选项发生了修改
     *
     * @return true 如果有至少一个选项被修改过
     */
    public boolean isDirty() {
        return dirty;
    }

    /**
     * 清除脏标记
     */
    public void clearDirty() {
        dirty = false;
    }
}
