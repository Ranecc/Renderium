#version 450

// ============================================
// Renderium Color Correction Pass
// 饱和度 / 色温 / Vibrance / Lift-Gamma-Gain
//
// 功能说明：
// 提供专业的颜色校正工具集，用于调整画面的色彩表现。
// 处理顺序：Lift/Gamma/Gain → 色温 → 饱和度 → Vibrance
//
// 各功能模块说明：
//
// 1. Lift-Gamma-Gain (LGG) 调色：
//    - Lift: 控制阴影区域（暗部）的颜色偏移和亮度
//    - Gamma: 控制中间调（中灰）的伽马曲线
*   - Gain: 控制高光区域的增益
//    - 这是专业调色软件（如 DaVinci Resolve）的标准工具
//
// 2. 色温调整：
//    - 模拟不同色温光源的效果（开尔文刻度）
//    - 低色温（< 5500K）：暖色调（橙/黄色偏移）
//    - 高色温（> 6500K）：冷色调（蓝色偏移）
//    - 中性白点：D65 (6504K)
//
// 3. 饱和度调整：
//    - 均匀地增强或降低所有颜色的饱和程度
//    - 影响包括肤色在内的所有色彩
//
// 4. Vibrance（智能饱和度）：
//    - 类似 Photoshop 的"自然饱和度"
//    - 智能保护已经高度饱和的颜色（防止过饱和）
//    - 主要提升低饱和度区域（如天空、草地）
//    - 对肤色影响较小（更自然的增强效果）
//
// 参数说明：
// - uSaturation: 全局饱和度（0.0-2.0，默认1.0）
// - uTemperature: 色温（1000K-40000K，默认6500K）
// - uVibrance: 智能饱和度（-1.0 到 1.0，默认0.0）
// - uLift: 阴影提升 RGB（各分量范围 -1.0 到 1.0）
// - uGamma: 中间调 Gamma（各分量范围 0.1 到 2.8）
// - uGain: 高光增益 RGB（各分量范围 0.0 到 2.0）
// ============================================

// --- 输入：上一 Pass 的输出纹理 ---
layout(binding = 0) uniform sampler2D uRenderiumInput;

// --- 输出：最终颜色 ---
layout(location = 0) out vec4 fragColor;

// --- Uniform 参数 ---
layout(push_constant) uniform ColorCorrectionParams {
    float uSaturation;       // 饱和度（默认 1.0）
    float uTemperature;      // 色温（单位：开尔文 K，默认 6500）
    float uVibrance;         // Vibrance / 智能饱和度（默认 0.0）

    // Lift-Gamma-Gain 参数（每个 vec3 包含 R, G, B 分量）
    vec3 uLift;              // 阴影提升（默认 0, 0, 0）
    vec3 uGamma;             // 中间调 Gamma（默认 1, 1, 1）
    vec3 uGain;              // 高光增益（默认 1, 1, 1）
} cc;

// --- 常量定义 ---
const float EPSILON = 1e-6;
const float D65_WHITE_POINT = 6504.0;  // D65 标准光源色温（sRGB 白点）

// ==================== 工具函数 ====================

/**
 * 计算像素的亮度值（ITC-R BT.709 标准）
 *
 * @param color 输入颜色 (RGB)
 * @return 亮度值（标量）
 */
float luminance(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

/**
 * Lift-Gamma-Gain (LGG) 调色曲线
 * 专业调色软件中的标准三路调色工具
 *
 * 算法原理：
 * 对于每个颜色通道 c ∈ [0, 1]：
 * output = lift + gain * pow(max(c, 0.0), 1.0/gamma)
 *
 * @param color 输入颜色（线性空间）
 * @param lift 阴影偏移（负值变暗，正值提亮并染色）
 * @param gamma 中间调伽马（< 1.0 变亮，> 1.0 变暗）
 * @param gain 高光增益（乘数）
 * @return LGG 调整后的颜色
 */
vec3 applyLiftGammaGain(vec3 color, vec3 lift, vec3 gamma, vec3 gain) {
    // 应用 LGG 公式到每个通道
    vec3 result;
    result.r = lift.r + gain.r * pow(max(color.r, 0.0), 1.0 / max(gamma.r, EPSILON));
    result.g = lift.g + gain.g * pow(max(color.g, 0.0), 1.0 / max(gamma.g, EPSILON));
    result.b = lift.b + gain.b * pow(max(color.b, 0.0), 1.0 / max(gamma.b, EPSILON));

    return clamp(result, 0.0, 2.0);  // 允许轻微超白以保留高光细节
}

/**
 * 色温调整
 * 将输入色温转换为 RGB 偏移量
 *
 * 算法基于 Planckian locus（黑体辐射轨迹）近似
 *
 * @param color 输入颜色
 * @param temperatureKelvin 目标色温（开尔文）
 * @return 色温调整后的颜色
 *
 * 色温参考：
 * - 1000K: 蜡烛光（深橙色）
 * - 2700K: 白炽灯（暖白色）
 * - 4000K: 日光灯（中性白）
 * - 5500K: 正午阳光（纯白色）
 * - 6500K: D65 标准（略带蓝的白色）
 * - 10000K: 阴天/阴影（蓝色调）
 * - 30000K: 深空背景（深蓝色）
 */
vec3 applyColorTemperature(vec3 color, float temperatureKelvin) {
    // 将温度限制在合理范围内
    float temp = clamp(temperatureKelvin, 1000.0, 40000.0);

    // 计算相对于 D65 白点的色温偏移
    // 使用简化的 Tanner Helland 近似算法
    vec3 tempColor;

    if (temp <= 66000.0 / 100.0) {  // <= 6600K（红域）
        // 红通道计算
        tempColor.r = 1.0;

        // 绿通道
        float green = 0.390081578769 * log(temp / 100.0) - 0.129890851021;
        tempColor.g = clamp(green, 0.0, 1.0);

        // 蓝通道（<= 1900K 时为 0）
        if (temp <= 19000.0 / 100.0) {  // <= 1900K
            tempColor.b = 0.0;
        } else {
            float blue = 0.543206178723 * log(temp / 100.0 - 10.0) - 0.196276095569;
            tempColor.b = clamp(blue, 0.0, 1.0);
        }
    } else {  // > 6600K（蓝域）
        // 红通道递减
        float red = 1.2929361860629286 * pow((66000.0 / 100.0) / temp, -0.1332047592);
        tempColor.r = clamp(red, 0.0, 1.0);

        // 绿通道
        float green = 1.129890860895 * pow((66000.0 / 100.0) / temp, -0.0755148492);
        tempColor.g = clamp(green, 0.0, 1.0);

        // 蓝通道
        tempColor.b = 1.0;
    }

    // 应用色温偏移：将颜色向目标色温方向混合
    // 使用 D65 作为基准点
    vec3 d65White = vec3(1.0);  // D65 在归一化空间为纯白

    // 计算混合系数（偏离 D65 的程度）
    float tempDiff = (temp - D65_WHITE_POINT) / D65_WHITE_POINT;
    float blendFactor = clamp(tempDiff * 0.5, -0.5, 0.5);  // 缩放因子

    // 在原始颜色和白平衡后的颜色之间插值
    vec3 balancedColor = color * (d65White / max(tempColor, vec3(EPSILON)));
    return mix(color, balancedColor, blendFactor);
}

/**
 * 饱和度调整
 * 均匀地改变所有颜色的鲜艳程度
 *
 * @param color 输入颜色
 * @param saturation 饱和度系数（0=黑白，1=原色，>1=增强）
 * @return 调整后的颜色
 */
vec3 applySaturation(vec3 color, float saturation) {
    float lum = luminance(color);
    return mix(vec3(lum), color, saturation);
}

/**
 * Vibrance（智能饱和度）
 * 类似 Photoshop 的"自然饱和度"功能
 *
 * 与普通饱和度的区别：
 * - 保护已高度饱和的区域（防止过饱和 clipping）
 * - 主要提升低饱和度区域
 * - 对肤色等中等饱和度的颜色影响较小
 *
 * @param color 输入颜色
 * @param vibrance 强度（-1.0 到 1.0，0=无效果）
 * @return 调整后的颜色
 *
 * 算法来源：
 * - Based on "Vibrance" by Jim Hejl & Richard Dawson-Dunn
 */
vec3 applyVibrance(vec3 color, float vibrance) {
    if (abs(vibrance) < EPSILON) {
        return color;  // 无需处理
    }

    // 计算每个通道的最大值（用于判断饱和度）
    float maxValue = max(max(color.r, color.g), color.b);
    float avgValue = (color.r + color.g + color.b) / 3.0;

    // 计算当前像素的饱和度水平
    float satLevel = maxValue - min(min(color.r, color.g), color.b);

    // 计算自适应权重：
    // - 高饱和度区域 → 权重小（保护）
    // - 低饱和度区域 → 权重大（增强）
    float adaptWeight = max(1.0 - satLevel * 2.0, 0.0);

    // 应用 vibrance 效果
    vec3 result = color;

    if (vibrance > 0.0) {
        // 增强模式：主要提升低饱和度区域
        result = mix(color, color * (1.0 + vibrance * adaptWeight), abs(vibrance));
    } else {
        // 降低模式：降低所有区域但保护低饱和度
        result = mix(color, mix(vec3(luminance(color)), color, 1.0 + vibrance), abs(vibrance));
    }

    return result;
}

// ==================== 主函数 ====================

void main() {
    // 读取输入颜色
    vec4 inputColor = texture(uRenderiumInput, gl_FragCoord.xy);
    vec3 color = inputColor.rgb;

    // ====== 处理顺序 ======
    // 按照 pack.yaml 规范定义的顺序执行：
    // Lift/Gamma/Gain → 色温 → 饱和度 → Vibrance

    // 步骤 1: Lift-Gamma-Gain 调色（基础色调映射）
    color = applyLiftGammaGain(color, cc.uLift, cc.uGamma, cc.uGain);

    // 步骤 2: 色温调整（白平衡）
    color = applyColorTemperature(color, cc.uTemperature);

    // 步骤 3: 饱和度调整（全局色彩强度）
    color = applySaturation(color, cc.uSaturation);

    // 步骤 4: Vibrance（智能饱和度增强）
    color = applyVibrance(color, cc.uVibrance);

    // 最终输出（保持 Alpha 通道不变）
    fragColor = vec4(clamp(color, 0.0, 2.0), inputColor.a);
}
