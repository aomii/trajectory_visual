package com.logicompress.experiment.encode;

import com.logicompress.experiment.model.TrackPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlockOffsetCodecRoundTripTest {

    @Test
    void reversesQuantizedFieldsAcrossSignsAndChunkBoundaries() {
        TrackPoint first = point(-0.000015, 0.000016, -1.234567, -0.000032, 31.000001, 1000);
        TrackPoint second = point(0.000016, -0.000017, 0.0, 0.000032, 32.000001, 1031);
        TrackPoint third = point(0.000032, 0.000031, 1.234567, -0.000001, 0.0, 1032);
        List<TrackPoint> original = List.of(first, second, third);
        String encoded = BlockOffsetCodec.encodePoints(original, 6);
        List<long[]> decoded = BlockOffsetCodec.decodePoints(encoded, 6);
        assertEquals(original.size(), decoded.size());
        for (int i = 0; i < original.size(); i++) {
            TrackPoint p = original.get(i);
            assertArrayEquals(new long[]{
                    BlockOffsetCodec.getRound(p.lat * 1_000_000),
                    BlockOffsetCodec.getRound(p.lon * 1_000_000),
                    BlockOffsetCodec.getRound(p.spdKph * 1_000_000),
                    BlockOffsetCodec.getRound(p.hgt * 1_000_000),
                    BlockOffsetCodec.getRound(p.aglHeading * 1_000_000),
                    p.gtmEpoch
            }, decoded.get(i));
        }
        // 每块前驱值独立重置；分别编码/解码仍得到相同整数序列。
        assertArrayEquals(decoded.get(2), BlockOffsetCodec.decodePoints(
                BlockOffsetCodec.encodePoints(List.of(third), 6), 6).get(0));
    }

    @Test
    void rejectsIncompleteInput() {
        assertThrows(IllegalArgumentException.class, () -> BlockOffsetCodec.decodePoints("?", 6));
        assertTrue(BlockOffsetCodec.decodePoints("", 6).isEmpty());
    }

    private static TrackPoint point(double lat, double lon, double speed, double height, double heading, long time) {
        TrackPoint p = new TrackPoint(lat, lon, time);
        p.spdKph = speed;
        p.hgt = height;
        p.aglHeading = heading;
        return p;
    }
}
