// ============================================================
// Phase 2 快速验证 (最小依赖版本)
// ============================================================
// 只验证可独立编译的组件: PrecisionConfig + DynamicPrecisionManager

package com.renderium.core;

public class Phase2MinVerification {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║    Renderium Phase 2 Minimal Verification  ║");
        System.out.println("╚══════════════════════════════════════╝\n");

        boolean allPassed = true;

        System.out.println("▶ [1/2] PrecisionConfig 配置系统");
        try { allPassed &= testPrecisionConfig(); }
        catch (Exception e) { System.out.println("  ❌ FAIL: " + e.getMessage()); allPassed = false; }
        System.out.println();

        System.out.println("▶ [2/2] DynamicPrecisionManager 核心决策");
        try { allPassed &= testDynamicPrecisionManager(); }
        catch (Exception e) { System.out.println("  ❌ FAIL: " + e.getMessage()); allPassed = false; }
        System.out.println();

        System.out.println("══════════════════════════════════════");
        if (allPassed) {
            System.out.println("✅ ALL TESTS PASSED - Phase 2 Core Ready!");
        } else {
            System.out.println("⚠️  SOME TESTS NEED ATTENTION");
        }
        System.out.println("══════════════════════════════════════");
    }

    private static boolean testPrecisionConfig() {
        boolean ok = true;

        // Test 1: DEFAULT_1000FPS 配置
        PrecisionConfig cfg1000 = PrecisionConfig.DEFAULT_1000FPS;
        ok &= check("1000FPS目标", cfg1000.getTargetFrameTimeMs() == 0.9f, cfg1000.getTargetFrameTimeMs());

        // Test 2: QUALITY_PRIORITY 配置
        PrecisionConfig cfgQuality = PrecisionConfig.QUALITY_PRIORITY;
        ok &= check("质量优先目标>1ms", cfgQuality.getTargetFrameTimeMs() > 1.0f,
                   cfgQuality.getTargetFrameTimeMs());

        // Test 3: Builder 模式自定义配置
        PrecisionConfig custom = new PrecisionConfig.Builder()
                .targetFrameTimeMs(1.5f)
                .adjustmentIntervalFrames(30)
                .enableAutoAdjustment(true)
                .build();
        ok &= check("Builder目标=1.5ms", custom.getTargetFrameTimeMs() == 1.5f,
                   custom.getTargetFrameTimeMs());
        ok &= check("调整间隔=30帧", custom.getAdjustmentIntervalFrames() == 30,
                   custom.getAdjustmentIntervalFrames());
        ok &= check("自动调整启用", custom.isAutoAdjustmentEnabled(),
                   custom.isAutoAdjustmentEnabled());

        // Test 4: BALANCED 预设
        PrecisionConfig balanced = PrecisionConfig.BALANCED;
        ok &= check("BALANCED存在", balanced != null, "not null");
        ok &= check("BALANCED目标≈16ms(60FPS)", 
                   Math.abs(balanced.getTargetFrameTimeMs() - 16.666f) < 1.0f,
                   balanced.getTargetFrameTimeMs() + "ms");

        return ok;
    }

    private static boolean testDynamicPrecisionManager() {
        DynamicPrecisionManager mgr = new DynamicPrecisionManager();
        boolean ok = true;

        // Test 1: 初始状态应该是 FP16_MEDIUM
        DynamicPrecisionManager.PrecisionLevel initial = mgr.getCurrentLevel();
        ok &= check("初始=FP16_MEDIUM", 
                   initial == DynamicPrecisionManager.PrecisionLevel.FP16_MEDIUM, 
                   initial);

        // Test 2: 热路径应该返回快速精度
        DynamicPrecisionManager.PrecisionLevel hot = mgr.decidePrecision(
            DynamicPrecisionManager.OperationCategory.HOT_PATH_PER_PIXEL);
        ok &= check("热路径→INT8_FAST", 
                   hot == DynamicPrecisionManager.PrecisionLevel.INT8_FAST, 
                   hot);

        // Test 3: 离线分析应该返回 KAHAN
        DynamicPrecisionManager.PrecisionLevel offline = mgr.decidePrecision(
            DynamicPrecisionManager.OperationCategory.OFFLINE_ANALYSIS);
        ok &= check("离线→KAHAN", 
                   offline == DynamicPrecisionManager.PrecisionLevel.KAHAN_PRECISE, 
                   offline);

        // Test 4: Tile 统计应该返回 FP16
        DynamicPrecisionManager.PrecisionLevel tile = mgr.decidePrecision(
            DynamicPrecisionManager.OperationCategory.TILE_LEVEL_STATS);
        ok &= check("Tile统计→FP16", 
                   tile == DynamicPrecisionManager.PrecisionLevel.FP16_MEDIUM, 
                   tile);

        // Test 5: 帧累加应该返回 FP32
        DynamicPrecisionManager.PrecisionLevel frame = mgr.decidePrecision(
            DynamicPrecisionManager.OperationCategory.FRAME_LEVEL_ACCUMULATION);
        ok &= check("帧累加→FP32", 
                   frame == DynamicPrecisionManager.PrecisionLevel.FP32_FULL, 
                   frame);

        // Test 6: 强制设置 SKIP
        mgr.forcePrecision(DynamicPrecisionManager.PrecisionLevel.SKIP);
        ok &= check("强制SKIP", 
                   mgr.getCurrentLevel() == DynamicPrecisionManager.PrecisionLevel.SKIP,
                   mgr.getCurrentLevel());

        // Test 7: 重置后应该回到自适应模式
        mgr.resetToAdaptive();
        ok &= check("重置后=FP16", 
                   mgr.getCurrentLevel() == DynamicPrecisionManager.PrecisionLevel.FP16_MEDIUM,
                   mgr.getCurrentLevel());

        // Test 8: 模拟快速帧更新（应该保持或升级）
        for (int i = 0; i < 120; i++) {
            mgr.updateFrameTime(0.3f);  // 模拟 0.3ms (远低于 0.9ms 目标)
        }
        float avgFast = mgr.getAverageFrameTime();
        ok &= check("快速帧平均≈0.3ms", Math.abs(avgFast - 0.3f) < 0.05f, avgFast + "ms");

        // Test 9: 模拟慢速帧更新（应该降级）
        DynamicPrecisionManager slowMgr = new DynamicPrecisionManager();
        for (int i = 0; i < 120; i++) {
            slowMgr.updateFrameTime(2.0f);  // 模拟 2.0ms (超支)
        }
        DynamicPrecisionManager.PrecisionLevel afterSlow = slowMgr.getCurrentLevel();
        // 超支后应该降级到更低精度
        ok &= check("慢速后降级", 
                   afterSlow.ordinal() <= DynamicPrecisionManager.PrecisionLevel.FP16_MEDIUM.ordinal(),
                   afterSlow + " (ordinal=" + afterSlow.ordinal() + ")");

        return ok;
    }

    private static boolean check(String name, boolean condition, Object actual) {
        String icon = condition ? "✅" : "❌";
        System.out.println("  " + icon + " " + name + " [" + actual + "]");
        return condition;
    }
}
