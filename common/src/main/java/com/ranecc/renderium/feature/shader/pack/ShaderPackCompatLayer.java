package com.ranecc.renderium.feature.shader.pack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * RGB 光影包兼容层 — 对接外部光影包的参数和纹理
 * <p>
 * 支持 Iris/OptiFine 光影包格式，将光影包参数映射到 Renderium 管线节点。
 * 不启用就短路：无光影包加载时零开销。
 * <p>
 * 兼容的光影包格式：
 * <ul>
 *   <li>Iris Shaders (v1.6+)</li>
 *   <li>OptiFine Shaders (C8+)</li>
 *   <li>Vanilla Shader (内置)</li>
 * </ul>
 *
 * @since 5.5.0
 */
public final class ShaderPackCompatLayer {

    private static final Logger LOGGER = Logger.getLogger("Renderium|ShaderPack");

    // ==================== 单例 ====================

    private static volatile ShaderPackCompatLayer instance;

    public static ShaderPackCompatLayer getInstance() {
        if (instance == null) {
            synchronized (ShaderPackCompatLayer.class) {
                if (instance == null) instance = new ShaderPackCompatLayer();
            }
        }
        return instance;
    }

    private ShaderPackCompatLayer() {}

    // ==================== 光影包状态 ====================

    /** 当前加载的光影包名称 */
    private volatile String activePackName = "internal";

    /** 光影包是否已加载 */
    private volatile boolean packLoaded = false;

    /** 光影包参数映射表 */
    private final ConcurrentHashMap<String, Float> floatParams = new ConcurrentHashMap<>();

    /** 光影包开关映射表 */
    private final ConcurrentHashMap<String, Boolean> toggleParams = new ConcurrentHashMap<>();

    /** 光影包纹理映射表 (名称 → Bindless 索引) */
    private final ConcurrentHashMap<String, Integer> textureBindings = new ConcurrentHashMap<>();

    // ==================== 光影包参数 → 管线节点映射 ====================

    /**
     * 光影包参数到 Renderium 管线节点的映射规则
     * <p>
     * Iris/OptiFine 光影包使用标准化的 uniform 名称，
     * 此表将其映射到 Renderium 管线节点的可调参数。
     */
    private static final Map<String, String> PARAM_MAPPING = Map.ofEntries(
        // Iris/OptiFine uniform → Renderium 节点.参数
        Map.entry("shadowMapResolution", "shadow_map.resolution"),
        Map.entry("shadowDistance", "shadow_map.distance"),
        Map.entry("wetnessHalfLife", "volumetric_fog.falloff"),
        Map.entry("drynessHalfLife", "volumetric_fog.density"),
        Map.entry("sunPathRotation", "direct_light.sun_rotation"),
        Map.entry("ambientOcclusionLevel", "ssao.intensity"),
        Map.entry("SSAO_QUALITY", "ssao.quality"),
        Map.entry("GI_QUALITY", "indirect_light.bounces"),
        Map.entry("VOLUMETRIC_LIGHT", "volumetric_fog.enabled"),
        Map.entry("DEPTH_OF_FIELD", "depth_of_field.enabled"),
        Map.entry("MOTION_BLUR", "motion_blur_enhanced.enabled"),
        Map.entry("TAA", "temporal_aa.enabled"),
        Map.entry("BLOOM_QUALITY", "bloom.quality"),
        Map.entry("REFLECTION_QUALITY", "ssr.quality")
    );

    // ==================== 公共 API ====================

    /**
     * 加载光影包
     *
     * @param packName 光影包名称
     * @param params 光影包参数 (uniform 名称 → 值)
     * @return 是否成功加载
     */
    public boolean loadPack(String packName, Map<String, ?> params) {
        if (packName == null || packName.isEmpty()) {
            unloadPack();
            return false;
        }

        this.activePackName = packName;
        this.packLoaded = true;
        this.floatParams.clear();
        this.toggleParams.clear();

        // 解析参数并映射到管线节点
        for (Map.Entry<String, ?> entry : params.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // 映射到 Renderium 节点参数
            String mappedKey = PARAM_MAPPING.getOrDefault(key, key);

            if (value instanceof Number n) {
                floatParams.put(mappedKey, n.floatValue());
            } else if (value instanceof Boolean b) {
                toggleParams.put(mappedKey, b);
            }
        }

        LOGGER.info("光影包加载: " + packName + " (" + params.size() + " 参数, " +
                     floatParams.size() + " 浮点, " + toggleParams.size() + " 开关)");
        return true;
    }

    /**
     * 卸载当前光影包
     */
    public void unloadPack() {
        this.activePackName = "internal";
        this.packLoaded = false;
        this.floatParams.clear();
        this.toggleParams.clear();
        this.textureBindings.clear();
    }

    /**
     * 获取浮点参数（带回退值）
     */
    public float getFloatParam(String key, float defaultValue) {
        return floatParams.getOrDefault(key, defaultValue);
    }

    /**
     * 获取开关参数（回退 false）
     */
    public boolean getToggleParam(String key) {
        return toggleParams.getOrDefault(key, false);
    }

    /**
     * 注册纹理绑定
     */
    public void registerTexture(String name, int bindlessIndex) {
        textureBindings.put(name, bindlessIndex);
    }

    /**
     * 获取纹理 Bindless 索引
     */
    public int getTextureIndex(String name) {
        return textureBindings.getOrDefault(name, -1);
    }

    /**
     * 是否有光影包加载
     */
    public boolean isPackLoaded() { return packLoaded; }

    /**
     * 获取当前光影包名称
     */
    public String getActivePackName() { return activePackName; }

    /**
     * 获取所有映射后的参数（供管线节点读取）
     */
    public Map<String, Float> getAllFloatParams() {
        return Collections.unmodifiableMap(floatParams);
    }

    public Map<String, Boolean> getAllToggleParams() {
        return Collections.unmodifiableMap(toggleParams);
    }
}
