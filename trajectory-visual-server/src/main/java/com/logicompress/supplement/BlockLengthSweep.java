package com.logicompress.supplement;

import com.logicompress.experiment.config.ExperimentConfig;
import com.logicompress.experiment.encode.BlockEncoder;
import com.logicompress.experiment.encode.EncodedStore;
import com.logicompress.experiment.encode.PartialDecoder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 表 6-16：块长权衡曲线（压缩率 vs 局部查询粒度）。
 *
 * <p>对同一保留点序列在不同块长下重编码，测每点字节与同一查询窗的部分解压字节比。
 * 块长越大：块间重复结构少、字节率更低，但一次窗口查询命中的块更粗、随机访问变弱。
 */
public final class BlockLengthSweep {

    /** 扫描档位（秒）：覆盖 5 min ~ 1 h，含当前默认 3600 */
    private static final long[] BLOCK_SIZES = {300, 600, 900, 1200, 1800, 2400, 3600};

    private static final class BlockAgg {
        int n;
        long sumZipped, sumPoints, sumBlocks, sumBytesRead;
        double sumRatio;

        void add(long zipped, int points, int blocks, int bytesRead) {
            n++;
            sumZipped += zipped;
            sumPoints += points;
            sumBlocks += blocks;
            sumBytesRead += bytesRead;
            sumRatio += (zipped == 0) ? 0 : (double) bytesRead / zipped;
        }
    }

    private BlockLengthSweep() {
    }

    public static void run(ExperimentConfig cfg, List<SupplementContext.WaybillCtx> ctxs, Path outDir)
            throws IOException {
        long t0 = System.currentTimeMillis();
        long defaultBlock = cfg.blockWindowS;          // 记下默认值，扫完恢复，避免影响后续实验
        Map<Long, BlockAgg> agg = new LinkedHashMap<>();
        List<String[]> perWaybill = new ArrayList<>();

        for (SupplementContext.WaybillCtx c : ctxs) {
            List<Integer> kept = SupplementContext.ourKept(c, cfg);
            if (kept == null || kept.isEmpty()) continue;
            long[] win = SupplementContext.queryWindow(c, cfg);
            for (long bs : BLOCK_SIZES) {
                cfg.blockWindowS = bs;
                EncodedStore store = new BlockEncoder().encode(c.cleaned.points, kept, c.semantic.anchors, cfg);
                PartialDecoder.WindowResult wr = new PartialDecoder()
                        .decodeWindow(store, win[0], win[1], cfg.precision);
                BlockAgg a = agg.computeIfAbsent(bs, k -> new BlockAgg());
                a.add(store.totalZippedBytes(), kept.size(), store.blocks.size(), wr.bytesRead);
                perWaybill.add(new String[]{c.waybill.waybillNo, String.valueOf(bs),
                        String.valueOf(store.totalZippedBytes()), String.valueOf(kept.size()),
                        String.valueOf(store.blocks.size()), String.valueOf(wr.bytesRead)});
            }
        }
        cfg.blockWindowS = defaultBlock;

        SupplementTables.csv(outDir, "table6_16_block_sweep_per_waybill",
                new String[]{"waybill_no", "block_window_s", "zipped_bytes", "n_points",
                        "n_blocks", "bytes_read"}, perWaybill);

        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<Long, BlockAgg> e : agg.entrySet()) {
            BlockAgg a = e.getValue();
            long bs = e.getKey();
            rows.add(new String[]{bs + (bs == defaultBlock ? "（本文默认）" : ""),
                    String.format(Locale.ROOT, "%,d", a.sumZipped),
                    SupplementTables.r4((double) a.sumZipped / a.sumPoints),
                    String.valueOf(a.sumBlocks),
                    SupplementTables.r4((double) a.sumBytesRead / a.sumZipped),
                    SupplementTables.r4(a.sumRatio / a.n)});
        }
        SupplementTables.both(outDir, "table6_16_block_tradeoff",
                "表 6-16 块长权衡（" + agg.values().stream().mapToInt(x -> x.n).max().orElse(0)
                        + " 运单，同一保留点序列）",
                "查询窗口径：轨迹 60% 处取 " + (cfg.queryWindowS / 60) + " min。"
                        + "「部分解压字节比」= 命中块字节 / 全部块字节；全局口径=字节求和，均值口径=逐运单比例取均值。",
                new String[]{"块长(s)", "编码后总字节", "字节/点", "总块数", "部分解压字节比(全局)", "部分解压字节比(均值)"},
                rows);

        System.out.printf(Locale.ROOT, "[表6-16] 完成：%d 个块长档位，耗时 %.1fs%n",
                agg.size(), (System.currentTimeMillis() - t0) / 1000.0);
    }
}
