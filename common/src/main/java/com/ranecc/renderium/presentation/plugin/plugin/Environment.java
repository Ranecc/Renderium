// Renderium - Blaze3D 优化器插件系统
// 运行时环境信息

package com.ranecc.renderium.presentation.plugin.plugin;

import java.util.Set;

/**
 * 运行时环境信息
 * <p>
 * 封装当前 Minecraft 运行环境的所有相关信息，
 * 包括版本、GPU 能力、已加载模组等。
 * 插件使用此对象判断是否支持当前环境。
 *
 * <h2>使用场景：</h2>
 * <ul>
 *   <li>检查 MC 版本是否匹配</li>
 *   <li>检测 GPU 特性（Vulkan/OpenGL/Compute Shader）</li>
 *   <li>识别冲突模组</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public final class Environment {

    // ==================== 基础环境信息 ====================

    /** Minecraft 版本字符串，如 "26.2.1" */
    private final String minecraftVersion;

    /** 加载器类型: "fabric" / "neoforge" / "forge" */
    private final String loaderType;

    /** 是否为客户端环境 */
    private final boolean isClient;

    // ==================== GPU/渲染能力 ====================

    /** 是否支持 Vulkan 渲染后端 */
    private final boolean hasVulkanSupport;

    /** 是否支持 OpenGL 渲染后端 */
    private final boolean hasOpenGLSupport;

    /** 是否支持 Compute Shader（需要 Vulkan 或 GL 4.3+） */
    private final boolean hasComputeShaderSupport;

    /** GPU 显存大小（MB），未知时为 -1 */
    private final int gpuMemoryMB;

    // ==================== 模组信息 ====================

    /** 当前加载的所有模组 ID 集合（不可变） */
    private final Set<String> loadedModIds;

    // ==================== 构造函数 ====================

    /**
     * 创建运行时环境实例
     *
     * @param minecraftVersion       MC 版本
     * @param loaderType             加载器类型
     * @param isClient               是否客户端
     * @param hasVulkanSupport       是否支持 Vulkan
     * @param hasOpenGLSupport       是否支持 OpenGL
     * @param hasComputeShaderSupport 是否支持计算着色器
     * @param gpuMemoryMB            GPU 显存大小（-1 表示未知）
     * @param loadedModIds           已加载模组 ID 集合
     */
    public Environment(String minecraftVersion, String loaderType, boolean isClient,
                       boolean hasVulkanSupport, boolean hasOpenGLSupport,
                       boolean hasComputeShaderSupport, int gpuMemoryMB,
                       Set<String> loadedModIds) {
        this.minecraftVersion = minecraftVersion;
        this.loaderType = loaderType;
        this.isClient = isClient;
        this.hasVulkanSupport = hasVulkanSupport;
        this.hasOpenGLSupport = hasOpenGLSupport;
        this.hasComputeShaderSupport = hasComputeShaderSupport;
        this.gpuMemoryMB = gpuMemoryMB;
        this.loadedModIds = Set.copyOf(loadedModIds);
    }

    // ==================== 版本检查方法 ====================

    /**
     * 检查 MC 版本是否以指定前缀开头
     *
     * @param prefix 版本前缀，如 "26.2"
     * @return 如果版本匹配返回 true
     */
    public boolean isVersionPrefix(String prefix) {
        return minecraftVersion != null && minecraftVersion.startsWith(prefix);
    }

    /**
     * 获取主版本号（如 "26.2"）
     *
     * @return 主版本部分
     */
    public String getMajorVersion() {
        if (minecraftVersion == null) return null;
        int dotIndex = minecraftVersion.indexOf('.');
        if (dotIndex < 0) return minecraftVersion;
        int secondDot = minecraftVersion.indexOf('.', dotIndex + 1);
        return secondDot > 0 ? minecraftVersion.substring(0, secondDot) : minecraftVersion;
    }

    // ==================== 模组检测方法 ====================

    /**
     * 检查指定模组是否已加载
     *
     * @param modId 模组 ID
     * @return 如果模组已加载返回 true
     */
    public boolean hasMod(String modId) {
        return loadedModIds.contains(modId);
    }

    /**
     * 检查是否有任何指定列表中的模组已加载
     *
     * @param modIds 模组 ID 列表
     * @return 如果任一模组存在返回 true
     */
    public boolean hasAnyMod(Iterable<String> modIds) {
        for (String modId : modIds) {
            if (loadedModIds.contains(modId)) {
                return true;
            }
        }
        return false;
    }

    // ==================== Getter 方法 ====================

    /**
     * 获取 Minecraft 完整版本字符串
     *
     * @return 版本字符串，如 "26.2.1"
     */
    public String getMinecraftVersion() { return minecraftVersion; }

    /**
     * 获取加载器类型
     *
     * @return 加载器标识：fabric/neoforge/forge
     */
    public String getLoaderType() { return loaderType; }

    /**
     * 是否客户端环境
     *
     * @return true 如果在客户端运行
     */
    public boolean isClient() { return isClient; }

    /**
     * 是否支持 Vulkan 后端
     *
     * @return true 如果 Vulkan 可用
     */
    public boolean hasVulkanSupport() { return hasVulkanSupport; }

    /**
     * 是否支持 OpenGL 后端
     *
     * @return true 如果 OpenGL 可用
     */
    public boolean hasOpenGLSupport() { return hasOpenGLSupport; }

    /**
     * 是否支持 Compute Shader
     *
     * @return true 如果计算着色器可用
     */
    public boolean hasComputeShaderSupport() { return hasComputeShaderSupport; }

    /**
     * 获取 GPU 显存大小
     *
     * @return 显存大小（MB），-1 表示未知
     */
    public int getGpuMemoryMB() { return gpuMemoryMB; }

    /**
     * 获取已加载模组 ID 的不可变集合
     *
     * @return 模组 ID 集合
     */
    public Set<String> getLoadedModIds() { return loadedModIds; }
}
