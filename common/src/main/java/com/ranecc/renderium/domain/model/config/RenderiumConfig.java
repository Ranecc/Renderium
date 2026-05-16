package com.ranecc.renderium.domain.model.config;
import com.ranecc.renderium.domain.model.config.RenderiumConfig;

import com.ranecc.renderium.domain.constant.ConfigConstants;
import com.ranecc.renderium.domain.enums.QualityLevel;
import com.ranecc.renderium.domain.enums.RenderiumMode;
import com.ranecc.renderium.domain.enums.SRTechnology;
import com.ranecc.renderium.tech.framegen.FrameGenMode;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Renderium 配置聚合根（Domain Layer）
 *
 * <p>作为 DDD 聚合根，封装所有渲染优化相关的配置项，
 * 保证配置数据的一致性和完整性。
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li>管理算法加速路径配置（GPU阈值、BFS/LOD/Kahan 开关）</li>
 *   <li>管理运行模式和质量等级</li>
 *   <li>提供配置验证不变式（validate()）</li>
 *   <li>支持配置持久化（委托给 ConfigManager）</li>
 * </ul>
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>零 net.minecraft.* 依赖（纯领域模型）</li>
 *   <li>所有默认值来源于 {@link ConfigConstants}</li>
 *   <li>load/save 方法标记为 @Deprecated，应委托给 ConfigManager</li>
 * </ul>
 *
 * @see com.ranecc.renderium.infrastructure.config.ConfigManager
 * @see ConfigConstants
 * @since 1.1.0
 */
public final class RenderiumConfig {

    private static final Logger LOGGER = Logger.getLogger(RenderiumConfig.class.getName());

    /** 配置文件名 */
    public static final String CONFIG_FILE = "renderium.properties";

    // ==================== 运行模式配置 ====================

    /** 当前运行模式（默认：COMPATIBILITY） */
    private RenderiumMode mode = RenderiumMode.COMPATIBILITY;

    /** 质量等级（默认：HIGH） */
    private QualityLevel qualityLevel = QualityLevel.HIGH;

    /** 是否启用模组（默认：true） */
    private boolean enabled = true;

    // ==================== 算法加速路径配置 ====================

    /**
     * 算法路径配置（内部聚合）
     *
     * <p>控制 Java/C++ 双路径的调度策略：
     * <ul>
     *   <li>GPU 占用率阈值（决定何时使用 Native 路径）</li>
     *   <li>强制模式（调试/测试用）</li>
     *   <li>BFS/LOD/Kahan 各算法的独立开关</li>
     * </ul>
     */
    private AlgorithmConfig algorithmConfig = new AlgorithmConfig();

    // ==================== 渲染参数配置 ====================

    /** FOV 视场角（默认值来自 ConfigConstants.DEFAULT_FOV） */
    private float fov = ConfigConstants.DEFAULT_FOV;

    /** 近裁剪面距离（默认值来自 ConfigConstants.DEFAULT_NEAR_PLANE） */
    private float nearPlane = ConfigConstants.DEFAULT_NEAR_PLANE;

    /** 远裁剪面距离（默认值来自 ConfigConstants.DEFAULT_FAR_PLANE） */
    private float farPlane = ConfigConstants.DEFAULT_FAR_PLANE;

    /** 窗口宽度（默认值来自 ConfigConstants.DEFAULT_WINDOW_WIDTH） */
    private int windowWidth = ConfigConstants.DEFAULT_WINDOW_WIDTH;

    /** 窗口高度（默认值来自 ConfigConstants.DEFAULT_WINDOW_HEIGHT） */
    private int windowHeight = ConfigConstants.DEFAULT_WINDOW_HEIGHT;

    // ==================== 超分辨率配置 ====================

    /** 超分辨率技术类型 */
    private SRTechnology srTechnology = SRTechnology.AUTO;

    /** 是否启用超分辨率 */
    private boolean superResolutionEnabled = false;

    /** 是否启用帧生成 */
    private boolean frameGenerationEnabled = false;

    /** 帧生成模式 */
    private FrameGenMode frameGenMode = FrameGenMode.OFF;

    /** 是否启用 Reflex 低延迟 */
    private boolean reflexEnabled = false;

    /** Reflex 低延迟模式 */
    private ReflexMode reflexMode = ReflexMode.OFF;

    // ==================== 剔除优化配置 ====================

    /** 是否启用遮挡剔除 */
    private boolean occlusionCullingEnabled = true;

    /** 是否启用视锥体剔除 */
    private boolean frustumCullingEnabled = true;

    /** 是否启用背面剔除 */
    private boolean backfaceCullingEnabled = true;

    // ==================== 性能调优配置 ====================

    /** 是否启用批量渲染 */
    private boolean batchingEnabled = true;

    /** 是否启用实例化渲染 */
    private boolean instancingEnabled = true;

    /** GPU 使用率阈值（默认值来自 ConfigConstants.DEFAULT_GPU_USAGE_THRESHOLD） */
    private float gpuUsageThreshold = ConfigConstants.DEFAULT_GPU_USAGE_THRESHOLD;

    // ==================== 构造函数 ====================

    /**
     * 默认构造函数 - 使用所有默认值
     */
    public RenderiumConfig() {}

    // ==================== 拦截层配置 ====================

    private InterceptionConfig interceptionConfig = new InterceptionConfig();

    public InterceptionConfig getInterceptionConfig() { return interceptionConfig; }

    // ==================== 子模块配置（Blaze3D 优化模块使用） ====================

    private ShaderPipelineConfig shaderPipelineConfig = new ShaderPipelineConfig();
    private VmaConfig vmaConfig = new VmaConfig();
    private AggressiveConfig aggressiveConfig = new AggressiveConfig();
    private ModernConfig modernConfig = new ModernConfig();
    private TransformConfig transformConfig = new TransformConfig();
    private FrameGraphConfig frameGraphConfig = new FrameGraphConfig();
    private VulkanCommandConfig vulkanCommandConfig = new VulkanCommandConfig();
    private MemoryConfig memoryConfig = new MemoryConfig();

    public ShaderPipelineConfig getShaderPipelineConfig() { return shaderPipelineConfig; }
    public VmaConfig getVmaConfig() { return vmaConfig; }
    public AggressiveConfig getAggressiveConfig() { return aggressiveConfig; }
    public ModernConfig getModernConfig() { return modernConfig; }
    public TransformConfig getTransformConfig() { return transformConfig; }
    public FrameGraphConfig getFrameGraphConfig() { return frameGraphConfig; }
    public VulkanCommandConfig getVulkanCommandConfig() { return vulkanCommandConfig; }
    public MemoryConfig getMemoryConfig() { return memoryConfig; }

    public boolean isVmaEnhancementEnabled() { return vmaConfig != null; }
    public boolean isFrameGraphOptimizationEnabled() { return frameGraphConfig != null; }
    public boolean isVulkanCommandOptimizationEnabled() { return vulkanCommandConfig != null; }
    public boolean isMemoryOptimizationEnabled() { return memoryConfig != null; }
    public boolean isShaderPipelineOptimizationEnabled() { return shaderPipelineConfig != null; }

    public String getProperty(String key, String defaultValue) { return defaultValue; }

    // ==================== 验证不变式 ====================

    /**
     * 验证配置值的合法性（DDD 不变式）
     *
     * <p>检查所有配置项是否在合法范围内，包括：
     * <ul>
     *   <li>GPU 阈值范围 [MIN_GPU_THRESHOLD, MAX_GPU_THRESHOLD]</li>
     *   <li>FOV 范围 [30, 150]</li>
     *   <li>LOD 级别范围 [1, LOD_MAX_LEVELS]</li>
     *   <li>算法配置子对象验证</li>
     * </ul>
     *
     * @return ValidationResult 验证结果（包含错误/警告列表）
     * @see ConfigValidator
     */
    public ValidationResult validate() {
        StringBuilder errors = new StringBuilder();
        StringBuilder warnings = new StringBuilder();

        // 验证 GPU 使用率阈值范围
        if (gpuUsageThreshold < ConfigConstants.MIN_GPU_THRESHOLD ||
            gpuUsageThreshold > ConfigConstants.MAX_GPU_THRESHOLD) {
            errors.append(String.format(
                "gpuUsageThreshold must be in range [%.2f, %.2f]; ",
                ConfigConstants.MIN_GPU_THRESHOLD,
                ConfigConstants.MAX_GPU_THRESHOLD
            ));
        }

        // 验证 FOV 范围 [30, 150]
        if (fov < 30.0f || fov > 150.0f) {
            errors.append("fov must be in range [30, 150]; ");
        }

        // 验证近/远裁剪面
        if (nearPlane <= 0) {
            errors.append("nearPlane must be > 0; ");
        }
        if (farPlane <= nearPlane) {
            errors.append("farPlane must be > nearPlane; ");
        }

        // 验证窗口尺寸
        if (windowWidth <= 0 || windowHeight <= 0) {
            errors.append("windowWidth and windowHeight must be > 0; ");
        }

        // 验证算法配置
        ValidationResult algoResult = algorithmConfig.validate();
        if (algoResult.hasErrors()) {
            errors.append("algorithmConfig: ").append(algoResult.getErrorMessage()).append("; ");
        }
        if (algoResult.hasWarnings()) {
            warnings.append("algorithmConfig: ").append(algoResult.getWarningMessage()).append("; ");
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

    // ==================== 持久化方法（已废弃，应委托 ConfigManager） ====================

    /**
     * 从文件加载配置（已废弃）
     *
     * @param configPath 配置文件路径
     * @return 加载的配置实例
     * @deprecated 应使用 {@link com.ranecc.renderium.infrastructure.config.ConfigManager#load(String)}
     */
    @Deprecated
    public static RenderiumConfig load(String configPath) {
        RenderiumConfig config = new RenderiumConfig();
        Path path = Path.of(configPath).resolve(CONFIG_FILE);

        if (!Files.isRegularFile(path)) {
            LOGGER.info("Config file not found, using defaults: " + path);
            return config;
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            Properties props = new Properties();
            props.load(reader);
            config.loadFromProperties(props);
        } catch (IOException e) {
            LOGGER.warning("Failed to load config from " + path + ": " + e.getMessage());
        }

        return config;
    }

    /**
     * 保存配置到文件（已废弃）
     *
     * @param configPath 配置目录路径
     * @deprecated 应使用 {@link com.ranecc.renderium.infrastructure.config.ConfigManager#save(RenderiumConfig, String)}
     */
    @Deprecated
    public void save(String configPath) {
        Path configFile = Path.of(configPath).resolve(CONFIG_FILE);

        try {
            Files.createDirectories(configFile.getParent());
        } catch (IOException e) {
            LOGGER.warning("Failed to create config directory: " + e.getMessage());
            return;
        }

        Properties props = new Properties();
        saveToProperties(props);

        try (Writer writer = Files.newBufferedWriter(configFile)) {
            props.store(writer, "Renderium Configuration v1.1");
        } catch (IOException e) {
            LOGGER.warning("Failed to save config to " + configFile + ": " + e.getMessage());
        }
    }

    // ==================== Properties 序列化辅助方法 ====================

    /**
     * 从 Properties 对象加载配置
     *
     * @param props 属性集合
     */
    public void loadFromProperties(Properties props) {
        // 加载运行模式
        mode = parseEnum(
            props.getProperty("renderium.mode", "COMPATIBILITY"),
            RenderiumMode.class,
            RenderiumMode.COMPATIBILITY
        );

        // 加载质量等级
        qualityLevel = parseEnum(
            props.getProperty("renderium.qualityLevel", "HIGH"),
            QualityLevel.class,
            QualityLevel.HIGH
        );

        // 加载布尔开关
        enabled = Boolean.parseBoolean(props.getProperty("renderium.enabled", "true"));
        superResolutionEnabled = Boolean.parseBoolean(
            props.getProperty("renderium.superResolution.enabled", "false")
        );
        occlusionCullingEnabled = Boolean.parseBoolean(
            props.getProperty("renderium.occlusionCulling.enabled", "true")
        );
        frustumCullingEnabled = Boolean.parseBoolean(
            props.getProperty("renderium.frustumCulling.enabled", "true")
        );
        backfaceCullingEnabled = Boolean.parseBoolean(
            props.getProperty("renderium.backfaceCulling.enabled", "true")
        );
        batchingEnabled = Boolean.parseBoolean(
            props.getProperty("renderium.batching.enabled", "true")
        );
        instancingEnabled = Boolean.parseBoolean(
            props.getProperty("renderium.instancing.enabled", "true")
        );

        // 加载浮点数参数
        fov = Float.parseFloat(props.getProperty("renderium.fov",
            String.valueOf(ConfigConstants.DEFAULT_FOV)));
        nearPlane = Float.parseFloat(props.getProperty("renderium.nearPlane",
            String.valueOf(ConfigConstants.DEFAULT_NEAR_PLANE)));
        farPlane = Float.parseFloat(props.getProperty("renderium.farPlane",
            String.valueOf(ConfigConstants.DEFAULT_FAR_PLANE)));
        gpuUsageThreshold = Float.parseFloat(props.getProperty("renderium.gpuUsageThreshold",
            String.valueOf(ConfigConstants.DEFAULT_GPU_USAGE_THRESHOLD)));

        // 加载整数参数
        windowWidth = Integer.parseInt(props.getProperty("renderium.windowWidth",
            String.valueOf(ConfigConstants.DEFAULT_WINDOW_WIDTH)));
        windowHeight = Integer.parseInt(props.getProperty("renderium.windowHeight",
            String.valueOf(ConfigConstants.DEFAULT_WINDOW_HEIGHT)));

        // 加载超分辨率技术
        srTechnology = parseEnum(
            props.getProperty("renderium.srTechnology", "AUTO"),
            SRTechnology.class,
            SRTechnology.AUTO
        );

        // 加载算法配置
        algorithmConfig = AlgorithmConfig.fromProperties(props);
    }

    /**
     * 将配置写入 Properties 对象
     *
     * @param props 属性集合
     */
    public void saveToProperties(Properties props) {
        // 保存运行模式
        props.setProperty("renderium.mode", mode.name());
        props.setProperty("renderium.qualityLevel", qualityLevel.name());

        // 保存布尔开关
        props.setProperty("renderium.enabled", String.valueOf(enabled));
        props.setProperty("renderium.superResolution.enabled", String.valueOf(superResolutionEnabled));
        props.setProperty("renderium.occlusionCulling.enabled", String.valueOf(occlusionCullingEnabled));
        props.setProperty("renderium.frustumCulling.enabled", String.valueOf(frustumCullingEnabled));
        props.setProperty("renderium.backfaceCulling.enabled", String.valueOf(backfaceCullingEnabled));
        props.setProperty("renderium.batching.enabled", String.valueOf(batchingEnabled));
        props.setProperty("renderium.instancing.enabled", String.valueOf(instancingEnabled));

        // 保存浮点数参数
        props.setProperty("renderium.fov", String.valueOf(fov));
        props.setProperty("renderium.nearPlane", String.valueOf(nearPlane));
        props.setProperty("renderium.farPlane", String.valueOf(farPlane));
        props.setProperty("renderium.gpuUsageThreshold", String.valueOf(gpuUsageThreshold));

        // 保存整数参数
        props.setProperty("renderium.windowWidth", String.valueOf(windowWidth));
        props.setProperty("renderium.windowHeight", String.valueOf(windowHeight));

        // 保存超分辨率技术
        props.setProperty("renderium.srTechnology", srTechnology.name());

        // 保存算法配置
        algorithmConfig.toProperties(props);
    }

    // ==================== 安全枚举解析 ====================

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
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(enumClass, value);
        } catch (IllegalArgumentException e) {
            LOGGER.warning("Invalid enum value '" + value + "' for " + enumClass.getSimpleName()
                         + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    // ==================== Getter/Setter: 运行模式配置 ====================

    /** 获取当前运行模式 */
    public RenderiumMode getMode() { return mode; }

    /** 设置运行模式 */
    public void setMode(RenderiumMode mode) {
        this.mode = mode;
    }

    /** 获取质量等级 */
    public QualityLevel getQualityLevel() { return qualityLevel; }

    /** 设置质量等级 */
    public void setQualityLevel(QualityLevel qualityLevel) {
        this.qualityLevel = qualityLevel;
    }

    /** 是否启用模组 */
    public boolean isEnabled() { return enabled; }

    /** 设置模组启用状态 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // ==================== Getter/Setter: 算法配置 ====================

    /** 获取算法路径配置 */
    public AlgorithmConfig getAlgorithmConfig() { return algorithmConfig; }

    /** 设置算法路径配置 */
    public void setAlgorithmConfig(AlgorithmConfig algorithmConfig) {
        this.algorithmConfig = algorithmConfig;
    }

    // ==================== Getter/Setter: 渲染参数 ====================

    /** 获取 FOV 视场角 */
    public float getFov() { return fov; }

    /** 设置 FOV 视场角 */
    public void setFov(float fov) { this.fov = fov; }

    /** 获取近裁剪面距离 */
    public float getNearPlane() { return nearPlane; }

    /** 设置近裁剪面距离 */
    public void setNearPlane(float nearPlane) { this.nearPlane = nearPlane; }

    /** 获取远裁剪面距离 */
    public float getFarPlane() { return farPlane; }

    /** 设置远裁剪面距离 */
    public void setFarPlane(float farPlane) { this.farPlane = farPlane; }

    /** 获取窗口宽度 */
    public int getWindowWidth() { return windowWidth; }

    /** 设置窗口宽度 */
    public void setWindowWidth(int windowWidth) { this.windowWidth = windowWidth; }

    /** 获取窗口高度 */
    public int getWindowHeight() { return windowHeight; }

    /** 设置窗口高度 */
    public void setWindowHeight(int windowHeight) { this.windowHeight = windowHeight; }

    // ==================== Getter/Setter: 超分辨率 ====================

    /** 获取超分辨率技术类型 */
    public SRTechnology getSrTechnology() { return srTechnology; }

    /** 设置超分辨率技术类型 */
    public void setSrTechnology(SRTechnology srTechnology) { this.srTechnology = srTechnology; }

    /** 是否启用超分辨率 */
    public boolean isSuperResolutionEnabled() { return superResolutionEnabled; }

    /** 设置超分辨率启用状态 */
    public void setSuperResolutionEnabled(boolean superResolutionEnabled) {
        this.superResolutionEnabled = superResolutionEnabled;
    }

    // ==================== Getter/Setter: 帧生成 ====================

    /** 是否启用帧生成 */
    public boolean isFrameGenerationEnabled() { return frameGenerationEnabled; }

    /** 设置帧生成启用状态 */
    public void setFrameGenerationEnabled(boolean frameGenerationEnabled) {
        this.frameGenerationEnabled = frameGenerationEnabled;
    }

    /** 获取帧生成模式 */
    public FrameGenMode getFrameGenMode() { return frameGenMode; }

    /** 设置帧生成模式 */
    public void setFrameGenMode(FrameGenMode frameGenMode) {
        this.frameGenMode = frameGenMode;
    }

    // ==================== Getter/Setter: Reflex 低延迟 ====================

    /** 是否启用 Reflex 低延迟 */
    public boolean isReflexEnabled() { return reflexEnabled; }

    /** 设置 Reflex 启用状态 */
    public void setReflexEnabled(boolean reflexEnabled) {
        this.reflexEnabled = reflexEnabled;
    }

    /** 获取 Reflex 模式 */
    public ReflexMode getReflexMode() { return reflexMode; }

    /** 设置 Reflex 模式 */
    public void setReflexMode(ReflexMode reflexMode) {
        this.reflexMode = reflexMode;
    }

    // ==================== Getter/Setter: 剔除优化 ====================

    /** 是否启用遮挡剔除 */
    public boolean isOcclusionCullingEnabled() { return occlusionCullingEnabled; }

    /** 设置遮挡剔除启用状态 */
    public void setOcclusionCullingEnabled(boolean occlusionCullingEnabled) {
        this.occlusionCullingEnabled = occlusionCullingEnabled;
    }

    /** 是否启用视锥体剔除 */
    public boolean isFrustumCullingEnabled() { return frustumCullingEnabled; }

    /** 设置视锥体剔除启用状态 */
    public void setFrustumCullingEnabled(boolean frustumCullingEnabled) {
        this.frustumCullingEnabled = frustumCullingEnabled;
    }

    /** 是否启用背面剔除 */
    public boolean isBackfaceCullingEnabled() { return backfaceCullingEnabled; }

    /** 设置背面剔除启用状态 */
    public void setBackfaceCullingEnabled(boolean backfaceCullingEnabled) {
        this.backfaceCullingEnabled = backfaceCullingEnabled;
    }

    // ==================== Getter/Setter: 性能调优 ====================

    /** 是否启用批量渲染 */
    public boolean isBatchingEnabled() { return batchingEnabled; }

    /** 设置批量渲染启用状态 */
    public void setBatchingEnabled(boolean batchingEnabled) { this.batchingEnabled = batchingEnabled; }

    /** 是否启用实例化渲染 */
    public boolean isInstancingEnabled() { return instancingEnabled; }

    /** 设置实例化渲染启用状态 */
    public void setInstancingEnabled(boolean instancingEnabled) {
        this.instancingEnabled = instancingEnabled;
    }

    /** 获取 GPU 使用率阈值 */
    public float getGpuUsageThreshold() { return gpuUsageThreshold; }

    /**
     * 设置 GPU 使用率阈值
     *
     * @param threshold 阈值（会被钳制到 [MIN_GPU_THRESHOLD, MAX_GPU_THRESHOLD] 范围）
     */
    public void setGpuUsageThreshold(float threshold) {
        this.gpuUsageThreshold = Math.max(ConfigConstants.MIN_GPU_THRESHOLD,
            Math.min(ConfigConstants.MAX_GPU_THRESHOLD, threshold));
    }

    // ==================== AlgorithmConfig 内部类 ====================

    /**
     * 算法加速路径配置（内部聚合）
     *
     * <p>控制 Java/C++ 双路径调度策略，包括：
     * <ul>
     *   <li>GPU 占用率阈值（决定何时切换到 Native 路径）</li>
     *   <li>强制模式（auto/java/native，用于调试和测试）</li>
     *   <li>BFS/LOD/Kahan 各算法的独立开关和偏好设置</li>
     * </ul>
     *
     * <h3>配置示例（Properties 格式）：</h3>
     * <pre>
     * algorithm.gpuUsageThreshold=0.7
     * algorithm.forceMode=auto
     * algorithm.bfs.enabled=true
     * algorithm.bfs.preferNative=true
     * algorithm.lod.enabled=true
     * algorithm.kahan.enabled=true
     * </pre>
     *
     * @since 1.1.0
     */
    public static final class AlgorithmConfig {

        /** GPU 占用率阈值（默认值来自 ConfigConstants.DEFAULT_GPU_USAGE_THRESHOLD） */
        private float gpuUsageThreshold = ConfigConstants.DEFAULT_GPU_USAGE_THRESHOLD;

        /** 强制模式: auto | java | native */
        private String forceMode = "auto";

        /** BFS 算法配置 */
        private BFSConfig bfs = new BFSConfig();

        /** LOD 算法配置 */
        private LODConfig lod = new LODConfig();

        /** Kahan 累加器配置 */
        private KahanConfig kahan = new KahanConfig();

        /**
         * 默认构造函数 - 使用所有默认值
         */
        public AlgorithmConfig() {}

        /**
         * 从 Properties 对象加载算法配置
         *
         * @param props 属性集合，键前缀为 "algorithm."
         * @return AlgorithmConfig 实例
         */
        public static AlgorithmConfig fromProperties(Properties props) {
            AlgorithmConfig config = new AlgorithmConfig();

            config.gpuUsageThreshold = Float.parseFloat(
                props.getProperty("algorithm.gpuUsageThreshold",
                    String.valueOf(ConfigConstants.DEFAULT_GPU_USAGE_THRESHOLD))
            );
            config.forceMode = props.getProperty("algorithm.forceMode", "auto");
            config.bfs = BFSConfig.fromProperties(props);
            config.lod = LODConfig.fromProperties(props);
            config.kahan = KahanConfig.fromProperties(props);

            return config;
        }

        /**
         * 将配置写入 Properties 对象
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
         * 验证算法配置的有效性
         *
         * @return ValidationResult 验证结果
         */
        public ValidationResult validate() {
            StringBuilder errors = new StringBuilder();
            StringBuilder warnings = new StringBuilder();

            // 验证 GPU 阈值范围
            if (gpuUsageThreshold < ConfigConstants.MIN_GPU_THRESHOLD ||
                gpuUsageThreshold > ConfigConstants.MAX_GPU_THRESHOLD) {
                errors.append(String.format(
                    "gpuUsageThreshold must be in range [%.2f, %.2f]; ",
                    ConfigConstants.MIN_GPU_THRESHOLD,
                    ConfigConstants.MAX_GPU_THRESHOLD
                ));
            }

            // 验证强制模式
            if (!"auto".equals(forceMode) && !"java".equals(forceMode) && !"native".equals(forceMode)) {
                errors.append("forceMode must be 'auto', 'java', or 'native'; ");
            }

            // 验证子配置
            ValidationResult bfsResult = bfs.validate();
            if (bfsResult.hasErrors()) {
                errors.append("bfs: ").append(bfsResult.getErrorMessage()).append("; ");
            }
            if (bfsResult.hasWarnings()) {
                warnings.append("bfs: ").append(bfsResult.getWarningMessage()).append("; ");
            }

            ValidationResult lodResult = lod.validate();
            if (lodResult.hasErrors()) {
                errors.append("lod: ").append(lodResult.getErrorMessage()).append("; ");
            }
            if (lodResult.hasWarnings()) {
                warnings.append("lod: ").append(lodResult.getWarningMessage()).append("; ");
            }

            ValidationResult kahanResult = kahan.validate();
            if (kahanResult.hasErrors()) {
                errors.append("kahan: ").append(kahanResult.getErrorMessage()).append("; ");
            }
            if (kahanResult.hasWarnings()) {
                warnings.append("kahan: ").append(kahanResult.getWarningMessage()).append("; ");
            }

            if (errors.length() > 0) {
                return ValidationResult.error(errors.toString());
            }
            if (warnings.length() > 0) {
                return ValidationResult.warning(warnings.toString());
            }
            return ValidationResult.ok();
        }

        // ==================== Getter/Setter: GPU 阈值和强制模式 ====================

        /** 获取 GPU 占用率阈值 */
        public float getGpuUsageThreshold() { return gpuUsageThreshold; }

        /**
         * 设置 GPU 占用率阈值
         *
         * @param threshold 阈值（会被钳制到合法范围）
         */
        public void setGpuUsageThreshold(float threshold) {
            this.gpuUsageThreshold = Math.max(ConfigConstants.MIN_GPU_THRESHOLD,
                Math.min(ConfigConstants.MAX_GPU_THRESHOLD, threshold));
        }

        /** 获取强制模式 */
        public String getForceMode() { return forceMode; }

        /** 设置强制模式 */
        public void setForceMode(String mode) {
            if ("auto".equals(mode) || "java".equals(mode) || "native".equals(mode)) {
                this.forceMode = mode;
            }
        }

        /** 是否强制使用 Java 路径 */
        public boolean isForceJava() { return "java".equals(forceMode); }

        /** 是否强制使用 Native 路径 */
        public boolean isForceNative() { return "native".equals(forceMode); }

        /** 是否自动选择路径 */
        public boolean isAutoMode() { return "auto".equals(forceMode); }

        // ==================== Getter/Setter: 子配置 ====================

        /** 获取 BFS 配置 */
        public BFSConfig getBfs() { return bfs; }

        /** 设置 BFS 配置 */
        public void setBfs(BFSConfig bfs) { this.bfs = bfs; }

        /** 获取 LOD 配置 */
        public LODConfig getLod() { return lod; }

        /** 设置 LOD 配置 */
        public void setLod(LODConfig lod) { this.lod = lod; }

        /** 获取 Kahan 配置 */
        public KahanConfig getKahan() { return kahan; }

        /** 设置 Kahan 配置 */
        public void setKahan(KahanConfig kahan) { this.kahan = kahan; }

        // ==================== BFS 配置内部类 ====================

        /**
         * BFS 遮挡剔除算法配置
         */
        public static final class BFSConfig {

            /** 是否启用 BFS 加速（默认：true） */
            private boolean enabled = true;

            /** 是否优先使用 Native 路径（默认：true） */
            private boolean preferNative = true;

            /** 最大区块数（默认值来自 ConfigConstants.DEFAULT_BFS_MAX_NODES） */
            private int maxSections = ConfigConstants.DEFAULT_BFS_MAX_NODES;

            /**
             * 默认构造函数
             */
            public BFSConfig() {}

            /**
             * 从 Properties 加载配置
             */
            public static BFSConfig fromProperties(Properties props) {
                BFSConfig config = new BFSConfig();
                config.enabled = Boolean.parseBoolean(
                    props.getProperty("algorithm.bfs.enabled", "true")
                );
                config.preferNative = Boolean.parseBoolean(
                    props.getProperty("algorithm.bfs.preferNative", "true")
                );
                config.maxSections = Integer.parseInt(
                    props.getProperty("algorithm.bfs.maxSections",
                        String.valueOf(ConfigConstants.DEFAULT_BFS_MAX_NODES))
                );
                return config;
            }

            /**
             * 写入 Properties
             */
            public void toProperties(Properties props) {
                props.setProperty("algorithm.bfs.enabled", String.valueOf(enabled));
                props.setProperty("algorithm.bfs.preferNative", String.valueOf(preferNative));
                props.setProperty("algorithm.bfs.maxSections", String.valueOf(maxSections));
            }

            /**
             * 验证配置有效性
             */
            public ValidationResult validate() {
                if (maxSections <= 0 || maxSections > 65536) {
                    return ValidationResult.error("maxSections must be in range [1, 65536]");
                }
                return ValidationResult.ok();
            }

            /** 是否启用 BFS */
            public boolean isEnabled() { return enabled; }

            /** 设置 BFS 启用状态 */
            public void setEnabled(boolean enabled) { this.enabled = enabled; }

            /** 是否优先使用 Native 路径 */
            public boolean isPreferNative() { return preferNative; }

            /** 设置 Native 路径偏好 */
            public void setPreferNative(boolean preferNative) { this.preferNative = preferNative; }

            /** 获取最大区块数 */
            public int getMaxSections() { return maxSections; }

            /**
             * 设置最大区块数
             *
             * @param max 最大区块数（会被钳制到 [1, 65536] 范围）
             */
            public void setMaxSections(int max) {
                this.maxSections = Math.max(1, Math.min(65536, max));
            }
        }

        // ==================== LOD 配置内部类 ====================

        /**
         * LOD 距离计算算法配置
         */
        public static final class LODConfig {

            /** 是否启用 LOD Native 加速（默认：true） */
            private boolean enabled = true;

            /** 是否优先使用 Native 路径（默认：true） */
            private boolean preferNative = true;

            /** 最大 LOD 等级数（默认值来自 ConfigConstants.LOD_MAX_LEVELS） */
            private int maxLevels = ConfigConstants.LOD_MAX_LEVELS;

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
                    props.getProperty("algorithm.lod.enabled", "true")
                );
                config.preferNative = Boolean.parseBoolean(
                    props.getProperty("algorithm.lod.preferNative", "true")
                );
                config.maxLevels = Integer.parseInt(
                    props.getProperty("algorithm.lod.maxLevels",
                        String.valueOf(ConfigConstants.LOD_MAX_LEVELS))
                );
                return config;
            }

            /**
             * 写入 Properties
             */
            public void toProperties(Properties props) {
                props.setProperty("algorithm.lod.enabled", String.valueOf(enabled));
                props.setProperty("algorithm.lod.preferNative", String.valueOf(preferNative));
                props.setProperty("algorithm.lod.maxLevels", String.valueOf(maxLevels));
            }

            /**
             * 验证配置有效性
             */
            public ValidationResult validate() {
                if (maxLevels < 1 || maxLevels > ConfigConstants.LOD_MAX_LEVELS) {
                    return ValidationResult.error(String.format(
                        "maxLevels must be in range [1, %d]", ConfigConstants.LOD_MAX_LEVELS
                    ));
                }
                return ValidationResult.ok();
            }

            /** 是否启用 LOD */
            public boolean isEnabled() { return enabled; }

            /** 设置 LOD 启用状态 */
            public void setEnabled(boolean enabled) { this.enabled = enabled; }

            /** 是否优先使用 Native 路径 */
            public boolean isPreferNative() { return preferNative; }

            /** 设置 Native 路径偏好 */
            public void setPreferNative(boolean preferNative) { this.preferNative = preferNative; }

            /** 获取最大 LOD 等级数 */
            public int getMaxLevels() { return maxLevels; }

            /**
             * 设置最大 LOD 等级数
             *
             * @param levels 等级数（会被钳制到 [1, LOD_MAX_LEVELS] 范围）
             */
            public void setMaxLevels(int levels) {
                this.maxLevels = Math.max(1, Math.min(ConfigConstants.LOD_MAX_LEVELS, levels));
            }
        }

        // ==================== Kahan 配置内部类 ====================

        /**
         * Kahan 高精度累加器配置
         */
        public static final class KahanConfig {

            /** 是否启用 Kahan Native 加速（默认：true） */
            private boolean enabled = true;

            /** 是否优先使用 Native 路径（默认：true） */
            private boolean preferNative = true;

            /** 批量操作大小（默认：256） */
            private int batchSize = 256;

            /**
             * 默认构造函数
             */
            public KahanConfig() {}

            /**
             * 从 Properties 加载配置
             */
            public static KahanConfig fromProperties(Properties props) {
                KahanConfig config = new KahanConfig();
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

            /**
             * 写入 Properties
             */
            public void toProperties(Properties props) {
                props.setProperty("algorithm.kahan.enabled", String.valueOf(enabled));
                props.setProperty("algorithm.kahan.preferNative", String.valueOf(preferNative));
                props.setProperty("algorithm.kahan.batchSize", String.valueOf(batchSize));
            }

            /**
             * 验证配置有效性
             */
            public ValidationResult validate() {
                if (batchSize <= 0 || batchSize > 1024) {
                    return ValidationResult.error("batchSize must be in range [1, 1024]");
                }
                return ValidationResult.ok();
            }

            /** 是否启用 Kahan */
            public boolean isEnabled() { return enabled; }

            /** 设置 Kahan 启用状态 */
            public void setEnabled(boolean enabled) { this.enabled = enabled; }

            /** 是否优先使用 Native 路径 */
            public boolean isPreferNative() { return preferNative; }

            /** 设置 Native 路径偏好 */
            public void setPreferNative(boolean preferNative) { this.preferNative = preferNative; }

            /** 获取批量操作大小 */
            public int getBatchSize() { return batchSize; }

            /**
             * 设置批量操作大小
             *
             * @param size 批量大小（会被钳制到 [1, 1024] 范围）
             */
            public void setBatchSize(int size) {
                this.batchSize = Math.max(1, Math.min(1024, size));
            }
        }
    }

    // ==================== ValidationResult 内部类 ====================

    /**
     * 配置验证结果
     *
     * <p>用于返回配置验证的结果，包含错误、警告和成功三种状态。
     * 所有验证方法都返回此类型的实例。
     *
     * @since 1.1.0
     */
    public static final class ValidationResult {

        /** 验证级别枚举 */
        public enum Level {
            /** 验证通过 */
            OK,
            /** 验证通过但有警告 */
            WARNING,
            /** 验证失败 */
            ERROR
        }

        /** 验证级别 */
        private final Level level;

        /** 错误消息（level == ERROR 时非空） */
        private final String errorMessage;

        /** 警告消息（level == WARNING 时非空） */
        private final String warningMessage;

        /**
         * 私有构造函数
         *
         * @param level          验证级别
         * @param errorMessage   错误消息
         * @param warningMessage 警告消息
         */
        private ValidationResult(Level level, String errorMessage, String warningMessage) {
            this.level = level;
            this.errorMessage = errorMessage != null ? errorMessage : "";
            this.warningMessage = warningMessage != null ? warningMessage : "";
        }

        /**
         * 创建成功的验证结果
         *
         * @return 验证结果（OK 级别）
         */
        public static ValidationResult ok() {
            return new ValidationResult(Level.OK, "", "");
        }

        /**
         * 创建带警告的验证结果
         *
         * @param message 警告消息
         * @return 验证结果（WARNING 级别）
         */
        public static ValidationResult warning(String message) {
            return new ValidationResult(Level.WARNING, "", message);
        }

        /**
         * 创建失败的验证结果
         *
         * @param message 错误消息
         * @return 验证结果（ERROR 级别）
         */
        public static ValidationResult error(String message) {
            return new ValidationResult(Level.ERROR, message, "");
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

        /**
         * 获取验证级别
         *
         * @return 验证级别（OK/WARNING/ERROR）
         */
        public Level getLevel() { return level; }

        /**
         * 获取错误消息
         *
         * @return 错误消息字符串（可能为空）
         */
        public String getErrorMessage() { return errorMessage; }

        /**
         * 获取警告消息
         *
         * @return 警告消息字符串（可能为空）
         */
        public String getWarningMessage() { return warningMessage; }

        @Override
        public String toString() {
            return switch (level) {
                case OK -> "ValidationResult{OK}";
                case WARNING -> "ValidationResult{WARNING: " + warningMessage + "}";
                case ERROR -> "ValidationResult{ERROR: " + errorMessage + "}";
            };
        }
    }

    /**
     * Reflex 低延迟模式枚举
     */
    public enum ReflexMode {
        OFF,
        LOW_LATENCY,
        LOW_LATENCY_BOOST
    }
}
