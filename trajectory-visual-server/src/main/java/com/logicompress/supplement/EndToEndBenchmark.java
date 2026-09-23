package com.logicompress.supplement;

import com.logicompress.experiment.baseline.Dps;
import com.logicompress.experiment.baseline.LossyBaseline;
import com.logicompress.experiment.baseline.PlainDp;
import com.logicompress.experiment.baseline.TdTr;
import com.logicompress.experiment.baseline.Trajic;
import com.logicompress.experiment.config.ExperimentConfig;
import com.logicompress.experiment.encode.BlockEncoder;
import com.logicompress.experiment.encode.EncodedStore;
import com.logicompress.experiment.eval.Metrics;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 完整组合方案的受控对比：各有损方法均叠加完全相同的分块偏移量编码 + DEFLATE。
 *
 * <p>输入字节统一为清洗后完整点列的规范 ASCII 字节；输出字节统一为编码负载字节。
 * 该口径直接计算 {@code inputBytes / encodedPayloadBytes}，不再把不同口径的逐运单
 * {@code CR_lossy * CR_lossless} 与只含有损层的基线混在同一列比较。MongoDB/BSON 文档、
 * 数据库索引和集合管理开销不在本受控实验内，论文中必须将结果称为“编码负载口径”。
 */
public final class EndToEndBenchmark {

    private EndToEndBenchmark() {
    }

    private static final class Agg {
        long inputBytes;
        long payloadBytes;
        long keptPoints;
        long blocks;
        long cleanPoints;
        int waybills;
        double sumPed;
        double sumSed;
        double sumSr;
        double sumUnit;
        double sumDwell;
        final List<Double> perWaybillCr = new ArrayList<>();
    }

    public static void run(ExperimentConfig cfg, List<SupplementContext.WaybillCtx> ctxs, Path outDir)
            throws IOException {
        Map<String, Agg> aggs = new LinkedHashMap<>();
        String[] names = {"本文候选锚点+DP", "DP", "DPS", "TD-TR", "Trajic"};
        LossyBaseline[] baselines = {new PlainDp(), new Dps(), new TdTr(), new Trajic()};
        for (String name : names) aggs.put(name, new Agg());

        for (SupplementContext.WaybillCtx c : ctxs) {
            long inputBytes = Metrics.naiveAsciiBytes(c.cleaned.points, cfg.precision);
            List<List<Integer>> keptByMethod = new ArrayList<>();
            keptByMethod.add(SupplementContext.ourKept(c, cfg));
            for (LossyBaseline baseline : baselines) {
                keptByMethod.add(baseline.compress(c.cleaned, cfg.baselineDpM));
            }

            for (int i = 0; i < names.length; i++) {
                List<Integer> kept = keptByMethod.get(i);
                if (kept == null || kept.isEmpty()) continue;
                EncodedStore store = new BlockEncoder().encode(
                        c.cleaned.points, kept, c.semantic.anchors, cfg);
                long payload = store.totalZippedBytes();
                if (payload <= 0) continue;

                Agg a = aggs.get(names[i]);
                a.inputBytes += inputBytes;
                a.payloadBytes += payload;
                a.keptPoints += kept.size();
                a.blocks += store.blocks.size();
                a.cleanPoints += c.nClean;
                a.waybills++;
                a.perWaybillCr.add((double) inputBytes / payload);

                Metrics.Error e = Metrics.pedSed(c.cleaned.points, kept);
                boolean[] mask = Metrics.indexMask(kept, c.cleaned.points.size());
                a.sumPed += e.avgPedM;
                a.sumSed += e.avgSedM;
                a.sumSr += Metrics.semanticRetention(c.semantic.anchors, kept);
                a.sumUnit += Metrics.unitIntegrity(c.semantic.stops, mask);
                a.sumDwell += Metrics.dwellFidelity(c.cleaned.points, c.semantic.stops, mask);
            }
        }

        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<String, Agg> entry : aggs.entrySet()) {
            Agg a = entry.getValue();
            double globalLossy = a.keptPoints == 0 ? 0 : (double) a.cleanPoints / a.keptPoints;
            double e2e = a.payloadBytes == 0 ? 0 : (double) a.inputBytes / a.payloadBytes;
            rows.add(new String[]{
                    entry.getKey(),
                    String.valueOf(a.keptPoints),
                    SupplementTables.r2(globalLossy),
                    String.valueOf(a.inputBytes),
                    String.valueOf(a.payloadBytes),
                    SupplementTables.r2(e2e),
                    SupplementTables.r2(mean(a.perWaybillCr)) + " / " + SupplementTables.r2(percentile(a.perWaybillCr, 0.50)),
                    SupplementTables.r2(percentile(a.perWaybillCr, 0.10)) + " / "
                            + SupplementTables.r2(percentile(a.perWaybillCr, 0.25)) + " / "
                            + SupplementTables.r2(percentile(a.perWaybillCr, 0.75)) + " / "
                            + SupplementTables.r2(percentile(a.perWaybillCr, 0.90)) + " / "
                            + SupplementTables.r2(percentile(a.perWaybillCr, 0.95)),
                    SupplementTables.r2(100.0 * a.sumSr / Math.max(1, a.waybills)),
                    SupplementTables.r2(100.0 * a.sumUnit / Math.max(1, a.waybills)),
                    SupplementTables.r2(100.0 * a.sumDwell / Math.max(1, a.waybills)),
                    SupplementTables.r2(a.sumPed / Math.max(1, a.waybills)),
                    SupplementTables.r2(a.sumSed / Math.max(1, a.waybills)),
                    String.valueOf(a.blocks)
            });
        }

        SupplementTables.both(outDir, "table6_end_to_end_common_encoder",
                "完整组合方案端到端对比（统一分块编码器，编码负载口径）",
                "各方法从同一清洗轨迹出发；有损后均使用同一量化精度、1 h 分块、差分/5位分片和 DEFLATE。"
                        + "输入为清洗后完整点列的规范文本字节，输出为压缩块负载字节；"
                        + "未计 MongoDB/BSON 文档与数据库索引开销。",
                new String[]{"方法", "保留点数", "全局点数CR", "统一输入(B)", "编码负载(B)", "全局端到端CR",
                        "逐运单CR均值/中位", "逐运单CR P10/P25/P75/P90/P95",
                        "候选锚点SR(%)", "候选单元完整率(%)", "观测跨度保真度(%)",
                        "PED均值(m)", "SED均值(m)", "块数"}, rows);
    }

    private static double mean(List<Double> values) {
        return values.isEmpty() ? 0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) return 0;
        List<Double> copy = new ArrayList<>(values);
        Collections.sort(copy);
        int n = copy.size();
        return n % 2 == 1 ? copy.get(n / 2) : (copy.get(n / 2 - 1) + copy.get(n / 2)) / 2.0;
    }

    private static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) return 0;
        List<Double> copy = new ArrayList<>(values);
        Collections.sort(copy);
        double pos = p * (copy.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) return copy.get(lo);
        double w = pos - lo;
        return copy.get(lo) * (1.0 - w) + copy.get(hi) * w;
    }
}
