// ============================================================
// NanGuardShader - 向后兼容包装类 (Deprecated)
// ============================================================
// 此类已迁移至: com.renderium.core.quality.NanGuardShader
//
// 迁移时间: 2026-04-25
// 迁移原因: Core 包重构 - 按功能域划分子包 (quality)
//
// 使用方式:
//   旧代码（无需修改，但会收到编译警告）:
//     import com.renderium.core.NanGuardShader;
//     float safe = NanGuardShader.nanGuardClamp(value, 0.0f, 1.0f);
//
//   新代码（推荐迁移）:
//     import com.renderium.core.quality.NanGuardShader;
//     float safe = NanGuardShader.nanGuardClamp(value, 0.0f, 1.0f);
//
// 删除计划: 此类将在 v7.0 中删除，请尽快迁移到新包路径。
// ============================================================

package com.renderium.core;

/**
 * NanGuard 无分支异常处理工具类（向后兼容包装类）
 * <p>
 * 此类仅为向后兼容而保留，所有静态方法调用均委托给
 * {@link com.renderium.core.quality.NanGuardShader}。
 *
 * @deprecated 已迁移至 {@link com.renderium.core.quality.NanGuardShader}
 *             请更新 import 路径。此类将在 v7.0 中删除。
 * @since 1.0
 * @see com.renderium.core.quality.NanGuardShader
 */
@Deprecated(since = "6.0", forRemoval = true)
public final class NanGuardShader {

    // 防止实例化（与原始类保持一致的工具类模式）
    private NanGuardShader() {
        throw new UnsupportedOperationException("NanGuardShader 是工具类，不允许实例化");
    }

    // ==================== 常量委托 ====================

    /** NaN 检测的 epsilon 阈值（委托） */
    public static final float NAN_EPSILON =
        com.renderium.core.quality.NanGuardShader.NAN_EPSILON;

    /** 最小安全归一化长度阈值（委托） */
    public static final float MIN_NORMALIZE_LENGTH =
        com.renderium.core.quality.NanGuardShader.MIN_NORMALIZE_LENGTH;

    /** 默认安全值（委托） */
    public static final float DEFAULT_SAFE_VALUE =
        com.renderium.core.quality.NanGuardShader.DEFAULT_SAFE_VALUE;

    // ==================== GLSL 函数库委托 ====================

    /**
     * 获取完整的 GLSL NanGuard 函数库源码（委托）
     *
     * @return 完整的 GLSL 函数库源码字符串
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static String getGlslLibrary() {
        return com.renderium.core.quality.NanGuardShader.getGlslLibrary();
    }

    /**
     * 获取单个 GLSL 函数的定义（委托）
     *
     * @param functionName 函数名称
     * @return 对应函数的 GLSL 定义字符串
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static String getGlslFunction(String functionName) {
        return com.renderium.core.quality.NanGuardShader.getGlslFunction(functionName);
    }

    // ==================== HLSL 函数库委托 ====================

    /**
     * 获取完整的 HLSL NanGuard 函数集（委托）
     *
     * @return 完整的 HLSL 函数定义字符串
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static String getHlslFunctions() {
        return com.renderium.core.quality.NanGuardShader.getHlslFunctions();
    }

    // ==================== Java 端静态方法委托 ====================

    /**
     * 带 NaN/Inf 检测的安全钳位函数（4参数版本，委托）
     *
     * @param value          输入值
     * @param minValue       允许的最小值
     * @param maxValue       允许的最大值
     * @param defaultValue   异常时的默认值
     * @return 处理后的安全值
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static float nanGuardClamp(float value, float minValue, float maxValue, float defaultValue) {
        return com.renderium.core.quality.NanGuardShader.nanGuardClamp(value, minValue, maxValue, defaultValue);
    }

    /**
     * 带 NaN/Inf 检测的安全钳位函数（3参数版本，委托）
     *
     * @param value      输入值
     * @param minValue   允许的最小值
     * @param maxValue   允许的最大值
     * @return 处理后的安全值
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static float nanGuardClamp(float value, float minValue, float maxValue) {
        return com.renderium.core.quality.NanGuardShader.nanGuardClamp(value, minValue, maxValue);
    }

    /**
     * 安全的向量归一化函数（带默认方向，委托）
     *
     * @param vector           输入三维向量
     * @param defaultDirection 归一化失败时的默认方向
     * @return 归一化后的单位向量
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static float[] safeNormalize(float[] vector, float[] defaultDirection) {
        return com.renderium.core.quality.NanGuardShader.safeNormalize(vector, defaultDirection);
    }

    /**
     * 安全的向量归一化函数（默认方向版本，委托）
     *
     * @param vector 输入三维向量
     * @return 归一化后的单位向量
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static float[] safeNormalize(float[] vector) {
        return com.renderium.core.quality.NanGuardShader.safeNormalize(vector);
    }

    /**
     * 安全的除法函数（4参数版本，委托）
     *
     * @param dividend     被除数
     * @param divisor      除数
     * @param defaultValue 除法失败时的回退值
     * @param epsilon      判定"除数过小"的阈值
     * @return 安全的除法结果
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static float safeDivide(float dividend, float divisor, float defaultValue, float epsilon) {
        return com.renderium.core.quality.NanGuardShader.safeDivide(dividend, divisor, defaultValue, epsilon);
    }

    /**
     * 安全的除法函数（3参数版本，委托）
     *
     * @param dividend     被除数
     * @param divisor      除数
     * @param defaultValue 除法失败时的回退值
     * @return 安全的除法结果
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static float safeDivide(float dividend, float divisor, float defaultValue) {
        return com.renderium.core.quality.NanGuardShader.safeDivide(dividend, divisor, defaultValue);
    }

    /**
     * 安全的除法函数（2参数版本，委托）
     *
     * @param dividend 被除数
     * @param divisor  除数
     * @return 安全的除法结果
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static float safeDivide(float dividend, float divisor) {
        return com.renderium.core.quality.NanGuardShader.safeDivide(dividend, divisor);
    }
}
