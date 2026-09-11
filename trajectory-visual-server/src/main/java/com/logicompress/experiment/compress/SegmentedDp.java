package com.logicompress.experiment.compress;

import com.logicompress.experiment.clean.CleanedTrack;
import com.logicompress.experiment.config.ExperimentConfig;
import com.logicompress.experiment.model.SemanticResult;
import com.logicompress.experiment.model.StopUnit;
import com.logicompress.experiment.model.TrackPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 带锚点的分级 DP 抽稀（论文算法 4-1，v2 口径，见《停留识别方案变更_v2》任务 4）：
 *
 * <ul>
 *   <li>锚点集合 = 停留单元（静态停留段）首末点 + 轨迹起止点 + 断线分段端点；</li>
 *   <li><b>静止段内部不跑 DP</b>：锚点对 [a,b] 完全落在停留单元内 → 只保留起止锚点
 *       （内部点挤在原地、无几何信息，起止时间戳已承载停留时长语义）；</li>
 *   <li>移动路段：统一 DP 抽稀（容差 {@code cfg.dpEpsNonKeyM}）；</li>
 *   <li>锚点强制保留（作为段端点天然保留，显式置 keep）。</li>
 * </ul>
 * DP 距离口径与公司 GisDouglasUtil 一致（Haversine + 海伦公式）。
 */
public class SegmentedDp {

    /** 返回压缩后保留的点在清洗后轨迹中的下标（时间升序） */
    public List<Integer> compress(CleanedTrack cleaned, SemanticResult semantic, ExperimentConfig cfg) {
        List<TrackPoint> pts = cleaned.points;
        int n = pts.size();
        boolean[] keep = new boolean[n];

        // 锚点 = 语义锚点 + 断线分段端点（消融时可通过 cfg.noAnchorForce 去掉语义锚点，仅保留分段端点）
        TreeSet<Integer> anchors = new TreeSet<>();
        if (!cfg.noAnchorForce) {
            anchors.addAll(semantic.anchors);
        }
        for (int[] seg : cleaned.segments) {
            anchors.add(seg[0]);
            anchors.add(seg[1]);
        }
        // 取区间内的锚点（0..n-1）
        Integer[] arr = anchors.stream().filter(i -> i >= 0 && i < n).toArray(Integer[]::new);

        // 停留单元（静态停留段）区间：段 (a,b) 完全落在某单元 [start,end] 内 → 静止段内部
        long[][] unitBounds = new long[0][];
        if (!semantic.stops.isEmpty()) {
            unitBounds = new long[semantic.stops.size()][];
            for (int k = 0; k < semantic.stops.size(); k++) {
                StopUnit u = semantic.stops.get(k);
                unitBounds[k] = new long[]{u.startIdx, u.endIdx};
            }
        }

        for (int i = 0; i + 1 < arr.length; i++) {
            int a = arr[i];
            int b = arr[i + 1];
            if (b <= a) continue;
            if (insideStopUnit(a, b, unitBounds)) {
                // v2：静止段内部不跑 DP，只保留起止锚点
                keep[a] = true;
                keep[b] = true;
            } else {
                // 移动路段：统一 DP 抽稀
                DpUtil.dp(pts, a, b, cfg.dpEpsNonKeyM, keep);
                keep[a] = true;
                keep[b] = true;
            }
        }
        if (arr.length == 1) {
            keep[arr[0]] = true;
        }

        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (keep[i]) result.add(i);
        }
        return result;
    }

    private boolean insideStopUnit(int a, int b, long[][] unitBounds) {
        for (long[] ub : unitBounds) {
            if (a >= ub[0] && b <= ub[1]) return true;
        }
        return false;
    }
}
