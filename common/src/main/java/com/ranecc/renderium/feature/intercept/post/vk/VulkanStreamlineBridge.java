package com.ranecc.renderium.feature.intercept.post.vk;

/**
 * TODO [REVIEW] 桩类 - Vulkan Streamline 桥接器
 * 连接 Vulkan API 与 NVIDIA Streamline 的桥接层
 */
public class VulkanStreamlineBridge {
    private volatile boolean connected = false;

    public VulkanStreamlineBridge() {
    }

    /** 检查 Vulkan 连接是否建立 */
    public boolean isConnected() { return connected; }

    /** 建立 Vulkan Streamline 连接 */
    public boolean connect() { this.connected = true; return true; }

    /** 断开连接并释放资源 */
    public void disconnect() { this.connected = false; }
}
