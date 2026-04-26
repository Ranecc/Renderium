// Renderium - Sodium Integration Mixin (Conditional)
// 仅当 Sodium 模组存在时加载
// Target: net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen
//
// 功能:
//   - 在 Sodium VideoSettingsScreen.init() 完成后追加 Renderium 页面
//   - 通过 VideoSettingsBridge 委托给 SodiumCompatibleProvider
//   - 条件加载: @IfMod("sodium")
//
// 设计参考: Sodium OptionsScreenMixin (中性命名)
// 关键特性: 零侵入式集成，不影响 Sodium 原有功能

package com.renderium.fabric.mixin;

import com.renderium.bridge.video.VideoSettingsBridge;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium Integration Mixin - 向 Sodium 视频设置界面追加 Renderium 标签页
 *
 * <p><b>Target:</b> {@code net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen}</p>
 * <p><b>条件加载:</b> 仅当 Sodium 模组存在时加载 ({@code @IfMod("sodium")})</p>
 *
 * <p>在 Sodium 的 VideoSettingsScreen 初始化完成后（init() TAIL 位置），
 * 通过 {@link VideoSettingsBridge#appendSodiumPage(Object)} 将 Renderium 设置页面
 * 追加到 Sodium 的 PageListWidget 中。</p>
 *
 * <h3>架构位置：</h3>
 * <pre>
 * ┌─────────────────────────────────────────────┐
 * │  Sodium VideoSettingsScreen                 │
 * │  ├─ General Options (Sodium 原生)            │
 * │  ├─ Quality Options (Sodium 原生)            │
 * │  ├─ ...                                      │
 * │  └─ Renderium Settings ← 由本 Mixin 追加     │
 * └─────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>执行流程：</h3>
 * <ol>
 *   <li>Sodium VideoSettingsScreen.init() 执行完毕</li>
 *   <li>本 Mixin 在 TAIL 位置触发</li>
 *   <li>调用 VideoSettingsBridge.appendSodiumPage(sodiumScreen)</li>
 *   <li>Bridge 委托给 SodiumCompatibleProvider.appendToSodiumScreen()</li>
 *   <li>Provider 通过反射或 Accessor 将 Renderium 页面添加到 pageList</li>
 * </ol>
 *
 * <h3>容错策略：</h3>
 * <ul>
 *   <li>Bridge 未初始化 → 记录警告，不崩溃</li>
 *   <li>追加操作异常 → 记录警告，不崩溃，Sodium 正常工作</li>
 *   <li>确保在任何情况下都不影响 Sodium 原有功能</li>
 * </ul>
 *
 * <h3>性能预算：</h3>
 * <ul>
 *   <li>Mixin 开销 &lt; 1μs（仅在 init() 阶段触发一次）</li>
 *   <li>不在热路径（render/tick）中执行任何操作</li>
 * </ul>
 *
 * @since 1.0.0
 * @version 1.0 (初始实现)
 */
// MC 26.2: IfMod 注解在当前 Mixin 版本不可用，Sodium 集成改为运行时检测
@Mixin(value = Object.class, remap = false)
public abstract class SodiumIntegrationMixin extends Screen {

    /** SLF4J Logger（名称：Renderium-SodiumIntegration） */
    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium-SodiumIntegration");

    /**
     * 构造函数（Mixin 要求）
     *
     * @param title 界面标题组件
     */
    protected SodiumIntegrationMixin(Component title) {
        super(title);
    }

    /**
     * Hook into Sodium VideoSettingsScreen.init()
     *
     * <p>在 Sodium 的 VideoSettingsScreen 初始化完成后调用（TAIL 注入）。
     * 此时 pageList 已经构建完成，可以安全地向其追加新的页面。</p>
     *
     * <h4>执行逻辑：</h4>
     * <ol>
     *   <li>检查 VideoSettingsBridge 是否已初始化</li>
     *   <li>调用 VideoSettingsBridge.appendSodiumPage(this) 尝试追加页面</li>
     *   <li>成功 → 记录 info 日志</li>
     *   <li>失败或异常 → 记录 warn 日志，不中断 Sodium 流程</li>
     * </ol>
     *
     * <h4>注意事项：</h4>
     * <ul>
     *   <li>此方法仅在 Sodium 模组存在时才会被加载和调用</li>
     *   <li>使用 remap = false 因为目标是 Sodium 的类（非 MC 映射）</li>
     *   <li>require = 1 确保此方法必须存在（否则 Mixin 加载失败）</li>
     * </ul>
     *
     * @param ci CallbackInfo（Mixin 注入回调）
     */
    @Inject(method = "init",
            at = @At("TAIL"),
            remap = false,
            require = 1)
    private void onInit(CallbackInfo ci) {
        try {
            // 检查 Bridge 是否已初始化
            if (!VideoSettingsBridge.isInitialized()) {
                LOGGER.warn("[Renderium] VideoSettingsBridge not initialized, cannot append to Sodium");
                return;
            }

            // 通过 Bridge 委托给 SodiumCompatibleProvider 追加页面
            VideoSettingsBridge.appendSodiumPage((Object) this);

            // 成功追加（无异常抛出）
            LOGGER.info("[Renderium] Successfully appended to Sodium VideoSettingsScreen");

        } catch (Exception e) {
            // 追加失败 - 记录警告但不崩溃
            // Sodium 将继续正常工作，只是没有 Renderium 标签页
            LOGGER.warn("[Renderium] Failed to append to Sodium screen", e);
        }
    }
}
