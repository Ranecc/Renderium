// ============================================================
// Renderium Accelerator - Kahan/Neumaier 高精度累加器
// ============================================================
// 使用Neumaier改进版Kahan算法，数值精度接近f80
// 支持单元素累加和批量SIMD优化
//
// 算法 (Neumaier变体):
//   当 |sum| >= |value|: compensation += (sum - newSum) + value
//   否则:                compensation += (value - newSum) + sum
//   比原始Kahan在大量异号累加时更稳定
//
// GPU友好: 批量模式可并行化为树形归约
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
namespace kahan_accumulator {

struct KahanContext {
    KahanState state;
    u32 contextId;
    bool valid;
};

static constexpr u32 MAX_KAHAN_CONTEXTS = 32;
static KahanContext g_contexts[MAX_KAHAN_CONTEXTS];
static std::atomic<u32> g_nextContextId{1};

static KahanContext* findContext(ComputeContextHandle handle) {
    u32 idx = static_cast<u32>(handle & 0xFFFF);
    if (idx >= MAX_KAHAN_CONTEXTS) return nullptr;

    KahanContext* ctx = &g_contexts[idx];
    if (!ctx->valid || ctx->contextId != static_cast<u32>(handle >> 16)) {
        return nullptr;
    }
    return ctx;
}

// Neumaier改进版单元素累加
static void neumaierAdd(KahanState& state, f64 value) {
    f64 newSum = state.sum + value;

    if (std::abs(state.sum) >= std::abs(value)) {
        state.compensation += (state.sum - newSum) + value;
    } else {
        state.compensation += (value - newSum) + state.sum;
    }

    state.sum = newSum;
    state.elementCount++;
}

ACCEL_API OperationResult createContext(
    f64 initialSum,
    ComputeContextHandle& outContext
) {
    u32 slot = UINT32_MAX;
    for (u32 i = 0; i < MAX_KAHAN_CONTEXTS; ++i) {
        if (!g_contexts[i].valid) {
            slot = i;
            break;
        }
    }

    if (slot == UINT32_MAX) {
        OperationResult result;
        result.error = ErrorCode::OutOfMemory;
        result.errorMessage = "No free Kahan context slots";
        return result;
    }

    u32 id = g_nextContextId.fetch_add(1, std::memory_order_relaxed);

    g_contexts[slot].state.sum = initialSum;
    g_contexts[slot].state.compensation = 0.0;
    g_contexts[slot].state.elementCount = (initialSum != 0.0) ? 1 : 0;
    g_contexts[slot].contextId = id;
    g_contexts[slot].valid = true;

    outContext = (static_cast<u64>(id) << 16) | static_cast<u64>(slot);

    OperationResult result;
    result.error = ErrorCode::Success;
    return result;
}

ACCEL_API OperationResult add(
    ComputeContextHandle context,
    f64 value
) {
    KahanContext* ctx = findContext(context);
    if (ctx == nullptr) {
        OperationResult result;
        result.error = ErrorCode::NotInitialized;
        result.errorMessage = "Invalid Kahan context handle";
        return result;
    }

    neumaierAdd(ctx->state, value);

    OperationResult result;
    result.error = ErrorCode::Success;
    return result;
}

ACCEL_API OperationResult batchAdd(
    ComputeContextHandle context,
    const f64* values,
    u64 count
) {
    if (values == nullptr || count == 0) {
        OperationResult result;
        result.error = ErrorCode::InvalidArgument;
        result.errorMessage = "Invalid values or count";
        return result;
    }

    KahanContext* ctx = findContext(context);
    if (ctx == nullptr) {
        OperationResult result;
        result.error = ErrorCode::NotInitialized;
        result.errorMessage = "Invalid Kahan context handle";
        return result;
    }

    // 批量累加: 顺序Neumaier (保证精度)
    // TODO: 大批量时可改为两阶段归约:
    //   Phase 1: 分组并行累加 (每组4-8个元素)
    //   Phase 2: 合并各组结果
    for (u64 i = 0; i < count; ++i) {
        neumaierAdd(ctx->state, values[i]);
    }

    OperationResult result;
    result.error = ErrorCode::Success;
    return result;
}

ACCEL_API OperationResult getState(
    ComputeContextHandle context,
    KahanState& outState
) {
    KahanContext* ctx = findContext(context);
    if (ctx == nullptr) {
        OperationResult result;
        result.error = ErrorCode::NotInitialized;
        result.errorMessage = "Invalid Kahan context handle";
        return result;
    }

    outState = ctx->state;

    OperationResult result;
    result.error = ErrorCode::Success;
    return result;
}

ACCEL_API OperationResult reset(
    ComputeContextHandle context,
    f64 newInitialSum
) {
    KahanContext* ctx = findContext(context);
    if (ctx == nullptr) {
        OperationResult result;
        result.error = ErrorCode::NotInitialized;
        result.errorMessage = "Invalid Kahan context handle";
        return result;
    }

    ctx->state.sum = newInitialSum;
    ctx->state.compensation = 0.0;
    ctx->state.elementCount = (newInitialSum != 0.0) ? 1 : 0;

    OperationResult result;
    result.error = ErrorCode::Success;
    return result;
}

ACCEL_API OperationResult destroyContext(ComputeContextHandle context) {
    KahanContext* ctx = findContext(context);
    if (ctx == nullptr) {
        OperationResult result;
        result.error = ErrorCode::NotInitialized;
        result.errorMessage = "Invalid Kahan context handle";
        return result;
    }

    ctx->valid = false;
    ctx->contextId = 0;

    OperationResult result;
    result.error = ErrorCode::Success;
    return result;
}

} // namespace kahan_accumulator
} // namespace accel
} // namespace renderium
