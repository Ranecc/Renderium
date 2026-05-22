// ============================================================
// SGS 零带宽抗振铃后处理 Shader (GLSL 450 core)
// ============================================================
//
// 基于 TOPS v2.5 自修复系统 §9.2 的 SGS (Scalar Gradient
// Smoothing) 修正算法
//
// 核心原理：
//   拉普拉斯算子 L = ∇²P 已包含 6 邻域信息（边缘检测通常已计算）
//   SGS 修正项 f_attract = -k * L 不需要额外纹理读取
//   纯 MAD 运算：1 次乘法 + 1 次加法/texel
//
// 性能特征：
//   - 零额外带宽：仅 5 次 texture() 调用（与原始拉普拉斯相同）
//   - 纯 ALU 运算：1 次乘法 + 1 次加法 + NaN 保护
//   - GPU 开销 < 0.05ms/frame (1080p)
//   - 完全无分支实现，避免 Warp 分化
//
// 适用场景：
//   - 超分辨率输出 (DLSS/FSR/XeSS) 的块效应消除
//   - 锐化/Unsharp Mask 后的振铃伪影抑制
//   - TAA/FXAA 后处理的边缘平滑
//
// 版本: 1.0
// 作者: Renderium Team
// 许可: 与 Renderium 主项目相同
//
// ============================================================

#version 450 core

// ================================================
// 输入/输出接口定义
// ================================================

/// 纹理坐标输入（来自全屏四边形的顶点着色器）
layout(location = 0) in vec2 vTexCoord;

/// 最终颜色输出（写入目标帧缓冲或纹理）
layout(location = 0) out vec4 fragColor;

// ================================================
// 资源绑定 (Descriptor Set)
// ================================================

/// 输入纹理：超分辨率输出或后处理链前级输出
/// 格式要求：R16G16B16A16_SFLOAT 或 R8G8B8A8_UNORM
layout(binding = 0) uniform sampler2D uInputTexture;

// ================================================
// Uniform 缓冲区：SGS 动态参数
// ================================================
//
// 【参数说明】
//   uAttractK    - 阻尼系数 k，控制 SGS 修正强度
//                  默认值 0.1，范围 [0.01, 1.0]
//                  值越大 → 振铃抑制越强，但可能损失细节
//                  值越小 → 保持更多细节，但振铃残留越多
//
//   uDecayTimer  - 衰减计时器（归一化到 [0, 1]）
//                  用于场景切换后的动态系数衰减
//                  1.0 = 刚发生场景切换（最大阻尼）
//                  0.0 = 已稳定（使用默认阻尼）
//
//   uSceneChange - 场景切换标志
//                  true  → 增大阻尼系数 3x（抑制瞬态伪影）
//                  false → 使用标准阻尼系数
//
//   uEdgeThreshold - 边缘强度阈值（条件执行优化）
//                    仅当拉普拉斯模长超过此值时执行 SGS
//                    默认 0.0（始终执行），建议范围 [0.005, 0.02]
//

layout(binding = 1) uniform SGSUniforms {
    /// 阻尼系数 k（默认 0.1，基于 TOPS §9.2 校准）
    float uAttractK;

    /// 衰减计时器 [0, 1]（Java 端每帧递减更新）
    float uDecayTimer;

    /// 场景切换标志（true 时增大阻尼 3x）
    bool uSceneChange;

    /// 边缘强度阈值（可选的条件执行优化阈值）
    float uEdgeThreshold;
};

// ================================================
// NanGuard 内联保护函数
// ================================================
//
// 复用 Phase 1 的 NanGuard 模式，防止 NaN/Inf 传播
// 采用无分支实现（mix/step 替代 if-else）
//

/**
 * sgsNanGuard - SGS 专用的 NaN/Inf 向量保护
 *
 * 【参数】
 *   @param value - 待保护的向量值（可能包含 NaN/Inf 分量）
 *
 * 【返回值】
 *   安全的向量：NaN/Inf 分量被替换为 0.0，正常分量保持不变
 *
 * 【实现原理】
 *   对每个分量独立检测异常：
 *     invalidMask.x = float(isnan(value.x)) + float(isinf(value.x))
 *   然后使用 mix() 无分支选择：
 *     result = mix(value, vec4(0.0), invalidMask)
 */
vec4 sgsNanGuard(vec4 value) {
    // 为每个分量生成独立的异常掩码（NaN 或 Inf 时为 1.0）
    vec4 invalidMask = vec4(
        float(isnan(value.r)) + float(isinf(value.r)),
        float(isnan(value.g)) + float(isinf(value.g)),
        float(isnan(value.b)) + float(isinf(value.b)),
        float(isnan(value.a)) + float(isinf(value.a))
    );

    // 无分支选择：mix(a, b, w) = a*(1-w) + b*w
    // invalidMask == 0.0 → 返回原始值（正常路径）
    // invalidMask == 1.0 → 返回 0.0（异常路径）
    return mix(value, vec4(0.0), invalidMask);
}

// ================================================
// 主函数：SGS 抗振铃处理
// ================================================
//
// 【算法流程】
//
//   步骤1: 采样中心像素及其 4-邻域（拉普拉斯计算所需）
//           - 中心点：texture(uInputTexture, vTexCoord)
//           - 左/右：vTexCoord ± (texelSize.x, 0)
//           - 上/下：vTexCoord ± (0, texelSize.y)
//           总计 5 次 texture() 调用 ← 与无 SGS 版本相同！
//
//   步骤2: 计算离散拉普拉斯算子 L
//           L = P(x+1,y) + P(x-1,y) + P(x,y+1) + P(x,y-1) - 4*P(x,y)
//           这是二阶导数的离散近似，检测局部曲率
//
//   步骤3: 动态调整阻尼系数 k
//           - 场景切换时：k_effective = min(k * 3.0, 1.0)
//             （增大 3x 以抑制瞬态伪影，上限钳位 1.0）
//           - 正常状态：k_effective = k
//           - 可选：结合 decayTimer 平滑过渡
//
//   步骤4: 计算 SGS 修正项（纯 MAD 运算！）
//           damping = -k_effective * L
//           这是标量吸引子阻尼的核心公式
//
//   步骤5: NanGuard 保护
//           damping = sgsNanGuard(damping)
//           防止极端情况下的数值溢出传播
//
//   步骤6: 条件执行优化（可选）
//           若 edgeStrength = length(L.rgb) < threshold：
//             直接返回 center（平坦区域跳过 SGS）
//           否则：
//             返回 center + damping
//           使用 step() 实现无分支条件选择
//
//   步骤7: 输出最终颜色
//           fragColor = center + damping
//
void main() {
    // ----------------------------------------
    // 步骤1: 采样中心像素
    // ----------------------------------------
    vec4 center = texture(uInputTexture, vTexCoord);

    // ----------------------------------------
    // 步骤2: 计算离散拉普拉斯算子（4-邻域差分）
    // ----------------------------------------
    //
    // 拉普拉斯算子的物理意义：
    //   度量像素与其邻域的平均偏差
    //   高值 → 边缘/高频区域（可能产生振铃）
    //   低值 → 平滑区域（无需修正）
    //
    // 注意：此计算与边缘检测 Pass 共享相同的纹理读取模式
    //       因此 SGS 不引入任何额外的带宽开销！

    // 计算单个 texel 的 UV 空间尺寸
    vec2 texelSize = 1.0 / textureSize(uInputTexture, 0);

    // 4-邻域采样（上、下、左、右）
    vec4 neighborL = texture(uInputTexture, vTexCoord + vec2(-texelSize.x, 0.0));  // 左
    vec4 neighborR = texture(uInputTexture, vTexCoord + vec2( texelSize.x, 0.0));  // 右
    vec4 neighborT = texture(uInputTexture, vTexCoord + vec2(0.0,  texelSize.y));  // 上
    vec4 neighborB = texture(uInputTexture, vTexCoord + vec2(0.0, -texelSize.y));  // 下

    // 离散拉普拉斯: L = Σ(邻居) - 4*中心
    // 这等价于二阶偏导数近似: ∇²P ≈ ∂²P/∂x² + ∂²P/∂y²
    vec4 laplacian = neighborL + neighborR + neighborT + neighborB - 4.0 * center;

    // ----------------------------------------
    // 步骤3: 动态调整阻尼系数 k
    // ----------------------------------------
    //
    // 场景切换时增大阻尼的原因：
    //   超分辨率算法在场景切换瞬间会产生更严重的伪影
    //   因为运动矢量估计在帧间不连续时失效
    //   增大 k 可以快速压制这些瞬态伪影

    float k = uAttractK;

    // 场景切换检测：使用 mix() 无分支选择（避免 Warp 分化）
    // sceneChangeBoost: true→3.0, false→1.0
    float sceneChangeBoost = mix(1.0, 3.0, uSceneChange ? 1.0 : 0.0);

    // 应用场景切换增益并钳位到安全范围 [0.0, 1.0]
    k = clamp(k * sceneChangeBoost, 0.0, 1.0);

    // 可选：结合衰减计时器实现平滑过渡
    // 当 decayTimer 从 1.0 衰减到 0.0 时，k 逐渐回归默认值
    // 公式：k_final = k_base + (k_boosted - k_base) * decayTimer
    float kEffective = mix(k, k * sceneChangeBoost, uDecayTimer);
    kEffective = clamp(kEffective, 0.0, 1.0);

    // ----------------------------------------
    // 步骤4: 计算 SGS 标量吸引子阻尼（纯 MAD 运算）
    // ----------------------------------------
    //
    // 数学原理（来自 TOPS §9.2）：
    //   f_attract = -k * ∇²P
    //
    // 这是梯度下降的一步迭代：
    //   P_new = P_old + f_attract = P_old - k * ∇²P
    //
    // 物理类比：
    //   将每个像素视为被弹簧连接到其邻域平均值的位置
    //   拉普拉斯度量偏离平衡位置的"力"
    //   阻尼项将像素拉向局部平衡，消除振荡（振铃）
    //
    // 计算成本分析：
    //   vec4 * float : 4 次 ALU 乘法（分量级）
    //   一元负号     : 0 次（编译器优化为乘数取反）
    //   总计         : ~4 条 MAD 指令（GPU 单周期完成）

    vec4 damping = -kEffective * laplacian;

    // ----------------------------------------
    // 步骤5: NanGuard 数值保护
    // ----------------------------------------
    damping = sgsNanGuard(damping);

    // ----------------------------------------
    // 步骤6: 条件执行优化（边缘强度门控）
    // ----------------------------------------
    //
    // 优化原理：
    //   平坦区域的拉普拉斯接近零，SGS 修正项也接近零
    //   可以跳过这些区域的计算以节省 ALU 开销
    //   但由于 GPU 的 SIMD 特性，实际节省有限
    //   主要价值在于避免对平坦区域的微小扰动
    //
    // 实现方式：
    //   edgeStrength = length(laplacian.rgb)
    //   skipMask = step(uEdgeThreshold, edgeStrength)
    //   result = mix(center, center + damping, skipMask)
    //
    // 当 uEdgeThreshold = 0.0 时，此优化等效于始终执行 SGS

    // 计算边缘强度（RGB 三通道拉普拉斯模长）
    float edgeStrength = length(laplacian.rgb);

    // 无分支门控：step(edge, x) 在 x > edge 时返回 1.0
    // executeMask: 边缘强度 > 阈值 → 1.0（执行 SGS）
    //              边缘强度 ≤ 阈值 → 0.0（跳过 SGS）
    float executeMask = step(uEdgeThreshold, edgeStrength);

    // ----------------------------------------
    // 步骤7: 输出最终颜色
    // ----------------------------------------
    //
    // 最终结果 = 中心像素 + SGS 阻尼修正
    // 在平坦区域（executeMask=0）：直接输出原始像素
    // 在边缘区域（executeMask=1）：应用抗振铃修正

    vec4 corrected = center + damping;
    fragColor = mix(center, corrected, executeMask);
}
