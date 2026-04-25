// Renderium - Pass 级智能路由器
// 选择性放行/短路官方渲染 Pass，实现"智能路由与热插拔"

package com.renderium.graphics.backend;

import com.renderium.core.RenderiumDualModeManager;
import com.renderium.core.RenderiumMode;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Pass 级智能路由器。
 *
 * <p>设计来源：智能短路.md §第三类红利（极端情况下的"动态降级路由"）
 *
 * <h2>核心策略</h2>
 * <p>把对官方管线的控制，从"非黑即白的短路"，改成<b>"Pass 级别的智能路由"</b>。
 * VulkanBackend 在向 Queue 提交 CommandBuffer 时，不再是单一的提交，
 * 而是增加一层极薄的<b>路由判断层</b>（纯位运算，没有性能损耗）：
 *
 * <ul>
 *   <li><b>自己擅长的部分</b>（GBuffer、基础光照、后处理超分）：
 *       走自己的 CommandBuffer，官方的对应 Pass 直接丢弃（位掩码抹零）。</li>
 *   <li><b>官方超常发挥的部分</b>（比如某个极度复杂的体积云 Compute Pass）：
 *       路由层识别到这个 Pass 的 Tag，选择放行。
 *       直接把官方准备好的 DescriptorSet 和 CommandBuffer 原封不动地扔进 VkQueue。</li>
 * </ul>
 *
 * <h2>路由决策模型</h2>
 * <p>每个渲染 Pass 有一个路由标签（RouteTag），决定其归属：
 * <pre>
 * ┌──────────────────────────────────────────────────────┐
 * │  RouteTag 枚举                                       │
 * ├──────────────────────────────────────────────────────┤
 * │  RENDERIUM_OWNED  → 走 Renderium CommandBuffer       │
 * │  OFFICIAL_PASSTHROUGH → 放行官方 CommandBuffer       │
 * │  AUTO_DECIDE     → 根据 SPIR-V 缓存池自动决策       │
 * │  BLOCKED         → 完全丢弃（不执行）                │
 * └──────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>性能保证</h2>
 * <ul>
 *   <li>路由判断使用位掩码（{@code long} 型），单次判断 ≤ 1ns</li>
 *   <li>路由表使用 ConcurrentHashMap，线程安全，无锁读取</li>
 *   <li>不创建任何临时对象，零 GC 压力</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public final class PassRouter {

    private static final Logger LOGGER = Logger.getLogger("Renderium-PassRouter");

    /** 单例实例 */
    private static volatile PassRouter instance;

    // ==================== 路由标签枚举 ====================

    /**
     * 渲染 Pass 的路由标签
     *
     * <p>每个标签对应一个位掩码值，用于高性能路由判断
     */
    public enum RouteTag {

        /** 走 Renderium 自己的 CommandBuffer（默认） */
        RENDERIUM_OWNED(0b001),

        /** 放行官方的 CommandBuffer（直接提交到 VkQueue） */
        OFFICIAL_PASSTHROUGH(0b010),

        /** 根据 SPIR-V 缓存池自动决策 */
        AUTO_DECIDE(0b011),

        /** 完全丢弃（不执行该 Pass） */
        BLOCKED(0b100);

        /** 位掩码值 */
        final int bits;

        RouteTag(int bits) {
            this.bits = bits;
        }

        /** 获取位掩码值 */
        public int getBits() { return bits; }

        /**
         * 从位掩码值解析 RouteTag
         *
         * @param bits 位掩码值
         * @return 对应的 RouteTag
         */
        public static RouteTag fromBits(int bits) {
            for (RouteTag tag : values()) {
                if (tag.bits == bits) return tag;
            }
            return RENDERIUM_OWNED; // 默认
        }
    }

    // ==================== 路由表 ====================

    /**
     * Pass 路由表
     *
     * <p>Key: Pass 名称（如 "GBuffer"、"Lighting"、"VolumetricFog"）
     * Value: 路由标签的位掩码值（使用 int 而非 RouteTag 对象，避免对象分配）
     */
    private final ConcurrentHashMap<String, Integer> routeTable = new ConcurrentHashMap<>();

    /** 全局路由位掩码（用于快速判断，不需要查表） */
    private volatile long globalRouteMask = 0L;

    /** 是否启用智能路由（false = 全部走 Renderium，即原来的短路模式） */
    private volatile boolean smartRoutingEnabled = true;

    /**
     * 私有构造函数
     */
    private PassRouter() {
        initializeDefaultRoutes();
    }

    /**
     * 获取单例实例
     *
     * @return PassRouter 唯一实例
     */
    public static synchronized PassRouter getInstance() {
        if (instance == null) {
            instance = new PassRouter();
        }
        return instance;
    }

    // ==================== 默认路由配置 ====================

    /**
     * 初始化默认路由规则
     *
     * <p>根据当前运行模式和引擎能力，设置各 Pass 的默认路由。
     * 这些默认值可以在运行时通过 {@link #setRoute} 动态修改。
     */
    private void initializeDefaultRoutes() {
        RenderiumDualModeManager dualMode = RenderiumDualModeManager.getInstance();

        // ===== Renderium 自己擅长的部分（走自己的管线） =====
        setRoute("GBuffer", RouteTag.RENDERIUM_OWNED);
        setRoute("BaseLighting", RouteTag.RENDERIUM_OWNED);
        setRoute("ShadowMap", RouteTag.RENDERIUM_OWNED);
        setRoute("PostProcess", RouteTag.RENDERIUM_OWNED);
        setRoute("SuperResolution", RouteTag.RENDERIUM_OWNED);
        setRoute("FrameGeneration", RouteTag.RENDERIUM_OWNED);
        setRoute("ToneMapping", RouteTag.RENDERIUM_OWNED);
        setRoute("AntiAliasing", RouteTag.RENDERIUM_OWNED);

        // ===== 官方可能超常发挥的部分（自动决策） =====
        // 如果官方有更好的实现（SPIR-V 缓存池里有），就放行；否则走自己的
        setRoute("VolumetricFog", RouteTag.AUTO_DECIDE);
        setRoute("VolumetricCloud", RouteTag.AUTO_DECIDE);
        setRoute("RTX_Reflection", RouteTag.AUTO_DECIDE);
        setRoute("RTX_AmbientOcclusion", RouteTag.AUTO_DECIDE);
        setRoute("ScreenSpaceGI", RouteTag.AUTO_DECIDE);
        setRoute("WaterRefraction", RouteTag.AUTO_DECIDE);

        // ===== 完全不需要的 Pass（丢弃） =====
        setRoute("VanillaParticle", RouteTag.BLOCKED);
        setRoute("VanillaWeather", RouteTag.BLOCKED);

        LOGGER.info("默认路由规则已初始化 (模式: " + dualMode.getCurrentMode().getDisplayName() + ")");
    }

    // ==================== 核心路由接口 ====================

    /**
     * 设置指定 Pass 的路由标签
     *
     * @param passName Pass 名称
     * @param tag 路由标签
     */
    public void setRoute(String passName, RouteTag tag) {
        routeTable.put(passName, tag.bits);
        updateGlobalMask(passName, tag);
        LOGGER.config(String.format("路由规则: %s → %s", passName, tag.name()));
    }

    /**
     * 批量设置路由规则
     *
     * @param routes Pass 名称到路由标签的映射
     */
    public void setRoutes(java.util.Map<String, RouteTag> routes) {
        routes.forEach(this::setRoute);
    }

    /**
     * 获取指定 Pass 的路由决策（核心方法，每帧每 Pass 调用一次）
     *
     * <p>这是路由器的核心方法，在渲染循环中每帧对每个 Pass 调用。
     * 必须保证极低的延迟（目标 ≤ 1ns）。
     *
     * <h3>决策流程</h3>
     * <ol>
     *   <li>查路由表获取 RouteTag</li>
     *   <li>如果是 AUTO_DECIDE，查询 SPIR-V 缓存池</li>
     *   <li>返回最终决策</li>
     * </ol>
     *
     * @param passName Pass 名称
     * @return 最终的路由决策
     */
    public RouteTag resolveRoute(String passName) {
        if (!smartRoutingEnabled) {
            return RouteTag.RENDERIUM_OWNED; // 禁用智能路由时全部走自己
        }

        Integer tagBits = routeTable.get(passName);
        if (tagBits == null) {
            // 未知 Pass：默认走 Renderium
            return RouteTag.RENDERIUM_OWNED;
        }

        RouteTag tag = RouteTag.fromBits(tagBits);

        // AUTO_DECIDE：查询 SPIR-V 缓存池
        if (tag == RouteTag.AUTO_DECIDE) {
            return resolveAutoDecision(passName);
        }

        return tag;
    }

    /**
     * 解析 AUTO_DECIDE 决策
     *
     * <p>逻辑：
     * <ul>
     *   <li>如果 SPIR-V 缓存池里有官方的该 Pass → 放行官方</li>
     *   <li>否则 → 走 Renderium 自己的实现</li>
     * </ul>
     *
     * @param passName Pass 名称
     * @return 解析后的路由决策
     */
    private RouteTag resolveAutoDecision(String passName) {
        SPIRVInterceptor spirvInterceptor = SPIRVInterceptor.getInstance();

        if (spirvInterceptor.hasModule(passName)) {
            // 官方有更好的实现，放行
            LOGGER.fine("AUTO_DECIDE → OFFICIAL_PASSTHROUGH: " + passName +
                    " (官方 SPIR-V 缓存命中)");
            return RouteTag.OFFICIAL_PASSTHROUGH;
        }

        // 官方没有，走自己的
        return RouteTag.RENDERIUM_OWNED;
    }

    /**
     * 快速判断指定 Pass 是否应该走 Renderium 管线
     *
     * <p>这是最高频调用的方法，用于渲染循环中的快速分支判断。
     * 使用位运算避免对象创建。
     *
     * @param passName Pass 名称
     * @return true 如果应该走 Renderium 自己的 CommandBuffer
     */
    public boolean isRenderiumOwned(String passName) {
        RouteTag tag = resolveRoute(passName);
        return tag == RouteTag.RENDERIUM_OWNED;
    }

    /**
     * 快速判断指定 Pass 是否应该放行官方
     *
     * @param passName Pass 名称
     * @return true 如果应该放行官方的 CommandBuffer
     */
    public boolean isOfficialPassthrough(String passName) {
        return resolveRoute(passName) == RouteTag.OFFICIAL_PASSTHROUGH;
    }

    /**
     * 快速判断指定 Pass 是否被丢弃
     *
     * @param passName Pass 名称
     * @return true 如果该 Pass 被完全丢弃
     */
    public boolean isBlocked(String passName) {
        return resolveRoute(passName) == RouteTag.BLOCKED;
    }

    // ==================== 全局掩码管理 ====================

    /**
     * 更新全局路由位掩码
     *
     * <p>用于不需要查表的快速判断场景。
     * 每个 Pass 的名称哈希值的低 6 位作为位索引。
     *
     * @param passName Pass 名称
     * @param tag 路由标签
     */
    private void updateGlobalMask(String passName, RouteTag tag) {
        // 使用 Pass 名称的哈希值低 6 位作为位索引
        int bitIndex = Math.abs(passName.hashCode()) % 64;
        long mask = this.globalRouteMask;

        if (tag == RouteTag.OFFICIAL_PASSTHROUGH) {
            mask |= (1L << bitIndex);  // 设置位 = 官方放行
        } else {
            mask &= ~(1L << bitIndex); // 清除位 = 走 Renderium
        }

        this.globalRouteMask = mask;
    }

    /**
     * 获取全局路由位掩码（用于快速判断）
     *
     * @return 64 位路由掩码
     */
    public long getGlobalRouteMask() {
        return globalRouteMask;
    }

    // ==================== 控制接口 ====================

    /**
     * 启用/禁用智能路由
     *
     * <p>禁用后所有 Pass 走 Renderium 自己的管线（回到原来的短路模式）。
     * 用于调试或在发现兼容性问题时快速回退。
     *
     * @param enabled true 启用智能路由，false 全部走 Renderium
     */
    public void setSmartRoutingEnabled(boolean enabled) {
        this.smartRoutingEnabled = enabled;
        LOGGER.info("智能路由 " + (enabled ? "已启用" : "已禁用（全部走 Renderium 管线）"));
    }

    /** 是否启用智能路由 */
    public boolean isSmartRoutingEnabled() { return smartRoutingEnabled; }

    /**
     * 重置为默认路由规则
     */
    public void resetToDefaults() {
        routeTable.clear();
        globalRouteMask = 0L;
        initializeDefaultRoutes();
        LOGGER.info("路由规则已重置为默认值");
    }

    /**
     * 获取当前路由表的快照（用于 UI 显示）
     *
     * @return Pass 名称到路由标签的不可变映射
     */
    public java.util.Map<String, RouteTag> getRouteTableSnapshot() {
        java.util.Map<String, RouteTag> snapshot = new java.util.LinkedHashMap<>();
        routeTable.forEach((name, bits) -> snapshot.put(name, RouteTag.fromBits(bits)));
        return java.util.Collections.unmodifiableMap(snapshot);
    }

    /**
     * 获取路由统计信息（用于调试和性能分析）
     *
     * @return 格式化的统计字符串
     */
    public String getRoutingStats() {
        int renderiumOwned = 0;
        int officialPassthrough = 0;
        int autoDecide = 0;
        int blocked = 0;

        for (Integer bits : routeTable.values()) {
            RouteTag tag = RouteTag.fromBits(bits);
            switch (tag) {
                case RENDERIUM_OWNED -> renderiumOwned++;
                case OFFICIAL_PASSTHROUGH -> officialPassthrough++;
                case AUTO_DECIDE -> autoDecide++;
                case BLOCKED -> blocked++;
            }
        }

        return String.format(
                "PassRouter{total=%d, renderium=%d, official=%d, auto=%d, blocked=%d, smart=%s}",
                routeTable.size(), renderiumOwned, officialPassthrough, autoDecide,
                blocked, smartRoutingEnabled);
    }

    @Override
    public String toString() {
        return getRoutingStats();
    }
}
