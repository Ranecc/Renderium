// Renderium - 光影系统 v2.0
// 自动曝光 (Auto Exposure) 后处理节点
//
// 核心算法:
//   1. 亮度直方图：计算场景亮度的 64-bin 直方图
//   2. 平均亮度：根据测光模式加权求和
//   3. 目标曝光：exposure = targetLuminance / avgLuminance
//   4. 时域适应：lerp(prevExposure, targetExposure, 1 - exp(-dt * adaptationRate))
//   5. 钳制：clamp(exposure, minExposure, maxExposure)
//   6. 应用：output = input * exposure
//
// 性能预算:
//   - 直方图计算: < 0.2ms/帧
//   - 曝光计算 + 适应: < 0.1ms/帧
//   - 应用: < 0.1ms/帧
//   - 总计: < 0.5ms/帧

package com.ranecc.renderium.feature.pipeline.node.builtin;

import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.pipeline.node.PipelineNode;

import java.util.logging.Logger;

/**
 * 自动曝光节点 (Auto Exposure)
 * <p>
 * 基于场景亮度直方图自动调整曝光值，模拟人眼适应。
 * 需要深度缓冲 + 几何信息用于测光权重计算。
 * <p>
 * Blender: Auto Exposure | Unreal: Auto Exposure (Eye Adaptation) | Unity: Auto Exposure
 * <p>
 * GPU 开销：<0.5ms (1080p)
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
 * ├─ Step 3: 亮度直方图                                          │
 * │   计算场景亮度的 64-bin 直方图                                 │
 *     ↓                                                          │
 * ├─ Step 4: 平均亮度                                            │
 * │   根据测光模式（平均/点测光/中央重点）加权求和                  │
 *     ↓                                                          │
 * ├─ Step 5: 目标曝光                                            │
 * │   exposure = targetLuminance / avgLuminance                   │
 *     ↓                                                          │
 * ├─ Step 6: 时域适应                                            │
 * │   lerp(prevExposure, targetExposure, 1 - exp(-dt * rate))    │
 *     ↓                                                          │
 * ├─ Step 7: 钳制                                               │
 * │   clamp(exposure, minExposure, maxExposure)                   │
 *     ↓                                                          │
 * ├─ Step 8: 应用曝光                                            │
 * │   output = input * exposure                                   │
 *     ↓                                                          │
 * └─ Step 9: 返回处理后的纹理句柄                                │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>参数调优建议：</h2>
 * <table border="1">
 *   <tr><th>参数</th><th>默认值</th><th>效果</th></tr>
 *   <tr><td>targetLuminance</td><td>0.18</td><td>目标亮度（18% 灰），0.01-1.0</td></tr>
 *   <tr><td>adaptationRate</td><td>1.0</td><td>适应速率，0.01-5.0，越大越快</td></tr>
 *   <tr><td>minExposure</td><td>0.5</td><td>最小曝光值，0.1-3.0</td></tr>
 *   <tr><td>maxExposure</td><td>8.0</td><td>最大曝光值，3.0-16.0</td></tr>
 *   <tr><td>meteringMode</td><td>2</td><td>测光模式：0=平均, 1=点测光, 2=中央重点</td></tr>
 * </table>
 *
 * @see AbstractPipelineNode
 * @see PipelineNode.Category#POST_PROCESS
 * @since 5.5.0
 */
public class AutoExposureNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(AutoExposureNode.class.getName());

    // ==================== 参数边界 ====================

    /** 目标亮度下界 */
    private static final float TARGET_LUMINANCE_MIN = 0.01f;

    /** 目标亮度上界 */
    private static final float TARGET_LUMINANCE_MAX = 1.0f;

    /** 目标亮度默认值（18% 灰） */
    private static final float TARGET_LUMINANCE_DEFAULT = 0.18f;

    /** 适应速率下界 */
    private static final float ADAPTATION_RATE_MIN = 0.01f;

    /** 适应速率上界 */
    private static final float ADAPTATION_RATE_MAX = 5.0f;

    /** 适应速率默认值 */
    private static final float ADAPTATION_RATE_DEFAULT = 1.0f;

    /** 最小曝光值下界 */
    private static final float MIN_EXPOSURE_MIN = 0.1f;

    /** 最小曝光值上界 */
    private static final float MIN_EXPOSURE_MAX = 3.0f;

    /** 最小曝光值默认值 */
    private static final float MIN_EXPOSURE_DEFAULT = 0.5f;

    /** 最大曝光值下界 */
    private static final float MAX_EXPOSURE_MIN = 3.0f;

    /** 最大曝光值上界 */
    private static final float MAX_EXPOSURE_MAX = 16.0f;

    /** 最大曝光值默认值 */
    private static final float MAX_EXPOSURE_DEFAULT = 8.0f;

    /** 测光模式下界 */
    private static final int METERING_MODE_MIN = 0;

    /** 测光模式上界 */
    private static final int METERING_MODE_MAX = 2;

    /** 测光模式默认值（中央重点） */
    private static final int METERING_MODE_DEFAULT = 2;

    // ==================== 可调参数 ====================

    /** 是否启用自动曝光 */
    private volatile boolean enabled = false;

    /** 目标亮度（18% 灰为标准中灰） */
    private volatile float targetLuminance = TARGET_LUMINANCE_DEFAULT;

    /** 适应速率，控制曝光变化速度 */
    private volatile float adaptationRate = ADAPTATION_RATE_DEFAULT;

    /** 最小曝光值 */
    private volatile float minExposure = MIN_EXPOSURE_DEFAULT;

    /** 最大曝光值 */
    private volatile float maxExposure = MAX_EXPOSURE_DEFAULT;

    /** 测光模式：0=平均, 1=点测光, 2=中央重点 */
    private volatile int meteringMode = METERING_MODE_DEFAULT;

    /** 上一帧曝光值（用于时域适应） */
    private volatile float prevExposure = 1.0f;

    // ==================== 构造函数 ====================

    /**
     * 构造自动曝光节点
     * <p>
     * 配置节点身份信息：
     * <ul>
     *   <li>ID: "auto_exposure"</li>
     *   <li>DisplayName: "Auto Exposure (自动曝光)"</li>
     *   <li>Category: {@link PipelineNode.Category#POST_PROCESS}</li>
     *   <li>Priority: 145（后处理阶段，色调映射之前）</li>
     *   <li>依赖: ["gbuffer_geometry"]</li>
     * </ul>
     */
    public AutoExposureNode() {
        super(
                "auto_exposure",                                // 唯一标识符（kebab-case）
                "Auto Exposure (自动曝光)",                      // 显示名称
                PipelineNode.Category.POST_PROCESS,            // 分类：后处理阶段
                145,                                           // 优先级（色调映射之前）
                new String[]{"gbuffer_geometry"}               // 依赖：G-Buffer 几何节点
        );
    }

    // ==================== PipelineNode 核心方法 ====================

    /**
     * 执行自动曝光计算
     * <p>
     * 每帧调用一次的热路径方法。完整流程：
     * <ol>
     *   <li>短路检查：enabled == false 时直接返回输入纹理</li>
     *   <li>输入校验：至少需要颜色纹理 1 张</li>
     *   <li>亮度直方图：64-bin 直方图</li>
     *   <li>平均亮度：加权求和</li>
     *   <li>目标曝光计算</li>
     *   <li>时域适应</li>
     *   <li>钳制曝光值</li>
     *   <li>应用曝光</li>
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
     * - 1080p 目标: < 0.5ms (Compute Shader)
     */
    @Override
    public long execute(RenderContext context, long... inputResources) {
        // 短路：禁用时直接传递输入
        if (!enabled) return passThrough(inputResources);

        // 输入校验
        if (inputResources == null || inputResources.length < 1) {
            LOGGER.warning("[AutoExposure] 输入资源不足: 需要颜色纹理 1 张, "
                    + "实际收到 " + (inputResources == null ? 0 : inputResources.length) + " 张");
            return 0L;
        }

        long startTimeNanos = System.nanoTime();

        // 快照读取 volatile 参数（一次读取，避免多次读不一致）
        float curTargetLuminance = this.targetLuminance;
        float curAdaptationRate = this.adaptationRate;
        float curMinExposure = this.minExposure;
        float curMaxExposure = this.maxExposure;
        int curMeteringMode = this.meteringMode;
        float curPrevExposure = this.prevExposure;

        // TODO: 实现 GPU Auto Exposure
        // 1. 亮度直方图：计算场景亮度的 64-bin 直方图
        //    使用 Compute Shader 并行计算每个像素的 luminance
        //    luminance = 0.2126 * R + 0.7152 * G + 0.0722 * B
        //    将结果分入 64 个 bin（对数分布，覆盖 10^-6 ~ 10^2）
        //
        // 2. 平均亮度：根据测光模式加权求和
        //    mode 0 (平均): avgLum = sum(luminance) / pixelCount
        //    mode 1 (点测光): avgLum = luminance at screen center (5% area)
        //    mode 2 (中央重点): avgLum = weighted sum, center pixels weight higher
        //
        // 3. 目标曝光：exposure = targetLuminance / avgLuminance
        //    避免除零：avgLuminance = max(avgLuminance, 0.0001)
        //
        // 4. 时域适应：lerp(prevExposure, targetExposure, 1 - exp(-dt * adaptationRate))
        //    dt = frameDeltaTime (秒)
        //    adaptedExposure = prevExposure + (targetExposure - prevExposure) * (1 - exp(-dt * adaptationRate))
        //
        // 5. 钳制：clamp(exposure, minExposure, maxExposure)
        //    finalExposure = max(minExposure, min(maxExposure, adaptedExposure))
        //
        // 6. 应用：output = input * exposure
        //    每个像素颜色乘以最终曝光值
        //
        // 更新上一帧曝光值
        // this.prevExposure = finalExposure;

        long elapsedMicros = (System.nanoTime() - startTimeNanos) / 1000;
        LOGGER.fine(String.format(
                "[AutoExposure] 完成 | targetLum=%.2f rate=%.2f minExp=%.1f maxExp=%.1f mode=%d prevExp=%.2f | %.1fμs",
                curTargetLuminance, curAdaptationRate, curMinExposure, curMaxExposure,
                curMeteringMode, curPrevExposure, elapsedMicros
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
                "[AutoExposure] 初始化成功 | targetLum=%.2f rate=%.2f minExp=%.1f maxExp=%.1f mode=%d",
                this.targetLuminance, this.adaptationRate, this.minExposure, this.maxExposure, this.meteringMode
        ));
        return true;
    }

    /**
     * 节点资源释放钩子
     * <p>
     * 重置上一帧曝光值。
     */
    @Override
    protected void onDispose() {
        this.prevExposure = 1.0f;
        LOGGER.fine("[AutoExposure] 资源已释放");
    }

    // ==================== 参数 Setter ====================

    /**
     * 设置是否启用自动曝光
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
     * 设置目标亮度
     * <p>
     * 值会被钳制到有效范围 [{@value #TARGET_LUMINANCE_MIN}, {@value #TARGET_LUMINANCE_MAX}]。
     *
     * @param v 目标亮度（0.01 ~ 1.0，18% 灰 = 0.18）
     */
    public void setTargetLuminance(float v) {
        this.targetLuminance = Math.max(TARGET_LUMINANCE_MIN, Math.min(TARGET_LUMINANCE_MAX, v));
    }

    /**
     * 设置适应速率
     * <p>
     * 值会被钳制到有效范围 [{@value #ADAPTATION_RATE_MIN}, {@value #ADAPTATION_RATE_MAX}]。
     *
     * @param v 适应速率（0.01 ~ 5.0，越大越快适应）
     */
    public void setAdaptationRate(float v) {
        this.adaptationRate = Math.max(ADAPTATION_RATE_MIN, Math.min(ADAPTATION_RATE_MAX, v));
    }

    /**
     * 设置最小曝光值
     * <p>
     * 值会被钳制到有效范围 [{@value #MIN_EXPOSURE_MIN}, {@value #MIN_EXPOSURE_MAX}]。
     *
     * @param v 最小曝光值（0.1 ~ 3.0）
     */
    public void setMinExposure(float v) {
        this.minExposure = Math.max(MIN_EXPOSURE_MIN, Math.min(MIN_EXPOSURE_MAX, v));
    }

    /**
     * 设置最大曝光值
     * <p>
     * 值会被钳制到有效范围 [{@value #MAX_EXPOSURE_MIN}, {@value #MAX_EXPOSURE_MAX}]。
     *
     * @param v 最大曝光值（3.0 ~ 16.0）
     */
    public void setMaxExposure(float v) {
        this.maxExposure = Math.max(MAX_EXPOSURE_MIN, Math.min(MAX_EXPOSURE_MAX, v));
    }

    /**
     * 设置测光模式
     * <p>
     * 值会被钳制到有效范围 [{@value #METERING_MODE_MIN}, {@value #METERING_MODE_MAX}]。
     *
     * @param v 测光模式（0=平均, 1=点测光, 2=中央重点）
     */
    public void setMeteringMode(int v) {
        this.meteringMode = Math.max(METERING_MODE_MIN, Math.min(METERING_MODE_MAX, v));
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
                "AutoExposureNode{enabled=%b targetLum=%.2f rate=%.2f minExp=%.1f maxExp=%.1f mode=%d prevExp=%.2f}",
                enabled, targetLuminance, adaptationRate, minExposure, maxExposure, meteringMode, prevExposure
        );
    }
}
