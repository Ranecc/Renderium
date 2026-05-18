// Renderium - Vulkan 桥接器
// 从 VulkanDeviceHolder 中缓存的 s7 VulkanDevice 提取 LWJGL VkDevice 对象

package com.ranecc.renderium.feature.blaze3d.aggressive;

import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import org.lwjgl.vulkan.VkDevice;

final class RenderiumVulkanBridge {

    private RenderiumVulkanBridge() {}

    /**
     * 从 VulkanDeviceHolder 获取 VkDevice。
     * holder 中缓存了 s7 VulkanDevice 对象，其 vkDevice() 返回 LWJGL VkDevice。
     */
    static VkDevice getDevice() {
        if (!VulkanDeviceHolder.isAvailable()) return null;
        Object vulkanDevice = VulkanDeviceHolder.getInstance().getVulkanDevice();
        if (vulkanDevice == null) return null;
        try {
            return (VkDevice) vulkanDevice.getClass().getMethod("vkDevice").invoke(vulkanDevice);
        } catch (Exception e) {
            return null;
        }
    }
}
