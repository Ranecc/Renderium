// ============================================================
// Renderium Accelerator - 平台抽象层 (L0)
// ============================================================
// 提供 Windows/POSIX 双平台兼容的底层操作
// 避免直接使用 Windows 宏，通过运行时检测选择实现
//
// 功能:
//   - 共享内存创建/映射/销毁
//   - 原子操作（跨平台统一接口）
//   - 线程原语（互斥/信号量）
//   - 高精度计时
//   - 系统信息查询
// ============================================================

#pragma once

#include "accel_config.h"

#include <chrono>
#include <functional>
#include <string>

namespace renderium {
namespace accel {
namespace platform {

// ==================== 平台信息查询 ====================

/**
 * 获取当前平台类型（运行时检测）
 * @return PlatformType 枚举值
 */
ACCEL_API PlatformType getPlatformType();

/**
 * 获取平台名称字符串
 * @return 静态字符串（如"Windows 10", "Linux 5.4"）
 */
ACCEL_API const char* getPlatformName();

/**
 * 检查是否支持特定功能
 * @param feature 功能标识符（如"avx2", "shared_memory"）
 * @return true 如果支持
 */
ACCEL_API bool isFeatureSupported(const char* feature);

/**
 * 获取CPU核心数（逻辑核心）
 * @return 核心数，失败返回0
 */
ACCEL_API u32 getLogicalCoreCount();

/**
 * 获取物理内存大小（字节）
 * @return 内存大小，失败返回0
 */
ACCEL_API u64 getTotalMemorySize();

// ==================== 共享内存接口 ====================

/**
 * 共享内存区域描述
 */
struct SharedMemoryRegion {
    void* address;                    // 映射地址
    size_t size;                      // 区域大小（字节）
    std::string name;                 // 区域名称
    bool isOwner;                      // 是否为创建者（负责销毁）
#ifdef _WIN32
    void* nativeHandle;                // Windows: HANDLE (file mapping)
#else
    int nativeFd;                     // POSIX: 文件描述符
#endif
};

/**
 * 创建或打开共享内存区域
 *
 * @param name      区域唯一名称（跨进程可见）
 * @param size      请求大小（字节，自动页对齐）
 * @param create    true=创建新区域, false=打开已有区域
 * @param outRegion 输出: 区域描述
 * @return 操作结果
 */
ACCEL_API OperationResult createSharedMemory(
    const char* name,
    size_t size,
    bool create,
    SharedMemoryRegion& outRegion
);

/**
 * 销毁共享内存区域
 * <p>
 * 仅创建者(owner)可以销毁，其他进程只需unmap。
 *
 * @param region 区域描述
 * @return 操作结果
 */
ACCEL_API OperationResult destroySharedMemory(SharedMemoryRegion& region);

/**
 * 解除共享内存映射（不销毁）
 * <p>
 * 用于非owner进程或临时释放。
 *
 * @param region 区域描述
 * @return 操作结果
 */
ACCEL_API OperationResult unmapSharedMemory(SharedMemoryRegion& region);

// ==================== 高精度计时 ====================

/**
 * 获取高精度时间戳（纳秒）
 * @return 从某固定点开始的纳秒数
 */
ACCEL_API u64 getTimestampNs();

/**
 * 获取高精度时间戳（微秒）
 * @return 微秒时间戳
 */
ACCEL_API u64 getTimestampUs();

/**
 * 获取单调时钟时间（毫秒）
 * <p>
 * 不受系统时间调整影响，适合测量耗时。
 *
 * @return 毫秒时间戳
 */
ACCEL_API u64 getMonotonicMs();

/**
 * 精确睡眠（微秒级）
 * @param us 睡眠时间（微秒）
 */
ACCEL_API void preciseSleepUs(u32 us);

/**
 * 作用域计时器（RAII，析构时输出耗时）
 */
class ScopedTimer {
public:
    explicit ScopedTimer(const char* label)
        : m_label(label), m_startNs(getTimestampNs()) {}

    ~ScopedTimer() {
        u64 elapsed = getTimestampNs() - m_startNs;
        (void)m_label;
        (void)elapsed;
    }

    ScopedTimer(const ScopedTimer&) = delete;
    ScopedTimer& operator=(const ScopedTimer&) = delete;
private:
    const char* m_label;
    u64 m_startNs;
};

#define RENDERIUM_TIMED_BLOCK(label) \
    renderium::accel::platform::ScopedTimer _scopedTimer_##__LINE__(label)

// ==================== 原子操作 ====================

/**
 * 原子比较并交换（32位）
 * @param target 目标变量指针
 * @param expected 期望值
 * @param desired  新值
 * return true 如果交换成功
 */
ACCEL_API bool atomicCompareExchange32(
    volatile i32* target,
    i32 expected,
    i32 desired
);

/**
 * 原子比较并交换（64位）
 * @param target 目标变量指针
 * @param expected 期望值
 * @param desired  新值
 * return true 如果交换成功
 */
ACCEL_API bool atomicCompareExchange64(
    volatile i64* target,
    i64 expected,
    i64 desired
);

/**
 * 原子递增并获取旧值（32位）
 * @param target 目标变量指针
 * return 递增前的值
 */
ACCEL_API i32 atomicFetchAdd32(volatile i32* target, i32 value);

/**
 * 原子递增并获取旧值（64位）
 * @param target 目标变量指针
 * return 递增前的值
 */
ACCEL_API i64 atomicFetchAdd64(volatile i64* target, i64 value);

/**
 * 原子递减并获取旧值（32位）
 * @param target 目标变量指针
 * @param value 递减值
 * @return 递减前的值
 */
ACCEL_API i32 atomicFetchSub32(volatile i32* target, i32 value);

/**
 * 原子递减并获取旧值（64位）
 * @param target 目标变量指针
 * @param value 递减值
 * @return 递减前的值
 */
ACCEL_API i64 atomicFetchSub64(volatile i64* target, i64 value);

/**
 * 原子加载（32位）
 * @param target 目标变量指针
 * @return 当前值
 */
ACCEL_API i32 atomicLoad32(volatile i32* target);

/**
 * 原子加载（64位）
 * @param target 目标变量指针
 * @return 当前值
 */
ACCEL_API i64 atomicLoad64(volatile i64* target);

/**
 * 原子存储（32位）
 * @param target 目标变量指针
 * @param value  新值
 */
ACCEL_API void atomicStore32(volatile i32* target, i32 value);

/**
 * 原子存储（64位）
 * @param target 目标变量指针
 * @param value  新值
 */
ACCEL_API void atomicStore64(volatile i64* target, i64 value);

/**
 * 延迟原子存储（32位，不立即对其他线程可见）
 * @param target 目标变量指针
 * @param value  新值
 */
ACCEL_API void atomicLazyStore32(volatile i32* target, i32 value);

/**
 * 延迟原子存储（64位，不立即对其他线程可见）
 * @param target 目标变量指针
 * @param value  新值
 */
ACCEL_API void atomicLazyStore64(volatile i64* target, i64 value);

// ==================== 内存屏障 ====================

/**
 * 编译器屏障（防止指令重排序）
 */
ACCEL_API void compilerBarrier();

/**
 * 内存屏障（LoadLoad + LoadStore）
 * <p>
 * 确保此点之前的所有读操作完成。
 */
ACCEL_API void loadBarrier();

/**
 * 内存屏障（StoreStore + LoadStore）
 * <p>
 * 确保此点之前的所有写操作对其他线程可见。
 */
ACCEL_API void storeBarrier();

/**
 * 全屏内存屏障（最严格）
 * <p>
 * 确保所有读写操作完成且对所有线程可见。
 */
ACCEL_API void fullBarrier();

// ==================== CPU特性查询 ====================

namespace cpu_features {

/// CPU是否支持AVX2指令集
ACCEL_API bool hasAVX2();

/// CPU是否支持AVX-512指令集
ACCEL_API bool hasAVX512();

/// CPU是否支持SSE4.2指令集
ACCEL_API bool hasSSE42();

/// CPU是否支持FMA3指令集
ACCEL_API bool hasFMA3();

/// 获取L1数据缓存大小（字节）
ACCEL_API u32 getL1CacheSize();

/// 获取L2缓存大小（字节）
ACCEL_API u32 getL2CacheSize();

/// 获取L3缓存大小（字节）
ACCEL_API u32 getL3CacheSize();

/// 获取逻辑CPU核心数
ACCEL_API u32 getLogicalCoreCount();

/// 获取物理CPU核心数
ACCEL_API u32 getPhysicalCoreCount();

/// 获取推荐的SIMD向量宽度（字节）: 16(SSE)/32(AVX2)/64(AVX-512)
ACCEL_API u32 getRecommendedVectorWidth();

/// 获取CPU信息摘要字符串（调试用）
ACCEL_API const char* getCpuInfoString();

/// 获取最优批处理块大小（float，考虑缓存行）
ACCEL_API u32 getOptimalBlockSizeFloat();

/// 获取最优批处理块大小（double，考虑缓存行）
ACCEL_API u32 getOptimalBlockSizeDouble();

} // namespace cpu_features

// ==================== 线程亲和性 ====================

/**
 * 设置当前线程的CPU亲和性
 * @param coreIds 允许运行的CPU核心ID列表
 * @param count 核心数量
 * @return 操作结果
 */
ACCEL_API OperationResult setThreadAffinity(
    const u32* coreIds,
    u32 count
);

/**
 * 获取当前线程运行的CPU核心ID
 * @return 核心ID，失败返回(u32)-1
 */
ACCEL_API u32 getCurrentCoreId();

} // namespace platform
} // namespace accel
} // namespace renderium
