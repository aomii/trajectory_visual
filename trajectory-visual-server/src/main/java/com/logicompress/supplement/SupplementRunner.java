package com.logicompress.supplement;

import com.logicompress.experiment.config.ExperimentConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 补充实验编排器：一次装载运单，顺序跑完论文第 6 章需要的全部补充实验，结果写 {@code outDir}。
 *
 * <p>与主实验（第 6 章 {@code Chapter6ExperimentRunner}，结果落 MySQL）的分工：
 * 主实验覆盖 6.2–6.6 的算法对比/消融/参数敏感性；本包只补主实验没覆盖的 5 组量：
 * <ul>
 *   <li>表 6-7  静态同坐标合并 vs ST-DBSCAN 选型对比 → {@link StopRecognitionCompare}</li>
 *   <li>表 6-12 无损层压缩结果（朴素字节/编码后字节/压缩率） → {@link LosslessBenchmark#runSize}</li>
 *   <li>表 6-13 编码/解码耗时基准（1 轮预热 + 5 轮） → {@link LosslessBenchmark#runBench}</li>
 *   <li>表 6-14 偏移量经验熵 vs 编码后字节率 → {@link EntropyBenchmark}</li>
 *   <li>表 6-16 块长权衡曲线 → {@link BlockLengthSweep}</li>
 *   <li>表 6-18 凸包保形度与压缩失真速度比 → {@link HullShapeBenchmark}</li>
 * </ul>
 * （表 6-19「方案 A vs 方案 B」已按用户 2026-09-11 决定从论文中取消，故本包不实现。）
 */
public final class SupplementRunner {

    private SupplementRunner() {
    }

    /**
     * @param cfg       实验参数（应与主实验同一份口径：容差 10 m、块长 3600 s、精度 6 等）
     * @param sourceDir 轨迹源目录（source_data_full）
     * @param metaById  运单业务元数据（收发坐标/装卸货时间），供表 6-7 围栏与表 6-18 凸包控制点使用
     * @param limit     运单上限（&lt;=0 全部）
     * @param outDir    导出目录
     * @return 参与统计的有效运单数
     */
    public static int runAll(ExperimentConfig cfg, Path sourceDir,
                             Map<Long, SupplementContext.WaybillMeta> metaById,
                             int limit, Path outDir) throws IOException {
        long t0 = System.currentTimeMillis();
        System.out.println("================ 补充实验开始 ================");
        System.out.println("源目录  : " + sourceDir);
        System.out.println("导出目录: " + outDir);
        System.out.println("口径    : 移动段容差 " + cfg.dpEpsNonKeyM + " m，基线容差 " + cfg.baselineDpM
                + " m，块长 " + cfg.blockWindowS + " s，精度 " + cfg.precision);

        List<SupplementContext.WaybillCtx> ctxs = SupplementContext.load(cfg, sourceDir, metaById, limit);
        long totalClean = 0, totalRaw = 0, totalStops = 0, totalAnchors = 0;
        for (SupplementContext.WaybillCtx c : ctxs) {
            totalClean += c.nClean;
            totalRaw += c.nRaw;
            totalStops += c.semantic.stops.size();
            totalAnchors += c.semantic.anchors.size();
        }
        System.out.printf(Locale.ROOT,
                "装载完成：有效运单 %d，原始点 %,d，清洗后 %,d，停留单元 %,d，锚点 %,d%n",
                ctxs.size(), totalRaw, totalClean, totalStops, totalAnchors);

        writeManifest(outDir, ctxs);
        StopRecognitionCompare.run(cfg, ctxs, outDir);
        LosslessBenchmark.runSize(cfg, ctxs, outDir);
        LosslessBenchmark.runBench(cfg, ctxs, outDir);
        EntropyBenchmark.run(cfg, ctxs, outDir);
        BlockLengthSweep.run(cfg, ctxs, outDir);
        HullShapeBenchmark.run(cfg, ctxs, outDir);

        double min = (System.currentTimeMillis() - t0) / 60000.0;
        System.out.printf(Locale.ROOT, "================ 补充实验完成，总耗时 %.1f 分钟 ================%n", min);
        return ctxs.size();
    }

    /** 写本次实验的运单清单（可复现）：运单号/原始点数/清洗后点数/停留数/锚点数 */
    static void writeManifest(Path dir, List<SupplementContext.WaybillCtx> ctxs) throws IOException {
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder("waybill_no,waybill_id,n_raw,n_clean,n_stops,n_anchors\n");
        for (SupplementContext.WaybillCtx c : ctxs) {
            sb.append(c.waybill.waybillNo).append(',').append(c.waybill.waybillId).append(',')
                    .append(c.nRaw).append(',').append(c.nClean).append(',')
                    .append(c.semantic.stops.size()).append(',').append(c.semantic.anchors.size()).append('\n');
        }
        Files.write(dir.resolve("manifest.csv"), sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
