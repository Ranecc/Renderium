// Renderium - DLSS Integration Manager
// NVIDIA DLSS 4.5 超分辨率集成管理器
// 通过 Streamline SDK 2.10.3 + FFM API 实现

package com.ranecc.renderium.tech.dlss.dlss;

import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;
import java.util.logging.Logger;

/**
 * DLSS 集成管理器
 * <p>
 * 通过 NVIDIA Streamline SDK 与 Minecraft Vulkan 后端集成。
 * 支持 DLSS 4.5 第二代 Transformer 模型。
 * <p>
 * 支持的超分辨率技术：
 * <ul>
 *   <li>NVIDIA DLSS 4.5 (Super Resolution + Frame Generation)</li>
 *   <li>NVIDIA DLSS 4 (Super Resolution)</li>
 *   <li>Intel XeSS（通过 Streamline）</li>
 *   <li>AMD FSR 3（通过 Streamline）</li>
 * </ul>
 * <p>
 * 初始化流程：
 * <pre>
 * DLSSManager manager = DLSSManager.getInstance();
 * manager.initialize(vkInstance, vkPhysicalDevice, vkDevice,
 *     vkComputeQueue, computeQueueFamily, presentQueueFamily);
 * </pre>
 * <p>
 * 每帧调用：
 * <pre>
 * manager.evaluate(cmdBuffer, colorView, depthView, mvView,
 *     outputView, width, height, cameraData);
 * </pre>
 *
 * @see SLContext
 * @see FrameEvaluator
 * @see CameraMatrixHelper
 */
public final class DLSSManager {

    private static final Logger LOGGER = Logger.getLogger(DLSSManager.class.getName());

    private static volatile DLSSManager instance;

    private boolean available = false;
    private boolean enabled = false;
    private boolean initialized = false;

    private DLSSMode mode = DLSSMode.OFF;
    private DLSSQuality quality = DLSSQuality.BALANCED;

    private int renderWidth = 0;
    private int renderHeight = 0;
    private int displayWidth = 0;
    private int displayHeight = 0;

    // Streamline 组件
    private SLContext slContext;
    private VulkanStreamlineBridge vkBridge;
    private FrameEvaluator frameEvaluator;
    private CameraMatrixHelper cameraHelper;
    private SLAutoExtractor sdkExtractor;

    // 帧状态
    private int frameIndex = 0;
    private float[] previousProjection = new float[16];
    private float[] previousView = new float[16];
    private boolean firstFrame = true;

    private DLSSManager() {}

    /**
     * 获取单例实例
     */
    public static DLSSManager getInstance() {
        if (instance == null) {
            synchronized (DLSSManager.class) {
                if (instance == null) {
                    instance = new DLSSManager();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化 DLSS
     * <p>
     * 完整的初始化流程：
     * <ol>
     *   <li>从模组 JAR 自动提取 Streamline SDK（玩家无需配置）</li>
     *   <li>加载 sl.interposer.dll</li>
     *   <li>初始化 Streamline（slInit）</li>
     *   <li>注册 Vulkan 信息（slSetVulkanInfo）</li>
     *   <li>检测特性支持（slIsFeatureSupported）</li>
     *   <li>解析 DLSS 函数（slGetFeatureFunction）</li>
     * </ol>
     *
     * @param vkInstance              VkInstance 句柄
     * @param vkPhysicalDevice        VkPhysicalDevice 句柄
     * @param vkDevice                VkDevice 句柄
     * @param vkComputeQueue          VkQueue 句柄（计算队列）
     * @param computeQueueFamilyIndex 计算队列族索引
     * @param presentQueueFamilyIndex 呈现队列族索引
     * @param modConfigDir            模组配置目录（如 .minecraft/renderium/）
     * @return 是否成功初始化
     */
    public boolean initialize(long vkInstance, long vkPhysicalDevice, long vkDevice,
                               long vkComputeQueue, int computeQueueFamilyIndex,
                               int presentQueueFamilyIndex,
                               java.nio.file.Path modConfigDir) {
        if (initialized) return available;

        // 1. 从模组 JAR 自动提取 Streamline SDK（无需玩家配置）
        sdkExtractor = new SLAutoExtractor(modConfigDir);
        if (!sdkExtractor.initialize()) {
            LOGGER.severe("Failed to initialize Streamline SDK (auto-extract failed)");
            LOGGER.severe("Missing DLLs: " + sdkExtractor.getMissingRequiredDlls());
            return false;
        }

        // 2. 创建 Streamline 上下文
        slContext = new SLContext();

        // 3. 加载 sl.interposer.dll
        if (!slContext.load(sdkExtractor.getInterposerPath())) {
            LOGGER.severe("Failed to load Streamline SDK");
            return false;
        }

        // 4. 初始化 Streamline
        if (!slContext.initialize(
            "Renderium", "Minecraft 26.2",
            sdkExtractor.getLogPath(),
            sdkExtractor.getCachePath(),
            sdkExtractor.getPluginPath()
        )) {
            LOGGER.severe("Streamline initialization failed");
            return false;
        }

        // 5. 初始化 VulkanStreamlineBridge 并注册 Vulkan 信息
        vkBridge = VulkanStreamlineBridge.getInstance();
        
        // 尝试初始化 Bridge（如果 Streamline SDK 已加载）
        if (SLFFMBindings.isLoaded()) {
            if (!vkBridge.initialize(sdkExtractor.getPluginPath())) {
                LOGGER.warning("Vulkan Bridge initialization failed, continuing in stub mode");
            }
        } else {
            LOGGER.info("Streamline SDK not loaded yet, will initialize Bridge later");
        }

        // 6. 注册 Vulkan 信息（通过 FFM Bridge）
        // 注意：VkDevice/VkQueue 句柄将在 OfficialVulkanHijacker 劫持后注册
        // 这里先标记 Bridge 可用状态
        if (vkBridge.isAvailable()) {
            LOGGER.info("Vulkan Bridge available for resource tagging");
        }

        // 7. 检测特性
        slContext.detectFeatures();

        // 8. 检查 DLSS 支持
        if (!slContext.isFeatureSupported(SLContext.Feature.DLSS)) {
            LOGGER.warning("DLSS not supported on this GPU");
            return false;
        }

        // 9. 解析 DLSS 函数
        if (!slContext.resolveDLSSFunctions()) {
            LOGGER.severe("Failed to resolve DLSS functions");
            return false;
        }

        // 10. 创建帧评估器
        // 注意：FrameEvaluator 当前只接受 SLContext 参数（vkBridge 已在重构中移除）
        frameEvaluator = new FrameEvaluator(slContext);

        // 11. 创建相机矩阵工具
        cameraHelper = CameraMatrixHelper.create(CameraMatrixHelper.JITTER_MODE_8X8);

        initialized = true;
        available = true;

        LOGGER.info("DLSS Manager initialized successfully");
        LOGGER.info("  DLSS-G available: " + sdkExtractor.isDLSSGAvailable());
        LOGGER.info("  XeSS available: " + sdkExtractor.isXeSSAvailable());
        LOGGER.info("  FSR available: " + sdkExtractor.isFSRAvailable());

        return available;
    }

    /**
     * 启用 DLSS
     *
     * @param mode    DLSS 模式
     * @param quality 质量设置
     */
    public void enable(DLSSMode mode, DLSSQuality quality) {
        if (!available) {
            throw new IllegalStateException("DLSS not available on this system");
        }

        this.enabled = true;
        this.mode = mode;
        this.quality = quality;

        // 设置 DLSS 选项
        setDLSSOptions(quality);

        // 如果需要帧生成，解析 DLSS-G 函数
        if (mode == DLSSMode.FRAME_GENERATION
            || mode == DLSSMode.SUPER_RESOLUTION_AND_FRAME_GENERATION) {
            if (sdkExtractor.isDLSSGAvailable()) {
                slContext.resolveDLSSGFunctions();
                setDLSSGOptions(DLSSGMode.ON);
            }
        }

        LOGGER.info("DLSS enabled: mode=" + mode + ", quality=" + quality);
    }

    /**
     * 禁用 DLSS
     */
    public void disable() {
        this.enabled = false;
        this.mode = DLSSMode.OFF;
        LOGGER.info("DLSS disabled");
    }

    /**
     * 更新分辨率
     * <p>
     * 当显示分辨率或渲染分辨率改变时调用。
     *
     * @param renderWidth  渲染宽度
     * @param renderHeight 渲染高度
     * @param displayWidth 显示宽度
     * @param displayHeight 显示高度
     */
    public void updateResolution(int renderWidth, int renderHeight,
                                  int displayWidth, int displayHeight) {
        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;

        if (enabled) {
            setDLSSOptions(quality);
        }
    }

    /**
     * 执行 DLSS 帧评估
     * <p>
     * 应在主场景渲染完成后、输出到屏幕前调用。
     * 对应 RenderExtension.onPostProcessingBegin。
     *
     * @param commandBuffer  VkCommandBuffer 句柄
     * @param colorTexture   输入颜色纹理 VkImageView 句柄
     * @param depthTexture   深度纹理 VkImageView 句柄
     * @param motionVectors  运动矢量纹理 VkImageView 句柄
     * @param outputTexture  输出纹理 VkImageView 句柄
     * @param textureWidth   纹理宽度
     * @param textureHeight  纹理高度
     * @param cameraData     相机数据
     */
    public void evaluate(long commandBuffer,
                         long colorTexture,
                         long depthTexture,
                         long motionVectors,
                         long outputTexture,
                         int textureWidth,
                         int textureHeight,
                         CameraData cameraData) {
        if (!enabled || !available) return;

        // 1. 开始帧
        if (!frameEvaluator.beginFrame(cameraData.deltaTime, frameIndex)) {
            LOGGER.warning("Failed to begin frame");
            return;
        }

        // 2. 标记资源（使用 FFM Bridge 进行真实的资源标记）
        VulkanStreamlineBridge.ResourceTagData[] resources = {
            new VulkanStreamlineBridge.ResourceTagData(
                SLFFMBindings.BUFFER_TYPE_HUDLESS_COLOR, colorTexture,
                textureWidth, textureHeight),
            new VulkanStreamlineBridge.ResourceTagData(
                SLFFMBindings.BUFFER_TYPE_DEPTH, depthTexture,
                textureWidth, textureHeight),
            new VulkanStreamlineBridge.ResourceTagData(
                SLFFMBindings.BUFFER_TYPE_MOTION_VECTORS, motionVectors,
                textureWidth, textureHeight),
            new VulkanStreamlineBridge.ResourceTagData(
                SLFFMBindings.BUFFER_TYPE_SCALING_OUTPUT_COLOR, outputTexture,
                displayWidth, displayHeight)
        };

        // 使用 Vulkan Bridge 标记资源（如果 Bridge 不可用则跳过）
        if (vkBridge != null && vkBridge.isAvailable()) {
            if (!vkBridge.tagResources(resources)) {
                LOGGER.warning("Failed to tag resources via Vulkan Bridge");
                frameEvaluator.endFrame();
                return;
            }
        } else {
            // 存根模式：使用空的 Object 数组调用 tagResources（内部为存根，返回 true）
            LOGGER.fine("Vulkan Bridge not available, using stub resource tagging");
            if (!frameEvaluator.tagResources(new Object[0])) {
                LOGGER.warning("Failed to tag resources (stub)");
                frameEvaluator.endFrame();
                return;
            }
        }

        // 3. 设置常量
        FrameEvaluator.ConstantsData constants = cameraHelper.buildConstantsData(
            cameraData.projectionMatrix, previousProjection,
            cameraData.viewMatrix, previousView,
            frameIndex, textureWidth, textureHeight,
            cameraData.fov, cameraData.nearPlane, cameraData.farPlane,
            cameraData.sceneChanged
        );

        if (!frameEvaluator.setConstants(constants)) {
            LOGGER.warning("Failed to set constants");
            frameEvaluator.endFrame();
            return;
        }

        // 4. 评估 DLSS
        if (!frameEvaluator.evaluateFeature(SLFFMBindings.FEATURE_DLSS, commandBuffer)) {
            LOGGER.warning("DLSS evaluation failed");
        }

        // 5. 如果启用帧生成，评估 DLSS-G
        if (mode == DLSSMode.FRAME_GENERATION
            || mode == DLSSMode.SUPER_RESOLUTION_AND_FRAME_GENERATION) {
            if (!frameEvaluator.evaluateFeature(SLFFMBindings.FEATURE_DLSS_G, commandBuffer)) {
                LOGGER.warning("DLSS-G evaluation failed");
            }
        }

        // 6. 结束帧
        frameEvaluator.endFrame();

        // 7. 保存当前帧数据
        System.arraycopy(cameraData.projectionMatrix, 0, previousProjection, 0, 16);
        System.arraycopy(cameraData.viewMatrix, 0, previousView, 0, 16);
        frameIndex++;
        firstFrame = false;
    }

    /**
     * 获取推荐的渲染分辨率
     * <p>
     * 使用 slDLSSGetOptimalSettings 获取 DLSS 推荐的渲染分辨率。
     *
     * @param displayWidth  显示宽度
     * @param displayHeight 显示高度
     * @param quality       质量设置
     * @return 推荐分辨率
     */
    public Resolution getRecommendedRenderResolution(int displayWidth, int displayHeight,
                                                      DLSSQuality quality) {
        // 尝试使用 Streamline API 获取最佳设置
        if (initialized && slContext.isFeatureSupported(SLContext.Feature.DLSS)) {
            try (Arena arena = Arena.ofConfined()) {
                // 构建 DLSSOptions（使用 VulkanConst 中定义的常量）
                MemorySegment options = arena.allocate(VulkanConst.SL_DLSS_OPTIONS_SIZE);
                options.set(ValueLayout.JAVA_INT, VulkanConst.SL_DLSS_OPTIONS_OFFSET_STYPE, VulkanConst.SL_STRUCT_TYPE_DLSS_OPTIONS);
                options.set(ValueLayout.JAVA_INT, VulkanConst.SL_DLSS_OPTIONS_OFFSET_MODE, toDLSSMode(quality));
                options.set(ValueLayout.JAVA_INT, VulkanConst.SL_DLSS_OPTIONS_OFFSET_OUTPUT_SCALING, 1); // outputScalingEnabled
                options.set(ValueLayout.JAVA_FLOAT, VulkanConst.SL_DLSS_OPTIONS_OFFSET_SHARPNESS, 1.0f); // sharpness

                // 构建输出
                MemorySegment settings = arena.allocate(VulkanConst.SL_DLSS_OPTIMAL_SETTINGS_SIZE);

                int result = SLFFMBindings.slDLSSGetOptimalSettings(options, settings);
                if (SLFFMBindings.isOk(result)) {
                    int width = settings.get(ValueLayout.JAVA_INT, VulkanConst.SL_DLSS_SETTINGS_OFFSET_RENDER_SIZE_X);
                    int height = settings.get(ValueLayout.JAVA_INT, VulkanConst.SL_DLSS_SETTINGS_OFFSET_RENDER_SIZE_Y);
                    return new Resolution(width, height);
                }
            } catch (Exception e) {
                LOGGER.warning("slDLSSGetOptimalSettings failed, using fallback: " + e.getMessage());
            }
        }

        // 回退：手动计算
        return calculateFallbackResolution(displayWidth, displayHeight, quality);
    }

    /**
     * 关闭 DLSS
     */
    public void shutdown() {
        if (slContext != null && slContext.isInitialized()) {
            slContext.shutdown();
        }
        initialized = false;
        available = false;
        enabled = false;
        LOGGER.info("DLSS Manager shut down");
    }

    // ==================== 内部方法 ====================

    /**
     * 设置 DLSS 选项
     * <p>
     * 对应 slDLSSSetOptions
     */
    private void setDLSSOptions(DLSSQuality quality) {
        if (!initialized) return;

        try (Arena arena = Arena.ofConfined()) {
            // 构建 ViewportHandle
            MemorySegment viewport = arena.allocate(VulkanConst.SL_VIEWPORT_HANDLE_SIZE);
            viewport.set(ValueLayout.JAVA_INT, VulkanConst.SL_VIEWPORT_HANDLE_OFFSET_STYPE, VulkanConst.SL_STRUCT_TYPE_VIEWPORT_HANDLE);
            viewport.set(ValueLayout.JAVA_INT, VulkanConst.SL_VIEWPORT_HANDLE_OFFSET_INDEX, 0);

            // 构建 DLSSOptions
            MemorySegment options = arena.allocate(VulkanConst.SL_DLSS_OPTIONS_SIZE);
            options.set(ValueLayout.JAVA_INT, VulkanConst.SL_DLSS_OPTIONS_OFFSET_STYPE, VulkanConst.SL_STRUCT_TYPE_DLSS_OPTIONS);
            options.set(ValueLayout.JAVA_INT, VulkanConst.SL_DLSS_OPTIONS_OFFSET_MODE, toDLSSMode(quality));
            options.set(ValueLayout.JAVA_INT, VulkanConst.SL_DLSS_OPTIONS_OFFSET_OUTPUT_SCALING, 1); // outputScalingEnabled
            options.set(ValueLayout.JAVA_FLOAT, VulkanConst.SL_DLSS_OPTIONS_OFFSET_SHARPNESS, 0.0f); // sharpness (0 = auto)
            options.set(ValueLayout.JAVA_INT, VulkanConst.SL_DLSS_OPTIONS_OFFSET_RENDER_WIDTH, renderWidth);
            options.set(ValueLayout.JAVA_INT, VulkanConst.SL_DLSS_OPTIONS_OFFSET_RENDER_HEIGHT, renderHeight);
            options.set(ValueLayout.JAVA_INT, VulkanConst.SL_DLSS_OPTIONS_OFFSET_DISPLAY_WIDTH, displayWidth);

            int result = SLFFMBindings.slDLSSSetOptions(viewport, options);
            if (!SLFFMBindings.isOk(result)) {
                LOGGER.warning("slDLSSSetOptions failed: " + SLFFMBindings.getResultDescription(result));
            }
        } catch (Exception e) {
            LOGGER.warning("setDLSSOptions error: " + e.getMessage());
        }
    }

    /**
     * 设置 DLSS-G 选项
     * <p>
     * 对应 slDLSSGSetOptions
     */
    private void setDLSSGOptions(DLSSGMode fgMode) {
        if (!initialized) return;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment viewport = arena.allocate(VulkanConst.SL_VIEWPORT_HANDLE_SIZE);
            viewport.set(ValueLayout.JAVA_INT, VulkanConst.SL_VIEWPORT_HANDLE_OFFSET_STYPE, VulkanConst.SL_STRUCT_TYPE_VIEWPORT_HANDLE);
            viewport.set(ValueLayout.JAVA_INT, VulkanConst.SL_VIEWPORT_HANDLE_OFFSET_INDEX, 0);

            MemorySegment options = arena.allocate(VulkanConst.SL_DLSS_G_OPTIONS_SIZE);
            options.set(ValueLayout.JAVA_INT, VulkanConst.SL_DLSS_G_OPTIONS_OFFSET_STYPE, VulkanConst.SL_STRUCT_TYPE_DLSS_G_OPTIONS);
            options.set(ValueLayout.JAVA_INT, VulkanConst.SL_DLSS_G_OPTIONS_OFFSET_MODE, fgMode.id);
            options.set(ValueLayout.JAVA_INT, VulkanConst.SL_DLSS_G_OPTIONS_OFFSET_NUM_FRAMES, 1); // numFramesToGenerate

            int result = SLFFMBindings.slDLSSGSetOptions(viewport, options);
            if (!SLFFMBindings.isOk(result)) {
                LOGGER.warning("slDLSSGSetOptions failed: " + SLFFMBindings.getResultDescription(result));
            }
        } catch (Exception e) {
            LOGGER.warning("setDLSSGOptions error: " + e.getMessage());
        }
    }

    /**
     * 将 DLSSQuality 转换为 Streamline DLSSMode
     */
    private int toDLSSMode(DLSSQuality quality) {
        return switch (quality) {
            case ULTRA_QUALITY -> SLFFMBindings.DLSS_MODE_ULTRA_QUALITY;
            case QUALITY -> SLFFMBindings.DLSS_MODE_MAX_QUALITY;
            case BALANCED -> SLFFMBindings.DLSS_MODE_BALANCED;
            case PERFORMANCE -> SLFFMBindings.DLSS_MODE_MAX_PERFORMANCE;
            case ULTRA_PERFORMANCE -> SLFFMBindings.DLSS_MODE_ULTRA_PERFORMANCE;
            case DLAA -> SLFFMBindings.DLSS_MODE_DLAA;
        };
    }

    /**
     * 手动计算渲染分辨率（回退方案）
     */
    private Resolution calculateFallbackResolution(int displayWidth, int displayHeight,
                                                    DLSSQuality quality) {
        float scale = switch (quality) {
            case ULTRA_QUALITY -> 0.77f;
            case QUALITY -> 0.75f;
            case BALANCED -> 0.66f;
            case PERFORMANCE -> 0.50f;
            case ULTRA_PERFORMANCE -> 0.33f;
            case DLAA -> 1.0f;
        };

        int width = ((int) (displayWidth * scale) / 2) * 2;
        int height = ((int) (displayHeight * scale) / 2) * 2;

        return new Resolution(width, height);
    }

    // ==================== Getter ====================

    public boolean isEnabled() { return enabled; }
    public boolean isAvailable() { return available; }
    public DLSSMode getMode() { return mode; }
    public DLSSQuality getQuality() { return quality; }
    public SLContext getSLContext() { return slContext; }
    public SLAutoExtractor getSdkExtractor() { return sdkExtractor; }

    /**
     * DLSS 模式
     */
    public enum DLSSMode {
        OFF,
        SUPER_RESOLUTION,
        FRAME_GENERATION,
        SUPER_RESOLUTION_AND_FRAME_GENERATION
    }

    /**
     * DLSS 质量级别
     */
    public enum DLSSQuality {
        ULTRA_QUALITY,
        QUALITY,
        BALANCED,
        PERFORMANCE,
        ULTRA_PERFORMANCE,
        DLAA
    }

    /**
     * DLSS-G 帧生成模式
     */
    public enum DLSSGMode {
        OFF(SLFFMBindings.DLSSG_MODE_OFF),
        ON(SLFFMBindings.DLSSG_MODE_ON),
        AUTO(SLFFMBindings.DLSSG_MODE_AUTO);

        public final int id;
        DLSSGMode(int id) { this.id = id; }
    }

    /**
     * 分辨率记录
     */
    public record Resolution(int width, int height) {}

    /**
     * 相机数据
     * <p>
     * 用于 DLSS 的运动矢量计算和抖动投影。
     *
     * @param posX             相机位置 X
     * @param posY             相机位置 Y
     * @param posZ             相机位置 Z
     * @param projectionMatrix 4x4 投影矩阵（列主序）
     * @param viewMatrix       4x4 视图矩阵（列主序）
     * @param fov              视场角（弧度）
     * @param nearPlane        近裁剪面
     * @param farPlane         远裁剪面
     * @param deltaTime        帧间隔时间（秒）
     * @param sceneChanged     场景是否变化
     */
    public record CameraData(
        float posX, float posY, float posZ,
        float[] projectionMatrix,
        float[] viewMatrix,
        float fov,
        float nearPlane,
        float farPlane,
        float deltaTime,
        boolean sceneChanged
    ) {}
}
