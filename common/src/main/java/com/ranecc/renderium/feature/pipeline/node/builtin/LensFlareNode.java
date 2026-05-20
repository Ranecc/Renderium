// Renderium - 光影系统 v2.0
// 镜头光晕 (Lens Flare) 后处理节点
//
// 核心算法:
//   1. 亮度提取：阈值过滤 → 仅保留高亮像素
//   2. 鬼影生成：沿像素到光源中心连线采样，带偏移
//   3. 条纹生成：4 方向（十字）条纹从高亮特征扩散
//   4. 色散：每个鬼影轻微 RGB 偏移
//   5. 合成：加法混合叠加到场景
//
// 性能预算:
//   - 亮度提取: < 0.1ms/帧
//   - 鬼影 + 条纹: < 0.5ms/帧
//   - 合成: < 0.1ms/帧
//   - 总计: 0.3-1ms/帧

package com.ranecc.renderium.feature.pipeline.node.builtin;

import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.pipeline.node.PipelineNode;

import java.util.logging.Logger;

/**
 * 镜头光晕节点 (Lens Flare)
 * <p>
 * 模拟真实镜头的光晕效果，包括鬼影、条纹和色散。
 * 需要色调映射后的场景纹理。
 * <p>
 * Blender: Lens Flare (Compositor) | Unreal: Lens Flare | Unity: Lens Flare
 * <p>
 * GPU 开销：0.3-1ms (1080p)
 * 短路条件：enabled == false → 直接返回输入纹理
 *
 * <h2>算法概述：</h2>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────┐
 * │ execute() 入口                                                │
 *     ↓                                                          │
 * ├─ Step 1: 短路检查（enabled == false → pass-through）          │
 *     ↓                                                          │
 * ├─ Step 2: 输入校验（至少需要颜色纹理 1 张）                     │
 *     ↓                                                          │
 * ├─ Step 3: 亮度提取                                            │
 * │   阈值过滤，仅保留亮度 > threshold 的像素                      │
 *     ↓                                                          │
 * ├─ Step 4: 鬼影生成                                            │
 * │   对每个鬼影，沿像素到光源中心连线采样，带偏移                  │
 *     ↓                                                          │
 * ├─ Step 5: 条纹生成                                            │
 * │   4 方向（十字）条纹从高亮特征扩散                             │
 *     ↓                                                          │
 * ├─ Step 6: 色散                                               │
 * │   每个鬼影轻微 RGB 偏移，模拟色散效果                          │
 *     ↓                                                          │
 * ├─ Step 7: 合成                                               │
 * │   加法混合叠加到场景                                          │
 *     ↓                                                          │
 * └─ Step 8: 返回处理后的纹理句柄                                │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>参数调优建议：</h2>
 * <table border="1">
 *   <tr><th>参数</th><th>默认值</th><th>效果</th></tr>
 *   <tr><td>intensity</td><td>0.5</td><td>光晕强度，0.0-2.0</td></tr>
 *   <tr><td>ghostCount</td><td>4</td><td>鬼影数量，1-8</td></tr>
 *   <tr><td>streakLength</td><td>0.5</td><td>条纹长度，0.1-2.0</td></tr>
 *   <tr><td>threshold</td><td>0.85</td><td>亮度阈值，0.5-1.0</td></tr>
 * </table>
 *
 * @see AbstractPipelineNode
 * @see PipelineNode.Category#POST_PROCESS
 * @since 5.5.0
 */
public class LensFlareNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(LensFlareNode.class.getName());

    // ==================== 参数边界 ====================

    /** 光晕强度下界 */
    private static final float INTENSITY_MIN = 0.0f;

    /** 光晕强度上界 */
    private static final float INTENSITY_MAX = 2.0f;

    /** 光晕强度默认值 */
    private static final float INTENSITY_DEFAULT = 0.5f;

    /** 鬼影数量下界 */
    private static final int GHOST_COUNT_MIN = 1;

    /** 鬼影数量上界 */
    private static final int GHOST_COUNT_MAX = 8;

    /** 鬼影数量默认值 */
    private static final int GHOST_COUNT_DEFAULT = 4;

    /** 条纹长度下界 */
    private static final float STREAK_LENGTH_MIN = 0.1f;

    /** 条纹长度上界 */
    private static final float STREAK_LENGTH_MAX = 2.0f;

    /** 条纹长度默认值 */
    private static final float STREAK_LENGTH_DEFAULT = 0.5f;

    /** 亮度阈值下界 */
    private static final float THRESHOLD_MIN = 0.5f;

    /** 亮度阈值上界 */
    private static final float THRESHOLD_MAX = 1.0f;

    /** 亮度阈值默认值 */
    private static final float THRESHOLD_DEFAULT = 0.85f;

    // ==================== 可调参数 ====================

    /** 是否启用镜头光晕 */
    private volatile boolean enabled = false;

    /** 光晕强度 */
    private volatile float intensity = INTENSITY_DEFAULT;

    /** 鬼影数量 */
    private volatile int ghostCount = GHOST_COUNT_DEFAULT;

    /** 条纹长度 */
    private volatile float streakLength = STREAK_LENGTH_DEFAULT;

    /** 亮度阈值，仅亮度高于此值的像素参与光晕计算 */
    private volatile float threshold = THRESHOLD_DEFAULT;

    // ==================== 构造函数 ====================

    /**
     * 构造镜头光晕节点
     * <p>
     * 配置节点身份信息：
     * <ul>
     *   <li>ID: "lens_flare"</li>
     *   <li>DisplayName: "Lens Flare (镜头光晕)"</li>
     *   <li>Category: {@link PipelineNode.Category#POST_PROCESS}</li>
     *   <li>Priority: 175（后处理阶段，色调映射之后）</li>
     *   <li>依赖: ["tonemap"]</li>
     * </ul>
     */
    public LensFlareNode() {
        super(
                "lens_flare",                                   // 唯一标识符（kebab-case）
                "Lens Flare (镜头光晕)",                        // 显示名称
                PipelineNode.Category.POST_PROCESS,            // 分类：后处理阶段
                175,                                           // 优先级
                new String[]{"tonemap"}                        // 依赖：色调映射节点
        );
    }

    // ==================== PipelineNode 核心方法 ====================

    /**
     * 执行镜头光晕计算
     * <p>
     * 每帧调用一次的热路径方法。完整流程：
     * <ol>
     *   <li>短路检查：enabled == false 时直接返回输入纹理</li>
     *   <li>输入校验：至少需要颜色纹理 1 张</li>
     *   <li>亮度提取：阈值过滤高亮像素</li>
     *   <li>鬼影生成：沿像素到光源中心连线采样</li>
     *   <li>条纹生成：4 方向十字条纹</li>
     *   <li>色散：轻微 RGB 偏移</li>
     *   <li>合成：加法混合叠加到场景</li>
     * </ol>
     *
     * 【方法参数】
     * @param context         RenderContext - 当前帧渲染上下文
     * @param inputResources long...      - 上游节点输出的资源句柄数组
     *                                  [0] = 颜色纹理句柄
     *
     * 【返回值】
     * @return long - 处理后的颜色纹理句柄，0 表示失败
     *
     * 【性能预算】
     * - 1080p 目标: 0.3-1ms (Fragment/Compute Shader)
     */
    @Override
    public long execute(RenderContext context, long... inputResources) {
        // 短路：禁用时直接传递输入
        if (!enabled) return passThrough(inputResources);

        // 输入校验
        if (inputResources == null || inputResources.length < 1) {
            LOGGER.warning("[LensFlare] 输入资源不足: 需要颜色纹理 1 张, "
                    + "实际收到 " + (inputResources == null ? 0 : inputResources.length) + " 张");
            return 0L;
        }

        long startTimeNanos = System.nanoTime();

        // 快照读取 volatile 参数（一次读取，避免多次读不一致）
        float curIntensity = this.intensity;
        int curGhostCount = this.ghostCount;
        float curStreakLength = this.streakLength;
        float curThreshold = this.threshold;

        // TODO: 实现 GPU Lens Flare
        // 1. 亮度提取：阈值过滤 → 仅保留亮度 > threshold 的像素
        //    brightPixel = luminance(color) > threshold ? color : vec3(0)
        //
        // 2. 鬼影生成：对每个鬼影，沿像素到光源中心连线采样，带偏移
        //    for (i = 0; i < ghostCount; i++) {
        //        vec2 ghostUV = (uv - lightCenter) * (i * 0.3 + 0.5) + lightCenter;
        //        ghostColor += texture(scene, ghostUV) * intensity * falloff;
        //    }
        //
        // 3. 条纹生成：4 方向（十字）条纹从高亮特征扩散
        //    沿上/下/左/右四个方向拉伸高亮像素
        //    streakColor = directionalBlur(brightTexture, streakLength, 4 directions)
        //
        // 4. 色散：每个鬼影轻微 RGB 偏移，模拟色散效果
        //    ghostR = sample(ghostUV + vec2(offset, 0))
        //    ghostG = sample(ghostUV)
        //    ghostB = sample(ghostUV - vec2(offset, 0))
        //
        // 5. 合成：加法混合叠加到场景
        //    output = sceneColor + (ghostColor + streakColor) * intensity

        long elapsedMicros = (System.nanoTime() - startTimeNanos) / 1000;
        LOGGER.fine(String.format(
                "[LensFlare] 完成 | intensity=%.2f ghosts=%d streak=%.2f threshold=%.2f | %.1fμs",
                curIntensity, curGhostCount, curStreakLength, curThreshold, elapsedMicros
        ));

        return inputResources[0]; // 待 GPU 实现后返回处理后的纹理
    }

    // ==================== 初始化与释放钩子 ====================

    /**
     * 节点初始化钩子
     *
     * 【方法参数】
     * @param context RenderContext - 渲染上下文
     *
     * 【返回值】
     * @return boolean - 是否成功初始化
     */
    @Override
    protected boolean onInitialize(RenderContext context) {
        LOGGER.info(String.format(
                "[LensFlare] 初始化成功 | intensity=%.2f ghosts=%d streak=%.2f threshold=%.2f",
                this.intensity, this.ghostCount, this.streakLength, this.threshold
        ));
        return true;
    }

    /**
     * 节点资源释放钩子
     * <p>
     * 清空内部缓冲区。
     */
    @Override
    protected void onDispose() {
        LOGGER.fine("[LensFlare] 资源已释放");
    }

    // ==================== 参数 Setter ====================

    /**
     * 设置是否启用镜头光晕
     *
     * @param v 是否启用
     */
    public void setEnabled(boolean v) { this.enabled = v; }

    /**
     * 获取当前启用状态
     *
     * @return boolean - 是否启用
     */
    public boolean isEnabled() { return enabled; }

    /**
     * 设置光晕强度
     * <p>
     * 值会被钳制到有效范围 [{@value #INTENSITY_MIN}, {@value #INTENSITY_MAX}]。
     *
     * @param v 光晕强度（0.0 ~ 2.0）
     */
    public void setIntensity(float v) {
        this.intensity = Math.max(INTENSITY_MIN, Math.min(INTENSITY_MAX, v));
    }

    /**
     * 设置鬼影数量
     * <p>
     * 值会被钳制到有效范围 [{@value #GHOST_COUNT_MIN}, {@value #GHOST_COUNT_MAX}]。
     *
     * @param v 鬼影数量（1 ~ 8）
     */
    public void setGhostCount(int v) {
        this.ghostCount = Math.max(GHOST_COUNT_MIN, Math.min(GHOST_COUNT_MAX, v));
    }

    /**
     * 设置条纹长度
     * <p>
     * 值会被钳制到有效范围 [{@value #STREAK_LENGTH_MIN}, {@value #STREAK_LENGTH_MAX}]。
     *
     * @param v 条纹长度（0.1 ~ 2.0）
     */
    public void setStreakLength(float v) {
        this.streakLength = Math.max(STREAK_LENGTH_MIN, Math.min(STREAK_LENGTH_MAX, v));
    }

    /**
     * 设置亮度阈值
     * <p>
     * 值会被钳制到有效范围 [{@value #THRESHOLD_MIN}, {@value #THRESHOLD_MAX}]。
     * 仅亮度高于此值的像素参与光晕计算。
     *
     * @param v 亮度阈值（0.5 ~ 1.0）
     */
    public void setThreshold(float v) {
        this.threshold = Math.max(THRESHOLD_MIN, Math.min(THRESHOLD_MAX, v));
    }

    // ==================== 辅助方法 ====================

    /**
     * 短路：禁用时直接传递输入
     *
     * @param inputs 输入资源句柄数组
     * @return long - 第一个输入资源句柄，无输入时返回 0
     */
    private long passThrough(long[] inputs) {
        return (inputs != null && inputs.length > 0) ? inputs[0] : 0L;
    }

    @Override
    public String toString() {
        return String.format(
                "LensFlareNode{enabled=%b intensity=%.2f ghosts=%d streak=%.2f threshold=%.2f}",
                enabled, intensity, ghostCount, streakLength, threshold
        );
    }
}
