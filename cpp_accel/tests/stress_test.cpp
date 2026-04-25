// Renderium Accelerator 压力测试
// 验证极端场景下的稳定性和性能
#include "renderium/accel/ffi_export.h"
#include "renderium/accel/platform_abstraction.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <thread>
#include <atomic>

using namespace renderium::accel;

static f64 percentile(f64* data, u32 count, f64 p) {
    u32 idx = static_cast<u32>(count * p / 100.0);
    if (idx >= count) idx = count - 1;
    return data[idx];
}

static int cmpF64(const void* a, const void* b) {
    f64 da = *(const f64*)a, db = *(const f64*)b;
    return (da > db) - (da < db);
}

// 压力1: 大规模BFS (2048区块)
static void stressLargeBFS() {
    printf("\n[压力1: 大规模BFS (2048区块)]\n");
    u64 ctx = 0;
    if (accel_bfs_createContext(2048, &ctx) != 0) { printf("  createContext失败\n"); return; }

    u32 gridSize = 12;
    u32 totalSections = gridSize * gridSize * gridSize;
    if (totalSections > 2048) totalSections = 2048;

    i32* sectionData = new i32[totalSections * 4];
    for (u32 i = 0; i < totalSections; i++) {
        sectionData[i * 4 + 0] = static_cast<i32>(i % gridSize);
        sectionData[i * 4 + 1] = static_cast<i32>((i / gridSize) % gridSize);
        sectionData[i * 4 + 2] = static_cast<i32>(i / (gridSize * gridSize));
        sectionData[i * 4 + 3] = -1;
    }
    accel_bfs_initGraph(ctx, sectionData, totalSections);

    u32 iterations = 5000;
    f64* latencies = new f64[iterations];
    f32 cameraParams[] = {6.0f, 6.0f, 6.0f, 1.047f, 256.0f};
    u8 resultBuffer[16384];

    for (u32 i = 0; i < iterations; i++) {
        u64 start = platform::getTimestampNs();
        accel_bfs_findVisible(ctx, cameraParams, i, resultBuffer, sizeof(resultBuffer));
        u64 end = platform::getTimestampNs();
        latencies[i] = static_cast<f64>(end - start) / 1000.0;
    }

    qsort(latencies, iterations, sizeof(f64), cmpF64);
    f64 p50 = percentile(latencies, iterations, 50);
    f64 p95 = percentile(latencies, iterations, 95);
    f64 p99 = percentile(latencies, iterations, 99);
    f64 fps = 1000.0 / p95;
    printf("  P50=%7.3fus  P95=%7.3fus  P99=%7.3fus  FPS=%7.1f  %s\n",
           p50, p95, p99, fps, p95 <= 1.0 ? "PASS" : "ACCEPTABLE");

    delete[] sectionData;
    delete[] latencies;
    accel_bfs_destroyContext(ctx);
}

// 压力2: 多线程并发Kahan累加
static void stressConcurrentKahan() {
    printf("\n[压力2: 多线程并发Kahan累加 (4线程)]\n");
    u64 ctx = 0;
    accel_kahan_createContext(0.0, &ctx);

    u32 iterations = 10000;
    std::atomic<bool> startFlag{false};
    f64 threadLatencies[4] = {0};

    auto worker = [&](int tid) {
        while (!startFlag.load(std::memory_order_acquire)) {}
        u64 t0 = platform::getTimestampNs();
        for (u32 i = 0; i < iterations; i++) {
            accel_kahan_add(ctx, static_cast<f64>(tid) * 0.001 + static_cast<f64>(i) * 0.0001);
        }
        u64 t1 = platform::getTimestampNs();
        threadLatencies[tid] = static_cast<f64>(t1 - t0) / 1000.0;
    };

    std::thread threads[4];
    for (int i = 0; i < 4; i++) threads[i] = std::thread(worker, i);
    startFlag.store(true, std::memory_order_release);
    for (int i = 0; i < 4; i++) threads[i].join();

    f64 totalUs = 0;
    for (int i = 0; i < 4; i++) {
        printf("  Thread %d: %.1fus total, %.3fus/op\n",
               i, threadLatencies[i], threadLatencies[i] / iterations);
        totalUs += threadLatencies[i];
    }
    printf("  总吞吐: %.0f ops/s\n", 4.0 * iterations / (totalUs / 4.0) * 1000000.0);

    accel_kahan_destroyContext(ctx);
}

// 压力3: Lyapunov连续评估稳定性
static void stressLyapunovStability() {
    printf("\n[压力3: Lyapunov连续评估稳定性 (100K帧)]\n");
    u64 ctx = 0;
    accel_lyapunov_createContext(32, &ctx);

    u32 iterations = 100000;
    f64 totalUs = 0;
    f64 maxUs = 0;
    u8 outputBuffer[64];

    for (u32 i = 0; i < iterations; i++) {
        f32 frameData[6] = {
            1920.0f + static_cast<f32>(i % 100) * 0.1f,
            1080.0f,
            0.016f + static_cast<f32>(i % 10) * 0.001f,
            static_cast<f32>(i % 60),
            1.0f, 1.0f
        };

        u64 start = platform::getTimestampNs();
        accel_lyapunov_evaluate(ctx, frameData, 6, 0.0f, 0.016, outputBuffer);
        u64 end = platform::getTimestampNs();
        f64 us = static_cast<f64>(end - start) / 1000.0;
        totalUs += us;
        if (us > maxUs) maxUs = us;
    }

    printf("  平均: %.3fus/op  最大: %.3fus  吞吐: %.0f eval/s\n",
           totalUs / iterations, maxUs, iterations / (totalUs / 1000000.0));

    accel_lyapunov_destroyContext(ctx);
}

// 压力4: LOD超大批量
static void stressLargeLODBatch() {
    printf("\n[压力4: LOD超大批量 (16384区块)]\n");
    u64 ctx = 0;
    f32 distances[] = {16.0f, 32.0f, 64.0f, 128.0f};
    if (accel_lod_createContext(4, distances, 32.0f, 2.0f, &ctx) != 0) { printf("  createContext失败\n"); return; }

    u32 batchSize = 16384;
    u32 floatsPerInput = 7;
    f32* inputData = new f32[batchSize * floatsPerInput];
    u8* outputData = new u8[batchSize * 12];

    for (u32 i = 0; i < batchSize; i++) {
        inputData[i * floatsPerInput + 0] = static_cast<f32>(i % 128);
        inputData[i * floatsPerInput + 1] = static_cast<f32>((i / 128) % 128);
        inputData[i * floatsPerInput + 2] = static_cast<f32>(i / 16384);
        inputData[i * floatsPerInput + 3] = 64.0f;
        inputData[i * floatsPerInput + 4] = 64.0f;
        inputData[i * floatsPerInput + 5] = 0.0f;
        inputData[i * floatsPerInput + 6] = 1.047f;
    }

    u32 iterations = 1000;
    f64* latencies = new f64[iterations];

    for (u32 i = 0; i < iterations; i++) {
        u64 start = platform::getTimestampNs();
        accel_lod_batchCompute(ctx, inputData, outputData, batchSize);
        u64 end = platform::getTimestampNs();
        latencies[i] = static_cast<f64>(end - start) / 1000.0;
    }

    qsort(latencies, iterations, sizeof(f64), cmpF64);
    f64 p50 = percentile(latencies, iterations, 50);
    f64 p95 = percentile(latencies, iterations, 95);
    f64 p99 = percentile(latencies, iterations, 99);
    printf("  P50=%7.3fus  P95=%7.3fus  P99=%7.3fus  吞吐=%.0f chunks/s\n",
           p50, p95, p99, batchSize / (p95 / 1000000.0));

    delete[] inputData;
    delete[] outputData;
    delete[] latencies;
    accel_lod_destroyContext(ctx);
}

// 压力5: 共享内存零拷贝吞吐
static void stressSharedMemory() {
    printf("\n[压力5: 共享内存零拷贝吞吐]\n");

    void* handle = nullptr;
    const char* name = "renderium_stress_test";
    u64 size = 4 * 1024 * 1024; // 4MB

    if (accel_sharedMemory_create(name, size, 1, &handle) != 0) {
        printf("  共享内存创建失败（可能已存在，尝试打开）\n");
        if (accel_sharedMemory_create(name, size, 0, &handle) != 0) {
            printf("  打开也失败，跳过此测试\n");
            return;
        }
    }

    void* addr = nullptr;
    u64 actualSize = 0;
    if (accel_sharedMemory_getAddress(handle, &addr, &actualSize) != 0 || addr == nullptr) {
        printf("  获取地址失败\n");
        accel_sharedMemory_destroy(handle);
        return;
    }

    // 写入测试
    u32 iterations = 1000;
    u64 start = platform::getTimestampNs();
    for (u32 i = 0; i < iterations; i++) {
        memset(addr, static_cast<int>(i & 0xFF), static_cast<size_t>(actualSize > 0 ? actualSize : size));
    }
    u64 end = platform::getTimestampNs();
    f64 writeThroughput = static_cast<f64>(iterations * size) / static_cast<f64>(end - start) * 1e9 / (1024.0 * 1024.0 * 1024.0);

    printf("  大小: %llu MB  写入吞吐: %.2f GB/s\n",
           size / (1024 * 1024), writeThroughput);

    accel_sharedMemory_destroy(handle);
}

int main() {
    printf("========================================\n");
    printf("  Renderium Accelerator 压力测试\n");
    printf("========================================\n");

    accel_initialize(2);

    stressLargeBFS();
    stressConcurrentKahan();
    stressLyapunovStability();
    stressLargeLODBatch();
    stressSharedMemory();

    printf("\n========================================\n");
    printf("  压力测试完成\n");
    printf("========================================\n");

    accel_shutdown();
    return 0;
}
