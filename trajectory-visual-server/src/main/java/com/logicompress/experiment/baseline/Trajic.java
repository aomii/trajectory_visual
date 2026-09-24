package com.logicompress.experiment.baseline;

import com.logicompress.experiment.clean.CleanedTrack;
import com.logicompress.experiment.geo.GeoUtil;
import com.logicompress.experiment.model.TrackPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * 线性预测抽稀启发式。Trajic 是历史代码标识；本类只输出保留点下标，
 * 并未实现原 Trajic 论文的预测残差编码系统。
 * 每次判断比较当前点和最近两个已发点的外推预测误差；该局部误差与最终
 * 保留折线的时间同步 SED 不同，tolM 不是最终折线 SED 的上界。
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
                    continue; // 当前外推预测误差未超阈值；不保证最终折线 SED
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
