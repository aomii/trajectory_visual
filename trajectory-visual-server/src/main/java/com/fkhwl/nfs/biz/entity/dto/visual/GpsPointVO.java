package com.fkhwl.nfs.biz.entity.dto.visual;

/**
 * 地图点（展示坐标即源数据坐标，GCJ-02）。
 */
public class GpsPointVO {
    /** 经度（GCJ-02，高德坐标系） */
    public double lon;
    /** 纬度（GCJ-02） */
    public double lat;
    /** 展示时间 yyyy-MM-dd HH:mm:ss */
    public String time;
    /** epoch 秒 */
    public long epoch;
    /** 速度 km/h */
    public double spd;
    /** 在原始/清洗序列中的序号 */
    public int seq;
    /**
     * 该点在本文有损压缩中是否被保留：true=保留（压缩线的组成点），false=被删除。
     * 仅原始线填（后端按同一套参数跑一次分级抽稀得出）；压缩线为 null（线上每点都是保留点）。
     * 前端据此把被删点高亮画出来，直观呈现"两条曲线的差异从哪来"。
     */
    public Boolean kept;
}
