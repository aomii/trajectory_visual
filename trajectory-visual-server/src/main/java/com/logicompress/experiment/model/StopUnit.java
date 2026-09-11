package com.logicompress.experiment.model;

/**
 * 停留单元（论文定义 3-2）：S = (p_start, p_end, Δt, ℓ)。
 * startIdx/endIdx 指向清洗后轨迹点列表的区间（含两端）。
 */
public class StopUnit {
    public int startIdx;
    public int endIdx;
    public long tStart;      // epoch 秒
    public long tEnd;        // epoch 秒
    public double durationS;
    /** 语义类别：LOAD / UNLOAD / GAS / REST / TRAFFIC */
    public String label;

    /** 是否来自 L1 围栏强匹配 */
    public boolean fromFence;
    /** 围栏侧：SEND / RECEIVE（fromFence=true 时有效） */
    public String fenceSide;

    public StopUnit() {
    }

    public double durationS() {
        return (double) (tEnd - tStart);
    }

    @Override
    public String toString() {
        return String.format("StopUnit[%s %s] idx[%d,%d] t=%d~%d dur=%.0fs",
                label, fromFence ? "fence:" + fenceSide : "cluster", startIdx, endIdx, tStart, tEnd, durationS());
    }
}
