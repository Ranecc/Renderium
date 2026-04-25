// Renderium Accelerator - Kahan累加器 FFI 桥接
// 将FFI参数转换为内部类型并调用 kahan_accumulator 命名空间

#include "accel_config.h"
#include "compute_apis.h"
#include "ffi_export.h"

#include <cstring>

extern "C" {

ACCEL_API int ACCEL_CALL accel_kahan_createContext(
    double initialSum,
    uint64_t* outContext
) {
    using namespace renderium::accel;

    if (outContext == nullptr) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }

    ComputeContextHandle handle = 0;
    OperationResult result = kahan_accumulator::createContext(initialSum, handle);

    if (result.error == ErrorCode::Success) {
        *outContext = handle;
    }

    return static_cast<int>(result.error);
}

ACCEL_API int ACCEL_CALL accel_kahan_add(uint64_t context, double value) {
    using namespace renderium::accel;

    OperationResult result = kahan_accumulator::add(context, value);
    return static_cast<int>(result.error);
}

ACCEL_API int ACCEL_CALL accel_kahan_batchAdd(
    uint64_t context,
    const double* values,
    uint64_t count
) {
    using namespace renderium::accel;

    if (values == nullptr || count == 0) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }

    OperationResult result = kahan_accumulator::batchAdd(
        context, values, static_cast<u64>(count));
    return static_cast<int>(result.error);
}

ACCEL_API int ACCEL_CALL accel_kahan_getState(
    uint64_t context,
    void* stateBuffer
) {
    using namespace renderium::accel;

    if (stateBuffer == nullptr) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }

    kahan_accumulator::KahanState state;
    OperationResult result = kahan_accumulator::getState(context, state);

    if (result.error == ErrorCode::Success) {
        // 写入输出缓冲区: [sum(f64), compensation(f64), elementCount(u64)] = 24 bytes
        double* outF64 = reinterpret_cast<double*>(stateBuffer);
        outF64[0] = state.sum;
        outF64[1] = state.compensation;

        uint64_t* outU64 = reinterpret_cast<uint64_t*>(stateBuffer) + 2;
        outU64[0] = state.elementCount;
    }

    return static_cast<int>(result.error);
}

ACCEL_API int ACCEL_CALL accel_kahan_reset(
    uint64_t context,
    double newInitialSum
) {
    using namespace renderium::accel;

    OperationResult result = kahan_accumulator::reset(context, newInitialSum);
    return static_cast<int>(result.error);
}

ACCEL_API int ACCEL_CALL accel_kahan_destroyContext(uint64_t context) {
    using namespace renderium::accel;

    OperationResult result = kahan_accumulator::destroyContext(context);
    return static_cast<int>(result.error);
}

} // extern "C"
