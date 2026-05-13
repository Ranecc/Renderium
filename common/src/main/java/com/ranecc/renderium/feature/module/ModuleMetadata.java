package com.ranecc.renderium.feature.module;

/** 模块元数据 [TODO] 完整实现待补充 */
public class ModuleMetadata {
    private String name;
    private String version;
    private String description;

    public ModuleMetadata(String name, String version, String description) {
        this.name = name;
        this.version = version;
        this.description = description;
    }

    public String getName() { return name; }
    public String getVersion() { return version; }
    public String getDescription() { return description; }
}
