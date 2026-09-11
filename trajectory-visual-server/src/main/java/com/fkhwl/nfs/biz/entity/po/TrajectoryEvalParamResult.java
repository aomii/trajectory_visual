package com.fkhwl.nfs.biz.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 参数敏感性实验（对应 SQL: trajectory_eval_param_result）。
 * param_type：dp_tolerance(DP 容差扫描) / block_window(分块时长扫描)；
 * param_value：容差米数或分块秒数字符串。
 */
@Data
@TableName("trajectory_eval_param_result")
public class TrajectoryEvalParamResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;
    private String paramType;
    private String paramValue;
    private String algorithmCode;

    private Double crLossyAvg;
    private Double crTotalAvg;
    private Double pedAvg;
    private Double sedAvg;
    private Double srAvg;
    private Double queryTimeMsAvg;
    private Double partialReadRatioAvg;
    private LocalDateTime createTime;
}
