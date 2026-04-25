// Renderium - 模块系统
// 模块元数据定义

package com.renderium.module;

import java.util.List;
import java.util.Objects;

/**
 * 模块元数据
 * <p>
 * 描述模块的基本属性、依赖关系和加载条件。
 * 每个模块实例必须提供有效的元数据。
 *
 * <h2>设计参考：</h2>
 * <ul>
 *   <li>Sodium: 通过 SodiumClientMod 静态管理</li>
 *   <li>Fabric/NeoForge: 使用 mod.json/toml 声明</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * new ModuleMetadata(
 *     "sodium-like-renderer",
 *     "类Sodium渲染优化器",
 *     "2.0.0",
 *     ModuleCategory.CORE,
 *     List.of(),           // 无前置依赖
 *     List.of("blaze3d")    // 后置可选依赖
 * );
 * }</pre>
 *
 * @author Renderium Team
 * @since 2.0.0
 */
public record ModuleMetadata(
        /** 模块唯一标识符 (小写字母+连字符) */
        String id,

        /** 显示名称 */
        String name,

        /** 模块版本 (语义化版本) */
        String version,

        /** 模块分类 */
        ModuleCategory category,

        /** 必需依赖列表 (这些模块必须先初始化) */
        List<String> dependencies,

        /** 可选依赖列表 (存在时增强功能) */
        List<String> optionalDependencies,

        /** 模块描述 */
        String description,

        /** 作者信息 */
        String author,

        /** 是否默认启用 */
        boolean enabledByDefault
) {

    /**
     * 简化构造函数（仅必填字段）
     *
     * @param id       模块 ID
     * @param name     显示名称
     * @param version  版本号
     * @param category 分类
     */
    public ModuleMetadata(String id, String name, String version, ModuleCategory category) {
        this(id, name, version, category, List.of(), List.of(),
                "No description", "Renderium Team", true);
    }

    /**
     * 验证元数据有效性
     *
     * @throws IllegalArgumentException 如果字段无效
     */
    public ModuleMetadata {
        Objects.requireNonNull(id, "Module ID cannot be null");
        Objects.requireNonNull(name, "Module name cannot be null");
        Objects.requireNonNull(version, "Version cannot be null");
        Objects.requireNonNull(category, "Category cannot be null");
        Objects.requireNonNull(dependencies, "Dependencies cannot be null");
        Objects.requireNonNull(optionalDependencies, "Optional deps cannot be null");

        // 验证 ID 格式
        if (!id.matches("^[a-z][a-z0-9_-]*$")) {
            throw new IllegalArgumentException(
                    "Invalid module ID format: '" + id + "'. Must match ^[a-z][a-z0-9_-]*$"
            );
        }

        // 验证版本格式
        if (!version.matches("^\\d+\\.\\d+\\.\\d+(-[a-zA-Z0-9.]+)?$")) {
            throw new IllegalArgumentException(
                    "Invalid version format: '" + version + "'. Expected semantic version"
            );
        }

        // 创建不可变副本
        dependencies = List.copyOf(dependencies);
        optionalDependencies = List.copyOf(optionalDependencies);
    }

    /**
     * 检查是否为核心模块
     *
     * @return true 如果属于 CORE 或 SYSTEM 分类
     */
    public boolean isCoreModule() {
        return category == ModuleCategory.CORE || category == ModuleCategory.SYSTEM;
    }

    /**
     * 获取完整的模块描述字符串
     *
     * @return 格式化文本
     */
    public String getFullDescription() {
        return String.format("%s v%s [%s] - %s",
                name, version, category.getDisplayName(), description);
    }
}
