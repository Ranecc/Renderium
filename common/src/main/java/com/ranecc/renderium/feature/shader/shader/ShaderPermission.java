// Renderium - Vulkan 光影三级权限系统
// 蓝标(沙盒) / 黄牌(注入) / 红牌(夺舍)

package com.ranecc.renderium.feature.shader.shader;

/**
 * Renderium Vulkan 光影三级权限枚举。
 *
 * <p>设计来源：总体概念设计.md §七（王炸生态：Vulkan 光影规范）
 * 和 总体概念设计2.md §五（光影规范：三级权限与警告机制）。
 *
 * <h2>核心哲学</h2>
 * <p><b>开放核心与责任分离</b>：
 * Renderium 提供一个安全的沙盒环境让光影作者发挥创意，
 * 同时通过权限等级明确界定"引擎控制权"的边界。
 * 权限越高，用户收到的警告越明显，责任越清晰。
 *
 * <h2>三级权限详解</h2>
 * <ol>
 *   <li><b>Level 0 - 蓝标 (SANDBOX)</b>：沙盒模式，完全安全。
 *       只能用引擎内置 Kernel 拼装参数。引擎保持完全控制权。</li>
 *   <li><b>Level 1 - 黄牌 (INJECTION)</b>：注入模式，有限风险。
 *       光影作者可提交自定义 SPIR-V 算法，引擎让出特定节点控制权。</li>
 *   <li><b>Level 2 - 红牌 (TAKEOVER)</b>：夺舍模式，完全接管。
 *       光影包覆盖整个渲染图，引擎短路自身渲染循环，
 *       将原始 Vulkan 句柄交给光影包 Lua 入口。</li>
 * </ol>
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public enum ShaderPermission {

    /**
     * Level 0 - 蓝标/沙盒模式 (SANDBOX)
     *
     * <p>最安全的权限等级。光影作者只能：
     * <ul>
     *   <li>使用 YAML 声明式地调整引擎内置参数旋钮</li>
     *   <li>选择引擎预置的渲染 Pass 和算法</li>
     *   <li>组合内置的后处理效果链</li>
     * </ul>
     *
     * <h3>能力范围</h3>
     * <ul>
     *   <li>✓ 调整曝光、对比度、饱和度等颜色参数</li>
     *   <li>✓ 启用/禁用 Bloom、Motion Blur、DOF 等后处理</li>
     *   <li>✓ 选择抗锯齿算法（MSAA/FXAA/TAA）</li>
     *   <li>✓ 调整阴影分辨率和距离</li>
     *   <li>✗ 不能提交自定义 Shader 代码</li>
     *   <li>✗ 不能访问底层 Vulkan API</li>
     *   <li>✗ 不能修改渲染管线拓扑</li>
     * </ul>
     *
     * <h3>用户体验</h3>
     * <p>无任何警告提示。全速运行，所有引擎优化生效。
     */
    SANDBOX(0, "蓝标", "§9■ 沙盒模式", "完全使用引擎内置参数，零风险"),

    /**
     * Level 1 - 黄牌/注入模式 (INJECTION)
     *
     * <p>中等风险权限等级。光影作者可以：
     * <ul>
     *   <li>包含 Level 0 的所有能力</li>
     *   <li>提交自己编译的 .spv (SPIR-V) 文件</li>
     *   <li>要求引擎在特定渲染节点注入自定义 Shader</li>
     * </ul>
     *
     * <h3>能力范围</h3>
     * <ul>
     *   <li>✓ 所有 SANDBOX 能力</li>
     *   <li>✓ 在几何 Pass 注入自定义顶点/片段 Shader</li>
     *   <li>✓ 在光照 Pass 注入自定义光照算法</li>
     *   <li>✓ 在后期 Pass 注入自定义后处理效果</li>
     *   <li>✗ 不能修改渲染图的拓扑结构</li>
     *   <li>✗ 不能访问 CommandBuffer 直接填充</li>
     *   <li>✗ 不能获取原始 VkDevice/VkQueue 句柄</li>
     * </ul>
     *
     * <h3>用户体验</h3>
     * <p>显示黄色警告："此光影包含自定义管线，部分引擎优化已禁用"。
     * 引擎让出被注入节点的描述符集控制权。
     */
    INJECTION(1, "黄牌", "§e⚠ 注入模式", "包含自定义 SPIR-V，部分优化已禁用"),

    /**
     * Level 2 - 红牌/夺舍模式 (TAKEOVER)
     *
     * <p>最高风险权限等级。光影作者获得几乎完全的控制权：
     * <ul>
     *   <li>包含 Level 0 和 Level 1 的所有能力</li>
     *   <li>通过 Lua 脚本声明覆盖整个渲染图</li>
     *   <li>引擎将纯 Vulkan 句柄和堆外内存指针打包交给光影</li>
     *   <li>光影包完全接管 CommandBuffer 的填充逻辑</li>
     * </ul>
     *
     * <h3>能力范围</h3>
     * <ul>
     *   <li>✓ 所有 INJECTION 能力</li>
     *   <li>✓ 自定义完整的渲染图拓扑（Pass 数量和连接关系）</li>
     *   <li>✓ 通过 Lua 编排复杂的运行时逻辑</li>
     *   <li>✓ 访问 VkDevice、VkQueue、CommandBuffer 等底层句柄</li>
     *   <li>✓ 直接管理 DescriptorSet、PipelineLayout 等 Vulkan 对象</li>
     * </ul>
     *
     * <h3>用户体验</h3>
     * <p>显示红色严重警告："底层已被光影夺舍，Renderium 所有优化失效，
     * 崩溃请勿反馈给 Renderium"。
     * 引擎的渲染循环直接短路休眠，仅提供 Vulkan 句柄初始化服务。
     */
    TAKEOVER(2, "红牌", "§c§l🚨 夺舍模式", "已接管底层管线，崩溃与引擎无关");

    // ==================== 字段定义 ====================

    /** 权限等级（0-2） */
    private final int level;

    /** 中文显示名称 */
    private final String displayName;

    /** UI 显示标签（带颜色的 Minecraft 文本） */
    private final String uiLabel;

    /** 用户可见的描述文本 */
    private final String description;

    // ==================== 构造函数 ====================

    ShaderPermission(int level, String displayName, String uiLabel, String description) {
        this.level = level;
        this.displayName = displayName;
        this.uiLabel = uiLabel;
        this.description = description;
    }

    // ==================== 查询接口 ====================

    /** 获取权限等级数值（0-2） */
    public int getLevel() { return level; }

    /** 获取中文显示名称 */
    public String getDisplayName() { return displayName; }

    /** 获取 UI 标签（带格式化代码） */
    public String getUiLabel() { return uiLabel; }

    /** 获取用户可见的描述 */
    public String getDescription() { return description; }

    /**
     * 检查是否为沙盒模式（最安全）
     */
    public boolean isSandbox() { return this == SANDBOX; }

    /**
     * 检查是否允许注入自定义 SPIR-V
     */
    public boolean allowsCustomSPIRV() {
        return this == INJECTION || this == TAKEOVER;
    }

    /**
     * 检查是否允许完全接管渲染管线
     */
    public boolean allowsFullTakeover() {
        return this == TAKEOVER;
    }

    /**
     * 检查是否需要向用户显示警告
     *
     * @return true 如果需要显示警告（非沙盒模式）
     */
    public boolean requiresWarning() {
        return this != SANDBOX;
    }

    /**
     * 获取警告级别（用于日志和 UI 着色）
     *
     * @return "info"、"warn" 或 "error"
     */
    public String getWarningSeverity() {
        return switch (this) {
            case SANDBOX -> "info";
            case INJECTION -> "warn";
            case TAKEOVER -> "error";
        };
    }

    /**
     * 构建完整的用户通知消息
     *
     * @param shaderPackName 光影包名称
     * @return 格式化的消息组件文本
     */
    public String buildUserNotification(String shaderPackName) {
        return switch (this) {
            case SANDBOX -> String.format(
                    "§9[Renderium 光影] §f%s 已加载（%s）\n  §7%s",
                    shaderPackName, displayName, description);
            case INJECTION -> String.format(
                    "§e[Renderium 光影] §f%s 已加载（%s）\n  %s\n  §6▸ 此光影包含自定义 SPIR-V 管线\n  §6▸ 部分 Renderium 引擎优化已被禁用",
                    shaderPackName, uiLabel, description);
            case TAKEOVER -> String.format(
                    "§c[Renderium 光影] §f%s 已加载（%s）\n  %s\n  §4§l!!! 严重警告 !!!§r\n" +
                    "  §c▸ 此光影已完全接管渲染管线\n" +
                    "  §c▸ Renderium 所有优化均已失效\n" +
                    "  §c▸ 如遇崩溃，请勿反馈给 Renderium\n" +
                    "  §c▸ 请联系光影作者 %s 获取支持",
                    shaderPackName, uiLabel, description, shaderPackName);
        };
    }

    @Override
    public String toString() {
        return String.format("ShaderPermission{level=%d, name=%s}", level, displayName);
    }
}
