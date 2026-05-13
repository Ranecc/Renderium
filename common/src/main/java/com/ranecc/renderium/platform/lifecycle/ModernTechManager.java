// ============================================================
// ModernTechManager - 现代渲染技术管理器
// ============================================================
// 从 RenderiumCore 中提取的独立组件
// 职责：超分辨率(SR)、帧生成(FG)、Reflex 低延迟的管理
//
// 管理的技术栈：
//   - SuperResolutionManager: DLSS/XeSS/FSR 超分辨率
//   - FrameGeneratorManager: DLSS-FG/FSR-FG 帧生成
//   - ReflexManager: NVIDIA Reflex 低延迟模式
//
// @see com.renderium.superres.SuperResolutionManager
// @see com.renderium.framegen.FrameGeneratorManager
// @see com.renderium.reflex.ReflexManager
// ============================================================

package com.ranecc.renderium.platform.lifecycle;

import com.ranecc.renderium.None;
import com.ranecc.renderium.tech.framegen.*;
import com.ranecc.renderium.tech.streamline.*;
import com.ranecc.renderium.tech.superres.*;
import com.ranecc.renderium.domain.model.config.RenderiumConfig;

/**
 * 现代渲染技术管理器
 * <p>
 * 统一管理超分辨率、帧生成和 Reflex 低延迟技术的生命周期。
 * 此类从 {@code RenderiumCore} 中提取，遵循单一职责原则。
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li>✅ 超分辨率技术管理（DLSS/FSR/XeSS）</li>
 *   <li>✅ 帧生成技术管理（DLSS-FG/FSR-FG）</li>
 *   <li>✅ Reflex 低延迟模式管理</li>
 *   <li>❌ 不负责 Streamline 初始化（由 StreamlineInitializer 处理）</li>
 *   <li>❌ 不负责帧数据处理（由 FrameProcessor 处理）</li>
 * </ul>
 *
 * <h3>依赖关系</h3>
 * <pre>{@code
 * StreamlineInitializer → 提供 SLContext + FrameEvaluator
 *       ↓
 * ModernTechManager → 使用它们创建 SR/FG/Reflex 管理器
 * }</pre>
 *
 * @author Renderium Team
 * @version 1.0
 * @since 5.0 (从 RenderiumCore 拆分)
 */
public final class ModernTechManager {

    private SuperResolutionManager superResolutionManager;
    private FrameGeneratorManager frameGeneratorManager;
    private ReflexManager reflexManager;

    /**
     * 初始化超分辨率管理器
     *
     * @param slContext Streamline 上下文（必须已初始化）
     * @param configLoader 配置加载器
     * @param bridge Vulkan Streamline 桥接器（可为 null，用于 XeSS/FSR）
     * @param frameEvaluator 帧评估器（可为 null，用于 XeSS/FSR）
     */
    public void initializeSuperResolution(SLContext slContext, SLConfigLoader configLoader,
                                          VulkanStreamlineBridge bridge, FrameEvaluator frameEvaluator) {
        if (slContext == null || !slContext.isInitialized()) return;

        superResolutionManager = new SuperResolutionManager(slContext, configLoader);

        // 注册 DLSS 适配器
        if (slContext.isFeatureSupported(SLContext.Feature.DLSS)) {
            DLSSManager dlssManager = DLSSManager.getInstance();
            superResolutionManager.registerAdapter(new DLSSAdapter(dlssManager));
        }

        // 注册 XeSS 适配器（bridge 和 frameEvaluator 可为 null，XeSS 会自行检查）
        superResolutionManager.registerAdapter(new XeSSAdapter(slContext, bridge, frameEvaluator));

        // 注册 FSR 适配器
        superResolutionManager.registerAdapter(new FSRAdapter(slContext, bridge, frameEvaluator));

        // 自动检测最佳技术
        superResolutionManager.detectAndSelect();
    }

    /**
     * 初始化帧生成管理器
     *
     * @param slContext Streamline 上下文（必须已初始化）
     * @param frameEvaluator 帧评估器
     * @param bridge Vulkan Streamline 桥接器（必需，用于 DLSS-FG/FSR-FG）
     */
    public void initializeFrameGeneration(SLContext slContext, FrameEvaluator frameEvaluator,
                                          VulkanStreamlineBridge bridge) {
        if (slContext == null || !slContext.isInitialized()) return;

        frameGeneratorManager = new FrameGeneratorManager();

        // 注册 DLSS FG
        frameGeneratorManager.registerGenerator(
            FrameGeneratorManager.FrameGenType.DLSS_FG,
            new DLSSFGAdapter(slContext, bridge, frameEvaluator)
        );

        // 注册 FSR FG
        frameGeneratorManager.registerGenerator(
            FrameGeneratorManager.FrameGenType.FSR_FG,
            new FSRFGAdapter(slContext, bridge, frameEvaluator)
        );

        // 自动检测
        frameGeneratorManager.detectAndSelect();
    }

    /**
     * 初始化 Reflex 低延迟模式
     *
     * @param slContext Streamline 上下文（必须已初始化）
     */
    public void initializeReflex(SLContext slContext) {
        if (slContext == null || !slContext.isInitialized()) return;

        reflexManager = new ReflexManager(slContext);
    }

    /**
     * 应用配置到各管理器
     *
     * @param config 渲染配置
     */
    public void applyConfig(RenderiumConfig config) {
        if (config == null) return;

        // 超分辨率配置
        if (superResolutionManager != null && superResolutionManager.isAvailable()) {
            superResolutionManager.setPreferredTechnology(config.getTechnology());
            superResolutionManager.setQuality(config.getQuality());
            superResolutionManager.enable();
        }

        // 帧生成配置
        if (frameGeneratorManager != null && config.isFrameGenerationEnabled()) {
            frameGeneratorManager.enable(config.getFrameGenMode());
        }

        // Reflex 配置
        if (reflexManager != null && config.isReflexEnabled()) {
            reflexManager.enable(switch (config.getReflexMode()) {
                case LOW_LATENCY -> ReflexManager.ReflexMode.LOW_LATENCY;
                case LOW_LATENCY_BOOST -> ReflexManager.ReflexMode.LOW_LATENCY_BOOST;
                default -> ReflexManager.ReflexMode.OFF;
            });
        }
    }

    /**
     * 关闭所有管理器并释放资源
     */
    public void shutdown() {
        // 关闭帧生成
        if (frameGeneratorManager != null) {
            frameGeneratorManager.shutdown();
            frameGeneratorManager = null;
        }

        // 关闭超分辨率
        if (superResolutionManager != null) {
            superResolutionManager.shutdown();
            superResolutionManager = null;
        }

        // 关闭 Reflex
        if (reflexManager != null) {
            reflexManager.shutdown();
            reflexManager = null;
        }
    }

    // ==================== 状态查询 ====================

    public boolean isSuperResolutionEnabled() {
        return superResolutionManager != null && superResolutionManager.isEnabled();
    }

    public boolean isFrameGenerationEnabled() {
        return frameGeneratorManager != null && frameGeneratorManager.isEnabled();
    }

    public boolean isReflexEnabled() {
        return reflexManager != null && reflexManager.isEnabled();
    }

    // ==================== Getter 方法 ====================

    public SuperResolutionManager getSuperResolutionManager() { return superResolutionManager; }
    public FrameGeneratorManager getFrameGeneratorManager() { return frameGeneratorManager; }
    public ReflexManager getReflexManager() { return reflexManager; }
}
