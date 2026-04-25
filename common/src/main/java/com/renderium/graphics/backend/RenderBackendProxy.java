// Renderium - 渲染后端热切换代理（无锁快速路径优化）
// 面向 1000+ FPS 的高性能设计

package com.renderium.graphics.backend;

import com.renderium.graphics.command.CommandBuffer;
import com.renderium.graphics.command.RenderCommand;
import com.renderium.graphics.event.VulkanActivationEvent;
import com.renderium.graphics.pipeline.RenderiumPipelineCache;
import com.renderium.graphics.pipeline.RenderPipeline;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 渲染后端热切换代理（无锁快速路径优化）
 * <p>
 * 实现了 {@link RenderBackend} 接口的所有必需方法，
 * 同时提供扩展的便利方法用于高级渲染操作。
 * </p>
 *
 * <h3>优化策略</h3>
 * <ul>
 *   <li><b>volatile 最小化</b>：仅真正需要跨线程可见的字段使用 volatile</li>
 *   <li><b>本地引用缓存</b>：热路径中缓存 volatile 读结果，避免重复内存屏障</li>
 *   <li><b>早期快速退出</b>：短路模式下立即返回，不执行任何额外逻辑</li>
 * </ul>
 */
public final class RenderBackendProxy implements RenderBackend {

    /** 调试开关（编译期可消除） */
    private static final boolean DEBUG_ENABLED = false;

    private static final Logger LOGGER = LoggerFactory.getLogger(RenderBackendProxy.class);

    /** 单例实例 */
    private static volatile RenderBackendProxy instance;

    /** 后端状态（需要 volatile，多线程可能切换） */
    private volatile BackendState state = BackendState.UNINITIALIZED;

    /** 活动后端引用（需要 volatile，短路模式可能运行时切换） */
    private volatile RenderBackend activeBackend;

    /** Vulkan 后端（仅初始化时写入，不需要 volatile） */
    private Object vulkanBackend;

    /** 管线缓存（非热路径，普通字段即可） */
    private final RenderiumPipelineCache pipelineCache = new RenderiumPipelineCache();

    /** 短路原因（仅初始化时写入） */
    private String shortCircuitReason;

    // ==================== 单例 ====================

    /**
     * 获取单例实例（非空返回值）
     *
     * 【返回值】RenderBackendProxy - 始终非 null
     */
    @NonNull
    public static RenderBackendProxy getInstance() {
        RenderBackendProxy inst = instance;
        if (inst == null) {
            synchronized (RenderBackendProxy.class) {
                inst = instance;
                if (inst == null) {
                    instance = inst = new RenderBackendProxy();
                }
            }
        }
        return inst;
    }

    // ==================== RenderBackend 接口实现 ====================

    /**
     * 初始化渲染后端（启动时调用一次）
     *
     * 【方法参数】
     * @param windowHandle long - 平台窗口句柄
     * 【返回值】boolean - 是否初始化成功
     */
    @Override
    public boolean initialize(long windowHandle) {
        if (state != BackendState.UNINITIALIZED) {
            return state == BackendState.VULKAN_ACTIVE;
        }

        boolean wasActive = false;

        // 尝试通过反射加载 VulkanBackend（模块化设计：Vulkan 后端可能不在 classpath 上）
        Object vkBackend = null;
        boolean vulkanOk = false;

        try {
            Class<?> vulkanBackendClass = Class.forName(
                "com.renderium.graphics.backend.vulkan.VulkanBackend");
            var constructor = vulkanBackendClass.getConstructor(long.class);
            vkBackend = constructor.newInstance(windowHandle);

            // 验证后端是否实现了 RenderBackend 接口
            if (vkBackend instanceof RenderBackend backend) {
                vulkanOk = backend.initialize(windowHandle);
            }
        } catch (ClassNotFoundException e) {
            LOGGER.info("VulkanBackend 类未找到，跳过 Vulkan 初始化");
        } catch (NoSuchMethodException e) {
            LOGGER.warn("VulkanBackend 缺少所需的构造函数: {}", e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("VulkanBackend 初始化异常: {}", e.getMessage());
        }

        if (vulkanOk && vkBackend instanceof RenderBackend backend) {
            vulkanBackend = vkBackend;
            activeBackend = backend;
            state = BackendState.VULKAN_ACTIVE;

            if (DEBUG_ENABLED) {
                LOGGER.info("Renderium: Vulkan backend active");
            }

            VulkanActivationEvent.fireActivationEvent(wasActive, true);
            return true;
        }

        shortCircuit("Vulkan initialization failed");
        VulkanActivationEvent.fireActivationEvent(wasActive, false);
        return false;
    }

    /** 销毁后端并释放所有资源 */
    @Override
    public void shutdown() {
        pipelineCache.clear();

        RenderBackend backend = activeBackend;
        if (backend != null) {
            backend.shutdown();
            activeBackend = null;
        }

        vulkanBackend = null;
        shortCircuitReason = null;
        state = BackendState.DISPOSED;

        if (DEBUG_ENABLED) {
            LOGGER.info("RenderBackendProxy disposed");
        }
    }

    /** 检查后端是否已初始化且可用 */
    @Override
    public boolean isInitialized() {
        return state == BackendState.VULKAN_ACTIVE && activeBackend != null;
    }

    /**
     * 执行单个渲染命令
     *
     * 【方法参数】
     * @param command RenderCommand - 要执行的命令
     */
    @Override
    public void executeCommand(RenderCommand command) {
        final RenderBackend backend = activeBackend;
        if (backend != null) {
            backend.executeCommand(command);
        }
    }

    /**
     * 提交并执行完整的 CommandBuffer
     *
     * 【方法参数】
     * @param commandBuffer CommandBuffer - 要提交的命令缓冲区
     */
    @Override
    public void submit(CommandBuffer commandBuffer) {
        final RenderBackend backend = activeBackend;
        if (backend != null) {
            backend.submit(commandBuffer);
            return;
        }
        handleShortCircuit("submit(CommandBuffer)");
    }

    /** 获取 Pipeline 缓存实例 */
    @Override
    public RenderiumPipelineCache getPipelineCache() {
        return pipelineCache;
    }

    /**
     * 创建底层 GPU Pipeline 对象
     *
     * 【方法参数】
     * @param pipeline RenderPipeline - 管线状态描述
     * 【返回值】long - 管线句柄 (>0 成功, 0 失败)
     */
    @Override
    public long createPipeline(RenderPipeline pipeline) {
        final RenderBackend backend = activeBackend;
        if (backend != null) {
            long handle = 0L; // TODO: use getOrCreate
            if (handle == 0L) {
                handle = backend.createPipeline(pipeline);
                if (handle != 0L) {
                    // handled by getOrCreate
                }
            }
            return handle;
        }
        return 0L;
    }

    /** 销毁底层 GPU Pipeline 对象 */
    @Override
    public void destroyPipeline(long handle) {
        final RenderBackend backend = activeBackend;
        if (backend != null) {
            backend.destroyPipeline(handle);
        }
    }

    /** 创建顶点缓冲区 */
    @Override
    public long createVertexBuffer(float[] data, int usageHint) {
        final RenderBackend backend = activeBackend;
        return backend != null ? backend.createVertexBuffer(data, usageHint) : 0L;
    }

    /** 创建索引缓冲区 */
    @Override
    public long createIndexBuffer(int[] data, int usageHint) {
        final RenderBackend backend = activeBackend;
        return backend != null ? backend.createIndexBuffer(data, usageHint) : 0L;
    }

    /** 创建纹理资源 */
    @Override
    public long createTexture(int width, int height, int format, byte[] data) {
        final RenderBackend backend = activeBackend;
        return backend != null ? backend.createTexture(width, height, format, data) : 0L;
    }

    /** 销毁 GPU 资源 */
    @Override
    public void destroyResource(long resourceHandle) {
        final RenderBackend backend = activeBackend;
        if (backend != null) {
            backend.destroyResource(resourceHandle);
        }
    }

    /** 获取后端类型枚举 */
    @Override
    public BackendType getType() {
        final RenderBackend backend = activeBackend;
        if (backend != null) {
            return backend.getType();
        }
        return BackendType.NULL;
    }

    /** 获取后端名称字符串 */
    @Override
    public String getBackendName() {
        final RenderBackend backend = activeBackend;
        if (backend != null) {
            return backend.getBackendName();
        }
        return "short_circuit";
    }

    /** 获取 GPU 设备信息字符串 */
    @Override
    public String getDeviceInfo() {
        final RenderBackend backend = activeBackend;
        if (backend != null) {
            return backend.getDeviceInfo();
        }
        return "SHORT_CIRCUITED";
    }

    /** 检查是否支持某项特性 */
    @Override
    public boolean supportsFeature(String feature) {
        final RenderBackend backend = activeBackend;
        if (backend != null) {
            return backend.supportsFeature(feature);
        }
        return false;
    }

    // ==================== 扩展便利方法（非接口方法，无 @Override）====================

    /** 创建渲染表面（扩展方法，非接口要求） */
    public void createSurface(long windowHandle) {
        final RenderBackend backend = activeBackend;
        if (backend instanceof ExtendedBackend ext) {
            ext.createSurface(windowHandle);
        }
    }

    /** 调整表面大小（扩展方法） */
    public void resizeSurface(int width, int height) {
        final RenderBackend backend = activeBackend;
        if (backend instanceof ExtendedBackend ext) {
            ext.resizeSurface(width, height);
        }
    }

    /** 创建命令缓冲区（扩展方法，可能返回 null） */
    @NonNull
    public CommandBuffer createCommandBuffer() {
        final RenderBackend backend = activeBackend;
        return backend instanceof ExtendedBackend ext
            ? ext.createCommandBuffer()
            : null; // NOOP not available
    }

    /** 创建临时命令缓冲区（扩展方法，可能返回 null） */
    @NonNull
    public CommandBuffer createTransientCommandBuffer() {
        final RenderBackend backend = activeBackend;
        return backend instanceof ExtendedBackend ext
            ? ext.createTransientCommandBuffer()
            : null; // NOOP not available
    }

    /** 绑定管线（扩展方法）
     *
     * 【方法参数】
     * @param pipeline RenderPipeline - 管线状态描述（非 null）
     */
    public void bindPipeline(@NonNull RenderPipeline pipeline) {
        final RenderBackend backend = activeBackend;
        if (backend instanceof ExtendedBackend ext) {
            ext.bindPipeline(pipeline);
        }
    }

    /** 绑定描述符集（扩展方法） */
    public void bindDescriptorSet(int index, long descriptorSet) {
        final RenderBackend backend = activeBackend;
        if (backend instanceof ExtendedBackend ext) {
            ext.bindDescriptorSet(index, descriptorSet);
        }
    }

    /** 绘制（扩展方法） */
    public void draw(int vertexCount, int firstVertex, int instanceCount) {
        final RenderBackend backend = activeBackend;
        if (backend instanceof ExtendedBackend ext) {
            ext.draw(vertexCount, firstVertex, instanceCount);
        }
    }

    /** 索引绘制（扩展方法） */
    public void drawIndexed(int indexCount, int firstIndex, int vertexOffset, int instanceCount) {
        final RenderBackend backend = activeBackend;
        if (backend instanceof ExtendedBackend ext) {
            ext.drawIndexed(indexCount, firstIndex, vertexOffset, instanceCount);
        }
    }

    /** 计算着色器派发（扩展方法） */
    public void dispatchCompute(int groupX, int groupY, int groupZ) {
        final RenderBackend backend = activeBackend;
        if (backend instanceof ExtendedBackend ext) {
            ext.dispatchCompute(groupX, groupY, groupZ);
        }
    }

    /** 清除附件（扩展方法） */
    public void clearAttachments(int mask, float r, float g, float b, float a, float depth, int stencil) {
        final RenderBackend backend = activeBackend;
        if (backend instanceof ExtendedBackend ext) {
            ext.clearAttachments(mask, r, g, b, a, depth, stencil);
        }
    }

    /** 复制纹理到缓冲区（扩展方法） */
    public void copyTextureToBuffer(long srcTexture, long dstBuffer, int width, int height) {
        final RenderBackend backend = activeBackend;
        if (backend instanceof ExtendedBackend ext) {
            ext.copyTextureToBuffer(srcTexture, dstBuffer, width, height);
        }
    }

    /** 复制缓冲区到纹理（扩展方法） */
    public void copyBufferToTexture(long srcBuffer, long dstTexture, int width, int height) {
        final RenderBackend backend = activeBackend;
        if (backend instanceof ExtendedBackend ext) {
            ext.copyBufferToTexture(srcBuffer, dstTexture, width, height);
        }
    }

    // ==================== 状态查询 ====================

    /** Vulkan 后端是否激活 */
    public boolean isVulkanActive() {
        return state == BackendState.VULKAN_ACTIVE;
    }

    /** 是否处于短路模式 */
    public boolean isShortCircuited() {
        return state == BackendState.SHORT_CIRCUITED;
    }

    /** 获取当前状态（非空返回值）
     *
     * 【返回值】BackendState - 当前后端状态
     */
    @NonNull
    public BackendState getState() {
        return state;
    }

    /** 获取 Vulkan 后端对象（可能为 null，调试用） */
    @Nullable
    public Object getVulkanBackend() {
        return vulkanBackend;
    }

    /** 获取管线缓存大小 */
    public int getPipelineCacheSize() {
        return pipelineCache.size();
    }

    /** 获取短路原因（可能为 null） */
    @Nullable
    public String getShortCircuitReason() {
        return shortCircuitReason;
    }

    /** 获取诊断信息（非空返回值）
     *
     * 【返回值】String - 诊断字符串
     */
    @NonNull
    public String getDiagnostics() {
        if (state == BackendState.SHORT_CIRCUITED) {
            return String.format("RenderBackendProxy{SHORT_CIRCUITED, reason='%s'}", shortCircuitReason);
        }
        return String.format(
            "RenderBackendProxy{state=%s, vulkan=%s, activeBackend=%s}",
            state,
            vulkanBackend != null ? vulkanBackend.getClass().getSimpleName() : "null",
            activeBackend != null ? activeBackend.getClass().getSimpleName() : "null"
        );
    }

    // ==================== 内部方法 ====================

    /**
     * 进入短路模式
     *
     * 【方法参数】
     * @param reason String - 短路原因（非 null）
     */
    private void shortCircuit(@NonNull String reason) {
        this.shortCircuitReason = reason;
        this.activeBackend = null;
        this.state = BackendState.SHORT_CIRCUITED;

        if (DEBUG_ENABLED) {
            LOGGER.warn("RenderBackendProxy short-circuited: {}", reason);
        }
    }

    /**
     * 处理短路模式下的方法调用
     *
     * 【方法参数】
     * @param methodName String - 调用的方法名（非 null）
     */
    private void handleShortCircuit(@NonNull String methodName) {
        if (DEBUG_ENABLED) {
            LOGGER.warn("{} called in short-circuit mode, command ignored. Reason: {}",
                       methodName, shortCircuitReason);
        }
    }

    // ==================== 内部接口和枚举 ====================

    /**
     * 扩展后端接口
     * <p>
     * 定义 RenderBackend 接口之外的额外方法。
     * 具体后端实现可选择性地实现此接口以提供完整功能集。
     * </p>
     */
    public interface ExtendedBackend extends RenderBackend {
        void createSurface(long windowHandle);
        void resizeSurface(int width, int height);
        CommandBuffer createCommandBuffer();
        CommandBuffer createTransientCommandBuffer();
        void bindPipeline(RenderPipeline pipeline);
        void bindDescriptorSet(int index, long descriptorSet);
        void draw(int vertexCount, int firstVertex, int instanceCount);
        void drawIndexed(int indexCount, int firstIndex, int vertexOffset, int instanceCount);
        void dispatchCompute(int groupX, int groupY, int groupZ);
        void clearAttachments(int mask, float r, float g, float b, float a, float depth, int stencil);
        void copyTextureToBuffer(long srcTexture, long dstBuffer, int width, int height);
        void copyBufferToTexture(long srcBuffer, long dstTexture, int width, int height);
    }

    /** 代理内部状态枚举 */
    public enum BackendState {
        UNINITIALIZED,
        VULKAN_ACTIVE,
        SHORT_CIRCUITED,
        DISPOSED
    }
}
