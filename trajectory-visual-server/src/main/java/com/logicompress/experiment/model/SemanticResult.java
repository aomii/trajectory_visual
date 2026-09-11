package com.logicompress.experiment.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 语义识别结果：停留单元集合 + 关键锚点下标（指向清洗后轨迹点）。
 * 关键锚点 = 所有停留单元的 start/end + 轨迹起止点（论文 3.6.2）。
 */
public class SemanticResult {
    public List<StopUnit> stops = new ArrayList<>();
    /** 关键锚点在清洗后轨迹点列表中的下标 */
    public Set<Integer> anchors = new HashSet<>();
    /** 每个点所属簇 id（-1=噪声/未聚类），L2 聚类结果，供诊断 */
    public int[] clusterId;
    /** 围栏命中点下标集合（L1 输出，供诊断与 L2 种子） */
    public Set<Integer> fenceHitIdx = new HashSet<>();

    /** 补充轨迹起止点为锚点 */
    public void addTrackEndpoints(int lastIdx) {
        anchors.add(0);
        if (lastIdx > 0) anchors.add(lastIdx);
    }
}
