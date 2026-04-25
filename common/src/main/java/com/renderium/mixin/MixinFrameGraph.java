package com.renderium.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
// Inspector 是 FrameGraphBuilder 的内部接口: FrameGraphBuilder.Inspector
import com.renderium.core.VulkanDeviceHolder;
import com.renderium.framegraph.RenderiumPassInjector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.logging.Level;
import java.util.logging.Logger;

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
     * 在 FrameGraphBuilder.execute() 头部注入 Renderium 自定义 Pass
     * <p>
     * 此方法在 Mojang 原生的 Pass 注册逻辑执行之前被调用，
     * 通过 {@link RenderiumPassInjector} 向 FrameGraphBuilder 添加自定义 Pass。
     * </p>
     *
     * 【方法参数】
     * @param resourceAllocator GraphicsResourceAllocator - 图形资源分配器（由 Blaze3D 提供）
     * @param inspector          FrameGraphBuilder  - 帧图检查器（用于调试）
     * @param ci                 CallbackInfo         - Mixin 回调信息
     *
     * 【返回值】void
     *
     * 【实现要点】
     * 1. 检查 VulkanDeviceHolder.isInitialized()
     *    - false → 直接返回，不注入任何 Pass
     *    - true → 继续注入流程
     * 2. 获取当前 FrameGraphBuilder 实例（通过 this 引用）
     * 3. 调用 RenderiumPassInjector.injectPasses() 执行实际注入
     * 4. 记录注入结果日志（成功/失败/跳过）
     *
     * 【异常处理】
     * - VulkanDeviceHolder 未初始化：静默跳过（INFO 日志）
     * - 注入过程异常：记录 SEVERE 日志但不中断原始 execute
     * - 不影响 Mojang 原生 Pass 的注册和执行
     */
    @Inject(
            method = "execute",
            at = @At("HEAD"),
            cancellable = false  // 不取消原始 execute，仅预注入 Pass
    )
    private void onExecuteHead(
            GraphicsResourceAllocator resourceAllocator,
            FrameGraphBuilder.Inspector inspector,
            CallbackInfo ci) {

        // 前置条件：VulkanDevice 必须已初始化
        if (!VulkanDeviceHolder.getInstance().isInitialized()) {
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
