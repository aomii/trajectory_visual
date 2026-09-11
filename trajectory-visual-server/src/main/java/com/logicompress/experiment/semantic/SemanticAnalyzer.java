package com.logicompress.experiment.semantic;

import com.logicompress.experiment.clean.CleanedTrack;
import com.logicompress.experiment.config.ExperimentConfig;
import com.logicompress.experiment.model.SemanticResult;
import com.logicompress.experiment.model.StopUnit;
import com.logicompress.experiment.model.TrackPoint;
import com.logicompress.experiment.model.Waybill;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义识别编排（论文研究内容一）。
 *
 * <p>v2 方案1 主路径（{@code cfg.stationaryMergeEnabled=true}，默认）：
 * <b>静态停留识别</b>——{@link StationaryMerger} 用"同坐标 + spd=0 跨大间隔合并"
 * 识别全部静态停留段（在哪停、停多久），每个停留段只保留 start/end 两个锚点。
 * 不做装卸货围栏分类、不做 ST-DBSCAN 时空聚类（其多抓的低速爬行停语义弱，
 * 且围栏在卸货侧大面积失效，见《停留识别方案变更_给claudecode_v2.md》）。
 *
 * <p>legacy 分层方法（{@code cfg.stationaryMergeEnabled=false}，供对比/回退）：
 * L1 电子围栏强匹配（LOAD/UNLOAD）→ L2 种子引导 ST-DBSCAN → L3 运动学精修，
 * 即 2026-08-21 原始口径（995 单元 / 1897 锚点）。
 *
 * <p>两种路径统一重建关键锚点 = 所有停留单元首末点 + 轨迹起止点，供第 4 章
 * 分级 DP 抽稀强制保留（SR 恒为 100%）。
 */
public class SemanticAnalyzer {

    /** 非围栏停留的类别阈值：≥30min 休息，其余为堵车/短停 */
    public static final long REST_MIN_S = 1800;

    /**
     * 语义识别编排入口。
     *
     * @return 停留单元集合 + 关键锚点集合（+ legacy 路径的簇号/围栏命中点诊断）
     */
    public SemanticResult analyze(Waybill w, CleanedTrack cleaned, ExperimentConfig cfg) {
        SemanticResult result = new SemanticResult();
        List<TrackPoint> pts = cleaned.points;
        if (pts.isEmpty()) return result;

        if (cfg.stationaryMergeEnabled) {
            // v2 主路径：静态停留识别（方案1）
            result.stops = StationaryMerger.merge(pts, cfg);
        } else {
            // legacy 分层方法（原始口径，供对比/回退）
            runLegacyLayered(w, pts, cfg, result);
        }

        // 重建关键锚点：停留单元首末点 + 轨迹起止点
        result.anchors.clear();
        for (StopUnit u : result.stops) {
            result.anchors.add(u.startIdx);
            result.anchors.add(u.endIdx);
        }
        result.addTrackEndpoints(pts.size() - 1);
        return result;
    }

    /**
     * legacy 分层识别（2026-08-21 口径，stationaryMergeEnabled=false 时）：
     * L1 电子围栏强匹配锁定装卸货 → L2 以 L1 命中点为种子做 ST-DBSCAN，
     * 非围栏簇按时长打标 REST/TRAFFIC 且与 L1 重叠 ≥50% 剔除 → L3 运动学精修。
     */
    private void runLegacyLayered(Waybill w, List<TrackPoint> pts, ExperimentConfig cfg, SemanticResult result) {
        // L1 围栏强匹配
        FenceMatcher fenceMatcher = new FenceMatcher();
        FenceMatcher.FenceHit sendHit = new FenceMatcher.FenceHit();
        FenceMatcher.FenceHit recvHit = new FenceMatcher.FenceHit();
        fenceMatcher.match(w, pts, cfg, result, sendHit, recvHit);

        // L2 种子引导 ST-DBSCAN（种子 = L1 围栏命中点）
        StDbscan dbscan = StDbscan.cluster(pts, cfg.dbscanEpsM, cfg.dbscanEpsTS, cfg.dbscanMinPts,
                result.fenceHitIdx);
        result.clusterId = dbscan.clusterId;

        // 非围栏簇 → 停留单元（时长 ≥ D_min，且不与 L1 单元显著时间重叠）
        List<StopUnit> l1Units = new ArrayList<>(result.stops);
        for (List<Integer> cluster : dbscan.clusters) {
            int minIdx = -1, maxIdx = -1;
            long tMin = Long.MAX_VALUE, tMax = Long.MIN_VALUE;
            for (int idx : cluster) {
                long t = pts.get(idx).gtmEpoch;
                if (t < tMin) { tMin = t; minIdx = idx; }
                if (t > tMax) { tMax = t; maxIdx = idx; }
            }
            long dur = tMax - tMin;
            if (dur < cfg.dMinS) continue;
            StopUnit u = new StopUnit();
            u.startIdx = minIdx;
            u.endIdx = maxIdx;
            u.tStart = tMin;
            u.tEnd = tMax;
            u.fromFence = false;
            u.label = dur >= REST_MIN_S ? "REST" : "TRAFFIC";
            if (overlapsL1(u, l1Units)) continue;
            result.stops.add(u);
        }

        // L3 运动学精修边界
        KinematicRefine.refine(pts, result.stops, cfg);
    }

    /**
     * 判断非围栏簇 u 是否与任一 L1 停留单元时间显著重叠：
     * 重叠时长 / min(两者时长) ≥ 50% 视为同一停留被 L1/L2 重复检出，返回 true（应剔除）。
     */
    private boolean overlapsL1(StopUnit u, List<StopUnit> l1Units) {
        for (StopUnit l1 : l1Units) {
            long inter = Math.min(u.tEnd, l1.tEnd) - Math.max(u.tStart, l1.tStart);
            if (inter <= 0) continue;
            double overlapRatio = (double) inter / Math.min(u.durationS(), l1.durationS());
            if (overlapRatio >= 0.5) return true;
        }
        return false;
    }
}
