// Renderium - MockGL11 兼容层入口
// 委托给 graphics.gl.MockGL11，保持向后兼容
// GL 常量定义集中在此，供 Mixin 层引用

package com.renderium.compatibility;

import com.renderium.graphics.command.CommandBuffer;
import com.renderium.graphics.command.DrawCommand;
import com.renderium.graphics.pipeline.RenderPipeline;

/**
 * MockGL11 兼容层入口。
 *
 * <p>此类保留是为了向后兼容旧的 Mixin 引用。
 * 所有实际逻辑已委托给 {@link com.renderium.graphics.gl.MockGL11}。
 *
 * <h2>GL 常量定义</h2>
 * <p>所有 OpenGL 常量集中在此类中定义，供 Mixin 层引用。
 * 这避免了在 Mixin 代码中硬编码十六进制数值。
 *
 * @see com.renderium.graphics.gl.MockGL11
 * @see com.renderium.graphics.gl.GLStateSnapshot
 * @author Renderium Team
 * @since 1.0.0
 */
public final class MockGL11 {

    // ==================== GL 常量定义 ====================

    /** GL 常量：三角形模式 */
    public static final int GL_TRIANGLES = 4;
    /** GL 常量：线段模式 */
    public static final int GL_LINES = 1;
    /** GL 常量：点模式 */
    public static final int GL_POINTS = 0;
    /** GL 常量：三角形带 */
    public static final int GL_TRIANGLE_STRIP = 5;
    /** GL 常量：三角形扇 */
    public static final int GL_TRIANGLE_FAN = 6;

    // glEnable/glDisable 常量
    public static final int GL_BLEND = 0x0BE2;
    public static final int GL_DEPTH_TEST = 0x0B71;
    public static final int GL_STENCIL_TEST = 0x0B90;
    public static final int GL_CULL_FACE = 0x0B44;
    public static final int GL_DITHER = 0x0BD0;
    public static final int GL_SCISSOR_TEST = 0x0C11;
    public static final int GL_MULTISAMPLE = 0x809D;
    public static final int GL_POLYGON_OFFSET_FILL = 0x8037;
    public static final int GL_SAMPLE_ALPHA_TO_COVERAGE = 0x809E;
    public static final int GL_ALPHA_TEST = 0x0BC0;

    // 混合因子常量
    public static final int GL_ZERO = 0;
    public static final int GL_ONE = 1;
    public static final int GL_SRC_COLOR = 0x0300;
    public static final int GL_ONE_MINUS_SRC_COLOR = 0x0301;
    public static final int GL_DST_COLOR = 0x0306;
    public static final int GL_ONE_MINUS_DST_COLOR = 0x0307;
    public static final int GL_SRC_ALPHA = 0x0302;
    public static final int GL_ONE_MINUS_SRC_ALPHA = 0x0303;
    public static final int GL_DST_ALPHA = 0x0304;
    public static final int GL_ONE_MINUS_DST_ALPHA = 0x0305;
    public static final int GL_CONSTANT_COLOR = 0x8001;
    public static final int GL_ONE_MINUS_CONSTANT_COLOR = 0x8002;
    public static final int GL_CONSTANT_ALPHA = 0x8003;
    public static final int GL_ONE_MINUS_CONSTANT_ALPHA = 0x8004;

    // 混合方程式常量
    public static final int GL_FUNC_ADD = 0x8006;
    public static final int GL_FUNC_SUBTRACT = 0x800A;
    public static final int GL_FUNC_REVERSE_SUBTRACT = 0x800B;
    public static final int GL_MIN = 0x8007;
    public static final int GL_MAX = 0x8008;

    // 比较函数常量
    public static final int GL_NEVER = 0x0200;
    public static final int GL_LESS = 0x0201;
    public static final int GL_EQUAL = 0x0202;
    public static final int GL_LEQUAL = 0x0203;
    public static final int GL_GREATER = 0x0204;
    public static final int GL_NOTEQUAL = 0x0205;
    public static final int GL_GEQUAL = 0x0206;
    public static final int GL_ALWAYS = 0x0207;

    // 剔除面常量
    public static final int GL_FRONT = 0x0404;
    public static final int GL_BACK = 0x0405;
    public static final int GL_FRONT_AND_BACK = 0x0408;
    public static final int GL_CW = 0x0900;
    public static final int GL_CCW = 0x0901;

    // 多边形模式常量
    public static final int GL_FILL = 0x1B02;
    public static final int GL_LINE = 0x1B01;
    public static final int GL_POINT = 0x1B00;

    // 索引类型常量
    public static final int GL_UNSIGNED_BYTE = 0x1401;
    public static final int GL_UNSIGNED_SHORT = 0x1403;
    public static final int GL_UNSIGNED_INT = 0x1405;

    // 纹理常量
    public static final int GL_TEXTURE_2D = 0x0DE1;
    public static final int GL_TEXTURE0 = 0x84C0;

    private MockGL11() {}

    // ==================== 委托方法 ====================

    /**
     * 委托给核心 MockGL11
     */
    public static void enable(int cap) {
        com.renderium.graphics.gl.MockGL11.enable(cap);
    }

    public static void disable(int cap) {
        com.renderium.graphics.gl.MockGL11.disable(cap);
    }

    public static boolean isEnabled(int cap) {
        return com.renderium.graphics.gl.MockGL11.isEnabled(cap);
    }

    public static void blendFunc(int sfactor, int dfactor) {
        com.renderium.graphics.gl.MockGL11.blendFunc(sfactor, dfactor);
    }

    public static void depthMask(boolean flag) {
        com.renderium.graphics.gl.MockGL11.depthMask(flag);
    }

    public static void depthFunc(int func) {
        com.renderium.graphics.gl.MockGL11.depthFunc(func);
    }

    public static void cullFace(int mode) {
        com.renderium.graphics.gl.MockGL11.cullFace(mode);
    }

    public static void frontFace(int mode) {
        com.renderium.graphics.gl.MockGL11.frontFace(mode);
    }

    public static void polygonMode(int face, int mode) {
        com.renderium.graphics.gl.MockGL11.polygonMode(face, mode);
    }

    public static void colorMask(boolean r, boolean g, boolean b, boolean a) {
        com.renderium.graphics.gl.MockGL11.colorMask(r, g, b, a);
    }

    public static void useProgram(int program) {
        com.renderium.graphics.gl.MockGL11.useProgram(program);
    }

    public static boolean drawArrays(int mode, int first, int count) {
        return com.renderium.graphics.gl.MockGL11.drawArrays(mode, first, count);
    }

    public static boolean drawElements(int mode, int count, int type, long indices) {
        return com.renderium.graphics.gl.MockGL11.drawElements(mode, count, type, indices);
    }

    public static int getError() {
        return com.renderium.graphics.gl.MockGL11.getError();
    }

    // ==================== 实例访问方法 ====================

    private static final MockGL11 INSTANCE = new MockGL11();

    /**
     * 获取 MockGL11 单例实例
     *
     * @return MockGL11 实例
     */
    public static MockGL11 getInstance() {
        return INSTANCE;
    }

    /**
     * 获取当前状态快照
     *
     * @return 状态快照实例
     */
    public com.renderium.graphics.gl.GLStateSnapshot getStateSnapshot() {
        return com.renderium.graphics.gl.MockGL11.getThreadLocalSnapshot();
    }
}
