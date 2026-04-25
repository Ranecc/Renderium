// Renderium - Fabric 平台帮助器实现
// 实现 PlatformHelper 接口，提供 Fabric 特定功能

package com.renderium.fabric.platform;

import com.renderium.platform.PlatformHelper;
import net.fabricmc.loader.api.FabricLoader;

import java.util.Optional;

/**
 * Fabric 平台帮助器实现
 * <p>
 * 使用 FabricLoader API 实现平台特定功能。
 *
 * @since 1.0.0
 */
public final class FabricPlatformHelper implements PlatformHelper {

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public Optional<String> getModVersion(String modId) {
        return FabricLoader.getInstance()
                .getModContainer(modId)
                .map(container -> container.getMetadata().getVersion().getFriendlyString());
    }

    @Override
    public String getGameDirectory() {
        return FabricLoader.getInstance().getGameDir().toAbsolutePath().toString();
    }

    @Override
    public String getConfigDirectory() {
        return FabricLoader.getInstance().getConfigDir().toAbsolutePath().toString();
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    /**
     * Fabric 模组使用 {@code @Environment(EnvType.CLIENT)} 注解标记，
     * 因此此实现类仅在客户端环境被加载。
     * 如果此方法被调用，说明当前一定是客户端环境。
     */
    @Override
    public boolean isClientEnvironment() {
        // Fabric 入口使用 @Environment(EnvType.CLIENT)，
        // 如果能执行到这里，必然是客户端
        return true;
    }
}
