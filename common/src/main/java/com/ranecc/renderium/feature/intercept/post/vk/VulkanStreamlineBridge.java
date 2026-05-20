package com.ranecc.renderium.feature.intercept.post.vk;

import java.util.logging.Logger;

/**
 * TODO [REVIEW] 桩类 - Vulkan Streamline 桥接器
 * 连接 Vulkan API 与 NVIDIA Streamline 的桥接层
 *
 * <p>当前为桩实现，未连接实际 Streamline SDK。所有连接状态均为 false。
 */
public class VulkanStreamlineBridge {
    private static final Logger LOGGER = Logger.getLogger(VulkanStreamlineBridge.class.getName());

    private volatile boolean connected = false;

    public VulkanStreamlineBridge() {
    }

    /** 检查 Vulkan 连接是否建立 */
    public boolean isConnected() { return connected; }

    /** 建立 Vulkan Streamline 连接（桩实现，不会真正连接） */
    public boolean connect() {
        LOGGER.warning("VulkanStreamlineBridge 为桩实现，未连接实际 Streamline SDK");
        this.connected = false; // 不假装已连接
        return false;
    }

    /** 断开连接并释放资源 */
    public void disconnect() { this.connected = false; }
}
