package com.logicompress.supplement;

import com.logicompress.experiment.config.ExperimentConfig;
import com.logicompress.experiment.encode.BlockEncoder;
import com.logicompress.experiment.encode.EncodedStore;
import com.logicompress.experiment.encode.PartialDecoder;
import com.logicompress.experiment.eval.Metrics;
import com.logicompress.experiment.model.TrackPoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 表 6-12 / 表 6-13：无损层规模与编码解码耗时基准。
 *
 * <p>对象是"本文有损层抽稀后的保留点序列"（与主实验一致），做分块偏移量编码 + DEFLATE：
 * <ul>
 *   <li>表 6-12：朴素文本字节 / 编码后字节 / 每点字节 / 无损层压缩率 / 总压缩率；</li>
 *   <li>表 6-13：编码（分块编码 + DEFLATE）、全量解压、按时间窗部分解压的微基准（3 轮预热 + 30 轮）。</li>
 * </ul>
 */
public final class LosslessBenchmark {

    /** 表 6-12 的聚合容器 */
    private static final class SizeAgg {
        long naiveBytes, zippedBytes, keptPoints, nClean, chunkCount;
        int n;
        /** 部分解压一致性校验：窗口内"部分解压取回点数 == 全量解压同窗口点数"的运单数 */
        int partialChecked, partialCountConsistent, partialFieldConsistent, partialHashConsistent;
        final List<Double> crLossless = new ArrayList<>();
        final List<Double> crTotal = new ArrayList<>();
    }

    private LosslessBenchmark() {
    }

    /** 表 6-12：无损层压缩结果（同时顺带校验部分解压与全量解压的一致性） */
    public static void runSize(ExperimentConfig cfg, List<SupplementContext.WaybillCtx> ctxs, Path outDir)
            throws IOException {
        long t0 = System.currentTimeMillis();
        SizeAgg a = new SizeAgg();
        for (SupplementContext.WaybillCtx c : ctxs) {
            List<Integer> kept = SupplementContext.ourKept(c, cfg);
            if (kept == null || kept.isEmpty()) continue;
            List<TrackPoint> keptPts = SupplementContext.materialize(c.cleaned, kept);
            long naive = Metrics.naiveAsciiBytes(keptPts, cfg.precision);
            EncodedStore store = new BlockEncoder().encode(c.cleaned.points, kept, c.semantic.anchors, cfg);
            long zipped = store.totalZippedBytes();

            a.n++;
            a.nClean += c.nClean;
            a.keptPoints += kept.size();
            a.naiveBytes += naive;
            a.zippedBytes += zipped;
            a.chunkCount += store.blocks.size();
            if (zipped > 0) {
                double crLossless = (double) naive / zipped;
                double crLossy = (double) c.nClean / kept.size();
                a.crLossless.add(crLossless);
                a.crTotal.add(crLossy * crLossless);
            }
            // 正确性校验：同一时间窗，部分解压取回的点数必须与全量解压后过滤完全一致
            long[] win = SupplementContext.queryWindow(c, cfg);
            List<TrackPoint> partial = new PartialDecoder().decodeWindow(store, win[0], win[1], cfg.precision).points;
            List<TrackPoint> full = new ArrayList<>();
            for (TrackPoint p : PartialDecoder.decodeAll(store, cfg.precision))
                if (p.gtmEpoch >= win[0] && p.gtmEpoch <= win[1]) full.add(p);
            a.partialChecked++;
            if (partial.size() == full.size()) a.partialCountConsistent++;
            if (samePoints(partial, full)) a.partialFieldConsistent++;
            if (sha256(partial).equals(sha256(full))) a.partialHashConsistent++;
        }
        double ratio = a.zippedBytes == 0 ? 0 : (double) a.naiveBytes / a.zippedBytes;
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"有效运单数", String.valueOf(a.n)});
        rows.add(new String[]{"保留点数", String.valueOf(a.keptPoints)});
        rows.add(new String[]{"分块数", String.valueOf(a.chunkCount)});
        rows.add(new String[]{"朴素文本字节（基准，六字段各保留 "
                + cfg.precision + " 位小数）", String.format(Locale.ROOT, "%,d", a.naiveBytes)
                + "（约 " + SupplementTables.r2((double) a.naiveBytes / Math.max(1, a.keptPoints)) + " B/点）"});
        rows.add(new String[]{"分块偏移量编码 + DEFLATE 后字节", String.format(Locale.ROOT, "%,d", a.zippedBytes)
                + "（**" + SupplementTables.r2((double) a.zippedBytes / Math.max(1, a.keptPoints)) + " B/点**）"});
        rows.add(new String[]{"无损层压缩率（全局合计字节口径）", "**" + SupplementTables.r2(ratio) + "×**"});
        rows.add(new String[]{"无损层压缩率（逐运单均值 / 中位）",
                SupplementTables.r2(mean(a.crLossless)) + " / " + SupplementTables.r2(median(a.crLossless))});
        rows.add(new String[]{"总压缩率 CR_total（逐运单相乘）均值 / 中位",
                "**" + SupplementTables.r2(mean(a.crTotal)) + "** / **" + SupplementTables.r2(median(a.crTotal)) + "**"});
        rows.add(new String[]{"部分解压与全量解压一致性（点数）",
                "**" + a.partialCountConsistent + " / " + a.partialChecked + "**"});
        rows.add(new String[]{"部分解压与全量解压一致性（六字段及顺序逐点比较）",
                "**" + a.partialFieldConsistent + " / " + a.partialChecked + "**"});
        rows.add(new String[]{"部分解压与全量解压一致性（规范序列 SHA-256）",
                "**" + a.partialHashConsistent + " / " + a.partialChecked + "**"});

        SupplementTables.both(outDir, "table6_12_lossless_summary",
                "表 6-12 无损层压缩结果（" + a.n + " 运单合计，" + String.format(Locale.ROOT, "%,d", a.keptPoints) + " 点）",
                "块长 " + cfg.blockWindowS + " s；朴素文本 = 六字段各按 " + cfg.precision
                        + " 位小数格式化的 ASCII 文本字节，作为无损层压缩前基准。",
                new String[]{"指标", "数值"}, rows);

        System.out.printf(Locale.ROOT, "[表6-12] 完成：运单 %d，保留点 %,d，朴素 %,d B → 编码 %,d B（%.2f×，%.2f B/点），耗时 %.1fs%n",
                a.n, a.keptPoints, a.naiveBytes, a.zippedBytes, ratio,
                (double) a.zippedBytes / Math.max(1, a.keptPoints), (System.currentTimeMillis() - t0) / 1000.0);
    }

    /** 表 6-13：编码/解码微基准（3 轮预热 + 30 轮，随机位置与 15/60/120 min 查询窗） */
    public static void runBench(ExperimentConfig cfg, List<SupplementContext.WaybillCtx> ctxs, Path outDir)
            throws IOException {
        long t0 = System.currentTimeMillis();
        final int WARMUP_ROUNDS = 3;
        final int ROUNDS = 30;
        Map<String, List<Double>> phaseMs = new LinkedHashMap<>();
        phaseMs.put("encode", new ArrayList<>());
        phaseMs.put("decode_all", new ArrayList<>());
        phaseMs.put("decode_window", new ArrayList<>());
        Map<String, List<Double>> sampleMs = new LinkedHashMap<>();
        sampleMs.put("encode", new ArrayList<>());
        sampleMs.put("decode_all", new ArrayList<>());
        sampleMs.put("decode_window", new ArrayList<>());
        List<String[]> roundRows = new ArrayList<>();
        long totalKeptPts = 0;
        long totalNaive = 0, totalZipped = 0;

        for (int r = 0; r < WARMUP_ROUNDS + ROUNDS; r++) {
            double msEncode = 0, msDecAll = 0, msDecWin = 0;
            long keptTotal = 0;
            long naive = 0, zipped = 0;
            for (SupplementContext.WaybillCtx c : ctxs) {
                List<Integer> kept = SupplementContext.ourKept(c, cfg);
                if (kept == null || kept.isEmpty()) continue;
                long[] win = randomizedWindow(c, cfg, r);
                long t1 = System.nanoTime();
                EncodedStore store = new BlockEncoder().encode(c.cleaned.points, kept, c.semantic.anchors, cfg);
                long t2 = System.nanoTime();
                PartialDecoder.decodeAll(store, cfg.precision);
                long t3 = System.nanoTime();
                new PartialDecoder().decodeWindow(store, win[0], win[1], cfg.precision);
                long t4 = System.nanoTime();
                double oneEncode = (t2 - t1) / 1e6;
                double oneAll = (t3 - t2) / 1e6;
                double oneWin = (t4 - t3) / 1e6;
                msEncode += oneEncode;
                msDecAll += oneAll;
                msDecWin += oneWin;
                if (r >= WARMUP_ROUNDS) {
                    sampleMs.get("encode").add(oneEncode);
                    sampleMs.get("decode_all").add(oneAll);
                    sampleMs.get("decode_window").add(oneWin);
                }
                keptTotal += kept.size();
                naive += Metrics.naiveAsciiBytes(SupplementContext.materialize(c.cleaned, kept), cfg.precision);
                zipped += store.totalZippedBytes();
            }
            if (r < WARMUP_ROUNDS) continue;
            totalKeptPts = keptTotal;
            totalNaive = naive;
            totalZipped = zipped;
            phaseMs.get("encode").add(msEncode);
            phaseMs.get("decode_all").add(msDecAll);
            phaseMs.get("decode_window").add(msDecWin);
            int measuredRound = r - WARMUP_ROUNDS + 1;
            roundRows.add(new String[]{String.valueOf(measuredRound), "encode", SupplementTables.r2(msEncode),
                    String.valueOf(keptTotal), SupplementTables.r4(msEncode / keptTotal * 1000)});
            roundRows.add(new String[]{String.valueOf(measuredRound), "decode_all", SupplementTables.r2(msDecAll),
                    String.valueOf(keptTotal), SupplementTables.r4(msDecAll / keptTotal * 1000)});
            roundRows.add(new String[]{String.valueOf(measuredRound), "decode_window", SupplementTables.r2(msDecWin),
                    String.valueOf(keptTotal), SupplementTables.r4(msDecWin / keptTotal * 1000)});
        }
        SupplementTables.csv(outDir, "table6_13_bench_rounds",
                new String[]{"round", "phase", "total_ms", "n_points", "ms_per_1000pts"}, roundRows);

        String[] phaseName = {"编码（分块编码 + DEFLATE）", "全量解压", "部分解压（随机 15/60/120 min 窗）"};
        String[] phaseKey = {"encode", "decode_all", "decode_window"};
        List<String[]> rows = new ArrayList<>();
        double encMed = 0, allMed = 0, winMed = 0;
        for (int i = 0; i < phaseKey.length; i++) {
            List<Double> v = new ArrayList<>(phaseMs.get(phaseKey[i]));
            Collections.sort(v);
            double median = percentile(v, 0.50);
            if (i == 0) encMed = median;
            if (i == 1) allMed = median;
            if (i == 2) winMed = median;
            List<Double> samples = sampleMs.get(phaseKey[i]);
            rows.add(new String[]{phaseName[i], SupplementTables.r2(median),
                    SupplementTables.r4(percentile(samples, 0.50)),
                    SupplementTables.r4(percentile(samples, 0.95)),
                    SupplementTables.r4(percentile(samples, 0.99)),
                    SupplementTables.r4(stddev(samples)),
                    SupplementTables.r4(median / totalKeptPts * 1000)});
        }
        rows.add(new String[]{"轮次总耗时比（全量解压 ÷ 部分解压）",
                "**" + SupplementTables.r2(winMed == 0 ? 0 : allMed / winMed) + "×**",
                "—", "—", "—", "—", "—"});

        SupplementTables.both(outDir, "table6_13_bench_summary",
                "表 6-13 编码/解码耗时（" + ctxs.size() + " 运单 / "
                        + String.format(Locale.ROOT, "%,d", totalKeptPts) + " 点，30 轮）",
                "JVM 预热 3 轮后重复 30 轮；查询窗按固定随机种子改变位置，并在 15/60/120 min 中轮换。堆上限 "
                        + Runtime.getRuntime().maxMemory() / 1024 / 1024 + " MB。"
                        + "编码环节只含分块编码 + DEFLATE（抽稀在计时区外）。"
                        + (totalNaive > 0 ? String.format(Locale.ROOT, "编码后总字节 %,d。", totalZipped) : ""),
                new String[]{"环节", "轮次总耗时P50(ms)", "单运单P50(ms)", "单运单P95(ms)",
                        "单运单P99(ms)", "单运单标准差(ms)", "每千点(ms)"}, rows);

        System.out.printf(Locale.ROOT, "[表6-13] 完成：运单 %d，保留点 %,d，编码中位 %.1f ms，全量 %.1f ms，部分 %.2f ms，耗时 %.1fs%n",
                ctxs.size(), totalKeptPts, encMed, allMed, winMed, (System.currentTimeMillis() - t0) / 1000.0);
    }

    private static double mean(List<Double> v) {
        return v.isEmpty() ? 0 : v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double median(List<Double> v) {
        if (v.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }

    private static double percentile(List<Double> values, double p) {
        if (values == null || values.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(values);
        Collections.sort(s);
        double pos = p * (s.size() - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        if (lo == hi) return s.get(lo);
        return s.get(lo) * (hi - pos) + s.get(hi) * (pos - lo);
    }

    private static double stddev(List<Double> values) {
        if (values == null || values.size() < 2) return 0;
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double ss = 0;
        for (double x : values) ss += (x - mean) * (x - mean);
        return Math.sqrt(ss / (values.size() - 1));
    }

    private static long[] randomizedWindow(SupplementContext.WaybillCtx c, ExperimentConfig cfg, int round) {
        if (c.cleaned.points.isEmpty()) return new long[]{0, 0};
        long first = c.cleaned.points.get(0).gtmEpoch;
        long last = c.cleaned.points.get(c.cleaned.points.size() - 1).gtmEpoch;
        long[] lengths = {900, 3600, 7200};
        long len = lengths[Math.floorMod(round + c.waybill.waybillNo.hashCode(), lengths.length)];
        long span = Math.max(0, last - first - len);
        long seed = 1469598103934665603L ^ c.waybill.waybillNo.hashCode() ^ (round * 1099511628211L);
        long offset = span == 0 ? 0 : Math.floorMod(seed, span + 1);
        long start = first + offset;
        return new long[]{start, Math.min(last, start + len)};
    }

    private static boolean samePoints(List<TrackPoint> a, List<TrackPoint> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            TrackPoint x = a.get(i), y = b.get(i);
            if (x.gtmEpoch != y.gtmEpoch
                    || Double.compare(x.lat, y.lat) != 0
                    || Double.compare(x.lon, y.lon) != 0
                    || Double.compare(x.spdKph, y.spdKph) != 0
                    || Double.compare(x.aglHeading, y.aglHeading) != 0
                    || Double.compare(x.hgt, y.hgt) != 0) return false;
        }
        return true;
    }

    private static String sha256(List<TrackPoint> points) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (TrackPoint p : points) {
                String row = p.gtmEpoch + "," + Double.toString(p.lat) + "," + Double.toString(p.lon)
                        + "," + Double.toString(p.spdKph) + "," + Double.toString(p.aglHeading)
                        + "," + Double.toString(p.hgt) + "\n";
                md.update(row.getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder out = new StringBuilder();
            for (byte x : md.digest()) out.append(String.format(Locale.ROOT, "%02x", x & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
