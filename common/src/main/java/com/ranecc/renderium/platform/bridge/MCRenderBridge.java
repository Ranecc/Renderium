package com.ranecc.renderium.platform.bridge;

import com.ranecc.renderium.platform.lifecycle.LifecycleManager;

public final class MCRenderBridge {

    private MCRenderBridge() {}

    private static volatile Object frameDataSnapshot;
    private static volatile LifecycleManager lifecycleManager;
    private static volatile Object batchTransformer;
    private static volatile Object commandBatcher;
    private static volatile Object backendHookPoint;

    public static Object getFrameDataSnapshot() {
        return frameDataSnapshot;
    }

    public static void setFrameDataSnapshot(Object snapshot) {
        frameDataSnapshot = snapshot;
    }

    public static LifecycleManager getLifecycleManager() {
        return lifecycleManager;
    }

    public static void setLifecycleManager(LifecycleManager manager) {
        lifecycleManager = manager;
    }

    public static Object getBatchTransformer() {
        return batchTransformer;
    }

    public static void setBatchTransformer(Object transformer) {
        batchTransformer = transformer;
    }

    public static Object getCommandBatcher() {
        return commandBatcher;
    }

    public static void setCommandBatcher(Object batcher) {
        commandBatcher = batcher;
    }

    public static Object getBackendHookPoint() {
        return backendHookPoint;
    }

    public static void setBackendHookPoint(Object hook) {
        backendHookPoint = hook;
    }
}
