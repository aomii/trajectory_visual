package com.logicompress.experiment.compress;

import com.logicompress.experiment.clean.CleanedTrack;
import com.logicompress.experiment.geo.GeoUtil;
import com.logicompress.experiment.model.TrackPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * 公共 DP 工具：递归 Douglas-Peucker，距离口径对齐公司 GisDouglasUtil
 * （Haversine + 海伦公式），maxDist ≥ tol 时保留分裂点。
 */
public final class DpUtil {

    private DpUtil() {
    }

    /** 对子段 [start,end] 执行递归 DP，选中的点置 keep[i]=true */
    public static void dp(List<TrackPoint> pts, int start, int end, double tol, boolean[] keep) {
        if (end - start < 2) {
            keep[start] = true;
            keep[end] = true;
            return;
        }
        double maxDist = -1;
        int split = -1;
        TrackPoint s = pts.get(start);
        TrackPoint e = pts.get(end);
        for (int i = start + 1; i < end; i++) {
            TrackPoint p = pts.get(i);
            double d = GeoUtil.distPointToSegmentM(p.lat, p.lon, s.lat, s.lon, e.lat, e.lon);
            if (d > maxDist) {
                maxDist = d;
                split = i;
            }
        }
        if (maxDist >= tol) {
            keep[split] = true;
            dp(pts, start, split, tol, keep);
            dp(pts, split, end, tol, keep);
        } else {
            keep[start] = true;
            keep[end] = true;
        }
    }

    /** 对整个清洗轨迹（按断线分段）跑固定容差 DP，返回保留下标 */
    public static List<Integer> dpOnSegments(CleanedTrack cleaned, double tol) {
        List<TrackPoint> pts = cleaned.points;
        boolean[] keep = new boolean[pts.size()];
        for (int[] seg : cleaned.segments) {
            dp(pts, seg[0], seg[1], tol, keep);
        }
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            if (keep[i]) result.add(i);
        }
        return result;
    }
}
