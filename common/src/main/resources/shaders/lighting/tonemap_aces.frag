#version 450

// ============================================
// Renderium Default ACES Tonemapping Pass
// ACES Filmic Tone Mapping Curve
// 将 HDR 线性颜色映射到 SDR 显示范围
//
// 功能说明：
// 1. 应用曝光调整（模拟相机曝光）
// 2. 使用 ACES Filmic 曲线进行色调映射（HDR → SDR）
// 3. 可选的饱和度和对比度调整
//
// ACES 算法来源：
// - Approximation by Krzyszof Narkowicz (2015)
// - Academy Color Encoding System 标准
// - 参考：https://knarkowicz.wordpress.com/2016/01/06/aces-filmic-tone-mapping-curve/
//
// 参数：
// - uExposure: 曝光值（0.1-10.0，默认1.0）
//   > 1.0 增亮，< 1.0 变暗
// - uSaturation: 饱和度（0.0-2.0，默认1.0）
//   1.0 = 原始饱和度，> 1.0 增强，< 1.0 降低
// - uContrast: 对比度（0.5-2.0，默认1.0）
//   1.0 = 原始对比度
//
// 色调映射曲线特性：
// - 保留高光细节（不会像 Reinhard 那样完全压平）
// - 自然的阴影过渡
// - 电影感的色彩表现
// ============================================

// --- 输入：上一 Pass 的输出纹理 ---
layout(binding = 0) uniform sampler2D uRenderiumInput;

// --- 输出：最终颜色 ---
layout(location = 0) out vec4 fragColor;

// --- Uniform 参数 ---
layout(push_constant) uniform TonemapParams {
    float uExposure;         // 曝光值（默认 1.0）
    float uSaturation;       // 饱和度（默认 1.0）
    float uContrast;         // 对比度（默认 1.0）
    float _padding;          // 对齐填充（保留）
} tonemap;

// --- 常量定义 ---
const float EPSILON = 1e-6;

// ==================== ACES 核心算法 ====================

/**
 * ACES Filmic Tone Mapping 曲线
 * 使用有理函数近似真实的 ACES ODT（输出设备变换）
 *
 * 公式：R(x) = (x * (A*x + B)) / (x * (C*x + D) + E)
 *
 * 系数说明：
 * - A, B: 控制高光区域的肩部形状
 * - C, D: 控制中间调的线性区域斜率
 * - E: 控制阴影区域的脚部形状
 *
 * @param x 输入 HDR 颜色分量（线性空间，可能 > 1.0）
 * @return 映射后的 SDR 颜色分量（范围 [0.0, 1.0]）
 */
vec3 acesFilmic(vec3 x) {
    // ACES 近似系数（Narkowicz 2015）
    const float A = 2.51;
    const float B = 0.03;
    const float C = 2.43;
    const float D = 0.59;
    const float E = 0.14;

    // 应用 ACES 有理函数映射
    return clamp((x * (A * x + B)) / (x * (C * x + D) + E), 0.0, 1.0);
}

/**
 * 曝光调整
 * 模拟相机的曝光控制
 *
 * @param color 输入颜色（HDR 线性空间）
 * @param exposure EV 值（曝光值）
 * @return 调整曝光后的颜色
 *
 * 曝光值说明：
 * - exposure = 1.0: 正常曝光（EV 0）
 * - exposure = 2.0: +1 EV（亮度翻倍）
 * - exposure = 0.5: -1 EV（亮度减半）
 */
vec3 applyExposure(vec3 color, float exposure) {
    // 使用 2^exposure 作为乘数（符合摄影学 EV 定义）
    float exposureMultiplier = pow(2.0, exposure);
    return color * exposureMultiplier;
}

/**
 * 饱和度调整
 * 在 ACES 色彩空间中调整色彩饱和度
 * 保持亮度不变，只改变色彩的鲜艳程度
 *
 * @param color 输入颜色
 * @param saturation 饱和度系数
 * @return 调整饱和度后的颜色
 *
 * 算法原理：
 * 计算颜色的灰度版本，然后在原始颜色和灰度之间进行插值
 */
vec3 applySaturation(vec3 color, float saturation) {
    // 计算亮度（使用 ITU-R BT.709 系数）
    float lum = dot(color, vec3(0.2126, 0.7152, 0.0722));

    // 在原色和灰度之间插值
    // saturation = 0.0 完全去饱和（黑白）
    // saturation = 1.0 保持原色
    // saturation > 1.0 增强饱和度
    return mix(vec3(lum), color, saturation);
}

/**
 * 对比度调整
 * 使用 S 型曲线调整对比度
 * 增强明暗差异，同时避免极端值的裁剪
 *
 * @param color 输入颜色
 * @param contrast 对比度系数
 * @return 调整对比度后的颜色
 *
 * 算法原理：
 * 使用简单的 power 函数实现中心加权对比度
 * 先将颜色移到以 0.5 为中心，应用 power，再移回
 */
vec3 applyContrast(vec3 color, float contrast) {
    // 以 0.5 为中心点进行对比度调整
    return pow((color - 0.5) * max(contrast, EPSILON) + 0.5, vec3(1.0));
}

/**
 * sRGB 伽马校正（线性 → sRGB）
 * 将线性颜色转换到伽马空间用于显示
 *
 * @param color 线性空间颜色
 * @return sRGB 伽马空间颜色
 */
vec3 linearToSRGB(vec3 color) {
    // 近似 sRGB 传输函数
    return pow(color, vec3(1.0 / 2.2));
}

// ==================== 主函数 ====================

void main() {
    // 读取输入颜色（来自上一 Pass，如 Bloom 或颜色校正）
    vec4 inputColor = texture(uRenderiumInput, gl_FragCoord.xy);

    vec3 color = inputColor.rgb;

    // ====== 步骤 1: 曝光调整 ======
    // 在色调映射之前应用曝光
    color = applyExposure(color, tonemap.uExposure);

    // ====== 步骤 2: ACES 色调映射 ======
    // 核心 HDR → SDR 映射
    color = acesFilmic(color);

    // ====== 步骤 3: 饱和度调整 ======
    // 在色调映射后调整饱和度（效果更自然）
    color = applySaturation(color, tonemap.uSaturation);

    // ====== 步骤 4: 对比度调整 ======
    color = applyContrast(color, tonemap.uContrast);

    // ====== 步骤 5: 伽马校正 ======
    // 转换到 sRGB 显示空间
    color = linearToSRGB(color);

    // 确保颜色值在有效范围内
    color = clamp(color, 0.0, 1.0);

    // 输出最终颜色
    fragColor = vec4(color, inputColor.a);
}
