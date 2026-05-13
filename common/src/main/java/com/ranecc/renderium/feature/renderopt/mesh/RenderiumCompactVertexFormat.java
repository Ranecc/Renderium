// Renderium - 紧凑顶点格式
// 区块网格的高效顶点数据表示，适配 Vulkan CommandBuffer 管线

package com.ranecc.renderium.feature.renderopt.mesh;

/**
 * Renderium 紧凑顶点格式实现。
 *
 * <p>算法思路与内存布局参考了 CaffeineMC 的 Sodium 项目 (LGPL-3.0)。
 * 本代码为 Renderium 团队完全独立重写，以适配 Vulkan CommandBuffer 管线。
 *
 * <h2>设计原理</h2>
 * <p>原版 Minecraft 使用浮点数存储每个顶点的位置、颜色、纹理坐标和光照信息，
 * 每个顶点占用约 40-48 字节。对于包含大量方块的区块（16x16x16 = 4096 个方块），
 * 这会导致显存占用过高和 GPU 带宽浪费。
 *
 * <p>本格式使用位域压缩技术将顶点数据压缩到 20 字节/顶点：
 * <ul>
 *   <li><b>位置</b>：20-bit 量化，覆盖 [-8, +24] 范围（相对于区块原点），精度约 0.03mm</li>
 *   <li><b>颜色</b>：ARGB 格式，混合环境光遮蔽（AO）</li>
 *   <li><b>纹理坐标</b>：15-bit 量化，带偏移符号用于纹理抗渗漏</li>
 *   <li><b>光照+材质</b>：8-bit 方块光照 + 8-bit 天空光照 + 8-bit 材质索引 + 8-bit 区段索引</li>
 * </ul>
 *
 * <h2>内存布局（20 字节/顶点）</h2>
 * <pre>
 * 偏移 | 大小 | 描述
 * ------|------|------------------------------------------
 * 0     | 4B   | 位置高半部分 (x[19:10], y[19:10], z[19:10])
 * 4     | 4B   | 位置低半部分 (x[9:0], y[9:0], z[9:0])
 * 8     | 4B   | 颜色 ARGB (含 AO)
 * 12    | 4B   | 纹理坐标 UV (u, v 各 15-bit + 符号位)
 * 16    | 4B   | 光照 (block, sky) + 材质索引 + 区段索引
 * </pre>
 *
 * @see <a href="https://github.com/CaffeineMC/sodium">Sodium Repository</a>
 * @author Renderium Team
 * @since 1.0.0
 */
public final class RenderiumCompactVertexFormat {

    /** 单个顶点的字节大小：20 字节 */
    public static final int STRIDE_BYTES = 20;

    /** 单个区块的最大顶点数估算（保守估计） */
    public static final int MAX_VERTICES_PER_SECTION = 65536;

    /** 位置量化的最大值：2^20 = 1048576 */
    private static final int POSITION_QUANTIZATION_MAX = 1 << 20;

    /** 纹理坐标量化的最大值：2^15 = 32768 */
    private static final int TEXTURE_QUANTIZATION_MAX = 1 << 15;

    /** 模型坐标系原点偏移：8.0（使范围变为 [-8, +24]） */
    private static final float MODEL_ORIGIN_OFFSET = 8.0f;

    /** 模型坐标系总范围：32.0（从 -8 到 +24） */
    private static final float MODEL_RANGE = 32.0f;

    // ==================== 位置编码/解码 ====================

    /**
     * 将浮点位置坐标量化为 20-bit 整数
     *
     * @param position 原始浮点坐标（相对于区块局部坐标）
     * @return 20-bit 量化后的整数值 [0, 1048575]
     */
    public static int quantizePosition(float position) {
        float normalized = normalizePosition(position);
        return ((int) (normalized * POSITION_QUANTIZATION_MAX)) & 0xFFFFF;
    }

    /**
     * 将浮点坐标归一化到 [0, 1] 范围
     *
     * @param value 原始浮点值
     * @return 归一化后的值
     */
    public static float normalizePosition(float value) {
        return (MODEL_ORIGIN_OFFSET + value) / MODEL_RANGE;
    }

    /**
     * 将三个轴的位置打包为高位 int（各取高 10 位）
     *
     * @param x 20-bit 量化后的 X 坐标
     * @param y 20-bit 量化后的 Y 坐标
     * @param z 20-bit 量化后的 Z 坐标
     * @return 打包后的高位 int：x[19:10] | y[19:10] | z[19:10]
     */
    public static int packPositionHigh(int x, int y, int z) {
        return (((x >>> 10) & 0x3FF) << 0) |
               (((y >>> 10) & 0x3FF) << 10) |
               (((z >>> 10) & 0x3FF) << 20);
    }

    /**
     * 将三个轴的位置打包为低位 int（各取低 10 位）
     *
     * @param x 20-bit 量化后的 X 坐标
     * @param y 20-bit 量化后的 Y 坐标
     * @param z 20-bit 量化后的 Z 坐标
     * @return 打包后的低位 int：x[9:0] | y[9:0] | z[9:0]
     */
    public static int packPositionLow(int x, int y, int z) {
        return ((x & 0x3FF) << 0) |
               ((y & 0x3FF) << 10) |
               ((z & 0x3FF) << 20);
    }

    // ==================== 纹理坐标编码 ====================

    /**
     * 编码单个纹理坐标分量
     *
     * <p>使用"中心偏移"技术减少纹理渗漏：
     * 计算四边形所有顶点的质心，然后将每个顶点向质心方向偏移一个最小单位，
     * 并在最高位记录偏移符号。GPU 端通过微小 epsilon 还原。
     *
     * @param center 该四边形的纹理坐标质心
     * @param value 当前顶点的纹理坐标值
     * @return 16-bit 编码值（15-bit 数据 + 1-bit 符号）
     */
    public static int encodeTextureCoordinate(float center, float value) {
        // 判断当前值在质心的哪一侧
        int bias = (value < center) ? 1 : -1;
        // 量化并应用偏移
        int quantized = Math.round(value * TEXTURE_QUANTIZATION_MAX) + bias;
        // 打包：低 15 位是数据，最高位是符号
        return (quantized & 0x7FFF) | (signBit(bias) << 15);
    }

    /**
     * 打包 U 和 V 纹理坐标到一个 int
     *
     * @param u 编码后的 U 坐标（16-bit）
     * @param v 编码后的 V 坐标（16-bit）
     * @return 打包结果：u 在低 16 位，v 在高 16 位
     */
    public static int packTextureUV(int u, int v) {
        return ((u & 0xFFFF) << 0) | ((v & 0xFFFF) << 16);
    }

    // ==================== 光照编码 ====================

    /**
     * 编码光照值
     *
     * <p>Minecraft 的光照系统使用两个独立的 8-bit 通道：
     * 方块光照（来自光源方块）和天空光照（来自天空）。
     * 原始值范围 [0, 15]，存储时左移 4 位变为 [0, 240]。
     * 此处添加 +8 偏移以避免零值导致的黑暗问题。
     *
     * @param rawLight 原始光照值（packed format: sky << 16 | block）
     * @return 编码后的 16-bit 光照值：block[7:0] | sky[15:8]
     */
    public static int encodeLight(int rawLight) {
        int blockLight = clampLight((rawLight >>> 0) & 0xFF);
        int skyLight = clampLight((rawLight >>> 16) & 0xFF);
        return (blockLight << 0) | (skyLight << 8);
    }

    /**
     * 钳制光照值到有效范围 [8, 248]
     *
     * <p>添加 +8 偏移确保最暗区域不会完全黑死
     *
     * @param light 原始光照分量
     * @return 钳制后的值
     */
    private static int clampLight(int light) {
        return Math.max(8, Math.min(248, light + 8));
    }

    /**
     * 打包光照、材质索引和区段索引到一个 int
     *
     * @param encodedLight 编码后的 16-bit 光照
     * @param materialBits 8-bit 材质索引
     * @param sectionIndex 8-bit 区段索引
     * @return 打包结果：light[15:0] | material[23:16] | section[31:24]
     */
    public static int packLightAndMaterial(int encodedLight, int materialBits, int sectionIndex) {
        return ((encodedLight & 0xFFFF) << 0) |
               ((materialBits & 0xFF) << 16) |
               ((sectionIndex & 0xFF) << 24);
    }

    // ==================== 颜色处理 ====================

    /**
     * 混合颜色与环境光遮蔽（AO）
     *
     * <p>AO 值范围 [0, 255]，其中 255 表示完全无遮挡（最亮）。
     * 通过逐通道乘法将 AO 效果应用到颜色上。
     *
     * @param argb ARGB 颜色值（32-bit packed）
     * @param ao 环境光遮蔽值 [0, 255]
     * @return 混合后的 ARGB 颜色
     */
    public static int multiplyColorByAO(int argb, int ao) {
        if (ao == 255) {
            return argb; // 完全无遮挡，跳过计算
        }
        int a = (argb >>> 24) & 0xFF;
        int r = multiplyChannel((argb >>> 16) & 0xFF, ao);
        int g = multiplyChannel((argb >>> 8) & 0xFF, ao);
        int b = multiplyChannel(argb & 0xFF, ao);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * 单通道颜色乘法
     *
     * @param color 颜色通道值 [0, 255]
     * @param ao AO 值 [0, 255]
     * @return 混合后的通道值
     */
    private static int multiplyChannel(int color, int ao) {
        return (color * (ao + 1)) >> 8;
    }

    // ==================== 工具方法 ====================

    /**
     * 提取整数的符号位（映射到 0 或 1）
     *
     * @param value 输入值
     * @return 0 如果非负，1 如果负
     */
    private static int signBit(int value) {
        return (value >>> 31);
    }

    /**
     * 计算给定字节缓冲区能容纳的最大顶点数
     *
     * @param bufferSize 缓冲区大小（字节）
     * @return 最大顶点数
     */
    public static int maxVerticesForBufferSize(int bufferSize) {
        return bufferSize / STRIDE_BYTES;
    }
}
