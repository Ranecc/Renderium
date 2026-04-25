// Renderium - Vulkan 后端激活事件系统
// 用于监听 Vulkan 后端的激活状态变化，支持延迟初始化

package com.renderium.graphics.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Vulkan 后端激活事件管理器。
 *
 * <p>该类实现了一个简单的事件系统，用于监听 Vulkan 后端的激活状态变化。
 * 当 Vulkan 后端从不可用变为可用时（例如通过外部模组注入），会触发激活事件，
 * 所有注册的监听器都会收到通知。</p>
 *
 * <h2>使用场景：</h2>
 * <ul>
 *   <li><b>延迟初始化</b>：当游戏启动时 Vulkan 不可用，但后续通过模组注入激活时，
 *       允许组件（如 FBOInteropHandler）重新初始化</li>
 *   <li><b>最小监视器模式</b>：组件在 Vulkan 不可用时进入最小监视器状态，
 *       等待激活事件而不是直接禁用</li>
 * </ul>
 *
 * <h2>示例代码：</h2>
 * <pre>{@code
 * // 注册监听器
 * VulkanActivationEvent.addListener((wasActive, isActive) -> {
 *     if (!wasActive && isActive) {
 *         // Vulkan 刚刚激活，重新初始化组件
 *         myComponent.reinitialize();
 *     }
 * });
 *
 * // 触发激活事件（由 RenderBackendProxy 调用）
 * VulkanActivationEvent.fireActivationEvent(false, true);
 * }</pre>
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public final class VulkanActivationEvent {

    private static final Logger LOGGER = Logger.getLogger("Renderium-VulkanEvent");

    /**
     * 事件监听器接口
     */
    @FunctionalInterface
    public interface Listener {
        /**
         * 当 Vulkan 激活状态发生变化时调用
         *
         * @param wasActive 之前是否激活
         * @param isActive  当前是否激活
         */
        void onActivationChanged(boolean wasActive, boolean isActive);
    }

    /** 监听器列表（线程安全） */
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** 当前 Vulkan 是否激活 */
    private static volatile boolean currentActive = false;

    /** 私有构造函数，防止实例化 */
    private VulkanActivationEvent() {}

    /**
     * 注册监听器
     *
     * @param listener 要注册的监听器
     */
    public static void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
            LOGGER.fine("VulkanActivationEvent listener registered: " + listener.getClass().getName());
        }
    }

    /**
     * 移除监听器
     *
     * @param listener 要移除的监听器
     */
    public static void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
            LOGGER.fine("VulkanActivationEvent listener removed: " + listener.getClass().getName());
        }
    }

    /**
     * 触发激活状态变化事件
     *
     * <p>该方法由 {@link com.renderium.graphics.backend.RenderBackendProxy} 在状态变化时调用。
     * 会通知所有注册的监听器。</p>
     *
     * @param wasActive 之前是否激活
     * @param isActive  当前是否激活
     */
    public static void fireActivationEvent(boolean wasActive, boolean isActive) {
        boolean stateChanged = (wasActive != isActive);

        currentActive = isActive;

        LOGGER.info(String.format(
            "VulkanActivationEvent: wasActive=%s, isActive=%s, changed=%s, listeners=%d",
            wasActive, isActive, stateChanged, listeners.size()
        ));

        // 只在状态真正变化时通知监听器
        if (stateChanged) {
            for (Listener listener : listeners) {
                try {
                    listener.onActivationChanged(wasActive, isActive);
                } catch (Exception e) {
                    LOGGER.warning("Listener threw exception: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 获取当前 Vulkan 是否激活
     *
     * @return true 如果 Vulkan 后端当前处于激活状态
     */
    public static boolean isCurrentActive() {
        return currentActive;
    }

    /**
     * 清除所有监听器
     *
     * <p>通常只在测试或关闭时调用。</p>
     */
    public static void clearListeners() {
        listeners.clear();
        LOGGER.fine("All VulkanActivationEvent listeners cleared");
    }

    /**
     * 获取监听器数量
     *
     * @return 当前注册的监听器数量
     */
    public static int getListenerCount() {
        return listeners.size();
    }
}
