// ============================================================
// Renderium Accelerator - 计算调度 FFI 实现
// ============================================================
// 实现 accel_compute_submit / accel_compute_poll /
//       accel_heartbeat / accel_is_degraded
//
// 线程模型:
//   - threadMode=0: 当前线程同步执行（退化路径，不创建线程）
//   - threadMode=1: 后台隔离线程执行（默认路径，创建安全线程）
// ============================================================

#include <cstring>
#include <new> // std::nothrow
#include "accel_config.h"
#include "compute_dispatcher.h"
#include "thread_safe.h"

using namespace renderium::accel;

// ==================== 全局状态 ====================

/// 上次提交的作业输出数据（保存结果）
struct PendingJob {
    void* outputBuffer = nullptr;
    int64_t outputCapacity = 0;
    int64_t resultBytes = 0;
};

static PendingJob g_pendingJob;

// ==================== 任务分配器 ====================

/// 根据 taskType 执行对应的计算（当前线程执行）
/// @return 0=成功, 负值=错误码
static int executeTask(int taskType, const void* input, int64_t inputBytes,
                       void* output, int64_t outputCapacity) {
    if (input == nullptr || output == nullptr) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }

    switch (taskType) {
    case 0: // BFS 遮挡剔除
        // 保持现有 Java GPU 路径，C++ 侧暂不重复实现
        return static_cast<int>(ErrorCode::UnsupportedOperation);

    case 1: // LOD 批量计算
        // LOD 在 cpp_accel 中已实现，由独立的 accel_lod_batchCompute 处理
        return static_cast<int>(ErrorCode::UnsupportedOperation);

    case 2: // 视锥体剔除回退（仅 CPU SIMD 路径）
        // 保留给未来 AVX2 实现
        return static_cast<int>(ErrorCode::UnsupportedOperation);

    default:
        return static_cast<int>(ErrorCode::InvalidArgument);
    }
}

// ==================== FFI 实现 ====================

extern "C" {

ACCEL_API ACCEL_CALL int accel_compute_submit(
    int32_t taskType,
    const void* inputData,
    int64_t inputBytes,
    void* outputBuffer,
    int64_t outputCapacity,
    int32_t threadMode)
{
    if (outputBuffer == nullptr || outputCapacity <= 0) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }

    // 保存输出缓冲区信息
    g_pendingJob.outputBuffer = outputBuffer;
    g_pendingJob.outputCapacity = outputCapacity;
    g_pendingJob.resultBytes = 0;

    if (threadMode == 0) {
        // 同步执行（当前线程）
        int result = executeTask(taskType, inputData, inputBytes,
                                outputBuffer, outputCapacity);
        if (result == 0) {
            g_pendingJob.resultBytes = outputCapacity;
        }
        return result;
    }

    // 异步执行（后台隔离线程）
    // 捕获输入数据副本（调用方 Arena 可能已释放）
    void* inputCopy = nullptr;
    if (inputData != nullptr && inputBytes > 0) {
        inputCopy = std::malloc(static_cast<size_t>(inputBytes));
        if (inputCopy == nullptr) {
            return static_cast<int>(ErrorCode::OutOfMemory);
        }
        std::memcpy(inputCopy, inputData, static_cast<size_t>(inputBytes));
    }

    int rc = thread_safe::executeInSafeThread(
        [taskType, inputCopy, inputBytes, outputBuffer, outputCapacity]() {
            // 在隔离线程中执行
            executeTask(taskType, inputCopy, inputBytes,
                       outputBuffer, outputCapacity);
            if (inputCopy) std::free(inputCopy);
        });

    if (rc != 0) {
        if (inputCopy) std::free(inputCopy);
    }

    return rc;
}

ACCEL_API ACCEL_CALL int accel_compute_poll(
    int32_t* outState,
    int64_t* outResultBytes)
{
    if (outState == nullptr) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }

    *outState = static_cast<int32_t>(thread_safe::taskState());
    if (outResultBytes != nullptr) {
        *outResultBytes = g_pendingJob.resultBytes;
    }

    return 0;
}

ACCEL_API ACCEL_CALL int64_t accel_heartbeat(void) {
    return static_cast<int64_t>(thread_safe::heartbeatNs());
}

ACCEL_API ACCEL_CALL int32_t accel_is_degraded(void) {
    return thread_safe::isDegraded() ? 1 : 0;
}

} // extern "C"
