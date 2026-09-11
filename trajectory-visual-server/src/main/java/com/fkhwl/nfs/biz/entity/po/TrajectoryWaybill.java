package com.fkhwl.nfs.biz.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 运单元数据（来自全量轨迹源文件 source_data_full 的 import 扫描）。
 * 只存元数据与统计，原始轨迹点数组不落 MySQL（原始以本地 JSON 文件为准，见规格书数据边界）。
 */
@Data
@TableName("trajectory_waybill")
public class TrajectoryWaybill {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 脱敏运单号（从文件名 track_{运单号}_{waybillId}.json 解析，如 Y250411113343） */
    private String waybillNo;
    /** 源文件中的 waybillId */
    private Long waybillId;
    /** 车辆内部 id（source_data_full 无车牌，车牌在业务库 waybill 表，本机未导入） */
    private Long vehicleId;
    private Integer fromType;
    /** 车牌号：轨迹文件无此字段，导入时从业务库 waybill 表按 waybillId 回填 */
    private String plateNo;
    /** 货物名称（业务库 waybill.material_name） */
    private String materialName;

    // ---- 收发货元数据（业务库 waybill 表回填；解决"工作台看不到收发时间/地点/坐标"） ----
    /** 发货地点名称 */
    private String sendAddrName;
    /** 发货行政区划 */
    private String sendAddrArea;
    /** 发货详细地址 */
    private String sendAddrDetail;
    /** 发货地经度 / 纬度（业务库 send_addr_lal，GCJ-02，与轨迹同系） */
    private Double sendLon;
    private Double sendLat;
    /** 收货地点名称 */
    private String receiveAddrName;
    /** 收货行政区划 */
    private String receiveAddrArea;
    /** 收货详细地址 */
    private String receiveAddrDetail;
    /** 收货地经度 / 纬度（业务库 receive_addr_lal，GCJ-02） */
    private Double receiveLon;
    private Double receiveLat;
    /** 装货（发货）时间 */
    private LocalDateTime loadTime;
    /** 卸货（收货）时间 */
    private LocalDateTime unloadTime;
    /** 运单创建/接单时间 */
    private LocalDateTime orderTime;

    /** 原始轨迹点数 */
    private Integer pointCount;
    /** 轨迹起止时间（北京时间） */
    private LocalDateTime gtmStart;
    private LocalDateTime gtmEnd;
    /** 源文件名（含相对路径，便于回读原始轨迹） */
    private String sourceFile;
    /** 数据状态：IMPORTED / COMPRESSED 等 */
    private String dataStatus;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableField(exist = false)
    private String vehiclePlate; // 冗余展示字段(占位)
}
