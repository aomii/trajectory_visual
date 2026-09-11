package com.fkhwl.nfs.biz.entity.mongo;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;

/**
 * MongoDB 压缩分片文档（collection=trajectory_chunk）。
 *
 * <p>数据边界（规格书 §2/§7）：MongoDB <b>只保存压缩后的分块 chunk</b>，
 * 原始轨迹点数组不写 Mongo。一个运单压缩后按固定时间窗切成若干个 chunk 分片，
 * 每片一条 TrajectoryChunkDoc：压缩负载 {@link #compressedPayload} 是本文
 * "分块偏移量变长编码 + DEFLATE"（见 com.logicompress.experiment.encode）的该块字节。
 *
 * <p>时间索引 = startTime/endTime（块时间跨度），配合 waybillId 查询可只命中
 * 与时间窗口相交的分片、只读它们的负载做部分解压（规格书 §10.5）。
 *
 * <p>停留语义（视觉标注需要）以{@link #stopUnits}存放在 chunkIndex==0 的那条分片上
 * （其余分片该字段为 null），避免与"只存压缩 chunk"边界冲突；字段坐标一律为源数据
 * 原始坐标（**已是 GCJ-02**，展示时不再二次转换，见 trajectory.coord.source-already-gcj02）。
 */
@Data
@Document(collection = "trajectory_chunk")
public class TrajectoryChunkDoc {

    /** Mongo _id */
    @Id
    private String id;

    /** 运单内部 id */
    private Long waybillId;
    private String waybillNo;

    /** 分片序号（0 起，该运单共 chunkCountTotal 片） */
    private Integer chunkIndex;
    /** 本块时间跨度（块内点起止），epoch 毫秒，作为时间索引范围 */
    private Long startTime;
    private Long endTime;

    /** 该块内保留（压缩后）点数 */
    private Integer pointCount;
    /** 该块是否含语义锚点 */
    private Boolean hasAnchor;

    /** 运单级统计（每条分片都冗余，便于前端按运单聚合） */
    private Integer chunkCountTotal;
    private Integer rawPointCount;
    private Integer keptPointCount;
    private Integer stayPointCount;

    /** 生成该分片的算法与参数（可复现：记录压缩时所用容差/精度/块长） */
    private String algorithmCode;
    private Double moveToleranceM;
    private Integer precision;
    private Long blockWindowS;

    /** 压缩负载：该块 ASCII 编码串的 DEFLATE 字节（= EncodedStore 中 [byteOffset, byteOffset+length) 切片） */
    private byte[] compressedPayload;

    /** 停留单元视觉元数据（仅 chunkIndex==0 填，其余为 null）：起止锚点坐标 + 时长 + 类别 */
    private List<MongoStopUnit> stopUnits;

    /**
     * 运单级压缩指标（仅 chunkIndex==0 填，其余为 null）。
     *
     * <p>把 {@link PipelineOutcome} 的核心指标随分片一起落库，工作台"压缩指标"卡片就能
     * 在不重跑压缩的前提下回显（原来这些数字只存在于响应体里，刷新页面即丢失）。
     * 口径与论文第 6 章完全一致：CR_lossy = 清洗后点数/保留点数，CR_lossless = 规范文本字节/压缩字节，
     * CR_total = 二者之积；字节均为"保持留点序列"的字节。
     */
    private Metrics metrics;

    private Date createTime;

    /** 单运单压缩指标的落库快照（字段语义见 {@link com.fkhwl.nfs.biz.entity.dto.visual.CompressResultVO}） */
    @Data
    public static class Metrics {
        private Integer rawPointCount;
        private Integer cleanedPointCount;
        private Integer keptPointCount;
        private Integer stopCount;
        private Integer anchorCount;
        private Integer chunkCount;
        private Double crLossy;
        private Double crLossless;
        private Double crTotal;
        /** 无损层压缩前规范文本字节（ASCII）/ 压缩后字节（DEFLATE） */
        private Long naiveBytes;
        private Long zippedBytes;
        /** 有损误差（米，逐原始点）与语义保真 */
        private Double pedAvgM, pedMaxM, sedAvgM, sedMaxM;
        private Double sr, unitIntegrity, dwellFidelity;
        /** 编码 / 全量解压耗时（毫秒） */
        private Double encodeMs, decodeMs;
        /** 本次压缩端到端耗时（毫秒，含计算与写库） */
        private Long compressDurationMs;
        /** 本次压缩的参数快照：移动段 DP 容差(米) / 量化位数 / 分块时长(秒) */
        private Double moveToleranceM;
        private Integer precision;
        private Long blockWindowS;
    }

    /**
     * 一个停留单元的视觉元数据（论文定义 3-2 S=(p_start,p_end,Δt,ℓ)）。
     * start/end 锚点坐标取源数据原始坐标（GCJ-02）；retainedBoth=true 表示压缩后起终锚点成对保留
     * （本文方法恒 true，正是语义单元完整率=100% 的在线体现）。
     */
    @Data
    public static class MongoStopUnit {
        /** 单元序号（时间升序） */
        private Integer seq;
        private Long startTime;
        private Long endTime;
        private Double durationS;
        /** 语义类别：REST / TRAFFIC（v2 口径不再区分装卸货） */
        private String label;
        /** 起/终锚点坐标（WGS84）与锚点对应保留点时间 */
        private Double startLat;
        private Double startLon;
        private Double endLat;
        private Double endLon;
        /** 压缩后起终锚点是否成对保留 */
        private Boolean retainedBoth;
    }
}
