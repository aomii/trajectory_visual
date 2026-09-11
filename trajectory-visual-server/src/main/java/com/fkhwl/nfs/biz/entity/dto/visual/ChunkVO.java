package com.fkhwl.nfs.biz.entity.dto.visual;

/**
 * Mongo 分片元数据（页面展示 chunk 清单，不含负载字节内容）。
 */
public class ChunkVO {
    public int chunkIndex;
    public String startTime;      // yyyy-MM-dd HH:mm:ss（块时间跨度）
    public String endTime;
    public int pointCount;        // 该块压缩后点数
    public boolean hasAnchor;     // 是否含语义锚点
    public int payloadBytes;      // 压缩负载字节数（该块 DEFLATE 后）
    public int chunkCountTotal;   // 该运单总块数
    /** 压缩该运单时所用的分块时长（秒）——"多少时长一个分片"的权威值 */
    public Long blockWindowS;
    public int rawPointCount;
    public int keptPointCount;
    public int stayPointCount;
    public long rawStartMs;       // 展示用原始 epoch 毫秒
    public long rawEndMs;
}
