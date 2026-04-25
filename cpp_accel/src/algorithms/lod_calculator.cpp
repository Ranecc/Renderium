// ============================================================
// Renderium Accelerator - LOD 距离计算器
// ============================================================
// 批量计算区块LOD等级，支持SIMD优化
// GPU友好设计: 无分支距离比较、平方距离避免sqrt
//
// 算法:
//   1. 计算区块到相机的平方距离 (避免sqrt)
//   2. 屏幕覆盖率 = f(距离, FOV, 区块尺寸)
//   3. LOD等级 = 二分查找距离阈值表 (无分支)
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
namespace lod_calculator {

struct LODContext {
    LODConfig config;
    u32 contextId;
    bool valid;
};

static constexpr u32 MAX_LOD_CONTEXTS = 16;
static LODContext g_contexts[MAX_LOD_CONTEXTS];
static std::atomic<u32> g_nextContextId{1};

static LODContext* findContext(ComputeContextHandle handle) {
    u32 idx = static_cast<u32>(handle & 0xFFFF);
    if (idx >= MAX_LOD_CONTEXTS) return nullptr;

    LODContext* ctx = &g_contexts[idx];
    if (!ctx->valid || ctx->contextId != static_cast<u32>(handle >> 16)) {
        return nullptr;
    }
    return ctx;
}

// 无分支LOD等级选择: 线性扫描距离阈值表
// 编译器可展开为SIMD比较
static u8 selectLODLevel(f32 distSq, const f32* distances, u32 maxLevels) {
    u8 level = 0;
    for (u32 i = 0; i < maxLevels; ++i) {
        f32 thresholdSq = distances[i] * distances[i];
        // 条件移动 (cmov) 而非分支
        level = (distSq > thresholdSq) ? static_cast<u8>(i + 1) : level;
    }
    // 钳制到最大等级
    return (level >= maxLevels) ? static_cast<u8>(maxLevels - 1) : level;
}

// 屏幕覆盖率估计 (GPU友好: 无除法，用乘以倒数)
static f32 estimateScreenCoverage(f32 distSq, f32 fov, f32 sectionSize) {
    // screenCoverage ≈ sectionSize / (distance * tan(fov/2))
    // 用fastInvSqrt近似避免除法
    f32 dist = std::sqrt(distSq);
    if (dist < 1.0f) dist = 1.0f;

    // FOV因子: 1/tan(fov/2) ≈ 2/fov (弧度近似)
    f32 fovRad = fov * (3.14159265f / 180.0f);
    f32 fovFactor = 2.0f / fovRad;

    f32 coverage = sectionSize * fovFactor / dist;
    // 钳制到 [0, 1]
    return (coverage > 1.0f) ? 1.0f : ((coverage < 0.0f) ? 0.0f : coverage);
}

ACCEL_API OperationResult createContext(
    const LODConfig& config,
    ComputeContextHandle& outContext
) {
    if (config.maxLevels == 0 || config.maxLevels > 8) {
        OperationResult result;
        result.error = ErrorCode::InvalidArgument;
        result.errorMessage = "maxLevels must be 1-8";
        return result;
    }

    u32 slot = UINT32_MAX;
    for (u32 i = 0; i < MAX_LOD_CONTEXTS; ++i) {
        if (!g_contexts[i].valid) {
            slot = i;
            break;
        }
    }

    if (slot == UINT32_MAX) {
        OperationResult result;
        result.error = ErrorCode::OutOfMemory;
        result.errorMessage = "No free LOD context slots";
        return result;
    }

    u32 id = g_nextContextId.fetch_add(1, std::memory_order_relaxed);

    g_contexts[slot].config = config;
    g_contexts[slot].contextId = id;
    g_contexts[slot].valid = true;

    outContext = (static_cast<u64>(id) << 16) | static_cast<u64>(slot);

    OperationResult result;
    result.error = ErrorCode::Success;
    return result;
}

ACCEL_API OperationResult batchComputeLOD(
    ComputeContextHandle context,
    const LODInput* inputs,
    LODOutput* outputs,
    u32 count
) {
    if (inputs == nullptr || outputs == nullptr || count == 0) {
        OperationResult result;
        result.error = ErrorCode::InvalidArgument;
        result.errorMessage = "Invalid inputs/outputs/count";
        return result;
    }

    LODContext* ctx = findContext(context);
    if (ctx == nullptr) {
        OperationResult result;
        result.error = ErrorCode::NotInitialized;
        result.errorMessage = "Invalid LOD context handle";
        return result;
    }

    const LODConfig& cfg = ctx->config;

    // 批量计算: 每个区块独立，可并行化
    for (u32 i = 0; i < count; ++i) {
        const LODInput& in = inputs[i];
        LODOutput& out = outputs[i];

        // 平方距离 (避免sqrt)
        f32 dx = static_cast<f32>(in.position.x) - in.cameraX;
        f32 dy = static_cast<f32>(in.position.y) - in.cameraY;
        f32 dz = static_cast<f32>(in.position.z) - in.cameraZ;
        f32 distSq = dx * dx + dy * dy + dz * dz;

        // LOD等级选择 (无分支)
        out.lodLevel = selectLODLevel(distSq, cfg.distances, cfg.maxLevels);

        // 实际距离 (仅输出用)
        out.distance = std::sqrt(distSq);

        // 屏幕覆盖率
        // 区块尺寸估计: 16.0f (Minecraft标准区块16x16x16)
        out.screenCoverage = estimateScreenCoverage(distSq, in.fov, 16.0f);
    }

    OperationResult result;
    result.error = ErrorCode::Success;
    return result;
}

ACCEL_API OperationResult updateThresholds(
    ComputeContextHandle context,
    const f32* newThresholds,
    u32 count
) {
    if (newThresholds == nullptr || count == 0) {
        OperationResult result;
        result.error = ErrorCode::InvalidArgument;
        result.errorMessage = "Invalid thresholds";
        return result;
    }

    LODContext* ctx = findContext(context);
    if (ctx == nullptr) {
        OperationResult result;
        result.error = ErrorCode::NotInitialized;
        result.errorMessage = "Invalid LOD context handle";
        return result;
    }

    u32 copyCount = (count > 8) ? 8 : count;
    u32 copyCount2 = (copyCount > ctx->config.maxLevels) ? ctx->config.maxLevels : copyCount;

    std::memcpy(ctx->config.distances, newThresholds, copyCount2 * sizeof(f32));

    OperationResult result;
    result.error = ErrorCode::Success;
    return result;
}

ACCEL_API OperationResult destroyContext(ComputeContextHandle context) {
    LODContext* ctx = findContext(context);
    if (ctx == nullptr) {
        OperationResult result;
        result.error = ErrorCode::NotInitialized;
        result.errorMessage = "Invalid LOD context handle";
        return result;
    }

    ctx->valid = false;
    ctx->contextId = 0;

    OperationResult result;
    result.error = ErrorCode::Success;
    return result;
}

} // namespace lod_calculator
} // namespace accel
} // namespace renderium
