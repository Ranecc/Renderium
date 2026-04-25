// ============================================================
// Renderium Accelerator - 收敛监控器
// ============================================================
// 四维收敛检测: 位置/速度/能量/质量
// 使用指数移动平均(EMA)平滑残差，避免噪声误判
//
// 算法:
//   residual = |current - target|
//   emaResidual = alpha * residual + (1-alpha) * prevEmaResidual
//   isConverged = (emaResidual < tolerance) && (iterations >= minIterations)
//
// GPU友好: 无分支收敛判断使用条件移动
// ============================================================

#include "accel_config.h"
#include "compute_apis.h"
#include "platform_abstraction.h"

#include <cstring>
#include <cmath>
#include <atomic>
#include <algorithm>

namespace renderium {
namespace accel {
namespace convergence_monitor {

// EMA平滑因子 (0.2 = 较快响应, 适合帧级监控)
static constexpr f32 EMA_ALPHA = 0.2f;

// 最小迭代次数 (避免过早判定收敛)
static constexpr u32 MIN_ITERATIONS = 3;

struct ConvergenceContext {
    f32 tolerance;
    u32 maxIterations;
    f32 emaResidual[4];         // 各维度EMA残差
    u32 iterationCount[4];      // 各维度迭代计数
    f32 lastValue[4];           // 各维度上次值
    u64 totalComputeTimeNs[4];  // 各维度累计耗时
    u32 contextId;
    bool valid;
};

static constexpr u32 MAX_CONVERGENCE_CONTEXTS = 16;
static ConvergenceContext g_contexts[MAX_CONVERGENCE_CONTEXTS];
static std::atomic<u32> g_nextContextId{1};

static ConvergenceContext* findContext(ComputeContextHandle handle) {
    u32 idx = static_cast<u32>(handle & 0xFFFF);
    if (idx >= MAX_CONVERGENCE_CONTEXTS) return nullptr;

    ConvergenceContext* ctx = &g_contexts[idx];
    if (!ctx->valid || ctx->contextId != static_cast<u32>(handle >> 16)) {
        return nullptr;
    }
    return ctx;
}

ACCEL_API OperationResult createContext(
    f32 tolerance,
    u32 maxIterations,
    ComputeContextHandle& outContext
) {
    if (tolerance <= 0.0f) {
        OperationResult result;
        result.error = ErrorCode::InvalidArgument;
        result.errorMessage = "Tolerance must be positive";
        return result;
    }

    u32 slot = UINT32_MAX;
    for (u32 i = 0; i < MAX_CONVERGENCE_CONTEXTS; ++i) {
        if (!g_contexts[i].valid) {
            slot = i;
            break;
        }
    }

    if (slot == UINT32_MAX) {
        OperationResult result;
        result.error = ErrorCode::OutOfMemory;
        result.errorMessage = "No free convergence context slots";
        return result;
    }

    u32 id = g_nextContextId.fetch_add(1, std::memory_order_relaxed);

    ConvergenceContext& ctx = g_contexts[slot];
    ctx.tolerance = tolerance;
    ctx.maxIterations = maxIterations;
    ctx.contextId = id;
    ctx.valid = true;

    std::memset(ctx.emaResidual, 0, sizeof(ctx.emaResidual));
    std::memset(ctx.iterationCount, 0, sizeof(ctx.iterationCount));
    std::memset(ctx.lastValue, 0, sizeof(ctx.lastValue));
    std::memset(ctx.totalComputeTimeNs, 0, sizeof(ctx.totalComputeTimeNs));

    outContext = (static_cast<u64>(id) << 16) | static_cast<u64>(slot);

    OperationResult result;
    result.error = ErrorCode::Success;
    return result;
}

ACCEL_API OperationResult checkConvergence(
    ComputeContextHandle context,
    Dimension dimension,
    f32 currentValue,
    f32 targetValue,
    bool& isConverged
) {
    ConvergenceContext* ctx = findContext(context);
    if (ctx == nullptr) {
        OperationResult result;
        result.error = ErrorCode::NotInitialized;
        result.errorMessage = "Invalid convergence context handle";
        return result;
    }

    u32 dimIdx = static_cast<u32>(dimension);
    if (dimIdx >= 4) {
        OperationResult result;
        result.error = ErrorCode::InvalidArgument;
        result.errorMessage = "Dimension index out of range";
        return result;
    }

    u64 startTime = platform::getTimestampNs();

    // 计算残差
    f32 residual = std::abs(currentValue - targetValue);

    // EMA平滑 (无分支: 条件移动)
    f32 prevEma = ctx->emaResidual[dimIdx];
    if (ctx->iterationCount[dimIdx] == 0) {
        // 首次: 直接赋值
        ctx->emaResidual[dimIdx] = residual;
    } else {
        // EMA更新: ema = alpha * residual + (1-alpha) * prevEma
        ctx->emaResidual[dimIdx] = EMA_ALPHA * residual + (1.0f - EMA_ALPHA) * prevEma;
    }

    ctx->iterationCount[dimIdx]++;
    ctx->lastValue[dimIdx] = currentValue;

    // 收敛判定: EMA残差 < 容差 且 迭代次数 >= 最小次数
    bool emaConverged = ctx->emaResidual[dimIdx] < ctx->tolerance;
    bool enoughIterations = ctx->iterationCount[dimIdx] >= MIN_ITERATIONS;
    isConverged = emaConverged && enoughIterations;

    // 超过最大迭代次数也视为"收敛"（强制停止）
    if (ctx->iterationCount[dimIdx] >= ctx->maxIterations) {
        isConverged = true;
    }

    u64 elapsed = platform::getTimestampNs() - startTime;
    ctx->totalComputeTimeNs[dimIdx] += elapsed;

    OperationResult result;
    result.error = ErrorCode::Success;
    return result;
}

ACCEL_API OperationResult getConvergenceState(
    ComputeContextHandle context,
    ConvergenceState& state
) {
    ConvergenceContext* ctx = findContext(context);
    if (ctx == nullptr) {
        OperationResult result;
        result.error = ErrorCode::NotInitialized;
        result.errorMessage = "Invalid convergence context handle";
        return result;
    }

    for (u32 i = 0; i < 4; ++i) {
        state.isConverged[i] = (ctx->emaResidual[i] < ctx->tolerance) &&
                               (ctx->iterationCount[i] >= MIN_ITERATIONS);
        state.residualNorm[i] = ctx->emaResidual[i];
        state.iterationCount[i] = ctx->iterationCount[i];
        state.totalTimeNs[i] = ctx->totalComputeTimeNs[i];
    }

    OperationResult result;
    result.error = ErrorCode::Success;
    return result;
}

ACCEL_API OperationResult reset(ComputeContextHandle context) {
    ConvergenceContext* ctx = findContext(context);
    if (ctx == nullptr) {
        OperationResult result;
        result.error = ErrorCode::NotInitialized;
        result.errorMessage = "Invalid convergence context handle";
        return result;
    }

    std::memset(ctx->emaResidual, 0, sizeof(ctx->emaResidual));
    std::memset(ctx->iterationCount, 0, sizeof(ctx->iterationCount));
    std::memset(ctx->lastValue, 0, sizeof(ctx->lastValue));
    std::memset(ctx->totalComputeTimeNs, 0, sizeof(ctx->totalComputeTimeNs));

    OperationResult result;
    result.error = ErrorCode::Success;
    return result;
}

ACCEL_API OperationResult destroyContext(ComputeContextHandle context) {
    ConvergenceContext* ctx = findContext(context);
    if (ctx == nullptr) {
        OperationResult result;
        result.error = ErrorCode::NotInitialized;
        result.errorMessage = "Invalid convergence context handle";
        return result;
    }

    ctx->valid = false;
    ctx->contextId = 0;

    OperationResult result;
    result.error = ErrorCode::Success;
    return result;
}

} // namespace convergence_monitor
} // namespace accel
} // namespace renderium
