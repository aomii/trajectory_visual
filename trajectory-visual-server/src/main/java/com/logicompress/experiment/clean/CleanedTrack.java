package com.logicompress.experiment.clean;

import com.logicompress.experiment.model.TrackPoint;

import java.util.List;

/**
 * 清洗后的轨迹：漂移点已剔除，并按断线（dt>breakGapS）切分为连续子段。
 */
public class CleanedTrack {
    /** 清洗后轨迹点（时间升序，无漂移点） */
    public final List<TrackPoint> points;
    /** 连续子段 [startIdx, endIdx]（含端点），按断线切分 */
    public final List<int[]> segments;
    /** 剔除的漂移点数 */
    public final int removedDrift;

    public CleanedTrack(List<TrackPoint> points, List<int[]> segments, int removedDrift) {
        this.points = points;
        this.segments = segments;
        this.removedDrift = removedDrift;
    }
}
