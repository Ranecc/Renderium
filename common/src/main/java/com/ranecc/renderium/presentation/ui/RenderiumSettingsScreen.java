// Renderium - 设置界面核心组件
// 提供类似 Sodium 的多标签页设置界面
// 支持兼容模式（追加到 Sodium）和狂暴模式（独立界面）

package com.ranecc.renderium.presentation.ui;
import com.ranecc.renderium.application.core.RenderiumCore;
import com.ranecc.renderium.domain.model.config.RenderiumConfig;
import com.ranecc.renderium.presentation.ui.tabs.CullingSettingsTab;
import com.ranecc.renderium.presentation.ui.tabs.DebugSettingsTab;
import com.ranecc.renderium.presentation.ui.tabs.FrameGenerationSettingsTab;
import com.ranecc.renderium.presentation.ui.tabs.GeneralSettingsTab;
import com.ranecc.renderium.presentation.ui.tabs.InterceptionSettingsTab;
import com.ranecc.renderium.presentation.ui.tabs.PerformanceSettingsTab;
import com.ranecc.renderium.presentation.ui.tabs.ReflexSettingsTab;
import com.ranecc.renderium.presentation.ui.tabs.SuperResolutionSettingsTab;
import com.ranecc.renderium.presentation.ui.tabs.ShaderSettingsTab;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Renderium 设置界面基类
 *
 * <p>提供类似 Sodium 的多标签页设置界面，支持：
 * <ul>
 *   <li>常规设置（General）：运行模式切换、基础开关</li>
 *   <li>超分辨率（Super Resolution）：DLSS/FSR/XeSS 配置</li>
 *   <li>帧生成（Frame Generation）：DLSS-FG/FSR-FG 配置</li>
 *   <li>Reflex：NVIDIA Reflex 低延迟配置</li>
 *   <li>剔除（Culling）：高级剔除优化配置</li>
 *   <li>性能（Performance）：Blaze3D 优化、VMA、激进优化等</li>
 * </ul>
 *
 * <h3>使用场景</h3>
 * <ol>
 *   <li><b>兼容模式</b>：通过 ModMenu 或 Sodium 扩展点打开</li>
 *   <li><b>狂暴模式</b>：替换原版 VideoSettingsScreen</li>
 * </ol>
 *
 * @see RenderiumConfig
 * @see RenderiumDualModeManager
 * @author Renderium Team
 * @since 1.0.0
 * @version 1.0
 */
public class RenderiumSettingsScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium-SettingsUI");

    /** 父屏幕（用于返回） */
    private final Screen parent;

    /** 当前配置实例 */
    private final RenderiumConfig config;

    /** 双模式管理器 */
    private final RenderiumDualModeManager modeManager;

    /** 标签页列表 */
    private final List<TabInfo> tabs = new ArrayList<>();

    /** 当前选中的标签页索引 */
    private int selectedTabIndex = 0;

    /** 标签页按钮列表 */
    private List<Button> tabButtons = new ArrayList<>();

    /** 标签页内容容器 */
    private Screen currentTabContent;

    /** 保存按钮 */
    private Button saveButton;

    /** 重置按钮 */
    private Button resetButton;

    /**
     * 创建 Renderium 设置界面
     *
     * @param parent 父屏幕
     * @param config 配置实例
     */
    public RenderiumSettingsScreen(Screen parent, RenderiumConfig config) {
        // TODO: MC 26.2 中 Component.translatable() 需要 brigadier 依赖
        // 临时使用 Component.literal() 替代，后续需要添加翻译支持
        super(Component.literal("Renderium Settings"));
        this.parent = parent;
        this.config = config;
        this.modeManager = RenderiumDualModeManager.getInstance();
        initializeTabs();
    }

    /**
     * 初始化所有标签页
     */
    private void initializeTabs() {
        // 添加所有标签页
        tabs.add(new TabInfo("renderium.config.category.general", this::createGeneralTab));
        tabs.add(new TabInfo("renderium.config.category.super_resolution", this::createSuperResolutionTab));
        tabs.add(new TabInfo("renderium.config.category.frame_generation", this::createFrameGenerationTab));
        tabs.add(new TabInfo("renderium.config.category.reflex", this::createReflexTab));
        tabs.add(new TabInfo("renderium.config.category.culling", this::createCullingTab));
        tabs.add(new TabInfo("renderium.config.category.interception", this::createInterceptionTab));  // Phase 7 新增
        tabs.add(new TabInfo("renderium.config.category.performance", this::createPerformanceTab));
        tabs.add(new TabInfo("renderium.config.category.debug", this::createDebugTab));
        tabs.add(new TabInfo("renderium.config.category.shader", this::createShaderTab));
    }

    @Override
    protected void init() {
        super.init();

        // 创建标签页按钮区域（左侧）
        createTabButtons();

        // 创建当前标签页内容
        switchToTab(selectedTabIndex);

        // 创建底部按钮（保存/重置/完成）
        createBottomButtons();
    }

    /**
     * 创建左侧标签页按钮
     */
    private void createTabButtons() {
        tabButtons.clear();
        clearWidgets();

        int tabWidth = 120;
        int tabHeight = 20;
        int tabX = 10;
        int tabY = 30;
        int tabSpacing = 2;

        for (int i = 0; i < tabs.size(); i++) {
            TabInfo tab = tabs.get(i);
            // 使用 final 局部变量解决 lambda final 问题
            final String tabLabel = tab.translationKey;
            final int tabIndex = i; // 解决 lambda 中引用非 final 变量的问题
            Button tabButton = Button.builder(
                    MCAbstract.text(tabLabel),
                    btn -> switchToTab(tabIndex)
            )
            .bounds(tabX, tabY + i * (tabHeight + tabSpacing), tabWidth, tabHeight)
            .build();

            // 高亮当前选中的标签页
            if (i == selectedTabIndex) {
                tabButton.active = false;
            }

            tabButtons.add(tabButton);
            addRenderableWidget(tabButton);
        }
    }

    /**
     * 切换到指定标签页
     *
     * @param tabIndex 标签页索引
     */
    private void switchToTab(int tabIndex) {
        if (tabIndex < 0 || tabIndex >= tabs.size()) {
            return;
        }

        selectedTabIndex = tabIndex;

        // 移除旧的内容组件
        if (currentTabContent != null) {
            children().removeIf(listener -> {
                // 保留标签页按钮和底部按钮
                return !tabButtons.contains(listener) &&
                       listener != saveButton &&
                       listener != resetButton;
            });
        }

        // 更新标签页按钮状态
        for (int i = 0; i < tabButtons.size(); i++) {
            tabButtons.get(i).active = (i != tabIndex);
        }

        // 创建新标签页内容
        TabInfo tab = tabs.get(tabIndex);
        currentTabContent = tab.contentFactory.create();

        // 添加新内容组件
        if (currentTabContent != null) {
            // MC 26.x API: Screen.init(int width, int height)
            currentTabContent.init(width, height);
            // 子屏幕的组件会自动通过 children() 管理
            // 不需要手动 addRenderableWidget
            // currentTabContent 作为嵌套屏幕使用
        }
    }

    /**
     * 创建底部按钮（保存/重置/完成）
     */
    private void createBottomButtons() {
        int buttonWidth = 100;
        int buttonHeight = 20;
        int buttonY = height - 30;
        int buttonSpacing = 10;

        // 保存按钮
        saveButton = Button.builder(
                Component.literal("Save"),  // 临时使用 literal
                btn -> saveConfig()
        )
        .bounds(width / 2 - buttonWidth - buttonSpacing / 2, buttonY, buttonWidth, buttonHeight)
        .build();
        addRenderableWidget(saveButton);

        // 重置按钮
        resetButton = Button.builder(
                Component.literal("Reset"),  // 临时使用 literal
                btn -> resetConfig()
        )
        .bounds(width / 2 + buttonSpacing / 2, buttonY, buttonWidth, buttonHeight)
        .build();
        addRenderableWidget(resetButton);

        // 完成按钮（返回父屏幕）
        Button doneButton = Button.builder(
                Component.literal("Done"),  // 临时使用 literal
                btn -> onClose()
        )
        .bounds(width / 2 - buttonWidth / 2, buttonY + 25, buttonWidth, buttonHeight)
        .build();
        addRenderableWidget(doneButton);
    }

    /**
     * 渲染设置界面
     *
     * <p>MC 26.2 API: Screen.render() 方法签名已变更
     * 此方法保留用于兼容性，但不再覆盖父类方法。
     */
    // MC 26.2 兼容性: 不再覆盖父类的 render() 方法
    // TODO: 适配新的渲染管线
    public void renderScreen(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // MC 26.2 使用标准渲染管线（如果需要可在此添加自定义渲染）
        LOGGER.debug("Rendering RenderiumSettingsScreen (MC 26.2 mode)");
    }

    @Override
    public void onClose() {
        // MC 26.2 API: 使用 minecraft.gui.setScreen() 替代已废弃的 setScreen()
        if (minecraft != null) {
            minecraft.gui.setScreen(parent);
        }
    }

    // ==================== 标签页内容创建方法 ====================

    /**
     * 创建常规设置标签页
     *
     * @return 常规设置界面
     */
    private Screen createGeneralTab() {
        return new GeneralSettingsTab(this, config);
    }

    /**
     * 创建超分辨率设置标签页
     *
     * @return 超分辨率设置界面
     */
    private Screen createSuperResolutionTab() {
        return new SuperResolutionSettingsTab(this, config);
    }

    /**
     * 创建帧生成设置标签页
     *
     * @return 帧生成设置界面
     */
    private Screen createFrameGenerationTab() {
        return new FrameGenerationSettingsTab(this, config);
    }

    /**
     * 创建 Reflex 设置标签页
     *
     * @return Reflex 设置界面
     */
    private Screen createReflexTab() {
        return new ReflexSettingsTab(this, config);
    }

    /**
     * 创建剔除设置标签页
     *
     * @return 剔除设置界面
     */
    private Screen createCullingTab() {
        return new CullingSettingsTab(this, config);
    }

    /**
     * 创建拦截层设置标签页（Phase 7 新增）
     *
     * <p>提供 Blaze3D 拦截层系统的完整配置界面，包括：
     * <ul>
     *   <li>前拦截层：模组检测、LOD 注入、剔除优化</li>
     *   <li>后拦截层：帧捕获、超分辨率、帧生成、后处理</li>
     *   <li>Sodium 重定向器配置</li>
     * </ul>
     *
     * @return 拦截层设置界面
     * @since 5.1.0
     */
    private Screen createInterceptionTab() {
        return new InterceptionSettingsTab(this, config);
    }

    /**
     * 创建性能设置标签页
     *
     * @return 性能设置界面
     */
    private Screen createPerformanceTab() {
        return new PerformanceSettingsTab(this, config);
    }

    /**
     * 创建设置标签页
     *
     * <p>提供以下开发期工具：
     * <ul>
     *   <li>FFM 调试开关（VulkanFFMDebugger.DEBUG_ENABLED）</li>
     *   <li>日志输出级别控制（ALL ~ OFF）</li>
     *   <li>FFM 慢调用阈值调节（1ms ~ 100ms）</li>
     *   <li>FFM 调试统计报告输出</li>
     *   <li>Vulkan FFM 运行时信息</li>
     *   <li>调试计数器重置</li>
     * </ul>
     *
     * @return 调试设置界面
     * @since 5.6.0
     */
    private Screen createDebugTab() {
        return new DebugSettingsTab(this, config);
    }

    private Screen createShaderTab() {
        // MC 26.2: ShaderSettingsTab 构造函数只接受 (Screen parent)
        // config 通过 RenderiumConfigLoader.getInstance() 内部获取
        return new ShaderSettingsTab(this);
    }

    // ==================== 配置操作方法 ====================

    /**
     * 保存配置到文件
     */
    private void saveConfig() {
        try {
            RenderiumCore core = RenderiumCore.getInstance();
            core.saveConfig();
            LOGGER.info("Renderium 配置已保存");
        } catch (Exception e) {
            LOGGER.error("保存 Renderium 配置失败", e);
        }
    }

    /**
     * 重置配置为默认值
     */
    private void resetConfig() {
        try {
            RenderiumCore core = RenderiumCore.getInstance();
            core.resetConfig();
            LOGGER.info("Renderium 配置已重置为默认值");
            // 重新加载当前标签页
            switchToTab(selectedTabIndex);
        } catch (Exception e) {
            LOGGER.error("重置 Renderium 配置失败", e);
        }
    }

    // ==================== 内部类 ====================

    /**
     * 标签页信息
     */
    private static class TabInfo {
        /** 翻译键 */
        final String translationKey;

        /** 内容工厂方法 */
        final ScreenFactory contentFactory;

        TabInfo(String translationKey, ScreenFactory contentFactory) {
            this.translationKey = translationKey;
            this.contentFactory = contentFactory;
        }
    }

    /**
     * 屏幕工厂函数式接口
     */
    @FunctionalInterface
    private interface ScreenFactory {
        Screen create();
    }
}
