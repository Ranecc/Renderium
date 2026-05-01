// Renderium - 光影系统 v2.0
// 渲染管线节点接口 - 所有光影特效的统一抽象

package com.ranecc.renderium.feature.pipeline.pipeline.node;

import com.ranecc.renderium.None;

/**
 * 渲染管线节点接口
 * <p>
 * Renderium 光影系统中所有渲染特效的统一抽象。
 * 每个节点代表一个独立的渲染处理步骤（如阴影、光照、后处理等），
 * 可以独立启用/禁用、支持依赖关系和优先级排序。
 *
 * <h2>设计原则：</h2>
 * <ul>
 *   <li><b>单一职责</b>：每个节点只负责一个特定的渲染任务</li>
 *   <li><b>可组合</b>：节点之间通过依赖关系组成有向无环图（DAG）</li>
 *   <li><b>可热切换</b>：运行时动态启用/禁用，无需重启</li>
 *   <li><b>短路支持</b>：禁用时自动短路（传递输入到输出）</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>
 * // 创建自定义节点
 * public class MyCustomNode implements PipelineNode {
 *     public String getId() { return "my_custom_effect"; }
 *     public String getDisplayName() { return "My Custom Effect"; }
 *     public NodeCategory getCategory() { return NodeCategory.POST_PROCESS; }
 *     public String[] getDependencies() { return new String[0]; }
 *     public int getPriority() { return 100; }
 *     
 *     public boolean initialize(RenderContext ctx) {
 *         // 初始化 GPU 资源
 *         return true;
 *     }
 *     
 *     public long execute(RenderContext ctx, long... inputs) {
 *         // 执行渲染逻辑
 *         return outputTextureHandle;
 *     }
 *     
 *     public void dispose() {
 *         // 释放资源
 *     }
 * }
 *
 * // 注册到系统
 * PipelineNodeRegistry.getInstance().register(new MyCustomNode());
 * </pre>
 *
 * @see PipelineNodeRegistry
 * @see RenderContext
 * @since 2.1.0
 */
public interface PipelineNode {

    /**
     * 获取节点唯一标识符
     * <p>
     * 标识符用于在注册表和配置文件中引用此节点。
     * 必须全局唯一，建议使用 kebab-case 命名。
     *
     * 【返回值】
     * @return String - 唯一标识符，如 "shadow_map"、"bloom"、"tonemap"
     */
    String getId();

    /**
     * 节点显示名称
     * <p>
     * 用于 UI 显示和日志输出。
     *
     * 【返回值】
     * @return String - 人类可读的显示名称
     */
    String getDisplayName();

    /**
     * 获取节点分类
     * <p>
     * 分类决定了节点在渲染管线中的大致位置。
     *
     * 【返回值】
     * @return Category - 节点所属分类
     */
    Category getCategory();

    /**
     * 获取此节点的依赖列表
     * <p>
     * 依赖节点必须在此节点之前执行。
     * 用于构建 DAG 并进行拓扑排序。
     *
     * 【返回值】
     * @return String[] - 依赖节点的 ID 数组（可能为空数组）
     */
    String[] getDependencies();

    /**
     * 获取执行优先级
     * <p>
     * 数值越小越先执行。
     * 当多个节点处于同一拓扑层级时，按优先级排序。
     *
     * 【返回值】
     * @return int - 优先级数值（0 = 最高优先级）
     */
    int getPriority();

    /**
     * 初始化节点资源
     * <p>
     * 在节点首次启用前调用一次。
     * 用于分配 GPU 资源、编译着色器、创建缓冲区等。
     *
     * 【方法参数】
     * @param context RenderContext - 渲染上下文，包含 Vulkan 设备、内存分配器等
     *
     * 【返回值】
     * @return boolean - 是否成功初始化：
     *         true  - 初始化成功，可以执行
     *         false - 初始化失败，节点将被标记为不可用
     */
    boolean initialize(RenderContext context);

    /**
     * 执行节点渲染逻辑
     * <p>
     * 每帧调用一次（如果节点已启用）。
     * 接收输入资源（如纹理句柄），处理后输出结果。
     *
     * 【方法参数】
     * @param context RenderContext - 当前帧的渲染上下文
     * @param inputResources long... - 输入资源句柄数组（通常是 Vulkan Image 句柄）
     *
     * 【返回值】
     * @return long - 输出资源句柄（Vulkan Image handle），0 表示失败
     *
     * 【性能要求】
     * - 此方法是热路径，每帧调用
     * - 应避免对象分配和同步操作
     * - 目标执行时间取决于具体实现
     */
    long execute(RenderContext context, long... inputResources);

    /**
     * 释放节点占用的所有资源
     * <p>
     * 在节点被移除或系统关闭时调用。
     * 必须释放所有 GPU 资源（纹理、缓冲区、着色器模块等）。
     */
    void dispose();

    /**
     * 检查节点是否已初始化
     * <p>
     * 默认实现返回 false，子类应重写此方法以反映实际状态。
     *
     * @return boolean - true 表示已初始化，false 表示未初始化
     */
    default boolean isInitialized() { return false; }

    // ==================== 内部枚举定义 ====================

    /**
     * 节点当前状态
     */
    enum State {
        /** 启用并正常执行 */
        ENABLED,
        /** 禁用（短路模式，直接传递输入到输出） */
        DISABLED,
        /** 使用官方原版实现（不执行自定义逻辑） */
        OFFICIAL,
        /** 绕过模式（与 DISABLED 类似但保留状态） */
        BYPASS
    }

    /**
     * 节点分类
     * <p>
     * 决定节点在渲染管线中的大致阶段
     */
    enum Category {
        /** 预计算阶段（Blaze3D 之前）：阴影、HiZ、遮挡剔除等 */
        PRE_RENDER,
        /** G-Buffer 生成阶段：几何信息输出 */
        GBUFFER,
        /** 光照计算阶段：直接光、间接光、AO 等 */
        LIGHTING,
        /** 后处理阶段：Bloom、Tonemap、DOF 等 */
        POST_PROCESS
    }
}
