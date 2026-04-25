// ============================================================
// StreamlineInitializer - Streamline SDK 初始化器
// ============================================================
// 从 RenderiumCore 中提取的独立组件
// 职责：Streamline SDK 的完整初始化流程
//
// 初始化流程：
//   1. SDK 路径验证与配置加载
//   2. SLContext 创建与加载
//   3. Vulkan Bridge 初始化
//   4. 特性检测与帧评估器创建
//
// @see com.renderium.streamline.SLContext
// @see com.renderium.streamline.VulkanStreamlineBridge
// ============================================================

package com.renderium.core.component;

import com.renderium.config.ConfigConstants;
import com.renderium.platform.PlatformHelper;
import com.renderium.streamline.*;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Streamline SDK 初始化器
 * <p>
 * 负责 NVIDIA Streamline SDK 的完整初始化生命周期，
 * 包括配置加载、上下文创建、Vulkan 桥接和特性检测。
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li>✅ Streamline SDK 配置与加载</li>
 *   <li>✅ SLContext 生命周期管理</li>
 *   <li>✅ Vulkan-Streamline 桥接初始化</li>
 *   <li>❌ 不负责超分辨率/帧生成管理（由 ModernTechManager 处理）</li>
 *   <li>❌ 不负责 Reflex 管理（由 ModernTechManager 处理）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * StreamlineInitializer initializer = new StreamlineInitializer();
 * InitializationResult result = initializer.initialize(config);
 *
 * if (result.isSuccess()) {
 *     SLContext context = result.getSLContext();
 *     VulkanStreamlineBridge bridge = result.getBridge();
 *     // 使用 context 和 bridge 初始化 SR/FG 管理器
 * }
 * }</pre>
 *
 * @author Renderium Team
 * @version 1.0
 * @since 5.0 (从 RenderiumCore 拆分)
 */
public final class StreamlineInitializer {

    private static final Logger LOGGER = Logger.getLogger(StreamlineInitializer.class.getName());

    /** Streamline 上下文实例 */
    private SLContext slContext;

    /** Vulkan-Streamline 桥接实例 */
    private VulkanStreamlineBridge vkBridge;

    /** 帧评估器实例 */
    private FrameEvaluator frameEvaluator;

    /** 配置加载器实例 */
    private SLConfigLoader configLoader;

    /**
     * 初始化 Streamline SDK
     *
     * @param sdkPath SDK 路径（如果为 null 则使用默认路径）
     * @return 初始化结果对象，包含所有创建的组件或错误信息
     */
    public InitializationResult initialize(String sdkPath) {
        try {
            // 步骤1: 验证并加载配置
            if (sdkPath == null || sdkPath.isEmpty()) {
                String configDirPath = PlatformHelper.getInstance().getConfigDirectory();
                sdkPath = Path.of(configDirPath)
                    .resolve(ConfigConstants.DEFAULT_STREAMLINE_SDK_SUBDIR)
                    .toString();
            }

            configLoader = new SLConfigLoader(Path.of(sdkPath));
            if (!configLoader.validate()) {
                LOGGER.warning("Streamline SDK validation failed");
                return InitializationResult.failure("SDK validation failed");
            }

            // 步骤2: 创建并加载 SLContext
            slContext = new SLContext();
            if (!slContext.load(configLoader.getInterposerPath())) {
                LOGGER.warning("Failed to load Streamline SDK");
                return InitializationResult.failure("Failed to load SDK");
            }

            // 步骤3: 初始化 SLContext
            if (!slContext.initialize(
                "Renderium", "Minecraft 26.2",
                configLoader.getLogPath(),
                configLoader.getCachePath(),
                configLoader.getPluginPath()
            )) {
                LOGGER.warning("Streamline initialization failed");
                return InitializationResult.failure("Initialization failed");
            }

            // 步骤4: 创建 Vulkan 桥接
            vkBridge = VulkanStreamlineBridge.getInstance();
            if (!slContext.registerVulkanInfo(vkBridge)) {
                LOGGER.warning("Vulkan info registration failed");
            } else {
                LOGGER.info("Vulkan-Streamline bridge initialized successfully");
            }

            // 步骤5: 特性检测
            slContext.detectFeatures();

            // 步骤6: 创建帧评估器
            frameEvaluator = new FrameEvaluator(slContext);

            LOGGER.info("Streamline SDK initialized successfully");
            return InitializationResult.success(this);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Streamline initialization exception", e);
            return InitializationResult.failure(e.getMessage());
        }
    }

    /**
     * 关闭 Streamline 并释放资源
     */
    public void shutdown() {
        try {
            if (slContext != null) {
                slContext.shutdown();
                slContext = null;
            }
            vkBridge = null;
            frameEvaluator = null;
            configLoader = null;
            LOGGER.info("Streamline shutdown completed");
        } catch (Exception e) {
            LOGGER.warning("Error during Streamline shutdown: " + e.getMessage());
        }
    }

    // ==================== Getter 方法 ====================

    public SLContext getSLContext() { return slContext; }
    public VulkanStreamlineBridge getBridge() { return vkBridge; }
    public FrameEvaluator getFrameEvaluator() { return frameEvaluator; }
    public SLConfigLoader getConfigLoader() { return configLoader; }

    public boolean isInitialized() {
        return slContext != null && slContext.isInitialized();
    }

    // ==================== 内部数据类 ====================

    /**
     * 初始化结果（不可变）
     */
    public static final class InitializationResult {

        private final boolean success;
        private final String errorMessage;
        private final StreamlineInitializer initializer;

        private InitializationResult(boolean success, String errorMessage, StreamlineInitializer initializer) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.initializer = initializer;
        }

        public static InitializationResult success(StreamlineInitializer init) {
            return new InitializationResult(true, null, init);
        }

        public static InitializationResult failure(String error) {
            return new InitializationResult(false, error, null);
        }

        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public StreamlineInitializer getInitializer() { return initializer; }
    }
}
