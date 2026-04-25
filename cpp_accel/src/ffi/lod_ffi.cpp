// Renderium Accelerator - LOD计算器 FFI 桥接
// 将FFI参数转换为内部类型并调用 lod_calculator 命名空间

#include "accel_config.h"
#include "compute_apis.h"
#include "ffi_export.h"

#include <cstring>

extern "C" {

ACCEL_API int ACCEL_CALL accel_lod_createContext(
    uint32_t maxLevels,
    const float* distances,
    float baseDistance,
    float falloffFactor,
    uint64_t* outContext
) {
    using namespace renderium::accel;

    if (outContext == nullptr) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }

    if (maxLevels == 0 || maxLevels > 8) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }

    lod_calculator::LODConfig config;
    config.maxLevels = maxLevels;
    config.baseDistance = baseDistance;
    config.falloffFactor = falloffFactor;

    std::memset(config.distances, 0, sizeof(config.distances));
    if (distances != nullptr) {
        u32 copyCount = (maxLevels > 8) ? 8 : maxLevels;
        std::memcpy(config.distances, distances, copyCount * sizeof(f32));
    } else {
        // 默认距离阈值: 指数递增
        f32 dist = baseDistance;
        for (u32 i = 0; i < maxLevels; ++i) {
            config.distances[i] = dist;
            dist *= falloffFactor;
        }
    }

    ComputeContextHandle handle = 0;
    OperationResult result = lod_calculator::createContext(config, handle);

    if (result.error == ErrorCode::Success) {
        *outContext = handle;
    }

    return static_cast<int>(result.error);
}

ACCEL_API int ACCEL_CALL accel_lod_batchCompute(
    uint64_t context,
    const float* inputData,
    uint8_t* outputData,
    uint32_t count
) {
    using namespace renderium::accel;

    if (inputData == nullptr || outputData == nullptr || count == 0) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }

    // inputData布局: 每个区块7个float [posX, posY, posZ, camX, camY, camZ, fov]
    // outputData布局: 每个区块6字节 [lodLevel(u8), pad(u8), distance(f32)]
    //                  实际用 lod_calculator::LODOutput 结构体

    u32 inputStride = 7;

    std::vector<lod_calculator::LODInput> inputs(count);
    std::vector<lod_calculator::LODOutput> outputs(count);

    for (u32 i = 0; i < count; ++i) {
        const float* in = inputData + i * inputStride;
        inputs[i].position.x = static_cast<i32>(in[0]);
        inputs[i].position.y = static_cast<i32>(in[1]);
        inputs[i].position.z = static_cast<i32>(in[2]);
        inputs[i].cameraX = in[3];
        inputs[i].cameraY = in[4];
        inputs[i].cameraZ = in[5];
        inputs[i].fov = in[6];
    }

    OperationResult result = lod_calculator::batchComputeLOD(
        context, inputs.data(), outputs.data(), count);

    if (result.error == ErrorCode::Success) {
        for (u32 i = 0; i < count; ++i) {
            // 写入紧凑输出格式: [lodLevel(1), distance(4), screenCoverage(4)] = 9 bytes
            // 对齐到4字节: [lodLevel(1), pad(3), distance(4), screenCoverage(4)] = 12 bytes
            outputData[i * 12 + 0] = outputs[i].lodLevel;
            outputData[i * 12 + 1] = 0;
            outputData[i * 12 + 2] = 0;
            outputData[i * 12 + 3] = 0;
            std::memcpy(&outputData[i * 12 + 4], &outputs[i].distance, sizeof(f32));
            std::memcpy(&outputData[i * 12 + 8], &outputs[i].screenCoverage, sizeof(f32));
        }
    }

    return static_cast<int>(result.error);
}

ACCEL_API int ACCEL_CALL accel_lod_updateThresholds(
    uint64_t context,
    const float* newThresholds,
    uint32_t count
) {
    using namespace renderium::accel;

    if (newThresholds == nullptr || count == 0) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }

    OperationResult result = lod_calculator::updateThresholds(context, newThresholds, count);
    return static_cast<int>(result.error);
}

ACCEL_API int ACCEL_CALL accel_lod_destroyContext(uint64_t context) {
    using namespace renderium::accel;

    OperationResult result = lod_calculator::destroyContext(context);
    return static_cast<int>(result.error);
}

} // extern "C"
