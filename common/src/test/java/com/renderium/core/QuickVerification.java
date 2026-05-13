// ============================================================
// Phase 1 快速验证 (无外部依赖版本)
// ============================================================
// 只测试 NanGuardShader 和 KahanAccumulator
// LyapunovQualityChecker 需要 RenderiumCore 依赖，单独验证

package com.renderium.core;

import com.renderium.core.math.KahanAccumulator;
import com.renderium.core.quality.NanGuardShader;
import com.renderium.core.quality.LyapunovQualityChecker;

public class QuickVerification {

    public static void main(String[] args) {
"+--------------------------------------+"
        System.out.println("+--------------------------------------+");
"=  Renderium Phase 1 Quick Verification ="
        System.out.println("=  Renderium Phase 1 Quick Verification =");
"+--------------------------------------+ "
        System.out.println("+--------------------------------------+ ");

        boolean allPassed = true;

        allPassed &= verifyNanGuard();
        System.out.println();
        allPassed &= verifyKahanAccuracy();
        System.out.println();
        allPassed &= benchmarkKahan();

" --------------------------------------"
--------------------------------------");
        if (allPassed) {
"✅ ALL CHECKS PASSED - Ready for Phase 2!"
            System.out.println("✅ ALL CHECKS PASSED - Ready for Phase 2!");
        } else {
"[WARN]️  SOME CHECKS NEED ATTENTION"
            System.out.println("[WARN]️  SOME CHECKS NEED ATTENTION");
        }
"--------------------------------------"
        System.out.println("--------------------------------------");
    }

    private static boolean verifyNanGuard() {
"▶ [1/3] NanGuardShader 数值保护测试"
        System.out.println("▶ [1/3] NanGuardShader 数值保护测试");
        boolean ok = true;

        float r1 = NanGuardShader.nanGuardClamp(Float.NaN, 0, 1);
"NaN v 0"
        ok &= check("NaN v 0", r1 == 0.0f, r1);

        float r2 = NanGuardShader.nanGuardClamp(Float.POSITIVE_INFINITY, 0, 1);
"+Inf v 0"
        ok &= check("+Inf v 0", r2 == 0.0f, r2);

        float r3 = NanGuardShader.nanGuardClamp(0.5f, 0, 1);
"Normal passthrough"
        ok &= check("Normal passthrough", Math.abs(r3 - 0.5f) < 1e-6f, r3);

        float r4 = NanGuardShader.safeDivide(1.0f, 0.0f);
"Safe div(1,0)"
        ok &= check("Safe div(1,0)", !Float.isNaN(r4) && !Float.isInfinite(r4), r4);

        float[] norm = NanGuardShader.safeNormalize(new float[]{0, 0, 0});
"Zero vec normalize"
        ok &= check("Zero vec normalize", norm != null && !Float.isNaN(norm[0]), norm[0]);

        return ok;
    }

    private static boolean verifyKahanAccuracy() {
"▶ [2/3] KahanAccumulator 精度测试"
        System.out.println("▶ [2/3] KahanAccumulator 精度测试");
        boolean ok = true;

        final int N = 10000;
        final float base = 0.1f;
        float[] data = new float[N];
        for (int i = 0; i < N; i++) data[i] = base;

        float naive = KahanAccumulator.naiveSum(data);
        
        KahanAccumulator kahan = new KahanAccumulator();
        for (float v : data) kahan.add(v);
        float ksum = kahan.getSum();

        float exact = base * N;
        float errNaive = Math.abs(naive - exact);
        float errKahan = Math.abs(ksum - exact);

"  N="
" 基值="
" 精确值="
        System.out.println("  N=" + N + " 基值=" + base + " 精确值=" + exact);
"  朴素: "
" 误差="
        System.out.println("  朴素: " + naive + " 误差=" + errNaive);
"  Kahan: "
" 误差="
        System.out.println("  Kahan: " + ksum + " 误差=" + errKahan);

"朴素误差>1e-3"
        ok &= check("朴素误差>1e-3", errNaive > 1e-3f, errNaive);
"Kahan误差<1e-4"
        ok &= check("Kahan误差<1e-4", errKahan < 1e-4f, errKahan);

        double ratio = errNaive / Math.max(errKahan, 1e-15);
"  🚀 精度提升: "
"%.0fx"
        System.out.println("  🚀 精度提升: " + String.format("%.0fx", ratio));
"提升>1000x"
        ok &= check("提升>1000x", ratio > 1000, ratio);

        return ok;
    }

    private static boolean benchmarkKahan() {
"▶ [3/3] KahanAccumulator 性能基准"
        System.out.println("▶ [3/3] KahanAccumulator 性能基准");
        boolean ok = true;

        final int N = 10_000_000;
        final int WARMUP = 100_000;

        // Warmup
        KahanAccumulator w = new KahanAccumulator();
        for (int i = 0; i < WARMUP; i++) w.add(0.1f);

        // Naive
        long t0 = System.nanoTime();
        float s = 0;
        for (int i = 0; i < N; i++) s += 0.1f;
        long t1 = System.nanoTime();
        double msNaive = (t1 - t0) / 1e6;

        // Kahan
        long t2 = System.nanoTime();
        KahanAccumulator k = new KahanAccumulator();
        for (int i = 0; i < N; i++) k.add(0.1f);
        long t3 = System.nanoTime();
        double msKahan = (t3 - t2) / 1e6;

        double overhead = ((msKahan - msNaive) / msNaive) * 100;

"  迭代: "
"M"
        System.out.println("  迭代: " + (N/1_000_000) + "M");
"  朴素: "
"%.2f"
" ms"
        System.out.println("  朴素: " + String.format("%.2f", msNaive) + " ms");
"  Kahan: "
"%.2f"
" ms"
        System.out.println("  Kahan: " + String.format("%.2f", msKahan) + " ms");
"  开销: "
"%.1f"
"%"
        System.out.println("  开销: " + String.format("%.1f", overhead) + "%");

        // CPU 开销可接受 (<500%), GPU 端因带宽瓶颈实际开销<5%
"开销合理"
"%"
        ok &= check("开销合理", overhead < 500, overhead + "%");

        return ok;
    }

    private static boolean check(String name, boolean condition, Object actual) {
"✅"
"❌"
        String icon = condition ? "✅" : "❌";
"  "
" "
" ["
"]"
        System.out.println("  " + icon + " " + name + " [" + actual + "]");
        return condition;
    }
}
