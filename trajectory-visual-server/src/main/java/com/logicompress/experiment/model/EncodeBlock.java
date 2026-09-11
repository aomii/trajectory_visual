package com.logicompress.experiment.model;

import java.util.List;

/**
 * 编码块 + 稀疏时间索引行（论文 4.4 时间索引字段）。
 */
public class EncodeBlock {
    public int chunkId;
    /** 块内点起止时间（北京时间 epoch 秒） */
    public long tStart, tEnd;
    public int pointCount;
    /** 该块 zip 后负载在整块 blob 中的字节偏移与长度 */
    public long byteOffset;
    public int length;
    /** 是否含关键锚点 */
    public boolean hasKeyPoint;
    /** 块内点（编码前），供诊断 */
    public List<TrackPoint> points;

    /** 块内 ASCII 编码串（编码后、zip 前），供字节统计 */
    public String ascii;

    public long durationS() {
        return tEnd - tStart;
    }
}
