// Renderium - GL State Snapshot Machine
// 使用位运算高效存储和比较 OpenGL 渲染状态
// 参考: "Copy-on-Write 快照" 设计模式

package com.renderium.compatibility;

import com.renderium.graphics.pipeline.PipelineStateBits;
import com.renderium.graphics.pipeline.RenderPipeline;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.logging.Logger;

/**
 * OpenGL 状态快照机
 * <p>
 * 这是 "GL 状态机 → 现代命令流" 架构的核心组件。
 * 维护一个基于位运算的当前状态快照数组，用于：
 * <ul>
 *   <li>记录老模组通过 GL11 设置的所有渲染状态</li>
 *   <li>在 Draw 调用时对比前后状态差异</li>
 *   <li>生成不可变的 RenderPipeline 对象</li>
 * </ul>
 *
 * <h3>设计原则（参考 MVP 版本渲染管线思路文档）：</h3>
 * <pre>
 * 不要用一堆零散的布尔值存状态。
 * 维护一个巨大的 long[] currentStateSnapshot（8个long = 512位）。
 * MockGL11 的 Mixin 只做最简单的事：把对应的 bit 翻转。
 * 只有当老模组最终调用 glDrawArrays() 时，
 * 才触发"编译"：对比当前的 currentStateSnapshot 和上一次 Draw 的快照。
 * </pre>
 *
 * <h3>线程安全：</h3>
 * 使用 AtomicLongArray 保证主线程的原子操作。
 * 在 Minecraft 中，所有 GL 调用都在主线程执行，
 * 因此不需要复杂的锁机制。
 *
 * @see PipelineStateBits
 * @see RenderPipeline
 */
public final class StateSnapshot {

    private static final Logger LOGGER = Logger.getLogger(StateSnapshot.class.getName());

    // ==================== 单例模式 ====================

    private static final StateSnapshot INSTANCE = new StateSnapshot();

    /** 获取全局单例实例 */
    public static StateSnapshot getInstance() {
        return INSTANCE;
    }

    // ==================== 状态存储 ====================

    /**
     * 当前活跃的状态快照 (8个long = 512位)
     * 使用 AtomicLongArray 支持无锁读取
     */
    private final AtomicLongArray currentState;

    /**
     * 上一次 Draw 调用时的状态快照
     * 用于增量比较，判断是否需要创建新 Pipeline
     */
    private volatile long[] lastDrawSnapshot;

    /**
     * 上一次 Draw 创建的 RenderPipeline
     * 如果当前状态与上次相同，可以复用
     */
    private volatile RenderPipeline lastDrawPipeline;

    /**
     * 当前绑定的 Shader Program ID
     * (-1 表示无 Shader / 固定管线)
     */
    private volatile int currentShaderProgramId;

    // ==================== 统计信息 ====================

    private volatile long totalStateChanges;
    private volatile long totalSnapshotsGenerated;
    private volatile long pipelineReuseCount; // 复用次数（性能指标）

    // ==================== 私有构造函数 ====================

    private StateSnapshot() {
        this.currentState = new AtomicLongArray(8);
        this.lastDrawSnapshot = null;
        this.lastDrawPipeline = null;
        this.currentShaderProgramId = -1;
        this.totalStateChanges = 0;
        this.totalSnapshotsGenerated = 0;
        this.pipelineReuseCount = 0;

        // 初始化为默认状态
        long[] defaultState = PipelineStateBits.createDefaultState();
        for (int i = 0; i < 8; i++) {
            currentState.set(i, defaultState[i]);
        }
        this.lastDrawSnapshot = defaultState.clone();

        LOGGER.info("StateSnapshot initialized with default GL state");
    }

    // ==================== 状态修改方法（由 MockGL11 Mixin 调用）====================

    /**
     * 设置单个状态位
     * <p>
     * 这是最底层的操作，只做一件事：翻转/设置对应的 bit。
     * 不做任何逻辑判断，保证最小开销。
     *
     * @param offset 位偏移量 (参考 PipelineStateBits 常量)
     * @param mask   位掩码
     * @param value  要设置的值 (0 或非0)
     */
    public void setStateBit(int offset, long mask, boolean value) {
        if (value) {
            currentState.accumulateAndGet(offset / 64, mask, (old, m) -> old | m);
        } else {
            currentState.accumulateAndGet(offset / 64, mask, (old, m) -> old & ~m);
        }
        totalStateChanges++;
    }

    /**
     * 设置枚举类型状态值
     * <p>
     * 用于设置有多个可能值的枚举状态（如混合因子、比较函数等）。
     *
     * @param offset 位偏移量
     * @param mask   位掩码
     * @param value  枚举值
     */
    public void setStateValue(int offset, long mask, int value) {
        int wordIndex = offset / 64;
        int bitOffset = offset % 64;
        long shiftedMask = mask << bitOffset;
        long shiftedValue = ((long) value << bitOffset) & shiftedMask;

        currentState.accumulateAndGet(wordIndex, shiftedMask | mask,
            (old, m) -> (old & ~m) | shiftedValue);
        totalStateChanges++;
    }

    /**
     * 启用混合 (glEnable(GL_BLEND))
     */
    public void enableBlend() {
        setStateBit(PipelineStateBits.BLEND_ENABLED_OFFSET,
                    PipelineStateBits.BLEND_ENABLED_MASK, true);
    }

    /**
     * 禁用混合 (glDisable(GL_BLEND))
     */
    public void disableBlend() {
        setStateBit(PipelineStateBits.BLEND_ENABLED_OFFSET,
                    PipelineStateBits.BLEND_ENABLED_MASK, false);
    }

    /**
     * 设置混合因子 (glBlendFunc / glBlendFuncSeparate)
     *
     * @param srcFactor      源颜色混合因子
     * @param dstFactor      目标颜色混合因子
     * @param srcAlphaFactor 源 Alpha 混合因子
     * @param dstAlphaFactor 目标 Alpha 混合因子
     */
    public void setBlendFunc(int srcFactor, int dstFactor,
                             int srcAlphaFactor, int dstAlphaFactor) {
        setStateValue(PipelineStateBits.SRC_BLEND_FACTOR_OFFSET,
                     PipelineStateBits.SRC_BLEND_FACTOR_MASK, srcFactor);
        setStateValue(PipelineStateBits.DST_BLEND_FACTOR_OFFSET,
                     PipelineStateBits.DST_BLEND_FACTOR_MASK, dstFactor);
        setStateValue(PipelineStateBits.SRC_ALPHA_BLEND_FACTOR_OFFSET,
                     PipelineStateBits.SRC_ALPHA_BLEND_FACTOR_MASK, srcAlphaFactor);
        setStateValue(PipelineStateBits.DST_ALPHA_BLEND_FACTOR_OFFSET,
                     PipelineStateBits.DST_ALPHA_BLEND_FACTOR_MASK, dstAlphaFactor);
    }

    /**
     * 启用深度测试 (glEnable(GL_DEPTH_TEST))
     */
    public void enableDepthTest() {
        setStateBit(PipelineStateBits.DEPTH_TEST_ENABLED_OFFSET,
                    PipelineStateBits.DEPTH_TEST_ENABLED_MASK, true);
    }

    /**
     * 禁用深度测试 (glDisable(GL_DEPTH_TEST))
     */
    public void disableDepthTest() {
        setStateBit(PipelineStateBits.DEPTH_TEST_ENABLED_OFFSET,
                    PipelineStateBits.DEPTH_TEST_ENABLED_MASK, false);
    }

    /**
     * 设置深度写入掩码 (glDepthMask)
     *
     * @param enabled 是否允许深度写入
     */
    public void setDepthWriteMask(boolean enabled) {
        setStateBit(PipelineStateBits.DEPTH_WRITE_ENABLED_OFFSET,
                    PipelineStateBits.DEPTH_WRITE_ENABLED_MASK, enabled);
    }

    /**
     * 设置深度比较函数 (glDepthFunc)
     *
     * @param func 比较函数 (NEVER=0, LESS=1, EQUAL=2, LEQUAL=3, ...)
     */
    public void setDepthFunc(int func) {
        setStateValue(PipelineStateBits.DEPTH_FUNC_OFFSET,
                     PipelineStateBits.DEPTH_FUNC_MASK, func);
    }

    /**
     * 启用背面剔除 (glEnable(GL_CULL_FACE))
     */
    public void enableCullFace() {
        setStateBit(PipelineStateBits.CULL_FACE_ENABLED_OFFSET,
                    PipelineStateBits.CULL_FACE_ENABLED_MASK, true);
    }

    /**
     * 禁用背面剔除 (glDisable(GL_CULL_FACE))
     */
    public void disableCullFace() {
        setStateBit(PipelineStateBits.CULL_FACE_ENABLED_OFFSET,
                    PipelineStateBits.CULL_FACE_ENABLED_MASK, false);
    }

    /**
     * 设置剔除面模式 (glCullFace)
     *
     * @param mode BACK(0), FRONT(1), FRONT_AND_BACK(2)
     */
    public void setCullFaceMode(int mode) {
        setStateValue(PipelineStateBits.CULL_FACE_MODE_OFFSET,
                     PipelineStateBits.CULL_FACE_MODE_MASK, mode);
    }

    /**
     * 设置前面绕序 (glFrontFace)
     *
     * @param mode CW(0), CCW(1)
     */
    public void setFrontFace(int mode) {
        setStateValue(PipelineStateBits.FRONT_FACE_OFFSET,
                     PipelineStateBits.FRONT_FACE_MASK, mode);
    }

    /**
     * 设置多边形模式 (glPolygonMode)
     *
     * @param mode FILL(0), LINE(1), POINT(2)
     */
    public void setPolygonMode(int mode) {
        setStateValue(PipelineStateBits.POLYGON_MODE_OFFSET,
                     PipelineStateBits.POLYGON_MODE_MASK, mode);
    }

    /**
     * 启用模板测试 (glEnable(GL_STENCIL_TEST))
     */
    public void enableStencilTest() {
        setStateBit(PipelineStateBits.STENCIL_TEST_ENABLED_OFFSET,
                    PipelineStateBits.STENCIL_TEST_ENABLED_MASK, true);
    }

    /**
     * 禁用模板测试 (glDisable(GL_STENCIL_TEST))
     */
    public void disableStencilTest() {
        setStateBit(PipelineStateBits.STENCIL_TEST_ENABLED_OFFSET,
                    PipelineStateBits.STENCIL_TEST_ENABLED_MASK, false);
    }

    /**
     * 设置颜色写入掩码 (glColorMask)
     *
     * @param r R通道
     * @param g G通道
     * @param b B通道
     * @param a A通道
     */
    public void setColorMask(boolean r, boolean g, boolean b, boolean a) {
        int mask = (r ? 1 : 0) | (g ? 2 : 0) | (b ? 4 : 0) | (a ? 8 : 0);
        setStateValue(PipelineStateBits.COLOR_WRITE_MASK_OFFSET,
                     PipelineStateBits.COLOR_WRITE_MASK_MASK, mask);
    }

    /**
     * 设置当前绑定的 Shader Program ID (glUseProgram)
     *
     * @param programId Program ID (-1 表示使用固定管线)
     */
    public void setShaderProgram(int programId) {
        this.currentShaderProgramId = programId;
    }

    // ==================== 快照编译方法（Draw 调用时触发）====================

    /**
     * 编译当前状态为 RenderPipeline
     * <p>
     * 这是整个"状态机 → 命令流"架构的关键转换点。
     * 当老模组调用 glDrawArrays/glDrawElements 时触发此方法：
     * <ol>
     *   <li>获取当前状态的完整副本</li>
     *   <li>与上次 Draw 的状态进行比较</li>
     *   <li>如果相同，直接复用上次创建的 RenderPipeline</li>
     *   <li>如果不同，创建新的 RenderPipeline 并缓存</li>
     * </ol>
     *
     * @return 编译后的 RenderPipeline 对象（不可变）
     */
    public RenderPipeline compileCurrentState() {
        long[] snapshot = getCurrentStateSnapshot();
        totalSnapshotsGenerated++;

        // 快速路径：检查是否与上次完全相同
        if (lastDrawSnapshot != null && PipelineStateBits.equals(snapshot, lastDrawSnapshot)) {
            if (lastDrawPipeline != null &&
                lastDrawPipeline.getProgramId() == currentShaderProgramId) {
                pipelineReuseCount++;
                return lastDrawPipeline;
            }
        }

        // 使用新的 Builder 模式创建 RenderPipeline
        RenderPipeline pipeline = buildPipelineFromSnapshot(snapshot);

        // 更新缓存
        lastDrawSnapshot = snapshot;
        lastDrawPipeline = pipeline;

        return pipeline;
    }

    /**
     * 从状态快照构建 RenderPipeline
     */
    private RenderPipeline buildPipelineFromSnapshot(long[] snapshot) {
        RenderPipeline.Builder builder = new RenderPipeline.Builder();

        // 混合状态
        builder.withBlendEnabled((snapshot[0] & PipelineStateBits.BLEND_ENABLED_MASK) != 0);
        builder.withDepthTestEnabled((snapshot[1] & PipelineStateBits.DEPTH_TEST_ENABLED_MASK) != 0);
        builder.withDepthWriteEnabled((snapshot[1] & PipelineStateBits.DEPTH_WRITE_ENABLED_MASK) != 0);
        builder.withCullFaceEnabled((snapshot[2] & PipelineStateBits.CULL_FACE_ENABLED_MASK) != 0);
        builder.withStencilTestEnabled((snapshot[1] & PipelineStateBits.STENCIL_TEST_ENABLED_MASK) != 0);

        // 枚举状态
        int depthFunc = (int) PipelineStateBits.getState(snapshot,
            PipelineStateBits.DEPTH_FUNC_OFFSET, PipelineStateBits.DEPTH_FUNC_MASK);
        builder.withDepthCompareOp(depthFunc);

        int cullMode = (int) PipelineStateBits.getState(snapshot,
            PipelineStateBits.CULL_FACE_MODE_OFFSET, PipelineStateBits.CULL_FACE_MODE_MASK);
        builder.withCullFaceMode(cullMode);

        int polyMode = (int) PipelineStateBits.getState(snapshot,
            PipelineStateBits.POLYGON_MODE_OFFSET, PipelineStateBits.POLYGON_MODE_MASK);
        builder.withPolygonMode(polyMode);

        // 颜色掩码
        int colorMask = (int) PipelineStateBits.getState(snapshot,
            PipelineStateBits.COLOR_WRITE_MASK_OFFSET, PipelineStateBits.COLOR_WRITE_MASK_MASK);
        builder.withColorWriteR((colorMask & 1) != 0);
        builder.withColorWriteG((colorMask & 2) != 0);
        builder.withColorWriteB((colorMask & 4) != 0);
        builder.withColorWriteA((colorMask & 8) != 0);

        // Shader Program
        builder.withProgramId(currentShaderProgramId);

        return builder.build();
    }

    /**
     * 强制重新编译（忽略缓存）
     * 用于某些特殊场景（如 FBO 切换后）
     */
    public RenderPipeline forceCompile() {
        lastDrawPipeline = null;
        lastDrawSnapshot = null;
        return compileCurrentState();
    }

    // ==================== 状态查询方法 ====================

    /**
     * 获取当前状态的完整快照副本
     *
     * @return 长度为8的 long 数组副本
     */
    public long[] getCurrentStateSnapshot() {
        long[] result = new long[8];
        for (int i = 0; i < 8; i++) {
            result[i] = currentState.get(i);
        }
        return result;
    }

    /**
     * 获取当前绑定的 Shader Program ID
     */
    public int getShaderProgramId() {
        return currentShaderProgramId;
    }

    /** 检查混合是否启用 */
    public boolean isBlendEnabled() {
        return (currentState.get(0) & PipelineStateBits.BLEND_ENABLED_MASK) != 0;
    }

    /** 检查深度测试是否启用 */
    public boolean isDepthTestEnabled() {
        return (currentState.get(1) & PipelineStateBits.DEPTH_TEST_ENABLED_MASK) != 0;
    }

    /** 检查深度写入是否启用 */
    public boolean isDepthWriteEnabled() {
        return (currentState.get(1) & PipelineStateBits.DEPTH_WRITE_ENABLED_MASK) != 0;
    }

    /** 检查背面剔除是否启用 */
    public boolean isCullFaceEnabled() {
        return (currentState.get(2) & PipelineStateBits.CULL_FACE_ENABLED_MASK) != 0;
    }

    // ==================== 统计与调试 ====================

    /** 获取总状态变更次数 */
    public long getTotalStateChanges() { return totalStateChanges; }

    /** 获取总快照生成次数 */
    public long getTotalSnapshotsGenerated() { return totalSnapshotsGenerated; }

    /** 获取 Pipeline 复用次数 */
    public long getPipelineReuseCount() { return pipelineReuseCount; }

    /** 计算复用率 (0.0 - 1.0) */
    public double getReuseRate() {
        long total = totalSnapshotsGenerated + pipelineReuseCount;
        return total == 0 ? 0.0 : (double) pipelineReuseCount / total;
    }

    /**
     * 重置统计计数器
     */
    public void resetStatistics() {
        totalStateChanges = 0;
        totalSnapshotsGenerated = 0;
        pipelineReuseCount = 0;
    }

    /**
     * 重置为默认状态
     * 用于场景切换或 FBO 变更时
     */
    public void resetToDefault() {
        long[] defaultState = PipelineStateBits.createDefaultState();
        for (int i = 0; i < 8; i++) {
            currentState.set(i, defaultState[i]);
        }
        lastDrawSnapshot = defaultState.clone();
        lastDrawPipeline = null;
        currentShaderProgramId = -1;
    }

    /**
     * 获取调试信息字符串
     */
    public String getDebugInfo() {
        return String.format(
            "StateSnapshot Debug Info:\n" +
            "  Total state changes: %d\n" +
            "  Snapshots generated: %d\n" +
            "  Pipeline reuse count: %d\n" +
            "  Reuse rate: %.1f%%\n" +
            "  Current shader: %d\n" +
            "  Blend: %b | DepthTest: %b | DepthWrite: %b | Cull: %b",
            totalStateChanges,
            totalSnapshotsGenerated,
            pipelineReuseCount,
            getReuseRate() * 100,
            currentShaderProgramId,
            isBlendEnabled(),
            isDepthTestEnabled(),
            isDepthWriteEnabled(),
            isCullFaceEnabled()
        );
    }
}
