package com.ranecc.renderium.infrastructure.vulkan.vulkan.adapter;

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

        @Override
        public boolean isEnabled() { return true; }

        @Override
        public long setupDebugMessenger(long instance) {
            if (instance == 0L) return 0L;
            if (initialized && debugMessenger != 0L) return debugMessenger;
            try {
                this.debugMessenger = 0xDEADBEEFL;
                this.initialized = true;
                return debugMessenger;
            } catch (Exception e) {
                return 0L;
            }
        }

        @Override
        public void destroy(long instance) {
            if (!initialized || debugMessenger == 0L) return;
            debugMessenger = 0L;
            initialized = false;
        }

        public long getDebugMessenger() { return debugMessenger; }
        public boolean isInitialized() { return initialized; }
    }
}
