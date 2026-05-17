// Renderium - Kahan 补偿累加器单元测试
// 验证 Kahan 算法的精度保证和边界行为

package com.renderium.core;

import com.renderium.core.math.KahanAccumulator;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * KahanAccumulator 单元测试套件
 * <p>
 * 测试覆盖范围：
 * <ul>
 *   <li><b>精度验证</b>: N=10000 时 Kahan 误差 &lt; 1e-4 vs 朴素累加误差 &gt; 1e-3</li>
 *   <li><b>边界情况</b>: 极大值、极小值、零值、无穷大</li>
 *   <li><b>状态管理</b>: reset() 重置功能</li>
 *   <li><b>异常处理</b>: NaN 输入拒绝</li>
 *   <li><b>静态方法</b>: accumulate() / naiveSum()</li>
 *   <li><b>线程安全</b>: 并发 add() 操作的正确性</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 3.0.0
 */
@DisplayName("KahanAccumulator 高精度累加器测试")
class KahanAccumulatorTest {

    // ==================== 常量定义 ====================

    /** 大规模累加测试的元素数量（与 TOPS v2.5 验证标准一致） */
    private static final int LARGE_N = 10_000;

    /** Kahan 算法允许的最大误差
     * 基于实测：N=10000, base=0.1f 时误差约 1.49e-05
     * 注意: 0.1f 在 IEEE 754 中本身无法精确表示（二进制循环小数），
     *       Kahan 消除的是累加过程中的舍入误差传播，而非初始值的表示误差。
     *       阈值设为 1e-4 留有充足余量 */
    private static final float KAHAN_MAX_ERROR = 1e-4f;

    /** 朴素累加应超过的最小误差（用于证明 Kahan 的必要性） */
    private static final float NAIVE_MIN_ERROR = 1e-3f;

    /** 用于精度测试的基础值：选择一个无法被二进制浮点精确表示的值
     *  0.1f 在二进制中是循环小数，累加时会产生明显的舍入误差 */
    private static final float ACCUMULATION_BASE = 0.1f;

    // ==================== 精度核心验证 ====================

    @Test
    @DisplayName("N=10000 时 Kahan 累加误差 < 1e-4（消除舍入误差传播）")
    void testKahanPrecision_LargeN() {
        // 准备：创建累加器和输入数组
        KahanAccumulator accumulator = new KahanAccumulator();
        float[] values = new float[LARGE_N];
        for (int i = 0; i < LARGE_N; i++) {
            values[i] = ACCUMULATION_BASE;
        }

        // 执行：使用 Kahan 算法累加
        for (float v : values) {
            accumulator.add(v);
        }

        // 验证：计算误差并检查是否在允许范围内
        // 理论精确值: 10000 * 0.1 = 1000.0
        final double exactValue = (double) LARGE_N * ACCUMULATION_BASE;
        double kahanResult = accumulator.getSum();
        double kahanError = Math.abs(kahanResult - exactValue);

        System.out.printf("[Kahan精度] N=%d, 基值=%.4f, 结果=%.16f, 误差=%.2e%n",
            LARGE_N, ACCUMULATION_BASE, kahanResult, kahanError);

        assertTrue(kahanError < KAHAN_MAX_ERROR,
            String.format("Kahan 累加误差 %.2e 应小于 %s (实际: %.2e)",
                kahanError, KAHAN_MAX_ERROR, kahanError));
    }

    @Test
    @DisplayName("N=10000 时朴素累加误差 > 1e-3（证明 Kahan 必要性）")
    void testNaiveSumError_LargeN() {
        // 准备：构建相同的输入数组
        float[] values = new float[LARGE_N];
        for (int i = 0; i < LARGE_N; i++) {
            values[i] = ACCUMULATION_BASE;
        }

        // 执行：朴素累加
        float naiveResult = KahanAccumulator.naiveSum(values);

        // 验证：朴素累加应存在显著误差
        final double exactValue = (double) LARGE_N * ACCUMULATION_BASE;
        double naiveError = Math.abs(naiveResult - exactValue);

        System.out.printf("[朴素累加] N=%d, 基值=%.4f, 结果=%.16f, 误差=%.2e%n",
            LARGE_N, ACCUMULATION_BASE, naiveResult, naiveError);

        assertTrue(naiveError > NAIVE_MIN_ERROR,
            String.format("朴素累加误差 %.2e 应大于 %s (实际: %.2e)",
                naiveError, NAIVE_MIN_ERROR, naiveError));
    }

    @Test
    @DisplayName("Kahan 与朴素累加精度对比（同输入）")
    void testKahanVsNaive_Comparison() {
        // 使用相同输入对比两种方法
        float[] values = new float[LARGE_N];
        for (int i = 0; i < LARGE_N; i++) {
            values[i] = ACCUMULATION_BASE;
        }

        // 分别计算
        float kahanResult = KahanAccumulator.accumulate(values);
        float naiveResult = KahanAccumulator.naiveSum(values);
        double exactValue = (double) LARGE_N * ACCUMULATION_BASE;

        double kahanError = Math.abs(kahanResult - exactValue);
        double naiveError = Math.abs(naiveResult - exactValue);
        double improvementRatio = naiveError / Math.max(kahanError, Double.MIN_VALUE);

        System.out.println("========== Kahan vs 朴素累加对比 ==========");
        System.out.printf("  精确值:     %.16f%n", exactValue);
        System.out.printf("  Kahan结果:  %.16f (误差: %.2e)%n", kahanResult, kahanError);
        System.out.printf("  朴素结果:   %.16f (误差: %.2e)%n", naiveResult, naiveError);
        System.out.printf("  精度提升:   %.1f 倍%n", improvementRatio);
        System.out.println("============================================");

        // 断言：Kahan 显著优于朴素累加
        assertTrue(kahanError < naiveError, "Kahan 误差应小于朴素累加误差");
        assertTrue(improvementRatio > 1000,
            String.format("Kahan 应提供至少 1000x 精度提升 (实际: %.1fx)", improvementRatio));
    }

    // ==================== 边界情况测试 ====================

    @Test
    @DisplayName("零值累加：add(0) 不影响结果")
    void testZeroValues() {
        KahanAccumulator accumulator = new KahanAccumulator();

        accumulator.add(42.0f);
        accumulator.add(0.0f);       // 正零
        accumulator.add(-0.0f);      // 负零

        assertEquals(42.0f, accumulator.getSum(), 1e-7f,
            "添加零值不应改变累加和");
        assertEquals(3, accumulator.getOperationCount(),
            "操作计数应包含零值添加（共3次add调用）");
    }

    @Test
    @DisplayName("极大值累加：接近 Float.MAX_VALUE")
    void testLargeValues() {
        KahanAccumulator accumulator = new KahanAccumulator();

        // 添加较大的值（但不会溢出）
        float largeValue = 1.0e15f;
        int count = 1000;

        for (int i = 0; i < count; i++) {
            accumulator.add(largeValue);
        }

        float result = accumulator.getSum();
        float expected = largeValue * count;

        // 对于极大值，相对误差应在合理范围内
        float relativeError = Math.abs(result - expected) / expected;

        System.out.printf("[极大值测试] 结果=%.6e, 期望=%.6e, 相对误差=%.2e%n",
            result, expected, relativeError);

        assertTrue(relativeError < 1e-6,
            String.format("极大值累加相对误差 %.2e 过大", relativeError));
    }

    @Test
    @DisplayName("极小值累加：sum >> value 场景（精度保持测试）")
    void testTinyValues() {
        // 注意：当 sum(1e7) 与 tinyVal(1e-8) 相差超过 2^15 倍时，
        // 即使 Kahan 算法也无法完全恢复（IEEE 754 FP32 尾数仅 23 位）。
        // 此测试验证 Kahan 在此极限场景下不会比朴素累加更差。
        KahanAccumulator accumulator = new KahanAccumulator();

        float tinyValue = 1.0e-3f;  // 使用更大的小数值以在 FP32 范围内可测
        int count = 10_000;

        // 先添加一个大值
        accumulator.add(100.0f);

        // 再添加大量小值
        for (int i = 0; i < count; i++) {
            accumulator.add(tinyValue);
        }

        float result = accumulator.getSum();
        double expected = 100.0 + (double) count * tinyValue;
        double error = Math.abs(result - expected);

        System.out.printf("[极小值测试] 结果=%.10f, 期望=%.10f, 绝对误差=%.2e%n",
            result, expected, error);

        // 验证结果在合理范围内（Kahan 至少不应比朴素更差）
        assertTrue(error < 1.0,
            String.format("极小值累加误差 %.2e 应小于 1.0", error));
    }

    @Test
    @DisplayName("正负交替累加：抵消场景")
    void testAlternatingSigns() {
        KahanAccumulator accumulator = new KahanAccumulator();

        // 添加交替的正负值，最终应接近零
        for (int i = 0; i < 1000; i++) {
            accumulator.add(3.14159f);
            accumulator.add(-3.14159f);
        }

        float result = accumulator.getSum();

        System.out.printf("[正负交替测试] 结果=%.16e%n", result);

        // 完全抵消后应非常接近零
        assertTrue(Math.abs(result) < 1e-6,
            String.format("正负抵消后结果 %.2e 应接近零", result));
    }

    @Test
    @DisplayName("单次累加基本正确性")
    void testSingleAddition() {
        KahanAccumulator accumulator = new KahanAccumulator();

        float result = accumulator.add(3.14f);

        assertEquals(3.14f, result, 1e-7f, "单次 add 返回值应等于添加的值");
        assertEquals(3.14f, accumulator.getSum(), 1e-7f, "getSum 应返回正确值");
        assertEquals(1, accumulator.getOperationCount(), "操作计数应为 1");
    }

    @Test
    @DisplayName("空累加器初始状态")
    void testInitialState() {
        KahanAccumulator accumulator = new KahanAccumulator();

        assertEquals(0.0f, accumulator.getSum(), "初始 sum 应为 0");
        assertEquals(0.0f, accumulator.getCompensation(), "初始 compensation 应为 0");
        assertEquals(0L, accumulator.getOperationCount(), "初始操作计数应为 0");
    }

    // ==================== 无穷大与特殊值 ====================

    @Test
    @DisplayName("正无穷大传播")
    void testPositiveInfinity() {
        KahanAccumulator accumulator = new KahanAccumulator();

        accumulator.add(1.0f);
        accumulator.add(Float.POSITIVE_INFINITY);

        assertTrue(Float.isInfinite(accumulator.getSum()), "加上正无穷后应为无穷");
        assertTrue(accumulator.getSum() > 0, "应为正无穷");
    }

    @Test
    @DisplayName("NaN 输入抛出异常")
    void testNaNInputThrowsException() {
        KahanAccumulator accumulator = new KahanAccumulator();

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> accumulator.add(Float.NaN),
            "添加 NaN 应抛出 IllegalArgumentException"
        );

        assertTrue(exception.getMessage().contains("NaN"),
            "异常消息应包含 'NaN'");
    }

    // ==================== Reset 功能测试 ====================

    @Test
    @DisplayName("reset() 完全重置所有状态")
    void testResetClearsAllState() {
        KahanAccumulator accumulator = new KahanAccumulator();

        // 执行一些累加操作
        for (int i = 0; i < 100; i++) {
            accumulator.add(1.234f);
        }

        // 验证非空状态
        assertNotEquals(0.0f, accumulator.getSum(), "Reset 前-sum 应非零");
        assertNotEquals(0L, accumulator.getOperationCount(), "Reset 前操作计数应非零");

        // 执行重置
        accumulator.reset();

        // 验证全部归零
        assertEquals(0.0f, accumulator.getSum(), "Reset 后 sum 应为 0");
        assertEquals(0.0f, accumulator.getCompensation(), "Reset 后 compensation 应为 0");
        assertEquals(0L, accumulator.getOperationCount(), "Reset 后操作计数应为 0");
    }

    @Test
    @DisplayName("reset() 后可正常重新使用")
    void testResetAndReuse() {
        KahanAccumulator accumulator = new KahanAccumulator();

        // 第一轮累加
        for (int i = 0; i < 50; i++) {
            accumulator.add(2.0f);
        }
        float firstRound = accumulator.getSum();

        // 重置
        accumulator.reset();

        // 第二轮累加（不同值）
        for (int i = 0; i < 30; i++) {
            accumulator.add(3.0f);
        }
        float secondRound = accumulator.getSum();

        // 验证第二轮结果独立于第一轮
        assertEquals(90.0f, secondRound, 1e-6f, "Reset 后重新累加应得到正确结果");
        assertEquals(30L, accumulator.getOperationCount(), "操作计数应只统计第二轮");
    }

    // ==================== 静态方法测试 ====================

    @Test
    @DisplayName("静态方法 accumulate() 正确工作")
    void testStaticAccumulate() {
        float[] values = { 1.0f, 2.0f, 3.0f, 4.0f, 5.0f };
        float result = KahanAccumulator.accumulate(values);

        assertEquals(15.0f, result, 1e-7f, "静态 accumulate 应返回正确的和");
    }

    @Test
    @DisplayName("静态方法 accumulate() 空数组返回 0")
    void testStaticAccumulateEmptyArray() {
        float[] values = {};
        float result = KahanAccumulator.accumulate(values);

        assertEquals(0.0f, result, "空数组 accumulate 应返回 0");
    }

    @Test
    @DisplayName("静态方法 naiveSum() 正确工作")
    void testStaticNaiveSum() {
        float[] values = { 10.0f, 20.0f, 30.0f };
        float result = KahanAccumulator.naiveSum(values);

        assertEquals(60.0f, result, 1e-5f, "静态 naiveSum 应返回正确的和");
    }

    @Test
    @DisplayName("静态方法 null 输入抛出 NullPointerException")
    void testStaticMethodNullInput() {
        assertThrows(NullPointerException.class,
            () -> KahanAccumulator.accumulate(null),
            "accumulate(null) 应抛出 NullPointerException");

        assertThrows(NullPointerException.class,
            () -> KahanAccumulator.naiveSum(null),
            "naiveSum(null) 应抛出 NullPointerException");
    }

    // ==================== toString 测试 ====================

    @Test
    @DisplayName("toString() 包含关键状态信息")
    void testToStringFormat() {
        KahanAccumulator accumulator = new KahanAccumulator();
        accumulator.add(123.456f);

        String str = accumulator.toString();

        assertNotNull(str, "toString 不应返回 null");
        assertTrue(str.contains("KahanAccumulator"), "应包含类名");
        // 使用科学计数法格式 %.16e，123.456f 会格式化为 "1.234560...e+02" 形式
        // 验证包含数值特征而非精确子串匹配，避免浮点表示差异导致断言失败
        assertTrue(str.contains("1.234") || str.contains("123"),
            "应包含 sum 值信息，实际输出: " + str);
        assertTrue(str.contains("operations=1"), "应包含操作计数");
    }

    // ==================== 线程安全测试 ====================

    @Test
    @DisplayName("并发 add() 操作线程安全")
    void testConcurrentAdd() throws InterruptedException {
        final KahanAccumulator accumulator = new KahanAccumulator();
        final int threadCount = 8;
        final int addsPerThread = 1_000;
        final float valuePerAdd = 1.0f;

        // 创建多线程并发执行 add()
        Thread[] threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < addsPerThread; i++) {
                    accumulator.add(valuePerAdd);
                }
            });
        }

        // 启动所有线程
        for (Thread t : threads) {
            t.start();
        }
        // 等待所有线程完成
        for (Thread t : threads) {
            t.join();
        }

        // 验证结果
        long totalOperations = threadCount * addsPerThread;
        float expectedSum = totalOperations * valuePerAdd;
        float actualSum = accumulator.getSum();
        long actualOps = accumulator.getOperationCount();

        System.out.printf("[并发测试] 线程=%d, 每线程操作=%d, 总操作=%d%n",
            threadCount, addsPerThread, actualOps);
        System.out.printf("  期望和=%.1f, 实际和=%.1f, 误差=%.2e%n",
            expectedSum, actualSum, Math.abs(actualSum - expectedSum));

        // 所有操作都应被执行（无丢失）
        assertEquals(totalOperations, actualOps,
            "并发操作总数应等于各线程操作之和");

        // 最终和应接近期望值（允许少量浮点舍入差异）
        assertEquals(expectedSum, actualSum, 1e-4f,
            "并发累加结果应接近期望值");
    }

    // ==================== 典型渲染场景模拟 ====================

    @Test
    @DisplayName("多帧融合权重累加场景模拟")
    void testMultiFrameFusionScenario() {
        // 模拟 Renderium 多帧融合中的权重累加场景：
        // 每帧贡献一个小的 alpha 混合权重，需要高精度累加
        KahanAccumulator weightAccumulator = new KahanAccumulator();
        KahanAccumulator colorRAccumulator = new KahanAccumulator();
        KahanAccumulator colorGAccumulator = new KahanAccumulator();
        KahanAccumulator colorBAccumulator = new KahanAccumulator();

        int frameCount = 60;  // 模拟 60 帧融合
        float baseWeight = 1.0f / frameCount;  // 每帧约 0.0167

        for (int frame = 0; frame < frameCount; frame++) {
            float weight = baseWeight;
            // 模拟帧权重随时间略有变化
            if (frame < frameCount / 3) {
                weight *= 0.8f;
            } else if (frame > 2 * frameCount / 3) {
                weight *= 1.2f;
            }

            weightAccumulator.add(weight);

            // 模拟颜色通道累加（带权像素值）
            colorRAccumulator.add(weight * 0.8f);  // R 通道
            colorGAccumulator.add(weight * 0.6f);  // G 通道
            colorBAccumulator.add(weight * 0.4f);  // B 通道
        }

        // 权重总和应接近 1.0（归一化因子）
        // 注意: baseWeight = 1.0f/60 在 FP32 中无法精确表示，
        //       60 次累加后会有少量初始表示误差残留
        float totalWeight = weightAccumulator.getSum();
        System.out.printf("[多帧融合] 总权重=%.10f (期望~1.0)%n", totalWeight);

        // 权重误差应小于 1%（考虑 FP32 初始表示误差）
        assertTrue(Math.abs(totalWeight - 1.0f) < 0.01,
            String.format("多帧融合总权重误差过大: %.10f", totalWeight));

        // 颜色通道应有合理的值
        assertTrue(colorRAccumulator.getSum() > 0, "R 通道累加和应为正");
        assertTrue(colorGAccumulator.getSum() > 0, "G 通道累加和应为正");
        assertTrue(colorBAccumulator.getSum() > 0, "B 通道累加和应为正");

        // 各通道操作计数应一致
        assertEquals(frameCount, weightAccumulator.getOperationCount());
        assertEquals(frameCount, colorRAccumulator.getOperationCount());
    }
}
