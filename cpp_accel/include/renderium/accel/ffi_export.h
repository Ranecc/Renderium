// ============================================================
// Renderium Accelerator - FFI 导出层 (L3)
// ============================================================
// 提供 Panama FFM (Foreign Function & Memory) 可调用的C接口
//
// 设计原则:
//   1. 所有导出函数使用 C linkage (extern "C")
//   2. 避免使用 STL类型作为参数/返回值
//   3. 错误通过返回值传递，不抛异常
//   4. 内存管理明确：谁分配，谁释放
//   5. 支持零拷贝共享内存通信
//   6. 参数类型使用标准C类型（兼容C和C++编译器）
//
// Java端调用模式:
//   1. MethodHandle lookup = linker.defaultLookup().find("accel_xxx")
//   2. FunctionDescriptor = "(参数)返回值"
//   3. 调用时传入 MemorySegment (堆外内存)
// ============================================================

#pragma once

#include <cstdint>

// 导出宏定义（与accel_config.h一致，但此头文件可独立使用）
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

#ifdef __cplusplus
extern "C" {
#endif

// ==================== 库初始化与关闭 ====================

ACCEL_API ACCEL_CALL int accel_initialize(int32_t logLevel);

ACCEL_API ACCEL_CALL int accel_shutdown();

ACCEL_API ACCEL_CALL const char* accel_getVersion(
    int32_t* outMajor,
    int32_t* outMinor,
    int32_t* outPatch
);

ACCEL_API ACCEL_CALL const char* accel_getSystemInfo();

// ==================== 共享内存管理 ====================

ACCEL_API ACCEL_CALL int accel_sharedMemory_create(
    const char* name,
    uint64_t sizeBytes,
    int32_t createExclusive,
    void** outHandle
);

ACCEL_API ACCEL_CALL int accel_sharedMemory_getAddress(
    void* handle,
    void** outAddress,
    uint64_t* outSize
);

ACCEL_API ACCEL_CALL int accel_sharedMemory_destroy(void* handle);

// ==================== BFS 遮挡剔除 (FFI接口) ====================

ACCEL_API ACCEL_CALL int accel_bfs_createContext(
    uint32_t maxSections,
    uint64_t* outContext
);

ACCEL_API ACCEL_CALL int accel_bfs_initGraph(
    uint64_t context,
    const int32_t* sectionData,
    uint32_t sectionCount
);

ACCEL_API ACCEL_CALL int accel_bfs_setNeighbors(
    uint64_t context,
    uint32_t sectionIndex,
    const uint32_t* neighborData,
    uint32_t neighborCount
);

ACCEL_API ACCEL_CALL int accel_bfs_findVisible(
    uint64_t context,
    const float* cameraParams,
    uint32_t frameNumber,
    void* resultBuffer,
    uint64_t bufferSize
);

ACCEL_API ACCEL_CALL int accel_bfs_destroyContext(uint64_t context);

// ==================== LOD 计算 (FFI接口) ====================

ACCEL_API ACCEL_CALL int accel_lod_createContext(
    uint32_t maxLevels,
    const float* distances,
    float baseDistance,
    float falloffFactor,
    uint64_t* outContext
);

ACCEL_API ACCEL_CALL int accel_lod_batchCompute(
    uint64_t context,
    const float* inputData,
    uint8_t* outputData,
    uint32_t count
);

ACCEL_API ACCEL_CALL int accel_lod_updateThresholds(
    uint64_t context,
    const float* newThresholds,
    uint32_t count
);

ACCEL_API ACCEL_CALL int accel_lod_destroyContext(uint64_t context);

// ==================== Lyapunov 质量评估 (FFI接口) ====================

ACCEL_API ACCEL_CALL int accel_lyapunov_createContext(
    uint32_t windowSize,
    uint64_t* outContext
);

ACCEL_API ACCEL_CALL int accel_lyapunov_evaluate(
    uint64_t context,
    const float* frameData,
    uint64_t dataSize,
    float previousLyapunov,
    double deltaTime,
    void* outputBuffer
);

ACCEL_API ACCEL_CALL int accel_lyapunov_reset(uint64_t context);

ACCEL_API ACCEL_CALL int accel_lyapunov_destroyContext(uint64_t context);

// ==================== Kahan 累加器 (FFI接口) ====================

ACCEL_API ACCEL_CALL int accel_kahan_createContext(
    double initialSum,
    uint64_t* outContext
);

ACCEL_API ACCEL_CALL int accel_kahan_add(uint64_t context, double value);

ACCEL_API ACCEL_CALL int accel_kahan_batchAdd(
    uint64_t context,
    const double* values,
    uint64_t count
);

ACCEL_API ACCEL_CALL int accel_kahan_getState(
    uint64_t context,
    void* stateBuffer
);

ACCEL_API ACCEL_CALL int accel_kahan_reset(
    uint64_t context,
    double newInitialSum
);

ACCEL_API ACCEL_CALL int accel_kahan_destroyContext(uint64_t context);

// ==================== 收敛监控器 (FFI接口) ====================

ACCEL_API ACCEL_CALL int accel_convergence_createContext(
    float tolerance,
    uint32_t maxIterations,
    uint64_t* outContext
);

ACCEL_API ACCEL_CALL int accel_convergence_check(
    uint64_t context,
    uint32_t dimension,
    float currentValue,
    float targetValue,
    int32_t* isConverged
);

ACCEL_API ACCEL_CALL int accel_convergence_getState(
    uint64_t context,
    void* stateBuffer
);

ACCEL_API ACCEL_CALL int accel_convergence_reset(uint64_t context);

ACCEL_API ACCEL_CALL int accel_convergence_destroyContext(uint64_t context);

#ifdef __cplusplus
}
#endif
