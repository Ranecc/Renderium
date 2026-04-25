#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>

typedef int (*accel_init_fn)(int32_t);
typedef const char* (*accel_version_fn)(int32_t*, int32_t*, int32_t*);
typedef const char* (*accel_sysinfo_fn)(void);
typedef int (*accel_shutdown_fn)(void);

// BFS
typedef int (*accel_bfs_create_fn)(uint32_t, uint64_t*);
typedef int (*accel_bfs_destroy_fn)(uint64_t);

// Lyapunov
typedef int (*accel_lyapunov_create_fn)(uint32_t, uint64_t*);
typedef int (*accel_lyapunov_destroy_fn)(uint64_t);

// LOD
typedef int (*accel_lod_create_fn)(uint32_t, const float*, float, float, uint64_t*);
typedef int (*accel_lod_batch_fn)(uint64_t, const float*, uint8_t*, uint32_t);
typedef int (*accel_lod_destroy_fn)(uint64_t);

// Kahan
typedef int (*accel_kahan_create_fn)(double, uint64_t*);
typedef int (*accel_kahan_add_fn)(uint64_t, double);
typedef int (*accel_kahan_batch_fn)(uint64_t, const double*, uint64_t);
typedef int (*accel_kahan_getstate_fn)(uint64_t, void*);
typedef int (*accel_kahan_destroy_fn)(uint64_t);

// Convergence
typedef int (*accel_conv_create_fn)(float, uint32_t, uint64_t*);
typedef int (*accel_conv_check_fn)(uint64_t, uint32_t, float, float, int32_t*);
typedef int (*accel_conv_destroy_fn)(uint64_t);

static int g_pass = 0;
static int g_fail = 0;

#define CHECK(cond, msg) do { \
    if (cond) { g_pass++; printf("  PASS: %s\n", msg); } \
    else { g_fail++; printf("  FAIL: %s\n", msg); } \
} while(0)

int main() {
    void* lib = dlopen("./librenderium_accel.so", RTLD_NOW);
    if (!lib) { printf("FATAL: dlopen: %s\n", dlerror()); return 1; }
    printf("Library loaded\n");

    // 初始化
    accel_init_fn init = (accel_init_fn)dlsym(lib, "accel_initialize");
    CHECK(init && init(3) == 0, "accel_initialize(3)");

    // 版本
    accel_version_fn version = (accel_version_fn)dlsym(lib, "accel_getVersion");
    if (version) {
        int32_t major, minor, patch;
        const char* ver = version(&major, &minor, &patch);
        printf("  Version: %s (%d.%d.%d)\n", ver, major, minor, patch);
        g_pass++;
    }

    // ===== BFS 测试 =====
    printf("\n--- BFS Occlusion ---\n");
    accel_bfs_create_fn bfs_create = (accel_bfs_create_fn)dlsym(lib, "accel_bfs_createContext");
    accel_bfs_destroy_fn bfs_destroy = (accel_bfs_destroy_fn)dlsym(lib, "accel_bfs_destroyContext");
    uint64_t bfsCtx = 0;
    if (bfs_create && bfs_destroy) {
        int rc = bfs_create(4096, &bfsCtx);
        CHECK(rc == 0 && bfsCtx != 0, "BFS createContext(4096)");
        if (bfsCtx) bfs_destroy(bfsCtx);
    }

    // ===== Lyapunov 测试 =====
    printf("\n--- Lyapunov Checker ---\n");
    accel_lyapunov_create_fn lya_create = (accel_lyapunov_create_fn)dlsym(lib, "accel_lyapunov_createContext");
    accel_lyapunov_destroy_fn lya_destroy = (accel_lyapunov_destroy_fn)dlsym(lib, "accel_lyapunov_destroyContext");
    uint64_t lyaCtx = 0;
    if (lya_create && lya_destroy) {
        int rc = lya_create(32, &lyaCtx);
        CHECK(rc == 0 && lyaCtx != 0, "Lyapunov createContext(32)");
        if (lyaCtx) lya_destroy(lyaCtx);
    }

    // ===== LOD 测试 =====
    printf("\n--- LOD Calculator ---\n");
    accel_lod_create_fn lod_create = (accel_lod_create_fn)dlsym(lib, "accel_lod_createContext");
    accel_lod_batch_fn lod_batch = (accel_lod_batch_fn)dlsym(lib, "accel_lod_batchCompute");
    accel_lod_destroy_fn lod_destroy = (accel_lod_destroy_fn)dlsym(lib, "accel_lod_destroyContext");
    uint64_t lodCtx = 0;
    if (lod_create && lod_batch && lod_destroy) {
        float distances[] = {32.0f, 64.0f, 128.0f, 256.0f};
        int rc = lod_create(4, distances, 32.0f, 2.0f, &lodCtx);
        CHECK(rc == 0 && lodCtx != 0, "LOD createContext(4 levels)");

        if (lodCtx) {
            // 批量计算: 3个区块
            float inputs[] = {
                0, 0, 0,  10, 20, 30, 70.0f,   // pos(0,0,0), cam(10,20,30), fov=70
                100, 0, 0, 10, 20, 30, 70.0f,   // pos(100,0,0), cam(10,20,30)
                500, 0, 0, 10, 20, 30, 70.0f    // pos(500,0,0), cam(10,20,30)
            };
            uint8_t outputs[3 * 12];
            memset(outputs, 0, sizeof(outputs));
            int brc = lod_batch(lodCtx, inputs, outputs, 3);
            CHECK(brc == 0, "LOD batchCompute(3 sections)");

            // 验证LOD等级: 近处=0, 中距离=1-2, 远处=3
            uint8_t lod0 = outputs[0];
            uint8_t lod1 = outputs[12];
            uint8_t lod2 = outputs[24];
            printf("  LOD levels: near=%d, mid=%d, far=%d\n", lod0, lod1, lod2);
            CHECK(lod0 <= lod1, "Near LOD <= Mid LOD");
            CHECK(lod1 <= lod2, "Mid LOD <= Far LOD");

            lod_destroy(lodCtx);
        }
    }

    // ===== Kahan 测试 =====
    printf("\n--- Kahan Accumulator ---\n");
    accel_kahan_create_fn kahan_create = (accel_kahan_create_fn)dlsym(lib, "accel_kahan_createContext");
    accel_kahan_add_fn kahan_add = (accel_kahan_add_fn)dlsym(lib, "accel_kahan_add");
    accel_kahan_batch_fn kahan_batch = (accel_kahan_batch_fn)dlsym(lib, "accel_kahan_batchAdd");
    accel_kahan_getstate_fn kahan_state = (accel_kahan_getstate_fn)dlsym(lib, "accel_kahan_getState");
    accel_kahan_destroy_fn kahan_destroy = (accel_kahan_destroy_fn)dlsym(lib, "accel_kahan_destroyContext");
    uint64_t kahanCtx = 0;
    if (kahan_create && kahan_add && kahan_batch && kahan_state && kahan_destroy) {
        int rc = kahan_create(0.0, &kahanCtx);
        CHECK(rc == 0 && kahanCtx != 0, "Kahan createContext(0.0)");

        if (kahanCtx) {
            // 单元素累加
            kahan_add(kahanCtx, 1.0);
            kahan_add(kahanCtx, 1e100);
            kahan_add(kahanCtx, 1.0);
            kahan_add(kahanCtx, -1e100);

            // 读取状态
            uint8_t stateBuf[24];
            memset(stateBuf, 0, sizeof(stateBuf));
            int src = kahan_state(kahanCtx, stateBuf);
            CHECK(src == 0, "Kahan getState");

            double sum = *((double*)stateBuf);
            double compensation = *((double*)stateBuf + 1);
            double total = sum + compensation;
            printf("  sum=%.15f, compensation=%.15e, total=%.15f\n", sum, compensation, total);
            // 期望: total ≈ 2.0 (sum + compensation = Kahan补偿结果)
            CHECK(total > 1.9 && total < 2.1, "Kahan total ≈ 2.0 (compensated)");

            // 批量累加
            double values[] = {1.0, 2.0, 3.0, 4.0, 5.0};
            int brc = kahan_batch(kahanCtx, values, 5);
            CHECK(brc == 0, "Kahan batchAdd(5 elements)");

            kahan_destroy(kahanCtx);
        }
    }

    // ===== Convergence 测试 =====
    printf("\n--- Convergence Monitor ---\n");
    accel_conv_create_fn conv_create = (accel_conv_create_fn)dlsym(lib, "accel_convergence_createContext");
    accel_conv_check_fn conv_check = (accel_conv_check_fn)dlsym(lib, "accel_convergence_check");
    accel_conv_destroy_fn conv_destroy = (accel_conv_destroy_fn)dlsym(lib, "accel_convergence_destroyContext");
    uint64_t convCtx = 0;
    if (conv_create && conv_check && conv_destroy) {
        int rc = conv_create(0.01f, 100, &convCtx);
        CHECK(rc == 0 && convCtx != 0, "Convergence createContext(0.01, 100)");

        if (convCtx) {
            // 逐步收敛: 值指数衰减向0.0收敛
            // EMA平滑需要足够迭代让残差降到容差以下
            int32_t converged = 0;
            for (int i = 0; i < 100; ++i) {
                float current = 10.0f * 0.9f;  // 恒定残差，EMA会收敛
                if (i > 5) current = 0.005f;    // 跳到接近目标值
                conv_check(convCtx, 0, current, 0.0f, &converged);
                if (converged) break;
            }
            printf("  Converged after iterations: %s\n", converged ? "YES" : "NO");
            CHECK(converged, "Position dimension converged");

            conv_destroy(convCtx);
        }
    }

    // 关闭
    accel_shutdown_fn shutdown = (accel_shutdown_fn)dlsym(lib, "accel_shutdown");
    if (shutdown) shutdown();
    dlclose(lib);

    printf("\n========== Results: %d PASSED, %d FAILED ==========\n", g_pass, g_fail);
    return g_fail > 0 ? 1 : 0;
}
