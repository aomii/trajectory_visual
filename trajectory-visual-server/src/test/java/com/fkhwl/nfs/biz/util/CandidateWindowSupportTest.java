package com.fkhwl.nfs.biz.util;

import com.fkhwl.nfs.biz.entity.dto.visual.WindowSearchVO;
import com.fkhwl.nfs.biz.entity.mongo.TrajectoryChunkDoc;
import com.logicompress.experiment.model.TrackPoint;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CandidateWindowSupportTest {
    private static final long EIGHT = 8 * 3600, TWELVE = 12 * 3600;

    private static TrajectoryChunkDoc.MongoStopUnit stop() {
        TrajectoryChunkDoc.MongoStopUnit s = new TrajectoryChunkDoc.MongoStopUnit();
        s.setSeq(3);
        s.setLabel("REST");
        s.setStartTime(EIGHT);
        s.setEndTime(TWELVE);
        s.setStartLat(30.0000001);
        s.setStartLon(110.0);
        s.setEndLat(30.0);
        s.setEndLon(110.0000001);
        s.setRetainedBoth(true);
        return s;
    }

    @Test
    void overlapWorksWithoutWindowPointsAndHonorsInclusiveBoundaries() {
        TrajectoryChunkDoc.MongoStopUnit s = stop();
        List<WindowSearchVO.WindowStopInterval> middle = CandidateWindowSupport.intersect(
                Collections.singletonList(s), 9 * 3600, 10 * 3600, Collections.emptyList(), 6);
        assertEquals(1, middle.size());
        assertEquals(EIGHT, middle.get(0).observedStartEpoch);
        assertEquals(9 * 3600, middle.get(0).overlapStartEpoch);
        assertEquals(10 * 3600, middle.get(0).overlapEndEpoch);
        assertFalse(middle.get(0).retainedBothVerified);
        assertEquals(1, CandidateWindowSupport.intersect(Collections.singletonList(s), TWELVE,
                TWELVE + 1, Collections.emptyList(), 6).size());
        assertTrue(CandidateWindowSupport.intersect(Collections.singletonList(s), TWELVE + 1,
                TWELVE + 2, Collections.emptyList(), 6).isEmpty());
        assertEquals(1, CandidateWindowSupport.intersect(Collections.singletonList(s), 7 * 3600,
                9 * 3600, Collections.emptyList(), 6).size());
    }

    @Test
    void retainedBothIsRecomputedFromDecodedPointsAcrossChunks() {
        TrajectoryChunkDoc.MongoStopUnit s = stop();
        TrackPoint first = new TrackPoint(30.0, 110.0, EIGHT);
        TrackPoint last = new TrackPoint(30.0, 110.0, TWELVE);
        assertTrue(CandidateWindowSupport.retainedBoundary(Arrays.asList(first, last), s, 6));
        assertFalse(CandidateWindowSupport.retainedBoundary(Collections.singletonList(first), s, 6));
        s.setRetainedBoth(false);
        assertTrue(CandidateWindowSupport.retainedBoundary(Arrays.asList(first, last), s, 6));
    }
}
