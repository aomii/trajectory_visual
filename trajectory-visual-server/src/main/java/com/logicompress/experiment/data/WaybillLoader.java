package com.logicompress.experiment.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicompress.experiment.model.TrackPoint;
import com.logicompress.experiment.model.Waybill;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据加载：读 waybill_data（运单业务信息）与 track_data（轨迹），按 waybill_no 关联。
 * 字段清洗：字符串→数值；mlg 整列为空剔除；agl 记为航向角；gtm 按北京时间(UTC+8)转 epoch 秒。
 */
public class WaybillLoader {

    public static final ZoneOffset BEIJING = ZoneOffset.ofHours(8);
    private static final DateTimeFormatter GTM_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final ObjectMapper mapper = new ObjectMapper();

    private final String trackDir;
    private final String waybillDir;

    public WaybillLoader(String trackDir, String waybillDir) {
        this.trackDir = trackDir;
        this.waybillDir = waybillDir;
    }

    /** 加载全部运单（metadata + 轨迹），按 waybill_id 升序。返回列表含所有有效解析结果。 */
    public List<Waybill> loadAll() {
        Map<String, Waybill> byNo = new HashMap<>();
        Map<String, File> trackFiles = listFiles(trackDir, "track_");
        Map<String, File> waybillFiles = listFiles(waybillDir, "waybill_");

        // 先解析运单业务信息
        for (Map.Entry<String, File> e : waybillFiles.entrySet()) {
            try {
                Waybill w = parseWaybillMeta(e.getValue());
                if (w != null) {
                    w.waybillFile = e.getValue().getPath();
                    byNo.put(w.waybillNo, w);
                }
            } catch (Exception ex) {
                System.err.println("[warn] 解析运单失败 " + e.getValue().getName() + ": " + ex);
            }
        }
        // 再关联轨迹
        for (Map.Entry<String, File> e : trackFiles.entrySet()) {
            Waybill w = byNo.get(e.getKey());
            if (w == null) continue;
            try {
                w.rawPoints = parseTrack(e.getValue());
                w.trackFile = e.getValue().getPath();
            } catch (Exception ex) {
                System.err.println("[warn] 解析轨迹失败 " + e.getValue().getName() + ": " + ex);
            }
        }
        List<Waybill> result = new ArrayList<>(byNo.values());
        result.sort((a, b) -> Long.compare(a.waybillId, b.waybillId));
        return result;
    }

    private Map<String, File> listFiles(String dir, String prefix) {
        Map<String, File> m = new HashMap<>();
        File d = new File(dir);
        File[] files = d.listFiles((f) -> f.isFile() && f.getName().startsWith(prefix) && f.getName().endsWith(".json"));
        if (files == null) return m;
        for (File f : files) {
            String name = f.getName();
            String no = name.substring(prefix.length(), name.length() - ".json".length());
            int us = no.lastIndexOf('_');
            if (us > 0) no = no.substring(0, us); // 去掉尾部 _waybillId
            m.put(no, f);
        }
        return m;
    }

    /** 解析运单业务信息（含收发货地址圆心、装卸货时间、车辆/货物属性） */
    private Waybill parseWaybillMeta(File f) throws Exception {
        JsonNode root = mapper.readTree(f);
        Waybill w = new Waybill();
        w.waybillId = root.path("waybill_id").asLong();
        w.waybillNo = root.path("waybill_no").asText("");
        if (w.waybillNo.isEmpty()) return null;

        JsonNode li = root.path("waybill_list_item");
        JsonNode gpsPoint = root.path("gps_data").path("data").path("point");

        String sendLal = firstText(li.path("sendAddrLal"), gpsPoint.path("sendAddrLal"));
        String recvLal = firstText(li.path("receiveAddrLal"), gpsPoint.path("receiveAddrLal"));
        double[] send = parseLal(sendLal);
        double[] recv = parseLal(recvLal);
        w.sendLon = send[0]; w.sendLat = send[1];
        w.receiveLon = recv[0]; w.receiveLat = recv[1];

        w.sendAddrName = li.path("sendAddrName").asText("");
        w.receiveAddrName = li.path("receiveAddrName").asText("");
        w.plateNo = li.path("plateNo").asText("");
        w.materialName = li.path("materialName").asText("");
        w.vehicleId = li.path("vehicleId").asLong();
        w.loadEpoch = parseEpoch(li.path("loadTime").asText(""));
        w.unloadEpoch = parseEpoch(li.path("unloadTime").asText(""));
        return w;
    }

    /** 解析轨迹点列表 */
    private List<TrackPoint> parseTrack(File f) throws Exception {
        JsonNode root = mapper.readTree(f);
        JsonNode arr = root.path("locations");
        if (arr.isMissingNode() || !arr.isArray()) return new ArrayList<>();
        List<TrackPoint> pts = new ArrayList<>(arr.size());
        int idx = 0;
        for (JsonNode p : arr) {
            TrackPoint tp = new TrackPoint();
            tp.srcIdx = idx++;
            tp.lat = p.path("lat").asDouble();
            tp.lon = p.path("lon").asDouble();
            tp.spdKph = p.path("spd").asDouble();
            tp.hgt = p.path("hgt").asDouble();
            tp.aglHeading = p.path("agl").asDouble();
            String gtm = p.path("gtm").asText("");
            tp.gtmRaw = gtm;
            tp.gtmEpoch = parseEpoch(gtm);
            pts.add(tp);
        }
        return pts;
    }

    private static String firstText(JsonNode a, JsonNode b) {
        String s = a.asText("");
        if (s.isEmpty()) s = b.asText("");
        return s;
    }

    /** 解析 "lon,lat" 字符串 */
    private static double[] parseLal(String lal) {
        try {
            if (lal == null || lal.isEmpty()) return new double[]{0, 0};
            String[] parts = lal.split(",");
            return new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
        } catch (Exception e) {
            return new double[]{0, 0};
        }
    }

    /** gtm "yyyy-MM-dd HH:mm:ss" → 北京时间 epoch 秒；空串返回 0 */
    public static long parseEpoch(String s) {
        if (s == null || s.trim().isEmpty()) return 0L;
        try {
            return LocalDateTime.parse(s.trim(), GTM_FMT).toEpochSecond(BEIJING);
        } catch (Exception e) {
            return 0L;
        }
    }
}
