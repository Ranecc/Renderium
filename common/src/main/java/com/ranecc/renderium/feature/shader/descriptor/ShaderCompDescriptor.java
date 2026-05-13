package com.ranecc.renderium.feature.shader.descriptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * TODO [REVIEW] 桩类 - Shader Comp 文件描述符
 * 描述一个 .comp 着色器节点的完整元信息
 */
public class ShaderCompDescriptor {
    private final String nodeId;
    private final String version;
    private final Path sourcePath;
    private final Set<String> tags;
    private final Map<String, Object> metadata;
    private final Instant createdAt;
    private Instant lastModified;

    public ShaderCompDescriptor(String nodeId, String version, Path sourcePath) {
        this.nodeId = nodeId;
        this.version = version;
        this.sourcePath = sourcePath;
        this.tags = new LinkedHashSet<>();
        this.metadata = new LinkedHashMap<>();
        this.createdAt = Instant.now();
        this.lastModified = this.createdAt;
    }

    /** 获取节点唯一ID */
    public String getNodeId() { return nodeId; }

    /** 获取版本号 */
    public String getVersion() { return version; }

    /** 获取源文件路径 */
    public Path getSourcePath() { return sourcePath; }

    /** 获取标签集合（只读） */
    public Set<String> getTags() { return Collections.unmodifiableSet(tags); }

    /** 获取元数据映射（只读） */
    public Map<String, Object> getMetadata() { return Collections.unmodifiableMap(metadata); }

    /** 获取创建时间 */
    public Instant getCreatedAt() { return createdAt; }

    /** 获取最后修改时间 */
    public Instant getLastModified() { return lastModified; }

    /** 添加标签 */
    public void addTag(String tag) { this.tags.add(tag); }

    /** 设置元数据键值对 */
    public void setMetadata(String key, Object value) { this.metadata.put(key, value); }

    /** 更新最后修改时间 */
    public void touch() { this.lastModified = Instant.now(); }
}
