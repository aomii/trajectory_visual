package com.logicompress.supplement;

import com.logicompress.experiment.config.ExperimentConfig;
import com.logicompress.experiment.encode.BlockOffsetCodec;
import com.logicompress.experiment.model.TrackPoint;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 表 6-14：偏移量经验熵下界 vs 编码后字节率。
 *
 * <p>对保留点序列的六个字段（纬度/经度/速度/高程/航向/时间）量化后做一阶差分 → ZigZag，
 * 统计各字段偏移量取值的经验熵（bit/偏移量），求和得到"每点经验熵下界"；
 * 再与"整流编码（整链单块）+ DEFLATE"后的字节率对比，得到编码器相对熵下界的倍数。
 *
 * <p>该表用于如实说明：本文无损层的差异化价值在分块随机访问，而非熵最优（见论文 6.4.2）。
 */
public final class EntropyBenchmark {

    private static final String[] FIELD_NAMES = {"lat", "lon", "spd", "hgt", "agl", "gtm"};

    private EntropyBenchmark() {
    }

    public static void run(ExperimentConfig cfg, List<SupplementContext.WaybillCtx> ctxs, Path outDir)
            throws IOException {
        long t0 = System.currentTimeMillis();
        double factor = Math.pow(10, cfg.precision);
        Map<Integer, Map<Long, Long>> hist = new HashMap<>();
        long[] totalDeltas = new long[6];
        long[] totalVarintChars = new long[6];
        long zippedTotal = 0;
        long nPoints = 0;

        for (SupplementContext.WaybillCtx c : ctxs) {
            List<Integer> kept = SupplementContext.ourKept(c, cfg);
            if (kept == null || kept.isEmpty()) continue;
            List<TrackPoint> pts = SupplementContext.materialize(c.cleaned, kept);
            nPoints += pts.size();
            long[] prev = new long[6];
            for (TrackPoint p : pts) {
                long[] cur = quantize(p, factor);
                for (int f = 0; f < 6; f++) {
                    long offset = cur[f] - prev[f];
                    prev[f] = cur[f];
                    long zz = offset << 1;
                    if (zz < 0) zz = ~zz;                       // ZigZag：负数映射为正
                    hist.computeIfAbsent(f, k -> new HashMap<>()).merge(zz, 1L, Long::sum);
                    totalDeltas[f]++;
                    totalVarintChars[f] += varintChars(zz);
                }
            }
            // 整流编码（整链单块）+ DEFLATE：每运单独立存储，与生产形态一致
            zippedTotal += BlockOffsetCodec.deflate(
                    BlockOffsetCodec.encodePoints(pts, cfg.precision), cfg.zipLevel).length;
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
            double varintBits = varintCharsPer * 7.0;            // ASCII 7 位/字符
            sumEntropyBits += h;
            sumVarintBits += varintBits;
            rows.add(new String[]{FIELD_NAMES[f], SupplementTables.r4(h),
                    SupplementTables.r4(varintCharsPer), SupplementTables.r4(varintBits)});
        }
        rows.add(new String[]{"**合计（每点）**", "**" + SupplementTables.r4(sumEntropyBits) + "**",
                SupplementTables.r4(sumVarintBits / 7.0), "**" + SupplementTables.r4(sumVarintBits) + "**"});
        SupplementTables.both(outDir, "table6_14_entropy_fields",
                "表 6-14a 各字段偏移量经验熵 vs 5 位分片编码长度（" + nPoints + " 点）",
                "经验熵按各字段 ZigZag 偏移量取值分布计算；5 位分片按 ASCII 7 位/字符折算比特。",
                new String[]{"字段", "经验熵(bit/偏移量)", "分片字符数/偏移量", "分片比特/偏移量"}, rows);

        double zipBitsPerPoint = nPoints == 0 ? 0 : zippedTotal * 8.0 / nPoints;
        List<String[]> sum = new ArrayList<>();
        sum.add(new String[]{"保留点数", String.valueOf(nPoints)});
        sum.add(new String[]{"经验熵下界（bit/点）", "**" + SupplementTables.r4(sumEntropyBits) + "**"});
        sum.add(new String[]{"整流编码 + DEFLATE 后（bit/点）", SupplementTables.r4(zipBitsPerPoint)
                + "（" + SupplementTables.r4(zipBitsPerPoint / 8) + " B/点）"});
        sum.add(new String[]{"相对熵下界", "**" + SupplementTables.r4(sumEntropyBits == 0 ? 0
                : zipBitsPerPoint / sumEntropyBits) + "×**"});
        SupplementTables.both(outDir, "table6_14_entropy_summary",
                "表 6-14 偏移量经验熵 vs 编码后字节率（" + nPoints + " 点）",
                "整流编码 = 整链单块（不分块）编码 + DEFLATE，代表「放弃随机访问」时的字节极限。",
                new String[]{"指标", "数值"}, sum);

        System.out.printf(Locale.ROOT, "[表6-14] 完成：保留点 %,d，经验熵 %.2f bit/点，编码后 %.2f bit/点（%.2f×），耗时 %.1fs%n",
                nPoints, sumEntropyBits, zipBitsPerPoint,
                sumEntropyBits == 0 ? 0 : zipBitsPerPoint / sumEntropyBits,
                (System.currentTimeMillis() - t0) / 1000.0);
    }

    /** 四舍五入量化（与 BlockOffsetCodec 口径一致） */
    static long quant(double x) {
        return (long) Math.copySign(Math.round(Math.abs(x)), x);
    }

    static long[] quantize(TrackPoint p, double factor) {
        return new long[]{quant(p.lat * factor), quant(p.lon * factor), quant(p.spdKph * factor),
                quant(p.hgt * factor), quant(p.aglHeading * factor), p.gtmEpoch};
    }

    /** 5 位分片编码占用字符数 */
    static int varintChars(long zz) {
        int chars = 0;
        do {
            chars++;
            zz >>= 5;
        } while (zz >= 0x20);
        return chars;
    }
}
