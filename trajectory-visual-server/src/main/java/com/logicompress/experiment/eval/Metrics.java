package com.logicompress.experiment.eval;

import com.logicompress.experiment.geo.GeoUtil;
import com.logicompress.experiment.model.StopUnit;
import com.logicompress.experiment.model.TrackPoint;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 评估指标计算（论文第 6 章实验口径）：
 *
 * <p>PED（位置误差）：原始轨迹点到压缩折线的垂直距离。距离口径与公司
 * GisDouglasUtil 一致（Haversine + 海伦公式，见 {@link GeoUtil}）。
 * 每个被抽稀点归属到它所在的相邻保留段 [a,b]（按序列下标），段内点到弦的垂直距离即 PED。
 *
 * <p>SED（同步欧氏距离）：原始点到"按时间比例落在压缩折线上的同步点"的距离。
 * 对每个原始点 i，取时间上包夹它的保留段 [a,b]，按 (t_i−t_a)/(t_b−t_a) 在线段上插值出同步点，
 * 再求欧氏距离（局部平面投影）。口径与 TD-TR / Trajic 的误差定义一致。
 *
 * <p>SR（语义点保留率）：压缩后保留的锚点数 / 锚点总数。锚点 = 停留单元首末点 + 轨迹起止点。
 * 本文方法锚点强制保留（理论值恒为 100%），基线算法无语义信息，SR 反映其语义丢失程度。
 *
 * <p>naiveBytes（无损层基准）：把压缩保留点按"每字段全精度十进制"写成规范文本的字节数，
 * 作为无损编码前的"每点原始字节"，用于无损层压缩率口径（见 Main）。
 */
public final class Metrics {

    private Metrics() {
    }

    /** PED / SED 统计结果（米），由 {@link #pedSed} 一次扫描计算。 */
    public static class Error {
        /** PED 均值：全部原始点到压缩折线的垂直距离平均，米 */
        public double avgPedM;
        /** PED 最大值，米 */
        public double maxPedM;
        /** SED 均值：全部原始点到"按时间插值的同步点"的距离平均，米 */
        public double avgSedM;
        /** SED 最大值，米 */
        public double maxSedM;
    }

    /**
     * 一次扫描同时计算 PED 与 SED。
     *
     * @param pts  清洗后轨迹点（时间升序）
     * @param kept 压缩保留点在 pts 中的下标（升序）
     */
    public static Error pedSed(List<TrackPoint> pts, List<Integer> kept) {
        Error e = new Error();
        int n = pts.size();
        if (n == 0 || kept == null || kept.isEmpty()) return e;

        // 极端情况：压缩后只剩 1 个点，整条轨迹坍缩为该点，全部点到它取距离
        if (kept.size() < 2) {
            int a = kept.get(0);
            for (int i = 0; i < n; i++) {
                if (i == a) continue;
                double d = GeoUtil.haversineM(pts.get(i).lat, pts.get(i).lon,
                        pts.get(a).lat, pts.get(a).lon);
                e.avgPedM += d;
                e.avgSedM += d;
                if (d > e.maxPedM) e.maxPedM = d;
                if (d > e.maxSedM) e.maxSedM = d;
            }
            e.avgPedM /= n;
            e.avgSedM /= n;
            return e;
        }

        // ---- PED：遍历相邻保留段，段内被抽稀点求到弦的垂直距离 ----
        double sumPed = 0;
        for (int m = 0; m + 1 < kept.size(); m++) {
            int a = kept.get(m);
            int b = kept.get(m + 1);
            for (int i = a + 1; i < b; i++) {
                double d = GeoUtil.distPointToSegmentM(pts.get(i).lat, pts.get(i).lon,
                        pts.get(a).lat, pts.get(a).lon, pts.get(b).lat, pts.get(b).lon);
                sumPed += d;
                if (d > e.maxPedM) e.maxPedM = d;
            }
        }
        // 首保留点之前的点、末保留点之后的点（防御），计入首末段
        for (int i = 0; i < kept.get(0); i++) {
            double d = GeoUtil.haversineM(pts.get(i).lat, pts.get(i).lon,
                    pts.get(kept.get(0)).lat, pts.get(kept.get(0)).lon);
            sumPed += d;
            if (d > e.maxPedM) e.maxPedM = d;
        }
        int last = kept.get(kept.size() - 1);
        for (int i = last + 1; i < n; i++) {
            double d = GeoUtil.haversineM(pts.get(i).lat, pts.get(i).lon,
                    pts.get(last).lat, pts.get(last).lon);
            sumPed += d;
            if (d > e.maxPedM) e.maxPedM = d;
        }
        e.avgPedM = sumPed / n;

        // ---- SED：按时间比例插值同步点，双指针推进（时间升序） ----
        double sumSed = 0;
        int k = 0;
        for (int i = 0; i < n; i++) {
            long ti = pts.get(i).gtmEpoch;
            while (k + 1 < kept.size() && pts.get(kept.get(k + 1)).gtmEpoch <= ti) {
                k++;
            }
            int a = kept.get(k);
            int b = (k + 1 < kept.size()) ? kept.get(k + 1) : a;
            double d;
            if (b == a) {
                d = GeoUtil.haversineM(pts.get(i).lat, pts.get(i).lon,
                        pts.get(a).lat, pts.get(a).lon);
            } else {
                TrackPoint pa = pts.get(a);
                TrackPoint pb = pts.get(b);
                double r = (pb.gtmEpoch == pa.gtmEpoch) ? 0.0
                        : (double) (ti - pa.gtmEpoch) / (pb.gtmEpoch - pa.gtmEpoch);
                if (r < 0) r = 0;
                if (r > 1) r = 1;
                // 局部平面投影（以 pa 为原点），口径与 TdTr 一致
                double[] s0 = GeoUtil.project(pa.lat, pa.lon, pa.lat, pa.lon);
                double[] s1 = GeoUtil.project(pb.lat, pb.lon, pa.lat, pa.lon);
                double[] pi = GeoUtil.project(pts.get(i).lat, pts.get(i).lon, pa.lat, pa.lon);
                double sx = s0[0] + r * (s1[0] - s0[0]);
                double sy = s0[1] + r * (s1[1] - s0[1]);
                d = Math.hypot(pi[0] - sx, pi[1] - sy);
            }
            sumSed += d;
            if (d > e.maxSedM) e.maxSedM = d;
        }
        e.avgSedM = sumSed / n;
        return e;
    }

    /**
     * 语义点保留率：锚点中被压缩后保留的比例（0~1）。
     * 锚点集合为空时按 1.0 处理（无语义约束可比较）。
     */
    public static double semanticRetention(Set<Integer> anchors, List<Integer> kept) {
        if (anchors == null || anchors.isEmpty()) return 1.0;
        Set<Integer> keptSet = new HashSet<>(kept);
        long hit = 0;
        for (int a : anchors) {
            if (keptSet.contains(a)) hit++;
        }
        return (double) hit / anchors.size();
    }

    /**
     * 保留点掩码：boolean[nPoints]，kept 中的下标置 true。
     * 供单元完整率/停留时长保真度等按单元统计的指标复用（避免重复建 HashSet）。
     */
    public static boolean[] indexMask(List<Integer> kept, int nPoints) {
        boolean[] mask = new boolean[nPoints];
        if (kept == null) return mask;
        for (int i : kept) {
            if (i >= 0 && i < nPoints) mask[i] = true;
        }
        return mask;
    }

    /**
     * 语义单元完整率（claude_08 核心卖点）：起、终锚点<b>成对保留</b>的停留单元数 / 单元总数。
     * 起终点任一丢失即算该单元不完整（停留时长不可恢复）。空单元集按 1.0（无语义约束可比较）。
     */
    public static double unitIntegrity(List<StopUnit> stops, boolean[] keptMask) {
        if (stops == null || stops.isEmpty()) return 1.0;
        long complete = 0;
        for (StopUnit u : stops) {
            boolean sOk = u.startIdx >= 0 && u.startIdx < keptMask.length && keptMask[u.startIdx];
            boolean eOk = u.endIdx >= 0 && u.endIdx < keptMask.length && keptMask[u.endIdx];
            if (sOk && eOk) complete++;
        }
        return (double) complete / stops.size();
    }

    /**
     * 停留时长保真度（claude_08 指标③）：逐单元"保留点可恢复时长 / 原始时长"的均值（0~1）。
     * 单元内保留点时间跨度（首/末保留点 gtm 差）相对原始停留时长；单元内无保留点计 0；
     * <b>对所有单元求均值</b>（含不完整单元，避免只看完整单元掩盖失效）。
     * 时长 ≤0 的单元跳过；空单元集按 1.0。
     */
    public static double dwellFidelity(List<TrackPoint> pts, List<StopUnit> stops, boolean[] keptMask) {
        if (stops == null || stops.isEmpty()) return 1.0;
        double sum = 0;
        long count = 0;
        for (StopUnit u : stops) {
            double dur = u.durationS();
            if (dur <= 0) continue;
            long firstT = -1, lastT = -1;
            int lo = Math.max(0, u.startIdx);
            int hi = Math.min(pts.size() - 1, u.endIdx);
            for (int i = lo; i <= hi; i++) {
                if (i >= 0 && i < keptMask.length && keptMask[i]) {
                    if (firstT < 0) firstT = pts.get(i).gtmEpoch;
                    lastT = pts.get(i).gtmEpoch;
                }
            }
            double recovered;
            if (firstT < 0) {
                recovered = 0; // 单元内无保留点，可恢复时长 0
            } else {
                recovered = (double) (lastT - firstT) / dur;
                if (recovered < 0) recovered = 0;
                if (recovered > 1) recovered = 1;
            }
            sum += recovered;
            count++;
        }
        return count == 0 ? 1.0 : sum / count;
    }

    /**
     * 停留点保留率：逐单元"单元内被保留的点数 / 单元原始点数"的均值（0~1）。
     * 补出 dwellFidelity 单独看不出的<b>"只保起终、内部掏空"</b>失效模式：
     * 起终锚点成对保留但中间点全删时，时长可恢复（fidelity=1）但内部密度已失真。
     */
    public static double dwellPointRetention(List<StopUnit> stops, boolean[] keptMask) {
        if (stops == null || stops.isEmpty()) return 1.0;
        double sum = 0;
        long count = 0;
        for (StopUnit u : stops) {
            int total = u.endIdx - u.startIdx + 1;
            if (total <= 0) continue;
            int kept = 0;
            int lo = Math.max(0, u.startIdx);
            int hi = Math.min(keptMask.length - 1, u.endIdx);
            for (int i = lo; i <= hi; i++) {
                if (keptMask[i]) kept++;
            }
            sum += (double) kept / total;
            count++;
        }
        return count == 0 ? 1.0 : sum / count;
    }

    /**
     * 无损层压缩前基准：把点序列写成"每字段全精度十进制"的规范文本。
     * 字段与 {@code BlockOffsetCodec} 一致：lat/lon/spd/hgt/agl 量化精度 precision，
     * gtm 为 epoch 秒整数；字段逗号分隔、点分号结尾。作为"每点原始字节"的压缩前基准。
     */
    public static String naiveAscii(List<TrackPoint> pts, int precision) {
        if (pts == null || pts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(pts.size() * 48);
        String fmt = "%." + precision + "f";
        for (TrackPoint p : pts) {
            sb.append(String.format(fmt, p.lat)).append(',');
            sb.append(String.format(fmt, p.lon)).append(',');
            sb.append(String.format(fmt, p.spdKph)).append(',');
            sb.append(String.format(fmt, p.hgt)).append(',');
            sb.append(String.format(fmt, p.aglHeading)).append(',');
            sb.append(p.gtmEpoch).append(';');
        }
        return sb.toString();
    }

    /** {@link #naiveAscii} 的字节数 */
    public static long naiveAsciiBytes(List<TrackPoint> pts, int precision) {
        return naiveAscii(pts, precision).getBytes(StandardCharsets.US_ASCII).length;
    }
}
