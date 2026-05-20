// Renderium - Blaze3D Shader 转译模块
// Shader 转译管线顶层入口 - 管理整个 GLSL→SPIR-V 生命周期

package com.ranecc.renderium.feature.blaze3d.shader;

import com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import com.ranecc.renderium.infrastructure.gpu.VulkanStructs;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Shader 转译管线顶层入口 (单例)。
 *
 * <h2>职责：</h2>
 * <ul>
 *   <li>管理整个 GLSL→SPIR-V 转译生命周期的初始化/关闭</li>
 *   <li>协调子组件: Preprocessor → Lexer → Transformer → Compiler → Factory</li>
 *   <li>AOT 预编译调度 (光影包切换时)</li>
 *   <li>缓存管理 (L1 内存 + L2 磁盘)</li>
 * </ul>
 *
 * <h2>生命周期：</h2>
 * <pre>
 * 游戏启动:
 *   1. initialize(device) → 加载 libglslang, 初始化各子组件
 *   2. 检测是否有已安装的光影包
 *   3. 如果有 → 触发 AOT 预编译
 *
 * 光影包切换:
 *   1. onShaderPackChanged(newPackPath)
 *   2. 清除旧缓存
 *   3. 触发新的 AOT 预编译
 *   4. 更新 UniformRedirector 映射表
 *
 * 游戏关闭:
 *   1. shutdown() → 释放 libglslang, 清理缓存
 * </pre>
 *
 * @see GlslPreprocessor #include 展开
 * @see GlslLexer 词法分析
 * @see GlslToVkTransformer AST 转换
 * @see GlslangCompiler SPIR-V 编译
 * @see UniformRedirector Uniform 桥接
 * @since 3.0.0
 */
public final class ShaderTranspilerPipeline implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger("Renderium-ShaderTranspiler");

    /** 单例实例 */
    private static volatile ShaderTranspilerPipeline INSTANCE = new ShaderTranspilerPipeline();

    /** 是否启用 */
    private volatile boolean enabled = false;

    /** libglslang 编译器 (懒加载) */
    private volatile GlslangCompiler compiler;

    /** 全流程工厂 */
    private volatile ShaderPipelineFactory factory;

    /** VkDevice (来自 OfficialVulkanHijacker) */
    private volatile long vkDevice = 0L;

    /** 缓存目录 */
    private Path cacheDir;

    private ShaderTranspilerPipeline() {}

    public static ShaderTranspilerPipeline getInstance() { return INSTANCE; }

    // ==================== 生命周期 API ====================

    /**
     * 初始化转译管线。
     *
     * <h3>参数说明：</h3>
     * <table>
     *   <tr><th>参数</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>device</td><td>long</td><td>Vulkan Device 句柄 (来自 OfficialVulkanHijacker.getVkDevice())</td></tr>
     * </table>
     *
     * @param device Vulkan Device 句柄 (必须非零)
     */
    public void initialize(long device) {
        this.vkDevice = device;
        this.cacheDir = Paths.get(System.getProperty("java.io.tmpdir"),
                "renderium-shader-cache");

        try {
            // 初始化全流程工厂 (内部会懒加载 GlslangCompiler)
            this.factory = new ShaderPipelineFactory(device);

            this.enabled = true;
            LOGGER.info("✓ Shader Transpiler Pipeline 初始化完成 (Panama FFM, 零 JNI)");

        } catch (Exception e) {
            LOGGER.warning("✗ Shader Transpiler Pipeline 初始化失败: " + e.getMessage());
            this.enabled = false;
            this.factory = null;
        }
    }

    /**
     * 转译并创建单个 Shader Module (由 Mixin 调用)
     *
     * @param sourcePath GLSL 源文件路径
     * @param stage      Shader 阶段
     * @return 包含 VkShaderModule 和 Uniform 映射的结果
     */
    public ShaderPipelineFactory.PipelineResult transpileAndCreate(
            Path sourcePath, GlslToVkTransformer.ShaderStage stage)
            throws ShaderBuildException {

        ensureInitialized();
        return factory.buildShaderModule(sourcePath, stage);
    }

    /**
     * 注册 Uniform 映射到当前活跃的 Pipeline
     *
     * @param pipeline    RenderPipeline 实例
     * @param uniformMap 来自 GlslToVkTransformer 的映射表
     */
    public void registerUniformMap(Object pipeline,
                                    Map<String, GlslToVkTransformer.UniformBindingInfo> uniformMap) {
        if (!enabled || uniformMap == null || uniformMap.isEmpty()) return;

        // 计算 UBO 所需总大小
        int maxSize = 0;
        for (var entry : uniformMap.values()) {
            int end = entry.offset() + entry.sizeBytes();
            if (end > maxSize) maxSize = end;
        }

        if (maxSize == 0) return;

        // 分配 UBO 内存 (这里需要与 VulkanFFM 协作分配 vkMapMemory 的内存)
        // 暂时使用 Arena 分配，实际应通过 VulkanFFM 创建 UBO buffer 并 map
        try {
            MemorySegment uboMemory = allocateUBOForPipeline(maxSize);
            long deviceMemory = createUBOBuffer(uboMemory);

            UniformRedirector redirector = UniformRedirector.getInstance();
            redirector.initialize(uniformMap, uboMemory, deviceMemory);

            LOGGER.fine(String.format("Uniform 映射已注册: %d entries, %d bytes UBO",
                    uniformMap.size(), maxSize));
        } catch (Exception e) {
            LOGGER.warning("Uniform 映射注册失败: " + e.getMessage());
        }
    }

    /**
     * 光影包切换时的 AOT 预编译触发
     *
     * @param shaderPackRoot 光影包 shaders 目录根路径
     * @return 预编译结果统计
     */
    public PrecompileResult onShaderPackChanged(Path shaderPackRoot,
                                                 ProgressListener progressListener) {
        if (factory == null) {
            return PrecompileResult.skipped("工厂未初始化");
        }
        if (!Files.exists(shaderPackRoot)) {
            return PrecompileResult.skipped("光影包目录不存在: " + shaderPackRoot);
        }

        LOGGER.info("开始光影包 AOT 预编译: " + shaderPackRoot);

        long startTime = System.nanoTime();
        int successCount = 0;
        int failedCount = 0;

        try {
            // 扫描所有 shader 文件
            List<Path> shaders = discoverShaders(shaderPackRoot);

            // 批量构建
            var futures = factory.buildBatch(shaders);

            for (var future : futures) {
                try {
                    var result = future.get();
                    successCount++;
                    if (progressListener != null && result.shaderModule() != 0) {
                        progressListener.onProgress(
                                new PrecompileProgress(successCount + failedCount,
                                        shaders.size(),
                                        result.sourcePath().getFileName().toString(),
                                        (double)(successCount + failedCount) / shaders.size() * 100.0));
                    }
                } catch (Exception e) {
                    failedCount++;
                }
            }

        } catch (Exception e) {
            LOGGER.severe("AOT 预编译异常: " + e.getMessage());
        }

        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

        PrecompileResult result = new PrecompileResult(successCount, failedCount, elapsedMs,
                String.format("成功=%d 失败=%d 耗时=%dms", successCount, failedCount, elapsedMs));

        LOGGER.info(String.format("✓ 光影包 AOT 预编译完成: %s", result.message()));
        return result;
    }

    /**
     * 关闭管线，释放所有资源
     */
    @Override
    public void close() {
        if (factory != null) {
            factory.cleanup();
            factory = null;
        }
        if (compiler != null) {
            compiler.shutdown();
            compiler = null;
        }
        enabled = false;
        LOGGER.info("Shader Transpiler Pipeline 已关闭");
    }

    // ==================== 查询 API ====================

    /** 是否启用 */
    public boolean isEnabled() { return enabled; }

    /** 获取编译器实例 (可能为 null) */
    public GlslangCompiler getCompiler() { return compiler; }

    /** 获取工厂实例 (可能为 null) */
    public ShaderPipelineFactory getFactory() { return factory; }

    /** 获取 Shader 注册表 */
    public RenderiumShaderRegistry getRegistry() { return RenderiumShaderRegistry.getInstance(); }

    // ==================== 内部实现 ====================

    /** 确保管线已初始化 */
    private void ensureInitialized() {
        if (factory == null || !enabled) {
            throw new IllegalStateException(
                    "Shader Transpiler Pipeline 未初始化。" +
                            "请确保 OfficialVulkanHijacker 劫持成功后调用 initialize(device)"
            );
        }
    }

    /** 扫描目录下所有 GLSL shader 文件 */
    private List<Path> discoverShaders(Path root) throws Exception {
        if (!Files.exists(root)) return List.of();

        return Files.walk(root)
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return name.endsWith(".glsl") || name.endsWith(".vsh") ||
                           name.endsWith(".fsh") || name.endsWith(".vert") ||
                           name.endsWith(".frag") || name.endsWith(".comp") ||
                           name.endsWith(".geom");
                })
                .sorted()
                .toList();
    }

    /** 为 Pipeline 分配 UBO 内存 (占位实现，需与 VulkanFFM 对接) */
    private MemorySegment allocateUBOForPipeline(int sizeBytes) {
        // TODO: 通过 VulkanFFM 创建 VkBuffer → vkMapMemory → 返回 MappedMemorySegment
        // 这里先用 Java 堆外内存作为临时替代
        try {
            return java.lang.foreign.Arena.ofAuto().allocate(sizeBytes);
        } catch (Exception e) {
            throw new RuntimeException("无法分配 UBO 内存: " + sizeBytes + " bytes", e);
        }
    }

    /**
     * 创建 UBO Buffer。
     * <p>
     * 通过 VulkanAPIRegistry 调用 vkCreateBuffer 创建真实的 VkBuffer，
     * Buffer 大小取 memory 段大小与 256 字节中的较大值。
     *
     * @param memory SPIR-V 数据段，用于确定 Buffer 大小基准
     * @return VkBuffer 句柄，失败返回 0L
     */
    private long createUBOBuffer(MemorySegment memory) {
        long device = VulkanDeviceHolder.getInstance().getDevice();
        if (device == 0L) {
            LOGGER.warning("createUBOBuffer: VulkanDevice 不可用");
            return 0L;
        }

        // 根据传入的 memory 大小计算 UBO 大小，至少 256 字节
        long bufferSize = Math.max(memory.byteSize(), 256);

        try (Arena arena = Arena.ofConfined()) {
            // 创建 VkBufferCreateInfo: size = bufferSize, usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
            MemorySegment createInfo = VulkanStructs.createBufferCreateInfo(
                    arena, bufferSize, 0x00000010);
            long[] outBuffer = new long[1];
            int result = (int) VulkanAPIRegistry.invoke(
                    "vkCreateBuffer", device, createInfo.address(), 0L, outBuffer);
            if (result != 0) {
                LOGGER.warning("createUBOBuffer: vkCreateBuffer 返回 " + result);
                return 0L;
            }
            if (outBuffer[0] == 0L) {
                LOGGER.warning("createUBOBuffer: vkCreateBuffer 返回空句柄");
                return 0L;
            }
            return outBuffer[0];
        } catch (Throwable t) {
            LOGGER.warning("createUBOBuffer 失败: " + t.getMessage());
            return 0L;
        }
    }

    // ==================== 内部数据结构 ====================

    /** AOT 预编译结果 */
    public record PrecompileResult(
            int successCount,
            int failedCount,
            long elapsedMs,
            String message
    ) {
        public static PrecompileResult skipped(String reason) {
            return new PrecompileResult(0, 0, 0, reason);
        }
    }

    /** 预编译进度回调 */
    public interface ProgressListener {
        void onProgress(PrecompileProgress progress);
    }

    /** 预编译进度信息 */
    public record PrecompileProgress(
            int current,
            int total,
            String currentShader,
            double percentComplete
    ) {}
}
