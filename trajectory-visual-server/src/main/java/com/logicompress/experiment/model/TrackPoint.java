package com.logicompress.experiment.model;

/**
 * 轨迹点（清洗后的数值化表示）。
 * 字段语义（数据画像确认）：spd 单位 km/h；agl 是航向角(0-359°)非离地高度；hgt 为海拔(相对高差可靠)。
 * gtm 为北京时间（UTC+8）。
 */
public class TrackPoint {
    /** 纬度（WGS84） */
    public double lat;
    /** 经度（WGS84） */
    public double lon;
    /** 速度，km/h */
    public double spdKph;
    /** 海拔，米（部分运单存在绝对偏移，仅相对高差可用） */
    public double hgt;
    /** 航向角 0-359° */
    public double aglHeading;
    /** GPS 时间（北京时间），epoch 秒 */
    public long gtmEpoch;
    /** 原始 gtm 字符串 yyyy-MM-dd HH:mm:ss */
    public String gtmRaw;
    /** 在原始 locations 数组中的下标 */
    public int srcIdx;

    public TrackPoint() {
    }

    public TrackPoint(double lat, double lon, long gtmEpoch) {
        this.lat = lat;
        this.lon = lon;
        this.gtmEpoch = gtmEpoch;
    }

    @Override
    public String toString() {
        return String.format("(%f,%f t=%d spd=%.1f)", lat, lon, gtmEpoch, spdKph);
    }
}
