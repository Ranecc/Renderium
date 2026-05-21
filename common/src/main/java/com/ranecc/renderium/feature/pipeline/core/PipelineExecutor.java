package com.ranecc.renderium.feature.pipeline.core;

import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.pipeline.node.PipelineNode;
import com.ranecc.renderium.feature.pipeline.node.PipelineNodeRegistry;
import java.util.List;
import java.util.logging.Logger;

public final class PipelineExecutor {

    private static final Logger LOGGER = Logger.getLogger("Renderium|PipelineExecutor");

    private static volatile PipelineExecutor INSTANCE;

    private volatile boolean nodesInitialized = false;
    private volatile long lastOutputTexture = 0L;
    private volatile int frameCount = 0;

    private PipelineExecutor() {}

    public static synchronized PipelineExecutor getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PipelineExecutor();
        }
        return INSTANCE;
    }

    public long executePostProcessChain(RenderContext context, long colorTexture, long depthTexture) {
        if (!nodesInitialized) {
            initialize();
        }

        PipelineNodeRegistry registry = PipelineNodeRegistry.getInstance();
        List<String> executionOrder = registry.getExecutionOrder();

        if (executionOrder.isEmpty()) {
            return colorTexture;
        }

        long currentInput = colorTexture;
        long currentDepth = depthTexture;
        long taaHistoryBuffer = 0L;

        for (String nodeId : executionOrder) {
            PipelineNode node = registry.getNode(nodeId);
            if (node == null || !registry.isEnabled(nodeId)) {
                continue;
            }

            try {
                String id = node.getId();
                long output;

                if (id.contains("TAA") || id.contains("TemporalAA")) {
                    output = node.execute(context, currentInput, taaHistoryBuffer);
                    if (output != 0L && output != currentInput) {
                        taaHistoryBuffer = currentInput;
                    }
                } else if (id.contains("SSR")) {
                    output = node.execute(context, currentInput, currentDepth, 0L);
                } else if (id.contains("DOF") || id.contains("DepthOfField") ||
                           id.contains("MotionBlur") || id.contains("VolFog")) {
                    output = node.execute(context, currentInput, currentDepth);
                } else {
                    output = node.execute(context, currentInput);
                }

                if (output != 0L) {
                    currentInput = output;
                }
            } catch (Exception e) {
                LOGGER.warning("节点执行异常 [" + nodeId + "]: " + e.getMessage());
            }
        }

        lastOutputTexture = currentInput;
        frameCount++;
        return currentInput;
    }

    private void ensureNodesInitialized(RenderContext context) {
        synchronized (this) {
            if (nodesInitialized) return;
            nodesInitialized = doInitNodes(context);
        }
    }

    /**
     * 公开初始化入口（由生命周期管理器调用）。
     * 幂等——内部用 nodesInitialized 标志防护。
     */
    public void initialize() {
        synchronized (this) {
            if (nodesInitialized) return;
            nodesInitialized = doInitNodes(null);
        }
    }

    private boolean doInitNodes(RenderContext context) {
        PipelineNodeRegistry registry = PipelineNodeRegistry.getInstance();
        registry.registerBuiltinNodes();
        for (String id : registry.getAllNodeIds()) {
            try {
                PipelineNode node = registry.getNode(id);
                if (node != null) {
                    boolean ok = node.initialize(context);
                    if (!ok) {
                        registry.setNodeState(id, PipelineNode.State.DISABLED);
                        LOGGER.warning("节点初始化失败，已禁用: " + id);
                    }
                }
            } catch (Exception e) {
                LOGGER.warning("节点初始化异常 [" + id + "]: " + e.getMessage());
            }
        }
        nodesInitialized = true;
        LOGGER.info(String.format("PipelineExecutor 初始化完成: %d 个节点, 执行顺序: %s",
                registry.getTotalCount(), registry.getExecutionOrder()));
        return true;
    }

    public void shutdown() {
        PipelineNodeRegistry registry = PipelineNodeRegistry.getInstance();
        registry.disposeAll();
        nodesInitialized = false;
        lastOutputTexture = 0L;
        LOGGER.info("PipelineExecutor 已关闭");
    }

    public boolean isInitialized() { return nodesInitialized; }

    public long getLastOutputTexture() { return lastOutputTexture; }

    public int getFrameCount() { return frameCount; }

    public void resetInitialization() {
        nodesInitialized = false;
    }
}
