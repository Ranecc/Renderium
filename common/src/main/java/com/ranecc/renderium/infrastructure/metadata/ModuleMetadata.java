package com.ranecc.renderium.infrastructure.metadata;

/**
 * TODO [REVIEW] 桩类 - 模块元数据持有者
 * 用于描述功能模块的基本信息（ID、版本号、显示名称、描述等）
 */
public class ModuleMetadata {
    private final String moduleId;
    private final String version;
    private final String displayName;
    private final String description;

    public ModuleMetadata(String moduleId, String version, String displayName, String description) {
        this.moduleId = moduleId;
        this.version = version;
        this.displayName = displayName;
        this.description = description;
    }

    /** 获取模块唯一标识 */
    public String getModuleId() { return moduleId; }

    /** 获取模块版本号 */
    public String getVersion() { return version; }

    /** 获取显示名称 */
    public String getDisplayName() { return displayName; }

    /** 获取模块描述 */
    public String getDescription() { return description; }
}
