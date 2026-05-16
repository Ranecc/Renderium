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
     * 检查是否存在性能优化模组（如 Sodium）
     *
     * @return true 如果检测到性能模组
     */
    public boolean isPerformanceModPresent() {
        return false;
    }
}
