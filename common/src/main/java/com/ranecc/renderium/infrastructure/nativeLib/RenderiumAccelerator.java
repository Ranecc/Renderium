// ============================================================
// Renderium Accelerator - 原生加速库门面 (Facade)
// ============================================================
// 作为 Java 端唯一入口，管理 C++ 加速库的生命周期和 FFI Adapter 工厂
//
// 调用链:
//   RenderiumAccelerator.initialize()
//     → NativeLibraryLoader.load("renderium_accel")  // 加载 DLL/SO
//     → accel_initialize()                            // C++ 端初始化
//
//   accelerator.bfs()   → BfsOcclusionFFIAdapter      // BFS 遮挡剔除
//   accelerator.kahan() → KahanAccumulatorFFIAdapter  // 高精度累加
//   accelerator.lod()   → LodCalculatorFFIAdapter     // LOD 距离计算
//   accelerator.lyapunov() → LyapunovEvaluatorFFIAdapter // 质量评估
//   accelerator.convergence() → ConvergenceMonitorFFIAdapter // 收敛监控
// ============================================================

package com.ranecc.renderium.infrastructure.nativeLib;

import com.ranecc.renderium.feature.shader.pipeline.strategy.BfsOcclusion;
import com.ranecc.renderium.infrastructure.gpu.NativeLibraryLoader;
import com.ranecc.renderium.infrastructure.nativeLib.binding.*;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.logging.Logger;

public class RenderiumAccelerator {

    private static final String NATIVE_LIB_NAME = "renderium_accel";
    private static final Logger LOGGER =
        Logger.getLogger(RenderiumAccelerator.class.getName());

    /** 单例实例（双重检查锁定） */
    private static volatile RenderiumAccelerator instance;

    /** 原生库是否已成功加载 */
    private volatile boolean nativeLoaded = false;

    /** C++ 端是否已完成初始化 */
    private volatile boolean initialized = false;

    /** FFI 函数查找器（NativeLibraryLoader 的引用） */
    private final NativeLibraryLoader loader;

    /** BFS 遮挡剔除适配器（懒创建） */
    private volatile BfsOcclusion bfsModule;

    /** Kahan 累加器适配器（懒创建） */
    private volatile KahanAccumulatorFFIAdapter kahanAdapter;

    /** LOD 计算器适配器（懒创建） */
    private volatile LodCalculatorFFIAdapter lodAdapter;

    /** Lyapunov 评估器适配器（懒创建） */
    private volatile LyapunovEvaluatorFFIAdapter lyapunovAdapter;

    /** 收敛监控器适配器（懒创建） */
    private volatile ConvergenceMonitorFFIAdapter convergenceAdapter;

    // ==================== 单例 ====================

    private RenderiumAccelerator() {
        this.loader = NativeLibraryLoader.getInstance();
    }

    public static RenderiumAccelerator getInstance() {
        if (instance == null) {
            synchronized (RenderiumAccelerator.class) {
                if (instance == null) {
                    instance = new RenderiumAccelerator();
                }
            }
        }
        return instance;
    }

    // ==================== 生命周期管理 ====================

    /**
     * 初始化原生加速库
     *
     * 执行两步操作：
     * 1. 通过 NativeLibraryLoader 加载 renderium_accel 动态库
     * 2. 调用 C++ 端 accel_initialize() 完成全局初始化
     *
     * @param deviceHandle 设备句柄（当前未使用，保留用于未来 Vulkan 设备传递）
     * @return boolean 初始化成功返回 true；原生库不存在时返回 false（不抛异常）
     */
    public boolean initialize(Object deviceHandle) {
        if (initialized) {
            return true;
        }

        // 第一步：加载动态库（从 java.library.path 或 classpath 资源提取）
        if (!nativeLoaded) {
            nativeLoaded = loader.load(NATIVE_LIB_NAME);
            if (!nativeLoaded) {
                LOGGER.warning("原生库 " + NATIVE_LIB_NAME + " 加载失败，将使用 Java 回退实现");
                return false;
            }
        }

        // 第二步：调用 C++ accel_initialize() 完成全局状态初始化
        try {
            MethodHandle mhInit = loader.get("accel_initialize",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            int rc = (int) mhInit.invokeExact(2);  // logLevel=2 (INFO)
            if (rc != 0) {
                LOGGER.severe("C++ accel_initialize() 返回错误码: " + rc);
                return false;
            }
        } catch (Throwable e) {
            LOGGER.severe("C++ accel_initialize() 调用失败: " + e.getMessage());
            return false;
        }

        initialized = true;
        logVersionInfo();
        return true;
    }

    /**
     * 检查原生库是否可用
     *
     * @return boolean 原生库已加载且初始化完成返回 true
     */
    public boolean isNativeLibraryAvailable() {
        return nativeLoaded && initialized;
    }

    /**
     * 检查是否已初始化
     *
     * @return boolean
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 获取 C++ 库版本信息
     *
     * @return String 版本字符串，格式 "major.minor.patch"；未初始化时返回 "0.0.0"
     */
    public String getVersion() {
        if (!nativeLoaded) return "0.0.0";
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment major = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment minor = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment patch = arena.allocate(ValueLayout.JAVA_INT);

            MethodHandle mh = loader.get("accel_getVersion",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            Object result = mh.invokeExact(major, minor, patch);
            // 返回值是 const char* (版本描述字符串)
            // 同时 major/minor/patch 已被填充
            int mj = major.get(ValueLayout.JAVA_INT, 0);
            int mn = minor.get(ValueLayout.JAVA_INT, 0);
            int p = patch.get(ValueLayout.JAVA_INT, 0);
            return mj + "." + mn + "." + p;
        } catch (Throwable e) {
            return "unknown";
        }
    }

    /**
     * 关闭加速器，释放所有原生资源
     *
     * 调用 C++ accel_shutdown() 并释放所有 Adapter 引用。
     * 关闭后可重新 initialize()。
     */
    public void close() {
        if (nativeLoaded && initialized) {
            try {
                MethodHandle mh = loader.get("accel_shutdown",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
                mh.invokeExact();
            } catch (Throwable e) {
                LOGGER.warning("C++ accel_shutdown() 调用异常: " + e.getMessage());
            }
        }

        // 释放所有 Adapter 引用
        bfsModule = null;
        kahanAdapter = null;
        lodAdapter = null;
        lyapunovAdapter = null;
        convergenceAdapter = null;
        initialized = false;
        // 注意：不卸载 nativeLoaded（JVM 不支持卸载原生库），仅重置状态
    }

    // ==================== FFI Adapter 工厂方法 ====================

    /**
     * 获取 BFS 遮挡剔除模块
     *
     * 返回的 {@link BfsOcclusion} 接口实例通过 Panama FFM 直接调用 C++ 端算法，
     * 包含完整的 createContext/initGraph/setNeighbors/findVisible/destroyContext 链路。
     *
     * @return BfsOcclusion BFS 适配器；原生库不可用时返回 null
     * @throws IllegalStateException 如果原生库未初始化
     */
    public BfsOcclusion bfs() {
        ensureInitialized();
        if (bfsModule == null) {
            synchronized (this) {
                if (bfsModule == null) {
                    bfsModule = new BfsOcclusionFFIAdapter(loader);
                }
            }
        }
        return bfsModule;
    }

    /**
     * 获取 Kahan/Neumaier 高精度累加器
     *
     * 使用 Neumaier 改进版 Kahan-Babuška 算法，数值精度接近 f80（80 位扩展精度）。
     * 支持 SIMD 批量累加。
     *
     * @return KahanAccumulatorFFIAdapter Kahan 适配器；原生库不可用时返回 null
     */
    public KahanAccumulatorFFIAdapter kahan() {
        ensureInitialized();
        if (kahanAdapter == null) {
            synchronized (this) {
                if (kahanAdapter == null) {
                    kahanAdapter = new KahanAccumulatorFFIAdapter(loader);
                }
            }
        }
        return kahanAdapter;
    }

    /**
     * 获取 LOD 距离计算器
     *
     * 使用 SIMD 优化（AVX2/AVX-512/NEON）批量计算区块 LOD 等级，
     * 支持运行时动态阈值调整。
     *
     * @return LodCalculatorFFIAdapter LOD 适配器；原生库不可用时返回 null
     */
    public LodCalculatorFFIAdapter lod() {
        ensureInitialized();
        if (lodAdapter == null) {
            synchronized (this) {
                if (lodAdapter == null) {
                    lodAdapter = new LodCalculatorFFIAdapter(loader);
                }
            }
        }
        return lodAdapter;
    }

    /**
     * 获取 Lyapunov 帧质量评估器
     *
     * 基于 Lyapunov 指数的动态系统稳定性检测，
     * 用于评估帧渲染质量并检测画面退化。
     *
     * @return LyapunovEvaluatorFFIAdapter Lyapunov 适配器；原生库不可用时返回 null
     */
    public LyapunovEvaluatorFFIAdapter lyapunov() {
        ensureInitialized();
        if (lyapunovAdapter == null) {
            synchronized (this) {
                if (lyapunovAdapter == null) {
                    lyapunovAdapter = new LyapunovEvaluatorFFIAdapter(loader);
                }
            }
        }
        return lyapunovAdapter;
    }

    /**
     * 获取收敛监控器
     *
     * 四维度收敛监控（位置/速度/能量/质量），
     * 使用 EMA 平滑残差避免噪声误判。
     *
     * @return ConvergenceMonitorFFIAdapter 收敛监控适配器；原生库不可用时返回 null
     */
    public ConvergenceMonitorFFIAdapter convergence() {
        ensureInitialized();
        if (convergenceAdapter == null) {
            synchronized (this) {
                if (convergenceAdapter == null) {
                    convergenceAdapter = new ConvergenceMonitorFFIAdapter(loader);
                }
            }
        }
        return convergenceAdapter;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 确保原生库已初始化，否则抛出 IllegalStateException
     */
    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException(
                "RenderiumAccelerator 尚未初始化。请先调用 initialize(deviceHandle)。"
                + "若原生库不存在，应先检查 isNativeLibraryAvailable() 后回退到 Java 实现。");
        }
    }

    /**
     * 记录版本和系统信息到日志
     */
    private void logVersionInfo() {
        try {
            MethodHandle mhSysInfo = loader.get("accel_getSystemInfo",
                FunctionDescriptor.of(ValueLayout.ADDRESS));
            Object sysInfoResult = mhSysInfo.invokeExact();
            // 返回值是 const char* (JSON 格式的系统信息)
            LOGGER.info("原生加速库信息: " + sysInfoResult);
        } catch (Throwable e) {
            LOGGER.fine("无法获取原生库系统信息（非致命）: " + e.getMessage());
        }
    }
}
