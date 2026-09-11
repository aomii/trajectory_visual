package com.logicompress.experiment.baseline;

import com.logicompress.experiment.model.TrackPoint;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Google Polyline 编码（无损层基线）：差分 + ZigZag + 5位分片 base64(+63 ASCII)。
 * 精度因子可配：默认 1e5（Google 标准），实验对比时用 1e8 与本文编码器同精度对齐。
 */
public class PolylineEncoder {

    /** 编码轨迹点序列的经纬度（lat, lon 各一个差分值），返回 ASCII 串 */
    public static String encode(List<TrackPoint> pts, double factor) {
        StringBuilder sb = new StringBuilder();
        long prevLat = 0, prevLon = 0;
        for (TrackPoint p : pts) {
            long lat = Math.round(p.lat * factor);
            long lon = Math.round(p.lon * factor);
            encodeValue(lat - prevLat, sb);
            prevLat = lat;
            encodeValue(lon - prevLon, sb);
            prevLon = lon;
        }
        return sb.toString();
    }

    public static int encodedBytes(List<TrackPoint> pts, double factor) {
        return encode(pts, factor).getBytes(StandardCharsets.US_ASCII).length;
    }

    private static void encodeValue(long v, StringBuilder sb) {
        v <<= 1;
        if (v < 0) v = ~v;
        while (v >= 0x20) {
            sb.append((char) (0x20 | (v & 0x1f)));
            v >>= 5;
        }
        sb.append((char) (v + 63));
    }
}
