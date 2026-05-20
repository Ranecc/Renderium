package com.ranecc.renderium.tech.stub.renderbackendproxy;

import java.util.logging.Logger;

public class RenderBackendProxy {

    private static final Logger LOGGER = Logger.getLogger(RenderBackendProxy.class.getName());
    private static final RenderBackendProxy INSTANCE = new RenderBackendProxy();

    private boolean initialized;

    private RenderBackendProxy() {}

    public static RenderBackendProxy getInstance() {
        return INSTANCE;
    }

    public void initialize() {
        this.initialized = true;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isVulkanActive() {
        return initialized;
    }

    public Object getVulkanBackend() {
        return null;
    }

    /**
     * 桩实现：带索引的绘制调用
     * <p>当前不会真正执行 GPU 绘制，仅记录日志警告。
     */
    public void drawIndexed(int a, int b, int c, int d) {
        LOGGER.warning(String.format("RenderBackendProxy.drawIndexed 为桩实现，未连接实际后端 (args: %d,%d,%d,%d)", a, b, c, d));
    }

    /**
     * 桩实现：无索引的绘制调用
     * <p>当前不会真正执行 GPU 绘制，仅记录日志警告。
     */
    public void draw(int a, int b, int c) {
        LOGGER.warning(String.format("RenderBackendProxy.draw 为桩实现，未连接实际后端 (args: %d,%d,%d)", a, b, c));
    }
}
