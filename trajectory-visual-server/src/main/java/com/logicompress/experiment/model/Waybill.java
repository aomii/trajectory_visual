package com.logicompress.experiment.model;

import java.util.List;

/**
 * 运单：waybill_data（业务信息）与 track_data（轨迹）按 waybill_no 关联后的聚合。
 * 收发货地址经纬度作为 L1 圆形围栏的圆心，半径见 ExperimentConfig.fenceRadiusM。
 */
public class Waybill {
    public long waybillId;
    public String waybillNo;

    /** 发货地址圆心（经纬度，WGS84） */
    public double sendLon, sendLat;
    /** 收货地址圆心（经纬度，WGS84） */
    public double receiveLon, receiveLat;
    public String sendAddrName = "";
    public String receiveAddrName = "";

    /** 装货 / 卸货时间（北京时间 epoch 秒） */
    public long loadEpoch, unloadEpoch;

    public String plateNo = "";
    public String materialName = "";
    public long vehicleId;

    /** 原始轨迹点（按 gtm 升序） */
    public List<TrackPoint> rawPoints;

    /** 解析结果数据，原始文件完整路径 */
    public String trackFile;
    public String waybillFile;

    public double distanceSendReceiveM() {
        return com.logicompress.experiment.geo.GeoUtil.haversineM(
                sendLat, sendLon, receiveLat, receiveLon);
    }

    @Override
    public String toString() {
        return "Waybill{" + waybillNo + " id=" + waybillId
                + " send=(" + sendLon + "," + sendLat + ") recv=(" + receiveLon + "," + receiveLat + ")"
                + " points=" + (rawPoints == null ? 0 : rawPoints.size())
                + " plate=" + plateNo + " material=" + materialName + "}";
    }
}
