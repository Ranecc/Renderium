// ============================================================
// Renderium Accelerator - 纯计算加速库 (v1.0)
// ============================================================
// 定位: 高性能数值计算引擎（无GPU依赖）
// 职责: 加速Java端的热点算法（BFS/LOD/矩阵运算等）
//
// 架构设计:
//   - L0: 平台抽象层 (Windows/POSIX 兼容)
//   - L1: 数学基础库 (向量/矩阵/四元数)
//   - L2: 核心算法 (BFS/LOD/Lyapunov/Kahan)
//   - L3: FFI接口层 (Panama FFM 导出函数)
//   - L4: 共享内存通信 (零拷贝数据交换)
//
// 编译要求:
//   - C++17 标准
//   - 无外部依赖（纯标准库实现）
//   - Windows: MSVC 19.x / MinGW-w64
//   - Linux: GCC 9+ / Clang 10+
//   - macOS: Clang 10+ / Xcode 12+
//
// 使用方式:
//   Java → Panama FFM → C共享库 → 共享内存 → 零拷贝返回
// ============================================================

#pragma once

// ==================== 版本信息 ====================

#define RENDERIUM_ACCEL_VERSION_MAJOR 1
#define RENDERIUM_ACCEL_VERSION_MINOR 0
#define RENDERIUM_ACCEL_VERSION_PATCH 0
#define RENDERIUM_ACCEL_VERSION_STRING "1.0.0"

// ==================== 平台检测 ====================
// 避免使用Windows宏（_WIN32, _MSC_VER等在源文件中使用）
// 此处仅定义特性宏，用于条件编译

#if defined(__cplusplus) && __cplusplus >= 201703L
    #define ACCEL_CXX17_SUPPORTED 1
#else
    #define ACCEL_CXX17_SUPPORTED 0
#endif

// 平台类型枚举（运行时检测）
enum class PlatformType {
    Windows,
    Linux,
    MacOS,
    Unknown
};

// ==================== 导出宏定义 ====================
// 统一的符号导出机制（跨平台）

#ifdef _WIN32
    #ifdef RENDERIUM_ACCEL_EXPORTS
        #define ACCEL_API __declspec(dllexport)
    #else
        #define ACCEL_API __declspec(dllimport)
    #endif
    #define ACCEL_CALL __cdecl
#elif __GNUC__ >= 4
    #define ACCEL_API __attribute__((visibility("default")))
    #define ACCEL_CALL
#else
    #define ACCEL_API
    #define ACCEL_CALL
#endif

// ==================== 基础类型定义 ====================
// 确保跨平台一致性

#include <cstdint>
#include <cstddef>

namespace renderium {
namespace accel {

// ==================== 固定宽度类型别名 ====================

using i8  = int8_t;
using i16 = int16_t;
using i32 = int32_t;
using i64 = int64_t;

using u8  = uint8_t;
using u16 = uint16_t;
using u32 = uint32_t;
using u64 = uint64_t;

using f32 = float;
using f64 = double;

// ==================== 句柄类型 ====================

/// 共享内存区域句柄（不透明指针）
using SharedMemoryHandle = void*;

/// 计算上下文句柄（状态保持）
using ComputeContextHandle = u64;

/// 计算结果句柄（输出数据）
using ResultBufferHandle = u64;

// ==================== 错误码定义 ====================

enum class ErrorCode : i32 {
    Success = 0,                    // 操作成功
    InvalidArgument = -1,           // 无效参数
    OutOfMemory = -2,               // 内存不足
    NotInitialized = -3,            // 未初始化
    AlreadyExists = -4,             // 已存在
    NotFound = -5,                  // 未找到
    PlatformError = -6,            // 平台错误
    UnsupportedOperation = -7,     // 不支持的操作
    Timeout = -8,                   // 超时
    InternalError = -99             // 内部错误
};

// ==================== 结果结构体 ====================

/**
 * 通用操作结果
 * <p>
 * 所有导出API统一使用此结构返回结果，
 * 避免异常跨语言边界传递。
 */
struct OperationResult {
    ErrorCode error;                // 错误码
    i32 detailedCode;               // 详细错误码（平台相关）
    const char* errorMessage;       // 错误消息（UTF-8，静态字符串）
};

// ==================== 内存布局常量 ====================

namespace MemoryLayout {

/// 共享内存对齐要求（Cache Line大小）
constexpr size_t CACHE_LINE_SIZE = 64;

/// SIMD向量对齐（AVX2: 32字节）
constexpr size_t SIMD_ALIGN = 32;

/// 默认页大小（大多数系统）
constexpr size_t PAGE_SIZE = 4096;

/// 最大共享内存区域数
constexpr u32 MAX_SHARED_REGIONS = 16;

} // namespace MemoryLayout

// ==================== 计算配置 ====================

namespace ComputeConfig {

/// BFS遮挡剔除默认参数
namespace BfsOcclusion {
    constexpr u32 MAX_SECTIONS = 8192;          // 最大区块数
    constexpr u32 MAX_VISIBLE_SECTIONS = 4096;  // 最大可见区块数
    constexpr u32 DIRECTION_COUNT = 6;           // 方向数（六向）
    constexpr f32 DEFAULT_FOV = 70.0f;           // 默认视野角度
}

/// LOD计算默认参数
namespace LOD {
    constexpr u32 MAX_LOD_LEVELS = 8;            // 最大LOD等级
    constexpr f32 DEFAULT_MAX_DISTANCE = 2048.0f; // 默认最大距离
}

/// 数值计算精度
namespace Precision {
    constexpr f32 KAHAN_EPSILON = 1e-7f;         // Kahan累加器阈值
    constexpr f64 LYAPUNOV_DT = 0.001;           // Lyapunov时间步长
    constexpr i32 CONVERGENCE_MAX_ITER = 100;     // 收敛最大迭代次数
}

} // namespace ComputeConfig

} // namespace accel
} // namespace renderium
