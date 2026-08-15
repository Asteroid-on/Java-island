package com.island.perf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 性能测试共享工具：计时、分位数统计、输出格式化。 */
public final class PerfUtil {

    private PerfUtil() {}

    /** 简单统计：样本数、平均、中位、p95、p99、最大、最小。 */
    public record Stats(int n, double avg, double p50, double p95, double p99, double max, double min) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "n=%d avg=%.3fms p50=%.3fms p95=%.3fms p99=%.3fms max=%.3fms min=%.3fms",
                    n, avg, p50, p95, p99, max, min);
        }
    }

    public static Stats stats(List<Double> samplesMs) {
        Collections.sort(samplesMs);
        int n = samplesMs.size();
        double sum = 0;
        for (double d : samplesMs) sum += d;
        return new Stats(n, sum / n,
                q(samplesMs, 0.50), q(samplesMs, 0.95), q(samplesMs, 0.99),
                samplesMs.get(n - 1), samplesMs.get(0));
    }

    private static double q(List<Double> sorted, double p) {
        int idx = (int) Math.ceil(sorted.size() * p) - 1;
        if (idx < 0) idx = 0;
        return sorted.get(idx);
    }

    /** 对一次操作计时的采样器。 */
    public static List<Double> sample(int iterations, Runnable op) {
        List<Double> samples = new ArrayList<>(iterations);
        // 预热
        for (int i = 0; i < Math.min(iterations, 200); i++) op.run();
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            op.run();
            samples.add((System.nanoTime() - t0) / 1e6);
        }
        return samples;
    }

    public static void print(String title, Stats s) {
        System.out.printf("[PERF] %-46s %s%n", title, s);
    }

    public static void header(String title) {
        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println(title);
        System.out.println("=".repeat(80));
    }
}
