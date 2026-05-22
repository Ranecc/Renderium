// Renderium - 光影系统 v2.0
// Tonemap 节点 - HDR 色调映射后处理（ACES / Reinhard / Uncharted 2）
//
// 核心算法:
//   1. 曝光调整 color = hdrColor * exposure
//   2. 色调映射 根据 ToneMapType 压缩至 [0,1]
//   3. 饱和度调整 在 LDR 空间调整色彩饱和度
//   4. 对比度调整 围绕中灰点 (0.5) 进行对比度缩放
//   5. 暗角效果 基于屏幕坐标的径向衰减
//   6. Gamma 校正 output = pow(color, 1.0/gamma)
//
// 性能预算:
//   - 1080p 目标: < 0.3ms (Compute Shader)

package com.ranecc.renderium.feature.shader.pipeline.node.builtin.postprocess.tonemap;

import com.ranecc.renderium.domain.constant.VulkanConst;
import com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryPools;
import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.shader.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.shader.pipeline.node.PipelineNode;
import com.ranecc.renderium.infrastructure.gpu.ComputePipelineHelper;
import com.ranecc.renderium.infrastructure.gpu.FrameCommandContext;
import com.ranecc.renderium.infrastructure.gpu.PerFrameArena;
import com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.ranecc.renderium.infrastructure.gpu.RenderiumProfiler;

/**
 * 色调映射 (Tonemapping) 后处理节点
 * <p>
 * 将高动态范围 (HDR) 线性颜色映射到低动态范围 (LDR) 显示设备可呈现的 sRGB 范围。
 * 此节点位于 Bloom 泛光之后，是渲染管线中从 HDR 到屏幕输出的关键转换步骤。
 *
 * <h2>处理流水线</h2>
 * <pre>
 *   HDR 输入纹理 (来自 Bloom)
 *       │
 *       ▼
 *   ┌─────────────┐
 *   │  1. 曝光调整   │  color = hdrColor * exposure
 *   └──────┬──────┘
 *          ▼
 *   ┌─────────────┐
 *   │  2. 色调映射   │  根据 ToneMapType 选择曲线压缩
 *   └──────┬──────┘
 *          ▼
 *   ┌─────────────┐
 *   │  3. 饱和度调整  │  在 LDR 空间调整色彩饱和度
 *   └──────┬──────┘
 *          ▼
 *   ┌─────────────┐
 *   │  4. 对比度调整  │  围绕中灰点 (0.5) 进行对比度缩放
 *   └──────┬──────┘
 *          ▼
 *   ┌─────────────┐
 *   │  5. 暗角效果   │  基于屏幕坐标的径向衰减
 *   └──────┬──────┘
 *          ▼
 *   ┌─────────────┐
 *   │  6. Gamma校正  │  output = pow(color, 1.0/gamma)
 *   └──────┬──────┘
 *          ▼
 *      LDR 屏幕输出
 * </pre>
 *
 * <h2>支持的三种色调映射曲线</h2>
 * <table border="1">
 *   <tr><th>类型</th><th>公式</th><th>特点</th></tr>
 *   <tr>
 *     <td>{@code ACES}</td>
 *     <td>(x*(2.51x+0.03)) / (x*(2.43x+0.59)+0.14)</td>
 *     <td>电影级曲线，肩部过渡平滑，高光保留好</td>
 *   </tr>
 *   <tr>
 *     <td>{@code REINHARD}</td>
 *     <td>x / (x + 1)</td>
 *     <td>简单全局映射，计算高效但会压暗整体画面</td>
 *   </tr>
 *   <tr>
 *     <td>{@code UNCHARTED2}</td>
 *     <td>Uncharted 2 Filmic Hable 曲线</td>
 *     <td>游戏常用，肩部和趾部都有良好过渡</td>
 *   </tr>
 * </table>
 *
 * <h2>依赖关系</h2>
 * <ul>
 *   <li>依赖节点: {@code "bloom"} — 接收 Bloom 处理后的 HDR 颜色纹理作为输入</li>
 * </ul>
 *
 * <h2>性能说明</h2>
 * <p>
 * 此节点为全屏后处理 pass，在 GPU 着色器中逐像素执行。
 * 所有参数均为 volatile 字段，支持运行时热修改而无需重建管线。
 * </p>
 *
 * @see AbstractPipelineNode
 * @see PipelineNode.Category#POST_PROCESS
 * @since 2.1.0
 */
public class Tonemap extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(Tonemap.class.getName());

    /** FrameCommandContext 节点 ID，用于帧级共享 Command Buffer */
    private static final int NODE_ID = 5;

    /**
     * Shader key，镜像 shaders-src/ 目录结构
     * @see com.ranecc.renderium.feature.shader.ShaderPathResolver#resolveSPIRV(String)
     */
    @Override
    protected String shaderKey() {
        return "pipeline/postprocess/tonemap/tonemap";
    }

    // ==================== 常量定义 ====================

    /** 曝光值下界 */
    private static final float EXPOSURE_MIN = 0.1f;
    /** 曝光值上界 */
    private static final float EXPOSURE_MAX = 5.0f;
    /** 默认曝光值 */
    private static final float EXPOSURE_DEFAULT = 1.0f;

    /** Gamma 下界 */
    private static final float GAMMA_MIN = 1.8f;
    /** Gamma 上界 */
    private static final float GAMMA_MAX = 2.4f;
    /** 默认 Gamma (sRGB 标准) */
    private static final float GAMMA_DEFAULT = 2.2f;

    /** 饱和度下界 */
    private static final float SATURATION_MIN = 0.5f;
    /** 饱和度上界 */
    private static final float SATURATION_MAX = 1.5f;
    /** 默认饱和度（无调整） */
    private static final float SATURATION_DEFAULT = 1.0f;

    /** 对比度下界 */
    private static final float CONTRAST_MIN = 0.8f;
    /** 对比度上界 */
    private static final float CONTRAST_MAX = 1.5f;
    /** 默认对比度（无调整） */
    private static final float CONTRAST_DEFAULT = 1.0f;

    /** 暗角强度下界 */
    private static final float VIGNETTE_MIN = 0.0f;
    /** 暗角强度上界 */
    private static final float VIGNETTE_MAX = 0.5f;
    /** 默认暗角强度 */
    private static final float VIGNETTE_DEFAULT = 0.15f;

    // ==================== ACES 曲线常量 ====================

    /** ACES: 分子线性系数 a = 2.51 */
    private static final float ACES_A = 2.51f;
    /** ACES: 分子常数项 b = 0.03 */
    private static final float ACES_B = 0.03f;
    /** ACES: 分母线性系数 c = 2.43 */
    private static final float ACES_C = 2.43f;
    /** ACES: 分母一次项 d = 0.59 */
    private static final float ACES_D = 0.59f;
    /** ACES: 分母常数 e = 0.14 */
    private static final float ACES_E = 0.14f;

    // ==================== Uncharted 2 曲线常量 ====================

    /** Uncharted 2: A = 0.15 (肩部强度) */
    private static final float UC2_A = 0.15f;
    /** Uncharted 2: B = 0.50 (线性区) */
    private static final float UC2_B = 0.50f;
    /** Uncharted 2: C = 0.10 (肩部偏移) */
    private static final float UC2_C = 0.10f;
    /** Uncharted 2: D = 0.20 (趾部强度) */
    private static final float UC2_D = 0.20f;
    /** Uncharted 2: E = 0.02 (趾部偏移) */
    private static final float UC2_E = 0.02f;
    /** Uncharted 2: F = 0.30 (白点缩放) */
    private static final float UC2_F = 0.30f;

    /** Uncharted 2: 白点值 (用于归一化) */
    private static final float UC2_WHITE_SCALE = uc2Curve(11.2f);

    // ==================== GPU Pipeline 资源 ====================

    /** Vulkan Compute Pipeline 句柄，0 表示未创建 */
    private volatile long computePipeline = 0L;
    private volatile long pipelineLayout = 0L;
    private volatile long descriptorSet = 0L;
    private volatile long outputImage = 0L;
    private volatile long outputImageView = 0L;
    private volatile int lastOutputWidth = 0;
    private volatile int lastOutputHeight = 0;

    // ==================== 动态参数 (volatile, 支持热修改) ====================

    /**
     * 曝光补偿值
     * <p>
     * 控制整体亮度。值越大画面越亮，值越小越暗。
     * 应用公式: {@code color = hdrColor * exposure}
     *
     * 【取值范围】0.1 ~ 5.0, 【默认值】1.0
     */
    private volatile float exposure = EXPOSURE_DEFAULT;

    /**
     * Gamma 校正指数
     * <p>
     * 用于将线性颜色转换为非线性显示空间。
     * sRGB 标准值为 2.2。
     * 应用公式: {@code output = pow(color, 1.0 / gamma)}
     *
     * 【取值范围】1.8 ~ 2.4, 【默认值】2.2 (sRGB)
     */
    private volatile float gamma = GAMMA_DEFAULT;

    /**
     * 色调映射曲线类型
     * <p>
     * 决定使用哪种算法将 HDR 值压缩到 [0,1] 范围。
     *
     * 【默认值】{@link ToneMapType#ACES}
     */
    private volatile ToneMapType tonemapType = ToneMapType.ACES;

    /**
     * 饱和度倍率
     * <p>
     * 在 LDR 空间对颜色进行饱和度缩放。
     * 计算方式: 将 RGB 转换为亮度，然后在原色与灰度之间插值。
     *
     * 【取值范围】0.5 ~ 1.5, 【默认值】1.0 (无变化)
     */
    private volatile float saturation = SATURATION_DEFAULT;

    /**
     * 对比度倍率
     * <p>
     * 围绕中灰点 (0.5) 进行缩放，增强或减弱明暗差异。
     * 公式: {@code result = (color - 0.5) * contrast + 0.5}
     *
     * 【取值范围】0.8 ~ 1.5, 【默认值】1.0 (无变化)
     */
    private volatile float contrast = CONTRAST_DEFAULT;

    /**
     * 暗角 (Vignette) 效果强度
     * <p>
     * 使画面边缘逐渐变暗，模拟真实相机镜头的渐晕现象。
     * 基于像素到屏幕中心的归一化距离进行余弦衰减:
     * {@code factor = 1.0 - vignetteStrength * smoothstep(inner, outer, dist)}
     *
     * 【取值范围】0.0 ~ 0.5, 【默认值】0.15 (轻微暗角)
     */
    private volatile float vignetteStrength = VIGNETTE_DEFAULT;

    // ==================== 内部枚举 ====================

    /**
     * 色调映射曲线类型枚举
     * <p>
     * 定义三种主流色调映射算法，各有不同的视觉特性和适用场景。
     */
    public enum ToneMapType {
        /**
         * ACES (Academy Color Encoding System) Filmic 曲线
         * <p>
         * 电影工业标准曲线，由 Academy of Motion Picture Arts and Sciences 制定。
         * 具有良好的肩部 (shoulder roll-off)，高光区域过渡自然，
         * 能够保留更多的高动态范围细节，是目前游戏和电影渲染的首选方案。
         *
         * <h3>数学公式：</h3>
         * <pre>
         *              x * (A*x + B)
         * f(x) = ─────────────────
         *         x * (C*x + D) + E
         *
         * 其中: A=2.51, B=0.03, C=2.43, D=0.59, E=0.14
         * </pre>
         *
         * <h3>特点：</h3>
         * <ul>
         *   <li>高光区域有柔和的肩部滚降</li>
         *   <li>中间调保持良好对比度</li>
         *   <li>暗部细节保留较好</li>
         *   <li>计算量适中（约 7 次乘法 + 4 次加法）</li>
         * </ul>
         */
        ACES,

        /**
         * Reinhard 色调映射曲线
         * <p>
         * 由 Erik Reinhard 于 2002 年提出的经典全局色调映射算子。
         * 是最简单的 HDR→LDR 映射方法之一，基于除法将所有值压缩到 [0,1]。
         *
         * <h3>数学公式：</h3>
         * <pre>
         *           x
         * f(x) = ──────
         *         x + 1
         * </pre>
         *
         * <h3>特点：</h3>
         * <ul>
         *   <li>实现极其简单，仅 1 次加法 + 1 次除法</li>
         *   <li>保证输出严格在 [0, 1] 范围内</li>
         *   <li>缺点：会均匀压缩所有亮度，导致画面偏"灰"</li>
         *   <li>缺乏肩部控制，高光截断生硬</li>
         *   <li>适合快速原型验证或低端设备</li>
         * </ul>
         */
        REINHARD,

        /**
         * Uncharted 2 Filmic (Hable) 曲线
         * <p>
         * 由 John Hable 为《神秘海域 2》(Uncharted 2) 开发的电影级色调映射。
         * 通过精心设计的有理函数同时模拟胶片的肩部 (shoulder) 和趾部 (toe) 特性。
         *
         * <h3>数学公式：</h3>
         * <pre>
         *                        (x*(A*x+C*B)+D*E)
         *   shoulder(x) = ─────────────────────────
         *                   (x*(A*x+B)+D*F)
         *
         *                    shoulder(x) - E/F
         *   f(x) = ─────────────────────────────
         *            shoulder(whitePoint) - E/F
         *
         * 其中: A=0.15, B=0.50, C=0.10, D=0.20, E=0.02, F=0.30
         *       whitePoint = 11.2
         * </pre>
         *
         * <h3>特点：</h3>
         * <ul>
         *   <li>近似模拟 Kodak 电影胶片的响应曲线</li>
         *   <li>肩部和趾部都有良好过渡，视觉质量优秀</li>
         *   <li>曾被大量 AAA 游戏采用（如《战地3》《使命召唤》）</li>
         *   <li>计算量较大（约 12 次乘法 + 多次除法）</li>
         *   <li>白点归一化确保最大亮度正确映射</li>
         * </ul>
         */
        UNCHARTED2
    }

    // ==================== 构造方法 ====================

    /**
     * 构造 Tonemap 节点
     * <p>
     * 配置节点元信息：ID、显示名称、分类、优先级和依赖关系。
     * 优先级设为 170，位于 Bloom (200) 之前执行顺序的下游位置。
     */
    public Tonemap() {
        super(
                "tonemap",                                    // 节点唯一标识符"
                "Tonemapping (色调映射)",                     // UI 显示名称"
                PipelineNode.Category.POST_PROCESS,           // 后处理阶段
                170,                                          // 执行优先级
                new String[]{"bloom"}                         // 依赖 Bloom 节点的 HDR 输出
        );
    }

    // ==================== 参数 Setter 方法 ====================

    /**
     * 设置曝光补偿值
     *
     * 【方法参数】
     * @param value float - 目标曝光值，将被钳位到 [0.1, 5.0] 范围
     */
    public void setExposure(float value) {
        this.exposure = clamp(value, EXPOSURE_MIN, EXPOSURE_MAX);
    }

    /**
     * 设置 Gamma 校正指数
     *
     * 【方法参数】
     * @param value float - 目标 gamma 值，将被钳位到 [1.8, 2.4] 范围
     */
    public void setGamma(float value) {
        this.gamma = clamp(value, GAMMA_MIN, GAMMA_MAX);
    }

    /**
     * 设置色调映射曲线类型
     *
     * 【方法参数】
     * @param type ToneMapType - 目标曲线类型 (ACES/REINHARD/UNCHARTED2)
     */
    public void setTonemapType(ToneMapType type) {
        if (type != null) {
            this.tonemapType = type;
        }
    }

    /**
     * 设置饱和度倍率
     *
     * 【方法参数】
     * @param value float - 目标饱和度，将被钳位到 [0.5, 1.5] 范围
     */
    public void setSaturation(float value) {
        this.saturation = clamp(value, SATURATION_MIN, SATURATION_MAX);
    }

    /**
     * 设置对比度倍率
     *
     * 【方法参数】
     * @param value float - 目标对比度，将被钳位到 [0.8, 1.5] 范围
     */
    public void setContrast(float value) {
        this.contrast = clamp(value, CONTRAST_MIN, CONTRAST_MAX);
    }

    /**
     * 设置暗角效果强度
     *
     * 【方法参数】
     * @param value float - 目标暗角强度，将被钳位到 [0.0, 0.5] 范围
     */
    public void setVignetteStrength(float value) {
        this.vignetteStrength = clamp(value, VIGNETTE_MIN, VIGNETTE_MAX);
    }

    // ==================== 参数 Getter 方法 ====================

    /** 获取当前曝光值 */
    public float getExposure() { return exposure; }

    /** 获取当前 Gamma 值 */
    public float getGamma() { return gamma; }

    /** 获取当前色调映射类型 */
    public ToneMapType getTonemapType() { return tonemapType; }

    /** 获取当前饱和度 */
    public float getSaturation() { return saturation; }

    /** 获取当前对比度 */
    public float getContrast() { return contrast; }

    /** 获取当前暗角强度 */
    public float getVignetteStrength() { return vignetteStrength; }

    // ==================== 核心执行逻辑 ====================

    /**
     * 执行色调映射处理
     * <p>
     * 完整的 HDR → LDR 转换流水线，按以下顺序依次应用：
     * <ol>
     *   <li>输入校验：检查 inputResources 是否包含有效的 HDR 纹理句柄</li>
     *   <li>曝光调整：将 HDR 颜色乘以曝光系数</li>
     *   <li>色调映射：根据当前 tonemapType 选择对应曲线压缩至 [0,1]</li>
     *   <li>饱和度调整：在 LDR 空间进行色彩饱和度缩放</li>
     *   <li>对比度调整：围绕中灰点 (0.5) 进行对比度缩放</li>
     *   <li>暗角效果：基于屏幕坐标的径向衰减</li>
     *   <li>Gamma 校正：从线性空间转换到 sRGB 显示空间</li>
     * </ol>
     *
     * 【方法参数】
     * @param context RenderContext - 当前帧渲染上下文（含 Vulkan 设备/命令缓冲等）
     * @param inputResources long... - 输入资源数组：
     *                          [0] = HDR 颜色纹理句柄（来自 Bloom 节点输出）
     *
     * 【返回值】
     * @return long - LDR 屏幕输出纹理句柄；输入无效时返回 0L
     *
     * 【性能特征】
     * - 全屏 quad pass，每像素执行完整着色器计算
     * - 所有分支通过 uniform 统一控制，避免 GPU 动态分支开销
     * - 总计约 20-40 次 ALU 操作/像素（取决于所选曲线）
     */
    @Override
    public long execute(RenderContext context, long... inputResources) {
        // 输入校验
        if (inputResources == null || inputResources.length == 0) {
            LOGGER.warning("[Tonemap] 输入资源为空，跳过处理");
            return 0L;
        }

        long hdrTexture = inputResources[0];
        if (hdrTexture == 0L) {
            LOGGER.warning("[Tonemap] HDR 纹理句柄无效 (0)");
            return 0L;
        }

        long startTimeNanos = System.nanoTime();
        RenderiumProfiler.recordStart(5);

        // 快照读取 volatile 参数（一次读取，避免多次读不一致）
        float curExposure = this.exposure;
        float curGamma = this.gamma;
        int curTonemapType = this.tonemapType.ordinal();
        float curSaturation = this.saturation;
        float curContrast = this.contrast;
        float curVignetteStrength = this.vignetteStrength;

        ensurePipeline();
        if (computePipeline == 0L) return hdrTexture;

        ensureOutputImage(context);
        if (outputImage == 0L || outputImageView == 0L) return hdrTexture;

        try {
            long dev = VulkanDeviceHolder.getInstance().getDevice();
            if (dev == 0L || descriptorSet == 0L) return hdrTexture;

            // 更新 descriptor: binding 0 = HDR 输入 (readonly), binding 1 = 输出 (writeonly)
            ComputePipelineHelper.updateStorageImageDescriptor(dev, descriptorSet, 0, hdrTexture, 0L);
            ComputePipelineHelper.updateStorageImageDescriptor(dev, descriptorSet, 1, outputImageView, 0L);

            // 构建 PushConstants: 32 bytes
            //   offset 0:   exposure       (float)
            //   offset 4:   gamma          (float)
            //   offset 8:   tonemapType    (int)
            //   offset 12:  saturation     (float)
            //   offset 16:  contrast       (float)
            //   offset 20:  vignetteStrength (float)
            //   offset 24:  pad0           (float)
            //   offset 28:  pad1           (float)
            MemorySegment params = PerFrameArena.allocate(32L);
            params.set(ValueLayout.JAVA_FLOAT, 0, curExposure);
            params.set(ValueLayout.JAVA_FLOAT, 4, curGamma);
            params.set(ValueLayout.JAVA_INT, 8, curTonemapType);
            params.set(ValueLayout.JAVA_FLOAT, 12, curSaturation);
            params.set(ValueLayout.JAVA_FLOAT, 16, curContrast);
            params.set(ValueLayout.JAVA_FLOAT, 20, curVignetteStrength);
            // pad0 (offset 24) 和 pad1 (offset 28) 保持 0

            // Vulkan compute dispatch
            long cmdBuf = FrameCommandContext.beginNodeCB(NODE_ID);
            if (cmdBuf != 0L) {
                VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf, 1, computePipeline);
                MemorySegment dsPtr = PerFrameArena.allocateLongs(1);
                dsPtr.set(ValueLayout.JAVA_LONG, 0, descriptorSet);
                VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets", cmdBuf, 1, pipelineLayout, 0, 1, dsPtr.address(), 0, 0L);
                VulkanAPIRegistry.invoke("vkCmdPushConstants", cmdBuf, pipelineLayout, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT, 0, 32, params.address());

                int w = (context.getWidth() + 7) / 8;
                int h = (context.getHeight() + 7) / 8;
                VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, w, h, 1);
                FrameCommandContext.endNodeCB(NODE_ID);
            }
        } catch (Throwable t) {
            LOGGER.fine("[Tonemap] dispatch 失败: " + t.getMessage());
        }

        RenderiumProfiler.recordEnd(5);
        double elapsedMicros = (System.nanoTime() - startTimeNanos) / 1000.0;
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(String.format(
                    "[Tonemap] 完成 | type=%s exp=%.2f gamma=%.2f sat=%.2f con=%.2f vig=%.2f | %.1f\u00b5s",
                tonemapType.name(), curExposure, curGamma, curSaturation,
                curContrast, curVignetteStrength, elapsedMicros
        ));
        }

        return outputImageView;
    }

    // ==================== 初始化与释放钩子 ====================

    @Override
    protected boolean onInitialize(RenderContext context) {
        LOGGER.info(String.format(
                "[Tonemap] 初始化成功 | type=%s exp=%.2f gamma=%.2f",
                this.tonemapType.name(), this.exposure, this.gamma
        ));
        return true;
    }

    @Override
    protected void onDispose() {
        long dev = VulkanDeviceHolder.getInstance().getDevice();
        if (dev != 0L) {
            if (computePipeline != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroyPipeline", dev, computePipeline, 0L); } catch (Throwable ignored) {}
                computePipeline = 0L;
            }
            if (pipelineLayout != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroyPipelineLayout", dev, pipelineLayout, 0L); } catch (Throwable ignored) {}
                pipelineLayout = 0L;
            }
            if (outputImageView != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroyImageView", dev, outputImageView, 0L); } catch (Throwable ignored) {}
                outputImageView = 0L;
            }
            if (outputImage != 0L) {
                var mgr = VulkanGPUResourceManager.getInstance();
                mgr.releaseResource(new VulkanGPUResourceManager.GpuResource(
                        outputImage, 0L, lastOutputWidth, lastOutputHeight,
                        VulkanConst.FORMAT_R8G8B8A8_UNORM, VulkanGPUResourceManager.ResourceType.IMAGE));
                outputImage = 0L;
            }
        }
        lastOutputWidth = 0;
        lastOutputHeight = 0;
        LOGGER.fine("[Tonemap] 资源已释放");
    }

    // ==================== 辅助方法 ====================

    /**
     * 确保 Compute Pipeline 已创建
     * <p>
     * 双重检查锁定（DCL），synchronized 确保单次创建。
     * Tonemap Binding 配置:
     *   binding 0 = STORAGE_IMAGE (HDR 输入, readonly)
     *   binding 1 = STORAGE_IMAGE (输出纹理, writeonly)
     * PushConstant: size=32, offset=0
     */
    private void ensurePipeline() {
        if (computePipeline != 0L) return;
        synchronized (this) {
            if (computePipeline != 0L) return;
            try {
                byte[] spirv = resolveSPIRV();
                if (spirv == null || spirv.length == 0) {
                    LOGGER.warning("[Tonemap] SPIR-V 着色器加载失败: " + shaderKey());
                    return;
                }
                var bindings = new ComputePipelineHelper.Binding[]{
                    new ComputePipelineHelper.Binding(0, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                    new ComputePipelineHelper.Binding(1, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                };
                var pc = new ComputePipelineHelper.PushConstant(0, 32, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT);
                var r = ComputePipelineHelper.createComputePipeline(spirv, bindings, pc);
                if (r != null) {
                    computePipeline = r.pipeline();
                    pipelineLayout = r.pipelineLayout();
                    descriptorSet = r.descriptorSet();
                    LOGGER.fine("[Tonemap] Compute Pipeline 创建成功: " + shaderKey());
                }
            } catch (Exception e) {
                LOGGER.warning("[Tonemap] Pipeline 创建异常: " + e.getMessage());
            }
        }
    }

    /**
     * 确保输出存储图像已创建
     * <p>
     * 当窗口尺寸变化时自动重建 RGBA8 存储图像。
     * 使用 VulkanGPUResourceManager 创建和管理资源生命周期。
     */
    private void ensureOutputImage(RenderContext ctx) {
        int w = ctx.getWidth();
        int h = ctx.getHeight();
        if (w == lastOutputWidth && h == lastOutputHeight && outputImage != 0L) return;

        long dev = VulkanDeviceHolder.getInstance().getDevice();
        if (dev == 0L) return;

        // 销毁旧资源（如有）
        if (outputImageView != 0L) {
            try { VulkanAPIRegistry.invoke("vkDestroyImageView", dev, outputImageView, 0L); } catch (Throwable ignored) {}
            outputImageView = 0L;
        }
        if (outputImage != 0L) {
            var mgr = VulkanGPUResourceManager.getInstance();
            mgr.releaseResource(new VulkanGPUResourceManager.GpuResource(
                    outputImage, 0L, lastOutputWidth, lastOutputHeight,
                    VulkanConst.FORMAT_R8G8B8A8_UNORM, VulkanGPUResourceManager.ResourceType.IMAGE));
            outputImage = 0L;
        }

        // 创建新的 RGBA8 存储图像
        var mgr = VulkanGPUResourceManager.getInstance();
        int usage = VulkanConst.IMAGE_USAGE_STORAGE_BIT | VulkanConst.IMAGE_USAGE_SAMPLED_BIT;
        var resource = mgr.createImage(w, h, VulkanConst.FORMAT_R8G8B8A8_UNORM, usage, VmaMemoryPools.PoolType.RENDER_TARGET);
        if (resource == null || !resource.isValid()) {
            LOGGER.warning("[Tonemap] 输出图像创建失败 [" + w + "x" + h + "]");
            return;
        }
        outputImage = resource.handle;

        // 创建 ImageView
        long view = mgr.createView(outputImage, VulkanConst.FORMAT_R8G8B8A8_UNORM, VulkanConst.IMAGE_ASPECT_COLOR_BIT);
        if (view == 0L) {
            LOGGER.warning("[Tonemap] ImageView 创建失败");
            mgr.releaseResource(resource);
            outputImage = 0L;
            return;
        }
        outputImageView = view;

        lastOutputWidth = w;
        lastOutputHeight = h;
        LOGGER.fine("[Tonemap] 输出图像已创建 [" + w + "x" + h + "]");
    }

    /**
     * 从 classpath 加载 SPIR-V 二进制资源
     */
    private static byte[] loadSPIRV(String path) {
        try (var is = Tonemap.class.getResourceAsStream(path)) {
            if (is == null) return null;
            return is.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 色调映射曲线算法 (CPU 端参考实现) ====================
    // 以下方法提供 CPU 端的精确数学实现，可用于：
    //   - 单元测试验证
    //   - 着色器代码生成参考
    //   - 调试/可视化工具

    /**
     * ACES Filmic 色调映射曲线
     * <p>
     * Academy Color Encoding System 近似曲线。
     * 由 Krzysztof Narkowicz 提出的简化版本，
     * 广泛应用于现代游戏引擎和离线渲染器。
     *
     * <h3>数学公式：</h3>
     * <pre>
     *                 x * (A*x + B)
     * f(x) = ──────────────────────
     *         x * (C*x + D) + E
     *
     * 常数: A=2.51, B=0.03, C=2.43, D=0.59, E=0.14
     * </pre>
     *
     * 【方法参数】
     * @param x float - 输入 HDR 值 (线性空间，通常 > 1.0)
     *
     * 【返回值】
     * @return float - 映射后的 LDR 值 [0, ~1]
     */
    public static float acesFilmic(float x) {
        // 分子: x * (A*x + B)
        float numerator = x * (ACES_A * x + ACES_B);
        // 分母: x * (C*x + D) + E
        float denominator = x * (ACES_C * x + ACES_D) + ACES_E;
        // 有理函数求值
        return numerator / denominator;
    }

    /**
     * ACES Filmic 向量化版本 (RGB 三通道同时处理)
     *
     * 【方法参数】
     * @param color float[3] - RGB 输入颜色 (HDR 线性空间)
     *
     * 【返回值】
     * @return float[3] - 映射后的 LDR 颜色
     */
    public static float[] acesFilmicVec3(float[] color) {
        return new float[]{
                acesFilmic(color[0]),
                acesFilmic(color[1]),
                acesFilmic(color[2])
        };
    }

    /**
     * Reinhard 色调映射曲线
     * <p>
     * 经典的全局色调映射算子，基于简单除法将所有正值压缩到 (0, 1)。
     * 优点是实现极简且数值稳定；缺点是缺乏肩部控制，高光区域表现平淡。
     *
     * <h3>数学公式：</h3>
     * <pre>
     *           x
     * f(x) = ──────
     *         x + 1
     * </pre>
     *
     * 【方法参数】
     * @param x float - 输入 HDR 值
     *
     * 【返回值】
     * @return float - 映射后的值 (0, 1)
     */
    public static float reinhard(float x) {
        return x / (x + 1.0f);
    }

    /**
     * Reinhard 向量化版本 (RGB 三通道)
     *
     * 【方法参数】
     * @param color float[3] - RGB 输入颜色
     *
     * 【返回值】
     * @return float[3] - 映射后的颜色
     */
    public static float[] reinhardVec3(float[] color) {
        return new float[]{
                reinhard(color[0]),
                reinhard(color[1]),
                reinhard(color[2])
        };
    }

    /**
     * Uncharted 2 Filmic (Hable) 色调映射曲线
     * <p>
     * John Hable 为《神秘海域 2》开发的摄影级曲线，
     * 通过有理函数同时模拟胶片响应的肩部 (shoulder) 和趾部 (toe)。
     * 结果经过白点归一化，确保白场正确映射到接近 1.0。
     *
     * <h3>数学公式：</h3>
     * <pre>
     *   内部曲线:
     *                  x*(A*x + C*B) + D*E
     *   t(x) = ────────────────────────────
     *            x*(A*x + B) + D*F
     *
     *   最终映射:
     *               t(x) - E/F
     *   f(x) = ─────────────────
     *           t(whitePoint) - E/F
     *
     *   常数: A=0.15, B=0.50, C=0.10, D=0.20, E=0.02, F=0.30
     *   白点: whitePoint = 11.2
     * </pre>
     *
     * 【方法参数】
     * @param x float - 输入 HDR 值
     *
     * 【返回值】
     * @return float - 归一化后的 LDR 值 [0, ~1]
     */
    public static float uncharted2Filmic(float x) {
        // 计算内部曲线值
        float tx = uc2Curve(x);
        // 白点处的曲线值 (用于归一化)
        float tWhite = UC2_WHITE_SCALE;
        // 偏移并归一化: (t(x) - E/F) / (t(white) - E/F)
        float offset = UC2_E / UC2_F;
        return Math.max(0.0f, (tx - offset) / (tWhite - offset));
    }

    /**
     * Uncharted 2 向量化版本 (RGB 三通道)
     *
     * 【方法参数】
     * @param color float[3] - RGB 输入颜色
     *
     * 【返回值】
     * @return float[3] - 映射后的颜色
     */
    public static float[] uncharted2FilmicVec3(float[] color) {
        return new float[]{
                uncharted2Filmic(color[0]),
                uncharted2Filmic(color[1]),
                uncharted2Filmic(color[2])
        };
    }

    // ==================== 图像调整辅助算法 ====================

    /**
     * 饱和度调整
     * <p>
     * 通过在原始颜色与对应的灰度值之间进行线性插值来调整饱和度。
     * saturation = 1.0 时保持不变；< 1.0 降低饱和度（趋近灰度）；> 1.0 增强饱和度。
     *
     * <h3>数学公式：</h3>
     * <pre>
     *   lum  = dot(rgb, (0.2126, 0.7152, 0.0722))   // Rec.709 亮度
     *   result = mix((lum,lum,lum), rgb, saturation)
     * </pre>
     *
     * 【方法参数】
     * @param color  float[3] - 输入 LDR 颜色
     * @param sat    float   - 饱和度因子 (通常 0.5~1.5)
     *
     * 【返回值】
     * @return float[3] - 调整后的颜色
     */
    public static float[] applySaturation(float[] color, float sat) {
        // Rec.709 亮度系数 (人眼对绿色最敏感)
        float luminance = 0.2126f * color[0] + 0.7152f * color[1] + 0.0722f * color[2];
        // 线性插值: 灰度 <-> 原色
        float invSat = 1.0f - sat;
        return new float[]{
                luminance * invSat + color[0] * sat,
                luminance * invSat + color[1] * sat,
                luminance * invSat + color[2] * sat
        };
    }

    /**
     * 对比度调整
     * <p>
     * 围绕中灰点 (0.5) 进行缩放，使亮部更亮、暗部更暗。
     * contrast = 1.0 保持不变；> 1.0 增强对比度；< 1.0 降低对比度。
     *
     * <h3>数学公式：</h3>
     * <pre>
     *   result = (color - 0.5) * contrast + 0.5
     * </pre>
     *
     * 【方法参数】
     * @param color    float[3] - 输入颜色
     * @param contrast float   - 对比度因子 (通常 0.8~1.5)
     *
     * 【返回值】
     * @return float[3] - 调整后的颜色 (可能超出 [0,1]，需后续 clamp)
     */
    public static float[] applyContrast(float[] color, float contrast) {
        return new float[]{
                (color[0] - 0.5f) * contrast + 0.5f,
                (color[1] - 0.5f) * contrast + 0.5f,
                (color[2] - 0.5f) * contrast + 0.5f
        };
    }

    /**
     * 暗角 (Vignette) 效果计算
     * <p>
     * 基于像素到屏幕中心的归一化距离，使用 smoothstep 函数产生平滑的边缘衰减。
     * 模拟真实相机镜头的光学渐晕现象。
     *
     * <h3>数学公式：</h3>
     * <pre>
     *   uv_centered = uv * 2.0 - 1.0          // 归一化到 [-1, 1]
     *   dist = length(uv_centered)             // 到中心距离 [0, ~1.414]
     *   mask = 1.0 - strength * smoothstep(inner, outer, dist)
     *   result = color * mask
     * </pre>
     *
     * 【方法参数】
     * @param color    float[3] - 输入颜色
     * @param uv       float[2] - 屏幕 UV 坐标 [0,1]x[0,1]
     * @param strength float   - 暗角强度 (0.0~0.5)
     * @param inner    float   - 内半径 (暗角起始位置, 默认 0.4)
     * @param outer    float   - 外半径 (完全暗角位置, 默认 1.0)
     *
     * 【返回值】
     * @return float[3] - 应用暗角后的颜色
     */
    public static float[] applyVignette(float[] color, float[] uv, float strength,
                                         float inner, float outer) {
        // UV 居中并归一化到 [-1, 1]
        float cx = uv[0] * 2.0f - 1.0f;
        float cy = uv[1] * 2.0f - 1.0f;
        // 到中心的欧几里得距离
        float dist = (float) Math.sqrt(cx * cx + cy * cy);
        // Smoothstep 插值: 在 inner~outer 之间产生 0→1 的平滑过渡
        float mask = 1.0f - strength * smoothstep(inner, outer, dist);
        // 应用衰减
        return new float[]{
                color[0] * mask,
                color[1] * mask,
                color[2] * mask
        };
    }

    /**
     * Gamma 校正
     * <p>
     * 将线性颜色值转换到非线性显示空间 (如 sRGB)。
     * 这是色调映射的最后一步，确保输出颜色在显示器上正确呈现。
     *
     * <h3>数学公式：</h3>
     * <pre>
     *   output = pow(clamp(input, 0.0, 1.0), 1.0 / gamma)
     * </pre>
     *
     * 【方法参数】
     * @param color float[3] - 输入线性颜色
     * @param gamma float   - Gamma 值 (sRGB 标准为 2.2)
     *
     * 【返回值】
     * @return float[3] - Gamma 校正后的非线性颜色
     */
    public static float[] applyGammaCorrection(float[] color, float gamma) {
        float invGamma = 1.0f / gamma;
        return new float[]{
                (float) Math.pow(Math.max(0.0f, color[0]), invGamma),
                (float) Math.pow(Math.max(0.0f, color[1]), invGamma),
                (float) Math.pow(Math.max(0.0f, color[2]), invGamma)
        };
    }

    // ==================== 私有辅助方法 ====================

    /**
     * Uncharted 2 内部曲线 (未归一化的原始 Hable 函数)
     * <p>
     * 有理函数形式: (x*(A*x+C*B)+D*E) / (x*(A*x+B)+D*F)
     *
     * 【方法参数】
     * @param x float - 输入值
     *
     * 【返回值】
     * @return float - 原始曲线输出
     */
    private static float uc2Curve(float x) {
        float numerator   = x * (UC2_A * x + UC2_C * UC2_B) + UC2_D * UC2_E;
        float denominator = x * (UC2_A * x + UC2_B) + UC2_D * UC2_F;
        return numerator / denominator;
    }

    /**
     * 值钳位 (Clamp)
     * <p>
     * 将值限制在指定范围内。
     *
     * 【方法参数】
     * @param value float - 待钳位的值
     * @param min   float - 下界
     * @param max   float - 上界
     *
     * 【返回值】
     * @return float - 钳位后的值
     */
    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 平滑阶梯函数 (Smoothstep)
     * <p>
     * Ken Perlin 的 smoothstep 函数，在 edge0~edge1 之间产生
     * 平滑的 Hermite 插值 (C1 连续，端点导数为 0)。
     *
     * <h3>数学公式：</h3>
     * <pre>
     *   t = clamp((x - edge0) / (edge1 - edge0), 0, 1)
     *   result = t * t * (3 - 2*t)
     * </pre>
     *
     * 【方法参数】
     * @param edge0 float - 下边界 (结果 ≈ 0)
     * @param edge1 float - 上边界 (结果 ≈ 1)
     * @param x     float - 输入值
     *
     * 【返回值】
     * @return float - 平滑插值结果 [0, 1]
     */
    private static float smoothstep(float edge0, float edge1, float x) {
        // 归一化到 [0, 1]
        float t = clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        // Hermite 插值: 3t^2 - 2t^3
        return t * t * (3.0f - 2.0f * t);
    }
}
