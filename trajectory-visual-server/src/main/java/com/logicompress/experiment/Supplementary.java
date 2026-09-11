package com.logicompress.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicompress.experiment.baseline.Dps;
import com.logicompress.experiment.baseline.LossyBaseline;
import com.logicompress.experiment.baseline.PlainDp;
import com.logicompress.experiment.baseline.TdTr;
import com.logicompress.experiment.baseline.Trajic;
import com.logicompress.experiment.clean.CleanedTrack;
import com.logicompress.experiment.clean.DriftFilter;
import com.logicompress.experiment.compress.SegmentedDp;
import com.logicompress.experiment.config.ExperimentConfig;
import com.logicompress.experiment.data.WaybillLoader;
import com.logicompress.experiment.encode.BlockEncoder;
import com.logicompress.experiment.encode.BlockOffsetCodec;
import com.logicompress.experiment.encode.EncodedStore;
import com.logicompress.experiment.encode.PartialDecoder;
import com.logicompress.experiment.eval.Metrics;
import com.logicompress.experiment.geo.GeoUtil;
import com.logicompress.experiment.model.SemanticResult;
import com.logicompress.experiment.model.StopUnit;
import com.logicompress.experiment.model.TrackPoint;
import com.logicompress.experiment.model.Waybill;
import com.logicompress.experiment.semantic.FenceMatcher;
import com.logicompress.experiment.semantic.SemanticAnalyzer;
import com.logicompress.experiment.semantic.StDbscan;
import com.logicompress.experiment.semantic.StationaryMerger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 第 6 章补充实验（对应 claude_12 的 6.3.4 / 6.6 等"待补"项）。
 *
 * <p>与 {@link Main} 共用同一套清洗/语义/压缩/编码组件，所有下标仍以
 * {@code cleaned.points} 为统一索引空间。全部模式先一次加载→清洗→语义并缓存，
 * 保证 141 个有效运单集合在模式间一致（manifest.csv 记录）。
 *
 * <p>运行方式：{@code run.sh --supplem <mode> [--key=value ...]}，
 * 结果落盘 {@code results/supplem/}。
 */
public final class Supplementary {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Supplementary() {
    }

    // ------------------------------------------------------------------
    // 入口与公共助手
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            usage();
            return;
        }
        String mode = args[0];
        ExperimentConfig cfg = new ExperimentConfig();
        cfg.applyArgs(Arrays.copyOfRange(args, 1, args.length));
        Path dir = Paths.get(cfg.resultsDir, "supplem");
        Files.createDirectories(dir);
        JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("params.json").toFile(),
                new LinkedHashMap<>(cfg.asMap()));
        switch (mode) {
            case "sweep-crsr": sweepCrsr(cfg, dir); break;
            case "ablation": ablation(cfg, dir); break;
            case "sweep-blocks": sweepBlocks(cfg, dir); break;
            case "bench": bench(cfg, dir); break;
            case "hull": hull(cfg, dir); break;
            case "scheme-ab": schemeAb(cfg, dir); break;
            case "sem-stats": semStats(cfg, dir); break;
            case "entropy": entropy(cfg, dir); break;
            case "sem-merge-compare": semMergeCompare(cfg, dir); break;
            case "sem-debug": semDebug(cfg, args.length > 1 ? args[1] : null); break;
            case "stop-vs-dbscan": stopVsDbscan(cfg, dir); break;
            default:
                System.err.println("[error] 未知模式: " + mode);
                usage();
        }
    }

    private static void usage() {
        System.out.println("用法: run.sh --supplem <mode> [--key=value ...]");
        System.out.println("模式: sweep-crsr | ablation | sweep-blocks | bench | hull | scheme-ab | sem-stats | entropy | stop-vs-dbscan | sem-merge-compare | sem-debug");
    }

    /** 运单上下文：一次加载→清洗→语义后缓存，供多个容差/块长/变体重用 */
    static final class WaybillContext {
        final Waybill waybill;
        final CleanedTrack cleaned;
        final SemanticResult semantic;
        final int nClean;

        WaybillContext(Waybill w, CleanedTrack c, SemanticResult s) {
            this.waybill = w;
            this.cleaned = c;
            this.semantic = s;
            this.nClean = c.points.size();
        }
    }

    static List<WaybillContext> loadContexts(ExperimentConfig cfg) {
        WaybillLoader loader = new WaybillLoader(cfg.trackDataDir, cfg.waybillDataDir);
        List<Waybill> all = loader.loadAll();
        List<WaybillContext> ctxs = new ArrayList<>();
        for (Waybill w : all) {
            if (w == null || w.rawPoints == null || w.rawPoints.isEmpty()) continue;
            CleanedTrack cleaned = new DriftFilter().clean(w.rawPoints, cfg.driftSpeedKph, cfg.breakGapS);
            if (cleaned.points.size() < cfg.minPoints) continue;
            SemanticResult semantic = new SemanticAnalyzer().analyze(w, cleaned, cfg);
            ctxs.add(new WaybillContext(w, cleaned, semantic));
        }
        return ctxs;
    }

    /** 写本次实验使用的运单集合清单（可复现：141 个有效运单与 run-all 一致） */
    static void writeManifest(Path dir, List<WaybillContext> ctxs) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("waybill_no,waybill_id,n_raw,n_clean,n_stops,n_anchors");
        for (WaybillContext c : ctxs) {
            lines.add(String.join(",", c.waybill.waybillNo, String.valueOf(c.waybill.waybillId),
                    String.valueOf(c.waybill.rawPoints.size()), String.valueOf(c.nClean),
                    String.valueOf(c.semantic.stops.size()), String.valueOf(c.semantic.anchors.size())));
        }
        Files.write(dir.resolve("manifest.csv"), lines, StandardCharsets.UTF_8);
    }

    /** 本文分级 DP：设置 key/non 容差与锚点开关后调用 SegmentedDp */
    static List<Integer> ourKept(WaybillContext c, ExperimentConfig cfg, double key, double non, boolean noAnchorForce) {
        cfg.dpEpsKeyM = key;
        cfg.dpEpsNonKeyM = non;
        cfg.noAnchorForce = noAnchorForce;
        return new SegmentedDp().compress(c.cleaned, c.semantic, cfg);
    }

    static List<TrackPoint> materialize(CleanedTrack cleaned, List<Integer> kept) {
        List<TrackPoint> pts = new ArrayList<>(kept.size());
        for (int i : kept) pts.add(cleaned.points.get(i));
        return pts;
    }

    /** 固定查询窗（与 Main.processWaybill 第 ⑦ 步口径一致）：轨迹 60% 处取 1h */
    static long[] queryWindow(WaybillContext c, ExperimentConfig cfg) {
        long tStart = c.cleaned.points.get(0).gtmEpoch;
        long tEnd = c.cleaned.points.get(c.cleaned.points.size() - 1).gtmEpoch;
        long t1 = tStart + Math.round((tEnd - tStart) * cfg.queryWindowFraction);
        return new long[]{t1, t1 + cfg.queryWindowS};
    }

    static int anchorHit(Set<Integer> anchors, List<Integer> kept) {
        if (anchors == null || anchors.isEmpty()) return 0;
        Set<Integer> ks = new java.util.HashSet<>(kept);
        int hit = 0;
        for (int a : anchors) {
            if (ks.contains(a)) hit++;
        }
        return hit;
    }

    /** 单（运单×方法×容差）的全部指标：CR/SR/单元完整率/停留时长保真度/停留点保留率/PED/SED */
    static final class WayMetrics {
        double crLossy, sr, ui, df, dp, pedAvg, sedAvg;
    }

    static WayMetrics compute(WaybillContext c, List<Integer> kept) {
        WayMetrics m = new WayMetrics();
        boolean[] mask = Metrics.indexMask(kept, c.cleaned.points.size());
        m.crLossy = (double) c.nClean / kept.size();
        m.sr = Metrics.semanticRetention(c.semantic.anchors, kept);
        m.ui = Metrics.unitIntegrity(c.semantic.stops, mask);
        m.df = Metrics.dwellFidelity(c.cleaned.points, c.semantic.stops, mask);
        m.dp = Metrics.dwellPointRetention(c.semantic.stops, mask);
        Metrics.Error e = Metrics.pedSed(c.cleaned.points, kept);
        m.pedAvg = e.avgPedM;
        m.sedAvg = e.avgSedM;
        return m;
    }

    static void writeCsv(Path path, String[] header, List<String[]> rows) throws IOException {
        List<String> lines = new ArrayList<>(rows.size() + 1);
        lines.add(String.join(",", header));
        for (String[] r : rows) lines.add(String.join(",", r));
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    static String r2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    static String r4(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    // ------------------------------------------------------------------
    // sweep-crsr：同压缩率下 SR / 单元完整率 / 停留时长曲线
    // ------------------------------------------------------------------

    static final class Agg {
        int n;
        double sumNClean, sumNKept, sumAnchor, sumAnchorHit;
        double sumCr, sumSr, sumUi, sumDf, sumDp, sumPed, sumSed;

        void add(int nClean, int nKept, int anchor, int hit, WayMetrics m) {
            n++;
            sumNClean += nClean;
            sumNKept += nKept;
            sumAnchor += anchor;
            sumAnchorHit += hit;
            sumCr += m.crLossy;
            sumSr += m.sr;
            sumUi += m.ui;
            sumDf += m.df;
            sumDp += m.dp;
            sumPed += m.pedAvg;
            sumSed += m.sedAvg;
        }

        double crGlobal() {
            return sumNKept == 0 ? 0 : sumNClean / sumNKept;
        }

        double srGlobal() {
            return sumAnchor == 0 ? 1.0 : sumAnchorHit / sumAnchor;
        }

        double mean(double s) {
            return n == 0 ? 0 : s / n;
        }
    }

    static void sweepCrsr(ExperimentConfig cfg, Path dir) throws IOException {
        List<WaybillContext> ctxs = loadContexts(cfg);
        writeManifest(dir, ctxs);

        int[] tols = {5, 10, 15, 20, 30};
        LossyBaseline[] baselines = {new PlainDp(), new Dps(), new TdTr(), new Trajic()};
        String[] bNames = {"DP", "DPS", "TD-TR", "Trajic"};
        int[][] ourPairs = {{5, 8}, {8, 15}, {10, 20}, {15, 30}, {20, 40}};

        Map<String, Agg> agg = new LinkedHashMap<>();
        List<String[]> longRows = new ArrayList<>();

        for (int bi = 0; bi < baselines.length; bi++) {
            for (int tol : tols) {
                String key = bNames[bi] + "|" + tol + "|" + tol;
                for (WaybillContext c : ctxs) {
                    List<Integer> kept = baselines[bi].compress(c.cleaned, tol);
                    if (kept.isEmpty()) continue;
                    Agg a = agg.computeIfAbsent(key, k -> new Agg());
                    WayMetrics m = compute(c, kept);
                    a.add(c.nClean, kept.size(), c.semantic.anchors.size(), anchorHit(c.semantic.anchors, kept), m);
                    longRows.add(new String[]{bNames[bi], String.valueOf(tol), String.valueOf(tol),
                            c.waybill.waybillNo, String.valueOf(c.nClean), String.valueOf(kept.size()),
                            r4(m.crLossy), r4(m.sr), r4(m.ui), r4(m.df), r4(m.dp), r2(m.pedAvg), r2(m.sedAvg)});
                }
            }
        }
        for (int tol : tols) {
            String key = "OursUniform|" + tol + "|" + tol;
            for (WaybillContext c : ctxs) {
                List<Integer> kept = ourKept(c, cfg, tol, tol, false);
                Agg a = agg.computeIfAbsent(key, k -> new Agg());
                WayMetrics m = compute(c, kept);
                a.add(c.nClean, kept.size(), c.semantic.anchors.size(), anchorHit(c.semantic.anchors, kept), m);
                longRows.add(new String[]{"OursUniform", String.valueOf(tol), String.valueOf(tol),
                        c.waybill.waybillNo, String.valueOf(c.nClean), String.valueOf(kept.size()),
                        r4(m.crLossy), r4(m.sr), r4(m.ui), r4(m.df), r4(m.dp), r2(m.pedAvg), r2(m.sedAvg)});
            }
        }
        for (int[] pair : ourPairs) {
            String key = "OursGraded|" + pair[0] + "|" + pair[1];
            for (WaybillContext c : ctxs) {
                List<Integer> kept = ourKept(c, cfg, pair[0], pair[1], false);
                Agg a = agg.computeIfAbsent(key, k -> new Agg());
                WayMetrics m = compute(c, kept);
                a.add(c.nClean, kept.size(), c.semantic.anchors.size(), anchorHit(c.semantic.anchors, kept), m);
                longRows.add(new String[]{"OursGraded", String.valueOf(pair[0]), String.valueOf(pair[1]),
                        c.waybill.waybillNo, String.valueOf(c.nClean), String.valueOf(kept.size()),
                        r4(m.crLossy), r4(m.sr), r4(m.ui), r4(m.df), r4(m.dp), r2(m.pedAvg), r2(m.sedAvg)});
            }
        }

        String[] longHeader = {"method", "tol_key", "tol_non", "waybill_no", "n_clean", "n_kept",
                "cr_lossy", "sr", "unit_integrity", "dwell_fidelity", "dwell_point_retention", "ped_avg_m", "sed_avg_m"};
        writeCsv(dir.resolve("sweep_crsr_long.csv"), longHeader, longRows);

        List<String[]> aggRows = new ArrayList<>();
        for (Map.Entry<String, Agg> e : agg.entrySet()) {
            String[] p = e.getKey().split("\\|");
            Agg a = e.getValue();
            aggRows.add(new String[]{p[0], p[1], p[2], String.valueOf(a.n),
                    r4(a.crGlobal()), r4(a.mean(a.sumCr)), r4(a.srGlobal()), r4(a.mean(a.sumSr)),
                    r4(a.mean(a.sumUi)), r4(a.mean(a.sumDf)), r4(a.mean(a.sumDp)), r2(a.mean(a.sumPed)), r2(a.mean(a.sumSed))});
        }
        String[] aggHeader = {"method", "tol_key", "tol_non", "n_waybills", "cr_global", "cr_mean",
                "sr_global", "sr_mean", "unit_integrity_mean", "dwell_fidelity_mean", "dwell_point_retention_mean",
                "ped_avg_m_mean", "sed_avg_m_mean"};
        writeCsv(dir.resolve("sweep_crsr_agg.csv"), aggHeader, aggRows);
        System.out.println("[sweep-crsr] 完成，运单数=" + ctxs.size() + "，组合数=" + agg.size());
    }

    // ------------------------------------------------------------------
    // ablation：去分级 / 去锚点 / 去无损层 / 去分块索引
    // ------------------------------------------------------------------

    static final class AbAgg {
        int n;
        double sumNClean, sumNKept, sumAnchor, sumAnchorHit;
        double sumUi, sumDf, sumDp, sumPed, sumSed;
        double sumNaive, sumStored;

        void add(int nClean, int nKept, int anchor, int hit, WayMetrics m, long naive, long stored) {
            n++;
            sumNClean += nClean;
            sumNKept += nKept;
            sumAnchor += anchor;
            sumAnchorHit += hit;
            sumUi += m.ui;
            sumDf += m.df;
            sumDp += m.dp;
            sumPed += m.pedAvg;
            sumSed += m.sedAvg;
            sumNaive += naive;
            sumStored += stored;
        }

        double mean(double s) {
            return n == 0 ? 0 : s / n;
        }
    }

    /** losslessMode：0=分块编码(normal)，1=naive 本身(A3 去无损层)，2=整流编码(A4 去分块索引) */
    static int losslessMode(String variant) {
        if ("A3-no-lossless".equals(variant)) return 1;
        if ("A4-wholestream".equals(variant)) return 2;
        return 0;
    }

    /** 各消融变体的 key/non 容差（A1 为统一 10m，其余 8/15m） */
    static String[] variantTols(String variant) {
        return "A1-uniform".equals(variant) ? new String[]{"10", "10"} : new String[]{"8", "15"};
    }

    static void emitAblRow(WaybillContext c, List<String[]> longRows, Map<String, AbAgg> agg,
                           String variant, double key, double non, boolean anchorForce,
                           List<Integer> kept, WayMetrics m, long naive, long stored, double partial, int mode) {
        double crLossless = (mode == 1) ? 1.0 : (double) naive / stored;
        double crTotal = m.crLossy * crLossless;
        longRows.add(new String[]{variant, c.waybill.waybillNo, String.valueOf(c.nClean),
                String.valueOf(kept.size()), r4(m.crLossy), r4(crLossless), r4(crTotal),
                r4(m.sr), r4(m.ui), r4(m.df), r4(m.dp), r2(m.pedAvg), r2(m.sedAvg),
                String.valueOf(stored), partial < 0 ? "" : r4(partial)});
        AbAgg a = agg.computeIfAbsent(variant, k -> new AbAgg());
        a.add(c.nClean, kept.size(), c.semantic.anchors.size(), anchorHit(c.semantic.anchors, kept), m, naive, stored);
    }

    static void ablation(ExperimentConfig cfg, Path dir) throws IOException {
        List<WaybillContext> ctxs = loadContexts(cfg);
        writeManifest(dir, ctxs);
        List<String[]> longRows = new ArrayList<>();
        Map<String, AbAgg> agg = new LinkedHashMap<>();

        for (WaybillContext c : ctxs) {
            // 基准 A0：分级(8,15)+锚点，一次算好 naive/分块/整流 三种字节
            List<Integer> kept0 = ourKept(c, cfg, 8, 15, false);
            if (kept0 == null || kept0.isEmpty()) continue;
            List<TrackPoint> keptPts0 = materialize(c.cleaned, kept0);
            long naive0 = Metrics.naiveAsciiBytes(keptPts0, cfg.precision);
            EncodedStore store0 = new BlockEncoder().encode(c.cleaned.points, kept0, c.semantic.anchors, cfg);
            long zipped0 = store0.totalZippedBytes();
            double partial0 = partialRatio(c, store0, cfg);
            long whole0 = BlockOffsetCodec.deflate(BlockOffsetCodec.encodePoints(keptPts0, cfg.precision), cfg.zipLevel).length;
            WayMetrics m0 = compute(c, kept0);

            emitAblRow(c, longRows, agg, "A0-full", 8, 15, true, kept0, m0, naive0, zipped0, partial0, 0);
            emitAblRow(c, longRows, agg, "A3-no-lossless", 8, 15, true, kept0, m0, naive0, naive0, -1, 1);
            emitAblRow(c, longRows, agg, "A4-wholestream", 8, 15, true, kept0, m0, naive0, whole0, -1, 2);

            // A1 去分级：统一容差(10,10)，锚点仍保留
            List<Integer> kept1 = ourKept(c, cfg, 10, 10, false);
            List<TrackPoint> keptPts1 = materialize(c.cleaned, kept1);
            long naive1 = Metrics.naiveAsciiBytes(keptPts1, cfg.precision);
            EncodedStore store1 = new BlockEncoder().encode(c.cleaned.points, kept1, c.semantic.anchors, cfg);
            double partial1 = partialRatio(c, store1, cfg);
            WayMetrics m1 = compute(c, kept1);
            emitAblRow(c, longRows, agg, "A1-uniform", 10, 10, true, kept1, m1, naive1, store1.totalZippedBytes(), partial1, 0);

            // A2 去锚点：分级(8,15)但锚点不强制保留
            List<Integer> kept2 = ourKept(c, cfg, 8, 15, true);
            List<TrackPoint> keptPts2 = materialize(c.cleaned, kept2);
            long naive2 = Metrics.naiveAsciiBytes(keptPts2, cfg.precision);
            EncodedStore store2 = new BlockEncoder().encode(c.cleaned.points, kept2, c.semantic.anchors, cfg);
            double partial2 = partialRatio(c, store2, cfg);
            WayMetrics m2 = compute(c, kept2);
            emitAblRow(c, longRows, agg, "A2-no-anchor", 8, 15, false, kept2, m2, naive2, store2.totalZippedBytes(), partial2, 0);
        }

        String[] longHeader = {"variant", "waybill_no", "n_clean", "n_kept", "cr_lossy", "cr_lossless", "cr_total",
                "sr", "unit_integrity", "dwell_fidelity", "dwell_point_retention", "ped_avg_m", "sed_avg_m",
                "bytes", "partial_bytes_ratio"};
        writeCsv(dir.resolve("ablation_long.csv"), longHeader, longRows);

        List<String[]> aggRows = new ArrayList<>();
        for (Map.Entry<String, AbAgg> e : agg.entrySet()) {
            String variant = e.getKey();
            AbAgg a = e.getValue();
            double crLossyG = a.sumNKept == 0 ? 0 : a.sumNClean / a.sumNKept;
            double crLosslessG = (losslessMode(variant) == 1) ? 1.0
                    : (a.sumStored == 0 ? 0 : a.sumNaive / a.sumStored);
            double bytesTotal = (losslessMode(variant) == 1) ? a.sumNaive : a.sumStored;
            double srG = a.sumAnchor == 0 ? 1.0 : a.sumAnchorHit / a.sumAnchor;
            boolean anchor = !"A2-no-anchor".equals(variant);
            String[] tols = variantTols(variant);
            aggRows.add(new String[]{variant, tols[0], tols[1], String.valueOf(anchor), r4(crLossyG), r4(crLosslessG),
                    r4(crLossyG * crLosslessG), r4(srG), r4(a.mean(a.sumUi)), r4(a.mean(a.sumDf)), r4(a.mean(a.sumDp)),
                    r2(a.mean(a.sumPed)), r2(a.mean(a.sumSed)),
                    String.valueOf((long) bytesTotal), r4(bytesTotal / (a.sumNKept == 0 ? 1 : a.sumNKept))});
        }
        String[] aggHeader = {"variant", "key_m", "non_m", "anchor_force", "cr_lossy_global", "cr_lossless_global",
                "cr_total_global", "sr_global", "unit_integrity_mean", "dwell_fidelity_mean", "dwell_point_retention_mean",
                "ped_avg_m_mean", "sed_avg_m_mean", "bytes_total", "bytes_per_point"};
        writeCsv(dir.resolve("ablation_agg.csv"), aggHeader, aggRows);
        System.out.println("[ablation] 完成，运单数=" + ctxs.size());
    }

    // ------------------------------------------------------------------
    // sweep-blocks：块长权衡曲线
    // ------------------------------------------------------------------

    static final class BlockAgg {
        int n;
        long sumZipped, sumPoints, sumBlocks, sumBytesRead;
        double sumRatio; // 逐运单 partial_bytes_ratio 之和（均值口径与主实验一致）

        void add(long zipped, int points, int blocks, int bytesRead) {
            n++;
            sumZipped += zipped;
            sumPoints += points;
            sumBlocks += blocks;
            sumBytesRead += bytesRead;
            sumRatio += (zipped == 0) ? 0 : (double) bytesRead / zipped;
        }
    }

    static void sweepBlocks(ExperimentConfig cfg, Path dir) throws IOException {
        List<WaybillContext> ctxs = loadContexts(cfg);
        writeManifest(dir, ctxs);
        long[] blockSizes = {300, 600, 900, 1200, 1800, 2400, 3600};
        Map<Long, BlockAgg> agg = new LinkedHashMap<>();
        for (WaybillContext c : ctxs) {
            List<Integer> kept = ourKept(c, cfg, 8, 15, false);
            if (kept == null || kept.isEmpty()) continue;
            long[] win = queryWindow(c, cfg);
            for (long bs : blockSizes) {
                cfg.blockWindowS = bs;
                EncodedStore store = new BlockEncoder().encode(c.cleaned.points, kept, c.semantic.anchors, cfg);
                PartialDecoder.WindowResult wr = new PartialDecoder().decodeWindow(store, win[0], win[1], cfg.precision);
                BlockAgg a = agg.computeIfAbsent(bs, k -> new BlockAgg());
                a.add(store.totalZippedBytes(), kept.size(), store.blocks.size(), wr.bytesRead);
            }
        }
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<Long, BlockAgg> e : agg.entrySet()) {
            BlockAgg a = e.getValue();
            rows.add(new String[]{String.valueOf(e.getKey()), String.valueOf(a.n),
                    String.valueOf(a.sumZipped), r4((double) a.sumZipped / a.sumPoints),
                    String.valueOf(a.sumBlocks), r2((double) a.sumBytesRead / a.sumZipped),
                    r4(a.sumRatio / a.n)});
        }
        String[] header = {"block_window_s", "n_waybills", "zipped_bytes_total", "bytes_per_point",
                "total_blocks", "partial_bytes_ratio_global", "partial_bytes_ratio_mean"};
        writeCsv(dir.resolve("block_sweep.csv"), header, rows);
        System.out.println("[sweep-blocks] 完成，块长档位=" + rows.size());
    }

    // ------------------------------------------------------------------
    // bench：编码/解码耗时严格基准（1 轮 warmup + 5 轮）
    // ------------------------------------------------------------------

    static void bench(ExperimentConfig cfg, Path dir) throws IOException {
        List<WaybillContext> ctxs = loadContexts(cfg);
        writeManifest(dir, ctxs);
        final int ROUNDS = 5;
        Map<String, List<Double>> phaseMs = new LinkedHashMap<>();
        phaseMs.put("encode", new ArrayList<>());
        phaseMs.put("decode_all", new ArrayList<>());
        phaseMs.put("decode_window", new ArrayList<>());
        List<String[]> roundRows = new ArrayList<>();
        int totalKeptPts = 0;

        for (int r = 0; r <= ROUNDS; r++) {
            double msEncode = 0, msDecAll = 0, msDecWin = 0;
            int keptTotal = 0;
            for (WaybillContext c : ctxs) {
                List<Integer> kept = ourKept(c, cfg, cfg.dpEpsKeyM, cfg.dpEpsNonKeyM, false);
                if (kept == null || kept.isEmpty()) continue;
                long[] win = queryWindow(c, cfg);
                long t0 = System.nanoTime();
                EncodedStore store = new BlockEncoder().encode(c.cleaned.points, kept, c.semantic.anchors, cfg);
                long t1 = System.nanoTime();
                List<TrackPoint> all = PartialDecoder.decodeAll(store, cfg.precision);
                long t2 = System.nanoTime();
                PartialDecoder.WindowResult wr = new PartialDecoder().decodeWindow(store, win[0], win[1], cfg.precision);
                long t3 = System.nanoTime();
                msEncode += (t1 - t0) / 1e6;
                msDecAll += (t2 - t1) / 1e6;
                msDecWin += (t3 - t2) / 1e6;
                keptTotal += kept.size();
            }
            if (r == 0) continue; // warmup 轮只跑不计时
            totalKeptPts = keptTotal;
            phaseMs.get("encode").add(msEncode);
            phaseMs.get("decode_all").add(msDecAll);
            phaseMs.get("decode_window").add(msDecWin);
            roundRows.add(new String[]{String.valueOf(r), "encode", r2(msEncode), String.valueOf(keptTotal), r4(msEncode / keptTotal * 1000)});
            roundRows.add(new String[]{String.valueOf(r), "decode_all", r2(msDecAll), String.valueOf(keptTotal), r4(msDecAll / keptTotal * 1000)});
            roundRows.add(new String[]{String.valueOf(r), "decode_window", r2(msDecWin), String.valueOf(keptTotal), r4(msDecWin / keptTotal * 1000)});
        }
        writeCsv(dir.resolve("bench_rounds.csv"),
                new String[]{"round", "phase", "total_ms", "n_points", "ms_per_1000pts"}, roundRows);

        List<String[]> summaryRows = new ArrayList<>();
        for (Map.Entry<String, List<Double>> e : phaseMs.entrySet()) {
            List<Double> v = e.getValue();
            Collections.sort(v);
            double mean = v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double median = v.get(v.size() / 2);
            double min = v.get(0);
            double max = v.get(v.size() - 1);
            summaryRows.add(new String[]{e.getKey(), r2(mean), r2(median), r2(min), r2(max),
                    r4(median / totalKeptPts * 1000),
                    System.getProperty("java.version"),
                    String.valueOf(Runtime.getRuntime().maxMemory() / 1024 / 1024) + "MB"});
        }
        writeCsv(dir.resolve("bench_summary.csv"),
                new String[]{"phase", "mean_ms", "median_ms", "min_ms", "max_ms", "median_ms_per_1000pts", "jvm_version", "heap"},
                summaryRows);
        System.out.println("[bench] 完成，运单数=" + ctxs.size() + "，保留点数=" + totalKeptPts);
    }

    // ------------------------------------------------------------------
    // hull：凸包保形度 + 压缩失真速度比
    // ------------------------------------------------------------------

    static final class HullAgg {
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

    /** 凸包面积：keptIndices 对应的轨迹点 + 装/卸货围栏中心点（两侧同用，控制变量） */
    static double hullArea(WaybillContext c, List<Integer> keptIndices, double refLat, double refLon) {
        List<double[]> xy = new ArrayList<>();
        if (keptIndices == null) {
            for (TrackPoint p : c.cleaned.points) {
                xy.add(GeoUtil.project(p.lat, p.lon, refLat, refLon));
            }
        } else {
            for (int idx : keptIndices) {
                TrackPoint p = c.cleaned.points.get(idx);
                xy.add(GeoUtil.project(p.lat, p.lon, refLat, refLon));
            }
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

    static double distortionSpeedRatio(WaybillContext c, List<Integer> kept, double speedLimitKph) {
        if (kept.size() < 2) return 0;
        List<TrackPoint> pts = c.cleaned.points;
        int over = 0;
        for (int i = 0; i + 1 < kept.size(); i++) {
            if (DriftFilter.inferredSpeedKph(pts.get(kept.get(i)), pts.get(kept.get(i + 1))) > speedLimitKph) {
                over++;
            }
        }
        return (double) over / (kept.size() - 1);
    }

    static void emitHullRow(WaybillContext c, List<String[]> longRows, Map<String, HullAgg> agg,
                            String method, List<Integer> kept, double areaOrig, double refLat, double refLon,
                            double speedLimitKph) {
        double areaKept = hullArea(c, kept, refLat, refLon);
        double ratio = areaOrig <= 0 ? 0 : areaKept / areaOrig;
        double dist = distortionSpeedRatio(c, kept, speedLimitKph);
        HullAgg a = agg.computeIfAbsent(method, k -> new HullAgg());
        a.add(ratio, dist);
        longRows.add(new String[]{method, c.waybill.waybillNo, r2(areaOrig), r2(areaKept), r4(ratio), r4(dist)});
    }

    static void hull(ExperimentConfig cfg, Path dir) throws IOException {
        List<WaybillContext> ctxs = loadContexts(cfg);
        writeManifest(dir, ctxs);
        List<String[]> longRows = new ArrayList<>();
        Map<String, HullAgg> agg = new LinkedHashMap<>();
        LossyBaseline[] baselines = {new PlainDp(), new Dps(), new TdTr(), new Trajic()};
        String[] names = {"DP", "DPS", "TD-TR", "Trajic"};

        for (WaybillContext c : ctxs) {
            double refLat = c.cleaned.points.get(0).lat;
            double refLon = c.cleaned.points.get(0).lon;
            double areaOrig = hullArea(c, null, refLat, refLon);
            if (areaOrig <= 0) continue;
            List<Integer> keptOurs = ourKept(c, cfg, 8, 15, false);
            emitHullRow(c, longRows, agg, "Ours", keptOurs, areaOrig, refLat, refLon, cfg.speedLimitKph);
            for (int i = 0; i < baselines.length; i++) {
                List<Integer> kept = baselines[i].compress(c.cleaned, cfg.baselineDpM);
                if (kept == null || kept.isEmpty()) continue;
                emitHullRow(c, longRows, agg, names[i], kept, areaOrig, refLat, refLon, cfg.speedLimitKph);
            }
        }
        writeCsv(dir.resolve("hull_long.csv"),
                new String[]{"method", "waybill_no", "area_orig_m2", "area_kept_m2", "area_ratio", "distortion_speed_ratio"},
                longRows);

        List<String[]> aggRows = new ArrayList<>();
        for (Map.Entry<String, HullAgg> e : agg.entrySet()) {
            HullAgg a = e.getValue();
            List<Double> sorted = new ArrayList<>(a.ratios);
            Collections.sort(sorted);
            double med = sorted.get(sorted.size() / 2);
            aggRows.add(new String[]{e.getKey(), String.valueOf(a.n),
                    r4(a.sumRatio / a.n), r4(med), r4(a.sumDist / a.n)});
        }
        writeCsv(dir.resolve("hull_agg.csv"),
                new String[]{"method", "n_waybills", "area_ratio_mean", "area_ratio_median", "distortion_speed_ratio_mean"},
                aggRows);
        System.out.println("[hull] 完成，运单数=" + ctxs.size());
    }

    // ------------------------------------------------------------------
    // scheme-ab：方案 A（查询时抽稀）vs 方案 B（压缩时抽稀+分块）
    // ------------------------------------------------------------------

    static void schemeAb(ExperimentConfig cfg, Path dir) throws IOException {
        List<WaybillContext> ctxs = loadContexts(cfg);
        writeManifest(dir, ctxs);
        List<String[]> rows = new ArrayList<>();
        long sumABytes = 0, sumBBytes = 0, sumARet = 0, sumBRet = 0;
        double sumAQueryMs = 0, sumBQueryMs = 0;
        int n = 0;

        for (WaybillContext c : ctxs) {
            // 方案 A：全量点整流偏移量编码存储；查询 = 全量 inflate+解码 + 查询时 DP(10m) + 过滤窗口
            String aAscii = BlockOffsetCodec.encodePoints(c.cleaned.points, cfg.precision);
            byte[] aBytes = BlockOffsetCodec.deflate(aAscii, cfg.zipLevel);
            long[] win = queryWindow(c, cfg);
            long t0 = System.nanoTime();
            List<TrackPoint> aPts = BlockOffsetCodec.decodeToPoints(BlockOffsetCodec.inflate(aBytes), cfg.precision);
            CleanedTrack aTrack = new CleanedTrack(aPts,
                    Collections.singletonList(new int[]{0, Math.max(0, aPts.size() - 1)}), 0);
            List<Integer> aKept = new PlainDp().compress(aTrack, cfg.baselineDpM);
            int aWin = 0;
            for (int idx : aKept) {
                long t = aTrack.points.get(idx).gtmEpoch;
                if (t >= win[0] && t <= win[1]) aWin++;
            }
            long t1 = System.nanoTime();

            // 方案 B：分级抽稀(8,15)+分块编码存储；查询 = 按时间窗部分解压
            List<Integer> kept = ourKept(c, cfg, 8, 15, false);
            if (kept == null || kept.isEmpty()) continue;
            EncodedStore store = new BlockEncoder().encode(c.cleaned.points, kept, c.semantic.anchors, cfg);
            long bBytes = store.totalZippedBytes();
            long t2 = System.nanoTime();
            PartialDecoder.WindowResult wr = new PartialDecoder().decodeWindow(store, win[0], win[1], cfg.precision);
            long t3 = System.nanoTime();

            n++;
            sumABytes += aBytes.length;
            sumBBytes += bBytes;
            sumARet += aWin;
            sumBRet += wr.points.size();
            sumAQueryMs += (t1 - t0) / 1e6;
            sumBQueryMs += (t3 - t2) / 1e6;
            rows.add(new String[]{"A", c.waybill.waybillNo, String.valueOf(aBytes.length), r4((t1 - t0) / 1e6), String.valueOf(aWin)});
            rows.add(new String[]{"B", c.waybill.waybillNo, String.valueOf(bBytes), r4((t3 - t2) / 1e6), String.valueOf(wr.points.size())});
        }
        writeCsv(dir.resolve("scheme_ab.csv"),
                new String[]{"variant", "waybill_no", "storage_bytes", "query_ms", "returned_points"}, rows);

        List<String[]> summary = new ArrayList<>();
        summary.add(new String[]{"A", String.valueOf(n), String.valueOf(sumABytes), r2(sumAQueryMs), r4(sumAQueryMs / n), String.valueOf(sumARet),
                "全量整流偏移量编码存储；查询=全量解压+DP(10m)"});
        summary.add(new String[]{"B", String.valueOf(n), String.valueOf(sumBBytes), r2(sumBQueryMs), r4(sumBQueryMs / n), String.valueOf(sumBRet),
                "分级抽稀+分块编码存储；查询=按时间窗部分解压"});
        writeCsv(dir.resolve("scheme_ab_summary.csv"),
                new String[]{"variant", "n_waybills", "storage_bytes_total", "query_ms_total", "query_ms_mean", "returned_points_total", "note"},
                summary);
        System.out.println("[scheme-ab] 完成，运单数=" + n);
    }

    // ------------------------------------------------------------------
    // sem-stats：停留类别分布 + L1 业务真值覆盖率（诚实口径，非查准/查全）
    // ------------------------------------------------------------------

    static void semStats(ExperimentConfig cfg, Path dir) throws IOException {
        List<WaybillContext> ctxs = loadContexts(cfg);
        writeManifest(dir, ctxs);
        List<String[]> rows = new ArrayList<>();
        int withStops = 0, withLoad = 0, withUnload = 0, withAny = 0;
        for (WaybillContext c : ctxs) {
            int nLoad = 0, nUnload = 0, nRest = 0, nTraffic = 0, nOther = 0, nFence = 0;
            for (StopUnit u : c.semantic.stops) {
                if (u.fromFence) nFence++;
                switch (u.label == null ? "" : u.label) {
                    case "LOAD": nLoad++; break;
                    case "UNLOAD": nUnload++; break;
                    case "REST": nRest++; break;
                    case "TRAFFIC": nTraffic++; break;
                    default: nOther++;
                }
            }
            boolean hasLoad = nLoad > 0, hasUnload = nUnload > 0;
            if (!c.semantic.stops.isEmpty()) withStops++;
            if (hasLoad) withLoad++;
            if (hasUnload) withUnload++;
            if (hasLoad || hasUnload) withAny++;
            rows.add(new String[]{c.waybill.waybillNo, String.valueOf(c.semantic.stops.size()),
                    String.valueOf(nLoad), String.valueOf(nUnload), String.valueOf(nRest),
                    String.valueOf(nTraffic), String.valueOf(nOther), String.valueOf(nFence),
                    String.valueOf(hasLoad), String.valueOf(hasUnload)});
        }
        writeCsv(dir.resolve("sem_stats.csv"),
                new String[]{"waybill_no", "n_stops", "n_load", "n_unload", "n_rest", "n_traffic", "n_other",
                        "n_fence_units", "has_load", "has_unload"}, rows);
        List<String[]> summary = new ArrayList<>();
        summary.add(new String[]{String.valueOf(ctxs.size()), String.valueOf(withStops),
                String.valueOf(withLoad), String.valueOf(withUnload), String.valueOf(withAny),
                r4((double) withAny / ctxs.size())});
        writeCsv(dir.resolve("sem_stats_summary.csv"),
                new String[]{"n_waybills", "n_waybills_with_stops", "n_waybills_with_load", "n_waybills_with_unload",
                        "n_waybills_with_any_loadunload", "frac_any_loadunload"}, summary);
        System.out.println("[sem-stats] 完成，运单数=" + ctxs.size()
                + "，L1 识别到装卸货单元运单占比=" + r4((double) withAny / ctxs.size()));
    }

    // ------------------------------------------------------------------
    // sem-merge-compare：同坐标静止合并改进前 vs 改进后 对照
    // ------------------------------------------------------------------

    static void semMergeCompare(ExperimentConfig cfg, Path dir) throws IOException {
        WaybillLoader loader = new WaybillLoader(cfg.trackDataDir, cfg.waybillDataDir);
        List<Waybill> all = loader.loadAll();
        List<String[]> rows = new ArrayList<>();
        int n = 0;
        long beforeStops = 0, afterStops = 0, beforeAnchors = 0, afterAnchors = 0;
        long beforeDwell = 0, afterDwell = 0, beforeLoad = 0, beforeUnload = 0, afterLoad = 0, afterUnload = 0;
        int beforeWithAny = 0, afterWithAny = 0;
        for (Waybill w : all) {
            if (w == null || w.rawPoints == null || w.rawPoints.isEmpty()) continue;
            CleanedTrack cleaned = new DriftFilter().clean(w.rawPoints, cfg.driftSpeedKph, cfg.breakGapS);
            if (cleaned.points.size() < cfg.minPoints) continue;
            n++;
            cfg.stationaryMergeEnabled = false;
            SemanticResult before = new SemanticAnalyzer().analyze(w, cleaned, cfg);
            cfg.stationaryMergeEnabled = true;
            SemanticResult after = new SemanticAnalyzer().analyze(w, cleaned, cfg);

            long bDwell = 0, aDwell = 0;
            for (StopUnit u : before.stops) bDwell += u.durationS();
            for (StopUnit u : after.stops) aDwell += u.durationS();
            int bLoad = 0, bUnload = 0, aLoad = 0, aUnload = 0;
            for (StopUnit u : before.stops) {
                if ("LOAD".equals(u.label)) bLoad++;
                if ("UNLOAD".equals(u.label)) bUnload++;
            }
            for (StopUnit u : after.stops) {
                if ("LOAD".equals(u.label)) aLoad++;
                if ("UNLOAD".equals(u.label)) aUnload++;
            }
            boolean bAny = bLoad + bUnload > 0, aAny = aLoad + aUnload > 0;
            beforeStops += before.stops.size();
            afterStops += after.stops.size();
            beforeAnchors += before.anchors.size();
            afterAnchors += after.anchors.size();
            beforeDwell += bDwell;
            afterDwell += aDwell;
            if (bAny) beforeWithAny++;
            if (aAny) afterWithAny++;
            beforeLoad += bLoad;
            beforeUnload += bUnload;
            afterLoad += aLoad;
            afterUnload += aUnload;

            rows.add(new String[]{w.waybillNo,
                    String.valueOf(before.stops.size()), String.valueOf(after.stops.size()),
                    String.valueOf(before.anchors.size()), String.valueOf(after.anchors.size()),
                    String.valueOf(bDwell), String.valueOf(aDwell),
                    String.valueOf(bLoad), String.valueOf(bUnload), String.valueOf(aLoad), String.valueOf(aUnload)});
        }
        writeCsv(dir.resolve("sem_merge_compare.csv"),
                new String[]{"waybill_no", "stops_before", "stops_after", "anchors_before", "anchors_after",
                        "dwell_s_before", "dwell_s_after", "load_before", "unload_before", "load_after", "unload_after"},
                rows);
        List<String[]> sum = new ArrayList<>();
        sum.add(new String[]{String.valueOf(n),
                String.valueOf(beforeStops), String.valueOf(afterStops),
                String.valueOf(beforeAnchors), String.valueOf(afterAnchors),
                String.valueOf(beforeDwell), String.valueOf(afterDwell),
                r4((double) beforeStops / n), r4((double) afterStops / n),
                r4((double) beforeDwell / beforeStops), r4((double) afterDwell / afterStops),
                String.valueOf(beforeWithAny), String.valueOf(afterWithAny),
                r4((double) beforeWithAny / n), r4((double) afterWithAny / n),
                String.valueOf(beforeLoad), String.valueOf(beforeUnload), String.valueOf(afterLoad), String.valueOf(afterUnload)});
        writeCsv(dir.resolve("sem_merge_compare_summary.csv"),
                new String[]{"n_waybills", "stops_total_before", "stops_total_after", "anchors_total_before", "anchors_total_after",
                        "dwell_s_total_before", "dwell_s_total_after", "stops_mean_before", "stops_mean_after",
                        "dwell_mean_s_before", "dwell_mean_s_after",
                        "waybills_with_loadunload_before", "waybills_with_loadunload_after",
                        "frac_with_loadunload_before", "frac_with_loadunload_after",
                        "load_before", "unload_before", "load_after", "unload_after"}, sum);
        System.out.println("[sem-merge-compare] 完成，运单数=" + n
                + "，停留单元 " + beforeStops + " → " + afterStops
                + "，锚点 " + beforeAnchors + " → " + afterAnchors
                + "，总停留时长 " + (beforeDwell / 3600) + "h → " + (afterDwell / 3600) + "h");
    }

    // ------------------------------------------------------------------
    // stop-vs-dbscan：静态识别(方案1) vs ST-DBSCAN 对比（v2 选型论证）
    // ------------------------------------------------------------------

    /** ST-DBSCAN 独立停留识别（作为对比基线，无围栏种子）：簇时长 ≥ dMinS → REST/TRAFFIC 单元 */
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

    /** 时长分位数：[中位, P90, 最大] */
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

    /** 贪心匹配：A 中每个单元与未占用的 B 中重叠 ≥50% 的单元配对。返回匹配数；onlyA/onlyB 为未匹配下标数 */
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

    /** 与任一围栏单元(LOAD/UNLOAD)时间重叠 ≥50% 的单元数（业务锚定度） */
    static int fenceOverlapCount(List<StopUnit> stops, List<StopUnit> fenceUnits) {
        int c = 0;
        for (StopUnit s : stops) {
            for (StopUnit f : fenceUnits) {
                if (StationaryMerger.overlapRatio(s, f) >= 0.5) { c++; break; }
            }
        }
        return c;
    }

    static void stopVsDbscan(ExperimentConfig cfg, Path dir) throws IOException {
        WaybillLoader loader = new WaybillLoader(cfg.trackDataDir, cfg.waybillDataDir);
        List<Waybill> all = loader.loadAll();
        List<String[]> rows = new ArrayList<>();
        List<Double> sDurs = new ArrayList<>(), dDurs = new ArrayList<>();
        long sStops = 0, dStops = 0, sLong = 0, dLong = 0, sOnly = 0, dOnly = 0, both = 0, sFence = 0, dFence = 0;
        int n = 0;
        for (Waybill w : all) {
            if (w == null || w.rawPoints == null || w.rawPoints.isEmpty()) continue;
            CleanedTrack cleaned = new DriftFilter().clean(w.rawPoints, cfg.driftSpeedKph, cfg.breakGapS);
            if (cleaned.points.size() < cfg.minPoints) continue;
            n++;
            List<TrackPoint> pts = cleaned.points;
            List<StopUnit> staticStops = StationaryMerger.merge(pts, cfg);
            List<StopUnit> dbStops = dbscanDetect(pts, cfg);
            // 围栏装卸货单元（业务锚定参考，非识别主路径）
            SemanticResult tmp = new SemanticResult();
            FenceMatcher fm = new FenceMatcher();
            fm.match(w, pts, cfg, tmp, new FenceMatcher.FenceHit(), new FenceMatcher.FenceHit());
            List<StopUnit> fenceUnits = tmp.stops;

            int[] onlyA = new int[1], onlyB = new int[1];
            int inter = matchOverlap(staticStops, dbStops, onlyA, onlyB);
            int sF = fenceOverlapCount(staticStops, fenceUnits);
            int dF = fenceOverlapCount(dbStops, fenceUnits);
            int sl = 0, dl = 0;
            for (StopUnit u : staticStops) { sDurs.add(u.durationS()); if (u.durationS() >= SemanticAnalyzer.REST_MIN_S) sl++; }
            for (StopUnit u : dbStops) { dDurs.add(u.durationS()); if (u.durationS() >= SemanticAnalyzer.REST_MIN_S) dl++; }

            sStops += staticStops.size();
            dStops += dbStops.size();
            sLong += sl;
            dLong += dl;
            sOnly += onlyA[0];
            dOnly += onlyB[0];
            both += inter;
            sFence += sF;
            dFence += dF;
            rows.add(new String[]{w.waybillNo, String.valueOf(staticStops.size()), String.valueOf(dbStops.size()),
                    String.valueOf(sl), String.valueOf(dl), String.valueOf(onlyA[0]), String.valueOf(onlyB[0]),
                    String.valueOf(inter), String.valueOf(sF), String.valueOf(dF),
                    String.valueOf(staticStops.size() * 2), String.valueOf(dbStops.size() * 2)});
        }
        writeCsv(dir.resolve("stop_merge_vs_dbscan.csv"),
                new String[]{"waybill_no", "static_stops", "dbscan_stops", "static_long_30min", "dbscan_long_30min",
                        "static_only", "dbscan_only", "overlap_both", "static_fence_overlap", "dbscan_fence_overlap",
                        "static_anchors", "dbscan_anchors"}, rows);

        double[] ss = durStats(sDurs), ds = durStats(dDurs);
        List<String[]> sum = new ArrayList<>();
        sum.add(new String[]{String.valueOf(n), String.valueOf(sStops), String.valueOf(dStops),
                String.valueOf(sLong), String.valueOf(dLong), String.valueOf(sOnly), String.valueOf(dOnly),
                String.valueOf(both), String.valueOf(sFence), String.valueOf(dFence),
                r2(ss[0]), r2(ds[0]), r2(ss[1]), r2(ds[1]), r2(ss[2]), r2(ds[2])});
        writeCsv(dir.resolve("stop_merge_vs_dbscan_summary.csv"),
                new String[]{"n_waybills", "static_stops_total", "dbscan_stops_total",
                        "static_long_total", "dbscan_long_total", "static_only_total", "dbscan_only_total",
                        "overlap_both_total", "static_fence_overlap_total", "dbscan_fence_overlap_total",
                        "static_dur_median_s", "dbscan_dur_median_s", "static_dur_p90_s", "dbscan_dur_p90_s",
                        "static_dur_max_s", "dbscan_dur_max_s"}, sum);
        System.out.println("[stop-vs-dbscan] 完成，运单数=" + n
                + "，静态停留 " + sStops + " vs ST-DBSCAN " + dStops
                + "（交集 " + both + "，仅静态 " + sOnly + "，仅DBSCAN " + dOnly + "），"
                + "长停(>30min) 静态 " + sLong + " vs DBSCAN " + dLong);
    }

    /** 诊断：打印单个运单改进前/后的停留单元（startIdx/endIdx/tStart~tEnd/label） */
    static void semDebug(ExperimentConfig cfg, String waybillNo) {
        WaybillLoader loader = new WaybillLoader(cfg.trackDataDir, cfg.waybillDataDir);
        List<Waybill> all = loader.loadAll();
        Waybill w = all.stream().filter(x -> x != null && x.waybillNo.equals(waybillNo)).findFirst().orElse(null);
        if (w == null) {
            System.err.println("[sem-debug] 未找到运单 " + waybillNo);
            return;
        }
        CleanedTrack cleaned = new DriftFilter().clean(w.rawPoints, cfg.driftSpeedKph, cfg.breakGapS);
        cfg.stationaryMergeEnabled = false;
        SemanticResult before = new SemanticAnalyzer().analyze(w, cleaned, cfg);
        cfg.stationaryMergeEnabled = true;
        SemanticResult after = new SemanticAnalyzer().analyze(w, cleaned, cfg);
        System.out.println("== 改进前 (" + waybillNo + ") 停留 " + before.stops.size() + " 锚点 " + before.anchors.size() + " ==");
        for (StopUnit u : before.stops) {
            System.out.printf("  %-8s idx[%d,%d] %s ~ %s dur=%.0fs%n", u.label, u.startIdx, u.endIdx,
                    u.tStart, u.tEnd, u.durationS());
        }
        System.out.println("== 静止段（findSegments 原始输出）==");
        for (StopUnit u : StationaryMerger.findSegments(cleaned.points, cfg)) {
            System.out.printf("  %-8s idx[%d,%d] %s ~ %s dur=%.0fs%n", u.label, u.startIdx, u.endIdx,
                    u.tStart, u.tEnd, u.durationS());
        }
        System.out.println("== 改进后 停留 " + after.stops.size() + " 锚点 " + after.anchors.size() + " ==");
        for (StopUnit u : after.stops) {
            System.out.printf("  %-8s idx[%d,%d] %s ~ %s dur=%.0fs%n", u.label, u.startIdx, u.endIdx,
                    u.tStart, u.tEnd, u.durationS());
        }
    }

    // ------------------------------------------------------------------
    // entropy：偏移量分布经验熵 vs 5位分片长度 vs zip 后（第 4 章 4.3.8）
    // ------------------------------------------------------------------

    static long quant(double x) {
        return (long) Math.copySign(Math.round(Math.abs(x)), x);
    }

    static long[] quantize(TrackPoint p, double factor) {
        return new long[]{quant(p.lat * factor), quant(p.lon * factor), quant(p.spdKph * factor),
                quant(p.hgt * factor), quant(p.aglHeading * factor), p.gtmEpoch};
    }

    static int varintChars(long zz) {
        int chars = 0;
        do {
            chars++;
            zz >>= 5;
        } while (zz >= 0x20);
        return chars;
    }

    static void entropy(ExperimentConfig cfg, Path dir) throws IOException {
        List<WaybillContext> ctxs = loadContexts(cfg);
        writeManifest(dir, ctxs);
        double factor = Math.pow(10, cfg.precision);
        String[] fieldNames = {"lat", "lon", "spd", "hgt", "agl", "gtm"};
        // 逐字段：delta 值(zigzag 非负)→计数；总偏移量数；varint 总字符数
        Map<Integer, Map<Long, Long>> hist = new HashMap<>();
        long[] totalDeltas = new long[6];
        long[] totalVarintChars = new long[6];
        long zippedTotal = 0;
        long nPoints = 0;

        for (WaybillContext c : ctxs) {
            List<Integer> kept = ourKept(c, cfg, 8, 15, false);
            if (kept == null || kept.isEmpty()) continue;
            List<TrackPoint> pts = materialize(c.cleaned, kept);
            nPoints += pts.size();
            long[] prev = new long[6];
            for (TrackPoint p : pts) {
                long[] cur = quantize(p, factor);
                for (int f = 0; f < 6; f++) {
                    long offset = cur[f] - prev[f];
                    prev[f] = cur[f];
                    long zz = offset << 1;
                    if (zz < 0) zz = ~zz;
                    Map<Long, Long> h = hist.computeIfAbsent(f, k -> new HashMap<>());
                    h.merge(zz, 1L, Long::sum);
                    totalDeltas[f]++;
                    totalVarintChars[f] += varintChars(zz);
                }
            }
            // 整流编码 + DEFLATE 的字节（每运单独立存储，与生产一致）
            zippedTotal += BlockOffsetCodec.deflate(BlockOffsetCodec.encodePoints(pts, cfg.precision), cfg.zipLevel).length;
        }

        double sumEntropyBits = 0, sumVarintBits = 0;
        List<String[]> rows = new ArrayList<>();
        for (int f = 0; f < 6; f++) {
            long total = totalDeltas[f];
            if (total == 0) continue;
            double h = 0;
            for (long cnt : hist.getOrDefault(f, Collections.emptyMap()).values()) {
                double p = (double) cnt / total;
                h -= p * (Math.log(p) / Math.log(2));
            }
            double varintCharsPer = (double) totalVarintChars[f] / total;
            double varintBits = varintCharsPer * 7.0; // ASCII 7 位/字符
            sumEntropyBits += h;
            sumVarintBits += varintBits;
            rows.add(new String[]{fieldNames[f], r4(h), r4(varintCharsPer), r4(varintBits)});
        }
        // 总行：每点 6 字段合计 vs zip 后
        double zipBitsPerPoint = zippedTotal * 8.0 / nPoints;
        rows.add(new String[]{"TOTAL", r4(sumEntropyBits), r4(sumVarintBits / 7.0), r4(sumVarintBits)});
        writeCsv(dir.resolve("entropy_agg.csv"),
                new String[]{"field", "entropy_bits_per_offset", "varint_chars_per_offset", "varint_bits_per_offset"},
                rows);
        List<String[]> z = new ArrayList<>();
        z.add(new String[]{String.valueOf(nPoints), r4(sumEntropyBits), r4(sumVarintBits), r4(zipBitsPerPoint),
                r4(zipBitsPerPoint / sumEntropyBits)});
        writeCsv(dir.resolve("entropy_summary.csv"),
                new String[]{"n_points", "entropy_bits_per_point", "varint_bits_per_point", "zipped_bits_per_point", "zip_vs_entropy"},
                z);
        System.out.println("[entropy] 完成，保留点数=" + nPoints);
    }

    static double partialRatio(WaybillContext c, EncodedStore store, ExperimentConfig cfg) {
        long[] win = queryWindow(c, cfg);
        PartialDecoder.WindowResult wr = new PartialDecoder().decodeWindow(store, win[0], win[1], cfg.precision);
        int total = store.totalZippedBytes();
        return total == 0 ? 0 : (double) wr.bytesRead / total;
    }
}
