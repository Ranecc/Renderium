// Renderium Accelerator - 收敛监控器 FFI 桥接
// 将FFI参数转换为内部类型并调用 convergence_monitor 命名空间

#include "accel_config.h"
#include "compute_apis.h"
#include "ffi_export.h"

#include <cstring>

extern "C" {

ACCEL_API int ACCEL_CALL accel_convergence_createContext(
    float tolerance,
    uint32_t maxIterations,
    uint64_t* outContext
) {
    using namespace renderium::accel;

    if (outContext == nullptr) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }

    if (tolerance <= 0.0f) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }

    ComputeContextHandle handle = 0;
    OperationResult result = convergence_monitor::createContext(
        tolerance, maxIterations, handle);

    if (result.error == ErrorCode::Success) {
        *outContext = handle;
    }

    return static_cast<int>(result.error);
}

ACCEL_API int ACCEL_CALL accel_convergence_check(
    uint64_t context,
    uint32_t dimension,
    float currentValue,
    float targetValue,
    int32_t* isConverged
) {
    using namespace renderium::accel;

    if (isConverged == nullptr) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }

    if (dimension >= 4) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }

    bool converged = false;
    OperationResult result = convergence_monitor::checkConvergence(
        context,
        static_cast<convergence_monitor::Dimension>(dimension),
        currentValue,
        targetValue,
        converged
    );

    if (result.error == ErrorCode::Success) {
        *isConverged = converged ? 1 : 0;
    }

    return static_cast<int>(result.error);
}

ACCEL_API int ACCEL_CALL accel_convergence_getState(
    uint64_t context,
    void* stateBuffer
) {
    using namespace renderium::accel;

    if (stateBuffer == nullptr) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }

    convergence_monitor::ConvergenceState state;
    OperationResult result = convergence_monitor::getConvergenceState(context, state);

    if (result.error == ErrorCode::Success) {
        // 写入输出缓冲区:
        // [isConverged(4*1), residualNorm(4*4), iterationCount(4*4), totalTimeNs(4*8)]
        // = 4 + 16 + 16 + 32 = 68 bytes, 对齐到72 bytes

        int32_t* outI32 = reinterpret_cast<int32_t*>(stateBuffer);
        for (u32 i = 0; i < 4; ++i) {
            outI32[i] = state.isConverged[i] ? 1 : 0;
        }

        float* outF32 = reinterpret_cast<float*>(stateBuffer) + 4;
        for (u32 i = 0; i < 4; ++i) {
            outF32[i] = state.residualNorm[i];
        }

        int32_t* outIter = reinterpret_cast<int32_t*>(stateBuffer) + 8;
        for (u32 i = 0; i < 4; ++i) {
            outIter[i] = static_cast<int32_t>(state.iterationCount[i]);
        }

        int64_t* outI64 = reinterpret_cast<int64_t*>(
            reinterpret_cast<uint8_t*>(stateBuffer) + 48);
        for (u32 i = 0; i < 4; ++i) {
            outI64[i] = static_cast<int64_t>(state.totalTimeNs[i]);
        }
    }

    return static_cast<int>(result.error);
}

ACCEL_API int ACCEL_CALL accel_convergence_reset(uint64_t context) {
    using namespace renderium::accel;

    OperationResult result = convergence_monitor::reset(context);
    return static_cast<int>(result.error);
}

ACCEL_API int ACCEL_CALL accel_convergence_destroyContext(uint64_t context) {
    using namespace renderium::accel;

    OperationResult result = convergence_monitor::destroyContext(context);
    return static_cast<int>(result.error);
}

} // extern "C"
