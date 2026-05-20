// Renderium - 后处理 Compute Shader 分发辅助
// 共享的 GPU Compute 调度基础设施，用于后处理管道节点
// 提供 GLSL 加载 → SPIR-V 编译 → Pipeline 缓存 → Dispatch 的一站式能力

package com.ranecc.renderium.infrastructure.gpu;

import com.ranecc.renderium.feature.blaze3d.shader.GlslangCompiler;
import com.ranecc.renderium.feature.lod.compute.LodCullingComputePass;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 后处理 Compute Shader 分发辅助工具
 *
 * <p>为后处理管道节点（DOF、MotionBlur、TAA、VolumetricFog）提供统一的
 * GLSL 加载、Pipeline 缓存和 Compute Dispatch 能力。</p>
 *
 * <h3>核心流程</h3>
 * <ol>
 *   <li>从 classpath 加载 {@code .comp} 资源文件</li>
 *   <li>通过 {@link GlslangCompiler} 编译为 SPIR-V</li>
 *   <li>创建并缓存 VkPipeline（按 shader 路径唯一缓存）</li>
 *   <li>执行 vkCmdBindPipeline / vkCmdDispatch</li>
 * </ol>
 *
 * <h3>使用方式</h3>
 * <pre>
 * int groupsX = (width + 15) / 16;
 * int groupsY = (height + 15) / 16;
 * PostProcessComputeHelper.dispatch(
 *     "shaders/compute/dof_bokeh.comp",
 *     width, height,
 *     cmdBuf, colorTexture, depthTexture,
 *     outputTexture, paramsPtr
 * );
 * </pre>
 *
 * <p>当前 Pipeline Layout 为空（无 DescriptorSet），适用于仅使用
 * PushConstants 的简单 Compute Shader。完整的 Image Load/Store 支持
 * 需要在下一轮迭代中通过 {@code vkCreateDescriptorSetLayout} 创建
 * 专用的 DescriptorSet Layout。</p>
 */
public final class PostProcessComputeHelper {

    private static final Logger LOGGER = Logger.getLogger("Renderium|PostProcessCompute");

    /** 缓存已编译的 Compute Pipeline (shaderPath → pipelineHandle) */
    private static final ConcurrentHashMap<String, Long> PIPELINE_CACHE = new ConcurrentHashMap<>();

    /** 缓存已编译的 Pipeline Layout (shaderPath → layoutHandle) */
    private static final ConcurrentHashMap<String, Long> LAYOUT_CACHE = new ConcurrentHashMap<>();

    /** Shader Module 缓存 (shaderPath → moduleHandle)，用于析构时清理 */
    private static final ConcurrentHashMap<String, Long> MODULE_CACHE = new ConcurrentHashMap<>();

    /** Vulkan 常量 — VkPipelineBindPoint */
    private static final int VK_PIPELINE_BIND_POINT_COMPUTE = 1;

    /** Vulkan 常量 — VkShaderStageFlagBits */
    private static final int VK_SHADER_STAGE_COMPUTE_BIT = 0x00000020;

    /** VK_SUCCESS */
    private static final int VK_SUCCESS = 0;

    /** 全局 Pipeline Cache 句柄（0 = 不使用） */
    private static final long VK_NULL_HANDLE = 0L;

    private PostProcessComputeHelper() {}

    /**
     * 执行后处理 Compute Shader Dispatch
     *
     * <p>完整的单次 Dispatch 流程：绑定 Pipeline → 分发 Compute 工作组。
     * 工作组数量自动根据纹理尺寸以 16x16 线程组计算。</p>
     *
     * @param glslPath      GLSL 着色器资源路径 (classpath 相对路径)
     * @param width         输入纹理宽度（用于计算工作组数量）
     * @param height        输入纹理高度（用于计算工作组数量）
     * @param cmdBuf        VkCommandBuffer 句柄
     * @param inputTexture  输入颜色纹理 VkImageView 句柄 (binding 0)
     * @param depthTexture  输入深度纹理 VkImageView 句柄 (binding 1, 可为 0)
     * @param outputTexture 输出颜色纹理 VkImageView 句柄 (binding 2)
     * @param paramsPtr     参数 UBO MemorySegment 指针 (binding 3, 可为 0)
     * @return true 表示 dispatch 成功
     */
    public static boolean dispatch(String glslPath, int width, int height,
                                    long cmdBuf, long inputTexture, long depthTexture,
                                    long outputTexture, long paramsPtr) {
        if (!VulkanFFMBinding.isFfmLoaded()) {
            LOGGER.warning("FFM 未加载，跳过 compute dispatch: " + glslPath);
            return false;
        }

        try {
            long pipeline = getOrCreatePipeline(glslPath);
            if (pipeline == VK_NULL_HANDLE) {
                LOGGER.warning("Pipeline 创建失败，跳过 dispatch: " + glslPath);
                return false;
            }

            VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);

            int groupsX = Math.max(1, (width + 15) / 16);
            int groupsY = Math.max(1, (height + 15) / 16);
            VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, groupsX, groupsY, 1);

            return true;
        } catch (Throwable t) {
            LOGGER.warning("PostProcess dispatch 异常 [" + glslPath + "]: " + t.getMessage());
            return false;
        }
    }

    /**
     * 获取或创建 Compute Pipeline
     *
     * <p>按 shader 资源路径缓存，首次调用时执行完整的 Pipeline 创建链路：
     * GLSL 编译 → ShaderModule 创建 → PipelineLayout 创建 → ComputePipeline 创建。</p>
     *
     * @param glslPath GLSL 着色器资源路径
     * @return VkPipeline 句柄，失败返回 0
     */
    private static long getOrCreatePipeline(String glslPath) {
        return PIPELINE_CACHE.computeIfAbsent(glslPath, path -> {
            long device = VulkanDeviceHolder.getInstance().getVkDeviceHandle();
            if (device == VK_NULL_HANDLE) {
                LOGGER.warning("VkDevice 不可用");
                return VK_NULL_HANDLE;
            }

            try (Arena arena = Arena.ofConfined()) {
                byte[] spirv = loadAndCompileGLSL(path);
                if (spirv == null || spirv.length == 0) {
                    LOGGER.warning("SPIR-V 加载/编译失败: " + path);
                    return VK_NULL_HANDLE;
                }

                long shaderModule = createShaderModule(device, spirv, arena);
                if (shaderModule == VK_NULL_HANDLE) {
                    LOGGER.warning("ShaderModule 创建失败: " + path);
                    return VK_NULL_HANDLE;
                }
                MODULE_CACHE.put(path, shaderModule);

                long pipelineLayout = createPipelineLayout(device, arena);
                if (pipelineLayout == VK_NULL_HANDLE) {
                    LOGGER.warning("PipelineLayout 创建失败: " + path);
                    return VK_NULL_HANDLE;
                }
                LAYOUT_CACHE.put(path, pipelineLayout);

                long pipeline = createComputePipeline(device, shaderModule, pipelineLayout, arena, path);
                if (pipeline == VK_NULL_HANDLE) {
                    return VK_NULL_HANDLE;
                }

                LOGGER.info("✓ Compute Pipeline 创建成功: " + path);
                return pipeline;
            } catch (Exception e) {
                LOGGER.warning("创建 Pipeline 失败 " + path + ": " + e.getMessage());
                return VK_NULL_HANDLE;
            }
        });
    }

    /**
     * 从 classpath 加载 GLSL 源码并编译为 SPIR-V
     *
     * <p>加载策略：优先加载同路径下的 {@code .spv} 预编译文件（更高效），
     * 回退到 {@code .comp} GLSL 源码实时编译。</p>
     *
     * @param glslPath GLSL 资源路径（如 "shaders/compute/dof_bokeh.comp"）
     * @return SPIR-V 字节数组，失败返回 null
     */
    private static byte[] loadAndCompileGLSL(String glslPath) {
        ClassLoader cl = PostProcessComputeHelper.class.getClassLoader();

        String spvPath = glslPath.replace(".comp", ".spv");
        try (InputStream spvIs = cl.getResourceAsStream(spvPath)) {
            if (spvIs != null) {
                byte[] spirv = spvIs.readAllBytes();
                if (spirv.length > 4) {
                    LOGGER.fine("加载预编译 SPIR-V: " + spvPath + " (" + spirv.length + " bytes)");
                    return spirv;
                }
            }
        } catch (IOException e) {
            // .spv 文件不存在，尝试 GLSL 编译
        }

        try (InputStream is = cl.getResourceAsStream(glslPath)) {
            if (is == null) {
                LOGGER.warning("GLSL 资源不存在: " + glslPath);
                return null;
            }
            String glslSource = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return compileGLSL(glslSource);
        } catch (IOException e) {
            LOGGER.warning("读取 GLSL 资源失败 " + glslPath + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 编译 GLSL 源码为 SPIR-V 字节码
     *
     * <p>通过 {@link GlslangCompiler} 实例将 GLSL Compute Shader 源码
     * 编译为 SPIR-V 二进制格式。优先使用直接调用，若类不可达则回退反射。</p>
     *
     * @param source GLSL 源码字符串
     * @return SPIR-V 字节数组，编译失败返回 null
     */
    private static byte[] compileGLSL(String source) {
        GlslangCompiler compiler = GlslangCompiler.getInstance();
        if (compiler == null || !compiler.isInitialized()) {
            LOGGER.warning("GlslangCompiler 未初始化，尝试延迟初始化...");
            try {
                compiler = GlslangCompiler.initialize();
            } catch (Exception e) {
                LOGGER.warning("GlslangCompiler 初始化失败: " + e.getMessage());
                return compileGLSLReflective(source);
            }
        }

        try {
            return compiler.compile(source, GlslangCompiler.Stage.COMPUTE);
        } catch (Exception e) {
            LOGGER.warning("GLSL 直接编译失败，尝试反射回退: " + e.getMessage());
            return compileGLSLReflective(source);
        }
    }

    /**
     * 反射回退方式的 GLSL 编译
     *
     * <p>当直接调用 {@link GlslangCompiler} 不可行时（如跨模块类加载问题），
     * 通过反射调用 compile 方法。</p>
     */
    private static byte[] compileGLSLReflective(String source) {
        try {
            Class<?> cc = Class.forName("com.ranecc.renderium.feature.blaze3d.shader.GlslangCompiler");
            Object compiler = cc.getMethod("getInstance").invoke(null);

            Boolean initialized = (Boolean) cc.getMethod("isInitialized").invoke(compiler);
            if (!initialized) {
                cc.getMethod("initialize").invoke(null);
            }

            Class<?> stageEnum = Class.forName("com.ranecc.renderium.feature.blaze3d.shader.GlslangCompiler$Stage");
            Object computeStage = stageEnum.getEnumConstants()[5];
            return (byte[]) cc.getMethod("compile", String.class, stageEnum)
                    .invoke(compiler, source, computeStage);
        } catch (Exception e) {
            LOGGER.warning("GLSL 反射编译失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 创建 VkShaderModule
     *
     * @param device VkDevice 句柄
     * @param spirv  SPIR-V 字节码
     * @param arena  临时内存分配器
     * @return VkShaderModule 句柄，失败返回 0
     */
    private static long createShaderModule(long device, byte[] spirv, Arena arena) {
        try {
            MemorySegment spirvSeg = arena.allocate(spirv.length, 4);
            spirvSeg.asByteBuffer().put(spirv);

            MemorySegment createInfo = arena.allocate(ValueLayout.JAVA_LONG, 4);
            createInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 14L);
            createInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
            createInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, (long) spirv.length);
            createInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, spirvSeg.address());

            MemorySegment moduleOut = arena.allocate(ValueLayout.JAVA_LONG);
            int result = (int) VulkanAPIRegistry.invoke("vkCreateShaderModule",
                    device, createInfo.address(), VK_NULL_HANDLE, moduleOut.address());

            if (result != VK_SUCCESS) {
                LOGGER.warning("vkCreateShaderModule 失败, result=" + result);
                return VK_NULL_HANDLE;
            }
            return moduleOut.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable t) {
            LOGGER.warning("创建 ShaderModule 异常: " + t.getMessage());
            return VK_NULL_HANDLE;
        }
    }

    /**
     * 创建 Pipeline Layout
     *
     * <p>当前创建空 Pipeline Layout（无 DescriptorSet），适用于仅使用
     * PushConstants 的简单场景。后续需扩展为包含 PostProcess 专用
     * DescriptorSet Layout（3 个 StorageImage + 1 个 UBO）。</p>
     *
     * @param device VkDevice 句柄
     * @param arena  临时内存分配器
     * @return VkPipelineLayout 句柄，失败返回 0
     */
    private static long createPipelineLayout(long device, Arena arena) {
        try {
            MemorySegment layoutInfo = arena.allocate(ValueLayout.JAVA_LONG, 4);
            layoutInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 38L);
            layoutInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
            layoutInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);
            layoutInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 0L);

            MemorySegment layoutOut = arena.allocate(ValueLayout.JAVA_LONG);
            int result = (int) VulkanAPIRegistry.invoke("vkCreatePipelineLayout",
                    device, layoutInfo.address(), VK_NULL_HANDLE, layoutOut.address());

            if (result != VK_SUCCESS) {
                LOGGER.warning("vkCreatePipelineLayout 失败, result=" + result);
                return VK_NULL_HANDLE;
            }
            return layoutOut.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable t) {
            LOGGER.warning("创建 PipelineLayout 异常: " + t.getMessage());
            return VK_NULL_HANDLE;
        }
    }

    /**
     * 创建 Compute Pipeline
     *
     * @param device         VkDevice 句柄
     * @param shaderModule   VkShaderModule 句柄
     * @param pipelineLayout VkPipelineLayout 句柄
     * @param arena          临时内存分配器
     * @param debugPath      调试用 shader 路径
     * @return VkPipeline 句柄，失败返回 0
     */
    private static long createComputePipeline(long device, long shaderModule,
                                                long pipelineLayout, Arena arena, String debugPath) {
        try {
            byte[] mainBytes = "main\0".getBytes(StandardCharsets.UTF_8);
            MemorySegment mainName = arena.allocate(mainBytes.length, 1);
            mainName.asByteBuffer().put(mainBytes);

            MemorySegment stageInfo = arena.allocate(ValueLayout.JAVA_LONG, 7);
            stageInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 10L);
            stageInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
            stageInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);
            stageInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, (long) VK_SHADER_STAGE_COMPUTE_BIT);
            stageInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, shaderModule);
            stageInfo.setAtIndex(ValueLayout.JAVA_LONG, 5, mainName.address());
            stageInfo.setAtIndex(ValueLayout.JAVA_LONG, 6, 0L);

            MemorySegment pipelineInfo = arena.allocate(ValueLayout.JAVA_LONG, 8);
            pipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 24L);
            pipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
            pipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);
            pipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, stageInfo.address());
            pipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, pipelineLayout);
            pipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 5, 0L);
            pipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 6, 0L);
            pipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 7, -1L);

            MemorySegment pipelineOut = arena.allocate(ValueLayout.JAVA_LONG);
            int result = (int) VulkanAPIRegistry.invoke("vkCreateComputePipelines",
                    device, VK_NULL_HANDLE, 1, pipelineInfo.address(), VK_NULL_HANDLE, pipelineOut.address());

            if (result != VK_SUCCESS) {
                LOGGER.warning("vkCreateComputePipelines 失败 [" + debugPath + "], result=" + result);
                return VK_NULL_HANDLE;
            }
            return pipelineOut.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable t) {
            LOGGER.warning("创建 ComputePipeline 异常 [" + debugPath + "]: " + t.getMessage());
            return VK_NULL_HANDLE;
        }
    }

    /**
     * 清理指定 shader 的缓存资源
     *
     * <p>释放 Pipeline、PipelineLayout、ShaderModule 的 Vulkan 句柄。
     * 通常在模块卸载或资源重建时调用。</p>
     *
     * @param glslPath GLSL 资源路径
     */
    public static void cleanup(String glslPath) {
        long device = VulkanDeviceHolder.getInstance().getVkDeviceHandle();
        if (device == VK_NULL_HANDLE) return;

        Long pipeline = PIPELINE_CACHE.remove(glslPath);
        if (pipeline != null && pipeline != VK_NULL_HANDLE) {
            try {
                VulkanAPIRegistry.invoke("vkDestroyPipeline", device, pipeline, VK_NULL_HANDLE);
            } catch (Throwable ignored) {}
        }

        Long layout = LAYOUT_CACHE.remove(glslPath);
        if (layout != null && layout != VK_NULL_HANDLE) {
            try {
                VulkanAPIRegistry.invoke("vkDestroyPipelineLayout", device, layout, VK_NULL_HANDLE);
            } catch (Throwable ignored) {}
        }

        Long module = MODULE_CACHE.remove(glslPath);
        if (module != null && module != VK_NULL_HANDLE) {
            try {
                VulkanAPIRegistry.invoke("vkDestroyShaderModule", device, module, VK_NULL_HANDLE);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * 清理全部缓存资源
     *
     * <p>在渲染器关闭或完全的 Pipeline 重建时调用。</p>
     */
    public static void cleanupAll() {
        for (String path : PIPELINE_CACHE.keySet()) {
            cleanup(path);
        }
    }
}
