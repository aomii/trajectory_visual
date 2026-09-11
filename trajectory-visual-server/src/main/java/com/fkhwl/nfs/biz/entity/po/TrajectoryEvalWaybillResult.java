package com.fkhwl.nfs.biz.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单运单 × 算法 的实验明细（对应 SQL: trajectory_eval_waybill_result）。
 * 供前端"运单下钻"与算法对比表格读取，前端的统计中位数/min/max 由该表实时汇总。
 */
@Data
@TableName("trajectory_eval_waybill_result")
public class TrajectoryEvalWaybillResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;
    private Long waybillId;
    private String waybillNo;
    private String algorithmCode;
    /** 算法参数快照 JSON */
    private String parameterJson;

    private Integer rawPointCount;
    private Integer keptPointCount;
    private Integer chunkCount;

    private Double crLossy;
    private Double crLossless;
    private Double crTotal;
    private Double pedAvg;
    private Double pedMax;
    private Double sedAvg;
    private Double sedMax;
    private Double sr;
    private Double semanticUnitCompleteRate;
    private Double stayDurationPreserveRate;

    private Double encodeTimeMs;
    private Double decodeTimeMs;
    private Long storageBytes;
    private Double queryTimeMs;
    private Double partialReadRatio;

    /** 附加列（非规格书强制，供下钻展示停留信息）：停留单元数/锚点数 */
    private Integer stayCount;
    private Integer anchorCount;
    private LocalDateTime createTime;
}
