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
//   1. VulkanAPIRegistry.invoke("accel_xxx", args)
//   2. 函数指针由 NativeLibraryLoader 加载
//   3. 调用时传入 MemorySegment (堆外内存)
//
// 模块列表:
//   L0: 平台抽象 (共享内存/计时/原子操作)
//   L1: CPU计算 (BFS/LOD/Lyapunov/Kahan/收敛监控)
//   L2: 计算调度 (submit/poll/heartbeat/degraded)
//   L3: 线程安全 (崩溃隔离)
// ============================================================

#pragma once

#include <stdint.h>

#ifdef _WIN32
    #ifdef RENDERIUM_ACCEL_EXPORTS
        #define ACCEL_API __declspec(dllexport)
    #else
        #define ACCEL_API __declspec(dllimport)
    #endif
    #define ACCEL_CALL
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

// ==================== 计算调度 (L2: 线程池 + 崩溃隔离) ====================
//
// 设计:
//   accel_compute_submit   — 提交计算作业到后台线程（非阻塞）
//   accel_compute_poll     — 轮询作业状态 (IDLE/RUNNING/DONE/FAILED)
//   accel_heartbeat        — 返回心跳时间戳 (ns)，超时500ms判定线程挂死
//   accel_is_degraded      — 查询是否发生过崩溃
//
// 崩溃隔离:
//   C++ 后台线程安装 SIGSEGV/SIGABRT 信号处理，
//   触发时通过 sigsetjmp/siglongjmp 恢复，
//   设置 degraded=true 后线程退出。
//   JVM 不受影响（信号在线程级处理）。

/** 调度状态枚举 */
#define ACCEL_COMPUTE_IDLE      0
#define ACCEL_COMPUTE_RUNNING   1
#define ACCEL_COMPUTE_DONE      2
#define ACCEL_COMPUTE_FAILED    3

/**
 * 提交计算作业到后台线程。
 * @param taskType    计算类型 (0=BFS, 1=LOD, ...)
 * @param inputData   输入数据指针
 * @param inputBytes  输入数据大小
 * @param outputBuffer 输出缓冲区指针
 * @param outputCapacity 输出缓冲区容量
 * @param threadMode  0=当前线程同步执行, 1=后台线程异步执行
 * @return 0=成功, 负值=错误码
 */
ACCEL_API ACCEL_CALL int accel_compute_submit(
    int32_t taskType,
    const void* inputData,
    int64_t inputBytes,
    void* outputBuffer,
    int64_t outputCapacity,
    int32_t threadMode
);

/**
 * 轮询最后一次提交的作业状态。
 * @param outState     输出: 当前状态 (ACCEL_COMPUTE_*)
 * @param outResultBytes 输出: 结果数据字节数
 * @return 0=成功, 负值=错误码
 */
ACCEL_API ACCEL_CALL int accel_compute_poll(
    int32_t* outState,
    int64_t* outResultBytes
);

/**
 * 返回后台线程心跳时间戳（纳秒级）。
 * Java 侧轮询间隔 100ms，超时 500ms 判定线程挂死。
 * @return 最近一次心跳的纳秒时间戳，0 表示线程未启动
 */
ACCEL_API ACCEL_CALL int64_t accel_heartbeat(void);

/**
 * 查询计算系统是否已降级（发生过崩溃）。
 * @return 0=正常, 1=已降级
 */
ACCEL_API ACCEL_CALL int32_t accel_is_degraded(void);

#ifdef __cplusplus
}
#endif
