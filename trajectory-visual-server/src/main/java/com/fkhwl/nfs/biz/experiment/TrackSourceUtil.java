package com.fkhwl.nfs.biz.experiment;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicompress.experiment.model.TrackPoint;
import com.logicompress.experiment.model.Waybill;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 全量轨迹源文件（7709 在线库 source_data_full）的扫描与解析。
 *
 * <p>文件 JSON 结构（实测，source_data_full 版经纬度为字符串）：
 * <pre>
 * { "waybillId":4091, "vehicleId":816, "fromType":1, "pointCount":121,
 *   "locations":[ {"agl":181.0,"gtm":"2025-04-11 15:12:41","gtmMs":1744355561000,
 *                  "lat":"23.96008527980585","lon":"103.18970742182670",
 *                  "spd":0.0,"hgt":1112.0,"mlg":0.0}, ... ] }
 * </pre>
 * 坐标 = GCJ-02（源文件入湖前已由 WGS84 转 GCJ-02，展示层不再转换）；gtm 北京时间；
 * loc 下标即 srcIdx。同名目录 source_data 的 lat/lon 为数字，
 * 本解析对 number/string 均兼容（统一 double 化）。
 */
public final class TrackSourceUtil {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TrackSourceUtil() {
    }

    /** 列出目录下全部轨迹文件（按文件名自然序，便于 limit/offset 稳定切片） */
    public static List<Path> listTrackFiles(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "track_*.json")) {
            for (Path p : ds) out.add(p);
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    /** 从文件名 track_{waybillNo}_{waybillId}.json 解析运单号（找不到返回文件名） */
    public static String parseWaybillNo(Path file) {
        String name = file.getFileName().toString();
        String base = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
        if (base.startsWith("track_")) base = base.substring("track_".length());
        int last = base.lastIndexOf('_');
        return last > 0 ? base.substring(0, last) : base;
    }

    /** 从文件名解析 waybillId（末尾 _id），失败返回 -1 */
    public static long parseWaybillId(Path file) {
        String name = file.getFileName().toString();
        String base = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
        int last = base.lastIndexOf('_');
        if (last < 0) return -1;
        try {
            return Long.parseLong(base.substring(last + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 解析单个轨迹文件为算法模型 Waybill（rawPoints 时间升序）。
     *
     * @param file 源文件绝对路径
     * @return Waybill（vehicleId/fromType 取自 JSON；无业务围栏字段）
     */
    public static Waybill parseFile(Path file) throws IOException {
        JsonNode root = JSON.readTree(file.toFile());
        Waybill w = new Waybill();
        w.waybillNo = parseWaybillNo(file);
        w.waybillId = root.path("waybillId").asLong(parseWaybillId(file));
        w.vehicleId = root.path("vehicleId").asLong(0);
        w.trackFile = file.toString();
        w.sendAddrName = "";
        w.receiveAddrName = "";
        w.plateNo = "";

        JsonNode locs = root.path("locations");
        List<TrackPoint> pts = new ArrayList<>(locs.size());
        int i = 0;
        for (JsonNode n : locs) {
            TrackPoint p = new TrackPoint();
            p.srcIdx = i;
            p.lat = nodeDouble(n.path("lat"));
            p.lon = nodeDouble(n.path("lon"));
            p.spdKph = n.path("spd").asDouble(0);
            p.hgt = n.path("hgt").asDouble(0);
            p.aglHeading = n.path("agl").asDouble(0);
            p.gtmEpoch = n.path("gtmMs").asLong(0) / 1000L;   // epoch 毫秒 → 秒（北京时间）
            JsonNode g = n.path("gtm");
            p.gtmRaw = g.isTextual() ? g.asText() : "";
            pts.add(p);
            i++;
        }
        // 时间升序（数据一般已有序；双保险，同时保持稳定）
        pts.sort(Comparator.comparingLong(t -> t.gtmEpoch));
        w.rawPoints = pts;
        return w;
    }

    /** 兼容 number/string 的经纬度取值 */
    private static double nodeDouble(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return 0;
        if (n.isNumber()) return n.asDouble();
        try {
            return Double.parseDouble(n.asText().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 查找某个 waybillId 对应的源文件（优先用已存 meta 的 sourceFile；否则目录扫描） */
    public static Path resolveFile(Path dir, String sourceFile, long waybillId) throws IOException {
        if (sourceFile != null && !sourceFile.isEmpty()) {
            Path p = dir.resolve(sourceFile);
            if (Files.exists(p)) return p;
        }
        for (Path p : listTrackFiles(dir)) {
            if (parseWaybillId(p) == waybillId) return p;
        }
        return null;
    }

    /** 元数据：{waybillId, vehicleId, fromType, pointCount, firstGtmMs, lastGtmMs} */
    public static class Meta {
        public long waybillId = -1;
        public long vehicleId;
        public int fromType;
        public int pointCount;
        public long firstGtmMs;
        public long lastGtmMs;
    }

    /**
     * 轻量解析文件元数据（只读顶层标量与各点 gtmMs 的首/末值，不构造轨迹点）。
     * 用于 trajectory_waybill 导入，避免把 7709 个文件的全部点载入内存。
     */
    public static Meta parseMeta(Path file) throws IOException {
        Meta m = new Meta();
        m.waybillId = parseWaybillId(file); // 文件名兜底
        boolean firstSet = false;
        try (JsonParser p = JSON.getFactory().createParser(file.toFile())) {
            String field = null;
            while (p.nextToken() != null) {
                switch (p.currentToken()) {
                    case FIELD_NAME:
                        field = p.getCurrentName();
                        break;
                    case VALUE_NUMBER_INT:
                        if (field == null) break;
                        switch (field) {
                            case "waybillId": m.waybillId = p.getLongValue(); break;
                            case "vehicleId": m.vehicleId = p.getLongValue(); break;
                            case "fromType": m.fromType = p.getIntValue(); break;
                            case "pointCount": m.pointCount = p.getIntValue(); break;
                            case "gtmMs":
                                if (!firstSet) { m.firstGtmMs = p.getLongValue(); firstSet = true; }
                                m.lastGtmMs = p.getLongValue();
                                break;
                            default: break;
                        }
                        break;
                    default:
                        break;
                }
            }
        }
        if (m.pointCount <= 0) m.pointCount = 0;
        return m;
    }

    /** epoch 毫秒(北京) → LocalDateTime（与 gtm 字符串一致） */
    public static java.time.LocalDateTime epochMsToLocal(long ms) {
        if (ms <= 0) return null;
        return java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(ms), java.time.ZoneId.of("Asia/Shanghai"));
    }
}
