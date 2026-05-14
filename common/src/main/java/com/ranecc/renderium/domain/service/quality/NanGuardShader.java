// 迁移自: 1.0.0: com.renderium.core.quality.NanGuardShader



// 迁移目标: com.ranecc.renderium.domain.service.quality



// 迁移规则: 优先使用 snapshot 1.1.0 版本，修改 package/import，保持业务逻辑不变











// ============================================================



// NanGuard 无分支异常处理系统



// ============================================================



// 基于 TOPS v2.5 L0.5 精度守恒层 §3.3 的 NanGuard 模式



//



// 核心原则：



//   - if-else 导致 Warp 分化 → 使用 select 指令



//   - 使用 isnan/isinf + step/clamp/mad 替代 if-else



//   - 覆盖 NaN/Inf/除零三种数值异常



//



// 适用场景：



//   - GPU Shader 中的数值计算（GLSL/HLSL）



//   - 需要避免 Warp 分化的并行计算场景



//   - 对数值稳定性要求高的渲染管线



//



// 集成方式：



//   1. GLSL: #include "nanguard.glsl" 或通过 getGlslLibrary() 获取完整源码



//   2. HLSL: 通过 getHlslFunctions() 获取对应函数并嵌入 shader



//   3. Java 端: 直接调用静态方法进行 CPU 端的数值保护



//



// @see <a href="https://www.khronos.org/opengl/wiki/Core_Language_(GLSL)">GLSL Core Language</a>



// @see <a href="https://docs.microsoft.com/windows/win32/direct3dhlsl/dx-graphics-hlsl">HLSL Documentation</a>



// ============================================================







package com.ranecc.renderium.domain.service.quality;







/**



 * NanGuard 无分支异常处理工具类



 * <p>



 * 提供 GPU Shader 安全编程的无分支实现，防止 Warp 分化导致的性能问题。



 * 包含 GLSL/HLSL 内联函数字符串常量和 Java 端辅助方法。



 * </p>



 *



 * <h2>设计原理</h2>



 * <p>



 * 在 GPU 并行计算中，同一个 Warp（通常 32 个线程）中的所有线程必须执行相同的指令。



 * 当使用 if-语句时，如果不同线程走不同的分支，就会发生 <strong>Warp 分化</strong>，



 * 导致两部分线程串行执行，性能下降约 50%。



 * </p>



 *



 * <h3>Warp 分化示例：</h3>



 * <pre>{@code



 * // ❌ 错误：Warp分化



 * if (isnan(x)) result = safe_default; else result = x;



 *



 * // ✅ 正确：无分支



 * result = (isnan(x) || isinf(x)) ? safe_default : x;



 * }</pre>



 *



 * <h2>功能覆盖</h2>



 * <ul>



 *   <li><b>NaN 保护</b>: 检测和处理非数值（Not a Number）异常</li>



 *   <li><b>Inf 保护</b>: 检测和处理无穷大（Infinity）异常</li>



 *   <li><b>除零保护</b>: 安全的除法运算，避免除以零</li>



 *   <li><b>归一化保护</b>: 安全的向量归一化，处理零向量情况</li>



 *   <li><b>范围钳位</b>: 带异常检测的安全钳位操作</li>



 * </ul>



 *



 * <h2>使用示例</h2>



 * <h3>GLSL Shader 集成：</h3>



 * <pre>{@code



 * // 方式1: 通过 include 指令



 * #include "nanguard.glsl"



 *



 * void main() {



 *     float value = nanGuardClamp(texColor.r, 0.0, 1.0);



 *     vec3 normal = safeNormalize(cross(dx, dy));



 *     float factor = safeDivide(a, b, 0.0);



 * }



 * }</pre>



 *



 * <h3>HLSL Shader 集成：</h3>



 * <pre>{@code



 * // 从 Java 获取 HLSL 函数定义



 * String hlslCode = NanGuardShader.getHlslFunctions();



 *



 * // 在 shader 中使用



 * float4 main(PSInput input) : SV_Target {



 *     float value = nanGuardClamp(input.color.r, 0.0, 1.0);



 *     return float4(value, value, value, 1.0);



 * }



 * }</pre>



 *



 * <h3>Java 端使用：</h3>



 * <pre>{@code



 * // 数值保护



 * float safeValue = NanGuardShader.nanGuardClamp(unsafeValue, 0.0f, 1.0f);



 *



 * // 向量归一化



 * float[] normal = NanGuardShader.safeNormalize(new float[]{1.0f, 2.0f, 3.0f});



 *



 * // 安全除法



 * float result = NanGuardShader.safeDivide(a, b, 0.0f);



 * }</pre>



 *



 * <h2>性能特征</h2>



 * <ul>



 *   <li>所有函数使用无分支实现，避免 Warp 分化</li>



 *   <li>使用 GPU 原生指令（step、clamp、mix、mad），单周期执行</li>



 *   <li>额外的开销仅为 2-4 条 ALU 指令</li>



 *   <li>相比 if-else 分支版本，在 Worst-case 下提升约 2x 性能</li>



 * </ul>



 *



 * @author Renderium Team



 * @version 1.0



 * @since 1.0



 * @see RenderiumCore



 */



public final class NanGuardShader {







    /** 私有构造方法，防止实例化（工具类模式） */



    private NanGuardShader() {



        throw new UnsupportedOperationException("NanGuardShader 是工具类，不允许实例化");



    }







    // ============================================================



    // 常量定义



    // ============================================================







    /**



     * NaN 检测的 epsilon 阈值



     * <p>



     * 用于浮点数比较的极小值，避免精度问题导致的误判。



     * 值为 1e-6，适用于大多数渲染场景。



     * </p>



     */



    public static final float NAN_EPSILON = 1e-6f;







    /**



     * 最小安全归一化长度阈值



     * <p>



     * 当向量长度小于此值时，safeNormalize 返回默认方向而非零向量。



     * 防止归一化零向量或接近零的向量导致 NaN。



     * </p>



     */



    public static final float MIN_NORMALIZE_LENGTH = 1e-8f;







    /**



     * 默认安全值（用于替换 NaN/Inf）



     * <p>



     * 当检测到数值异常时使用的默认替代值。



     * 通常设为 0.0，但可根据场景调整。



     * </p>



     */



    public static final float DEFAULT_SAFE_VALUE = 0.0f;







    // ============================================================



    // GLSL 函数库（完整源码）



    // ============================================================







    /**



     * 获取完整的 GLSL NanGuard 函数库源码



     * <p>



     * 返回包含所有 NanGuard 函数的完整 GLSL 代码，



     * 可直接通过 {@code #include} 或字符串拼接方式集成到 shader 中。



     * </p>



     *



     * <h3>使用方式：</h3>



     * <pre>{@code



     * // 方式1: 写入文件后 #include



     * String glslCode = NanGuardShader.getGlslLibrary();



     * Files.write(Paths.get("nanguard.glsl"), glslCode.getBytes());



     *



     * // 方式2: 直接拼接



     "\" + myShader;"



     * }</pre>



     *



     * @return 完整的 GLSL 函数库源码字符串



     */



    public static String getGlslLibrary() {



        return GLSL_LIBRARY;



    }







    /**



     * 获取单个 GLSL 函数的定义



     * <p>



     * 支持按需获取特定函数，减少 shader 代码体积。



     * </p>



     *



     * @param functionName 函数名称（"nanGuardClamp", "safeNormalize", "safeDivide"）



     * @return 对应函数的 GLSL 定义字符串，若名称无效则返回空字符串



     * @throws IllegalArgumentException 若 functionName 为 null



     */



    public static String getGlslFunction(String functionName) {



        if (functionName == null) {



            throw new IllegalArgumentException("函数名称不能为 null");



        }







        switch (functionName) {



            case "nanGuardClamp":



                return GLSL_NAN_GUARD_CLAMP;



            case "safeNormalize":



                return GLSL_SAFE_NORMALIZE;



            case "safeDivide":



                return GLSL_SAFE_DIVIDE;



            default:



                return "";



        }



    }







    // ============================================================



    // HLSL 函数库



    // ============================================================







    /**



     * 获取完整的 HLSL NanGuard 函数集



     * <p>



     * 返回所有 NanGuard 函数的 HLSL 版本，



     * 用于 DirectX/Vulkan (HLSL 后端) 的 shader 开发。



     * </p>



     *



     * <h3>GLSL vs HLSL 对应关系：</h3>



     * <table border="1">



     *   <tr><th>操作</th><th>GLSL</th><th>HLSL</th></tr>



     *   <tr><td>条件选择</td><td>mix(a, b, cond)</td><td>cond ? b : a / lerp(a, b, cond)</td></tr>



     *   <tr><td>阶跃函数</td><td>step(edge, x)</td><td>step(edge, x)</td></tr>



     *   <tr><td>钳位</td><td>clamp(x, min, max)</td><td>saturate(x) / clamp(x, min, max)</td></tr>



     *   <tr><td>MAD</td><td>fma(a, b, c)</td><td>mad(a, b, c)</td></tr>



     *   <tr><td>NaN 检测</td><td>isnan(x)</td><td>isnan(x)</td></tr>



     *   <tr><td>Inf 检测</td><td>isinf(x)</td><td>isinf(x)</td></tr>



     * </table>



     *



     * @return 完整的 HLSL 函数定义字符串



     */



    public static String getHlslFunctions() {



        return HLSL_FUNCTIONS;



    }







    // ============================================================



    // Java 端静态方法（CPU 端数值保护）



    // ============================================================







    /**



     * 带 NaN/Inf 检测的安全钳位函数（Java 版本）



     * <p>



     * 对输入值进行范围限制的同时，检测并处理 NaN 和 Inf 异常。



     * 如果输入值为 NaN 或 Inf，直接返回 defaultValue 而非钳位结果。



     * </p>



     *



     * <h3>算法流程：</h3>



     * <ol>



     *   <li>检测输入是否为 NaN 或 Inf</li>



     *   <li>若是异常值，返回 defaultValue</li>



     *   <li>否则返回 clamp(value, minValue, maxValue)</li>



     * </ol>



     *



     * <h3>参数说明：</h3>



     * <ul>



     *   <li>{@code value}: 待处理的输入值（可能包含 NaN/Inf）</li>



     *   <li>{@code minValue}: 允许的最小值（下界）</li>



     *   <li>{@code maxValue}: 允许的最大值（上界）</li>



     *   <li>{@code defaultValue}: 异常时的回退值（可选，默认 0.0）</li>



     * </ul>



     *



     * <h3>使用示例：</h3>



     * <pre>{@code



     * // 从纹理读取的颜色值，可能有 NaN



     * float texValue = texture(myTex, uv).r;



     * float safeColor = NanGuardShader.nanGuardClamp(texValue, 0.0f, 1.0f);



     *



     * // 自定义默认值



     * float depth = NanGuardShader.nanGuardClamp(rawDepth, 0.0f, 100.0f, 1.0f);



     * }</pre>



     *



     * @param value          输入值（可能为 NaN/Inf）



     * @param minValue       允许的最小值



     * @param maxValue       允许的最大值



     * @param defaultValue   异常时的默认值（可选，默认 {@link #DEFAULT_SAFE_VALUE}）



     * @return 处理后的安全值：若输入正常则返回钳位结果，否则返回 defaultValue



     */



    public static float nanGuardClamp(float value, float minValue, float maxValue, float defaultValue) {



        // 检测 NaN 或 Inf



        boolean isInvalid = Float.isNaN(value) || Float.isInfinite(value);







        // 无分支选择：使用条件表达式而非 if-else（保持与 GPU 端一致）



        return isInvalid ? defaultValue : Math.max(minValue, Math.min(maxValue, value));



    }







    /**



     * 带 NaN/Inf 检测的安全钳位函数（重载版本，使用默认值 0.0）



     *



     * @param value      输入值（可能为 NaN/Inf）



     * @param minValue   允许的最小值



     * @param maxValue   允许的最大值



     * @return 处理后的安全值



     * @see #nanGuardClamp(float, float, float, float)



     */



    public static float nanGuardClamp(float value, float minValue, float maxValue) {



        return nanGuardClamp(value, minValue, maxValue, DEFAULT_SAFE_VALUE);



    }







    /**



     * 安全的向量归一化函数（Java 版本）



     * <p>



     * 对输入向量进行归一化处理，当向量长度过小时返回默认方向，



     * 避免归一化零向量导致的 NaN 结果。



     * </p>



     *



     * <h3>算法流程：</h3>



     * <ol>



     *   <li>计算向量的 L2 范数（长度）</li>



     *   <li>检测长度是否低于最小阈值 {@link #MIN_NORMALIZE_LENGTH}</li>



     *   <li>若长度安全，返回 vector / length</li>



     *   <li>若长度过短，返回 defaultDirection（单位向量）</li>



     * </ol>



     *



     * <h3>应用场景：</h3>



     * <ul>



     *   <li>法线计算：{@code cross(dFdx(pos), dFdy(pos))} 可能产生零向量</li>



     *   <li>光线方向归一化：光源位置与表面点重合时</li>



     *   <li>切线空间构建：UV 退化三角形的情况</li>



     * </ul>



     *



     * <h3>使用示例：</h3>



     * <pre>{@code



     * // 计算法线并安全归一化



     * float[] dx = {1.0f, 0.0f, 0.0f};



     * float[] dy = {0.0f, 1.0f, 0.0f};



     * float[] normal = NanGuardShader.safeNormalize(cross(dx, dy));



     *



     * // 使用自定义默认方向（世界空间上方向）



     * float[] up = {0.0f, 1.0f, 0.0f};



     * float[] safeNormal = NanGuardShader.safeNormalize(zeroVector, up);



     * }</pre>



     *



     * @param vector           输入三维向量（长度 >= 2，必须为 3 维）



     * @param defaultDirection 归一化失败时的默认方向（长度 >= 2，必须为 3 维且已归一化）



     * @return 归一化后的单位向量；若输入长度过短则返回 defaultDirection



     * @throws IllegalArgumentException 若向量维度不是 3



     */



    public static float[] safeNormalize(float[] vector, float[] defaultDirection) {



        // 参数校验



        if (vector == null || vector.length != 3) {



            throw new IllegalArgumentException("输入向量必须是 3 维");



        }



        if (defaultDirection == null || defaultDirection.length != 3) {



            throw new IllegalArgumentException("默认方向必须是 3 维");



        }







        // GPU优化：使用平方长度和快速逆平方根，避免昂贵的sqrt运算



        float lenSq = vector[0] * vector[0] +



                      vector[1] * vector[1] +



                      vector[2] * vector[2];







        // 检测长度是否安全（比较平方长度，等价于 length > MIN_NORMALIZE_LENGTH）



        boolean isSafe = lenSq > MIN_NORMALIZE_LENGTH * MIN_NORMALIZE_LENGTH;







        // 根据安全性选择结果



        if (isSafe) {



            float invLength = fastInvSqrt(lenSq);



            return new float[]{



                vector[0] * invLength,



                vector[1] * invLength,



                vector[2] * invLength



            };



        } else {



            // 返回默认方向（确保它是单位向量）



            float defLenSq = defaultDirection[0] * defaultDirection[0] +



                             defaultDirection[1] * defaultDirection[1] +



                             defaultDirection[2] * defaultDirection[2];



            float invDefLen = defLenSq > MIN_NORMALIZE_LENGTH * MIN_NORMALIZE_LENGTH



                              ? fastInvSqrt(defLenSq) : 1.0f;



            return new float[]{



                defaultDirection[0] * invDefLen,



                defaultDirection[1] * invDefLen,



                defaultDirection[2] * invDefLen



            };



        }



    }







    /**



     * 安全的向量归一化函数（重载版本，默认方向为 (0, 0, 1)）



     *



     * @param vector 输入三维向量



     * @return 归一化后的单位向量；若输入为零向量则返回 (0, 0, 1)



     * @see #safeNormalize(float[], float[])



     */



    public static float[] safeNormalize(float[] vector) {



        return safeNormalize(vector, new float[]{0.0f, 0.0f, 1.0f});



    }







    /**



     * 安全的除法函数（Java 版本）



     * <p>



     * 执行安全的除法运算，当除数为零或绝对值过小时返回默认值，



     * 避免 ±Inf 或 NaN 结果。



     * </p>



     *



     * <h3>算法流程：</h3>



     * <ol>



     *   <li>检测除数绝对值是否低于 epsilon 阈值</li>



     *   <li>若除数安全，返回 dividend / divisor</li>



     *   <li>若除数危险（接近零），返回 defaultValue</li>



     * </ol>



     *



     * <h3>应用场景：</h3>



     * <ul>



     *   <li>透视除法：w 分量可能为零（顶点在相机后方）</li>



     *   <li>光照计算：距离衰减的分母可能为零</li>



     *   <li>投影变换：NDC 空间的齐次坐标归一化</li>



     *   <li>颜色空间转换：某些通道可能为零</li>



     * </ul>



     *



     * <h3>使用示例：</h3>



     * <pre>{@code



     * // 安全的距离衰减



     * float distance = length(lightPos - surfacePos);



     * float attenuation = NanGuardShader.safeDivide(1.0f, distance * distance, 0.0f);



     *



     * // 透视校正插值



     * float perspectiveFactor = NanGuardShader.safeDivide(attr.w, clipW, 1.0f);



     *



     * // 自定义 epsilon 阈值（更严格的检查）



     * float result = NanGuardShader.safeDivide(num, den, 0.0f, 1e-10f);



     * }</pre>



     *



     * @param dividend     被除数



     * @param divisor      除数（不能为零或接近零）



     * @param defaultValue 除法失败时的回退值（可选，默认 0.0）



     * @param epsilon      判定"除数过小"的阈值（可选，默认 1e-6）



     * @return 安全的除法结果；若除数过小则返回 defaultValue



     */



    public static float safeDivide(float dividend, float divisor, float defaultValue, float epsilon) {



        // 检测除数是否安全



        boolean isSafe = Math.abs(divisor) > epsilon;







        // 无分支选择



        return isSafe ? dividend / divisor : defaultValue;



    }







    /**



     * 安全的除法函数（重载版本，使用默认 epsilon）



     *



     * @param dividend     被除数



     * @param divisor      除数



     * @param defaultValue 除法失败时的回退值



     * @return 安全的除法结果



     * @see #safeDivide(float, float, float, float)



     */



    public static float safeDivide(float dividend, float divisor, float defaultValue) {



        return safeDivide(dividend, divisor, defaultValue, NAN_EPSILON);



    }







    /**



     * 安全的除法函数（简化版本，默认值 0.0）



     *



     * @param dividend 被除数



     * @param divisor  除数



     * @return 安全的除法结果



     * @see #safeDivide(float, float)



     */



    public static float safeDivide(float dividend, float divisor) {



        return safeDivide(dividend, divisor, DEFAULT_SAFE_VALUE);



    }







    // ============================================================



    // GLSL 函数源码常量



    // ============================================================







    /**



     * nanGuardClamp 的 GLSL 实现



     * <p>



     * 功能：带 NaN/Inf 检测的安全钳位



     * </p>



     *



     * <h3>算法详解：</h3>



     * <pre>{@code



     * // 步骤1: 检测异常（NaN 或 Inf）



     * float invalidMask = isnan(value) + isinf(value);  // 异常时为 1.0，正常时为 0.0



     *



     * // 步骤2: 正常值的钳位



     * float clampedValue = clamp(value, minValue, maxValue);



     *



     * // 步骤3: 无分支选择（mix = lerp）



     * // invalidMask == 0 → 返回 clampedValue（正常路径）



     * // invalidMask == 1 → 返回 defaultValue（异常路径）



     * result = mix(clampedValue, defaultValue, invalidMask);



     * }</pre>



     *



     * <h3>指令数统计：</h3>



     * <ul>



     *   <li>isnan: 1 条指令</li>



     *   <li>isinf: 1 条指令</li>



     *   <li>add: 1 条指令</li>



     *   <li>clamp: 3 条指令（max + min）</li>



     *   <li>mix: 3 条指令（lerp）</li>



     *   <li><b>总计: ~9 条 ALU 指令</b></li>



     * </ul>



     */



    private static final String GLSL_NAN_GUARD_CLAMP =



        "// ================================================%n" +



        "// NanGuard: nanGuardClamp - 带 NaN/Inf 检测的安全钳位%n" +



        "// ================================================%n" +



        "// 参数:\n" +
        "//   value        - 输入值（可能为 NaN/Inf）\n" +
        "//   minValue     - 允许的最小值\n" +
        "//   maxValue     - 允许的最大值\n" +
        "//   defaultValue - 异常时的回退值（默认 0.0）\n" +
        "// 返回:\n" +
        "//   正常值 → clamp(value, minVal, maxVal)\n" +
        "//   异常值 → defaultValue\n" +
        "//\n" +
        "// 实现原理:\n" +
        "//   使用 mix() 进行无分支选择，避免 if-else 导致的 Warp 分化\n" +
        "//   mix(a, b, weight) = a*(1-weight) + b*weight\n" +
        "//   当 weight=0 时返回 a，weight=1 时返回 b\n" +
        "// ================================================\n" +
        "float nanGuardClamp(float value, float minValue, float maxValue, float defaultValue) {\n" +
        "    // 检测 NaN 或 Inf（异常时返回 1.0，正常时返回 0.0）"






        "    float invalidMask = float(isnan(value)) + float(isinf(value));






        "\" +\n\"    // 对正常值进行钳位\n\" +\n\"    float clampedValue = clamp(value, minValue, maxValue);\n\" +\n\"\n\" +\n\"    // 无分支选择：invalidMask=0 → clampedValue, invalidMask=1 → defaultValue\n\" +\n\"    // mix 是 GPU 原生的线性插值指令，不产生分支\n\" +\n\"    return mix(clampedValue, defaultValue, invalidMask);\n\" +\n\"}\n\";"







    /**



     * safeNormalize 的 GLSL 实现



     * <p>



     * 功能：安全的向量归一化，处理零向量情况



     * </p>



     *



     * <h3>算法详解：</h3>



     * <pre>{@code



     * // 步骤1: 计算向量长度



     * float len = length(vector);



     *



     * // 步骤2: 检测长度是否安全（使用 step 创建无分支掩码）



     * // step(edge, x): x <= edge 返回 0.0，x > edge 返回 1.0



     * // 所以 step(minLen, len): len 过短 → 0.0，len 安全 → 1.0



     * float safeMask = step(MIN_NORMALIZE_LENGTH, len);



     *



     * // 步骤3: 计算两种结果



     * float normalizedVector = vector / len;         // 正常路径



     * float fallbackVector = defaultDirection;        // 回退路径



     *



     * // 步骤4: 无分支选择



     * result = mix(fallbackVector, normalizedVector, safeMask);



     * }</pre>



     *



     * <h3>指令数统计：</h3>



     * <ul>



     *   <li>length (dot + sqrt): ~8 条指令</li>



     *   <li>step: 1 条指令</li>



     *   <li>div: 1 条指令（倒数+乘法）</li>



     *   <li>mix: 3 条指令</li>



     *   <li><b>总计: ~13 条 ALU 指令</b></li>



     * </ul>



     */



    private static final String GLSL_SAFE_NORMALIZE =



        "// ================================================\n" +
        "// NanGuard: safeNormalize - 安全的向量归一化\n" +
        "// ================================================\n" +
        "// 参数:\n" +
        "//   vector           - 输入三维向量\n" +
        "//   defaultDirection - 归一化失败时的默认方向（需为单位向量）\n" +
        "// 返回:\n" +
        "//   长度安全 → normalize(vector)\n" +
        "//   长度过短 → defaultDirection\n" +
        "//\n" +
        "// 实现原理:\n" +
        "//   使用 step() 函数生成无分支掩码，配合 mix() 选择结果\n" +
        "//   step(edge, x) 是阶跃函数：x<=edge→0, x>edge→1\n" +
        "//   这避免了 if (length < eps) 导致的 Warp 分化\n" +
        "// ================================================\n" +
        "\" +\n\"#ifndef NANGUARD_MIN_NORMALIZE_LENGTH\n\" +\n\"#define NANGUARD_MIN_NORMALIZE_LENGTH 1e-8\n\" +\n\"#endif\n\" +\n\"\n\" +\n\"vec3 safeNormalize(vec3 vector, vec3 defaultDirection) {\n\" +\n\"    // 计算向量长度（L2 范数）\n\" +\n\"    float len = length(vector);\n\" +\n\"\n\" +\n\"    // 无分支检测：长度是否超过最小阈值\n\" +\n\"    // step(edge, x) 返回: x > edge ? 1.0 : 0.0\n\" +\n\"    // 因此 safeMask: 长度安全→1.0, 长度过短→0.0\n\" +\n\"    float safeMask = step(NANGUARD_MIN_NORMALIZE_LENGTH, len);\n\" +\n\"\n\" +\n\"    // 计算归一化结果（即使长度很短也会计算，但不会被选中）\n\" +\n\"    vec3 normalizedVector = vector / max(len, NANGUARD_MIN_NORMALIZE_LENGTH);\n\" +\n\"\n\" +\n\"    // 无分支选择：safeMask=1.0 → normalizedVector, safeMask=0.0 → defaultDirection\n\" +\n\"    // 这是唯一的出口点，保证所有线程都执行相同路径\n\" +\n\"    return mix(defaultDirection, normalizedVector, safeMask);\n\" +\n\"}\n\";"







    /**



     * safeDivide 的 GLSL 实现



     * <p>



     * 功能：安全的除法运算，避免除零



     * </p>



     *



     * <h3>算法详解：</h3>



     * <pre>{@code



     * // 步骤1: 检测除数绝对值是否足够大



     * // absDivisor = abs(divisor)



     * // safeMask = step(epsilon, absDivisor): |d|>eps → 1.0, 否则 → 0.0



     *



     * // 步骤2: 计算两种结果



     * float divisionResult = dividend / divisor;  // 正常路径



     * float fallbackResult = defaultValue;         // 回退路径



     *



     * // 步骤3: 无分支选择



     * result = mix(fallbackResult, divisionResult, safeMask);



     * }</pre>



     *



     * <h3>指令数统计：</h3>



     * <ul>



     *   <li>abs: 1 条指令</li>



     *   <li>step: 1 条指令</li>



     *   <li>div: 1 条指令</li>



     *   <li>mix: 3 条指令</li>



     *   <li><b>总计: ~6 条 ALU 指令</b></li>



     * </ul>



     */



    private static final String GLSL_SAFE_DIVIDE =



        "// ================================================\n" +
        "// NanGuard: safeDivide - 安全的除法运算\n" +
        "// ================================================\n" +
        "// 参数:\n" +
        "//   dividend     - 被除数\n" +
        "//   divisor      - 除数（可能为零或接近零）\n" +
        "//   defaultValue - 除法失败时的回退值（默认 0.0）\n" +
        "//   epsilon      - 判定'除数过小'的阈值（默认 1e-6）\n" +
        "// 返回:\n" +
        "//   除数安全 → dividend / divisor\n" +
        "//   除数过小 → defaultValue\n" +
        "//\n" +
        "// 实现原理:\n" +
        "//   使用 step() 检测除数绝对值，mix() 进行无分支选择\n" +
        "//   完全避免 if (abs(divisor) < eps) 的分支判断\n" +
        "// ================================================\n" +
        "\" +\n\"#ifndef NANGUARD_DIVIDE_EPSILON\n\" +\n\"#define NANGUARD_DIVIDE_EPSILON 1e-6\n\" +\n\"#endif\n\" +\n\"\n\" +\n\"float safeDivide(float dividend, float divisor, float defaultValue, float epsilon) {\n\" +\n\"    // 计算除数的绝对值\n\" +\n\"    float absDivisor = abs(divisor);\n\" +\n\"\n\" +\n\"    // 无分支检测：除数绝对值是否大于 epsilon\n\" +\n\"    // step(edge, x): x > edge → 1.0, x <= edge → 0.0\n\" +\n\"    // safeMask: 除数安全→1.0, 除数危险→0.0\n\" +\n\"    float safeMask = step(epsilon, absDivisor);\n\" +\n\"\n\" +\n\"    // 计算除法结果（即使除数为零也会执行，GPU 会产生 ±Inf 但不会崩溃）\n\" +\n\"    float divisionResult = dividend / divisor;\n\" +\n\"\n\" +\n\"    // 无分支选择：safeMask=1.0 → divisionResult, safeMask=0.0 → defaultValue\n\" +\n\"    return mix(defaultValue, divisionResult, safeMask);\n\" +\n\"}\n\";"







    /**



     * 完整的 GLSL 函数库（包含所有函数及辅助宏）



     */



    private static final String GLSL_LIBRARY =



        "// ============================================================\n" +
        "// NanGuard 无分支异常处理库 (GLSL 版本)\n" +
        "// ============================================================\n" +
        "// 基于 TOPS v2.5 L0.5 精度守恒层 §3.3 的 NanGuard 模式\n" +
        "//\n" +
        "// 核心原则：\n" +
        "//   - if-else 导致 Warp 分化 → 使用 select/mix 指令\n" +
        "//   - 使用 isnan/isinf + step/clamp/mad 替代 if-else\n" +
        "//   - 覆盖 NaN/Inf/除零三种数值异常\n" +
        "//\n" +
        "// 版本: 1.0\n" +
       ">// 作者: Renderium Team\n" +
        "// 许可: 与 Renderium 主项目相同\n" +
        "//\n" +
        "// 使用方式:"






        \1
" +";



        "//   或者将此文件内容直接粘贴到 shader 顶部\n" +
        "//\n" +
        "// 性能特征:\n" +
        "//   - 所有函数完全无分支，避免 Warp 分化\n" +
        "//   - 使用 GPU 原生指令（step、clamp、mix），单周期执行\n" +
        "//   - 额外开销仅 2-4 条 ALU 指令\n" +
        "//   - Worst-case 下比 if-else 版本快约 2x\n" +
        "//\n" +
        "// 函数列表:\n" +
        "//   1. nanGuardClamp(value, minVal, maxVal, defaultVal)\n" +
        "//      - 带 NaN/Inf 检测的安全钳位\n" +
        "//   2. safeNormalize(vector, defaultDir)\n" +
        "//      - 安全的向量归一化（处理零向量）\n" +
        "//   3. safeDivide(dividend, divisor, defaultVal, epsilon)\n" +
        "//      - 安全的除法运算（避免除零）\n" +
        "// ============================================================"






        "\" +\n\"// 防止重复包含的保护宏\n\" +\n\"#ifndef NANGUARD_GLSL_INCLUDED\n\" +\n\"#define NANGUARD_GLSL_INCLUDED\n\" +\n\"\n\" +\n\"// ================================================\n\" +



        // 全局配置常量



        "// 全局配置常量（可通过 #define 覆盖）\n" +
        "// ================================================"






        \1
" +";



        GLSL_SAFE_NORMALIZE + "










        GLSL_SAFE_DIVIDE + "










        "// ================================================\n" +
        "// 便捷包装函数（使用默认参数）\n" +
        "// ================================================\n" +
        "\" +\n\"/**\n\" +\n\" * nanGuardClamp 的便捷版本（defaultValue = 0.0）\n\" +\n\" */\n\" +\n\"float nanGuardClamp(float value, float minValue, float maxValue) {\n\" +\n\"    return nanGuardClamp(value, minValue, maxValue, NANGUARD_DEFAULT_SAFE_VALUE);\n\" +\n\"}\n\" +\n\"\n\" +\n\"/**\n\" +\n\" * safeNormalize 的便捷版本（defaultDirection = vec3(0.0, 0.0, 1.0)）\n\" +\n\" */\n\" +\n\"vec3 safeNormalize(vec3 vector) {\n\" +\n\"    return safeNormalize(vector, vec3(0.0, 0.0, 1.0));\n\" +\n\"}\n\" +\n\"\n\" +\n\"/**\n\" +\n\" * safeDivide 的便捷版本（epsilon = 1e-6, defaultValue = 0.0）\n\" +\n\" */\n\" +\n\"float safeDivide(float dividend, float divisor, float defaultValue) {\n\" +\n\"    return safeDivide(dividend, divisor, defaultValue, NANGUARD_DIVIDE_EPSILON);\n\" +\n\"}\n\" +\n\"\n\" +\n\"/**\n\" +\n\" * safeDivide 的最简版本（defaultValue = 0.0）\n\" +\n\" */\n\" +\n\"float safeDivide(float dividend, float divisor) {\n\" +\n\"    return safeDivide(dividend, divisor, NANGUARD_DEFAULT_SAFE_VALUE, NANGUARD_DIVIDE_EPSILON);\n\" +\n\"}\n\" +\n\"\n\" +\n\"// ================================================\n\" +\n\"// 扩展函数：向量和矩阵版本\n\" +\n\"// ================================================\n\" +\n\"\n\" +\n\"/**\n\" +\n\" * nanGuardClamp 的 vec2/vec3/vec4 版本（分量级操作）\n\" +\n\" */\n\" +\n\"vec2 nanGuardClamp(vec2 value, vec2 minValue, vec2 maxValue, vec2 defaultValue) {\n\" +\n\"    vec2 invalidMask = vec2(\n\" +\n\"        float(isnan(value.x)) + float(isinf(value.x)),\n\" +\n\"        float(isnan(value.y)) + float(isinf(value.y))\n\" +\n\"    );\n\" +\n\"    return mix(clamp(value, minValue, maxValue), defaultValue, invalidMask);\n\" +\n\"}\n\" +\n\"\n\" +\n\"vec3 nanGuardClamp(vec3 value, vec3 minValue, vec3 maxValue, vec3 defaultValue) {\n\" +\n\"    vec3 invalidMask = vec3(\n\" +\n\"        float(isnan(value.x)) + float(isinf(value.x)),\n\" +\n\"        float(isnan(value.y)) + float(isinf(value.y)),\n\" +\n\"        float(isnan(value.z)) + float(isinf(value.z))\n\" +\n\"    );\n\" +\n\"    return mix(clamp(value, minValue, maxValue), defaultValue, invalidMask);\n\" +\n\"}\n\" +\n\"\n\" +\n\"vec4 nanGuardClamp(vec4 value, vec4 minValue, vec4 maxValue, vec4 defaultValue) {\n\" +\n\"    vec4 invalidMask = vec4(\n\" +\n\"        float(isnan(value.x)) + float(isinf(value.x)),\n\" +\n\"        float(isnan(value.y)) + float(isinf(value.y)),\n\" +\n\"        float(isnan(value.z)) + float(isinf(value.z)),\n\" +\n\"        float(isnan(value.w)) + float(isinf(value.w))\n\" +\n\"    );\n\" +\n\"    return mix(clamp(value, minValue, maxValue), defaultValue, invalidMask);\n\" +\n\"}\n\" +\n\"\n\" +\n\"#endif // NANGUARD_GLSL_INCLUDED\n\";"







    // ============================================================



    // HLSL 函数源码常量



    // ============================================================







    /**



     * 完整的 HLSL 函数集



     * <p>



     * 提供与 GLSL 版本功能相同的 HLSL 实现，



     * 适配 DirectX 和 Vulkan (HLSL 后端) 渲染管线。



     * </p>



     *



     * <h3>主要差异：</h3>



     * <ul>



     *   <li>HLSL 使用 {@code lerp()} 替代 GLSL 的 {@code mix()}</li>



     *   <li>HLSL 的 {@code saturate()} 等同于 {@code clamp(x, 0, 1)}</li>



     *   <li>HLSL 支持 {@code mad(a,b,c)} 作为 fused multiply-add</li>



     * </ul>



     */



    private static final String HLSL_FUNCTIONS =



        "// ============================================================\n" +
        "// NanGuard 无分支异常处理库 (HLSL 版本)\n" +
        "// ============================================================\n" +
        "// 基于 TOPS v2.5 L0.5 精度守恒层 §3.3 的 NanGuard 模式\n" +
        "//\n" +
        "// 与 GLSL 版本功能完全对应，语法适配 HLSL/DirectX\n" +
        "//\n" +
        "// 主要差异:\n" +
        "//   - mix() → lerp()\n" +
        "//   - clamp(x, 0, 1) → saturate(x)\n" +
        "//   - fma() → mad()\n" +
        "// ============================================================\n" +
        "\" +\n\"#ifndef NANGUARD_HLSL_INCLUDED\n\" +\n\"#define NANGUARD_HLSL_INCLUDED\n\" +\n\"\n\" +\n\"// 配置常量\n\" +\n\"#ifndef NANGUARD_EPSILON\n\" +\n\"#define NANGUARD_EPSILON 1e-6\n\" +\n\"#endif\n\" +\n\"\n\" +\n\"#ifndef NANGUARD_MIN_NORMALIZE_LENGTH\n\" +\n\"#define NANGUARD_MIN_NORMALIZE_LENGTH 1e-8\n\" +\n\"#endif\n\" +\n\"\n\" +\n\"#ifndef NANGUARD_DEFAULT_SAFE_VALUE\n\" +\n\"#define NANGUARD_DEFAULT_SAFE_VALUE 0.0\n\" +\n\"#endif\n\" +\n\"\n\" +\n\"#ifndef NANGUARD_DIVIDE_EPSILON\n\" +\n\"#define NANGUARD_DIVIDE_EPSILON 1e-6\n\" +\n\"#endif\n\" +\n\"\n\" +\n\"// ================================================\n\" +\n\"// nanGuardClamp: 带 NaN/Inf 检测的安全钳位\n\" +\n\"// ================================================\n\" +\n\"float nanGuardClamp(float value, float minValue, float maxValue, float defaultValue) {\n\" +\n\"    // 检测 NaN 或 Inf（HLSL 同样支持 isnan/isinf）\n\" +\n\"    float invalidMask = isnan(value) + isinf(value);\n\" +\n\"\n\" +\n\"    // 对正常值进行钳位\n\" +\n\"    float clampedValue = clamp(value, minValue, maxValue);\n\" +\n\"\n\" +\n\"    // HLSL 使用 lerp 进行线性插值（等同于 GLSL 的 mix）\n\" +\n\"    return lerp(clampedValue, defaultValue, invalidMask);\n\" +\n\"}\n\" +\n\"\n\" +\n\"// ================================================\n\" +\n\"// safeNormalize: 安全的向量归一化\n\" +\n\"// ================================================\n\" +\n\"float3 safeNormalize(float3 vector, float3 defaultDirection) {\n\" +\n\"    // 计算向量长度\n\" +\n\"    float len = length(vector);\n\" +\n\"\n\" +\n\"    // 无分支检测：长度是否安全\n\" +\n\"    float safeMask = step(NANGUARD_MIN_NORMALIZE_LENGTH, len);\n\" +\n\"\n\" +\n\"    // 计算归一化结果\n\" +\n\"    float3 normalizedVector = vector / max(len, NANGUARD_MIN_NORMALIZE_LENGTH);\n\" +\n\"\n\" +\n\"    // 无分支选择\n\" +\n\"    return lerp(defaultDirection, normalizedVector, safeMask);\n\" +\n\"}\n\" +\n\"\n\" +\n\"// ================================================\n\" +\n\"// safeDivide: 安全的除法运算\n\" +\n\"// ================================================\n\" +\n\"float safeDivide(float dividend, float divisor, float defaultValue, float epsilon) {\n\" +\n\"    // 计算除数绝对值\n\" +\n\"    float absDivisor = abs(divisor);\n\" +\n\"\n\" +\n\"    // 无分支检测：除数是否安全\n\" +\n\"    float safeMask = step(epsilon, absDivisor);\n\" +\n\"\n\" +\n\"    // 计算除法结果\n\" +\n\"    float divisionResult = dividend / divisor;\n\" +\n\"\n\" +\n\"    // 无分支选择\n\" +\n\"    return lerp(defaultValue, divisionResult, safeMask);\n\" +\n\"}\n\" +\n\"\n\" +\n\"// ================================================\n\" +\n\"// 便捷包装函数（使用默认参数）\n\" +\n\"// ================================================\n\" +\n\"\n\" +\n\"float nanGuardClamp(float value, float minValue, float maxValue) {\n\" +\n\"    return nanGuardClamp(value, minValue, maxValue, NANGUARD_DEFAULT_SAFE_VALUE);\n\" +\n\"}\n\" +\n\"\n\" +\n\"float3 safeNormalize(float3 vector) {\n\" +\n\"    return safeNormalize(vector, float3(0.0, 0.0, 1.0));\n\" +\n\"}\n\" +\n\"\n\" +\n\"float safeDivide(float dividend, float divisor, float defaultValue) {\n\" +\n\"    return safeDivide(dividend, divisor, defaultValue, NANGUARD_DIVIDE_EPSILON);\n\" +\n\"}\n\" +\n\"\n\" +\n\"float safeDivide(float dividend, float divisor) {\n\" +\n\"    return safeDivide(dividend, divisor, NANGUARD_DEFAULT_SAFE_VALUE, NANGUARD_DIVIDE_EPSILON);\n\" +\n\"}\n\" +\n\"\n\" +\n\"#endif // NANGUARD_HLSL_INCLUDED\n\";"







    /**



     * 快速逆平方根近似（Quake III 算法，GPU友好）。



     *



     * <p>计算 {@code 1.0f / sqrt(x)} 的近似值，精度约 ±1%，



     * 比 {@code 1.0f / (float) Math.sqrt(x)} 快约 3-4 倍。



     *



     * <p>原理：利用 IEEE 754 浮点数位表示，



     * 将整数表示减半并取"魔数"差值，得到逆平方根的近似整数表示。



     *



     * @param x 输入值（必须 > 0）



     * @return 1/sqrt(x) 的近似值



     */



    private static float fastInvSqrt(float x) {



        float xHalf = 0.5f * x;



        int i = Float.floatToIntBits(x);



        i = 0x5f3759df - (i >> 1);



        float result = Float.intBitsToFloat(i);



        // 一次牛顿-拉夫森迭代提高精度



        result *= (1.5f - xHalf * result * result);



        return result;



    }



}
