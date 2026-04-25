// Renderium - Pass 级智能路由器
// 用于在官方渲染 Pass 和自定义渲染 Pass 之间进行智能路由
// 来源文档: pass-routing-strategy.md
// 策略ID: ROUTE1 (Pass Routing Strategy #1)
// 预期收益: 灵活接管渲染流程，实现 DLSS/Reflex/FrameGen 等功能

package com.renderium.router;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Logger;

/**
 * Pass 级智能路由器 🔀
 *
 * <p>在官方渲染 Pass 和自定义渲染 Pass 之间进行智能路由，
 * 实现灵活接管渲染流程，为 DLSS/Reflex/FrameGen 等功能提供基础。
 *
 * <h2>路由原理：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    官方渲染流程                              │
 * │  FrameGraph → 按依赖关系排序 → 执行每个 Pass                │
 * │  → TerrainPass, EntityPass, PostProcessPass, ...           │
 * └─────────────────────────────────────────────────────────────┘
 *                              ↓
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    Renderium 路由流程                        │
 * │  PassRouter.intercept(passName)                            │
 * │  → 查询路由表：是否应该接管该 Pass？                        │
 * │  → 是：执行自定义 PassHandler                               │
 * │  → 否：放行给官方 Pass                                     │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>路由策略：</h3>
 * <ul>
 *   <li><b>REPLACE</b>: 完全替代官方 Pass，执行自定义实现</li>
 *   <li><b>ENHANCE</b>: 在官方 Pass 前后执行额外操作</li>
 *   <li><b>PASSTHROUGH</b>: 完全放行给官方 Pass</li>
 * </ul>
 *
 * <h3>核心路由点：</h3>
 * <ul>
 *   <li><b>后处理 Pass</b>: 替换为 DLSS/Reflex 实现</li>
 *   <li><b>帧生成 Pass</b>: 插入 FrameGen 逻辑</li>
 *   <li><b>地形渲染 Pass</b>: 通常放行给官方实现</li>
 *   <li><b>实体渲染 Pass</b>: 通常放行给官方实现</li>
 * </ul>
 *
 * <h3>参考文档：</h3>
 * <ul>
 *   <li>pass-routing-strategy.md §1.0（Pass 路由策略）</li>
 *   <li>dlss-integration-guide.md §2.1（DLSS 与 Pass 路由）</li>
 *   <li>framegen-implementation.md §3.0（帧生成实现指南）</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 3.0.0
 * @see PassHandler
 * @see SPIRVInterceptor
 * @see OfficialVulkanHijacker
 */
public class PassRouter {

    private static final Logger LOGGER = Logger.getLogger(PassRouter.class.getName());

    // ==================== 路由表 ====================

    /** Pass 路由表（Pass 名称 → 处理器） */
    private final Map<String, PassHandler> handlerMap = new ConcurrentHashMap<>();

    /** 直通 Pass 列表（不拦截的 Pass） */
    private final Set<String> passthroughPasses = new CopyOnWriteArraySet<>();

    /** 路由策略表（Pass 名称 → 路由策略） */
    private final Map<String, RoutingStrategy> strategyMap = new ConcurrentHashMap<>();

    // ==================== 单例模式 ====================

    /** 单例实例（volatile 保证可见性） */
    private static volatile PassRouter instance;

    /**
     * 私有构造函数（防止外部实例化）
     */
    private PassRouter() {
        // 默认直通 Pass（官方擅长的 Pass）
        addPassthroughPass("terrain_solid");
        addPassthroughPass("terrain_cutout");
        addPassthroughPass("entity_solid");
        addPassthroughPass("entity_cutout");
        addPassthroughPass("particles");
        addPassthroughPass("block_outline");
        addPassthroughPass("debug_lines");
        addPassthroughPass("gui_overlay");
        
        // 默认替换 Pass（我们擅长的 Pass）
        addReplacementPass("post_process", PostProcessHandler.getInstance());
        addReplacementPass("shadow_pass", ShadowPassHandler.getInstance());
        
        LOGGER.info("Pass 路由器已初始化，默认直通 " + passthroughPasses.size() + " 个 Pass");
    }

    /**
     * 获取单例实例
     *
     * @return PassRouter 实例
     */
    public static PassRouter getInstance() {
        if (instance == null) {
            synchronized (PassRouter.class) {
                if (instance == null) {
                    instance = new PassRouter();
                }
            }
        }
        return instance;
    }

    // ==================== 路由注册 ====================

    /**
     * 添加直通 Pass（不拦截，完全放行给官方）
     *
     * @param passName Pass 名称
     */
    public void addPassthroughPass(String passName) {
        passthroughPasses.add(passName);
        strategyMap.put(passName, RoutingStrategy.PASSTHROUGH);
        LOGGER.fine("添加直通 Pass: " + passName);
    }

    /**
     * 添加替换 Pass（完全替代官方实现）
     *
     * @param passName Pass 名称
     * @param handler Pass 处理器
     */
    public void addReplacementPass(String passName, PassHandler handler) {
        handlerMap.put(passName, handler);
        strategyMap.put(passName, RoutingStrategy.REPLACE);
        LOGGER.fine("添加替换 Pass: " + passName);
    }

    /**
     * 添加增强 Pass（在官方 Pass 前后执行额外操作）
     *
     * @param passName Pass 名称
     * @param handler Pass 处理器
     */
    public void addEnhancedPass(String passName, PassHandler handler) {
        handlerMap.put(passName, handler);
        strategyMap.put(passName, RoutingStrategy.ENHANCE);
        LOGGER.fine("添加增强 Pass: " + passName);
    }

    /**
     * 移除 Pass 路由
     *
     * @param passName Pass 名称
     */
    public void removePass(String passName) {
        handlerMap.remove(passName);
        passthroughPasses.remove(passName);
        strategyMap.remove(passName);
        LOGGER.fine("移除 Pass 路由: " + passName);
    }

    // ==================== 路由拦截 ====================

    /**
     * 拦截 Pass 执行
     *
     * @param passName Pass 名称
     * @param commandBuffer Vulkan 命令缓冲区句柄
     * @return true 如果已接管，false 如果放行
     */
    public boolean intercept(String passName, long commandBuffer) {
        RoutingStrategy strategy = strategyMap.getOrDefault(passName, RoutingStrategy.PASSTHROUGH);
        
        switch (strategy) {
            case PASSTHROUGH:
                // 放行给官方
                return false;
                
            case REPLACE:
                // 完全替代官方
                PassHandler replaceHandler = handlerMap.get(passName);
                if (replaceHandler != null) {
                    replaceHandler.handle(commandBuffer);
                    return true;
                }
                return false;
                
            case ENHANCE:
                // 增强官方 Pass
                PassHandler enhanceHandler = handlerMap.get(passName);
                if (enhanceHandler != null) {
                    enhanceHandler.onBeforePass(commandBuffer);
                    // 官方 Pass 执行（由调用方处理）
                    // ...
                    enhanceHandler.onAfterPass(commandBuffer);
                    return true;
                }
                return false;
                
            default:
                return false;
        }
    }

    // ==================== 查询接口 ====================

    /**
     * 获取 Pass 的路由策略
     *
     * @param passName Pass 名称
     * @return 路由策略
     */
    public RoutingStrategy getStrategy(String passName) {
        return strategyMap.getOrDefault(passName, RoutingStrategy.PASSTHROUGH);
    }

    /**
     * 检查 Pass 是否被接管
     *
     * @param passName Pass 名称
     * @return true 如果被接管
     */
    public boolean isIntercepted(String passName) {
        return !passthroughPasses.contains(passName);
    }

    /**
     * 获取所有直通的 Pass
     *
     * @return 直通 Pass 集合
     */
    public Set<String> getPassthroughPasses() {
        return passthroughPasses;
    }

    /**
     * 获取所有接管的 Pass
     *
     * @return 接管 Pass 集合
     */
    public Set<String> getInterceptedPasses() {
        return handlerMap.keySet();
    }

    /**
     * 获取路由统计信息
     *
     * @return 路由统计信息字符串
     */
    public String getRoutingStats() {
        return String.format("Pass Router Stats: %d intercepted, %d passthrough, %d total",
                           handlerMap.size(), passthroughPasses.size(),
                           handlerMap.size() + passthroughPasses.size());
    }

    // ==================== 生命周期管理 ====================

    /**
     * 关闭路由器
     */
    public void shutdown() {
        handlerMap.clear();
        passthroughPasses.clear();
        strategyMap.clear();
        LOGGER.info("Pass 路由器已关闭");
    }

    // ==================== 内部枚举 ====================

    /**
     * 路由策略枚举
     */
    public enum RoutingStrategy {
        /** 完全替代官方 Pass */
        REPLACE,
        /** 在官方 Pass 前后执行额外操作 */
        ENHANCE,
        /** 完全放行给官方 Pass */
        PASSTHROUGH
    }

    // ==================== 默认处理器 ====================

    /**
     * 后处理 Pass 处理器（DLSS 集成点）
     */
    private static class PostProcessHandler implements PassHandler {
        private static volatile PostProcessHandler instance;
        
        public static PostProcessHandler getInstance() {
            if (instance == null) {
                synchronized (PostProcessHandler.class) {
                    if (instance == null) {
                        instance = new PostProcessHandler();
                    }
                }
            }
            return instance;
        }
        
        @Override
        public void handle(long commandBuffer) {
            // TODO (Phase 2): 实现 DLSS 后处理逻辑
            // DLSSManager.getInstance().evaluate(commandBuffer, ...);
        }
    }

    /**
     * 阴影 Pass 处理器（可选优化）
     */
    private static class ShadowPassHandler implements PassHandler {
        private static volatile ShadowPassHandler instance;
        
        public static ShadowPassHandler getInstance() {
            if (instance == null) {
                synchronized (ShadowPassHandler.class) {
                    if (instance == null) {
                        instance = new ShadowPassHandler();
                    }
                }
            }
            return instance;
        }
        
        @Override
        public void handle(long commandBuffer) {
            // TODO (Phase 3): 实现优化后的阴影渲染
        }
    }
}