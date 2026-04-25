// ============================================================
// NativeAcceleratorIntegration - C++ 加速器集成组件
// ============================================================
// 从 RenderiumCore 中提取的独立组件
// 职责：C++ 原生加速库的加载、初始化、上下文管理
//
// 功能：
//   - 加载原生库（RenderiumAccelerator）
//   - 创建算法上下文（Lyapunov/LOD/Convergence）
//   - 提供快速路径给 Java 端使用
//
// @see com.renderium.accel.RenderiumAccelerator
// ============================================================

package com.renderium.core.component;

import com.renderium.accel.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * C++ 原生加速器集成组件
 * <p>
 * 管理 C++ 原生加速库的生命周期，提供 Lyapunov 评估、
 * LOD 计算、收敛检查等算法的原生实现。
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li>✅ 原生库加载与初始化</li>
 *   <li>✅ 原生算法上下文管理</li>
 *   <li>✅ 可用性检测与降级策略</li>
 *   <li>❌ 不负责具体算法调用（由 QualityAssuranceManager 调用）</li>
 * </ul>
 *
 * @author Renderium Team
 * @version 1.0
 * @since 5.0 (从 RenderiumCore 拆分)
 */
public final class NativeAcceleratorIntegration {

    private static final Logger LOGGER = Logger.getLogger(NativeAcceleratorIntegration.class.getName());

    /** C++加速器实例 */
    private volatile RenderiumAccelerator accelerator;

    /** 是否可用 */
    private volatile boolean nativeAccelAvailable = false;

    /** 原生Lyapunov评估器上下文 */
    private long nativeLyapunovCtx = 0;

    /** 原生LOD计算器上下文 */
    private long nativeLodCtx = 0;

    /** 原生收敛监控器上下文 */
    private long nativeConvergenceCtx = 0;

    /**
     * 初始化C++加速器（可选功能）
     * <p>
     * 如果原生库不可用，自动回退到纯Java实现。
     */
    public void initialize() {
        try {
            accelerator = RenderiumAccelerator.getInstance();
            accelerator.initialize();
            nativeAccelAvailable = accelerator.isNativeLibraryAvailable();

            if (nativeAccelAvailable) {
                // 创建原生算法上下文
                nativeLyapunovCtx = accelerator.lyapunov().createContext(32);
                nativeLodCtx = accelerator.lod().createContext(4, null, 32f, 2f);
                nativeConvergenceCtx = accelerator.convergence().createContext(0.01f, 100);

                LOGGER.info("C++加速器初始化成功: " + accelerator.getVersion());
            }
        } catch (UnsatisfiedLinkError e) {
            nativeAccelAvailable = false;
            LOGGER.info("C++加速库不可用，使用纯Java回退: " + e.getMessage());
        } catch (Exception e) {
            nativeAccelAvailable = false;
            LOGGER.warning("C++加速器初始化失败: " + e.getMessage());
        }
    }

    /**
     * 关闭并释放所有原生资源
     */
    public void shutdown() {
        if (accelerator != null && nativeAccelAvailable) {
            try {
                if (nativeLyapunovCtx != 0) {
                    accelerator.lyapunov().destroyContext(nativeLyapunovCtx);
                    nativeLyapunovCtx = 0;
                }
                if (nativeLodCtx != 0) {
                    accelerator.lod().destroyContext(nativeLodCtx);
                    nativeLodCtx = 0;
                }
                if (nativeConvergenceCtx != 0) {
                    accelerator.convergence().destroyContext(nativeConvergenceCtx);
                    nativeConvergenceCtx = 0;
                }
                accelerator.close();
            } catch (Exception e) {
                LOGGER.warning("关闭C++加速器时出错: " + e.getMessage());
            }
        }
        accelerator = null;
        nativeAccelAvailable = false;
    }

    // ==================== Getter 方法 ====================

    public boolean isNativeAccelAvailable() { return nativeAccelAvailable; }

    public RenderiumAccelerator getAccelerator() { return accelerator; }

    public long getNativeLyapunovCtx() { return nativeLyapunovCtx; }

    public long getNativeLodCtx() { return nativeLodCtx; }

    public long getNativeConvergenceCtx() { return nativeConvergenceCtx; }
}
