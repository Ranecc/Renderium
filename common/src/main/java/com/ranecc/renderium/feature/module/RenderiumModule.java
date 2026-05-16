package com.ranecc.renderium.feature.module;
import com.ranecc.renderium.feature.module.RenderiumModule;
import com.ranecc.renderium.feature.module.ModuleContext;

/**
 * Renderium 模块接口 — 定义优化器模块的标准生命周期
 *
 * <p>所有 Renderium 优化器模块（如 Blaze3D、Vulkan、Memory 等）都必须实现此接口。
 * 提供统一的模块加载、初始化和元数据查询能力。</p>
 *
 * <h2>模块生命周期</h2>
 * <pre>
 *   canLoad() → load() → initialize()
 * </pre>
 *
 * <h3>实现要求</h3>
 * <ul>
 *   <li>线程安全：模块可能在多线程环境下被查询</li>
 *   <li>幂等性：load() 和 initialize() 应该支持多次调用（幂等）</li>
 *   <li>快速失败：canLoad() 应该在模块不兼容时立即返回 false</li>
 * </ul>
 *
 * @author Renderium Team
 * @version 1.0
 * @since 3.0.0
 * @see ModuleContext
 * @see ModuleMetadata
 */
public interface RenderiumModule {

    /**
     * 获取模块元数据
     *
     * @return 模块的元数据信息（名称、版本、描述等），不能为 null
     */
    ModuleMetadata getMetadata();

    /**
     * 检查模块是否可以在当前环境中加载
     *
     * <p>此方法应该在 load() 之前调用，用于检查运行时环境是否满足模块的依赖要求。</p>
     *
     * @param context 模块上下文，提供运行时环境信息和依赖注入能力
     * @return true 表示可以安全加载，false 表示环境不兼容
     */
    boolean canLoad(ModuleContext context);

    /**
     * 加载模块（注册组件、配置监听器等）
     *
     * <p>在此阶段，模块应该：
     * <ul>
     *   <li>注册所需的优化器和监听器</li>
     *   <li>读取初始配置</li>
     *   <li>建立与其他模块的依赖关系</li>
     * </ul></p>
     *
     * @param context 模块上下文，提供运行时环境信息和依赖注入能力
     * @return true 表示加载成功，false 表示加载失败（应该回滚）
     */
    boolean load(ModuleContext context);

    /**
     * 初始化模块（启动后台任务、预热缓存等）
     *
     * <p>此方法在 load() 成功后调用。模块应该在这里执行重量级初始化操作：
     * <ul>
     *   <li>启动后台线程或定时任务</li>
     *   <li>预分配资源池</li>
     *   <li>预热 JIT 编译路径</li>
     * </ul></p>
     *
     * @param context 模块上下文，提供运行时环境信息和依赖注入能力
     * @return true 表示初始化成功，false 表示初始化失败（应该禁用模块）
     */
    boolean initialize(ModuleContext context);

    default boolean enable() { return true; }
    default void disable() {}
    default void dispose() {}
    default String getStatusString() { return "未知"; }

    default void onFrameBegin(float deltaTime) {}
    default void onFrameEnd() {}
}
