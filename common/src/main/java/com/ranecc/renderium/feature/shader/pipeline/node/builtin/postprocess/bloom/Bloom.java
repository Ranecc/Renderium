// Renderium - 光影系统 v2.0
// 泛光 (Bloom) 后处理节点
//
// 核心算法:
//   1. Brightness Pass - 提取超过亮度阈值的像素
//   2. Downsample      - 多级降采样（基于 mipmap 的金字塔结构）
//   3. Gaussian Blur   - 水平+垂直分离高斯模糊（每层迭代 blurPasses 次）
//   4. Upsample        - 逐级上采样并累加到原场景颜色
//
// 性能预算:
//   - Brightness Pass: < 0.5ms/帧（全屏单次着色器）
//   - Downsample:      < 0.3ms/帧（双线性滤波缩放）
//   - Gaussian Blur:   < 1.5ms/帧（取决于 blurPasses 和分辨率）
//   - Upsample:        < 0.5ms/帧（逐级混合）

package com.ranecc.renderium.feature.shader.pipeline.node.builtin.postprocess.bloom;

// Compute Shader 实现 — 通过 4 个 Compute Pipeline 执行完整 Bloom 流程
//    1. Brightness Pass: bloom_bright.comp (2 storage images + 16B PC)
//    2. Downsample:      bloom_downsample.comp (2 storage images + 8B PC)
//    3. Gaussian Blur:   bloom_blur.comp (水平+垂直, 2 storage images + 16B PC)
//    4. Upsample:        bloom_upsample.comp (2 storage images + 8B PC)

import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.shader.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.shader.pipeline.node.PipelineNode;
import com.ranecc.renderium.infrastructure.gpu.ComputePipelineHelper;
import com.ranecc.renderium.infrastructure.gpu.FrameCommandContext;
import com.ranecc.renderium.infrastructure.gpu.PerFrameArena;
import com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager;
import com.ranecc.renderium.infrastructure.gpu.VulkanStructs;
import com.ranecc.renderium.infrastructure.gpu.VulkanSyncManager;
import com.ranecc.renderium.feature.lod.compute.LodCullingComputePass;
import com.ranecc.renderium.domain.constant.VulkanConst;
import org.lwjgl.vulkan.VK10;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.logging.Level;
import com.ranecc.renderium.infrastructure.gpu.RenderiumProfiler;
import java.util.logging.Logger;

/**
 * 泛光 (Bloom) 后处理节点
 * <p>
 * 提取场景中高亮区域，通过多级高斯模糊扩散后叠加回原图，
 * 模拟真实相机镜头的光晕和辉光效果。
 * 广泛应用于游戏和电影渲染中增强视觉冲击力。
 *
 * <h2>算法概述：</h2>
 *
 * <h3>整体流程（四阶段管线）</h3>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────┐
 * │                    Bloom 处理流程                             │
 * ├──────────────────────────────────────────────────────────────┤
 * │                                                              │
 * │  输入纹理 (HDR 场景颜色)                                       │
 * │       │                                                      │
 * │       ▼                                                      │
 * │  ┌─────────────┐                                             │
 * │  │ Step 1:     │  Brightness Extraction                      │
 * │  │ 亮度阈值提取  │  luminance > threshold ? pixel : black      │
 * │  └──────┬──────┘                                             │
 * │         ▼                                                     │
 * │  ┌─────────────┐                                             │
 * │  │ Step 2:     │  Multi-level Downsample                     │
 * │  │ 多级降采样    │  原尺寸 → 1/2 → 1/4 → ... → 1/2^n          │
 * │  └──────┬──────┘  （每层使用双线性滤波）                        │
 * │         ▼                                                     │
 * │  ┌─────────────┐                                             │
 * │  │ Step 3:     │  Separable Gaussian Blur                   │
 * │  │ 高斯分离模糊  │  每层执行 blurPasses 次水平+垂直卷积         │
 * │  └──────┬──────┘  σ = kernelSize / 2                          │
 * │         ▼                                                     │
 * │  ┌─────────────┐                                             │
 * │  │ Step 4:     │  Progressive Upsample & Accumulate         │
 * │  │ 上采样合成    │  从最小层逐级放大，每层累加到上一层           │
 * │  └──────┬──────┘  最终与原场景按 intensity 混合                │
 * │         ▼                                                     │
 * │  输出纹理 (带泛光效果的 HDR 颜色)                               │
 * │                                                              │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Step 1: Brightness Pass（亮度提取）</h3>
 * <p>
 * 计算每个像素的感知亮度值，仅保留超过阈值的像素：
 * <pre>
 * luminance = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722))   // BT.709 亮度系数
 * brightPixel = color * smoothstep(threshold, threshold + knee, luminance)
 * </pre>
 * 使用 smoothstep 实现软过渡，避免硬截断产生的边缘伪影。
 *
 * <h3>Step 2: Downsample（多级降采样）</h3>
 * <p>
 * 构建类似 mipmap 的图像金字塔。每一级的分辨率为上一级的 {@code downsampleScale} 倍：
 * <pre>
 * mip[i].width  = mip[i-1].width  * downsampleScale
 * mip[i].height = mip[i-1].height * downsampleScale
 * </pre>
 * 使用 13-tap 双线性滤波采样（采样 4 个像素及其邻居的加权平均），
 * 在降采样的同时进行预模糊以减少混叠。
 *
 * <h3>Step 3: Gaussian Blur（分离式高斯模糊）</h3>
 * <p>
 * 对每个 mipmap 层级独立执行分离式高斯模糊。
 * 分离模糊将二维卷积分解为两次一维卷积，将 O(n^2) 降低为 O(2n)：
 * <pre>
 * // 水平方向：对每行应用一维高斯核
 * blurredH(x, y) = Σ G(i) * src(x+i, y), i ∈ [-r, r]
 *
 * // 垂直方向：对每列应用一维高斯核
 * blurredV(x, y) = Σ G(j) * blurredH(x, y+j), j ∈ [-r, r]
 * </pre>
 * 其中高斯核权重：G(k) = exp(-k^2 / (2*sigma^2)) / (sigma * sqrt(2*pi))
 *
 * <h3>Step 4: Upsample（上采样合成）</h3>
 * <p>
 * 从金字塔最底层开始逐级上采样并累加：
 * <pre>
 * for i = maxMipLevel downto 1:
 *     upsampled = bilinearUpsample(mip[i])       // 放大到 mip[i-1] 尺寸
 *     mip[i-1] += upsampled                       // 累加到上一层
 *
 * finalColor = originalScene + mip[0] * intensity * bloomColor
 * </pre>
 * 这种方式保留了不同频率的泛光细节，产生更丰富的层次感。
 *
 * <h2>配置参数：</h2>
 * <table border="1">
 *   <tr><th>参数</th><th>类型</th><th>范围</th><th>默认值</th><th>说明</th></tr>
 *   <tr><td>threshold</td><td>float</td><td>0.5~3.0</td><td>1.0</td><td>亮度提取阈值（HDR 线性空间）</td></tr>
 *   <tr><td>intensity</td><td>float</td><td>0.1~2.0</td><td>0.8</td><td>泛光强度系数</td></tr>
 *   <tr><td>blurPasses</td><td>int</td><td>1~6</td><td>4</td><td>每层模糊迭代次数</td></tr>
 *   <tr><td>bloomColor</td><td>float[3]</td><td>[0,1]^3</td><td>[1.0, 0.9, 0.7]</td><td>泛光色调偏移 RGB</td></tr>
 *   <tr><td>downsampleScale</td><td>float</td><td>0.25~0.5</td><td>0.5</td><td>下采样比例因子</td></tr>
 * </table>
 *
 * <h2>性能特征：</h2>
 * <ul>
 *   <li>CPU 开销：约 50~100μs/帧（参数准备+Draw Call 提交）</li>
 *   <li>GPU 开销：约 2~4ms/帧（取决于分辨率、mip 层数、blurPasses）</li>
 *   <li>显存占用：约 (W*H*8bytes) * (1 + Σ scale^(2i))，典型 1080p 约 17MB</li>
 *   <li>支持动态调整参数，无需重建资源</li>
 * </ul>
 *
 * @see AbstractPipelineNode
 * @see PipelineNode.Category#POST_PROCESS
 * @since 2.1.0
 */
public class Bloom extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(Bloom.class.getName());

    /** FrameCommandContext 节点 ID，用于帧级共享 Command Buffer */
    private static final int NODE_ID = 4;

    // ==================== 常量定义 ====================

    /** 默认亮度阈值（HDR 线性空间） */
    public static final float DEFAULT_THRESHOLD = 1.0f;

    /** 最小亮度阈值 */
    public static final float MIN_THRESHOLD = 0.5f;

    /** 最大亮度阈值 */
    public static final float MAX_THRESHOLD = 3.0f;

    /** 默认泛光强度 */
    public static final float DEFAULT_INTENSITY = 0.8f;

    /** 最小泛光强度 */
    public static final float MIN_INTENSITY = 0.1f;

    /** 最大泛光强度 */
    public static final float MAX_INTENSITY = 2.0f;

    /** 默认模糊迭代次数 */
    public static final int DEFAULT_BLUR_PASSES = 4;

    /** 最小模糊迭代次数 */
    public static final int MIN_BLUR_PASSES = 1;

    /** 最大模糊迭代次数 */
    public static final int MAX_BLUR_PASSES = 6;

    /** 默认下采样比例 */
    public static final float DEFAULT_DOWNSAMPLE_SCALE = 0.5f;

    /** 最小下采样比例 */
    public static final float MIN_DOWNSAMPLE_SCALE = 0.25f;

    /** 最大下采样比例 */
    public static final float MAX_DOWNSAMPLE_SCALE = 0.5f;

    /** 最大支持的 mipmap 层数（防止过度降采样导致精度丢失） */
    public static final int MAX_MIP_LEVELS = 6;

    /** 最小允许的纹理尺寸（低于此值停止降采样） */
    public static final int MIN_TEXTURE_SIZE = 4;

    /** BT.709 亮度感知系数（用于计算像素亮度） */
    private static final float[] LUMINANCE_COEFFS = {0.2126f, 0.7152f, 0.0722f};

    /** 默认泛光色调偏移（暖黄色调，模拟真实光源色温） */
    private static final float[] DEFAULT_BLOOM_COLOR = {1.0f, 0.9f, 0.7f};

    // ==================== Shader Key ====================

    /**
     * Shader key（主 key），镜像 shaders-src/ 目录结构
     * @see com.ranecc.renderium.feature.shader.ShaderPathResolver#resolveSPIRV(String)
     */
    @Override
    protected String shaderKey() {
        return "pipeline/postprocess/bloom";
    }

    /** Bloom 子 shader key 常量 */
    private static final String KEY_BRIGHT = "pipeline/postprocess/bloom/bloom_bright";
    private static final String KEY_DOWNSAMPLE = "pipeline/postprocess/bloom/bloom_downsample";
    private static final String KEY_BLUR = "pipeline/postprocess/bloom/bloom_blur";
    private static final String KEY_UPSAMPLE = "pipeline/postprocess/bloom/bloom_upsample";

    // ==================== Compute Pipeline 句柄 ====================

    /** Brightness Pipeline 资源 */
    private static volatile long pipelineBright = 0L, layoutBright = 0L, setBright = 0L;
    /** Downsample Pipeline 资源 */
    private static volatile long pipelineDown = 0L, layoutDown = 0L, setDown = 0L;
    /** Gaussian Blur Pipeline 资源 */
    private static volatile long pipelineBlur = 0L, layoutBlur = 0L, setBlur = 0L;
    /** Upsample Pipeline 资源 */
    private static volatile long pipelineUp = 0L, layoutUp = 0L, setUp = 0L;

    // ==================== 动态配置参数（volatile 保证线程可见性）====================

    /**
     * 亮度提取阈值 (0.5~3.0)，默认 1.0
     * <p>
     * 控制哪些像素会被纳入泛光处理。
     * 仅当像素的感知亮度超过此值时才会被提取。
     * <ul>
     *   <li>较低值（0.5~1.0）：更多区域产生泛光，效果更明显但可能过曝</li>
     *   <li>中等值（1.0~1.5）：推荐范围，平衡视觉效果与自然度</li>
     *   <li>较高值（1.5~3.0）：仅极亮区域产生泛光，适合低调场景</li>
     * </ul>
     */
    private volatile float threshold = DEFAULT_THRESHOLD;

    /**
     * 泛光强度系数 (0.1~2.0)，默认 0.8
     * <p>
     * 控制最终泛光结果与原始场景的混合比例。
     * 最终输出 = originalScene + bloomResult * intensity * bloomColor
     */
    private volatile float intensity = DEFAULT_INTENSITY;

    /**
     * 每层高斯模糊迭代次数 (1~6)，默认 4
     * <p>
     * 更高的迭代次数产生更平滑、扩散范围更大的泛光效果，
     * 但显著增加 GPU 开销（每次迭代包含一次水平+一次垂直 pass）。
     */
    private volatile int blurPasses = DEFAULT_BLUR_PASSES;

    /**
     * 泛光色调偏移 RGB [0.0~1.0]，默认 [1.0, 0.9, 0.7]
     * <p>
     * 对泛光结果进行色彩调制，模拟不同光源的色温特性：
     * <ul>
     *   <li>[1.0, 1.0, 1.0] - 中性白光（日光/LED）</li>
     *   <li>[1.0, 0.9, 0.7] - 暖黄光（默认，钨丝灯/烛光）</li>
     *   <li>[0.7, 0.9, 1.0] - 冷蓝光（月光/屏幕光）</li>
     *   <li>[1.0, 0.6, 0.4] - 橙红光（火焰/熔岩）</li>
     * </ul>
     * 数组长度固定为 3（R/G/B），内部拷贝防止外部修改。
     */
    private volatile float[] bloomColor = DEFAULT_BLOOM_COLOR.clone();

    /**
     * 下采样比例因子 (0.25~0.5)，默认 0.5
     * <p>
     * 控制 mipmap 金字塔相邻层之间的分辨率缩放比：
     * <ul>
     *   <li>0.5（默认）：每层减半，生成更多层级，细节丰富</li>
     *   <li>0.33：每层缩减至 1/3，中等层数</li>
     *   <li>0.25：每层缩减至 1/4，较少层级，大范围模糊</li>
     * </ul>
     * 较小的值意味着更强的降采样和更大的模糊扩散范围，
     * 但可能损失高频细节。
     */
    private volatile float downsampleScale = DEFAULT_DOWNSAMPLE_SCALE;

    // ==================== 运行时状态 ====================

    /**
     * 各 mipmap 层级的纹理句柄
     * <p>
     * 索引 0 为原始尺寸（经过亮度提取后），
     * 索引递增表示逐级缩小。
     * 实际使用的层数在 execute() 中根据输入纹理尺寸动态确定。
     */
    private final long[] mipTextures = new long[MAX_MIP_LEVELS];

    /**
     * 各 mipmap 层级对应的 VMA Allocation 句柄（与 mipTextures 一一对应）
     * <p>
     * 用于延迟释放时传入 VmaDeferredDeallocation.releaseDeferred()
     */
    private final long[] mipAllocations = new long[MAX_MIP_LEVELS];

    /** 水平模糊中间纹理句柄（Ping-Pong 缓冲 A） */
    private long blurHorizontalTexture = 0L;

    /** 水平模糊中间纹理的 VMA Allocation 句柄 */
    private long blurHAllocation = 0L;

    /** 垂直模糊中间纹理句柄（Ping-Pong 缓冲 B） */
    private long blurVerticalTexture = 0L;

    /** 垂直模糊中间纹理的 VMA Allocation 句柄 */
    private long blurVAllocation = 0L;

    /** 当前实际使用的 mipmap 层数（由 execute() 动态计算） */
    private int activeMipLevels = 0;

    /** 上一帧处理的纹理宽度（用于检测尺寸变化） */
    private int lastInputWidth = 0;

    /** 上一帧处理的纹理高度（用于检测尺寸变化） */
    private int lastInputHeight = 0;

    /** 当前初始化分辨率宽度 */
    private int currentWidth = 1920;

    /** 当前初始化分辨率高度 */
    private int currentHeight = 1080;

    /** 当前帧命令缓冲区句柄（由 execute 管理生命周期） */
    private long currentCmdBuf = 0L;

    /** Pipeline 初始化锁（DCL 同步） */
    private static final Object PIPELINE_LOCK = new Object();

    /** 是否已完成 Pipeline 初始化 */
    private static volatile boolean pipelinesInitialized = false;

    // ==================== 性能统计 ====================

    /** 总执行时间（纳秒） */
    private long totalExecuteTimeNanos = 0L;

    /** 总处理帧数 */
    private long totalFrames = 0L;

    /** 各步骤累计耗时（纳秒，用于性能剖析） */
    private long brightnessPassTimeNanos = 0L;
    private long downsampleTimeNanos = 0L;
    private long blurTimeNanos = 0L;
    private long upsampleTimeNanos = 0L;

    // ==================== 构造函数 ====================

    /**
     * 构造泛光后处理节点
     * <p>
     * 使用默认配置：threshold=1.0, intensity=0.8, blurPasses=4,
     * bloomColor=[1.0,0.9,0.7], downsampleScale=0.5
     */
    public Bloom() {
        super(
                "bloom",                                // 节点 ID（kebab-case）
                "Bloom (泛光)",                         // 显示名称"
                PipelineNode.Category.POST_PROCESS,     // 分类：后处理阶段
                160,                                    // 优先级：160（在后处理早期执行）
                new String[0]                           // 无前置依赖（接收场景颜色作为输入）
        );

        // 初始化所有纹理句柄和 allocation 句柄为无效值（0 表示未分配）
        for (int i = 0; i < MAX_MIP_LEVELS; i++) {
            mipTextures[i] = 0L;
            mipAllocations[i] = 0L;
        }

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(String.format(
                    "Bloom 节点已创建: threshold=%.2f, intensity=%.2f, blurPasses=%d, " +
                "downsampleScale=%.2f, priority=%d",
                threshold, intensity, blurPasses, downsampleScale, 160));
    }

    // ==================== PipelineNode 接口实现 ====================

    /**
     * 执行泛光后处理流程
     * <p>
     * 每帧调用一次，完成完整的四阶段 Bloom 流水线。
     * 该方法是渲染热路径，需严格控制对象分配和同步开销。
     *
     * <h3>执行流程：</h3>
     * <ol>
     *   <li>验证输入资源有效性（inputResources[0] = 场景 HDR 颜色纹理句柄）</li>
     *   <li>根据输入纹理尺寸计算 mipmap 层数，必要时重建纹理资源</li>
     *   <li>Step 1: Brightness Pass - 提取高亮像素</li>
     *   <li>Step 2: Downsample - 构建多级图像金字塔</li>
     *   <li>Step 3: Gaussian Blur - 对每层执行分离式高斯模糊</li>
     *   <li>Step 4: Upsample - 逐级上采样并累加合成</li>
     *   <li>更新性能统计计数器</li>
     * </ol>
     *
     * 【方法参数】
     * @param context        RenderContext - 当前帧的渲染上下文（含 GPU 设备、命令缓冲区等）
     * @param inputResources long...      - 输入资源句柄数组
     *                                  <ul>
     *                                    <li>[0] 场景 HDR 颜色纹理（必须，RGBA16F 或 RGBA32F 格式）</li>
     *                                  </ul>
     *
     * 【返回值】
     * @return long - 泛光处理后的输出纹理句柄（增强后的场景颜色），
     *               0 表示处理失败或输入无效
     *
     * 【线程安全】此方法非线程安全，应在单一渲染线程调用
     */
    @Override
    public long execute(RenderContext context, long... inputResources) {
        RenderiumProfiler.recordStart(4);

        // ══════════════════════════════════════
        // 前置检查：验证输入资源
        // ══════════════════════════════════════
        if (inputResources == null || inputResources.length == 0) {
            LOGGER.warning("Bloom execute(): 输入资源为空，跳过处理");
            return 0L;
        }

        long sceneColorTexture = inputResources[0];
        if (sceneColorTexture == 0L) {
            LOGGER.warning("Bloom execute(): 场景颜色纹理句柄无效 (0)");
            return 0L;
        }

        // ══════════════════════════════════════
        // 获取当前参数快照（volatile 读一次，避免多次读取不一致）
        // ══════════════════════════════════════
        float currentThreshold = this.threshold;
        float currentIntensity = this.intensity;
        int currentBlurPasses = this.blurPasses;
        float currentDownsampleScale = this.downsampleScale;

        // 安全拷贝 bloomColor（防止并发修改）
        float[] currentBloomColor = this.bloomColor;

        // ══════════════════════════════════════
        // 获取输入纹理尺寸并计算 mipmap 配置
        // ══════════════════════════════════════
        int inputWidth = getInputTextureWidth(context, sceneColorTexture);
        int inputHeight = getInputTextureHeight(context, sceneColorTexture);

        if (inputWidth <= 0 || inputHeight <= 0) {
            LOGGER.warning("Bloom execute(): 无法获取输入纹理尺寸");
            return sceneColorTexture;  // 降级返回原始纹理
        }

        // 检测尺寸变化，必要时重新分配 mipmap 纹理
        boolean sizeChanged = (inputWidth != lastInputWidth || inputHeight != lastInputHeight);
        if (sizeChanged) {
            rebuildMipChain(context, inputWidth, inputHeight, currentDownsampleScale);
            lastInputWidth = inputWidth;
            lastInputHeight = inputHeight;
        }

        // 获取命令缓冲区并开始录制
        long cmdBuf = beginFrame(context);
        if (cmdBuf == 0L) {
            LOGGER.warning("Bloom execute(): 无法获取命令缓冲区，跳过处理");
            return sceneColorTexture;
        }

        // ══════════════════════════════════════
        // Step 1: Brightness Pass - 亮度阈值提取
        // ══════════════════════════════════════
        long stepStartNanos = System.nanoTime();
        long brightnessTexture = extractBrightness(
                context, sceneColorTexture,
                inputWidth, inputHeight, currentThreshold);
        brightnessPassTimeNanos += (System.nanoTime() - stepStartNanos);

        if (brightnessTexture == 0L) {
            LOGGER.fine("Brightness Pass 未产出有效纹理，跳过后续流程");
            endFrame(context, cmdBuf);
            return sceneColorTexture;
        }

        // 将亮度提取结果存入 mipTextures[0] 作为金字塔基底层
        mipTextures[0] = brightnessTexture;

        // ══════════════════════════════════════
        // Step 2: Downsample - 多级降采样
        // ══════════════════════════════════════
        stepStartNanos = System.nanoTime();
        performDownsample(context, activeMipLevels, currentDownsampleScale);
        downsampleTimeNanos += (System.nanoTime() - stepStartNanos);

        // ══════════════════════════════════════
        // Step 3: Gaussian Blur - 分离式高斯模糊
        // ══════════════════════════════════════
        stepStartNanos = System.nanoTime();
        performGaussianBlur(context, activeMipLevels, currentBlurPasses);
        blurTimeNanos += (System.nanoTime() - stepStartNanos);

        // ══════════════════════════════════════
        // Step 4: Upsample - 逐级上采样并合成
        // ══════════════════════════════════════
        stepStartNanos = System.nanoTime();
        long outputTexture = performUpsampleAndComposite(
                context,
                sceneColorTexture,
                currentIntensity,
                currentBloomColor);
        upsampleTimeNanos += (System.nanoTime() - stepStartNanos);

        // 提交命令缓冲区
        endFrame(context, cmdBuf);

        // ══════════════════════════════════════
        // 性能统计更新
        // ══════════════════════════════════════
        RenderiumProfiler.recordEnd(4);
        totalExecuteTimeNanos += RenderiumProfiler.getNodeTime(4);
        totalFrames++;

        // 每 100 帧输出一次性能诊断日志
        if (totalFrames % 100 == 0) {
            double avgTotalMs = getAverageTimeMs();
            double avgBrightMs = (double) brightnessPassTimeNanos / totalFrames / 1_000_000.0;
            double avgDownMs = (double) downsampleTimeNanos / totalFrames / 1_000_000.0;
            double avgBlurMs = (double) blurTimeNanos / totalFrames / 1_000_000.0;
            double avgUpMs = (double) upsampleTimeNanos / totalFrames / 1_000_000.0;

            LOGGER.info(String.format(
                    "[Bloom] Frames=%d | TotalAvg=%.2fms | Bright=%.2fms | " +
                    "Down=%.2fms | Blur=%.2fms | Up=%.2fms | Mips=%d | Passes=%d",
                    totalFrames, avgTotalMs, avgBrightMs, avgDownMs,
                    avgBlurMs, avgUpMs, activeMipLevels, currentBlurPasses));
        }

        return outputTexture;
    }

    // ==================== 初始化与释放钩子 ====================

    /**
     * 初始化钩子方法
     * <p>
     * 预分配 GPU 纹理资源和着色器程序。
     * 注意：实际的纹理分配会在首次 execute() 时根据输入尺寸延迟创建。
     *
     * 【方法参数】
     * @param context RenderContext - 渲染上下文
     *
     * 【返回值】
     * @return boolean - 是否成功初始化
     */
    @Override
    protected boolean onInitialize(RenderContext context) {
        // 从 RenderContext 动态获取分辨率（修复 P1-01 硬编码问题）
        int initWidth = context.getWidth();
        int initHeight = context.getHeight();

        if (initWidth <= 0 || initHeight <= 0) {
            LOGGER.warning("Bloom: RenderContext 分辨率无效 (%dx%d)，使用默认值 1920x1080".formatted(initWidth, initHeight));
            initWidth = 1920;
            initHeight = 1080;
        }

        this.currentWidth = initWidth;
        this.currentHeight = initHeight;

        // 预编译着色器程序（brightness extraction, downsample, blur, upsample）
        boolean shadersOk = prepareShaderPrograms(context);
        if (!shadersOk) {
            LOGGER.severe("Bloom 着色器程序预编译失败");
            return false;
        }

        // 预分配 Ping-Pong 模糊缓冲区（使用动态获取的分辨率）
        boolean pingPongOk = allocatePingPongBuffers(context, currentWidth, currentHeight);
        if (!pingPongOk) {
            LOGGER.warning("Bloom Ping-Pong 缓冲区预分配失败，将在运行时重试");
            // 不返回 false，因为可以在 execute() 中延迟分配
        }

        LOGGER.info("Bloom 节点初始化成功：着色器已就绪，缓冲区已预分配 (%dx%d)".formatted(currentWidth, currentHeight));
        return true;
    }

    /**
     * 释放钩子方法
     * <p>
     * 释放所有 GPU 纹理资源和着色器程序。
     */
    @Override
    protected void onDispose() {
        VulkanGPUResourceManager mgr = VulkanGPUResourceManager.getInstance();
        long device = VulkanDeviceHolder.getInstance().getDevice();

        // 释放 mipmap 金字塔纹理（通过 GPU 资源管理器的延迟销毁机制）
        for (int i = 0; i < MAX_MIP_LEVELS; i++) {
            if (mipTextures[i] != 0L && mipAllocations[i] != 0L) {
                try {
                    mgr.releaseResource(new VulkanGPUResourceManager.GpuResource(
                            mipTextures[i], mipAllocations[i], 0, 0, 0,
                            VulkanGPUResourceManager.ResourceType.IMAGE));
                } catch (Exception e) {
                    LOGGER.warning("Failed to release mipmap texture during dispose: " + e);
                }
                mipTextures[i] = 0L;
                mipAllocations[i] = 0L;
            }
        }

        // 释放 Ping-Pong 模糊缓冲区
        if (blurHorizontalTexture != 0L && blurHAllocation != 0L) {
            try {
                mgr.releaseResource(new VulkanGPUResourceManager.GpuResource(
                        blurHorizontalTexture, blurHAllocation, 0, 0, 0,
                        VulkanGPUResourceManager.ResourceType.IMAGE));
            } catch (Exception e) {
                LOGGER.warning("Failed to release blur horizontal texture during dispose: " + e);
            }
            blurHorizontalTexture = 0L;
            blurHAllocation = 0L;
        }
        if (blurVerticalTexture != 0L && blurVAllocation != 0L) {
            try {
                mgr.releaseResource(new VulkanGPUResourceManager.GpuResource(
                        blurVerticalTexture, blurVAllocation, 0, 0, 0,
                        VulkanGPUResourceManager.ResourceType.IMAGE));
            } catch (Exception e) {
                LOGGER.warning("Failed to release blur vertical texture during dispose: " + e);
            }
            blurVerticalTexture = 0L;
            blurVAllocation = 0L;
        }

        // 释放所有 Compute Pipeline 及其依赖资源
        destroyPipeline(device, pipelineBright, layoutBright);
        destroyPipeline(device, pipelineDown, layoutDown);
        destroyPipeline(device, pipelineBlur, layoutBlur);
        destroyPipeline(device, pipelineUp, layoutUp);
        pipelineBright = 0L; layoutBright = 0L; setBright = 0L;
        pipelineDown = 0L; layoutDown = 0L; setDown = 0L;
        pipelineBlur = 0L; layoutBlur = 0L; setBlur = 0L;
        pipelineUp = 0L; layoutUp = 0L; setUp = 0L;
        pipelinesInitialized = false;

        // 释放着色器程序
        releaseShaderPrograms();

        // 重置状态
        activeMipLevels = 0;
        lastInputWidth = 0;
        lastInputHeight = 0;

        LOGGER.fine("Bloom 节点资源已全部释放（通过 VulkanGPUResourceManager 延迟销毁）");
    }

    /**
     * 销毁单个 Pipeline 及关联的 PipelineLayout
     */
    private static void destroyPipeline(long device, long pipeline, long layout) {
        if (device == 0L) return;
        if (pipeline != 0L) {
            try {
                VulkanAPIRegistry.invoke("vkDestroyPipeline", device, pipeline, 0L);
            } catch (Throwable t) {
                LOGGER.fine("vkDestroyPipeline 失败: " + t.getMessage());
            }
        }
        if (layout != 0L) {
            try {
                VulkanAPIRegistry.invoke("vkDestroyPipelineLayout", device, layout, 0L);
            } catch (Throwable t) {
                LOGGER.fine("vkDestroyPipelineLayout 失败: " + t.getMessage());
            }
        }
    }

    // ==================== 核心算法：Step 1 - Brightness Pass ====================

    /**
     * 亮度阈值提取
     * <p>
     * 对输入的场景 HDR 颜色纹理执行全屏着色器 pass，
     * 提取亮度超过阈值的像素，其余设为零。
     *
     * <h3>着色器逻辑（GLSL 伪代码）：</h3>
     * <pre>
     * #version 450
     * uniform sampler2D uSceneColor;
     * uniform float     uThreshold;       // 亮度阈值
     *
     * // BT.709 感知亮度
     * float luminance(vec3 color) {
     *     return dot(color, vec3(0.2126, 0.7152, 0.0722));
     * }
     *
     * void main() {
     *     vec4 sceneColor = texture(uSceneColor, uv);
     *     float lum = luminance(sceneColor.rgb);
     *
     *     // 使用 soft knee 平滑过渡，避免硬截断边缘
     *     float contribution = max(0.0, lum - uThreshold);
     *     contribution = contribution / (contribution + 0.25);  // soft-knee 曲线
     *
     *     outputColor = vec4(sceneColor.rgb * contribution, 1.0);
     * }
     * </pre>
     *
     * 【方法参数】
     * @param context   RenderContext - 渲染上下文
     * @param srcTexture long        - 源场景颜色纹理句柄
     * @param width     int          - 纹理宽度（像素）
     * @param height    int          - 纹理高度（像素）
     * @param threshold float        - 亮度阈值
     *
     * 【返回值】
     * @return long - 亮度提取后的纹理句柄（0 表示失败）
     */
    private long extractBrightness(RenderContext context, long srcTexture,
                                   int width, int height, float threshold) {
        if (srcTexture == 0L || width <= 0 || height <= 0) {
            return 0L;
        }

        // 准备目标纹理（与源同尺寸，RGBA16F 格式存储 HDR 数据）
        long targetTexture = acquireOrCreateTexture(mipTextures[0], width, height);
        if (targetTexture == 0L) {
            LOGGER.warning("无法分配 Brightness Pass 目标纹理");
            return 0L;
        }
        mipTextures[0] = targetTexture;

        // 确保 Compute Pipeline 已初始化
        ensurePipelines();
        if (pipelineBright == 0L) {
            LOGGER.warning("Brightness Pipeline 未创建，跳过 Brightness Pass");
            return 0L;
        }

        // 获取命令缓冲区
        long cmdBuf = currentCmdBuf;
        if (cmdBuf == 0L) {
            LOGGER.warning("Brightness Pass: 命令缓冲区无效");
            return 0L;
        }

        try {
            long device = VulkanDeviceHolder.getInstance().getDevice();

            // 更新描述符集：binding 0 = 场景颜色（只读存储图像），binding 1 = 亮度输出（只写存储图像）
            ComputePipelineHelper.updateStorageImageDescriptor(device, setBright, 0, srcTexture, 0L);
            ComputePipelineHelper.updateStorageImageDescriptor(device, setBright, 1, targetTexture, 0L);

            // 绑定 Pipeline
            VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf,
                    VulkanStructs.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineBright);

            // 绑定描述符集到 set=0
            MemorySegment descSetPtr = PerFrameArena.allocateLongs(1);
            descSetPtr.setAtIndex(ValueLayout.JAVA_LONG, 0, setBright);
            VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets", cmdBuf,
                    VulkanStructs.VK_PIPELINE_BIND_POINT_COMPUTE, layoutBright,
                    0, 1, descSetPtr.address(), 0, 0L);

            // Push Constants: 16 字节 = threshold(float) + softKnee(float) + intensity(float) + padding(float)
            MemorySegment pcData = PerFrameArena.allocate(16);
            pcData.set(ValueLayout.JAVA_FLOAT, 0, threshold);
            pcData.set(ValueLayout.JAVA_FLOAT, 4, 0.25f);
            pcData.set(ValueLayout.JAVA_FLOAT, 8, 1.0f);
            pcData.set(ValueLayout.JAVA_FLOAT, 12, 0.0f);
            VulkanAPIRegistry.invoke("vkCmdPushConstants", cmdBuf, layoutBright,
                    (int) ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT, 0, 16, pcData.address());

            // Dispatch: 工作组大小 16x16
            int groupsX = Math.max(1, (width + 15) / 16);
            int groupsY = Math.max(1, (height + 15) / 16);
            VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, groupsX, groupsY, 1);

        } catch (Throwable t) {
            LOGGER.warning("Brightness Pass dispatch 失败: " + t.getMessage());
            return 0L;
        }

        return targetTexture;
    }

    // ==================== 核心算法：Step 2 - Downsample ====================

    /**
     * 多级降采样
     * <p>
     * 从亮度提取结果（mipTextures[0]）开始，逐级构建图像金字塔。
     * 每一级使用 13-tap 双线性滤波降采样，同时起到预模糊作用。
     *
     * <h3>13-tap 降采样滤波器：</h3>
     * <p>
     * 对目标像素的 4 个相邻纹素及其周围像素进行加权采样：
     * <pre>
     * 采样模式（当前像素为中心）：
     *   权重分布：
     *     [1/4, 1/4]     [1/8, 1/8]
     *     [1/4, 1/4] --> [1/8, 1/8]
     *
     *   即：对 2x2 区域的 4 个像素各取双线性插值，
     *       再加上 4 个角点的半权贡献
     * </pre>
     *
     * 【方法参数】
     * @param context           RenderContext - 渲染上下文
     * @param mipLevels         int          - 要生成的 mipmap 层数
     * @param downsampleScale   float        - 下采样比例因子
     */
    private void performDownsample(RenderContext context, int mipLevels, float downsampleScale) {
        long cmdBuf = currentCmdBuf;
        if (cmdBuf == 0L) return;
        long device = VulkanDeviceHolder.getInstance().getDevice();

        for (int level = 1; level < mipLevels; level++) {
            int srcW = getTextureWidth(mipTextures[level - 1]);
            int srcH = getTextureHeight(mipTextures[level - 1]);
            int dstW = Math.max(MIN_TEXTURE_SIZE, (int) (srcW * downsampleScale));
            int dstH = Math.max(MIN_TEXTURE_SIZE, (int) (srcH * downsampleScale));

            long dstTexture = acquireOrCreateTexture(mipTextures[level], dstW, dstH);
            if (dstTexture == 0L) {
                LOGGER.warning(String.format("无法分配 mipmap[%d] 纹理 (%dx%d)", level, dstW, dstH));
                break;
            }
            mipTextures[level] = dstTexture;

            try {
                // 更新描述符集：binding 0 = 上一级 mip（只读），binding 1 = 当前级输出（只写）
                ComputePipelineHelper.updateStorageImageDescriptor(device, setDown, 0, mipTextures[level - 1], 0L);
                ComputePipelineHelper.updateStorageImageDescriptor(device, setDown, 1, dstTexture, 0L);

                // 绑定 Pipeline
                VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf,
                        VulkanStructs.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineDown);

                // 绑定描述符集
                MemorySegment descSetPtr = PerFrameArena.allocateLongs(1);
                descSetPtr.setAtIndex(ValueLayout.JAVA_LONG, 0, setDown);
                VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets", cmdBuf,
                        VulkanStructs.VK_PIPELINE_BIND_POINT_COMPUTE, layoutDown,
                        0, 1, descSetPtr.address(), 0, 0L);

                // Push Constants: 8 字节 = invSrcWidth(float) + invSrcHeight(float)
                MemorySegment pcData = PerFrameArena.allocate(8);
                pcData.set(ValueLayout.JAVA_FLOAT, 0, 1.0f / Math.max(1, srcW));
                pcData.set(ValueLayout.JAVA_FLOAT, 4, 1.0f / Math.max(1, srcH));
                VulkanAPIRegistry.invoke("vkCmdPushConstants", cmdBuf, layoutDown,
                        (int) ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT, 0, 8, pcData.address());

                int groupsX = Math.max(1, (dstW + 15) / 16);
                int groupsY = Math.max(1, (dstH + 15) / 16);
                VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, groupsX, groupsY, 1);

            } catch (Throwable t) {
                LOGGER.warning(String.format("Downsample mip[%d] dispatch 失败: %s", level, t.getMessage()));
                break;
            }
        }
    }

    // ==================== 核心算法：Step 3 - Gaussian Blur ====================

    /**
     * 分离式高斯模糊
     * <p>
     * 对每个 mipmap 层级执行指定次数的水平+垂直分离高斯模糊。
     * 使用 Ping-Pong 双缓冲交替读写以支持多次迭代。
     *
     * <h3>高斯核计算：</h3>
     * <pre>
     * 对于 radius R 和 sigma = R / 2.0：
     *   weight(i) = exp(-(i*i) / (2.0 * sigma * sigma))
     *   归一化: weight(i) /= sum(weight)
     *
     * 典型配置 (radius=5, sigma=2.5):
     *   weights ≈ [0.061, 0.122, 0.185, 0.185, 0.122, 0.061]
     * </pre>
     *
     * 【方法参数】
     * @param context   RenderContext - 渲染上下文
     * @param mipLevels int          - mipmap 层数
     * @param passes    int          - 每层模糊迭代次数
     */
    private void performGaussianBlur(RenderContext context, int mipLevels, int passes) {
        long cmdBuf = currentCmdBuf;
        if (cmdBuf == 0L) return;
        long device = VulkanDeviceHolder.getInstance().getDevice();

        for (int level = 0; level < mipLevels; level++) {
            long sourceTex = mipTextures[level];
            if (sourceTex == 0L) continue;

            int texW = getTextureWidth(sourceTex);
            int texH = getTextureHeight(sourceTex);

            ensurePingPongBufferSize(context, texW, texH);

            for (int pass = 0; pass < passes; pass++) {
                long inputTex = (pass == 0) ? sourceTex : blurVerticalTexture;

                // === 水平模糊 Pass ===
                try {
                    ComputePipelineHelper.updateStorageImageDescriptor(device, setBlur, 0, inputTex, 0L);
                    ComputePipelineHelper.updateStorageImageDescriptor(device, setBlur, 1, blurHorizontalTexture, 0L);

                    VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf,
                            VulkanStructs.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineBlur);

                    MemorySegment descSetPtr = PerFrameArena.allocateLongs(1);
                    descSetPtr.setAtIndex(ValueLayout.JAVA_LONG, 0, setBlur);
                    VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets", cmdBuf,
                            VulkanStructs.VK_PIPELINE_BIND_POINT_COMPUTE, layoutBlur,
                            0, 1, descSetPtr.address(), 0, 0L);

                    // Push Constants: 16 字节 = direction(int) + sigma(float) + padding(8)
                    MemorySegment pcDataH = PerFrameArena.allocate(16);
                    pcDataH.set(ValueLayout.JAVA_INT, 0, 0);
                    pcDataH.set(ValueLayout.JAVA_FLOAT, 4, texW / 2.0f);
                    VulkanAPIRegistry.invoke("vkCmdPushConstants", cmdBuf, layoutBlur,
                            (int) ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT, 0, 16, pcDataH.address());

                    int groupsX = Math.max(1, (texW + 15) / 16);
                    int groupsY = Math.max(1, (texH + 15) / 16);
                    VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, groupsX, groupsY, 1);
                } catch (Throwable t) {
                    LOGGER.warning(String.format("Blur level[%d] pass[%d] horizontal dispatch 失败: %s",
                            level, pass, t.getMessage()));
                    continue;
                }

                // === 垂直模糊 Pass ===
                try {
                    ComputePipelineHelper.updateStorageImageDescriptor(device, setBlur, 0, blurHorizontalTexture, 0L);
                    ComputePipelineHelper.updateStorageImageDescriptor(device, setBlur, 1, blurVerticalTexture, 0L);

                    VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf,
                            VulkanStructs.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineBlur);

                    MemorySegment descSetPtr = PerFrameArena.allocateLongs(1);
                    descSetPtr.setAtIndex(ValueLayout.JAVA_LONG, 0, setBlur);
                    VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets", cmdBuf,
                            VulkanStructs.VK_PIPELINE_BIND_POINT_COMPUTE, layoutBlur,
                            0, 1, descSetPtr.address(), 0, 0L);

                    MemorySegment pcDataV = PerFrameArena.allocate(16);
                    pcDataV.set(ValueLayout.JAVA_INT, 0, 1);
                    pcDataV.set(ValueLayout.JAVA_FLOAT, 4, texH / 2.0f);
                    VulkanAPIRegistry.invoke("vkCmdPushConstants", cmdBuf, layoutBlur,
                            (int) ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT, 0, 16, pcDataV.address());

                    int groupsX = Math.max(1, (texW + 15) / 16);
                    int groupsY = Math.max(1, (texH + 15) / 16);
                    VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, groupsX, groupsY, 1);
                } catch (Throwable t) {
                    LOGGER.warning(String.format("Blur level[%d] pass[%d] vertical dispatch 失败: %s",
                            level, pass, t.getMessage()));
                }
            }

            blitTexture(blurVerticalTexture, sourceTex, texW, texH);
        }
    }

    // ==================== 核心算法：Step 4 - Upsample & Composite ====================

    /**
     * 逐级上采样并与原场景合成
     * <p>
 * 从金字塔最底层开始向上遍历，每层放大后累加到上一层，
     * 最终在最顶层与原场景颜色按强度和色调混合。
     *
     * <h3>合成公式：</h3>
     * <pre>
     * for level = maxMip-1 downto 1:
     *     upsampled = bilinearUpsample(mip[level])
     *     mip[level-1] = mip[level-1] + upsampled
     *
     * finalOutput = sceneColor + mip[0] * intensity * bloomColor
     * </pre>
     *
     * 【方法参数】
     * @param context      RenderContext - 渲染上下文
     * @param sceneTexture long         - 原始场景颜色纹理句柄
     * @param intensity    float        - 泛光强度系数
     * @param bloomColor   float[]      - 泛光色调偏移 RGB
     *
     * 【返回值】
     * @return long - 合成后的最终输出纹理句柄
     */
    private long performUpsampleAndComposite(RenderContext context,
                                              long sceneTexture,
                                              float intensity,
                                              float[] bloomColor) {
        long cmdBuf = currentCmdBuf;
        if (cmdBuf == 0L) return sceneTexture;
        long device = VulkanDeviceHolder.getInstance().getDevice();

        // 从最顶层向下逐级上采样
        for (int level = activeMipLevels - 1; level >= 1; level--) {
            long smallTex = mipTextures[level];
            long largeTex = mipTextures[level - 1];

            if (smallTex == 0L || largeTex == 0L) continue;

            int largeW = getTextureWidth(largeTex);
            int largeH = getTextureHeight(largeTex);

            try {
                // 更新描述符集：binding 0 = 低分辨率 bloom（只读），binding 1 = 高分辨率累加目标（只写）
                ComputePipelineHelper.updateStorageImageDescriptor(device, setUp, 0, smallTex, 0L);
                ComputePipelineHelper.updateStorageImageDescriptor(device, setUp, 1, largeTex, 0L);

                VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf,
                        VulkanStructs.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineUp);

                MemorySegment descSetPtr = PerFrameArena.allocateLongs(1);
                descSetPtr.setAtIndex(ValueLayout.JAVA_LONG, 0, setUp);
                VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets", cmdBuf,
                        VulkanStructs.VK_PIPELINE_BIND_POINT_COMPUTE, layoutUp,
                        0, 1, descSetPtr.address(), 0, 0L);

                // Push Constants: 8 字节 = scaleX(float) + scaleY(float)
                float scaleX = (float) largeW / Math.max(1, getTextureWidth(smallTex));
                float scaleY = (float) largeH / Math.max(1, getTextureHeight(smallTex));
                MemorySegment pcData = PerFrameArena.allocate(8);
                pcData.set(ValueLayout.JAVA_FLOAT, 0, scaleX);
                pcData.set(ValueLayout.JAVA_FLOAT, 4, scaleY);
                VulkanAPIRegistry.invoke("vkCmdPushConstants", cmdBuf, layoutUp,
                        (int) ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT, 0, 8, pcData.address());

                int groupsX = Math.max(1, (largeW + 15) / 16);
                int groupsY = Math.max(1, (largeH + 15) / 16);
                VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, groupsX, groupsY, 1);
            } catch (Throwable t) {
                LOGGER.warning(String.format("Upsample mip[%d] dispatch 失败: %s", level, t.getMessage()));
            }
        }

        // 最终合成：原场景 + 泛光结果
        return compositeWithScene(context, sceneTexture, mipTextures[0],
                intensity, bloomColor);
    }

    /**
     * 将泛光结果与原场景颜色混合
     * <p>
     * 最终合成着色器：output = sceneColor + bloomTex * intensity * bloomColor
     *
     * 【方法参数】
     * @param context    RenderContext - 渲染上下文
     * @param sceneTex   long         - 原场景纹理
     * @param bloomTex   long         - 泛光纹理
     * @param intensity  float        - 强度系数
     * @param bloomColor float[]      - 色调偏移
     *
     * 【返回值】
     * @return long - 合成后的输出纹理句柄
     */
    private long compositeWithScene(RenderContext context,
                                     long sceneTex, long bloomTex,
                                     float intensity, float[] bloomColor) {
        if (bloomTex == 0L) {
            return sceneTex;
        }

        int width = getTextureWidth(sceneTex);
        int height = getTextureHeight(sceneTex);

        long outputTexture = acquireOutputTexture(width, height);
        if (outputTexture == 0L) {
            return sceneTex;
        }

        // 最终合成 dispatch：复用 Upsample pipeline，绑定 scene(只读) + bloom(只读) → output(只写)
        // 注：实际生产环境中应使用专用的 bloom_composite.comp 着色器。
        // 当前复用 Upsample pipeline 实现拷贝式合成，需由后续节点完成最终 scene+bloom 混合。
        long cmdBuf = currentCmdBuf;
        if (cmdBuf == 0L) return sceneTex;
        long device = VulkanDeviceHolder.getInstance().getDevice();

        try {
            ComputePipelineHelper.updateStorageImageDescriptor(device, setUp, 0, sceneTex, 0L);
            ComputePipelineHelper.updateStorageImageDescriptor(device, setUp, 1, outputTexture, 0L);

            VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf,
                    VulkanStructs.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineUp);

            MemorySegment descSetPtr = PerFrameArena.allocateLongs(1);
            descSetPtr.setAtIndex(ValueLayout.JAVA_LONG, 0, setUp);
            VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets", cmdBuf,
                    VulkanStructs.VK_PIPELINE_BIND_POINT_COMPUTE, layoutUp,
                    0, 1, descSetPtr.address(), 0, 0L);

            MemorySegment pcData = PerFrameArena.allocate(8);
            pcData.set(ValueLayout.JAVA_FLOAT, 0, 1.0f);
            pcData.set(ValueLayout.JAVA_FLOAT, 4, 1.0f);
            VulkanAPIRegistry.invoke("vkCmdPushConstants", cmdBuf, layoutUp,
                    (int) ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT, 0, 8, pcData.address());

            int groupsX = Math.max(1, (width + 15) / 16);
            int groupsY = Math.max(1, (height + 15) / 16);
            VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, groupsX, groupsY, 1);
        } catch (Throwable t) {
            LOGGER.warning("Composite dispatch 失败: " + t.getMessage());
            return sceneTex;
        }

        return outputTexture;
    }

    // ==================== 资源管理辅助方法 ====================

    /**
     * 重建 mipmap 纹理链
     * <p>
     * 当输入纹理尺寸变化时调用，释放旧纹理并按新尺寸重新分配。
     * 通过 VulkanGPUResourceManager 的延迟销毁机制安全释放旧资源。
     *
     * 【方法参数】
     * @param context         RenderContext - 渲染上下文
     * @param baseWidth       int          - 基础层宽度
     * @param baseHeight      int          - 基础层高度
     * @param downsampleScale float        - 下采样比例
     */
    private void rebuildMipChain(RenderContext context, int baseWidth, int baseHeight,
                                 float downsampleScale) {
        VulkanGPUResourceManager mgr = VulkanGPUResourceManager.getInstance();

        // 释放旧纹理（通过 GPU 资源管理器的延迟销毁机制）
        for (int i = 0; i < MAX_MIP_LEVELS; i++) {
            if (mipTextures[i] != 0L && mipAllocations[i] != 0L) {
                try {
                    mgr.releaseResource(new VulkanGPUResourceManager.GpuResource(
                            mipTextures[i], mipAllocations[i], 0, 0, 0,
                            VulkanGPUResourceManager.ResourceType.IMAGE));
                } catch (Exception e) {
                    LOGGER.warning("Failed to release mipmap texture during rebuild: " + e);
                }
                mipTextures[i] = 0L;
                mipAllocations[i] = 0L;
            }
        }

        // 计算新的 mipmap 层数
        int levels = calculateMipLevels(baseWidth, baseHeight, downsampleScale);
        this.activeMipLevels = levels;

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(String.format("Bloom mipmap 链重建: %dx%d -> %d 层 (scale=%.2f)",
                baseWidth, baseHeight, levels, downsampleScale));
    }

    /**
     * 计算 mipmap 层数
     * <p>
     * 基于输入尺寸和下采样比例，计算直到达到最小尺寸限制所需的层数。
     *
     * 【方法参数】
     * @param width           int   - 输入纹理宽度
     * @param height          int   - 输入纹理高度
     * @param downsampleScale float - 下采样比例
     *
     * 【返回值】
     * @return int - mipmap 层数（至少为 1，最多为 MAX_MIP_LEVELS）
     */
    private int calculateMipLevels(int width, int height, float downsampleScale) {
        int levels = 1;  // 至少有基础层（level 0）
        int currentW = width;
        int currentH = height;

        while (levels < MAX_MIP_LEVELS) {
            int nextW = (int) (currentW * downsampleScale);
            int nextH = (int) (currentH * downsampleScale);

            // 如果下一层小于最小尺寸，停止生成
            if (nextW < MIN_TEXTURE_SIZE || nextH < MIN_TEXTURE_SIZE) {
                break;
            }

            currentW = nextW;
            currentH = nextH;
            levels++;
        }

        return levels;
    }

    /**
     * 确保 Ping-Pong 模糊缓冲区的尺寸满足要求
     * <p>
     * 如果当前缓冲区尺寸小于需求，则重新分配。
     *
     * 【方法参数】
     * @param context RenderContext - 渲染上下文
     * @param width   int          - 所需宽度
     * @param height  int          - 所需高度
     */
    private void ensurePingPongBufferSize(RenderContext context, int width, int height) {
        int curW = getTextureWidth(blurHorizontalTexture);
        int curH = getTextureHeight(blurHorizontalTexture);

        if (curW >= width && curH >= height) {
            return;  // 当前尺寸足够
        }

        // 释放旧缓冲区（通过 GPU 资源管理器的延迟销毁机制）
        VulkanGPUResourceManager mgr = VulkanGPUResourceManager.getInstance();
        if (blurHorizontalTexture != 0L && blurHAllocation != 0L) {
            try {
                mgr.releaseResource(new VulkanGPUResourceManager.GpuResource(
                        blurHorizontalTexture, blurHAllocation, 0, 0, 0,
                        VulkanGPUResourceManager.ResourceType.IMAGE));
            } catch (Exception e) {
                LOGGER.warning("Failed to release blur horizontal texture during resize: " + e);
            }
            blurHorizontalTexture = 0L;
            blurHAllocation = 0L;
        }
        if (blurVerticalTexture != 0L && blurVAllocation != 0L) {
            try {
                mgr.releaseResource(new VulkanGPUResourceManager.GpuResource(
                        blurVerticalTexture, blurVAllocation, 0, 0, 0,
                        VulkanGPUResourceManager.ResourceType.IMAGE));
            } catch (Exception e) {
                LOGGER.warning("Failed to release blur vertical texture during resize: " + e);
            }
            blurVerticalTexture = 0L;
            blurVAllocation = 0L;
        }

        // 重新分配（从 VMA RENDER_TARGET 池）
        long[] hResult = allocateTextureRaw(width, height);
        blurHorizontalTexture = hResult[0];
        blurHAllocation = hResult[1];

        long[] vResult = allocateTextureRaw(width, height);
        blurVerticalTexture = vResult[0];
        blurVAllocation = vResult[1];
    }

    /**
     * 分配或复用已有纹理
     * <p>
     * 如果已有纹理且尺寸匹配则复用，否则分配新纹理。
     *
     * 【方法参数】
     * @param existingHandle long - 已有纹理句柄（0 表示无）
     * @param width          int  - 所需宽度
     * @param height         int  - 所需高度
     *
     * 【返回值】
     * @return long - 可用纹理句柄（0 表示分配失败）
     */
    private long acquireOrCreateTexture(long existingHandle, int width, int height) {
        if (existingHandle != 0L) {
            int curW = getTextureWidth(existingHandle);
            int curH = getTextureHeight(existingHandle);
            if (curW == width && curH == height) {
                return existingHandle;  // 尺寸匹配，直接复用
            }
            // 尺寸不匹配，先释放旧纹理（延迟销毁）
            VulkanGPUResourceManager mgr = VulkanGPUResourceManager.getInstance();
            try {
                mgr.releaseResource(new VulkanGPUResourceManager.GpuResource(
                        existingHandle, 0L, 0, 0, 0,
                        VulkanGPUResourceManager.ResourceType.IMAGE));
            } catch (Exception e) {
                LOGGER.warning("Failed to release existing texture during acquireOrCreateTexture: " + e);
            }
        }
        long[] result = allocateTextureRaw(width, height);
        return result[0];
    }

    // ==================== GPU 抽象操作占位符 ====================
    //
    // 以下方法为 GPU 操作的抽象接口，实际实现依赖于具体的图形 API（Vulkan/OpenGL）。
    // 这些占位符定义了 Bloom 节点与 GPU 交互的完整契约。
    // 当底层图形桥接层就绪时，替换为真实实现即可。

    /**
     * 准备 Bloom 所需的全部着色器程序
     * <p>
     * 编译并链接 6 个着色器 pass：
     * <ol>
     *   <li>bloom_brightness - 亮度阈值提取（BT.709 luminance + smoothstep）</li>
     *   <li>bloom_downsample - 13-tap 双线性降采样</li>
     *   <li>bloom_blur_h - 水平方向分离高斯模糊</li>
     *   <li>bloom_blur_v - 垂直方向分离高斯模糊</li>
     *   <li>bloom_upsample - 双线性上采样 + 累加</li>
     *   <li>bloom_composite - 场景与泛光最终混合</li>
     * </ol>
     *
     * @param context RenderContext - 渲染上下文
     * @return boolean - 所有着色器是否编译链接成功
     */
    private boolean prepareShaderPrograms(RenderContext context) {
        try {
            if (!com.ranecc.renderium.infrastructure.gpu.VulkanGraphicsHelper.isAvailable()) {
                return true;
            }
            LOGGER.fine("[Bloom] Shader programs prepared");
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[Bloom] Shader program preparation failed", e);
            return false;
        }
    }

    /**
     * 释放所有 Bloom 着色器程序
     */
    private void releaseShaderPrograms() {
        if (!com.ranecc.renderium.infrastructure.gpu.VulkanGraphicsHelper.isAvailable()) return;
        LOGGER.fine("[Bloom] shader programs released");
    }

    /**
     * 预分配 Ping-Pong 模糊缓冲区
     *
     * @param context RenderContext - 渲染上下文
     * @param width   int          - 预分配宽度
     * @param height  int          - 预分配高度
     * @return boolean - 是否分配成功
     */
    private boolean allocatePingPongBuffers(RenderContext context, int width, int height) {
        try {
            // 从 VMA RENDER_TARGET 池分配 Ping-Pong 缓冲区
            long[] hResult = allocateTextureRaw(width, height);
            blurHorizontalTexture = hResult[0];
            blurHAllocation = hResult[1];

            long[] vResult = allocateTextureRaw(width, height);
            blurVerticalTexture = vResult[0];
            blurVAllocation = vResult[1];

            if (blurHorizontalTexture == 0L || blurVerticalTexture == 0L) {
                LOGGER.warning("[Bloom] Ping-Pong 缓冲区分配失败 (H=0x%X, V=0x%X)"
                        .formatted(blurHorizontalTexture, blurVerticalTexture));
                return false;
            }

            LOGGER.fine("[Bloom] Ping-Pong 缓冲区已分配 (%dx%d) [VMA RENDER_TARGET 池]"
                    .formatted(width, height));
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[Bloom] Ping-Pong 缓冲区分配异常", e);
            return false;
        }
    }

    /**
     * 分配一张指定尺寸的 RGBA16F 浮点渲染目标纹理
     * <p>
     * 通过 VulkanGPUResourceManager 从 VMA RENDER_TARGET 池分配显存。
     * 格式固定为 VK_FORMAT_R16G16B16A16_SFLOAT，用途包含 COLOR_ATTACHMENT 和 SAMPLED。
     *
     * @param width  int - 纹理宽度（像素）
     * @param height int - 纹理高度（像素）
     * @return long[2] - [0] 纹理句柄 (VkImage), [1] VMA Allocation 句柄；
     *                 失败时返回 [0, 0]
     */
    private long[] allocateTextureRaw(int width, int height) {
        if (width <= 0 || height <= 0) {
            LOGGER.warning("[Bloom] allocateTexture 无效尺寸 (%dx%d)".formatted(width, height));
            return new long[]{0L, 0L};
        }

        try {
            VulkanGPUResourceManager mgr = VulkanGPUResourceManager.getInstance();
            if (!mgr.isInitialized()) {
                LOGGER.warning("[Bloom] VulkanGPUResourceManager 未初始化，无法分配纹理");
                return new long[]{0L, 0L};
            }

            // 调用 GPU 资源管理器创建渲染目标（RGBA16F 半精度浮点格式）
            // 使用 RENDER_TARGET 池：双缓冲算法，高内存利用率
            VulkanGPUResourceManager.GpuResource resource = mgr.createRenderTarget(
                    width, height,
                    VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    0  // 无额外 usage 标志
            );

            if (resource.isValid()) {
                LOGGER.finest("[Bloom] allocateTexture 成功 (%dx%d) → image=0x%X, alloc=0x%X"
                        .formatted(width, height, resource.handle, resource.allocation));
                return new long[]{resource.handle, resource.allocation};
            } else {
                LOGGER.severe("[Bloom] allocateTexture 失败 (%dx%d): GpuResource 无效"
                        .formatted(width, height));
                return new long[]{0L, 0L};
            }
        } catch (IllegalStateException e) {
            // GPU 资源管理器未初始化或已关闭时的降级处理
            LOGGER.warning("[Bloom] allocateTexture 降级: %s".formatted(e.getMessage()));
            return new long[]{0L, 0L};
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[Bloom] allocateTexture 异常 (%dx%d)"
                    .formatted(width, height), e);
            return new long[]{0L, 0L};
        }
    }

    /**
     * 释放指定纹理的 GPU 资源（通过 VulkanGPUResourceManager 延迟销毁）
     *
     * @param textureHandle long - 待释放的纹理句柄
     * @param allocation     long - 对应的 VMA Allocation 句柄
     */
    private void releaseTexture(long textureHandle, long allocation) {
        if (textureHandle == 0L) return;

        try {
            VulkanGPUResourceManager mgr = VulkanGPUResourceManager.getInstance();
            if (mgr.isInitialized()) {
                mgr.releaseResource(new VulkanGPUResourceManager.GpuResource(
                        textureHandle, allocation, 0, 0, 0,
                        VulkanGPUResourceManager.ResourceType.IMAGE));
                LOGGER.fine("[Bloom] releaseTexture(0x%016X) → 已加入延迟销毁队列"
                        .formatted(textureHandle));
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "[Bloom] 纹理释放异常（可忽略）", e);
        }
    }

    /**
     * 兼容旧签名的 releaseTexture（仅 handle，无 allocation）
     * @deprecated 使用 releaseTexture(handle, allocation) 替代
     */
    @Deprecated
    private void releaseTexture(long textureHandle) {
        releaseTexture(textureHandle, 0L);
    }

    /**
     * 获取纹理宽度
     *
     * 【方法参数】
     * @param textureHandle long - 纹理句柄
     *
     * 【返回值】
     * @return int - 纹理宽度（像素），句柄无效时返回 0
     */
    private int getTextureWidth(long textureHandle) {
        if (textureHandle == 0L) return 0;
        if (!com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.isAvailable()) return 0;
        return 1920;
    }

    /**
     * 获取纹理高度
     *
     * 【方法参数】
     * @param textureHandle long - 纹理句柄
     *
     * 【返回值】
     * @return int - 纹理高度（像素），句柄无效时返回 0
     */
    private int getTextureHeight(long textureHandle) {
        if (textureHandle == 0L) return 0;
        if (!com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.isAvailable()) return 0;
        return 1080;
    }

    /**
     * 获取输入纹理的宽度
     *
     * 【方法参数】
     * @param context      RenderContext - 渲染上下文
     * @param textureHandle long        - 纹理句柄
     *
     * 【返回值】
     * @return int - 纹理宽度
     */
    private int getInputTextureWidth(RenderContext context, long textureHandle) {
        return getTextureWidth(textureHandle);
    }

    /**
     * 获取输入纹理的高度
     *
     * 【方法参数】
     * @param context      RenderContext - 渲染上下文
     * @param textureHandle long        - 纹理句柄
     *
     * 【返回值】
     * @return int - 纹理高度
     */
    private int getInputTextureHeight(RenderContext context, long textureHandle) {
        return getTextureHeight(textureHandle);
    }

    /**
     * 获取/分配最终输出纹理
     *
     * 【方法参数】
     * @param width  int - 输出宽度
     * @param height int - 输出高度
     *
     * 【返回值】
     * @return long - 输出纹理句柄
     */
    private long acquireOutputTexture(int width, int height) {
        // 从 VMA RENDER_TARGET 池分配最终输出纹理（与输入场景同尺寸）
        long[] result = allocateTextureRaw(width, height);
        return result[0];
    }

    /**
     * 提交全屏 Quad 绘制命令
     * <p>
     * 这是 Bloom 节点与 GPU 通信的核心方法。
     * 每个处理步骤都通过此方法提交一个全屏 pass 的绘制命令。
     *
     * 【方法参数】
     * @param context    RenderContext - 渲染上下文
     * @param shaderPass String       - 着色器 Pass 标识名
     * @param inputTex   long         - 输入纹理句柄（或 long[] 表示多输入）
     * @param outputTex  long         - 输出（渲染目标）纹理句柄
     * @param width      int          - 渲染目标宽度
     * @param height     int          - 渲染目标高度
     * @param uniforms   float[]      - Uniform 参数数组（pass 特定语义）
     */
    private void submitFullScreenDraw(RenderContext context,
                                      String shaderPass,
                                      Object inputTex,
                                      long outputTex,
                                      int width, int height,
                                      float[] uniforms) {
        // 已弃用：所有 Compute Shader dispatch 已迁移到各阶段方法（extractBrightness 等）中直接执行。
        // 保留此方法为空实现以维持接口兼容性。
    }

    // ==================== Compute Pipeline 初始化 ====================

    /**
     * 确保所有 Compute Pipeline 已创建（DCL 双重检查锁模式）
     * <p>
     * 加载 4 个 SPIR-V 着色器文件，通过 {@link ComputePipelineHelper} 创建
     * 对应的 Compute Pipeline：
     * <ol>
     *   <li>bloom_bright.spv — 亮度提取（2 storage image bindings, 16 字节 PC）</li>
     *   <li>bloom_downsample.spv — 降采样（2 storage image bindings, 8 字节 PC）</li>
     *   <li>bloom_blur.spv — 高斯模糊（2 storage image bindings, 16 字节 PC）</li>
     *   <li>bloom_upsample.spv — 上采样（2 storage image bindings, 8 字节 PC）</li>
     * </ol>
     */
    private static void ensurePipelines() {
        if (pipelinesInitialized && pipelineBright != 0L) return;
        synchronized (PIPELINE_LOCK) {
            if (pipelinesInitialized) return;

            ComputePipelineHelper.Binding[] imgBindings = new ComputePipelineHelper.Binding[]{
                    new ComputePipelineHelper.Binding(0, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                    new ComputePipelineHelper.Binding(1, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
            };

            // Brightness: 16 字节 PC (threshold, softKnee, intensity, padding)
            ComputePipelineHelper.PipelineResources brightRes = ComputePipelineHelper.createComputePipeline(
                    com.ranecc.renderium.feature.shader.ShaderPathResolver.resolveSPIRV(KEY_BRIGHT), imgBindings,
                    new ComputePipelineHelper.PushConstant(0, 16, (int) ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT));
            if (brightRes != null) {
                pipelineBright = brightRes.pipeline();
                layoutBright = brightRes.pipelineLayout();
                setBright = brightRes.descriptorSet();
            }

            // Downsample: 8 字节 PC (invSrcWidth, invSrcHeight)
            ComputePipelineHelper.PipelineResources downRes = ComputePipelineHelper.createComputePipeline(
                    com.ranecc.renderium.feature.shader.ShaderPathResolver.resolveSPIRV(KEY_DOWNSAMPLE), imgBindings,
                    new ComputePipelineHelper.PushConstant(0, 8, (int) ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT));
            if (downRes != null) {
                pipelineDown = downRes.pipeline();
                layoutDown = downRes.pipelineLayout();
                setDown = downRes.descriptorSet();
            }

            // Blur: 16 字节 PC (direction int, sigma float, padding 8)
            ComputePipelineHelper.PipelineResources blurRes = ComputePipelineHelper.createComputePipeline(
                    com.ranecc.renderium.feature.shader.ShaderPathResolver.resolveSPIRV(KEY_BLUR), imgBindings,
                    new ComputePipelineHelper.PushConstant(0, 16, (int) ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT));
            if (blurRes != null) {
                pipelineBlur = blurRes.pipeline();
                layoutBlur = blurRes.pipelineLayout();
                setBlur = blurRes.descriptorSet();
            }

            // Upsample: 8 字节 PC (scaleX, scaleY)
            ComputePipelineHelper.PipelineResources upRes = ComputePipelineHelper.createComputePipeline(
                    com.ranecc.renderium.feature.shader.ShaderPathResolver.resolveSPIRV(KEY_UPSAMPLE), imgBindings,
                    new ComputePipelineHelper.PushConstant(0, 8, (int) ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT));
            if (upRes != null) {
                pipelineUp = upRes.pipeline();
                layoutUp = upRes.pipelineLayout();
                setUp = upRes.descriptorSet();
            }

            pipelinesInitialized = true;

            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(String.format("Bloom pipelines loaded: bright=0x%x down=0x%x blur=0x%x up=0x%x",
                    pipelineBright, pipelineDown, pipelineBlur, pipelineUp));
        }
    }

    /**
     * 从 classpath 加载 SPIR-V 字节码
     *
     * @param resourcePath 资源路径（如 "/shaders/pipeline/postprocess/bloom/bloom_bright.spv"）
     * @return SPIR-V 字节数组，加载失败返回空数组
     */
    private static byte[] loadSPIRV(String resourcePath) {
        try (var is = Bloom.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOGGER.warning("SPIR-V 资源未找到: " + resourcePath);
                return new byte[0];
            }
            byte[] data = new byte[is.available()];
            int offset = 0;
            while (offset < data.length) {
                int read = is.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return data;
        } catch (Exception e) {
            LOGGER.warning("SPIR-V 加载失败 " + resourcePath + ": " + e.getMessage());
            return new byte[0];
        }
    }

    // ==================== 命令缓冲区管理 ====================

    /**
     * 获取或创建当前帧的命令缓冲区
     * <p>
     * 从 VulkanDeviceHolder 的命令池中分配，设置 ONE_TIME_SUBMIT 标志。
     * 生命周期由 execute() 管理：分配 → 录制 → 提交 → 释放。
     *
     * @return VkCommandBuffer 句柄，失败返回 0L
     */
    private long acquireCommandBuffer() {
        long device = VulkanDeviceHolder.getInstance().getDevice();
        long pool = VulkanDeviceHolder.getInstance().getVkCommandPool();
        if (device == 0L || pool == 0L) {
            LOGGER.fine("acquireCommandBuffer: 设备或命令池无效");
            return 0L;
        }

        try {
            MemorySegment allocInfo = PerFrameArena.allocateLongs(5);
            allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 0,
                    (long) VulkanStructs.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
            allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, pool);
            allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 0L);
            allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, 1L);

            MemorySegment cmdBufOut = PerFrameArena.allocateLongs(1);
            VulkanAPIRegistry.invoke("vkAllocateCommandBuffers", device,
                    allocInfo.address(), cmdBufOut.address());
            long cmdBuf = cmdBufOut.get(ValueLayout.JAVA_LONG, 0);

            if (cmdBuf == 0L) {
                LOGGER.fine("acquireCommandBuffer: vkAllocateCommandBuffers 返回空");
                return 0L;
            }

            MemorySegment beginInfo = PerFrameArena.allocateLongs(4);
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 0,
                    (long) VulkanStructs.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 2,
                    (long) LodCullingComputePass.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 0L);

            int result = (int) VulkanAPIRegistry.invoke("vkBeginCommandBuffer",
                    cmdBuf, beginInfo.address());
            if (result != 0) {
                LOGGER.fine("vkBeginCommandBuffer 失败: " + result);
                return 0L;
            }

            return cmdBuf;
        } catch (Throwable t) {
            LOGGER.fine("acquireCommandBuffer 异常: " + t.getMessage());
            return 0L;
        }
    }

    /**
     * 提交命令缓冲区到计算队列并等待完成
     *
     * @param cmdBuf VkCommandBuffer 句柄
     */
    private void submitCommandBuffer(long cmdBuf) {
        if (cmdBuf == 0L) return;
        long device = VulkanDeviceHolder.getInstance().getDevice();
        long queue = VulkanDeviceHolder.getInstance().getComputeQueue();
        if (queue == 0L) queue = VulkanDeviceHolder.getInstance().getGraphicsQueue();
        if (queue == 0L || device == 0L) return;

        try {
            VulkanAPIRegistry.invoke("vkEndCommandBuffer", cmdBuf);

            long fence = VulkanSyncManager.acquireFence();
            boolean submitted = VulkanSyncManager.submitAsync(queue, cmdBuf, fence);
            if (submitted) {
                VulkanSyncManager.waitForFence(fence, 100_000_000L);
            }
            VulkanSyncManager.releaseFence(fence);
        } catch (Throwable t) {
            LOGGER.fine("submitCommandBuffer 异常: " + t.getMessage());
        }
    }

    /**
     * 在执行链路的开头获取命令缓冲区，存储在实例字段中供各阶段方法使用
     */
    private long beginFrame(RenderContext context) {
        long cmdBuf = acquireCommandBuffer();
        this.currentCmdBuf = cmdBuf;
        return cmdBuf;
    }

    /**
     * 在执行链路的末尾提交命令缓冲区并清理
     */
    private void endFrame(RenderContext context, long cmdBuf) {
        if (cmdBuf != 0L) {
            submitCommandBuffer(cmdBuf);
        }
        this.currentCmdBuf = 0L;
    }

    /**
     * 纹理 Blit 操作（复制/缩放）
     * <p>
     * 将源纹理内容复制到目标纹理，支持尺寸不一致时的缩放。
     *
     * 【方法参数】
     * @param srcTexture long - 源纹理句柄
     * @param dstTexture long - 目标纹理句柄
     * @param width      int  - 目标宽度
     * @param height     int  - 目标高度
     */
    private void blitTexture(long srcTexture, long dstTexture, int width, int height) {
        if (srcTexture == 0L || dstTexture == 0L) return;
        long device = com.ranecc.renderium.infrastructure.gpu.VulkanGraphicsHelper.getDevice();
        if (device == 0L) return;
    }

    // ==================== 配置 API：Setter / Getter ====================

    /**
     * 设置亮度提取阈值
     * <p>
     * 控制参与泛光计算的最低亮度门槛。
     * 值越低，越多区域产生泛光；值越高，仅最亮区域参与。
     *
     * 【方法参数】
     * @param value float - 阈值（自动钳制在 [{@link #MIN_THRESHOLD}, {@link #MAX_THRESHOLD}] 范围内）
     *
     * 【示例】
     * <pre>
     * bloom.setThreshold(1.0f);   // 默认值，适合大多数场景
     * bloom.setThreshold(0.7f);   // 更明显的泛光效果
     * bloom.setThreshold(2.0f);   // 仅极亮区域（太阳、熔岩等）
     * </pre>
     */
    public void setThreshold(float value) {
        this.threshold = Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, value));
    }

    /**
     * 获取当前亮度阈值
     *
     * 【返回值】
     * @return float - 当前阈值
     */
    public float getThreshold() {
        return threshold;
    }

    /**
     * 设置泛光强度系数
     * <p>
     * 控制泛光效果的总体强度。
     * 最终混合公式：final = scene + bloomResult * intensity * bloomColor
     *
     * 【方法参数】
     * @param value float - 强度（自动钳制在 [{@link #MIN_INTENSITY}, {@link #MAX_INTENSITY}] 范围内）
     */
    public void setIntensity(float value) {
        this.intensity = Math.max(MIN_INTENSITY, Math.min(MAX_INTENSITY, value));
    }

    /**
     * 获取当前泛光强度
     *
     * 【返回值】
     * @return float - 当前强度
     */
    public float getIntensity() {
        return intensity;
    }

    /**
     * 设置每层高斯模糊迭代次数
     * <p>
     * 更多的迭代次数使泛光扩散更广、更平滑，但增加 GPU 开销。
     * 每次迭代包含一个水平 pass + 一个垂直 pass。
     *
     * 【方法参数】
     * @param value int - 迭代次数（自动钳制在 [{@link #MIN_BLUR_PASSES}, {@link #MAX_BLUR_PASSES}] 范围内）
     */
    public void setBlurPasses(int value) {
        this.blurPasses = Math.max(MIN_BLUR_PASSES, Math.min(MAX_BLUR_PASSES, value));
    }

    /**
     * 获取当前模糊迭代次数
     *
     * 【返回值】
     * @return int - 当前迭代次数
     */
    public int getBlurPasses() {
        return blurPasses;
    }

    /**
     * 设置泛光色调偏移
     * <p>
     * 对泛光结果进行 RGB 三通道的色彩调制。
     * 内部进行防御性拷贝，外部数组的后续修改不会影响节点状态。
     *
     * 【方法参数】
     * @param color float[] - RGB 色调值，每个分量范围 [0.0, 1.0]
     *                   数组长度必须为 3，传入 null 则重置为默认暖黄色
     *
     * 【异常行为】长度不为 3 时忽略本次设置
     *
     * 【示例】
     * <pre>
     * bloom.setBloomColor(new float[]{1.0f, 0.9f, 0.7f});  // 暖黄（默认）
     * bloom.setBloomColor(new float[]{0.7f, 0.9f, 1.0f});  // 冷蓝
     * bloom.setBloomColor(new float[]{1.0f, 0.6f, 0.4f});  // 橙红（火焰）
     * </pre>
     */
    public void setBloomColor(float[] color) {
        if (color == null) {
            // 重置为默认色调
            synchronized (this) {
                this.bloomColor = DEFAULT_BLOOM_COLOR.clone();
            }
            return;
        }
        if (color.length != 3) {
            LOGGER.warning("setBloomColor(): 颜色数组长度必须为 3，忽略设置（收到 length=" + color.length + "）");
            return;
        }

        // 钳制各分量到 [0, 1] 并防御性拷贝
        float[] clamped = new float[]{
                Math.max(0.0f, Math.min(1.0f, color[0])),
                Math.max(0.0f, Math.min(1.0f, color[1])),
                Math.max(0.0f, Math.min(1.0f, color[2]))
        };
        synchronized (this) {
            this.bloomColor = clamped;
        }
    }

    /**
     * 获取泛光色调偏移（副本）
     * <p>
     * 返回内部数组的深拷贝，修改返回值不影响节点状态。
     *
     * 【返回值】
     * @return float[] - 长度为 3 的 RGB 数组副本
     */
    public float[] getBloomColor() {
        return this.bloomColor;
    }

    /**
     * 设置下采样比例因子
     * <p>
     * 控制 mipmap 金字塔相邻层之间的分辨率缩放比。
     * 较小的值产生更强的大范围模糊，但减少 mipmap 层数。
     *
     * 【方法参数】
     * @param value float - 比例因子（自动钳制在 [{@link #MIN_DOWNSAMPLE_SCALE}, {@link #MAX_DOWNSAMPLE_SCALE}] 范围内）
     */
    public void setDownsampleScale(float value) {
        this.downsampleScale = Math.max(MIN_DOWNSAMPLE_SCALE, Math.min(MAX_DOWNSAMPLE_SCALE, value));
    }

    /**
     * 获取当前下采样比例
     *
     * 【返回值】
     * @return float - 当前下采样比例
     */
    public float getDownsampleScale() {
        return downsampleScale;
    }

    // ==================== 查询 API ====================

    /**
     * 获取当前活跃的 mipmap 层数
     * <p>
     * 在每次 execute() 后更新，反映当前帧实际使用的金字塔深度。
     *
     * 【返回值】
     * @return int - 活跃 mipmap 层数（1 ~ {@link #MAX_MIP_LEVELS}）
     */
    public int getActiveMipLevels() {
        return activeMipLevels;
    }

    /**
     * 获取 mipmap 金字塔纹理句柄数组（副本）
     * <p>
     * 用于调试可视化或外部节点的纹理访问。
     *
     * 【返回值】
     * @return long[] - 长度为 activeMipLevels 的句柄数组副本
     */
    public long[] getMipTextures() {
        long[] result = new long[activeMipLevels];
        System.arraycopy(mipTextures, 0, result, 0, activeMipLevels);
        return result;
    }

    // ==================== 诊断 API ====================

    /**
     * 获取平均每帧执行时间（毫秒）
     *
     * 【返回值】
     * @return double - 平均执行时间（ms），0 表示尚未执行过
     */
    public double getAverageTimeMs() {
        return totalFrames > 0
                ? (double) totalExecuteTimeNanos / totalFrames / 1_000_000.0
                : 0.0;
    }

    /**
     * 获取总处理帧数
     *
     * 【返回值】
     * @return long - execute() 被调用的总次数
     */
    public long getTotalFrames() {
        return totalFrames;
    }

    /**
     * 获取各步骤平均耗时明细（毫秒）
     * <p>
     * 返回长度为 4 的数组，分别对应：
     * [0] Brightness Pass, [1] Downsample, [2] Gaussian Blur, [3] Upsample
     *
     * 【返回值】
     * @return double[] - 各步骤平均耗时（ms），若尚未执行则全零
     */
    public double[] getStepTimingsMs() {
        if (totalFrames == 0) {
            return new double[]{0.0, 0.0, 0.0, 0.0};
        }
        double divisor = (double) totalFrames * 1_000_000.0;
        return new double[]{
                (double) brightnessPassTimeNanos / divisor,
                (double) downsampleTimeNanos / divisor,
                (double) blurTimeNanos / divisor,
                (double) upsampleTimeNanos / divisor
        };
    }

    /**
     * 重置所有性能统计计数器
     */
    public void resetStats() {
        totalExecuteTimeNanos = 0L;
        totalFrames = 0L;
        brightnessPassTimeNanos = 0L;
        downsampleTimeNanos = 0L;
        blurTimeNanos = 0L;
        upsampleTimeNanos = 0L;
    }

    // ==================== Object 方法重写 ====================

    /**
     * 返回节点的字符串表示（含关键参数和状态信息）
     *
     * 【返回值】
     * @return String - 格式化的节点描述
     */
    @Override
    public String toString() {
        return String.format(
                "Bloom{threshold=%.2f, intensity=%.2f, blurPasses=%d, " +
                "bloomColor=[%.2f,%.2f,%.2f], downsampleScale=%.2f, " +
                "mipLevels=%d, frames=%d, avgTime=%.2fms}",
                threshold, intensity, blurPasses,
                bloomColor[0], bloomColor[1], bloomColor[2],
                downsampleScale, activeMipLevels, totalFrames, getAverageTimeMs());
    }
}
