package com.fkhwl.nfs.biz.util;

import com.logicompress.experiment.model.TrackPoint;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WindowResultVerifierTest {
    private static TrackPoint point(double lat, long time) {
        TrackPoint p = new TrackPoint(lat, 116.0, time);
        p.spdKph = 2;
        p.hgt = 30;
        p.aglHeading = 90;
        return p;
    }

    @Test
    void sameCountDifferentCoordinateFails() {
        List<TrackPoint> a = Arrays.asList(point(39.0, 1), point(39.1, 2));
        List<TrackPoint> b = Arrays.asList(point(39.0, 1), point(39.2, 2));
        assertEquals(a.size(), b.size());
        assertFalse(WindowResultVerifier.sameContent(a, b));
        assertNotEquals(WindowResultVerifier.sha256(a), WindowResultVerifier.sha256(b));
    }

    @Test
    void reorderingFailsAndIdenticalPasses() {
        TrackPoint p1 = point(39.0, 1), p2 = point(39.1, 2);
        List<TrackPoint> a = Arrays.asList(p1, p2);
        List<TrackPoint> b = Arrays.asList(p2, p1);
        assertFalse(WindowResultVerifier.sameContent(a, b));
        assertNotEquals(WindowResultVerifier.sha256(a), WindowResultVerifier.sha256(b));
        assertTrue(WindowResultVerifier.sameContent(a, Arrays.asList(p1, p2)));
    }
}
