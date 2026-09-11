package com.fkhwl.nfs.biz.service.visual.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fkhwl.nfs.biz.entity.form.visual.WaybillQueryForm;
import com.fkhwl.nfs.biz.entity.po.TrajectoryWaybill;
import com.fkhwl.nfs.biz.experiment.TrackSourceUtil;
import com.fkhwl.nfs.biz.mapper.TrajectoryWaybillMapper;
import com.fkhwl.nfs.biz.service.visual.VisualWaybillService;
import com.fkhwl.nfs.config.VisualProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运单元数据服务实现。
 *
 * <p>导入逻辑：扫描 trajectory.source.full-data-dir 下 track_*.json，用 TrackSourceUtil.parseMeta
 * 轻量读取顶层元数据与首末点时间，按 waybill_id upsert。原始点数组始终不入库；
 * 轨迹时间范围(gmt_start/gmt_end)用于"按时间范围检索运单"。
 *
 * <p>轨迹文件本身没有车牌/货物/收发货时间地点字段，导入时按 waybill_id 从同库业务表
 * {@code waybill} 一次性回填（车牌、货物、收发地点名称与地址、收发坐标 lal、装货/卸货时间）。
 */
@Service
public class VisualWaybillServiceImpl implements VisualWaybillService {

    private final TrajectoryWaybillMapper mapper;
    private final VisualProperties props;
    /** 业务表 waybill（老项目 ml_network_freight），回填车牌/货物/收发货元数据 */
    private final JdbcTemplate jdbc;

    public VisualWaybillServiceImpl(TrajectoryWaybillMapper mapper, VisualProperties props, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.props = props;
        this.jdbc = jdbc;
    }

    /** 业务表 waybill 中与运单展示相关的字段快照 */
    private static class BizWaybill {
        String plateNo = "";
        String materialName = "";
        String sendAddrName = "", sendAddrArea = "", sendAddrDetail = "";
        String receiveAddrName = "", receiveAddrArea = "", receiveAddrDetail = "";
        Double sendLon, sendLat, receiveLon, receiveLat;
        LocalDateTime loadTime, unloadTime, orderTime;
    }

    /**
     * 按 waybill_id 从业务表 waybill 取回填数据；不存在或已删除返回 null。
     * 一次查询取全部字段（原来只查车牌，现在补齐收发货元数据，避免多次往返）。
     */
    private BizWaybill bizOf(long waybillId) {
        String sql = "SELECT plate_no, material_name,"
                + " send_addr_name, send_addr_area, send_addr_detail, send_addr_lal,"
                + " receive_addr_name, receive_addr_area, receive_addr_detail, receive_addr_lal,"
                + " load_time, unload_time, create_time"
                + " FROM waybill WHERE id = ? AND deleted = 0 LIMIT 1";
        try {
            return jdbc.query(sql, rs -> {
                if (!rs.next()) return null;
                BizWaybill b = new BizWaybill();
                b.plateNo = nz(rs.getString("plate_no"));
                b.materialName = nz(rs.getString("material_name"));
                b.sendAddrName = nz(rs.getString("send_addr_name"));
                b.sendAddrArea = nz(rs.getString("send_addr_area"));
                b.sendAddrDetail = nz(rs.getString("send_addr_detail"));
                b.receiveAddrName = nz(rs.getString("receive_addr_name"));
                b.receiveAddrArea = nz(rs.getString("receive_addr_area"));
                b.receiveAddrDetail = nz(rs.getString("receive_addr_detail"));
                Double[] s = parseLal(rs.getString("send_addr_lal"));
                Double[] r = parseLal(rs.getString("receive_addr_lal"));
                b.sendLon = s[0]; b.sendLat = s[1];
                b.receiveLon = r[0]; b.receiveLat = r[1];
                b.loadTime = toLocal(rs.getTimestamp("load_time"));
                b.unloadTime = toLocal(rs.getTimestamp("unload_time"));
                b.orderTime = toLocal(rs.getTimestamp("create_time"));
                return b;
            }, waybillId);
        } catch (Exception e) {
            return null;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 业务库地址 lal 为 "lon,lat" 字符串；解析失败返回 {null,null} */
    private static Double[] parseLal(String lal) {
        if (lal == null || lal.trim().isEmpty()) return new Double[]{null, null};
        String[] parts = lal.split(",");
        if (parts.length != 2) return new Double[]{null, null};
        try {
            return new Double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
        } catch (NumberFormatException e) {
            return new Double[]{null, null};
        }
    }

    private static LocalDateTime toLocal(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    @Override
    public Map<String, Object> importFromSource(Integer limitOverride) {
        Path dir = Paths.get(props.getSource().getFullDataDir());
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("源目录不存在: " + dir);
        }
        int limit = limitOverride != null && limitOverride > 0
                ? limitOverride : props.getSource().getMaxFilesPerJob();

        List<Path> files;
        try {
            files = TrackSourceUtil.listTrackFiles(dir);
        } catch (Exception e) {
            throw new IllegalStateException("源目录扫描失败: " + dir, e);
        }
        if (limit > 0 && files.size() > limit) {
            files = files.subList(0, limit);
        }

        int imported = 0, updated = 0, failed = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Path f : files) {
            try {
                TrackSourceUtil.Meta meta = TrackSourceUtil.parseMeta(f);
                if (meta.waybillId <= 0) {
                    failed++;
                    continue;
                }
                TrajectoryWaybill existing = mapper.selectOne(
                        new LambdaQueryWrapper<TrajectoryWaybill>()
                                .eq(TrajectoryWaybill::getWaybillId, meta.waybillId));
                TrajectoryWaybill row = existing == null ? new TrajectoryWaybill() : existing;
                row.setWaybillNo(TrackSourceUtil.parseWaybillNo(f));
                row.setWaybillId(meta.waybillId);
                row.setVehicleId(meta.vehicleId);
                row.setFromType(meta.fromType);
                // 业务库回填：车牌 + 货物 + 收发货地点/坐标/时间（轨迹文件无这些字段）
                BizWaybill biz = bizOf(meta.waybillId);
                if (biz != null) {
                    row.setPlateNo(biz.plateNo);
                    row.setMaterialName(biz.materialName);
                    row.setSendAddrName(biz.sendAddrName);
                    row.setSendAddrArea(biz.sendAddrArea);
                    row.setSendAddrDetail(biz.sendAddrDetail);
                    row.setSendLon(biz.sendLon);
                    row.setSendLat(biz.sendLat);
                    row.setReceiveAddrName(biz.receiveAddrName);
                    row.setReceiveAddrArea(biz.receiveAddrArea);
                    row.setReceiveAddrDetail(biz.receiveAddrDetail);
                    row.setReceiveLon(biz.receiveLon);
                    row.setReceiveLat(biz.receiveLat);
                    row.setLoadTime(biz.loadTime);
                    row.setUnloadTime(biz.unloadTime);
                    row.setOrderTime(biz.orderTime);
                }
                row.setPointCount(meta.pointCount);
                row.setGtmStart(TrackSourceUtil.epochMsToLocal(meta.firstGtmMs));
                row.setGtmEnd(TrackSourceUtil.epochMsToLocal(meta.lastGtmMs));
                row.setSourceFile(f.getFileName().toString());
                if (row.getDataStatus() == null) row.setDataStatus("IMPORTED");
                row.setUpdateTime(now);
                if (existing == null) {
                    row.setCreateTime(now);
                    mapper.insert(row);
                    imported++;
                } else {
                    mapper.updateById(row);
                    updated++;
                }
            } catch (Exception e) {
                failed++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", files.size());
        m.put("imported", imported);
        m.put("updated", updated);
        m.put("failed", failed);
        m.put("sourceDir", dir.toString());
        return m;
    }

    @Override
    public IPage<TrajectoryWaybill> page(WaybillQueryForm form) {
        LambdaQueryWrapper<TrajectoryWaybill> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(form.getWaybillNo())) {
            qw.like(TrajectoryWaybill::getWaybillNo, form.getWaybillNo().trim());
        }
        if (form.getVehicleId() != null) {
            qw.eq(TrajectoryWaybill::getVehicleId, form.getVehicleId());
        }
        if (StringUtils.hasText(form.getPlateNo())) {
            // 车牌过滤：数据源无车牌列时查不到，属预期；代码保留以兼容业务库回填后的查询
            qw.like(TrajectoryWaybill::getPlateNo, form.getPlateNo().trim());
        }
        if (form.getStartTime() != null) {
            qw.and(w -> w.ge(TrajectoryWaybill::getGtmEnd, form.getStartTime()));
        }
        if (form.getEndTime() != null) {
            qw.and(w -> w.le(TrajectoryWaybill::getGtmStart, form.getEndTime()));
        }
        if (StringUtils.hasText(form.getDataStatus())) {
            qw.eq(TrajectoryWaybill::getDataStatus, form.getDataStatus());
        }
        qw.orderByDesc(TrajectoryWaybill::getWaybillId);
        long current = Math.max(1, form.getCurrent());
        long size = Math.min(200, Math.max(1, form.getSize()));
        return mapper.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public TrajectoryWaybill getByWaybillId(long waybillId) {
        return mapper.selectOne(new LambdaQueryWrapper<TrajectoryWaybill>()
                .eq(TrajectoryWaybill::getWaybillId, waybillId).last("limit 1"));
    }
}
