// ============================================================
// Renderium Accelerator - 门面类 (Facade)
// ============================================================
// 组合所有算法模块，提供统一入口
// 各模块可独立使用，也可通过此门面统一管理生命周期
//
// 使用示例:
//   RenderiumAccelerator accel = RenderiumAccelerator.getInstance();
//   accel.initialize();
//
//   // BFS
//   long bfsCtx = accel.bfs().createContext(8192);
//   int[] result = accel.bfs().findVisible(bfsCtx, x, y, z, fov, dist, frame);
//   accel.bfs().destroyContext(bfsCtx);
//
//   // LOD
//   long lodCtx = accel.lod().createContext(4, null, 32f, 2f);
//   byte[] lodResult = accel.lod().batchCompute(lodCtx, inputs, count);
//   accel.lod().destroyContext(lodCtx);
//
//   accel.shutdown();
// ============================================================

package com.renderium.accel;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Renderium 加速器门面类
 * <p>
 * 组合所有算法模块，提供统一初始化/关闭管理。
 * 各模块 (bfs, lod, lyapunov, kahan, convergence) 可独立使用。
 *
 * <h3>GPU/CPU动态调度：</h3>
 * <ul>
 *   <li>异步无锁调度器，不阻塞渲染/计算线程</li>
 *   <li>根据GPU占用率动态选择CPU或GPU路径</li>
 *   <li>元状态机调控: Stable/Transitioning/Unstable</li>
 * </ul>
 *
 * @since 1.0.0
 */
public final class RenderiumAccelerator implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(RenderiumAccelerator.class.getName());

    private static final RenderiumAccelerator INSTANCE = new RenderiumAccelerator();

    public static RenderiumAccelerator getInstance() {
        return INSTANCE;
    }

    private NativeLibraryLoader loader;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    private BfsOcclusion bfsModule;
    private LodCalculator lodModule;
    private LyapunovEvaluator lyapunovModule;
    private KahanAccumulator kahanModule;
    private ConvergenceMonitor convergenceModule;
    private SharedMemory sharedMemoryModule;

    private RenderiumAccelerator() {}

    // ==================== 生命周期管理 ====================

    /**
     * 初始化加速器
     * <p>加载原生库并创建所有算法模块实例</p>
     */
    public synchronized void initialize() {
        if (initialized.get()) {
            LOGGER.warning("加速器已初始化，跳过重复初始化");
            return;
        }

        try {
            loader = new NativeLibraryLoader();

            // 调用C++初始化
            try {
                MethodHandle mh = loader.get("accel_initialize",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                int rc = (int) mh.invokeExact(3);
                if (rc != 0) throw new RuntimeException("原生初始化失败，返回码: " + rc);
            } catch (Throwable e) {
                throw new IllegalStateException("原生初始化失败", e);
            }

            // 创建模块实例
            bfsModule = new BfsOcclusion(loader);
            lodModule = new LodCalculator(loader);
            lyapunovModule = new LyapunovEvaluator(loader);
            kahanModule = new KahanAccumulator(loader);
            convergenceModule = new ConvergenceMonitor(loader);
            sharedMemoryModule = new SharedMemory(loader);

            initialized.set(true);
            LOGGER.info("Renderium Accelerator 初始化成功");

        } catch (UnsatisfiedLinkError e) {
            throw new IllegalStateException("无法加载原生库: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("加速器初始化失败", e);
        }
    }

    @Override
    public synchronized void close() {
        if (!initialized.get()) return;

        try {
            MethodHandle mh = loader.get("accel_shutdown",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
            mh.invokeExact();
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "关闭原生库时出错", e);
        }

        bfsModule = null;
        lodModule = null;
        lyapunovModule = null;
        kahanModule = null;
        convergenceModule = null;
        sharedMemoryModule = null;
        loader = null;
        initialized.set(false);
        LOGGER.info("Renderium Accelerator 已关闭");
    }

    public boolean isInitialized() {
        return initialized.get();
    }

    // ==================== 模块访问 ====================

    /** BFS遮挡剔除模块 */
    public BfsOcclusion bfs() {
        ensureInitialized();
        return bfsModule;
    }

    /** LOD距离计算模块 */
    public LodCalculator lod() {
        ensureInitialized();
        return lodModule;
    }

    /** Lyapunov质量评估模块 */
    public LyapunovEvaluator lyapunov() {
        ensureInitialized();
        return lyapunovModule;
    }

    /** Kahan高精度累加模块 */
    public KahanAccumulator kahan() {
        ensureInitialized();
        return kahanModule;
    }

    /** 收敛监控模块 */
    public ConvergenceMonitor convergence() {
        ensureInitialized();
        return convergenceModule;
    }

    /** 共享内存零拷贝模块 */
    public SharedMemory sharedMemory() {
        ensureInitialized();
        return sharedMemoryModule;
    }

    // ==================== 版本信息 ====================

    /**
     * 获取原生库版本号
     * @return 版本字符串 (如 "1.0.0")
     */
    public String getVersion() {
        ensureInitialized();

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment majorSeg = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment minorSeg = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment patchSeg = arena.allocate(ValueLayout.JAVA_INT);

            MethodHandle mh = loader.get("accel_getVersion",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            MemorySegment addr = (MemorySegment) mh.invokeExact(majorSeg, minorSeg, patchSeg);

            int major = majorSeg.get(ValueLayout.JAVA_INT, 0);
            int minor = minorSeg.get(ValueLayout.JAVA_INT, 0);
            int patch = patchSeg.get(ValueLayout.JAVA_INT, 0);

            return major + "." + minor + "." + patch;

        } catch (Throwable e) {
            return "unknown (" + e.getMessage() + ")";
        }
    }

    /**
     * 检查原生库是否可用
     * @return true 如果库已成功加载
     */
    public boolean isNativeLibraryAvailable() {
        return loader != null && initialized.get();
    }

    /**
     * 获取系统信息（CPU/内存/缓存等）
     * @return JSON格式系统信息字符串
     */
    public String getSystemInfo() {
        ensureInitialized();
        try {
            MethodHandle mh = loader.get("accel_getSystemInfo",
                FunctionDescriptor.of(ValueLayout.ADDRESS));
            MemorySegment addr = (MemorySegment) mh.invokeExact();
            return addr.getString(0);
        } catch (Throwable e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 获取原生库加载来源（诊断用）
     * @return 来源描述: "embedded-jar", "local-path", "classloader", 或 "unknown"
     */
    public String getLoadSource() {
        return loader != null ? loader.getLoadSource() : "not-loaded";
    }

    /**
     * 获取原生库提取器实例
     * @return 提取器，可用于手动提取其他原生库
     */
    public NativeLibraryExtractor getExtractor() {
        return loader != null ? loader.getExtractor() : null;
    }

    /**
     * 获取完整的加载诊断报告
     * @return 多行诊断信息字符串
     */
    public String getDiagnosticReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== RenderiumAccelerator 诊断报告 ===\n");
        sb.append(String.format("初始化状态: %s\n", initialized.get() ? "已初始化" : "未初始化"));
        sb.append(String.format("原生库可用: %s\n", isNativeLibraryAvailable()));
        if (loader != null) {
            sb.append(String.format("加载来源:   %s\n", getLoadSource()));
            var extractor = loader.getExtractor();
            if (extractor != null) {
                sb.append(String.format("平台ID:     %s\n", extractor.getPlatformId()));
                sb.append(String.format("提取目录:   %s\n", extractor.getExtractionDir()));
                sb.append(String.format("JAR内嵌:    renderium_accel=%b\n",
                    extractor.isEmbedded("renderium_accel")));
            }
        }
        try {
            sb.append(String.format("版本号:      %s\n", getVersion()));
        } catch (Exception ignored) {}
        try {
            sb.append(String.format("系统信息:    %s\n", getSystemInfo()));
        } catch (Exception ignored) {}
        sb.append("=======================================");
        return sb.toString();
    }

    // ==================== 内部辅助 ====================

    private void ensureInitialized() {
        if (!initialized.get()) {
            throw new IllegalStateException("加速器尚未初始化，请先调用 initialize()");
        }
    }
}
