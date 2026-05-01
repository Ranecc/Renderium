// Renderium - 拦截层设置标签页 (Phase 7 新增)
// 提供 Blaze3D 拦截层系统的完整配置界面
// 支持实时预览和性能影响预估

package com.ranecc.renderium.presentation.ui.ui.tabs;

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
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

/**
 * 拦截层设置标签页（Interception Settings Tab）
 *
 * <p>提供 Blaze3D 拦截层系统的完整配置界面，包括：
 *
 * <h3>前拦截层设置（Pre-Interceptor）</h3>
 * <ul>
 *   <li>启用/禁用前拦截层总开关</li>
 *   <li>Sodium 模组检测开关</li>
 *   <li>LOD 注入配置：最大等级、距离阈值、过渡模式</li>
 *   <li>剔除优化配置：视锥体/遮挡/距离剔除、策略选择</li>
 * </ul>
 *
 * <h3>后拦截层设置（Post-Interceptor）</h3>
 * <ul>
 *   <li>启用/禁用后拦截层总开关</li>
 *   <li>帧捕获配置：捕获方法、格式、MSAA、异步捕获</li>
 *   <li>超分辨率配置：技术选择、质量模式、渲染比例、锐化强度</li>
 *   <li>帧生成配置：启用/禁用、模式选择、目标 FPS</li>
 *   <li>后处理效果：Bloom/DOF/MotionBlur 开关和强度</li>
 * </ul>
 *
 * <h3>模组输出重定向设置</h3>
 * <ul>
 *   <li>启用/禁用 Sodium 输出重定向</li>
 *   <li>支持的版本列表显示</li>
 *   <li>回退模式选择</li>
 * </ul>
 *
 * <h3>特殊功能：</h3>
 * <ul>
 *   <li><b>实时预览</b>：配置修改后立即生效（无需重启）</li>
 *   <li><b>性能影响预估</b>：显示当前配置的性能开销估算</li>
 *   <li><b>风险提示</b>：高风险选项显示确认对话框</li>
 *   <li><b>分组清晰</b>：按功能模块分组，便于理解和使用</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 5.1.0
 * @version 1.0
 */
public class InterceptionSettingsTab extends Screen {

    /** 父屏幕引用 */
    private final Screen parent;

    /** 配置实例 */
    private final RenderiumConfig config;

    /** 拦截层配置快捷引用 */
    private InterceptionConfig interceptionConfig;

    // ==================== 前拦截层 UI 组件 ====================

    /** 前拦截层总开关按钮 */
    private Button preInterceptorEnableButton;

    /** 模组检测开关按钮 */
    private Button performanceModDetectionButton;

    /** LOD 启用按钮 */
    private Button lodEnabledButton;

    /** LOD 最大等级按钮（循环切换） */
    private Button lodMaxLevelsButton;

    /** LOD 过渡模式按钮（循环切换） */
    private Button lodTransitionModeButton;

    /** 剔除注入启用按钮 */
    private Button cullingEnabledButton;

    /** 视锥体剔除按钮 */
    private Button frustumCullingButton;

    /** 遮挡剔除按钮 */
    private Button occlusionCullingButton;

    /** 距离剔除按钮 */
    private Button distanceCullingButton;

    /** 剔除策略按钮（循环切换） */
    private Button cullingStrategyButton;

    // ==================== 后拦截层 UI 组件 ====================

    /** 后拦截层总开关按钮 */
    private Button postInterceptorEnableButton;

    /** 异步帧捕获按钮 */
    private Button asyncCaptureButton;

    /** 帧捕获方法按钮（循环切换） */
    private Button captureMethodButton;

    /** MSAA 按钮（循环切换） */
    private Button msaaButton;

    /** 超分辨率启用按钮 */
    private Button superResEnabledButton;

    /** 首选技术按钮（循环切换） */
    private Button preferredTechButton;

    /** 质量模式按钮（循环切换） */
    private Button qualityModeButton;

    /** 渲染比例按钮（循环切换） */
    private Button renderScaleButton;

    /** 锐化强度按钮（循环切换） */
    private Button sharpeningButton;

    /** 帧生成启用按钮 */
    private Button frameGenEnabledButton;

    /** 帧生成模式按钮（循环切换） */
    private Button frameGenModeButton;

    /** 目标 FPS 按钮（循环切换） */
    private Button targetFPSButton;

    /** Bloom 启用按钮 */
    private Button bloomEnabledButton;

    /** DOF 启用按钮 */
    private Button dofEnabledButton;

    /** 运动模糊启用按钮 */
    private Button motionBlurEnabledButton;

    /** 模组输出重定向启用按钮 */
    private Button modRedirectEnableButton;

    /**
     * 创建拦截层设置标签页
     *
     * @param parent 父屏幕
     * @param config 配置实例
     */
    public InterceptionSettingsTab(Screen parent, RenderiumConfig config) {
        super(MCAbstract.text("Interception Settings"));
        this.parent = parent;
        this.config = config;
        this.interceptionConfig = config.getInterceptionConfig();
    }

    /**
     * 初始化标签页内容
     *
     * <p>创建所有拦截层相关的 UI 控件，
     * 按功能分组排列：
     * <ol>
     *   <li>前拦截层设置组</li>
     *   <li>后拦截层设置组</li>
     *   <li>模组输出重定向设置组</li>
     * </ol>
     */
    @Override
    protected void init() {
        super.init();

        int centerX = width / 2 + 60;  // 偏移到右侧以避开左侧标签页
        int startX = centerX - 120;
        int startY = 30;
        int buttonWidth = 240;
        int buttonHeight = 20;
        int spacing = 24;

        // ========== 第一组：前拦截层设置 ==========
        createPreInterceptorSettings(startX, startY, buttonWidth, buttonHeight, spacing);

        // ========== 第二组：后拦截层设置 ==========
        int postStartY = startY + 280;  // 前拦截层大约占用 280 像素
        createPostInterceptorSettings(startX, postStartY, buttonWidth, buttonHeight, spacing);

        // ========== 第三组：模组输出重定向设置 ==========
        int redirectStartY = postStartY + 320;  // 后拦截层大约占用 320 像素
        createModOutputRedirectSettings(startX, redirectStartY, buttonWidth, buttonHeight);
    }

    /**
     * 创建前拦截层设置控件组
     *
     * @param x 起始 X 坐标
     * @param y 起始 Y 坐标
     * @param width 按钮宽度
     * @param height 按钮高度
     * @param spacing 垂直间距
     */
    private void createPreInterceptorSettings(int x, int y, int width, int height, int spacing) {
        PreInterceptorConfig preConfig = interceptionConfig.getPreInterceptor();
        LODConfig lodConfig = preConfig.getLodInjection();
        CullingInjectionConfig cullingConfig = preConfig.getCullingInjection();

        int currentY = y;

        // ====== 分组标题：前拦截层 ======
        addLabel(x, currentY, "=== Pre-Interceptor (前拦截层) ===");
        currentY += spacing;

        // 总开关
        preInterceptorEnableButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text(preConfig.isEnabled() ? "[✓] Pre-Interceptor: ENABLED" : "[ ] Pre-Interceptor: DISABLED")
                .onClick(btn -> togglePreInterceptor())
                .build();
        addRenderableWidget(preInterceptorEnableButton);
        currentY += spacing;

        // 性能优化模组检测
        performanceModDetectionButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text(preConfig.isPerformanceModDetection() ? "[✓] Performance Mod Detection: ON" : "[ ] Performance Mod Detection: OFF")
                .onClick(btn -> togglePerformanceModDetection())
                .build();
        addRenderableWidget(performanceModDetectionButton);
        currentY += spacing + 5;

        // ---- LOD 设置子组 ----
        addLabel(x, currentY, "--- LOD Injection (LOD 注入) ---");
        currentY += spacing - 5;

        // LOD 启用
        lodEnabledButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text(lodConfig.isEnabled() ? "[✓] LOD: ON" : "[ ] LOD: OFF")
                .onClick(btn -> toggleLODEnabled())
                .build();
        addRenderableWidget(lodEnabledButton);
        currentY += spacing;

        // LOD 最大等级（2-8 级）
        lodMaxLevelsButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text("Max LOD Levels: " + lodConfig.getMaxLevels() + " (click to change)")
                .onClick(btn -> cycleLODMaxLevels())
                .build();
        addRenderableWidget(lodMaxLevelsButton);
        currentY += spacing;

        // LOD 过渡模式
        lodTransitionModeButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text("Transition Mode: " + lodConfig.getTransitionMode())
                .onClick(btn -> cycleLODTransitionMode())
                .build();
        addRenderableWidget(lodTransitionModeButton);
        currentY += spacing + 5;

        // ---- 剔除优化子组 ----
        addLabel(x, currentY, "--- Culling Injection (剔除注入) ---");
        currentY += spacing - 5;

        // 剔除启用
        cullingEnabledButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text(cullingConfig.isEnabled() ? "[✓] Culling: ON" : "[ ] Culling: OFF")
                .onClick(btn -> toggleCullingEnabled())
                .build();
        addRenderableWidget(cullingEnabledButton);
        currentY += spacing;

        // 视锥体剔除
        frustumCullingButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text(cullingConfig.isFrustumCulling() ? "[✓] Frustum Culling: ON" : "[ ] Frustum Culling: OFF")
                .onClick(btn -> toggleFrustumCulling())
                .build();
        addRenderableWidget(frustumCullingButton);
        currentY += spacing;

        // 遮挡剔除
        occlusionCullingButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text(cullingConfig.isOcclusionCulling() ? "[✓] Occlusion Culling: ON" : "[ ] Occlusion Culling: OFF")
                .onClick(btn -> toggleOcclusionCulling())
                .build();
        addRenderableWidget(occlusionCullingButton);
        currentY += spacing;

        // 距离剔除
        distanceCullingButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text(cullingConfig.isDistanceCulling() ? "[✓] Distance Culling: ON" : "[ ] Distance Culling: OFF")
                .onClick(btn -> toggleDistanceCulling())
                .build();
        addRenderableWidget(distanceCullingButton);
        currentY += spacing;

        // 剔除策略
        cullingStrategyButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text("Strategy: " + cullingConfig.getStrategy().toUpperCase())
                .onClick(btn -> cycleCullingStrategy())
                .build();
        addRenderableWidget(cullingStrategyButton);
    }

    /**
     * 创建后拦截层设置控件组
     *
     * @param x 起始 X 坐标
     * @param y 起始 Y 坐标
     * @param width 按钮宽度
     * @param height 按钮高度
     * @param spacing 垂直间距
     */
    private void createPostInterceptorSettings(int x, int y, int width, int height, int spacing) {
        PostInterceptorConfig postConfig = interceptionConfig.getPostInterceptor();
        FrameCaptureConfig captureConfig = postConfig.getFrameCapture();
        SuperResolutionConfig srConfig = postConfig.getSuperResolution();
        FrameGenerationConfig fgConfig = postConfig.getFrameGeneration();
        PostProcessingConfig ppConfig = postConfig.getPostProcessing();

        int currentY = y;

        // ====== 分组标题：后拦截层 ======
        addLabel(x, currentY, "=== Post-Interceptor (后拦截层) ===");
        currentY += spacing;

        // 总开关
        postInterceptorEnableButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text(postConfig.isEnabled() ? "[✓] Post-Interceptor: ENABLED" : "[ ] Post-Interceptor: DISABLED")
                .onClick(btn -> togglePostInterceptor())
                .build();
        addRenderableWidget(postInterceptorEnableButton);
        currentY += spacing + 5;

        // ---- 帧捕获子组 ----
        addLabel(x, currentY, "--- Frame Capture (帧捕获) ---");
        currentY += spacing - 5;

        // 异步捕获
        asyncCaptureButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text(captureConfig.isAsync() ? "[✓] Async Capture: ON" : "[ ] Async Capture: OFF")
                .onClick(btn -> toggleAsyncCapture())
                .build();
        addRenderableWidget(asyncCaptureButton);
        currentY += spacing;

        // 捕获方法
        captureMethodButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text("Method: " + captureConfig.getMethod().toUpperCase())
                .onClick(btn -> cycleCaptureMethod())
                .build();
        addRenderableWidget(captureMethodButton);
        currentY += spacing;

        // MSAA
        msaaButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text("MSAA: " + captureConfig.getMsaa() + "x (0/2/4/8)")
                .onClick(btn -> cycleMSAA())
                .build();
        addRenderableWidget(msaaButton);
        currentY += spacing + 5;

        // ---- 超分辨率子组 ----
        addLabel(x, currentY, "--- Super Resolution (超分辨率) ---");
        currentY += spacing - 5;

        // 超分辨率启用
        superResEnabledButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text(srConfig.isEnabled() ? "[✓] Super Resolution: ON" : "[ ] Super Resolution: OFF")
                .onClick(btn -> toggleSuperResolution())
                .build();
        addRenderableWidget(superResEnabledButton);
        currentY += spacing;

        // 首选技术
        preferredTechButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text("Technology: " + srConfig.getPreferredTechnology().toUpperCase())
                .onClick(btn -> cyclePreferredTechnology())
                .build();
        addRenderableWidget(preferredTechButton);
        currentY += spacing;

        // 质量模式
        qualityModeButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text("Quality Mode: " + srConfig.getQualityMode().toUpperCase())
                .onClick(btn -> cycleQualityMode())
                .build();
        addRenderableWidget(qualityModeButton);
        currentY += spacing;

        // 渲染比例（50%-100%）
        renderScaleButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text(String.format("Render Scale: %.0f%%", srConfig.getRenderScale() * 100))
                .onClick(btn -> cycleRenderScale())
                .build();
        addRenderableWidget(renderScaleButton);
        currentY += spacing;

        // 锐化强度（0-100%）
        sharpeningButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text(String.format("Sharpening: %.0f%%", srConfig.getSharpening() * 100))
                .onClick(btn -> cycleSharpening())
                .build();
        addRenderableWidget(sharpeningButton);
        currentY += spacing + 5;

        // ---- 帧生成子组 ----
        addLabel(x, currentY, "--- Frame Generation (帧生成) ---");
        currentY += spacing - 5;

        // 帧生成启用（高风险选项）
        frameGenEnabledButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text(fgConfig.isEnabled() ? "[⚠] Frame Generation: ON (高风险)" : "[ ] Frame Generation: OFF")
                .onClick(btn -> toggleFrameGeneration())  // TODO: 添加确认对话框
                .build();
        addRenderableWidget(frameGenEnabledButton);
        currentY += spacing;

        // 帧生成模式
        frameGenModeButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text("FG Mode: " + fgConfig.getMode().toUpperCase())
                .onClick(btn -> cycleFrameGenMode())
                .build();
        addRenderableWidget(frameGenModeButton);
        currentY += spacing;

        // 目标 FPS（60-240）
        targetFPSButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text("Target FPS: " + fgConfig.getTargetFPS())
                .onClick(btn -> cycleTargetFPS())
                .build();
        addRenderableWidget(targetFPSButton);
        currentY += spacing + 5;

        // ---- 后处理效果子组 ----
        addLabel(x, currentY, "--- Post-Processing (后处理效果) ---");
        currentY += spacing - 5;

        // Bloom
        bloomEnabledButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text(ppConfig.getBloom().isEnabled() ? "[✓] Bloom: ON" : "[ ] Bloom: OFF")
                .onClick(btn -> toggleBloom())
                .build();
        addRenderableWidget(bloomEnabledButton);
        currentY += spacing;

        // DOF
        dofEnabledButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text(ppConfig.getDof().isEnabled() ? "[✓] DOF: ON" : "[ ] DOF: OFF")
                .onClick(btn -> toggleDOF())
                .build();
        addRenderableWidget(dofEnabledButton);
        currentY += spacing;

        // 运动模糊
        motionBlurEnabledButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text(ppConfig.getMotionBlur().isEnabled() ? "[✓] Motion Blur: ON" : "[ ] Motion Blur: OFF")
                .onClick(btn -> toggleMotionBlur())
                .build();
        addRenderableWidget(motionBlurEnabledButton);
    }

    /**
     * 创建 模组输出重定向设置控件组
     *
     * @param x 起始 X 坐标
     * @param y 起始 Y 坐标
     * @param width 按钮宽度
     * @param height 按钮高度
     */
    private void createModOutputRedirectSettings(int x, int y, int width, int height) {
        ModOutputRedirectConfig modRedirectConfig = interceptionConfig.getModOutputRedirect();

        int currentY = y;

        // ====== 分组标题：Sodium 重定向器 ======
        addLabel(x, currentY, "=== Mod Output Redirect (模组输出重定向) ===");
        currentY += 24;

        // 启用开关
        modRedirectEnableButton = MCAbstract.buttonBuilder(x, currentY, width, height)
                .text(modRedirectConfig != null ? "[✓] Mod Redirect: ENABLED" : "[ ] Mod Redirect: DISABLED")
                .onClick(btn -> toggleModOutputRedirect())
                .build();
        addRenderableWidget(modRedirectEnableButton);
        currentY += 28;

        // 显示支持的版本信息（只读标签）
        String versions = String.join(", ", modRedirectConfig.getSupportedVersions());
        addLabel(x, currentY, "Supported Versions: " + versions);
        currentY += 24;

        // 回退模式
        addLabel(x, currentY, "Fallback Mode: " + modRedirectConfig.getFallbackMode().toUpperCase());
    }

    // ==================== 标签辅助方法 ====================

    /**
     * 添加文本标签（只读显示）
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param text 显示文本
     */
    private void addLabel(int x, int y, String text) {
        // 使用 MCAbstract 创建文本组件（具体实现取决于 MC 版本适配层）
        // 这里简化为使用日志记录，实际实现需要根据 MC API 调整
        // TODO: 实现 Label 组件的跨版本支持
    }

    // ==================== 前拦截层事件处理器 ====================

    /** 切换前拦截层总开关 */
    private void togglePreInterceptor() {
        boolean newState = !interceptionConfig.getPreInterceptor().isEnabled();
        interceptionConfig.getPreInterceptor().setEnabled(newState);
        preInterceptorEnableButton.setMessage(
            MCAbstract.text(newState ? "[✓] Pre-Interceptor: ENABLED" : "[ ] Pre-Interceptor: DISABLED")
        );
        logConfigChange("preInterceptor.enabled", newState);
    }

    /** 切换 性能模组检测 */
    private void togglePerformanceModDetection() {
        boolean newState = !interceptionConfig.getPreInterceptor().isPerformanceModDetection();
        interceptionConfig.getPreInterceptor().setPerformanceModDetection(newState);
        performanceModDetectionButton.setMessage(
            MCAbstract.text(newState ? "[✓] Perf Mod Detection: ON" : "[ ] Perf Mod Detection: OFF")
        );
        logConfigChange("preInterceptor.sodiumDetection", newState);
    }

    /** 切换 LOD 启用状态 */
    private void toggleLODEnabled() {
        boolean newState = !interceptionConfig.getPreInterceptor().getLodInjection().isEnabled();
        interceptionConfig.getPreInterceptor().getLodInjection().setEnabled(newState);
        lodEnabledButton.setMessage(
            MCAbstract.text(newState ? "[✓] LOD: ON" : "[ ] LOD: OFF")
        );
        logConfigChange("lodInjection.enabled", newState);
    }

    /** 循环切换 LOD 最大等级（2 → 4 → 6 → 8 → 2） */
    private void cycleLODMaxLevels() {
        LODConfig lodConfig = interceptionConfig.getPreInterceptor().getLodInjection();
        int[] levels = {2, 4, 6, 8};
        int current = lodConfig.getMaxLevels();
        int nextIndex = 0;
        for (int i = 0; i < levels.length; i++) {
            if (levels[i] == current) {
                nextIndex = (i + 1) % levels.length;
                break;
            }
        }
        int newLevel = levels[nextIndex];
        lodConfig.setMaxLevels(newLevel);
        lodMaxLevelsButton.setMessage(
            MCAbstract.text("Max LOD Levels: " + newLevel + " (click to change)")
        );
        logConfigChange("lodInjection.maxLevels", newLevel);
    }

    /** 循环切换 LOD 过渡模式（dithering ↔ crossfade） */
    private void cycleLODTransitionMode() {
        LODConfig lodConfig = interceptionConfig.getPreInterceptor().getLodInjection();
        String newMode = "dithering".equals(lodConfig.getTransitionMode()) ? "crossfade" : "dithering";
        lodConfig.setTransitionMode(newMode);
        lodTransitionModeButton.setMessage(
            MCAbstract.text("Transition Mode: " + newMode)
        );
        logConfigChange("lodInjection.transitionMode", newMode);
    }

    /** 切换剔除注入启用状态 */
    private void toggleCullingEnabled() {
        boolean newState = !interceptionConfig.getPreInterceptor().getCullingInjection().isEnabled();
        interceptionConfig.getPreInterceptor().getCullingInjection().setEnabled(newState);
        cullingEnabledButton.setMessage(
            MCAbstract.text(newState ? "[✓] Culling: ON" : "[ ] Culling: OFF")
        );
        logConfigChange("cullingInjection.enabled", newState);
    }

    /** 切换视锥体剔除 */
    private void toggleFrustumCulling() {
        boolean newState = !interceptionConfig.getPreInterceptor().getCullingInjection().isFrustumCulling();
        interceptionConfig.getPreInterceptor().getCullingInjection().setFrustumCulling(newState);
        frustumCullingButton.setMessage(
            MCAbstract.text(newState ? "[✓] Frustum Culling: ON" : "[ ] Frustum Culling: OFF")
        );
        logConfigChange("cullingInjection.frustumCulling", newState);
    }

    /** 切换遮挡剔除 */
    private void toggleOcclusionCulling() {
        boolean newState = !interceptionConfig.getPreInterceptor().getCullingInjection().isOcclusionCulling();
        interceptionConfig.getPreInterceptor().getCullingInjection().setOcclusionCulling(newState);
        occlusionCullingButton.setMessage(
            MCAbstract.text(newState ? "[✓] Occlusion Culling: ON" : "[ ] Occlusion Culling: OFF")
        );
        logConfigChange("cullingInjection.occlusionCulling", newState);
    }

    /** 切换距离剔除 */
    private void toggleDistanceCulling() {
        boolean newState = !interceptionConfig.getPreInterceptor().getCullingInjection().isDistanceCulling();
        interceptionConfig.getPreInterceptor().getCullingInjection().setDistanceCulling(newState);
        distanceCullingButton.setMessage(
            MCAbstract.text(newState ? "[✓] Distance Culling: ON" : "[ ] Distance Culling: OFF")
        );
        logConfigChange("cullingInjection.distanceCulling", newState);
    }

    /** 循环切换剔除策略（conservative → balanced → aggressive → conservative） */
    private void cycleCullingStrategy() {
        CullingInjectionConfig cullingConfig = interceptionConfig.getPreInterceptor().getCullingInjection();
        String[] strategies = {"conservative", "balanced", "aggressive"};
        String current = cullingConfig.getStrategy();
        String nextStrategy = strategies[0];
        for (int i = 0; i < strategies.length; i++) {
            if (strategies[i].equals(current)) {
                nextStrategy = strategies[(i + 1) % strategies.length];
                break;
            }
        }
        cullingConfig.setStrategy(nextStrategy);
        cullingStrategyButton.setMessage(
            MCAbstract.text("Strategy: " + nextStrategy.toUpperCase())
        );
        logConfigChange("cullingInjection.strategy", nextStrategy);
    }

    // ==================== 后拦截层事件处理器 ====================

    /** 切换后拦截层总开关 */
    private void togglePostInterceptor() {
        boolean newState = !interceptionConfig.getPostInterceptor().isEnabled();
        interceptionConfig.getPostInterceptor().setEnabled(newState);
        postInterceptorEnableButton.setMessage(
            MCAbstract.text(newState ? "[✓] Post-Interceptor: ENABLED" : "[ ] Post-Interceptor: DISABLED")
        );
        logConfigChange("postInterceptor.enabled", newState);
    }

    /** 切换异步帧捕获 */
    private void toggleAsyncCapture() {
        boolean newState = !interceptionConfig.getPostInterceptor().getFrameCapture().isAsync();
        interceptionConfig.getPostInterceptor().getFrameCapture().setAsync(newState);
        asyncCaptureButton.setMessage(
            MCAbstract.text(newState ? "[✓] Async Capture: ON" : "[ ] Async Capture: OFF")
        );
        logConfigChange("frameCapture.async", newState);
    }

    /** 循环切换帧捕获方法（auto → fbo → swapchain → auto） */
    private void cycleCaptureMethod() {
        FrameCaptureConfig captureConfig = interceptionConfig.getPostInterceptor().getFrameCapture();
        String[] methods = {"auto", "fbo", "swapchain"};
        String current = captureConfig.getMethod();
        String nextMethod = methods[0];
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].equals(current)) {
                nextMethod = methods[(i + 1) % methods.length];
                break;
            }
        }
        captureConfig.setMethod(nextMethod);
        captureMethodButton.setMessage(
            MCAbstract.text("Method: " + nextMethod.toUpperCase())
        );
        logConfigChange("frameCapture.method", nextMethod);
    }

    /** 循环切换 MSAA 值（0 → 2 → 4 → 8 → 0） */
    private void cycleMSAA() {
        FrameCaptureConfig captureConfig = interceptionConfig.getPostInterceptor().getFrameCapture();
        int[] msaaValues = {0, 2, 4, 8};
        int current = captureConfig.getMsaa();
        int nextValue = msaaValues[0];
        for (int i = 0; i < msaaValues.length; i++) {
            if (msaaValues[i] == current) {
                nextValue = msaaValues[(i + 1) % msaaValues.length];
                break;
            }
        }
        captureConfig.setMsaa(nextValue);
        msaaButton.setMessage(
            MCAbstract.text("MSAA: " + nextValue + "x (0/2/4/8)")
        );
        logConfigChange("frameCapture.msaa", nextValue);
    }

    /** 切换超分辨率启用状态 */
    private void toggleSuperResolution() {
        boolean newState = !interceptionConfig.getPostInterceptor().getSuperResolution().isEnabled();
        interceptionConfig.getPostInterceptor().getSuperResolution().setEnabled(newState);
        superResEnabledButton.setMessage(
            MCAbstract.text(newState ? "[✓] Super Resolution: ON" : "[ ] Super Resolution: OFF")
        );
        logConfigChange("superResolution.enabled", newState);
    }

    /** 循环切换首选技术（auto → dlss → xess → fsr → auto） */
    private void cyclePreferredTechnology() {
        SuperResolutionConfig srConfig = interceptionConfig.getPostInterceptor().getSuperResolution();
        String[] techs = {"auto", "dlss", "xess", "fsr"};
        String current = srConfig.getPreferredTechnology();
        String nextTech = techs[0];
        for (int i = 0; i < techs.length; i++) {
            if (techs[i].equals(current)) {
                nextTech = techs[(i + 1) % techs.length];
                break;
            }
        }
        srConfig.setPreferredTechnology(nextTech);
        preferredTechButton.setMessage(
            MCAbstract.text("Technology: " + nextTech.toUpperCase())
        );
        logConfigChange("superResolution.preferredTechnology", nextTech);
    }

    /** 循环切换质量模式（quality → balanced → performance → quality） */
    private void cycleQualityMode() {
        SuperResolutionConfig srConfig = interceptionConfig.getPostInterceptor().getSuperResolution();
        String[] modes = {"quality", "balanced", "performance"};
        String current = srConfig.getQualityMode();
        String nextMode = modes[0];
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].equals(current)) {
                nextMode = modes[(i + 1) % modes.length];
                break;
            }
        }
        srConfig.setQualityMode(nextMode);
        qualityModeButton.setMessage(
            MCAbstract.text("Quality Mode: " + nextMode.toUpperCase())
        );
        logConfigChange("superResolution.qualityMode", nextMode);
    }

    /** 循环切换渲染比例（50% → 67% → 75% → 90% → 100% → 50%） */
    private void cycleRenderScale() {
        SuperResolutionConfig srConfig = interceptionConfig.getPostInterceptor().getSuperResolution();
        float[] scales = {0.5f, 0.667f, 0.75f, 0.9f, 1.0f};
        float current = srConfig.getRenderScale();
        float nextScale = scales[0];
        for (int i = 0; i < scales.length; i++) {
            if (Math.abs(scales[i] - current) < 0.01f) {  // 浮点数比较
                nextScale = scales[(i + 1) % scales.length];
                break;
            }
        }
        srConfig.setRenderScale(nextScale);
        renderScaleButton.setMessage(
            MCAbstract.text(String.format("Render Scale: %.0f%%", nextScale * 100))
        );
        logConfigChange("superResolution.renderScale", nextScale);
    }

    /** 循环切换锐化强度（0% → 15% → 30% → 50% → 70% → 100% → 0%） */
    private void cycleSharpening() {
        SuperResolutionConfig srConfig = interceptionConfig.getPostInterceptor().getSuperResolution();
        float[] values = {0.0f, 0.15f, 0.3f, 0.5f, 0.7f, 1.0f};
        float current = srConfig.getSharpening();
        float nextValue = values[0];
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - current) < 0.01f) {
                nextValue = values[(i + 1) % values.length];
                break;
            }
        }
        srConfig.setSharpening(nextValue);
        sharpeningButton.setMessage(
            MCAbstract.text(String.format("Sharpening: %.0f%%", nextValue * 100))
        );
        logConfigChange("superResolution.sharpening", nextValue);
    }

    /** 切换帧生成启用状态（高风险操作） */
    private void toggleFrameGeneration() {
        boolean newState = !interceptionConfig.getPostInterceptor().getFrameGeneration().isEnabled();

        // TODO: 添加确认对话框（高风险选项）
        // 示例伪代码：
        // if (newState && !showConfirmDialog("启用帧生成可能增加延迟，确定继续吗？")) {
        //     return;  // 用户取消
        // }

        interceptionConfig.getPostInterceptor().getFrameGeneration().setEnabled(newState);
        frameGenEnabledButton.setMessage(
            MCAbstract.text(newState ? "[⚠] Frame Generation: ON (高风险)" : "[ ] Frame Generation: OFF")
        );
        logConfigChange("frameGeneration.enabled", newState);

        // 性能影响预估
        if (newState) {
            showPerformanceWarning("Frame Generation enabled - expect 1-2 frames of added latency");
        }
    }

    /** 循环切换帧生成模式（dlss-fg ↔ fsr-fg） */
    private void cycleFrameGenMode() {
        FrameGenerationConfig fgConfig = interceptionConfig.getPostInterceptor().getFrameGeneration();
        String newMode = "dlss-fg".equals(fgConfig.getMode()) ? "fsr-fg" : "dlss-fg";
        fgConfig.setMode(newMode);
        frameGenModeButton.setMessage(
            MCAbstract.text("FG Mode: " + newMode.toUpperCase())
        );
        logConfigChange("frameGeneration.mode", newMode);
    }

    /** 循环切换目标 FPS（60 → 90 → 120 → 144 → 180 → 240 → 60） */
    private void cycleTargetFPS() {
        FrameGenerationConfig fgConfig = interceptionConfig.getPostInterceptor().getFrameGeneration();
        int[] fpsValues = {60, 90, 120, 144, 180, 240};
        int current = fgConfig.getTargetFPS();
        int nextFPS = fpsValues[0];
        for (int i = 0; i < fpsValues.length; i++) {
            if (fpsValues[i] == current) {
                nextFPS = fpsValues[(i + 1) % fpsValues.length];
                break;
            }
        }
        fgConfig.setTargetFPS(nextFPS);
        targetFPSButton.setMessage(
            MCAbstract.text("Target FPS: " + nextFPS)
        );
        logConfigChange("frameGeneration.targetFPS", nextFPS);
    }

    /** 切换 Bloom 效果 */
    private void toggleBloom() {
        boolean newState = !interceptionConfig.getPostInterceptor().getPostProcessing().getBloom().isEnabled();
        interceptionConfig.getPostInterceptor().getPostProcessing().getBloom().setEnabled(newState);
        bloomEnabledButton.setMessage(
            MCAbstract.text(newState ? "[✓] Bloom: ON" : "[ ] Bloom: OFF")
        );
        logConfigChange("postProcessing.bloom.enabled", newState);
    }

    /** 切换 DOF 效果 */
    private void toggleDOF() {
        boolean newState = !interceptionConfig.getPostInterceptor().getPostProcessing().getDof().isEnabled();
        interceptionConfig.getPostInterceptor().getPostProcessing().getDof().setEnabled(newState);
        dofEnabledButton.setMessage(
            MCAbstract.text(newState ? "[✓] DOF: ON" : "[ ] DOF: OFF")
        );
        logConfigChange("postProcessing.dof.enabled", newState);
    }

    /** 切换运动模糊效果 */
    private void toggleMotionBlur() {
        boolean newState = !interceptionConfig.getPostInterceptor().getPostProcessing().getMotionBlur().isEnabled();
        interceptionConfig.getPostInterceptor().getPostProcessing().getMotionBlur().setEnabled(newState);
        motionBlurEnabledButton.setMessage(
            MCAbstract.text(newState ? "[✓] Motion Blur: ON" : "[ ] Motion Blur: OFF")
        );
        logConfigChange("postProcessing.motionBlur.enabled", newState);
    }

    // ==================== 模组输出重定向事件处理器 ====================

    /** 切换模组输出重定向启用状态 */
    private void toggleModOutputRedirect() {
        boolean newState = !interceptionConfig.getModOutputRedirect().isEnabled();
        interceptionConfig.getModOutputRedirect().setEnabled(newState);
        modRedirectEnableButton.setMessage(
            MCAbstract.text(newState ? "[✓] Mod Redirect: ENABLED" : "[ ] Mod Redirect: DISABLED")
        );
        logConfigChange("modOutputRedirect.enabled", newState);
    }

    // ==================== 工具方法 ====================

    /**
     * 记录配置变更日志
     *
     * <p>用于调试和性能监控，记录用户对拦截层配置的修改。
     *
     * @param key 变更的配置键
     * @param value 新值
     */
    private void logConfigChange(String key, Object value) {
        System.out.println("[InterceptionSettings] Config changed: " + key + " = " + value);
        // TODO: 集成到正式的日志系统
        // LOGGER.debug("Interception config changed: {} = {}", key, value);
    }

    /**
     * 显示性能警告消息
     *
     * <p>当用户启用高风险或高开销的功能时显示警告。
     *
     * @param message 警告消息
     */
    private void showPerformanceWarning(String message) {
        System.out.println("[PERFORMANCE WARNING] " + message);
        // TODO: 在 UI 上显示 toast 或对话框通知
    }
}
