// ============================================================
// Renderium Accelerator - 高精度计时器 (跨平台)
// ============================================================
// 提供纳秒级精度的单调时钟
//
// 平台实现:
//   Windows: QueryPerformanceCounter (100ns精度)
//   POSIX:  clock_gettime(CLOCK_MONOTONIC) (1ns精度)
//
// 使用场景:
//   - 算法性能测量
//   - 帧时间预算计算
//   - 超时检测
// ============================================================

#include "platform_abstraction.h"

#if defined(RENDERIUM_PLATFORM_WINDOWS)
    #ifndef WIN32_LEAN_AND_MEAN
        #define WIN32_LEAN_AND_MEAN
    #endif
    #include <windows.h>
    
    // 全局频率缓存（避免重复查询）
    static LARGE_INTEGER s_qpcFrequency = { 0 };
    static bool s_qpcInitialized = false;
    
    // 初始化QPC频率（线程安全的一次性初始化）
    static void ensureQPCInitialized() {
        if (!s_qpcInitialized) {
            QueryPerformanceFrequency(&s_qpcFrequency);
            s_qpcInitialized = true;
        }
    }
    
#elif defined(RENDERIUM_PLATFORM_LINUX) || defined(RENDERIUM_PLATFORM_MACOS)
    #include <time.h>
    #include <sys/time.h>
#endif

namespace renderium {
namespace accel {
namespace platform {

/**
 * 获取高精度时间戳（纳秒）
 * @return 从某固定点开始的纳秒数（单调递增）
 */
ACCEL_API u64 getTimestampNs() {
    #if defined(RENDERIUM_PLATFORM_WINDOWS)
        ensureQPCInitialized();
        
        LARGE_INTEGER counter;
        QueryPerformanceCounter(&counter);
        
        // 转换为纳秒: (counter * 1e9) / frequency
        // 使用分段计算避免128位整数（MSVC不支持__int128）
        u64 sec = counter.QuadPart / s_qpcFrequency.QuadPart;
        u64 rem = counter.QuadPart % s_qpcFrequency.QuadPart;
        
        u64 result = sec * 1000000000ULL + 
                     (rem * 1000000000ULL) / s_qpcFrequency.QuadPart;
        
        return result;
        
    #elif defined(RENDERIUM_PLATFORM_LINUX) || defined(RENDERIUM_PLATFORM_MACOS)
        struct timespec ts;
        
        #ifdef CLOCK_MONOTONIC_RAW
            // 如果支持RAW时钟（不受NTP调整影响），优先使用
            clock_gettime(CLOCK_MONOTONIC_RAW, &ts);
        #else
            clock_gettime(CLOCK_MONOTONIC, &ts);
        #endif
        
        // 转换为纳秒: sec * 1e9 + nsec
        return static_cast<u64>(ts.tv_sec) * 1000000000ULL + 
               static_cast<u64>(ts.tv_nsec);
        
    #else
        #error "Unsupported platform for timer"
        return 0;
    #endif
}

/**
 * 获取高精度时间戳（微秒）
 * @return 微秒时间戳
 */
ACCEL_API u64 getTimestampUs() {
    // 直接除以1000比单独实现更简单且足够精确
    return getTimestampNs() / 1000;
}

/**
 * 获取单调时钟时间（毫秒）
 * <p>
 * 不受系统时间调整影响，适合测量耗时间隔。
 * 用于帧率计算、超时检测等场景。
 *
 * @return 毫秒时间戳
 */
ACCEL_API u64 getMonotonicMs() {
    return getTimestampNs() / 1000000;
}

/**
 * 高精度睡眠（微秒级）
 * <p>
 * 在繁忙等待循环中自旋，适用于极短延迟。
 * 对于>1ms的延迟，建议使用OS原生的sleep函数。
 *
 * @param sleepUs 睡眠时长（微秒）
 */
ACCEL_API void preciseSleepUs(u32 sleepUs) {
    if (sleepUs == 0) return;
    
    const u64 start = getTimestampNs();
    const u64 target = start + (static_cast<u64>(sleepUs) * 1000);
    
    // 忙等待循环（自旋锁风格）
    while (getTimestampNs() < target) {
        // CPU提示：告诉CPU我们在忙等待
        #if defined(RENDERIUM_PLATFORM_WINDOWS)
            _mm_pause();  // SSE2 pause指令 (减少功耗)
        #elif defined(__x86_64__) || defined(__i386__)
            __asm__ volatile("pause" ::: "memory");  // x86 pause
        #elif defined(__aarch64__)
            __asm__ volatile("yield" ::: "memory");  // ARM yield
        #endif
    }
}

} // namespace platform
} // namespace accel
} // namespace renderium
