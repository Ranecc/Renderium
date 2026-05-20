// ============================================================
// Renderium 存根模式健康检查器
// ============================================================
// 在初始化完成后检测哪些功能在存根/骨架模式下运行，
// 输出一次性警告日志，让用户和开发者清楚了解当前状态。
//
// 设计原则：
// - 仅首次调用时输出完整报告（避免每帧刷屏）
// - 不阻断任何功能，纯诊断用途
// - 报告格式化，便于日志分析和用户理解
// ============================================================

package com.ranecc.renderium.infrastructure.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ranecc.renderium.infrastructure.gpu.OfficialVulkanHijacker;
import com.ranecc.renderium.infrastructure.nativeLib.RenderiumAccelerator;
import com.ranecc.renderium.tech.streamline.VulkanStreamlineBridge;

/**
 * 存根模式健康检查器
 *
 * <p>检测并报告所有处于存根模式（不可用但被声明支持）的功能模块。
 * 在 {@code InitializeUseCase} 末尾调用，确保启动时即暴露问题。
 *
 * <p>使用示例：
 * <pre>
 *   // 初始化完成后立即检查
 *   StubModeHealthCheck.getInstance().reportAllStubs();
 *   // 输出示例：
 *   // [RENDERIUM] ════════ 存根模式健康检查报告 ════════
 *   // [RENDERIUM] ⚠ Streamline SDK: 存根模式 (sl.interposer.dll 未找到)
 *   // [RENDERIUM] ⚠ Native Accelerator: 不可用 (renderium_accel 未加载)
 *   // [RENDERIUM] ✓ Vulkan Hijacker: 已启用
 *   // [RENDERIUM] ─────────────────────────────────────
 *   // [RENDERIUM] 共发现 2 个功能处于存根模式
 * </pre>
 */
public final class StubModeHealthCheck {

    private static final Logger LOGGER = Logger.getLogger("Renderium|Diagnostics");
    private static final String SEPARATOR = "════════════════════════════════════";

    /** 单例实例 */
    private static volatile StubModeHealthCheck instance;

    /** 是否已输出过报告（防止重复） */
    private volatile boolean reported = false;

    /** 收集到的存根项 */
    private final List<StubItem> stubItems = Collections.synchronizedList(new ArrayList<>());

    private StubModeHealthCheck() {}

    public static StubModeHealthCheck getInstance() {
        if (instance == null) {
            synchronized (StubModeHealthCheck.class) {
                if (instance == null) {
                    instance = new StubModeHealthCheck();
                }
            }
        }
        return instance;
    }

    /**
     * 执行全量健康检查并输出报告
     *
     * <p>仅首次调用时输出完整报告，后续调用静默返回。
     * 如需强制重新输出，使用 {@link #forceReport()}。
     *
     * @return int 处于存根模式的功能数量（0 = 全部正常）
     */
    public int reportAllStubs() {
        if (reported) {
            return stubItems.size();
        }
        return forceReport();
    }

    /**
     * 强制重新执行健康检查并输出报告
     *
     * @return int 处于存根模式的功能数量
     */
    public int forceReport() {
        stubItems.clear();

        // 检查 1: 原生加速库
        checkNativeAccelerator();

        // 检查 2: Streamline SDK / DLSS / FSR / XeSS
        checkStreamlineBridge();

        // 检查 3: Vulkan 渲染劫持
        checkVulkanHijacker();

        // 输出报告
        int stubCount = stubItems.size();
        if (stubCount > 0) {
            outputReport(stubCount);
        } else {
            LOGGER.info("╔" + SEPARATOR + "╗");
            LOGGER.info("║  存根模式健康检查: 全部功能正常运行              ║");
            LOGGER.info("╚" + SEPARATOR + "╝");
        }

        reported = true;
        return stubCount;
    }

    /**
     * 获取当前收集的存根项（只读视图）
     *
     * @return 不可修改的存根项列表
     */
    public List<StubItem> getStubItems() {
        return Collections.unmodifiableList(new ArrayList<>(stubItems));
    }

    /**
     * 重置报告状态（用于测试或重新初始化场景）
     */
    public void reset() {
        reported = false;
        stubItems.clear();
    }

    // ==================== 内部检查方法 ====================

    /**
     * 检查原生 C++ 加速库是否可用
     */
    private void checkNativeAccelerator() {
        try {
            RenderiumAccelerator accel = RenderiumAccelerator.getInstance();
            if (!accel.isNativeLibraryAvailable()) {
                stubItems.add(new StubItem(
                    "Native Accelerator",
                    "C++ 加速库 (renderium_accel)",
                    "renderium_accel.dll/.so 未加载",
                    "BFS/LOD/Kahan/Lyapunov 将使用 Java 回退实现",
                    "将 renderium_accel 放入 java.library.path 或 native/ 目录"
                ));
            }
        } catch (Exception e) {
            stubItems.add(new StubItem(
                "Native Accelerator",
                "C++ 加速库 (renderium_accel)",
                "加载异常: " + e.getMessage(),
                "将完全无法使用原生加速路径",
                "检查 DLL/SO 架构是否匹配 (x64/ARM64)"
            ));
        }
    }

    /**
     * 检查 Streamline SDK 是否可用
     */
    private void checkStreamlineBridge() {
        try {
            VulkanStreamlineBridge bridge = VulkanStreamlineBridge.getInstance();
            if (!bridge.isAvailable()) {
                stubItems.add(new StubItem(
                    "Streamline SDK",
                    "DLSS / FSR / XeSS / FrameGen / Reflex",
                    bridge.isInitialized()
                        ? "sl.interposer.dll 未找到"
                        : "VulkanStreamlineBridge 尚未初始化",
                    "超分辨率、帧生成、低延迟功能将不会生效",
                    "安装 NVIDIA Streamline SDK 并确保 sl.interposer.dll 在 PATH 中"
                ));
            } else if (bridge.isStubMode()) {
                stubItems.add(new StubItem(
                    "Streamline SDK",
                    "DLSS / FSR / XeSS / FrameGen / Reflex",
                    "已初始化但处于存根模式",
                    "资源标记等操作将被跳过",
                    "检查 sl.interposer.dll 版本是否与 SDK 匹配"
                ));
            }
        } catch (Exception e) {
            stubItems.add(new StubItem(
                "Streamline SDK",
                "DLSS / FSR / XeSS / FrameGen / Reflex",
                "检查异常: " + e.getMessage(),
                "整个超分辨率技术栈可能不可用",
                "查看上方异常信息排查"
            ));
        }
    }

    /**
     * 检查 Vulkan 渲染劫持是否启用
     */
    private void checkVulkanHijacker() {
        try {
            OfficialVulkanHijacker hijacker = OfficialVulkanHijacker.getInstance();
            if (!hijacker.isAvailable()) {
                stubItems.add(new StubItem(
                    "Vulkan Hijacker",
                    "渲染管线劫持",
                    "当前已禁用",
                    "无法扩展 Minecraft 官方 Vulkan 渲染器",
                    "在配置中启用 vulkan.hijacker.enabled 或检查劫持条件"
                ));
            }
        } catch (Exception e) {
            // Hijacker 可能未初始化，不视为存根问题
            LOGGER.fine("Vulkan Hijacker 检查跳过: " + e.getMessage());
        }
    }

    // ==================== 报告输出 ====================

    /**
     * 格式化输出存根报告到日志
     */
    private void outputReport(int count) {
        LOGGER.warning("╔" + SEPARATOR + "╗");
        LOGGER.warning("║  ⚠ 存根模式健康检查: 发现 " + count + " 个功能处于存根/降级模式      ║");
        LOGGER.warning("╠" + SEPARATOR + "╣");

        for (int i = 0; i < stubItems.size(); i++) {
            StubItem item = stubItems.get(i);
            String idx = String.valueOf(i + 1);
            LOGGER.warning("║  " + idx + ". [" + item.name + "]                          ║");
            LOGGER.warning("║     功能: " + item.feature + "                       ".substring(
                Math.min(item.feature.length(), 35)) + "║");
            LOGGER.warning("║     原因: " + item.reason);
            LOGGER.warning("║     影响: " + item.impact);
            LOGGER.warning("║     建议: " + item.suggestion);
            if (i < stubItems.size() - 1) {
                LOGGER.warning("║  ─────────────────────────────────────────────── ║");
            }
        }

        LOGGER.warning("╠" + SEPARATOR + "╣");
        LOGGER.warning("║  共 " + count + " 个功能需要关注。详情请查阅上方各条目。           ║");
        LOGGER.warning("╚" + SEPARATOR + "╝");
    }

    // ==================== 内部数据结构 ====================

    /**
     * 存根项 — 描述一个处于存根模式的功能
     */
    public static final class StubItem {
        /** 功能名称（简短标识） */
        public final String name;
        /** 受影响的功能描述 */
        public final String feature;
        /** 存根原因 */
        public final String reason;
        /** 对用户的实际影响 */
        public final String impact;
        /** 解决建议 */
        public final String suggestion;

        public StubItem(String name, String feature, String reason,
                         String impact, String suggestion) {
            this.name = name;
            this.feature = feature;
            this.reason = reason;
            this.impact = impact;
            this.suggestion = suggestion;
        }

        @Override
        public String toString() {
            return "[" + name + "] " + feature + " - " + reason;
        }
    }
}
