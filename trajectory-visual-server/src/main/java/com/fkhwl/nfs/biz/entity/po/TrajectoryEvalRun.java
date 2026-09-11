package com.fkhwl.nfs.biz.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 第 6 章实验批次（一次 run 一条，对应 SQL: trajectory_eval_run）。
 * 记录数据源、运行范围、参数快照、状态与耗时，供前端选择"最近一次成功批次 / 历史批次"。
 */
@Data
@TableName("trajectory_eval_run")
public class TrajectoryEvalRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 批次号，如 TRAJECTORY-EVAL-20260909-001 */
    private String runNo;
    private String runName;
    /** 数据源目录（可复现：记录实际读哪个目录） */
    private String dataSourceDir;
    /** 参与统计的有效运单数 */
    private Integer waybillCount;
    /** 有效运单原始轨迹点总数 */
    private Long rawPointCount;
    /** RUNNING / SUCCESS / FAILED */
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private String remark;
    /** 实验参数快照 JSON（ExperimentConfig.asMap() 序列化，保证可复现） */
    private String parameterJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
