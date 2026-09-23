package com.fkhwl.nfs.biz.experiment;

import com.logicompress.experiment.encode.EncodedStore;
import com.logicompress.experiment.model.SemanticResult;
import com.logicompress.experiment.model.StopUnit;
import com.logicompress.experiment.model.TrackPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个运单完整实验产出的结构化结果（压缩/在线/评估三处共用）。
 *
 * <p>对应 04实验 Main.processWaybill 的全部计算，但额外保留分块编码产物
 * {@link #store}、停留单元 {@link #semantic} 等供"写入 MongoDB 分片 / 在线部分解压"使用。
 * 指标语义与论文第 6 章口径一致（单位见各字段注释）。
 */
public class PipelineOutcome {

    // ---- 标识与数据量 ----
    public String waybillNo;
    public long waybillId;
    public int nRaw;          // 原始点数（含漂移点）
    public int nClean;        // 清洗后点数
    public int nKept;         // 压缩后保留点数
    public int removedDrift;  // 剔除漂移点数
    public int nStops;        // 停留单元数
    public int nAnchors;      // 关键锚点数

    // ---- 清洗后点序列 / 语义 / 压缩产物（供在线写 Mongo 与展示） ----
    public List<TrackPoint> cleanedPoints = new ArrayList<>();
    public List<Integer> keptIdx = new ArrayList<>();     // 保留下标（清洗后）
    public SemanticResult semantic = new SemanticResult();
    public EncodedStore store;                            // 分块编码产物（PROPOSED 无损层）

    // ---- 压缩率 ----
    public double crLossy;       // nClean / nKept
    public double crLossless;    // naiveBytes / zippedBytes
    public double crTotal;       // crLossy × crLossless
    public double crE2e;         // cleanInputBytes / zippedBytes（统一编码负载口径）

    // ---- 有损误差与语义保真 ----
    public double pedAvgM, pedMaxM, sedAvgM, sedMaxM;
    public double sr;                          // 语义点保留率 0~1
    public double unitIntegrity;               // 语义单元完整率 0~1
    public double dwellFidelity;               // 停留时长保真度 0~1

    // ---- 无损层字节 ----
    public long naiveBytes;
    public long cleanInputBytes;
    public long asciiBytes;
    public long zippedBytes;

    // ---- 时间窗部分解压 ----
    public int blocks;
    public int blocksHit;
    public long bytesRead;
    public double partialBytesRatio;
    public double partialBlocksRatio;
    public boolean partialCorrect;
    public int decodedInWindow;
    public int fullInWindow;

    // ---- 耗时（单机毫秒，保留小数以区分亚毫秒操作） ----
    public double encodeMs;
    public double decodeMs;      // 全量解压耗时
    public double queryMs;       // 部分解压耗时

    /** 基线完整组合结果：有损输出继续进入与本文相同的分块无损编码器。 */
    public List<Baseline> baselines = new ArrayList<>();

    /** 该运单总原始时长秒（首末点时间差），供 6.2 统计 */
    public long trackDurationS;

    /** 单个基线结果（与本文共用 Metrics 口径） */
    public static class Baseline {
        public String name;             // DP / DPS / TD-TR / Trajic
        public int nKept;
        public double crLossy;
        public double crLossless;
        public double crTotal;
        public double crE2e;
        public long naiveBytes;
        public long cleanInputBytes;
        public long zippedBytes;
        public int blocks;
        public double encodeMs;
        public double decodeMs;
        public double queryMs;
        public double partialBytesRatio;
        public double pedAvgM, pedMaxM, sedAvgM, sedMaxM;
        public double sr;               // 用与本文相同的锚点评测
        public double unitIntegrity;
        public double dwellFidelity;
    }

    public static List<TrackPoint> stopAnchorPts(List<TrackPoint> cleaned, StopUnit u) {
        List<TrackPoint> out = new ArrayList<>(2);
        if (u.startIdx >= 0 && u.startIdx < cleaned.size()) out.add(cleaned.get(u.startIdx));
        if (u.endIdx >= 0 && u.endIdx < cleaned.size()) out.add(cleaned.get(u.endIdx));
        return out;
    }
}
