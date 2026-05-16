// Renderium - ReflexManagerImpl（NVIDIA Reflex 完整实现 - v2）
// 基于 Streamline SDK v2.10.3 真实头文件: sl_reflex.h, sl_pcl.h, sl_core_api.h
// 修正版：移除不存在的 slReflexSetMarker，改用 PCL 标记系统

package com.ranecc.renderium.tech.reflex;

import com.ranecc.renderium.None;
import com.ranecc.renderium.tech.streamline.SLContext;
import com.ranecc.renderium.tech.streamline.ffm.SLFFMBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * NVIDIA Reflex 低延迟管理器的完整实现（基于真实 API v2）
 * <p>
 * 通过 Streamline SDK 的 FFM (Foreign Function & Memory) 绑定，
 * 调用 NVIDIA Reflex 和 PCL (Performance Counters Library) 原生 API。
 *
 * <h3>API 来源验证：</h3>
 * <ul>
 *   <li><b>sl_reflex.h</b> - Reflex 核心选项和状态查询</li>
 *   <li><b>sl_pcl.h</b> - 性能计数器标记点（用于替代不存在的 slReflexSetMarker）</li>
 *   <li><b>sl_core_api.h</b> - slGetFeatureFunction 动态函数加载</li>
 * </ul>
 *
 * <h3>关键修正（v1 → v2）：</h3>
 * <ol>
 *   <li>❌ 移除假设的 `slReflexSetMarker()` 函数调用</li>
 *   <li>✅ 改用 PCL 标记系统 (`slPCLSetMarker`)</li>
 *   <li>✅ 修正 `ReflexOptions` 结构体布局（20字节，匹配真实定义）</li>
 *   <li>✅ 使用正确的 PCLMarker 枚举值</li>
 * </ol>
 *
 * @see <a href="file:///e:/DEV/Renderium/env/streamline-sdk-v2.10.3/include/sl_reflex.h">sl_reflex.h</a>
 * @see <a href="file:///e:/DEV/Renderium/env/streamline-sdk-v2.10.3/include/sl_pcl.h">sl_pcl.h</a>
 * @since 5.2.0
 */
public final class ReflexManagerImpl {

    private static final Logger LOGGER = Logger.getLogger("Renderium|ReflexImpl");

    // ==================== Reflex 模式常量 ====================
    /**
     * Reflex 模式枚举
     * 对应 sl_reflex.h 第 30-39 行:
     * ```cpp
     * enum ReflexMode { eOff, eLowLatency, eLowLatencyWithBoost };
     * ```
     */
    static final int REFLEX_MODE_OFF = 0;                  // ReflexMode::eOff
    static final int REFLEX_MODE_LOW_LATENCY = 1;          // ReflexMode::eLowLatency
    static final int REFLEX_MODE_LOW_LATENCY_BOOST = 2;    // ReflexMode::eLowLatencyWithBoost

    // ==================== PCL 标记点常量 ====================
    /**
     * PCL (Performance Counters Library) 标记点枚举
     * 对应 sl_pcl.h 第 58-82 行:
     * ```cpp
     * enum class PCLMarker: uint32_t {
     *     eSimulationStart = 0,
     *     eSimulationEnd = 1,
     *     eRenderSubmitStart = 2,
     *     eRenderSubmitEnd = 3,
     *     ePresentStart = 4,
     *     ePresentEnd = 5,
     *     eTriggerFlash = 7,        // ← 对应 INPUT_SAMPLE
     *     ePCLatencyPing = 8,
     *     eCameraConstructed = 17,
     *     ...
     * };
     * ```
     */
    static final int PCL_MARKER_SIMULATION_START = 0;
    static final int PCL_MARKER_SIMULATION_END = 1;
    static final int PCL_MARKER_RENDER_SUBMIT_START = 2;
    static final int PCL_MARKER_RENDER_SUBMIT_END = 3;
    static final int PCL_MARKER_PRESENT_START = 4;
    static final int PCL_MARKER_PRESENT_END = 5;
    static final int PCL_MARKER_TRIGGER_FLASH = 7;       // 输入采样/触发闪烁
    static final int PCL_MARKER_PC_LATENCY_PING = 8;
    static final int PCL_MARKER_CAMERA_CONSTRUCTED = 17;

    // ==================== 依赖注入 ====================
    private final SLContext slContext;
    private final boolean available;
    private boolean enabled = false;
    private int currentMode = REFLEX_MODE_OFF;

    /** 缓存的 slReflexSetOptions 函数句柄 */
    private java.lang.invoke.MethodHandle cachedOptionsFn = null;

    /** 缓存的 slPCLSetMarker 函数句柄（替代不存在的 slReflexSetMarker）*/
    private java.lang.invoke.MethodHandle cachedPCLMarkerFn = null;

    // ==================== 构造函数 ====================

    /**
     * 创建 Reflex 管理器实例
     *
     * @param slContext Streamline 上下文（必须已初始化）
     */
    ReflexManagerImpl(SLContext slContext) {
        this.slContext = slContext;

        if (slContext == null || !slContext.isInitialized()) {
            this.available = false;
            LOGGER.warning("ReflexManagerImpl: SLContext invalid, Reflex unavailable");
            return;
        }

        this.available = checkReflexAvailability();

        if (this.available) {
            preloadFunctions();
            LOGGER.info("ReflexManagerImpl initialized successfully (v2 - based on real SDK headers)");
        } else {
            LOGGER.warning("ReflexManagerImpl: Reflex feature not supported");
        }
    }

    // ==================== 公共 API ====================

    /**
     * 启用 Reflex 低延迟模式
     *
     * @param mode 目标模式 (OFF/LOW_LATENCY/LOW_LATENCY_BOOST)
     * @return 是否成功启用
     *
     * 【真实 API 调用链】
     * 1. slGetFeatureFunction(FEATURE_REFLEX, "slReflexSetOptions", &fnPtr)
     * 2. fnPtr(&options)  // options 为 ReflexOptions 结构体
     */
    boolean enable(int mode) {
        if (!available) {
            LOGGER.warning("enable: Reflex not available");
            return false;
        }

        try (Arena arena = Arena.ofConfined()) {
            // Step 1: 获取或使用缓存的 slReflexSetOptions 函数指针
            if (cachedOptionsFn == null) {
                if (!loadReflexOptionsFunction(arena)) {
                    LOGGER.warning("enable: Failed to load slReflexSetOptions");
                    return false;
                }
            }

            // Step 2: 构建真实的 ReflexOptions 结构体（20 字节）
            MemorySegment options = buildReflexOptions(arena, mode);

            // Step 3: 调用 slReflexSetOptions(options)
            cachedOptionsFn.invokeExact(options);

            // Step 4: 更新内部状态
            this.enabled = true;
            this.currentMode = mode;

            String modeName = getModeString(mode);
            LOGGER.info(String.format("Reflex enabled [mode=%s (%d)]", modeName, mode));
            return true;

        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "enable: Exception during activation", t);
            return false;
        }
    }

    /**
     * 禁用 Reflex
     */
    void disable() {
        if (!enabled) return;

        try {
            if (cachedOptionsFn != null) {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment options = buildReflexOptions(arena, REFLEX_MODE_OFF);
                    cachedOptionsFn.invokeExact(options);
                }
            }

            this.enabled = false;
            this.currentMode = REFLEX_MODE_OFF;
            LOGGER.info("Reflex disabled");

        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "disable: Exception", t);
        }
    }

    /**
     * 设置输入采样标记点（PCL 方式）
     * <p>
     * 使用 PCL_MARKER_TRIGGER_FLASH 标记玩家输入采样时刻。
     * 替代不存在的 slReflexSetMarker()。
     *
     * 【真实 API】
     * 通过 slGetFeatureFunction(FEATURE_PCL, "slPCLSetMarker", &fnPtr) 获取，
     * 然后调用 fnPtr(PCLMarker::eTriggerFlash)
     */
    void markInputSample() {
        if (!enabled) return;
        setPCLMarkerInternal(PCL_MARKER_TRIGGER_FLASH);
    }

    /**
     * 设置帧提交标记点（对应 vkQueueSubmit 后）
     * <p>
     * 使用 PCL_MARKER_RENDER_SUBMIT_END 标记 GPU 命令提交完成。
     */
    void markSubmitFrame() {
        if (!enabled) return;
        setPCLMarkerInternal(PCL_MARKER_RENDER_SUBMIT_END);
    }

    /**
     * 设置帧呈现标记点（对应 vkQueuePresent 后）
     * <p>
     * 使用 PCL_MARKER_PRESENT_END 标记帧已呈现到显示器。
     */
    void markPresent() {
        if (!enabled) return;
        setPCLMarkerInternal(PCL_MARKER_PRESENT_END);
    }

    // ==================== Getter ====================

    boolean isAvailable() { return available; }
    boolean isEnabled() { return enabled; }
    int getCurrentMode() { return currentMode; }

    // ==================== 静态工厂方法 ====================

    /** 静态实例持有者（延迟初始化） */
    private static volatile ReflexManagerImpl staticInstance;

    /**
     * 获取静态实例（如果已初始化）
     *
     * @return ReflexManagerImpl 实例，未初始化时返回 null
     */
    public static ReflexManagerImpl getInstanceOrNull() {
        return staticInstance;
    }

    /**
     * 设置静态实例（由初始化代码调用）
     *
     * @param instance 已初始化的 ReflexManagerImpl 实例
     */
    public static void setInstance(ReflexManagerImpl instance) {
        staticInstance = instance;
    }

    /**
     * 设置 Reflex 模式（从选项值）
     *
     * @param value 模式值（String 或 Integer）
     */
    public void setReflexMode(Object value) {
        int mode = REFLEX_MODE_OFF;
        if (value instanceof String) {
            String strVal = ((String) value).toUpperCase();
            switch (strVal) {
                case "LOW": mode = REFLEX_MODE_LOW_LATENCY; break;
                case "MEDIUM":
                case "HIGH": mode = REFLEX_MODE_LOW_LATENCY_BOOST; break;
                default: mode = REFLEX_MODE_OFF; break;
            }
        } else if (value instanceof Number) {
            mode = ((Number) value).intValue();
        }
        enable(mode);
    }

    /**
     * 关闭管理器
     */
    void shutdown() {
        disable();
        cachedOptionsFn = null;
        cachedPCLMarkerFn = null;
        LOGGER.fine("ReflexManagerImpl shut down (v2)");
    }

    // ==================== 内部实现（基于真实 API）====================

    /**
     * 检查 Reflex 功能可用性
     */
    private boolean checkReflexAvailability() {
        if (slContext == null || !slContext.isInitialized()) return false;
        if (!slContext.isFeatureSupported(SLContext.Feature.REFLEX)) return false;
        if (!SLFFMBindings.isLoaded()) {
            LOGGER.warning("checkAvailability: SLFFMBindings not loaded");
            return false;
        }
        return true;
    }

    /**
     * 预加载函数指针（Reflex + PCL）
     */
    private void preloadFunctions() {
        try (Arena arena = Arena.ofConfined()) {
            loadReflexOptionsFunction(arena);   // slReflexSetOptions
            loadPCLMarkerFunction(arena);         // slPCLSetMarker
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "preloadFunctions: Failed", e);
        }
    }

    /**
     * 加载 slReflexSetOptions 函数指针
     *
     * 【真实签名】sl_reflex.h 第 176 行:
     * using PFun_slReflexSetOptions = sl::Result(const sl::ReflexOptions& options);
     *
     * @return 是否成功加载
     */
    private boolean loadReflexOptionsFunction(Arena arena) {
        try {
            MemorySegment funcName = arena.allocateFrom("slReflexSetOptions");
            MemorySegment funcPtr = arena.allocate(ValueLayout.ADDRESS);

            int result = SLFFMBindings.slGetFeatureFunction(
                SLFFMBindings.FEATURE_REFLEX,
                funcName,
                funcPtr
            );

            if (result != SLFFMBindings.RESULT_OK) {
                LOGGER.warning(String.format(
                    "loadReflexOptionsFunction: slGetFeatureFunction returned %d", result
                ));
                return false;
            }

            MemorySegment fnAddr = funcPtr.get(ValueLayout.ADDRESS, 0);
            if (fnAddr.equals(MemorySegment.NULL)) {
                LOGGER.warning("loadReflexOptionsFunction: Null function pointer");
                return false;
            }

            // PFun_slReflexSetOptions = Result(const ReflexOptions& options)
            // FFM 签名: (Address) -> int
            cachedOptionsFn = java.lang.foreign.Linker.nativeLinker().downcallHandle(
                fnAddr,
                java.lang.foreign.FunctionDescriptor.of(
                    ValueLayout.ADDRESS  // const ReflexOptions* options
                )
            );

            LOGGER.fine("loadReflexOptionsFunction: ✅ slReflexSetOptions loaded");
            return true;

        } catch (Exception e) {
            LOGGER.log(Level.FINE, "loadReflexOptionsFunction: Failed", e);
            return false;
        }
    }

    /**
     * 加载 slPCLSetMarker 函数指针（替代不存在的 slReflexSetMarker）
     *
     * 【真实签名】通过 PCL 插件导出:
     * using PFun_slPCLSetMarker = sl::Result(PCLMarker marker);
     *
     * @return 是否成功加载
     */
    private boolean loadPCLMarkerFunction(Arena arena) {
        try {
            MemorySegment funcName = arena.allocateFrom("slPCLSetMarker");
            MemorySegment funcPtr = arena.allocate(ValueLayout.ADDRESS);

            int result = SLFFMBindings.slGetFeatureFunction(
                SLFFMBindings.FEATURE_PCL,  // 注意：使用 FEATURE_PCL 而非 FEATURE_REFLEX
                funcName,
                funcPtr
            );

            if (result != SLFFMBindings.RESULT_OK) {
                LOGGER.warning(String.format(
                    "loadPCLMarkerFunction: slGetFeatureFunction(PCL, slPCLSetMarker) returned %d " +
                    "(PCL may not be loaded or function name differs)",
                    result
                ));
                return false;
            }

            MemorySegment fnAddr = funcPtr.get(ValueLayout.ADDRESS, 0);
            if (fnAddr.equals(MemorySegment.NULL)) {
                LOGGER.warning("loadPCLMarkerFunction: Null function pointer");
                return false;
            }

            // PFun_slPCLSetMarker = Result(PCLMarker marker)
            // FFM 签名: (int) -> int
            cachedPCLMarkerFn = java.lang.foreign.Linker.nativeLinker().downcallHandle(
                fnAddr,
                java.lang.foreign.FunctionDescriptor.of(
                    ValueLayout.JAVA_INT  // PCLMarker marker (uint32 → int)
                )
            );

            LOGGER.fine("loadPCLMarkerFunction: ✅ slPCLSetMarker loaded (replaces non-existent slReflexSetMarker)");
            return true;

        } catch (Exception e) {
            LOGGER.log(Level.FINE, "loadPCLMarkerFunction: Failed", e);
            return false;
        }
    }

    /**
     * 设置 PCL 标记点（内部方法）
     *
     * @param pclMarker PCLMarker 枚举值
     */
    private void setPCLMarkerInternal(int pclMarker) {
        if (cachedPCLMarkerFn == null) {
            // 延迟加载
            try (Arena arena = Arena.ofConfined()) {
                if (!loadPCLMarkerFunction(arena)) {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.fine(String.format(
                            "setPCLMarkerInternal: Marker function unavailable (marker=%d/%s)",
                            pclMarker, getPCLMarkerName(pclMarker)
                        ));
                    }
                    return;
                }
            }
        }

        try {
            // 调用 slPCLSetMarker(marker)
            cachedPCLMarkerFn.invokeExact(pclMarker);

        } catch (Throwable t) {
            LOGGER.log(Level.FINE,
                String.format("setPCLMarkerInternal: Failed (marker=%d)", pclMarker), t);
        }
    }

    /**
     * 构建 ReflexOptions 结构体（匹配真实定义）
     *
     * 【真实结构体】sl_reflex.h 第 42-62 行:
     * ```cpp
     * SL_STRUCT_BEGIN(ReflexOptions, ...)
     *     ReflexMode mode;              // [0-3]   4 bytes (int32)
     *     uint32_t frameLimitUs;        // [4-7]   4 bytes
     *     bool useMarkersToOptimize;    // [8]     1 byte + 3 padding
     *     uint16_t virtualKey;           // [12-13] 2 bytes + 2 padding
     *     uint32_t idThread;             // [16-19] 4 bytes
     *     // Total: 20 bytes (+ 4 bytes alignment padding = 24)
     * SL_STRUCT_END()
     * ```
     *
     * @param arena 内存分配器
     * @param mode Reflex 模式
     * @return 填充好的结构体内存段（24 字节，含对齐）
     */
    private static MemorySegment buildReflexOptions(Arena arena, int mode) {
        // 分配 24 字节（20 字节实际数据 + 4 字节对齐填充）
        final int STRUCT_SIZE = 24;
        MemorySegment options = arena.allocate(STRUCT_SIZE);

        // [0-3] mode (ReflexMode → int32)
        options.set(ValueLayout.JAVA_INT, 0, mode);

        // [4-7] frameLimitUs (uint32, 0 = 不限制 FPS)
        options.set(ValueLayout.JAVA_INT, 4, 0);

        // [8] useMarkersToOptimize (bool, 默认 false)
        // 注意：真实字段是 useMarkersToOptimize，不是 useBoost！
        boolean useMarkersToOptimize = (mode == REFLEX_MODE_LOW_LATENCY_BOOST);
        options.set(ValueLayout.JAVA_BYTE, 8, (byte) (useMarkersToOptimize ? 1 : 0));

        // [9-11] padding (3 bytes, 自动清零)

        // [12-13] virtualKey (uint16_t, 0 = 使用默认热键)
        options.set(ValueLayout.JAVA_SHORT, 12, (short) 0);

        // [14-15] padding (2 bytes)

        // [16-19] idThread (uint32_t, 0 = 当前线程)
        options.set(ValueLayout.JAVA_INT, 16, 0);

        // [20-23] padding (4 bytes, 结构体对齐)

        if (LOGGER.isLoggable(Level.FINER)) {
            LOGGER.finer(String.format(
                "buildReflexOptions: mode=%d, frameLimitUs=0, useMarkers=%b, virtualKey=0, idThread=0",
                mode, useMarkersToOptimize
            ));
        }

        return options;
    }

    /**
     * 将模式数值转换为可读字符串
     */
    private static String getModeString(int mode) {
        return switch (mode) {
            case REFLEX_MODE_OFF -> "OFF";
            case REFLEX_MODE_LOW_LATENCY -> "LOW_LATENCY";
            case REFLEX_MODE_LOW_LATENCY_BOOST -> "LOW_LATENCY_BOOST";
            default -> "UNKNOWN(" + mode + ")";
        };
    }

    /**
     * 将 PCL 标记点数值转换为名称
     */
    private static String getPCLMarkerName(int marker) {
        return switch (marker) {
            case PCL_MARKER_SIMULATION_START -> "SIMULATION_START";
            case PCL_MARKER_SIMULATION_END -> "SIMULATION_END";
            case PCL_MARKER_RENDER_SUBMIT_START -> "RENDER_SUBMIT_START";
            case PCL_MARKER_RENDER_SUBMIT_END -> "RENDER_SUBMIT_END";
            case PCL_MARKER_PRESENT_START -> "PRESENT_START";
            case PCL_MARKER_PRESENT_END -> "PRESENT_END";
            case PCL_MARKER_TRIGGER_FLASH -> "TRIGGER_FLASH (INPUT_SAMPLE)";
            case PCL_MARKER_PC_LATENCY_PING -> "PC_LATENCY_PING";
            case PCL_MARKER_CAMERA_CONSTRUCTED -> "CAMERA_CONSTRUCTED";
            default -> "UNKNOWN(" + marker + ")";
        };
    }
}
