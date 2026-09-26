package com.fkhwl.nfs.biz.util;

import com.fkhwl.nfs.biz.entity.dto.visual.WindowSearchVO;
import com.fkhwl.nfs.biz.entity.mongo.TrajectoryChunkDoc;
import com.logicompress.experiment.model.TrackPoint;

import java.util.ArrayList;
import java.util.List;

/** 从持久化候选元数据恢复与闭区间时间窗相交的候选，不制造缺失观测点。 */
public final class CandidateWindowSupport {
    private CandidateWindowSupport() { }

    public static List<WindowSearchVO.WindowStopInterval> intersect(
            List<TrajectoryChunkDoc.MongoStopUnit> stops, long t1, long t2,
            List<TrackPoint> retainedPoints, int precision) {
        List<WindowSearchVO.WindowStopInterval> out = new ArrayList<>();
        if (stops == null) return out;
        for (TrajectoryChunkDoc.MongoStopUnit s : stops) {
            if (s.getStartTime() == null || s.getEndTime() == null
                    || s.getStartTime() > s.getEndTime()
                    || s.getStartTime() > t2 || s.getEndTime() < t1) continue;
            WindowSearchVO.WindowStopInterval interval = new WindowSearchVO.WindowStopInterval();
            interval.seq = s.getSeq() == null ? 0 : s.getSeq();
            interval.label = s.getLabel();
            interval.observedStartEpoch = s.getStartTime();
            interval.observedEndEpoch = s.getEndTime();
            interval.overlapStartEpoch = Math.max(t1, s.getStartTime());
            interval.overlapEndEpoch = Math.min(t2, s.getEndTime());
            interval.retainedBothVerified = retainedBoundary(retainedPoints, s, precision);
            out.add(interval);
        }
        return out;
    }

    /** 用解码点的时间与量化坐标复核两端，不能依赖预填 retainedBoth。 */
    public static boolean retainedBoundary(List<TrackPoint> points,
                                           TrajectoryChunkDoc.MongoStopUnit s, int precision) {
        if (s.getStartTime() == null || s.getEndTime() == null
                || s.getStartLat() == null || s.getStartLon() == null
                || s.getEndLat() == null || s.getEndLon() == null) return false;
        double scale = Math.pow(10, precision);
        boolean start = false, end = false;
        for (TrackPoint p : points) {
            if (p.gtmEpoch == s.getStartTime()
                    && Math.round(p.lat * scale) == Math.round(s.getStartLat() * scale)
                    && Math.round(p.lon * scale) == Math.round(s.getStartLon() * scale)) start = true;
            if (p.gtmEpoch == s.getEndTime()
                    && Math.round(p.lat * scale) == Math.round(s.getEndLat() * scale)
                    && Math.round(p.lon * scale) == Math.round(s.getEndLon() * scale)) end = true;
            if (start && end) return true;
        }
        return false;
    }
}
