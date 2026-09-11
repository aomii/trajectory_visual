package com.logicompress.supplement;

import com.logicompress.experiment.config.ExperimentConfig;
import com.logicompress.experiment.model.SemanticResult;
import com.logicompress.experiment.model.StopUnit;
import com.logicompress.experiment.model.TrackPoint;
import com.logicompress.experiment.semantic.FenceMatcher;
import com.logicompress.experiment.semantic.SemanticAnalyzer;
import com.logicompress.experiment.semantic.StDbscan;
import com.logicompress.experiment.semantic.StationaryMerger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 表 6-7：静态同坐标合并 vs ST-DBSCAN（停留识别选型对比）。
 *
 * <p>对同一批运单分别用两种方法识别停留，统计单元数/长停数/时长分布/与业务围栏（收发货地址圆围栏
 * + 装卸货时窗）的时间重叠数，以及两方法的交集与各自独有单元数。
 *
 * <p>口径与 04实验 Supplementary#stopVsDbscan 一致，但数据源改为可视化系统源目录
 * （轨迹走 TrackSourceUtil，业务信息由 MySQL 提供），见 {@link SupplementContext}。
 */
public final class StopRecognitionCompare {

    private StopRecognitionCompare() {
    }

    public static void run(ExperimentConfig cfg, List<SupplementContext.WaybillCtx> ctxs, Path outDir)
            throws IOException {
        long t0 = System.currentTimeMillis();
        List<Double> sDurs = new ArrayList<>();
        List<Double> dDurs = new ArrayList<>();
        long sStops = 0, dStops = 0, sLong = 0, dLong = 0;
        long sOnly = 0, dOnly = 0, both = 0, sFence = 0, dFence = 0;
        int n = 0;
        List<String[]> perWaybill = new ArrayList<>();

        for (SupplementContext.WaybillCtx c : ctxs) {
            List<TrackPoint> pts = c.cleaned.points;
            // ① 本文：静态同坐标合并
            List<StopUnit> staticStops = StationaryMerger.merge(pts, cfg);
            // ② 基线：ST-DBSCAN（无围栏种子，纯时空聚类）
            List<StopUnit> dbStops = dbscanDetect(pts, cfg);
            // ③ 业务参考：收发货围栏 + 装卸货时窗命中的单元（仅作间接锚定对照，非识别主路径）
            SemanticResult tmp = new SemanticResult();
            FenceMatcher fm = new FenceMatcher();
            fm.match(c.waybill, pts, cfg, tmp, new FenceMatcher.FenceHit(), new FenceMatcher.FenceHit());
            List<StopUnit> fenceUnits = tmp.stops;

            int[] onlyA = new int[1];
            int[] onlyB = new int[1];
            int inter = matchOverlap(staticStops, dbStops, onlyA, onlyB);
            int sF = fenceOverlapCount(staticStops, fenceUnits);
            int dF = fenceOverlapCount(dbStops, fenceUnits);

            int sl = 0, dl = 0;
            for (StopUnit u : staticStops) {
                sDurs.add(u.durationS());
                if (u.durationS() >= SemanticAnalyzer.REST_MIN_S) sl++;
            }
            for (StopUnit u : dbStops) {
                dDurs.add(u.durationS());
                if (u.durationS() >= SemanticAnalyzer.REST_MIN_S) dl++;
            }

            sStops += staticStops.size();
            dStops += dbStops.size();
            sLong += sl;
            dLong += dl;
            sOnly += onlyA[0];
            dOnly += onlyB[0];
            both += inter;
            sFence += sF;
            dFence += dF;
            n++;
            perWaybill.add(new String[]{c.waybill.waybillNo,
                    String.valueOf(staticStops.size()), String.valueOf(dbStops.size()),
                    String.valueOf(sl), String.valueOf(dl), String.valueOf(inter),
                    String.valueOf(onlyA[0]), String.valueOf(onlyB[0]),
                    String.valueOf(sF), String.valueOf(dF)});
        }

        SupplementTables.csv(outDir, "table6_7_stop_vs_dbscan_per_waybill",
                new String[]{"waybill_no", "static_stops", "dbscan_stops", "static_long_30min", "dbscan_long_30min",
                        "overlap_both", "static_only", "dbscan_only", "static_fence_overlap", "dbscan_fence_overlap"},
                perWaybill);

        double[] ss = durStats(sDurs);
        double[] ds = durStats(dDurs);
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"有效运单数", String.valueOf(n), "—"});
        rows.add(new String[]{"停留单元总数", String.valueOf(sStops), String.valueOf(dStops)});
        rows.add(new String[]{"长停留单元数（>30 min）", String.valueOf(sLong), String.valueOf(dLong)});
        rows.add(new String[]{"停留时长中位", fmtSec(ss[0]), fmtSec(ds[0])});
        rows.add(new String[]{"停留时长 P90", fmtSec(ss[1]), fmtSec(ds[1])});
        rows.add(new String[]{"停留时长最长", fmtSec(ss[2]), fmtSec(ds[2])});
        rows.add(new String[]{"与围栏装卸货单元时间重叠数", String.valueOf(sFence), String.valueOf(dFence)});
        rows.add(new String[]{"两方法交集单元数", String.valueOf(both), String.valueOf(both)});
        rows.add(new String[]{"仅本方法独有单元数", String.valueOf(sOnly), String.valueOf(dOnly)});

        SupplementTables.both(outDir, "table6_7_static_vs_dbscan",
                "表 6-7 静态合并 vs ST-DBSCAN 对比（" + n + " 运单）",
                "口径：" + cfg.dbscanEpsM + " m / " + cfg.dbscanEpsTS + " s / MinPts=" + cfg.dbscanMinPts
                        + "；交集判据为时间重叠比 ≥ 0.5。围栏重叠数为间接业务锚定对照（非查准/查全）。",
                new String[]{"指标", "静态合并（本文）", "ST-DBSCAN"}, rows);

        System.out.printf(Locale.ROOT,
                "[表6-7] 完成：运单 %d，静态停留 %d vs ST-DBSCAN %d（交集 %d，仅静态 %d，仅DBSCAN %d），"
                        + "长停 %d vs %d，耗时 %.1fs%n",
                n, sStops, dStops, both, sOnly, dOnly, sLong, dLong,
                (System.currentTimeMillis() - t0) / 1000.0);
    }

    /** ST-DBSCAN 聚类 → 停留单元（时长 ≥ dMinS 才计），与 04实验 Supplementary#dbscanDetect 同口径 */
    static List<StopUnit> dbscanDetect(List<TrackPoint> pts, ExperimentConfig cfg) {
        StDbscan db = StDbscan.cluster(pts, cfg.dbscanEpsM, cfg.dbscanEpsTS, cfg.dbscanMinPts,
                Collections.emptySet());
        List<StopUnit> out = new ArrayList<>();
        for (List<Integer> cluster : db.clusters) {
            int minI = -1, maxI = -1;
            long tMin = Long.MAX_VALUE, tMax = Long.MIN_VALUE;
            for (int idx : cluster) {
                long t = pts.get(idx).gtmEpoch;
                if (t < tMin) { tMin = t; minI = idx; }
                if (t > tMax) { tMax = t; maxI = idx; }
            }
            long dur = tMax - tMin;
            if (dur < cfg.dMinS) continue;
            StopUnit u = new StopUnit();
            u.startIdx = minI;
            u.endIdx = maxI;
            u.tStart = tMin;
            u.tEnd = tMax;
            u.fromFence = false;
            u.label = dur >= SemanticAnalyzer.REST_MIN_S ? "REST" : "TRAFFIC";
            out.add(u);
        }
        return out;
    }

    /** 两两时间重叠比 ≥ 0.5 视为同一停留；返回交集数，并回填各自独有数 */
    static int matchOverlap(List<StopUnit> a, List<StopUnit> b, int[] onlyA, int[] onlyB) {
        boolean[] bUsed = new boolean[b.size()];
        int matched = 0;
        for (StopUnit ua : a) {
            for (int j = 0; j < b.size(); j++) {
                if (bUsed[j]) continue;
                if (StationaryMerger.overlapRatio(ua, b.get(j)) >= 0.5) {
                    bUsed[j] = true;
                    matched++;
                    break;
                }
            }
        }
        onlyA[0] = a.size() - matched;
        int bMatched = 0;
        for (boolean u : bUsed) if (u) bMatched++;
        onlyB[0] = b.size() - bMatched;
        return matched;
    }

    /** 停留单元中与围栏单元时间重叠 ≥ 0.5 的个数 */
    static int fenceOverlapCount(List<StopUnit> stops, List<StopUnit> fenceUnits) {
        int c = 0;
        for (StopUnit s : stops) {
            for (StopUnit f : fenceUnits) {
                if (StationaryMerger.overlapRatio(s, f) >= 0.5) { c++; break; }
            }
        }
        return c;
    }

    /** 时长分布：{中位, P90, 最大}（秒） */
    static double[] durStats(List<Double> durs) {
        double[] r = new double[]{0, 0, 0};
        if (durs.isEmpty()) return r;
        List<Double> s = new ArrayList<>(durs);
        Collections.sort(s);
        r[0] = s.get(s.size() / 2);
        r[1] = s.get(Math.min(s.size() - 1, (int) Math.ceil(s.size() * 0.9) - 1));
        r[2] = s.get(s.size() - 1);
        return r;
    }

    /** 秒 → 论文表里的可读写法：<1h 用 s，≥1h 用 h（保留 1 位） */
    static String fmtSec(double sec) {
        if (sec >= 3600) return String.format(Locale.ROOT, "%.1f h", sec / 3600);
        return String.format(Locale.ROOT, "%.0f s", sec);
    }
}
