// Renderium - 管线状态位定义
// 定义所有 OpenGL 状态的位域布局，用于高效的状态快照和 Hash 计算
// 参考: OpenGL 4.6 Specification / Vulkan Pipeline State

package com.renderium.graphics.pipeline;

/**
 * 管线状态位定义
 * <p>
 * 将所有 OpenGL 渲染状态编码为紧凑的位域，用于：
 * <ul>
 *   <li>快速状态比较（判断是否需要切换 Pipeline）</li>
 *   <li>高效的 Hash 计算（用于 Pipeline Cache 查找）</li>
 *   <li>最小化内存占用（Copy-on-Write 快照）</li>
 * </ul>
 * <p>
 * 位域布局设计原则：
 * <ol>
 *   <li>高频变化的状态放在低位（减少 Hash 冲突概率）</li>
 *   <li>相关联的状态分组存放</li>
 *   <li>预留扩展位给未来功能</li>
 * </ol>
 *
 * @see RenderPipeline
 * @see com.renderium.compatibility.StateSnapshot
 */
public final class PipelineStateBits {

    // ========== 防止实例化 ==========
    private PipelineStateBits() {}

    // ==================== 位域布局总览 ====================
    //
    // long[0]: 混合与颜色状态 (0-63)
    // long[1]: 深度与模板状态 (64-127)
    // long[2]: 光栅化状态 (128-191)
    // long[3]: 多重采样与遮罩 (192-255)
    // long[4-7]: 扩展预留 (256-511)
    //
    // 总计: 8个long = 512位 = 可表示512种独立开关/枚举状态
    // 实际使用约 150-200 位，其余为预留

    // ==================== long[0]: 混合与颜色状态 ====================

    /** 混合启用标志位偏移 */
    public static final int BLEND_ENABLED_OFFSET = 0;
    /** 混合启用标志位掩码 */
    public static final long BLEND_ENABLED_MASK = 1L << BLEND_ENABLED_OFFSET;

    /**
     * 混合模式枚举 (3位，8种组合)
     * 常见模式：
     * 0 = ZERO
     * 1 = ONE
     * 2 = SRC_COLOR
     * 3 = ONE_MINUS_SRC_COLOR
     * 4 = DST_COLOR
     * 5 = ONE_MINUS_DST_COLOR
     * 6 = SRC_ALPHA
     * 7 = ONE_MINUS_SRC_ALPHA
     */
    public static final int SRC_BLEND_FACTOR_OFFSET = 1;
    public static final long SRC_BLEND_FACTOR_MASK = 0x7L << SRC_BLEND_FACTOR_OFFSET;

    /** 目标混合因子 (3位) */
    public static final int DST_BLEND_FACTOR_OFFSET = 4;
    public static final long DST_BLEND_FACTOR_MASK = 0x7L << DST_BLEND_FACTOR_OFFSET;

    /** Alpha 混合因子 (3位) */
    public static final int SRC_ALPHA_BLEND_FACTOR_OFFSET = 7;
    public static final long SRC_ALPHA_BLEND_FACTOR_MASK = 0x7L << SRC_ALPHA_BLEND_FACTOR_OFFSET;

    /** 目标 Alpha 混合因子 (3位) */
    public static final int DST_ALPHA_BLEND_FACTOR_OFFSET = 10;
    public static final long DST_ALPHA_BLEND_FACTOR_MASK = 0x7L << DST_ALPHA_BLEND_FACTOR_OFFSET;

    /** 混合方程式 (2位: ADD, SUBTRACT, REVERSE_SUBTRACT, MIN, MAX) */
    public static final int BLEND_EQUATION_OFFSET = 13;
    public static final long BLEND_EQUATION_MASK = 0x3L << BLEND_EQUATION_OFFSET;

    /** 颜色写入掩码 (4位: R/G/B/A 各1位) */
    public static final int COLOR_WRITE_MASK_OFFSET = 15;
    public static final long COLOR_WRITE_MASK_MASK = 0xFL << COLOR_WRITE_MASK_OFFSET;

    // ==================== long[1]: 深度与模板状态 ====================

    /** 深度测试启用标志 */
    public static final int DEPTH_TEST_ENABLED_OFFSET = 32;
    public static final long DEPTH_TEST_ENABLED_MASK = 1L << DEPTH_TEST_ENABLED_OFFSET;

    /** 深度写入启用标志 */
    public static final int DEPTH_WRITE_ENABLED_OFFSET = 33;
    public static final long DEPTH_WRITE_ENABLED_MASK = 1L << DEPTH_WRITE_ENABLED_OFFSET;

    /**
     * 深度比较函数 (3位)
     * 0=NEVER, 1=LESS, 2=EQUAL, 3=LEQUAL,
     * 4=GREATER, 5=NOTEQUAL, 6=GEQUAL, 7=ALWAYS
     */
    public static final int DEPTH_FUNC_OFFSET = 34;
    public static final long DEPTH_FUNC_MASK = 0x7L << DEPTH_FUNC_OFFSET;

    /** 模板测试启用标志 */
    public static final int STENCIL_TEST_ENABLED_OFFSET = 37;
    public static final long STENCIL_TEST_ENABLED_MASK = 1L << STENCIL_TEST_ENABLED_OFFSET;

    /** 正面模板比较函数 (3位) */
    public static final int STENCIL_FRONT_FUNC_OFFSET = 38;
    public static final long STENCIL_FRONT_FUNC_MASK = 0x7L << STENCIL_FRONT_FUNC_OFFSET;

    /** 背面模板比较函数 (3位) */
    public static final int STENCIL_BACK_FUNC_OFFSET = 41;
    public static final long STENCIL_BACK_FUNC_MASK = 0x7L << STENCIL_BACK_FUNC_OFFSET;

    /** 模板操作失败时的动作 (3位) */
    public static final int STENCIL_FAIL_OFFSET = 44;
    public static final long STENCIL_FAIL_MASK = 0x7L << STENCIL_FAIL_OFFSET;

    /** 模板深度测试失败时的动作 (3位) */
    public static final int STENCIL_DEPTH_FAIL_OFFSET = 47;
    public static final long STENCIL_DEPTH_FAIL_MASK = 0x7L << STENCIL_DEPTH_FAIL_OFFSET;

    /** 模板通过时的动作 (3位) */
    public static final int STENCIL_PASS_OFFSET = 50;
    public static final long STENCIL_PASS_MASK = 0x7L << STENCIL_PASS_OFFSET;

    // ==================== long[2]: 光栅化状态 ====================

    /** 背面剔除启用标志 */
    public static final int CULL_FACE_ENABLED_OFFSET = 64;
    public static final long CULL_FACE_ENABLED_MASK = 1L << CULL_FACE_ENABLED_OFFSET;

    /**
     * 剔除的面 (2位: BACK, FRONT, FRONT_AND_BACK)
     */
    public static final int CULL_FACE_MODE_OFFSET = 65;
    public static final long CULL_FACE_MODE_MASK = 0x3L << CULL_FACE_MODE_OFFSET;

    /** 前面绕序 (1位: CW=0, CCW=1) */
    public static final int FRONT_FACE_OFFSET = 67;
    public static final long FRONT_FACE_MASK = 1L << FRONT_FACE_OFFSET;

    /**
     * 多边形模式 (2位: FILL, LINE, POINT)
     */
    public static final int POLYGON_MODE_OFFSET = 68;
    public static final long POLYGON_MODE_MASK = 0x3L << POLYGON_MODE_OFFSET;

    /** 多边形偏移启用标志 */
    public static final int POLYGON_OFFSET_FILL_ENABLED_OFFSET = 70;
    public static final long POLYGON_OFFSET_FILL_ENABLED_MASK = 1L << POLYGON_OFFSET_FILL_ENABLED_OFFSET;

    /** 多边形偏移线段启用 */
    public static final int POLYGON_OFFSET_LINE_ENABLED_OFFSET = 71;
    public static final long POLYGON_OFFSET_LINE_ENABLED_MASK = 1L << POLYGON_OFFSET_LINE_ENABLED_OFFSET;

    /** 多边形偏移点启用 */
    public static final int POLYGON_OFFSET_POINT_ENABLED_OFFSET = 72;
    public static final long POLYGON_OFFSET_POINT_ENABLED_MASK = 1L << POLYGON_OFFSET_POINT_ENABLED_OFFSET;

    /** 线宽 (使用浮点数，这里只存储是否非默认) */
    public static final int LINE_WIDTH_NON_DEFAULT_OFFSET = 73;
    public static final long LINE_WIDTH_NON_DEFAULT_MASK = 1L << LINE_WIDTH_NON_DEFAULT_OFFSET;

    /** 点大小启用标志 */
    public static final int POINT_SIZE_ENABLED_OFFSET = 74;
    public static final long POINT_SIZE_ENABLED_MASK = 1L << POINT_SIZE_ENABLED_OFFSET;

    /** 剪裁测试启用 */
    public static final int SCISSOR_TEST_ENABLED_OFFSET = 75;
    public static final long SCISSOR_TEST_ENABLED_MASK = 1L << SCISSOR_TEST_ENABLED_OFFSET;

    /** 逻辑操作模式 (4位) */
    public static final int LOGIC_OP_OFFSET = 76;
    public static final long LOGIC_OP_MASK = 0xFL << LOGIC_OP_OFFSET;

    // ==================== long[3]: 多重采样与遮罩 ====================

    /** 多重采样启用 */
    public static final int MULTISAMPLE_ENABLED_OFFSET = 96;
    public static final long MULTISAMPLE_ENABLED_MASK = 1L << MULTISAMPLE_ENABLED_OFFSET;

    /** 样本遮罩 */
    public static final int SAMPLE_MASK_OFFSET = 97;
    public static final long SAMPLE_MASK_MASK = 0xFFL << SAMPLE_MASK_OFFSET;

    /** AlphaToCoverage 启用 */
    public static final int ALPHA_TO_COVERAGE_ENABLED_OFFSET = 105;
    public static final long ALPHA_TO_COVERAGE_ENABLED_MASK = 1L << ALPHA_TO_COVERAGE_ENABLED_OFFSET;

    /** AlphaToOne 启用 */
    public static final int ALPHA_TO_ONE_ENABLED_OFFSET = 106;
    public static final long ALPHA_TO_ONE_ENABLED_MASK = 1L << ALPHA_TO_ONE_ENABLED_OFFSET;

    /** Dithering 启用 (OpenGL 特有) */
    public static final int DITHER_ENABLED_OFFSET = 107;
    public static final long DITHER_ENABLED_MASK = 1L << DITHER_ENABLED_OFFSET;

    // ==================== 枚举值常量 ====================

    /** 混合因子枚举值 */
    public enum BlendFactor {
        ZERO(0), ONE(1), SRC_COLOR(2), ONE_MINUS_SRC_COLOR(3),
        DST_COLOR(4), ONE_MINUS_DST_COLOR(5), SRC_ALPHA(6),
        ONE_MINUS_SRC_ALPHA(7), DST_ALPHA(8), ONE_MINUS_DST_ALPHA(9),
        CONSTANT_COLOR(10), ONE_MINUS_CONSTANT_COLOR(11),
        CONSTANT_ALPHA(12), ONE_MINUS_CONSTANT_ALPHA(13);

        public final int id;
        BlendFactor(int id) { this.id = id; }
    }

    /** 混合方程式枚举值 */
    public enum BlendEquation {
        ADD(0), SUBTRACT(1), REVERSE_SUBTRACT(2), MIN(3), MAX(4);
        public final int id;
        BlendEquation(int id) { this.id = id; }
    }

    /** 比较函数枚举值 */
    public enum CompareFunc {
        NEVER(0), LESS(1), EQUAL(2), LEQUAL(3),
        GREATER(4), NOTEQUAL(5), GEQUAL(6), ALWAYS(7);
        public final int id;
        CompareFunc(int id) { this.id = id; }
    }

    /** 模板操作枚举值 */
    public enum StencilOp {
        KEEP(0), ZERO(1), REPLACE(2), INCR(3),
        INCR_WRAP(4), DECR(5), DECR_WRAP(6), INVERT(7);
        public final int id;
        StencilOp(int id) { this.id = id; }
    }

    /** 剔除面模式 */
    public enum CullMode {
        BACK(0), FRONT(1), FRONT_AND_BACK(2);
        public final int id;
        CullMode(int id) { this.id = id; }
    }

    /** 前面绕序 */
    public enum FrontFace {
        CW(0), CCW(1);
        public final int id;
        FrontFace(int id) { this.id = id; }
    }

    /** 多边形模式 */
    public enum PolygonMode {
        FILL(0), LINE(1), POINT(2);
        public final int id;
        PolygonMode(int id) { this.id = id; }
    }

    // ==================== 工具方法 ====================

    /**
     * 设置状态位
     *
     * @param stateBits 当前状态数组
     * @param offset    位偏移
     * @param mask      位掩码
     * @param value     要设置的值
     * @return 更新后的状态数组副本
     */
    public static long[] setState(long[] stateBits, int offset, long mask, long value) {
        long[] result = stateBits.clone();
        int wordIndex = offset / 64;
        int bitOffset = offset % 64;
        result[wordIndex] = (result[wordIndex] & ~mask) | ((value << bitOffset) & mask);
        return result;
    }

    /**
     * 获取状态值
     *
     * @param stateBits 状态数组
     * @param offset    位偏移
     * @param mask      位掩码
     * @return 状态值
     */
    public static long getState(long[] stateBits, int offset, long mask) {
        int wordIndex = offset / 64;
        int bitOffset = offset % 64;
        return (stateBits[wordIndex] & mask) >>> bitOffset;
    }

    /**
     * 计算状态数组的 Hash 值
     * <p>
     * 使用 FNV-1a 变体算法，对管线状态进行高效哈希。
     * 该 Hash 用于 Pipeline Cache 的 O(1) 查找。
     *
     * @param stateBits 管线状态位数组
     * @return 64位 Hash 值
     */
    public static long computeHash(long[] stateBits) {
        // FNV-1a 64-bit 偏移基
        long hash = 0xcbf29ce484222325L;
        // FNV-1a 64-bit 质数
        final long FNV_PRIME = 0x100000001b3L;

        for (long word : stateBits) {
            hash ^= word;
            hash *= FNV_PRIME;
        }

        return hash;
    }

    /**
     * 比较两个状态数组是否相等
     *
     * @param a 第一个状态数组
     * @param b 第二个状态数组
     * @return 是否完全相等
     */
    public static boolean equals(long[] a, long[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    /**
     * 创建初始状态数组（所有状态为默认值）
     * <p>
     * 默认值对应 OpenGL 初始状态：
     * <ul>
     *   <li>混合关闭</li>
     *   <li>深度测试开启、深度写入开启</li>
     *   <li>背面剔除关闭</li>
     *   <li>多边形填充模式</li>
     * </ul>
     *
     * @return 默认状态的位数组
     */
    public static long[] createDefaultState() {
        long[] state = new long[8]; // 512位

        // 设置默认深度测试状态
        state[1] |= DEPTH_TEST_ENABLED_MASK | DEPTH_WRITE_ENABLED_MASK;
        // 深度比较函数默认 LEQUAL
        state[1] |= (3L << DEPTH_FUNC_OFFSET); // LEQUAL = 3

        // 设置默认前面绕序为 CCW
        state[2] |= FRONT_FACE_MASK; // CCW = 1

        // 设置默认多边形模式为 FILL (已经是0，无需设置)

        // Dithering 默认开启 (OpenGL 特有)
        state[3] |= DITHER_ENABLED_MASK;

        return state;
    }
}
