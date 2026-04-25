// ============================================================
// Phase 3 集成验证 - 高级优化组件功能测试
// ============================================================
// 验证 AdaptivePrecisionManager 和 ConvergenceMonitor 的核心功能

package com.renderium.core;

public class Phase3IntegrationTest {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║   Renderium Phase 3 Integration Test    ║");
        System.out.println("╚══════════════════════════════════════╝\n");

        boolean allPassed = true;

        System.out.println("▶ [1/2] AdaptivePrecisionManager 自适应精度分配");
        try { allPassed &= testAdaptivePrecisionManager(); }
        catch (Exception e) { System.out.println("  ❌ FAIL: " + e.getMessage()); allPassed = false; }
        System.out.println();

        System.out.println("▶ [2/2] ConvergenceMonitor 收敛监控");
        try { allPassed &= testConvergenceMonitor(); }
        catch (Exception e) { System.out.println("  ❌ FAIL: " + e.getMessage()); allPassed = false; }
        System.out.println();

        System.out.println("══════════════════════════════════════");
        if (allPassed) {
            System.out.println("✅ ALL PHASE 3 TESTS PASSED - Advanced Optimization Ready!");
        } else {
            System.out.println("⚠️  SOME TESTS NEED ATTENTION");
        }
        System.out.println("══════════════════════════════════════");
    }

    private static boolean testAdaptivePrecisionManager() {
        boolean ok = true;
        AdaptivePrecisionManager mgr = new AdaptivePrecisionManager(16);  // 16x16 Tiles

        // Test 1: 创建简单测试图像（渐变 + 边缘）
        int width = 64, height = 64;  // 4x4 = 16 Tiles
        float[][] frameData = createTestFrame(width, height);

        // Test 2: 分析帧
        AdaptivePrecisionManager.FrameAnalysisResult result = mgr.analyzeFrame(frameData, width, height);
        ok &= check("分析结果非空", result != null, "not null");

        // Test 3: 检查精度图尺寸
        int expectedTilesX = (width + 15) / 16;  // 4
        int expectedTilesY = (height + 15) / 16;  // 4
        ok &= check("Tile网格宽度=" + expectedTilesX,
                   result.getTilesX() == expectedTilesX,
                   result.getTilesX());
        ok &= check("Tile网格高度=" + expectedTilesY,
                   result.getTilesY() == expectedTilesY,
                   result.getTilesY());

        // Test 4: 检查分布统计（应该包含所有4种精度）
        int[] dist = result.getTileDistribution();
        int totalTiles = 0;
        for (int count : dist) totalTiles += count;
        ok &= check("总Tile数=" + (expectedTilesX * expectedTilesY),
                   totalTiles == expectedTilesX * expectedTilesY,
                   totalTiles);

        // Test 5: 资源压力影响
        mgr.updateResourcePressure(0.8f);  // 高压
        AdaptivePrecisionManager.FrameAnalysisResult highPressureResult =
            mgr.analyzeFrame(frameData, width, height);
        ok &= check("高压下阈值提高",
                   highPressureResult.getThresholdHigh() > result.getThresholdHigh(),
                   String.format("%.3f > %.3f",
                       highPressureResult.getThresholdHigh(), result.getThresholdHigh()));

        // Test 6: 估算算力节省
        double savings = result.computeEstimatedSavingsPercent();
        ok &= check("算力节省>0%", savings > 0, String.format("%.1f%%", savings));

        // Test 7: 状态摘要
        String summary = mgr.getStatusSummary();
        ok &= check("状态摘要非空", summary != null && !summary.isEmpty(), "length=" + summary.length());

        return ok;
    }

    private static boolean testConvergenceMonitor() {
        boolean ok = true;
        ConvergenceMonitor monitor = new ConvergenceMonitor();

        // Test 1: 初始状态
        ok &= check("初始未收敛", !monitor.hasConverged(), "converged=" + monitor.hasConverged());
        ok &= check("初始置信度=0", monitor.getConfidence() == 0.0, monitor.getConfidence());

        // Test 2: 更新各项指标（模拟快速收敛场景）
        for (int i = 0; i < 20; i++) {
            monitor.updateEnergy(100.0 - i * 4.0);      // 能量递减
            monitor.updateMotion(10.0 - i * 0.4);       // 运动递减
            monitor.updateGradient(50.0 - i * 2.0);     // 梯度递减
            monitor.updateResidual(0.5 - i * 0.02);    // 残差递减
        }

        double confidence = monitor.getConfidence();
        ok &= check("20次迭代后置信度>0", confidence > 0.0, String.format("%.4f", confidence));

        // Test 3: 继续迭代至收敛
        for (int i = 20; i < 50; i++) {
            monitor.updateEnergy(20.0 - i * 0.3);
            monitor.updateMotion(2.0 - i * 0.03);
            monitor.updateGradient(10.0 - i * 0.15);
            monitor.updateResidual(0.1 - i * 0.002);
        }

        confidence = monitor.getConfidence();
        boolean converged = monitor.hasConverged();
        ok &= check("50次迭代后高置信度", confidence > 0.8, String.format("%.4f", confidence));
        System.out.println(String.format(
            "     收敛状态: %s, 置信度: %.4f",
            converged ? "已收敛" : "未收敛", confidence
        ));

        // Test 4: 重置功能
        monitor.reset();
        ok &= check("重置后置信度=0", monitor.getConfidence() == 0.0, monitor.getConfidence());
        ok &= check("重置后未收敛", !monitor.hasConverged(), "converged=" + monitor.hasConverged());

        // Test 5: 状态摘要
        String summary = monitor.getStatusSummary();
        ok &= check("状态摘要非空", summary != null && !summary.isEmpty(), "length=" + summary.length());

        return ok;
    }

    /**
     * 创建测试帧数据（包含不同复杂度的区域）
     */
    private static float[][] createTestFrame(int width, int height) {
        float[][] frame = new float[height][width];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float value = 0.0f;

                // 左上角：均匀区域（低复杂性）→ 应该被分配 SKIP
                if (x < width/2 && y < height/2) {
                    value = 128.0f;  // 中等灰度，无变化
                }
                // 右上角：平滑渐变（中低复杂性）→ INT8 或 FP16
                else if (x >= width/2 && y < height/2) {
                    value = 128.0f + (x - width/2) * 0.5f;  // 水平渐变
                }
                // 左下角：中等细节区域 → FP16
                else if (x < width/2 && y >= height/2) {
                    value = 128.0f + (float)Math.sin(x * 0.3) * 30.0f;  // 正弦波纹理
                }
                // 右下角：高频边缘（高复杂性）→ FP32
                else {
                    value = ((x + y) % 2 == 0) ? 255.0f : 0.0f;  // 棋盘格（最大梯度）
                }

                frame[y][x] = value;
            }
        }

        return frame;
    }

    private static boolean check(String name, boolean condition, Object actual) {
        String icon = condition ? "✅" : "❌";
        System.out.println("  " + icon + " " + name + " [" + actual + "]");
        return condition;
    }
}
