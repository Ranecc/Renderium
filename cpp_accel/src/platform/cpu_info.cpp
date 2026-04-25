// ============================================================
// Renderium Accelerator - CPU 特性检测 (跨平台)
// ============================================================
// 运行时检测CPU指令集支持和缓存配置
//
// 检测内容:
//   - SIMD指令集: SSE4.2, AVX, AVX2, AVX-512, FMA3
//   - 缓存大小: L1/L2/L3 Data Cache
//   - 核心数: 逻辑/物理
//
// 使用场景:
//   - 选择最优代码路径（AVX-512 vs AVX2 vs Scalar）
//   - 调整算法分块大小（匹配缓存行）
//   - 多线程并行度设置
// ============================================================

#include "platform_abstraction.h"

#if defined(RENDERIUM_PLATFORM_WINDOWS)
    #ifndef WIN32_LEAN_AND_MEAN
        #define WIN32_LEAN_AND_MEAN
    #endif
    #include <windows.h>
    #include <intrin.h>  // __cpuidex
    
#elif defined(RENDERIUM_PLATFORM_LINUX) || defined(RENDERIUM_PLATFORM_MACOS)
    #if defined(__x86_64__) || defined(__i386__)
        #include <cpuid.h>  // GCC/Clang x86 intrinsic
    #endif
    #include <unistd.h>
    #include <sys/sysinfo.h>
    
    #ifdef __linux__
        #include <fstream>
        #include <string>
    #endif
#endif

namespace renderium {
namespace accel {
namespace platform {
namespace cpu_features {

// ==================== CPUID 封装 ====================

/**
 * 执行CPUID指令并返回结果
 * @param func CPUID功能号
 * @param subfunc 子功能号（通常为0）
 * @param outEAX 输出: EAX寄存器
 * @param outEBX 输出: EBX寄存器
 * @param outECX 输出: ECX寄存器
 * @param outEDX 输出: EDX寄存器
 */
static void executeCpuId(
    i32 func,
    i32 subfunc,
    i32& outEAX,
    i32& outEBX,
    i32& outECX,
    i32& outEDX
) {
    #if defined(_MSC_VER) || defined(__MINGW32__)
        int regs[4];
        __cpuidex(regs, func, subfunc);
        outEAX = regs[0];
        outEBX = regs[1];
        outECX = regs[2];
        outEDX = regs[3];
    #elif defined(__GNUC__) || defined(__clang__)
        __cpuid_count(func, subfunc, outEAX, outEBX, outECX, outEDX);
    #else
        outEAX = outEBX = outECX = outEDX = 0;
    #endif
}

// ==================== SIMD 指令集检测 ====================

/** 缓存的CPU特性标志 */
static struct CpuFeaturesCache {
    bool initialized = false;
    
    bool hasSSE42  = false;
    bool hasAVX     = false;
    bool hasAVX2    = false;
    bool hasAVX512F = false;
    bool hasAVX512DQ = false;
    bool hasAVX512BW = false;
    bool hasFMA3    = false;
    
    u32 l1DataCacheSize = 32768;   // 默认32KB
    u32 l2CacheSize   = 262144;    // 默认256KB
    u32 l3CacheSize   = 8388608;   // 默认8MB
    
    u32 logicalCores  = 1;
    u32 physicalCores = 1;
} s_cache;

/**
 * 初始化CPU特性缓存（一次性调用）
 */
static void initializeCache() {
    if (s_cache.initialized) return;
    
    i32 eax, ebx, ecx, edx;
    
    // ====== 基本特性 (Func 0x01) ======
    executeCpuId(1, 0, eax, ebx, ecx, edx);
    
    s_cache.hasSSE42 = (ecx & (1 << 20)) != 0;  // bit 20: SSE4.2
    s_cache.hasFMA3  = (ecx & (1 << 12)) != 0;  // bit 12: FMA3
    
    // ====== AVX 支持 (Func 0x01, ECX bit 28) ======
    s_cache.hasAVX = (ecx & (1 << 28)) != 0;
    
    // 如果支持AVX，检查操作系统是否启用了YMM状态保存
    if (s_cache.hasAVX) {
        // 使用XGETBV检查OS支持 (跨平台兼容)
        u64 xcrFeatureMask = 0;
        #if defined(_MSC_VER) && !defined(__MINGW32__)
            xcrFeatureMask = static_cast<u64>(_xgetbv(0));
        #elif defined(__GNUC__) || defined(__clang__) || defined(__MINGW32__)
            u32 eax_reg, edx_reg;
            __asm__ volatile ("xgetbv" : "=a"(eax_reg), "=d"(edx_reg) : "c"(0));
            xcrFeatureMask = (static_cast<u64>(edx_reg) << 32) | eax_reg;
        #else
            xcrFeatureMask = 0;
        #endif
        
        // bit 1 (SSE) + bit 2 (AVX) 必须都为1
        s_cache.hasAVX = ((xcrFeatureMask & 0x6) == 0x6);
        
        // AVX-512需要bit 5 (OPMASK), bit 6 (ZMM_HI256), bit 7 (HI16_ZMM)
        if ((xcrFeatureMask & 0xE0) == 0xE0) {
            executeCpuId(7, 0, eax, ebx, ecx, edx);
            
            s_cache.hasAVX2    = (ebx & (1 << 5)) != 0;   // bit 5: AVX2
            s_cache.hasAVX512F = (ebx & (1 << 16)) != 0;  // bit 16: AVX-512 Foundation
            s_cache.hasAVX512DQ = (ebx & (1 << 17)) != 0; // bit 17: AVX-512 DQ
            s_cache.hasAVX512BW = (ebx & (1 << 30)) != 0; // bit 30: AVX-512 BW
        }
    }
    
    // ====== 缓存大小检测 (Func 0x04) ======
    for (i32 cacheType = 0; cacheType < 4; ++cacheType) {
        executeCpuId(0x04, cacheType, eax, ebx, ecx, edx);
        
        u32 cacheTypeField = (eax >> 0) & 0x1F;  // bits 0-4
        
        if (cacheTypeField == 1) {  // Data Cache
            // Ways of associativity (bits 12-21, using +1 encoding)
            u32 ways = ((ebx >> 22) & 0x3FF) + 1;
            // Line size (bits 0-11, using +1 encoding)
            u32 lineSize = (ebx & 0xFFF) + 1;
            // Sets (bits 32-31 from ECX, bits 31-0 from EDX)
            u32 sets = ecx + 1;
            
            u32 totalSize = ways * lineSize * sets;
            
            // 根据level字段确定是L1/L2/L3
            u32 level = (eax >> 5) & 0x7;  // bits 5-7
            
            switch (level) {
                case 1:
                    s_cache.l1DataCacheSize = totalSize;
                    break;
                case 2:
                    s_cache.l2CacheSize = totalSize;
                    break;
                case 3:
                    s_cache.l3CacheSize = totalSize;
                    break;
                default:
                    break;
            }
        }
    }
    
    // ====== 核心数检测 ======
    #if defined(RENDERIUM_PLATFORM_WINDOWS)
        SYSTEM_INFO sysInfo;
        GetSystemInfo(&sysInfo);
        s_cache.logicalCores = static_cast<u32>(sysInfo.dwNumberOfProcessors);
        
        // 获取物理核心数（需要额外的逻辑）
        // 简化处理：假设超线程比为2:1或1:1
        s_cache.physicalCores = (s_cache.logicalCores + 1) / 2;
        
    #elif defined(RENDERIUM_PLATFORM_LINUX)
        s_cache.logicalCores = static_cast<u32>(sysconf(_SC_NPROCESSORS_ONLN));
        
        // 从 /proc/cpuinfo 读取物理核心数
        std::ifstream cpuinfo("/proc/cpuinfo");
        std::string line;
        u32 lastPhysicalId = 0xFFFFFFFF;
        u32 physicalCount = 0;
        
        while (std::getline(cpuinfo, line)) {
            if (line.find("physical id") != std::string::npos) {
                u32 id = 0;
                auto pos = line.find_last_of(':');
                if (pos != std::string::npos) {
                    id = static_cast<u32>(std::stoi(line.substr(pos + 1)));
                    if (id != lastPhysicalId) {
                        physicalCount++;
                        lastPhysicalId = id;
                    }
                }
            }
        }
        
        s_cache.physicalCores = (physicalCount > 0) ? physicalCount : s_cache.logicalCores;
        
    #elif defined(RENDERIUM_PLATFORM_MACOS)
        s_cache.logicalCores = static_cast<u32>(sysconf(_SC_NPROCESSORS_ONLN));
        s_cache.physicalCores = s_cache.logicalCores;  // macOS简化处理
    #else
        s_cache.logicalCores = 1;
        s_cache.physicalCores = 1;
    #endif
    
    s_cache.initialized = true;
}

// ==================== 公共API实现 ====================

ACCEL_API bool hasAVX2() {
    initializeCache();
    return s_cache.hasAVX2;
}

ACCEL_API bool hasAVX512() {
    initializeCache();
    // 需要完整的AVX-512支持（F+DQ+BW）
    return s_cache.hasAVX512F && s_cache.hasAVX512DQ && s_cache.hasAVX512BW;
}

ACCEL_API bool hasSSE42() {
    initializeCache();
    return s_cache.hasSSE42;
}

ACCEL_API bool hasFMA3() {
    initializeCache();
    return s_cache.hasFMA3;
}

ACCEL_API u32 getL1CacheSize() {
    initializeCache();
    return s_cache.l1DataCacheSize;
}

ACCEL_API u32 getL2CacheSize() {
    initializeCache();
    return s_cache.l2CacheSize;
}

ACCEL_API u32 getL3CacheSize() {
    initializeCache();
    return s_cache.l3CacheSize;
}

/**
 * 获取推荐的SIMD向量宽度（字节）
 * <p>
 * 用于算法选择最优的向量化路径。
 *
 * @return 向量宽度: 16(SSE)/32(AVX)/64(AVX-512)
 */
ACCEL_API u32 getRecommendedVectorWidth() {
    initializeCache();
    
    if (hasAVX512()) return 64;
    if (hasAVX2()) return 32;
    if (hasSSE42()) return 16;
    
    return 0;  // 无SIMD，使用标量
}

/**
 * 获取推荐的算法分块大小（元素数）
 * <p>
 * 匹配L1数据缓存大小以优化缓存利用率。
 * 对于float(4字节)，返回 L1_SIZE / sizeof(float)。
 *
 * @return 推荐的分块元素数量
 */
ACCEL_API u32 getOptimalBlockSizeFloat() {
    initializeCache();
    // 使用L1缓存的75%（预留空间给栈变量和其他数据）
    return (s_cache.l1DataCacheSize * 3 / 4) / sizeof(float);
}

/**
 * 获取推荐的算法分块大小（double）
 * @see getOptimalBlockSizeFloat()
 */
ACCEL_API u32 getOptimalBlockSizeDouble() {
    initializeCache();
    return (s_cache.l1DataCacheSize * 3 / 4) / sizeof(double);
}

/**
 * 获取逻辑CPU核心数
 */
ACCEL_API u32 getLogicalCoreCount() {
    initializeCache();
    return s_cache.logicalCores;
}

/**
 * 获取物理CPU核心数
 */
ACCEL_API u32 getPhysicalCoreCount() {
    initializeCache();
    return s_cache.physicalCores;
}

/**
 * 打印CPU信息摘要（调试用）
 */
ACCEL_API const char* getCpuInfoString() {
    initializeCache();
    
    static char buffer[512];
    
    snprintf(buffer, sizeof(buffer),
        "CPU Info:\n"
        "  Cores: %u logical, %u physical\n"
        "  SIMD: SSE4.2=%c AVX=%c AVX2=%c AVX-512=%c FMA3=%c\n"
        "  Cache: L1=%uKB L2=%uKB L3=%uMB\n"
        "  Vector Width: %u bytes",
        s_cache.logicalCores,
        s_cache.physicalCores,
        s_cache.hasSSE42 ? 'Y' : 'N',
        s_cache.hasAVX ? 'Y' : 'N',
        s_cache.hasAVX2 ? 'Y' : 'N',
        hasAVX512() ? 'Y' : 'N',
        s_cache.hasFMA3 ? 'Y' : 'N',
        s_cache.l1DataCacheSize / 1024,
        s_cache.l2CacheSize / 1024,
        s_cache.l3CacheSize / (1024 * 1024),
        getRecommendedVectorWidth()
    );
    
    return buffer;
}

} // namespace cpu_features
} // namespace platform
} // namespace accel
} // namespace renderium
