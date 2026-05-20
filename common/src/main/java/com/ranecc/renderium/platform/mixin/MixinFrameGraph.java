package com.ranecc.renderium.platform.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
// Inspector 是 FrameGraphBuilder 的内部接口: FrameGraphBuilder.Inspector
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.logging.Level;
import java.util.logging.Logger;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import com.ranecc.renderium.platform.mixin.RenderiumPassInjector;

/**
 * FrameGraphBuilder Mixin - Renderium Pass 注入点
 * <p>
 * 在 {@link FrameGraphBuilder#execute(GraphicsResourceAllocator, FrameGraphBuilder.Inspector)}
 * 的头部注入所有 Renderium 自定义 Pass（LOD Culling、SuperResolution、FrameGeneration、EffectPipeline）。
 * </p>
 *
 * <h3>注入点定义：</h3>
 * <pre>
 * 目标类：com.mojang.blaze3d.framegraph.FrameGraphBuilder
 * 目标方法：execute(GraphicsResourceAllocator, Inspector)
 * 注入时机：@At("HEAD") - 在 Mojang 原生 Pass 注册之前
 * cancellable = false - 不取消原始 execute（仅预注入）
 * </pre>
 *
 * <h3>注入的 Pass 链（按执行顺序）：</h3>
 * <ol>
 *   <li><b>Renderium_LodCulling_Compute</b> - LOD 视锥体/遮挡剔除 Compute Shader</li>
 *   <li><b>Renderium_SuperResolution</b> - DLSS/FSR/XeSS 超分辨率</li>
 *   <li><b>Renderium_FrameGeneration</b> - AI 帧生成 (DLSS-FG/FSR-FG)</li>
 *   <li><b>Renderium_EffectPipeline</b> - Bloom/DOF/TAA 后处理链</li>
 * </ol>
 *
 * <h3>前置条件检查：</h3>
 * <ul>
 *   <li>VulkanDeviceHolder.isInitialized() 必须为 true</li>
 *   <li>如果未初始化，跳过注入（不报错）</li>
 * </ul>
 *
 * @see com.renderium.core.VulkanDeviceHolder
 * @see com.renderium.framegraph.RenderiumPassInjector
 * @since 5.2.0
 */
@Mixin(FrameGraphBuilder.class)
public abstract class MixinFrameGraph {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger("Renderium|MixinFrameGraph");

    /**
     * 在 FrameGraphBuilder.reset() 头部注入 — 重置 Pass 注入状态
     * <p>
     * 当 FrameGraphBuilder 重建时（如分辨率变化/资源重分配），
     * 需要重置 {@link RenderiumPassInjector} 的注入状态，
     * 以便在下次 execute() 时重新注入自定义 Pass。
     * </p>
     *
     * @param ci CallbackInfo - Mixin 回调信息
     */
    @Inject(method = "reset", at = @At("HEAD"), require = 0)
    private void onReset(CallbackInfo ci) {
        RenderiumPassInjector.getInstance().reset();
        LOGGER.fine("Renderium: FrameGraph Reset — Pass 注入状态已重置");
    }

    /**
     * 在 FrameGraphBuilder.execute() 头部注入 Renderium 自定义 Pass (snapshot-3)
     * <p>
     * snapshot-3 签名: execute(GraphicsResourceAllocator) 
     * snapshot-7+: execute(GraphicsResourceAllocator, Inspector)
     * 当前适配 snapshot-3。
     * </p>
     *
     * @param resourceAllocator GraphicsResourceAllocator - 图形资源分配器
     * @param ci                 CallbackInfo
     */
    @Inject(
            method = "execute",
            at = @At("HEAD"),
            cancellable = false,
            require = 0
    )
    private void onExecuteHead(
            GraphicsResourceAllocator resourceAllocator,
            CallbackInfo ci) {

        // 前置条件：VulkanDevice 必须可用（已初始化且未降级）
        if (!VulkanDeviceHolder.isAvailable()) {
            return;  // 静默跳过，不注入任何 Pass
        }

        try {
            // 获取当前 FrameGraphBuilder 实例（this 即为 FrameGraphBuilder）
            FrameGraphBuilder self = (FrameGraphBuilder) (Object) this;

            // 调用 RenderiumPassInjector 执行 Pass 注入
            RenderiumPassInjector injector = RenderiumPassInjector.getInstance();
            int injectedCount = injector.injectPasses(self, resourceAllocator);

            if (injectedCount > 0) {
                LOGGER.fine("Renderium: 已注入 " + injectedCount + " 个自定义 Pass 到 FrameGraph");
            }

        } catch (Exception e) {
            // 记录错误但不中断原始 execute 流程
            LOGGER.log(Level.SEVERE,
                "Renderium: FrameGraph Pass 注入失败（不影响原始渲染）", e);
        }
    }
}
