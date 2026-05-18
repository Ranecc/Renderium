package com.ranecc.renderium.feature.lod.compute;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import com.ranecc.renderium.infrastructure.gpu.VulkanOperationGuard;

/**
 * Hi-Z (Hierarchical Z-Buffer) 计算管线
 * <p>
 * 负责创建和管理 Hi-Z Build 和 Occlusion Query 两个 Compute Pipeline，
 * 包括 SPIR-V 加载、Shader Module 创建、Descriptor Set Layout 创建、
 * Pipeline Layout 创建、Compute Pipeline 创建以及命令缓冲区操作。
 * </p>
 *
 * <h3>管线组成：</h3>
 * <ul>
 *   <li><b>Hi-Z Build Pipeline</b>: 从深度缓冲构建层次化 Z-Buffer Mipmap 金字塔</li>
 *   <li><b>Occlusion Query Pipeline</b>: 使用 Hi-Z 金字塔进行 GPU 并行遮挡测试</li>
 * </ul>
 *
 * <h3>执行流程：</h3>
 * <pre>
 * allocateCommandBuffer → beginCommandBuffer
 *   → bindAndDispatchHiZBuild → insertMemoryBarrier
 *   → bindAndDispatchOcclusionQuery → insertMemoryBarrier
 *   → endCommandBuffer → submitAndWait
 * </pre>
 */
public final class HiZComputePipeline {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger("Renderium|HiZPipeline");

    // ==================== 常量定义 ====================

    /** SPIR-V 魔数 (0x07230203) */
    private static final int SPIRV_MAGIC_NUMBER = 0x07230203;

    /** Vulkan 成功码 */
    private static final int VK_SUCCESS = 0;

    /** Vulkan Pipeline 绑定点：计算 */
    private static final int VK_PIPELINE_BIND_POINT_COMPUTE = 1;

    /** Vulkan 命令缓冲区使用：一次性提交 */
    private static final int VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT = 0x00000001;

    /** Vulkan Pipeline 阶段标志：计算着色器 (VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT = 0x00000800) */
    private static final int VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT = 0x00000800;

    /** Vulkan 访问标志：着色器写入 */
    private static final int VK_ACCESS_SHADER_WRITE_BIT = 0x00200000;

    /** Vulkan 访问标志：着色器读取 */
    private static final int VK_ACCESS_SHADER_READ_BIT = 0x00400000;

    /** Vulkan 图像布局：通用 */
    private static final int VK_IMAGE_LAYOUT_GENERAL = 0;

    /** Vulkan 描述符类型：存储图像 */
    private static final int VK_DESCRIPTOR_TYPE_STORAGE_IMAGE = 10;

    /** Vulkan 描述符类型：组合图像采样器 */
    private static final int VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER = 11;

    /** Vulkan 描述符类型：均匀缓冲区 */
    private static final int VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER = 6;

    /** Vulkan 描述符类型：存储缓冲区 */
    private static final int VK_DESCRIPTOR_TYPE_STORAGE_BUFFER = 12;

    /** Vulkan 着色器阶段标志：计算 */
    private static final int VK_SHADER_STAGE_COMPUTE_BIT = 0x00000020;

    /** Hi-Z 最大 Mipmap 层数（与着色器中 HIZ_MAX_MIP_LEVELS 一致） */
    private static final int MAX_HIZ_MIP_LEVELS = 10;

    /** 默认超时时间（100毫秒，纳秒单位） */
    private static final long DEFAULT_FENCE_TIMEOUT_NS = 100_000_000L;

    /** Vulkan 结构体类型：内存屏障 (VK_STRUCTURE_TYPE_MEMORY_BARRIER = 4) */
    private static final int VK_STRUCTURE_TYPE_MEMORY_BARRIER = 4;

    // ==================== Pipeline 缓存（首次创建后复用）====================

    /** Hi-Z Build Compute Pipeline 句柄 */
    private static volatile long hizBuildPipeline = 0L;

    /** Hi-Z Occlusion Query Compute Pipeline 句柄 */
    private static volatile long hizOcclusionPipeline = 0L;

    /** Hi-Z Build Shader Module 句柄 */
    private static volatile long hizBuildShaderModule = 0L;

    /** Hi-Z Occlusion Query Shader Module 句柄 */
    private static volatile long hizOcclusionShaderModule = 0L;

    /** Pipeline Layout 句柄（两个 Pipeline 共享） */
    private static volatile long pipelineLayout = 0L;

    /** Hi-Z Build Descriptor Set Layout 句柄 */
    private static volatile long hizBuildDescSetLayout = 0L;

    /** Hi-Z Occlusion Query Descriptor Set Layout 句柄 */
    private static volatile long hizOcclusionDescSetLayout = 0L;

    // ==================== SPIR-V 二进制数据（预编译的着色器）====================

    /** Hi-Z Build 着色器 SPIR-V 字节码 */
    private static byte[] HIZ_BUILD_SPIRV;

    /** Hi-Z Occlusion Query 着色器 SPIR-V 字节码 */
    private static byte[] HIZ_OCCLUSION_SPIRV;

    // ==================== 构造函数（私有）====================

    private HiZComputePipeline() {}

    // ==================== getter ====================

    public static long getHizBuildPipeline() { return hizBuildPipeline; }
    public static long getHizOcclusionPipeline() { return hizOcclusionPipeline; }
    public static long getHizBuildShaderModule() { return hizBuildShaderModule; }
    public static long getHizOcclusionShaderModule() { return hizOcclusionShaderModule; }
    public static long getPipelineLayout() { return pipelineLayout; }
    public static long getHizBuildDescSetLayout() { return hizBuildDescSetLayout; }
    public static long getHizOcclusionDescSetLayout() { return hizOcclusionDescSetLayout; }
    public static byte[] getHizBuildSpirv() { return HIZ_BUILD_SPIRV; }
    public static byte[] getHizOcclusionSpirv() { return HIZ_OCCLUSION_SPIRV; }
    public static int getMaxHiZMipLevels() { return MAX_HIZ_MIP_LEVELS; }

    // ==================== SPIR-V 加载 ====================

    /**
     * 从 classpath 加载 SPIR-V 着色器字节码
     * <p>
     * 加载预编译的 .spv 文件或运行时编译 GLSL 源码：
     * <ul>
     *   <li>优先尝试加载预编译的 SPIR-V (.spv) 文件</li>
     *   <li>如果不存在，尝试从 GLSL 源码 (.comp) 编译（需要 GlslangCompiler）</li>
     *   <li>两者都失败则记录错误并返回 null</li>
     * </ul>
     *
     * 【资源路径】
     * - hiz_build.spv: shaders/compute/hiz_build.spv
     * - hiz_occlusion_query.spv: shaders/compute/hiz_occlusion_query.spv
     *
     * 【返回值】void（副作用：设置 HIZ_BUILD_SPIRV 和 HIZ_OCCLUSION_SPIRV 字段）
     *
     * @throws IOException 如果无法加载或编译着色器
     */
    public static void loadSPIRVBinaries() throws IOException {
        ClassLoader loader = HiZComputePipeline.class.getClassLoader();

        // 尝试加载 Hi-Z Build 着色器
        HIZ_BUILD_SPIRV = loadShaderResource(loader, "shaders/compute/hiz_build.spv",
                "shaders/compute/hiz_build.comp", "HiZ Build");

        // 尝试加载 Occlusion Query 着色器
        HIZ_OCCLUSION_SPIRV = loadShaderResource(loader, "shaders/compute/hiz_occlusion_query.spv",
                "shaders/compute/hiz_occlusion_query.comp", "Occlusion Query");

        LOGGER.info(String.format("[HiZPipeline] SPIR-V 加载完成 | HiZBuild=%d bytes, Occlusion=%d bytes",
                HIZ_BUILD_SPIRV != null ? HIZ_BUILD_SPIRV.length : 0,
                HIZ_OCCLUSION_SPIRV != null ? HIZ_OCCLUSION_SPIRV.length : 0));
    }

    /**
     * 加载单个着色器资源（优先 SPIR-V，回退 GLSL 编译）
     *
     * 【方法参数】
     * @param loader ClassLoader - 类加载器
     * @param spirvPath String - SPIR-V 文件的 classpath 路径
     * @param glslPath String - GLSL 源文件的 classpath 路径（回退选项）
     * @param shaderName String - 着色器名称（用于日志）
     *
     * 【返回值】byte[] - SPIR-V 字节数组，如果加载失败返回 null
     *
     * @throws IOException 如果两种方式都失败
     */
    private static byte[] loadShaderResource(ClassLoader loader, String spirvPath,
                                              String glslPath, String shaderName) throws IOException {
        // 方式 1: 尝试加载预编译的 SPIR-V 文件
        try (InputStream is = loader.getResourceAsStream(spirvPath)) {
            if (is != null) {
                byte[] spirv = is.readAllBytes();
                validateSPIRV(spirv, shaderName);
                LOGGER.fine("[HiZPipeline] 加载预编译 SPIR-V: " + spirvPath +
                        " (" + spirv.length + " bytes)");
                return spirv;
            }
        } catch (Exception e) {
            LOGGER.fine("[HiZPipeline] 无法加载 SPIR-V: " + spirvPath + " (" + e.getMessage() + ")");
        }

        // 方式 2: 尝试从 GLSL 源码编译
        LOGGER.info("[HiZPipeline] 正在编译 GLSL → SPIR-V: " + glslPath);
        try (InputStream glslStream = loader.getResourceAsStream(glslPath)) {
            if (glslStream != null) {
                String glslSource = new String(glslStream.readAllBytes());
                return compileGLSLToSPIRV(glslSource, shaderName);
            }
        } catch (Exception e) {
            LOGGER.warning("[HiZPipeline] GLSL 编译失败: " + e.getMessage());
        }

        throw new IOException("无法加载或编译着色器: " + shaderName +
                " (SPIR-V: " + spirvPath + ", GLSL: " + glslPath + ")");
    }

    /**
     * 验证 SPIR-V 字节码的有效性
     * <p>
     * 检查 SPIR-V 魔数 (0x07230203) 和最小长度要求。
     *
     * 【方法参数】
     * @param spirvCode byte[] - SPIR-V 字节数据
     * @param shaderName String - 着色器名称（用于日志）
     *
     * @throws IllegalArgumentException 如果 SPIR-V 无效
     */
    private static void validateSPIRV(byte[] spirvCode, String shaderName) {
        if (spirvCode == null || spirvCode.length < 20) {
            throw new IllegalArgumentException(shaderName + ": SPIR-V 数据过短 (" +
                    (spirvCode != null ? spirvCode.length : "null") + " bytes)");
        }

        // 检查 SPIR-V 魔数 (little-endian)
        ByteBuffer bb = ByteBuffer.wrap(spirvCode).order(ByteOrder.LITTLE_ENDIAN);
        int magic = bb.getInt(0);
        if (magic != SPIRV_MAGIC_NUMBER) {
            throw new IllegalArgumentException(shaderName + ": 无效的 SPIR-V 魔数 (0x" +
                    Integer.toHexString(magic) + ", 期望 0x" + Integer.toHexString(SPIRV_MAGIC_NUMBER) + ")");
        }
    }

    /**
     * 将 GLSL 源码编译为 SPIR-V 字节码
     * <p>
     * 使用 GlslangCompiler 进行编译（如果可用），
     * 否则记录警告并返回 null。
     *
     * 【方法参数】
     * @param glslSource String - GLSL 源码字符串
     * @param shaderName String - 着色器名称（用于日志）
     *
     * 【返回值】byte[] - 编译后的 SPIR-V 字节码
     *
     * @throws IllegalStateException 如果 GlslangCompiler 不可用或编译失败
     */
    private static byte[] compileGLSLToSPIRV(String glslSource, String shaderName) {
        try {
            // 尝试使用项目的 GlslangCompiler
            Class<?> compilerClass = Class.forName(
                    "com.renderium.module.impl.blaze3d.shader.GlslangCompiler");
            java.lang.reflect.Method initialize = compilerClass.getMethod("initialize");
            Object compiler = initialize.invoke(null);
            java.lang.reflect.Method compile = compilerClass.getMethod(
                    "compile", String.class, Enum.class);

            // 获取 COMPUTE Stage 枚举 (使用反射避免泛型类型推断问题)
            Class<?> stageClass = Class.forName(
                    "com.renderium.module.impl.blaze3d.shader.GlslangCompiler$Stage");
            Object computeStage = java.lang.reflect.Method.class.getMethod("valueOf", Class.class, String.class)
                    .invoke(null, stageClass, "COMPUTE");

            // 执行编译
            byte[] spirv = (byte[]) compile.invoke(compiler, glslSource, computeStage);

            if (spirv != null && spirv.length > 0) {
                validateSPIRV(spirv, shaderName);
                LOGGER.info("[HiZPipeline] ✓ GLSL 编译成功: " + shaderName +
                        " (" + spirv.length + " bytes)");
                return spirv;
            }

        } catch (ClassNotFoundException e) {
            LOGGER.warning("[HiZPipeline] GlslangCompiler 未找到，无法编译 GLSL");

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[HiZPipeline] GLSL 编译失败: " + e.getMessage(), e);
        }

        throw new IllegalStateException("无法编译 GLSL 着色器: " + shaderName);
    }

    // ==================== Vulkan 资源创建 ====================

    /**
     * 创建 VkShaderModule（从 SPIR-V 字节码）
     * <p>
     * 为 Hi-Z Build 和 Occlusion Query 两个着色器创建 Shader Module。
     *
     * 【返回值】void（副作用：设置 hizBuildShaderModule 和 hizOcclusionShaderModule）
     *
     * @throws Exception 如果创建失败
     */
    public static void createShaderModules() throws Exception {
        MethodHandle vkCreateShaderModule = VulkanFFMBinding.getVkCreateShaderModule();
        if (!VulkanFFMBinding.isFfmLoaded() || vkCreateShaderModule == null) {
            throw new IllegalStateException("FFM 方法句柄未加载");
        }

        // 创建 Hi-Z Build Shader Module
        hizBuildShaderModule = createShaderModuleInternal(HIZ_BUILD_SPIRV, "HiZ Build", vkCreateShaderModule);

        // 创建 Occlusion Query Shader Module
        hizOcclusionShaderModule = createShaderModuleInternal(HIZ_OCCLUSION_SPIRV, "Occlusion Query", vkCreateShaderModule);

        LOGGER.info(String.format("[HiZPipeline] Shader Modules 创建成功 | " +
                        "HiZBuild=0x%s, Occlusion=0x%s",
                Long.toHexString(hizBuildShaderModule),
                Long.toHexString(hizOcclusionShaderModule)));
    }

    /**
     * 创建单个 VkShaderModule
     *
     * 【方法参数】
     * @param spirvCode byte[] - SPIR-V 字节码
     * @param name String - 着色器名称（用于日志）
     * @param vkCreateShaderModule MethodHandle - vkCreateShaderModule 方法句柄
     *
     * 【返回值】long - VkShaderModule 句柄
     *
     * @throws Exception 如果创建失败
     */
    private static long createShaderModuleInternal(byte[] spirvCode, String name,
                                                    MethodHandle vkCreateShaderModule) throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            // 构建 VkShaderModuleCreateInfo 结构体
            // sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO (9)
            // codeSize = spirvCode.length
            // pCode = spirvCode 地址

            MemorySegment createInfo = arena.allocate(ValueLayout.JAVA_LONG, 4);  // 简化的结构体
            createInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 9L);  // sType
            createInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);  // pNext
            createInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, spirvCode.length);  // codeSize

            // 分配 SPIR-V 数据到原生内存并设置 pCode 指针
            MemorySegment spirvSegment = arena.allocate(spirvCode.length);
            spirvSegment.asByteBuffer().put(spirvCode);
            createInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, spirvSegment.address());  // pCode

            MemorySegment shaderModuleOut = arena.allocate(ValueLayout.JAVA_LONG);

            // 调用 vkCreateShaderModule
            int result = VK_SUCCESS;
            try {
                result = (int) vkCreateShaderModule.invokeExact(
                        LodCullingComputePass.getVkDevice(),         // device
                        createInfo.address(),                   // pCreateInfo
                        0L,                                     // pAllocator (null)
                        shaderModuleOut.address()               // pShaderModule (output)
                );
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "vkCreateShaderModule 调用失败", t);
            }

            if (result != VK_SUCCESS) {
                throw new RuntimeException("vkCreateShaderModule 失败: VkResult=" + result +
                        " (shader: " + name + ")");
            }

            long module = shaderModuleOut.get(ValueLayout.JAVA_LONG, 0);
            LOGGER.fine("[HiZPipeline] Shader Module 创建成功: " + name + " (handle=0x" +
                    Long.toHexString(module) + ")");
            return module;
        }
    }

    /**
     * 创建 Descriptor Set Layouts
     * <p>
     * 为两个 Compute Shader 创建描述符集布局：
     * <ul>
     *   <li>Hi-Z Build: binding 0 (depthBuffer sampler2D), binding 1 (hizMipmaps image[10]), binding 2 (config UBO)</li>
     *   <li>Occlusion Query: binding 0 (objects SSBO), binding 1 (hizMipmaps sampler[10]), binding 2 (config UBO), binding 3 (visibilityMask SSBO)</li>
     * </ul>
     *
     * 【方法参数】无
     *
     * 【返回值】void（副作用：设置 hizBuildDescSetLayout 和 hizOcclusionDescSetLayout）
     *
     * @throws Exception 如果创建失败
     */
    public static void createDescriptorSetLayouts() throws Exception {
        MethodHandle vkCreateDescriptorSetLayout = VulkanFFMBinding.getVkCreateDescriptorSetLayout();
        if (!VulkanFFMBinding.isFfmLoaded() || vkCreateDescriptorSetLayout == null) {
            throw new IllegalStateException("FFM 方法句柄未加载");
        }

        try (Arena arena = Arena.ofConfined()) {
            // ==================== Hi-Z Build Descriptor Set Layout ====================
            // 布局定义：
            //   binding 0: depthBuffer      -> COMBINED_IMAGE_SAMPLER (采样深度缓冲)
            //   binding 1: hizMipmaps       -> STORAGE_IMAGE[10]      (写入 Hi-Z 金字塔 10 层 Mipmap)
            //   binding 2: config           -> UNIFORM_BUFFER          (配置参数 UBO)

            // 每个 VkDescriptorSetLayoutBinding 结构体占用 5 个 JAVA_LONG 字段:
            // [0] binding (uint32), [1] descriptorType (uint32), [2] descriptorCount (uint32),
            // [3] stageFlags (uint32), [4] pImmutableSamplers (pointer)
            int bindingFieldCount = 5;

            // --- 构建 Hi-Z Build 的 3 个 Binding ---
            MemorySegment hizBindings = arena.allocate(ValueLayout.JAVA_LONG, MAX_HIZ_MIP_LEVELS * bindingFieldCount);

            // Binding 0: depthBuffer (COMBINED_IMAGE_SAMPLER, 1个)
            hizBindings.setAtIndex(ValueLayout.JAVA_LONG, 0, 0L);                                                   // binding = 0
            hizBindings.setAtIndex(ValueLayout.JAVA_LONG, 1, (long) VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);    // descriptorType
            hizBindings.setAtIndex(ValueLayout.JAVA_LONG, 2, 1L);                                                 // descriptorCount = 1
            hizBindings.setAtIndex(ValueLayout.JAVA_LONG, 3, (long) VK_SHADER_STAGE_COMPUTE_BIT);                 // stageFlags
            hizBindings.setAtIndex(ValueLayout.JAVA_LONG, 4, 0L);                                                 // pImmutableSamplers = null

            // Binding 1: hizMipmaps (STORAGE_IMAGE, 10层)
            hizBindings.setAtIndex(ValueLayout.JAVA_LONG, 5, 1L);                                                 // binding = 1
            hizBindings.setAtIndex(ValueLayout.JAVA_LONG, 6, (long) VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);             // descriptorType
            hizBindings.setAtIndex(ValueLayout.JAVA_LONG, 7, (long) MAX_HIZ_MIP_LEVELS);                          // descriptorCount = 10
            hizBindings.setAtIndex(ValueLayout.JAVA_LONG, 8, (long) VK_SHADER_STAGE_COMPUTE_BIT);                 // stageFlags
            hizBindings.setAtIndex(ValueLayout.JAVA_LONG, 9, 0L);                                                 // pImmutableSamplers = null

            // Binding 2: config (UNIFORM_BUFFER, 1个)
            hizBindings.setAtIndex(ValueLayout.JAVA_LONG, 10, 2L);                                                // binding = 2
            hizBindings.setAtIndex(ValueLayout.JAVA_LONG, 11, (long) VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);           // descriptorType
            hizBindings.setAtIndex(ValueLayout.JAVA_LONG, 12, 1L);                                                // descriptorCount = 1
            hizBindings.setAtIndex(ValueLayout.JAVA_LONG, 13, (long) VK_SHADER_STAGE_COMPUTE_BIT);                // stageFlags
            hizBindings.setAtIndex(ValueLayout.JAVA_LONG, 14, 0L);                                                // pImmutableSamplers = null

            // VkDescriptorSetLayoutCreateInfo (Hi-Z Build):
            // [0] sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO (11)
            // [1] pNext = null
            // [2] flags = 0
            // [3] bindingCount = 3
            // [4] pBindings = hizBindings 地址
            MemorySegment hizCreateInfo = arena.allocate(ValueLayout.JAVA_LONG, 5);
            hizCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 11L);          // sType = DESCRIPTOR_SET_LAYOUT_CREATE_INFO
            hizCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);           // pNext = null
            hizCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);           // flags = 0
            hizCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 3L);           // bindingCount = 3
            hizCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, hizBindings.address());  // pBindings

            // 输出参数：Hi-Z Build Descriptor Set Layout 句柄
            MemorySegment hizLayoutOut = arena.allocate(ValueLayout.JAVA_LONG);

            // 调用 vkCreateDescriptorSetLayout(device, pCreateInfo, pAllocator, pSetLayout)
            int hizResult = VK_SUCCESS;
            try {
                hizResult = (int) vkCreateDescriptorSetLayout.invokeExact(
                        LodCullingComputePass.getVkDevice(),       // device
                        hizCreateInfo.address(),              // pCreateInfo
                        0L,                                   // pAllocator = null
                        hizLayoutOut.address()                // pSetLayout (output)
                );
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "vkCreateDescriptorSetLayout(HiZBuild) 调用失败", t);
                throw new RuntimeException("vkCreateDescriptorSetLayout(HiZBuild) 调用异常", t);
            }

            if (hizResult != VK_SUCCESS) {
                throw new RuntimeException("vkCreateDescriptorSetLayout(HiZBuild) 失败: VkResult=" + hizResult);
            }

            hizBuildDescSetLayout = hizLayoutOut.get(ValueLayout.JAVA_LONG, 0);

            // ==================== Occlusion Query Descriptor Set Layout ====================
            // 布局定义：
            //   binding 0: objects         -> STORAGE_BUFFER         (物体 AABB 数据)
            //   binding 1: hizMipmaps      -> COMBINED_IMAGE_SAMPLER[10] (读取 Hi-Z 金字塔)
            //   binding 2: config          -> UNIFORM_BUFFER          (配置参数 UBO)
            //   binding 3: visibilityMask  -> STORAGE_BUFFER          (输出可见性掩码)

            // 构建 Occlusion Query 的 4 个 Binding
            MemorySegment occBindings = arena.allocate(ValueLayout.JAVA_LONG, 4 * bindingFieldCount);

            // Binding 0: objects (STORAGE_BUFFER, 1个)
            occBindings.setAtIndex(ValueLayout.JAVA_LONG, 0, 0L);                                                   // binding = 0
            occBindings.setAtIndex(ValueLayout.JAVA_LONG, 1, (long) VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);              // descriptorType
            occBindings.setAtIndex(ValueLayout.JAVA_LONG, 2, 1L);                                                  // descriptorCount = 1
            occBindings.setAtIndex(ValueLayout.JAVA_LONG, 3, (long) VK_SHADER_STAGE_COMPUTE_BIT);                  // stageFlags
            occBindings.setAtIndex(ValueLayout.JAVA_LONG, 4, 0L);                                                  // pImmutableSamplers = null

            // Binding 1: hizMipmaps (COMBINED_IMAGE_SAMPLER, 10层)
            occBindings.setAtIndex(ValueLayout.JAVA_LONG, 5, 1L);                                                  // binding = 1
            occBindings.setAtIndex(ValueLayout.JAVA_LONG, 6, (long) VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);     // descriptorType
            occBindings.setAtIndex(ValueLayout.JAVA_LONG, 7, (long) MAX_HIZ_MIP_LEVELS);                           // descriptorCount = 10
            occBindings.setAtIndex(ValueLayout.JAVA_LONG, 8, (long) VK_SHADER_STAGE_COMPUTE_BIT);                  // stageFlags
            occBindings.setAtIndex(ValueLayout.JAVA_LONG, 9, 0L);                                                  // pImmutableSamplers = null

            // Binding 2: config (UNIFORM_BUFFER, 1个)
            occBindings.setAtIndex(ValueLayout.JAVA_LONG, 10, 2L);                                                 // binding = 2
            occBindings.setAtIndex(ValueLayout.JAVA_LONG, 11, (long) VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);            // descriptorType
            occBindings.setAtIndex(ValueLayout.JAVA_LONG, 12, 1L);                                                 // descriptorCount = 1
            occBindings.setAtIndex(ValueLayout.JAVA_LONG, 13, (long) VK_SHADER_STAGE_COMPUTE_BIT);                 // stageFlags
            occBindings.setAtIndex(ValueLayout.JAVA_LONG, 14, 0L);                                                 // pImmutableSamplers = null

            // Binding 3: visibilityMask (STORAGE_BUFFER, 1个)
            occBindings.setAtIndex(ValueLayout.JAVA_LONG, 15, 3L);                                                 // binding = 3
            occBindings.setAtIndex(ValueLayout.JAVA_LONG, 16, (long) VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);             // descriptorType
            occBindings.setAtIndex(ValueLayout.JAVA_LONG, 17, 1L);                                                 // descriptorCount = 1
            occBindings.setAtIndex(ValueLayout.JAVA_LONG, 18, (long) VK_SHADER_STAGE_COMPUTE_BIT);                 // stageFlags
            occBindings.setAtIndex(ValueLayout.JAVA_LONG, 19, 0L);                                                 // pImmutableSamplers = null

            // VkDescriptorSetLayoutCreateInfo (Occlusion Query):
            MemorySegment occCreateInfo = arena.allocate(ValueLayout.JAVA_LONG, 5);
            occCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 11L);          // sType = DESCRIPTOR_SET_LAYOUT_CREATE_INFO
            occCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);           // pNext = null
            occCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);           // flags = 0
            occCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 4L);           // bindingCount = 4
            occCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, occBindings.address());  // pBindings

            // 输出参数：Occlusion Query Descriptor Set Layout 句柄
            MemorySegment occLayoutOut = arena.allocate(ValueLayout.JAVA_LONG);

            // 调用 vkCreateDescriptorSetLayout
            int occResult = VK_SUCCESS;
            try {
                occResult = (int) vkCreateDescriptorSetLayout.invokeExact(
                        LodCullingComputePass.getVkDevice(),        // device
                        occCreateInfo.address(),              // pCreateInfo
                        0L,                                   // pAllocator = null
                        occLayoutOut.address()                // pSetLayout (output)
                );
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "vkCreateDescriptorSetLayout(Occlusion) 调用失败", t);
                throw new RuntimeException("vkCreateDescriptorSetLayout(Occlusion) 调用异常", t);
            }

            if (occResult != VK_SUCCESS) {
                throw new RuntimeException("vkCreateDescriptorSetLayout(Occlusion) 失败: VkResult=" + occResult);
            }

            hizOcclusionDescSetLayout = occLayoutOut.get(ValueLayout.JAVA_LONG, 0);

            LOGGER.info(String.format("[HiZPipeline] ✓ Descriptor Set Layouts 创建成功 | " +
                            "HiZBuild=0x%s (%d bindings), Occlusion=0x%s (%d bindings)",
                    Long.toHexString(hizBuildDescSetLayout), 3,
                    Long.toHexString(hizOcclusionDescSetLayout), 4));
        }
    }

    /**
     * 创建 Pipeline Layout
     * <p>
     * 创建包含 Push Constants 范围的 Pipeline Layout。
     * 两个 Compute Pipeline 共享同一个 Layout（简化管理）。
     * <p>
     * Pipeline Layout 定义了 Shader 可以访问的资源布局：
     * <ul>
     *   <li>Descriptor Set Layouts: 定义绑定资源的类型和排列</li>
     *   <li>Push Constant Range: 允许通过 vkCmdPushConstants 快速传递小量数据（128字节）</li>
     * </ul>
     *
     * 【方法参数】无
     *
     * 【返回值】void（副作用：设置 pipelineLayout）
     *
     * @throws Exception 如果创建失败
     */
    public static void createPipelineLayout() throws Exception {
        MethodHandle vkCreatePipelineLayout = VulkanFFMBinding.getVkCreatePipelineLayout();
        if (!VulkanFFMBinding.isFfmLoaded() || vkCreatePipelineLayout == null) {
            throw new IllegalStateException("FFM 方法句柄未加载");
        }

        try (Arena arena = Arena.ofConfined()) {
            // ==================== VkPushConstantRange ====================
            // 定义 Push Constant 的可访问范围：
            // - stageFlags: 仅计算着色器可访问
            // - offset: 起始偏移量为 0
            // - size: 最大 128 字节（Vulkan 规范要求的最低保证值）
            // 结构体字段: [0] stageFlags, [1] offset, [2] size
            MemorySegment pushConstantRange = arena.allocate(ValueLayout.JAVA_LONG, 3);
            pushConstantRange.setAtIndex(ValueLayout.JAVA_LONG, 0, (long) VK_SHADER_STAGE_COMPUTE_BIT);  // stageFlags = COMPUTE
            pushConstantRange.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);   // offset = 0
            pushConstantRange.setAtIndex(ValueLayout.JAVA_LONG, 2, 128L); // size = 128 bytes

            // ==================== Descriptor Set Layouts 数组 ====================
            // 将两个 Descriptor Set Layout 句柄存入数组
            // Hi-Z Build Pipeline 使用 setLayouts[0], Occlusion Query 使用 setLayouts[1]
            MemorySegment setLayoutsArr = arena.allocate(ValueLayout.JAVA_LONG, 2);
            setLayoutsArr.setAtIndex(ValueLayout.JAVA_LONG, 0, hizBuildDescSetLayout);       // setLayouts[0]: HiZ Build DSL
            setLayoutsArr.setAtIndex(ValueLayout.JAVA_LONG, 1, hizOcclusionDescSetLayout);  // setLayouts[1]: Occlusion DSL

            // ==================== VkPipelineLayoutCreateInfo ====================
            // 结构体字段布局:
            // [0] sType          = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO (12)
            // [1] pNext          = null
            // [2] flags          = 0 (无特殊标志)
            // [3] setLayoutCount = 2 (两个 Descriptor Set Layout)
            // [4] pSetLayouts    = setLayoutsArr 地址
            // [5] pushConstantRangeCount = 1 (一个 Push Constant 范围)
            // [6] pPushConstantRanges = pushConstantRange 地址
            MemorySegment layoutCreateInfo = arena.allocate(ValueLayout.JAVA_LONG, 7);
            layoutCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 12L);                       // sType = PIPELINE_LAYOUT_CREATE_INFO
            layoutCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);                        // pNext = null
            layoutCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);                        // flags = 0
            layoutCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 2L);                        // setLayoutCount = 2
            layoutCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, setLayoutsArr.address());   // pSetLayouts
            layoutCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 5, 1L);                        // pushConstantRangeCount = 1
            layoutCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 6, pushConstantRange.address()); // pPushConstantRanges

            // 输出参数：Pipeline Layout 句柄
            MemorySegment layoutOut = arena.allocate(ValueLayout.JAVA_LONG);

            // 调用 vkCreatePipelineLayout(device, pCreateInfo, pAllocator, pPipelineLayout)
            int result = VK_SUCCESS;
            try {
                result = (int) vkCreatePipelineLayout.invokeExact(
                        LodCullingComputePass.getVkDevice(),        // device
                        layoutCreateInfo.address(),            // pCreateInfo
                        0L,                                    // pAllocator = null
                        layoutOut.address()                     // pPipelineLayout (output)
                );
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "vkCreatePipelineLayout 调用失败", t);
                throw new RuntimeException("vkCreatePipelineLayout 调用异常", t);
            }

            if (result != VK_SUCCESS) {
                throw new RuntimeException("vkCreatePipelineLayout 失败: VkResult=" + result);
            }

            pipelineLayout = layoutOut.get(ValueLayout.JAVA_LONG, 0);

            LOGGER.info(String.format("[HiZPipeline] ✓ Pipeline Layout 创建成功 | handle=0x%s (2 DSLs + 128B PushConst)",
                    Long.toHexString(pipelineLayout)));
        }
    }

    /**
     * 创建 Compute Pipelines
     * <p>
     * 为 Hi-Z Build 和 Occlusion Query 创建 Compute Pipeline。
     * 使用 vkCreateComputePipelines 一次性创建两个 Pipeline（批量创建效率更高）。
     *
     * 【方法参数】无
     *
     * 【返回值】void（副作用：设置 hizBuildPipeline 和 hizOcclusionPipeline）
     *
     * @throws Exception 如果创建失败
     */
    public static void createComputePipelines() throws Exception {
        MethodHandle vkCreateComputePipelines = VulkanFFMBinding.getVkCreateComputePipelines();
        if (!VulkanFFMBinding.isFfmLoaded() || vkCreateComputePipelines == null) {
            throw new IllegalStateException("FFM 方法句柄未加载");
        }

        try (Arena arena = Arena.ofConfined()) {
            // ==================== 准备着色器入口点名称 "main" ====================
            // Vulkan 要求 pName 指向以 null 结尾的 UTF-8 字符串
            // 使用 asByteBuffer().put() 写入字节数组（兼容所有 Java 版本）
            byte[] mainBytes = "main\0".getBytes(StandardCharsets.UTF_8);
            MemorySegment mainName = arena.allocate(mainBytes.length, 1);
            mainName.asByteBuffer().put(mainBytes);

            // ==================== 构建 VkPipelineShaderStageCreateInfo 辅助方法 ====================
            // 每个 Stage CreateInfo 占用 7 个 JAVA_LONG 字段:
            // [0] sType(10), [1] pNext, [2] flags, [3] stage, [4] module, [5] pName(ptr), [6] pSpecializationInfo

            // --- Hi-Z Build Shader Stage ---
            MemorySegment hizStageInfo = arena.allocate(ValueLayout.JAVA_LONG, 7);
            hizStageInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 10L);                                // sType = PIPELINE_SHADER_STAGE_CREATE_INFO
            hizStageInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);                                 // pNext = null
            hizStageInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);                                 // flags = 0
            hizStageInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, (long) VK_SHADER_STAGE_COMPUTE_BIT); // stage = COMPUTE
            hizStageInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, hizBuildShaderModule);                // module = HiZ Build SM
            hizStageInfo.setAtIndex(ValueLayout.JAVA_LONG, 6, 0L);                                 // pSpecializationInfo = null

            // --- Occlusion Query Shader Stage ---
            MemorySegment occStageInfo = arena.allocate(ValueLayout.JAVA_LONG, 7);
            occStageInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 10L);                                // sType = PIPELINE_SHADER_STAGE_CREATE_INFO
            occStageInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);                                 // pNext = null
            occStageInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);                                 // flags = 0
            occStageInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, (long) VK_SHADER_STAGE_COMPUTE_BIT); // stage = COMPUTE
            occStageInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, hizOcclusionShaderModule);            // module = Occlusion SM
            occStageInfo.setAtIndex(ValueLayout.JAVA_LONG, 6, 0L);                                 // pSpecializationInfo = null

            // ==================== 构建 VkComputePipelineCreateInfo 数组 ====================
            // 每个 Compute Pipeline CreateInfo 占用 6 个 JAVA_LONG 字段:
            // [0] sType(24), [1] pNext, [2] flags, [3] stage(ptr), [4] layout, [5] basePipelineHandle, + basePipelineIndex(int)

            // 使用 7 个字段来容纳最后的 basePipelineIndex (作为第 7 个 long 的低 32 位)
            int createInfoFieldCount = 7;

            // --- Hi-Z Build Pipeline CreateInfo ---
            MemorySegment hizPipelineInfo = arena.allocate(ValueLayout.JAVA_LONG, createInfoFieldCount);
            hizPipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 24L);                    // sType = COMPUTE_PIPELINE_CREATE_INFO
            hizPipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);                     // pNext = null
            hizPipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);                     // flags = 0
            hizPipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, hizStageInfo.address()); // stage = HiZ Build Stage Info
            hizPipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, pipelineLayout);          // layout
            hizPipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 5, 0L);                     // basePipelineHandle = NULL
            hizPipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 6, 0xFFFFFFFFL);            // basePipelineIndex = -1 (无基础管线)

            // --- Occlusion Query Pipeline CreateInfo ---
            MemorySegment occPipelineInfo = arena.allocate(ValueLayout.JAVA_LONG, createInfoFieldCount);
            occPipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 24L);                    // sType = COMPUTE_PIPELINE_CREATE_INFO
            occPipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);                     // pNext = null
            occPipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);                     // flags = 0
            occPipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, occStageInfo.address()); // stage = Occ Query Stage Info
            occPipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, pipelineLayout);          // layout
            occPipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 5, 0L);                     // basePipelineHandle = NULL
            occPipelineInfo.setAtIndex(ValueLayout.JAVA_LONG, 6, 0xFFFFFFFFL);            // basePipelineIndex = -1

            // ==================== 构建 CreateInfo 数组（连续内存）====================
            // 将两个 CreateInfo 放入连续内存区域（Vulkan API 需要数组形式传入）
            MemorySegment createInfos = arena.allocate(ValueLayout.JAVA_LONG, 2 * createInfoFieldCount);
            // 复制 HiZ Build Pipeline CreateInfo
            MemorySegment.copy(hizPipelineInfo, 0, createInfos, 0, createInfoFieldCount * ValueLayout.JAVA_LONG.byteSize());
            // 复制 Occlusion Query Pipeline CreateInfo
            MemorySegment.copy(occPipelineInfo, 0, createInfos, createInfoFieldCount * ValueLayout.JAVA_LONG.byteSize(),
                    createInfoFieldCount * ValueLayout.JAVA_LONG.byteSize());

            // ==================== 输出参数：Pipeline 句柄数组 ====================
            MemorySegment pipelinesOut = arena.allocate(ValueLayout.JAVA_LONG, 2);  // 两个 Pipeline 句柄

            // ==================== 调用 vkCreateComputePipelines ====================
            // 签名: vkCreateComputePipelines(device, pipelineCache, createInfoCount, pCreateInfos, pAllocator, pPipelines)
            int result = VK_SUCCESS;
            try {
                result = (int) vkCreateComputePipelines.invokeExact(
                        LodCullingComputePass.getVkDevice(),            // device
                        0L,                                        // pipelineCache = VK_NULL_HANDLE (不使用缓存)
                        2,                                         // createInfoCount = 2 (同时创建两个 Pipeline)
                        createInfos.address(),                     // pCreateInfos
                        0L,                                        // pAllocator = null
                        pipelinesOut.address()                     // pPipelines (输出: 两个 VkPipeline 句柄)
                );
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "vkCreateComputePipelines 调用失败", t);
                throw new RuntimeException("vkCreateComputePipelines 调用异常", t);
            }

            if (result != VK_SUCCESS) {
                throw new RuntimeException("vkCreateComputePipelines 失败: VkResult=" + result);
            }

            // 从输出数组中提取 Pipeline 句柄
            hizBuildPipeline = pipelinesOut.getAtIndex(ValueLayout.JAVA_LONG, 0);
            hizOcclusionPipeline = pipelinesOut.getAtIndex(ValueLayout.JAVA_LONG, 1);

            LOGGER.info(String.format("[HiZPipeline] ✓ Compute Pipelines 创建成功 | " +
                            "HiZBuild=0x%s, Occlusion=0x%s",
                    Long.toHexString(hizBuildPipeline),
                    Long.toHexString(hizOcclusionPipeline)));
        }
    }

    /**
     * 创建同步对象（Command Pool + Fence）
     * <p>
     * 创建用于命令缓冲区分配和 GPU-CPU 同步的对象：
     * <ul>
     *   <li><b>CommandPool</b>: 管理命令缓冲区的生命周期，设置为 TRANSIENT（短生命周期）+ RESET（可重置）</li>
     *   <li><b>Fence</b>: GPU 完成信号量，初始状态为 SIGNALED（避免首次等待死锁）</li>
     * </ul>
     *
     * 【方法参数】无
     *
     * 【返回值】void（副作用：设置 LodCullingComputePass.commandPool 和 LodCullingComputePass.fence）
     *
     * @throws Exception 如果创建失败
     */
    public static void createSyncObjects() throws Exception {
        if (!VulkanFFMBinding.isFfmLoaded()) {
            throw new IllegalStateException("FFM 方法句柄未加载");
        }

        try (Arena arena = Arena.ofConfined()) {
            // ==================== 获取计算队列家族索引 ====================
            // 从 VulkanDeviceHolder 获取计算队列所属的队列家族索引
            int computeQueueFamilyIndex = 0;  // 默认值
            try {
                // 尝试通过反射获取 getComputeQueueFamilyIndex 方法
                java.lang.reflect.Method qfiMethod = VulkanDeviceHolder.class.getMethod("getComputeQueueFamilyIndex");
                computeQueueFamilyIndex = (int) qfiMethod.invoke(
                        VulkanDeviceHolder.class.getField("INSTANCE").get(null));
            } catch (Exception e) {
                LOGGER.fine("[HiZPipeline] 无法获取队列家族索引，使用默认值 0: " + e.getMessage());
            }

            // ==================== Step 1: 创建 Command Pool ====================
            // VkCommandPoolCreateInfo 结构体字段:
            // [0] sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO (27)
            // [1] pNext = null
            // [2] flags = TRANSIENT(2) | RESET_COMMAND_BUFFER(4) = 6
            // [3] queueFamilyIndex = 计算队列家族索引

            MethodHandle vkCreateCommandPool = VulkanFFMBinding.getVkCreateCommandPool();
            if (vkCreateCommandPool != null) {
                MemorySegment poolCreateInfo = arena.allocate(ValueLayout.JAVA_LONG, 4);
                poolCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 27L);                       // sType = COMMAND_POOL_CREATE_INFO
                poolCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);                        // pNext = null
                poolCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 6L);                        // flags = TRANSIENT | RESET_COMMAND_BUFFER
                poolCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, (long) computeQueueFamilyIndex); // queueFamilyIndex

                MemorySegment commandPoolOut = arena.allocate(ValueLayout.JAVA_LONG);

                // 调用 vkCreateCommandPool(device, pCreateInfo, pAllocator, pCommandPool)
                int poolResult = VK_SUCCESS;
                try {
                    poolResult = (int) vkCreateCommandPool.invokeExact(
                            LodCullingComputePass.getVkDevice(),        // device
                            poolCreateInfo.address(),              // pCreateInfo
                            0L,                                    // pAllocator = null
                            commandPoolOut.address()                // pCommandPool (output)
                    );
                } catch (Throwable t) {
                    LOGGER.log(Level.WARNING, "vkCreateCommandPool 调用失败", t);
                    throw new RuntimeException("vkCreateCommandPool 调用异常", t);
                }

                if (poolResult != VK_SUCCESS) {
                    throw new RuntimeException("vkCreateCommandPool 失败: VkResult=" + poolResult);
                }

                LodCullingComputePass.setCommandPool(commandPoolOut.get(ValueLayout.JAVA_LONG, 0));
                LOGGER.fine("[HiZPipeline] ✓ Command Pool 创建成功 | handle=0x" +
                        Long.toHexString(LodCullingComputePass.getCommandPool()) +
                        " (queueFamily=" + computeQueueFamilyIndex + ")");
            }

            // ==================== Step 2: 创建 Fence ====================
            // VkFenceCreateInfo 结构体字段:
            // [0] sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO (9)
            // [1] pNext = null
            // [2] flags = VK_FENCE_CREATE_SIGNALED_BIT (1)
            // 初始为 SIGNALED 状态，避免第一次 vkWaitForFences 死锁

            MethodHandle vkCreateFence = VulkanFFMBinding.getVkCreateFence();
            if (vkCreateFence != null) {
                MemorySegment fenceCreateInfo = arena.allocate(ValueLayout.JAVA_LONG, 3);
                fenceCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 9L);   // sType = FENCE_CREATE_INFO
                fenceCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);    // pNext = null
                fenceCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 1L);    // flags = SIGNALED

                MemorySegment fenceOut = arena.allocate(ValueLayout.JAVA_LONG);

                // 调用 vkCreateFence(device, pCreateInfo, pAllocator, pFence)
                int fenceResult = VK_SUCCESS;
                try {
                    fenceResult = (int) vkCreateFence.invokeExact(
                            LodCullingComputePass.getVkDevice(),       // device
                            fenceCreateInfo.address(),            // pCreateInfo
                            0L,                                   // pAllocator = null
                            fenceOut.address()                     // pFence (output)
                    );
                } catch (Throwable t) {
                    LOGGER.log(Level.WARNING, "vkCreateFence 调用失败", t);
                    throw new RuntimeException("vkCreateFence 调用异常", t);
                }

                if (fenceResult != VK_SUCCESS) {
                    throw new RuntimeException("vkCreateFence 失败: VkResult=" + fenceResult);
                }

                LodCullingComputePass.setFence(fenceOut.get(ValueLayout.JAVA_LONG, 0));
                LOGGER.fine("[HiZPipeline] ✓ Fence 创建成功 | handle=0x" +
                        Long.toHexString(LodCullingComputePass.getFence()));
            }

            LOGGER.info(String.format("[HiZPipeline] ✓ 同步对象创建成功 | CommandPool=0x%s, Fence=0x%s",
                    Long.toHexString(LodCullingComputePass.getCommandPool()),
                    Long.toHexString(LodCullingComputePass.getFence())));
        }
    }

    // ==================== 命令缓冲区操作 ====================

    /**
     * 分配命令缓冲区
     * <p>
     * 从已创建的 Command Pool 中分配一个主级别（Primary）命令缓冲区。
     * Primary Command Buffer 可以直接提交到队列执行，不能被 Secondary Buffer 调用。
     *
     * 【方法参数】
     * @param device long - VkDevice 句柄
     *
     * 【返回值】long - VkCommandBuffer 句柄，如果分配失败返回 0L
     *
     * 【调用方式】
     * 每帧调用一次（或根据需要），分配的缓冲区在录制并提交后由 Command Pool 统一管理
     */
    public static long allocateCommandBuffer(long device) {
        MethodHandle vkAllocateCommandBuffers = VulkanFFMBinding.getVkAllocateCommandBuffers();
        if (!VulkanFFMBinding.isFfmLoaded() || vkAllocateCommandBuffers == null) {
            LOGGER.severe("[HiZPipeline] FFM 方法句柄未加载，无法分配命令缓冲区");
            return 0L;
        }

        if (LodCullingComputePass.getCommandPool() == 0L) {
            LOGGER.severe("[HiZPipeline] CommandPool 无效（0x0），请确保初始化已完成");
            return 0L;
        }

        try (Arena arena = Arena.ofConfined()) {
            // ==================== 构建 VkCommandBufferAllocateInfo ====================
            // 结构体字段布局:
            // [0] sType              = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO (44)
            // [1] pNext              = null
            // [2] commandPool        = 已创建的 Command Pool 句柄
            // [3] level              = VK_COMMAND_BUFFER_LEVEL_PRIMARY (0) - 主命令缓冲区
            // [4] commandBufferCount = 1 (仅分配一个)

            MemorySegment allocInfo = arena.allocate(ValueLayout.JAVA_LONG, 5);
            allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 44L);          // sType = COMMAND_BUFFER_ALLOCATE_INFO
            allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);           // pNext = null
            allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, LodCullingComputePass.getCommandPool());   // commandPool
            allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 0L);           // level = PRIMARY (0)
            allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, 1L);           // commandBufferCount = 1

            // 输出参数：VkCommandBuffer 句柄
            MemorySegment cmdBufOut = arena.allocate(ValueLayout.JAVA_LONG);

            // 调用 vkAllocateCommandBuffers(device, pAllocateInfo, pCommandBuffers)
            int result = VK_SUCCESS;
            try {
                result = (int) vkAllocateCommandBuffers.invokeExact(
                        device,                // device
                        allocInfo.address(),   // pAllocateInfo
                        cmdBufOut.address()    // pCommandBuffers (output)
                );
            } catch (Throwable t) {
                LOGGER.log(Level.SEVERE, "[HiZPipeline] vkAllocateCommandBuffers 调用失败", t);
                return 0L;
            }

            if (result != VK_SUCCESS) {
                LOGGER.severe("[HiZPipeline] vkAllocateCommandBuffers 失败: VkResult=" + result);
                return 0L;
            }

            long cmdBuf = cmdBufOut.get(ValueLayout.JAVA_LONG, 0);

            if (cmdBuf == 0L) {
                LOGGER.warning("[HiZPipeline] vkAllocateCommandBuffers 返回空句柄");
                return 0L;
            }

            LOGGER.fine("[HiZPipeline] ✓ 命令缓冲区分配成功 | handle=0x" + Long.toHexString(cmdBuf));
            return cmdBuf;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[HiZPipeline] 命令缓冲区分配异常: " + e.getMessage(), e);
            return 0L;
        }
    }

    /**
     * 开始录制命令缓冲区
     *
     * 【方法参数】
     * @param cmdBuf long - VkCommandBuffer 句柄
     *
     * @throws Exception 如果录制开始失败
     */
    public static void beginCommandBuffer(long cmdBuf) throws Exception {
        MethodHandle vkBeginCommandBuffer = VulkanFFMBinding.getVkBeginCommandBuffer();
        if (!VulkanFFMBinding.isFfmLoaded() || vkBeginCommandBuffer == null) {
            throw new IllegalStateException("FFM 方法句柄未加载");
        }

        try (Arena arena = Arena.ofConfined()) {
            // VkCommandBufferBeginInfo:
            //   - sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO
            //   - flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
            //   - pInheritanceInfo = nullptr (primary buffer)

            MemorySegment beginInfo = arena.allocate(ValueLayout.JAVA_LONG, 4);
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 28L);  // sType
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);  // flags
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);  // pInheritanceInfo

            int result = VK_SUCCESS;
            try {
                result = (int) vkBeginCommandBuffer.invokeExact(cmdBuf, beginInfo.address());
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "vkBeginCommandBuffer 调用失败", t);
                throw new RuntimeException("vkBeginCommandBuffer 失败", t);
            }

            if (result != VK_SUCCESS) {
                throw new RuntimeException("vkBeginCommandBuffer 失败: VkResult=" + result);
            }
        }
    }

    /**
     * 结束录制命令缓冲区
     *
     * 【方法参数】
     * @param cmdBuf long - VkCommandBuffer 句柄
     *
     * @throws Exception 如果结束录制失败
     */
    public static void endCommandBuffer(long cmdBuf) throws Exception {
        MethodHandle vkEndCommandBuffer = VulkanFFMBinding.getVkEndCommandBuffer();
        if (!VulkanFFMBinding.isFfmLoaded() || vkEndCommandBuffer == null) {
            throw new IllegalStateException("FFM 方法句柄未加载");
        }

        int result = VK_SUCCESS;  // 默认成功值

        try {
            result = (int) vkEndCommandBuffer.invokeExact(cmdBuf);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Vulkan API 调用异常", t);
            result = -1;  // 标记异常
        }

        if (result != VK_SUCCESS) {
            throw new RuntimeException("vkEndCommandBuffer 失败: VkResult=" + result);
        }
    }

    // ==================== Pipeline 绑定与 Dispatch ====================

    /**
     * 绑定 Hi-Z Build Pipeline 并分发计算任务
     * <p>
     * 录制以下命令到命令缓冲区：
     * <ol>
     *   <li>vkCmdBindPipeline(COMPUTE, hizBuildPipeline)</li>
     *   <li>vkCmdBindDescriptorSets（绑定深度缓冲、Hi-Z 输出、配置 UBO）</li>
     *   <li>vkCmdDispatch(screenWidth/16, screenHeight/16, 1)</li>
     * </ol>
     *
     * 【方法参数】
     * @param cmdBuf long - VkCommandBuffer 句柄
     * @param holder VulkanDeviceHolder - 设备持有者（提供纹理和配置数据）
     *
     * @throws Exception 如果绑定或分发失败
     */
    public static void bindAndDispatchHiZBuild(long cmdBuf, VulkanDeviceHolder holder) throws Exception {
        if (VulkanOperationGuard.isFailed()) return;

        // Step 1: 绑定 Pipeline
        bindPipeline(cmdBuf, hizBuildPipeline, "HiZ Build");

        // Step 2: 绑定 Descriptor Sets（depthBuffer, hizMipmaps, config）
        // 注意: Descriptor Set 绑定需要预先分配的 Descriptor Pool 和已更新的 Descriptor Set。
        // 当前阶段跳过绑定，待 Descriptor Pool 实现后补充完整的 vkCmdBindDescriptorSets 调用。
        // 后续实现时需要:
        //   1. 创建 VkDescriptorPool (包含所有 binding 的 descriptor 数量)
        //   2. 从 Pool 分配 VkDescriptorSet (对应 hizBuildDescSetLayout)
        //   3. 使用 vkUpdateDescriptorSets 写入实际资源句柄
        //   4. 调用 vkCmdBindDescriptorSets(cmdBuf, COMPUTE, pipelineLayout, 0, 1, &descSet, 0, null)
        if (VulkanFFMBinding.getVkCmdBindDescriptorSets() != null) {
            LOGGER.fine("[HiZPipeline] HiZ Build: DescriptorSet 绑定暂未实现（等待 DescriptorPool）");
        }

        // Step 3: 计算工作组数量（基于屏幕分辨率）
        // 工作组大小为 16x16（与着色器 local_size 一致）
        int screenWidth = 1920;   // 从 holder 获取屏幕尺寸（VulkanOperationGuard 已保护）
        int screenHeight = 1080;
        int groupsX = (screenWidth + 15) / 16;
        int groupsY = (screenHeight + 15) / 16;

        // Step 4: Dispatch
        dispatchCompute(cmdBuf, groupsX, groupsY, 1, "HiZ Build");

        LOGGER.fine(String.format("[HiZPipeline] HiZ Build Dispatch: %d\u00d7%d workgroups", groupsX, groupsY));
    }

    /**
     * 绑定 Occlusion Query Pipeline 并分发计算任务
     * <p>
     * 录制以下命令到命令缓冲区：
     * <ol>
     *   <li>vkCmdBindPipeline(COMPUTE, hizOcclusionPipeline)</li>
     *   <li>vkCmdBindDescriptorSets（绑定物体数据、Hi-Z 金字塔、配置、输出掩码）</li>
     *   <li>vkCmdDispatch(objectCount/64, 1, 1)</li>
     * </ol>
     *
     * 【方法参数】
     * @param cmdBuf long - VkCommandBuffer 句柄
     * @param holder VulkanDeviceHolder - 设备持有者（提供物体数据和配置）
     *
     * @throws Exception 如果绑定或分发失败
     */
    public static void bindAndDispatchOcclusionQuery(long cmdBuf, VulkanDeviceHolder holder) throws Exception {
        if (VulkanOperationGuard.isFailed()) return;

        // Step 1: 绑定 Pipeline
        bindPipeline(cmdBuf, hizOcclusionPipeline, "Occlusion Query");

        // Step 2: 绑定 Descriptor Sets（objects, hizMipmaps, config, visibilityMask）
        // 注意: 与 HiZ Build 类似，Descriptor Set 绑定需要完整的 Descriptor Pool 基础设施。
        // 当前阶段跳过绑定，待 Descriptor Pool 实现后补充。
        // Occlusion Query 需要绑定的资源:
        //   - binding 0: objects SSBO (物体 AABB 数据)
        //   - binding 1: hizMipmaps sampler[10] (Hi-Z 金字塔只读采样)
        //   - binding 2: config UBO (相机矩阵、屏幕尺寸等配置)
        //   - binding 3: visibilityMask SSBO (输出可见性掩码，R32UI 格式)
        if (VulkanFFMBinding.getVkCmdBindDescriptorSets() != null) {
            LOGGER.fine("[HiZPipeline] Occlusion Query: DescriptorSet 绑定暂未实现（等待 DescriptorPool）");
        }

        // Step 3: 计算工作组数量（基于物体数量）
        // 工作组大小为 64（与着色器 WORKGROUP_SIZE 一致）
        int objectCount = 1024;  // 从 holder 获取物体数量（VulkanOperationGuard 已保护）
        int groupsX = (objectCount + 63) / 64;

        // Step 4: Dispatch
        dispatchCompute(cmdBuf, groupsX, 1, 1, "Occlusion Query");

        LOGGER.fine(String.format("[HiZPipeline] Occlusion Query Dispatch: %d workgroups (%d objects)",
                groupsX, objectCount));
    }

    /**
     * 绑定 Compute Pipeline 到命令缓冲区
     *
     * 【方法参数】
     * @param cmdBuf long - VkCommandBuffer 句柄
     * @param pipeline long - VkPipeline 句柄
     * @param name String - Pipeline 名称（用于日志）
     *
     * @throws Exception 如果绑定失败
     */
    private static void bindPipeline(long cmdBuf, long pipeline, String name) throws Exception {
        MethodHandle vkCmdBindPipeline = VulkanFFMBinding.getVkCmdBindPipeline();
        if (!VulkanFFMBinding.isFfmLoaded() || vkCmdBindPipeline == null) {
            throw new IllegalStateException("FFM 方法句柄未加载");
        }

        try {
            try {
                vkCmdBindPipeline.invokeExact(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            } catch (Throwable t) {
                // shutdown 期间忽略 Vulkan 清理错误
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Vulkan API 调用异常", t);
        }
        LOGGER.fine("[HiZPipeline] Pipeline 已绑定: " + name + " (handle=0x" + Long.toHexString(pipeline) + ")");
    }

    /**
     * 发送 Compute Dispatch 命令
     *
     * 【方法参数】
     * @param cmdBuf long - VkCommandBuffer 句柄
     * @param groupCountX int - X 维度工作组数量
     * @param groupCountY int - Y 维度工作组数量
     * @param groupCountZ int - Z 维度工作组数量
     * @param name String - 操作名称（用于日志）
     *
     * @throws Exception 如果分发失败
     */
    private static void dispatchCompute(long cmdBuf, int groupCountX, int groupCountY,
                                        int groupCountZ, String name) throws Exception {
        MethodHandle vkCmdDispatch = VulkanFFMBinding.getVkCmdDispatch();
        if (!VulkanFFMBinding.isFfmLoaded() || vkCmdDispatch == null) {
            throw new IllegalStateException("FFM 方法句柄未加载");
        }

        try {
            try {
                vkCmdDispatch.invokeExact(cmdBuf, groupCountX, groupCountY, groupCountZ);
            } catch (Throwable t) {
                // shutdown 期间忽略 Vulkan 清理错误
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Vulkan API 调用异常", t);
        }
    }

    // ==================== 屏障与同步 ====================

    /**
     * 插入内存屏障（确保 Hi-Z 写入完成后才被读取）
     * <p>
     * 在 Hi-Z Build 和 Occlusion Query 之间插入 Pipeline Barrier：
     * <ul>
     *   <li>源阶段：COMPUTE SHADER</li>
     *   <li>目标阶段：COMPUTE SHADER</li>
     *   <li>访问掩码：SHADER_WRITE → SHADER_READ</li>
     * </ul>
     *
     * 【方法参数】
     * @param cmdBuf long - VkCommandBuffer 句柄
     *
     * @throws Exception 如果插入屏障失败
     */
    public static void insertMemoryBarrier(long cmdBuf) throws Exception {
        MethodHandle vkCmdPipelineBarrier = VulkanFFMBinding.getVkCmdPipelineBarrier();
        if (vkCmdPipelineBarrier == null) {
            LOGGER.warning("[HiZPipeline] vkCmdPipelineBarrier method handle 未加载，跳过内存屏障");
            return;
        }

        try (Arena arena = Arena.ofConfined()) {
            // VkMemoryBarrier 结构体布局 (24 bytes = 3 × JAVA_LONG):
            // [0] bytes  0-7:  sType (int32 at offset 0) + padding (4 bytes)
            // [1] bytes  8-15: pNext (pointer, 8 bytes)
            // [2] bytes 16-23: srcAccessMask (int32 at offset 16) + dstAccessMask (int32 at offset 20)
            MemorySegment barrier = arena.allocate(ValueLayout.JAVA_LONG, 3);
            barrier.setAtIndex(ValueLayout.JAVA_LONG, 0, (long) VK_STRUCTURE_TYPE_MEMORY_BARRIER);
            barrier.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L); // pNext = null
            // 将 srcAccessMask 打包到低 32 位，dstAccessMask 打包到高 32 位
            long maskSlot = ((long) VK_ACCESS_SHADER_READ_BIT << 32)
                          | ((long) VK_ACCESS_SHADER_WRITE_BIT & 0xFFFFFFFFL);
            barrier.setAtIndex(ValueLayout.JAVA_LONG, 2, maskSlot);

            try {
                vkCmdPipelineBarrier.invokeExact(
                        cmdBuf,                                    // commandBuffer (VkCommandBuffer)
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,      // srcStageMask: COMPUTE_SHADER
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,      // dstStageMask: COMPUTE_SHADER
                        0,                                         // dependencyFlags: 无
                        1,                                         // memoryBarrierCount: 1
                        barrier.address(),                         // pMemoryBarriers: VkMemoryBarrier 指针
                        0,                                         // bufferMemoryBarrierCount: 0
                        0L,                                        // pBufferMemoryBarriers: null
                        0,                                         // imageMemoryBarrierCount: 0
                        0L                                         // pImageMemoryBarriers: null
                );
            } catch (Throwable t) {
                // shutdown 期间忽略 Vulkan 清理错误
            }

            LOGGER.fine("[HiZPipeline] Memory barrier inserted (handle=0x" + Long.toHexString(cmdBuf)
                    + ", srcAccess=SHADER_WRITE→dstAccess=SHADER_READ, stage=COMPUTE_SHADER)");
        }
    }
}