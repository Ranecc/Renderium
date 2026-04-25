package com.renderium.config;

import com.renderium.core.RenderiumMode;
import com.renderium.framegen.FrameGenMode;
import com.renderium.superres.SuperResolutionAdapter;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Renderium v5 配置系统
 *
 * <p>管理所有渲染优化相关的配置项，包括：
 * <ul>
 *   <li>超分辨率技术（DLSS/XeSS/FSR）</li>
 *   <li>帧生成设置</li>
 *   <li>NVIDIA Reflex 低延迟</li>
 *   <li>v5 新增：Blaze3D 优化配置（仅 Aggressive 模式生效）</li>
 * </ul>
 *
 * <h2>配置文件格式</h2>
 * <p>使用 Java Properties 格式（renderium.properties），
 * 所有配置项均支持运行时热重载。
 *
 * @author Renderium Team
 * @since 1.0.0
 * @version 5.0
 */
public final class RenderiumConfig {

    /** 配置文件名 */
    private static final String CONFIG_FILE = "renderium.properties";

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(RenderiumConfig.class.getName());

    /** 原始配置属性（用于动态读取任意配置项） */
    private Properties rawProperties = new Properties();

    // ==================== 超分辨率配置 ====================

    /** 超分辨率技术类型 */
    private SuperResolutionAdapter.Technology technology = SuperResolutionAdapter.Technology.DLSS;

    /** 超分辨率质量等级 */
    private SuperResolutionAdapter.Quality quality = SuperResolutionAdapter.Quality.BALANCED;

    // ==================== 帧生成配置 ====================

    /** 是否启用帧生成 */
    private boolean frameGenerationEnabled = false;

    /** 帧生成模式 */
    private FrameGenMode frameGenMode = FrameGenMode.FIXED_2X;

    // ==================== Reflex 配置 ====================

    /** 是否启用 NVIDIA Reflex */
    private boolean reflexEnabled = false;

    /** Reflex 模式 */
    private ReflexMode reflexMode = ReflexMode.LOW_LATENCY;

    // ==================== 通用配置 ====================

    /** Streamline SDK 路径（可选，为空则使用默认路径） */
    private String streamlineSdkPath;

    /** 是否启用 Renderium 模组 */
    private boolean enabled = true;

    /** 是否启用调试模式 */
    private boolean debugMode = false;

    /** 锐化强度 (0.0 - 1.0) */
    private float sharpening = 0.0f;
    
    /** 是否启用超分辨率 */
    private boolean superResolutionEnabled = false;

    /** 是否启用动态分辨率缩放 */
    private boolean dynamicResolution = false;

    // ==================== v5 Blaze3D 优化配置 (仅 Aggressive 模式生效) ====================

    /** 是否启用帧图优化（Frame Graph Optimization） */
    private boolean frameGraphOptimizationEnabled = true;

    /** 是否启用 Vulkan 命令缓冲区优化 */
    private boolean vulkanCommandOptimizationEnabled = true;

    /** 是否启用内存优化（堆外存储） */
    private boolean memoryOptimizationEnabled = true;

    /** 是否启用着色器管线优化 */
    private boolean shaderPipelineOptimizationEnabled = true;

    // ==================== v5 详细配置对象 ====================

    /** 帧图优化详细配置 */
    private FrameGraphConfig frameGraphConfig = new FrameGraphConfig();

    /** Vulkan 命令优化详细配置 */
    private VulkanCommandConfig vulkanCommandConfig = new VulkanCommandConfig();

    /** 内存优化详细配置 */
    private MemoryConfig memoryConfig = new MemoryConfig();

    /** 着色器管线优化详细配置 */
    private ShaderPipelineConfig shaderPipelineConfig = new ShaderPipelineConfig();

    // ==================== Phase 7: 拦截层配置 (v5.1 新增) ====================

    /**
     * 拦截层总配置（v5.1 新增）
     * <p>
     * 控制 PreBlaze3DInterceptor 和 PostBlaze3DInterceptor 的完整行为，
     * 包括前拦截层的模组检测/LOD/剔除注入，以及后拦截层的帧捕获/超分辨率/帧生成等。
     *
     * @see InterceptionConfig
     * @since 5.1.0
     */
    private InterceptionConfig interceptionConfig = new InterceptionConfig();

    // ==================== 脏标记与变更追踪 (v5.2 新增) ====================

    /**
     * 脏标记：是否有未保存的修改
     * <p>
     * 使用 volatile 保证多线程间的可见性。
     * 当任何配置项通过 setter 方法修改时，此标记会被设置为 true。
     *
     * @see #markDirty()
     * @see #markSaved()
     * @see #isDirty()
     * @since 5.2.0
     */
    private volatile boolean dirty = false;

    /**
     * 变更监听器列表（volatile snapshot 模式）
     * <p>
     * 使用数组而非 List，配合 volatile 实现无锁读取。
     * 写入时创建新数组副本，保证读线程始终看到一致快照。
     * <p>
     * 性能特征：
     * <ul>
     *   <li>读取（notifyListeners）：O(n) 遍历，无锁</li>
     *   <li>写入（add/remove）：O(n) 复制数组，短暂同步</li>
     * </ul>
     *
     * @see #addChangeListener(ConfigChangeListener)
     * @see #removeChangeListener(ConfigChangeListener)
     * @see #notifyListeners(Identifier)
     * @since 5.2.0
     */
    private volatile ConfigChangeListener[] listeners = new ConfigChangeListener[0];

    // ==================== 后处理效果配置 ====================

    /** 后处理效果总开关 */
    private boolean effectsEnabled = true;

    /** 后处理效果详细配置 */
    private EffectsConfig effectsConfig = new EffectsConfig();

    // ==================== 算法加速路径配置 (v5.4 新增) ====================

    /**
     * 算法加速路径总配置（v5.4 新增）
     * <p>
     * 控制 Java/C++ 双路径的调度策略，包括：
     * <ul>
     *   <li>GPU 占用率阈值（决定何时使用 Native 路径）</li>
     *   <li>强制模式（调试/测试用）</li>
     *   <li>BFS/LOD/Kahan 各算法的独立开关</li>
     * </ul>
     *
     * @see AlgorithmConfig
     * @since 5.4.0
     */
    private AlgorithmConfig algorithmConfig = new AlgorithmConfig();

    // ==================== 运行模式配置 ====================

    /** 当前运行模式 */
    private RenderiumMode mode = RenderiumMode.COMPATIBILITY;

    // ==================== 剔除配置 ====================

    /** 是否启用相邻面剔除 */
    private boolean neighborFaceCullingEnabled = true;

    /** 是否启用背面剔除 */
    private boolean backfaceCullingEnabled = true;

    /** 是否启用视锥体剔除 */
    private boolean frustumCullingEnabled = true;

    /** 是否启用遮挡剔除 */
    private boolean occlusionCullingEnabled = true;

    // ==================== 批量渲染配置 ====================

    /** 是否启用批量渲染 */
    private boolean batchingEnabled = true;

    /** 是否启用实例化渲染 */
    private boolean instancingEnabled = true;

    // ==================== 调试/显示配置 ====================

    /** 是否绘制实体轮廓 */
    private boolean drawOutlineEnabled = true;

    /** 是否启用调试覆盖层 */
    private boolean debugOverlayEnabled = false;

    /** 是否启用垂直同步 */
    private boolean vsyncEnabled = true;

    // ==================== 图形效果配置 ====================

    /** 是否启用雾效 */
    private boolean fogEnabled = true;

    /** 是否启用云彩 */
    private boolean cloudsEnabled = true;

    /** 是否启用暗角效果 */
    private boolean vignetteEnabled = true;

    /** 是否启用后处理 */
    private boolean postProcessingEnabled = true;

    /** 是否启用阴影 */
    private boolean shadowEnabled = true;

    // ==================== Quality 扩展配置 (v5.3 新增 - 视频选项系统) ====================

    /**
     * 是否启用隐藏流体剔除（Renderium 扩展）
     * <p>
     * 启用后不会渲染被不透明方块完全包围的流体面（水/岩浆），
     * 显著减少水下场景的过度绘制和 GPU 开销。
     *
     * <p><b>性能影响：</b>
     * <ul>
     *   <li>启用时：水下场景 GPU 开销降低 30-50%</li>
     *   <li>禁用时：渲染所有流体面，包括不可见的</li>
     * </ul>
     *
     * <p><b>兼容性：</b>需要 REQUIRES_RENDERER_RELOAD 标志
     *
     * @see RendererVideoOptionsRegistrar#buildHiddenFluidCullingOption(RendererConfigBuilder)
     * @since 5.3.0
     */
    private boolean hiddenFluidCulling = false;

    /**
     * 是否启用改进流体塑形（Renderium 扩展）
     * <p>
     * 启用后使用改进的几何算法渲染流体的表面形状，
     * 使水面/岩浆面更加平滑自然，减少锯齿状边缘。
     *
     * <p><b>视觉效果：</b>
     * <ul>
     *   <li>启用时：流体表面平滑，斜面过渡自然</li>
     *   <li>禁用时：使用 MC 默认的阶梯状流体几何</li>
     * </ul>
     *
     * <p><b>兼容性：</b>需要 REQUIRES_RENDERER_RELOAD 标志
     *
     * @see RendererVideoOptionsRegistrar#buildImprovedFluidShapingOption(RendererConfigBuilder)
     * @since 5.3.0
     */
    private boolean improvedFluidShaping = false;

    // ==================== 单例模式 (v5.3 新增) ====================

    /**
     * 全局单例实例（volatile 保证多线程可见性）
     *
     * @since 5.3.0
     */
    private static volatile RenderiumConfig instance;

    /**
     * 热路径配置快照（volatile 保证无锁读取）
     *
     * <p>渲染线程通过此快照访问配置，避免直接访问可变状态。
     * <p>
     * <b>更新策略：</b>
     * <ul>
     *   <li>冷路径修改配置后调用 {@link #commitSnapshot()} 更新</li>
     *   <li>热路径通过 {@link #getSnapshot()} 读取，无锁 volatile 读</li>
     * </ul>
     *
     * @see RenderiumConfigSnapshot
     * @since 5.3.0
     */
    private static volatile RenderiumConfigSnapshot snapshot;

    /**
     * 获取全局配置单例
     * <p>
     * 采用双重检查锁定（Double-Checked Locking）模式，
     * 保证线程安全的同时避免不必要的同步开销。
     *
     * <h4>使用示例</h4>
     * <pre>{@code
     * // 在选项绑定中使用
     * .setBinding(
     *     value -> RenderiumConfig.getInstance().setHiddenFluidCulling(value),
     *     () -> RenderiumConfig.getInstance().isHiddenFluidCulling()
     * )
     * }</pre>
     *
     * @return 全局唯一的 RenderiumConfig 实例（非 null）
     * @since 5.3.0
     */
    public static RenderiumConfig getInstance() {
        if (instance == null) {
            synchronized (RenderiumConfig.class) {
                if (instance == null) {
                    instance = new RenderiumConfig();
                }
            }
        }
        return instance;
    }

    /**
     * 重置单例实例（仅用于测试）
     *
     * @param newInstance 新的配置实例（可为 null）
     * @since 5.3.0
     */
    static void setInstance(RenderiumConfig newInstance) {
        synchronized (RenderiumConfig.class) {
            instance = newInstance;
        }
    }

    // ==================== Phase 2: 狂暴模式优化配置 (仅 Aggressive 模式生效) ====================

    /**
     * 是否启用 VMA（Vulkan Memory Allocator）激进优化 🚀
     *
     * <p><b>使用场景：</b>当启用狂暴模式时，自动管理显存分配，防止 OOM。
     * <br><b>默认值：</b>false（关闭）
     * <br><b>性能影响：</b>
     * <ul>
     *   <li>启用时：实时监控显存，自动清理非关键资源</li>
     *   <li>禁用时：依赖系统默认的显存管理策略</li>
     * </ul>
     *
     * @see #vmaConfig
     */
    private boolean vmaEnhancementEnabled = false;

    /**
     * VMA 详细配置对象 💰
     *
     * <p>控制显存预算、警告阈值、碎片整理策略等。
     * 仅在 {@link #vmaEnhancementEnabled} 为 true 时生效。
     *
     * @see VmaConfig
     */
    private VmaConfig vmaConfig = new VmaConfig();

    /**
     * 是否启用激进 MC 优化（顶点压缩、批量合并、异步上传）🚀
     *
     * <p><b>使用场景：</b>将多个 Chunk 的 Draw Call 从 1000+ 合并到 &lt;10，
     * 显著减少 CPU 渲染开销。
     * <br><b>默认值：</b>false（关闭）
     * <br><b>性能影响：</b>
     * <ul>
     *   <li>启用时：Draw Calls 减少 20-50x，CPU 渲染耗时降低 4-6x</li>
     *   <li>禁用时：逐 Chunk 绘制，Draw Calls 2000-5000</li>
     * </ul>
     *
     * @see AggressiveConfig
     */
    private boolean aggressiveOptimizationEnabled = false;

    /**
     * 激进优化详细配置对象 🎯
     *
     * <p>控制批量大小、顶点压缩率、异步上传策略等。
     * 仅在 {@link #aggressiveOptimizationEnabled} 为 true 时生效。
     *
     * @see AggressiveConfig
     */
    private AggressiveConfig aggressiveConfig = new AggressiveConfig();

    /**
     * 是否启用现代 Render 架构（Hi-Z、Bindless、ECS、GPU 驱动剔除）🚀
     *
     * <p><b>使用场景：</b>启用高级渲染技术以提升大规模场景的渲染效率。
     * <br><b>默认值：</b>false（关闭）
     * <br><b>性能影响：</b>
     * <ul>
     *   <li>Hi-Z：遮挡剔除效率提升，GPU 利用率提高 +30%</li>
     *   <li>Bindless：减少 descriptor binding 开销，支持更多纹理</li>
     *   <li>ECS：数据驱动架构，提升 CPU 缓存命中率</li>
     * </ul>
     *
     * @see ModernConfig
     */
    private boolean modernRenderArchitectureEnabled = false;

    /**
     * 现代架构详细配置对象 ⚙️
     *
     * <p>控制 Hi-Z Mipmap 级别、Bindless 纹理池大小、ECS 组件配置等。
     * 仅在 {@link #modernRenderArchitectureEnabled} 为 true 时生效。
     *
     * @see ModernConfig
     */
    private ModernConfig modernConfig = new ModernConfig();

    /**
     * 是否启用 GPU 变换与合并优化 🚀
     *
     * <p><b>使用场景：</b>将顶点变换从 CPU 转移到 GPU，通过 Compute Shader 批量处理，
     * 减少 CPU 瓶颈并提高顶点处理吞吐量。
     * <br><b>默认值：</b>false（关闭）
     * <br><b>性能影响：</b>
     * <ul>
     *   <li>启用时：CPU 顶点处理负载降低 60-80%，支持更高顶点数</li>
     *   <li>禁用时：CPU 执行所有顶点变换</li>
     * </ul>
     *
     * @see TransformConfig
     */
    private boolean gpuTransformMergingEnabled = false;

    /**
     * GPU 变换与合并详细配置对象 ⚙️
     *
     * <p>控制 Compute Shader 批次大小、变换矩阵格式、实例化策略等。
     * 仅在 {@link #gpuTransformMergingEnabled} 为 true 时生效。
     *
     * @see TransformConfig
     */
    private TransformConfig transformConfig = new TransformConfig();

    /**
     * Reflex 模式枚举
     */
    public enum ReflexMode {
        OFF,
        LOW_LATENCY,
        LOW_LATENCY_BOOST
    }

    /**
     * 默认构造函数 - 使用默认配置值
     */
    public RenderiumConfig() {}

    /**
     * 验证配置值的合法性
     *
     * @return 验证结果
     */
    public ValidationResult validate() {
        StringBuilder errors = new StringBuilder();
        StringBuilder warnings = new StringBuilder();

        // 验证 FrameGraphConfig
        if (frameGraphConfig.getMaxParallelPasses() <= 0) {
            errors.append("maxParallelPasses must be > 0; ");
        } else if (frameGraphConfig.getMaxParallelPasses() > 16) {
            warnings.append("maxParallelPasses > 16 may cause performance issues; ");
        }
        if (frameGraphConfig.getResourceReuseStrategy() < 0 || frameGraphConfig.getResourceReuseStrategy() > 2) {
            errors.append("resourceReuseStrategy must be 0, 1, or 2; ");
        }

        // 验证 VulkanCommandConfig
        if (vulkanCommandConfig.getMaxCommandsPerBatch() <= 0) {
            errors.append("maxCommandsPerBatch must be > 0; ");
        } else if (vulkanCommandConfig.getMaxCommandsPerBatch() > 256) {
            warnings.append("maxCommandsPerBatch > 256 may cause driver timeouts; ");
        }

        // 验证 MemoryConfig
        if (memoryConfig.getInitialPoolSizeMB() <= 0) {
            errors.append("initialPoolSizeMB must be > 0; ");
        }
        if (memoryConfig.getMaxPoolSizeMB() <= memoryConfig.getInitialPoolSizeMB()) {
            errors.append("maxPoolSizeMB must be > initialPoolSizeMB; ");
        }
        if (memoryConfig.getGcPressureThreshold() <= 0.0f || memoryConfig.getGcPressureThreshold() > 1.0f) {
            errors.append("gcPressureThreshold must be in range (0.0, 1.0]; ");
        }

        // 验证 ShaderPipelineConfig
        if (shaderPipelineConfig.getMaxPipelineCacheEntries() <= 0) {
            errors.append("maxPipelineCacheEntries must be > 0; ");
        } else if (shaderPipelineConfig.getMaxPipelineCacheEntries() > 4096) {
            warnings.append("maxPipelineCacheEntries > 4096 may use excessive memory; ");
        }
        if (shaderPipelineConfig.getParallelCompileThreads() < 0) {
            errors.append("parallelCompileThreads must be >= 0; ");
        }

        // 验证 VmaConfig
        if (vmaConfig.getMemoryBudgetBytes() <= 0) {
            errors.append("memoryBudgetBytes must be > 0; ");
        }
        if (vmaConfig.getHighWaterMark() <= 0.0f || vmaConfig.getHighWaterMark() >= 1.0f) {
            errors.append("highWaterMark must be in range (0.0, 1.0); ");
        }
        if (vmaConfig.getCriticalMark() <= vmaConfig.getHighWaterMark() || vmaConfig.getCriticalMark() > 1.0f) {
            errors.append("criticalMark must be > highWaterMark and <= 1.0; ");
        }

        // 验证 AggressiveConfig
        if (aggressiveConfig.getMaxChunksPerBatch() <= 0) {
            errors.append("maxChunksPerBatch must be > 0; ");
        } else if (aggressiveConfig.getMaxChunksPerBatch() > 1024) {
            warnings.append("maxChunksPerBatch > 1024 may cause batch processing delays; ");
        }
        if (aggressiveConfig.getAsyncUploadThresholdKB() <= 0) {
            errors.append("asyncUploadThresholdKB must be > 0; ");
        }
        if (aggressiveConfig.getMaxUploadsPerFrame() <= 0) {
            errors.append("maxUploadsPerFrame must be > 0; ");
        } else if (aggressiveConfig.getMaxUploadsPerFrame() > 16) {
            warnings.append("maxUploadsPerFrame > 16 may impact frame rate; ");
        }

        // 验证 ModernConfig
        if (modernConfig.getHiZBufferSize() < 256 || modernConfig.getHiZBufferSize() > 4096) {
            errors.append("hiZBufferSize must be in range [256, 4096]; ");
        }
        if (modernConfig.getBindlessMaxTextures() <= 0) {
            errors.append("bindlessMaxTextures must be > 0; ");
        } else if (modernConfig.getBindlessMaxTextures() > 8192) {
            warnings.append("bindlessMaxTextures > 8192 may exceed GPU limits; ");
        }
        if (modernConfig.getFrustumCullWorkgroupSize() <= 0) {
            errors.append("frustumCullWorkgroupSize must be > 0; ");
        }
        if (modernConfig.getHizOcclusionWorkgroupSize() <= 0) {
            errors.append("hizOcclusionWorkgroupSize must be > 0; ");
        }

        // 验证 TransformConfig
        if (transformConfig.getMaxChunks() <= 0) {
            errors.append("maxChunks must be > 0; ");
        } else if (transformConfig.getMaxChunks() > 131072) {
            warnings.append("maxChunks > 131072 may exceed buffer limits; ");
        }
        if (transformConfig.getGlobalVertexBufferSizeMB() <= 0) {
            errors.append("globalVertexBufferSizeMB must be > 0; ");
        }
        if (transformConfig.getInstanceTransformBufferSizeMB() <= 0) {
            errors.append("instanceTransformBufferSizeMB must be > 0; ");
        }
        if (transformConfig.getStaticPromoteFrames() <= 0) {
            errors.append("staticPromoteFrames must be > 0; ");
        }

        // 验证 EffectsConfig
        if (effectsConfig.getBloomIntensity() < 0.0f || effectsConfig.getBloomIntensity() > 2.0f) {
            errors.append("bloomIntensity must be in range [0.0, 2.0]; ");
        }
        if (effectsConfig.getBloomThreshold() < 0.0f || effectsConfig.getBloomThreshold() > 1.0f) {
            errors.append("bloomThreshold must be in range [0.0, 1.0]; ");
        }
        if (effectsConfig.getBloomRadius() < 1.0f || effectsConfig.getBloomRadius() > 16.0f) {
            errors.append("bloomRadius must be in range [1.0, 16.0]; ");
        }
        if (effectsConfig.getMotionBlurSampleCount() < 2 || effectsConfig.getMotionBlurSampleCount() > 32) {
            errors.append("motionBlurSampleCount must be in range [2, 32]; ");
        }

        // 验证 InterceptionConfig (v5.1 新增)
        ValidationResult interceptionResult = interceptionConfig.validate();
        if (interceptionResult.hasErrors()) {
            errors.append("interception config: ").append(interceptionResult.message).append("; ");
        } else if (interceptionResult.hasWarnings()) {
            warnings.append("interception config: ").append(interceptionResult.message).append("; ");
        }

        // 构建验证结果
        if (errors.length() > 0) {
            return ValidationResult.error(errors.toString());
        }
        if (warnings.length() > 0) {
            return ValidationResult.warning(warnings.toString());
        }
        return ValidationResult.ok();
    }

    /**
     * 从文件加载配置
     *
     * @param configDir 配置目录路径
     * @return 加载的配置实例（如果文件不存在则使用默认值）
     */
    public static RenderiumConfig load(Path configDir) {
        RenderiumConfig config = new RenderiumConfig();
        Path configFile = configDir.resolve(CONFIG_FILE);

        if (!Files.isRegularFile(configFile)) {
            return config; // 使用默认值
        }

        try (Reader reader = Files.newBufferedReader(configFile)) {
            Properties props = new Properties();
            props.load(reader);

            // 加载超分辨率配置
            config.technology = parseEnum(
                props.getProperty("superResolution.technology", "DLSS"),
                SuperResolutionAdapter.Technology.class,
                SuperResolutionAdapter.Technology.DLSS
            );
            config.quality = parseEnum(
                props.getProperty("superResolution.quality", "BALANCED"),
                SuperResolutionAdapter.Quality.class,
                SuperResolutionAdapter.Quality.BALANCED
            );

            // 加载帧生成配置
            config.frameGenerationEnabled = Boolean.parseBoolean(
                props.getProperty("frameGeneration.enabled", "false")
            );
            config.frameGenMode = parseEnum(
                props.getProperty("frameGeneration.mode", "FIXED_2X"),
                FrameGenMode.class,
                FrameGenMode.FIXED_2X
            );

            // 加载 Reflex 配置
            config.reflexEnabled = Boolean.parseBoolean(
                props.getProperty("reflex.enabled", "false")
            );
            config.reflexMode = parseEnum(
                props.getProperty("reflex.mode", "LOW_LATENCY"),
                ReflexMode.class,
                ReflexMode.LOW_LATENCY
            );

            // 加载通用配置
            config.sharpening = Float.parseFloat(
                props.getProperty("sharpening", "0.0")
            );
            config.dynamicResolution = Boolean.parseBoolean(
                props.getProperty("dynamicResolution", "false")
            );

            // ========== v5: 加载 Blaze3D 优化配置 ==========

            // 加载布尔开关
            config.frameGraphOptimizationEnabled = Boolean.parseBoolean(
                props.getProperty("blaze3d.frameGraph.enabled", "true")
            );
            config.vulkanCommandOptimizationEnabled = Boolean.parseBoolean(
                props.getProperty("blaze3d.vulkanCommand.enabled", "true")
            );
            config.memoryOptimizationEnabled = Boolean.parseBoolean(
                props.getProperty("blaze3d.memory.enabled", "true")
            );
            config.shaderPipelineOptimizationEnabled = Boolean.parseBoolean(
                props.getProperty("blaze3d.shaderPipeline.enabled", "true")
            );

            // 加载帧图详细配置
            config.frameGraphConfig = FrameGraphConfig.fromProperties(props);

            // 加载 Vulkan 命令详细配置
            config.vulkanCommandConfig = VulkanCommandConfig.fromProperties(props);

            // 加载内存优化详细配置
            config.memoryConfig = MemoryConfig.fromProperties(props);

            // 加载着色器管线详细配置
            config.shaderPipelineConfig = ShaderPipelineConfig.fromProperties(props);

            // ========== Phase 2: 加载 VMA 激进优化配置 ==========
            config.vmaEnhancementEnabled = Boolean.parseBoolean(
                props.getProperty("blaze3d.vma.enabled", "false")
            );
            config.vmaConfig = VmaConfig.fromProperties(props);

            // ========== Phase 2: 加载激进优化配置 ==========
            config.aggressiveOptimizationEnabled = Boolean.parseBoolean(
                props.getProperty("blaze3d.aggressive.enabled", "false")
            );
            config.aggressiveConfig = AggressiveConfig.fromProperties(props);

            // ========== Phase 2: 加载现代架构配置 ==========
            config.modernRenderArchitectureEnabled = Boolean.parseBoolean(
                props.getProperty("blaze3d.modern.enabled", "false")
            );
            config.modernConfig = ModernConfig.fromProperties(props);

            // ========== Phase 2: 加载变换合并配置 ==========
            config.gpuTransformMergingEnabled = Boolean.parseBoolean(
                props.getProperty("blaze3d.transform.enabled", "false")
            );
            config.transformConfig = TransformConfig.fromProperties(props);

            // ========== 加载后处理效果配置 ==========
            config.effectsEnabled = Boolean.parseBoolean(
                props.getProperty("effects.enabled", "true")
            );
            config.effectsConfig = EffectsConfig.fromProperties(props);

            // ========== Phase 7: 加载拦截层配置 (v5.1 新增) ==========
            config.interceptionConfig = InterceptionConfig.fromProperties(props);

            // ========== Phase 8: 加载算法加速路径配置 (v5.4 新增) ==========
            config.algorithmConfig = AlgorithmConfig.fromProperties(props);

            // 保存原始配置属性（用于动态读取）
            config.rawProperties = props;

        } catch (IOException e) {
            LOGGER.warning("Failed to load config from " + configFile +
                           ", using defaults: " + e.getMessage());
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid config value in " + configFile +
                           ", using defaults: " + e.getMessage());
        }

        // 验证配置
        ValidationResult result = config.validate();
        if (result.level == ValidationResult.Level.ERROR) {
            LOGGER.severe("Config validation failed: " + result.message);
        } else if (result.level == ValidationResult.Level.WARNING) {
            LOGGER.warning("Config validation warnings: " + result.message);
        }

        return config;
    }

    /**
     * 保存配置到文件
     *
     * @param configDir 配置目录路径
     */
    public void save(Path configDir) {
        Path configFile = configDir.resolve(CONFIG_FILE);

        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            return; // 无法创建目录则放弃保存
        }

        Properties props = new Properties();

        // 保存超分辨率配置
        props.setProperty("superResolution.technology", technology.name());
        props.setProperty("superResolution.quality", quality.name());

        // 保存帧生成配置
        props.setProperty("frameGeneration.enabled", String.valueOf(frameGenerationEnabled));
        props.setProperty("frameGeneration.mode", frameGenMode.name());

        // 保存 Reflex 配置
        props.setProperty("reflex.enabled", String.valueOf(reflexEnabled));
        props.setProperty("reflex.mode", reflexMode.name());

        // 保存通用配置
        props.setProperty("sharpening", String.valueOf(sharpening));
        props.setProperty("dynamicResolution", String.valueOf(dynamicResolution));

        // ========== v5: 保存 Blaze3D 优化配置 ==========

        // 保存布尔开关
        props.setProperty("blaze3d.frameGraph.enabled", String.valueOf(frameGraphOptimizationEnabled));
        props.setProperty("blaze3d.vulkanCommand.enabled", String.valueOf(vulkanCommandOptimizationEnabled));
        props.setProperty("blaze3d.memory.enabled", String.valueOf(memoryOptimizationEnabled));
        props.setProperty("blaze3d.shaderPipeline.enabled", String.valueOf(shaderPipelineOptimizationEnabled));

        // 保存详细配置
        frameGraphConfig.toProperties(props);
        vulkanCommandConfig.toProperties(props);
        memoryConfig.toProperties(props);
        shaderPipelineConfig.toProperties(props);

        // ========== Phase 2: 保存 VMA 激进优化配置 ==========
        props.setProperty("blaze3d.vma.enabled", String.valueOf(vmaEnhancementEnabled));
        vmaConfig.toProperties(props);

        // ========== Phase 2: 保存激进优化配置 ==========
        props.setProperty("blaze3d.aggressive.enabled", String.valueOf(aggressiveOptimizationEnabled));
        aggressiveConfig.toProperties(props);

        // ========== Phase 2: 保存现代架构配置 ==========
        props.setProperty("blaze3d.modern.enabled", String.valueOf(modernRenderArchitectureEnabled));
        modernConfig.toProperties(props);

        // ========== Phase 2: 保存变换合并配置 ==========
        props.setProperty("blaze3d.transform.enabled", String.valueOf(gpuTransformMergingEnabled));
        transformConfig.toProperties(props);

        // 保存后处理效果配置
        props.setProperty("effects.enabled", String.valueOf(effectsEnabled));
        effectsConfig.toProperties(props);

        // ========== Phase 7: 保存拦截层配置 (v5.1 新增) ==========
        interceptionConfig.toProperties(props);

        // ========== Phase 8: 保存算法加速路径配置 (v5.4 新增) ==========
        algorithmConfig.toProperties(props);

        try (Writer writer = Files.newBufferedWriter(configFile)) {
            props.store(writer, "Renderium Configuration v5");
            markSaved(); // 保存成功后清除脏标记
        } catch (IOException e) {
            LOGGER.severe("Failed to save config to " + configFile +
                         ": " + e.getMessage());
        }
    }

    /**
     * 安全解析枚举值
     *
     * @param value         字符串值
     * @param enumClass     枚举类
     * @param defaultValue  解析失败时的默认值
     * @param <T>           枚举类型
     * @return 解析成功返回枚举值，否则返回默认值
     */
    private static <T extends Enum<T>> T parseEnum(String value, Class<T> enumClass, T defaultValue) {
        try {
            return Enum.valueOf(enumClass, value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return defaultValue;
        }
    }

    // ==================== Getter/Setter: 基础配置 ====================

    public SuperResolutionAdapter.Technology getTechnology() { return technology; }
    public void setTechnology(SuperResolutionAdapter.Technology technology) {
        this.technology = technology;
        markDirty(Identifier.TECHNOLOGY);
    }

    public SuperResolutionAdapter.Quality getQuality() { return quality; }
    public void setQuality(SuperResolutionAdapter.Quality quality) {
        this.quality = quality;
        markDirty(Identifier.QUALITY);
    }

    public boolean isFrameGenerationEnabled() { return frameGenerationEnabled; }
    public void setFrameGenerationEnabled(boolean enabled) {
        this.frameGenerationEnabled = enabled;
        markDirty(Identifier.FRAME_GENERATION_ENABLED);
    }

    public FrameGenMode getFrameGenMode() { return frameGenMode; }
    public void setFrameGenMode(FrameGenMode mode) {
        this.frameGenMode = mode;
        markDirty(Identifier.FRAME_GEN_MODE);
    }

    public boolean isReflexEnabled() { return reflexEnabled; }
    public void setReflexEnabled(boolean enabled) {
        this.reflexEnabled = enabled;
        markDirty(Identifier.REFLEX_ENABLED);
    }

    public ReflexMode getReflexMode() { return reflexMode; }
    public void setReflexMode(ReflexMode mode) {
        this.reflexMode = mode;
        markDirty(Identifier.REFLEX_MODE);
    }

    public float getSharpening() { return sharpening; }
    public void setSharpening(float sharpening) {
        this.sharpening = sharpening;
        markDirty(Identifier.SHARPENING);
    }

    public String getStreamlineSdkPath() { return streamlineSdkPath; }
    public void setStreamlineSdkPath(String path) {
        this.streamlineSdkPath = path;
        markDirty(Identifier.STREAMLINE_SDK_PATH);
    }

    /** 模组启用状态 */
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        markDirty(Identifier.ENABLED);
    }

    /** 调试模式 */
    public boolean isDebugMode() { return debugMode; }
    public void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
        markDirty(Identifier.DEBUG_MODE);
    }

    /** 超分辨率启用状态 */
    public boolean isSuperResolutionEnabled() { return superResolutionEnabled; }
    public void setSuperResolutionEnabled(boolean enabled) {
        this.superResolutionEnabled = enabled;
        markDirty(Identifier.SUPER_RESOLUTION_ENABLED);
    }

    public boolean isDynamicResolution() { return dynamicResolution; }
    public void setDynamicResolution(boolean dynamicResolution) {
        this.dynamicResolution = dynamicResolution;
        markDirty(Identifier.DYNAMIC_RESOLUTION);
    }

    // ==================== Getter/Setter: 运行模式配置 ====================

    /** 获取当前运行模式 */
    public RenderiumMode getMode() { return mode; }
    /** 设置运行模式 */
    public void setMode(RenderiumMode m) {
        this.mode = m;
        markDirty(Identifier.MODE);
    }

    // ==================== 动态配置访问 ====================

    /**
     * 获取原始配置属性值
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    public String getProperty(String key, String defaultValue) {
        return rawProperties.getProperty(key, defaultValue);
    }

    // ==================== Getter/Setter: 剔除配置 ====================

    /** 是否启用相邻面剔除 */
    public boolean isNeighborFaceCullingEnabled() { return neighborFaceCullingEnabled; }
    public void setNeighborFaceCullingEnabled(boolean v) {
        this.neighborFaceCullingEnabled = v;
        markDirty(Identifier.NEIGHBOR_FACE_CULLING);
    }
    /** 是否启用背面剔除 */
    public boolean isBackfaceCullingEnabled() { return backfaceCullingEnabled; }
    public void setBackfaceCullingEnabled(boolean v) {
        this.backfaceCullingEnabled = v;
        markDirty(Identifier.BACKFACE_CULLING);
    }
    /** 是否启用视锥体剔除 */
    public boolean isFrustumCullingEnabled() { return frustumCullingEnabled; }
    public void setFrustumCullingEnabled(boolean v) {
        this.frustumCullingEnabled = v;
        markDirty(Identifier.FRUSTUM_CULLING);
    }
    /** 是否启用遮挡剔除 */
    public boolean isOcclusionCullingEnabled() { return occlusionCullingEnabled; }
    public void setOcclusionCullingEnabled(boolean v) {
        this.occlusionCullingEnabled = v;
        markDirty(Identifier.OCCLUSION_CULLING);
    }

    // ==================== Getter/Setter: 批量渲染配置 ====================

    /** 是否启用批量渲染 */
    public boolean isBatchingEnabled() { return batchingEnabled; }
    public void setBatchingEnabled(boolean v) {
        this.batchingEnabled = v;
        markDirty(Identifier.BATCHING);
    }
    /** 是否启用实例化渲染 */
    public boolean isInstancingEnabled() { return instancingEnabled; }
    public void setInstancingEnabled(boolean v) {
        this.instancingEnabled = v;
        markDirty(Identifier.INSTANCING);
    }

    // ==================== Getter/Setter: 调试/显示配置 ====================

    /** 是否绘制实体轮廓 */
    public boolean isDrawOutlineEnabled() { return drawOutlineEnabled; }
    public void setDrawOutlineEnabled(boolean v) {
        this.drawOutlineEnabled = v;
        markDirty(Identifier.DRAW_OUTLINE);
    }
    /** 是否启用调试覆盖层 */
    public boolean isDebugOverlayEnabled() { return debugOverlayEnabled; }
    public void setDebugOverlayEnabled(boolean v) {
        this.debugOverlayEnabled = v;
        markDirty(Identifier.DEBUG_OVERLAY);
    }
    /** 是否启用垂直同步 */
    public boolean isVSyncEnabled() { return vsyncEnabled; }
    public void setVSyncEnabled(boolean v) {
        this.vsyncEnabled = v;
        markDirty(Identifier.VSYNC);
    }

    // ==================== Getter/Setter: 图形效果配置 ====================

    /** 是否启用雾效 */
    public boolean isFogEnabled() { return fogEnabled; }
    public void setFogEnabled(boolean v) {
        this.fogEnabled = v;
        markDirty(Identifier.FOG);
    }
    /** 是否启用云彩 */
    public boolean isCloudsEnabled() { return cloudsEnabled; }
    public void setCloudsEnabled(boolean v) {
        this.cloudsEnabled = v;
        markDirty(Identifier.CLOUDS);
    }
    /** 是否启用暗角效果 */
    public boolean isVignetteEnabled() { return vignetteEnabled; }
    public void setVignetteEnabled(boolean v) {
        this.vignetteEnabled = v;
        markDirty(Identifier.VIGNETTE);
    }
    /** 是否启用后处理 */
    public boolean isPostProcessingEnabled() { return postProcessingEnabled; }
    public void setPostProcessingEnabled(boolean v) {
        this.postProcessingEnabled = v;
        markDirty(Identifier.POST_PROCESSING);
    }
    /** 是否启用阴影 */
    public boolean isShadowEnabled() { return shadowEnabled; }
    public void setShadowEnabled(boolean v) {
        this.shadowEnabled = v;
        markDirty(Identifier.SHADOW);
    }

    // ==================== Getter/Setter: Quality 扩展配置 (v5.3 新增) ====================

    /**
     * 是否启用隐藏流体剔除
     *
     * @return true 如果启用隐藏流体剔除
     * @see #hiddenFluidCulling
     * @since 5.3.0
     */
    public boolean isHiddenFluidCulling() { return hiddenFluidCulling; }

    /**
     * 设置是否启用隐藏流体剔除
     * <p>
     * 启用后不会渲染被不透明方块完全包围的流体面。
     * 修改后会标记配置为脏状态，触发持久化回调。
     *
     * @param enabled true 启用，false 禁用
     * @since 5.3.0
     */
    public void setHiddenFluidCulling(boolean enabled) {
        this.hiddenFluidCulling = enabled;
        markDirty(Identifier.HIDDEN_FLUID_CULLING);
    }

    /**
     * 是否启用改进流体塑形
     *
     * @return true 如果启用改进流体塑形
     * @see #improvedFluidShaping
     * @since 5.3.0
     */
    public boolean isImprovedFluidShaping() { return improvedFluidShaping; }

    /**
     * 设置是否启用改进流体塑形
     * <p>
     * 启用后使用改进算法渲染流体的几何形状。
     * 修改后会标记配置为脏状态，触发持久化回调。
     *
     * @param enabled true 启用，false 禁用
     * @since 5.3.0
     */
    public void setImprovedFluidShaping(boolean enabled) {
        this.improvedFluidShaping = enabled;
        markDirty(Identifier.IMPROVED_FLUID_SHAPING);
    }

    // ==================== Getter/Setter: v5 Blaze3D 优化开关 ====================

    /**
     * 是否启用帧图优化（仅 Aggressive 模式生效）
     *
     * @return true 如果启用帧图优化
     */
    public boolean isFrameGraphOptimizationEnabled() { return frameGraphOptimizationEnabled; }

    /**
     * 设置是否启用帧图优化
     *
     * @param enabled 是否启用
     */
    public void setFrameGraphOptimizationEnabled(boolean enabled) {
        this.frameGraphOptimizationEnabled = enabled;
        markDirty(Identifier.FRAME_GRAPH_OPTIMIZATION);
    }

    /**
     * 是否启用 Vulkan 命令缓冲区优化（仅 Aggressive 模式生效）
     *
     * @return true 如果启用 Vulkan 命令优化
     */
    public boolean isVulkanCommandOptimizationEnabled() { return vulkanCommandOptimizationEnabled; }

    /**
     * 设置是否启用 Vulkan 命令缓冲区优化
     *
     * @param enabled 是否启用
     */
    public void setVulkanCommandOptimizationEnabled(boolean enabled) {
        this.vulkanCommandOptimizationEnabled = enabled;
        markDirty(Identifier.VULKAN_COMMAND_OPTIMIZATION);
    }

    /**
     * 是否启用内存优化（仅 Aggressive 模式生效）
     *
     * @return true 如果启用内存优化
     */
    public boolean isMemoryOptimizationEnabled() { return memoryOptimizationEnabled; }

    /**
     * 设置是否启用内存优化
     *
     * @param enabled 是否启用
     */
    public void setMemoryOptimizationEnabled(boolean enabled) {
        this.memoryOptimizationEnabled = enabled;
        markDirty(Identifier.MEMORY_OPTIMIZATION);
    }

    /**
     * 是否启用着色器管线优化（仅 Aggressive 模式生效）
     *
     * @return true 如果启��着色器管线优化
     */
    public boolean isShaderPipelineOptimizationEnabled() { return shaderPipelineOptimizationEnabled; }

    /**
     * 设置是否启用着色器管线优化
     *
     * @param enabled 是否启用
     */
    public void setShaderPipelineOptimizationEnabled(boolean enabled) {
        this.shaderPipelineOptimizationEnabled = enabled;
        markDirty(Identifier.SHADER_PIPELINE_OPTIMIZATION);
    }

    // ==================== Getter/Setter: v5 详细配置对象 ====================

    /**
     * 获取帧图优化详细配置
     *
     * @return FrameGraphConfig 实例
     */
    public FrameGraphConfig getFrameGraphConfig() { return frameGraphConfig; }

    /**
     * 获取 Vulkan 命令优化详细配置
     *
     * @return VulkanCommandConfig 实例
     */
    public VulkanCommandConfig getVulkanCommandConfig() { return vulkanCommandConfig; }

    /**
     * 获取内存优化详细配置
     *
     * @return MemoryConfig 实例
     */
    public MemoryConfig getMemoryConfig() { return memoryConfig; }

    /**
     * 获取着色器管线优化详细配置
     *
     * @return ShaderPipelineConfig 实例
     */
    public ShaderPipelineConfig getShaderPipelineConfig() { return shaderPipelineConfig; }

    // ==================== Getter/Setter: 后处理效果配置 ====================

    /**
     * 是否启用后处理效果
     *
     * @return true 如果后处理效果总开关已启用
     */
    public boolean isEffectsEnabled() { return effectsEnabled; }

    /**
     * 设置是否启用后处理效果
     *
     * @param enabled 是否启用所有后处理效果
     */
    public void setEffectsEnabled(boolean enabled) {
        this.effectsEnabled = enabled;
        markDirty(Identifier.EFFECTS_ENABLED);
    }

    /**
     * 获取后处理效果详细配置
     *
     * @return EffectsConfig 实例
     */
    public EffectsConfig getEffectsConfig() { return effectsConfig; }

    // ==================== Getter/Setter: Phase 2 VMA 激进优化 ====================

    /**
     * 是否启用 VMA（Vulkan Memory Allocator）激进优化
     *
     * <p>VMA 优化包括内存池预分配、资源别名、延迟释放等高级特性。
     * 仅在狂暴模式（Aggressive）下生效。
     *
     * @return true 如果已启用 VMA 激进优化
     */
    public boolean isVmaEnhancementEnabled() { return vmaEnhancementEnabled; }

    /**
     * 设置是否启用 VMA 激进优化
     *
     * @param enabled 是否启用 VMA 激进优化
     */
    public void setVmaEnhancementEnabled(boolean enabled) {
        this.vmaEnhancementEnabled = enabled;
        markDirty(Identifier.VMA_ENHANCEMENT);
    }

    /**
     * 获取 VMA 详细配置对象
     *
     * @return VmaConfig 实例，包含内存池大小、水位线、别名等配置
     */
    public VmaConfig getVmaConfig() { return vmaConfig; }

    // ==================== Getter/Setter: Phase 2 激进 MC 优化 ====================

    /**
     * 是否启用激进 Minecraft 优化
     *
     * <p>包括顶点压缩(AG1)、批量合并(AG2)、异步上传(AG3)。
     * 仅在狂暴模式（Aggressive）下生效。
     *
     * @return true 如果已启用激进 MC 优化
     */
    public boolean isAggressiveOptimizationEnabled() { return aggressiveOptimizationEnabled; }

    /**
     * 设置是否启用激进 MC 优化
     *
     * @param enabled 是否启用激进 MC 优化
     */
    public void setAggressiveOptimizationEnabled(boolean enabled) {
        this.aggressiveOptimizationEnabled = enabled;
        markDirty(Identifier.AGGRESSIVE_OPTIMIZATION);
    }

    /**
     * 获取激进优化详细配置对象
     *
     * @return AggressiveConfig 实例，包含顶点压缩、批量合并、异步上传参数
     */
    public AggressiveConfig getAggressiveConfig() { return aggressiveConfig; }

    // ==================== Getter/Setter: Phase 2 现代 Render 架构 ====================

    /**
     * 是否启用现代 Render 架构
     *
     * <p>包括 Hi-Z 遮挡剔除、Bindless 纹理、ECS 场景图、GPU 驱动剔除。
     * 仅适用于支持 Compute Shader 的现代 GPU，仅在狂暴模式下生效。
     *
     * @return true 如果已启用现代 Render 架构
     */
    public boolean isModernRenderArchitectureEnabled() { return modernRenderArchitectureEnabled; }

    /**
     * 设置是否启用现代 Render 架构
     *
     * @param enabled 是否启用现代 Render 架构
     */
    public void setModernRenderArchitectureEnabled(boolean enabled) {
        this.modernRenderArchitectureEnabled = enabled;
        markDirty(Identifier.MODERN_RENDER_ARCHITECTURE);
    }

    /**
     * 获取现代架构详细配置对象
     *
     * @return ModernConfig 实例，包含 Hi-Z、Bindless、ECS、GPU 剔除参数
     */
    public ModernConfig getModernConfig() { return modernConfig; }

    // ==================== Getter/Setter: Phase 2 GPU 变换与合并 ====================

    /**
     * 是否启用 GPU 变换与合并优化
     *
     * <p>包括全局顶点缓冲区、实例变换缓冲、静态几何体缓存、层级批量合并、材质合并渲染。
     * 仅适用于支持 Instancing 和 SSBO 的现代 GPU，仅在狂暴模式下生效。
     *
     * @return true 如果已启用 GPU 变换与合并优化
     */
    public boolean isGpuTransformMergingEnabled() { return gpuTransformMergingEnabled; }

    /**
     * 设置是否启用 GPU 变换与合并优化
     *
     * @param enabled 是否启用 GPU 变换与合并优化
     */
    public void setGpuTransformMergingEnabled(boolean enabled) {
        this.gpuTransformMergingEnabled = enabled;
        markDirty(Identifier.GPU_TRANSFORM_MERGING);
    }

    /**
     * 获取 GPU 变换与合并详细配置对象
     *
     * @return TransformConfig 实例，包含缓冲区容量、静态缓存、合并策略参数
     */
    public TransformConfig getTransformConfig() { return transformConfig; }

    // ==================== Getter/Setter: Phase 7 拦截层配置 ====================

    /**
     * 获取拦截层总配置对象（v5.1 新增）
     *
     * <p>包含前拦截层、后拦截层、模组输出重定向的完整配置。
     * 通过此对象可以访问所有拦截层相关的配置项。
     *
     * @return InterceptionConfig 实例，包含完整的拦截层配置
     * @see InterceptionConfig
     * @since 5.1.0
     */
    public InterceptionConfig getInterceptionConfig() { return interceptionConfig; }

    /**
     * 设置拦截层总配置对象（v5.1 新增）
     *
     * @param config 拦截层配置实例
     * @since 5.1.0
     */
    public void setInterceptionConfig(InterceptionConfig config) {
        this.interceptionConfig = config;
        markDirty(Identifier.INTERCEPTION_CONFIG);
    }

    // ==================== Phase 7: 拦截层配置内部类 (v5.1 新增) ====================

    /**
     * 拦截层总配置 - 控制 PreBlaze3DInterceptor 和 PostBlaze3DInterceptor 行为
     *
     * <p>此配置类是 v5.1 架构的核心配置入口，统一管理：
     * <ul>
     *   <li><b>前拦截层</b>：模组检测、LOD 注入、剔除优化</li>
     *   <li><b>后拦截层</b>：帧捕获、超分辨率、帧生成、后处理</li>
     *   <li><b>模组输出重定向</b>：第三方模组的专用输出重定向</li>
     * </ul>
     *
     * <h3>配置示例：</h3>
     * <pre>
     * # Properties 格式示例：
     *     interception.preInterceptor.enabled=true
     *     interception.preInterceptor.lodInjection.maxLevels=4
     *     interception.postInterceptor.superResolution.renderScale=0.667
     *     interception.modOutputRedirect.enabled=true
     * </pre>
     *
     * @see PreInterceptorConfig 前拦截层详细配置
     * @see PostInterceptorConfig 后拦截层详细配置
     * @see ModOutputRedirectConfig 模组输出重定向配置
     * @since 5.1.0
     */
    public static final class InterceptionConfig {

        /** 前拦截层配置 */
        private PreInterceptorConfig preInterceptor = new PreInterceptorConfig();

        /** 后拦截层配置 */
        private PostInterceptorConfig postInterceptor = new PostInterceptorConfig();

        /** 模组输出重定向器配置 */
        private ModOutputRedirectConfig modOutputRedirect = new ModOutputRedirectConfig();

        /**
         * 默认构造函数 - 使用 spec.md 定义的默认值
         */
        public InterceptionConfig() {}

        /**
         * 从 Properties 对象加载拦截层配置
         *
         * @param props 属性集合，键前缀为 "interception."
         * @return InterceptionConfig 实例
         */
        public static InterceptionConfig fromProperties(Properties props) {
            InterceptionConfig config = new InterceptionConfig();

            // 加载前拦截层配置
            config.preInterceptor = PreInterceptorConfig.fromProperties(props);

            // 加载后拦截层配置
            config.postInterceptor = PostInterceptorConfig.fromProperties(props);

            // 加载模组输出重定向配置
            config.modOutputRedirect = ModOutputRedirectConfig.fromProperties(props);

            return config;
        }

        /**
         * 将配置写入 Properties 对象
         *
         * @param props 属性集合
         */
        public void toProperties(Properties props) {
            preInterceptor.toProperties(props);
            postInterceptor.toProperties(props);
            modOutputRedirect.toProperties(props);
        }

        /**
         * 验证拦截层配置的有效性
         *
         * @return 验证结果（OK/WARNING/ERROR）
         */
        public ValidationResult validate() {
            StringBuilder errors = new StringBuilder();
            StringBuilder warnings = new StringBuilder();

            // 验证前拦截层配置
            ValidationResult preResult = preInterceptor.validate();
            if (preResult.hasErrors()) {
                errors.append("preInterceptor: ").append(preResult.message).append("; ");
            } else if (preResult.hasWarnings()) {
                warnings.append("preInterceptor: ").append(preResult.message).append("; ");
            }

            // 验证后拦截层配置
            ValidationResult postResult = postInterceptor.validate();
            if (postResult.hasErrors()) {
                errors.append("postInterceptor: ").append(postResult.message).append("; ");
            } else if (postResult.hasWarnings()) {
                warnings.append("postInterceptor: ").append(postResult.message).append("; ");
            }

            // 验证模组输出重定向配置
            ValidationResult redirectResult = modOutputRedirect.validate();
            if (redirectResult.hasErrors()) {
                errors.append("modOutputRedirect: ").append(redirectResult.message).append("; ");
            } else if (redirectResult.hasWarnings()) {
                warnings.append("modOutputRedirect: ").append(redirectResult.message).append("; ");
            }

            // 构建最终结果
            if (errors.length() > 0) {
                return ValidationResult.error(errors.toString());
            }
            if (warnings.length() > 0) {
                return ValidationResult.warning(warnings.toString());
            }
            return ValidationResult.ok();
        }

        // ==================== Getter/Setter ====================

        /** 获取前拦截层配置 */
        public PreInterceptorConfig getPreInterceptor() { return preInterceptor; }
        public void setPreInterceptor(PreInterceptorConfig config) { this.preInterceptor = config; }

        /** 获取后拦截层配置 */
        public PostInterceptorConfig getPostInterceptor() { return postInterceptor; }
        public void setPostInterceptor(PostInterceptorConfig config) { this.postInterceptor = config; }

        /** 获取模组输出重定向配置 */
        public ModOutputRedirectConfig getModOutputRedirect() { return modOutputRedirect; }
        public void setModOutputRedirect(ModOutputRedirectConfig config) { this.modOutputRedirect = config; }
    }

    /**
     * 前拦截层配置 - 控制模组检测、LOD 注入、剔除优化等
     *
     * <p>管理 {@link com.renderium.interception.PreBlaze3DInterceptor} 的行为，
     * 在 Blaze3D 渲染管线执行前进行预处理。
     *
     * @since 5.1.0
     */
    public static final class PreInterceptorConfig {

        /** 是否启用前拦截层（默认：false） */
        private boolean enabled = false;

        /** 是否启用性能优化模组检测（默认：true） */
        private boolean performanceModDetection = true;

        /** LOD 注入配置 */
        private LODConfig lodInjection = new LODConfig();

        /** 剔除注入配置 */
        private CullingInjectionConfig cullingInjection = new CullingInjectionConfig();

        /**
         * 默认构造函数
         */
        public PreInterceptorConfig() {}

        /**
         * 从 Properties 加载配置
         */
        public static PreInterceptorConfig fromProperties(Properties props) {
            PreInterceptorConfig config = new PreInterceptorConfig();

            config.enabled = Boolean.parseBoolean(
                props.getProperty("interception.preInterceptor.enabled", "false")
            );
            config.performanceModDetection = Boolean.parseBoolean(
                props.getProperty("interception.preInterceptor.performanceModDetection", "true")
            );
            config.lodInjection = LODConfig.fromProperties(props);
            config.cullingInjection = CullingInjectionConfig.fromProperties(props);

            return config;
        }

        /**
         * 写入 Properties
         */
        public void toProperties(Properties props) {
            props.setProperty("interception.preInterceptor.enabled", String.valueOf(enabled));
            props.setProperty("interception.preInterceptor.performanceModDetection", String.valueOf(performanceModDetection));
            lodInjection.toProperties(props);
            cullingInjection.toProperties(props);
        }

        /**
         * 验证配置有效性
         */
        public ValidationResult validate() {
            StringBuilder errors = new StringBuilder();
            StringBuilder warnings = new StringBuilder();

            // 验证 LOD 配置
            ValidationResult lodResult = lodInjection.validate();
            if (lodResult.hasErrors()) errors.append("lodInjection: ").append(lodResult.message);
            else if (lodResult.hasWarnings()) warnings.append("lodInjection: ").append(lodResult.message);

            // 验证剔除配置
            ValidationResult cullingResult = cullingInjection.validate();
            if (cullingResult.hasErrors()) errors.append("cullingInjection: ").append(cullingResult.message);
            else if (cullingResult.hasWarnings()) warnings.append("cullingInjection: ").append(cullingResult.message);

            if (errors.length() > 0) return ValidationResult.error(errors.toString());
            if (warnings.length() > 0) return ValidationResult.warning(warnings.toString());
            return ValidationResult.ok();
        }

        // Getter/Setter
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isPerformanceModDetection() { return performanceModDetection; }
        public void setPerformanceModDetection(boolean detection) { this.performanceModDetection = detection; }

        public LODConfig getLodInjection() { return lodInjection; }
        public void setLodInjection(LODConfig config) { this.lodInjection = config; }

        public CullingInjectionConfig getCullingInjection() { return cullingInjection; }
        public void setCullingInjection(CullingInjectionConfig config) { this.cullingInjection = config; }
    }

    /**
     * 后拦截层配置 - 控制帧捕获、超分辨率、帧生成、后处理等
     *
     * <p>管理 {@link com.renderium.interception.PostBlaze3DInterceptor} 的行为，
     * 在 Blaze3D 渲染管线执行后进行后处理。
     *
     * @since 5.1.0
     */
    public static final class PostInterceptorConfig {

        /** 是否启用后拦截层（默认：false） */
        private boolean enabled = false;

        /** 帧捕获配置 */
        private FrameCaptureConfig frameCapture = new FrameCaptureConfig();

        /** 超分辨率配置（后拦截层专用，独立于主配置的 SuperResolutionAdapter） */
        private SuperResolutionConfig superResolution = new SuperResolutionConfig();

        /** 帧生成配置 */
        private FrameGenerationConfig frameGeneration = new FrameGenerationConfig();

        /** 后处理效果配置（拦截层专用） */
        private PostProcessingConfig postProcessing = new PostProcessingConfig();

        /**
         * 默认构造函数
         */
        public PostInterceptorConfig() {}

        /**
         * 从 Properties 加载配置
         */
        public static PostInterceptorConfig fromProperties(Properties props) {
            PostInterceptorConfig config = new PostInterceptorConfig();

            config.enabled = Boolean.parseBoolean(
                props.getProperty("interception.postInterceptor.enabled", "false")
            );
            config.frameCapture = FrameCaptureConfig.fromProperties(props);
            config.superResolution = SuperResolutionConfig.fromProperties(props);
            config.frameGeneration = FrameGenerationConfig.fromProperties(props);
            config.postProcessing = PostProcessingConfig.fromProperties(props);

            return config;
        }

        /**
         * 写入 Properties
         */
        public void toProperties(Properties props) {
            props.setProperty("interception.postInterceptor.enabled", String.valueOf(enabled));
            frameCapture.toProperties(props);
            superResolution.toProperties(props);
            frameGeneration.toProperties(props);
            postProcessing.toProperties(props);
        }

        /**
         * 验证配置有效性
         */
        public ValidationResult validate() {
            StringBuilder errors = new StringBuilder();
            StringBuilder warnings = new StringBuilder();

            // 验证各子配置
            ValidationResult[] results = {
                frameCapture.validate(),
                superResolution.validate(),
                frameGeneration.validate(),
                postProcessing.validate()
            };

            String[] names = {"frameCapture", "superResolution", "frameGeneration", "postProcessing"};
            for (int i = 0; i < results.length; i++) {
                if (results[i].hasErrors()) errors.append(names[i]).append(": ").append(results[i].message).append("; ");
                else if (results[i].hasWarnings()) warnings.append(names[i]).append(": ").append(results[i].message).append("; ");
            }

            if (errors.length() > 0) return ValidationResult.error(errors.toString());
            if (warnings.length() > 0) return ValidationResult.warning(warnings.toString());
            return ValidationResult.ok();
        }

        // Getter/Setter
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public FrameCaptureConfig getFrameCapture() { return frameCapture; }
        public void setFrameCapture(FrameCaptureConfig config) { this.frameCapture = config; }

        public SuperResolutionConfig getSuperResolution() { return superResolution; }
        public void setSuperResolution(SuperResolutionConfig config) { this.superResolution = config; }

        public FrameGenerationConfig getFrameGeneration() { return frameGeneration; }
        public void setFrameGeneration(FrameGenerationConfig config) { this.frameGeneration = config; }

        public PostProcessingConfig getPostProcessing() { return postProcessing; }
        public void setPostProcessing(PostProcessingConfig config) { this.postProcessing = config; }
    }

    /**
     * LOD (Level of Detail) 注入配置
     *
     * <p>控制前拦截层的多细节层次系统行为，
     * 包括最大 LOD 等级、距离阈值、过渡模式等。
     *
     * @since 5.1.0
     */
    public static final class LODConfig {

        /** 是否启用 LOD 注入（默认：false） */
        private boolean enabled = false;

        /** 最大 LOD 等级数（2-8，默认：4） */
        private int maxLevels = 4;

        /** 距离阈值数组（单位：方块，默认：[32, 64, 128]） */
        private int[] distanceThresholds = {32, 64, 128};

        /** 过渡模式：dithering | crossfade（默认：dithering） */
        private String transitionMode = "dithering";

        /**
         * 默认构造函数
         */
        public LODConfig() {}

        /**
         * 从 Properties 加载配置
         */
        public static LODConfig fromProperties(Properties props) {
            LODConfig config = new LODConfig();

            config.enabled = Boolean.parseBoolean(
                props.getProperty("interception.preInterceptor.lodInjection.enabled", "false")
            );
            config.maxLevels = Integer.parseInt(
                props.getProperty("interception.preInterceptor.lodInjection.maxLevels", "4")
            );

            // 解析距离阈值数组
            String thresholdsStr = props.getProperty(
                "interception.preInterceptor.lodInjection.distanceThresholds", "32,64,128"
            );
            try {
                String[] parts = thresholdsStr.split(",");
                config.distanceThresholds = new int[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    config.distanceThresholds[i] = Integer.parseInt(parts[i].trim());
                }
            } catch (NumberFormatException e) {
                // 使用默认值
                config.distanceThresholds = new int[]{32, 64, 128};
            }

            config.transitionMode = props.getProperty(
                "interception.preInterceptor.lodInjection.transitionMode", "dithering"
            );

            return config;
        }

        /**
         * 写入 Properties
         */
        public void toProperties(Properties props) {
            props.setProperty("interception.preInterceptor.lodInjection.enabled", String.valueOf(enabled));
            props.setProperty("interception.preInterceptor.lodInjection.maxLevels", String.valueOf(maxLevels));

            // 将距离阈值数组转换为逗号分隔字符串
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < distanceThresholds.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(distanceThresholds[i]);
            }
            props.setProperty("interception.preInterceptor.lodInjection.distanceThresholds", sb.toString());

            props.setProperty("interception.preInterceptor.lodInjection.transitionMode", transitionMode);
        }

        /**
         * 验证配置有效性
         */
        public ValidationResult validate() {
            StringBuilder errors = new StringBuilder();
            StringBuilder warnings = new StringBuilder();

            // 验证 maxLevels 范围 [2, 8]
            if (maxLevels < 2 || maxLevels > 8) {
                errors.append("maxLevels must be in range [2, 8]; ");
            }

            // 验证距离阈值递增且为正数
            if (distanceThresholds != null && distanceThresholds.length > 0) {
                for (int i = 0; i < distanceThresholds.length; i++) {
                    if (distanceThresholds[i] <= 0) {
                        errors.append("distanceThresholds[" + i + "] must be > 0; ");
                    }
                    if (i > 0 && distanceThresholds[i] <= distanceThresholds[i - 1]) {
                        errors.append("distanceThresholds must be strictly increasing; ");
                        break; // 只报告一次错误
                    }
                }
                if (distanceThresholds.length != maxLevels - 1) {
                    warnings.append("distanceThresholds length should be maxLevels-1; ");
                }
            }

            // 验证过渡模式
            if (!"dithering".equals(transitionMode) && !"crossfade".equals(transitionMode)) {
                errors.append("transitionMode must be 'dithering' or 'crossfade'; ");
            }

            if (errors.length() > 0) return ValidationResult.error(errors.toString());
            if (warnings.length() > 0) return ValidationResult.warning(warnings.toString());
            return ValidationResult.ok();
        }

        // Getter/Setter
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getMaxLevels() { return maxLevels; }
        public void setMaxLevels(int levels) { this.maxLevels = levels; }

        public int[] getDistanceThresholds() { return distanceThresholds; }
        public void setDistanceThresholds(int[] thresholds) { this.distanceThresholds = thresholds; }

        public String getTransitionMode() { return transitionMode; }
        public void setTransitionMode(String mode) { this.transitionMode = mode; }
    }

    /**
     * 剔除注入配置 - 控制视锥体/遮挡/距离剔除优化
     *
     * @since 5.1.0
     */
    public static final class CullingInjectionConfig {

        /** 是否启用剔除注入（默认：false） */
        private boolean enabled = false;

        /** 是否启用视锥体剔除（默认：true） */
        private boolean frustumCulling = true;

        /** 是否启用遮挡剔除（默认：true） */
        private boolean occlusionCulling = true;

        /** 是否启用距离剔除（默认：true） */
        private boolean distanceCulling = true;

        /** 剔除策略：conservative | balanced | aggressive（默认：balanced） */
        private String strategy = "balanced";

        /**
         * 默认构造函数
         */
        public CullingInjectionConfig() {}

        /**
         * 从 Properties 加载配置
         */
        public static CullingInjectionConfig fromProperties(Properties props) {
            CullingInjectionConfig config = new CullingInjectionConfig();

            config.enabled = Boolean.parseBoolean(
                props.getProperty("interception.preInterceptor.cullingInjection.enabled", "false")
            );
            config.frustumCulling = Boolean.parseBoolean(
                props.getProperty("interception.preInterceptor.cullingInjection.frustumCulling", "true")
            );
            config.occlusionCulling = Boolean.parseBoolean(
                props.getProperty("interception.preInterceptor.cullingInjection.occlusionCulling", "true")
            );
            config.distanceCulling = Boolean.parseBoolean(
                props.getProperty("interception.preInterceptor.cullingInjection.distanceCulling", "true")
            );
            config.strategy = props.getProperty(
                "interception.preInterceptor.cullingInjection.strategy", "balanced"
            );

            return config;
        }

        /**
         * 写入 Properties
         */
        public void toProperties(Properties props) {
            props.setProperty("interception.preInterceptor.cullingInjection.enabled", String.valueOf(enabled));
            props.setProperty("interception.preInterceptor.cullingInjection.frustumCulling", String.valueOf(frustumCulling));
            props.setProperty("interception.preInterceptor.cullingInjection.occlusionCulling", String.valueOf(occlusionCulling));
            props.setProperty("interception.preInterceptor.cullingInjection.distanceCulling", String.valueOf(distanceCulling));
            props.setProperty("interception.preInterceptor.cullingInjection.strategy", strategy);
        }

        /**
         * 验证配置有效性
         */
        public ValidationResult validate() {
            // 验证策略枚举值
            if (!"conservative".equals(strategy) &&
                !"balanced".equals(strategy) &&
                !"aggressive".equals(strategy)) {
                return ValidationResult.error("strategy must be 'conservative', 'balanced', or 'aggressive'");
            }
            return ValidationResult.ok();
        }

        // Getter/Setter
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isFrustumCulling() { return frustumCulling; }
        public void setFrustumCulling(boolean culling) { this.frustumCulling = culling; }

        public boolean isOcclusionCulling() { return occlusionCulling; }
        public void setOcclusionCulling(boolean culling) { this.occlusionCulling = culling; }

        public boolean isDistanceCulling() { return distanceCulling; }
        public void setDistanceCulling(boolean culling) { this.distanceCulling = culling; }

        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }
    }

    /**
     * 帧捕获配置 - 控制后拦截层的帧数据捕获方式
     *
     * @since 5.1.0
     */
    public static final class FrameCaptureConfig {

        /** 是否启用帧捕获（默认：false） */
        private boolean enabled = false;

        /** 捕获方法：auto | fbo | swapchain（默认：auto） */
        private String method = "auto";

        /** 帧格式：RGBA8 | RGBA16F | RGBA32F（默认：RGBA16F 支持HDR） */
        private String format = "RGBA16F";

        /** MSAA 采样数：0, 2, 4, 8（默认：4） */
        private int msaa = 4;

        /** 是否启用异步捕获（默认：false） */
        private boolean async = false;

        /**
         * 默认构造函数
         */
        public FrameCaptureConfig() {}

        /**
         * 从 Properties 加载配置
         */
        public static FrameCaptureConfig fromProperties(Properties props) {
            FrameCaptureConfig config = new FrameCaptureConfig();

            config.enabled = Boolean.parseBoolean(
                props.getProperty("interception.postInterceptor.frameCapture.enabled", "false")
            );
            config.method = props.getProperty(
                "interception.postInterceptor.frameCapture.method", "auto"
            );
            config.format = props.getProperty(
                "interception.postInterceptor.frameCapture.format", "RGBA16F"
            );
            config.msaa = Integer.parseInt(
                props.getProperty("interception.postInterceptor.frameCapture.msaa", "4")
            );
            config.async = Boolean.parseBoolean(
                props.getProperty("interception.postInterceptor.frameCapture.async", "false")
            );

            return config;
        }

        /**
         * 写入 Properties
         */
        public void toProperties(Properties props) {
            props.setProperty("interception.postInterceptor.frameCapture.enabled", String.valueOf(enabled));
            props.setProperty("interception.postInterceptor.frameCapture.method", method);
            props.setProperty("interception.postInterceptor.frameCapture.format", format);
            props.setProperty("interception.postInterceptor.frameCapture.msaa", String.valueOf(msaa));
            props.setProperty("interception.postInterceptor.frameCapture.async", String.valueOf(async));
        }

        /**
         * 验证配置有效性
         */
        public ValidationResult validate() {
            StringBuilder errors = new StringBuilder();

            // 验证捕获方法
            if (!"auto".equals(method) && !"fbo".equals(method) && !"swapchain".equals(method)) {
                errors.append("method must be 'auto', 'fbo', or 'swapchain'; ");
            }

            // 验证帧格式
            if (!"RGBA8".equals(format) && !"RGBA16F".equals(format) && !"RGBA32F".equals(format)) {
                errors.append("format must be 'RGBA8', 'RGBA16F', or 'RGBA32F'; ");
            }

            // 验证 MSAA 值（合法值：0, 2, 4, 8）
            if (msaa != 0 && msaa != 2 && msaa != 4 && msaa != 8) {
                errors.append("msaa must be 0, 2, 4, or 8; ");
            }

            if (errors.length() > 0) return ValidationResult.error(errors.toString());
            return ValidationResult.ok();
        }

        // Getter/Setter
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }

        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }

        public int getMsaa() { return msaa; }
        public void setMsaa(int msaa) { this.msaa = msaa; }

        public boolean isAsync() { return async; }
        public void setAsync(boolean async) { this.async = async; }
    }

    /**
     * 超分辨率配置（后拦截层专用）
     *
     * <p>独立于主配置的 {@link SuperResolutionAdapter}，
     * 专门用于后拦截层的超分辨率处理。
     *
     * @since 5.1.0
     */
    public static final class SuperResolutionConfig {

        /** 是否启用超分辨率注入（默认：false） */
        private boolean enabled = false;

        /** 首选技术：auto | dlss | xess | fsr（默认：auto） */
        private String preferredTechnology = "auto";

        /** 质量模式：quality | balanced | performance（默认：balanced） */
        private String qualityMode = "balanced";

        /** 渲染比例（0.5 - 1.0，默认：0.667 即 67%） */
        private float renderScale = 0.667f;

        /** 锐化强度（0.0 - 1.0，默认：0.3） */
        private float sharpening = 0.3f;

        /**
         * 默认构造函数
         */
        public SuperResolutionConfig() {}

        /**
         * 从 Properties 加载配置
         */
        public static SuperResolutionConfig fromProperties(Properties props) {
            SuperResolutionConfig config = new SuperResolutionConfig();

            config.enabled = Boolean.parseBoolean(
                props.getProperty("interception.postInterceptor.superResolution.enabled", "false")
            );
            config.preferredTechnology = props.getProperty(
                "interception.postInterceptor.superResolution.preferredTechnology", "auto"
            );
            config.qualityMode = props.getProperty(
                "interception.postInterceptor.superResolution.qualityMode", "balanced"
            );
            config.renderScale = Float.parseFloat(
                props.getProperty("interception.postInterceptor.superResolution.renderScale", "0.667")
            );
            config.sharpening = Float.parseFloat(
                props.getProperty("interception.postInterceptor.superResolution.sharpening", "0.3")
            );

            return config;
        }

        /**
         * 写入 Properties
         */
        public void toProperties(Properties props) {
            props.setProperty("interception.postInterceptor.superResolution.enabled", String.valueOf(enabled));
            props.setProperty("interception.postInterceptor.superResolution.preferredTechnology", preferredTechnology);
            props.setProperty("interception.postInterceptor.superResolution.qualityMode", qualityMode);
            props.setProperty("interception.postInterceptor.superResolution.renderScale", String.valueOf(renderScale));
            props.setProperty("interception.postInterceptor.superResolution.sharpening", String.valueOf(sharpening));
        }

        /**
         * 验证配置有效性
         */
        public ValidationResult validate() {
            StringBuilder errors = new StringBuilder();
            StringBuilder warnings = new StringBuilder();

            // 验证首选技术
            if (!"auto".equals(preferredTechnology) &&
                !"dlss".equals(preferredTechnology) &&
                !"xess".equals(preferredTechnology) &&
                !"fsr".equals(preferredTechnology)) {
                errors.append("preferredTechnology must be 'auto', 'dlss', 'xess', or 'fsr'; ");
            }

            // 验证质量模式
            if (!"quality".equals(qualityMode) &&
                !"balanced".equals(qualityMode) &&
                !"performance".equals(qualityMode)) {
                errors.append("qualityMode must be 'quality', 'balanced', or 'performance'; ");
            }

            // 验证渲染比例范围 [0.5, 1.0]
            if (renderScale < 0.5f || renderScale > 1.0f) {
                errors.append("renderScale must be in range [0.5, 1.0]; ");
            }

            // 验证锐化强度范围 [0.0, 1.0]
            if (sharpening < 0.0f || sharpening > 1.0f) {
                errors.append("sharpening must be in range [0.0, 1.0]; ");
            }

            // 性能警告
            if (renderScale < 0.6f && "quality".equals(qualityMode)) {
                warnings.append("low renderScale with quality mode may cause performance issues; ");
            }

            if (errors.length() > 0) return ValidationResult.error(errors.toString());
            if (warnings.length() > 0) return ValidationResult.warning(warnings.toString());
            return ValidationResult.ok();
        }

        // Getter/Setter
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getPreferredTechnology() { return preferredTechnology; }
        public void setPreferredTechnology(String tech) { this.preferredTechnology = tech; }

        public String getQualityMode() { return qualityMode; }
        public void setQualityMode(String mode) { this.qualityMode = mode; }

        public float getRenderScale() { return renderScale; }
        public void setRenderScale(float scale) { this.renderScale = scale; }

        public float getSharpening() { return sharpening; }
        public void setSharpening(float sharpening) { this.sharpening = sharpening; }
    }

    /**
     * 帧生成配置 - 控制 DLSS-FG / FSR-FG 行为
     *
     * @since 5.1.0
     */
    public static final class FrameGenerationConfig {

        /** 是否启用帧生成（默认：false，需硬件支持） */
        private boolean enabled = false;

        /** 帧生成模式：dlss-fg | fsr-fg（默认：dlss-fg） */
        private String mode = "dlss-fg";

        /** 目标 FPS（60-240，默认：120） */
        private int targetFPS = 120;

        /**
         * 默认构造函数
         */
        public FrameGenerationConfig() {}

        /**
         * 从 Properties 加载配置
         */
        public static FrameGenerationConfig fromProperties(Properties props) {
            FrameGenerationConfig config = new FrameGenerationConfig();

            config.enabled = Boolean.parseBoolean(
                props.getProperty("interception.postInterceptor.frameGeneration.enabled", "false")
            );
            config.mode = props.getProperty(
                "interception.postInterceptor.frameGeneration.mode", "dlss-fg"
            );
            config.targetFPS = Integer.parseInt(
                props.getProperty("interception.postInterceptor.frameGeneration.targetFPS", "120")
            );

            return config;
        }

        /**
         * 写入 Properties
         */
        public void toProperties(Properties props) {
            props.setProperty("interception.postInterceptor.frameGeneration.enabled", String.valueOf(enabled));
            props.setProperty("interception.postInterceptor.frameGeneration.mode", mode);
            props.setProperty("interception.postInterceptor.frameGeneration.targetFPS", String.valueOf(targetFPS));
        }

        /**
         * 验证配置有效性
         */
        public ValidationResult validate() {
            StringBuilder errors = new StringBuilder();
            StringBuilder warnings = new StringBuilder();

            // 验证帧生成模式
            if (!"dlss-fg".equals(mode) && !"fsr-fg".equals(mode)) {
                errors.append("mode must be 'dlss-fg' or 'fsr-fg'; ");
            }

            // 验证目标 FPS 范围 [60, 240]
            if (targetFPS < 60 || targetFPS > 240) {
                errors.append("targetFPS must be in range [60, 240]; ");
            }

            // 性能警告
            if (enabled && targetFPS > 144) {
                warnings.append("high targetFPS may not be achievable on all hardware; ");
            }

            if (errors.length() > 0) return ValidationResult.error(errors.toString());
            if (warnings.length() > 0) return ValidationResult.warning(warnings.toString());
            return ValidationResult.ok();
        }

        // Getter/Setter
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }

        public int getTargetFPS() { return targetFPS; }
        public void setTargetFPS(int fps) { this.targetFPS = fps; }
    }

    /**
     * 后处理配置（拦截层专用）- 简化的后处理效果配置
     *
     * <p>独立于主配置的 {@link EffectsConfig}，
     * 专门用于后拦截层的轻量级后处理。
     *
     * @since 5.1.0
     */
    public static final class PostProcessingConfig {

        /** Bloom 配置 */
        private BloomConfig bloom = new BloomConfig();

        /** DOF（景深）配置 */
        private DOFConfig dof = new DOFConfig();

        /** 运动模糊配置 */
        private MotionBlurConfig motionBlur = new MotionBlurConfig();

        /**
         * 默认构造函数
         */
        public PostProcessingConfig() {}

        /**
         * 从 Properties 加载配置
         */
        public static PostProcessingConfig fromProperties(Properties props) {
            PostProcessingConfig config = new PostProcessingConfig();

            config.bloom = BloomConfig.fromProperties(props);
            config.dof = DOFConfig.fromProperties(props);
            config.motionBlur = MotionBlurConfig.fromProperties(props);

            return config;
        }

        /**
         * 写入 Properties
         */
        public void toProperties(Properties props) {
            bloom.toProperties(props);
            dof.toProperties(props);
            motionBlur.toProperties(props);
        }

        /**
         * 验证配置有效性
         */
        public ValidationResult validate() {
            StringBuilder errors = new StringBuilder();

            // 验证 Bloom 强度范围
            if (bloom.intensity < 0.0f || bloom.intensity > 2.0f) {
                errors.append("bloom intensity must be in range [0.0, 2.0]; ");
            }

            // 验证运动模糊强度范围
            if (motionBlur.intensity < 0.0f || motionBlur.intensity > 1.0f) {
                errors.append("motionBlur intensity must be in range [0.0, 1.0]; ");
            }

            if (errors.length() > 0) return ValidationResult.error(errors.toString());
            return ValidationResult.ok();
        }

        // Getter/Setter
        public BloomConfig getBloom() { return bloom; }
        public void setBloom(BloomConfig bloom) { this.bloom = bloom; }

        public DOFConfig getDof() { return dof; }
        public void setDof(DOFConfig dof) { this.dof = dof; }

        public MotionBlurConfig getMotionBlur() { return motionBlur; }
        public void setMotionBlur(MotionBlurConfig motionBlur) { this.motionBlur = motionBlur; }

        /**
         * Bloom 子配置
         */
        public static final class BloomConfig {
            boolean enabled = true;
            float intensity = 0.5f;

            public static BloomConfig fromProperties(Properties props) {
                BloomConfig config = new BloomConfig();
                config.enabled = Boolean.parseBoolean(
                    props.getProperty("interception.postInterceptor.postProcessing.bloom.enabled", "true")
                );
                config.intensity = Float.parseFloat(
                    props.getProperty("interception.postInterceptor.postProcessing.bloom.intensity", "0.5")
                );
                return config;
            }

            public void toProperties(Properties props) {
                props.setProperty("interception.postInterceptor.postProcessing.bloom.enabled", String.valueOf(enabled));
                props.setProperty("interception.postInterceptor.postProcessing.bloom.intensity", String.valueOf(intensity));
            }

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public float getIntensity() { return intensity; }
            public void setIntensity(float intensity) { this.intensity = intensity; }
        }

        /**
         * DOF（景深）子配置
         */
        public static final class DOFConfig {
            boolean enabled = false;

            public static DOFConfig fromProperties(Properties props) {
                DOFConfig config = new DOFConfig();
                config.enabled = Boolean.parseBoolean(
                    props.getProperty("interception.postInterceptor.postProcessing.dof.enabled", "false")
                );
                return config;
            }

            public void toProperties(Properties props) {
                props.setProperty("interception.postInterceptor.postProcessing.dof.enabled", String.valueOf(enabled));
            }

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
        }

        /**
         * 运动模糊子配置
         */
        public static final class MotionBlurConfig {
            boolean enabled = true;
            float intensity = 0.3f;

            public static MotionBlurConfig fromProperties(Properties props) {
                MotionBlurConfig config = new MotionBlurConfig();
                config.enabled = Boolean.parseBoolean(
                    props.getProperty("interception.postInterceptor.postProcessing.motionBlur.enabled", "true")
                );
                config.intensity = Float.parseFloat(
                    props.getProperty("interception.postInterceptor.postProcessing.motionBlur.intensity", "0.3")
                );
                return config;
            }

            public void toProperties(Properties props) {
                props.setProperty("interception.postInterceptor.postProcessing.motionBlur.enabled", String.valueOf(enabled));
                props.setProperty("interception.postInterceptor.postProcessing.motionBlur.intensity", String.valueOf(intensity));
            }

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public float getIntensity() { return intensity; }
            public void setIntensity(float intensity) { this.intensity = intensity; }
        }
    }

    /**
     * 模组输出重定向器配置
     *
     * <p>专门控制模组渲染输出的截取和重定向行为。
     *
     * @since 5.1.0
     */
    public static final class ModOutputRedirectConfig {

        /** 是否启用模组输出重定向（默认：true） */
        private boolean enabled = true;

        /** 支持的模组版本列表（默认：["0.5.x", "0.6.x"]） */
        private String[] supportedVersions = {"0.5.x", "0.6.x"};

        /** 回退模式：safe | disable（默认：safe） */
        private String fallbackMode = "safe";

        /**
         * 默认构造函数
         */
        public ModOutputRedirectConfig() {}

        /**
         * 从 Properties 加载配置
         */
        public static ModOutputRedirectConfig fromProperties(Properties props) {
            ModOutputRedirectConfig config = new ModOutputRedirectConfig();

            config.enabled = Boolean.parseBoolean(
                props.getProperty("interception.modOutputRedirect.enabled", "true")
            );

            // 解析支持的版本列表
            String versionsStr = props.getProperty(
                "interception.modOutputRedirect.supportedVersions", "0.5.x,0.6.x"
            );
            try {
                String[] parts = versionsStr.split(",");
                config.supportedVersions = new String[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    config.supportedVersions[i] = parts[i].trim();
                }
            } catch (Exception e) {
                config.supportedVersions = new String[]{"0.5.x", "0.6.x"};
            }

            config.fallbackMode = props.getProperty(
                "interception.modOutputRedirect.fallbackMode", "safe"
            );

            return config;
        }

        /**
         * 写入 Properties
         */
        public void toProperties(Properties props) {
            props.setProperty("interception.modOutputRedirect.enabled", String.valueOf(enabled));

            // 将版本列表转换为逗号分隔字符串
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < supportedVersions.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(supportedVersions[i]);
            }
            props.setProperty("interception.modOutputRedirect.supportedVersions", sb.toString());

            props.setProperty("interception.modOutputRedirect.fallbackMode", fallbackMode);
        }

        /**
         * 验证配置有效性
         */
        public ValidationResult validate() {
            StringBuilder errors = new StringBuilder();

            // 验证回退模式
            if (!"safe".equals(fallbackMode) && !"disable".equals(fallbackMode)) {
                errors.append("fallbackMode must be 'safe' or 'disable'; ");
            }

            if (errors.length() > 0) return ValidationResult.error(errors.toString());
            return ValidationResult.ok();
        }

        // Getter/Setter
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String[] getSupportedVersions() { return supportedVersions; }
        public void setSupportedVersions(String[] versions) { this.supportedVersions = versions; }

        public String getFallbackMode() { return fallbackMode; }
        public void setFallbackMode(String mode) { this.fallbackMode = mode; }
    }

    // ==================== 配置验证结果 ====================

    /**
     * 配置验证结果
     *
     * <p>用于返回配置验证的结果，包括错误、警告和成功三种状态。
     */
    public static final class ValidationResult {

        /** 验证级别 */
        public final Level level;

        /** 验证消息 */
        public final String message;

        /** 验证级别枚举 */
        public enum Level {
            /** 验证通过 */
            OK,
            /** 验证通过但有警告 */
            WARNING,
            /** 验证失败 */
            ERROR
        }

        private ValidationResult(Level level, String message) {
            this.level = level;
            this.message = message;
        }

        /**
         * 创建成功的验证结果
         *
         * @return 验证结果
         */
        public static ValidationResult ok() {
            return new ValidationResult(Level.OK, "");
        }

        /**
         * 创建带警告的验证结果
         *
         * @param message 警告消息
         * @return 验证结果
         */
        public static ValidationResult warning(String message) {
            return new ValidationResult(Level.WARNING, message);
        }

        /**
         * 创建失败的验证结果
         *
         * @param message 错误消息
         * @return 验证结果
         */
        public static ValidationResult error(String message) {
            return new ValidationResult(Level.ERROR, message);
        }

        /**
         * 检查验证是否成功（无错误）
         *
         * @return true 如果验证通过（OK 或 WARNING）
         */
        public boolean isOk() {
            return level != Level.ERROR;
        }

        /**
         * 检查是否有警告
         *
         * @return true 如果有警告
         */
        public boolean hasWarnings() {
            return level == Level.WARNING;
        }

        /**
         * 检查是否有错误
         *
         * @return true 如果有错误
         */
        public boolean hasErrors() {
            return level == Level.ERROR;
        }
    }

    // ==================== 配置变更标识符 (v5.2 新增) ====================

    /**
     * 配置项标识符枚举 - 用于标识具体变更的配置项
     * <p>
     * 每个枚举值对应一个或一组相关的配置项，
     * 在调用 {@link ConfigChangeListener#onConfigChanged(RenderiumConfig, Identifier)} 时使用。
     *
     * @see ConfigChangeListener
     * @since 5.2.0
     */
    public enum Identifier {
        /** 超分辨率技术类型（technology） */
        TECHNOLOGY,
        /** 超分辨率质量等级（quality） */
        QUALITY,
        /** 帧生成启用状态（frameGenerationEnabled） */
        FRAME_GENERATION_ENABLED,
        /** 帧生成模式（frameGenMode） */
        FRAME_GEN_MODE,
        /** Reflex 启用状态（reflexEnabled） */
        REFLEX_ENABLED,
        /** Reflex 模式（reflexMode） */
        REFLEX_MODE,
        /** 锐化强度（sharpening） */
        SHARPENING,
        /** Streamline SDK 路径（streamlineSdkPath） */
        STREAMLINE_SDK_PATH,
        /** 模组启用状态（enabled） */
        ENABLED,
        /** 调试模式（debugMode） */
        DEBUG_MODE,
        /** 超分辨率启用状态（superResolutionEnabled） */
        SUPER_RESOLUTION_ENABLED,
        /** 动态分辨率缩放（dynamicResolution） */
        DYNAMIC_RESOLUTION,
        /** 运行模式（mode） */
        MODE,
        /** 相邻面剔除（neighborFaceCullingEnabled） */
        NEIGHBOR_FACE_CULLING,
        /** 背面剔除（backfaceCullingEnabled） */
        BACKFACE_CULLING,
        /** 视锥体剔除（frustumCullingEnabled） */
        FRUSTUM_CULLING,
        /** 遮挡剔除（occlusionCullingEnabled） */
        OCCLUSION_CULLING,
        /** 批量渲染（batchingEnabled） */
        BATCHING,
        /** 实例化渲染（instancingEnabled） */
        INSTANCING,
        /** 实体轮廓绘制（drawOutlineEnabled） */
        DRAW_OUTLINE,
        /** 调试覆盖层（debugOverlayEnabled） */
        DEBUG_OVERLAY,
        /** 垂直同步（vsyncEnabled） */
        VSYNC,
        /** 雾效（fogEnabled） */
        FOG,
        /** 云彩（cloudsEnabled） */
        CLOUDS,
        /** 暗角效果（vignetteEnabled） */
        VIGNETTE,
        /** 后处理（postProcessingEnabled） */
        POST_PROCESSING,
        /** 阴影（shadowEnabled） */
        SHADOW,
        /** 隐藏流体剔除（hiddenFluidCulling）- v5.3 新增 */
        HIDDEN_FLUID_CULLING,
        /** 改进流体塑形（improvedFluidShaping）- v5.3 新增 */
        IMPROVED_FLUID_SHAPING,
        /** 帧图优化启用状态（frameGraphOptimizationEnabled） */
        FRAME_GRAPH_OPTIMIZATION,
        /** Vulkan 命令优化启用状态（vulkanCommandOptimizationEnabled） */
        VULKAN_COMMAND_OPTIMIZATION,
        /** 内存优化启用状态（memoryOptimizationEnabled） */
        MEMORY_OPTIMIZATION,
        /** 着色器管线优化启用状态（shaderPipelineOptimizationEnabled） */
        SHADER_PIPELINE_OPTIMIZATION,
        /** 帧图详细配置（frameGraphConfig） */
        FRAME_GRAPH_CONFIG,
        /** Vulkan 命令详细配置（vulkanCommandConfig） */
        VULKAN_COMMAND_CONFIG,
        /** 内存优化详细配置（memoryConfig） */
        MEMORY_CONFIG,
        /** 着色器管线详细配置（shaderPipelineConfig） */
        SHADER_PIPELINE_CONFIG,
        /** 后处理效果总开关（effectsEnabled） */
        EFFECTS_ENABLED,
        /** 后处理效果详细配置（effectsConfig） */
        EFFECTS_CONFIG,
        /** VMA 激进优化启用状态（vmaEnhancementEnabled） */
        VMA_ENHANCEMENT,
        /** VMA 详细配置（vmaConfig） */
        VMA_CONFIG,
        /** 激进 MC 优化启用状态（aggressiveOptimizationEnabled） */
        AGGRESSIVE_OPTIMIZATION,
        /** 激进优化详细配置（aggressiveConfig） */
        AGGRESSIVE_CONFIG,
        /** 现代 Render 架构启用状态（modernRenderArchitectureEnabled） */
        MODERN_RENDER_ARCHITECTURE,
        /** 现代架构详细配置（modernConfig） */
        MODERN_CONFIG,
        /** GPU 变换与合并启用状态（gpuTransformMergingEnabled） */
        GPU_TRANSFORM_MERGING,
        /** GPU 变换与合并详细配置（transformConfig） */
        TRANSFORM_CONFIG,
        /** 拦截层配置（interceptionConfig） */
        INTERCEPTION_CONFIG,
        /** 算法加速路径配置（algorithmConfig）- v5.4 新增 */
        ALGORITHM_CONFIG
    }

    // ==================== 配置变更监听器接口 (v5.2 新增) ====================

    /**
     * 配置变更监听器函数式接口
     * <p>
     * 用于监听 {@link RenderiumConfig} 中任何配置项的变更。
     * 当通过 setter 方法修改配置时，会自动通知所有注册的监听器。
     * <p>
     * <b>线程安全性：</b>
     * <ul>
     *   <li>监听器的 onConfigChanged 方法可能在任意线程被调用</li>
     *   <li>实现方应保证方法的线程安全性</li>
     *   <li>不建议在回调中执行耗时操作</li>
     * </ul>
     *
     * <h3>使用示例：</h3>
     * <pre>{@code
     * config.addChangeListener((cfg, id) -> {
     *     System.out.println("配置项变更: " + id);
     *     if (cfg.isDirty()) {
     *         cfg.save(configDir);
     *     }
     * });
     * }</pre>
     *
     * @see RenderiumConfig#addChangeListener(ConfigChangeListener)
     * @see RenderiumConfig#removeChangeListener(ConfigChangeListener)
     * @see Identifier
     * @since 5.2.0
     */
    @FunctionalInterface
    public interface ConfigChangeListener {
        /**
         * 配置变更回调方法
         *
         * @param config         发生变更的配置实例（即 this）
         * @param changedOption  变更的配置项标识符，标识具体是哪个配置项发生了变化
         */
        void onConfigChanged(RenderiumConfig config, Identifier changedOption);
    }

    // ==================== 脏标记管理方法 (v5.2 新增) ====================

    /**
     * 标记配置为脏状态（有未保存的修改）
     * <p>
     * 此方法会：
     * <ol>
     *   <li>将 dirty 标记设置为 true（volatile 写）</li>
     *   <li>通知所有已注册的监听器</li>
     * </ol>
     * <p>
     * <b>性能特征：</b>目标 &lt; 50ns（单监听器场景）
     * <p>
     * <b>线程安全：</b>volatile 写保证可见性，无锁操作
     *
     * @param changedOption 变更的配置项标识符，用于通知监听器具体是哪项配置发生了变化
     * @see #isDirty()
     * @see #markSaved()
     * @see #notifyListeners(Identifier)
     * @since 5.2.0
     */
    public void markDirty(Identifier changedOption) {
        this.dirty = true;
        notifyListeners(changedOption);
    }

    /**
     * 标记配置为已保存状态（清除脏标记）
     * <p>
     * 通常在成功调用 {@link #save(Path)} 后调用此方法。
     * <p>
     * <b>性能特征：</b>&lt; 10ns（单次 volatile 写）
     * <p>
     * <b>线程安全：</b>volatile 写保证可见性
     *
     * @see #isDirty()
     * @see #markDirty(Identifier)
     * @since 5.2.0
     */
    public void markSaved() {
        this.dirty = false;
    }

    /**
     * 检查配置是否有未保存的修改
     * <p>
     * 用于判断是否需要执行增量保存操作。
     * <p>
     * <b>性能特征：</b>&lt; 10ns（单次 volatile 读）
     * <p>
     * <b>线程安全：</b>volatile 读保证可见性
     *
     * @return true 如果存在未保存的修改，false 如果配置已是最新状态
     * @see #markDirty(Identifier)
     * @see #markSaved()
     * @since 5.2.0
     */
    public boolean isDirty() {
        return this.dirty;
    }

    // ==================== 监听器管理方法 (v5.2 新增) ====================

    /**
     * 线程安全地添加配置变更监听器
     * <p>
     * 使用 volatile snapshot 模式实现无锁读取：
     * <ul>
     *   <li>创建新的数组副本（长度+1）</li>
     *   <li>将新监听器追加到副本末尾</li>
     *   <li>原子性地替换 volatile 引用</li>
     * </ul>
     * <p>
     * <b>性能特征：</b>O(n) 数组复制，其中 n 为当前监听器数量
     * <p>
     * <b>重复检测：</b>如果监听器已存在，不会重复添加
     *
     * @param listener 要添加的配置变更监听器，不能为 null
     * @throws IllegalArgumentException 如果 listener 为 null
     * @see #removeChangeListener(ConfigChangeListener)
     * @see ConfigChangeListener
     * @since 5.2.0
     */
    public void addChangeListener(ConfigChangeListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }

        synchronized (this) {
            // 检查是否已存在，避免重复添加
            ConfigChangeListener[] current = this.listeners;
            for (ConfigChangeListener existing : current) {
                if (existing == listener) {
                    return; // 已存在，直接返回
                }
            }

            // 创建新数组并添加监听器
            ConfigChangeListener[] updated = new ConfigChangeListener[current.length + 1];
            System.arraycopy(current, 0, updated, 0, current.length);
            updated[current.length] = listener;

            // 原子性替换
            this.listeners = updated;
        }
    }

    /**
     * 线程安全地移除配置变更监听器
     * <p>
     * 使用 volatile snapshot 模式实现无锁读取：
     * <ul>
     *   <li>创建新的数组副本（长度-1 或不变）</li>
     *   <li>跳过要移除的监听器</li>
     *   <li>原子性地替换 volatile 引用</li>
     * </ul>
     * <p>
     * <b>性能特征：</b>O(n) 数组复制，其中 n 为当前监听器数量
     *
     * @param listener 要移除的配置变更监听器，不能为 null
     * @throws IllegalArgumentException 如果 listener 为 null
     * @return true 如果成功找到并移除，false 如果未找到
     * @see #addChangeListener(ConfigChangeListener)
     * @since 5.2.0
     */
    public boolean removeChangeListener(ConfigChangeListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }

        synchronized (this) {
            ConfigChangeListener[] current = this.listeners;

            // 查找要移除的监听器索引
            int index = -1;
            for (int i = 0; i < current.length; i++) {
                if (current[i] == listener) {
                    index = i;
                    break;
                }
            }

            if (index == -1) {
                return false; // 未找到
            }

            // 创建新数组（长度-1）
            ConfigChangeListener[] updated = new ConfigChangeListener[current.length - 1];
            System.arraycopy(current, 0, updated, 0, index);
            System.arraycopy(current, index + 1, updated, index, current.length - index - 1);

            // 原子性替换
            this.listeners = updated;
            return true;
        }
    }

    /**
     * 通知所有已注册的配置变更监听器
     * <p>
     * 此方法是内部方法，由 {@link #markDirty(Identifier)} 自动调用。
     * 使用 volatile snapshot 模式实现无锁遍历：
     * <ol>
     *   <li>获取当前监听器数组的快照引用（volatile 读）</li>
     *   <li>遍历快照数组，逐个调用监听器</li>
     * </ol>
     * <p>
     * <b>重要特性：</b>
     * <ul>
     *   <li>快照一致性：遍历时即使其他线程添加/移除监听器，也不会影响当前遍历</li>
     *   <li>异常隔离：某个监听器抛出异常不会影响其他监听器的执行</li>
     *   <li>顺序保证：按注册顺序通知</li>
     * </ul>
     * <p>
     * <b>性能特征：</b>目标 &lt; 100ns（单监听器场景）
     * <p>
     * <b>注意：</b>此方法设计为内部使用，但也可以在需要手动触发通知的场景下调用。
     *
     * @param changedOption 变更的配置项标识符
     * @see #markDirty(Identifier)
     * @see ConfigChangeListener#onConfigChanged(RenderiumConfig, Identifier)
     * @since 5.2.0
     */
    void notifyListeners(Identifier changedOption) {
        // 获取快照引用（volatile 读）
        final ConfigChangeListener[] snapshot = this.listeners;

        // 遍历快照，逐个通知监听器
        for (ConfigChangeListener listener : snapshot) {
            try {
                listener.onConfigChanged(this, changedOption);
            } catch (Exception e) {
                LOGGER.warning("Config change listener threw exception: " + e.getMessage());
            }
        }
    }

    // ==================== 热路径快照管理 (v5.3 新增) ====================

    /**
     * 获取热路径配置快照（无锁读取）
     *
     * <p>此方法专为渲染循环等热路径设计：
     * <ul>
     *   <li><b>O(1)</b> volatile 读 + 字段访问</li>
     *   <li><b>&lt; 5ns</b> 单次调用开销</li>
     *   <li><b>线程安全</b>：返回的快照是不可变的，可安全跨线程使用</li>
     * </ul>
     *
     * <h4>使用示例（热路径）：</h4>
     * <pre>{@code
     * // 渲染循环中
     * RenderiumConfigSnapshot snap = RenderiumConfig.getSnapshot();
     * if (snap.superResolutionEnabled) {
     *     // 执行超分辨率处理...
     * }
     * }</pre>
     *
     * @return 当前配置快照（非 null）
     * @see #commitSnapshot()
     * @since 5.3.0
     */
    public static RenderiumConfigSnapshot getSnapshot() {
        RenderiumConfigSnapshot snap = snapshot;
        if (snap == null) {
            // 首次访问时延迟初始化
            snap = getInstance().createSnapshot();
            snapshot = snap;
        }
        return snap;
    }

    /**
     * 提交配置变更并更新快照（冷路径调用）
     *
     * <p>当 UI 或配置加载修改了配置项后，
     * 调用此方法将变更同步到热路径快照。
     * <p>
     * <b>性能特征：</b>~100ns（创建新对象 + volatile 写）
     * <p>
     * <b>调用时机：</b>
     * <ul>
     *   <li>UI 设置项变更后</li>
     *   <li>配置文件加载后</li>
     *   <li>命令行参数应用后</li>
     * </ul>
     *
     * <h4>使用示例（冷路径）：</h4>
     * <pre>{@code
     * // UI 回调中
     * config.setSuperResolutionEnabled(true);
     * config.commitSnapshot(); // 同步到热路径
     * }</pre>
     *
     * @since 5.3.0
     */
    public void commitSnapshot() {
        this.snapshot = createSnapshot();
    }

    /**
     * 从当前配置状态创建新的不可变快照
     *
     * <p>此方法是内部方法，由 {@link #commitSnapshot()} 和
     * {@link #getSnapshot()} 延迟初始化时调用。
     * <p>
     * <b>实现细节：</b>
     * <ul>
     *   <li>使用 Builder 模式从当前配置复制所有热路径字段</li>
     *   <li>枚举类型转换为 ordinal 以减少内存占用</li>
     *   <li>生成的快照对象是不可变的（final 字段）</li>
     * </ul>
     *
     * @return 新的配置快照实例
     * @since 5.3.0
     */
    private RenderiumConfigSnapshot createSnapshot() {
        return new RenderiumConfigSnapshot.Builder()
            .fromConfig(this)
            .build();
    }

    // ==================== 算法加速路径配置内部类 (v5.4 新增) ====================

    /**
     * 算法加速路径配置 - 控制 Java/C++ 双路径调度
     *
     * <p>此配置类管理所有与算法路径选择相关的设置，
     * 包括 GPU 占用率阈值、强制模式、各算法的独立开关等。
     *
     * <h3>配置示例：</h3>
     * <pre>
     * # Properties 格式示例：
     *     algorithm.gpuUsageThreshold=0.7
     *     algorithm.forceMode=auto
     *     algorithm.bfs.enabled=true
     *     algorithm.bfs.preferNative=true
     * </pre>
     *
     * @since 5.4.0
     */
    public static final class AlgorithmConfig {

        /** GPU 占用率阈值（0.0-1.0，低于此值时优先使用 Native 路径） */
        private float gpuUsageThreshold = 0.7f;

        /** 强制模式: auto | java | native */
        private String forceMode = "auto";

        /** BFS 算法配置 */
        private BFSAlgorithmConfig bfs = new BFSAlgorithmConfig();

        /** LOD 算法配置 */
        private LODAlgorithmConfig lod = new LODAlgorithmConfig();

        /** Kahan 累加器配置 */
        private KahanAlgorithmConfig kahan = new KahanAlgorithmConfig();

        /**
         * 默认构造函数
         */
        public AlgorithmConfig() {}

        /**
         * 从 Properties 加载算法配置
         *
         * @param props 属性集合，键前缀为 "algorithm."
         * @return AlgorithmConfig 实例
         */
        public static AlgorithmConfig fromProperties(Properties props) {
            AlgorithmConfig config = new AlgorithmConfig();

            config.gpuUsageThreshold = Float.parseFloat(
                props.getProperty("algorithm.gpuUsageThreshold", "0.7")
            );
            config.forceMode = props.getProperty("algorithm.forceMode", "auto");
            config.bfs = BFSAlgorithmConfig.fromProperties(props);
            config.lod = LODAlgorithmConfig.fromProperties(props);
            config.kahan = KahanAlgorithmConfig.fromProperties(props);

            return config;
        }

        /**
         * 将配置写入 Properties
         *
         * @param props 属性集合
         */
        public void toProperties(Properties props) {
            props.setProperty("algorithm.gpuUsageThreshold", String.valueOf(gpuUsageThreshold));
            props.setProperty("algorithm.forceMode", forceMode);
            bfs.toProperties(props);
            lod.toProperties(props);
            kahan.toProperties(props);
        }

        /**
         * 验证配置有效性
         *
         * @return 验证结果
         */
        public ValidationResult validate() {
            StringBuilder errors = new StringBuilder();

            if (gpuUsageThreshold < 0.0f || gpuUsageThreshold > 1.0f) {
                errors.append("gpuUsageThreshold must be in range [0.0, 1.0]; ");
            }

            if (!"auto".equals(forceMode) && !"java".equals(forceMode) && !"native".equals(forceMode)) {
                errors.append("forceMode must be 'auto', 'java', or 'native'; ");
            }

            ValidationResult bfsResult = bfs.validate();
            if (bfsResult.hasErrors()) {
                errors.append("bfs: ").append(bfsResult.message).append("; ");
            }

            ValidationResult lodResult = lod.validate();
            if (lodResult.hasErrors()) {
                errors.append("lod: ").append(lodResult.message).append("; ");
            }

            if (errors.length() > 0) return ValidationResult.error(errors.toString());
            return ValidationResult.ok();
        }

        // ==================== Getter/Setter ====================

        public float getGpuUsageThreshold() { return gpuUsageThreshold; }
        public void setGpuUsageThreshold(float threshold) {
            this.gpuUsageThreshold = Math.max(0.0f, Math.min(1.0f, threshold));
        }

        public String getForceMode() { return forceMode; }
        public void setForceMode(String mode) {
            if ("auto".equals(mode) || "java".equals(mode) || "native".equals(mode)) {
                this.forceMode = mode;
            }
        }

        /** 是否强制使用 Java 路径 */
        public boolean isForceJava() { return "java".equals(forceMode); }
        /** 是否强制使用 Native 路径 */
        public boolean isForceNative() { return "native".equals(forceMode); }
        /** 是否自动选择 */
        public boolean isAutoMode() { return "auto".equals(forceMode); }

        public BFSAlgorithmConfig getBfs() { return bfs; }
        public void setBfs(BFSAlgorithmConfig config) { this.bfs = config; }

        public LODAlgorithmConfig getLod() { return lod; }
        public void setLod(LODAlgorithmConfig config) { this.lod = config; }

        public KahanAlgorithmConfig getKahan() { return kahan; }
        public void setKahan(KahanAlgorithmConfig config) { this.kahan = config; }

        // ==================== 子配置类 ====================

        /**
         * BFS 遮挡剔除算法配置
         */
        public static final class BFSAlgorithmConfig {

            /** 是否启用 BFS 加速（默认：true） */
            private boolean enabled = true;

            /** 是否优先使用 Native 路径（默认：true） */
            private boolean preferNative = true;

            /** 最大区块数（默认：8192） */
            private int maxSections = 8192;

            public static BFSAlgorithmConfig fromProperties(Properties props) {
                BFSAlgorithmConfig config = new BFSAlgorithmConfig();
                config.enabled = Boolean.parseBoolean(
                    props.getProperty("algorithm.bfs.enabled", "true")
                );
                config.preferNative = Boolean.parseBoolean(
                    props.getProperty("algorithm.bfs.preferNative", "true")
                );
                config.maxSections = Integer.parseInt(
                    props.getProperty("algorithm.bfs.maxSections", "8192")
                );
                return config;
            }

            public void toProperties(Properties props) {
                props.setProperty("algorithm.bfs.enabled", String.valueOf(enabled));
                props.setProperty("algorithm.bfs.preferNative", String.valueOf(preferNative));
                props.setProperty("algorithm.bfs.maxSections", String.valueOf(maxSections));
            }

            public ValidationResult validate() {
                if (maxSections <= 0 || maxSections > 65536) {
                    return ValidationResult.error("maxSections must be in range [1, 65536]");
                }
                return ValidationResult.ok();
            }

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }

            public boolean isPreferNative() { return preferNative; }
            public void setPreferNative(boolean prefer) { this.preferNative = prefer; }

            public int getMaxSections() { return maxSections; }
            public void setMaxSections(int max) { this.maxSections = Math.max(1, Math.min(65536, max)); }
        }

        /**
         * LOD 距离计算算法配置
         */
        public static final class LODAlgorithmConfig {

            /** 是否启用 LOD Native 加速（默认：true） */
            private boolean enabled = true;

            /** 是否优先使用 Native 路径（默认：true） */
            private boolean preferNative = true;

            /** 最大 LOD 等级数（默认：4） */
            private int maxLevels = 4;

            public static LODAlgorithmConfig fromProperties(Properties props) {
                LODAlgorithmConfig config = new LODAlgorithmConfig();
                config.enabled = Boolean.parseBoolean(
                    props.getProperty("algorithm.lod.enabled", "true")
                );
                config.preferNative = Boolean.parseBoolean(
                    props.getProperty("algorithm.lod.preferNative", "true")
                );
                config.maxLevels = Integer.parseInt(
                    props.getProperty("algorithm.lod.maxLevels", "4")
                );
                return config;
            }

            public void toProperties(Properties props) {
                props.setProperty("algorithm.lod.enabled", String.valueOf(enabled));
                props.setProperty("algorithm.lod.preferNative", String.valueOf(preferNative));
                props.setProperty("algorithm.lod.maxLevels", String.valueOf(maxLevels));
            }

            public ValidationResult validate() {
                if (maxLevels < 2 || maxLevels > 8) {
                    return ValidationResult.error("maxLevels must be in range [2, 8]");
                }
                return ValidationResult.ok();
            }

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }

            public boolean isPreferNative() { return preferNative; }
            public void setPreferNative(boolean prefer) { this.preferNative = prefer; }

            public int getMaxLevels() { return maxLevels; }
            public void setMaxLevels(int levels) { this.maxLevels = Math.max(2, Math.min(8, levels)); }
        }

        /**
         * Kahan 高精度累加器配置
         */
        public static final class KahanAlgorithmConfig {

            /** 是否启用 Kahan Native 加速（默认：true） */
            private boolean enabled = true;

            /** 是否优先使用 Native 路径（默认：true） */
            private boolean preferNative = true;

            /** 批量操作大小（默认：256） */
            private int batchSize = 256;

            public static KahanAlgorithmConfig fromProperties(Properties props) {
                KahanAlgorithmConfig config = new KahanAlgorithmConfig();
                config.enabled = Boolean.parseBoolean(
                    props.getProperty("algorithm.kahan.enabled", "true")
                );
                config.preferNative = Boolean.parseBoolean(
                    props.getProperty("algorithm.kahan.preferNative", "true")
                );
                config.batchSize = Integer.parseInt(
                    props.getProperty("algorithm.kahan.batchSize", "256")
                );
                return config;
            }

            public void toProperties(Properties props) {
                props.setProperty("algorithm.kahan.enabled", String.valueOf(enabled));
                props.setProperty("algorithm.kahan.preferNative", String.valueOf(preferNative));
                props.setProperty("algorithm.kahan.batchSize", String.valueOf(batchSize));
            }

            public ValidationResult validate() {
                if (batchSize <= 0 || batchSize > 1024) {
                    return ValidationResult.error("batchSize must be in range [1, 1024]");
                }
                return ValidationResult.ok();
            }

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }

            public boolean isPreferNative() { return preferNative; }
            public void setPreferNative(boolean prefer) { this.preferNative = prefer; }

            public int getBatchSize() { return batchSize; }
            public void setBatchSize(int size) { this.batchSize = Math.max(1, Math.min(1024, size)); }
        }
    }

    // ==================== Getter/Setter: 算法加速路径配置 ====================

    /**
     * 获取算法加速路径配置对象（v5.4 新增）
     *
     * @return AlgorithmConfig 实例，包含完整的算法路径调度配置
     * @since 5.4.0
     */
    public AlgorithmConfig getAlgorithmConfig() { return algorithmConfig; }

    /**
     * 设置算法加速路径配置对象（v5.4 新增）
     *
     * @param config 算法配置实例
     * @since 5.4.0
     */
    public void setAlgorithmConfig(AlgorithmConfig config) {
        this.algorithmConfig = config;
        markDirty(Identifier.ALGORITHM_CONFIG);
    }
}
