// Renderium - ModMenu API 实现
// 提供 ModMenu 集成支持，在模组列表中显示配置按钮
// 点击后打开原版视频设置界面（Mixin 自动追加 Renderium 选项）

package com.ranecc.renderium.platform.modmenu;

import com.ranecc.renderium.platform.bridge.video.VideoSettingsBridge;
import net.minecraft.client.gui.screens.Screen;

import java.util.Optional;

/**
 * ModMenu API 实现类
 *
 * <p>通过 Java ServiceLoader 机制被 ModMenu 自动发现和加载，
 * 在模组列表页面为 Renderium 提供配置按钮。
 *
 * <h3>集成原理</h3>
 * <pre>
 * ModMenu 启动流程：
 * 1. 扫描所有实现 ModMenuApi 接口的类
 * 2. 调用 getModConfigScreenFactory() 获取配置工厂
 * 3. 用户点击"Config"按钮时调用工厂方法
 * 4. 工厂方法返回原版 VideoSettingsScreen（Mixin 自动追加 Renderium 选项）
 * </pre>
 *
 * @see VideoSettingsBridge
 * @author Renderium Team
 * @since 1.1.0
 * @version 2.0 - 改为打开原版 VideoSettingsScreen（Mixin 追加选项）
 */
public class RenderiumModMenuIntegration implements /* ModMenuApi */ Object {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("Renderium-ModMenu");

    /**
     * 获取模组配置屏幕工厂
     *
     * <p>当用户在 ModMenu 中点击 Renderium 的"Config"按钮时调用。
     * 返回一个工厂函数，用于创建视频设置界面。
     *
     * @return Optional 包含配置屏幕工厂；如果当前环境不支持则返回空
     */
    public Optional<java.util.function.Function<Screen, ? extends Screen>> getModConfigScreenFactory() {
        try {
            // 检查 VideoSettingsBridge 是否已初始化
            if (!VideoSettingsBridge.isInitialized()) {
                LOGGER.debug("VideoSettingsBridge not initialized, deferring config screen creation");
                return Optional.of(this::createDeferredConfigScreen);
            }

            // 返回配置屏幕工厂
            return Optional.of(this::createConfigScreen);

        } catch (Exception e) {
            LOGGER.warn("Failed to create config screen factory: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 创建视频设置屏幕（标准模式）
     *
     * <p>通过 VideoSettingsBridge 打开视频设置界面，
     * Mixin 会自动追加 Renderium 选项到原版界面。
     *
     * @param parent 父屏幕（ModMenu 的主界面）
     * @return 视频设置界面
     */
    private Screen createConfigScreen(Screen parent) {
        LOGGER.debug("Creating Renderium config screen from ModMenu");
        Screen settings = VideoSettingsBridge.openSettings(parent);
        return settings != null ? settings : parent;
    }

    /**
     * 创建延迟初始化的配置屏幕（Bridge 未初始化时使用）
     *
     * <p>如果 VideoSettingsBridge 尚未初始化（例如首次打开），
     * 此方法会先尝试初始化 Bridge，然后再创建配置屏幕。
     *
     * @param parent 父屏幕
     * @return 视频设置界面或父屏幕（如果初始化失败）
     */
    private Screen createDeferredConfigScreen(Screen parent) {
        try {
            // 尝试延迟初始化
            if (!VideoSettingsBridge.isInitialized()) {
                VideoSettingsBridge.initialize();
                LOGGER.info("VideoSettingsBridge deferred initialization completed");
            }
            Screen settings = VideoSettingsBridge.openSettings(parent);
            return settings != null ? settings : parent;
        } catch (Exception e) {
            LOGGER.warn("Deferred initialization failed, returning parent screen", e);
            return parent;
        }
    }
}
