// Renderium - Frame Generation Context（帧生成上下文）
// 存储帧生成所需的所有资源和参数
// 由 SuperResolutionPass 或 FrameGenerationPass 构建，传递给 slEvaluateFeature

package com.renderium.gpu.framegen;

import java.util.Arrays;
import java.util.logging.Logger;

/**
 * 帧生成上下文数据结构
 * <p>
 * 封装 Streamline SDK 帧生成（DLSS-FG / FSR-FG）所需的所有输入资源和运行时参数。
 * 作为 {@code FrameGenerationPass.execute()} 的 context 参数传入，
 * 在渲染管线中从 SuperResolutionPass 传递到 FrameGenerationPass。
 *
 * <h3>生命周期：</h3>
 * <pre>
 * 1. 构造: new FrameGenContext(generateCount)     ← 设置倍率
 * 2. 配置: setTextureResources(...)               ← 绑定 Vulkan 纹理句柄
 * 3. 更新: updateJitter(phase)                    ← 每帧更新抖动相位
 * 4. 校验: isValid()                              ← 执行前校验完整性
 * 5. 使用: 传递给 FrameGenerationPass.execute()
 * 6. 重置: reset()                                ← 场景切换或禁用时清理
 * </pre>
 *
 * <h3>线程安全性：</h3>
 * 此类非线程安全。应在单一线程（渲染线程）中创建和使用。
 *
 * @see com.renderium.framegraph.pass.FrameGenerationPass
 * @since 5.2.0
 */
public final class FrameGenContext {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger("Renderium|FrameGenContext");

    // ==================== 核心配置 ====================

    /** 帧生成倍率 (2/3/4)，表示在两个渲染帧之间插入的插值帧数量 + 1 */
    private int generateCount;

    /**
     * 当前抖动相位 (0~63 for 8x8 模式)
     * <p>
     * 用于 TAA/Frame Generation 的亚像素抖动序列索引。
     * DLSS 4.5 推荐使用 8x8 模式（64 个采样点）。
     *
     * @see CameraJitterGenerator
     */
    private int jitterPhase;

    // ==================== 纹理资源句柄（Vulkan ImageView）====================

    /**
     * 当前帧颜色纹理 ImageView
     * <p>
     * 格式要求: R16G16B16A16_SFLOAT 或 B10G11R11_UFLOAT_PACK32
     * 必须包含无 UI/HUD 的纯净渲染结果
     */
    private long currentColorView;

    /**
     * 前一帧颜色纹理 ImageView（双缓冲）
     * <p>
     * 用于光流网络计算运动信息。
     * 每帧完成后与 currentColorView 交换。
     */
    private long previousColorView;

    /**
     * 运动矢量纹理 ImageView
     * <p>
     * 格式要求: RG16F 或 RG32F
     * 存储屏幕空间运动矢量 (motion_x, motion_y)
     * 范围: [-1.0, 1.0]（归一化到屏幕空间）
     * 如果为 0L，FrameGenerationPass 将跳过或内部生成
     */
    private long motionVectorView;

    /**
     * 深度纹理 ImageView
     * <p>
     * 格式要求: D32_SFLOAT 或 D24_UNORM_S8_UINT
     * 用于辅助运动矢量计算和遮挡处理
     * 推荐使用反向深度格式以提高精度
     */
    private long depthTextureView;

    /**
     * UI 蒙版纹理 ImageView
     * <p>
     * 格式要求: R8_UNORM 或 R8G8B8A8_UNORM
     * 用于标记 UI 元素区域（白色=UI区域，黑色=3D场景）
     * 帧生成完成后将原始 UI 覆盖回输出，防止 UI 插值产生重影伪影
     */
    private long uiMaskView;

    /**
     * 输出颜色纹理 ImageView
     * <p>
     * 帧生成结果写入目标。
     * 格式应与 currentColorView 一致。
     */
    private long outputColorView;

    // ==================== Jitter 数据 ====================

    /**
     * 当前帧抖动投影矩阵 (4×4, 列主序)
     * <p>
     * 在原始投影矩阵基础上添加亚像素偏移后的矩阵。
     * 由 CameraJitterGenerator.applyJitterToProjection() 生成。
     * DLSS/FG 需要此矩阵来正确重建几何边缘。
     */
    private float[] jitterMatrix;

    // ==================== 时间戳 ====================

    /**
     * 当前帧时间戳（纳秒）
     * <p>
     * 用于帧间隔计算和 Reflex 延迟测量基准点。
     * 通过 System.nanoTime() 获取。
     */
    private long frameTimeNs;

    // ==================== 构造函数 ====================

    /**
     * 创建帧生成上下文
     *
     * 【方法参数】
     * @param generateCount int - 帧生成倍率（2=2x, 3=3x, 4=4x）
     *                       有效范围: 2 ~ 4
     *                       典型值: 2（DLSS-FG 标准）
     *
     * 【返回值】FrameGenContext 实例
     *
     * 【异常处理】
     * - generateCount < 2 → 自动修正为 2
     * - generateCount > 4 → 自动修正为 4
     *
     * 【使用示例】
     * <pre>
     * FrameGenContext ctx = new FrameGenContext(2);  // 2x 帧生成
     * ctx.setTextureResources(currentView, prevView, mvView, depthView, uiMaskView, outputView);
     * ctx.updateJitter(frameIndex % 64);
     * </pre>
     */
    public FrameGenContext(int generateCount) {
        this.generateCount = clampGenerateCount(generateCount);
        this.jitterPhase = 0;
        this.jitterMatrix = new float[16];
        this.frameTimeNs = 0L;

        // 初始化所有纹理句柄为 0L（无效状态）
        this.currentColorView = 0L;
        this.previousColorView = 0L;
        this.motionVectorView = 0L;
        this.depthTextureView = 0L;
        this.uiMaskView = 0L;
        this.outputColorView = 0L;

        // 初始化抖动矩阵为单位矩阵（列主序）
        Arrays.fill(jitterMatrix, 0.0f);
        jitterMatrix[0] = 1.0f;   // [0][0]
        jitterMatrix[5] = 1.0f;   // [1][1]
        jitterMatrix[10] = 1.0f;  // [2][2]
        jitterMatrix[15] = 1.0f;  // [3][3]

        if (LOGGER.isLoggable(java.util.logging.Level.FINE)) {
            LOGGER.fine(String.format("FrameGenContext created [generateCount=%d]", this.generateCount));
        }
    }

    // ==================== 核心方法 ====================

    /**
     * 批量设置所有纹理资源句柄
     *
     * 【方法参数】
     * @param current  long - 当前帧颜色纹理 ImageView 句柄（必须 > 0）
     * @param previous long - 前一帧颜色纹理 ImageView 句柄（双缓冲，必须 > 0）
     * @param mv       long - 运动矢量纹理 ImageView 句柄（可选，可为 0L）
     * @param depth    long - 深度纹理 ImageView 句柄（推荐，可为 0L）
     * @param uiMask   long - UI 蒙版纹理 ImageView 句柄（推荐防止 UI 伪影，可为 0L）
     * @param output   long - 输出颜色纹理 ImageView 句柄（必须 > 0）
     *
     * 【返回值】void
     *
     * 【调用时机】
     * 在每帧渲染前调用，更新当前帧的纹理绑定。
     * 通常由 SuperResolutionPass 或渲染管线管理器负责填充。
     *
     * 【性能说明】
     * 此方法仅赋值操作，无内存分配，可安全每帧调用。
     */
    public void setTextureResources(long current, long previous, long mv,
                                     long depth, long uiMask, long output) {
        this.currentColorView = current;
        this.previousColorView = previous;
        this.motionVectorView = mv;
        this.depthTextureView = depth;
        this.uiMaskView = uiMask;
        this.outputColorView = output;
        this.frameTimeNs = System.nanoTime();
    }

    /**
     * 更新抖动相位和矩阵
     *
     * 【方法参数】
     * @param phase int - 新的抖动相位值
     *                 有效范围: 0 ~ 63（对于 8x8 模式）
     *                 超出范围将自动取模
     *
     * 【返回值】void
     *
     * 【实现细节】
     * 1. 更新 jitterPhase 字段（取模到有效范围）
     * 2. 从 CameraJitterGenerator 获取对应相位的抖动偏移
     * 3. 将偏移应用到基础投影矩阵的 [0][2] 和 [1][2] 元素
     *
     * 【调用时机】
     * 每帧渲染开始时调用，通常在 setTextureResources() 之后。
     *
     * 【依赖】
     * 需要 CameraJitterGenerator 已初始化并生成了 Halton 序列表。
     *
     * @see CameraJitterGenerator#getJitterOffset(int)
     */
    public void updateJitter(int phase) {
        this.jitterPhase = phase % 64;  // 8x8 模式最大 64 个相位

        // 使用 Halton(2,3) 序列生成低差异抖动偏移（与 DLSS 兼容）
        float[] offset = generateHaltonOffset(this.jitterPhase);

        // 应用到抖动矩阵（修改 [0][2]=index 8 和 [1][2]=index 9，列主序）
        if (jitterMatrix != null && jitterMatrix.length >= 16) {
            jitterMatrix[8] += offset[0];  // X 投影偏移
            jitterMatrix[9] += offset[1];  // Y 投影偏移
        }
    }

    /**
     * 使用 Halton(2,3) 低差异序列生成子像素抖动偏移
     * @param phase 抖动相位索引 (0~63)
     * @return 长度为 2 的 float 数组 [offsetX, offsetY]，范围 (-0.5, 0.5)
     */
    private static float[] generateHaltonOffset(int phase) {
        float x = halton(phase + 1, 2) - 0.5f;
        float y = halton(phase + 1, 3) - 0.5f;
        return new float[]{x, y};
    }

    /**
     * Halton 序列第 n 项计算（radix 进制）
     * @param index 索引（从 1 开始）
     * @param radix 基数（通常为质数，如 2、3）
     * @return 范围 [0, 1) 的 Halton 值
     */
    private static float halton(int index, int radix) {
        float result = 0.0f;
        float f = 1.0f / radix;
        int i = index;
        while (i > 0) {
            result += f * (i % radix);
            i = (int) Math.floor(i / (float) radix);
            f /= radix;
        }
        return result;
    }

    /**
     * 获取当前子像素 X 偏移
     *
     * 【方法参数】无
     *
     * 【返回值】float - X 方向亚像素偏移量，范围 (-0.5, 0.5)
     *
     * 【使用场景】
     * 用于调试 HUD 显示、自定义着色器中的抖动校正等。
     */
    public float getJitterOffsetX() {
        if (jitterMatrix != null && jitterMatrix.length > 8) {
            return jitterMatrix[8];
        }
        return 0.0f;
    }

    /**
     * 获取当前子像素 Y 偏移
     *
     * 【方法参数】无
     *
     * 【返回值】float - Y 方向亚像素偏移量，范围 (-0.5, 0.5)
     *
     * 【使用场景】
     * 用于调试 HUD 显示、自定义着色器中的抖动校正等。
     */
    public float getJitterOffsetY() {
        if (jitterMatrix != null && jitterMatrix.length > 9) {
            return jitterMatrix[9];
        }
        return 0.0f;
    }

    /**
     * 校验所有必填字段是否已设置
     *
     * 【方法参数】无
     *
     * 【返回值】boolean - true 表示上下文完整可用，false 表示缺少必要资源
     *
     * 【校验规则】
     * <ul>
     *   <li>currentColorView > 0（必须有当前帧颜色）</li>
     *   <li>previousColorView > 0（必须有前一帧用于运动估计）</li>
     *   <li>outputColorView > 0（必须有输出目标）</li>
     *   <li>generateCount 在 [2, 4] 范围内</li>
     * </ul>
     *
     * 【注意】
     * motionVectorView、depthTextureView、uiMaskView 为可选资源，
     * 缺失时不阻断执行，但可能影响质量或导致回退模式。
     *
     * 【调用时机】
     * 在 FrameGenerationPass.execute() 入口处调用，
     * 校验失败时降级为无帧生成模式（直接复制当前帧到输出）。
     */
    public boolean isValid() {
        boolean valid = currentColorView > 0L
                      && previousColorView > 0L
                      && outputColorView > 0L
                      && generateCount >= 2
                      && generateCount <= 4;

        if (!valid && LOGGER.isLoggable(java.util.logging.Level.WARNING)) {
            LOGGER.warning(String.format(
                "FrameGenContext validation failed [current=0x%s, prev=0x%s, output=0x%s, genCount=%d]",
                Long.toHexString(currentColorView),
                Long.toHexString(previousColorView),
                Long.toHexString(outputColorView),
                generateCount));
        }

        return valid;
    }

    /**
     * 重置所有字段到初始状态
     *
     * 【方法参数】无
     *
     * 【返回值】void
     *
     * 【使用场景】
     * <ul>
     *   <li>场景切换时清除历史数据</li>
     *   <li>禁用帧生成时释放引用</li>
     *   <li>窗口大小改变后重建资源前</li>
     * </ul>
     *
     * 【注意】
     * 此方法不释放 Vulkan 资源（由外部管理器负责），
     * 仅清零本地引用和状态。
     */
    public void reset() {
        this.generateCount = 2;
        this.jitterPhase = 0;
        this.currentColorView = 0L;
        this.previousColorView = 0L;
        this.motionVectorView = 0L;
        this.depthTextureView = 0L;
        this.uiMaskView = 0L;
        this.outputColorView = 0L;
        this.frameTimeNs = 0L;

        // 重置抖动矩阵为单位矩阵
        Arrays.fill(jitterMatrix, 0.0f);
        jitterMatrix[0] = 1.0f;
        jitterMatrix[5] = 1.0f;
        jitterMatrix[10] = 1.0f;
        jitterMatrix[15] = 1.0f;

        LOGGER.fine("FrameGenContext reset to initial state");
    }

    // ==================== Getter / Setter ====================

    public int getGenerateCount() { return generateCount; }
    public void setGenerateCount(int count) { this.generateCount = clampGenerateCount(count); }

    public int getJitterPhase() { return jitterPhase; }

    public long getCurrentColorView() { return currentColorView; }
    public long getPreviousColorView() { return previousColorView; }
    public long getMotionVectorView() { return motionVectorView; }
    public long getDepthTextureView() { return depthTextureView; }
    public long getUiMaskView() { return uiMaskView; }
    public long getOutputColorView() { return outputColorView; }

    public float[] getJitterMatrix() { return jitterMatrix; }
    public void setJitterMatrix(float[] matrix) {
        if (matrix != null && matrix.length == 16) {
            System.arraycopy(matrix, 0, this.jitterMatrix, 0, 16);
        }
    }

    public long getFrameTimeNs() { return frameTimeNs; }

    // ==================== 内部工具方法 ====================

    /**
     * 限制帧生成倍率到有效范围 [2, 4]
     *
     * @param count 输入值
     * @return 限制后的值
     */
    private static int clampGenerateCount(int count) {
        return Math.max(2, Math.min(4, count));
    }

    /**
     * 生成人类可读的状态摘要
     *
     * @return 格式化的字符串，包含所有关键参数和资源状态
     */
    @Override
    public String toString() {
        return String.format(
            "FrameGenContext{\n" +
            "  generateCount=%d,\n" +
            "  jitterPhase=%d,\n" +
            "  textures={\n" +
            "    currentColor=0x%s,\n" +
            "    previousColor=0x%s,\n" +
            "    motionVector=0x%s,\n" +
            "    depth=0x%s,\n" +
            "    uiMask=0x%s,\n" +
            "    outputColor=0x%s\n" +
            "  },\n" +
            "  jitter=(%.4f, %.4f),\n" +
            "  frameTimeNs=%d\n" +
            "}",
            generateCount,
            jitterPhase,
            Long.toHexString(currentColorView),
            Long.toHexString(previousColorView),
            Long.toHexString(motionVectorView),
            Long.toHexString(depthTextureView),
            Long.toHexString(uiMaskView),
            Long.toHexString(outputColorView),
            getJitterOffsetX(),
            getJitterOffsetY(),
            frameTimeNs
        );
    }
}
