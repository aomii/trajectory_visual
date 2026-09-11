package com.logicompress.experiment.semantic;

import com.logicompress.experiment.config.ExperimentConfig;
import com.logicompress.experiment.geo.GeoUtil;
import com.logicompress.experiment.model.StopUnit;
import com.logicompress.experiment.model.TrackPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * 静态停留识别（v2 方案1，见《停留识别方案变更_给claudecode_v2.md》第 3 节）。
 *
 * <p>货车北斗终端在行驶/ACC 档 30s 上报，拔钥匙后进入心跳模式（实测 5-6min 间隔，
 * 心跳点会上传轨迹）。真实停留（尤其过夜/装卸长停）的密集段往往只有几十秒，
 * 之后由稀疏心跳点续接——ST-DBSCAN 的时空邻域(epsTS=300s)连不上这些点，
 * 导致长停漏检、时长低估。本类不依赖聚类密度，直接用<b>位置不变</b>这一物理事实：
 * 连续<b>同坐标（位移≤ε_same，容忍 GPS 抖动）且速度=0</b>的点，即使时间间隔很大
 * （心跳/补传点），仍属同一静止段；段时长 = 首尾点时间差。
 *
 * <p>两次停车之间必有行驶点（spd&gt;0 或大位移），天然切断静止段，不会误并。
 * 每个停留段只输出 start/end 两个锚点（内部点不参与压缩，见 SegmentedDp）——
 * 起止时间戳已承载"停在哪、停多久"的语义。
 */
public class StationaryMerger {

    private StationaryMerger() {
    }

    /**
     * v2 主识别入口：返回全部静态停留单元（时间升序）。
     * 段时长 ≥ dMinS 才成单元；label 按时长 REST(≥30min)/TRAFFIC。
     *
     * @param pts 清洗后轨迹点（时间升序）
     * @param cfg 实验配置（stationarySameM / dMinS）
     */
    public static List<StopUnit> merge(List<TrackPoint> pts, ExperimentConfig cfg) {
        return findSegments(pts, cfg);
    }

    /**
     * 找全部静止段（候选停留单元）：
     * 遍历点序列，维护当前段 [start, i-1]；相邻两点均满足
     * {@code spd==0 && 位移≤ε_same} 则并入，否则闭合当前段并新开一段。
     * 段闭合时：时长（首尾时间差）≥ dMinS 才生成单元，label 按时长打标。
     */
    public static List<StopUnit> findSegments(List<TrackPoint> pts, ExperimentConfig cfg) {
        List<StopUnit> segments = new ArrayList<>();
        int n = pts.size();
        if (n == 0) return segments;
        int start = 0;
        for (int i = 1; i < n; i++) {
            TrackPoint prev = pts.get(i - 1);
            TrackPoint cur = pts.get(i);
            boolean sameStationary = cur.spdKph <= 1e-9 && prev.spdKph <= 1e-9
                    && GeoUtil.haversineM(prev.lat, prev.lon, cur.lat, cur.lon) <= cfg.stationarySameM;
            if (!sameStationary) {
                closeSegment(pts, start, i - 1, cfg, segments);
                start = i;
            }
        }
        closeSegment(pts, start, n - 1, cfg, segments);
        return segments;
    }

    /** 闭合静止段 [s, e]：时长 ≥ dMinS 则生成停留单元（起/终 = 段首/末点） */
    static void closeSegment(List<TrackPoint> pts, int s, int e, ExperimentConfig cfg, List<StopUnit> segments) {
        if (e <= s) return; // 单点不成段
        long tStart = pts.get(s).gtmEpoch;
        long tEnd = pts.get(e).gtmEpoch;
        long dur = tEnd - tStart;
        if (dur < cfg.dMinS) return;
        StopUnit u = new StopUnit();
        u.startIdx = s;
        u.endIdx = e;
        u.tStart = tStart;
        u.tEnd = tEnd;
        u.fromFence = false;
        u.label = dur >= SemanticAnalyzer.REST_MIN_S ? "REST" : "TRAFFIC";
        segments.add(u);
    }

    /** 两个停留单元的时间重叠比例：inter / min(两者时长)，供对比/交集统计复用 */
    public static double overlapRatio(StopUnit a, StopUnit b) {
        long inter = Math.min(a.tEnd, b.tEnd) - Math.max(a.tStart, b.tStart);
        if (inter <= 0) return 0;
        return (double) inter / Math.min(a.durationS(), b.durationS());
    }
}
