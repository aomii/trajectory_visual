package com.fkhwl.nfs.biz.entity.form.visual;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 运单查询表单（页面一 轨迹压缩工作台的筛选区）。
 * 支持按运单号/车辆id/车牌(数据源缺失故常空)/时间范围分页；车牌过滤在数据源无车牌列时自然为空结果。
 */
@Data
public class WaybillQueryForm {

    private long current = 1;
    private long size = 10;

    /** 运单号（模糊） */
    private String waybillNo;
    /** 车辆内部 id */
    private Long vehicleId;
    /** 车牌号（模糊；7709 在线库文件无车牌字段，导入时置空） */
    private String plateNo;

    /** 轨迹开始不早于（轨迹与时间范围重叠） */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    /** 压缩状态过滤：空=全部；IMPORTED=未压缩；COMPRESSED=已压缩 */
    private String dataStatus;
}
