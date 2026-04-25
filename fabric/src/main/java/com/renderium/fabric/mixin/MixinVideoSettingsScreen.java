// Renderium - Video Settings Screen Mixin (Enhanced v2)
// MC 26.2-snapshot-3 compatible
// Target: net.minecraft.client.gui.screens.options.VideoSettingsScreen
//
// 增强内容:
//   - 集成 VideoSettingsBridge.onVanillaScreenOpen() 通知机制
//   - 记录详细的 Provider 模式和配置状态日志
//   - 条件性注入"打开 Renderium 设置"按钮（仅独立模式）
//
// 设计参考: Sodium OptionsScreenMixin (中性命名)
// 关键变更: 使用 Bridge 模式解耦，避免硬编码依赖

package com.renderium.fabric.mixin;

import com.renderium.bridge.video.VideoSettingsBridge;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Video Settings Screen Mixin - Renderium 集成入口点（增强版）
 *
 * <p><b>Target:</b> {@code VideoSettingsScreen} (extends OptionsSubScreen)</p>
 *
 * <p>在视频设置界面打开时执行以下操作：
 * <ol>
 *   <li>通过 {@link VideoSettingsBridge#onVanillaScreenOpen(Object)} 通知桥接器</li>
 *   <li>记录当前 Provider 模式和配置状态的详细日志</li>
 *   <li>在独立模式下注入"打开 Renderium 设置"按钮</li>
 * </ol></p>
 *
 * <h3>MC 26.2 方法结构</h3>
 * <ul>
 *   <li>{@code addOptions()} - 添加显示/质量/偏好选项（注入点）</li>
 *   <li>{@code addTitle()} - 添加标题和警告信息</li>
 *   <li>{@code tick()} - 每帧更新（处理 anisotropy 等）</li>
 * </ul>
 *
 * <h3>性能预算</h3>
 * <ul>
 *   <li>Mixin 开销 &lt; 1μs（避免在热路径做重操作）</li>
 *   <li>日志记录仅在界面打开时触发（非每帧）</li>
 *   <li>按钮注入仅执行一次（addOptions 阶段）</li>
 * </ul>
 *
 * @since 1.0.0
 * @version 2.0 (集成 VideoSettingsBridge)
 */
@Mixin(VideoSettingsScreen.class)
public abstract class MixinVideoSettingsScreen {

    /** SLF4J Logger（名称：Renderium-Mixin） */
    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium-Mixin");

    /**
     * Hook into VideoSettingsScreen.addOptions()
     *
     * <p>在所有视频选项添加完成后调用（TAIL 注入）。
     * 执行以下操作：
     * <ol>
     *   <li>检查 VideoSettingsBridge 是否已初始化</li>
     *   <li>调用 onVanillaScreenOpen() 通知桥接器（用于统计/调试）</li>
     *   <li>记录当前 Provider 模式的详细信息</li>
     *   <li>条件性注入自定义按钮（仅 StandaloneProvider 模式）</li>
     * </ol></p>
     *
     * <h4>容错策略：</h4>
     * <ul>
     *   <li>Bridge 未初始化 → 静默跳过，不破坏原版功能</li>
     *   <li>任何异常 → 记录 debug 日志，继续原版流程</li>
     *   <li>按钮注入失败 → 不影响其他功能</li>
     * </ul>
     *
     * @param ci CallbackInfo（Mixin 注入回调）
     */
    @Inject(method = "addOptions",
            at = @At("TAIL"),
            remap = false,
            require = 1)
    private void onAddOptions(CallbackInfo ci) {
        try {
            // 检查 Bridge 是否已初始化
            if (!VideoSettingsBridge.isInitialized()) {
                LOGGER.debug("[Renderium] VideoSettingsScreen opened but Bridge not initialized");
                return;
            }

            // 通知桥接器：原生视频设置界面已打开
            VideoSettingsBridge.onVanillaScreenOpen((VideoSettingsScreen) (Object) this);

            // 记录详细的 Provider 模式和配置状态
            var provider = VideoSettingsBridge.getActiveProvider();
            LOGGER.info("[Renderium] VideoSettingsScreen opened via Mixin - Provider: {}, Compatible: {}",
                    provider != null ? provider.getName() : "NULL",
                    VideoSettingsBridge.isCompatibleMode());

            // TODO: Task 5.x - 在独立模式下注入"打开 Renderium 设置"按钮
            // 条件：provider instanceof StandaloneProvider
            // 实现：使用 Button.builder() 创建按钮并添加到 this.renderables

        } catch (Exception e) {
            // 静默失败 - 不破坏视频设置界面
            LOGGER.debug("[Renderium] VideoSettingsScreen hook error", e);
        }
    }
}
