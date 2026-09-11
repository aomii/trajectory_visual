package com.logicompress.experiment.semantic;

import com.logicompress.experiment.config.ExperimentConfig;
import com.logicompress.experiment.model.StopUnit;
import com.logicompress.experiment.model.TrackPoint;

import java.util.List;

/**
 * L3 运动学阈值精修（claude_09 3.5）：
 * 对停留单元的边界用速度阈值精修——低速度段的前沿/后沿并入停留区间，
 * 更准确地刻画"进入/离开停留"的时刻。与 L2 交叉验证策略由 SemanticAnalyzer 调用。
 */
public class KinematicRefine {

    /** 边界前后扩展的最大时间间隔（秒），防止把断线后的点也并入 */
    private static final long MAX_EXTEND_GAP_S = 300;

    /**
     * L3 运动学阈值精修（论文 3.5）——L3 核心逻辑：
     * 对每个停留单元，向前/向后扩展边界——只要前（后）相邻点速度 < vThKph（≈11km/h）
     * 且与当前边界点的时间间隔 ≤ MAX_EXTEND_GAP_S(5min)，就把该低速点并入停留区间，
     * 从而把"进入/离开停留时的低速滑行段"计入停留，修正起止时刻。
     * 间隔上限防止断线后（时间跳变）的点被误并入。
     *
     * @param pts   清洗后轨迹点（按时间升序）
     * @param units 待精修的停留单元（原地修改 startIdx/endIdx/tStart/tEnd）
     */
    public static void refine(List<TrackPoint> pts, List<StopUnit> units, ExperimentConfig cfg) {
        int n = pts.size();
        for (StopUnit u : units) {
            int s = u.startIdx;
            int e = u.endIdx;
            // 向前扩展：前一点低速 且 与当前边界的间隔在阈值内
            while (s > 0 && pts.get(s - 1).spdKph < cfg.vThKph
                    && (pts.get(s).gtmEpoch - pts.get(s - 1).gtmEpoch) <= MAX_EXTEND_GAP_S) {
                s--;
            }
            // 向后扩展
            while (e < n - 1 && pts.get(e + 1).spdKph < cfg.vThKph
                    && (pts.get(e + 1).gtmEpoch - pts.get(e).gtmEpoch) <= MAX_EXTEND_GAP_S) {
                e++;
            }
            u.startIdx = s;
            u.endIdx = e;
            u.tStart = pts.get(s).gtmEpoch;
            u.tEnd = pts.get(e).gtmEpoch;
        }
    }
}
