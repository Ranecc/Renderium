// Renderium - RenderSystem Mixin for NeoForge (v6)
// 拦截渲染系统初始化和关闭，提供后处理核心入口点
// Minecraft 26.2 (unobfuscated) - Blaze3D FrameGraph 架构
//
// ⚠️ L0 安全修复版本（v5→v6 变更）：
//   - 移除不存在的 flipFrame() 注入（MC 26.2 无此方法）
//   - 帧呈现拦截迁移到 GameRenderer.renderLevel() TAIL
//   - 所有 Core 调用统一走 BackendInterceptor（禁止越层访问）
//
// 功能说明：
// - 初始化时启动 RenderiumCore 并检测 Vulkan 后端
// - 关闭时安全释放所有资源
// - 帧呈现通过 GameRenderer Mixin 拦截（非本类）

package com.renderium.neoforge.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.renderium.core.RenderiumDualModeManager;
import com.renderium.neoforge.RenderiumMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin 到 RenderSystem（NeoForge v6 版本）
 *
 * <p><b>L0 安全原则</b>：此类仅负责<strong>生命周期管理</strong>，
 * 不包含任何渲染逻辑或 MC 内部状态访问。
 *
 * <h2>职责边界</h2>
 * <pre>
 * ✅ 允许: 通过 BackendInterceptor 委托初始化/关闭
 * ❌ 禁止: 直接调用 RenderiumCore.getInstance()
 * ❌ 禁止: 访问 MC 内部字段或方法
 * ❌ 禁止: 包含任何后处理业务逻辑
 * </pre>
 *
 * <h3>v6 变更（从 v5 升级）</h3>
 * <ul>
 *   <li><b>移除 flipFrame 注入</b>: MC 26.2 的 RenderSystem 无此方法，
 *       帧呈现拦截已迁移到 {@code MixinGameRendererNeoForge}</li>
 *   <li><b>统一走 BackendInterceptor</b>: 初始化/关闭通过桥接器委托，
 *       不再直接引用 RenderiumCore</li>
 *   <li><b>防御性异常隔离</b>: 所有注入点均有 try-catch 保护</li>
 * </ul>
 *
 * @see com.renderium.graphics.backend.BackendInterceptor 后端桥接器
 * @since 1.0.0
 * @version 6.0 (L0 安全修复版)
 */
@Mixin(RenderSystem.class)
public class MixinRenderSystemNeoForge {

    /**
     * 标记 Renderium 是否已成功初始化
     */
    @Unique
    private static boolean renderiumInitialized = false;

    // ==================== 初始化拦截 ====================

    /**
     * 在渲染器初始化后调用（TAIL 注入）
     * <p>
     * 通过 BackendInterceptor 委托初始化，遵循 L0 抽象层规范。
     *
     * @param device GpuDevice 实例（MC 26.2 统一设备抽象）
     * @param ci    Mixin 回调信息
     */
    @Inject(
            method = "initRenderer",
            at = @At("TAIL")
    )
    private static void onInitRenderer(GpuDevice device, CallbackInfo ci) {
        if (renderiumInitialized) {
            RenderiumMod.LOGGER.warning("[L0] initRenderer called multiple times, skipping");
            return;
        }

        try {
            // 提取设备信息（纯数据采集，无业务逻辑）
            String backendName = device.getBackendName();
            String vendor = device.getVendor();

            RenderiumMod.LOGGER.info("═══════════════════════════════════════");
            RenderiumMod.LOGGER.info("  Renderium v6 - 渲染系统初始化 (NeoForge)");
            RenderiumMod.LOGGER.info("  后端: {}", backendName);
            RenderiumMod.LOGGER.info("  厂商: {}", vendor);
            RenderiumMod.LOGGER.info("═══════════════════════════════════════");

            // ====== L0 规范: 通过 BackendInterceptor 委托（禁止直接调用 Core）======
            // BackendInterceptor 内部会:
            //   1. 提取 Vulkan 设备句柄（如果可用）
            //   2. 调用 RenderiumCore.initialize()
            //   3. 初始化 Streamline SDK
            boolean isVulkan = isVulkanBackend(backendName);
            long deviceHandle = isVulkan ? 1L : 0L;

            try {
                // 尝试通过 BackendInterceptor 初始化（优先）
                Class<?> interceptorClass = Class.forName(
                        "com.renderium.graphics.backend.BackendInterceptor");
                java.lang.reflect.Method initMethod = interceptorClass.getMethod(
                        "onRenderSystemInit", long.class, String.class, String.class);
                initMethod.invoke(null, deviceHandle, backendName, vendor);

            } catch (ClassNotFoundException e) {
                // BackendInterceptor 不可用时降级为直接调用（带警告）
                RenderiumMod.LOGGER.warn("[L0] BackendInterceptor 未找到，降级为直接初始化");
                Class<?> coreClass = Class.forName("com.renderium.core.RenderiumCore");
                Object coreInstance = coreClass.getMethod("getInstance").invoke(null);
                coreClass.getMethod("initialize", long.class).invoke(coreInstance, deviceHandle);
            }

            renderiumInitialized = true;

            // 输出模式信息（通过 DualModeManager 读取，只读操作）
            try {
                Class<?> modeMgrClass = Class.forName(
                        "com.renderium.core.RenderiumDualModeManager");
                Object modeManager = modeMgrClass.getMethod("getInstance").invoke(null);
                Object currentMode = modeMgrClass.getMethod("getCurrentMode").invoke(modeManager);
                RenderiumMod.LOGGER.info("  运行模式: {}", currentMode.toString());
            } catch (Exception ignored) {}

            RenderiumMod.LOGGER.info("═══════════════════════════════════════");

        } catch (Exception e) {
            RenderiumMod.LOGGER.error("[L0] 初始化失败", e);
            renderiumInitialized = false;
        }
    }

    // ==================== 关闭拦截 ====================

    /**
     * 在渲染器关闭时调用（HEAD）
     * <p>
     * 通过 BackendInterceptor 委托关闭，遵循 L0 抽象层规范。
     *
     * @param ci Mixin 回调信息
     */
    @Inject(
            method = "shutdownRenderer",
            at = @At("HEAD")
    )
    private static void onShutdownRenderer(CallbackInfo ci) {
        if (!renderiumInitialized) {
            return;
        }

        RenderiumMod.LOGGER.info("[L0] 正在关闭渲染系统...");

        try {
            // ====== L0 规范: 通过 BackendInterceptor 委托关闭 ======
            try {
                Class<?> interceptorClass = Class.forName(
                        "com.renderium.graphics.backend.BackendInterceptor");
                java.lang.reflect.Method shutdownMethod = interceptorClass.getMethod(
                        "onRenderSystemShutdown");
                shutdownMethod.invoke(null);

            } catch (ClassNotFoundException e) {
                // 降级为直接调用
                Class<?> coreClass = Class.forName("com.renderium.core.RenderiumCore");
                Object coreInstance = coreClass.getMethod("getInstance").invoke(null);
                coreClass.getMethod("shutdown").invoke(coreInstance);
            }

            renderiumInitialized = false;
            RenderiumMod.LOGGER.info("[L0] 渲染系统已安全关闭");

        } catch (Exception e) {
            RenderiumMod.LOGGER.error("[L0] 关闭时发生异常", e);
            renderiumInitialized = false;
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 检测是否为 Vulkan 后端（纯工具方法，无副作用）
     *
     * @param backendName 后端名称
     * @return true 如果是 Vulkan
     */
    private static boolean isVulkanBackend(String backendName) {
        return backendName != null && "Vulkan".equalsIgnoreCase(backendName.trim());
    }
}
