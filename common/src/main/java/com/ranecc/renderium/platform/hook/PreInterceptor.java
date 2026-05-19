// Renderium - Blaze3D 拦截层系统
// 前拦截层接口（平台钩子）- 定义渲染前的拦截操作

package com.ranecc.renderium.platform.hook;

import com.ranecc.renderium.domain.model.LODContext;
import com.ranecc.renderium.domain.model.InterceptionCallback;
import com.ranecc.renderium.domain.model.RenderContext;
import com.ranecc.renderium.domain.model.InterceptionResult;

/**
 * 前拦截层接口（Pre-Blaze3D Interceptor）- 平台钩子版本
 * <p>
 * 此接口镜像 {@code PreBlaze3DInterceptor} 的方法签名，
 * 但将 Feature 层特有的类型替换为 {@code Object}，
 * 使得平台层不依赖于 Feature 模块。
 *
 * <p>
 * 定义在 Blaze3D 渲染管线执行前的拦截操作，
 * 包括模组输出检测、LOD 预处理注入、剔除优化注入等。
 *
 * <h3>架构位置：</h3>
 * <pre>
 * MixinRenderSystem
 *     │
 *     ▼
 * PreInterceptor（平台钩子）
 *     │
 *     ├── 模组检测 + 输出处理
 *     ├── LOD 预处理注入
 *     └── 剔除优化注入
 * </pre>
 *
 * @see PostInterceptor 后拦截层接口
 * @see RenderContext 渲染上下文
 * @see InterceptionResult 拦截结果
 * @since 5.1.0
 */
public interface PreInterceptor {

    // ==================== 核心方法 ====================

    /**
     * 执行前拦截操作（核心方法）
     *
     * @param context 渲染上下文（包含相机、模组、资源等信息）
     * @return 拦截结果（包含修改后的上下文、性能指标、状态信息）
     * @throws IllegalArgumentException 如果 context 为 null
     */
    InterceptionResult intercept(RenderContext context);

    /**
     * 检测指定模组是否存在且已加载
     *
     * @param modId 模组标识符（小写，如 "sodium"、"iris"、"oculus"）
     * @return true 如果模组存在且已成功加载
     * @throws IllegalArgumentException 如果 modId 为 null 或空字符串
     */
    boolean isModDetected(String modId);

    /**
     * 注册模组输出处理器
     *
     * @param modId   模组标识符（不能为 null 或空字符串）
     * @param handler 模组输出处理器实例（Object 类型，Feature 层向下转型使用）
     * @throws IllegalArgumentException 如果 modId 或 handler 为 null
     * @throws IllegalStateException    如果该模组已注册过处理器
     */
    void registerModHandler(String modId, Object handler);

    /**
     * 注入 LOD（细节层次）预处理逻辑
     *
     * @param lodContext LOD 上下文（包含距离、过渡参数、Billboard 配置等）
     * @throws IllegalArgumentException 如果 lodContext 为 null
     */
    void injectLOD(LODContext lodContext);

    /**
     * 注入剔除优化逻辑
     *
     * @param cullContext 剔除上下文（Object 类型，Feature 层向下转型使用）
     * @throws IllegalArgumentException 如果 cullContext 为 null
     */
    void injectCulling(Object cullContext);

    // ==================== 生命周期方法 ====================

    /**
     * 初始化前拦截层
     *
     * @return true 表示初始化成功，false 表示失败
     */
    boolean initialize();

    /**
     * 关闭前拦截层并释放资源
     */
    void shutdown();

    /**
     * 检查是否已初始化
     *
     * @return true 如果已成功初始化且未关闭
     */
    boolean isInitialized();

    // ==================== 异步回调支持 ====================

    /**
     * 设置异步拦截完成回调
     *
     * @param callback 异步完成回调（可以为 null 以禁用异步模式）
     */
    void setAsyncCallback(InterceptionCallback callback);
}
