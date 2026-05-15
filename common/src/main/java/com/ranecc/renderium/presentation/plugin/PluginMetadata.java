// Renderium - Blaze3D 优化器插件系统
// 插件元数据定义

package com.ranecc.renderium.presentation.plugin;

import java.util.List;
import java.util.Objects;

/**
 * 插件元数据
 * <p>
 * 包含插件的标识信息、版本、支持范围等静态属性。
 * 每个插件实例必须提供有效的元数据。
 *
 * <h2>字段说明：</h2>
 * <ul>
 *   <li>{@code id} - 全局唯一标识符</li>
 *   <li>{@code minecraftVersion} - 支持的 Minecraft 版本范围</li>
 *   <li>{@code stability} - 稳定性等级</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public record PluginMetadata(
        /** 插件全局唯一 ID，格式: "renderium-optimizer-{version}" */
        String id,
        /** 显示名称 */
        String name,
        /** 支持的 MC 版本范围，如 "26.2.x" */
        String minecraftVersion,
        /** 插件自身版本号，语义化版本如 "1.0.0" */
        String pluginVersion,
        /** 稳定性等级 */
        PluginStability stability,
        /** 提供的优化列表描述 */
        List<String> optimizations,
        /** 已知冲突的模组/插件 ID 列表 */
        List<String> conflicts
) {

    /**
     * 验证元数据有效性
     *
     * @throws IllegalArgumentException 如果任何必填字段为空或无效
     */
    public PluginMetadata {
        Objects.requireNonNull(id, "Plugin ID cannot be null");
        Objects.requireNonNull(name, "Plugin name cannot be null");
        Objects.requireNonNull(minecraftVersion, "Minecraft version cannot be null");
        Objects.requireNonNull(pluginVersion, "Plugin version cannot be null");
        Objects.requireNonNull(stability, "Stability cannot be null");
        Objects.requireNonNull(optimizations, "Optimizations list cannot be null");
        Objects.requireNonNull(conflicts, "Conflicts list cannot be null");

        // 验证 ID 格式
        if (id.isBlank()) {
            throw new IllegalArgumentException("Plugin ID cannot be blank");
        }
        if (!id.matches("^[a-z][a-z0-9._-]*$")) {
            throw new IllegalArgumentException(
                    "Invalid plugin ID format: '\n'" + id + "'\n'. Must match ^[a-z][a-z0-9._-]*$"
            );
        }

        // 验证版本号格式（基本语义版本检查）
        if (!pluginVersion.matches("^\\d+\\.\\d+\\.\\d+(-[a-zA-Z0-9.]+)?$")) {
            throw new IllegalArgumentException(
                    "Invalid plugin version format: '" + pluginVersion +
                            "'. Expected semantic version like '1.0.0'"
            );
        }

        // 创建不可变副本
        optimizations = List.copyOf(optimizations);
        conflicts = List.copyOf(conflicts);
    }

    /**
     * 获取完整的插件描述字符串
     *
     * @return 格式化的描述文本
     */
    public String getDescription() {
        return String.format("%s v%s for MC %s [%s]",
                name, pluginVersion, minecraftVersion, stability.getDisplayName());
    }

    /**
     * 检查是否与指定模组冲突
     *
     * @param modId 待检查的模组 ID
     * @return 如果存在冲突返回 true
     */
    public boolean hasConflictWith(String modId) {
        return conflicts.contains(modId);
    }

    /**
     * 获取风险等级提示
     *
     * @return 根据稳定性返回的风险提示
     */
    public String getRiskLevel() {
        return switch (stability) {
            case EXPERIMENTAL -> "高风险 - 可能导致游戏崩溃";
            case BETA -> "中风险 - 可能影响游戏稳定性";
            case STABLE -> "低风险 - 经过充分验证";
        };
    }
}
