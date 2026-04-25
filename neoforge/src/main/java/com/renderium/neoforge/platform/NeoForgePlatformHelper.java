// Renderium - NeoForge 平台帮助器实现
// 实现 PlatformHelper 接口，提供 NeoForge 特定功能

package com.renderium.neoforge.platform;

import com.renderium.platform.PlatformHelper;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.Optional;

/**
 * NeoForge 平台帮助器实现
 * <p>
 * 使用 NeoForge API 实现平台特定功能。
 *
 * @since 1.0.0
 */
public final class NeoForgePlatformHelper implements PlatformHelper {

    @Override
    public String getPlatformName() {
        return "NeoForge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public Optional<String> getModVersion(String modId) {
        return ModList.get()
                .getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().toString());
    }

    @Override
    public String getGameDirectory() {
        return FMLPaths.GAMEDIR.get().toAbsolutePath().toString();
    }

    @Override
    public String getConfigDirectory() {
        return FMLPaths.CONFIGDIR.get().toAbsolutePath().toString();
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLEnvironment.isProduction();
    }

    /**
     * NeoForge 模组使用 {@code @Mod(dist = Dist.CLIENT)} 注解标记，
     * 因此此实现类仅在客户端环境被加载。
     * 如果此方法被调用，说明当前一定是客户端环境。
     */
    @Override
    public boolean isClientEnvironment() {
        // NeoForge 入口使用 @Mod(dist = Dist.CLIENT)，
        // 如果能执行到这里，必然是客户端
        return true;
    }
}
