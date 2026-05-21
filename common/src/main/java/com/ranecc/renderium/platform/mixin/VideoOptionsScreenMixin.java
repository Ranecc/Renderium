package com.ranecc.renderium.platform.mixin;

import com.ranecc.renderium.platform.bridge.video.VideoSettingsACL;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.world.option.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VideoSettingsScreen Mixin 注入器
 *
 * <p>在原版 VideoSettingsScreen.addOptions() 方法尾部注入 Renderium 选项。
 * 原版选项（Display/Quality/Preferences）完整保留，仅在底部追加 Renderium 和 Debug 分区。
 *
 * <h3>注入策略：</h3>
 * <ul>
 *   <li>注入点：addOptions() TAIL（两版本完全一致）</li>
 *   <li>继承 OptionsSubScreen 以访问 this.list 字段（通过 AW 暴露）</li>
 *   <li>通过 VideoSettingsACL 防腐层隔离 Mixin 与 Feature 层</li>
 *   <li>require = 0 容错：MC 更新导致签名变化时不崩溃</li>
 * </ul>
 *
 * @see VideoSettingsACL
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoOptionsScreenMixin extends OptionsSubScreen {

    /**
     * dummy 构造函数（Mixin 框架不会实际调用）
     * 继承 OptionsSubScreen 以访问 this.list 字段
     */
    protected VideoOptionsScreenMixin() {
        super(null, null);
    }

    /**
     * 在 addOptions() 尾部追加 Renderium 和 Debug 分区
     *
     * <p>注入时机：原版 addOptions() 执行完毕后，this.list 已包含所有原版选项。
     * 此时追加 Renderium 分区和 Debug 分区到列表末尾。
     *
     * @param ci 回调信息（Mixin 框架提供）
     */
    @Inject(
        method = "addOptions",
        at = @At("TAIL"),
        remap = true,
        require = 0  // 容错：注入失败不崩溃
    )
    private void renderium$appendOptions(CallbackInfo ci) {
        // ACL 未就绪时静默返回（Renderium 尚未初始化）
        if (!VideoSettingsACL.isReady()) return;

        // 防御性检查：list 可能为 null（理论上不会，但安全起见）
        if (this.list == null) return;

        // 追加 Renderium 分区
        this.list.addHeader(VideoSettingsACL.getRenderiumHeader());
        OptionInstance<?>[] renderiumOpts = VideoSettingsACL.createRenderiumOptions();
        if (renderiumOpts.length > 0) {
            this.list.addSmall(renderiumOpts);
        }

        // 追加 Debug 分区
        this.list.addHeader(VideoSettingsACL.getDebugHeader());
        OptionInstance<?>[] debugOpts = VideoSettingsACL.createDebugOptions();
        if (debugOpts.length > 0) {
            this.list.addSmall(debugOpts);
        }
    }
}
