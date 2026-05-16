package com.ranecc.renderium.feature.module;

import java.util.List;

/**
 * 模块元数据
 * <p>
 * 描述一个 Renderium 模块的完整元数据信息，包括标识符、版本、分类、依赖等。
 * 采用不可变设计，构造后所有字段不可修改。
 */
public final class ModuleMetadata {

    private final String id;
    private final String name;
    private final String version;
    private final ModuleCategory category;
    private final List<String> dependencies;
    private final List<String> optionalDependencies;
    private final String description;
    private final String author;
    private final boolean defaultEnabled;

    /**
     * 创建模块元数据
     *
     * @param id                 模块唯一标识符（kebab-case）
     * @param name               模块显示名称
     * @param version            模块版本号
     * @param category           模块分类
     * @param dependencies       前置依赖模块 ID 列表（不可变）
     * @param optionalDependencies 可选依赖模块 ID 列表（不可变）
     * @param description        模块描述
     * @param author             作者
     * @param defaultEnabled     是否默认启用
     */
    public ModuleMetadata(String id, String name, String version,
                          ModuleCategory category,
                          List<String> dependencies, List<String> optionalDependencies,
                          String description, String author, boolean defaultEnabled) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.category = category;
        this.dependencies = dependencies;
        this.optionalDependencies = optionalDependencies;
        this.description = description;
        this.author = author;
        this.defaultEnabled = defaultEnabled;
    }

    /**
     * 简化的构造方法（仅名称、版本、描述）
     *
     * @param name        模块名称
     * @param version     版本号
     * @param description 描述
     */
    public ModuleMetadata(String name, String version, String description) {
        this(name, name, version,
                ModuleCategory.CORE,
                List.of(), List.of(),
                description, "Renderium Team", true);
    }

    /** 模块唯一标识符 */
    public String id() { return id; }

    /** 模块显示名称 */
    public String name() { return name; }

    /** 模块版本号 */
    public String version() { return version; }

    /** 模块分类 */
    public ModuleCategory category() { return category; }

    /** 前置依赖模块 ID 列表 */
    public List<String> dependencies() { return dependencies; }

    /** 可选依赖模块 ID 列表 */
    public List<String> optionalDependencies() { return optionalDependencies; }

    /** 模块描述 */
    public String description() { return description; }

    /** 作者 */
    public String author() { return author; }

    /** 是否默认启用 */
    public boolean defaultEnabled() { return defaultEnabled; }

    /** 是否为 CORE 分类模块 */
    public boolean isCoreModule() { return category == ModuleCategory.CORE; }
}
