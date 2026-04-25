// Renderium - CameraJitterGenerator（相机子像素抖动序列生成器）
// 基于 Halton(2,3) 低差异序列，支持多模式预计算
// 单例模式，线程安全

package com.renderium.gpu.framegen;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * 相机子像素抖动序列生成器（单例模式）
 * <p>
 * 使用 Halton(2,3) 低差异序列为 TAA/Frame Generation 生成优化的亚像素采样偏移。
 * 支持 2×2、4×4、8×8 三种抖动模式，预计算所有相位以实现 O(1) 查询。
 *
 * <h3>设计原理：</h3>
 * <pre>
 * Halton 序列是一种低差异序列（Low-Discrepancy Sequence），
 * 在单位超立方体 [0,1)^d 中提供比纯随机更均匀的采样分布。
 * 对于 TAA/FG 抖动：
 *   - X 轴使用 Halton(2)（二进制基数）
 *   - Y 轴使用 Halton(3)（三进制基数）
 *   - 这种组合在屏幕空间中产生良好的覆盖性
 * </pre>
 *
 * <h3>线程安全性：</h3>
 * <ul>
 *   <li>读操作（getJitterOffset）无锁，通过 final 数组保证可见性</li>
 *   <li>写操作（setJitterMode、recompute）使用 ReentrantLock 保护</li>
 *   <li>初始化采用双重检查锁定（DCL）+ volatile</li>
 * </ul>
 *
 * <h3>性能特征：</h3>
 * <ul>
 *   <li>查询耗时: O(1) - 直接数组索引</li>
 *   <li>内存占用: ~2KB（64个相位 × 2个分量 × 3种模式 × float）</li>
 *   <li>预计算开销: 仅在切换模式时发生（~微秒级）</li>
 * </ul>
 *
 * @see FrameGenContext
 * @since 5.2.0
 */
public final class CameraJitterGenerator {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger("Renderium|CameraJitterGen");

    // ==================== 单例实例 ====================

    /** volatile 保证 DCL 的可见性 */
    private static volatile CameraJitterGenerator instance;

    /** 单例锁对象 */
    private static final Object INSTANCE_LOCK = new Object();

    // ==================== 抖动模式枚举 ====================

    /**
     * 抖动模式
     * <p>
     * 定义采样网格的大小，影响 TAA/FG 的质量和性能权衡。
     */
    public enum JitterMode {
        /** 2×2 网格（4 个相位）- 最低质量，最快收敛 */
        MODE_2X2(2, 4),

        /** 4×4 网格（16 个相位）- 平衡质量和性能 */
        MODE_4X4(4, 16),

        /** 8×8 网格（64 个相位）- 最高质量，DLSS 推荐 */
        MODE_8X8(8, 64);

        /** 网格边长 */
        public final int gridSize;

        /** 总相位数（gridSize²）*/
        public final int phaseCount;

        JitterMode(int gridSize, int phaseCount) {
            this.gridSize = gridSize;
            this.phaseCount = phaseCount;
        }
    }

    // ==================== 预计算数据 ====================

    /**
     * 预计算的抖动偏移表
     * <p>
     * 三维数组索引: [modeOrdinal][phase][component]
     * - modeOrdinal: 0=2x2, 1=4x4, 2=8x8
     * - phase: 0 ~ mode.phaseCount-1
     * - component: 0=X offset, 1=Y offset
     * <p>
     * 所有值范围 (-0.5, 0.5)，已从 Halton [0,1) 映射到 [-0.5, 0.5)
     */
    private final float[][][] jitterTable;

    /** 当前活跃的模式 */
    private volatile JitterMode currentMode;

    /** 保护写操作的锁 */
    private final ReentrantLock writeLock = new ReentrantLock();

    // ==================== 构造函数（私有）====================

    /**
     * 私有构造函数 - 初始化所有模式的预计算表
     * <p>
     * 在构造时一次性计算所有三种模式的完整 Halton 序列，
     * 后续切换模式只需更改指针，无需重新计算。
     */
    private CameraJitterGenerator() {
        JitterMode[] modes = JitterMode.values();
        this.jitterTable = new float[modes.length][][];

        for (JitterMode mode : modes) {
            this.jitterTable[mode.ordinal()] = computeHaltonSequence(mode);
        }

        this.currentMode = JitterMode.MODE_8X8;  // 默认使用最高质量模式

        LOGGER.info(String.format(
            "CameraJitterGenerator initialized [modes=%d, default=%s]",
            modes.length, currentMode
        ));
    }

    // ==================== 公共 API ====================

    /**
     * 获取单例实例（线程安全的双重检查锁定）
     *
     * 【方法参数】无
     *
     * 【返回值】CameraJitterGenerator - 全局唯一的生成器实例
     *
     * 【线程安全】是 - 使用 DCL + volatile 保证
     *
     * 【调用示例】
     * <pre>
     * CameraJitterGenerator gen = CameraJitterGenerator.getInstance();
     * float[] offset = gen.getJitterOffset(frameIndex % 64);
     * </pre>
     */
    public static CameraJitterGenerator getInstance() {
        if (instance == null) {  // 第一次检查（无锁）
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {  // 第二次检查（有锁）
                    instance = new CameraJitterGenerator();
                }
            }
        }
        return instance;
    }

    /**
     * 获取指定相位的抖动偏移量（O(1) 查询）
     *
     * 【方法参数】
     * @param phase int - 抖动相位索引
     *               有效范围: 0 ~ currentMode.phaseCount-1
     *               超出范围将自动取模
     *
     * 【返回值】float[2] - 长度为 2 的数组
     *                     [0] = X 方向偏移，范围 (-0.5, 0.5)
     *                     [1] = Y 方向偏移，范围 (-0.5, 0.5)
     *
     * 【线程安全】是 - 读操作无锁（final 数组 + volatile mode）
     *
     * 【性能说明】
     * 此方法是渲染管线的热路径组件，每帧调用一次。
     * 通过预计算确保 O(1) 复杂度，避免运行时 Halton 计算。
     *
     * 【使用场景】
     * <pre>
     * // 在帧循环中
     * int phase = frameCount % gen.getPhaseCount();
     * float[] jitter = gen.getJitterOffset(phase);
     * projectionMatrix[0][2] += jitter[0];  // 应用 X 抖动
     * projectionMatrix[1][2] += jitter[1];  // 应用 Y 抖动
     * </pre>
     */
    public float[] getJitterOffset(int phase) {
        JitterMode mode = currentMode;
        int safePhase = Math.abs(phase) % mode.phaseCount;

        // 返回副本以防止外部修改内部状态
        return new float[]{
            jitterTable[mode.ordinal()][safePhase][0],
            jitterTable[mode.ordinal()][safePhase][1]
        };
    }

    /**
     * 获取当前模式的相位总数
     *
     * 【方法参数】无
     *
     * 【返回值】int - 当前抖动模式的相位数量
     *              2x2 → 4, 4x4 → 16, 8x8 → 64
     */
    public int getPhaseCount() {
        return currentMode.phaseCount;
    }

    /**
     * 切换抖动模式（线程安全）
     *
     * 【方法参数】
     * @param mode JitterMode - 目标抖动模式
     *                      可选值: MODE_2X2, MODE_4X4, MODE_8X8
     *
     * 【返回值】void
     *
     * 【线程安全】是 - 使用 ReentrantLock 保护
     *
     * 【性能影响】
     * 模式切换本身是 O(1) 操作（仅指针赋值），
     * 但建议仅在设置菜单或场景切换时调用，避免每帧切换。
     *
     * 【调用时机示例】
     * <pre>
     * // 用户在设置界面选择 TAA 质量
     * switch (qualitySetting) {
     *     case LOW:
     *         CameraJitterGenerator.getInstance().setJitterMode(JitterMode.MODE_2X2);
     *         break;
     *     case MEDIUM:
     *         CameraJitterGenerator.getInstance().setJitterMode(JitterMode.MODE_4X4);
     *         break;
     *     case HIGH:
     *         CameraJitterGenerator.getInstance().setJitterMode(JitterMode.MODE_8X8);
     *         break;
     * }
     * </pre>
     */
    public void setJitterMode(JitterMode mode) {
        if (mode == null) {
            LOGGER.warning("setJitterMode: mode is null, ignoring");
            return;
        }

        writeLock.lock();
        try {
            if (this.currentMode != mode) {
                JitterMode oldMode = this.currentMode;
                this.currentMode = mode;

                LOGGER.info(String.format(
                    "Jitter mode changed: %s → %s [phases: %d → %d]",
                    oldMode, mode,
                    oldMode.phaseCount,
                    mode.phaseCount
                ));
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 获取当前抖动模式
     *
     * 【方法参数】无
     *
     * 【返回值】JitterMode - 当前活跃的抖动模式
     */
    public JitterMode getCurrentMode() {
        return currentMode;
    }

    // ==================== 内部算法 ====================

    /**
     * 计算指定模式的完整 Halton(2,3) 序列表
     *
     * 【方法参数】
     * @param mode JitterMode - 目标抖动模式
     *
     * 【返回值】float[phaseCount][2] - 二维数组
     *           每行是一个 [x, y] 偏移对
     *
     * 【算法细节】
     * <pre>
     * 对于每个相位 i ∈ [0, phaseCount):
     *   x[i] = halton(i+1, 2) - 0.5  // 使用基数 2
     *   y[i] = halton(i+1, 3) - 0.5  // 使用基数 3
     *
     * Halton(n, radix) 实现：
     *   result = 0
     *   f = 1/radix
     *   while n > 0:
     *     result += f * (n % radix)
     *     n = floor(n / radix)
     *     f /= radix
     * </pre>
     *
     * 【数学依据】
     * Halton 序列的 discrepancy 上界为：
     * D_N* = O((log N)^d / N)
     * 其中 d 是维度数（此处 d=2），N 是样本数。
     * 这比随机采样的 O(N^{-1/2}) 收敛速度更快。
     */
    private static float[][] computeHaltonSequence(JitterMode mode) {
        int count = mode.phaseCount;
        float[][] sequence = new float[count][2];

        for (int i = 0; i < count; i++) {
            // Halton 序列索引从 1 开始（0 会产生全零序列）
            sequence[i][0] = halton(i + 1, 2) - 0.5f;  // X 轴：基数 2
            sequence[i][1] = halton(i + 1, 3) - 0.5f;  // Y 轴：基数 3
        }

        return sequence;
    }

    /**
     * Halton 序列第 n 项计算（radix 进制）
     *
     * 【方法参数】
     * @param index int - 样本索引（从 1 开始）
     * @param radix int - 进制基数（质数，如 2、3、5、7...）
     *
     * 【返回值】float - 范围 [0.0, 1.0) 的 Halton 值
     *
     * 【数值稳定性】
     * 使用 float 运算而非 double 以匹配 GPU shader 的精度。
     * 对于最大 64 个样本（8×8 模式），float 精度足够。
     *
     * 【时间复杂度】O(log_radix(index))
     * 空间复杂度: O(1)（迭代实现，无递归栈）
     */
    private static float halton(int index, int radix) {
        float result = 0.0f;
        float f = 1.0f / radix;
        int n = index;

        while (n > 0) {
            result += f * (n % radix);  // 提取最低位数字并累加
            n = (int) Math.floor(n / (float) radix);  // 整除去掉已处理位
            f /= radix;  // 下一位权重降低
        }

        return result;
    }

    // ==================== 调试与诊断 ====================

    /**
     * 生成当前模式的可视化字符串（用于调试 HUD）
     *
     * 【方法参数】无
     *
     * 【返回值】String - 格式化的模式信息
     *
     * 【输出示例】
     * <pre>
     * CameraJitterGenerator{mode=MODE_8X8, phases=64, sample=[(-0.25, +0.12)]}
     * </pre>
     */
    @Override
    public String toString() {
        float[] sample = getJitterOffset(0);
        return String.format(
            "CameraJitterGenerator{mode=%s, phases=%d, sample=[(%.3f, %.3f)]}",
            currentMode,
            currentMode.phaseCount,
            sample[0],
            sample[1]
        );
    }

    /**
     * 导出当前模式的完整序列（用于单元测试或离线分析）
     *
     * 【方法参数】无
     *
     * 【返回值】float[][] - 当前模式的所有相位偏移的深拷贝
     */
    public float[][] exportCurrentSequence() {
        JitterMode mode = currentMode;
        float[][] copy = new float[mode.phaseCount][];
        for (int i = 0; i < mode.phaseCount; i++) {
            copy[i] = getJitterOffset(i);
        }
        return copy;
    }
}
