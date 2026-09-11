package com.logicompress.experiment.baseline;

import com.logicompress.experiment.clean.CleanedTrack;
import com.logicompress.experiment.geo.GeoUtil;
import com.logicompress.experiment.model.TrackPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * TD-TR（Top-Down Time-Ratio）：DP 变体，分裂准则用同步欧氏距离 SED——
 * 对每个候选点，比较其到"按时间比例落在弦上的同步点"的距离，保时间对齐。
 */
public class TdTr implements LossyBaseline {

    @Override
    public List<Integer> compress(CleanedTrack cleaned, double tolM) {
        List<TrackPoint> pts = cleaned.points;
        boolean[] keep = new boolean[pts.size()];
        for (int[] seg : cleaned.segments) {
            tdtr(pts, seg[0], seg[1], tolM, keep);
        }
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            if (keep[i]) result.add(i);
        }
        return result;
    }

    private void tdtr(List<TrackPoint> pts, int start, int end, double tol, boolean[] keep) {
        if (end - start < 2) {
            keep[start] = true;
            keep[end] = true;
            return;
        }
        double maxSed = -1;
        int split = -1;
        long t0 = pts.get(start).gtmEpoch;
        long t1 = pts.get(end).gtmEpoch;
        double dt = t1 - t0;
        double[] s = GeoUtil.project(pts.get(start).lat, pts.get(start).lon, pts.get(start).lat, pts.get(start).lon);
        double[] e = GeoUtil.project(pts.get(end).lat, pts.get(end).lon, pts.get(start).lat, pts.get(start).lon);
        for (int i = start + 1; i < end; i++) {
            double r = dt == 0 ? 0.5 : (double) (pts.get(i).gtmEpoch - t0) / dt;
            double sx = s[0] + r * (e[0] - s[0]);
            double sy = s[1] + r * (e[1] - s[1]);
            double[] p = GeoUtil.project(pts.get(i).lat, pts.get(i).lon, pts.get(start).lat, pts.get(start).lon);
            double d = Math.hypot(p[0] - sx, p[1] - sy);
            if (d > maxSed) {
                maxSed = d;
                split = i;
            }
        }
        if (maxSed >= tol) {
            keep[split] = true;
            tdtr(pts, start, split, tol, keep);
            tdtr(pts, split, end, tol, keep);
        } else {
            keep[start] = true;
            keep[end] = true;
        }
    }
}
