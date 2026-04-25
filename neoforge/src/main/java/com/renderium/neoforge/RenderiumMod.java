// Renderium - NeoForge Module Main Class
// Entry point and logging configuration for NeoForge

package com.renderium.neoforge;

import com.renderium.core.RenderiumCore;
import com.renderium.core.RenderiumDualModeManager;
import com.renderium.dlss.DLSSManager;
import com.renderium.culling.CullingController;
import com.renderium.platform.PlatformHelper;
import com.renderium.neoforge.platform.NeoForgeNotificationSender;
import com.renderium.neoforge.platform.NeoForgePlatformHelper;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Renderium NeoForge 模块主类
 * 使用 @Mod 注解注册模组
 */
@Mod(value = "renderium", dist = Dist.CLIENT)
public class RenderiumMod {

    /**
     * Log4j2 日志记录器
     */
    public static final Logger LOGGER = LogManager.getLogger("Renderium");

    /**
     * 模组事件总线
     */
    private final IEventBus modEventBus;

    /**
     * 构造函数
     * 在模组实例化时调用
     *
     * @param modEventBus NeoForge 事件总线
     */
    public RenderiumMod(IEventBus modEventBus) {
        this.modEventBus = modEventBus;

        // 初始化平台帮助器（必须在其他组件之前）
        PlatformHelper.initialize(new NeoForgePlatformHelper());

        // ======== 双重客户端验证 ========
        // 虽然 @Mod(dist = Dist.CLIENT) 已保证此方法仅在客户端调用，
        // 但作为防御性编程，再次检查 PlatformHelper
        if (!PlatformHelper.getInstance().isClientEnvironment()) {
            LOGGER.error("CRITICAL: NeoForge Mod 在非客户端环境被加载！这不应该发生。");
            return;
        }

        LOGGER.info("===========================================");
        LOGGER.info("  Renderium - Modern Minecraft Renderer");
        LOGGER.info("  Version: {}", getVersion());
        LOGGER.info("  Platform: NeoForge (Client Verified)");
        LOGGER.info("===========================================");

        // 注册事件监听器
        registerEventListeners();

        // 初始化核心组件
        initializeComponents();

        // 注册模式通知发送器
        registerNotificationSender();

        LOGGER.info("Renderium initialized successfully");
    }

    /**
     * 注册事件监听器
     * 使用 @SubscribeEvent 注解的方法会在相应事件触发时被调用
     */
    private void registerEventListeners() {
        LOGGER.debug("Registering event listeners...");

        // 注册客户端设置事件
        modEventBus.addListener(this::onClientSetup);

        // 注册客户端 Tick 事件到游戏事件总线
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(this::onClientTickStart);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(this::onClientTickEnd);

        LOGGER.debug("Event listeners registered");
    }

    /**
     * 初始化核心组件
     */
    private void initializeComponents() {
        LOGGER.debug("Initializing core components...");

        // 初始化 DLSS 管理器
        DLSSManager dlssManager = DLSSManager.getInstance();
        LOGGER.debug("DLSS Manager initialized, available: {}", dlssManager.isAvailable());

        // 初始化剔除控制器
        CullingController cullingController = CullingController.getInstance();
        LOGGER.debug("Culling Controller initialized");

        LOGGER.debug("All core components initialized");
    }

    /**
     * 注册模式通知发送器
     */
    private void registerNotificationSender() {
        RenderiumDualModeManager dualMode = RenderiumDualModeManager.getInstance();
        dualMode.setNotificationSender(new NeoForgeNotificationSender());
        LOGGER.debug("Notification sender registered");
    }

    /**
     * 获取模组版本
     */
    private String getVersion() {
        return "1.0.0-SNAPSHOT";
    }

    /**
     * 客户端设置事件
     * 在客户端初始化时调用
     *
     * @param event 客户端设置事件
     */
    public void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("Client setup event received");
        // 客户端特定初始化代码可以放在这里
    }

    /**
     * 客户端 Tick 事件 - 开始
     *
     * @param event 客户端 Tick 事件
     */
    public void onClientTickStart(ClientTickEvent.Pre event) {
        // Tick 开始时的逻辑
    }

    /**
     * 客户端 Tick 事件 - 结束
     * 每帧结束时调用
     *
     * @param event 客户端 Tick 事件
     */
    public void onClientTickEnd(ClientTickEvent.Post event) {
        // 每帧结束时的逻辑
        // 例如：更新 DLSS 状态、同步数据等
    }
}
