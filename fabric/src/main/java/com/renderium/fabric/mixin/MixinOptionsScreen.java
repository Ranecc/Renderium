// Renderium - Options Screen Mixin (Enhanced v4)
// 拦截原版"视频设置"按钮，通过 VideoSettingsBridge 委托
// MC 26.2-snapshot-3 compatible
//
// 增强内容:
//   - 使用 VideoSettingsBridge.openSettings() 替代硬编码重定向
//   - 支持 StandaloneProvider 和 CompatibleProvider 两种模式
//   - 容错回退：Bridge 未初始化或异常时允许原版行为继续
//
// 设计参考: Sodium OptionsScreenMixin (中性命名)
// 关键变更: 完全委托给 Bridge，Mixin 层零业务逻辑

package com.renderium.fabric.mixin;

import com.renderium.bridge.video.VideoSettingsBridge;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Options Screen Mixin - 视频设置按钮重定向到 Renderium 设置界面（增强版）
 *
 * <p>拦截原版 OptionsScreen 中的"视频设置"按钮回调，
 * 通过 {@link VideoSettingsBridge#openSettings(Screen)} 委托给当前活跃的 Provider。</p>
 *
 * <h3>MC 26.2 API 变更</h3>
 * <ul>
 *   <li>{@code Minecraft.setScreen()} → {@code Minecraft.gui.setScreen()}</li>
 *   <li>按钮回调返回 Screen 对象（由框架自动调用 gui.setScreen）</li>
 * </ul>
 *
 * <h3>双模式支持</h3>
 * <ul>
 *   <li><b>StandaloneProvider</b>: 返回完整的 Renderium 设置界面（替换原版）</li>
 *   <li><b>SodiumCompatibleProvider</b>: 返回 null 或由 Mixin 处理的界面（追加标签页）</li>
 * </ul>
 *
 * <h3>容错策略</h3>
 * <ul>
 *   <li>Bridge 未初始化 → 不取消，让原版逻辑继续执行</li>
 *   <li>openSettings() 返回 null → 不取消，让原版逻辑继续执行</li>
 *   <li>任何异常 → 记录 warn 日志，不取消，让原版逻辑继续执行</li>
 *   <li>确保在任何情况下都不会破坏原版视频设置功能</li>
 * </ul>
 *
 * @since 1.0.0
 * @version 4.0 (完全委托给 VideoSettingsBridge)
 */
@Mixin(OptionsScreen.class)
public abstract class MixinOptionsScreen extends Screen {

    /** SLF4J Logger（名称：Renderium-Mixin） */
    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium-Mixin");

    /**
     * 构造函数（Mixin 要求）
     *
     * @param title 界面标题组件
     */
    protected MixinOptionsScreen(Component title) {
        super(title);
    }

    /**
     * 拦截视频设置按钮回调（动态方法名）
     *
     * <p>在 MC 26.2 中，OptionsScreen.init() 创建按钮时使用 lambda：
     * <pre>
     * this.openScreenButton(VIDEO, () -> new VideoSettingsScreen(this, this.minecraft, this.options))
     * </pre>
     * 这个 lambda 被编译为 {@code lambda$init$2} 方法（Yarn 映射为 {@code method_19828}）。
     * 我们在 HEAD 位置拦截它，尝试通过 Bridge 打开 Renderium 设置界面。</p>
     *
     * <h4>执行流程：</h4>
     * <ol>
     *   <li>检查 VideoSettingsBridge 是否已初始化</li>
     *   <li>调用 VideoSettingsBridge.openSettings(this) 获取 Renderium 界面</li>
     *   <li>如果返回非 null Screen → 取消原版逻辑，返回 Renderium 界面</li>
     *   <li>如果返回 null 或异常 → 不取消，让原版 VideoSettingsScreen 继续执行</li>
     * </ol>
     *
     * <h4>性能预算：</h4>
     * <ul>
     *   <li>Mixin 开销 &lt; 1μs（仅 volatile 读取 + 条件判断）</li>
     *   <li>实际界面创建开销由 Provider 承担（不在热路径）</li>
     * </ul>
     *
     * @param ci CallbackInfoReturnable&lt;Screen&gt;（可取消的回调）
     */
    @Dynamic
    @Inject(
            method = {"method_19828", "lambda$init$2"},
            require = 1,
            at = @At("HEAD"),
            cancellable = true
    )
    private void openVideoSettings(CallbackInfoReturnable<Screen> ci) {
        try {
            // 检查 Bridge 是否已初始化
            if (!VideoSettingsBridge.isInitialized()) {
                LOGGER.debug("[Renderium] VideoSettingsBridge not initialized, using vanilla");
                return; // 不取消，让原版逻辑继续
            }

            // 通过 Bridge 委托给当前活跃的 Provider
            Screen renderiumScreen = VideoSettingsBridge.openSettings(this);
            if (renderiumScreen != null) {
                // Provider 返回了有效界面 → 取消原版逻辑，使用 Renderium 界面
                ci.setReturnValue(renderiumScreen);
                LOGGER.debug("[Renderium] 视频设置 → {} 模式",
                        VideoSettingsBridge.getActiveProvider().getName());
                return;
            }

            // Provider 返回 null（通常是 CompatibleProvider）
            // 不取消，让原版逻辑继续执行（或由其他 Mixin 处理）

        } catch (Exception e) {
            // 异常时记录警告但不中断，允许原版行为继续
            LOGGER.warn("[Renderium] Failed to open settings, falling back to vanilla", e);
            // 不调用 ci.cancel()，让原版逻辑正常执行
        }
    }
}
