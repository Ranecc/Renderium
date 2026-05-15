package com.ranecc.renderium.platform.bridge.video;

import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 兼容模式视频设置提供者（Sodium 兼容）
 * <p>
 * 检测 Sodium 模组是否存在，并在兼容模式下运行。
 * 当 Sodium 已加载时，此提供者将 Renderium 设置作为标签页追加到
 * Sodium 的 VideoSettingsScreen 中，而非替换整个界面。
 * <p>
 * 检测机制：
 * <ul>
 *   <li>通过 {@code Class.forName()} 反射检测 Sodium 核心类</li>
 *   <li>检测结果缓存（避免重复反射开销）</li>
 *   <li>首次调用时执行检测，后续直接返回缓存结果</li>
 * </ul>
 *
 * <h3>架构位置：</h3>
 * <pre>
 * ┌─────────────────────────────────────────────┐
 * │  Sodium VideoSettingsScreen                 │
 * │  ├─ General Options (原生)                   │
 * │  ├─ Quality Options (原生)                   │
 * │  ├─ ...                                      │
 * │  └─ Renderium Settings ← 由本提供者追加      │
 * │       (通过 SodiumIntegrationMixin 注入)     │
 * └─────────────────────────────────────────────┘
 * </pre>
 *
 * @see VideoSettingsProvider
 * @see StandaloneProvider
 * @see VideoSettingsBridge#initialize()
 * @since 1.0.0
 */
public final class SodiumCompatibleProvider implements VideoSettingsProvider {

    /** 日志记录器（SLF4J，名称：Renderium-VideoSettings） */
    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium-VideoSettings");

    /** 单例实例（无状态，线程安全） */
    public static final SodiumCompatibleProvider INSTANCE = new SodiumCompatibleProvider();

    /**
     * Sodium 视频设置界面的全限定类名
     * <p>用于运行时反射检测 Sodium 是否已加载
     */
    private static final String SODIUM_VIDEO_SETTINGS_CLASS =
            "net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen";

    /** Sodium 检测缓存（volatile 保证多线程可见性） */
    private static volatile Boolean sodiumDetectedCache = null;

    private SodiumCompatibleProvider() {}

    /**
     * 打开兼容模式的视频设置界面
     *
     * <p>在 Sodium 环境下，此方法返回 null（兼容模式不打开独立界面）。
     * 实际的界面集成由 {@code SodiumIntegrationMixin} 在 Sodium 的 init() 尾部完成，
     * 通过 {@link #appendToSodiumScreen(Object)} 方法将 Renderium 设置作为标签页追加。</p>
     *
     * <h4>设计决策：</h4>
     * <ul>
     *   <li>返回 null 通知 Mixin 层不要替换原版界面</li>
     *   <li>由 SodiumIntegrationMixin 负责追加标签页到 Sodium 界面</li>
     *   <li>避免重复创建界面或冲突</li>
     * </ul>
     *
     * @param parent 父级 Screen（通常为 OptionsScreen）
     * @return 始终返回 null（兼容模式不打开独立界面）
     */
    @Override
    public Screen openSettings(Screen parent) {
        // 兼容模式：不打开独立界面，由 SodiumIntegrationMixin 负责追加标签页
        // 返回 null 通知 MixinOptionsScreen 让原版逻辑继续执行
        return null;
    }

    /**
     * 检查 Sodium 兼容模式是否可用
     * <p>
     * 通过反射检测 Sodium 核心类是否存在于类路径中。
     * 使用缓存机制避免每次调用的反射开销：
     * <ul>
     *   <li>首次调用：执行 Class.forName() 检测并缓存结果</li>
     *   <li>后续调用：直接返回缓存值（&lt; 10ns）</li>
     * </ul>
     *
     * @return true 如果 Sodium 模组已加载且可用
     */
    @Override
    public boolean isAvailable() {
        Boolean cached = sodiumDetectedCache;
        if (cached != null) {
            return cached;
        }
        boolean detected = detectSodium();
        sodiumDetectedCache = detected;
        return detected;
    }

    /**
     * 获取提供者名称
     *
     * @return "Sodium Compatible"（固定值）
     */
    @Override
    public String getName() {
        return "Sodium Compatible";
    }

    /**
     * 执行 Sodium 存在性检测
     * <p>
     * 通过 Class.forName 反射加载 Sodium 的 VideoSettingsScreen 类。
     * 如果类加载成功，说明 Sodium 模组已存在于类路径中。
     * <p>
     * 性能说明：
     * <ul>
     *   <li>仅首次调用时执行（后续使用缓存）</li>
     *   <li>Class.forName 开销约 1-5ms（可接受的一次性成本）</li>
     * </ul>
     *
     * @return true 如果 Sodium 的 VideoSettingsScreen 类可加载
     */
    private static boolean detectSodium() {
        try {
            Class.forName(SODIUM_VIDEO_SETTINGS_CLASS);
            LOGGER.info("Sodium detected: compatible mode enabled");
            return true;
        } catch (ClassNotFoundException e) {
            LOGGER.debug("Sodium not found: standalone mode will be used");
            return false;
        }
    }

    /**
     * 重置检测缓存（用于测试或模块热重载场景）
     * <p>
     * 清除缓存的检测结果，下次调用 {@link #isAvailable()} 时将重新检测。
     */
    static void resetDetectionCache() {
        sodiumDetectedCache = null;
    }

    // ==================== Sodium 集成 API ====================

    /**
     * 追加 Renderium 页面到 Sodium 的 VideoSettingsScreen
     *
     * <p>此方法由 {@link VideoSettingsBridge#appendSodiumPage(Object)} 调用，
     * 在 Sodium 的 VideoSettingsScreen.init() 完成后执行。
     * 负责将 Renderium 设置页面追加到 Sodium 的 PageListWidget 中。</p>
     *
     * <h4>实现策略：</h4>
     * <ul>
     *   <li><b>方案 A（推荐）</b>: 使用 Mixin Accessor 访问 pageList 字段</li>
     *   <li><b>方案 B（备选）</b>: 使用反射调用 addPage() 方法</li>
     *   <li><b>方案 C（最后手段）</b>: 直接操作 Widget 树</li>
     * </ul>
     *
     * <h4>当前状态：</h4>
     * <ul>
     *   <li>记录日志表示已进入追加逻辑</li>
     *   <li>具体实现在 Task 5.x 完成（需要定义 RendererOptionPage）</li>
     *   <li>当前为空实现，仅记录日志</li>
     * </ul>
     *
     * @param sodiumScreen Sodium 的 VideoSettingsScreen 实例（由 Mixin 传入）
     */
    public void appendToSodiumScreen(Object sodiumScreen) {
        LOGGER.info("Appending Renderium tab to Sodium VideoSettingsScreen");

        // TODO: Task 5.x - 实现具体的追加逻辑
        // 方案 1: 使用 Mixin Accessor 访问 private final PageListWidget pageList 字段
        //   SodiumVideoSettingsAccessor accessor = (SodiumVideoSettingsAccessor) sodiumScreen;
        //   accessor.renderium$getPageList().addPage(renderiumPage);
        //
        // 方案 2: 使用反射调用 addPage() 方法
        //   Method addPageMethod = sodiumScreen.getClass().getDeclaredMethod("addPage", RendererOptionPage.class);
        //   addPageMethod.setAccessible(true);
        //   addPageMethod.invoke(sodiumScreen, createRenderiumPage());
        //
        // 方案 3: 直接操作子组件（不推荐，脆弱且版本敏感）
    }
}
