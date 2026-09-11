package com.fkhwl.nfs.biz.entity.dto.visual;

import java.util.ArrayList;
import java.util.List;

/**
 * 轨迹视图：一条线（原始线 = 清洗后全量点；压缩线 = Mongo 解码保留点）+ 停留标注。
 * 返回坐标为 GCJ-02（源数据本身即 GCJ-02，展示层不再转换），两种线同坐标系故可精确叠加。
 */
public class TrackViewVO {
    public long waybillId;
    public String waybillNo;
    public String coordSystem = "gcj02";
    public String lineType;          // original / compressed
    public int pointCount;
    /** 若为压缩线，同时带压缩产物概要 */
    public Integer keptPointCount;
    public Integer chunkCount;
    public Integer stopCount;
    public String algorithmCode;     // 压缩线时=PROPOSED
    public Double crLossy;
    public Double crTotal;
    /**
     * 压缩指标明细（压缩线 = 分片里落库的指标快照；原始线 = 清洗后点数等）。
     * 未压缩运单为 null。工作台"压缩指标"卡片直接取这里的数字。
     */
    public CompressResultVO metrics;
    public List<GpsPointVO> points = new ArrayList<>();
    public List<StayVO> stays = new ArrayList<>();
    /** 数据源提示（源文件未含车牌等业务信息） */
    public String notice;
    /** 原始线专用：被本文有损压缩删除的点数（= 清洗后点数 − 保留点数），便于前端提示与抽样 */
    public Integer deletedPointCount;
}
