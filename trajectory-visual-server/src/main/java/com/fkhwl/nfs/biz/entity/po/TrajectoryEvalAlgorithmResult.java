package com.fkhwl.nfs.biz.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次实验批次下、每种算法的聚合指标（对应 SQL: trajectory_eval_algorithm_result）。
 * algorithm_code：PROPOSED(本文) / DP / DPS / TD-TR / Trajic。
 */
@Data
@TableName("trajectory_eval_algorithm_result")
public class TrajectoryEvalAlgorithmResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;
    private String algorithmCode;
    private String algorithmName;
    /** 算法参数快照 JSON */
    private String parameterJson;

    private Integer waybillCount;
    private Long rawPointCount;
    private Long keptPointCount;
    private Integer chunkCount;

    private Double crLossyAvg;
    private Double crLosslessAvg;
    private Double crTotalAvg;
    /** 清洗后完整点列规范文本总字节 / 编码负载总字节。 */
    private Double crE2eGlobal;
    private Long inputBytes;
    private Double pedAvg;
    private Double sedAvg;
    private Double srAvg;
    private Double semanticUnitCompleteRate;
    private Double stayDurationPreserveRate;
    private Double encodeTimeMsAvg;
    private Double decodeTimeMsAvg;
    private Long storageBytes;
    private Double queryTimeMsAvg;
    private Double partialReadRatioAvg;
    private LocalDateTime createTime;
}
