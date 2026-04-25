package com.renderium.config;

import java.util.Properties;

/**
 * 后处理效果 (Post-processing Effects) 配置
 *
 * <p>控制 Bloom、DOF、MotionBlur 等后处理效果的启用状态和参数。
 * 所有参数都支持运行时动态修改（通过 UI 或 Lua 脚本）。
 *
 * @author Renderium Team
 * @since 1.0.0
 * @version 5.0
 */
public final class EffectsConfig {

    /** 是否启用 Bloom 泛光效果 */
    private boolean bloomEnabled = true;

    /** Bloom 强度 (0.0 - 2.0) */
    private float bloomIntensity = 0.5f;

    /** Bloom 高亮阈值 (0.0 - 1.0) */
    private float bloomThreshold = 0.8f;

    /** Bloom 模糊半径 (1 - 16) */
    private float bloomRadius = 6.0f;

    /** 是否启用 DOF 景深效果 */
    private boolean dofEnabled = false;

    /** DOF 焦距（世界单位） */
    private float dofFocalDistance = 50.0f;

    /** DOF 光圈大小 */
    private float dofAperture = 4.0f;

    /** DOF 模糊强度 (0.0 - 1.0) */
    private float dofBlurStrength = 0.5f;

    /** 是否启用运动模糊效果 */
    private boolean motionBlurEnabled = false;

    /** 运动模糊强度 (0.0 - 1.0) */
    private float motionBlurStrength = 0.5f;

    /** 运动模糊采样数 (2 - 32) */
    private int motionBlurSampleCount = 8;

    /**
     * 默认构造函数
     */
    public EffectsConfig() {}

    /**
     * 从 Properties 对象加载配置
     *
     * @param props 属性集合，键前缀为 "effects."
     * @return EffectsConfig 实例
     */
    public static EffectsConfig fromProperties(Properties props) {
        EffectsConfig config = new EffectsConfig();

        // 加载 Bloom 配置
        config.bloomEnabled = Boolean.parseBoolean(
            props.getProperty("effects.bloom.enabled", "true")
        );
        config.bloomIntensity = Float.parseFloat(
            props.getProperty("effects.bloom.intensity", "0.5")
        );
        config.bloomThreshold = Float.parseFloat(
            props.getProperty("effects.bloom.threshold", "0.8")
        );
        config.bloomRadius = Float.parseFloat(
            props.getProperty("effects.bloom.radius", "6.0")
        );

        // 加载 DOF 配置
        config.dofEnabled = Boolean.parseBoolean(
            props.getProperty("effects.dof.enabled", "false")
        );
        config.dofFocalDistance = Float.parseFloat(
            props.getProperty("effects.dof.focalDistance", "50.0")
        );
        config.dofAperture = Float.parseFloat(
            props.getProperty("effects.dof.aperture", "4.0")
        );
        config.dofBlurStrength = Float.parseFloat(
            props.getProperty("effects.dof.blurStrength", "0.5")
        );

        // 加载 MotionBlur 配置
        config.motionBlurEnabled = Boolean.parseBoolean(
            props.getProperty("effects.motionBlur.enabled", "false")
        );
        config.motionBlurStrength = Float.parseFloat(
            props.getProperty("effects.motionBlur.strength", "0.5")
        );
        config.motionBlurSampleCount = Integer.parseInt(
            props.getProperty("effects.motionBlur.sampleCount", "8")
        );

        return config;
    }

    /**
     * 将配置写入 Properties 对象
     *
     * @param props 属性集合
     */
    public void toProperties(Properties props) {
        // 保存 Bloom 配置
        props.setProperty("effects.bloom.enabled", String.valueOf(bloomEnabled));
        props.setProperty("effects.bloom.intensity", String.valueOf(bloomIntensity));
        props.setProperty("effects.bloom.threshold", String.valueOf(bloomThreshold));
        props.setProperty("effects.bloom.radius", String.valueOf(bloomRadius));

        // 保存 DOF 配置
        props.setProperty("effects.dof.enabled", String.valueOf(dofEnabled));
        props.setProperty("effects.dof.focalDistance", String.valueOf(dofFocalDistance));
        props.setProperty("effects.dof.aperture", String.valueOf(dofAperture));
        props.setProperty("effects.dof.blurStrength", String.valueOf(dofBlurStrength));

        // 保存 MotionBlur 配置
        props.setProperty("effects.motionBlur.enabled", String.valueOf(motionBlurEnabled));
        props.setProperty("effects.motionBlur.strength", String.valueOf(motionBlurStrength));
        props.setProperty("effects.motionBlur.sampleCount", String.valueOf(motionBlurSampleCount));
    }

    /** 是否启用 Bloom 泛光效果 */
    public boolean isBloomEnabled() { return bloomEnabled; }
    public void setBloomEnabled(boolean enabled) { this.bloomEnabled = enabled; }

    /** Bloom 强度 */
    public float getBloomIntensity() { return bloomIntensity; }
    public void setBloomIntensity(float intensity) { this.bloomIntensity = intensity; }

    /** Bloom 高亮阈值 */
    public float getBloomThreshold() { return bloomThreshold; }
    public void setBloomThreshold(float threshold) { this.bloomThreshold = threshold; }

    /** Bloom 模糊半径 */
    public float getBloomRadius() { return bloomRadius; }
    public void setBloomRadius(float radius) { this.bloomRadius = radius; }

    /** 是否启用 DOF 景深效果 */
    public boolean isDofEnabled() { return dofEnabled; }
    public void setDofEnabled(boolean enabled) { this.dofEnabled = enabled; }

    /** DOF 焦距 */
    public float getDofFocalDistance() { return dofFocalDistance; }
    public void setDofFocalDistance(float distance) { this.dofFocalDistance = distance; }

    /** DOF 光圈大小 */
    public float getDofAperture() { return dofAperture; }
    public void setDofAperture(float aperture) { this.dofAperture = aperture; }

    /** DOF 模糊强度 */
    public float getDofBlurStrength() { return dofBlurStrength; }
    public void setDofBlurStrength(float strength) { this.dofBlurStrength = strength; }

    /** 是否启用运动模糊效果 */
    public boolean isMotionBlurEnabled() { return motionBlurEnabled; }
    public void setMotionBlurEnabled(boolean enabled) { this.motionBlurEnabled = enabled; }

    /** 运动模糊强度 */
    public float getMotionBlurStrength() { return motionBlurStrength; }
    public void setMotionBlurStrength(float strength) { this.motionBlurStrength = strength; }

    /** 运动模糊采样数 */
    public int getMotionBlurSampleCount() { return motionBlurSampleCount; }
    public void setMotionBlurSampleCount(int count) { this.motionBlurSampleCount = count; }
}
