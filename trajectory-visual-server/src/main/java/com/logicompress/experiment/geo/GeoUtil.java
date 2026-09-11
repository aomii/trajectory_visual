package com.logicompress.experiment.geo;

import com.logicompress.experiment.model.TrackPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * 几何工具。距离口径对齐公司代码：
 * - Haversine 地球半径 6370996.81m（GisDouglasUtil）
 * - 点到线段距离用海伦公式（GisDouglasUtil.distToSegment）
 * - 点在圆内用 圆心距<=半径（MapFixUtil.IsInCircle）
 * - 凸包用 Graham 扫描 + 鞋带面积（GrahamScanUtil），面积在局部平面投影下计算
 */
public final class GeoUtil {

    /** 与公司 GisDouglasUtil 一致的地球半径（米） */
    public static final double EARTH_RADIUS_M = 6370996.81;

    private GeoUtil() {
    }

    /** Haversine 两点大圆距离，米 */
    public static double haversineM(double lat1, double lon1, double lat2, double lon2) {
        double radLat1 = Math.toRadians(lat1);
        double radLat2 = Math.toRadians(lat2);
        double a = radLat1 - radLat2;
        double b = Math.toRadians(lon1) - Math.toRadians(lon2);
        double s = 2 * Math.asin(Math.sqrt(
                Math.pow(Math.sin(a / 2), 2)
                        + Math.cos(radLat1) * Math.cos(radLat2) * Math.pow(Math.sin(b / 2), 2)));
        return s * EARTH_RADIUS_M;
    }

    /** 点到线段（start-end）的距离，米。海伦公式：三角形面积的两倍除以底边 = 高 */
    public static double distPointToSegmentM(double lat, double lon,
                                             double slat, double slon, double elat, double elon) {
        double a = haversineM(slat, slon, elat, elon);
        if (a < 1e-9) {
            return haversineM(lat, lon, slat, slon);
        }
        double b = haversineM(slat, slon, lat, lon);
        double c = haversineM(elat, elon, lat, lon);
        double p = (a + b + c) / 2.0;
        // 数值保护：对极扁三角形，p*(p-a) 可能略负
        double areaSq = Math.max(0, p * (p - a) * (p - b) * (p - c));
        double s = Math.sqrt(areaSq);
        return s * 2.0 / a;
    }

    /** 点是否落在圆（圆心 centerLat, centerLon，半径 rM 米）内 */
    public static boolean pointInCircle(double lat, double lon, double centerLat, double centerLon, double rM) {
        return haversineM(lat, lon, centerLat, centerLon) <= rM;
    }

    /** 局部平面投影点（用于凸包面积等平面几何）：以 (refLat, refLon) 为原点，正交等距近似，单位米 */
    public static double[] project(double lat, double lon, double refLat, double refLon) {
        double x = EARTH_RADIUS_M * Math.toRadians(lon - refLon) * Math.cos(Math.toRadians(refLat));
        double y = EARTH_RADIUS_M * Math.toRadians(lat - refLat);
        return new double[]{x, y};
    }

    /** 轨迹点列表投影为平面 (x,y)，参考点为首点 */
    public static double[][] projectPoints(List<TrackPoint> pts) {
        if (pts.isEmpty()) return new double[0][];
        double[][] xy = new double[pts.size()][];
        for (int i = 0; i < pts.size(); i++) {
            TrackPoint p = pts.get(i);
            xy[i] = project(p.lat, p.lon, pts.get(0).lat, pts.get(0).lon);
        }
        return xy;
    }

    /** 叉积方向：>0 逆时针，<0 顺时针，=0 共线（平面点） */
    public static double orient(double[] a, double[] b, double[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    /** Graham 扫描凸包（返回按逆时针排列的顶点，可能含共线点剔除）。输入为平面 (x,y) 点集 */
    public static List<double[]> convexHull(List<double[]> pts) {
        List<double[]> p = new ArrayList<>(pts);
        if (p.size() <= 3) return p;
        // 最左下点
        p.sort((u, v) -> {
            int c = Double.compare(u[0], v[0]);
            return c != 0 ? c : Double.compare(u[1], v[1]);
        });
        double[] base = p.get(0);
        p.sort((u, v) -> {
            double o = orient(base, u, v);
            if (Math.abs(o) > 1e-9) return o > 0 ? -1 : 1; // 极角小的在前（逆时针）
            double d1 = (u[0] - base[0]) * (u[0] - base[0]) + (u[1] - base[1]) * (u[1] - base[1]);
            double d2 = (v[0] - base[0]) * (v[0] - base[0]) + (v[1] - base[1]) * (v[1] - base[1]);
            return Double.compare(d1, d2);
        });
        List<double[]> hull = new ArrayList<>();
        hull.add(base);
        for (int i = 1; i < p.size(); i++) {
            while (hull.size() >= 2 && orient(hull.get(hull.size() - 2), hull.get(hull.size() - 1), p.get(i)) <= 0) {
                hull.remove(hull.size() - 1);
            }
            hull.add(p.get(i));
        }
        return hull;
    }

    /** 多边形面积（鞋带公式），平方米 */
    public static double polygonAreaM2(List<double[]> hull) {
        if (hull.size() < 3) return 0;
        double sum = 0;
        for (int i = 0; i < hull.size(); i++) {
            double[] a = hull.get(i);
            double[] b = hull.get((i + 1) % hull.size());
            sum += a[0] * b[1] - a[1] * b[0];
        }
        return Math.abs(sum) / 2.0;
    }
}
