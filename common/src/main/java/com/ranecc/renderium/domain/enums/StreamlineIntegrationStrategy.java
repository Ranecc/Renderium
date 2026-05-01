// Renderium - Blaze3D 优化器插件系统
// Streamline 集成策略枚举

package com.ranecc.renderium.domain.enums;

/**
 * Streamline 集成策略
 * <p>
 * 定义 Streamline SDK 与 Minecraft 渲染管线的集成方式。
 * 不同策略对应不同的集成深度和复杂度。
 *
 * <h2>策略说明：</h2>
 * <ul>
 *   <li>{@link #FLIP_FRAME} - 简单但时序不正确（不推荐）</li>
 *   <li>{@link #POST_CHAIN} - 在后处理链中注入</li>
 *   <li>{@link #FRAME_GRAPH} - 在帧图中添加 Pass（推荐）</li>
 *   <li>{@link #SWAP_CHAIN} - 完全独立的交换链（专业级）</li>
 * </ul>
 *
 * @see StreamlineIntegrationPoint
 * @author Renderium Team
 * @since 1.0.0
 */
public enum StreamlineIntegrationStrategy {

    /**
     * flipFrame 拦截策略（不推荐）
     * <p>在 RenderSystem.flipFrame() 时拦截并应用 Streamline。
     *
     * <h3>问题：</h3>
     * <ul>
     *   <li>时序太晚，可能错过异步后处理</li>
     *   <li>深度缓冲可能已被清除</li>
     *   <li>没有运动向量缓冲</li>
     * </ul>
     *
     * <h3>适用场景：</h3>
     * 仅用于快速原型验证，不应在生产环境使用。
     */
    FLIP_FRAME("flipFrame 拦截", 3, false, false),

    /**
     * PostChain 注入策略
     * <p>在 Mojang PostChain.addToFrame() 尾部注入 Streamline Pass。
     *
     * <h3>优点：</h3>
     * <ul>
     *   <li>在 FrameGraph 内，时序较正确</li>
     *   <li>可以访问 FrameGraph 资源</li>
     * </ul>
     *
     * <h3>缺点：</h3>
     * <ul>
     *   <li>仍需处理深度/运动向量</li>
     *   <li>需要修改 PostChain</li>
     * </ul>
     */
    POST_CHAIN("PostChain 注入", 6, true, false),

    /**
     * FrameGraph 级别集成（推荐）
     * <p>直接在 LevelRenderer.renderLevel() 中修改 FrameGraph，
     * 添加专用的 Streamline Pass。
     *
     * <h3>优点：</h3>
     * <ul>
     *   <li>完全控制 FrameGraph 结构</li>
     *   <li>可以创建运动向量资源</li>
     *   <li>时序正确，与渲染协调</li>
     * </ul>
     *
     * <h3>适用场景：</h3>
     * 基础超分辨率（DLSS/FSR/XeSS），不需要 Frame Generation。
     */
    FRAME_GRAPH("FrameGraph 集成", 8, true, true),

    /**
     * 独立交换链策略（专业级）
     * <p>完全接管显示输出，使用 SL.createSwapChain() 创建独立交换链。
     *
     * <h3>优点：</h3>
     * <ul>
     *   <li>完全控制渲染时序</li>
     *   <li>支持完整 Frame Generation</li>
     *   <li>最佳性能和灵活性</li>
     * </ul>
     *
     * <h3>缺点：</h3>
     * <ul>
     *   <li>实现最复杂</li>
     *   <li>需要深度修改渲染流程</li>
     *   <li>与 Iris/Sodium 兼容性挑战</li>
     * </ul>
     *
     * <h3>适用场景：</h3>
     * 需要 Frame Generation 的生产环境。
     */
    SWAP_CHAIN("独立交换链", 9, true, true);

    // ==================== 字段定义 ====================

    /** 策略显示名称 */
    private final String displayName;

    /** 推荐评分 (1-10) */
    private final int rating;

    /** 是否支持基础超分辨率 */
    private final boolean supportsSuperResolution;

    /** 是否支持 Frame Generation */
    private final boolean supportsFrameGeneration;

    // ==================== 构造函数 ====================

    StreamlineIntegrationStrategy(String displayName, int rating,
                                   boolean supportsSuperResolution,
                                   boolean supportsFrameGeneration) {
        this.displayName = displayName;
        this.rating = rating;
        this.supportsSuperResolution = supportsSuperResolution;
        this.supportsFrameGeneration = supportsFrameGeneration;
    }

    // ==================== 查询方法 ====================

    /**
     * 获取显示名称
     *
     * @return 中文描述名称
     */
    public String getDisplayName() { return displayName; }

    /**
     * 获取推荐评分
     *
     * @return 评分值 (1-10)
     */
    public int getRating() { return rating; }

    /**
     * 是否支持超分辨率功能
     *
     * @return true 如果支持 DLSS/FSR/XeSS
     */
    public boolean isSuperResolutionSupported() { return supportsSuperResolution; }

    /**
     * 是否支持帧生成功能
     *
     * @return true 如果支持 DLSS-FG/FSR-FG
     */
    public boolean isFrameGenerationSupported() { return supportsFrameGeneration; }

    /**
     * 获取此策略的警告信息
     *
     * @return 警告文本，无警告返回 null
     */
    public String getWarningMessage() {
        return switch (this) {
            case FLIP_FRAME -> "⚠️ 此策略时序不正确，仅用于测试";
            case POST_CHAIN -> "⚠️ 可能与某些模组冲突";
            case FRAME_GRAPH -> null; // 推荐方案无需警告
            case SWAP_CHAIN -> "🔥 复杂度高，需充分测试兼容性";
        };
    }
}
