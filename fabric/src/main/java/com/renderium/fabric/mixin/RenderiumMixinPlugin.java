// Renderium - Fabric Mixin Plugin (v2)
// 修复：使用 Fabric Loader API 直接检测模组，避免 PlatformHelper null 问题
//
// ⚠️ 重要说明：
// - 此类的 onLoad() 在 RenderiumMod.onInitializeClient() 之前执行
// - 此时 PlatformHelper 尚未初始化，不能使用 PlatformHelper.getInstance()
// - 必须使用 net.fabricmc.loader.FabricLoader 直接检测模组

package com.renderium.fabric.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Renderium Fabric Mixin 插件（v2 - 修复版）。
 *
 * <p><b>核心改进：</b>使用 {@link FabricLoader} API 直接检测模组，
 * 避免在 {@code onLoad()} 中调用未初始化的 {@code PlatformHelper}。</p>
 *
 * <h3>Mixin 加载策略：</h3>
 * <ul>
 *   <li><b>核心 Mixin</b>：始终加载（RenderSystem、GameRenderer 等）</li>
 *   <li><b>Sodium 相关 Mixin</b>：仅在 Sodium 存在时加载</li>
 *   <li><b>Vulkan Backport Mixin</b>：始终加载（内部有运行时检查）</li>
 * </ul>
 *
 * <h3>Debug 输出：</h3>
 * <p>当 Debug 模式启用时，会输出详细的 Mixin 加载决策日志。</p>
 *
 * @author Renderium Team
 * @version 2.0
 * @since 1.0.0
 */
public class RenderiumMixinPlugin implements IMixinConfigPlugin {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger("Renderium-MixinPlugin");

    /** Sodium 模组 ID */
    private static final String SODIUM_MOD_ID = "sodium";

    /** Sodium 相关 Mixin 的类名前缀 */
    private static final String SODIUM_MIXIN_PREFIX = "com.renderium.fabric.mixin.MixinSodium";

    /** Sodium 可选 Mixin（目标类可能不存在，启动时静默跳过） */
    private static final Set<String> SODIUM_OPTIONAL_MIXINS = java.util.Collections.singleton(
            "com.renderium.fabric.mixin.ModSettingsButtonMixin"
    );

    /** Vulkan 相关 Mixin 的类名 */
    private static final String VULKAN_BACKEND_MIXIN = "com.renderium.fabric.mixin.MixinVulkanBackend";

    /** 缓存的 Sodium 存在状态 */
    private Boolean sodiumPresent = null;

    /** 是否启用 Debug 模式（通过 JVM 参数 -Drenderium.debug=true 启用） */
    private final boolean debugMode = Boolean.getBoolean("renderium.debug");

    /**
     * Mixin 配置加载时的回调
     *
     * <p><b>时机：</b>此方法在类加载阶段执行，早于任何 Mod 的初始化方法。
     * 因此<b>不能</b>依赖任何需要初始化的服务（如 PlatformHelper）。</p>
     *
     * @param mixinPackage 当前 Mixin 包名
     */
    @Override
    public void onLoad(String mixinPackage) {
        // 使用 FabricLoader API 直接检测 Sodium（不需要 PlatformHelper）
        this.sodiumPresent = FabricLoader.getInstance().isModLoaded(SODIUM_MOD_ID);

        if (debugMode) {
            LOGGER.info("[DEBUG] RenderiumMixinPlugin.onLoad() called");
            LOGGER.info("[DEBUG] Mixin package: " + mixinPackage);
            LOGGER.info("[DEBUG] Sodium present: " + sodiumPresent);
            LOGGER.info("[DEBUG] Debug mode: ENABLED");
            logFabricEnvironment();
        }
    }

    /**
     * 判断是否应该应用某个 Mixin
     *
     * @param targetClassName 目标类全限定名
     * @param mixinClassName  Mixin 类全限定名
     * @return true 如果应该应用该 Mixin
     */
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Sodium 相关 Mixin 仅在 Sodium 存在时加载
        if (mixinClassName.startsWith(SODIUM_MIXIN_PREFIX)
                || SODIUM_OPTIONAL_MIXINS.contains(mixinClassName)) {
            if (!sodiumPresent) {
                if (debugMode) {
                    LOGGER.info("[DEBUG] Skipping " + mixinClassName + " (Sodium not present)");
                }
                return false;
            }

            if (debugMode) {
                LOGGER.info("[DEBUG] Allowing " + mixinClassName + " (Sodium present)");
            }
            return true;
        }

        // VulkanBackend Mixin - 在编译时检查目标类是否存在
        // 如果 Minecraft 版本不包含 VulkanBackend，则跳过
        if (mixinClassName.equals(VULKAN_BACKEND_MIXIN)) {
            try {
                Class.forName("com.mojang.blaze3d.vulkan.VulkanBackend");
                if (debugMode) {
                    LOGGER.info("[DEBUG] Allowing " + mixinClassName + " (VulkanBackend class found)");
                }
                return true;
            } catch (ClassNotFoundException e) {
                if (debugMode) {
                    LOGGER.info("[DEBUG] Skipping " + mixinClassName + " (VulkanBackend class not found)");
                }
                return false;
            }
        }

        // 所有其他 Mixin 始终加载
        if (debugMode) {
            LOGGER.info("[DEBUG] Allowing " + mixinClassName + " (core mixin)");
        }
        return true;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        if (debugMode) {
            LOGGER.info("[DEBUG] Accept targets - myTargets: " + myTargets.size());
            LOGGER.info("[DEBUG] Accept targets - otherTargets: " + otherTargets.size());
        }
    }

    @Override
    public List<String> getMixins() {
        return null; // 使用配置文件中的列表
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
        if (debugMode) {
            LOGGER.fine("[DEBUG] Pre-apply: " + mixinClassName + " -> " + targetClassName);
        }
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
                           String mixinClassName, IMixinInfo mixinInfo) {
        if (debugMode) {
            LOGGER.fine("[DEBUG] Post-applied: " + mixinClassName + " -> " + targetClassName);
        }
    }

    /**
     * 记录 Fabric 环境信息（仅 Debug 模式）
     */
    private void logFabricEnvironment() {
        try {
            FabricLoader loader = FabricLoader.getInstance();
            LOGGER.info("[DEBUG] Fabric Environment:");
            LOGGER.info("[DEBUG]   Game type: " + loader.getEnvironmentType());
            LOGGER.info("[DEBUG]   Loaded mods: " + loader.getAllMods().size());

            // 列出所有已加载的 mod
            loader.getAllMods().forEach(mod -> {
                LOGGER.info("[DEBUG]     - " + mod.getMetadata().getId()
                    + " v" + mod.getMetadata().getVersion().getFriendlyString());
            });
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[DEBUG] Failed to log environment", e);
        }
    }
}
