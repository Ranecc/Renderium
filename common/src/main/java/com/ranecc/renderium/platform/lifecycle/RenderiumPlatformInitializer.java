// Renderium - 命令和设置系统初始化器
// 负责在模组初始化阶段注册 /renderium 命令
// 和初始化视频设置桥接器

package com.ranecc.renderium.platform.lifecycle;

import com.ranecc.renderium.platform.bridge.video.RenderiumOptionFactory;
import com.ranecc.renderium.platform.bridge.video.VideoSettingsACL;
import com.ranecc.renderium.platform.bridge.video.VideoSettingsBridge;
import com.ranecc.renderium.platform.command.RenderiumCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renderium 平台组件初始化器
 *
 * <p>作为 Fabric 模组的客户端初始化入口点之一，
 * 负责注册与平台相关的核心服务：
 * <ul>
 *   <li>视频设置桥接器 ({@link VideoSettingsBridge})</li>
 *   <li>游戏内管理命令 (/renderium)</li>
 *   <li>ModMenu 集成支持</li>
 * </ul>
 *
 * <h3>初始化顺序</h3>
 * <pre>
 * Fabric Loader 启动流程：
 * 1. 加载所有 mod JAR 文件
 * 2. 解析 fabric.mod.json
 * 3. 调用 ClientModInitializer.onInitializeClient()
 *    ├── VideoSettingsBridge.initialize()        // 初始化设置系统
 *    ├── CommandRegistrationCallback.EVENT.register() // 注册命令
 *    └── [可选] ModMenu API 自动发现              // 通过 ServiceLoader
 * </pre>
 *
 * <h3>配置要求</h3>
 * <p>需要在 {@code fabric.mod.json} 中声明为此入口点：
 * <pre>
 * {
 *   "entrypoints": {
 *     "main": [
 *       "com.ranecc.renderium.fabric.RenderiumFabricMod"
 *     ],
 *     "client": [
 *       "com.ranecc.renderium.platform.lifecycle.RenderiumPlatformInitializer"
 *     ]
 *   }
 * }
 * </pre>
 *
 * @see VideoSettingsBridge
 * @see RenderiumCommand
 * @see com.ranecc.renderium.platform.modmenu.RenderiumModMenuIntegration
 * @author Renderium Team
 * @since 1.1.0
 * @version 1.0
 */
public class RenderiumPlatformInitializer implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium-Platform");

    /**
     * 客户端初始化方法
     *
     * <p>Fabric Loader 在游戏启动时自动调用此方法。
     * 执行以下初始化操作：
     * <ol>
     *   <li>初始化视频设置桥接器（检测环境）</li>
     *   <li>注册 /renderium 管理命令</li>
     *   <li>记录初始化结果</li>
     * </ol>
     */
    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Renderium platform components...");

        try {
            // 步骤 1: 初始化视频设置系统
            initializeVideoSettingsSystem();

            // 步骤 2: 注册管理命令
            registerCommands();

            // 步骤 3: 验证初始化状态
            verifyInitialization();

            LOGGER.info("Renderium platform initialization completed successfully");

        } catch (Exception e) {
            LOGGER.error("Failed to initialize Renderium platform components", e);
            throw new RuntimeException("Renderium platform initialization failed", e);
        }
    }

    /**
     * 初始化视频设置桥接器
     *
     * <p>调用 {@link VideoSettingsBridge#initialize()} 方法，
     * 该方法会自动检测运行环境（Sodium 是否存在）
     * 并选择合适的 {@link com.ranecc.renderium.platform.bridge.video.VideoSettingsProvider} 实现。
     *
     * <h4>执行逻辑：</h4>
     * <ol>
     *   <li>检查是否已初始化（防止重复初始化）</li>
     *   <li>调用 Bridge.initialize()</li>
     *   <li>记录检测结果日志</li>
     * </ol>
     */
    private void initializeVideoSettingsSystem() {
        if (VideoSettingsBridge.isInitialized()) {
            LOGGER.info("VideoSettingsBridge already initialized, skipping");
            return;
        }

        try {
            VideoSettingsBridge.initialize();

            String providerName = VideoSettingsBridge.getActiveProvider() != null ?
                    VideoSettingsBridge.getActiveProvider().getName() : "Unknown";
            boolean compatibleMode = VideoSettingsBridge.isCompatibleMode();

            LOGGER.info("Video settings system initialized: provider={}, compatible={}",
                    providerName, compatibleMode);

            // 注册 ACL 回调（Mixin 层通过 ACL 调用 Feature 层的选项工厂）
            VideoSettingsACL.registerRenderiumOptions(RenderiumOptionFactory::createRenderiumOptions);
            VideoSettingsACL.registerDebugOptions(RenderiumOptionFactory::createDebugOptions);
            LOGGER.info("VideoSettingsACL callbacks registered");

        } catch (Exception e) {
            LOGGER.warn("VideoSettingsBridge initialization deferred (may be initialized later by other component)", e);
            // 不抛出异常，允许延迟初始化
        }
    }

    /**
     * 注册 /renderium 命令
     *
     * <p>通过 Fabric API 的 {@link CommandRegistrationCallback} 注册命令。
     * 支持服务端和客户端两种环境：
     * <ul>
     *   <li><b>服务端</b>：在专用服务器或集成服务器中可用</li>
     *   <li><b>客户端</b>：在单人游戏中可用（通过 ClientCommandRegistrationCallback）</li>
     * </ul>
     *
     * <h4>子命令列表：</h4>
     * <ul>
     *   <li>{@code status} - 显示状态信息</li>
     *   <li>{@code reload} - 重载配置文件</li>
     *   <li>{@code reset} - 重置为默认值</li>
     *   <li>{@code toggle &lt;option&gt;} - 切换选项</li>
     *   <li>{@code info} - 详细系统信息</li>
     *   <li>{@code list} - 列出所有选项</li>
     * </ul>
     */
    private void registerCommands() {
        try {
            // 注册服务端/集成服务器命令
            CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
                LOGGER.debug("Registering /renderium command for environment: {}", environment);
                RenderiumCommand.register(dispatcher);
            });

            // 尝试注册客户端命令（如果 API 可用）
            try {
                ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
                    LOGGER.debug("Registering client-side /renderium command");
                    RenderiumCommand.register(dispatcher);
                });
            } catch (NoClassDefFoundError e) {
                // ClientCommandRegistrationCallback 可能不可用（取决于 Fabric API 版本）
                LOGGER.debug("ClientCommandRegistrationCallback not available, skipping client commands");
            }

            LOGGER.info("/renderium command registered with subcommands: status, reload, reset, toggle, info, list");

        } catch (Exception e) {
            LOGGER.warn("Failed to register /renderium command: {}", e.getMessage());
            // 不抛出异常，命令注册失败不应阻止模组启动
        }
    }

    /**
     * 验证初始化状态
     *
     * <p>检查所有关键组件是否已正确初始化，
     * 并输出诊断信息供调试使用。
     */
    private void verifyInitialization() {
        boolean allGood = true;

        // 检查 VideoSettingsBridge
        if (!VideoSettingsBridge.isInitialized()) {
            LOGGER.warn("VideoSettingsBridge not initialized after startup");
            allGood = false;
        }

        // 输出诊断摘要
        if (allGood) {
            LOGGER.info("✓ All platform components verified OK");
        } else {
            LOGGER.warn("⚠ Some platform components may not be fully initialized");
            LOGGER.warn("  This is normal if certain features are not needed in current environment");
        }
    }

    /**
     * 获取初始化状态报告（用于调试）
     *
     * @return 格式化的状态报告字符串
     */
    public static String getStatusReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Renderium Platform Status ===\n");
        sb.append(String.format("VideoSettingsBridge: %s%n",
                VideoSettingsBridge.isInitialized() ? "OK" : "NOT INITIALIZED"));
        if (VideoSettingsBridge.getActiveProvider() != null) {
            sb.append(String.format("Active Provider: %s%n",
                    VideoSettingsBridge.getActiveProvider().getName()));
        }
        sb.append(String.format("Compatible Mode: %s%n",
                VideoSettingsBridge.isCompatibleMode()));
        return sb.toString();
    }
}
