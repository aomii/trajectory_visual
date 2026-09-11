package com.fkhwl.nfs.biz.experiment;

import com.logicompress.experiment.baseline.Dps;
import com.logicompress.experiment.baseline.LossyBaseline;
import com.logicompress.experiment.baseline.PlainDp;
import com.logicompress.experiment.baseline.TdTr;
import com.logicompress.experiment.baseline.Trajic;
import com.logicompress.experiment.clean.CleanedTrack;
import com.logicompress.experiment.clean.DriftFilter;
import com.logicompress.experiment.compress.SegmentedDp;
import com.logicompress.experiment.config.ExperimentConfig;
import com.logicompress.experiment.encode.BlockEncoder;
import com.logicompress.experiment.encode.EncodedStore;
import com.logicompress.experiment.encode.PartialDecoder;
import com.logicompress.experiment.eval.Metrics;
import com.logicompress.experiment.model.SemanticResult;
import com.logicompress.experiment.model.StopUnit;
import com.logicompress.experiment.model.TrackPoint;
import com.logicompress.experiment.model.Waybill;
import com.logicompress.experiment.semantic.SemanticAnalyzer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 单运单压缩/实验全链路（v2 口径，严格复用 04实验 移植算法，保证数字可复现）：
 *
 * <ol>
 *   <li>清洗 DriftFilter（漂移剔除 + 断线分段）；</li>
 *   <li>语义识别 SemanticAnalyzer（v2 静态同坐标停留合并，锚点=停留单元首末点+轨迹起止点）；</li>
 *   <li>分级抽稀 SegmentedDp：静止段只保留起终锚点，移动段 DP（容差默认 10m，用户 2026-09-09 定稿）；</li>
 *   <li>指标 Metrics：PED/SED（逐原始点），SR/语义单元完整率/停留时长保真度；</li>
 *   <li>无损层 BlockEncoder：分块偏移量变长编码 + DEFLATE，保留 EncodedStore 供在线写 Mongo；</li>
 *   <li>部分解压 PartialDecoder：时间窗命中块校验（与全量解压一致性）；</li>
 *   <li>有损基线 PlainDp/Dps/TdTr/Trajic（同容差）。</li>
 * </ol>
 *
 * <p>可选关闭无损层/基线/部分解压，供消融变体复用（A3 去无损 / 去锚点等）。
 */
public final class CompressionPipeline {

    private CompressionPipeline() {
    }

    /** 算法代码常量 */
    public static final String ALG_PROPOSED = "PROPOSED";
    public static final String[] BASELINE_CODES = {"DP", "DPS", "TD-TR", "Trajic"};

    /**
     * 对单个运单执行完整压缩链路。无效轨迹（无点/清洗后不足 minPoints）返回 null。
     *
     * @param w      已装载轨迹的运单（rawPoints 非空、时间升序）
     * @param cfg    实验参数（容差/块长/精度等已按运行要求设置好）
     * @param flags  开关：lossless/partial/baselines
     */
    public static PipelineOutcome process(Waybill w, ExperimentConfig cfg, boolean lossless, boolean partial, boolean baselines) {
        if (w == null || w.rawPoints == null || w.rawPoints.isEmpty()) return null;

        PipelineOutcome r = new PipelineOutcome();
        r.waybillNo = w.waybillNo;
        r.waybillId = w.waybillId;
        r.nRaw = w.rawPoints.size();
        if (!w.rawPoints.isEmpty()) {
            r.trackDurationS = w.rawPoints.get(w.rawPoints.size() - 1).gtmEpoch
                    - w.rawPoints.get(0).gtmEpoch;
        }

        // ① 清洗（口径见 04实验 Main：漂移用经纬度推算速度，断线>10min切段不插值）
        CleanedTrack cleaned = new DriftFilter().clean(w.rawPoints, cfg.driftSpeedKph, cfg.breakGapS);
        r.nClean = cleaned.points.size();
        r.removedDrift = cleaned.removedDrift;
        if (cleaned.points.size() < cfg.minPoints) return null; // 无效轨迹
        r.cleanedPoints = cleaned.points;

        // ② 语义识别（v2 静态同坐标停留，REST≥30min / TRAFFIC）
        SemanticResult semantic = new SemanticAnalyzer().analyze(w, cleaned, cfg);
        r.semantic = semantic;
        r.nStops = semantic.stops.size();
        r.nAnchors = semantic.anchors.size();

        // ③ 分级抽稀（静止段保两端、移动段 DP cfg.dpEpsNonKeyM）
        List<Integer> kept = new SegmentedDp().compress(cleaned, semantic, cfg);
        r.keptIdx = kept;
        r.nKept = kept.size();
        r.crLossy = (double) r.nClean / r.nKept;

        // ④ 有损误差与语义保真（Metrics 口径与 04实验 一致）
        Metrics.Error err = Metrics.pedSed(cleaned.points, kept);
        r.pedAvgM = err.avgPedM;
        r.pedMaxM = err.maxPedM;
        r.sedAvgM = err.avgSedM;
        r.sedMaxM = err.maxSedM;
        r.sr = Metrics.semanticRetention(semantic.anchors, kept);
        boolean[] keptMask = Metrics.indexMask(kept, cleaned.points.size());
        r.unitIntegrity = Metrics.unitIntegrity(semantic.stops, keptMask);
        r.dwellFidelity = Metrics.dwellFidelity(cleaned.points, semantic.stops, keptMask);

        if (lossless) {
            // ⑤ 无损层：对象是"保留点序列"；分块 → 块首绝对坐标/块内差分 → ZigZag → 5位变长 → DEFLATE
            List<TrackPoint> keptPts = new ArrayList<>(kept.size());
            for (int idx : kept) keptPts.add(cleaned.points.get(idx));
            r.naiveBytes = Metrics.naiveAsciiBytes(keptPts, cfg.precision); // 压缩前规范文本字节
            long t0 = System.nanoTime();
            EncodedStore store = new BlockEncoder().encode(cleaned.points, kept, semantic.anchors, cfg);
            r.encodeMs = (System.nanoTime() - t0) / 1e6;
            r.store = store;
            r.asciiBytes = store.totalAsciiBytes;
            r.zippedBytes = store.totalZippedBytes();
            r.blocks = store.blocks.size();
            r.crLossless = (double) r.naiveBytes / r.zippedBytes;
            r.crTotal = r.crLossy * r.crLossless;

            // ⑥ 全量解压计时（decode_ms）
            t0 = System.nanoTime();
            List<TrackPoint> all = PartialDecoder.decodeAll(store, cfg.precision);
            r.decodeMs = (System.nanoTime() - t0) / 1e6;

            if (partial) {
                // ⑦ 部分解压：查询窗位于轨迹 ~60% 处，1h；命中块字节收益与正确性
                long tStart = cleaned.points.get(0).gtmEpoch;
                long tEnd = cleaned.points.get(cleaned.points.size() - 1).gtmEpoch;
                long t1 = tStart + Math.round((tEnd - tStart) * cfg.queryWindowFraction);
                long t2 = t1 + cfg.queryWindowS;
                PartialDecoder decoder = new PartialDecoder();
                t0 = System.nanoTime();
                PartialDecoder.WindowResult wr = decoder.decodeWindow(store, t1, t2, cfg.precision);
                r.queryMs = (System.nanoTime() - t0) / 1e6;
                r.blocksHit = wr.blocksHit;
                r.bytesRead = wr.bytesRead;
                r.partialBytesRatio = (double) wr.bytesRead / r.zippedBytes;
                r.partialBlocksRatio = (double) wr.blocksHit / r.blocks;
                r.decodedInWindow = wr.points.size();
                int full = 0;
                for (TrackPoint p : all) {
                    if (p.gtmEpoch >= t1 && p.gtmEpoch <= t2) full++;
                }
                r.fullInWindow = full;
                r.partialCorrect = wr.points.size() == full;
            }
        } else {
            // 去无损层（消融 A3）：总压缩率退化为有损层压缩率
            r.crLossless = 1.0;
            r.crTotal = r.crLossy;
        }

        if (baselines) {
            // ⑧ 有损基线：同一清洗轨迹、同一容差 cfg.baselineDpM；用本文锚点集合评 SR/单元完整率
            LossyBaseline[] b = {new PlainDp(), new Dps(), new TdTr(), new Trajic()};
            for (int i = 0; i < b.length; i++) {
                List<Integer> bKept = b[i].compress(cleaned, cfg.baselineDpM);
                if (bKept.isEmpty()) continue;
                PipelineOutcome.Baseline bm = new PipelineOutcome.Baseline();
                bm.name = BASELINE_CODES[i];
                bm.nKept = bKept.size();
                bm.crLossy = (double) r.nClean / bm.nKept;
                Metrics.Error be = Metrics.pedSed(cleaned.points, bKept);
                bm.pedAvgM = be.avgPedM;
                bm.pedMaxM = be.maxPedM;
                bm.sedAvgM = be.avgSedM;
                bm.sedMaxM = be.maxSedM;
                bm.sr = Metrics.semanticRetention(semantic.anchors, bKept);
                boolean[] bMask = Metrics.indexMask(bKept, cleaned.points.size());
                bm.unitIntegrity = Metrics.unitIntegrity(semantic.stops, bMask);
                bm.dwellFidelity = Metrics.dwellFidelity(cleaned.points, semantic.stops, bMask);
                r.baselines.add(bm);
            }
        }
        return r;
    }

    /** 默认完整开关：有损+无损+部分解压+基线 */
    public static PipelineOutcome processFull(Waybill w, ExperimentConfig cfg) {
        return process(w, cfg, true, true, true);
    }
}
