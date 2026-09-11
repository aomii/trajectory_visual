package com.logicompress.experiment.baseline;

import com.logicompress.experiment.clean.CleanedTrack;
import com.logicompress.experiment.geo.GeoUtil;
import com.logicompress.experiment.model.TrackPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Trajic（预测编码 + SED 误差界）：用最近两个已发点做线性外推预测当前位置，
 * 预测误差（同步欧氏距离）在 tol 内则缓冲不发点，超界则发修正点重置预测器。
 * 被跳过的点满足 SED ≤ tol（误差有界），属离线可用的批次压缩基线。
 */
public class Trajic implements LossyBaseline {

    @Override
    public List<Integer> compress(CleanedTrack cleaned, double tolM) {
        List<TrackPoint> pts = cleaned.points;
        List<Integer> out = new ArrayList<>();
        double refLat = pts.get(0).lat;
        double refLon = pts.get(0).lon;
        for (int[] seg : cleaned.segments) {
            int s = seg[0];
            int e = seg[1];
            out.add(s);
            if (e <= s) continue;
            // 预测状态：最近两个已发点（时间 + 平面坐标）
            long t0 = pts.get(s).gtmEpoch;
            long t1 = t0;
            double[] p0 = GeoUtil.project(pts.get(s).lat, pts.get(s).lon, refLat, refLon);
            double[] p1 = p0;
            for (int j = s + 1; j <= e; j++) {
                long tj = pts.get(j).gtmEpoch;
                double r = (t1 - t0) == 0 ? 0 : (double) (tj - t0) / (t1 - t0);
                double px = p0[0] + r * (p1[0] - p0[0]);
                double py = p0[1] + r * (p1[1] - p0[1]);
                double[] actual = GeoUtil.project(pts.get(j).lat, pts.get(j).lon, refLat, refLon);
                double sed = Math.hypot(actual[0] - px, actual[1] - py);
                if (sed <= tolM) {
                    continue; // 误差有界，缓冲
                }
                out.add(j); // 修正点
                p0 = p1;
                p1 = actual;
                t0 = t1;
                t1 = tj;
            }
            if (out.get(out.size() - 1) != e) out.add(e); // 保证终点
        }
        // 去重并保持升序
        List<Integer> result = new ArrayList<>();
        int prev = -1;
        for (int i : out) {
            if (i != prev) {
                result.add(i);
                prev = i;
            }
        }
        return result;
    }
}
