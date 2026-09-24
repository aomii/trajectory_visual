package com.logicompress.experiment.eval;

import com.logicompress.experiment.model.StopUnit;
import com.logicompress.experiment.model.TrackPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetricsBoundarySpanTest {

    private static StopUnit stop(int start, int end, long tStart, long tEnd) {
        StopUnit unit = new StopUnit();
        unit.startIdx = start;
        unit.endIdx = end;
        unit.tStart = tStart;
        unit.tEnd = tEnd;
        return unit;
    }

    @Test
    void weightedBoundarySpanDoesNotReuseLegacyDwellFidelity() {
        long[] times = {0, 10, 20, 60, 80, 120};
        List<TrackPoint> points = new ArrayList<>();
        for (long t : times) points.add(new TrackPoint(0, 0, t));
        List<StopUnit> stops = List.of(stop(0, 1, 0, 10), stop(2, 5, 20, 120));
        boolean[] mask = Metrics.indexMask(List.of(0, 1, 3, 4), points.size());

        Metrics.BoundarySpan span = Metrics.boundarySpanRetention(stops, mask);
        assertEquals(2, span.eligibleUnits);
        assertEquals(10.0, span.completeSeconds);
        assertEquals(110.0, span.eligibleSeconds);
        assertEquals(10.0 / 110.0, span.rate(), 1e-12);
        assertEquals(0.6, Metrics.dwellFidelity(points, stops, mask), 1e-12);
    }

    @Test
    void emptyAndInvalidStopsAreNotPerfectRetention() {
        assertNull(Metrics.boundarySpanRetention(List.of(), new boolean[1]).rate());
        assertNull(Metrics.boundarySpanRetention(List.of(stop(0, 0, 2, 2)), new boolean[]{true}).rate());
    }

    @Test
    void bothBoundariesAreRequired() {
        StopUnit unit = stop(0, 2, 0, 100);
        assertEquals(0.0, Metrics.boundarySpanRetention(List.of(unit), new boolean[]{true, true, false}).rate());
        assertEquals(1.0, Metrics.boundarySpanRetention(List.of(unit), new boolean[]{true, false, true}).rate());
    }
}
