package com.logicompress.supplement;

import com.logicompress.experiment.config.ExperimentConfig;
import com.logicompress.experiment.encode.BlockEncoder;
import com.logicompress.experiment.encode.EncodedStore;
import com.logicompress.experiment.encode.PartialDecoder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** 固定种子的内存解码微基准；不把结果称为数据库端到端读取耗时。 */
public final class RandomWindowBenchmark {
    public static final long SEED = 20260925L;
    private static final int REPEATS = 5;
    private static final long[] WINDOWS = {900, 3600, 7200};

    private RandomWindowBenchmark() { }

    public static void run(ExperimentConfig cfg, List<SupplementContext.WaybillCtx> ctxs, Path outDir)
            throws IOException {
        List<List<Double>> latency = new ArrayList<>();
        for (int i = 0; i < WINDOWS.length; i++) latency.add(new ArrayList<>());
        long[] read = new long[WINDOWS.length], total = new long[WINDOWS.length];
        long[] hits = new long[WINDOWS.length], cross = new long[WINDOWS.length];
        int[] n = new int[WINDOWS.length];
        List<String[]> per = new ArrayList<>();
        BlockEncoder encoder = new BlockEncoder();
        PartialDecoder decoder = new PartialDecoder();
        for (SupplementContext.WaybillCtx c : ctxs) {
            List<Integer> kept = SupplementContext.ourKept(c, cfg);
            if (kept == null || kept.isEmpty()) continue;
            EncodedStore store = encoder.encode(c.cleaned.points, kept, c.semantic.anchors, cfg);
            long start = c.cleaned.points.get(0).gtmEpoch;
            long end = c.cleaned.points.get(c.cleaned.points.size() - 1).gtmEpoch;
            Random random = new Random(SEED ^ c.waybill.waybillId);
            for (int k = 0; k < WINDOWS.length; k++) {
                long width = WINDOWS[k];
                for (int rep = 0; rep < REPEATS; rep++) {
                    long maxOffset = Math.max(0, end - start - width);
                    long t1 = start + (maxOffset == 0 ? 0 : (long) (random.nextDouble() * maxOffset));
                    // Warm-up of the same code path, excluded from timing.
                    decoder.decodeWindow(store, t1, t1 + width, cfg.precision);
                    long t0 = System.nanoTime();
                    PartialDecoder.WindowResult result = decoder.decodeWindow(store, t1, t1 + width, cfg.precision);
                    double ms = (System.nanoTime() - t0) / 1e6;
                    n[k]++; read[k] += result.bytesRead; total[k] += store.totalZippedBytes();
                    hits[k] += result.blocksHit;
                    if (result.blocksHit >= 2) cross[k]++;
                    latency.get(k).add(ms);
                    per.add(new String[]{c.waybill.waybillNo, String.valueOf(width), String.valueOf(rep),
                            String.valueOf(t1), String.valueOf(result.blocksHit), String.valueOf(result.bytesRead),
                            String.valueOf(store.totalZippedBytes()), SupplementTables.r4(ms)});
                }
            }
        }
        SupplementTables.csv(outDir, "random_window_per_query",
                new String[]{"waybill_no", "window_s", "replicate", "query_start_epoch_s", "blocks_hit",
                        "payload_bytes_read", "total_payload_bytes", "decode_ms"}, per);
        List<String[]> rows = new ArrayList<>();
        for (int k = 0; k < WINDOWS.length; k++) {
            List<Double> sorted = latency.get(k);
            Collections.sort(sorted);
            rows.add(new String[]{String.valueOf(WINDOWS[k]), String.valueOf(n[k]),
                    SupplementTables.r4(n[k] == 0 ? 0 : (double) read[k] / total[k]),
                    SupplementTables.r4(n[k] == 0 ? 0 : (double) hits[k] / n[k]),
                    String.valueOf(cross[k]), SupplementTables.r4(percentile(sorted, .5)),
                    SupplementTables.r4(percentile(sorted, .95))});
        }
        SupplementTables.both(outDir, "random_window_summary", "随机查询窗内存解码微基准",
                "默认 3600 s 块；窗口 15/60/120 min；每运单每档 5 次，种子 " + SEED
                        + " 与运单 ID 异或，单次预热后计时。计时含内存块扫描、负载拷贝、解码和过滤，"
                        + "不含磁盘/数据库 I/O、候选元数据读取与正确性校验；不能作为数据库端到端延迟。",
                new String[]{"窗口(s)", "查询次数", "负载读取比(全局)", "命中块数均值", "跨至少2块次数", "解码P50(ms)", "解码P95(ms)"}, rows);
        System.out.printf(Locale.ROOT, "[随机窗口] 完成 %d 次内存微基准%n", n[0] + n[1] + n[2]);
    }

    private static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        double pos = p * (sorted.size() - 1);
        int lo = (int) pos, hi = (int) Math.ceil(pos);
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (pos - lo);
    }
}
