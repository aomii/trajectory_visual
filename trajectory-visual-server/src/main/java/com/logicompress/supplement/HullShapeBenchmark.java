package com.logicompress.supplement;

import com.logicompress.experiment.baseline.Dps;
import com.logicompress.experiment.baseline.LossyBaseline;
import com.logicompress.experiment.baseline.PlainDp;
import com.logicompress.experiment.baseline.TdTr;
import com.logicompress.experiment.baseline.Trajic;
import com.logicompress.experiment.clean.DriftFilter;
import com.logicompress.experiment.config.ExperimentConfig;
import com.logicompress.experiment.geo.GeoUtil;
import com.logicompress.experiment.model.TrackPoint;
import com.logicompress.experiment.model.Waybill;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 表 6-18：凸包保形度与压缩失真速度比（第 5 章评估子系统的全路段/移动段指标）。
 *
 * <p>凸包面积比 = 压缩后轨迹（含收发货围栏中心点作控制变量）凸包面积 / 原始凸包面积，理想≈1；
 * 压缩失真速度比 = 压缩后相邻点推算速度超过限速（默认 90 km/h）的段占比。
 * 两者都是粗粒度几何信号，用于说明"整体几何保形是各方法都能满足的基础检查"。
 */
public final class HullShapeBenchmark {

    private static final class HullAgg {
        int n;
        final List<Double> ratios = new ArrayList<>();
        double sumRatio, sumDist;

        void add(double ratio, double dist) {
            n++;
            ratios.add(ratio);
            sumRatio += ratio;
            sumDist += dist;
        }
    }

    private HullShapeBenchmark() {
    }

    public static void run(ExperimentConfig cfg, List<SupplementContext.WaybillCtx> ctxs, Path outDir)
            throws IOException {
        long t0 = System.currentTimeMillis();
        List<String[]> longRows = new ArrayList<>();
        Map<String, HullAgg> agg = new LinkedHashMap<>();
        LossyBaseline[] baselines = {new PlainDp(), new Dps(), new TdTr(), new Trajic()};
        String[] names = {"DP", "DPS", "TD-TR", "Trajic"};

        for (SupplementContext.WaybillCtx c : ctxs) {
            double refLat = c.cleaned.points.get(0).lat;
            double refLon = c.cleaned.points.get(0).lon;
            double areaOrig = hullArea(c, null, refLat, refLon);
            if (areaOrig <= 0) continue;

            emit(longRows, agg, "Ours", c, SupplementContext.ourKept(c, cfg), areaOrig, refLat, refLon, cfg);
            for (int i = 0; i < baselines.length; i++) {
                List<Integer> kept = baselines[i].compress(c.cleaned, cfg.baselineDpM);
                if (kept == null || kept.isEmpty()) continue;
                emit(longRows, agg, names[i], c, kept, areaOrig, refLat, refLon, cfg);
            }
        }

        SupplementTables.csv(outDir, "table6_18_hull_per_waybill",
                new String[]{"method", "waybill_no", "area_orig_m2", "area_kept_m2",
                        "area_ratio", "distortion_speed_ratio"}, longRows);

        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<String, HullAgg> e : agg.entrySet()) {
            HullAgg a = e.getValue();
            List<Double> sorted = new ArrayList<>(a.ratios);
            Collections.sort(sorted);
            rows.add(new String[]{label(e.getKey()), String.valueOf(a.n),
                    SupplementTables.r4(a.sumRatio / a.n), SupplementTables.r4(sorted.get(sorted.size() / 2)),
                    SupplementTables.r4(a.sumDist / a.n * 100) + "%"});
        }
        SupplementTables.both(outDir, "table6_18_hull_summary",
                "表 6-18 凸包保形度与压缩失真速度比（" + ctxs.size() + " 运单，均值）",
                "凸包两侧同加收发围栏中心点作控制变量；失真速度比限速 " + cfg.speedLimitKph
                        + " km/h，数值为超过该速度的相邻保留点段占比。",
                new String[]{"方法", "运单数", "凸包面积比（理想≈1）", "面积比中位", "压缩失真速度比"}, rows);

        System.out.printf(Locale.ROOT, "[表6-18] 完成：运单 %d，方法 %d 个，耗时 %.1fs%n",
                ctxs.size(), agg.size(), (System.currentTimeMillis() - t0) / 1000.0);
    }

    private static void emit(List<String[]> longRows, Map<String, HullAgg> agg, String method,
                             SupplementContext.WaybillCtx c, List<Integer> kept, double areaOrig,
                             double refLat, double refLon, ExperimentConfig cfg) {
        if (kept == null || kept.isEmpty()) return;
        double areaKept = hullArea(c, kept, refLat, refLon);
        double ratio = areaOrig <= 0 ? 0 : areaKept / areaOrig;
        double dist = distortionSpeedRatio(c, kept, cfg.speedLimitKph);
        agg.computeIfAbsent(method, k -> new HullAgg()).add(ratio, dist);
        longRows.add(new String[]{method, c.waybill.waybillNo, SupplementTables.r2(areaOrig),
                SupplementTables.r2(areaKept), SupplementTables.r4(ratio), SupplementTables.r4(dist)});
    }

    private static String label(String method) {
        return "Ours".equals(method) ? "本文 静态锚点分级" : method;
    }

    /**
     * 凸包面积（米²）：保留点 + 收发围栏中心点，以首点为原点到局部平面投影后求凸包。
     *
     * <p><b>求凸包前先去重</b>：静止段由大量同坐标点构成（纯停车轨迹可达上百个点完全重合），
     * 重复点对凸包无任何贡献，却会让 {@link GeoUtil#convexHull} 的 Graham 扫描退化——
     * 实测会让"原始轨迹凸包"被算成几十平方米（真实应约 100 km²），
     * 进而使"压缩后/原始"面积比出现 10^7 量级的假异常值。按毫米级坐标去重即可避免。
     */
    static double hullArea(SupplementContext.WaybillCtx c, List<Integer> keptIndices,
                           double refLat, double refLon) {
        List<double[]> xy = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        if (keptIndices == null) {
            for (TrackPoint p : c.cleaned.points) addUnique(xy, seen, p, refLat, refLon);
        } else {
            for (int idx : keptIndices) addUnique(xy, seen, c.cleaned.points.get(idx), refLat, refLon);
        }
        Waybill w = c.waybill;
        if (w.sendLat != 0 || w.sendLon != 0) {
            xy.add(GeoUtil.project(w.sendLat, w.sendLon, refLat, refLon));
        }
        if (w.receiveLat != 0 || w.receiveLon != 0) {
            xy.add(GeoUtil.project(w.receiveLat, w.receiveLon, refLat, refLon));
        }
        return GeoUtil.polygonAreaM2(GeoUtil.convexHull(xy));
    }

    /** 投影 + 毫米级去重后加入点集 */
    private static void addUnique(List<double[]> xy, java.util.Set<Long> seen, TrackPoint p,
                                  double refLat, double refLon) {
        double[] v = GeoUtil.project(p.lat, p.lon, refLat, refLon);
        long key = (Math.round(v[0] * 1000) << 21) ^ Math.round(v[1] * 1000);
        if (seen.add(key)) xy.add(v);
    }

    /** 压缩失真速度比：压缩后相邻保留点推算速度 > 限速的段占比 */
    static double distortionSpeedRatio(SupplementContext.WaybillCtx c, List<Integer> kept, double speedLimitKph) {
        if (kept == null || kept.size() < 2) return 0;
        List<TrackPoint> pts = c.cleaned.points;
        int over = 0;
        for (int i = 0; i + 1 < kept.size(); i++) {
            if (DriftFilter.inferredSpeedKph(pts.get(kept.get(i)), pts.get(kept.get(i + 1))) > speedLimitKph) {
                over++;
            }
        }
        return (double) over / (kept.size() - 1);
    }
}
