// Renderium - Blaze3D Shader 转译模块
// VkShaderModule 注册表 - ResourceLocation → handle 映射

package com.ranecc.renderium.feature.blaze3d.shader;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * VkShaderModule 注册表。
 * <p>
 * 将 ResourceLocation 映射到 Vulkan VkShaderModule 句柄，
 * 供 Mixin 和渲染管线快速查找已编译的 shader module。</p>
 *
 * @since 3.0.0
 */
public final class RenderiumShaderRegistry {

    private static final Logger LOGGER = Logger.getLogger("Renderium-ShaderRegistry");

    /** location → VkShaderModule handle 映射 (线程安全) */
    private final ConcurrentHashMap<String, Long> modules = new ConcurrentHashMap<>();

    private static volatile RenderiumShaderRegistry instance;

    private RenderiumShaderRegistry() {}

    /**
     * 获取单例实例（线程安全懒加载 - 双重检查锁定）
     * 
     * <p>使用 volatile + 双重检查锁定确保线程安全和性能。
     */
    public static RenderiumShaderRegistry getInstance() {
        if (instance == null) {
            synchronized (RenderiumShaderRegistry.class) {
                if (instance == null) {
                    instance = new RenderiumShaderRegistry();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化 Shader 注册表
     *
     * <p>在模块加载时调用，预注册内置 Shader。
     */
    public void initialize() {
        if (!modules.isEmpty()) {
            LOGGER.warning("Shader Registry 已初始化，跳过重复初始化");
            return;
        }
        LOGGER.info("RenderiumShaderRegistry 已初始化");
    }

    /**
     * 注册一个 VkShaderModule
     *
     * @param location 资源位置标识符 (如 "shaders/gbuffers_terrain")
     * @param shaderModule VkShaderModule handle (非零)
     */
    public void register(String location, long shaderModule) {
        if (location == null || shaderModule == 0L) return;
        Long prev = modules.put(location, shaderModule);
        if (prev != null && prev != shaderModule) {
            LOGGER.fine("Shader registry 更新: " + location +
                    " → 0x" + Long.toHexString(shaderModule) +
                    " (旧: 0x" + Long.toHexString(prev) + ")");
        }
    }

    /**
     * 查找 VkShaderModule
     *
     * @param location 资源位置标识符
     * @return VkShaderModule handle，未找到返回 null
     */
    public Long get(String location) {
        return modules.get(location);
    }

    /**
     * 是否包含指定 location
     */
    public boolean contains(String location) {
        return modules.containsKey(location);
    }

    /** 清除所有注册的模块 */
    public void clear() {
        int count = modules.size();
        modules.clear();
        if (count > 0) {
            LOGGER.info("Shader registry 已清除 " + count + " 个模块");
        }
    }

    /** 当前注册的模块数量 */
    public int size() { return modules.size(); }
}
