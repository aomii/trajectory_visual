package com.fkhwl.nfs.biz.entity.dto.visual;

import java.util.ArrayList;
import java.util.List;

/**
 * 全量轨迹压缩任务状态（POST /trajectory/compress-all → GET /compress-task/{taskId} 轮询）。
 */
public class CompressTaskVO {
    public String taskId;
    public String status;          // RUNNING / SUCCESS / FAILED
    public int totalFiles;
    public int processed;
    public int successCount;
    public int failedCount;
    public String currentWaybillNo;
    public long startTimeMs;
    public long elapsedMs;
    public String message;
    /** 失败清单（最多保留 50 条） */
    public List<Failure> failures = new ArrayList<>();

    public static class Failure {
        public String waybillNo;
        public String file;
        public String error;
    }
}
