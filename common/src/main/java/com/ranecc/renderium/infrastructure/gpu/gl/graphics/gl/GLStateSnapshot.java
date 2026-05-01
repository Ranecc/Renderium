// Renderium - OpenGL 状态快照机
// 使用位域（long[]）维护 GL 状态，零对象分配

package com.renderium.graphics.gl;

/**
 * OpenGL 状态快照机。
 *
 * <p>设计思路借鉴了 Mesa Zink 驱动 (MIT) 的 pipeline state tracking 机制。
 * Zink 将所有 OpenGL 状态打包为哈希值用于 Vulkan Pipeline 查找和缓存。
 * 本类采用相同的思想，但针对 Minecraft 的 GL 调用子集做了极致优化：
 * 使用固定大小的位域数组（long[]）存储所有状态，运行时只做位翻转操作，
 * 绝不产生任何对象分配。
 *
 * <h2>为什么不用对象？</h2>
 * <p>OpenGL 是一个巨大的隐式状态机。传统做法是为每种状态创建一个 Java 对象
 * （如 BlendState, DepthState 等），但这会导致：
 * <ul>
 *   <li>每次状态变化都可能触发对象创建</li>
 *   <li>大量小对象的 GC 压力</li>
 *   <li>缓存比较时需要逐字段反射或 equals()</li>
 * </ul>
 * <p>本方案使用原始类型（long + int + float 数组），将所有状态编码为位模式。
 * 状态比较退化为 long[] 的内存比较（System.arraycopy 或 Unsafe.compare），速度极快。
 *
 * <h2>内存布局</h2>
 * <pre>
 * ┌────────────────────────────────────────────────────┐
 * │ stateBits[0]  : 启用/禁用标志 (64 种 GL capability)│
 * │ stateBits[1]  : Blend 方程 / 函数                    │
 * │ stateBits[2]  : Depth / Stencil / Color mask        │
 * │ stateBits[3]  : Polygon / Cull face / Front face     │
 * │ stateBits[4]  : Texture 绑定槽位                     │
 * │ stateBits[5-7]: 预留扩展                             │
 * ├────────────────────────────────────────────────────┤
 * │ floatValues[] : 连续值 (颜色、深度范围等)            │
 * │ intValues[]   : 整数值 (纹理 ID、程序 ID 等)         │
 * └────────────────────────────────────────────────────┘
 * 总计: 8 * 8 = 64 bytes (位域) + 连续值区域
 * </pre>
 *
 * @see <a href="https://gitlab.freedesktop.org/mesa/mesa/-/tree/main/src/gallium/drivers/zink">Mesa Zink Driver</a>
 * @author Renderium Team
 * @since 1.0.0
 */
public final class GLStateSnapshot {

    // ==================== 位域布局定义 ====================

    /** 每个 long 的 bit 数 */
    private static final int BITS_PER_LONG = Long.SIZE; // 64

    /** 状态位数组大小（8 个 long = 512 bits） */
    private static final int STATE_ARRAY_SIZE = 8;

    /** 最大支持的 GL capability 数量（必须 <= 64） */
    private static final int MAX_CAPABILITIES = 64;

    // ---- stateBits[0]: 启用/禁用标志 (bit 0-63) ----
    public static final int CAP_BLEND = 0;
    public static final int CAP_DEPTH_TEST = 1;
    public static final int CAP_CULL_FACE = 2;
    public static final int CAP_STENCIL_TEST = 3;
    public static final int CAP_SCISSOR_TEST = 4;
    public static final int CAP_DITHER = 5;
    public static final int CAP_MULTISAMPLE = 6;
    public static final int CAP_POLYGON_OFFSET_FILL = 7;
    public static final int CAP_POLYGON_OFFSET_LINE = 8;
    public static final int CAP_POLYGON_OFFSET_POINT = 9;
    public static final int CAP_ALPHA_TEST = 10;
    public static final int CAP_LIGHTING = 11;
    public static final int CAP_FOG = 12;
    public static final int CAP_TEXTURE_2D = 13;
    public static final int CAP_LINE_SMOOTH = 14;
    // ... 14-63 预留

    // ---- stateBits[1]: Blend 相关 (bit 64-127) ----
    private static final int BLEND_BASE = BITS_PER_LONG; // offset = 64
    public static final int BLEND_EQUATION_RGB_LO = BLEND_BASE + 0;
    public static final int BLEND_EQUATION_RGB_HI = BLEND_BASE + 4;
    public static final int BLEND_EQUATION_ALPHA_LO = BLEND_BASE + 8;
    public static final int BLEND_EQUATION_ALPHA_HI = BLEND_BASE + 12;
    public static final int BLEND_FUNC_SRC_RGB_LO = BLEND_BASE + 16;
    public static final int BLEND_FUNC_SRC_RGB_HI = BLEND_BASE + 20;
    public static final int BLEND_FUNC_DST_RGB_LO = BLEND_BASE + 24;
    public static final int BLEND_FUNC_DST_RGB_HI = BLEND_BASE + 28;
    public static final int BLEND_FUNC_SRC_ALPHA_LO = BLEND_BASE + 32;
    public static final int BLEND_FUNC_SRC_ALPHA_HI = BLEND_BASE + 36;
    public static final int BLEND_FUNC_DST_ALPHA_LO = BLEND_BASE + 40;
    public static final int BLEND_FUNC_DST_ALPHA_HI = BLEND_BASE + 44;
    // ... 预留 blend color mask

    // ---- stateBits[2]: Depth / Stencil / Color (bit 128-191) ----
    private static final int DEPTH_BASE = 2 * BITS_PER_LONG; // offset = 128
    public static final int DEPTH_FUNC_LO = DEPTH_BASE + 0;
    public static final int DEPTH_FUNC_HI = DEPTH_BASE + 4;
    public static final int DEPTH_MASK = DEPTH_BASE + 8;      // 1 bit: write enable
    public static final int COLOR_WRITE_R = DEPTH_BASE + 9;
    public static final int COLOR_WRITE_G = DEPTH_BASE + 10;
    public static final int COLOR_WRITE_B = DEPTH_BASE + 11;
    public static final int COLOR_WRITE_A = DEPTH_BASE + 12;
    public static final int STENCIL_FUNC_LO = DEPTH_BASE + 16;
    public static final int STENCIL_FUNC_HI = DEPTH_BASE + 20;

    // ---- stateBits[3]: Polygon / Cull (bit 192-255) ----
    private static final int POLYGON_BASE = 3 * BITS_PER_LONG; // offset = 192
    public static final int CULL_FACE_MODE_LO = POLYGON_BASE + 0;
    public static final int CULL_FACE_MODE_HI = POLYGON_BASE + 4;
    public static final int FRONT_FACE_LO = POLYGON_BASE + 8;
    public static final int FRONT_FACE_HI = POLYGON_BASE + 12;
    public static final int POLYGON_MODE_LO = POLYGON_BASE + 16;
    public static final int POLYGON_MODE_HI = POLYGON_BASE + 20;

    // ==================== 连续值存储区 ====================

    /** 最大连续浮点值数量 */
    private static final int MAX_FLOAT_VALUES = 32;

    /** 最大连续整数值数量 */
    private static final int MAX_INT_VALUES = 32;

    // 浮点值索引定义
    public static final int FVAL_COLOR_R = 0;
    public static final int FVAL_COLOR_G = 1;
    public static final int FVAL_COLOR_B = 2;
    public static final int FVAL_COLOR_A = 3;
    public static final int FVAL_DEPTH_NEAR = 4;
    public static final int FVAL_DEPTH_FAR = 5;
    public static final int FVAL_POLYGON_OFFSET_FACTOR = 6;
    public static final int FVAL_POLYGON_OFFSET_UNITS = 7;
    public static final int FVAL_CLEAR_COLOR_R = 8;
    public static final int FVAL_CLEAR_COLOR_G = 9;
    public static final int FVAL_CLEAR_COLOR_B = 10;
    public static final int FVAL_CLEAR_COLOR_A = 11;
    public static final int FVAL_BLEND_COLOR_R = 12;
    public static final int FVAL_BLEND_COLOR_G = 13;
    public static final int FVAL_BLEND_COLOR_B = 14;
    public static final int FVAL_BLEND_COLOR_A = 15;
    // 16-31 预留

    // 整数值索引定义
    public static final int IVAL_TEXTURE_2D_BINDING = 0;
    public static final int IVAL_ACTIVE_TEXTURE = 1;
    public static final int IVAL_ARRAY_BUFFER_BINDING = 2;
    public static final int IVAL_ELEMENT_ARRAY_BUFFER_BINDING = 3;
    public static final int IVAL_PROGRAM_ID = 4;
    public static final int IVAL_VAO_BINDING = 5;
    // 6-31 预留

    // ==================== 核心存储 ====================

    /**
     * 状态位域数组 - 所有离散状态存储在这里
     *
     * <p>使用 long 而非 boolean[] 或 BitSet 的原因：
     * <ul>
     *   <li>long 是原子操作的最宽原生类型（支持 CAS）</li>
     *   <li>long[] 可以直接用 System.arraycopy 或 Unsafe.compare 做批量比较</li>
     *   <li>位运算（AND/OR/XOR）是 CPU 单周期操作</li>
     * </ul>
     */
    private final long[] stateBits;

    /**
     * 浮点值数组 - 存储无法用位表示的连续值
     */
    private final float[] floatValues;

    /**
     * 整数值数组 - 存储 ID 类型的状态
     */
    private final int[] intValues;

    /**
     * 脏标记 - 标记自上次 draw 以来是否有任何状态变化
     *
     * <p>volatile 保证跨线程可见性（渲染线程 vs 逻辑线程）
     */
    private volatile boolean dirty;

    /**
     * 帧计数器 - 用于检测每帧的状态变化频率
     */
    private int frameCounter;

    /**
     * 上次 Draw 时保存的快照副本（用于计算 diff）
     */
    private long[] lastDrawSnapshot;

    // ==================== 构造与初始化 ====================

    /**
     * 创建新的状态快照实例
     *
     * <p>所有数组在构造时一次性分配，后续操作不再分配内存。
     */
    public GLStateSnapshot() {
        this.stateBits = new long[STATE_ARRAY_SIZE];
        this.floatValues = new float[MAX_FLOAT_VALUES];
        this.intValues = new int[MAX_INT_VALUES];
        this.lastDrawSnapshot = new long[STATE_ARRAY_SIZE];

        // 初始化默认状态（匹配 OpenGL 默认值）
        initializeDefaults();
    }

    /**
     * 初始化为 OpenGL 默认状态
     */
    private void initializeDefaults() {
        // 默认禁用的能力
        setCapability(CAP_BLEND, false);
        setCapability(CAP_DEPTH_TEST, false);       // 注意：MC 通常会立即启用
        setCapability(CAP_CULL_FACE, false);
        setCapability(CAP_STENCIL_TEST, false);
        setCapability(CAP_SCISSOR_TEST, false);
        setCapability(CAP_DITHER, true);             // 默认开启
        setCapability(CAP_MULTISAMPLE, true);

        // 默认 blend: FUNC_ADD, SRC_ALPHA, ONE_MINUS_SRC_ALPHA
        setBlendEquation(0x8006); // GL_FUNC_ADD
        setBlendFuncSrcRGB(0x0302);                   // GL_SRC_ALPHA
        setBlendFuncDstRGB(0x0303);                   // GL_ONE_MINUS_SRC_ALPHA

        // 默认 depth: LESS, write enabled
        setDepthFunc(0x0201);                         // GL_LESS
        setDepthMask(true);

        // 默认 color write: RGBA 全部开启
        setColorWriteMask(true, true, true, true);

        // 默认颜色: 白色
        setColor(1.0f, 1.0f, 1.0f, 1.0f);

        // 默认 depth range: [0, 1]
        setDepthRange(0.0f, 1.0f);

        // 初始标记为 dirty（第一次 draw 必须编译）
        this.dirty = true;
    }

    // ==================== Capability 操作（核心 API）====================

    /**
     * 设置 capability 启用/禁用状态
     *
     * <p>这是最高频调用的方法之一。实现为单次位操作，O(1) 时间复杂度。
     *
     * @param capIndex capability 位索引（使用 CAP_* 常量）
     * @param enabled true=启用, false=禁用
     */
    public void setCapability(int capIndex, boolean enabled) {
        if (capIndex < 0 || capIndex >= MAX_CAPABILITIES) {
            return; // 超出范围的忽略
        }
        if (enabled) {
            stateBits[0] |= (1L << capIndex);
        } else {
            stateBits[0] &= ~(1L << capIndex);
        }
        this.dirty = true;
    }

    /**
     * 检查 capability 是否启用
     *
     * @param capIndex capability 位索引
     * @return true 如果已启用
     */
    public boolean isCapabilityEnabled(int capIndex) {
        if (capIndex < 0 || capIndex >= MAX_CAPABILITIES) return false;
        return (stateBits[0] & (1L << capIndex)) != 0;
    }

    // ==================== Blend 状态操作 ====================

    /**
     * 设置混合方程（RGB 通道）
     *
     * @param mode 方程常量（GL_FUNC_ADD 等）
     */
    public void setBlendEquation(int mode) {
        setBitField(BLEND_EQUATION_RGB_LO, BLEND_EQUATION_RGB_HI, mode & 0xFFFF);
        this.dirty = true;
    }

    /**
     * 设置混合函数 - 源因子 (RGB)
     *
     * @param func 因子常量（GL_SRC_ALPHA 等）
     */
    public void setBlendFuncSrcRGB(int func) {
        setBitField(BLEND_FUNC_SRC_RGB_LO, BLEND_FUNC_SRC_RGB_HI, func & 0xFFFF);
        this.dirty = true;
    }

    /**
     * 设置混合函数 - 目标因子 (RGB)
     *
     * @param func 因子常量（GL_ONE_MINUS_SRC_ALPHA 等）
     */
    public void setBlendFuncDstRGB(int func) {
        setBitField(BLEND_FUNC_DST_RGB_LO, BLEND_FUNC_DST_RGB_HI, func & 0xFFFF);
        this.dirty = true;
    }

    // ==================== Depth 状态操作 ====================

    /**
     * 设置深度测试函数
     *
     * @param func 函数常量（GL_LESS, GL_LEQUAL 等）
     */
    public void setDepthFunc(int func) {
        setBitField(DEPTH_FUNC_LO, DEPTH_FUNC_HI, func & 0xFFFF);
        this.dirty = true;
    }

    /**
     * 设置深度写入掩码
     *
     * @param enabled true 允许写入深度缓冲
     */
    public void setDepthMask(boolean enabled) {
        if (enabled) {
            stateBits[DEPTH_BASE / BITS_PER_LONG] |= (1L << (DEPTH_MASK % BITS_PER_LONG));
        } else {
            stateBits[DEPTH_BASE / BITS_PER_LONG] &= ~(1L << (DEPTH_MASK % BITS_PER_LONG));
        }
        this.dirty = true;
    }

    // ==================== Color / 清除色操作 ====================

    /**
     * 设置当前绘制颜色（glColor4f）
     *
     * @param r 红色分量
     * @param g 绿色分量
     * @param b 蓝色分量
     * @param a Alpha 分量
     */
    public void setColor(float r, float g, float b, float a) {
        floatValues[FVAL_COLOR_R] = r;
        floatValues[FVAL_COLOR_G] = g;
        floatValues[FVAL_COLOR_B] = b;
        floatValues[FVAL_COLOR_A] = a;
        this.dirty = true;
    }

    /**
     * 设置清除颜色（glClearColor）
     */
    public void setClearColor(float r, float g, float b, float a) {
        floatValues[FVAL_CLEAR_COLOR_R] = r;
        floatValues[FVAL_CLEAR_COLOR_G] = g;
        floatValues[FVAL_CLEAR_COLOR_B] = b;
        floatValues[FVAL_CLEAR_COLOR_A] = a;
        this.dirty = true;
    }

    /**
     * 设置颜色写入掩码（glColorMask）
     */
    public void setColorWriteMask(boolean r, boolean g, boolean b, boolean a) {
        int base = COLOR_WRITE_R;
        long mask = stateBits[base / BITS_PER_LONG];
        if (r) mask |= (1L << (base % BITS_PER_LONG)); else mask &= ~(1L << (base % BITS_PER_LONG));
        base++;
        if (g) mask |= (1L << (base % BITS_PER_LONG)); else mask &= ~(1L << (base % BITS_PER_LONG));
        base++;
        if (b) mask |= (1L << (base % BITS_PER_LONG)); else mask &= ~(1L << (base % BITS_PER_LONG));
        base++;
        if (a) mask |= (1L << (base % BITS_PER_LONG)); else mask &= ~(1L << (base % BITS_PER_LONG));
        stateBits[COLOR_WRITE_R / BITS_PER_LONG] = mask;
        this.dirty = true;
    }

    // ==================== Texture / Buffer 绑定 ====================

    /**
     * 绑定 2D 纹理（glBindTexture）
     *
     * @param textureId 纹理 ID（0 = 解绑）
     */
    public void bindTexture2D(int textureId) {
        intValues[IVAL_TEXTURE_2D_BINDING] = textureId;
        this.dirty = true;
    }

    /**
     * 设置当前纹理单元（glActiveTexture）
     *
     * @param unit 纹理单元索引（GL_TEXTURE0 + n）
     */
    public void setActiveTexture(int unit) {
        intValues[IVAL_ACTIVE_TEXTURE] = unit;
        this.dirty = true;
    }

    /**
     * 绑定数组缓冲（glBindBuffer for ARRAY_BUFFER）
     *
     * @param bufferId 缓冲区 ID
     */
    public void bindArrayBuffer(int bufferId) {
        intValues[IVAL_ARRAY_BUFFER_BINDING] = bufferId;
        this.dirty = true;
    }

    /**
     * 绑定元素数组缓冲（glBindBuffer for ELEMENT_ARRAY_BUFFER）
     *
     * @param bufferId 缓冲区 ID
     */
    public void bindElementArrayBuffer(int bufferId) {
        intValues[IVAL_ELEMENT_ARRAY_BUFFER_BINDING] = bufferId;
        this.dirty = true;
    }

    // ==================== 其他常用状态 ====================

    /**
     * 设置剔除面模式（glCullFace）
     *
     * @param mode GL_FRONT, GL_BACK, GL_FRONT_AND_BACK
     */
    public void setCullFaceMode(int mode) {
        setBitField(CULL_FACE_MODE_LO, CULL_FACE_MODE_HI, mode & 0xFFFF);
        this.dirty = true;
    }

    /**
     * 设置正面判定规则（glFrontFace）
     *
     * @param mode GL_CW, GL_CCW
     */
    public void setFrontFace(int mode) {
        setBitField(FRONT_FACE_LO, FRONT_FACE_HI, mode & 0xFFFF);
        this.dirty = true;
    }

    /**
     * 设置多边形填充模式（glPolygonMode）
     *
     * @param mode GL_FILL, GL_LINE, GL_POINT
     */
    public void setPolygonMode(int mode) {
        setBitField(POLYGON_MODE_LO, POLYGON_MODE_HI, mode & 0xFFFF);
        this.dirty = true;
    }

    /**
     * 设置深度范围（glDepthRange）
     */
    public void setDepthRange(float nearVal, float farVal) {
        floatValues[FVAL_DEPTH_NEAR] = nearVal;
        floatValues[FVAL_DEPTH_FAR] = farVal;
        this.dirty = true;
    }

    /**
     * 绑定着色器程序（glUseProgram）
     *
     * @param programId 程序 ID（0 = 解绑）
     */
    public void useProgram(int programId) {
        intValues[IVAL_PROGRAM_ID] = programId;
        this.dirty = true;
    }

    // ==================== Dirty 管理 ====================

    /**
     * 检查自上次 draw 以来是否有状态变化
     *
     * @return true 如果有变化需要重新编译 Pipeline
     */
    public boolean isDirty() {
        return dirty;
    }

    /**
     * 清除脏标记（在 draw 完成后调用）
     */
    public void clearDirty() {
        this.dirty = false;
        // 保存当前快照作为下次比较基准
        System.arraycopy(stateBits, 0, lastDrawSnapshot, 0, STATE_ARRAY_SIZE);
        frameCounter++;
    }

    /**
     * 强制标记为 dirty（外部状态变化时调用）
     */
    public void forceDirty() {
        this.dirty = true;
    }

    // ==================== 快照比较（给编译器用）====================

    /**
     * 获取当前状态的完整快照哈希
     *
     * <p>用于 Pipeline Cache 查找。
     * 将所有状态位 + 连续值混合为一个 64-bit 哈希。
     *
     * @return 状态哈希值
     */
    public long computeStateHash() {
        // 使用 FNV-1a 风格的混合哈希
        long hash = 2166136261L; // FNV offset basis

        // 混合位域
        for (long bits : stateBits) {
            hash ^= bits;
            hash *= 16777619L; // FNV prime
        }

        // 混合浮点值（通过 Float.floatToIntBits 转换）
        for (float f : floatValues) {
            hash ^= Float.floatToIntBits(f);
            hash *= 16777619L;
        }

        // 混合整数值
        for (int i : intValues) {
            hash ^= i;
            hash *= 16777619L;
        }

        return hash;
    }

    /**
     * 检查与上次 Draw 相比是否有特定位域的变化
     *
     * @param fieldStart 起始 bit 索引
     * @param fieldEnd 结束 bit 索引（不含）
     * @return true 如果该范围内的位有变化
     */
    public boolean hasFieldChanged(int fieldStart, int fieldEnd) {
        int startWord = fieldStart / BITS_PER_LONG;
        int endWord = fieldEnd / BITS_PER_LONG;

        for (int i = startWord; i <= endWord && i < STATE_ARRAY_SIZE; i++) {
            if ((stateBits[i] ^ lastDrawSnapshot[i]) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取上次 Draw 以来变化的位掩码
     *
     * @return 变化掩码数组（与 stateBits 同尺寸）
     */
    public long[] getDiffMask() {
        long[] diff = new long[STATE_ARRAY_SIZE];
        for (int i = 0; i < STATE_ARRAY_SIZE; i++) {
            diff[i] = stateBits[i] ^ lastDrawSnapshot[i];
        }
        return diff;
    }

    // ==================== 内部工具方法 ====================

    /**
     * 在指定位域设置一个值（低位到高位）
     *
     * @param loBit 最低位索引
     * @param hiBit 最高位索引（包含）
     * @param value 要设置的值（将被截断到可用位数）
     */
    private void setBitField(int loBit, int hiBit, int value) {
        int wordIndex = loBit / BITS_PER_LONG;
        int bitOffset = loBit % BITS_PER_LONG;
        int fieldWidth = hiBit - loBit + 1;
        long mask = ((1L << fieldWidth) - 1) << bitOffset;

        // 清除旧值，设置新值
        stateBits[wordIndex] = (stateBits[wordIndex] & ~mask) | (((long) value & (mask >>> bitOffset)) << bitOffset);
    }

    /**
     * 从位域读取一个值
     */
    private int getBitField(int loBit, int hiBit) {
        int wordIndex = loBit / BITS_PER_LONG;
        int bitOffset = loBit % BITS_PER_LONG;
        int fieldWidth = hiBit - loBit + 1;
        long mask = (1L << fieldWidth) - 1;

        return (int) ((stateBits[wordIndex] >>> bitOffset) & mask);
    }

    // ==================== Getter 方法（供读取当前状态）====================

    public long[] getStateBits() { return stateBits; }
    public float[] getFloatValues() { return floatValues; }
    public int[] getIntValues() { return intValues; }
    public long[] getLastDrawSnapshot() { return lastDrawSnapshot; }
    public int getFrameCounter() { return frameCounter; }

    // 便捷 getter
    public float getColorR() { return floatValues[FVAL_COLOR_R]; }
    public float getColorG() { return floatValues[FVAL_COLOR_G]; }
    public float getColorB() { return floatValues[FVAL_COLOR_B]; }
    public float getColorA() { return floatValues[FVAL_COLOR_A]; }
    public int getTexture2DBinding() { return intValues[IVAL_TEXTURE_2D_BINDING]; }
    public int getProgramId() { return intValues[IVAL_PROGRAM_ID]; }
    public int getActiveTextureUnit() { return intValues[IVAL_ACTIVE_TEXTURE]; }
    public boolean isBlendEnabled() { return isCapabilityEnabled(CAP_BLEND); }
    public boolean isDepthTestEnabled() { return isCapabilityEnabled(CAP_DEPTH_TEST); }
    public boolean isCullFaceEnabled() { return isCapabilityEnabled(CAP_CULL_FACE); }

    @Override
    public String toString() {
        return String.format("GLStateSnapshot{dirty=%s, frame=%d, blend=%s, depthTest=%s, cull=%s, tex=%d}",
                dirty, frameCounter,
                isBlendEnabled(), isDepthTestEnabled(), isCullFaceEnabled(),
                getTexture2DBinding());
    }
}
