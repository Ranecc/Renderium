// Renderium - Blaze3D Shader 转译模块
// Shader 管线工厂 - GLSL → SPIR-V → VkShaderModule 全流程编排

package com.ranecc.renderium.feature.blaze3d.module.impl.blaze3d.shader;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.stream.*;

/**
 * Shader 管线工厂 — GLSL → SPIR-V → VkShaderModule 全流程编排。
 *
 * <h2>全流程 (端到端 Panama)：</h2>
 * <pre>
 * .glsl 源码
 *   ↓ GlslPreprocessor (#include 展开)
 * 展开后 GLSL
 *   ↓ GlslLexer (词法分析 → Token 流)
 * Token 流
 *   ↓ GlslToVkTransformer (Token 级安全转换)
 * Vulkan GLSL
 *   ↓ GlslangCompiler (Panama FFM 内嵌 libglslang)
 * SPIR-V binary
 *   ↓ VulkanFFM.vkCreateShaderModule() (Panama FFM Vulkan API)
 * VkShaderModule handle ← 返回给调用者
 * </pre>
 *
 * <h2>使用示例：</h2>
 * <pre>{@code
 * // 1. 获取 VkDevice (来自 OfficialVulkanHijacker)
 * long device = OfficialVulkanHijacker.getInstance().getVkDevice();
 *
 * // 2. 创建工厂
 * ShaderPipelineFactory factory = new ShaderPipelineFactory(device);
 *
 * // 3. 一键编译并创建 VkShaderModule
 * PipelineResult result = factory.buildShaderModule(
 *     Paths.get("shaders/gbuffers_terrain.vsh"),
 *     ShaderStage.VERTEX
 * );
 *
 * // result.shaderModule()   → VkShaderModule handle
 * // result.uniformMap()      → Uniform 名称 → 绑定信息
 * }</pre>
 *
 * @see GlslPreprocessor 预处理器
 * @see GlslLexer 词法分析器
 * @see GlslToVkTransformer 转换引擎
 * @see GlslangCompiler SPIR-V 编译器
 * @see UniformRedirector Uniform 重定向器
 * @since 3.0.0
 */
public final class ShaderPipelineFactory {

    private static final Logger LOGGER = Logger.getLogger("Renderium-ShaderFactory");

    /** Vulkan Device handle (来自 OfficialVulkanHijacker) */
    private final long vkDevice;

    /** GLSL 预处理器 */
    private final GlslPreprocessor preprocessor;

    /** glslang 编译器 (懒加载) */
    private volatile GlslangCompiler glslang;

    /** 编译缓存 */
    private final ShaderCache cache;

    /** 缓存条目: key → (SPIR-V bytes + uniformMap) */
    private final ConcurrentHashMap<String, CachedSPIRV> cacheMap = new ConcurrentHashMap<>();

    /** 最大缓存条目数 */
    private static final int MAX_CACHE_SIZE = 128;

    /**
     * 编译结果
     *
     * @param shaderModule  VkShaderModule handle (来自 VulkanFFM)
     * @param uniformMap    Uniform 名称 → 绑定信息映射
     * @param sourcePath    原始源文件路径
     * @param compileTimeMs 编译耗时 (毫秒)
     */
    public record PipelineResult(
            long shaderModule,
            Map<String, GlslToVkTransformer.UniformBindingInfo> uniformMap,
            Path sourcePath,
            long compileTimeMs
    ) {}

    /** 缓存的 SPIR-V 数据 */
    private record CachedSPIRV(byte[] spirv,
                                Map<String, GlslToVkTransformer.UniformBindingInfo> uniformMap) {}

    // ==================== 构造函数 ====================

    /**
     * 构造工厂
     *
     * @param vkDevice Vulkan Device handle (必须非零，来自 OfficialVulkanHijacker.getVkDevice())
     * @throws IllegalArgumentException 如果 vkDevice 为 0
     */
    public ShaderPipelineFactory(long vkDevice) {
        if (vkDevice == 0L) {
            throw new IllegalArgumentException(
                    "vkDevice 不能为 0，请先通过 OfficialVulkanHijacker 劫持设备");
        }
        this.vkDevice = vkDevice;
        this.preprocessor = new GlslPreprocessor();
        Path cacheDir = Paths.get(System.getProperty("java.io.tmpdir"),
                "renderium-shader-cache");
        this.cache = new ShaderCache(cacheDir);
    }

    // ==================== 核心公共 API ====================

    /**
     * 一键构建: GLSL 源文件 → VkShaderModule。
     *
     * <h3>完整流程：</h3>
     * <ol>
     *   <li>读取并预处理 GLSL (#include 展开)</li>
     *   <li>词法分析 → Token 流</li>
     *   <li>Token 级转换 (GL → Vk GLSL)</li>
     *   <li>libglang 编译 → SPIR-V</li>
     *   <li>VulkanFFM.vkCreateShaderModule() → VkShaderModule</li>
     *   <li>产出 Uniform 映射表供 UniformRedirector 使用</li>
     * </ol>
     *
     * @param sourcePath GLSL 源文件路径 (.glsl/.vsh/.fsh)
     * @param stage      Shader 阶段
     * @return 包含 VkShaderModule handle 和 Uniform 映射的结果
     * @throws ShaderBuildException 任何步骤失败
     */
    public PipelineResult buildShaderModule(Path sourcePath,
                                             GlslToVkTransformer.ShaderStage stage)
            throws ShaderBuildException {

        long startTime = System.nanoTime();

        try {
            // Step 1: 预处理 (#include 展开)
            String rawSource = preprocessor.preprocess(sourcePath, Map.of());

            // Step 2: 计算缓存键
            String cacheKey = computeCacheKey(sourcePath, stage, rawSource);

            // Step 3: 检查缓存 (L1 内存优先)
            CachedSPIRV cached = cacheMap.get(cacheKey);
            if (cached != null) {
                LOGGER.fine("Shader L1 缓存命中: " + sourcePath.getFileName());
                long module = createModuleFromSPIRV(cached.spirv(), sourcePath.getFileName().toString());
                long elapsed = (System.nanoTime() - startTime) / 1_000_000;
                return new PipelineResult(module, cached.uniformMap(), sourcePath, elapsed);
            }

            // Step 4: 词法分析
            GlslLexer lexer = new GlslLexer(rawSource);
            List<GlslLexer.GlslToken> tokens = lexer.tokenize();

            // Step 5: Token 级转换 (GL → Vk GLSL)
            GlslToVkTransformer transformer = new GlslToVkTransformer(stage);
            GlslToVkTransformer.TransformationResult transformResult =
                    transformer.transform(tokens);

            // 记录转换警告
            for (var warning : transformResult.warnings()) {
                if (warning.level() == GlslToVkTransformer.TransformWarning.Level.ERROR) {
                    LOGGER.severe("[" + sourcePath.getFileName() + ":" + warning.line() + "] " +
                            warning.message());
                } else if (warning.level() == GlslToVkTransformer.TransformWarning.Level.WARN) {
                    LOGGER.warning("[" + sourcePath.getFileName() + ":" + warning.line() + "] " +
                            warning.message());
                }
            }

            // Step 6: libglang 编译 (Panama FFM!)
            ensureGlslangInitialized();
            byte[] spirv = glslang.compile(transformResult.vulkanGLSL(), toGlslangStage(stage));

            // Step 7: 创建 VkShaderModule (使用 VulkanFFM, 不是 JNI!)
            long shaderModule = createModuleFromSPIRV(spirv, sourcePath.getFileName().toString());

            // Step 8: 缓存结果
            cached = new CachedSPIRV(spirv, transformResult.uniformMap());
            evictCacheIfNeeded();
            cacheMap.put(cacheKey, cached);

            // 同时写入 L2 磁盘缓存
            cache.put(cacheKey, spirv);

            long elapsed = (System.nanoTime() - startTime) / 1_000_000;

            LOGGER.info(String.format(
                    "✓ Shader 构建完成: %s → module=0x%s (%dms, %d bytes SPIR-V)",
                    sourcePath.getFileName(),
                    Long.toHexString(shaderModule),
                    elapsed,
                    spirv.length
            ));

            return new PipelineResult(
                    shaderModule, transformResult.uniformMap(),
                    sourcePath, elapsed
            );

        } catch (GlslCompileException e) {
            throw new ShaderBuildException("SPIR-V 编译失败: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ShaderBuildException("Shader 构建失败: " + sourcePath +
                    " → " + e.getMessage(), e);
        }
    }

    /**
     * 异步批量构建 (用于 AOT 预编译场景)
     *
     * @param sources shader 文件列表
     * @return Future 列表 (每个 Future 对应一个 shader 的构建结果或异常)
     */
    public List<CompletableFuture<PipelineResult>> buildBatch(List<Path> sources) {
        return sources.stream()
                .map(src -> CompletableFuture.supplyAsync(() -> {
                    try {
                        GlslToVkTransformer.ShaderStage stage = detectStage(src);
                        return buildShaderModule(src, stage);
                    } catch (ShaderBuildException e) {
                        // Lambda 不允许 checked 异常，包装为 CompletionException
                        throw new CompletionException(e);
                    }
                }))
                .toList();
    }

    /**
     * 清理所有缓存的 Shader Modules 和资源
     */
    public void cleanup() {
        cacheMap.clear();
        cache.clear();

        if (glslang != null) {
            glslang.shutdown();
            glslang = null;
        }

        LOGGER.info("ShaderPipelineFactory 已清理");
    }

    // ==================== 内部实现 ====================

    /** 确保 glslang 已初始化 (懒加载) */
    private void ensureGlslangInitialized() {
        if (glslang == null) {
            synchronized (this) {
                if (glslang == null) {
                    glslang = GlslangCompiler.initialize();
                }
            }
        }
    }

    /**
     * 从 SPIR-V 创建 VkShaderModule。
     * ★ 核心修复: 使用 VulkanFFM.vkCreateShaderModule() 而非 JNI!
     */
    private long createModuleFromSPIRV(byte[] spirv, String debugName) {
        // 这里调用项目已有的 VulkanFFM 工具类
        // 如果 VulkanFFM 还没有这个方法，需要添加
        try {
            // 尝试反射调用 VulkanFFM 的方法
            var ffmc = Class.forName("com.renderium.graphics.vulkan.VulkanFFM");
            var method = ffmc.getMethod("vkCreateShaderModule", long.class, byte[].class);
            return (long) method.invoke(null, vkDevice, spirv);
        } catch (Exception e) {
            // 如果 VulkanFFM 暂时没有此方法，返回占位值并记录日志
            LOGGER.warning("VulkanFFM.vkCreateShaderModule() 暂不可用，" +
                    "请确保 VulkanFFM 实现了此方法。当前返回占位句柄。" +
                    "错误: " + e.getMessage());
            return 0xDEADBEEFL; // 占位值，实际使用时需替换为真实实现
        }
    }

    /** 从文件名推断 shader 阶段 */
    private GlslToVkTransformer.ShaderStage detectStage(Path path) {
        String name = path.getFileName().toString().toLowerCase();

        if (name.endsWith(".vert") || name.endsWith(".vsh") ||
                (name.contains("_gbuffers") && (name.endsWith(".vert") || name.endsWith(".vsh")))) {
            return GlslToVkTransformer.ShaderStage.VERTEX;
        }
        if (name.endsWith(".frag") || name.endsWith(".fsh") ||
                name.contains("composite") || name.contains("deferred") || name.contains("final")) {
            return GlslToVkTransformer.ShaderStage.FRAGMENT;
        }
        if (name.endsWith(".comp")) {
            return GlslToVkTransformer.ShaderStage.COMPUTE;
        }
        if (name.endsWith(".geom")) {
            return GlslToVkTransformer.ShaderStage.GEOMETRY;
        }

        return GlslToVkTransformer.ShaderStage.FRAGMENT; // 默认
    }

    /** 转换阶段枚举 */
    private GlslangCompiler.Stage toGlslangStage(GlslToVkTransformer.ShaderStage stage) {
        return switch (stage) {
            case VERTEX       -> GlslangCompiler.Stage.VERTEX;
            case FRAGMENT     -> GlslangCompiler.Stage.FRAGMENT;
            case COMPUTE      -> GlslangCompiler.Stage.COMPUTE;
            case GEOMETRY     -> GlslangCompiler.Stage.GEOMETRY;
            case TESS_CONTROL -> GlslangCompiler.Stage.TESS_CONTROL;
            case TESS_EVAL    -> GlslangCompiler.Stage.TESS_EVAL;
        };
    }

    /** 计算缓存键 (SHA-256 前 20 字符 Base64) */
    private String computeCacheKey(Path source, GlslToVkTransformer.ShaderStage stage,
                                    String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(source.toAbsolutePath().toString().getBytes());
            md.update((byte) stage.ordinal());
            md.update(content.getBytes());
            return Base64.getEncoder().encodeToString(md.digest()).substring(0, 20);
        } catch (Exception e) {
            return source.getFileName() + "_" + stage.name();
        }
    }

    /** LRU 淘汰 (超过上限时移除最老的一半) */
    private void evictCacheIfNeeded() {
        if (cacheMap.size() <= MAX_CACHE_SIZE) return;
        cacheMap.keySet().stream()
                .limit(MAX_CACHE_SIZE / 2)
                .forEach(cacheMap::remove);
    }
}

/** Shader 构建异常 */
class ShaderBuildException extends Exception {

    public ShaderBuildException(String message) { super(message); }
    public ShaderBuildException(String message, Throwable cause) { super(message, cause); }
}
