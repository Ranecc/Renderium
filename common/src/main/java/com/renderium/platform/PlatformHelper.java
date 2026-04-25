// Renderium - 平台抽象接口
// 提供平台无关的 API 来访问平台特定功能

package com.renderium.platform;

import java.util.Optional;

/**
 * 平台抽象接口
 * <p>
 * 提供平台无关的 API 来访问平台特定功能，如模组检测、配置目录等。
 * 由各平台（Fabric/NeoForge）提供具体实现。
 *
 * @since 1.0.0
 */
public interface PlatformHelper {

    /**
     * 获取平台名称
     *
     * @return 平台名称（如 "Fabric", "NeoForge"）
     */
    String getPlatformName();

    /**
     * 检查指定模组是否已加载
     *
     * @param modId 模组 ID
     * @return 如果模组已加载则返回 true
     */
    boolean isModLoaded(String modId);

    /**
     * 获取模组版本
     *
     * @param modId 模组 ID
     * @return 模组版本字符串，如果模组未加载则返回空
     */
    Optional<String> getModVersion(String modId);

    /**
     * 获取游戏目录
     *
     * @return 游戏目录的绝对路径
     */
    String getGameDirectory();

    /**
     * 获取配置目录
     *
     * @return 配置目录的绝对路径
     */
    String getConfigDirectory();

    /**
     * 检查是否在开发环境
     *
     * @return 如果在开发环境则返回 true
     */
    boolean isDevelopmentEnvironment();

    /**
     * 检查当前是否运行在客户端环境中。
     *
     * <p>Renderium 的核心功能（Vulkan 渲染、DLSS、后处理等）
     * 仅在客户端有效。服务端调用任何图形相关 API 都会导致崩溃。
     *
     * <p><b>重要：</b>此方法必须在调用任何 graphics.* 或 vulkan.* 包中的代码之前检查。
     *
     * @return 如果当前是客户端环境则返回 true，否则返回 false（表示服务端或无头模式）
     */
    boolean isClientEnvironment();

    /**
     * 获取当前平台帮助器实例
     *
     * @return 平台帮助器实例
     * @throws IllegalStateException 如果平台未初始化
     */
    static PlatformHelper getInstance() {
        return PlatformHelperHolder.INSTANCE;
    }

    /**
     * 初始化平台帮助器
     *
     * @param helper 平台帮助器实例
     * @throws IllegalStateException 如果已经初始化
     */
    static void initialize(PlatformHelper helper) {
        if (PlatformHelperHolder.INSTANCE != null) {
            throw new IllegalStateException("PlatformHelper already initialized");
        }
        PlatformHelperHolder.INSTANCE = helper;
    }
}

/**
 * 延迟初始化持有者
 */
final class PlatformHelperHolder {
    static volatile PlatformHelper INSTANCE;

    private PlatformHelperHolder() {}
}
