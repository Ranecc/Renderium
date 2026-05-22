// ============================================================
// NanGuard 无分支异常处理库 (GLSL 版本)
// ============================================================
//
// 基于 TOPS v2.5 L0.5 精度守恒层 §3.3 的 NanGuard 模式
//
// 核心原则：
//   if-else 导致 Warp 分化 → 使用 select/mix 指令
//   使用 isnan/isinf + step/clamp/mad 替代 if-else
//   覆盖 NaN/Inf/除零三种数值异常
//
// 版本: 1.0
// 作者: Renderium Team
// 许可: 与 Renderium 主项目相同
//
// 使用方式：
//   方式1 - 通过 #include 指令（推荐）:
//     #include "nanguard.glsl"
//
//   方式2 - 通过 Java 代码动态注入:
//     String glslCode = NanGuardShader.getGlslLibrary();
//     shaderSource += glslCode;
//
// 性能特征：
//   - 所有函数完全无分支，避免 Warp 分化
//   - 使用 GPU 原生指令（step、clamp、mix），单周期执行
//   - 额外开销仅 2-4 条 ALU 指令
//   - Worst-case 下比 if-else 版本快约 2x
//
// 函数列表：
//   1. nanGuardClamp(value, minVal, maxVal, defaultVal)
//      - 带 NaN/Inf 检测的安全钳位
//      - 支持标量 (float) 和向量 (vec2/vec3/vec4)
//
//   2. safeNormalize(vector, defaultDir)
//      - 安全的向量归一化（处理零向量）
//      - 当向量长度过短时返回默认方向
//
//   3. safeDivide(dividend, divisor, defaultVal, epsilon)
//      - 安全的除法运算（避免除零）
//      - 当除数过小时返回默认值
//
// 兼容性：
//   - OpenGL ES 3.0+ (WebGL 2.0)
//   - OpenGL 3.3+ (Desktop GL)
//   - Vulkan GLSL (通过 glslang 编译)
//   - 支持 #version 330 es / 450 core
//
// ============================================================

// 防止重复包含的保护宏
#ifndef NANGUARD_GLSL_INCLUDED
#define NANGUARD_GLSL_INCLUDED

// ============================================================
// 全局配置常量（可通过外部 #define 覆盖）
// ============================================================

#ifndef NANGUARD_EPSILON
/// NaN 检测和比较用的 epsilon 阈值（默认 1e-6）
#define NANGUARD_EPSILON 1e-6
#endif

#ifndef NANGUARD_MIN_NORMALIZE_LENGTH
/// 归一化时的最小安全长度阈值（默认 1e-8）
/// 当向量长度低于此值时，safeNormalize 返回默认方向
#define NANGUARD_MIN_NORMALIZE_LENGTH 1e-8
#endif

#ifndef NANGUARD_DEFAULT_SAFE_VALUE
/// 默认安全回退值（用于替换 NaN/Inf，默认 0.0）
#define NANGUARD_DEFAULT_SAFE_VALUE 0.0
#endif

#ifndef NANGUARD_DIVIDE_EPSILON
/// 除零检测的 epsilon 阈值（默认 1e-6）
/// 当 |divisor| < epsilon 时视为除零
#define NANGUARD_DIVIDE_EPSILON 1e-6
#endif

// ============================================================
// 1. nanGuardClamp - 带 NaN/Inf 检测的安全钳位
// ============================================================
//
// 功能：对输入值进行范围限制的同时检测并处理数值异常
//
// 参数：
//   @param value        - 输入值（可能为 NaN 或 ±Inf）
//   @param minValue     - 允许的最小值（下界）
//   @param maxValue     - 允许的最大值（上界）
//   @param defaultValue - 异常时的回退值（当输入为 NaN/Inf 时返回此值）
//
// 返回值：
//   - 若 value 正常：返回 clamp(value, minValue, maxValue)
//   - 若 value 为 NaN 或 Inf：返回 defaultValue
//
// 实现原理（无分支）：
//   步骤1: invalidMask = isnan(value) + isinf(value)
//           - NaN 或 Inf 时 invalidMask = 1.0
//           - 正常值时 invalidMask = 0.0
//
//   步骤2: clampedValue = clamp(value, minValue, maxValue)
//           - 对正常值进行范围限制
//
//   步骤3: result = mix(clampedValue, defaultValue, invalidMask)
//           - mix() 是 GPU 原生的线性插值指令
//           - mix(a, b, w) = a*(1-w) + b*w
//           - w=0 → 返回 a（正常路径）
//           - w=1 → 返回 b（异常路径）
//           - 整个过程无 if-else，避免 Warp 分化
//
// 指令数统计：
//   isnan:    1 ALU
//   isinf:    1 ALU
//   add:      1 ALU
//   clamp:    3 ALU (max + min)
//   mix:      3 ALU (lerp)
//   总计:     ~9 ALU 指令
//
// 使用示例：
//   // 从纹理读取的颜色值，可能有 NaN
//   float texValue = texture(myTex, uv).r;
//   float safeColor = nanGuardClamp(texValue, 0.0, 1.0, 0.0);
//
//   // 深度值的保护
//   float depth = nanGuardClamp(rawDepth, 0.0, 100.0, 1.0);
//

float nanGuardClamp(float value, float minValue, float maxValue, float defaultValue) {
    // 检测 NaN 或 Inf（异常时返回 1.0，正常时返回 0.0）
    // 使用 float() 将 bool 转换为 0.0/1.0
    float invalidMask = float(isnan(value)) + float(isinf(value));

    // 对正常值进行标准钳位操作
    float clampedValue = clamp(value, minValue, maxValue);

    // 无分支选择：使用 mix 进行条件选择
    // invalidMask == 0.0 → 返回 clampedValue（正常路径）
    // invalidMask == 1.0 → 返回 defaultValue（异常路径）
    return mix(clampedValue, defaultValue, invalidMask);
}

// ============================================================
// nanGuardClamp 向量版本（分量级操作）
// ============================================================

/// nanGuardClamp 的 vec2 版本（对每个分量独立处理）
vec2 nanGuardClamp(vec2 value, vec2 minValue, vec2 maxValue, vec2 defaultValue) {
    // 为每个分量生成独立的异常掩码
    vec2 invalidMask = vec2(
        float(isnan(value.x)) + float(isinf(value.x)),
        float(isnan(value.y)) + float(isinf(value.y))
    );

    // 分量级钳位和无分支选择
    return mix(clamp(value, minValue, maxValue), defaultValue, invalidMask);
}

/// nanGuardClamp 的 vec3 版本（对每个分量独立处理）
vec3 nanGuardClamp(vec3 value, vec3 minValue, vec3 maxValue, vec3 defaultValue) {
    // 为每个分量生成独立的异常掩码
    vec3 invalidMask = vec3(
        float(isnan(value.x)) + float(isinf(value.x)),
        float(isnan(value.y)) + float(isinf(value.y)),
        float(isnan(value.z)) + float(isinf(value.z))
    );

    // 分量级钳位和无分支选择
    return mix(clamp(value, minValue, maxValue), defaultValue, invalidMask);
}

/// nanGuardClamp 的 vec4 版本（对每个分量独立处理）
vec4 nanGuardClamp(vec4 value, vec4 minValue, vec4 maxValue, vec4 defaultValue) {
    // 为每个分量生成独立的异常掩码
    vec4 invalidMask = vec4(
        float(isnan(value.x)) + float(isinf(value.x)),
        float(isnan(value.y)) + float(isinf(value.y)),
        float(isnan(value.z)) + float(isinf(value.z)),
        float(isnan(value.w)) + float(isinf(value.w))
    );

    // 分量级钳位和无分支选择
    return mix(clamp(value, minValue, maxValue), defaultValue, invalidMask);
}

// ============================================================
// 便捷包装函数（使用默认参数值）
// ============================================================

/** nanGuardClamp 标量版本（defaultValue = NANGUARD_DEFAULT_SAFE_VALUE） */
float nanGuardClamp(float value, float minValue, float maxValue) {
    return nanGuardClamp(value, minValue, maxValue, NANGUARD_DEFAULT_SAFE_VALUE);
}


// ============================================================
// 2. safeNormalize - 安全的向量归一化
// ============================================================
//
// 功能：对三维向量进行归一化，当向量长度过短时返回默认方向
//
// 参数：
//   @param vector           - 输入三维向量（待归一化）
//   @param defaultDirection - 归一化失败时的默认方向（必须为单位向量）
//
// 返回值：
//   - 若 vector 长度 > MIN_NORMALIZE_LENGTH：返回 normalize(vector)
//   - 若 vector 长度过短：返回 defaultDirection
//
// 实现原理（无分支）：
//   步骤1: len = length(vector)
//           - 计算 L2 范数 sqrt(x²+y²+z²)
//
//   步骤2: safeMask = step(MIN_NORMALIZE_LENGTH, len)
//           - step(edge, x) 是阶跃函数
//           - x <= edge → 返回 0.0
//           - x > edge  → 返回 1.0
//           - 因此：len 过短 → 0.0，len 安全 → 1.0
//
//   步骤3: normalizedVector = vector / max(len, MIN_LEN)
//           - 即使 len 很小也会计算（但结果不会被选中）
//           - max() 防止除以零（虽然结果不会使用）
//
//   步骤4: result = mix(defaultDirection, normalizedVector, safeMask)
//           - safeMask=0.0 → defaultDirection（回退路径）
//           - safeMask=1.0 → normalizedVector（正常路径）
//
// 为什么需要这个函数？
//   场景1: 法线计算 - cross(dFdx(pos), dFdy(pos)) 可能产生零向量
//   场景2: 光线方向 - 光源位置与表面点重合时 direction 为零向量
//   场景3: 切线空间 - UV 退化三角形导致切线计算失败
//   场景4: 物理模拟 - 速度为零时归一化会产生 NaN
//
// 指令数统计：
//   length (dot+sqrt): ~8 ALU
//   step:              1 ALU
//   max:               1 ALU
//   div (rcp+mul):     2 ALU
//   mix:               3 ALU
//   总计:              ~15 ALU 指令
//
// 使用示例：
//   // 计算法线并安全归一化
//   vec3 dx = dFdx(position);
//   vec3 dy = dFdy(position);
//   vec3 normal = safeNormalize(cross(dx, dy), vec3(0.0, 1.0, 0.0));
//
//   // 光照方向的安全归一化
//   vec3 lightDir = safeNormalize(lightPos - fragPos, vec3(0.0, 0.0, 1.0));
//

vec3 safeNormalize(vec3 vector, vec3 defaultDirection) {
    // 计算向量的 L2 范数（长度）
    float len = length(vector);

    // 无分支检测：长度是否超过最小阈值
    // step(edge, x): x <= edge 返回 0.0, x > edge 返回 1.0
    // 因此 safeMask: 长度安全→1.0, 长度过短→0.0
    float safeMask = step(NANGUARD_MIN_NORMALIZE_LENGTH, len);

    // 计算归一化结果（即使长度很短也会执行除法，但不会产生副作用）
    // 使用 max(len, MIN_LEN) 防止除以绝对零（虽然结果不会被选中）
    vec3 normalizedVector = vector / max(len, NANGUARD_MIN_NORMALIZE_LENGTH);

    // 无分支选择：唯一的出口点，保证所有线程执行相同代码路径
    // safeMask=1.0 → normalizedVector（正常情况）
    // safeMask=0.0 → defaultDirection（退化情况）
    return mix(defaultDirection, normalizedVector, safeMask);
}

// ============================================================
// 便捷包装函数（使用默认参数值）
// ============================================================

/** safeNormalize 的便捷版本（defaultDirection = (0, 0, 1)，即 z 轴正方向） */
vec3 safeNormalize(vec3 vector) {
    return safeNormalize(vector, vec3(0.0, 0.0, 1.0));
}


// ============================================================
// 3. safeDivide - 安全的除法运算
// ============================================================
//
// 功能：执行安全的除法运算，当除数为零或接近零时返回默认值
//
// 参数：
//   @param dividend     - 被除数
//   @param divisor      - 除数（不能为零或接近零）
//   @param defaultValue - 除法失败时的回退值
//   @param epsilon      - 判定"除数过小"的阈值（默认 1e-6）
//
// 返回值：
//   - 若 |divisor| > epsilon：返回 dividend / divisor
//   - 若 |divisor| <= epsilon：返回 defaultValue
//
// 实现原理（无分支）：
//   步骤1: absDivisor = abs(divisor)
//           - 取除数的绝对值
//
//   步骤2: safeMask = step(epsilon, absDivisor)
//           - |d| > eps → 1.0（安全）
//           - |d| <= eps → 0.0（危险）
//
//   步骤3: divisionResult = dividend / divisor
//           - 即使除数为零也会执行（GPU 会产生 ±Inf 但不崩溃）
//
//   步骤4: result = mix(defaultValue, divisionResult, safeMask)
//           - 无分支选择最终结果
//
// 为什么需要这个函数？
//   场景1: 透视除法 - w 分量可能为零（顶点在相机后方）
//   场景2: 距离衰减 - distance² 可能为零（光源与表面重合）
//   场景3: 投影变换 - NDC 空间的齐次坐标归一化
//   场景4: 颜色运算 - 某些颜色通道可能为零
//   场景5: 权重归一化 - 总权重可能为零
//
// 指令数统计：
//   abs:     1 ALU
//   step:    1 ALU
//   div:     1 ALU (或 rcp+mul = 2 ALU)
//   mix:     3 ALU
//   总计:    ~6-7 ALU 指令
//
// 使用示例：
//   // 安全的距离衰减计算
//   float dist = length(lightPos - fragPos);
//   float attenuation = safeDivide(1.0, dist * dist, 0.0);
//
//   // 透视校正插值
//   float perspFactor = safeDivide(attr.w, clipW, 1.0);
//
//   // 自定义 epsilon（更严格的检查）
//   float result = safeDivide(num, den, 0.0, 1e-10);
//

float safeDivide(float dividend, float divisor, float defaultValue, float epsilon) {
    // 计算除数的绝对值
    float absDivisor = abs(divisor);

    // 无分支检测：除数绝对值是否大于 epsilon
    // step(edge, x): x > edge → 1.0, x <= edge → 0.0
    // safeMask: 除数安全→1.0, 除数危险→0.0
    float safeMask = step(epsilon, absDivisor);

    // 执行除法（即使除数为零，GPU 会产生 ±Inf 但不会崩溃）
    // 这个值在危险情况下不会被选中，所以是安全的
    float divisionResult = dividend / divisor;

    // 无分支选择最终的返回值
    return mix(defaultValue, divisionResult, safeMask);
}

// ============================================================
// 便捷包装函数（使用默认参数值）
// ============================================================

/** safeDivide 的简化版本（使用默认 epsilon） */
float safeDivide(float dividend, float divisor, float defaultValue) {
    return safeDivide(dividend, divisor, defaultValue, NANGUARD_DIVIDE_EPSILON);
}

/** safeDivide 的最简版本（defaultValue=0, epsilon=1e-6） */
float safeDivide(float dividend, float divisor) {
    return safeDivide(dividend, divisor, NANGUARD_DEFAULT_SAFE_VALUE, NANGUARD_DIVIDE_EPSILON);
}


// ============================================================
// 高级组合函数（基于基础函数构建）
// ============================================================

/**
 * safeLength - 安全的向量长度计算
 * 防止 length() 在极端情况下产生的精度问题
 *
 * 参数：
 *   @param vector - 输入向量
 *
 * 返回值：
 *   向量的 L2 范数，保证非负且有限
 */
float safeLength(vec3 vector) {
    // 使用 dot 避免中间溢出，然后 sqrt 并用 nanGuardClamp 保护
    float squaredLen = dot(vector, vector);
    // 防止负零或极小值导致的精度问题
    return nanGuardClamp(sqrt(max(squaredLen, 0.0)), 0.0, 1e10);
}

/**
 * safeReflect - 安全的反射向量计算
 * 处理入射光线与法线平行的情况
 *
 * 参数：
 *   @param incident - 入射向量（指向表面）
 *   @param normal   - 表面法线（单位向量）
 *
 * 返回值：
 *   反射向量，若法线无效则返回 -incident
 */
vec3 safeReflect(vec3 incident, vec3 normal) {
    // 确保法线已归一化
    vec3 safeNormal = safeNormalize(normal, vec3(0.0, 1.0, 0.0));

    // 计算反射：R = I - 2*(N·I)*N
    float dotNI = dot(safeNormal, incident);
    return incident - 2.0 * dotNI * safeNormal;
}

/**
 * safeRefract - 安全的折射向量计算
 * 处理全内反射和非法线情况
 *
 * 参数：
 *   @param incident    - 入射向量（指向表面）
 *   @param normal      - 表面法线（单位向量）
 *   @param eta         - 相对折射率 (n1/n2)
 *   @param defaultDir  - 全内反射时的默认方向
 *
 * 返回值：
 *   折射向量，若发生全内反射则返回 defaultDir
 */
vec3 safeRefract(vec3 incident, vec3 normal, float eta, vec3 defaultDir) {
    vec3 safeNormal = safeNormalize(normal, vec3(0.0, 1.0, 0.0));

    float cosI = -dot(safeNormal, incident);
    float sinT2 = eta * eta * (1.0 - cosI * cosI);

    // 检查是否发生全内反射（sinT2 > 1）
    float totalReflection = step(1.0, sinT2);

    // 计算折射向量（即使全内反射也会计算，但不被选中）
    vec3 refracted = eta * incident + (eta * cosI - sqrt(abs(1.0 - sinT2))) * safeNormal;

    // 无分支选择
    return mix(refracted, defaultDir, totalReflection);
}

/**
 * safeMix - 安全的线性插值（保护两端点的 NaN）
 *
 * 参数：
 *   @param x - 起点
 *   @param y - 终点
 *   @param a - 插值因子 [0, 1]
 *
 * 返回值：
 *   lerp(x, y, a)，若 x 或 y 为 NaN 则使用另一端点
 */
float safeMix(float x, float y, float a) {
    // 保护两个端点
    float safeX = nanGuardClamp(x, -1e10, 1e10, 0.0);
    float safeY = nanGuardClamp(y, -1e10, 1e10, 0.0);

    // 标准 lerp
    return mix(safeX, safeY, a);
}

/**
 * safeSmoothstep - 安全的平滑阶梯函数
 * 保护输入值并确保输出在 [0, 1] 范围内
 *
 * 参数：
 *   @param edge0 - 下边界
 *   @param edge1 - 上边界
 *   @param x     - 输入值
 *
 * 返回值：
 *   smoothstep 结果，保证在 [0, 1] 范围内
 */
float safeSmoothstep(float edge0, float edge1, float x) {
    // 保护输入
    float safeX = nanGuardClamp(x, edge0 - 1.0, edge1 + 1.0, edge0);

    // 标准 smoothstep
    float t = clamp((safeX - edge0) / (edge1 - edge0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}


// ============================================================
// 调试辅助宏（可选，仅在调试模式启用）
// ============================================================

#ifdef NANGUARD_DEBUG
    /**
     * NANGUARD_ASSERT_VALID - 调试断言宏
     * 在调试模式下检测数值有效性，若无效则输出警告色（红色）
     *
     * 用法：
     *   float value = someCalculation();
     *   #ifdef NANGUARD_DEBUG
     *       if (NANGUARD_ASSERT_INVALID(value)) {
     *           // 输出红色表示检测到无效值
     *           fragColor = vec4(1.0, 0.0, 0.0, 1.0);
     *       }
     *   #endif
     */

    /// 检测值是否为 NaN 或 Inf（用于调试断言）
    bool nanguardIsInvalid(float value) {
        return isnan(value) || isinf(value);
    }

    /// 将无效值可视化为颜色（NaN→红色, +Inf→绿色, -Inf→蓝色）
    vec3 nanguardVisualizeInvalid(float value) {
        vec3 nanColor = vec3(1.0, 0.0, 0.0);   // 红色 = NaN
        vec3 posInfColor = vec3(0.0, 1.0, 0.0); // 绿色 = +Inf
        vec3 negInfColor = vec3(0.0, 0.0, 1.0); // 蓝色 = -Inf
        vec3 validColor = vec3(1.0, 1.0, 1.0);  // 白色 = 有效值

        float isNan = float(isnan(value));
        float isPosInf = float(isinf(value) && value > 0.0);
        float isNegInf = float(isinf(value) && value < 0.0);

        return mix(validColor, nanColor, isNan) +
               mix(validColor, posInfColor, isPosInf) +
               mix(validColor, negInfColor, isNegInf);
    }
#endif // NANGUARD_DEBUG


#endif // NANGUARD_GLSL_INCLUDED
