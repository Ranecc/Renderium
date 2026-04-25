// ============================================================
// Renderium Accelerator - Lyapunov 质量评估器
// ============================================================
// 核心算法实现 (Task 2 P0)
//
// 算法原理:
//   Lyapunov指数衡量动态系统对初始条件的敏感性。
//   在渲染质量评估中，用于检测帧间质量突变:
//     λ > 0: 不稳定（质量恶化）
//     λ ≈ 0: 稳定（质量一致）
//     λ < 0: 收敛（质量改善）
//
// 图论关联:
//   Lyapunov指数可视为"图稳定性度量"的连续版本:
//   - BFS可见集变化率 → 离散Lyapunov指数
//   - 帧间像素差异 → 连续Lyapunov指数
//   两者共同构成元状态机的输入信号
//
// 元状态机联动:
//   Lyapunov指数 → 质量维度信号 → 元状态机决策
//   高λ值触发UNSTABLE状态，低λ值维持STABLE
//
// 性能目标: 1080p < 150μs (AVX2), < 500μs (scalar)
// ============================================================

#include "compute_apis.h"
#include "platform_abstraction.h"

#include <cstring>
#include <cmath>
#include <vector>
#include <algorithm>
#include <numeric>

namespace renderium {
namespace accel {
namespace lyapunov_checker {

// ==================== 常量定义 ====================

/** 自然对数的倒数（预计算，避免运行时除法） */
static constexpr f64 INV_LOG2 = 1.4426950408889634;  // 1.0 / log(2.0)

/** Lyapunov指数稳定性阈值 */
static constexpr f32 STABILITY_THRESHOLD = 0.01f;

/** 质量退化阈值 */
static constexpr f32 DEGRADATION_MILD = 0.05f;
static constexpr f32 DEGRADATION_SEVERE = 0.15f;

/** 最小数据量要求（避免统计不稳定） */
static constexpr u32 MIN_DATA_POINTS = 16;

// ==================== Neumaier-Kahan 累加器 ====================

/**
 * Neumaier改进版Kahan累加器
 * <p>
 * 比标准Kahan算法在大量元素累加时更稳定。
 * 关键改进: 当|sum| > |value|时使用改进的补偿公式。
 *
 * 精度: 接近f80扩展精度，误差 < O(n·ε²)
 */
struct NeumaierAccumulator {
    f64 sum = 0.0;
    f64 compensation = 0.0;
    u64 count = 0;
    
    /**
     * 累加单个值
     */
    void add(f64 value) {
        f64 newSum = sum + value;
        
        if (std::abs(sum) >= std::abs(value)) {
            // 标准Kahan补偿
            compensation += (sum - newSum) + value;
        } else {
            // Neumaier改进: 当value更大时交换角色
            compensation += (value - newSum) + sum;
        }
        
        sum = newSum;
        count++;
    }
    
    /**
     * 获取补偿后的精确总和
     */
    f64 total() const {
        return sum + compensation;
    }
    
    /**
     * 重置累加器
     */
    void reset(f64 initialValue = 0.0) {
        sum = initialValue;
        compensation = 0.0;
        count = 0;
    }
};

// ==================== Lyapunov 上下文 ====================

struct LyapunovContext {
    // 滑动窗口参数
    u32 windowSize;                     // 窗口大小（帧数）
    
    // 历史数据（环形缓冲区）
    std::vector<f32> lyapunovHistory;   // Lyapunov指数历史
    u32 historyHead = 0;                // 环形缓冲区头指针
    u32 historyCount = 0;               // 已填充的历史数量
    
    // Neumaier累加器（用于窗口内统计）
    NeumaierAccumulator sumAccum;       // 总和累加
    NeumaierAccumulator sqSumAccum;     // 平方和累加（用于方差）
    
    // 上一帧数据（用于帧间差分）
    const f32* prevFrameData = nullptr;
    size_t prevFrameSize = 0;
    std::vector<f32> prevFrameBuffer;   // 拥有的上一帧数据副本
    
    // 状态
    f32 currentLyapunov = 0.0f;         // 当前Lyapunov指数
    f32 currentQualityScore = 1.0f;     // 当前质量评分
    bool currentIsStable = true;        // 当前是否稳定
    u32 currentDegradationLevel = 0;    // 当前退化等级
    
    // 性能统计
    u64 totalEvalTimeNs = 0;
    u32 totalEvaluations = 0;
    
    explicit LyapunovContext(u32 winSize) 
        : windowSize(winSize) {
        lyapunovHistory.resize(winSize, 0.0f);
        prevFrameBuffer.reserve(1024 * 1024);  // 预分配1M像素
    }
};

// ==================== 核心算法 ====================

/**
 * 计算帧间Lyapunov指数
 * <p>
 * 使用降采样帧数据（通常是亮度/对比度特征向量），
 * 而非原始像素数据，大幅减少计算量。
 *
 * 数学公式:
 *   λ = (1/N) × Σ log(|Δ_i / Δ_{i-1}|)
 * 其中:
 *   Δ_i = |frame_i - frame_{i-1}| (帧间差分)
 *   N = 差分序列长度
 *
 * 图论解释:
 *   Lyapunov指数衡量"帧空间"中轨迹的发散速率。
 *   高λ值意味着帧质量在快速变化（不稳定），
 *   类似于图中节点频繁加入/离开可见集。
 *
 * @param currentData 当前帧特征数据
 * @param prevData 上一帧特征数据
 * @param dataSize 数据元素数
 * @return Lyapunov指数
 */
static f32 computeLyapunovExponent(
    const f32* currentData,
    const f32* prevData,
    size_t dataSize
) {
    if (currentData == nullptr || prevData == nullptr || dataSize < MIN_DATA_POINTS) {
        return 0.0f;  // 数据不足，返回中性值
    }
    
    NeumaierAccumulator lyapunovSum;
    u32 validPairs = 0;
    
    // 上一帧的差分值（用于计算比率）
    f64 prevDelta = 0.0;
    bool hasPrevDelta = false;
    
    for (size_t i = 0; i < dataSize; ++i) {
        // 计算帧间差分
        f64 delta = std::abs(static_cast<f64>(currentData[i]) - 
                             static_cast<f64>(prevData[i]));
        
        // 跳过零差分（避免log(0)）
        if (delta < 1e-10) continue;
        
        if (hasPrevDelta && prevDelta > 1e-10) {
            // 计算差分比率
            f64 ratio = delta / prevDelta;
            
            // 跳过极端比率（避免数值爆炸）
            if (ratio > 1e6 || ratio < 1e-6) {
                prevDelta = delta;
                continue;
            }
            
            // 累加 log2(|ratio|)（使用乘法替代除法: log2(x) = log(x) * INV_LOG2）
            f64 logRatio = std::log(ratio) * INV_LOG2;
            lyapunovSum.add(logRatio);
            validPairs++;
        }
        
        prevDelta = delta;
        hasPrevDelta = true;
    }
    
    if (validPairs < MIN_DATA_POINTS / 2) {
        return 0.0f;  // 有效数据不足
    }
    
    // 平均Lyapunov指数
    return static_cast<f32>(lyapunovSum.total() / static_cast<f64>(validPairs));
}

/**
 * 从Lyapunov指数推导质量评分
 * <p>
 * 映射规则:
 *   λ < -0.5: 评分=1.0 (质量改善中)
 *   λ ≈ 0:    评分=0.9 (稳定)
 *   λ > 0:    评分=1.0 - k×λ (线性衰减)
 *   λ > 1.0:  评分=0.0 (严重退化)
 *
 * @param lyapunov Lyapunov指数
 * @return 质量评分 [0.0, 1.0]
 */
static f32 deriveQualityScore(f32 lyapunov) {
    if (lyapunov < -0.5f) {
        return 1.0f;  // 质量改善
    } else if (lyapunov < 0.0f) {
        // 轻微收敛，评分接近1.0
        return 0.95f + 0.05f * (1.0f + lyapunov * 2.0f);
    } else if (lyapunov < 1.0f) {
        // 轻微发散，线性衰减
        return 0.95f - 0.95f * lyapunov;
    } else {
        return 0.0f;  // 严重退化
    }
}

/**
 * 判断稳定性
 */
static bool determineStability(f32 lyapunov) {
    return std::abs(lyapunov) < STABILITY_THRESHOLD;
}

/**
 * 判断退化等级
 */
static u32 determineDegradationLevel(f32 lyapunov, f32 qualityScore) {
    if (lyapunov < 0.0f) return 0;  // 收敛，无退化
    if (qualityScore > 1.0f - DEGRADATION_MILD) return 0;  // 正常
    if (qualityScore > 1.0f - DEGRADATION_SEVERE) return 1;  // 轻微
    return 2;  // 严重
}

// ==================== 公共API实现 ====================

ACCEL_API OperationResult createContext(
    u32 windowSize,
    ComputeContextHandle& outContext
) {
    if (windowSize < 4 || windowSize > 256) {
        return {ErrorCode::InvalidArgument, 0, "windowSize must be 4-256"};
    }
    
    try {
        auto* ctx = new LyapunovContext(windowSize);
        outContext = reinterpret_cast<ComputeContextHandle>(ctx);
        return {ErrorCode::Success, 0, nullptr};
    } catch (const std::bad_alloc&) {
        return {ErrorCode::OutOfMemory, 0, "Failed to allocate Lyapunov context"};
    }
}

ACCEL_API OperationResult evaluateFrame(
    ComputeContextHandle context,
    const QualityInput& input,
    QualityOutput& output
) {
    auto* ctx = reinterpret_cast<LyapunovContext*>(context);
    if (ctx == nullptr) {
        return {ErrorCode::InvalidArgument, 0, "Null context"};
    }
    
    u64 startTime = platform::getTimestampNs();
    
    // 检查是否有上一帧数据
    if (ctx->prevFrameData == nullptr || ctx->prevFrameSize == 0) {
        // 第一帧: 保存数据，返回默认值
        if (input.frameData != nullptr && input.dataSize > 0) {
            ctx->prevFrameBuffer.assign(
                input.frameData, 
                input.frameData + input.dataSize
            );
            ctx->prevFrameData = ctx->prevFrameBuffer.data();
            ctx->prevFrameSize = input.dataSize;
        }
        
        output.lyapunovExponent = 0.0f;
        output.qualityScore = 1.0f;
        output.isStable = true;
        output.degradationLevel = 0;
        
        ctx->currentLyapunov = 0.0f;
        ctx->currentQualityScore = 1.0f;
        ctx->currentIsStable = true;
        ctx->currentDegradationLevel = 0;
        
        return {ErrorCode::Success, 0, nullptr};
    }
    
    // 计算Lyapunov指数
    size_t dataSize = std::min(input.dataSize, ctx->prevFrameSize);
    f32 lyapunov = computeLyapunovExponent(
        input.frameData,
        ctx->prevFrameData,
        dataSize
    );
    
    // 如果提供了上一帧的Lyapunov值，结合计算
    if (input.previousLyapunov != 0.0f) {
        // 指数移动平均（EMA），平滑短期波动
        constexpr f32 EMA_ALPHA = 0.3f;
        lyapunov = EMA_ALPHA * lyapunov + (1.0f - EMA_ALPHA) * input.previousLyapunov;
    }
    
    // 更新滑动窗口
    ctx->lyapunovHistory[ctx->historyHead] = lyapunov;
    ctx->historyHead = (ctx->historyHead + 1) % ctx->windowSize;
    ctx->historyCount = std::min(ctx->historyCount + 1, ctx->windowSize);
    
    // 计算窗口内统计量（用于元状态机）
    ctx->sumAccum.reset();
    ctx->sqSumAccum.reset();
    
    for (u32 i = 0; i < ctx->historyCount; ++i) {
        f64 val = static_cast<f64>(ctx->lyapunovHistory[i]);
        ctx->sumAccum.add(val);
        ctx->sqSumAccum.add(val * val);
    }
    
    // 推导输出
    output.lyapunovExponent = lyapunov;
    output.qualityScore = deriveQualityScore(lyapunov);
    output.isStable = determineStability(lyapunov);
    output.degradationLevel = determineDegradationLevel(lyapunov, output.qualityScore);
    
    // 更新上下文状态
    ctx->currentLyapunov = lyapunov;
    ctx->currentQualityScore = output.qualityScore;
    ctx->currentIsStable = output.isStable;
    ctx->currentDegradationLevel = output.degradationLevel;
    
    // 保存当前帧数据
    if (input.frameData != nullptr && input.dataSize > 0) {
        ctx->prevFrameBuffer.assign(
            input.frameData, 
            input.frameData + input.dataSize
        );
        ctx->prevFrameData = ctx->prevFrameBuffer.data();
        ctx->prevFrameSize = input.dataSize;
    }
    
    u64 elapsed = platform::getTimestampNs() - startTime;
    ctx->totalEvalTimeNs += elapsed;
    ctx->totalEvaluations++;
    
    return {ErrorCode::Success, 0, nullptr};
}

ACCEL_API OperationResult reset(ComputeContextHandle context) {
    auto* ctx = reinterpret_cast<LyapunovContext*>(context);
    if (ctx == nullptr) {
        return {ErrorCode::InvalidArgument, 0, "Null context"};
    }
    
    ctx->historyHead = 0;
    ctx->historyCount = 0;
    ctx->prevFrameData = nullptr;
    ctx->prevFrameSize = 0;
    ctx->prevFrameBuffer.clear();
    ctx->currentLyapunov = 0.0f;
    ctx->currentQualityScore = 1.0f;
    ctx->currentIsStable = true;
    ctx->currentDegradationLevel = 0;
    ctx->sumAccum.reset();
    ctx->sqSumAccum.reset();
    
    return {ErrorCode::Success, 0, nullptr};
}

ACCEL_API OperationResult destroyContext(ComputeContextHandle context) {
    auto* ctx = reinterpret_cast<LyapunovContext*>(context);
    if (ctx == nullptr) {
        return {ErrorCode::InvalidArgument, 0, "Null context"};
    }
    
    delete ctx;
    return {ErrorCode::Success, 0, nullptr};
}

} // namespace lyapunov_checker
} // namespace accel
} // namespace renderium
