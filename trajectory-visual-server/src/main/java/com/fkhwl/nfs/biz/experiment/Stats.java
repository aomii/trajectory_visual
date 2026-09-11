package com.fkhwl.nfs.biz.experiment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 指标聚合工具：mean / median / min / max，与 04实验 Main#stats 口径一致。
 */
public final class Stats {

    private Stats() {
    }

    public static double mean(List<Double> vals) {
        if (vals.isEmpty()) return 0;
        double s = 0;
        for (double v : vals) s += v;
        return round4(s / vals.size());
    }

    public static double median(List<Double> vals) {
        if (vals.isEmpty()) return 0;
        double[] a = vals.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(a);
        return round4(a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2.0);
    }

    public static double min(List<Double> vals) {
        if (vals.isEmpty()) return 0;
        double[] a = vals.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(a);
        return round4(a[0]);
    }

    public static double max(List<Double> vals) {
        if (vals.isEmpty()) return 0;
        double[] a = vals.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(a);
        return round4(a[a.length - 1]);
    }

    /** 返回按 key 命名的均值聚合表，便于前端/调试 */
    public static Map<String, Double> summaryMap(String metricName, List<Double> vals) {
        Map<String, Double> m = new TreeMap<>();
        m.put(metricName + "_mean", mean(vals));
        m.put(metricName + "_median", median(vals));
        m.put(metricName + "_min", min(vals));
        m.put(metricName + "_max", max(vals));
        return m;
    }

    public static List<Double> doubles(int n, java.util.function.DoubleSupplier f) {
        List<Double> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(f.getAsDouble());
        return out;
    }

    public static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static List<Double> empty() {
        return Collections.emptyList();
    }
}
