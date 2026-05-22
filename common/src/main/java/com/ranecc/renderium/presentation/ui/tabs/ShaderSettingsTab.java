package com.ranecc.renderium.presentation.ui.tabs;

import com.ranecc.renderium.feature.config.RenderiumConfigLoader;
import com.ranecc.renderium.presentation.ui.MCAbstract;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import java.util.logging.Logger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class ShaderSettingsTab extends Screen {

    private static final Logger LOGGER = Logger.getLogger("Renderium|ShaderSettingsTab");

    private final Screen parent;
    private final RenderiumConfigLoader configLoader;

    /** 脏标记：是否有未保存的更改 */
    private boolean dirty = false;

    /** 本地配置缓存（用于 UI 编辑，保存时写入 ConfigLoader） */
    private final Map<String, String> localValues = new ConcurrentHashMap<>();

    // ==================== UI 组件引用 ====================

    /** 全局开关按钮 */
    private Button enableButton;

    /** 预设选择按钮 */
    private Button presetButton;

    /** Shader Pack 路径显示按钮 */
    private Button shaderPackButton;

    /** 节点启用按钮数组 (8个节点) */
    private final Button[] nodeButtons = new Button[8];

    /** 参数滑块区域折叠状态 */
    private boolean slidersExpanded = false;

    /** 折叠/展开按钮 */
    private Button toggleSlidersButton;

    /** 保存按钮 */
    private Button saveButton;

    /** 重载 YAML 按钮 */
    private Button reloadButton;

    // ==================== 常量定义 ====================

    /** 预设枚举 */
    private enum ShaderPreset {
        OFF("OFF"),
        MINIMAL("MINIMAL"),
        BALANCED("BALANCED"),
        CINEMATIC("CINEMATIC"),
        ULTRA("ULTRA");

        private final String displayName;

        ShaderPreset(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public static ShaderPreset fromString(String name) {
            for (ShaderPreset p : values()) {
                if (p.displayName.equalsIgnoreCase(name)) {
                    return p;
                }
            }
            return BALANCED;
        }

        public ShaderPreset next() {
            int nextOrdinal = (this.ordinal() + 1) % values().length;
            return values()[nextOrdinal];
        }
    }

    /** 当前选中的预设 */
    private ShaderPreset currentPreset = ShaderPreset.BALANCED;

    /** 节点名称数组 */
    private static final String[] NODE_NAMES = {
        "Depth of Field",
        "Motion Blur",
        "Temporal AA",
        "Volumetric Fog",
        "SSR",
        "Lens Flare",
        "Chromatic Aberration",
        "Auto Exposure"
    };

    /** 节点配置键 */
    private static final String[] NODE_KEYS = {
        "depth_of_field.enabled",
        "motion_blur.enabled",
        "temporal_aa.enabled",
        "volumetric_fog.enabled",
        "screen_space_reflection.enabled",
        "lens_flare.enabled",
        "chromatic_aberration.enabled",
        "auto_exposure.enabled"
    };

    // ==================== 构造函数 ====================

    /**
     * 创建光影设置标签页
     *
     * @param parent 父屏幕
     */
    public ShaderSettingsTab(Screen parent) {
        super(MCAbstract.text("Shader Settings"));
        this.parent = parent;
        this.configLoader = RenderiumConfigLoader.getInstance();
        loadConfigToLocalCache();
    }

    // ==================== 初始化 ====================

    @Override
    protected void init() {
        super.init();

        int centerX = width / 2 + 60;
        int startY = 35;
        int spacing = 24;
        int buttonWidth = 200;
        int buttonHeight = 20;

        // ===== 第1行: 全局开关 =====
        boolean enabled = getLocalBool("renderium.enabled", false);
        enableButton = MCAbstract.buttonBuilder(centerX - 100, startY, buttonWidth, buttonHeight)
                .text(enabled ? "[ON]  Renderium Shader System" : "[OFF] Renderium Shader System")
                .onClick(btn -> toggleGlobalEnable())
                .build();
        addRenderableWidget(enableButton);

        // ===== 第2行: 预设选择器 =====
        String presetName = getLocalString("renderium.preset", "BALANCED");
        currentPreset = ShaderPreset.fromString(presetName);
        presetButton = MCAbstract.buttonBuilder(centerX - 100, startY + spacing, buttonWidth, buttonHeight)
                .text("Preset: " + currentPreset.getDisplayName())
                .onClick(btn -> cyclePreset())
                .build();
        addRenderableWidget(presetButton);

        // ===== 第3行: Shader Pack 选择 =====
        String packPath = getLocalString("renderium.pack.path", "internal");
        String displayPath = packPath.length() > 25 ? packPath.substring(0, 22) + "..." : packPath;
        shaderPackButton = MCAbstract.buttonBuilder(centerX - 100, startY + spacing * 2, buttonWidth, buttonHeight)
                .text("Pack: " + displayPath)
                .onClick(btn -> LOGGER.info("Shader pack browser not implemented yet"))
                .build();
        addRenderableWidget(shaderPackButton);

        // ===== 分隔线区域: 节点启用状态网格 (2列布局) =====
        int nodeStartY = startY + spacing * 3 + 10;
        int colSpacing = 110;
        int rowSpacing = 24;
        int nodeBtnWidth = 105;
        int nodeBtnHeight = 20;

        for (int i = 0; i < NODE_NAMES.length; i++) {
            int col = i % 2;
            int row = i / 2;
            int x = centerX - 100 + col * colSpacing;
            int y = nodeStartY + row * rowSpacing;

            boolean nodeEnabled = getLocalBool(NODE_KEYS[i], true);
            final int nodeIndex = i;

            nodeButtons[i] = MCAbstract.buttonBuilder(x, y, nodeBtnWidth, nodeBtnHeight)
                    .text(nodeEnabled ? "[x] " + NODE_NAMES[i] : "[ ] " + NODE_NAMES[i])
                    .onClick(btn -> toggleNode(nodeIndex))
                    .build();
            addRenderableWidget(nodeButtons[i]);
        }

        // ===== 参数滑块区域 (折叠面板) =====
        int sliderAreaY = nodeStartY + rowSpacing * 4 + 10;
        toggleSlidersButton = MCAbstract.buttonBuilder(centerX - 100, sliderAreaY, buttonWidth, buttonHeight)
                .text(slidersExpanded ? "▼ Parameter Sliders" : "▶ Parameter Sliders")
                .onClick(btn -> toggleSliderPanel())
                .build();
        addRenderableWidget(toggleSlidersButton);

        // 如果展开，添加参数滑块按钮
        if (slidersExpanded) {
            addParameterSliders(centerX, sliderAreaY + spacing, buttonWidth, buttonHeight);
        }

        // ===== 底部操作按钮 =====
        int bottomY = height - 45;
        saveButton = MCAbstract.buttonBuilder(centerX - 105, bottomY, 100, 20)
                .text(dirty ? "Save *" : "Save")
                .onClick(btn -> saveConfig())
                .build();
        addRenderableWidget(saveButton);

        reloadButton = MCAbstract.buttonBuilder(centerX + 5, bottomY, 100, 20)
                .text("Reload YAML")
                .onClick(btn -> reloadFromYaml())
                .build();
        addRenderableWidget(reloadButton);
    }

    // ==================== 配置读写方法 ====================

    /**
     * 从 ConfigLoader 加载配置到本地缓存
     *
     * 方法说明：读取所有光影相关配置到本地 Map，UI 操作只修改本地缓存
     */
    private void loadConfigToLocalCache() {
        localValues.clear();

        localValues.put("renderium.enabled", String.valueOf(configLoader.getBoolean("renderium.enabled", false)));
        localValues.put("renderium.preset", configLoader.getString("renderium.preset", "BALANCED"));
        localValues.put("renderium.pack.path", configLoader.getString("renderium.pack.path", "internal"));

        for (String key : NODE_KEYS) {
            localValues.put(key, String.valueOf(configLoader.getBoolean(key, true)));
        }

        DOF_PARAMS.forEach((k, v) -> localValues.put(k, String.valueOf(configLoader.getFloat(v.key, v.defaultVal))));
        MB_PARAMS.forEach((k, v) -> localValues.put(k, String.valueOf(configLoader.getFloat(v.key, v.defaultVal))));
        TAA_PARAMS.forEach((k, v) -> localValues.put(k, String.valueOf(configLoader.getFloat(v.key, v.defaultVal))));
        VOLFOG_PARAMS.forEach((k, v) -> localValues.put(k, String.valueOf(configLoader.getFloat(v.key, v.defaultVal))));

        dirty = false;
        LOGGER.info("Shader settings loaded to local cache");
    }

    /**
     * 获取本地缓存的字符串值
     *
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值
     */
    private String getLocalString(String key, String defaultValue) {
        return localValues.getOrDefault(key.toLowerCase(), defaultValue);
    }

    /**
     * 获取本地缓存的布尔值
     *
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值
     */
    private boolean getLocalBool(String key, boolean defaultValue) {
        String val = localValues.get(key.toLowerCase());
        if (val == null) return defaultValue;
        switch (val.toLowerCase()) {
            case "true": case "yes": case "1": case "on":
                return true;
            default:
                return false;
        }
    }

    /**
     * 获取本地缓存的浮点数值
     *
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值
     */
    private float getLocalFloat(String key, float defaultValue) {
        String val = localValues.get(key.toLowerCase());
        if (val == null) return defaultValue;
        try {
            return Float.parseFloat(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 设置本地缓存值并标记脏
     *
     * @param key 配置键
     * @param value 配置值
     */
    private void setLocalValue(String key, String value) {
        localValues.put(key.toLowerCase(), value);
        dirty = true;
    }

    // ==================== 事件处理方法 ====================

    /**
     * 切换全局开关状态
     *
     * 方法说明：反转 renderium.enabled 并更新按钮文本
     */
    private void toggleGlobalEnable() {
        boolean current = getLocalBool("renderium.enabled", false);
        boolean newState = !current;
        setLocalValue("renderium.enabled", String.valueOf(newState));
        enableButton.setMessage(MCAbstract.text(newState ? "[ON]  Renderium Shader System" : "[OFF] Renderium Shader System"));
        updateSaveButton();
    }

    /**
     * 循环切换预设
     *
     * 方法说明：在 OFF → MINIMAL → BALANCED → CINEMATIC → ULTA → OFF 之间循环
     */
    private void cyclePreset() {
        currentPreset = currentPreset.next();
        setLocalValue("renderium.preset", currentPreset.getDisplayName());
        presetButton.setMessage(MCAbstract.text("Preset: " + currentPreset.getDisplayName()));
        applyPresetDefaults(currentPreset);
        updateSaveButton();
    }

    /**
     * 应用预设默认值到各节点和参数
     *
     * @param preset 目标预设
     */
    private void applyPresetDefaults(ShaderPreset preset) {
        switch (preset) {
            case OFF:
                for (int i = 0; i < NODE_KEYS.length; i++) {
                    setLocalValue(NODE_KEYS[i], "false");
                    updateNodeButton(i, false);
                }
                break;
            case MINIMAL:
                setLocalValue(NODE_KEYS[0], "false"); setLocalValue(NODE_KEYS[1], "false");
                setLocalValue(NODE_KEYS[2], "true");  setLocalValue(NODE_KEYS[3], "false");
                setLocalValue(NODE_KEYS[4], "false"); setLocalValue(NODE_KEYS[5], "false");
                setLocalValue(NODE_KEYS[6], "false"); setLocalValue(NODE_KEYS[7], "true");
                refreshAllNodeButtons();
                break;
            case BALANCED:
                for (int i = 0; i < NODE_KEYS.length; i++) {
                    setLocalValue(NODE_KEYS[i], "true");
                }
                refreshAllNodeButtons();
                break;
            case CINEMATIC:
                for (int i = 0; i < NODE_KEYS.length; i++) {
                    setLocalValue(NODE_KEYS[i], "true");
                }
                setLocalValue("depth_of_field.focal_distance", "20.0");
                setLocalValue("depth_of_field.aperture", "4.0");
                refreshAllNodeButtons();
                break;
            case ULTRA:
                for (int i = 0; i < NODE_KEYS.length; i++) {
                    setLocalValue(NODE_KEYS[i], "true");
                }
                setLocalValue("depth_of_field.bokeh_samples", "32");
                setLocalValue("volumetric_fog.march_steps", "128");
                refreshAllNodeButtons();
                break;
        }
    }

    /**
     * 切换单个节点的启用状态
     *
     * @param nodeIndex 节点索引 (0-7)
     */
    private void toggleNode(int nodeIndex) {
        if (nodeIndex < 0 || nodeIndex >= NODE_KEYS.length) return;
        boolean current = getLocalBool(NODE_KEYS[nodeIndex], true);
        boolean newState = !current;
        setLocalValue(NODE_KEYS[nodeIndex], String.valueOf(newState));
        updateNodeButton(nodeIndex, newState);
        updateSaveButton();
    }

    /**
     * 更新单个节点按钮的文本
     *
     * @param nodeIndex 节点索引
     * @param enabled 是否启用
     */
    private void updateNodeButton(int nodeIndex, boolean enabled) {
        if (nodeButtons[nodeIndex] != null) {
            nodeButtons[nodeIndex].setMessage(MCAbstract.text(
                enabled ? "[x] " + NODE_NAMES[nodeIndex] : "[ ] " + NODE_NAMES[nodeIndex]
            ));
        }
    }

    /**
     * 刷新所有节点按钮显示
     */
    private void refreshAllNodeButtons() {
        for (int i = 0; i < NODE_KEYS.length; i++) {
            updateNodeButton(i, getLocalBool(NODE_KEYS[i], true));
        }
    }

    /**
     * 切换参数滑块面板的展开/折叠状态
     */
    private void toggleSliderPanel() {
        slidersExpanded = !slidersExpanded;
        clearWidgets();
        init();
    }

    // ==================== 参数滑块区域 ====================

    /** 参数元数据（用于创建可点击的调整按钮） */
    private static class ParamMeta {
        final String key;
        final String label;
        float defaultVal;
        float minVal;
        float maxVal;
        float step;

        ParamMeta(String key, String label, float defaultVal, float minVal, float maxVal, float step) {
            this.key = key;
            this.label = label;
            this.defaultVal = defaultVal;
            this.minVal = minVal;
            this.maxVal = maxVal;
            this.step = step;
        }
    }

    /** DOF 参数定义 */
    private static final Map<String, ParamMeta> DOF_PARAMS = new ConcurrentHashMap<>();
    static {
        DOF_PARAMS.put("dof_focal", new ParamMeta("depth_of_field.focal_distance", "Focal Distance", 15.0f, 0.5f, 100.0f, 1.0f));
        DOF_PARAMS.put("dof_aperture", new ParamMeta("depth_of_field.aperture", "Aperture", 2.8f, 1.0f, 16.0f, 0.2f));
        DOF_PARAMS.put("dof_bokeh", new ParamMeta("depth_of_field.bokeh_samples", "Bokeh Samples", 8.0f, 1.0f, 32.0f, 1.0f));
    }

    /** Motion Blur 参数定义 */
    private static final Map<String, ParamMeta> MB_PARAMS = new ConcurrentHashMap<>();
    static {
        MB_PARAMS.put("mb_strength", new ParamMeta("motion_blur.strength", "Strength", 0.5f, 0.0f, 1.0f, 0.05f));
        MB_PARAMS.put("mb_samples", new ParamMeta("motion_blur.sample_count", "Sample Count", 8.0f, 2.0f, 32.0f, 1.0f));
    }

    /** TAA 参数定义 */
    private static final Map<String, ParamMeta> TAA_PARAMS = new ConcurrentHashMap<>();
    static {
        TAA_PARAMS.put("taa_jitter", new ParamMeta("temporal_aa.jitter_strength", "Jitter Strength", 0.5f, 0.0f, 1.0f, 0.05f));
        TAA_PARAMS.put("taa_blend", new ParamMeta("temporal_aa.blend_factor", "Blend Factor", 0.9f, 0.5f, 0.99f, 0.01f));
    }

    /** VolFog 参数定义 */
    private static final Map<String, ParamMeta> VOLFOG_PARAMS = new ConcurrentHashMap<>();
    static {
        VOLFOG_PARAMS.put("volfog_density", new ParamMeta("volumetric_fog.density", "Density", 0.3f, 0.0f, 1.0f, 0.05f));
        VOLFOG_PARAMS.put("volfog_scatter", new ParamMeta("volumetric_fog.scattering", "Scattering", 0.5f, 0.0f, 1.0f, 0.05f));
        VOLFOG_PARAMS.put("volfog_march", new ParamMeta("volumetric_fog.march_steps", "March Steps", 48.0f, 8.0f, 128.0f, 4.0f));
    }

    /**
     * 添加参数滑块按钮组
     *
     * @param centerX 中心 X 坐标
     * @param startY 起始 Y 坐标
     * @param buttonWidth 按钮宽度
     * @param buttonHeight 按钮高度
     */
    private void addParameterSliders(int centerX, int startY, int buttonWidth, int buttonHeight) {
        int y = startY;
        int spacing = 22;
        int groupSpacing = 28;

        // --- DOF 分组 ---
        addParamGroupLabel(centerX, y, "--- DOF ---");
        y += 18;
        for (Map.Entry<String, ParamMeta> entry : DOF_PARAMS.entrySet()) {
            ParamMeta meta = entry.getValue();
            float val = getLocalFloat(meta.key, meta.defaultVal);
            addParamButton(centerX, y, buttonWidth, buttonHeight, entry.getKey(), meta, val);
            y += spacing;
        }
        y += groupSpacing;

        // --- Motion Blur 分组 ---
        addParamGroupLabel(centerX, y, "--- Motion Blur ---");
        y += 18;
        for (Map.Entry<String, ParamMeta> entry : MB_PARAMS.entrySet()) {
            ParamMeta meta = entry.getValue();
            float val = getLocalFloat(meta.key, meta.defaultVal);
            addParamButton(centerX, y, buttonWidth, buttonHeight, entry.getKey(), meta, val);
            y += spacing;
        }
        y += groupSpacing;

        // --- TAA 分组 ---
        addParamGroupLabel(centerX, y, "--- Temporal AA ---");
        y += 18;
        for (Map.Entry<String, ParamMeta> entry : TAA_PARAMS.entrySet()) {
            ParamMeta meta = entry.getValue();
            float val = getLocalFloat(meta.key, meta.defaultVal);
            addParamButton(centerX, y, buttonWidth, buttonHeight, entry.getKey(), meta, val);
            y += spacing;
        }
        y += groupSpacing;

        // --- VolFog 分组 ---
        addParamGroupLabel(centerX, y, "--- Volumetric Fog ---");
        y += 18;
        for (Map.Entry<String, ParamMeta> entry : VOLFOG_PARAMS.entrySet()) {
            ParamMeta meta = entry.getValue();
            float val = getLocalFloat(meta.key, meta.defaultVal);
            addParamButton(centerX, y, buttonWidth, buttonHeight, entry.getKey(), meta, val);
            y += spacing;
        }
    }

    /**
     * 添加分组标签（纯文本显示）
     *
     * @param centerX 中心 X
     * @param y Y 坐标
     * @param text 标签文本
     */
    private void addParamGroupLabel(int centerX, int y, String text) {
        Label label = new Label(centerX - 100, y, 200, 12, text);
        addRenderableWidget(label);
    }

    /**
     * 添加单个参数调整按钮（点击循环: 减少 → 显示值 → 增加）
     *
     * @param centerX 中心 X
     * @param y Y 坐标
     * @param width 按钮宽度
     * @param height 按钮高度
     * @param paramId 参数 ID
     * @meta 参数元数据
     * @param currentValue 当前值
     */
    private void addParamButton(int centerX, int y, int width, int height,
                                String paramId, ParamMeta meta, float currentValue) {
        String displayText = String.format("%s: %.2f", meta.label, currentValue);
        Button paramBtn = MCAbstract.buttonBuilder(centerX - 100, y, width, height)
                .text(displayText)
                .onClick(btn -> adjustParameter(paramId, meta))
                .build();
        addRenderableWidget(paramBtn);
    }

    /**
     * 调整参数值（点击时增加步进，超出最大值则回到最小值）
     *
     * @param paramId 参数 ID
     * @param meta 参数元数据
     */
    private void adjustParameter(String paramId, ParamMeta meta) {
        float current = getLocalFloat(meta.key, meta.defaultVal);
        float newVal = current + meta.step;
        if (newVal > meta.maxVal) {
            newVal = meta.minVal;
        }
        setLocalValue(meta.key, String.format("%.4f", newVal));

        clearWidgets();
        init();
        updateSaveButton();
    }

    // ==================== 保存与重载 ====================

    /**
     * 保存配置到 ConfigLoader
     *
     * 方法说明：将本地缓存的所有值写回 ConfigLoader.rawValues，
     * 实际持久化由 ConfigLoader 的 save 方法完成
     */
    private void saveConfig() {
        if (!dirty) {
            LOGGER.info("No changes to save");
            return;
        }

        for (Map.Entry<String, String> entry : localValues.entrySet()) {
            configLoader.setRawValue(entry.getKey(), entry.getValue());
        }

        dirty = false;
        updateSaveButton();
        LOGGER.info("Shader settings saved (" + localValues.size() + " parameters)");
    }

    /**
     * 从 YAML 重载配置
     *
     * 方法说明：调用 ConfigLoader.reload() 重新加载默认配置，
     * 然后刷新本地缓存并重建 UI
     */
    private void reloadFromYaml() {
        configLoader.reload();
        loadConfigToLocalCache();
        clearWidgets();
        init();
        LOGGER.info("Shader settings reloaded from YAML");
    }

    /**
     * 更新保存按钮显示（根据 dirty 状态显示 * 号）
     */
    private void updateSaveButton() {
        if (saveButton != null) {
            saveButton.setMessage(MCAbstract.text(dirty ? "Save *" : "Save"));
        }
    }

    // ==================== 内部标签组件 ====================

    /**
     * 简单文本标签组件（用于显示分组标题）
     */
    private static class Label extends net.minecraft.client.gui.components.AbstractWidget {
        private final String text;

        public Label(int x, int y, int width, int height, String text) {
            super(x, y, width, height, MCAbstract.text(text));
            this.text = text;
        }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.drawCenteredString(net.minecraft.client.Minecraft.getInstance().font,
                text, getX() + width / 2, getY(), 0xFFAAAAAA);
        }

        @Override
        public void onClick(double mouseX, double mouseY) { }
    }
}
