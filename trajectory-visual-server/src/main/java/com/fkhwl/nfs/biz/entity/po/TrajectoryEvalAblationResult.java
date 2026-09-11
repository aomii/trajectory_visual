package com.fkhwl.nfs.biz.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消融实验（对应 SQL: trajectory_eval_ablation_result）。
 * ablation_code：A0(完整方法) / A-TIGHT(收紧移动段容差) / A2(去锚点强制保留) /
 * A3(去无损层) / A4(去分块索引-整流编码)。
 */
@Data
@TableName("trajectory_eval_ablation_result")
public class TrajectoryEvalAblationResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;
    private String ablationCode;
    private String ablationName;
    /** 该变体参数快照 JSON */
    private String parameterJson;

    private Double crTotalAvg;
    private Double crLossyAvg;
    private Double srAvg;
    private Double semanticUnitCompleteRate;
    private Double stayDurationPreserveRate;
    private Double pedAvg;
    private Double sedAvg;
    private Double queryTimeMsAvg;
    private Long storageBytes;
    private String remark;
    private LocalDateTime createTime;
}
