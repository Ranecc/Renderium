// Renderium - NVIDIA Reflex Manager
// NVIDIA Reflex 低延迟集成

package com.ranecc.renderium.tech.reflex;

import com.ranecc.renderium.None;
import com.ranecc.renderium.tech.streamline.SLContext;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Logger;

/**
 * NVIDIA Reflex 管理器
 * <p>
 * 集成 NVIDIA Reflex 技术，降低输入延迟。
 * Reflex 通过优化渲染队列和 CPU/GPU 同步来减少延迟。
 * <p>
 * Reflex 模式：
 * <ul>
 *   <li>OFF - 关闭</li>
 *   <li>LOW_LATENCY - 低延迟（仅优化队列）</li>
 *   <li>LOW_LATENCY_BOOST - 低延迟+加速（优化队列+超频）</li>
 * </ul>
 * <p>
 * 与 DLSS Frame Generation 配合使用效果最佳。
 *
 * @see SLContext
 */
public final class ReflexManager {

    private static final Logger LOGGER = Logger.getLogger(ReflexManager.class.getName());

    /**
     * Reflex 模式常量
     * 对应 sl_reflex.h 中的 sl::ReflexMode
     */
    private static final int REFLEX_MODE_OFF = 0;
    private static final int REFLEX_MODE_LOW_LATENCY = 1;
    private static final int REFLEX_MODE_LOW_LATENCY_BOOST = 2;
    private static final int REFLEX_MODE_ON = 1;  // ON 等同于 LOW_LATENCY

    /**
     * Reflex 标志常量
     */
    private static final int REFLEX_MARKER_CALL_BACK = 0;
    private static final int REFLEX_MARKER_INPUT_SAMPLE = 1;
    private static final int REFLEX_MARKER_TRIGGER_FLASH = 2;
    private static final int REFLEX_MARKER_PRESENT = 3;
    private static final int REFLEX_MARKER_SUBMIT_FRAME = 4;

    private final SLContext slContext;

    /** 私有实现实例（委托所有底层操作）*/
    private final ReflexManagerImpl impl;

    private boolean available = false;
    private boolean enabled = false;
    private ReflexMode mode = ReflexMode.OFF;

    /**
     * Reflex 模式枚举
     */
    public enum ReflexMode {
        OFF(REFLEX_MODE_OFF),
        LOW_LATENCY(REFLEX_MODE_LOW_LATENCY),
        LOW_LATENCY_BOOST(REFLEX_MODE_LOW_LATENCY_BOOST),
        ON(REFLEX_MODE_ON);  // ON 模式（等同于 LOW_LATENCY）

        public final int id;
        ReflexMode(int id) { this.id = id; }
    }

    public ReflexManager(SLContext slContext) {
        this.slContext = slContext;
        this.impl = new ReflexManagerImpl(slContext);  // 创建实现实例
        this.available = impl.isAvailable();  // 从 impl 获取可用性
    }

    /**
     * 启用 Reflex
     *
     * @param mode Reflex 模式
     */
    public void enable(ReflexMode mode) {
        if (!available) {
            LOGGER.warning("NVIDIA Reflex not available");
            return;
        }

        boolean success = impl.enable(mode.id);  // 委托给 impl
        if (success) {
            this.mode = mode;
            this.enabled = true;
            LOGGER.info("NVIDIA Reflex enabled: " + mode);
        } else {
            LOGGER.warning("NVIDIA Reflex enable failed for mode: " + mode);
        }
    }

    /**
     * 禁用 Reflex
     */
    public void disable() {
        impl.disable();  // 委托给 impl
        this.enabled = false;
        this.mode = ReflexMode.OFF;
        LOGGER.info("NVIDIA Reflex disabled");
    }

    /**
     * 关闭 Reflex 管理器
     * <p>
     * 释放 Reflex 相关资源。
     */
    public void shutdown() {
        impl.shutdown();  // 委托给 impl
        LOGGER.fine("ReflexManager shut down");
    }

    /**
     * 标记输入采样点
     * <p>
     * 在处理玩家输入时调用。
     */
    public void markInputSample() {
        if (!enabled) return;
        impl.markInputSample();  // 委托给 impl
    }

    /**
     * 标记帧提交点
     * <p>
     * 在提交帧到 GPU 时调用。
     */
    public void markSubmitFrame() {
        if (!enabled) return;
        impl.markSubmitFrame();  // 委托给 impl
    }

    /**
     * 标记呈现点
     * <p>
     * 在帧呈现到显示器时调用。
     */
    public void markPresent() {
        if (!enabled) return;
        impl.markPresent();  // 委托给 impl
    }

    // ==================== Getter ====================

    public boolean isAvailable() { return available; }
    public boolean isEnabled() { return enabled; }
    public ReflexMode getMode() { return mode; }
}
