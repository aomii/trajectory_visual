package com.logicompress.experiment.semantic;

import com.logicompress.experiment.geo.GeoUtil;
import com.logicompress.experiment.model.SemanticResult;
import com.logicompress.experiment.model.StopUnit;
import com.logicompress.experiment.model.TrackPoint;
import com.logicompress.experiment.model.Waybill;
import com.logicompress.experiment.config.ExperimentConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * L1 电子围栏强匹配：以收发货地址经纬度为圆心、半径为 fenceRadiusM（默认 2000m）的圆形围栏。
 * 命中：连续点段落在圆内且时长 ≥ D_min，且与 loadTime/unloadTime 时窗吻合（±l1TimeWindowToleranceS）。
 * 输出装货(LOAD)/卸货(UNLOAD)停留单元并记录围栏命中点（作为 L2 ST-DBSCAN 的种子）。
 */
public class FenceMatcher {

    /** 对单个圆形围栏做匹配；返回命中点下标集合与生成的停留单元 */
    public static class FenceHit {
        public final Set<Integer> hitIdx = new HashSet<>();
        public final List<StopUnit> units = new ArrayList<>();
    }

    /**
     * L1 电子围栏强匹配入口：对发货（装货）围栏与收货（卸货）围栏各调用一次
     * {@link #findRuns}，把两个围栏的命中点汇入 result.fenceHitIdx——
     * 该集合同时作为 L2 ST-DBSCAN 的聚类种子（见 SemanticAnalyzer.analyze）。
     *
     * @param sendHit 装货围栏的命中点与生成的 LOAD 停留单元（供诊断）
     * @param recvHit 卸货围栏的命中点与生成的 UNLOAD 停留单元（供诊断）
     */
    public void match(Waybill w, List<TrackPoint> pts, ExperimentConfig cfg,
                      SemanticResult result, FenceHit sendHit, FenceHit recvHit) {
        // 发送（装货）围栏
        if (w.sendLon != 0 || w.sendLat != 0) {
            findRuns(pts, w.sendLat, w.sendLon, cfg.fenceRadiusM, w.loadEpoch, "LOAD", "SEND",
                    cfg, sendHit, result);
        }
        // 接收（卸货）围栏
        if (w.receiveLon != 0 || w.receiveLat != 0) {
            findRuns(pts, w.receiveLat, w.receiveLon, cfg.fenceRadiusM, w.unloadEpoch, "UNLOAD", "RECEIVE",
                    cfg, recvHit, result);
        }
        result.fenceHitIdx.addAll(sendHit.hitIdx);
        result.fenceHitIdx.addAll(recvHit.hitIdx);
    }

    /**
     * 对单个圆形围栏扫描连续命中段（run）——L1 核心逻辑：
     * 轨迹按时间序线性扫描，每段"连续落在圆内"的点区间 [start,end] 为一个候选 run；
     * 只有当 run 同时满足
     *   (1) 时长 ≥ dMinS（最短停留），且
     *   (2) 时间与目标业务时刻相交：tStart ≤ targetEpoch+容差 且 tEnd ≥ targetEpoch−容差
     * 才生成停留单元并登记首末锚点；否则仅保留命中点（供 L2 作种子），不生成单元。
     * 起/止点即"进入围栏第 1 点 / 离开围栏最后 1 点"（论文 3.2 L1 定义）。
     */
    private void findRuns(List<TrackPoint> pts, double centerLat, double centerLon, double radiusM,
                          long targetEpoch, String label, String side, ExperimentConfig cfg,
                          FenceHit hit, SemanticResult result) {
        int n = pts.size();
        int i = 0;
        while (i < n) {
            if (GeoUtil.pointInCircle(pts.get(i).lat, pts.get(i).lon, centerLat, centerLon, radiusM)) {
                int start = i;
                int end = i;
                while (end + 1 < n && GeoUtil.pointInCircle(pts.get(end + 1).lat, pts.get(end + 1).lon,
                        centerLat, centerLon, radiusM)) {
                    end++;
                }
                long tStart = pts.get(start).gtmEpoch;
                long tEnd = pts.get(end).gtmEpoch;
                for (int k = start; k <= end; k++) hit.hitIdx.add(k);
                long dur = tEnd - tStart;
                // 时长足够 且 与业务时窗吻合（区间相交）
                boolean inWindow = dur >= cfg.dMinS
                        && tStart <= targetEpoch + cfg.l1TimeWindowToleranceS
                        && tEnd >= targetEpoch - cfg.l1TimeWindowToleranceS;
                if (inWindow) {
                    StopUnit u = new StopUnit();
                    u.startIdx = start;
                    u.endIdx = end;
                    u.tStart = tStart;
                    u.tEnd = tEnd;
                    u.label = label;
                    u.fromFence = true;
                    u.fenceSide = side;
                    result.stops.add(u);
                    result.anchors.add(start);
                    result.anchors.add(end);
                    hit.units.add(u);
                }
                i = end + 1;
            } else {
                i++;
            }
        }
    }
}
