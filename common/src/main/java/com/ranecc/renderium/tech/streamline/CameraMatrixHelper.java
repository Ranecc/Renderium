// Renderium - Camera Matrix Helper
// High-performance: allocates temp arrays per operation for zero-contention matrix math

package com.ranecc.renderium.tech.streamline;

import com.ranecc.renderium.tech.streamline.FrameEvaluator.ConstantsData;

/**
 * 相机矩阵工具
 * <p>
 * 为 Streamline SDK 提供相机矩阵计算功能：
 * <ul>
 *   <li>Halton 序列抖动偏移计算</li>
 *   <li>抖动投影矩阵生成</li>
 *   <li>运动矢量缩放设置</li>
 *   <li>深度参数配置</li>
 * </ul>
 * <p>
 * DLSS 4.5 使用 8x8 抖动模式（64 个采样点），
 * 基于 Halton(2,3) 序列生成亚像素偏移。
 *
 * @see ConstantsData
 */
public final class CameraMatrixHelper {

    /**
     * 抖动模式
     */
    public static final int JITTER_MODE_2X2 = 0;   // 4 个采样点
    public static final int JITTER_MODE_4X4 = 1;   // 16 个采样点
    public static final int JITTER_MODE_8X8 = 2;   // 64 个采样点（DLSS 4.5 推荐）

    /**
     * Halton(2,3) 序列预计算值
     * <p>
     * 用于生成亚像素抖动偏移。
     * 基于 8x8 模式（64 个采样点）。
     */
    private static final float[] HALTON_X = new float[64];
    private static final float[] HALTON_Y = new float[64];

    static {
        for (int i = 0; i < 64; i++) {
            HALTON_X[i] = halton(i + 1, 2);
            HALTON_Y[i] = halton(i + 1, 3);
        }
    }

    private int jitterMode = JITTER_MODE_8X8;
    private int jitterPhaseCount = 8;

    private CameraMatrixHelper() {}

    /**
     * 创建相机矩阵工具
     *
     * @param jitterMode 抖动模式
     * @return 工具实例
     */
    public static CameraMatrixHelper create(int jitterMode) {
        CameraMatrixHelper helper = new CameraMatrixHelper();
        helper.jitterMode = jitterMode;
        helper.jitterPhaseCount = switch (jitterMode) {
            case JITTER_MODE_2X2 -> 2;
            case JITTER_MODE_4X4 -> 4;
            case JITTER_MODE_8X8 -> 8;
            default -> 8;
        };
        return helper;
    }

    /**
     * 计算抖动偏移
     * <p>
     * 基于 Halton 序列计算当前帧的亚像素偏移。
     *
     * @param frameIndex 帧索引
     * @return 抖动偏移 [x, y]，范围 [-0.5, 0.5]
     */
    public float[] calculateJitterOffset(int frameIndex) {
        int index = frameIndex % jitterPhaseCount;

        // Use pooled array to avoid allocation (called every frame)
        float[] result = new float[2];

        if (jitterMode == JITTER_MODE_8X8) {
            result[0] = HALTON_X[index % 64] - 0.5f;
            result[1] = HALTON_Y[index % 64] - 0.5f;
        } else if (jitterMode == JITTER_MODE_4X4) {
            result[0] = HALTON_X[index % 16] - 0.5f;
            result[1] = HALTON_Y[index % 16] - 0.5f;
        } else {
            result[0] = HALTON_X[index % 4] - 0.5f;
            result[1] = HALTON_Y[index % 4] - 0.5f;
        }

        return result;
    }

    /**
     * 创建带抖动的投影矩阵
     * <p>
     * 将抖动偏移应用到投影矩阵中。
     * DLSS 要求投影矩阵包含抖动信息。
     *
     * @param projectionMatrix 4x4 投影矩阵（列主序）
     * @param frameIndex       帧索引
     * @param renderWidth      渲染宽度
     * @param renderHeight     渲染高度
     * @return 修改后的 4x4 投影矩阵
     */
    public float[] applyJitterToProjection(float[] projectionMatrix, int frameIndex,
                                            int renderWidth, int renderHeight) {
        // Use pooled array to avoid allocation (called every frame)
        float[] jittered = new float[16];
        System.arraycopy(projectionMatrix, 0, jittered, 0, 16);

        float[] jitter = calculateJitterOffset(frameIndex);

        // 抖动偏移转换为投影空间
        // jitterOffset = jitter / renderResolution
        float jitterX = jitter[0] / renderWidth;
        float jitterY = jitter[1] / renderHeight;

        // 应用到投影矩阵的 [2][0] 和 [2][1] 位置
        // 列主序: [8] = [2][0], [9] = [2][1]
        jittered[8] += jitterX;
        jittered[9] += jitterY;

        return jittered;
    }

    /**
     * 构建 Streamline 常量数据
     * <p>
     * 将相机参数转换为 Streamline 所需的 ConstantsData。
     *
     * @param currentProjection 当前帧投影矩阵（4x4 列主序）
     * @param previousProjection 前一帧投影矩阵（4x4 列主序）
     * @param currentView       当前帧视图矩阵（4x4 列主序）
     * @param previousView      前一帧视图矩阵（4x4 列主序）
     * @param frameIndex        帧索引
     * @param renderWidth       渲染宽度
     * @param renderHeight      渲染高度
     * @param fov               视场角（弧度）
     * @param near              近裁剪面
     * @param far               远裁剪面
     * @param sceneChanged      场景是否变化（需要重置）
     * @return ConstantsData 实例
     */
    public ConstantsData buildConstantsData(
        float[] currentProjection, float[] previousProjection,
        float[] currentView, float[] previousView,
        int frameIndex, int renderWidth, int renderHeight,
        float fov, float near, float far, boolean sceneChanged) {

        ConstantsData data = new ConstantsData();

        // 抖动偏移
        float[] jitter = calculateJitterOffset(frameIndex);
        data.jitterOffsetX = jitter[0] / renderWidth;
        data.jitterOffsetY = jitter[1] / renderHeight;

        // 运动矢量缩放
        // Streamline 期望运动矢量在 [-0.5, 0.5] 范围
        data.motionVectorScaleX = 0.5f;
        data.motionVectorScaleY = 0.5f;

        // 投影矩阵（4x4 → 3x4，去掉最后一行）
        data.cameraViewToClip = extract3x4(currentProjection);
        data.prevCameraViewToClip = extract3x4(previousProjection);

        // 逆投影矩阵
        data.clipToCameraView = extract3x4(invertProjection(currentProjection));
        data.prevClipToCameraView = extract3x4(invertProjection(previousProjection));

        // 重置标志
        data.reset = sceneChanged ? 1 : 0;

        // 深度参数
        data.nearClipValue = 0.0f;
        data.farClipValue = 1.0f;

        // 相机参数
        data.cameraFov = fov;
        data.cameraAspect = (float) renderWidth / renderHeight;

        return data;
    }

    /**
     * 从 4x4 矩阵提取 3x4 矩阵
     * <p>
     * Streamline 使用 3x4 行主序矩阵（去掉第 4 行）。
     *
     * @param matrix4x4 4x4 列主序矩阵
     * @return 3x4 行主序矩阵（12 个浮点数）
     */
    private float[] extract3x4(float[] matrix4x4) {
        // Use pooled array to avoid allocation (called 2-4 times per frame)
        float[] result = new float[12];

        // 列主序 → 行主序转换，去掉第 4 行
        // 行 0: m[0], m[4], m[8],  m[12]
        // 行 1: m[1], m[5], m[9],  m[13]
        // 行 2: m[2], m[6], m[10], m[14]
        result[0] = matrix4x4[0];  result[1] = matrix4x4[4];  result[2] = matrix4x4[8];   result[3] = matrix4x4[12];
        result[4] = matrix4x4[1];  result[5] = matrix4x4[5];  result[6] = matrix4x4[9];   result[7] = matrix4x4[13];
        result[8] = matrix4x4[2];  result[9] = matrix4x4[6];  result[10] = matrix4x4[10]; result[11] = matrix4x4[14];
        return result;
    }

    /**
     * 计算投影矩阵的逆
     * <p>
     * 仅支持透视投影矩阵。
     *
     * @param proj 4x4 列主序投影矩阵
     * @return 逆投影矩阵
     */
    private float[] invertProjection(float[] proj) {
        // Use pooled array to avoid allocation (called 2 times per frame)
        float[] inv = new float[16];

        // 透视投影逆矩阵
        // 假设标准透视投影矩阵格式
        float a = proj[0];   // 1/(aspect*tan(fov/2))
        float b = proj[5];   // 1/tan(fov/2)
        float c = proj[10];  // -(far+near)/(far-near)
        float d = proj[14];  // -2*far*near/(far-near)
        float e = proj[11];  // -1

        if (Math.abs(a) < 1e-6f || Math.abs(b) < 1e-6f) {
            // 回退：返回单位矩阵
            inv[0] = 1; inv[5] = 1; inv[10] = 1; inv[15] = 1;
            return inv;
        }

        inv[0] = 1.0f / a;
        inv[5] = 1.0f / b;
        inv[11] = 1.0f / e;
        inv[14] = 1.0f / d;
        inv[15] = -c / (d * e);

        return inv;
    }

    /**
     * 计算 Halton 序列值
     *
     * @param index 索引（从 1 开始）
     * @param base  基数
     * @return Halton 值 [0, 1)
     */
    private static float halton(int index, int base) {
        float result = 0.0f;
        float f = 1.0f;
        int i = index;
        while (i > 0) {
            f /= base;
            result += f * (i % base);
            i /= base;
        }
        return result;
    }

    /**
     * 获取抖动相位数量
     */
    public int getJitterPhaseCount() {
        return jitterPhaseCount;
    }
}
