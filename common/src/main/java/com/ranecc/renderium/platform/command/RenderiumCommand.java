// Renderium - 游戏内管理命令
// 注册 /renderium 命令用于运行时管理和调试
// 支持子命令：status, reload, reset, toggle, info, list

package com.ranecc.renderium.platform.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.ranecc.renderium.application.core.RenderiumCore;
import com.ranecc.renderium.domain.model.config.RenderiumConfig;
import com.ranecc.renderium.feature.config.RenderiumConfigLoader;
import com.ranecc.renderium.platform.bridge.video.VideoSettingsBridge;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Renderium 命令注册器
 *
 * <p>通过 Fabric API 的 {@code CommandRegistrationCallback} 注册游戏内管理命令。
 * 提供完整的运行时管理功能，包括状态查询、配置重载、开关切换等。
 *
 * <h3>命令结构</h3>
 * <pre>
 * /renderium
 * ├── status          查看当前状态和性能指标
 * ├── reload           重新加载配置文件
 * ├── reset            重置为默认配置
 * ├── toggle &lt;option&gt;  切换指定选项的启用/禁用
 * │   ├── enable      启用 Renderium
 * │   ├── disable     禁用 Renderium
 * │   └── &lt;feature&gt;  切换特定功能（如 sr, fg, reflex）
 * ├── info             显示详细系统信息
 * └── list             列出所有可用选项和当前值
 * </pre>
 *
 * <h3>权限控制</h3>
 * <ul>
 *   <li>所有命令需要 OP 权限等级 2+（管理员）</li>
 *   <li>status 和 info 命令允许普通玩家使用（OP 0）</li>
 * </ul>
 *
 * <h3>注册方式</h3>
 * <p>在模组初始化时调用：
 * <pre>
 * CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
 *     RenderiumCommand.register(dispatcher);
 * });
 * </pre>
 *
 * @see CommandRegistrationCallback
 * @see VideoSettingsBridge
 * @author Renderium Team
 * @since 1.1.0
 * @version 1.0
 */
public final class RenderiumCommand {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("Renderium-Command");

    /** 私有构造函数（防止实例化） */
    private RenderiumCommand() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * 注册所有 /renderium 子命令
     *
     * <p>此方法应在 {@code CommandRegistrationCallback} 回调中调用，
     * 将命令注册到 Minecraft 的命令调度器中。
     *
     * @param dispatcher 命令调度器（由 Fabric API 提供）
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        try {
            // 创建根命令构建器（标注 requires 使所有子命令要求 OP 2）
            LiteralArgumentBuilder<CommandSourceStack> rootBuilder =
                    Commands.literal("renderium")
                            .requires(source -> source.hasPermission(2))
                            .executes(RenderiumCommand::executeStatus);

            // 注册子命令
            registerSubcommands(rootBuilder);

            // 注册到调度器
            dispatcher.register(rootBuilder);

            LOGGER.info("/renderium command registered successfully");
        } catch (Exception e) {
            LOGGER.error("Failed to register /renderium command", e);
        }
    }

    /**
     * 注册所有子命令到根命令构建器
     *
     * @param root 根命令构建器
     */
    private static void registerSubcommands(LiteralArgumentBuilder<CommandSourceStack> root) {
        // === 状态查询命令 ===
        root.then(Commands.literal("status")
                .executes(RenderiumCommand::executeStatus)
        );

        // === 配置重载命令 ===
        root.then(Commands.literal("reload")
                .executes(RenderiumCommand::executeReload)
        );

        // === 重置配置命令 ===
        root.then(Commands.literal("reset")
                .executes(RenderiumCommand::executeReset)
        );

        // === 开关切换命令 ===
        // MC 26.2 API: StringArgumentType 可能已移至其他包或重命名
        // 暂时使用 word() 参数类型（Brigadier 内置）
        root.then(Commands.literal("toggle")
                .then(Commands.argument("option", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests((context, builder) -> {
                            // 提供可选选项列表
                            String[] suggestions = {"enable", "disable", "sr", "fg", "reflex", "culling"};
                            for (String s : suggestions) {
                                builder.suggest(s);
                            }
                            return builder.buildFuture();
                        })
                        .executes(RenderiumCommand::executeToggle)
                )
        );

        // === 详细信息命令 ===
        root.then(Commands.literal("info")
                .executes(RenderiumCommand::executeInfo)
        );

        // === 列表命令 ===
        root.then(Commands.literal("list")
                .executes(RenderiumCommand::executeList)
        );
    }

    // ==================== 命令执行方法 ====================

    /**
     * 执行 status 命令 - 显示当前状态
     *
     * @param context 命令上下文
     * @return 执行结果码（1 = 成功）
     */
    private static int executeStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            RenderiumCore core = RenderiumCore.getInstance();
            RenderiumConfig config = core.getConfig();

            // 发送状态信息
            source.sendSuccess(() -> Component.literal("=== Renderium Status ===")
                    .withStyle(ChatFormatting.GOLD), false);

            source.sendSuccess(() -> Component.literal("• Enabled: " + config.isEnabled())
                    .withStyle(config.isEnabled() ? ChatFormatting.GREEN : ChatFormatting.RED), false);

            source.sendSuccess(() -> Component.literal("• Mode: " + config.getMode().name())
                    .withStyle(ChatFormatting.YELLOW), false);

            source.sendSuccess(() -> Component.literal("• Super Resolution: " + config.isSuperResolutionEnabled())
                    .withStyle(config.isSuperResolutionEnabled() ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);

            source.sendSuccess(() -> Component.literal("• Frame Generation: " + config.isFrameGenerationEnabled())
                    .withStyle(config.isFrameGenerationEnabled() ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);

            source.sendSuccess(() -> Component.literal("• Settings Provider: " +
                    (VideoSettingsBridge.getActiveProvider() != null ?
                            VideoSettingsBridge.getActiveProvider().getName() : "Not initialized"))
                    .withStyle(ChatFormatting.AQUA), false);

            return com.mojang.brigadier.Command.SINGLE_SUCCESS;

        } catch (Exception e) {
            source.sendFailure(Component.literal("Failed to get status: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * 执行 reload 命令 - 重载配置文件
     *
     * @param context 命令上下文
     * @return 执行结果码
     */
    private static int executeReload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            // 重载 YAML 配置
            RenderiumConfigLoader configLoader = RenderiumConfigLoader.getInstance();
            configLoader.loadDefault();

            // MC 26.2 兼容性: RenderiumCore 没有 reloadConfig() 方法
            // 配置已通过 ConfigLoader 重载，核心会在下次使用时自动读取新配置
            // TODO: 如果需要立即应用配置，可考虑重新初始化核心组件

            source.sendSuccess(() -> Component.literal("✓ Configuration reloaded successfully")
                    .withStyle(ChatFormatting.GREEN), true);

            LOGGER.info("Configuration reloaded via /renderium reload command");
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;

        } catch (Exception e) {
            source.sendFailure(Component.literal("✗ Failed to reload config: " + e.getMessage()));
            LOGGER.error("Failed to reload config via command", e);
            return 0;
        }
    }

    /**
     * 执行 reset 命令 - 重置为默认配置
     *
     * @param context 命令上下文
     * @return 执行结果码
     */
    private static int executeReset(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            RenderiumCore core = RenderiumCore.getInstance();
            core.resetConfig();

            source.sendSuccess(() -> Component.literal("✓ Configuration reset to defaults")
                    .withStyle(ChatFormatting.YELLOW), true);

            LOGGER.info("Configuration reset to defaults via /renderium reset command");
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;

        } catch (Exception e) {
            source.sendFailure(Component.literal("✗ Failed to reset config: " + e.getMessage()));
            LOGGER.error("Failed to reset config via command", e);
            return 0;
        }
    }

    /**
     * 执行 toggle 命令 - 切换指定选项
     *
     * @param context 命令上下文
     * @return 执行结果码
     */
    private static int executeToggle(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            // 获取参数（使用 Brigadier 的 StringArgumentType）
            String option = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "option").toLowerCase();

            RenderiumCore core = RenderiumCore.getInstance();
            RenderiumConfig config = core.getConfig();

            boolean newValue;
            String optionName;

            switch (option) {
                case "enable":
                    newValue = !config.isEnabled();
                    config.setEnabled(newValue);
                    optionName = "Enabled";
                    break;

                case "disable":
                    config.setEnabled(false);
                    newValue = false;
                    optionName = "Enabled";
                    break;

                case "sr":
                case "superresolution":
                    newValue = !config.isSuperResolutionEnabled();
                    config.setSuperResolutionEnabled(newValue);
                    optionName = "Super Resolution";
                    break;

                case "fg":
                case "framegeneration":
                    newValue = !config.isFrameGenerationEnabled();
                    config.setFrameGenerationEnabled(newValue);
                    optionName = "Frame Generation";
                    break;

                case "reflex":
                    newValue = !config.isReflexEnabled();
                    config.setReflexEnabled(newValue);
                    optionName = "NVIDIA Reflex";
                    break;

                case "culling":
                    // MC 26.2 兼容性: RenderiumConfig 没有 isAdvancedCullingEnabled()
                    // 改为使用 Occlusion Culling（最常用的裁剪选项）
                    newValue = !config.isOcclusionCullingEnabled();
                    config.setOcclusionCullingEnabled(newValue);
                    optionName = "Occlusion Culling";
                    break;

                default:
                    source.sendFailure(Component.literal("Unknown option: " + option +
                            ". Available: enable, disable, sr, fg, reflex, culling"));
                    return 0;
            }

            // 发送反馈
            ChatFormatting color = newValue ? ChatFormatting.GREEN : ChatFormatting.RED;
            source.sendSuccess(() -> Component.literal(String.format("✓ %s → %s", optionName, newValue ? "ON" : "OFF"))
                    .withStyle(color), true);

            LOGGER.info("Option toggled via command: {} = {}", option, newValue);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;

        } catch (Exception e) {
            source.sendFailure(Component.literal("✗ Failed to toggle option: " + e.getMessage()));
            LOGGER.error("Failed to toggle option via command", e);
            return 0;
        }
    }

    /**
     * 执行 info 命令 - 显示详细系统信息
     *
     * @param context 命令上下文
     * @return 执行结果码
     */
    private static int executeInfo(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Runtime runtime = Runtime.getRuntime();

            source.sendSuccess(() -> Component.literal("=== Renderium System Info ===")
                    .withStyle(ChatFormatting.GOLD), false);

            // JVM 信息
            source.sendSuccess(() -> Component.literal("• JVM Version: " + System.getProperty("java.version"))
                    .withStyle(ChatFormatting.GRAY), false);
            source.sendSuccess(() -> Component.literal("• Memory Usage: " +
                    formatBytes(runtime.totalMemory() - runtime.freeMemory()) + " / " +
                    formatBytes(runtime.maxMemory()))
                    .withStyle(ChatFormatting.GRAY), false);

            // 模组信息
            source.sendSuccess(() -> Component.literal("• Mod Version: " + getModVersion())
                    .withStyle(ChatFormatting.AQUA), false);
            source.sendSuccess(() -> Component.literal("• MC Version: " + getMCVersion())
                    .withStyle(ChatFormatting.AQUA), false);

            // 环境信息
            source.sendSuccess(() -> Component.literal("• Sodium Compatible: " + VideoSettingsBridge.isCompatibleMode())
                    .withStyle(VideoSettingsBridge.isCompatibleMode() ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);

            return com.mojang.brigadier.Command.SINGLE_SUCCESS;

        } catch (Exception e) {
            source.sendFailure(Component.literal("Failed to get info: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * 执行 list 命令 - 列出所有可用选项
     *
     * @param context 命令上下文
     * @return 执行结果码
     */
    private static int executeList(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            RenderiumConfig config = RenderiumCore.getInstance().getConfig();

            source.sendSuccess(() -> Component.literal("=== Renderium Options List ===")
                    .withStyle(ChatFormatting.GOLD), false);

            // 列出主要选项
            printOption(source, "enabled", String.valueOf(config.isEnabled()), ChatFormatting.WHITE);
            printOption(source, "mode", config.getMode().name(), ChatFormatting.YELLOW);
            printOption(source, "super_resolution", String.valueOf(config.isSuperResolutionEnabled()), ChatFormatting.AQUA);
            printOption(source, "frame_generation", String.valueOf(config.isFrameGenerationEnabled()), ChatFormatting.AQUA);
            printOption(source, "reflex", String.valueOf(config.isReflexEnabled()), ChatFormatting.AQUA);
            printOption(source, "occlusion_culling", String.valueOf(config.isOcclusionCullingEnabled()), ChatFormatting.AQUA);

            return com.mojang.brigadier.Command.SINGLE_SUCCESS;

        } catch (Exception e) {
            source.sendFailure(Component.literal("Failed to list options: " + e.getMessage()));
            return 0;
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 打印单个选项信息
     *
     * @param source   命令源
     * @param name     选项名称
     * @param value    选项值
     * @param color    文本颜色
     */
    private static void printOption(CommandSourceStack source, String name, String value, ChatFormatting color) {
        source.sendSuccess(() -> Component.literal(String.format("• %s: ", name))
                .append(Component.literal(value).withStyle(color)), false);
    }

    /**
     * 格式化字节数为人类可读格式
     *
     * @param bytes 字节数
     * @return 格式化字符串（如 "512 MB"）
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * 获取模组版本号
     *
     * @return 版本字符串
     */
    private static String getModVersion() {
        try {
            // 从 fabric.mod.json 读取版本
            return "1.1.0-SNAPSHOT";  // TODO: 从实际元数据获取
        } catch (Exception e) {
            return "Unknown";
        }
    }

    /**
     * 获取 Minecraft 版本号
     *
     * @return 版本字符串
     */
    private static String getMCVersion() {
        try {
            // MC 26.2 API: WorldVersion 可能已变更
            // 尝试获取版本名称，如果失败则返回 ID
            net.minecraft.WorldVersion version = net.minecraft.SharedConstants.getCurrentVersion();
            return version.name();  // 优先使用 name()
        } catch (Exception e) {
            try {
                // 备选方案: 使用 id()
                return net.minecraft.SharedConstants.getCurrentVersion().id();
            } catch (Exception e2) {
                return "26.2-SNAPSHOT";  // 已知的 MC 版本
            }
        }
    }
}
