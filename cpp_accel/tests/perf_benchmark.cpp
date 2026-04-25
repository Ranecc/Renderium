// Renderium Accelerator 性能基准测试
// 目标: 热路径延迟 < 1ms (等效 1000+ FPS)
#include "renderium/accel/ffi_export.h"
#include "renderium/accel/platform_abstraction.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>

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

static void printResult(const char* name, f64* latencies, u32 count) {
    qsort(latencies, count, sizeof(f64), cmpF64);
    f64 p50 = percentile(latencies, count, 50);
    f64 p95 = percentile(latencies, count, 95);
    f64 p99 = percentile(latencies, count, 99);
    f64 fps = 1000.0 / p95;
    bool pass = p95 <= 1.0;
    printf("  %-45s P50=%7.3fus  P95=%7.3fus  P99=%7.3fus  FPS=%7.1f  %s\n",
           name, p50, p95, p99, fps, pass ? "PASS" : "FAIL");
}

static void benchBfsOcclusion(u32 iterations) {
    printf("\n[BFS 遮挡剔除引擎]\n");
    u64 ctx = 0;
    if (accel_bfs_createContext(512, &ctx) != 0) { printf("  BFS createContext失败\n"); return; }

    u32 gridSize = 7;
    u32 totalSections = gridSize * gridSize * gridSize;
    i32* sectionData = new i32[totalSections * 4];
    for (u32 i = 0; i < totalSections; i++) {
        sectionData[i * 4 + 0] = static_cast<i32>(i % gridSize);
        sectionData[i * 4 + 1] = static_cast<i32>((i / gridSize) % gridSize);
        sectionData[i * 4 + 2] = static_cast<i32>(i / (gridSize * gridSize));
        sectionData[i * 4 + 3] = -1; // 全方向可见
    }
    accel_bfs_initGraph(ctx, sectionData, totalSections);

    f64* latencies = new f64[iterations];
    f32 cameraParams[] = {3.5f, 3.5f, 3.5f, 1.047f, 128.0f};
    u8 resultBuffer[4096];

    for (u32 i = 0; i < 1000; i++) {
        accel_bfs_findVisible(ctx, cameraParams, i, resultBuffer, sizeof(resultBuffer));
    }

    for (u32 i = 0; i < iterations; i++) {
        u64 start = platform::getTimestampNs();
        accel_bfs_findVisible(ctx, cameraParams, i, resultBuffer, sizeof(resultBuffer));
        u64 end = platform::getTimestampNs();
        latencies[i] = static_cast<f64>(end - start) / 1000.0;
    }

    printResult("BFS findVisible (343 sections)", latencies, iterations);
    delete[] sectionData;
    delete[] latencies;
    accel_bfs_destroyContext(ctx);
}

static void benchLODCalculator(u32 iterations) {
    printf("\n[LOD 距离计算器]\n");
    u64 ctx = 0;
    f32 distances[] = {16.0f, 32.0f, 64.0f, 128.0f};
    if (accel_lod_createContext(4, distances, 32.0f, 2.0f, &ctx) != 0) { printf("  LOD createContext失败\n"); return; }

    u32 batchSize = 4096;
    u32 floatsPerInput = 7;
    f32* inputData = new f32[batchSize * floatsPerInput];
    u8* outputData = new u8[batchSize * 12];

    for (u32 i = 0; i < batchSize; i++) {
        inputData[i * floatsPerInput + 0] = static_cast<f32>(i % 64);
        inputData[i * floatsPerInput + 1] = static_cast<f32>((i / 64) % 64);
        inputData[i * floatsPerInput + 2] = static_cast<f32>(i / 4096);
        inputData[i * floatsPerInput + 3] = 32.0f;
        inputData[i * floatsPerInput + 4] = 32.0f;
        inputData[i * floatsPerInput + 5] = 0.0f;
        inputData[i * floatsPerInput + 6] = 1.047f;
    }

    f64* latencies = new f64[iterations];

    for (u32 i = 0; i < 1000; i++) {
        accel_lod_batchCompute(ctx, inputData, outputData, batchSize);
    }

    for (u32 i = 0; i < iterations; i++) {
        u64 start = platform::getTimestampNs();
        accel_lod_batchCompute(ctx, inputData, outputData, batchSize);
        u64 end = platform::getTimestampNs();
        latencies[i] = static_cast<f64>(end - start) / 1000.0;
    }

    printResult("LOD batchCompute (4096 chunks)", latencies, iterations);
    delete[] inputData;
    delete[] outputData;
    delete[] latencies;
    accel_lod_destroyContext(ctx);
}

static void benchKahanAccumulator(u32 iterations) {
    printf("\n[Kahan 高精度累加器]\n");
    u64 ctx = 0;
    accel_kahan_createContext(0.0, &ctx);

    f64* latencies = new f64[iterations];

    for (u32 i = 0; i < 1000; i++) {
        accel_kahan_add(ctx, static_cast<f64>(i) * 0.001);
    }
    accel_kahan_reset(ctx, 0.0);

    for (u32 i = 0; i < iterations; i++) {
        u64 start = platform::getTimestampNs();
        accel_kahan_add(ctx, static_cast<f64>(i) * 0.001);
        u64 end = platform::getTimestampNs();
        latencies[i] = static_cast<f64>(end - start) / 1000.0;
    }
    printResult("Kahan add (single)", latencies, iterations);

    f64 values[1024];
    for (u32 i = 0; i < 1024; i++) values[i] = static_cast<f64>(i) * 0.001;

    for (u32 i = 0; i < iterations; i++) {
        u64 start = platform::getTimestampNs();
        accel_kahan_batchAdd(ctx, values, 1024);
        u64 end = platform::getTimestampNs();
        latencies[i] = static_cast<f64>(end - start) / 1000.0;
    }
    printResult("Kahan batchAdd (1024 values)", latencies, iterations);

    delete[] latencies;
    accel_kahan_destroyContext(ctx);
}

static void benchLyapunovEvaluator(u32 iterations) {
    printf("\n[Lyapunov 质量评估器]\n");
    u64 ctx = 0;
    accel_lyapunov_createContext(32, &ctx);

    f32 frameData[6] = {1920.0f, 1080.0f, 0.016f, 1.0f, 1.0f, 1.0f};
    f64* latencies = new f64[iterations];
    u8 outputBuffer[64];

    for (u32 i = 0; i < 1000; i++) {
        accel_lyapunov_evaluate(ctx, frameData, 6, 0.0f, 0.016, outputBuffer);
    }

    for (u32 i = 0; i < iterations; i++) {
        frameData[2] = static_cast<f32>(i) * 0.00001f;
        u64 start = platform::getTimestampNs();
        accel_lyapunov_evaluate(ctx, frameData, 6, 0.0f, 0.016, outputBuffer);
        u64 end = platform::getTimestampNs();
        latencies[i] = static_cast<f64>(end - start) / 1000.0;
    }
    printResult("Lyapunov evaluate", latencies, iterations);

    delete[] latencies;
    accel_lyapunov_destroyContext(ctx);
}

static void benchConvergenceMonitor(u32 iterations) {
    printf("\n[收敛监控器]\n");
    u64 ctx = 0;
    accel_convergence_createContext(0.01f, 100, &ctx);

    f64* latencies = new f64[iterations];
    i32 converged = 0;

    for (u32 i = 0; i < 1000; i++) {
        accel_convergence_check(ctx, 0, static_cast<f32>(i) * 0.001f, 1.0f, &converged);
    }
    accel_convergence_reset(ctx);

    for (u32 i = 0; i < iterations; i++) {
        u64 start = platform::getTimestampNs();
        accel_convergence_check(ctx, 0, static_cast<f32>(i) * 0.001f, 1.0f, &converged);
        u64 end = platform::getTimestampNs();
        latencies[i] = static_cast<f64>(end - start) / 1000.0;
    }
    printResult("Convergence check (4D)", latencies, iterations);

    delete[] latencies;
    accel_convergence_destroyContext(ctx);
}

static void benchHotPathSimulation(u32 iterations) {
    printf("\n[综合热路径模拟 (等效 onFrameBegin)]\n");

    u64 bfsCtx = 0, kahanCtx = 0, convCtx = 0;
    accel_bfs_createContext(512, &bfsCtx);
    i32 sectionData[4] = {0, 0, 0, -1};
    accel_bfs_initGraph(bfsCtx, sectionData, 1);
    accel_kahan_createContext(0.0, &kahanCtx);
    accel_convergence_createContext(0.01f, 100, &convCtx);

    f64* latencies = new f64[iterations];
    i32 converged = 0;
    f32 cameraParams[] = {3.5f, 3.5f, 3.5f, 1.047f, 128.0f};
    u8 resultBuffer[4096];

    for (u32 i = 0; i < 1000; i++) {
        accel_kahan_add(kahanCtx, 0.016);
        accel_convergence_check(convCtx, 0, 0.5f, 1.0f, &converged);
        accel_bfs_findVisible(bfsCtx, cameraParams, i, resultBuffer, sizeof(resultBuffer));
    }

    for (u32 i = 0; i < iterations; i++) {
        u64 start = platform::getTimestampNs();

        accel_kahan_add(kahanCtx, 0.016);
        accel_convergence_check(convCtx, 0, 0.5f, 1.0f, &converged);
        accel_bfs_findVisible(bfsCtx, cameraParams, i, resultBuffer, sizeof(resultBuffer));

        u64 end = platform::getTimestampNs();
        latencies[i] = static_cast<f64>(end - start) / 1000.0;
    }

    printResult("Full hotpath (Kahan+Conv+BFS)", latencies, iterations);

    qsort(latencies, iterations, sizeof(f64), cmpF64);
    f64 p95 = percentile(latencies, iterations, 95);
    f64 fps = 1000.0 / p95;
    printf("\n  >>> 等效FPS (基于P95): %.1f FPS  <<<\n", fps);
    printf("  >>> 目标: 1000-2000 FPS  <<<\n");
    printf("  >>> 状态: %s  <<<\n", fps >= 1000.0 ? "PASS" : "NEEDS OPTIMIZATION");

    delete[] latencies;
    accel_kahan_destroyContext(kahanCtx);
    accel_convergence_destroyContext(convCtx);
    accel_bfs_destroyContext(bfsCtx);
}

int main() {
    printf("========================================\n");
    printf("  Renderium Accelerator 性能基准测试\n");
    printf("  目标: 热路径 P95 < 1ms (1000+ FPS)\n");
    printf("========================================\n");

    accel_initialize(2);

    u32 iterations = 10000;

    benchBfsOcclusion(iterations);
    benchLODCalculator(iterations);
    benchKahanAccumulator(iterations);
    benchLyapunovEvaluator(iterations);
    benchConvergenceMonitor(iterations);
    benchHotPathSimulation(iterations);

    printf("\n========================================\n");
    printf("  测试完成\n");
    printf("========================================\n");

    accel_shutdown();
    return 0;
}
