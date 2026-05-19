package com.ranecc.renderium.infrastructure.gpu;

import java.lang.invoke.MethodHandle;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Vulkan API 注册中心 — 统一管理所有 FFM 绑定的查找、校验与调用。
 *
 * <p>替代 {@code VulkanFFMBinding} 的 40+ 散装 getter 模式。
 * 所有 Vulkan API 函数名注册到同一张表中，提供统一的调取和可用性检查。
 *
 * <h3>使用方式</h3>
 * <pre>
 * // 注册（由 VulkanFFMBinding 初始化时调用）
 * VulkanAPIRegistry.register("vkCmdDispatch", methodHandle);
 *
 * // 调用
 * int result = (int) VulkanAPIRegistry.invoke("vkCreateShaderModule", device, info, 0L, out);
 *
 * // 可用性检查
 * if (VulkanAPIRegistry.isAvailable("vkCmdDispatch")) { ... }
 *
 * // 批量校验（启动时确保核心 API 齐全）
 * VulkanAPIRegistry.verifyOrThrow("vkCreateBuffer", "vkDestroyBuffer", "vkCmdDispatch");
 * </pre>
 *
 * @see VulkanFFMBinding
 */
public final class VulkanAPIRegistry {

    private static final Logger LOGGER = Logger.getLogger("Renderium|VulkanAPI");

    private static final ConcurrentHashMap<String, MethodHandle> HANDLES = new ConcurrentHashMap<>();
    private static volatile boolean sealed = false;

    private VulkanAPIRegistry() {}

    /**
     * 注册一个 Vulkan API 函数。
     */
    public static void register(String apiName, MethodHandle handle) {
        if (sealed) {
            throw new IllegalStateException("VulkanAPIRegistry 已密封，无法注册: " + apiName);
        }
        if (handle == null) {
            LOGGER.warning("VulkanAPIRegistry: " + apiName + " 注册的 MethodHandle 为 null");
            return;
        }
        HANDLES.put(apiName, handle);
    }

    /**
     * 批量注册 Vulkan API 函数。
     */
    public static void registerAll(Map<String, MethodHandle> apis) {
        if (sealed) {
            throw new IllegalStateException("VulkanAPIRegistry 已密封，无法批量注册");
        }
        if (apis != null) {
            HANDLES.putAll(apis);
        }
    }

    /**
     * 密封注册中心，阻止后续注册（可选，用于调试阶段检测遗漏注册）。
     */
    public static void seal() {
        sealed = true;
    }

    /**
     * 检查指定 API 是否已注册且可用。
     */
    public static boolean isAvailable(String apiName) {
        MethodHandle handle = HANDLES.get(apiName);
        return handle != null;
    }

    /**
     * 获取指定 API 的 MethodHandle。
     *
     * @return MethodHandle，如果未注册返回 null
     */
    public static MethodHandle getHandle(String apiName) {
        return HANDLES.get(apiName);
    }

    /**
     * 调用指定 Vulkan API。
     *
     * @param <T>  返回类型
     * @param apiName  Vulkan API 函数名（如 "vkCmdDispatch"）
     * @param args    调用参数
     * @return 函数返回值（如果返回 void 则为 null）
     * @throws IllegalStateException 如果 API 未注册
     * @throws Throwable             MethodHandle.invoke 抛出的异常
     */
    @SuppressWarnings("unchecked")
    public static <T> T invoke(String apiName, Object... args) throws Throwable {
        MethodHandle handle = HANDLES.get(apiName);
        if (handle == null) {
            throw new IllegalStateException("Vulkan API 未注册: " + apiName
                + " — 请检查 VulkanFFMBinding 初始化是否完成");
        }
        return (T) handle.invokeWithArguments(args);
    }

    /**
     * 调用指定 Vulkan API（使用 invokeWithArguments，展开可变参数数组）。
     */
    public static Object invokeExact(String apiName, Object... args) throws Throwable {
        MethodHandle handle = HANDLES.get(apiName);
        if (handle == null) {
            throw new IllegalStateException("Vulkan API 未注册: " + apiName);
        }
        return handle.invokeWithArguments(args);
    }

    /**
     * 验证所有指定的 API 均已注册。
     *
     * @param apiNames 需要验证的 API 名称列表
     * @throws IllegalStateException 如果任何 API 未注册
     */
    public static void verifyOrThrow(String... apiNames) {
        for (String name : apiNames) {
            if (!HANDLES.containsKey(name)) {
                throw new IllegalStateException("必需 Vulkan API 未注册: " + name);
            }
        }
    }

    /**
     * 获取当前已注册的所有 API 名称（只读视图）。
     */
    public static Set<String> getRegisteredAPIs() {
        return Collections.unmodifiableSet(HANDLES.keySet());
    }

    /**
     * 获取已注册的 API 数量。
     */
    public static int getRegisteredCount() {
        return HANDLES.size();
    }

    /**
     * 清除所有注册（用于重启/重新初始化）。
     */
    public static void reset() {
        HANDLES.clear();
        sealed = false;
        LOGGER.fine("VulkanAPIRegistry 已重置");
    }
}
