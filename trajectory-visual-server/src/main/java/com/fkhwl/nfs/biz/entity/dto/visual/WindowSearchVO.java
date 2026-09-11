package com.fkhwl.nfs.biz.entity.dto.visual;

import java.util.ArrayList;
import java.util.List;

/**
 * 时间窗口部分检索结果（规格书 §10.5）：只解命中分片 vs 全量解压的性能与字节对比。
 */
public class WindowSearchVO {
    public long waybillId;
    public String waybillNo;
    public long t1Epoch, t2Epoch;      // 查询窗（epoch 秒）
    public String t1, t2;

    /** 命中分片数与读取字节 */
    public int totalChunks;
    public int hitChunks;
    public long bytesRead;
    public long totalBytes;
    public double readRatio;           // bytesRead / totalBytes
    public double blockHitRatio;       // hitChunks / totalChunks

    /** 正确性：部分解压取回窗口点数 vs 全量解压同窗口点数 */
    public int partialPoints;
    public int fullPoints;
    public boolean consistent;

    /** 耗时对比（毫秒，保留小数区分亚毫秒操作） */
    public double partialMs;
    public double fullMs;

    /** 窗口内轨迹点（GCJ-02，地图展示；源数据即 GCJ-02，未做二次转换） */
    public List<GpsPointVO> points = new ArrayList<>();
    /** 数据状态：NOT_COMPRESSED 表示该运单尚未压缩/无分片 */
    public String dataStatus = "OK";

    // ---- 顶部提示用：分片时长与轨迹时间范围（一片多少秒 / 时间范围是什么） ----
    /** 分块时长（秒）：该运单分片按此时间窗切分 */
    public Long blockWindowS;
    /** 该运单已压缩分片覆盖的时间范围（首片起 ~ 末片止） */
    public String trackStartTime;
    public String trackEndTime;
}
