// ============================================================
// Renderium Accelerator - 库初始化与FFI导出
// ============================================================
// 提供Panama FFM可调用的C接口
// 所有导出函数使用 extern "C" + ACCEL_API 宏
// 参数类型使用标准C类型（与ffi_export.h一致）
// ============================================================

#include "accel_config.h"
#include "platform_abstraction.h"
#include "compute_apis.h"
#include "ffi_export.h"

#include <cstdio>
#include <cstring>
#include <atomic>

namespace renderium {
namespace accel {

static std::atomic<bool> g_initialized{false};
static i32 g_logLevel = 0;

static constexpr u32 MAX_CONTEXTS = 64;
static void* g_contexts[MAX_CONTEXTS] = {};
static std::atomic<u32> g_contextCount{0};

#define ACCEL_LOG(level, fmt, ...) \
    do { \
        if (g_logLevel >= level) { \
            std::printf("[RenderiumAccel] " fmt "\n", ##__VA_ARGS__); \
        } \
    } while(0)

#define ACCEL_LOG0(level, msg) \
    do { \
        if (g_logLevel >= level) { \
            std::puts("[RenderiumAccel] " msg); \
        } \
    } while(0)

} // namespace accel
} // namespace renderium

// ==================== C导出函数 ====================

extern "C" {

ACCEL_API int ACCEL_CALL accel_initialize(int32_t logLevel) {
    using namespace renderium::accel;
    
    if (g_initialized.load(std::memory_order_acquire)) {
        ACCEL_LOG0(2, "Already initialized, skipping");
        return 0;
    }
    
    g_logLevel = logLevel;
    
    bool hasAVX2 = platform::cpu_features::hasAVX2();
    bool hasSSE42 = platform::cpu_features::hasSSE42();
    u32 cores = platform::cpu_features::getLogicalCoreCount();
    u32 l1Size = platform::cpu_features::getL1CacheSize();
    
    ACCEL_LOG(3, "Renderium Accelerator v%d.%d.%d initializing...",
        RENDERIUM_ACCEL_VERSION_MAJOR,
        RENDERIUM_ACCEL_VERSION_MINOR,
        RENDERIUM_ACCEL_VERSION_PATCH);
    ACCEL_LOG(3, "  CPU: %u cores, L1=%uKB, AVX2=%c, SSE4.2=%c",
        cores, l1Size / 1024,
        hasAVX2 ? 'Y' : 'N',
        hasSSE42 ? 'Y' : 'N');
    
    std::memset(g_contexts, 0, sizeof(g_contexts));
    g_contextCount.store(0, std::memory_order_relaxed);
    
    g_initialized.store(true, std::memory_order_release);
    
    ACCEL_LOG0(3, "Initialization complete");
    return 0;
}

ACCEL_API int ACCEL_CALL accel_shutdown() {
    using namespace renderium::accel;
    
    if (!g_initialized.load(std::memory_order_acquire)) {
        return 0;
    }
    
    ACCEL_LOG0(3, "Shutting down...");
    
    u32 remaining = g_contextCount.load(std::memory_order_relaxed);
    if (remaining > 0) {
        ACCEL_LOG(2, "Warning: %u contexts still alive at shutdown", remaining);
    }
    
    std::memset(g_contexts, 0, sizeof(g_contexts));
    g_contextCount.store(0, std::memory_order_relaxed);
    
    g_initialized.store(false, std::memory_order_release);
    
    return 0;
}

ACCEL_API const char* ACCEL_CALL accel_getVersion(
    int32_t* outMajor,
    int32_t* outMinor,
    int32_t* outPatch
) {
    using namespace renderium::accel;
    
    if (outMajor) *outMajor = RENDERIUM_ACCEL_VERSION_MAJOR;
    if (outMinor) *outMinor = RENDERIUM_ACCEL_VERSION_MINOR;
    if (outPatch) *outPatch = RENDERIUM_ACCEL_VERSION_PATCH;
    
    return RENDERIUM_ACCEL_VERSION_STRING;
}

ACCEL_API const char* ACCEL_CALL accel_getSystemInfo() {
    using namespace renderium::accel;
    
    static char buffer[1024];
    
    snprintf(buffer, sizeof(buffer),
        "{"
        "\"platform\":\"%s\","
        "\"cpu\":{"
            "\"cores\":%u,"
            "\"physicalCores\":%u,"
            "\"features\":[%s%s%s%s%s]"
        "},"
        "\"memory\":{\"total\":%llu},"
        "\"cache\":{"
            "\"l1\":%u,"
            "\"l2\":%u,"
            "\"l3\":%u"
        "},"
        "\"vectorWidth\":%u"
        "}",
        platform::getPlatformName(),
        platform::cpu_features::getLogicalCoreCount(),
        platform::cpu_features::getPhysicalCoreCount(),
        platform::cpu_features::hasSSE42()  ? "\"sse4.2\","  : "",
        platform::cpu_features::hasAVX2()   ? "\"avx2\","    : "",
        platform::cpu_features::hasAVX512() ? "\"avx512\","  : "",
        platform::cpu_features::hasFMA3()   ? "\"fma3\","    : "",
        "\"end\"",
        (unsigned long long)platform::getTotalMemorySize(),
        platform::cpu_features::getL1CacheSize(),
        platform::cpu_features::getL2CacheSize(),
        platform::cpu_features::getL3CacheSize(),
        platform::cpu_features::getRecommendedVectorWidth()
    );
    
    return buffer;
}

// ==================== BFS FFI ====================

ACCEL_API int ACCEL_CALL accel_bfs_createContext(
    uint32_t maxSections,
    uint64_t* outContext
) {
    using namespace renderium::accel;
    
    if (outContext == nullptr) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }
    
    ComputeContextHandle handle = 0;
    OperationResult result = bfs_occlusion::createContext(maxSections, handle);
    
    if (result.error == ErrorCode::Success) {
        *outContext = handle;
    }
    
    return static_cast<int>(result.error);
}

ACCEL_API int ACCEL_CALL accel_bfs_initGraph(
    uint64_t context,
    const int32_t* sectionData,
    uint32_t sectionCount
) {
    using namespace renderium::accel;
    
    if (sectionData == nullptr) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }
    
    std::vector<SectionCoord> coords(sectionCount);
    for (u32 i = 0; i < sectionCount; ++i) {
        coords[i].x = sectionData[i * 3 + 0];
        coords[i].y = sectionData[i * 3 + 1];
        coords[i].z = sectionData[i * 3 + 2];
    }
    
    OperationResult result = bfs_occlusion::initializeGraph(
        context, coords.data(), sectionCount);
    
    return static_cast<int>(result.error);
}

ACCEL_API int ACCEL_CALL accel_bfs_setNeighbors(
    uint64_t context,
    uint32_t sectionIndex,
    const uint32_t* neighborData,
    uint32_t neighborCount
) {
    using namespace renderium::accel;
    
    OperationResult result = bfs_occlusion::setNeighbors(
        context, sectionIndex, neighborData, neighborCount);
    
    return static_cast<int>(result.error);
}

ACCEL_API int ACCEL_CALL accel_bfs_findVisible(
    uint64_t context,
    const float* cameraParams,
    uint32_t frameNumber,
    void* resultBuffer,
    uint64_t bufferSize
) {
    using namespace renderium::accel;
    
    if (cameraParams == nullptr || resultBuffer == nullptr) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }
    
    bfs_occlusion::CameraView camera;
    camera.eyeX = cameraParams[0];
    camera.eyeY = cameraParams[1];
    camera.eyeZ = cameraParams[2];
    camera.lookX = cameraParams[3];
    camera.lookY = cameraParams[4];
    camera.lookZ = cameraParams[5];
    camera.fov = cameraParams[6];
    camera.renderDistance = cameraParams[7];
    
    OperationResult result = bfs_occlusion::findVisibleSections(
        context, camera, frameNumber);
    
    if (result.error != ErrorCode::Success) {
        return static_cast<int>(result.error);
    }
    
    bfs_occlusion::VisibilityResult* visResult = 
        reinterpret_cast<bfs_occlusion::VisibilityResult*>(resultBuffer);
    
    result = bfs_occlusion::getResult(context, visResult, static_cast<size_t>(bufferSize));
    
    return static_cast<int>(result.error);
}

ACCEL_API int ACCEL_CALL accel_bfs_destroyContext(uint64_t context) {
    using namespace renderium::accel;
    
    OperationResult result = bfs_occlusion::destroyContext(context);
    return static_cast<int>(result.error);
}

// ==================== Lyapunov FFI ====================

ACCEL_API int ACCEL_CALL accel_lyapunov_createContext(
    uint32_t windowSize,
    uint64_t* outContext
) {
    using namespace renderium::accel;
    
    if (outContext == nullptr) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }
    
    ComputeContextHandle handle = 0;
    OperationResult result = lyapunov_checker::createContext(windowSize, handle);
    
    if (result.error == ErrorCode::Success) {
        *outContext = handle;
    }
    
    return static_cast<int>(result.error);
}

ACCEL_API int ACCEL_CALL accel_lyapunov_evaluate(
    uint64_t context,
    const float* frameData,
    uint64_t dataSize,
    float previousLyapunov,
    double deltaTime,
    void* outputBuffer
) {
    using namespace renderium::accel;
    
    if (frameData == nullptr || outputBuffer == nullptr) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }
    
    lyapunov_checker::QualityInput input;
    input.frameData = frameData;
    input.dataSize = static_cast<size_t>(dataSize);
    input.previousLyapunov = previousLyapunov;
    input.deltaTime = deltaTime;
    
    lyapunov_checker::QualityOutput output;
    
    OperationResult result = lyapunov_checker::evaluateFrame(context, input, output);
    
    if (result.error == ErrorCode::Success) {
        float* outF32 = reinterpret_cast<float*>(outputBuffer);
        outF32[0] = output.lyapunovExponent;
        outF32[1] = output.qualityScore;
        
        int32_t* outI32 = reinterpret_cast<int32_t*>(outputBuffer) + 2;
        outI32[0] = output.isStable ? 1 : 0;
        
        uint32_t* outU32 = reinterpret_cast<uint32_t*>(outputBuffer) + 3;
        outU32[0] = output.degradationLevel;
    }
    
    return static_cast<int>(result.error);
}

ACCEL_API int ACCEL_CALL accel_lyapunov_reset(uint64_t context) {
    using namespace renderium::accel;
    
    OperationResult result = lyapunov_checker::reset(context);
    return static_cast<int>(result.error);
}

ACCEL_API int ACCEL_CALL accel_lyapunov_destroyContext(uint64_t context) {
    using namespace renderium::accel;
    
    OperationResult result = lyapunov_checker::destroyContext(context);
    return static_cast<int>(result.error);
}

// ==================== 共享内存 FFI ====================

ACCEL_API int ACCEL_CALL accel_sharedMemory_create(
    const char* name,
    uint64_t sizeBytes,
    int32_t createExclusive,
    void** outHandle
) {
    using namespace renderium::accel;
    
    if (name == nullptr || outHandle == nullptr) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }
    
    platform::SharedMemoryRegion region;
    OperationResult result = platform::createSharedMemory(
        name, static_cast<size_t>(sizeBytes), createExclusive != 0, region);
    
    if (result.error == ErrorCode::Success) {
        *outHandle = region.address;
    }
    
    return static_cast<int>(result.error);
}

ACCEL_API int ACCEL_CALL accel_sharedMemory_getAddress(
    void* handle,
    void** outAddress,
    uint64_t* outSize
) {
    using namespace renderium::accel;
    
    if (handle == nullptr || outAddress == nullptr) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }
    
    *outAddress = handle;
    if (outSize) *outSize = 0;
    
    return 0;
}

ACCEL_API int ACCEL_CALL accel_sharedMemory_destroy(void* handle) {
    using namespace renderium::accel;
    
    if (handle == nullptr) {
        return static_cast<int>(ErrorCode::InvalidArgument);
    }
    
    platform::SharedMemoryRegion region;
    region.address = handle;
    
    OperationResult result = platform::unmapSharedMemory(region);
    return static_cast<int>(result.error);
}

} // extern "C"
