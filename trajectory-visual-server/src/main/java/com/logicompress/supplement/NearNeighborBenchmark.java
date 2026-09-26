package com.logicompress.supplement;

import com.logicompress.experiment.baseline.PlainDp;
import com.logicompress.experiment.baseline.TdTr;
import com.logicompress.experiment.config.ExperimentConfig;
import com.logicompress.experiment.encode.BlockEncoder;
import com.logicompress.experiment.encode.EncodedStore;
import com.logicompress.experiment.eval.Metrics;
import com.logicompress.experiment.model.StopUnit;
import com.logicompress.experiment.model.TrackPoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 针对候选约束增量的受控近邻对照；事件侧车按逻辑 UTF-8 序列化计费。 */
public final class NearNeighborBenchmark {
    private NearNeighborBenchmark() { }

    public static void run(ExperimentConfig cfg, List<SupplementContext.WaybillCtx> ctxs, Path outDir)
            throws IOException {
        String[] names = {"候选锚点+DP", "同锚点+TD-TR", "DP+独立候选元数据"};
        long[] payload = new long[3], sidecar = new long[3], keptCount = new long[3], input = new long[3];
        long[] complete = new long[3], units = new long[3];
        double[] sedSum = new double[3];
        int[] n = new int[3];
        List<String[]> per = new ArrayList<>();
        PlainDp dp = new PlainDp();
        TdTr td = new TdTr();
        BlockEncoder encoder = new BlockEncoder();
        for (SupplementContext.WaybillCtx c : ctxs) {
            List<List<Integer>> methods = new ArrayList<>();
            methods.add(SupplementContext.ourKept(c, cfg));
            methods.add(td.compressWithAnchors(c.cleaned, c.semantic.anchors, cfg.baselineDpM));
            methods.add(dp.compress(c.cleaned, cfg.baselineDpM));
            long metaBytes = sidecarBytes(c);
            long in = Metrics.naiveAsciiBytes(c.cleaned.points, cfg.precision);
            for (int i = 0; i < names.length; i++) {
                List<Integer> kept = methods.get(i);
                if (kept == null || kept.isEmpty()) continue;
                EncodedStore store = encoder.encode(c.cleaned.points, kept, c.semantic.anchors, cfg);
                long bytes = store.totalZippedBytes();
                boolean[] mask = Metrics.indexMask(kept, c.cleaned.points.size());
                long paired = c.semantic.stops.stream().filter(u -> mask[u.startIdx] && mask[u.endIdx]).count();
                double sed = Metrics.pedSed(c.cleaned.points, kept).avgSedM;
                n[i]++; input[i] += in; payload[i] += bytes; sidecar[i] += metaBytes;
                keptCount[i] += kept.size(); units[i] += c.semantic.stops.size(); complete[i] += paired;
                sedSum[i] += sed;
                per.add(new String[]{c.waybill.waybillNo, names[i], String.valueOf(in), String.valueOf(bytes),
                        String.valueOf(metaBytes), String.valueOf(kept.size()), String.valueOf(paired),
                        String.valueOf(c.semantic.stops.size()), SupplementTables.r4(sed)});
            }
        }
        SupplementTables.csv(outDir, "near_neighbor_per_waybill",
                new String[]{"waybill_no", "method", "input_ascii_bytes", "payload_bytes", "event_sidecar_utf8_bytes",
                        "kept_points", "paired_boundary_units", "candidate_units", "sed_m"}, per);
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            rows.add(new String[]{names[i], String.valueOf(n[i]), String.valueOf(keptCount[i]),
                    String.valueOf(payload[i]), String.valueOf(sidecar[i]), String.valueOf(payload[i] + sidecar[i]),
                    SupplementTables.r4((double) input[i] / (payload[i] + sidecar[i])),
                    units[i] == 0 ? "—" : SupplementTables.r4(100.0 * complete[i] / units[i]),
                    SupplementTables.r4(sedSum[i] / Math.max(1, n[i]))});
        }
        SupplementTables.both(outDir, "near_neighbor_summary", "候选锚点近邻方法对照",
                "三法共用分块编码和候选事件 UTF-8 侧车；侧车含边界时间、坐标与标签，"
                        + "是逻辑序列化字节，不是 MongoDB/BSON 实存占用。DP+侧车可以恢复事件记录，"
                        + "但其点子集可能不含事件两端；成对保留率是内部结构指标，非外部识别准确率。",
                new String[]{"方法", "运单", "保留点", "负载B", "候选侧车B", "合计B", "端到端CR", "点子集边界成对率(%)", "SED均值(m)"}, rows);
    }

    static long sidecarBytes(SupplementContext.WaybillCtx c) {
        StringBuilder b = new StringBuilder();
        int seq = 0;
        for (StopUnit u : c.semantic.stops) {
            TrackPoint a = c.cleaned.points.get(u.startIdx), z = c.cleaned.points.get(u.endIdx);
            b.append(String.format(Locale.ROOT, "%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%s\n",
                    seq++, u.tStart, u.tEnd, a.lat, a.lon, z.lat, z.lon, u.label));
        }
        return b.toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
