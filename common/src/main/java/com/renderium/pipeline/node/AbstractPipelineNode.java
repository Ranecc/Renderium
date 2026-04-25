// Renderium - 光影系统 v2.0
// 渲染管线节点抽象基类 - 提供通用实现

package com.renderium.pipeline.node;

import com.renderium.interception.context.RenderContext;
import java.util.logging.Logger;

/**
 * 渲染管线节点抽象基类
 * <p>
 * 提供 PipelineNode 接口的通用实现，简化自定义节点的开发。
 * 子类只需实现 execute() 方法和相关配置即可。
 *
 * <h2>提供的功能：</h2>
 * <ul>
 *   <li>通用的字段管理（id, displayName, category 等）</li>
 *   <li>默认的 initialize()/dispose() 实现</li>
 *   <li>日志记录支持</li>
 *   <li>状态检查辅助方法</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>
 * public class BloomNode extends AbstractPipelineNode {
 *     public BloomNode() {
 *         super(
 *             "bloom",                          // ID
 *             "Bloom (泛光)",                    // 显示名称
 *             Category.POST_PROCESS,            // 分类
 *             200,                              // 优先级
 *             new String[0]                     // 无依赖
 *         );
 *     }
 *
 *     &#64;Override
 *     public long execute(RenderContext ctx, long... inputs) {
 *         // 执行 Bloom 后处理...
 *         return outputTexture;
 *     }
 * }
 * </pre>
 *
 * @see PipelineNode
 * @since 2.1.0
 */
public abstract class AbstractPipelineNode implements PipelineNode {

    protected final Logger logger = Logger.getLogger(getClass().getName());

    /** 节点唯一标识符 */
    private final String id;

    /** 显示名称 */
    private final String displayName;

    /** 节点分类 */
    private final Category category;

    /** 执行优先级 */
    private final int priority;

    /** 依赖节点 ID 列表 */
    private final String[] dependencies;

    /** 是否已初始化 */
    protected volatile boolean initialized = false;

    /**
     * 构造函数
     *
     * 【参数说明】
     * @param id           String              - 唯一标识符（kebab-case）
     * @param displayName  String              - 显示名称
     * @param category     Category            - 节点分类
     * @param priority     int                 - 执行优先级（越小越先执行）
     * @param dependencies String[]            - 依赖节点 ID 数组
     */
    protected AbstractPipelineNode(String id,
                                   String displayName,
                                   Category category,
                                   int priority,
                                   String[] dependencies) {
        this.id = id;
        this.displayName = displayName;
        this.category = category;
        this.priority = priority;
        this.dependencies = dependencies != null ? dependencies : new String[0];
    }

    /**
     * 简化构造函数（无依赖）
     */
    protected AbstractPipelineNode(String id,
                                   String displayName,
                                   Category category,
                                   int priority) {
        this(id, displayName, category, priority, new String[0]);
    }

    // ==================== PipelineNode 接口实现 ====================

    @Override
    public String getId() { return id; }

    @Override
    public String getDisplayName() { return displayName; }

    @Override
    public Category getCategory() { return category; }

    @Override
    public String[] getDependencies() { return dependencies; }

    @Override
    public int getPriority() { return priority; }

    /**
     * 默认初始化实现
     * <p>
     * 子类可重写此方法以添加自定义初始化逻辑。
     * 默认实现仅设置 initialized 标志。
     */
    @Override
    public boolean initialize(RenderContext context) {
        if (initialized) {
            return true;
        }

        try {
            // 调用子类的自定义初始化
            boolean success = onInitialize(context);

            if (success) {
                initialized = true;
                logger.fine("✓ 节点初始化成功: " + displayName);
            } else {
                logger.warning("✗ 节点初始化失败: " + displayName);
            }

            return success;

        } catch (Exception e) {
            logger.severe("✗ 节点初始化异常 [" + displayName + "]: " + e.getMessage());
            return false;
        }
    }

    /**
     * 默认释放实现
     * <p>
     * 子类可重写此方法以添加自定义清理逻辑。
     */
    @Override
    public void dispose() {
        if (!initialized) return;

        try {
            onDispose();
            initialized = false;
            logger.fine("✓ 节点资源已释放: " + displayName);
        } catch (Exception e) {
            logger.warning("释放节点资源时出错 [" + displayName + "]: " + e.getMessage());
        }
    }

    // ==================== 子类钩子方法 ====================

    /**
     * 初始化钩子方法
     * <p>
     * 子类重写此方法以执行自定义初始化逻辑。
     *
     * 【方法参数】
     * @param context RenderContext - 渲染上下文
     *
     * 【返回值】
     * @return boolean - 是否成功初始化
     */
    protected boolean onInitialize(RenderContext context) {
        return true;
    }

    /**
     * 释放钩子方法
     * <p>
     * 子类重写此方法以执行自定义清理逻辑。
     */
    protected void onDispose() {
        // 默认空实现
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查节点是否已初始化
     */
    public boolean isInitialized() { return initialized; }

    /**
     * 获取日志记录器（供子类使用）
     */
    protected Logger getLogger() { return logger; }

    @Override
    public String toString() {
        return String.format("PipelineNode{id=%s, name=%s, cat=%s, pri=%d, init=%s}",
                id, displayName, category, priority, initialized);
    }
}
