package com.fkhwl.nfs.biz.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 时间格式工具：gtm 采用北京时间(UTC+8)，格式 yyyy-MM-dd HH:mm:ss。
 * 与数据源、论文口径一致。
 */
public final class TimeFmt {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TimeFmt() {
    }

    /** epoch 秒 → 北京字符串 */
    public static String secToStr(long epochSec) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSec), ZONE).format(FMT);
    }

    /** epoch 毫秒 → 北京字符串 */
    public static String msToStr(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZONE).format(FMT);
    }

    /** "yyyy-MM-dd HH:mm:ss"（北京） → epoch 秒 */
    public static long strToSec(String text) {
        return LocalDateTime.parse(text, FMT).atZone(ZONE).toEpochSecond();
    }
}
