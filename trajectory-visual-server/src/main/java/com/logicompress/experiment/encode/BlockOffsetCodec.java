package com.logicompress.experiment.encode;

import com.logicompress.experiment.model.TrackPoint;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 分块偏移量 5 位变长编码——公司 TrjCompressor 的算法复刻（无反射/Lombok）：
 * 量化(×10^precision，round-half-away 对齐 getRound) → 差分 → ZigZag → 5 位分片(+63 ASCII) → DEFLATE。
 * 每块独立编码（块首绝对坐标=prev 从 0 开始），可独立解压。
 * 编码字段顺序固定：lat, lon, spd, hgt, agl（Double，量化）+ gtm（Long，epoch 秒）。
 * 注：TrjCompressor.read 解码含 result&gt;&gt;=1（论文草稿漏写），以源码为准。
 */
public class BlockOffsetCodec {

    public static final int FIELD_COUNT = 6;
    public static final int F_LAT = 0, F_LON = 1, F_SPD = 2, F_HGT = 3, F_AGL = 4, F_GTM = 5;

    /** 对一块轨迹点编码，返回 ASCII 串（未 zip） */
    public static String encodePoints(List<TrackPoint> pts, int precision) {
        double factor = Math.pow(10, precision);
        StringBuilder out = new StringBuilder();
        long[] prev = new long[FIELD_COUNT];
        for (TrackPoint p : pts) {
            long[] curr = toQuantized(p, factor);
            for (int f = 0; f < FIELD_COUNT; f++) {
                long offset = curr[f] - prev[f];
                prev[f] = curr[f];
                // ZigZag（对齐 TrjCompressor.write）
                offset <<= 1;
                if (offset < 0) offset = ~offset;
                // 5 位分片 + 63 ASCII
                while (offset >= 0x20) {
                    out.append((char) ((0x20 | (offset & 0x1f)) + 63));
                    offset >>= 5;
                }
                out.append((char) (offset + 63));
            }
        }
        return out.toString();
    }

    /** 解码 ASCII 串为逐点量化值 long[FIELD_COUNT] */
    public static List<long[]> decodePoints(String ascii, int precision) {
        List<long[]> result = new ArrayList<>();
        long[] vals = new long[FIELD_COUNT];
        int n = ascii.length();
        int i = 0;
        while (i < n) {
            long[] point = new long[FIELD_COUNT];
            for (int f = 0; f < FIELD_COUNT; f++) {
                long b = 0x20, res = 0, shift = 0, comp = 0;
                while (b >= 0x20 && i < n) {
                    b = ascii.charAt(i) - 63;
                    i++;
                    res |= (b & 0x1f) << shift;
                    shift += 5;
                    comp = res & 1;
                }
                res >>= 1;
                if (comp == 1) res = ~res;
                vals[f] += res;
                point[f] = vals[f];
            }
            result.add(point);
        }
        return result;
    }

    /** 解码为 TrackPoint 列表 */
    public static List<TrackPoint> decodeToPoints(String ascii, int precision) {
        double factor = Math.pow(10, precision);
        List<long[]> q = decodePoints(ascii, precision);
        List<TrackPoint> pts = new ArrayList<>(q.size());
        for (long[] v : q) {
            TrackPoint p = new TrackPoint(v[F_LAT] / factor, v[F_LON] / factor, v[F_GTM]);
            p.spdKph = v[F_SPD] / factor;
            p.hgt = v[F_HGT] / factor;
            p.aglHeading = v[F_AGL] / factor;
            pts.add(p);
        }
        return pts;
    }

    static long[] toQuantized(TrackPoint p, double factor) {
        return new long[]{
                getRound(p.lat * factor),
                getRound(p.lon * factor),
                getRound(p.spdKph * factor),
                getRound(p.hgt * factor),
                getRound(p.aglHeading * factor),
                p.gtmEpoch
        };
    }

    /** 对齐 TrjCompressor.getRound：先对绝对值四舍五入，再取原符号 */
    static long getRound(double x) {
        return (long) Math.copySign(Math.round(Math.abs(x)), x);
    }

    public static byte[] deflate(String ascii, int level) {
        Deflater d = new Deflater(level);
        d.setInput(ascii.getBytes(StandardCharsets.US_ASCII));
        d.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(ascii.length() / 2 + 64);
        byte[] buf = new byte[8192];
        while (!d.finished()) {
            int len = d.deflate(buf);
            bos.write(buf, 0, len);
        }
        d.end();
        return bos.toByteArray();
    }

    public static String inflate(byte[] data) {
        Inflater inf = new Inflater();
        inf.setInput(data);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length * 3);
        byte[] buf = new byte[8192];
        try {
            while (!inf.finished()) {
                int len = inf.inflate(buf);
                if (len == 0) break;
                bos.write(buf, 0, len);
            }
        } catch (DataFormatException e) {
            throw new RuntimeException("解压失败", e);
        } finally {
            inf.end();
        }
        return new String(bos.toByteArray(), StandardCharsets.US_ASCII);
    }
}
