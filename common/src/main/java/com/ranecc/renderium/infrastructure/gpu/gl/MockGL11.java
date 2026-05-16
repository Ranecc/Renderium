// Renderium - MockGL11 拦截层
// 拦截所有 GL11 调用，维护 long[] 位域状态机，零对象分配
// Vulkan 不可用时自动禁用拦截，透传给官方 OpenGL 渲染器

package com.ranecc.renderium.infrastructure.gpu.gl;

import com.ranecc.renderium.None;
import com.ranecc.renderium.feature.pipeline.CommandBuffer;
import com.ranecc.renderium.feature.pipeline.core.RenderPipeline;
import com.ranecc.renderium.feature.renderopt.GLStateSnapshot;
import com.ranecc.renderium.infrastructure.vulkan.command.DrawCommand;
import com.ranecc.renderium.tech.stub.renderbackendproxy.RenderBackendProxy;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * MockGL11 - OpenGL 状态拦截器。
 *
 * <p>设计思路借鉴了 Mesa Zink (MIT) 的 OpenGL state tracker 机制，
 * 以及总体概念设计文档中"MockGL11（状态欺骗与数据榨取机）"的架构思想。
 *
 * <h2>核心职责</h2>
 * <p>作为原版 GL11 的替代品，拦截所有 OpenGL 调用：
 * <ol>
 *   <li><b>状态维护</b>：将 GL 状态变化记录到 {@link GLStateSnapshot} 的位域数组中</li>
 *   <li><b>状态欺骗</b>：当模组查询状态时返回快照中的值，而非真实 GL 状态</li>
 *   <li><b>绘制拦截</b>：当 glDraw* 被调用时，触发 Pipeline 编译和 Command 提交</li>
 * </ol>
 *
 * <h2>零分配保证</h2>
 * <p>本类的所有公共方法都遵循以下约束：
 * <ul>
 *   <li><b>不 new 任何对象</b>：状态变化只做位翻转或原始类型赋值</li>
 *   <li><b>不调用 LWJGL GL 方法</b>：所有 GL 调用都被拦截</li>
 *   <li><b>线程安全通过 ThreadLocal 实现</b>：每个线程有独立的快照副本</li>
 * </ul>
 *
 * <h2>与 Zink 的关系</h2>
 * <p>Zink 是一个完整的 OpenGL 实现，需要支持完整的 OpenGL 规范。
 * MockGL11 只针对 Minecraft 的 GL 调用子集进行优化，不需要完整兼容。
 * 这使得我们可以使用更激进的状态编码策略。
 *
 * <h2>短路策略</h2>
 * <p>当 {@link RenderBackendProxy} 处于 SHORT_CIRCUITED 状态时（Vulkan 不可用），
 * MockGL11 自动禁用拦截，所有 GL 调用透传给官方渲染器。
 * 游戏正常运行，只是没有 Renderium 的优化。
 * <p>drawArrays/drawElements 返回 false 表示"未拦截，请透传给官方渲染器"。
 *
 * @see <a href="https://gitlab.freedesktop.org/mesa/mesa/-/tree/main/src/gallium/drivers/zink">Mesa Zink Driver (OpenGL on Vulkan)</a>
 * @see <a href="https://gitlab.freedesktop.org/mesa/mesa/-/tree/main/src/mesa/state_tracker">Mesa State Tracker</a>
 * @see RenderBackendProxy
 * @author Renderium Team
 * @since 1.0.0
 */
public final class MockGL11 {

    private static final Logger LOGGER = Logger.getLogger(MockGL11.class.getName());

    /** 线程本地存储 - 每个渲染线程有独立的状态快照 */
    private static final ThreadLocal<GLStateSnapshot> threadLocalSnapshot =
            ThreadLocal.withInitial(GLStateSnapshot::new);

    /** 全局共享的后端代理 */
    private static volatile RenderBackendProxy backendProxy;

    /** 全局共享的命令缓冲区（每帧由渲染线程设置） */
    private static final ThreadLocal<CommandBuffer> threadLocalCommandBuffer = new ThreadLocal<>();

    /** 上一次编译的 RenderPipeline（用于复用，避免重复编译） */
    private static final ThreadLocal<RenderPipeline> threadLocalLastPipeline = new ThreadLocal<>();

    /** 是否启用拦截模式 */
    private static volatile boolean interceptionEnabled = true;

    /** 统计：拦截的 draw 调用次数 */
    private static volatile long interceptedDrawCount = 0;

    /** 统计：透传给官方 GL 的 draw 调用次数 */
    private static volatile long passthroughDrawCount = 0;

    /** 反射方法缓存：Class → "submitDraw" Method */
    private static final ConcurrentHashMap<Class<?>, Method> cachedSubmitDrawMethod = new ConcurrentHashMap<>();

    /** 反射方法缓存：Class → "submitDrawIndexed" Method */
    private static final ConcurrentHashMap<Class<?>, Method> cachedSubmitDrawIndexedMethod = new ConcurrentHashMap<>();

    /**
     * 私有构造函数 - 工具类
     */
    private MockGL11() {}

    // ==================== 初始化与配置 ====================

    /**
     * 设置全局后端代理
     *
     * <p>必须在首次使用前调用。通常在 RenderiumCore.init() 中设置。
     * 后端代理决定了是否启用 GL 拦截。
     *
     * @param proxy 后端代理实例
     */
    public static void setBackendProxy(RenderBackendProxy proxy) {
        backendProxy = proxy;
        // 根据后端状态决定是否启用拦截
        if (proxy != null) {
            interceptionEnabled = proxy.isInitialized();
        }
    }

    /**
     * 启用/禁用拦截模式
     *
     * <p>禁用时，所有调用直接透传给底层（用于调试或降级）
     *
     * @param enabled true=启用拦截，false=透传模式
     */
    public static void setInterceptionEnabled(boolean enabled) {
        interceptionEnabled = enabled;
    }

    /**
     * 检查是否已初始化
     */
    public static boolean isInitialized() {
        return backendProxy != null;
    }

    /**
     * 设置当前线程的命令缓冲区
     *
     * <p>每帧开始时由渲染线程调用
     *
     * @param cmdBuf 命令缓冲区
     */
    public static void setCommandBuffer(CommandBuffer cmdBuf) {
        threadLocalCommandBuffer.set(cmdBuf);
    }

    // ==================== 状态查询 API（给模组用的假状态）====================

    /**
     * 查询某个 capability 是否启用
     *
     * <p>对应 glIsEnabled(cap)。返回快照中的值，不查询真实 GL 状态。
     * 这确保了依赖状态检查的模组逻辑分支不会崩溃。
     *
     * @param cap GL 常量（如 GL_BLEND, GL_DEPTH_TEST）
     * @return 快照中记录的启用/禁用状态
     */
    public static boolean isEnabled(int cap) {
        int capIndex = mapCapabilityToIndex(cap);
        if (capIndex >= 0) {
            return getSnapshot().isCapabilityEnabled(capIndex);
        }
        return false;
    }

    /**
     * 获取当前绑定的纹理 ID
     *
     * 对应 glGetIntegerv(GL_TEXTURE_BINDING_2D)
     */
    public static int getTextureBinding() {
        return getSnapshot().getTexture2DBinding();
    }

    /**
     * 获取当前的错误码（始终返回 GL_NO_ERROR）
     *
     * <p>MockGL11 不产生 GL 错误
     */
    public static int getError() {
        return 0; // GL_NO_ERROR
    }

    // ==================== 状态修改 API（被 Mixin 拦截的入口）====================

    /**
     * 启用某个 GL 能力
     *
     * <p>对应 glEnable(cap)。
     * 实现为单次位翻转操作，O(1)，无对象分配。
     *
     * @param cap GL 常量
     */
    public static void enable(int cap) {
        if (!interceptionEnabled) return;
        int index = mapCapabilityToIndex(cap);
        if (index >= 0) {
            getSnapshot().setCapability(index, true);
        }
    }

    /**
     * 禁用某个 GL 能力
     *
     * <p>对应 glDisable(cap)。
     */
    public static void disable(int cap) {
        if (!interceptionEnabled) return;
        int index = mapCapabilityToIndex(cap);
        if (index >= 0) {
            getSnapshot().setCapability(index, false);
        }
    }

    /**
     * 绑定 2D 纹理
     *
     * <p>对应 glBindTexture(GL_TEXTURE_2D, texture)。
     */
    public static void bindTexture(int target, int textureId) {
        if (!interceptionEnabled) return;
        if (target == 0x0DE1) { // GL_TEXTURE_2D
            getSnapshot().bindTexture2D(textureId);
        }
    }

    /**
     * 设置活跃纹理单元
     *
     * <p>对应 glActiveTexture(texture)。
     */
    public static void activeTexture(int texture) {
        if (!interceptionEnabled) return;
        getSnapshot().setActiveTexture(texture);
    }

    /**
     * 设置混合方程
     *
     * <p>对应 glBlendEquation(mode)。
     */
    public static void blendEquation(int mode) {
        if (!interceptionEnabled) return;
        getSnapshot().setBlendEquation(mode);
    }

    /**
     * 设置混合函数
     *
     * <p>对应 glBlendFunc(sfactor, dfactor)。
     */
    public static void blendFunc(int sfactor, int dfactor) {
        if (!interceptionEnabled) return;
        getSnapshot().setBlendFuncSrcRGB(sfactor);
        getSnapshot().setBlendFuncDstRGB(dfactor);
    }

    /**
     * 设置深度测试函数
     *
     * <p>对应 glDepthFunc(func)。
     */
    public static void depthFunc(int func) {
        if (!interceptionEnabled) return;
        getSnapshot().setDepthFunc(func);
    }

    /**
     * 设置深度掩码
     *
     * <p>对应 glDepthMask(flag)。
     */
    public static void depthMask(boolean flag) {
        if (!interceptionEnabled) return;
        getSnapshot().setDepthMask(flag);
    }

    /**
     * 设置剔除面模式
     *
     * <p>对应 glCullFace(mode)。
     */
    public static void cullFace(int mode) {
        if (!interceptionEnabled) return;
        getSnapshot().setCullFaceMode(mode);
    }

    /**
     * 设置正面缠绕顺序
     *
     * <p>对应 glFrontFace(mode)。
     */
    public static void frontFace(int mode) {
        if (!interceptionEnabled) return;
        getSnapshot().setFrontFace(mode);
    }

    /**
     * 设置多边形填充模式
     *
     * <p>对应 glPolygonMode(face, mode)。
     * 我们只跟踪 mode 参数（face 在 MC 中几乎总是 GL_FRONT_AND_BACK）。
     */
    public static void polygonMode(int face, int mode) {
        if (!interceptionEnabled) return;
        getSnapshot().setPolygonMode(mode);
    }

    /**
     * 设置绘制颜色
     *
     * <p>对应 glColor4f(r, g, b, a)。
     */
    public static void color4f(float r, float g, float b, float a) {
        if (!interceptionEnabled) return;
        getSnapshot().setColor(r, g, b, a);
    }

    /**
     * 设置清除颜色
     *
     * <p>对应 glClearColor(r, g, b, a)。
     */
    public static void clearColor(float r, float g, float b, float a) {
        if (!interceptionEnabled) return;
        getSnapshot().setClearColor(r, g, b, a);
    }

    /**
     * 设置颜色写入掩码
     *
     * <p>对应 glColorMask(r, g, b, a)。
     */
    public static void colorMask(boolean r, boolean g, boolean b, boolean a) {
        if (!interceptionEnabled) return;
        getSnapshot().setColorWriteMask(r, g, b, a);
    }

    /**
     * 设置深度范围
     *
     * <p>对应 glDepthRange(nearVal, farVal)。
     */
    public static void depthRange(float nearVal, float farVal) {
        if (!interceptionEnabled) return;
        getSnapshot().setDepthRange(nearVal, farVal);
    }

    /**
     * 使用着色器程序
     *
     * <p>对应 glUseProgram(program)。
     */
    public static void useProgram(int program) {
        if (!interceptionEnabled) return;
        getSnapshot().useProgram(program);
    }

    // ==================== 绘制命令拦截（核心！）====================

    /**
     * 拦截 glDrawArrays 调用
     *
     * <p>这是整个拦截系统的核心出口。
     * 当任何模组调用 glDrawArrays 时：
     * <ol>
     *   <li>检查后端代理是否启用 Vulkan</li>
     *   <li>如果 Vulkan 不可用，返回 false 让官方 GL 处理</li>
     *   <li>检查快照是否有脏标记</li>
     *   <li>如果有变化，编译新的 RenderPipeline</li>
     *   <li>生成 DrawCommand 提交给 CommandBuffer</li>
     *   <li>清除脏标记</li>
     * </ol>
     *
     * @param mode 绘制模式（GL_TRIANGLES 等）
     * @param first 起始索引
     * @param count 顶点数量
     * @return true 如果成功拦截并提交到 CommandBuffer，false 表示应透传给官方 GL
     */
    public static boolean drawArrays(int mode, int first, int count) {
        // 检查后端是否可用
        if (!isVulkanActive()) {
            passthroughDrawCount++;
            return false; // 降级：让官方 GL 处理
        }

        GLStateSnapshot snapshot = getSnapshot();

        // 只有 dirty 时才需要重新编译 Pipeline
        if (snapshot.isDirty()) {
            compileAndSubmit(snapshot, mode, first, count, false, 0, 0L);
        } else {
            // 状态未变，复用上次 Pipeline，只提交 Draw
            submitDrawOnly(mode, first, count);
        }

        interceptedDrawCount++;
        return true;
    }

    /**
     * 拦截 glDrawElements 调用
     *
     * @param mode 绘制模式
     * @param count 索引数量
     * @param type 索引数据类型
     * @param indices 索引缓冲偏移
     * @return true 如果成功拦截，false 表示应透传给官方 GL
     */
    public static boolean drawElements(int mode, int count, int type, long indices) {
        if (!isVulkanActive()) {
            passthroughDrawCount++;
            return false;
        }

        GLStateSnapshot snapshot = getSnapshot();
        if (snapshot.isDirty()) {
            compileAndSubmit(snapshot, mode, count, 0, true, type, indices);
        } else {
            submitDrawElementsOnly(mode, count, type, indices);
        }

        interceptedDrawCount++;
        return true;
    }

    // ==================== 编译与提交核心逻辑 ====================

    /**
     * 编译当前状态并提交 Draw Command
     *
     * <p>核心流程：
     * <ol>
     *   <li>从 GLStateSnapshot 构建 RenderPipeline（不可变）</li>
     *   <li>缓存 Pipeline（通过 PipelineCache）</li>
     *   <li>创建 DrawCommand</li>
     *   <li>录制到 CommandBuffer</li>
     *   <li>清除脏标记</li>
     * </ol>
     *
     * @param snapshot 当前 GL 状态快照
     * @param mode 绘制模式
     * @param count 顶点/索引数量
     * @param first 起始顶点（DrawArrays 时有效）
     * @param indexed 是否为索引绘制
     * @param indexType 索引类型（DrawElements 时有效）
     * @param indexOffset 索引缓冲偏移（DrawElements 时有效）
     */
    private static void compileAndSubmit(GLStateSnapshot snapshot,
                                          int mode, int count, int first,
                                          boolean indexed, int indexType, long indexOffset) {
        // Step 1: 从快照构建不可变的 RenderPipeline
        RenderPipeline pipeline = RenderPipeline.Builder.fromSnapshot(snapshot).build();

        // Step 2: 缓存到 ThreadLocal，供后续 submitDrawOnly 复用
        threadLocalLastPipeline.set(pipeline);

        // Step 3: 创建 DrawCommand 并录制到 CommandBuffer
        CommandBuffer cmdBuf = threadLocalCommandBuffer.get();
        if (cmdBuf != null && cmdBuf.getState() == CommandBuffer.State.RECORDING) {
            DrawCommand command;
            if (indexed) {
                command = new DrawCommand(pipeline, mode, count, indexType, indexOffset);
            } else {
                command = new DrawCommand(pipeline, mode, first, count);
            }
            cmdBuf.record(command);
        }

        // Step 4: 同时直接提交到 VulkanBackend（绕过 CommandBuffer 的延迟）
        // 这确保了即使没有设置 CommandBuffer，渲染也能工作
        if (backendProxy != null && backendProxy.getVulkanBackend() != null) {
            var vkBackend = backendProxy.getVulkanBackend();
            if (indexed) {
                submitDrawIndexedViaReflection(vkBackend, pipeline, mode, count, indexType, indexOffset);
            } else {
                submitDrawViaReflection(vkBackend, pipeline, mode, first, count);
            }
        }

        // Step 5: 清除脏标记
        snapshot.clearDirty();
    }

    /**
     * 复用上次 Pipeline，只提交 DrawArrays
     *
     * <p>当状态未变化时调用，跳过 Pipeline 编译步骤
     */
    private static void submitDrawOnly(int mode, int first, int count) {
        RenderPipeline pipeline = threadLocalLastPipeline.get();
        if (pipeline == null) {
            // 没有缓存的 Pipeline，强制重新编译
            GLStateSnapshot snapshot = getSnapshot();
            snapshot.forceDirty();
            compileAndSubmit(snapshot, mode, count, first, false, 0, 0L);
            return;
        }

        // 录制到 CommandBuffer
        CommandBuffer cmdBuf = threadLocalCommandBuffer.get();
        if (cmdBuf != null && cmdBuf.getState() == CommandBuffer.State.RECORDING) {
            cmdBuf.record(new DrawCommand(pipeline, mode, first, count));
        }

        // 直接提交到 VulkanBackend
        if (backendProxy != null && backendProxy.getVulkanBackend() != null) {
            submitDrawViaReflection(backendProxy.getVulkanBackend(), pipeline, mode, first, count);
        }
    }

    /**
     * 复用上次 Pipeline，只提交 DrawElements
     */
    private static void submitDrawElementsOnly(int mode, int count, int type, long indices) {
        RenderPipeline pipeline = threadLocalLastPipeline.get();
        if (pipeline == null) {
            GLStateSnapshot snapshot = getSnapshot();
            snapshot.forceDirty();
            compileAndSubmit(snapshot, mode, count, 0, true, type, indices);
            return;
        }

        CommandBuffer cmdBuf = threadLocalCommandBuffer.get();
        if (cmdBuf != null && cmdBuf.getState() == CommandBuffer.State.RECORDING) {
            cmdBuf.record(new DrawCommand(pipeline, mode, count, type, indices));
        }

        if (backendProxy != null && backendProxy.getVulkanBackend() != null) {
            submitDrawIndexedViaReflection(backendProxy.getVulkanBackend(), pipeline, mode, count, type, indices);
        }
    }

    // ==================== 内部工具方法 ====================

    /**
     * 通过反射调用 VulkanBackend 的 submitDraw 方法
     * <p>
     * TODO: getVulkanBackend() 现在返回 Object 类型（待模块重构后恢复强类型），
     * 此处使用反射桥接，避免编译错误。
     *
     * @param vkBackend   Vulkan 后端对象（Object 类型）
     * @param pipeline    渲染管线
     * @param mode        绘制模式
     * @param first       起始顶点
     * @param count       顶点数量
     */
    private static void submitDrawViaReflection(Object vkBackend, RenderPipeline pipeline,
                                                  int mode, int first, int count) {
        try {
            Method method = cachedSubmitDrawMethod.computeIfAbsent(vkBackend.getClass(),
                cls -> {
                    try {
                        return cls.getMethod("submitDraw", RenderPipeline.class, int.class, int.class, int.class);
                    } catch (NoSuchMethodException e) {
                        return null;
                    }
                });
            if (method != null) {
                method.invoke(vkBackend, pipeline, mode, first, count);
            }
        } catch (Exception e) {
            LOGGER.fine("反射调用 vkBackend.submitDraw() 失败（可能 Vulkan 后端未完全初始化）: "
                + e.getMessage());
        }
    }

    /**
     * 通过反射调用 VulkanBackend 的 submitDrawIndexed 方法
     * <p>
     * TODO: getVulkanBackend() 现在返回 Object 类型（待模块重构后恢复强类型），
     * 此处使用反射桥接，避免编译错误。
     *
     * @param vkBackend   Vulkan 后端对象（Object 类型）
     * @param pipeline    渲染管线
     * @param mode        绘制模式
     * @param count       索引数量
     * @param indexType   索引类型
     * @param indexOffset 索引偏移
     */
    private static void submitDrawIndexedViaReflection(Object vkBackend, RenderPipeline pipeline,
                                                         int mode, int count, int indexType, long indexOffset) {
        try {
            Method method = cachedSubmitDrawIndexedMethod.computeIfAbsent(vkBackend.getClass(),
                cls -> {
                    try {
                        return cls.getMethod("submitDrawIndexed", RenderPipeline.class, int.class, int.class, int.class, long.class);
                    } catch (NoSuchMethodException e) {
                        return null;
                    }
                });
            if (method != null) {
                method.invoke(vkBackend, pipeline, mode, count, indexType, indexOffset);
            }
        } catch (Exception e) {
            LOGGER.fine("反射调用 vkBackend.submitDrawIndexed() 失败（可能 Vulkan 后端未完全初始化）: "
                + e.getMessage());
        }
    }

    /**
     * 获取当前线程的状态快照
     */
    private static GLStateSnapshot getSnapshot() {
        return threadLocalSnapshot.get();
    }

    /**
     * 获取当前线程的状态快照（公开接口，供 compatibility 层调用）
     *
     * @return 当前线程的 GLStateSnapshot 实例
     */
    public static GLStateSnapshot getThreadLocalSnapshot() {
        return threadLocalSnapshot.get();
    }

    /**
     * 检查 Vulkan 后端是否活跃
     *
     * <p>只有 Vulkan 活跃时才拦截 GL 调用。
     * 官方 GL 回退模式下不拦截。
     *
     * @return true 如果应该拦截 GL 调用
     */
    private static boolean isVulkanActive() {
        if (!interceptionEnabled) return false;
        if (backendProxy == null) return false;
        return backendProxy.isVulkanActive();
    }

    /**
     * 将 GL 常量映射到位索引
     *
     * <p>Minecraft 常用的 GL 常量映射表。
     * 未映射的常量会被忽略（安全降级）。
     */
    private static int mapCapabilityToIndex(int cap) {
        return switch (cap) {
            case 0x0BE2 -> GLStateSnapshot.CAP_BLEND;           // GL_BLEND
            case 0x0B71 -> GLStateSnapshot.CAP_DEPTH_TEST;      // GL_DEPTH_TEST
            case 0x0C44 -> GLStateSnapshot.CAP_CULL_FACE;       // GL_CULL_FACE
            case 0x0B90 -> GLStateSnapshot.CAP_STENCIL_TEST;    // GL_STENCIL_TEST
            case 0x0C11 -> GLStateSnapshot.CAP_SCISSOR_TEST;    // GL_SCISSOR_TEST
            case 0x0BD0 -> GLStateSnapshot.CAP_DITHER;          // GL_DITHER
            case 0x809D -> GLStateSnapshot.CAP_MULTISAMPLE;      // GL_MULTISAMPLE
            default -> -1; // 未映射
        };
    }

    // ==================== 调试与诊断 ====================

    /**
     * 获取当前线程快照的诊断信息
     */
    public static String diagnoseCurrentState() {
        GLStateSnapshot snap = getSnapshot();
        return String.format(
                "MockGL11[thread=%s]{interception=%s, dirty=%s, frame=%d, blend=%s, depth=%s, cull=%s, tex=%d, intercepted=%d, passthrough=%d}",
                Thread.currentThread().getName(),
                interceptionEnabled,
                snap.isDirty(),
                snap.getFrameCounter(),
                snap.isBlendEnabled() ? "ON" : "OFF",
                snap.isDepthTestEnabled() ? "ON" : "OFF",
                snap.isCullFaceEnabled() ? "ON" : "OFF",
                snap.getTexture2DBinding(),
                interceptedDrawCount,
                passthroughDrawCount
        );
    }

    /**
     * 强制标记当前快照为 dirty（外部状态变化时调用）
     */
    public static void forceDirty() {
        getSnapshot().forceDirty();
    }

    /**
     * 获取拦截统计
     *
     * @return 拦截的 draw 调用次数
     */
    public static long getInterceptedDrawCount() {
        return interceptedDrawCount;
    }

    /**
     * 获取透传统计
     *
     * @return 透传给官方 GL 的 draw 调用次数
     */
    public static long getPassthroughDrawCount() {
        return passthroughDrawCount;
    }
}
