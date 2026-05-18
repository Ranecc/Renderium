// Renderium - 渲染管线状态（不可变）
// 将 GL 状态编译为 Vulkan Pipeline 所需的参数集合

package com.ranecc.renderium.infrastructure.gpu.pipeline;

import com.ranecc.renderium.None;
import com.ranecc.renderium.feature.renderopt.GLStateSnapshot;

/**
 * 渲染管线状态对象（不可变）。
 *
 * <p>这是 MockGL11 状态编译器的输出产物。当 glDraw* 被调用时，
 * 当前的 {@link GLStateSnapshot} 被编译为这个不可变的 RenderPipeline 对象，
 * 然后提交给 CommandBuffer → VulkanBackend 执行。
 *
 * <h2>设计哲学</h2>
 * <p>借鉴了 Mesa Zink (MIT) 的 pipeline state object 设计：
 * <ul>
 *   <li><b>不可变性</b>：一旦创建就不再修改，天然线程安全</li>
 *   <li><b>值类型语义</b>：equals/hashCode 基于所有字段，适合做 HashMap 键</li>
 *   <li><b>预计算</b>：Vulkan 需要的参数在创建时就已确定，运行时零计算</li>
 * </ul>
 *
 * <h2>Pipeline Cache 集成</h2>
 * <p>每个唯一的 RenderPipeline 对应一个 VkPipeline（或其 handle）。
 * 通过 hashCode() 快速查找缓存，避免重复创建昂贵的 Vulkan 对象。
 *
 * @see <a href="https://gitlab.freedesktop.org/mesa/mesa/-/tree/main/src/gallium/drivers/zink">Mesa Zink Driver</a>
 * @author Renderium Team
 * @since 1.0.0
 */
public final class RenderPipeline {

    // ==================== 混合状态 ====================

    /** 是否启用混合 */
    private final boolean blendEnabled;

    /** RGB 混合方程 */
    private final int blendEquationRgb;

    /** Alpha 混合方程 */
    private final int blendEquationAlpha;

    /** RGB 源混合因子 */
    private final int blendSrcRgb;

    /** RGB 目标混合因子 */
    private final int blendDstRgb;

    /** Alpha 源混合因子 */
    private final int blendSrcAlpha;

    /** Alpha 目标混合因子 */
    private final int blendDstAlpha;

    /** 混合常量颜色 (RGBA, packed int) */
    private final int blendColor;

    // ==================== 深度/模板状态 ====================

    /** 是否启用深度测试 */
    private final boolean depthTestEnabled;

    /** 是否允许写入深度缓冲 */
    private final boolean depthWriteEnabled;

    /** 深度比较函数 */
    private final int depthCompareOp;

    /** 深度范围近值 */
    private final float depthRangeNear;

    /** 深度范围远值 */
    private final float depthRangeFar;

    /** 是否启用模板测试 */
    private final boolean stencilTestEnabled;

    // ==================== 光栅化状态 ====================

    /** 是否启用面剔除 */
    private final boolean cullFaceEnabled;

    /** 剔除的面（GL_FRONT / GL_BACK） */
    private final int cullFaceMode;

    /** 正面缠绕顺序（GL_CW / GL_CCW） */
    private final int frontFaceMode;

    /** 多边形填充模式（GL_FILL / GL_LINE / GL_POINT） */
    private final int polygonMode;

    /** 是否启用多边形偏移 */
    private final boolean polygonOffsetEnabled;

    /** 多边形偏移因子 */
    private final float polygonOffsetFactor;

    /** 多边形偏移单位 */
    private final float polygonOffsetUnits;

    // ==================== 颜色写入掩码 ====================

    /** R 通道写入权限 */
    private final boolean colorWriteR;

    /** G 通道写入权限 */
    private final boolean colorWriteG;

    /** B 通道写入权限 */
    private final boolean colorWriteB;

    /** A 通道写入权限 */
    private final boolean colorWriteA;

    // ==================== 绑定资源 ====================

    /** 当前绑定的 2D 纹理 ID */
    private final int texture2DBinding;

    /** 当前活跃纹理单元 */
    private final int activeTextureUnit;

    /** 当前绑定的着色器程序 ID */
    private final int programId;

    /** 当前绑定的数组缓冲 ID */
    private final int arrayBufferBinding;

    /** 当前绑定的元素数组缓冲 ID */
    private final int elementArrayBufferBinding;

    // ==================== 绘制状态 ====================

    /** 绘制颜色 (RGBA) - 来自 glColor4f */
    private final float drawColorR;
    private final float drawColorG;
    private final float drawColorB;
    private final float drawColorA;

    /** 清除颜色 (RGBA) - 来自 glClearColor */
    private final float clearColorR;
    private final float clearColorG;
    private final float clearColorB;
    private final float clearColorA;

    // ==================== 缓存的哈希码 ====================

    /** 预计算的哈希码（用于 Pipeline Cache 查找） */
    private final int cachedHashCode;

    // ==================== 构造方法 ====================

    /**
     * 私有构造 - 使用 Builder 创建
     */
    private RenderPipeline(Builder builder) {
        this.blendEnabled = builder.blendEnabled;
        this.blendEquationRgb = builder.blendEquationRgb;
        this.blendEquationAlpha = builder.blendEquationAlpha;
        this.blendSrcRgb = builder.blendSrcRgb;
        this.blendDstRgb = builder.blendDstRgb;
        this.blendSrcAlpha = builder.blendSrcAlpha;
        this.blendDstAlpha = builder.blendDstAlpha;
        this.blendColor = builder.blendColor;

        this.depthTestEnabled = builder.depthTestEnabled;
        this.depthWriteEnabled = builder.depthWriteEnabled;
        this.depthCompareOp = builder.depthCompareOp;
        this.depthRangeNear = builder.depthRangeNear;
        this.depthRangeFar = builder.depthRangeFar;
        this.stencilTestEnabled = builder.stencilTestEnabled;

        this.cullFaceEnabled = builder.cullFaceEnabled;
        this.cullFaceMode = builder.cullFaceMode;
        this.frontFaceMode = builder.frontFaceMode;
        this.polygonMode = builder.polygonMode;
        this.polygonOffsetEnabled = builder.polygonOffsetEnabled;
        this.polygonOffsetFactor = builder.polygonOffsetFactor;
        this.polygonOffsetUnits = builder.polygonOffsetUnits;

        this.colorWriteR = builder.colorWriteR;
        this.colorWriteG = builder.colorWriteG;
        this.colorWriteB = builder.colorWriteB;
        this.colorWriteA = builder.colorWriteA;

        this.texture2DBinding = builder.texture2DBinding;
        this.activeTextureUnit = builder.activeTextureUnit;
        this.programId = builder.programId;
        this.arrayBufferBinding = builder.arrayBufferBinding;
        this.elementArrayBufferBinding = builder.elementArrayBufferBinding;

        this.drawColorR = builder.drawColorR;
        this.drawColorG = builder.drawColorG;
        this.drawColorB = builder.drawColorB;
        this.drawColorA = builder.drawColorA;
        this.clearColorR = builder.clearColorR;
        this.clearColorG = builder.clearColorG;
        this.clearColorB = builder.clearColorB;
        this.clearColorA = builder.clearColorA;

        // 构造时立即计算哈希码
        this.cachedHashCode = computeHash();
    }

    // ==================== Builder ====================

    /**
     * RenderPipeline 构建器
     *
     * <p>从 GLStateSnapshot 提取数据填充此 Builder，
     * 然后 build() 创建不可变实例。
     */
    public static final class Builder {

        // 所有字段默认值与 OpenGL 默认一致
        private boolean blendEnabled;
        private int blendEquationRgb = 0x8006; // GL_FUNC_ADD
        private int blendEquationAlpha = 0x8006;
        private int blendSrcRgb = 0x0302;       // GL_SRC_ALPHA
        private int blendDstRgb = 0x0303;       // GL_ONE_MINUS_SRC_ALPHA
        private int blendSrcAlpha = 0x0302;
        private int blendDstAlpha = 0x0303;
        private int blendColor = 0xFFFFFFFF;     // 白色

        private boolean depthTestEnabled;
        private boolean depthWriteEnabled = true;
        private int depthCompareOp = 0x0201;     // GL_LESS
        private float depthRangeNear = 0.0f;
        private float depthRangeFar = 1.0f;
        private boolean stencilTestEnabled;

        private boolean cullFaceEnabled;
        private int cullFaceMode = 0x0405;      // GL_BACK
        private int frontFaceMode = 0x0901;     // GL_CCW
        private int polygonMode = 0x1B02;       // GL_FILL
        private boolean polygonOffsetEnabled;
        private float polygonOffsetFactor = 0.0f;
        private float polygonOffsetUnits = 0.0f;

        private boolean colorWriteR = true;
        private boolean colorWriteG = true;
        private boolean colorWriteB = true;
        private boolean colorWriteA = true;

        private int texture2DBinding;
        private int activeTextureUnit = 0x84C0;  // GL_TEXTURE0
        private int programId;
        private int arrayBufferBinding;
        private int elementArrayBufferBinding;

        private float drawColorR = 1.0f;
        private float drawColorG = 1.0f;
        private float drawColorB = 1.0f;
        private float drawColorA = 1.0f;
        private float clearColorR = 0.0f;
        private float clearColorG = 0.0f;
        private float clearColorB = 0.0f;
        private float clearColorA = 1.0f;

        public Builder() {}

        /** 从 GLStateSnapshot 快速填充 */
        public static Builder fromSnapshot(GLStateSnapshot snapshot) {
            Builder b = new Builder();
            b.blendEnabled = snapshot.isBlendEnabled();
            b.depthTestEnabled = snapshot.isDepthTestEnabled();
            b.cullFaceEnabled = snapshot.isCullFaceEnabled();
            b.stencilTestEnabled = snapshot.isCapabilityEnabled(GLStateSnapshot.CAP_STENCIL_TEST);
            b.polygonOffsetEnabled = snapshot.isCullFaceEnabled();

            b.texture2DBinding = snapshot.getTexture2DBinding();
            b.activeTextureUnit = 0;
            b.programId = 0;

            b.drawColorR = 1.0f;
            b.drawColorG = 1.0f;
            b.drawColorB = 1.0f;
            b.drawColorA = 1.0f;

            return b;
        }

        // ==================== Setter 方法 ====================

        public Builder withBlendEnabled(boolean v) { this.blendEnabled = v; return this; }
        public Builder withBlendEquationRgb(int v) { this.blendEquationRgb = v; return this; }
        public Builder withBlendEquationAlpha(int v) { this.blendEquationAlpha = v; return this; }
        public Builder withBlendSrcRgb(int v) { this.blendSrcRgb = v; return this; }
        public Builder withBlendDstRgb(int v) { this.blendDstRgb = v; return this; }
        public Builder withBlendSrcAlpha(int v) { this.blendSrcAlpha = v; return this; }
        public Builder withBlendDstAlpha(int v) { this.blendDstAlpha = v; return this; }
        public Builder withBlendColor(int v) { this.blendColor = v; return this; }
        public Builder withDepthTestEnabled(boolean v) { this.depthTestEnabled = v; return this; }
        public Builder withDepthWriteEnabled(boolean v) { this.depthWriteEnabled = v; return this; }
        public Builder withDepthCompareOp(int v) { this.depthCompareOp = v; return this; }
        public Builder withDepthRangeNear(float v) { this.depthRangeNear = v; return this; }
        public Builder withDepthRangeFar(float v) { this.depthRangeFar = v; return this; }
        public Builder withStencilTestEnabled(boolean v) { this.stencilTestEnabled = v; return this; }
        public Builder withCullFaceEnabled(boolean v) { this.cullFaceEnabled = v; return this; }
        public Builder withCullFaceMode(int v) { this.cullFaceMode = v; return this; }
        public Builder withFrontFaceMode(int v) { this.frontFaceMode = v; return this; }
        public Builder withPolygonMode(int v) { this.polygonMode = v; return this; }
        public Builder withPolygonOffsetEnabled(boolean v) { this.polygonOffsetEnabled = v; return this; }
        public Builder withPolygonOffsetFactor(float v) { this.polygonOffsetFactor = v; return this; }
        public Builder withPolygonOffsetUnits(float v) { this.polygonOffsetUnits = v; return this; }
        public Builder withColorWriteR(boolean v) { this.colorWriteR = v; return this; }
        public Builder withColorWriteG(boolean v) { this.colorWriteG = v; return this; }
        public Builder withColorWriteB(boolean v) { this.colorWriteB = v; return this; }
        public Builder withColorWriteA(boolean v) { this.colorWriteA = v; return this; }
        public Builder withTexture2DBinding(int v) { this.texture2DBinding = v; return this; }
        public Builder withActiveTextureUnit(int v) { this.activeTextureUnit = v; return this; }
        public Builder withProgramId(int v) { this.programId = v; return this; }
        public Builder withArrayBufferBinding(int v) { this.arrayBufferBinding = v; return this; }
        public Builder withElementArrayBufferBinding(int v) { this.elementArrayBufferBinding = v; return this; }
        public Builder withDrawColor(float r, float g, float b, float a) {
            this.drawColorR = r; this.drawColorG = g; this.drawColorB = b; this.drawColorA = a;
            return this;
        }
        public Builder withClearColor(float r, float g, float b, float a) {
            this.clearColorR = r; this.clearColorG = g; this.clearColorB = b; this.clearColorA = a;
            return this;
        }

        public RenderPipeline build() {
            return new RenderPipeline(this);
        }
    }

    // ==================== 核心契约 ====================

    /**
     * 计算哈希码
     *
     * <p>基于所有字段计算确定性哈希。相同状态的 RenderPipeline 必须产生相同的哈希值，
     * 这是 Pipeline Cache 正确工作的前提。
     *
     * <p>算法选择：使用与 Java HashMap 兼容的 31-多项式哈希，
     * 保证低碰撞率的同时避免昂贵的计算。
     */
    @Override
    public int hashCode() {
        return cachedHashCode;
    }

    /**
     * 获取管线状态的哈希值（用于 Pipeline Cache 查找）
     *
     * @return 哈希值
     */
    public long getHash() {
        return Integer.toUnsignedLong(cachedHashCode);
    }

    /**
     * Pipeline 状态位掩码定义
     *
     * <p>stateBits[0]: 混合+深度+模板+光栅化状态 (32bit each)
     * <pre>
     * Bit  0: blendEnabled
     * Bit  1: depthTestEnabled
     * Bit  2: depthWriteEnabled
     * Bit  3: stencilTestEnabled
     * Bit  4: cullFaceEnabled
     * Bit  5: polygonOffsetEnabled
     * Bit  6: colorWriteR
     * Bit  7: colorWriteG
     * Bit  8: colorWriteB
     * Bit  9: colorWriteA
     * Bits 16-19: blendEquationRgb
     * Bits 20-23: blendEquationAlpha
     * Bits 24-27: blendSrcRgb
     * Bits 28-31: blendDstRgb
     * </pre>
     *
     * stateBits[1]: 深度比较+剔除模式+前面模式+多边形模式
     * stateBits[2]: programId (程序 ID)
     */
    private static final int BIT_BLEND_ENABLED = 1 << 0;
    private static final int BIT_DEPTH_TEST_ENABLED = 1 << 1;
    private static final int BIT_DEPTH_WRITE_ENABLED = 1 << 2;
    private static final int BIT_STENCIL_TEST_ENABLED = 1 << 3;
    private static final int BIT_CULL_FACE_ENABLED = 1 << 4;
    private static final int BIT_POLYGON_OFFSET_ENABLED = 1 << 5;

    /**
     * 从位域状态快照创建 RenderPipeline
     *
     * @param stateBits  位域状态数组 [3]
     * @param programId  着色器程序 ID
     * @return 新的 RenderPipeline 实例
     */
    public static RenderPipeline fromStateBits(long[] stateBits, int programId) {
        if (stateBits == null || stateBits.length < 3) {
            throw new IllegalArgumentException("stateBits must be a non-null array of length >= 3");
        }

        long word0 = stateBits[0];
        long word1 = stateBits[1];
        long word2 = stateBits[2];

        Builder builder = new Builder()
            .withBlendEnabled((word0 & BIT_BLEND_ENABLED) != 0)
            .withDepthTestEnabled((word0 & BIT_DEPTH_TEST_ENABLED) != 0)
            .withDepthWriteEnabled((word0 & BIT_DEPTH_WRITE_ENABLED) != 0)
            .withStencilTestEnabled((word0 & BIT_STENCIL_TEST_ENABLED) != 0)
            .withCullFaceEnabled((word0 & BIT_CULL_FACE_ENABLED) != 0)
            .withPolygonOffsetEnabled((word0 & BIT_POLYGON_OFFSET_ENABLED) != 0)
            .withColorWriteR((word0 & (1 << 6)) != 0)
            .withColorWriteG((word0 & (1 << 7)) != 0)
            .withColorWriteB((word0 & (1 << 8)) != 0)
            .withColorWriteA((word0 & (1 << 9)) != 0)
            .withBlendEquationRgb((int) ((word0 >> 16) & 0xF))
            .withBlendEquationAlpha((int) ((word0 >> 20) & 0xF))
            .withBlendSrcRgb((int) ((word0 >> 24) & 0xF))
            .withBlendDstRgb((int) ((word0 >> 28) & 0xF))
            .withDepthCompareOp((int) (word1 & 0x7))
            .withCullFaceMode((int) ((word1 >> 4) & 0x7))
            .withFrontFaceMode((int) ((word1 >> 8) & 0x1))
            .withPolygonMode((int) ((word1 >> 12) & 0x3))
            .withTexture2DBinding((int) ((word2 >> 32) & 0xFFFFFFFFL))
            .withProgramId(programId);

        return builder.build();
    }

    /**
     * 实际哈希计算逻辑
     */
    private int computeHash() {
        int result = 17;

        // 混合状态
        result = 31 * result + (blendEnabled ? 1 : 0);
        result = 31 * result + blendEquationRgb;
        result = 31 * result + blendEquationAlpha;
        result = 31 * result + blendSrcRgb;
        result = 31 * result + blendDstRgb;
        result = 31 * result + blendSrcAlpha;
        result = 31 * result + blendDstAlpha;
        result = 31 * result + blendColor;

        // 深度状态
        result = 31 * result + (depthTestEnabled ? 1 : 0);
        result = 31 * result + (depthWriteEnabled ? 1 : 0);
        result = 31 * result + depthCompareOp;
        result = 31 * result + Float.floatToIntBits(depthRangeNear);
        result = 31 * result + Float.floatToIntBits(depthRangeFar);

        // 光栅化状态
        result = 31 * result + (cullFaceEnabled ? 1 : 0);
        result = 31 * result + cullFaceMode;
        result = 31 * result + frontFaceMode;
        result = 31 * result + polygonMode;
        result = 31 * result + Float.floatToIntBits(polygonOffsetFactor);
        result = 31 * result + Float.floatToIntBits(polygonOffsetUnits);

        // 颜色掩码
        result = 31 * result + (colorWriteR ? 1 : 0);
        result = 31 * result + (colorWriteG ? 1 : 0);
        result = 31 * result + (colorWriteB ? 1 : 0);
        result = 31 * result + (colorWriteA ? 1 : 0);

        // 资源绑定
        result = 31 * result + texture2DBinding;
        result = 31 * result + programId;

        // 绘制颜色
        result = 31 * result + Float.floatToIntBits(drawColorR);
        result = 31 * result + Float.floatToIntBits(drawColorG);
        result = 31 * result + Float.floatToIntBits(drawColorB);
        result = 31 * result + Float.floatToIntBits(drawColorA);

        return result;
    }

    /**
     * 相等性判断
     *
     * <p>两个 RenderPipeline 相等当且仅当所有字段都相等。
     * 这确保了 Pipeline Cache 能正确复用 VkPipeline。
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof RenderPipeline)) return false;
        RenderPipeline other = (RenderPipeline) obj;

        // 快速路径：先比哈希
        if (cachedHashCode != other.cachedHashCode) return false;

        // 逐字段比较（按重要性排序，最可能不同的放前面）
        return blendEnabled == other.blendEnabled &&
               depthTestEnabled == other.depthTestEnabled &&
               cullFaceEnabled == other.cullFaceEnabled &&
               depthCompareOp == other.depthCompareOp &&
               blendSrcRgb == other.blendSrcRgb &&
               blendDstRgb == other.blendDstRgb &&
               programId == other.programId &&
               texture2DBinding == other.texture2DBinding &&
               polygonMode == other.polygonMode &&
               cullFaceMode == other.cullFaceMode &&
               frontFaceMode == other.frontFaceMode &&
               depthWriteEnabled == other.depthWriteEnabled &&
               colorWriteR == other.colorWriteR &&
               colorWriteG == other.colorWriteG &&
               colorWriteB == other.colorWriteB &&
               colorWriteA == other.colorWriteA &&
               blendEquationRgb == other.blendEquationRgb &&
               blendEquationAlpha == other.blendEquationAlpha &&
               blendSrcAlpha == other.blendSrcAlpha &&
               blendDstAlpha == other.blendDstAlpha &&
               stencilTestEnabled == other.stencilTestEnabled &&
               polygonOffsetEnabled == other.polygonOffsetEnabled &&
               Float.compare(polygonOffsetFactor, other.polygonOffsetFactor) == 0 &&
               Float.compare(polygonOffsetUnits, other.polygonOffsetUnits) == 0 &&
               Float.compare(depthRangeNear, other.depthRangeNear) == 0 &&
               Float.compare(depthRangeFar, other.depthRangeFar) == 0 &&
               Float.compare(drawColorR, other.drawColorR) == 0 &&
               Float.compare(drawColorG, other.drawColorG) == 0 &&
               Float.compare(drawColorB, other.drawColorB) == 0 &&
               Float.compare(drawColorA, other.drawColorA) == 0;
    }

    // ==================== Getter 方法 ====================

    public boolean isBlendEnabled() { return blendEnabled; }
    public boolean isDepthTestEnabled() { return depthTestEnabled; }
    public boolean isDepthWriteEnabled() { return depthWriteEnabled; }
    public boolean isCullFaceEnabled() { return cullFaceEnabled; }
    public boolean isStencilTestEnabled() { return stencilTestEnabled; }
    public int getDepthCompareOp() { return depthCompareOp; }
    public int getPolygonMode() { return polygonMode; }
    public int getCullFaceMode() { return cullFaceMode; }
    public int getProgramId() { return programId; }
    public int getTexture2DBinding() { return texture2DBinding; }
    public int getBlendSrcRgb() { return blendSrcRgb; }
    public int getBlendDstRgb() { return blendDstRgb; }

    @Override
    public String toString() {
        return String.format("RenderPipeline{hash=%08x, blend=%s, depth=%s, cull=%s, prog=%d}",
                cachedHashCode,
                blendEnabled ? "ON" : "OFF",
                depthTestEnabled ? "ON" : "OFF",
                cullFaceEnabled ? "ON" : "OFF",
                programId);
    }
}
