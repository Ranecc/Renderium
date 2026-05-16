package com.ranecc.renderium.infrastructure.vulkan.adapter;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXTI;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.LongBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * VulkanDebug - Vulkan 调试/验证层管理器 (26.2-snapshot-3 兼容)
 *
 * <p>策略模式实现，提供 Enabled/Disabled 两种状态。
 * 用于管理 Vulkan Validation Layer 和 Debug Utils Messenger。</p>
 *
 * <h3>设计说明:</h3>
 * <ul>
 *   <li>使用工厂方法创建实例</li>
 *   <li>Disabled 实现零开销抽象（发布模式优化）</li>
 *   <li>Enabled 实现完整的验证层和调试回调功能</li>
 * </ul>
 */
public interface VulkanDebug {

    /**
     * 创建禁用状态的调试器（发布模式使用）
     *
     * @return Disabled 实例，所有方法均为空实现
     */
    static VulkanDebug disabled() { return Disabled.INSTANCE; }

    /**
     * 创建启用状态的调试器（开发模式使用）
     *
     * @return Enabled 实例，包含完整的验证层功能
     */
    static VulkanDebug enabled() { return new Enabled(); }

    /** 是否启用验证层 */
    boolean isEnabled();

    /** 设置 Debug Messenger */
    long setupDebugMessenger(long instance);

    /** 销毁 Messenger */
    void destroy(long instance);

    /** ========== Disabled 实现（零开销） ========== */

    enum Disabled implements VulkanDebug {
        INSTANCE;

        @Override
        public boolean isEnabled() { return false; }

        @Override
        public long setupDebugMessenger(long instance) { return 0L; }

        @Override
        public void destroy(long instance) {}
    }

    /** ========== Enabled 实现 ========== */

    final class Enabled implements VulkanDebug {
        private long debugMessenger = 0L;
        private volatile boolean initialized = false;

        private static final Logger LOGGER = Logger.getLogger(Enabled.class.getName());

        private static final MethodHandle DEBUG_CALLBACK_HANDLE;

        private Object debugCallbackRef;

        static {
            MethodHandle handle;
            try {
                handle = MethodHandles.lookup().findStatic(
                        Enabled.class, "onDebugMessage",
                        MethodType.methodType(int.class, int.class, int.class, long.class, long.class));
            } catch (ReflectiveOperationException e) {
                LOGGER.severe("Failed to initialize debug callback MethodHandle: " + e.getMessage());
                handle = null;
            }
            DEBUG_CALLBACK_HANDLE = handle;
        }

        private static int onDebugMessage(int messageSeverity, int messageType, long pCallbackData, long pUserData) {
            VkDebugUtilsMessengerCallbackDataEXT callbackData =
                    VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
            String message = callbackData.pMessageString();

            Level level;
            if ((messageSeverity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
                level = Level.SEVERE;
            } else if ((messageSeverity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) {
                level = Level.WARNING;
            } else if ((messageSeverity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT) != 0) {
                level = Level.INFO;
            } else {
                level = Level.FINE;
            }

            LOGGER.log(level, "[Vulkan Debug] " + message);
            return VK10.VK_FALSE;
        }

        @Override
        public boolean isEnabled() { return true; }

        @Override
        public long setupDebugMessenger(long instance) {
            if (instance == 0L) return 0L;
            if (initialized && debugMessenger != 0L) return debugMessenger;

            if (DEBUG_CALLBACK_HANDLE == null) {
                LOGGER.severe("Debug callback MethodHandle not initialized");
                return 0L;
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDebugUtilsMessengerCallbackEXTI debugCallback = Enabled::onDebugMessage;
                debugCallbackRef = debugCallback;

                VkDebugUtilsMessengerCreateInfoEXT createInfo =
                        VkDebugUtilsMessengerCreateInfoEXT.calloc(stack);
                createInfo.sType(EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT);
                createInfo.messageSeverity(
                        EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT |
                        EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT |
                        EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
                        EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT);
                createInfo.messageType(
                        EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                        EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                        EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT);
                createInfo.pfnUserCallback(debugCallback);

                LongBuffer pMessenger = stack.mallocLong(1);
                VkInstanceCreateInfo instanceCi = VkInstanceCreateInfo.calloc(stack);
                instanceCi.sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
                VkInstance vkInstance = new VkInstance(instance, instanceCi);
                int result = EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(
                        vkInstance, createInfo, null, pMessenger);

                if (result != VK10.VK_SUCCESS) {
                    LOGGER.severe("vkCreateDebugUtilsMessengerEXT failed with VkResult=" + result);
                    return 0L;
                }

                this.debugMessenger = pMessenger.get(0);
                this.initialized = true;
                LOGGER.fine("Vulkan debug messenger created: 0x" + Long.toHexString(debugMessenger));
                return debugMessenger;
            } catch (Exception e) {
                LOGGER.severe("Failed to setup debug messenger: " + e.getMessage());
                return 0L;
            }
        }

        @Override
        public void destroy(long instance) {
            if (!initialized || debugMessenger == 0L) return;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkInstanceCreateInfo instanceCi = VkInstanceCreateInfo.calloc(stack);
                instanceCi.sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
                VkInstance vkInstance = new VkInstance(instance, instanceCi);
                EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(vkInstance, debugMessenger, null);
            }
            debugMessenger = 0L;
            initialized = false;
            debugCallbackRef = null;
        }

        public long getDebugMessenger() { return debugMessenger; }
        public boolean isInitialized() { return initialized; }
    }
}
