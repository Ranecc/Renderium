// ============================================================
// Renderium Accelerator - 跨平台原子操作
// ============================================================
// 统一的原子操作接口，屏蔽平台差异
//
// 实现策略:
//   - MSVC:  Interlocked* 系列函数
//   - GCC/Clang: __atomic_* builtins (C++11 std::atomic底层)
//
// 注意: 此文件提供C风格接口供FFI层使用
//       C++内部代码应优先使用 std::atomic<T>
// ============================================================

#include "platform_abstraction.h"

#if defined(RENDERIUM_PLATFORM_WINDOWS)
    #ifndef WIN32_LEAN_AND_MEAN
        #define WIN32_LEAN_AND_MEAN
    #endif
    #include <windows.h>
    #include <intrin.h>
#elif defined(RENDERIUM_PLATFORM_LINUX)
    #include <sched.h>
    #include <pthread.h>
#endif

#include <atomic>

namespace renderium {
namespace accel {
namespace platform {

// ==================== 比较并交换 (CAS) ====================

ACCEL_API bool atomicCompareExchange32(
    volatile i32* target,
    i32 expected,
    i32 desired
) {
    // 使用C++11 std::atomic确保可移植性
    auto* atomicTarget = reinterpret_cast<volatile std::atomic<i32>*>(target);
    return atomicTarget->compare_exchange_strong(expected, desired,
        std::memory_order_acq_rel, std::memory_order_relaxed);
}

ACCEL_API bool atomicCompareExchange64(
    volatile i64* target,
    i64 expected,
    i64 desired
) {
    auto* atomicTarget = reinterpret_cast<volatile std::atomic<i64>*>(target);
    return atomicTarget->compare_exchange_strong(expected, desired,
        std::memory_order_acq_rel, std::memory_order_relaxed);
}

// ==================== 原子算术操作 ====================

ACCEL_API i32 atomicFetchAdd32(volatile i32* target, i32 value) {
    auto* atomicTarget = reinterpret_cast<volatile std::atomic<i32>*>(target);
    return atomicTarget->fetch_add(value, std::memory_order_acq_rel);
}

ACCEL_API i64 atomicFetchAdd64(volatile i64* target, i64 value) {
    auto* atomicTarget = reinterpret_cast<volatile std::atomic<i64>*>(target);
    return atomicTarget->fetch_add(value, std::memory_order_acq_rel);
}

ACCEL_API i32 atomicFetchSub32(volatile i32* target, i32 value) {
    auto* atomicTarget = reinterpret_cast<volatile std::atomic<i32>*>(target);
    return atomicTarget->fetch_sub(value, std::memory_order_acq_rel);
}

ACCEL_API i64 atomicFetchSub64(volatile i64* target, i64 value) {
    auto* atomicTarget = reinterpret_cast<volatile std::atomic<i64>*>(target);
    return atomicTarget->fetch_sub(value, std::memory_order_acq_rel);
}

// ==================== 原子读写 ====================

ACCEL_API i32 atomicLoad32(const volatile i32* target) {
    auto* atomicTarget = reinterpret_cast<const volatile std::atomic<i32>*>(target);
    return atomicTarget->load(std::memory_order_acquire);
}

ACCEL_API i64 atomicLoad64(const volatile i64* target) {
    auto* atomicTarget = reinterpret_cast<const volatile std::atomic<i64>*>(target);
    return atomicTarget->load(std::memory_order_acquire);
}

ACCEL_API void atomicStore32(volatile i32* target, i32 value) {
    auto* atomicTarget = reinterpret_cast<volatile std::atomic<i32>*>(target);
    atomicTarget->store(value, std::memory_order_release);
}

ACCEL_API void atomicStore64(volatile i64* target, i64 value) {
    auto* atomicTarget = reinterpret_cast<volatile std::atomic<i64>*>(target);
    atomicTarget->store(value, std::memory_order_release);
}

/**
 * 延迟写入（减少内存屏障开销）
 * <p>
 * 仅保证最终可见性，不保证其他线程立即看到。
 * 适用于统计计数器等非关键路径。
 */
ACCEL_API void atomicLazyStore32(volatile i32* target, i32 value) {
    auto* atomicTarget = reinterpret_cast<volatile std::atomic<i32>*>(target);
    atomicTarget->store(value, std::memory_order_relaxed);
}

ACCEL_API void atomicLazyStore64(volatile i64* target, i64 value) {
    auto* atomicTarget = reinterpret_cast<volatile std::atomic<i64>*>(target);
    atomicTarget->store(value, std::memory_order_relaxed);
}

// ==================== 内存屏障 ====================

ACCEL_API void compilerBarrier() {
    std::atomic_signal_fence(std::memory_order_seq_cst);
}

ACCEL_API void loadBarrier() {
    std::atomic_thread_fence(std::memory_order_acquire);
}

ACCEL_API void storeBarrier() {
    std::atomic_thread_fence(std::memory_order_release);
}

ACCEL_API void fullBarrier() {
    std::atomic_thread_fence(std::memory_order_seq_cst);
}

// ==================== 线程亲和性 ====================

ACCEL_API OperationResult setThreadAffinity(
    const u32* coreIds,
    u32 count
) {
    if (coreIds == nullptr || count == 0) {
        return {ErrorCode::InvalidArgument, 0, "Invalid core IDs"};
    }
    
    #if defined(RENDERIUM_PLATFORM_WINDOWS)
        DWORD_PTR mask = 0;
        for (u32 i = 0; i < count && i < 64; ++i) {
            mask |= (1ULL << coreIds[i]);
        }
        
        DWORD_PTR result = SetThreadAffinityMask(GetCurrentThread(), mask);
        if (result == 0) {
            return {ErrorCode::PlatformError, 
                    static_cast<i32>(GetLastError()),
                    "SetThreadAffinityMask failed"};
        }
        
    #elif defined(RENDERIUM_PLATFORM_LINUX)
        cpu_set_t cpuset;
        CPU_ZERO(&cpuset);
        
        for (u32 i = 0; i < count; ++i) {
            CPU_SET(static_cast<int>(coreIds[i]), &cpuset);
        }
        
        int ret = sched_setaffinity(0, sizeof(cpuset), &cpuset);
        if (ret != 0) {
            return {ErrorCode::PlatformError, errno, "sched_setaffinity failed"};
        }
        
    #elif defined(RENDERIUM_PLATFORM_MACOS)
        // macOS不直接支持线程亲和性
        // 使用 tag_affinity 作为提示（非强制）
        // 这里简化处理，直接返回成功
    #endif
    
    return {ErrorCode::Success, 0, nullptr};
}

ACCEL_API u32 getCurrentCoreId() {
    #if defined(RENDERIUM_PLATFORM_WINDOWS)
        return static_cast<u32>(GetCurrentProcessorNumber());
    #elif defined(RENDERIUM_PLATFORM_LINUX)
        return static_cast<u32>(sched_getcpu());
    #elif defined(RENDERIUM_PLATFORM_MACOS)
        // macOS没有直接的API，使用线程ID近似
        return static_cast<u32>(pthread_mach_thread_np(pthread_self()));
    #else
        return static_cast<u32>(-1);
    #endif
}

} // namespace platform
} // namespace accel
} // namespace renderium
