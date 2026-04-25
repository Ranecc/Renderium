// Renderium - 渲染后端接口
// 定义 Vulkan/DX12/OpenGL 后端的统一抽象，支持运行时热切换
// 参考: UE5 RHI / bgfx / wgpu Backend 抽象

package com.renderium.graphics.backend;

import com.renderium.graphics.command.CommandBuffer;
import com.renderium.graphics.command.RenderCommand;
import com.renderium.graphics.pipeline.RenderiumPipelineCache;
import com.renderium.graphics.pipeline.RenderPipeline;

/**
 * 渲染后端接口
 * <p>
 * 这是整个图形抽象层的最底层接口，定义了所有后端（Vulkan/DX12/OpenGL）
 * 必须实现的功能集合。
 * <p>
 * 设计原则（参考 MVP 版本渲染管线思路文档）：
 * <ul>
 *   <li><b>API 无关</b>: 接口中不暴露任何 {@code Vk*}/{@code ID3D12*}/{@code GL} 的类型</li>
 *   <li><b>命令消费</b>: 核心职责是消费 CommandBuffer 并翻译为底层 API 调用</li>
 *   <li><b>资源管理</b>: 管理后端特有的 GPU 资源生命周期</li>
 *   <li><b>可替换性</b>: 支持通过 RenderBackendProxy 运行时切换</li>
 * </ul>
 *
 * <h3>后端实现清单：</h3>
 * <ul>
 *   <li>{@code VulkanBackend} - 基于 LWJGL 3.4+ 的 Vulkan 后端 (目标 26.2)</li>
 *   <li>{@code OpenGLBackend} - OpenGL 兼容回退后端 (26.1.x)</li>
 *   <li>{@code D3D12Backend} - DirectX 12 后端 (Windows 11 可选)</li>
 * </ul>
 *
 * @see com.renderium.graphics.backend.RenderBackendProxy
 */
public interface RenderBackend {

    // ==================== 后端类型枚举 ====================

    /**
     * 渲染后端类型标识
     */
    enum BackendType {
        /** Vulkan 后端 (Minecraft 26.2 默认) */
        VULKAN("Vulkan", "vk"),

        /** OpenGL 后端 (兼容回退) */
        OPENGL("OpenGL", "gl"),

        /** DirectX 12 后端 (Windows 11 可选) */
        D3D12("DirectX 12", "d3d12"),

        /** 空后端 (用于测试/无头模式) */
        NULL("Null", "null");

        private final String displayName;
        private final String shortName;

        BackendType(String displayName, String shortName) {
            this.displayName = displayName;
            this.shortName = shortName;
        }

        public String getDisplayName() { return displayName; }
        public String getShortName() { return shortName; }
    }

    // ==================== 生命周期方法 ====================

    /**
     * 初始化后端
     * <p>
     * 创建底层的设备、队列、命令池等核心资源。
     * 该方法应该在游戏启动时调用一次。
     *
     * @param windowHandle 平台窗口句柄 (Windows: HWND, Linux: Display*)
     * @return 是否初始化成功
     */
    boolean initialize(long windowHandle);

    /**
     * 销毁后端并释放所有资源
     * <p>
     * 必须在切换后端或退出游戏前调用。
     * 调用后该后端实例不可再使用。
     */
    void shutdown();

    /**
     * 检查后端是否已初始化且可用
     *
     * @return 是否可用
     */
    boolean isInitialized();

    // ==================== 命令执行方法 ====================

    /**
     * 执行单个渲染命令
     * <p>
     * 将 RenderCommand 翻译为对应的底层 API 调用。
     * 这是渲染线程的核心工作循环。
     *
     * @param command 要执行的命令
     */
    void executeCommand(RenderCommand command);

    /**
     * 提交并执行完整的 CommandBuffer
     * <p>
     * 批量执行缓冲区中的所有命令。
     * 实现可以优化批量提交的性能。
     *
     * @param commandBuffer 要执行的命令缓冲区
     */
    void submit(CommandBuffer commandBuffer);

    // ==================== Pipeline 管理 ====================

    /**
     * 获取 Pipeline 缓存实例
     * <p>
     * 每个后端维护自己的 Pipeline Cache，
     * 因为不同后端的 Pipeline 句柄不兼容。
     *
     * @return 该后端的 PipelineCache 实例
     */
    RenderiumPipelineCache getPipelineCache();

    /**
     * 创建底层 GPU Pipeline 对象
     * <p>
     * 由 PipelineCache 延迟调用，将 RenderPipeline 状态翻译为真实 Pipeline。
     *
     * @param pipeline 管线状态描述
     * @return 后端 Pipeline 句柄 (>0 成功, 0 失败)
     */
    long createPipeline(RenderPipeline pipeline);

    /**
     * 销毁底层 GPU Pipeline 对象
     *
     * @param handle Pipeline 句柄
     */
    void destroyPipeline(long handle);

    // ==================== 资源管理 ====================

    /**
     * 创建顶点缓冲区
     *
     * @param data      顶点数据
     * @param usageHint 使用提示 (STATIC/DYNAMIC/STREAM)
     * @return 资源句柄
     */
    long createVertexBuffer(float[] data, int usageHint);

    /**
     * 创建索引缓冲区
     *
     * @param data      索引数据
     * @param usageHint 使用提示
     * @return 资源句柄
     */
    long createIndexBuffer(int[] data, int usageHint);

    /**
     * 创建纹理资源
     *
     * @param width     纹理宽度
     * @param height    纹理高度
     * @param format    像素格式
     * @param data      像素数据 (可为 null 表示仅分配)
     * @return 资源句柄
     */
    long createTexture(int width, int height, int format, byte[] data);

    /**
     * 销毁 GPU 资源
     *
     * @param resourceHandle 资源句柄
     */
    void destroyResource(long resourceHandle);

    // ==================== 渲染状态查询 ====================

    /**
     * 获取后端类型
     *
     * @return 后端类型枚举
     */
    BackendType getType();

    /**
     * 获取后端名称字符串
     *
     * @return 名称 (如 "Vulkan 1.3", "OpenGL 4.6")
     */
    String getBackendName();

    /**
     * 获取 GPU 设备信息字符串
     *
     * @return 设备信息 (如 "NVIDIA GeForce RTX 4090")
     */
    String getDeviceInfo();

    /**
     * 检查是否支持某项特性
     *
     * @param feature 特征名称
     * @return 是否支持
     */
    boolean supportsFeature(String feature);
}
