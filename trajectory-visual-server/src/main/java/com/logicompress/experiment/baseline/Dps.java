package com.logicompress.experiment.baseline;

import com.logicompress.experiment.clean.CleanedTrack;
import com.logicompress.experiment.compress.DpUtil;
import com.logicompress.experiment.geo.GeoUtil;
import com.logicompress.experiment.model.TrackPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * DPS（Direction-Preserving）：DP 后处理——把被抽稀压平的大转向角（急弯/路口）点重新插回，
 * 保留方向信息。阈值：原始子路径上转向角 ≥ turnDeg 的点重新保留。
 */
public class Dps implements LossyBaseline {

    /** 转向角阈值（度） */
    public static final double TURN_DEG = 30.0;
    private static final int MAX_ITER = 20;

    @Override
    public List<Integer> compress(CleanedTrack cleaned, double tolM) {
        List<TrackPoint> pts = cleaned.points;
        boolean[] keep = new boolean[pts.size()];
        // 先跑纯 DP
        for (int[] seg : cleaned.segments) {
            DpUtil.dp(pts, seg[0], seg[1], tolM, keep);
        }
        // 再按断线分段做方向保持回插
        for (int[] seg : cleaned.segments) {
            reinsertTurns(pts, seg[0], seg[1], keep, TURN_DEG);
        }
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            if (keep[i]) result.add(i);
        }
        return result;
    }

    /** 在 [a0,b0] 子段内迭代：若保留点之间的原始子路径存在大转向点，则插回转向最大的点 */
    private void reinsertTurns(List<TrackPoint> pts, int a0, int b0, boolean[] keep, double turnDeg) {
        for (int iter = 0; iter < MAX_ITER; iter++) {
            boolean changed = false;
            List<Integer> retained = new ArrayList<>();
            for (int i = a0; i <= b0; i++) if (keep[i]) retained.add(i);
            for (int k = 0; k + 2 < retained.size(); k++) {
                int a = retained.get(k);
                int c = retained.get(k + 2);
                if (c - a < 2) continue;
                int best = -1;
                double bestTurn = 0;
                for (int j = a + 1; j < c; j++) {
                    if (keep[j]) continue;
                    double turn = turnAt(pts, j);
                    if (turn > bestTurn) {
                        bestTurn = turn;
                        best = j;
                    }
                }
                if (best >= 0 && bestTurn >= turnDeg) {
                    keep[best] = true;
                    changed = true;
                }
            }
            if (!changed) break;
        }
    }

    /** 点 j 处方向变化角（0~180°），用平面投影方位角计算 */
    private double turnAt(List<TrackPoint> pts, int j) {
        double b1 = bearing(pts.get(j - 1), pts.get(j));
        double b2 = bearing(pts.get(j), pts.get(j + 1));
        double d = Math.abs(b1 - b2);
        if (d > 180) d = 360 - d;
        return d;
    }

    private double bearing(TrackPoint from, TrackPoint to) {
        double[] p = GeoUtil.project(from.lat, from.lon, from.lat, from.lon);
        double[] q = GeoUtil.project(to.lat, to.lon, from.lat, from.lon);
        return Math.toDegrees(Math.atan2(q[1] - p[1], q[0] - p[0]));
    }
}
