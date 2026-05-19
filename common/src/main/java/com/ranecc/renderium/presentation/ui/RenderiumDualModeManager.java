package com.ranecc.renderium.presentation.ui;

import com.ranecc.renderium.domain.enums.RenderiumMode;
import java.util.logging.Logger;

/**
 * Renderium 双模式管理器
 *
 * <p>负责管理兼容模式与狂暴模式的切换，以及模组检测。
 */
public final class RenderiumDualModeManager {

    private static final Logger LOGGER = Logger.getLogger(RenderiumDualModeManager.class.getName());

    private static volatile RenderiumDualModeManager instance;

    private RenderiumMode currentMode = RenderiumMode.COMPATIBILITY;

    private RenderiumDualModeManager() {
    }

    /**
     * 获取全局单例实例
     *
     * @return RenderiumDualModeManager 实例
     */
    public static RenderiumDualModeManager getInstance() {
        if (instance == null) {
            synchronized (RenderiumDualModeManager.class) {
                if (instance == null) {
                    instance = new RenderiumDualModeManager();
                }
            }
        }
        return instance;
    }

    /**
     * 获取当前运行模式
     *
     * @return 当前模式
     */
    public RenderiumMode getCurrentMode() {
        return currentMode;
    }

    /**
     * 设置运行模式
     *
     * @param mode 目标模式
     */
    public void setMode(RenderiumMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode cannot be null");
        }
        RenderiumMode oldMode = this.currentMode;
        this.currentMode = mode;
        LOGGER.info("Renderium mode changed: " + oldMode + " -> " + mode);
    }

    /**
     * 性能优化模组的主类全限定名
     * Sodium: me.jellysquid.mods.sodium.client.SodiumClientMod
     * OptiFine: optifine.OptiFineClass（若存在）
     */
    private static final String[] PERFORMANCE_MOD_CLASSES = {
        "me.jellysquid.mods.sodium.client.SodiumClientMod",
        "optifine.OptiFineClass"
    };

    /** 模组检测结果缓存 */
    private volatile Boolean modPresentCache = null;

    /**
     * 检查是否存在性能优化模组（如 Sodium、OptiFine）
     * <p>
     * 通过反射检测已知性能优化模组的主类是否存在于类路径中。
     * 使用双检锁 + volatile 缓存避免重复反射。
     * </p>
     *
     * @return true 如果检测到性能模组
     */
    public boolean isPerformanceModPresent() {
        if (modPresentCache != null) {
            return modPresentCache;
        }
        synchronized (this) {
            if (modPresentCache != null) {
                return modPresentCache;
            }
            for (String className : PERFORMANCE_MOD_CLASSES) {
                try {
                    Class.forName(className);
                    LOGGER.info("检测到性能优化模组: " + className);
                    modPresentCache = true;
                    return true;
                } catch (ClassNotFoundException e) {
                    LOGGER.fine("未检测到模组: " + className);
                } catch (NoClassDefFoundError e) {
                    LOGGER.fine("模组存在但依赖缺失: " + className);
                }
            }
            modPresentCache = false;
            LOGGER.fine("未检测到任何性能优化模组");
            return false;
        }
    }
}
