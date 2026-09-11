package com.logicompress.experiment.clean;

import com.logicompress.experiment.geo.GeoUtil;
import com.logicompress.experiment.model.TrackPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * 漂移点剔除 + 断线分段。
 * 漂移判定用经纬度推算的相邻速度（spd 字段本身不报警，数据画像已证实），
 * 阈值默认 120km/h（正常运输 q99.9=83.6km/h，>120 仅 56 对）。
 * 断线：相邻点 dt > breakGapS 处切段，不跨段插值。
 */
public class DriftFilter {

    /** 由相邻两点经纬度推算速度（km/h） */
    public static double inferredSpeedKph(TrackPoint a, TrackPoint b) {
        double dtS = (b.gtmEpoch - a.gtmEpoch);
        if (dtS <= 0) return Double.POSITIVE_INFINITY; // 时间不推进视为异常
        double dM = GeoUtil.haversineM(a.lat, a.lon, b.lat, b.lon);
        return dM / dtS * 3.6; // m/s → km/h
    }

    public CleanedTrack clean(List<TrackPoint> raw, double driftSpeedKph, long breakGapS) {
        List<TrackPoint> pts = new ArrayList<>();
        int n = raw.size();
        boolean[] drift = new boolean[n];
        int removedDrift = 0;

        // 标记漂移点：任一条关联边速度超阈值
        for (int i = 0; i < n; i++) {
            boolean prevBad = i > 0 && inferredSpeedKph(raw.get(i - 1), raw.get(i)) > driftSpeedKph;
            boolean nextBad = i < n - 1 && inferredSpeedKph(raw.get(i), raw.get(i + 1)) > driftSpeedKph;
            if (prevBad || nextBad) {
                drift[i] = true;
            }
        }
        for (int i = 0; i < n; i++) {
            if (drift[i]) {
                removedDrift++;
                continue;
            }
            pts.add(raw.get(i));
        }

        // 断线切分（在清洗后序列上）
        List<int[]> segments = new ArrayList<>();
        if (pts.isEmpty()) {
            return new CleanedTrack(pts, segments, removedDrift);
        }
        int segStart = 0;
        for (int i = 1; i < pts.size(); i++) {
            if (pts.get(i).gtmEpoch - pts.get(i - 1).gtmEpoch > breakGapS) {
                segments.add(new int[]{segStart, i - 1});
                segStart = i;
            }
        }
        segments.add(new int[]{segStart, pts.size() - 1});
        return new CleanedTrack(pts, segments, removedDrift);
    }
}
