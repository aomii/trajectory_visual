package com.fkhwl.nfs.biz.entity.dto.visual;

/**
 * 停留单元（页面悬停展示起止时间与停留时长）。
 * 坐标展示已转 GCJ-02；retainedBoth=压缩后起终锚点成对保留。
 */
public class StayVO {
    public int seq;
    public String label;          // REST / TRAFFIC
    public long startEpoch;
    public long endEpoch;
    public String startTime;      // yyyy-MM-dd HH:mm:ss
    public String endTime;
    public double durationS;
    public double startLat, startLon, endLat, endLon;
    public boolean retainedBoth;
}
