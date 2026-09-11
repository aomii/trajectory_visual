package com.fkhwl.nfs.biz.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实验运行失败记录（对应 SQL: trajectory_eval_error_record）。
 * 逐运单失败写入，不中断整个批次；前端"日常分析模式"可查看失败文件与原因。
 */
@Data
@TableName("trajectory_eval_error_record")
public class TrajectoryEvalErrorRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;
    private String waybillNo;
    private String sourceFile;
    private String experimentCode;
    private String errorMessage;
    private LocalDateTime createTime;
}
