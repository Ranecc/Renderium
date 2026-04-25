// Renderium - 模块系统
// 核心模块接口 - 所有功能模块必须实现此接口

package com.renderium.module;

/**
 * Renderium 模块接口
 * <p>
 * 定义功能模块的标准契约。所有优化、集成、光影等功能都作为模块实现。
 * 参考 Sodium 的 `SodiumClientMod` 设计，但采用更灵活的接口方式。
 *
 * <h2>架构定位：</h2>
 * <pre>
 * Renderium Core
 *      ↓
 * ModuleRegistry (管理所有模块)
 *      ↓
 * ┌─────────────────────────────┐
 * │      RenderiumModule ← 你在这里  │
 * │                             │
 * │  • SodiumLikeRenderer       │
 * │  • Blaze3DOptimizer         │
 * │  • StreamlineIntegration    │
 * │  • RGBShaderLoader          │
 * └─────────────────────────────┘
 *      ↓
 * Minecraft Blaze3D / Vanilla Renderer
 * </pre>
 *
 * <h2>生命周期：</h2>
 * <ol>
 *   <li>{@link #getMetadata()} - 返回元数据（注册时调用）</li>
 *   <li>{@link #initialize(ModuleContext)} - 初始化资源</li>
 *   <li>{@link #enable()} - 激活功能</li>
 *   <li>{@link #disable()} - 停用功能（保留资源）</li>
 *   <li>{@link #dispose()} - 完全释放资源</li>
 * </ol>
 *
 * <h3>设计原则（参考 Sodium）：</h3>
 * <ul>
 *   <li><b>编译时绑定</b>: 不是插件，直接打包进 JAR</li>
 *   <li><b>Mixin 接管</b>: 核心模块使用 @Overwrite 替换原生方法</li>
 *   <li><b>安全降级</b>: 失败时回退到原生渲染器</li>
 *   <li><b>性能优先</b>: 减少内存分配和 GC 压力</li>
 * </ul>
 *
 * @see ModuleMetadata
 * @see ModuleRegistry
 * @see ModuleContext
 * @author Renderium Team
 * @since 2.0.0
 */
public interface RenderiumModule {

    /**
     * 获取模块元数据
     * <p>包含 ID、版本、依赖等信息。
     * 此方法应快速返回，不进行复杂计算。
     *
     * @return 不可变的 ModuleMetadata 实例
     */
    ModuleMetadata getMetadata();

    /**
     * 检查模块是否可在当前环境加载
     * <p>在此方法中进行：
     * <ul>
     *   <li>检测前置依赖（如 Sodium 是否安装）</li>
     *   <li>检查 GPU 能力</li>
     *   <li>验证 MC 版本兼容性</li>
     * </ul>
     *
     * @param context 模块上下文（可查询其他模块状态）
     * @return true 如果可以加载
     */
    default boolean canLoad(ModuleContext context) {
        return true; // 默认允许加载
    }

    /**
     * 初始化模块
     * <p>在此阶段：
     * <ul>
     *   <li>读取配置参数</li>
     *   <li>预分配资源（缓冲区、着色器等）</li>
     *   <li>注册 Mixin 注入点</li>
     *   <li>初始化子组件</li>
     * </ul>
     *
     * <p><b>重要：</b>此方法不应修改游戏状态，
     * 仅准备资源。实际功能激活在 {@link #enable()} 中进行。
     *
     * @param context 模块上下文
     * @return 初始化成功返回 true
     */
    boolean initialize(ModuleContext context);

    /**
     * 启用模块功能
     * <p>激活模块的所有功能：
     * <ul>
     *   <li>对于渲染模块：替换原生渲染器</li>
     *   <li>对于优化模块：注入优化逻辑</li>
     *   <li>对于集成模块：初始化 SDK 连接</li>
     * </ul>
     *
     * @return 启用成功返回 true
     */
    boolean enable();

    /**
     * 禁用模块功能
     * <p>停用功能但保留资源：
     * <ul>
     *   <li>恢复被替换的原生方法</li>
     *   <li>停止优化逻辑</li>
     *   <li>断开 SDK 连接</li>
     * </ul>
     * <p><b>注意：</b>之后可以重新调用 {@link #enable()}。
     */
    void disable();

    /**
     * 完全释放模块资源
     * <p>调用后模块不可再使用，需要重新初始化。
     * 应释放：
     * <ul>
     *   <li>GPU 资源（Buffer、Texture、Program）</li>
     *   <li>内存池和缓存</li>
     *   <li>线程和定时任务</li>
     *   <li>事件监听器</li>
     * </ul>
     */
    void dispose();

    // ==================== 可选钩子方法 ====================

    /**
     * 帧开始回调（可选）
     * <p>每帧开始时由 RenderiumCore 调用。
     * 用于更新动画、收集统计等。
     *
     * @param deltaTime 帧间隔时间（秒）
     */
    default void onFrameBegin(float deltaTime) {}

    /**
     * 帧结束回调（可选）
     * <p>每帧结束时调用。
     * 用于提交命令、清理临时资源等。
     */
    default void onFrameEnd() {}

    /**
     * 获取模块统计信息（可选）
     * <p>用于调试面板和性能监控。
     *
     * @return 统计信息字符串，无统计返回 null
     */
    default String getStatistics() { return null; }

    /**
     * 获取模块当前状态摘要（可选）
     * <p>用于 UI 显示和日志记录。
     *
     * @return 状态描述字符串
     */
    default String getStatusString() {
        return getMetadata().name() + " v" + getMetadata().version();
    }
}
