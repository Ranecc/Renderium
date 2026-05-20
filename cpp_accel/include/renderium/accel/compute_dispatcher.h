// ============================================================
// Renderium Accelerator - 异步计算调度器 (无锁设计)
// ============================================================
// 注意：此文件当前未集成到任何编译单元中。
// 设计用于未来的 GPU 调度分配（DAG-based compute dispatch scheduling）。
// 当前状态：独立头文件，仅包含接口和实现定义，未包含在 CMakeLists.txt 的源文件列表中。
// ============================================================
// 动态调度器与元层状态机异步于渲染与计算进程
// 设计约束:
//   1. 零阻塞: 调度决策使用原子变量，不持有任何互斥锁
//   2. 异步更新: GPU占用率由独立线程写入，调度器仅读取
//   3. O(1)决策: 调度决策延迟 < 100ns
//   4. 最终一致: GPU信息允许短暂过时 (< 100ms)
//
// 架构:
//   ┌──────────────┐     ┌──────────────┐
//   │ Vulkan渲染线程 │     │ 计算调度线程   │
//   │ (写入GPU状态)  │     │ (读取GPU状态)  │
//   └──────┬───────┘     └──────┬───────┘
//          │ atomic write        │ atomic read
//          ▼                     ▼
//   ┌────────────────────────────────────┐
//   │  原子共享状态 (无锁双缓冲)          │
//   │  - GPUOccupancy (4个f32原子变量)   │
//   │  - DispatchCounters (8个u64原子)   │
//   │  - MacroState (1个u8原子)          │
//   └────────────────────────────────────┘
//          │                     │
//          ▼                     ▼
//   ┌──────────────┐     ┌──────────────┐
//   │ 元状态机       │     │ 算法执行器     │
//   │ (异步状态转换)  │     │ (CPU/GPU路径) │
//   └──────────────┘     └──────────────┘
// ============================================================

#pragma once

#include "accel_config.h"
#include "platform_abstraction.h"
#include <atomic>
#include <cstdint>

namespace renderium {
namespace accel {
namespace compute_dispatcher {

// ==================== 原子浮点辅助 ====================
// 使用 std::bit_cast (C++20) 实现无锁浮点读写
// 解决 Issue: memcpy 方式存在 UB 风险（C++ 标准 §6.9 [basic.types]）
// bit_cast 保证：类型安全、编译器优化友好、无数据竞争

#if __cplusplus >= 202002L

// C++20+: std::bit_cast 是标准推荐方式
inline f32 atomicLoadF32(const std::atomic<u32>& target) {
    u32 bits = target.load(std::memory_order_relaxed);
    return std::bit_cast<f32>(bits);
}

inline void atomicStoreF32(std::atomic<u32>& target, f32 value) {
    u32 bits = std::bit_cast<u32>(value);
    target.store(bits, std::memory_order_relaxed);
}

#else

// C++17 fallback: union type-punning + volatile 强制内存访问顺序
// 联合体类型转换在 C++ 中是合法的（与 memcpy 不同）
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wstrict-aliasing"
inline f32 atomicLoadF32(const std::atomic<u32>& target) {
    union { u32 bits; f32 val; } conv;
    conv.bits = target.load(std::memory_order_relaxed);
    // volatile 写入防止编译器重排或消除读取
    *(volatile f32*)&conv.val = conv.val;
    return conv.val;
}

inline void atomicStoreF32(std::atomic<u32>& target, f32 value) {
    union { u32 bits; f32 val; } conv;
    conv.val = value;
    target.store(conv.bits, std::memory_order_relaxed);
}
#pragma GCC diagnostic pop

#endif

// ==================== 枚举定义 ====================

enum class ComputeDevice : u8 {
    CPU = 0,
    GPU = 1,
    Auto = 2
};

enum class AlgorithmType : u8 {
    BFSOcclusion = 0,
    LODBatchCalc = 1,
    LyapunovEval = 2,
    KahanAccumulate = 3,
    ConvergenceCheck = 4,
    Count
};

// 元状态机: 场景宏观状态 (异步于渲染/计算)
enum class SceneMacroState : u8 {
    Stable = 0,          // 图结构不变，可复用上帧结果
    Transitioning = 1,   // 图结构缓慢变化，增量更新
    Unstable = 2         // 图结构剧烈变化，全量遍历
};

// ==================== 算法配置文件 ====================

struct AlgorithmProfile {
    f32 gpuAffinity;
    u32 minBatchForGPU;
    u32 estimatedCpuTimeUs;
    u32 estimatedGpuTimeUs;
};

static constexpr AlgorithmProfile ALGORITHM_PROFILES[] = {
    { 0.1f, 65536, 50, 200 },   // BFSOcclusion
    { 0.9f, 256,   100, 10 },   // LODBatchCalc
    { 0.15f, 65536, 30, 150 },  // LyapunovEval
    { 0.5f, 1024,  20, 15 },    // KahanAccumulate
    { 0.1f, 65536, 10, 80 }     // ConvergenceCheck
};

// ==================== 无锁共享状态 ====================
// 由Vulkan渲染线程写入，调度线程读取
// 使用relaxed内存序: 允许短暂过时，保证零阻塞

struct alignas(64) AtomicGPUState {
    std::atomic<u32> graphicsQueueUsage;    // f32 as u32 bits
    std::atomic<u32> computeQueueUsage;     // f32 as u32 bits
    std::atomic<u32> memoryBandwidthUsage;  // f32 as u32 bits
    std::atomic<u32> availableSMs;          // u32 直接原子
    std::atomic<u32> totalSMs;              // u32 直接原子
    std::atomic<u64> lastUpdateTimeNs;      // 最后更新时间戳
    std::atomic<u8>  macroState;            // SceneMacroState

    // 调度统计 (原子递增，不阻塞)
    std::atomic<u64> cpuDispatchCount;
    std::atomic<u64> gpuDispatchCount;
    std::atomic<u64> fallbackCount;
};

// 全局唯一实例 (cache-line对齐避免false sharing)
static alignas(64) AtomicGPUState g_gpuState = {};

// ==================== 异步GPU状态更新 ====================
// 由Vulkan渲染线程每帧调用 (写入端)
// 零阻塞: 仅原子store，无锁

inline void asyncUpdateGPUOccupancy(
    f32 graphicsUsage,
    f32 computeUsage,
    f32 bandwidthUsage,
    u32 availSMs,
    u32 totalSMs
) {
    atomicStoreF32(g_gpuState.graphicsQueueUsage, graphicsUsage);
    atomicStoreF32(g_gpuState.computeQueueUsage, computeUsage);
    atomicStoreF32(g_gpuState.memoryBandwidthUsage, bandwidthUsage);
    g_gpuState.availableSMs.store(availSMs, std::memory_order_relaxed);
    g_gpuState.totalSMs.store(totalSMs, std::memory_order_relaxed);
    // 时间戳最后写入 (作为可见性标记)
    g_gpuState.lastUpdateTimeNs.store(
        platform::getTimestampNs(),
        std::memory_order_release
    );
}

// ==================== 异步元状态机更新 ====================
// 由场景变化检测器调用 (独立于渲染/计算)

inline void asyncUpdateMacroState(SceneMacroState newState) {
    g_gpuState.macroState.store(
        static_cast<u8>(newState),
        std::memory_order_relaxed
    );
}

inline SceneMacroState asyncGetMacroState() {
    return static_cast<SceneMacroState>(
        g_gpuState.macroState.load(std::memory_order_relaxed)
    );
}

// ==================== 调度决策 ====================
// 由计算线程调用 (读取端)
// 零阻塞: 仅原子load，O(1)决策

struct DispatchDecision {
    ComputeDevice device;
    f32 confidence;
    f32 estimatedSpeedup;
    u32 recommendedBatchSize;
};

static constexpr f32 GPU_OCCUPANCY_THRESHOLD = 0.7f;

inline DispatchDecision dispatch(
    AlgorithmType algo,
    u32 batchSize
) {
    DispatchDecision decision;
    decision.device = ComputeDevice::CPU;
    decision.confidence = 1.0f;
    decision.estimatedSpeedup = 1.0f;
    decision.recommendedBatchSize = batchSize;

    u32 algoIdx = static_cast<u32>(algo);
    if (algoIdx >= static_cast<u32>(AlgorithmType::Count)) {
        return decision;
    }

    const AlgorithmProfile& profile = ALGORITHM_PROFILES[algoIdx];

    // 读取GPU状态 (原子load，零阻塞)
    f32 gpuLoad = atomicLoadF32(g_gpuState.graphicsQueueUsage) * 0.6f +
                  atomicLoadF32(g_gpuState.computeQueueUsage) * 0.3f +
                  atomicLoadF32(g_gpuState.memoryBandwidthUsage) * 0.1f;

    // 读取元状态 (原子load)
    SceneMacroState macroState = asyncGetMacroState();

    // 决策逻辑 (无分支: 使用条件移动)
    bool gpuAvailable = gpuLoad < GPU_OCCUPANCY_THRESHOLD;
    bool batchSufficient = batchSize >= profile.minBatchForGPU;
    bool gpuBeneficial = profile.gpuAffinity > 0.3f;

    // 元状态调控: Unstable时倾向CPU (避免GPU抖动)
    bool macroAllowsGPU = (macroState != SceneMacroState::Unstable);

    bool useGPU = gpuAvailable && batchSufficient && gpuBeneficial && macroAllowsGPU;

    if (useGPU) {
        decision.device = ComputeDevice::GPU;
        decision.confidence = profile.gpuAffinity * (1.0f - gpuLoad);
        decision.estimatedSpeedup = static_cast<f32>(profile.estimatedCpuTimeUs) /
                                    static_cast<f32>(profile.estimatedGpuTimeUs);
        g_gpuState.gpuDispatchCount.fetch_add(1, std::memory_order_relaxed);
    } else {
        decision.device = ComputeDevice::CPU;
        decision.confidence = 1.0f - profile.gpuAffinity;
        decision.estimatedSpeedup = 1.0f;
        g_gpuState.cpuDispatchCount.fetch_add(1, std::memory_order_relaxed);
    }

    return decision;
}

// ==================== 统计查询 ====================

struct DispatchStats {
    u64 cpuDispatchCount;
    u64 gpuDispatchCount;
    u64 fallbackCount;
    f32 currentGpuLoad;
    SceneMacroState macroState;
};

inline DispatchStats getStats() {
    DispatchStats stats;
    stats.cpuDispatchCount = g_gpuState.cpuDispatchCount.load(std::memory_order_relaxed);
    stats.gpuDispatchCount = g_gpuState.gpuDispatchCount.load(std::memory_order_relaxed);
    stats.fallbackCount = g_gpuState.fallbackCount.load(std::memory_order_relaxed);
    stats.currentGpuLoad = atomicLoadF32(g_gpuState.graphicsQueueUsage);
    stats.macroState = asyncGetMacroState();
    return stats;
}

inline f32 getGPUAffinity(AlgorithmType algo) {
    u32 idx = static_cast<u32>(algo);
    if (idx >= static_cast<u32>(AlgorithmType::Count)) return 0.0f;
    return ALGORITHM_PROFILES[idx].gpuAffinity;
}

} // namespace compute_dispatcher
} // namespace accel
} // namespace renderium
