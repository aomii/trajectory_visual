package com.fkhwl.nfs.biz.util;

/**
 * 坐标转换工具：WGS84 → GCJ-02（高德地图坐标系）。
 *
 * <p><b>当前口径（2026-09-10 用户确认）：源 JSON（source_data_full）与业务库 waybill 的
 * 收发货地址坐标已经是 GCJ-02</b>（入湖前做过一次 WGS84→GCJ-02）。因此展示层
 * <b>不做二次转换</b>，直接用源坐标叠加高德底图；再做一次会整体偏移约 300~600m，
 * 且与收发货地址标记互相错位。统一入口见 {@link #toDisplay(double, double, boolean)}。
 *
 * <p>{@link #wgs84togcj02} 保留：仅当日后换成真正的 WGS84 源
 * （配置 {@code trajectory.coord.source-already-gcj02=false}）时才启用。
 * 所有压缩、指标计算不改动坐标本身，与 04实验 口径一致。
 *
 * <p>实现为标准 GCJ-02 偏移算法（火星坐标），Krasovsky 椭球常数。
 */
public final class CoordinateTransformUtil {

    private static final double PI = Math.PI;
    private static final double A = 6378245.0;                    // 长半轴
    private static final double EE = 0.00669342162296594323;      // 偏心率平方

    private CoordinateTransformUtil() {
    }

    /** 是否在中国境外（简化边界判断，境外不偏移） */
    private static boolean outOfChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }

    /**
     * WGS84 经纬度 → GCJ-02。返回 {lon, lat}。境外/边界外返回原值。
     *
     * @param wgsLat 纬度（WGS84）
     * @param wgsLon 经度（WGS84）
     */
    /**
     * 展示坐标统一入口：负责把"源坐标"转成"高德可用的 GCJ-02"，返回 {lon, lat}。
     *
     * @param lat                源纬度
     * @param lon                源经度
     * @param sourceAlreadyGcj02 true=源已是 GCJ-02（当前口径），原样返回；false=源为 WGS84，做转换
     */
    public static double[] toDisplay(double lat, double lon, boolean sourceAlreadyGcj02) {
        return sourceAlreadyGcj02 ? new double[]{lon, lat} : wgs84togcj02(lat, lon);
    }

    public static double[] wgs84togcj02(double wgsLat, double wgsLon) {
        if (outOfChina(wgsLat, wgsLon)) {
            return new double[]{wgsLon, wgsLat};
        }
        double dLat = transformLat(wgsLon - 105.0, wgsLat - 35.0);
        double dLon = transformLon(wgsLon - 105.0, wgsLat - 35.0);
        double radLat = wgsLat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{wgsLon + dLon, wgsLat + dLat};
    }
}
