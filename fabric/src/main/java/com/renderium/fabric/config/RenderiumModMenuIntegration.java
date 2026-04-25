// Renderium - ModMenu 集成 (v7 - 兼容模式)
// 支持可选的 ModMenu 集成 + 独立运行能力
//
// 设计策略：
// 1. 不直接 implements ModMenuApi（避免编译时依赖）
// 2. 使用 Fabric Loader 的 entrypoint 机制动态检测
// 3. 如果 ModMenu 存在且兼容，提供完整集成
// 4. 如果 ModMenu 不存在或不兼容，静默降级
//
// 兼容性：
// - MC 26.2-snapshot-3 (✅)
// - Fabric Loader 0.18.x (✅)
// - ModMenu >= 9.0.0 且 < 11.0.0 (⚠️ 部分版本有 setScreen 问题)

package com.renderium.fabric.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ModMenu API 集成类 (v7) - 兼容模式
 *
 * <p>提供<strong>可选</strong>的 ModMenu 集成：</p>
 * <ul>
 *   <li><b>如果 ModMenu 已安装且兼容</b>: 自动注册配置界面工厂</li>
 *   <li><b>如果 ModMenu 未安装</b>: 此类不会被加载（安全降级）</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <p>在 {@code fabric.mod.json} 中声明为 modmenu entrypoint：</p>
 * <pre>{@code
 * "entrypoints": {
 *   "modmenu": [ "com.renderium.fabric.config.RenderiumModMenuIntegration" ]
 * }
 * }</pre>
 *
 * <h3>关于 MC 26.2 兼容性</h3>
 * <p><b>重要提示:</b></p>
 * <ul>
 *   <li>ModMenu 18.0.0-alpha.8 与 MC 26.2 <strong>不兼容</strong></li>
 *   <li>错误: {@code NoSuchMethodError: Minecraft.setScreen()}</li>
 *   <li>建议: 升级到 ModMenu >= 11.0.0-beta 或移除此模组</li>
 * </ul>
 *
 * @see com.renderium.ui.RenderiumSettingsScreen
 * @since 1.0.0
 * @version 7.0
 */
public class RenderiumModMenuIntegration {

    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium-ModMenu");

    /** 模组 ID */
    static final String MOD_ID = "renderium";

    /**
     * 获取模组 ID 命名空间（静态方法供 ModMenu 反射调用）
     *
     * @return 模组 ID "renderium"
     */
    public static String getModIdNamespace() {
        LOGGER.info("ModMenu requesting mod ID: {}", MOD_ID);
        return MOD_ID;
    }

    /**
     * 创建配置屏幕工厂（静态方法供 ModMenu 反射调用）
     *
     * <p>返回一个 Lambda 表达式作为 ConfigScreenFactory。</p>
     *
     * @return 工厂对象（Lambda 或 null）
     */
    public static Object getConfigScreenFactory() {
        LOGGER.info("ModMenu requesting config screen factory...");

        try {
            // 返回一个双参数函数：(Minecraft, Screen) -> Screen
            // 使用 Java 8+ 的 Lambda 表达式
            return (java.util.function.BiFunction<Minecraft, Screen, Screen>) (mc, parent) -> {
                try {
                    LOGGER.info("Creating Renderium settings screen...");

                    com.renderium.core.RenderiumCore core =
                            com.renderium.core.RenderiumCore.getInstance();
                    com.renderium.config.RenderiumConfig config = core.getConfig();

                    return new com.renderium.ui.RenderiumSettingsScreen(parent, config);

                } catch (Exception e) {
                    LOGGER.error("Failed to create settings screen", e);
                    return null;  // 让 ModMenu 显示默认行为
                }
            };

        } catch (Exception e) {
            LOGGER.error("Failed to create config factory", e);
            return null;
        }
    }

    /**
     * 备用方案：创建简单的信息屏幕（当主界面不可用时）
     *
     * @param parent 父屏幕
     * @return 信息屏幕或 null
     */
    static Object createFallbackScreen(Screen parent) {
        try {
            LOGGER.warn("Creating fallback info screen...");
            // 返回 null 表示使用 ModMenu 默认行为
            return null;

        } catch (Exception e) {
            LOGGER.error("Failed to create fallback screen", e);
            return null;
        }
    }
}
