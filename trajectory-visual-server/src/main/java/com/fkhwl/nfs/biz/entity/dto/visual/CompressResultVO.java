package com.fkhwl.nfs.biz.entity.dto.visual;

/**
 * 单运单压缩结果指标（POST /trajectory/compress/{waybillId} 返回；
 * 同时作为压缩线 TrackViewVO.metrics 的载荷，故字段口径与分片落库快照一致）。
 *
 * <p>数值字段用包装类型：早期分片（未落库指标）读不回来的项保持 null，
 * 前端显示"—"而不是误导性的 0。
 */
public class CompressResultVO {
    public long waybillId;
    public String waybillNo;
    public Integer rawPointCount;       // 原始点数(文件)
    public Integer cleanedPointCount;   // 清洗后点数(压缩基准)
    public Integer keptPointCount;      // 压缩后保留点数
    public Integer stopCount;           // 停留单元数
    public Integer anchorCount;         // 锚点数
    public Integer chunkCount;          // 分片数(Mongo)
    public Double crLossy;              // 有损层：清洗后点数 / 保留点数
    public Double crLossless;           // 无损层：规范文本字节 / 压缩字节
    public Double crTotal;              // crLossy × crLossless
    public Long naiveBytes;             // 无损层压缩前规范文本字节
    public long storageBytes;           // 无损层压缩后字节（zip 负载合计，恒可得）
    // ---- 有损误差与语义保真（论文第 6 章口径） ----
    public Double pedAvgM;
    public Double pedMaxM;
    public Double sedAvgM;
    public Double sedMaxM;
    public Double sr;               // 语义点保留率 0~1
    public Double unitIntegrity;    // 语义单元完整率 0~1
    public Double dwellFidelity;    // 停留时长保真度 0~1
    public Double encodeMs;
    public Double decodeMs;
    public long durationMs;         // 端到端耗时（含写库）
    /** 参数快照（工作台展示"本次压缩用什么参数"） */
    public Double moveToleranceM;
    public Integer precision;
    public Long blockWindowS;
    public boolean success = true;
    public String message;
}
