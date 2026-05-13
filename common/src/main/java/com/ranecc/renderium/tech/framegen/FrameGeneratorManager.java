// Renderium - Frame Generation Manager
// 帧生成技术统一管理器

package com.ranecc.renderium.tech.framegen;

import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 帧生成管理器
 * <p>
 * 统一管理 DLSS FG、FSR FG 等帧生成技术。
 * 提供自动检测和手动选择功能。
 * <p>
 * 降级优先级：DLSS FG → FSR FG → OFF
 *
 * @see FrameGenerator
 * @see FrameGenMode
 */
public final class FrameGeneratorManager {

    private static final Logger LOGGER = Logger.getLogger(FrameGeneratorManager.class.getName());

    /** 单例实例 */
    private static volatile FrameGeneratorManager instance;

    private final Map<FrameGenType, FrameGenerator> generators = new EnumMap<>(FrameGenType.class);
    private FrameGenerator activeGenerator;
    private FrameGenMode currentMode = FrameGenMode.OFF;

    /**
     * 获取单例实例
     *
     * @return FrameGeneratorManager 单例实例
     */
    public static FrameGeneratorManager getInstance() {
        if (instance == null) {
            synchronized (FrameGeneratorManager.class) {
                if (instance == null) {
                    instance = new FrameGeneratorManager();
                }
            }
        }
        return instance;
    }

    /**
     * 设置是否启用帧生成
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        if (enabled) {
            if (activeGenerator == null && !detectAndSelect()) {
                LOGGER.warning("无法启用帧生成：无可用帧生成器");
                return;
            }
            enable(currentMode != FrameGenMode.OFF ? currentMode : FrameGenMode.FIXED_2X);  // 使用 FIXED_2X 替代不存在的 HIGH
        } else {
            disable();
        }
    }

    /**
     * 设置超分辨率模式
     *
     * @param mode 模式字符串（OFF/NIS/FSR/DLSS）
     */
    public void setSrMode(Object mode) {
        String modeStr = mode != null ? mode.toString().toUpperCase() : "OFF";
        try {
            FrameGenMode fgMode = FrameGenMode.valueOf(modeStr);
            if (fgMode == FrameGenMode.OFF) {
                disable();
            } else {
                enable(fgMode);
            }
        } catch (IllegalArgumentException e) {
            LOGGER.warning("未知的超分辨率模式: " + modeStr + ", 使用 OFF");
            disable();
        }
    }

    /**
     * 帧生成技术类型
     */
    public enum FrameGenType {
        DLSS_FG("DLSS Frame Generation", 0),
        FSR_FG("FSR Frame Generation", 1);

        public final String displayName;
        public final int priority;

        FrameGenType(String displayName, int priority) {
            this.displayName = displayName;
            this.priority = priority;
        }
    }

    public FrameGeneratorManager() {}

    /**
     * 注册帧生成器
     */
    public void registerGenerator(FrameGenType type, FrameGenerator generator) {
        generators.put(type, generator);
        LOGGER.info("Registered frame generator: " + generator.getName());
    }

    /**
     * 自动检测并选择最佳帧生成技术
     *
     * @return 是否有可用的帧生成技术
     */
    public boolean detectAndSelect() {
        FrameGenType[] priority = {FrameGenType.DLSS_FG, FrameGenType.FSR_FG};

        for (FrameGenType type : priority) {
            FrameGenerator gen = generators.get(type);
            if (gen != null && gen.isSupported()) {
                activeGenerator = gen;
                LOGGER.info("Selected frame generator: " + gen.getName());
                return true;
            }
        }

        LOGGER.info("No frame generation technology available");
        return false;
    }

    /**
     * 启用帧生成
     *
     * @param mode 帧生成模式
     * @return 是否成功启用
     */
    public boolean enable(FrameGenMode mode) {
        if (activeGenerator == null || !activeGenerator.isSupported()) {
            LOGGER.warning("No supported frame generator available");
            return false;
        }

        activeGenerator.enable(mode);
        currentMode = mode;
        LOGGER.info("Frame generation enabled: " + activeGenerator.getName() + " @ " + mode);
        return true;
    }

    /**
     * 禁用帧生成
     */
    public void disable() {
        if (activeGenerator != null) {
            activeGenerator.disable();
        }
        currentMode = FrameGenMode.OFF;
    }

    /**
     * 执行帧生成
     */
    public void generateFrame(FrameGenerator.FrameGenData currentFrame,
                               FrameGenerator.FrameGenData previousFrame,
                               long cmdBuffer) {
        if (activeGenerator != null && activeGenerator.isEnabled()) {
            activeGenerator.generateFrame(currentFrame, previousFrame, cmdBuffer);
        }
    }

    /**
     * 关闭所有帧生成器
     */
    public void shutdown() {
        for (FrameGenerator gen : generators.values()) {
            gen.shutdown();
        }
        generators.clear();
        activeGenerator = null;
    }

    // ==================== Getter ====================

    public FrameGenerator getActiveGenerator() { return activeGenerator; }
    public FrameGenMode getCurrentMode() { return currentMode; }
    public boolean isEnabled() { return activeGenerator != null && activeGenerator.isEnabled(); }
    public boolean isSupported() { return activeGenerator != null && activeGenerator.isSupported(); }
}
