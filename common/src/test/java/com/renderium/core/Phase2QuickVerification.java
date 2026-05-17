// ============================================================
// Phase 2 快速验证 (无外部依赖版本)
// ============================================================
// 验证内容：
//   1. DynamicPrecisionManager 决策算法正确性
//   2. PrecisionConfig 配置加载
//   3. PhaseEvent 数据结构
//   4. 性能基准数据收集

package com.renderium.core;

import com.renderium.core.phase.PhaseTransitionDetector;
import com.renderium.core.phase.PhaseEvent;

import java.time.Instant;

public class Phase2QuickVerification {

    public static void main(String[] args) {
        System.out.println("+--------------------------------------+");
        System.out.println("=    Renderium Phase 2 Quick Verification     =");
        System.out.println("+--------------------------------------+ ");

        boolean allPassed = true;

        // Test 1: DynamicPrecisionManager
        System.out.println("▶ [1/3] DynamicPrecisionManager 精度决策");
        try { allPassed &= testDynamicPrecisionManager(); }
        catch (Exception e) { System.out.println("  ❌ FAIL: " + e.getMessage()); allPassed = false; }
        System.out.println();

        // Test 2: PrecisionConfig 预设配置
        System.out.println("▶ [2/3] PrecisionConfig 配置验证");
        try { allPassed &= testPrecisionConfig(); }
        catch (Exception e) { System.out.println("  ❌ FAIL: " + e.getMessage()); allPassed = false; }
        System.out.println();

        // Test 3: PhaseEvent 数据结构
        System.out.println("▶ [3/3] PhaseEvent 数据结构");
        try { allPassed &= testPhaseEvent(); }
        catch (Exception e) { System.out.println("  ❌ FAIL: " + e.getMessage()); allPassed = false; }
        System.out.println();

        // Summary
        System.out.println("--------------------------------------");
        if (allPassed) {
            System.out.println("✅ ALL TESTS PASSED - Phase 2 Ready!");
        } else {
            System.out.println("[WARN]️  SOME TESTS NEED ATTENTION");
        }
        System.out.println("--------------------------------------");
    }

    private static boolean testDynamicPrecisionManager() {
        DynamicPrecisionManager mgr = new DynamicPrecisionManager();
        boolean ok = true;

        // Test 1: 初始状态
        DynamicPrecisionManager.PrecisionLevel initial = mgr.getCurrentLevel();
        ok &= check("初始精度=FP16_MEDIUM", initial == DynamicPrecisionManager.PrecisionLevel.FP16_MEDIUM, initial);

        // Test 2: HOT_PATH 应返回快速精度
        DynamicPrecisionManager.PrecisionLevel hot = mgr.decidePrecision(
            DynamicPrecisionManager.OperationCategory.HOT_PATH_PER_PIXEL);
        ok &= check("热路径vINT8_FAST", hot == DynamicPrecisionManager.PrecisionLevel.INT8_FAST, hot);

        // Test 3: OFFLINE_ANALYSIS 应返回 KAHAN
        DynamicPrecisionManager.PrecisionLevel offline = mgr.decidePrecision(
            DynamicPrecisionManager.OperationCategory.OFFLINE_ANALYSIS);
        ok &= check("离线分析vKAHAN", offline == DynamicPrecisionManager.PrecisionLevel.KAHAN_PRECISE, offline);

        // Test 4: 强制设置
        mgr.forcePrecision(DynamicPrecisionManager.PrecisionLevel.SKIP);
        ok &= check("强制SKIP", mgr.getCurrentLevel() == DynamicPrecisionManager.PrecisionLevel.SKIP,
                   mgr.getCurrentLevel());

        // Test 5: 重置自适应
        mgr.resetToAdaptive();
        ok &= check("重置后=FP16", mgr.getCurrentLevel() == DynamicPrecisionManager.PrecisionLevel.FP16_MEDIUM,
                   mgr.getCurrentLevel());

        // Test 6: 模拟帧时间更新
        for (int i = 0; i < 100; i++) {
            mgr.updateFrameTime(0.5f);  // 模拟 0.5ms 帧时间
        }
        float avgTime = mgr.getAverageFrameTime();
        ok &= check("平均帧时间~0.5ms", Math.abs(avgTime - 0.5f) < 0.1f, avgTime + "ms");

        return ok;
    }

    private static boolean testPrecisionConfig() {
        boolean ok = true;

        // Test 1: 默认配置
        PrecisionConfig config = PrecisionConfig.DEFAULT_1000FPS;
        ok &= check("1000FPS目标帧时间", config.getTargetFrameTimeMs() == 0.9f,
                   config.getTargetFrameTimeMs() + "ms");

        // Test 2: Builder 模式
        PrecisionConfig custom = new PrecisionConfig.Builder()
                .targetFrameTimeMs(2.0f)
                .defaultHotPathPrecision(DynamicPrecisionManager.PrecisionLevel.FP16_MEDIUM)
                .enableAutoAdjustment(true)
                .build();
        ok &= check("自定义目标=2.0ms", custom.getTargetFrameTimeMs() == 2.0f,
                   custom.getTargetFrameTimeMs() + "ms");

        // Test 3: 质量优先配置
        PrecisionConfig quality = PrecisionConfig.QUALITY_PRIORITY;
        ok &= check("质量优先目标>1ms", quality.getTargetFrameTimeMs() > 1.0f,
                   quality.getTargetFrameTimeMs() + "ms");

        return ok;
    }

    private static boolean testPhaseEvent() {
        boolean ok = true;

        // Test 1: 创建事件
        PhaseTransitionDetector.PhaseType type = PhaseTransitionDetector.PhaseType.SCENE_CHANGE;
        PhaseEvent event = new PhaseEvent(type, 0.85f, 123.45f, 567.89f, 42);

        ok &= check("事件类型", event.getPhaseType() == type, event.getPhaseType());
        ok &= check("强度=0.85", Math.abs(event.getIntensity() - 0.85f) < 0.01f, event.getIntensity());
        ok &= check("帧号=42", event.getFrameNumber() == 42, event.getFrameNumber());

        // Test 2: toString
        String str = event.toString();
        ok &= check("包含SCENE_CHANGE", str.contains("SCENE_CHANGE"), "toString()格式");

        // Test 3: 时间戳自动设置
        long before = System.currentTimeMillis();
        PhaseEvent event2 = new PhaseEvent(PhaseTransitionDetector.PhaseType.MOTION_CHANGE, 0.3f, 10f, 20f, 0);
        Instant after = event2.getTimestamp();
        ok &= check("时间戳合理", after.toEpochMilli() >= before && after.toEpochMilli() <= System.currentTimeMillis(),
                   "timestamp=" + after);

        return ok;
    }

    private static boolean check(String name, boolean condition, Object actual) {
        String icon = condition ? "✅" : "❌";
        System.out.println("  " + icon + " " + name + " [" + actual + "]");
        return condition;
    }
}
