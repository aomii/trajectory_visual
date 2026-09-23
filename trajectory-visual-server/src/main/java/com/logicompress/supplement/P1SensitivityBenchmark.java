package com.logicompress.supplement;

import com.logicompress.experiment.config.ExperimentConfig;
import com.logicompress.experiment.geo.GeoUtil;
import com.logicompress.experiment.model.StopUnit;
import com.logicompress.experiment.model.TrackPoint;
import com.logicompress.experiment.semantic.StationaryMerger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** P1-3: candidate-stop parameter sensitivity and ST-DBSCAN parameter sweep. */
public final class P1SensitivityBenchmark {
    private P1SensitivityBenchmark() {}

    public static void run(ExperimentConfig cfg, List<SupplementContext.WaybillCtx> ctxs, Path outDir)
            throws IOException {
        double oldSame = cfg.stationarySameM;
        long oldDMin = cfg.dMinS;
        double oldDbM = cfg.dbscanEpsM;
        long oldDbT = cfg.dbscanEpsTS;
        int oldDbPts = cfg.dbscanMinPts;
        try {
            List<String[]> stationary = new ArrayList<>();
            for (double eps : new double[]{5, 10, 15, 20, 30}) {
                cfg.stationarySameM = eps;
                cfg.dMinS = 180;
                stationary.add(summarize("epsilon_same_m", fmt(eps), detectStatic(ctxs, cfg)));
            }
            cfg.stationarySameM = 10;
            for (long dmin : new long[]{60, 180, 300, 600}) {
                cfg.dMinS = dmin;
                stationary.add(summarize("d_min_s", String.valueOf(dmin), detectStatic(ctxs, cfg)));
            }
            cfg.dMinS = 180;
            for (long gap : new long[]{1800, 7200, 18000, Long.MAX_VALUE}) {
                stationary.add(summarize("max_cross_gap_s",
                        gap == Long.MAX_VALUE ? "unlimited" : String.valueOf(gap), detectGapLimited(ctxs, cfg, gap)));
            }
            SupplementTables.both(outDir, "table6_p1_stationary_sensitivity",
                    "P1-3 候选静止单元参数敏感性（5,224 运单）",
                    "每次只改变一个参数；max_cross_gap_s=unlimited 对应当前实现。结果为输出稳定性描述，不是检测准确率。",
                    new String[]{"参数", "取值", "候选单元数", "含候选运单数", "跨度P50(s)", "跨度P90(s)", "最大跨度(h)"},
                    stationary);

            cfg.stationarySameM = 10;
            cfg.dMinS = 180;
            List<List<StopUnit>> defaults = detectStatic(ctxs, cfg);
            List<String[]> grouping = new ArrayList<>();
            for (long threshold : new long[]{900, 1800, 3600}) {
                long longCount = 0, total = 0;
                for (List<StopUnit> units : defaults) {
                    total += units.size();
                    for (StopUnit u : units) if (u.durationS() >= threshold) longCount++;
                }
                grouping.add(new String[]{String.valueOf(threshold), String.valueOf(longCount),
                        String.valueOf(total - longCount), SupplementTables.r2(100.0 * longCount / Math.max(1, total))});
            }
            SupplementTables.both(outDir, "table6_p1_long_stop_threshold",
                    "P1-3 LONG_STOP 分组阈值敏感性",
                    "该阈值只改变 LONG_STOP/SHORT_STOP 分组，不改变候选单元边界或总数。",
                    new String[]{"T_long(s)", "LONG_STOP", "SHORT_STOP", "LONG_STOP占比(%)"}, grouping);

            List<String[]> dbscan = new ArrayList<>();
            double[][] mt = {{30,300},{30,600},{30,1800},{50,300},{50,600},{50,1800},
                    {100,300},{100,600},{100,1800}};
            for (double[] x : mt) {
                cfg.dbscanEpsM = x[0]; cfg.dbscanEpsTS = (long) x[1]; cfg.dbscanMinPts = 3;
                dbscan.add(summarizeDb(cfg, detectDbscan(ctxs, cfg)));
            }
            for (int minPts : new int[]{2, 5}) {
                cfg.dbscanEpsM = 50; cfg.dbscanEpsTS = 300; cfg.dbscanMinPts = minPts;
                dbscan.add(summarizeDb(cfg, detectDbscan(ctxs, cfg)));
            }
            SupplementTables.both(outDir, "table6_p1_stdbscan_sweep",
                    "P1-3 ST-DBSCAN 参数扫描",
                    "缺少人工真值，故只报告输出数量与跨度分布，不以任一组输出数量判定优劣。",
                    new String[]{"epsilon_s(m)", "epsilon_t(s)", "MinPts", "候选单元数", "含候选运单数",
                            "跨度P50(s)", "跨度P90(s)", "最大跨度(h)"}, dbscan);
        } finally {
            cfg.stationarySameM = oldSame; cfg.dMinS = oldDMin;
            cfg.dbscanEpsM = oldDbM; cfg.dbscanEpsTS = oldDbT; cfg.dbscanMinPts = oldDbPts;
        }
    }

    private static List<List<StopUnit>> detectStatic(List<SupplementContext.WaybillCtx> ctxs, ExperimentConfig cfg) {
        List<List<StopUnit>> all = new ArrayList<>(ctxs.size());
        for (SupplementContext.WaybillCtx c : ctxs) all.add(StationaryMerger.merge(c.cleaned.points, cfg));
        return all;
    }

    private static List<List<StopUnit>> detectDbscan(List<SupplementContext.WaybillCtx> ctxs, ExperimentConfig cfg) {
        List<List<StopUnit>> all = new ArrayList<>(ctxs.size());
        for (SupplementContext.WaybillCtx c : ctxs) all.add(StopRecognitionCompare.dbscanDetect(c.cleaned.points, cfg));
        return all;
    }

    private static List<List<StopUnit>> detectGapLimited(List<SupplementContext.WaybillCtx> ctxs,
                                                          ExperimentConfig cfg, long maxGap) {
        List<List<StopUnit>> all = new ArrayList<>(ctxs.size());
        for (SupplementContext.WaybillCtx c : ctxs) all.add(mergeWithGap(c.cleaned.points, cfg, maxGap));
        return all;
    }

    private static List<StopUnit> mergeWithGap(List<TrackPoint> pts, ExperimentConfig cfg, long maxGap) {
        List<StopUnit> out = new ArrayList<>();
        if (pts.isEmpty()) return out;
        int start = 0;
        for (int i = 1; i < pts.size(); i++) {
            TrackPoint a = pts.get(i - 1), b = pts.get(i);
            boolean same = a.spdKph <= 1e-9 && b.spdKph <= 1e-9
                    && GeoUtil.haversineM(a.lat, a.lon, b.lat, b.lon) <= cfg.stationarySameM
                    && (maxGap == Long.MAX_VALUE || b.gtmEpoch - a.gtmEpoch <= maxGap);
            if (!same) { close(pts, start, i - 1, cfg.dMinS, out); start = i; }
        }
        close(pts, start, pts.size() - 1, cfg.dMinS, out);
        return out;
    }

    private static void close(List<TrackPoint> pts, int s, int e, long dMin, List<StopUnit> out) {
        if (e <= s) return;
        long dur = pts.get(e).gtmEpoch - pts.get(s).gtmEpoch;
        if (dur < dMin) return;
        StopUnit u = new StopUnit();
        u.startIdx = s; u.endIdx = e; u.tStart = pts.get(s).gtmEpoch; u.tEnd = pts.get(e).gtmEpoch;
        u.label = dur >= 1800 ? "LONG_STOP" : "SHORT_STOP";
        out.add(u);
    }

    private static String[] summarize(String parameter, String value, List<List<StopUnit>> all) {
        Stats s = stats(all);
        return new String[]{parameter, value, String.valueOf(s.total), String.valueOf(s.waybills),
                fmt(s.p50), fmt(s.p90), SupplementTables.r2(s.max / 3600.0)};
    }

    private static String[] summarizeDb(ExperimentConfig cfg, List<List<StopUnit>> all) {
        Stats s = stats(all);
        return new String[]{fmt(cfg.dbscanEpsM), String.valueOf(cfg.dbscanEpsTS), String.valueOf(cfg.dbscanMinPts),
                String.valueOf(s.total), String.valueOf(s.waybills), fmt(s.p50), fmt(s.p90),
                SupplementTables.r2(s.max / 3600.0)};
    }

    private static Stats stats(List<List<StopUnit>> all) {
        Stats s = new Stats();
        List<Double> durations = new ArrayList<>();
        for (List<StopUnit> units : all) {
            if (!units.isEmpty()) s.waybills++;
            s.total += units.size();
            for (StopUnit u : units) durations.add(u.durationS());
        }
        Collections.sort(durations);
        if (!durations.isEmpty()) {
            s.p50 = percentile(durations, .50); s.p90 = percentile(durations, .90);
            s.max = durations.get(durations.size() - 1);
        }
        return s;
    }

    private static double percentile(List<Double> x, double p) {
        int i = Math.min(x.size() - 1, Math.max(0, (int) Math.ceil(p * x.size()) - 1));
        return x.get(i);
    }

    private static String fmt(double x) { return String.format(Locale.ROOT, "%.0f", x); }

    private static final class Stats { long total; double p50, p90, max; int waybills; }
}
