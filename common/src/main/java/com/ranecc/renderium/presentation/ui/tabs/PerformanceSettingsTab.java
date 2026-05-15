// Renderium - 性能设置标签页 (Enhanced)
// 提供 Blaze3D 优化、VMA、激进优化等 Phase 2 配置
// v5.4 新增: 算法加速路径调度配置 (Java/C++ 双路径)

package com.ranecc.renderium.presentation.ui.tabs;
import com.ranecc.renderium.domain.model.config.RenderiumConfig;

import com.ranecc.renderium.None;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 性能设置标签页（增强版）
 *
 * <p>提供以下配置项：
 * <ul>
 *   <li>帧图优化开关</li>
 *   <li>Vulkan 命令优化开关</li>
 *   <li>内存优化开关</li>
 *   <li>着色器管线优化开关</li>
 *   <li>VMA 增强开关</li>
 *   <li>激进优化开关</li>
 *   <li>现代渲染架构开关</li>
 *   <li>GPU 变换合并开关</li>
 * </ul>
 *
 * <h3>v5.4 新增 - 算法加速路径配置：</h3>
 * <ul>
 *   <li>GPU 占用率阈值滑块（控制 Java/C++ 路径切换）</li>
 *   <li>强制模式切换按钮 (Auto/Java/Native)</li>
 *   <li>BFS/LOD/Kahan 各算法独立开关</li>
 *   <li>实时路径统计显示</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.0.0
 * @version 5.4
 */
public class PerformanceSettingsTab extends Screen {

    /** 日志记录器 */
    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium-PerformanceTab");

    private final Screen parent;
    private final RenderiumConfig config;

    /** 引用 AdaptivePathSelector 用于显示统计信息（可为 null） */
    private final AdaptivePathSelector pathSelector;

    public PerformanceSettingsTab(Screen parent, RenderiumConfig config) {
        this(parent, config, null);
    }

    /**
     * 构造性能设置标签页
     *
     * @param parent 父屏幕
     * @param config 配置实例
     * @param pathSelector 自适应路径选择器（可为 null，用于显示统计）
     */
    public PerformanceSettingsTab(Screen parent, RenderiumConfig config,
                                   AdaptivePathSelector pathSelector) {
        super(MCAbstract.text("Performance Settings"));
        this.parent = parent;
        this.config = config;
        this.pathSelector = pathSelector;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = width / 2 + 60;
        int startY = 40;
        int spacing = 25;
        int buttonWidth = 200;
        int buttonHeight = 20;

        // ==================== 原有配置项 ====================

        // 帧图优化
        addButton(centerX, startY, buttonWidth, buttonHeight,
                config.isFrameGraphOptimizationEnabled(), "Frame Graph",
                enabled -> config.setFrameGraphOptimizationEnabled(enabled));

        // Vulkan 命令优化
        addButton(centerX, startY + spacing, buttonWidth, buttonHeight,
                config.isVulkanCommandOptimizationEnabled(), "Vulkan Cmd Opt",
                enabled -> config.setVulkanCommandOptimizationEnabled(enabled));

        // 内存优化
        addButton(centerX, startY + spacing * 2, buttonWidth, buttonHeight,
                config.isMemoryOptimizationEnabled(), "Memory Opt",
                enabled -> config.setMemoryOptimizationEnabled(enabled));

        // 着色器管线优化
        addButton(centerX, startY + spacing * 3, buttonWidth, buttonHeight,
                config.isShaderPipelineOptimizationEnabled(), "Shader Pipeline",
                enabled -> config.setShaderPipelineOptimizationEnabled(enabled));

        // VMA 增强
        addButton(centerX, startY + spacing * 4, buttonWidth, buttonHeight,
                config.isVmaEnhancementEnabled(), "VMA Enhanced",
                enabled -> config.setVmaEnhancementEnabled(enabled));

        // 激进优化
        addButton(centerX, startY + spacing * 5, buttonWidth, buttonHeight,
                config.isAggressiveOptimizationEnabled(), "Aggressive Opt",
                enabled -> config.setAggressiveOptimizationEnabled(enabled));

        // 现代渲染架构
        addButton(centerX, startY + spacing * 6, buttonWidth, buttonHeight,
                config.isModernRenderArchitectureEnabled(), "Modern Arch",
                enabled -> config.setModernRenderArchitectureEnabled(enabled));

        // GPU 变换合并
        addButton(centerX, startY + spacing * 7, buttonWidth, buttonHeight,
                config.isGpuTransformMergingEnabled(), "GPU Transform Merge",
                enabled -> config.setGpuTransformMergingEnabled(enabled));

        // ==================== v5.4 新增: 算法加速路径配置 ====================

        int algoStartY = startY + spacing * 9;  // 留出间隔
        int algoSpacing = 28;

        // 分隔标题: Algorithm Path Configuration
        addLabel(centerX, algoStartY - 15, "═══ Algorithm Path Config ═══");

        // GPU 占用率阈值滑块 (简化为按钮组: 50%/70%/90%)
        addGpuThresholdButtons(centerX, algoStartY, buttonWidth, buttonHeight);

        // 强制模式切换 (Auto → Java → Native → Auto 循环)
        addForceModeButton(centerX, algoStartY + algoSpacing, buttonWidth, buttonHeight);

        // BFS 算法开关
        addAlgorithmToggle(centerX, algoStartY + algoSpacing * 2, buttonWidth, buttonHeight,
                "BFS Occlusion", config.getAlgorithmConfig().getBfs().isEnabled(),
                enabled -> config.getAlgorithmConfig().getBfs().setEnabled(enabled));

        // BFS 优先 Native 开关
        addAlgorithmToggle(centerX, algoStartY + algoSpacing * 3, buttonWidth, buttonHeight,
                "BFS Prefer Native", config.getAlgorithmConfig().getBfs().isPreferNative(),
                enabled -> config.getAlgorithmConfig().getBfs().setPreferNative(enabled));

        // LOD 算法开关
        addAlgorithmToggle(centerX, algoStartY + algoSpacing * 4, buttonWidth, buttonHeight,
                "LOD Calculator", config.getAlgorithmConfig().getLod().isEnabled(),
                enabled -> config.getAlgorithmConfig().getLod().setEnabled(enabled));

        // Kahan 算法开关
        addAlgorithmToggle(centerX, algoStartY + algoSpacing * 5, buttonWidth, buttonHeight,
                "Kahan Accumulator", config.getAlgorithmConfig().getKahan().isEnabled(),
                enabled -> config.getAlgorithmConfig().getKahan().setEnabled(enabled));

        // 实时统计显示（只读标签）
        if (pathSelector != null) {
            addStatsLabel(centerX, algoStartY + algoSpacing * 7);
        }
    }

    /**
     * 添加性能优化开关按钮（使用 MCAbstract）
     *
     * @param centerX 中心 X 坐标
     * @param y Y 坐标
     * @param width 按钮宽度
     * @param height 按钮高度
     * @param enabled 当前启用状态
     * @param label 按钮标签文本
     * @param setter 状态设置回调
     */
    private void addButton(
            int centerX, int y, int width, int height,
            boolean enabled, String label,
            Consumer<Boolean> setter) {
        String displayText = enabled ? (label + ": ON") : (label + ": OFF");
        Button button = MCAbstract.buttonBuilder(centerX - width / 2, y, width, height)
                .text(displayText)
                .onClick(btn -> {
                    boolean newState = !enabled;
                    setter.accept(newState);
                    btn.setMessage(MCAbstract.text(newState ? (label + ": ON") : (label + ": OFF")));
                })
                .build();
        addRenderableWidget(button);
    }

    /**
     * 添加算法开关按钮（带图标前缀）
     *
     * @param centerX 中心 X 坐标
     * @param y Y 坐标
     * @param width 按钮宽度
     * @param height 按钮高度
     * @param label 标签文本
     * @param enabled 当前启用状态
     * @param setter 状态设置回调
     */
    private void addAlgorithmToggle(
            int centerX, int y, int width, int height,
            String label, boolean enabled,
            Consumer<Boolean> setter) {
        String prefix = enabled ? "[✓] " : "[ ] ";
        String displayText = prefix + label;
        Button button = MCAbstract.buttonBuilder(centerX - width / 2, y, width, height)
                .text(displayText)
                .onClick(btn -> {
                    boolean newState = !enabled;
                    setter.accept(newState);
                    String newPrefix = newState ? "[✓] " : "[ ] ";
                    btn.setMessage(MCAbstract.text(newPrefix + label));
                })
                .build();
        addRenderableWidget(button);
    }

    /**
     * 添加 GPU 占用率阈值按钮组 (50% / 70% / 90%)
     * <p>
     * 点击循环切换阈值，当前选中的高亮显示。
     *
     * @param centerX 中心 X 坐标
     * @param y Y 坐标
     * @param width 按钮宽度
     * @param height 按钮高度
     */
    private void addGpuThresholdButtons(int centerX, int y, int width, int height) {
        float currentThreshold = config.getAlgorithmConfig().getGpuUsageThreshold();

        // 将阈值映射到选项索引: 0=50%, 1=70%, 2=90%
        int selectedIndex;
        if (currentThreshold <= 0.55f) selectedIndex = 0;
        else if (currentThreshold <= 0.80f) selectedIndex = 1;
        else selectedIndex = 2;

        final float[] thresholds = {0.5f, 0.7f, 0.9f};
        final String[] labels = {"GPU Threshold: 50%", "GPU Threshold: 70%", "GPU Threshold: 90%"};
        final int[] currentIndex = {selectedIndex};

        Button button = MCAbstract.buttonBuilder(centerX - width / 2, y, width, height)
                .text(labels[currentIndex[0]])
                .onClick(btn -> {
                    currentIndex[0] = (currentIndex[0] + 1) % 3;
                    float newThreshold = thresholds[currentIndex[0]];
                    config.getAlgorithmConfig().setGpuUsageThreshold(newThreshold);
                    btn.setMessage(MCAbstract.text(labels[currentIndex[0]]));
                })
                .build();
        addRenderableWidget(button);
    }

    /**
     * 添加强制模式切换按钮 (Auto → Java → Native → Auto)
     *
     * @param centerX 中心 X 坐标
     * @param y Y 坐标
     * @param width 按钮宽度
     * @param height 按钮高度
     */
    private void addForceModeButton(int centerX, int y, int width, int height) {
        String currentMode = config.getAlgorithmConfig().getForceMode();
        final int[] modeIndex = {"auto".equals(currentMode) ? 0 :
                                  "java".equals(currentMode) ? 1 : 2};

        final String[] modes = {"auto", "java", "native"};
        final String[] labels = {"Mode: AUTO", "Mode: JAVA (Safe)", "Mode: NATIVE (Fast)"};

        Button button = MCAbstract.buttonBuilder(centerX - width / 2, y, width, height)
                .text(labels[modeIndex[0]])
                .onClick(btn -> {
                    modeIndex[0] = (modeIndex[0] + 1) % 3;
                    String newMode = modes[modeIndex[0]];
                    config.getAlgorithmConfig().setForceMode(newMode);
                    btn.setMessage(MCAbstract.text(labels[modeIndex[0]]));

                    // 同步到 AdaptivePathSelector（如果可用）
                    if (pathSelector != null) {
                        if ("java".equals(newMode)) {
                            pathSelector.setForceJava(true);
                        } else if ("native".equals(newMode)) {
                            pathSelector.setForceNative(true);
                        } else {
                            pathSelector.setForceJava(false);
                            pathSelector.setForceNative(false);
                        }
                    }
                })
                .build();
        addRenderableWidget(button);
    }

    /**
     * 添加分隔标签
     *
     * @param centerX 中心 X 坐标
     * @param y Y 坐标
     * @param text 标签文本
     */
    private void addLabel(int centerX, int y, String text) {
        // 使用 MCAbstract 创建纯文本标签（不可交互）
        // MC 26.2: Label 类可能已移除，使用 try-catch 降级处理
        try {
            Component labelComponent = MCAbstract.text(text);
            Object label = Class.forName("net.minecraft.client.gui.components.Label")
                .getConstructor(int.class, net.minecraft.network.chat.Component.class)
                .newInstance(centerX - MCAbstract.textWidth(text) / 2, y, labelComponent);
            // 使用反射调用 addChild 避免泛型约束问题（MC 26.2 API 变更）
            java.lang.reflect.Method addChildMethod = getClass().getMethod("addChild", net.minecraft.client.gui.components.events.GuiEventListener.class);
            addChildMethod.invoke(this, label);
        } catch (Exception e) {
            // Label 类不可用时静默忽略（非核心功能）
            LOGGER.trace("Failed to create Label: {}", e.getMessage());
        }
    }

    /**
     * 添加实时统计标签（显示当前路径使用情况）
     *
     * @param centerX 中心 X 坐标
     * @param y Y 坐标
     */
    private void addStatsLabel(int centerX, int y) {
        if (pathSelector == null) return;

        String statsText = pathSelector.getSchedulingStatistics();
        Component statsComponent = MCAbstract.text("§7" + statsText);  // §7 = 灰色

        // MC 26.2: Label 类可能已移除，使用反射降级处理
        try {
            Object label = Class.forName("net.minecraft.client.gui.components.Label")
                .getConstructor(int.class, net.minecraft.network.chat.Component.class)
                .newInstance(centerX - Math.min(MCAbstract.textWidth(statsText), 300) / 2, y, statsComponent);
            // 使用反射调用 addChild 避免泛型约束问题（MC 26.2 API 变更）
            java.lang.reflect.Method addChildMethod = getClass().getMethod("addChild", net.minecraft.client.gui.components.events.GuiEventListener.class);
            addChildMethod.invoke(this, label);
        } catch (Exception e) {
            LOGGER.trace("Failed to create StatsLabel: {}", e.getMessage());
        }
    }
}
