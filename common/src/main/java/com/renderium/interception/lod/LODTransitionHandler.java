// Renderium - Blaze3D 拦截层系统 Phase 3: LOD 多细节层次系统
// LODTransitionHandler.java - 过渡处理器（dithering / crossfade）
// 功能: 实现 LOD 等级间的平滑过渡，防止 popping 闪烁

package com.renderium.interception.lod;

import java.util.logging.Logger;

/**
 * LOD 过渡处理器。
 *
 * <p>负责在相邻 LOD 等级之间实现平滑过渡，消除 LOD 切换时的
 * "popping"（突兀跳变）视觉瑕疵。支持两种过渡策略：
 * <ul>
 *   <li><b>Dithering（抖动）</b>：基于屏幕空间噪声的伪随机选择，
 *       无额外内存开销，适合兼容模式。</li>
 *   <li><b>Crossfade（交叉淡化）</b>：在两个 LOD mesh 之间进行 alpha 混合，
 *       视觉质量最佳但需要 GPU 支持，适合狂暴模式。</li>
 * </ul>
 *
 * <h2>算法对比：</h2>
 * <pre>
 * ┌────────────────┬──────────────────────┬──────────────────────┐
 * │ 策略           │ Dithering            │ Crossfade            │
 * ├────────────────┼──────────────────────┼──────────────────────┤
 * │ 内存开销       │ 零（仅计算）          │ 2x Mesh 显存         │
 * │ 视觉质量       │ 轻微噪点（可接受）    │ 完美平滑             │
 * │ GPU 开销       │ 极低                 │ 中等（双 pass 渲染）  │
 * │ 适用模式       │ 兼容模式 (CPU)        │ 狂暴模式 (GPU)       │
 * │ CPU 开销       │ &lt;0.1ms              │ &lt;0.05ms（仅计算 alpha）│
 * └────────────────┴──────────────────────┴──────────────────────┘
 * </pre>
 *
 * <h2>Dithering 算法原理：</h2>
 * <pre>
 * 在过渡区间内，使用基于空间位置的哈希函数生成 [0,1) 的伪随机值，
 * 将此值与 transitionAlpha 比较：
 * - if (ditherValue &lt; alpha) → 使用较高细节 LOD
 * - else → 使用较低细节 LOD
 *
 * 这种方法产生的"噪声"在人眼看来是均匀分布的静态颗粒感，
 * 远比突然切换（popping）更容易接受。
 * </pre>
 *
 * @see LODCalculator#getTransitionAlpha(int, int, double)
 * @see RenderiumLODSystem
 * @author Renderium Team
 * @since 5.3.0
 */
public final class LODTransitionHandler {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(LODTransitionHandler.class.getName());

    // ==================== 常量定义 ====================

    /** 默认过渡持续时间（毫秒） */
    public static final long DEFAULT_TRANSITION_DURATION_MS = 200L;

    /** Dithering 噪声缩放因子（控制噪声粒度） */
    private static final float DITHER_SCALE = 0.00390625f; // 1/256

    /** 单例实例 */
    private static final LODTransitionHandler INSTANCE = new LODTransitionHandler();

    /**
     * 获取单例实例
     *
     * @return 全局唯一的 LODTransitionHandler 实例
     */
    public static LODTransitionHandler getInstance() {
        return INSTANCE;
    }

    // ==================== 配置字段 ====================

    /** 过渡持续时间（毫秒） */
    private volatile long transitionDurationMs = DEFAULT_TRANSITION_DURATION_MS;

    /** 是否启用 dithering 模式（否则使用硬切换） */
    private volatile boolean ditheringEnabled = true;

    /** 是否启用 crossfade 模式（GPU 双 pass 渲染） */
    private volatile boolean crossfadeEnabled = false;

    /** 当前帧索引（用于 temporal dithering） */
    private volatile int frameIndex = 0;

    // ==================== 私有构造函数 ====================

    private LODTransitionHandler() {}

    // ==================== 核心 API：Dithering ====================

    /**
     * 计算 Dithering 决策值。
     *
     * <h3>算法：</h3>
     * <pre>
     * 1. 基于屏幕空间位置 (screenX, screenY) 计算空间哈希
     * 2. 加入帧索引实现 temporal dithering（逐帧变化）
     * 3. 映射到 [0.0, 1.0] 范围
     * 4. 返回值 &lt; transitionAlpha → 选择高细节 LOD
     * </pre>
     *
     * <h3>时间复杂度：</h3>O(1)，纯数学运算，无内存分配
     *
     * @param screenX          屏幕空间 X 坐标（像素）
     * @param screenY          屏幕空间 Y 坐标（像素）
     * @param transitionAlpha  过渡混合系数 [0.0, 1.0]，来自 {@link LODCalculator}
     * @return true 表示应选择较高细节的 LOD，false 表示选择较低细节
     */
    public boolean computeDitherDecision(float screenX, float screenY, float transitionAlpha) {
        if (!ditheringEnabled || transitionAlpha <= 0.0f) {
            return false; // 未启用或无需过渡 → 直接用低细节
        }
        if (transitionAlpha >= 1.0f) {
            return true; // 完全过渡 → 用高细节
        }

        // 计算空间-时间哈希值，映射到 [0.0, 1.0)
        float ditherValue = spatialTemporalHash(screenX, screenY, frameIndex);

        // 与过渡 Alpha 比较
        return ditherValue < transitionAlpha;
    }

    /**
     * 基于 chunk 坐标的 Dithering 决策（便捷方法）。
     *
     * <p>将 chunk 坐标映射到伪屏幕坐标后调用 {@link #computeDitherDecision(float, float, float)}。
     *
     * @param chunkX           区块 X 坐标
     * @param chunkZ           区块 Z 坐标
     * @param transitionAlpha  过渡混合系数 [0.0, 1.0]
     * @return true 表示选择高细节 LOD
     */
    public boolean computeChunkDitherDecision(int chunkX, int chunkZ, float transitionAlpha) {
        // 将 chunk 坐标映射到伪屏幕空间（乘以固定缩放因子模拟屏幕位置）
        float pseudoScreenX = chunkX * 16.0f * DITHER_SCALE;
        float pseudoScreenZ = chunkZ * 16.0f * DITHER_SCALE;
        return computeDitherDecision(pseudoScreenX, pseudoScreenZ, transitionAlpha);
    }

    // ==================== 核心 API：Crossfade ====================

    /**
     * 计算 Crossfade 混合系数（Alpha）。
     *
     * <p>Crossfade 在两个 LOD mesh 之间进行线性插值混合，
     * 需要同时渲染两个 mesh 并在 fragment shader 中按 alpha 混合颜色。
     *
     * <h3>输出格式：</h3>
     * <pre>
     * 返回值 [0.0, 1.0]:
     *   - 0.0: 100% 使用 currentLOD mesh
     *   - 0.5: 50% currentLOD + 50% targetLOD
     *   - 1.0: 100% 使用 targetLOD mesh
     * </pre>
     *
     * @param currentLOD       当前 LOD 等级
     * @param targetLOD        目标 LOD 等级
     * @param distance         当前距离（区块数）
     * @param elapsedTimeNs    进入过渡区后的经过时间（纳秒）
     * @return 混合系数 [0.0, 1.0]
     */
    public float computeCrossfadeAlpha(int currentLOD, int targetLOD,
                                        double distance, long elapsedTimeNs) {
        if (!crossfadeEnabled) {
            return distance >= 0.5 ? 1.0f : 0.0f; // 未启用 → 硬切换
        }

        // 从 LODCalculator 获取基础过渡 alpha
        double baseAlpha = LODCalculator.getInstance()
            .getTransitionAlpha(currentLOD, targetLOD, distance);

        // 应用时间因子：使过渡在 transitionDurationMs 内完成
        if (transitionDurationMs > 0 && elapsedTimeNs > 0) {
            long elapsedMs = elapsedTimeNs / 1_000_000L;
            float timeFactor = Math.min(1.0f, (float) elapsedMs / (float) transitionDurationMs);

            // 使用 smoothstep 缓动函数让过渡更自然
            baseAlpha *= smoothStep(timeFactor);
        }

        // Clamp 到 [0, 1]
        return (float) Math.max(0.0, Math.min(1.0, baseAlpha));
    }

    /**
     * 获取 Crossfade 所需的双 pass 渲染配置。
     *
     * <p>返回是否需要在当前帧执行双 pass 渲染
     * （即同时渲染两个 LOD level 并混合）。
     *
     * @param transitionAlpha 原始过渡系数
     * @return true 如果需要双 pass 渲染
     */
    public boolean needsDualPassRendering(float transitionAlpha) {
        if (!crossfadeEnabled) {
            return false;
        }
        // 当过渡系数在有效范围内时需要双 pass
        return transitionAlpha > 0.01f && transitionAlpha < 0.99f;
    }

    // ==================== 帧更新 API ====================

    /**
     * 更新帧索引（每帧调用一次）。
     *
     * <p>用于 temporal dithering，使抖动图案逐帧变化，
     * 减少人眼对静态噪声的感知。
     *
     * @param newFrameIndex 新的帧序号（单调递增）
     */
    public void advanceFrame(int newFrameIndex) {
        this.frameIndex = newFrameIndex;
    }

    // ==================== 配置 API ====================

    /**
     * 设置过渡持续时间。
     *
     * @param durationMs 持续时间（毫秒，必须 >= 0）
     * @throws IllegalArgumentException 如果值为负数
     */
    public void setTransitionDuration(long durationMs) {
        if (durationMs < 0) {
            throw new IllegalArgumentException("过渡持续时间不能为负数: " + durationMs);
        }
        this.transitionDurationMs = durationMs;
        LOGGER.fine("过渡持续时间已设置为 " + durationMs + "ms");
    }

    /**
     * 启用/禁用 Dithering 模式。
     *
     * @param enabled 是否启用
     */
    public void setDitheringEnabled(boolean enabled) {
        this.ditheringEnabled = enabled;
        LOGGER.fine("Dithering 模式: " + (enabled ? "启用" : "禁用"));
    }

    /**
     * 启用/禁用 Crossfade 模式。
     *
     * <p>注意：Crossfade 需要 GPU 支持和额外显存，
     * 仅推荐在狂暴模式下启用。
     *
     * @param enabled 是否启用
     */
    public void setCrossfadeEnabled(boolean enabled) {
        this.crossfadeEnabled = enabled;
        LOGGER.fine("Crossfade 模式: " + (enabled ? "启用" : "禁用"));
    }

    // ==================== 查询 API ====================

    /**
     * 获取当前过渡持续时间。
     *
     * @return 持续时间（毫秒）
     */
    public long getTransitionDuration() {
        return transitionDurationMs;
    }

    /**
     * 检查 Dithering 是否已启用。
     *
     * @return true 如果已启用
     */
    public boolean isDitheringEnabled() {
        return ditheringEnabled;
    }

    /**
     * 检查 Crossfade 是否已启用。
     *
     * @return true 如果已启用
     */
    public boolean isCrossfadeEnabled() {
        return crossfadeEnabled;
    }

    /**
     * 获取当前帧索引。
     *
     * @return 帧序号
     */
    public int getFrameIndex() {
        return frameIndex;
    }

    // ==================== 内部工具方法 ====================

    /**
     * 空间-时间哈希函数（用于 Dithering）。
     *
     * <h3>算法：</h3>
     * 基于 xxHash 风格的快速哈希，结合空间坐标和帧索引，
     * 生成确定性的伪随机值。相同输入永远返回相同结果。
     *
     * <h3>特性：</h3>
     * <ul>
     *   <li>确定性：相同 (x, y, frame) → 相同输出</li>
     *   <li>均匀分布：输出在 [0,1) 上近似均匀</li>
     *   <li>低碰撞：邻近像素产生不同输出</li>
     *   <li>高性能：纯整数运算，无分支</li>
     * </ul>
     *
     * @param x      空间 X 坐标
     * @param y      空间 Y 坐标
     * @param frame  帧索引（时间维度）
     * @return 哈希值 [0.0, 1.0)
     */
    private static float spatialTemporalHash(float x, float y, int frame) {
        // 将浮点坐标转换为整数网格（量化以减少精度导致的漂移）
        int ix = Float.floatToIntBits(x);
        int iy = Float.floatToIntBits(y);

        // xxHash 风格的质数混合
        int hash = 0x9E3779B9; // 黄金比例相关的魔数（φ × 2^32）
        hash = (hash ^ ix) * 0x85EBCA6B;
        hash = Integer.rotateLeft(hash, 13);
        hash = (hash ^ iy) * 0xC2B2AE3D;
        hash = Integer.rotateLeft(hash, 7);
        hash = (hash ^ frame) * 0x27D4EB2D;

        // 最终混合（确保高位信息扩散到低位）
        hash ^= hash >>> 16;
        hash *= 0x85EBCA6B;
        hash ^= hash >>> 13;
        hash *= 0xC2B2AE35;
        hash ^= hash >>> 16;

        // 映射到 [0.0, 1.0)，使用无符号归一化
        return ((hash & 0xFFFFFFFFL) / (float) 0xFFFFFFFFL);
    }

    /**
     * 平滑阶梯函数（Hermite 插值）：f(t) = 3t² - 2t³
     *
     * <p>特性：端点导数为零，提供自然的加速/减速缓动效果。
     *
     * @param t 输入值 [0, 1]
     * @return 平滑后的值 [0, 1]
     */
    private static float smoothStep(float t) {
        t = Math.max(0.0f, Math.min(1.0f, t)); // clamp 到 [0, 1]
        return t * t * (3.0f - 2.0f * t);
    }
}
