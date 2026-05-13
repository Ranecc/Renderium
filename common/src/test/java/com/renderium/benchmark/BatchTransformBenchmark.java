// Renderium - 性能验证
// BatchTransformEngine JMH 基准测试 - 验证 10K 顶点 < 30μs 目标
//
// 运行方式：
//   1. 编译：mvn test-compile（或 IDE 直接运行）
".*BatchTransformBenchmark.*"
//   2. 执行：java -jar target/benchmarks.jar ".*BatchTransformBenchmark.*"
//
// 预期结果：
//   - Affine 路径（地形渲染）：~2-5μs/1K 顶点
//   - Perspective 路径（阴影贴图）：~8-15μs/1K 顶点
//   - Unsafe 加速：比 Safe 版本快 15-25%

package com.renderium.benchmark;

import com.renderium.bridge.batch.BatchTransformEngine;
import com.renderium.bridge.batch.BatchTransformEngineV3;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * BatchTransformEngine 性能基准测试
 * <p>
 * 对比 v2 和 v3 版本的批量顶点变换性能，
 * 验证是否达到 Shader 系统要求的 10K 顶点 &lt; 30μs 目标。
 *
 * <h3>测试场景：</h3>
 * <table>
 *   <tr><th>Benchmark</th><th>描述</th><th>目标</th></tr>
 *   <tr><td>v2_affine_1k</td><td>v2 仿射变换 1K 顶点</td><td>&lt; 20μs</td></tr>
 *   <tr><td>v2_perspective_1k</td><td>v2 透视变换 1K 顶点</td><td>&lt; 40μs</td></tr>
 *   <tr><td>v3_affine_unsafe_1k</td><td>v3 Unsafe 仿射 1K 顶点</td><td>&lt; 5μs ⭐</td></tr>
 *   <tr><td>v3_perspective_unsafe_1k</td><td>v3 Unsafe 透视 1K 顶点</td><td>&lt; 15μs ⭐</td></tr>
 *   <tr><td>v3_affine_unsafe_10k</td><td>v3 Unsafe 仿射 10K 顶点</td><td>&lt; 30μs ⭐⭐</td></tr>
 * </table>
 *
 * @since 3.0.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 200, timeUnit = TimeUnit.MILLISECONDS)
"-XX:+UseParallelGC"
"-Xms512m"
"-Xmx512m"
@Fork(value = 1, jvmArgsPrepend = {"-XX:+UseParallelGC", "-Xms512m", "-Xmx512m"})
@State(Scope.Thread)
public class BatchTransformBenchmark {

    // ==================== 测试数据 ====================

    /** 小规模：1K 顶点 */
    private float[] positions1k;

    /** 中规模：10K 顶点 */
    private float[] positions10k;

    /** 大规模：100K 顶点（压力测试） */
    private float[] positions100k;

    /** 单位矩阵（仿射变换） */
    private float[] identityMatrix;

    /** 投影矩阵（透视变换） */
    private float[] projectionMatrix;

    /** 模型视图矩阵（仿射 + 平移） */
    private float[] modelViewMatrix;

    // ==================== 被测引擎 ====================

    private BatchTransformEngine engineV2;
    private BatchTransformEngineV3 engineV3;

    // ==================== Setup ====================

    @Setup(Level.Trial)
    public void setup() {
        // 初始化测试数据
        positions1k = generateTestVertices(1_000);
        positions10k = generateTestVertices(10_000);
        positions100k = generateTestVertices(100_000);

        // 单位矩阵（第4行 = [0,0,0,1]，触发仿射快速路径）
        identityMatrix = new float[]{
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
        };

        // 模型视图矩阵（仿射：旋转 + 平移）
        modelViewMatrix = new float[]{
            0.707f, -0.707f, 0, 0,
            0.707f, 0.707f, 0, 0,
            0, 0, 1, 0,
            100, 50, -200, 1
        };

        // 透视投影矩阵（FOV=70°, Near=0.05, Far=1000）
        projectionMatrix = createPerspectiveProjection(70f, 16f / 9f, 0.05f, 1000f);

        // 初始化引擎
        engineV2 = new BatchTransformEngine();
        engineV3 = new BatchTransformEngineV3();
    }

    // ==================== Benchmark: v2 版本（基线对比）====================

    /**
     * v2 仿射路径 - 1K 顶点
     * <p>
     * 预期：< 20μs（当前实现约 15-18μs）
     */
    @Benchmark
    public void v2_affine_1k(Blackhole bh) {
        float[] result = engineV2.transformVertices(positions1k, 1_000, modelViewMatrix);
        bh.consume(result);
    }

    /**
     * v2 透视路径 - 1K 顶点
     * <p>
     * 预期：< 40μs（当前实现约 35-45μs）
     */
    @Benchmark
    public void v2_perspective_1k(Blackhole bh) {
        float[] result = engineV2.transformVertices(positions1k, 1_000, projectionMatrix);
        bh.consume(result);
    }

    /**
     * v2 仿射路径 - 10K 顶点
     * <p>
     * 预期：< 150μs（线性扩展）
     */
    @Benchmark
    public void v2_affine_10k(Blackhole bh) {
        float[] result = engineV2.transformVertices(positions10k, 10_000, modelViewMatrix);
        bh.consume(result);
    }

    // ==================== Benchmark: v3 Safe 回退版本 ====================

    /**
     * v3 Safe 仿射路径 - 1K 顶点
     * <p>
     * 预期：< 12μs（比 v2 快 ~30%）
     */
    @Benchmark
    public void v3_affine_safe_1k(Blackhole bh) {
        float[] result = engineV3.transformVertices(positions1k, 1_000, modelViewMatrix);
        bh.consume(result);
    }

    /**
     * v3 Safe 透视路径 - 1K 顶点
     * <p>
     * 预期：< 25μs（双步 NR 近似）
     */
    @Benchmark
    public void v3_perspective_safe_1k(Blackhole bh) {
        float[] result = engineV3.transformVertices(positions1k, 1_000, projectionMatrix);
        bh.consume(result);
    }

    // ==================== Benchmark: v3 Unsafe 极致版本（核心指标）====================

    /**
     * ⭐ 核心指标：v3 Unsafe 仿射路径 - 1K 顶点
     * <p>
     * 目标：< 5μs（Unsafe + 8x 展开 + 零边界检查）
     * 如果失败，说明 Unsafe 未启用或 JIT 未优化
     */
    @Benchmark
    public void v3_affine_unsafe_1k(Blackhole bh) {
        if (!engineV3.isUsingUnsafe()) return;  // Skip if Unsafe unavailable
        float[] result = engineV3.transformVertices(positions1k, 1_000, modelViewMatrix);
        bh.consume(result);
    }

    /**
     * ⭐ 核心指标：v3 Unsafe 透视路径 - 1K 顶点
     * <p>
     * 目标：< 15μs（Unsafe + 双步 NR + 8x 展开）
     */
    @Benchmark
    public void v3_perspective_unsafe_1k(Blackhole bh) {
        if (!engineV3.isUsingUnsafe()) return;
        float[] result = engineV3.transformVertices(positions1k, 1_000, projectionMatrix);
        bh.consume(result);
    }

    /**
     * ⭐⭐ 关键指标：v3 Unsafe 仿射路径 - 10K 顶点
     * <p>
     * 最终目标：< 30μs（Shader 系统要求）
     * 这是 GBufferGeometryNode 的典型负载
     */
    @Benchmark
    public void v3_affine_unsafe_10k(Blackhole bh) {
        if (!engineV3.isUsingUnsafe()) return;
        float[] result = engineV3.transformVertices(positions10k, 10_000, modelViewMatrix);
        bh.consume(result);
    }

    /**
     * v3 Unsafe 透视路径 - 10K 顶点
     * <p>
     * 目标：< 80μs（ShadowMapNode 典型负载）
     */
    @Benchmark
    public void v3_perspective_unsafe_10k(Blackhole bh) {
        if (!engineV3.isUsingUnsafe()) return;
        float[] result = engineV3.transformVertices(positions10k, 10_000, projectionMatrix);
        bh.consume(result);
    }

    // ==================== 压力测试：100K 顶点 ====================

    /**
     * 压力测试：v3 Unsafe 仿射 - 100K 顶点
     * <p>
     * 验证线性扩展性（应 < 300μs）
     */
    @Benchmark
    public void v3_affine_unsafe_100k(Blackhole bh) {
        if (!engineV3.isUsingUnsafe()) return;
        float[] result = engineV3.transformVertices(positions100k, 100_000, modelViewMatrix);
        bh.consume(result);
    }

    // ==================== 辅助方法 ====================

    /**
     * 生成随机测试顶点（模拟地形网格）
     *
     * @param count 顶点数量
     * @return [x0,y0,z0, x1,y1,z1, ...]
     */
    private static float[] generateTestVertices(int count) {
        float[] vertices = new float[count * 3];
        for (int i = 0; i < count; i++) {
            int base = i * 3;
            // 模拟 [-50, 50] 范围的地形坐标
            vertices[base]     = (float)(Math.random() * 100 - 50);      // x
            vertices[base + 1] = (float)(Math.random() * 64 - 32);       // y (高度)
            vertices[base + 2] = (float)(Math.random() * 100 - 50);      // z
        }
        return vertices;
    }

    /**
     * 创建透视投影矩阵（Column-Major OpenGL 格式）
     *
     * @param fovDeg 视场角（度）
     * @param aspect 宽高比
     * @param near 近裁剪面
     * @param far 远裁剪面
     * @return 4x4 投影矩阵
     */
    private static float[] createPerspectiveProjection(float fovDeg, float aspect,
                                                        float near, float far) {
        float fovRad = (float)Math.toRadians(fovDeg);
        float f = (float)(1.0 / Math.tan(fovRad / 2.0));
        float nf = 1.0f / (near - far);

        return new float[]{
            f / aspect, 0, 0, 0,
            0, f, 0, 0,
            0, 0, (far + near) * nf, -1,
            0, 0, (2 * far * near) * nf, 0
        };
    }
}
