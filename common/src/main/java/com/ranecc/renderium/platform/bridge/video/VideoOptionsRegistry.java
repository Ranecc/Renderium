package com.ranecc.renderium.platform.bridge.video;

/**
 * 视频选项注册表（线程安全存储）
 *
 * <p>存储和管理所有视频设置选项的注册信息。
 * 提供全局变更检测，供 UI 层判断是否有未保存的修改。
 */
public final class VideoOptionsRegistry {

    /** 是否有任何选项被修改（volatile 保证线程可见性） */
    private static volatile boolean anyOptionChanged;

    private VideoOptionsRegistry() {
    }

    /**
     * 检查是否有任何选项发生了修改
     *
     * @return true 如果有至少一个选项被修改过
     */
    public static boolean anyOptionChanged() {
        return anyOptionChanged;
    }

    /**
     * 标记选项已变更
     */
    public static void markChanged() {
        anyOptionChanged = true;
    }

    /**
     * 清除所有变更标记
     */
    public static void clearChanged() {
        anyOptionChanged = false;
    }
}
