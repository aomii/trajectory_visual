package com.fkhwl.nfs.biz.util;

import com.logicompress.experiment.model.TrackPoint;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** 按解码后的完整数值字段与原有顺序比较窗口点序列。 */
public final class WindowResultVerifier {
    private WindowResultVerifier() { }

    public static boolean sameContent(List<TrackPoint> a, List<TrackPoint> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            TrackPoint x = a.get(i), y = b.get(i);
            if (x.gtmEpoch != y.gtmEpoch
                    || Double.doubleToLongBits(x.lat) != Double.doubleToLongBits(y.lat)
                    || Double.doubleToLongBits(x.lon) != Double.doubleToLongBits(y.lon)
                    || Double.doubleToLongBits(x.spdKph) != Double.doubleToLongBits(y.spdKph)
                    || Double.doubleToLongBits(x.hgt) != Double.doubleToLongBits(y.hgt)
                    || Double.doubleToLongBits(x.aglHeading) != Double.doubleToLongBits(y.aglHeading)) return false;
        }
        return true;
    }

    /** SHA-256 of big-endian (epoch seconds, lat, lon, speed, altitude, heading) records. */
    public static String sha256(List<TrackPoint> points) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer record = ByteBuffer.allocate(48);
            for (TrackPoint p : points) {
                record.clear();
                record.putLong(p.gtmEpoch).putLong(Double.doubleToLongBits(p.lat))
                        .putLong(Double.doubleToLongBits(p.lon)).putLong(Double.doubleToLongBits(p.spdKph))
                        .putLong(Double.doubleToLongBits(p.hgt)).putLong(Double.doubleToLongBits(p.aglHeading));
                digest.update(record.array());
            }
            byte[] bytes = digest.digest();
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
