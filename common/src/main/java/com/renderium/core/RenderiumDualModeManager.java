// Renderium - 三轨制架构管理器 (v6)
// 统一管理兼容模式、受限兼容模式和狂暴模式的初始化、自动切换与用户通知
// v6 Phase 0: 简化为仅检测 Sodium，移除深度集成逻辑

package com.renderium.core;

import com.renderium.optimization.lod.RenderiumLODManager;
import com.renderium.platform.PlatformHelper;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Renderium v6 三轨制架构管理器。
 *
 * <p>整合了"双轨制"、"总体概念设计"、"总体概念设计2"三个文档的核心思想，
 * 并在 v6 Phase 0 中进行了重大策略调整：
 *
 * <h2>v6 三轨制设计（来自 双轨制.md + v6 策略决策）</h2>
 * <ul>
 *   <li><b>兼容模式 (COMPATIBILITY)</b>：无 Sodium 时默认启用。
 *       提供完整的后处理管线和安全微调功能。</li>
 *   <li><b>受限兼容模式 (COMPATIBILITY_LIMITED)</b>：检测到 Sodium 时自动启用。
 *       仅提供基础监控和日志，不进行 FBO 拦截和输出重定向。
 *       建议用户卸载 Sodium 以获得最佳体验。</li>
 *   <li><b>狂暴模式 (AGGRESSIVE)</b>：无 Sodium 时启用或用户手动选择。
 *       劫持 Chunk/PalettedContainer，用堆外 MemorySegment 替换，实现独立优化。</li>
 * </ul>
 *
 * <h2>v5 → v6 核心变更</h2>
 * <ul>
 *   <li>❌ 移除对 {@code SodiumFBOInterceptor} 的深度集成（v5 兼容模式核心）</li>
 *   <li>❌ 移除 FBO 拦截器初始化逻辑</li>
 *   <li>✅ 保留 Sodium 检测能力（sodiumPresent、sodiumVersion 字段）</li>
 *   <li>✅ 新增 COMPATIBILITY_LIMITED 模式（v6 新增）</li>
 *   <li>✅ 更新模式决策算法：Sodium 存在 → 受限兼容模式</li>
 *   <li>✅ 添加用户友好提示：建议卸载 Sodium</li>
 * </ul>
 *
 * <h2>设计理由：</h2>
 * <p>Sodium 是优秀的优化模组，与 Renderium 在功能上存在重叠。
 * v6 策略建议用户二选一使用，而非尝试深度集成（这会导致复杂性和稳定性问题）。
 * 当检测到共存时，Renderium 自动降级到受限模式，确保不会产生冲突或性能下降。
 *
 * <h2>官方 Vulkan 劫持（来自 总体概念设计.md §六 / 总体概念设计2.md §四）</h2>
 * <p>针对 MC 26.2 官方 Vulkan 后端，采用"黑盒劫持"策略：
 * 不 Hook 快照 API，而是劫持窗口创建和 Swapchain 初始化，
 * 拿到纯粹的 VkDevice 和 VkQueue 句柄后重建管线。
 *
 * <p><b>注意</b>：本类不直接引用 Minecraft 类。
 * 所有 MC 相关操作通过 {@link ModeNotificationSender} 接口抽象，
 * 由 Fabric/NeoForge 模块提供具体实现。
 *
 * @see ModeNotificationSender
 * @see PlatformHelper
 * @see RenderiumMode
 * @author Renderium Team
 * @since 1.0.0
 * @version 6.0
 */
public final class RenderiumDualModeManager {

    private static final Logger LOGGER = Logger.getLogger("Renderium-DualMode");

    // ==================== 模组 ID 定义 ====================

    /** Sodium 模组 ID */
    private static final String SODIUM_MOD_ID = "sodium";

    // ==================== 通知发送器接口 ====================

    /**
     * 模式通知发送器接口
     *
     * <p>抽象 MC 特定的消息发送逻辑，由 Fabric/NeoForge 模块实现。
     * 这样 common 模块就不需要直接依赖 MC 类。
     */
    public interface ModeNotificationSender {
        /**
         * 向玩家发送模式通知（完整版本）
         *
         * @param mode           当前运行模式
         * @param sodiumVersion  Sodium 版本（可能为空）
         * @param shaderModName  光影模组名称（可能为空）
         * @param shaderModPresent 是否检测到光影模组
         */
        void sendNotification(RenderiumMode mode, Optional<String> sodiumVersion,
                               String shaderModName, boolean shaderModPresent);

        /**
         * 检查玩家是否已就绪（世界已加载且玩家实体存在）
         *
         * @return true 如果可以发送通知
         */
        boolean isPlayerReady();

        /**
         * 延迟执行任务（在游戏主线程上）
         *
         * @param task 要执行的任务
         */
        void scheduleOnMainThread(Runnable task);
    }

    // ==================== 单例与状态 ====================

    private static volatile RenderiumDualModeManager instance;

    /** 当前运行模式 */
    private final RenderiumMode currentMode;

    /** 是否检测到 Sodium */
    private final boolean sodiumPresent;

    /** Sodium 版本信息 */
    private final Optional<String> sodiumVersion;

    /** 通知是否已发送 */
    private final AtomicBoolean notificationSent = new AtomicBoolean(false);

    /** 世界是否已加载 */
    private final AtomicBoolean worldLoaded = new AtomicBoolean(false);

    /** 通知发送器（由平台模块设置） */
    private volatile ModeNotificationSender notificationSender;

    /**
     * 私有构造函数 - 初始化三轨制管理器
     *
     * <p>构造流程（v6 简化版）：
     * <ol>
     *   <li>检测 Sodium 是否存在</li>
     *   <li>根据检测结果确定运行模式（v6: Sodium → COMPATIBILITY_LIMITED）</li>
     *   <li>记录启动日志（包含用户友好提示）</li>
     * </ol>
     *
     * <p><b>v6 变更：</b>不再初始化 FBO 拦截器等兼容模式组件。
     */
    private RenderiumDualModeManager() {
        PlatformHelper platform = PlatformHelper.getInstance();

        // 仅检测 Sodium（v6 保留检测能力，但不再深度集成）
        this.sodiumPresent = platform.isModLoaded(SODIUM_MOD_ID);
        this.sodiumVersion = this.sodiumPresent
                ? platform.getModVersion(SODIUM_MOD_ID)
                : Optional.empty();

        // 确定运行模式（v6 新算法）
        this.currentMode = determineMode();

        // 记录启动信息
        logStartupInfo();

        // v6: 不再调用 initializeCompatibilityComponents()
    }

    /**
     * 获取单例实例（线程安全懒加载 - 双重检查锁定）
     *
     * <p>使用 volatile 字段 + 双重检查锁定确保：
     * <ul>
     *   <li>线程安全：多个线程同时调用时只创建一个实例</li>
     *   <li>高性能：实例创建后无需同步开销</li>
     *   <li>有序性：volatile 防止指令重排导致部分构造的对象被访问</li>
     * </ul>
     *
     * @return RenderiumDualModeManager 单例
     */
    public static RenderiumDualModeManager getInstance() {
        if (instance == null) {
            synchronized (RenderiumDualModeManager.class) {
                if (instance == null) {
                    instance = new RenderiumDualModeManager();
                }
            }
        }
        return instance;
    }

    // ==================== 核心模式决策算法 (v6) ====================

    /**
     * 确定 Renderium 运行模式（v6 简化版）
     *
     * <p>v6 决策逻辑：
     * <ul>
     *   <li>检测到 Sodium → 自动启用受限兼容模式（COMPATIBILITY_LIMITED）</li>
     *   <li>未检测到 Sodium → 使用用户偏好或默认兼容模式</li>
     * </ul>
     *
     * <p><b>v5 → v6 变更：</b>从 COMPATIBILITY 改为 COMPATIBILITY_LIMITED，
     * 并添加用户友好提示建议卸载 Sodium。
     *
     * @return 确定的运行模式
     */
    private RenderiumMode determineMode() {
        if (sodiumPresent) {
            LOGGER.warning("═════════════════════════════════════════════════════");
            LOGGER.warning("  检测到 Sodium - 建议卸载以获得最佳 Renderium 体验");
            LOGGER.warning("  当前将启用兼容模式（功能受限）");
            LOGGER.warning("═════════════════════════════════════════════════════");
            return RenderiumMode.COMPATIBILITY_LIMITED;
        }

        // 无 Sodium → 用户选择或默认兼容
        return getUserPreferenceOrDefault(RenderiumMode.COMPATIBILITY);
    }

    /**
     * 获取用户偏好设置或返回默认值
     *
     * <p>从配置文件读取用户的模式偏好，
     * 如果未设置或无效则返回指定的默认值。
     *
     * @param defaultMode 默认模式（当用户未设置时使用）
     * @return 用户选择的模式或默认模式
     */
    private RenderiumMode getUserPreferenceOrDefault(RenderiumMode defaultMode) {
        // TODO: 从配置系统读取用户偏好
        // 目前暂时返回默认值，后续可扩展为从 RenderiumConfig 读取
        String preference = System.getProperty("renderium.mode");
        if (preference != null && !preference.isEmpty()) {
            try {
                RenderiumMode userMode = RenderiumMode.valueOf(preference.toUpperCase());
                LOGGER.info("用户指定模式: " + userMode.getDisplayName());
                return userMode;
            } catch (IllegalArgumentException e) {
                LOGGER.warning("无效的模式偏好值: " + preference + "，使用默认模式");
            }
        }
        LOGGER.info("无用户偏好设置 → 使用默认模式: " + defaultMode.getDisplayName());
        return defaultMode;
    }

    // ==================== 通知接口 ====================

    /**
     * 设置通知发送器（由 Fabric/NeoForge 模块调用）
     *
     * @param sender 通知发送器实现
     */
    public void setNotificationSender(ModeNotificationSender sender) {
        this.notificationSender = sender;
    }

    /**
     * 当世界加载完成时调用（由 Mixin 注入）
     */
    public void onWorldLoaded() {
        if (worldLoaded.compareAndSet(false, true)) {
            LOGGER.info("世界加载完成，准备发送模式通知给玩家");
            scheduleNotification();
        }
    }

    /**
     * 安排延迟通知（约 3 秒延迟）
     */
    private void scheduleNotification() {
        if (notificationSender == null) {
            LOGGER.warning("通知发送器未设置，无法发送模式通知");
            return;
        }

        notificationSender.scheduleOnMainThread(() -> {
            if (notificationSent.compareAndSet(false, true)) {
                // 发送完整的通知信息（包含光影模组检测状态）
                notificationSender.sendNotification(
                    currentMode,
                    sodiumVersion,
                    null,   // shaderModName - 暂时为空，后续可从模组加载器获取
                    false   // shaderModPresent - 暂时为 false
                );
            }
        });
    }

    // ==================== 兼容模式组件初始化 (v6 已废弃) ====================

    /**
     * 初始化兼容模式专用组件
     *
     * <p><b>已废弃 (v6.0)</b>：此方法在 v6 中不再被调用。
     * v6 策略移除了对 Sodium 的深度集成，包括 FBO 拦截器。
     *
     * <p>v5 功能：仅在兼容模式下初始化 FBO 拦截器和后处理链。
     * 狂暴模式不需要这些组件（它有自己的原生管线集成）。
     *
     * @deprecated 自 v6.0 起，Renderium 不再初始化 FBO 拦截器。
     *             此方法保留仅为避免破坏下游代码，但实际不会执行任何操作。
     */
    @Deprecated(since = "6.0")
    private void initializeCompatibilityComponents() {
        LOGGER.warning("initializeCompatibilityComponents() 已废弃 (v6)，不执行任何操作");
        // v6: 不再初始化 FBO 拦截器
    }

    /**
     * 关闭兼容模式组件（释放资源）
     *
     * <p><b>已废弃 (v6.0)</b>：v6 不再使用 FBO 拦截器，此方法为空操作。
     *
     * @deprecated 自 v6.0 起
     */
    @Deprecated(since = "6.0")
    public void shutdownCompatibilityComponents() {
        LOGGER.fine("shutdownCompatibilityComponents() 已废弃 (v6)，不执行任何操作");
        // v6: 无需关闭的资源
    }

    // ==================== 公开查询接口 ====================

    /** 获取当前运行模式 */
    public RenderiumMode getCurrentMode() { return currentMode; }

    /** 是否处于兼容模式 */
    public boolean isCompatibleMode() { return currentMode.isCompatible(); }

    /** 是否处于狂暴模式 */
    public boolean isAggressiveMode() { return currentMode.isAggressive(); }

    /** 是否检测到性能优化模组 */
    public boolean isPerformanceModPresent() { return sodiumPresent; }

    /** 获取性能优化模组版本 */
    public Optional<String> getPerformanceModVersion() { return sodiumVersion; }

    /** 世界是否已加载 */
    public boolean isWorldLoaded() { return worldLoaded.get(); }

    /** 通知是否已发送 */
    public boolean wasNotificationSent() { return notificationSent.get(); }

    /**
     * 获取兼容模式 FBO 拦截器
     *
     * <p><b>已废弃 (v6.0)</b>：v6 不再使用 FBO 拦截器。
     *
     * @return 始终返回 null（v6.0 起）
     * @deprecated 自 v6.0 起，Renderium 不再提供 FBO 拦截功能
     */
    @Deprecated(since = "6.0")
    public Object getFboInterceptor() {
        return null; // v6: 始终返回 null
    }

    /**
     * 兼容模式的 FBO 拦截是否已就绪
     *
     * <p><b>已废弃 (v6.0)</b>：v6 不再使用 FBO 拦截。
     *
     * @return 始终返回 false（v6.0 起）
     * @deprecated 自 v6.0 起
     */
    @Deprecated(since = "6.0")
    public boolean isFBOInterceptionReady() {
        return false; // v6: 始终返回 false
    }

    /**
     * 根据当前模式计算有效渲染距离
     *
     * @param userSetting 用户设置的渲染距离
     * @return 有效渲染距离（受模式限制）
     */
    public int getEffectiveRenderDistance(int userSetting) {
        return switch (currentMode) {
            case COMPATIBILITY -> userSetting; // 兼容模式不限制
            case COMPATIBILITY_LIMITED -> userSetting; // 受限兼容模式不限制（由 Sodium 管理）
            case AGGRESSIVE -> Math.min(userSetting, RenderiumLODManager.MAX_FAR_RANGE_CHUNKS); // 狂暴模式受 LOD 限制
        };
    }

    /**
     * 根据当前模式获取 Mixin 优先级
     *
     * @return Mixin 优先级数值（越小优先级越高）
     */
    public int getMixinPriority() {
        return switch (currentMode) {
            case COMPATIBILITY -> 1000; // 兼容模式中优先级
            case COMPATIBILITY_LIMITED -> 1500; // 受限兼容模式低优先级（让 Sodium 先加载）
            case AGGRESSIVE -> 500;     // 狂暴模式高优先级（需要尽早注入）
        };
    }

    // ==================== 日志记录 (v6) ====================

    /**
     * 记录启动信息日志
     */
    private void logStartupInfo() {
        LOGGER.info("═════════════════════════════════════════════════════════");
        LOGGER.info("  Renderium 三轨制架构 v6.0");
        LOGGER.info("═════════════════════════════════════════════════════════");
        LOGGER.info("  运行模式:     " + currentMode.getDisplayName() + " (" + currentMode.getId() + ")");
        LOGGER.info("  Sodium:      " + (sodiumPresent ? "✓ " + sodiumVersion.orElse("") : "✗ 未检测"));
        LOGGER.info("───────────────────────────────────────────────────────");

        switch (currentMode) {
            case COMPATIBILITY -> logCompatibilityDetails();
            case COMPATIBILITY_LIMITED -> logCompatibilityLimitedDetails();
            case AGGRESSIVE -> logAggressiveDetails();
        }

        LOGGER.info("═════════════════════════════════════════════════════════");
    }

    /**
     * 记录兼容模式详细特性
     */
    private void logCompatibilityDetails() {
        LOGGER.info("  [兼容模式] 完整后处理 + 安全微调:");
        LOGGER.info("    • 完整后处理管线（DLSS/XeSS/FSR 超分辨率 + 帧生成）");
        LOGGER.info("    • 安全的 Blaze3D 非侵入式性能微调");
        LOGGER.info("    • 零 Mixin 侵入 Chunk/PalettedContainer");
        LOGGER.info("    • 原生 Vulkan 管线集成后处理");
    }

    /**
     * 记录受限兼容模式详细特性（v6 新增）
     */
    private void logCompatibilityLimitedDetails() {
        LOGGER.info("  [受限兼容模式] 仅检测与日志（功能受限）:");
        LOGGER.info("    • ⚠️ 检测到 Sodium，已自动降级到此模式");
        LOGGER.info("    • 不拦截/重定向 Sodium 输出");
        LOGGER.info("    • 不提供后处理增强（避免冲突）");
        LOGGER.info("    • 仅提供基础监控和日志记录");
        LOGGER.info("    💡 建议：卸载 Sodium 以启用完整 Renderium 功能");
    }

    /**
     * 记录狂暴模式详细特性
     */
    private void logAggressiveDetails() {
        LOGGER.info("  [狂暴模式] 完整优化 + 夺舍策略:");
        LOGGER.info("    • 完整 Blaze3D 优化（帧图重建与命令缓冲区优化）");
        LOGGER.info("    • Vulkan 调度优化（异步计算、多队列并行）");
        LOGGER.info("    • 劫持 ChunkSection，@Overwrite getBlockState/setBlockState");
        LOGGER.info("    • 替换 PalettedContainer → RenderiumCompactStorage");
        LOGGER.info("    • 底层存储: Panama MemorySegment (SoA, 堆外, 零 GC)");
        LOGGER.info("    • 独立实现面剔除/遮挡剔除/紧凑顶点 (师承 Sodium LGPL-3.0)");
        LOGGER.info("    • 劫持官方 Vulkan 后端 (黑盒劫持 VkDevice/VkQueue)");
    }

    @Override
    public String toString() {
        return String.format(
                "RenderiumDualMode{mode=%s(%s), sodium=%s}",
                currentMode.getDisplayName(),
                currentMode.getId(),
                sodiumPresent ? "yes (" + sodiumVersion.orElse("?") + ")" : "no"
        );
    }
}
