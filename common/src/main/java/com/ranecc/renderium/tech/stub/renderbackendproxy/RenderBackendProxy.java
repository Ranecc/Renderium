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

    public void drawIndexed(int a, int b, int c, int d) {}
    public void draw(int a, int b, int c) {}
}
