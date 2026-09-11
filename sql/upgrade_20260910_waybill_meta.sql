-- ============================================================
-- 2026-09-10 增量升级：trajectory_waybill 补收发货元数据
-- 背景：问题记录 #2 —— 轨迹压缩工作台的运单源数据缺少"收发货时间/地点"与收发坐标，
--       这些字段在业务库 waybill 表里本来就有，导入时未回填。
-- 用法：对已存在的库执行本脚本（init.sql 已同步，但 init.sql 会 DROP 表，勿重跑）。
--       mysql -uroot -proot ml_network_freight < sql/upgrade_20260910_waybill_meta.sql
-- 执行后重新调用一次导入接口，历史运单的收发货元数据才会被回填：
--       curl -X POST "http://127.0.0.1:8080/api/visual/waybill/admin/import-waybills"
-- ============================================================
USE ml_network_freight;

ALTER TABLE trajectory_waybill
  ADD COLUMN material_name       VARCHAR(120) DEFAULT ''     COMMENT '货物名称（业务库 waybill 表回填）' AFTER plate_no,
  ADD COLUMN send_addr_name      VARCHAR(60)  DEFAULT ''     COMMENT '发货地点名称' AFTER material_name,
  ADD COLUMN send_addr_area      VARCHAR(60)  DEFAULT ''     COMMENT '发货行政区划' AFTER send_addr_name,
  ADD COLUMN send_addr_detail    VARCHAR(200) DEFAULT ''     COMMENT '发货详细地址' AFTER send_addr_area,
  ADD COLUMN send_lon            DOUBLE       DEFAULT NULL   COMMENT '发货地经度（GCJ-02）' AFTER send_addr_detail,
  ADD COLUMN send_lat            DOUBLE       DEFAULT NULL   COMMENT '发货地纬度（GCJ-02）' AFTER send_lon,
  ADD COLUMN receive_addr_name   VARCHAR(60)  DEFAULT ''     COMMENT '收货地点名称' AFTER send_lat,
  ADD COLUMN receive_addr_area   VARCHAR(60)  DEFAULT ''     COMMENT '收货行政区划' AFTER receive_addr_name,
  ADD COLUMN receive_addr_detail VARCHAR(200) DEFAULT ''     COMMENT '收货详细地址' AFTER receive_addr_area,
  ADD COLUMN receive_lon         DOUBLE       DEFAULT NULL   COMMENT '收货地经度（GCJ-02）' AFTER receive_addr_detail,
  ADD COLUMN receive_lat         DOUBLE       DEFAULT NULL   COMMENT '收货地纬度（GCJ-02）' AFTER receive_lon,
  ADD COLUMN load_time           DATETIME     DEFAULT NULL   COMMENT '装货（发货）时间' AFTER receive_lat,
  ADD COLUMN unload_time         DATETIME     DEFAULT NULL   COMMENT '卸货（收货）时间' AFTER load_time,
  ADD COLUMN order_time          DATETIME     DEFAULT NULL   COMMENT '运单创建/接单时间' AFTER unload_time;

-- 说明：plate_no 列注释同步为"从业务库 waybill 表回填"（原注释写着 7709 文件无车牌，已过时）
ALTER TABLE trajectory_waybill
  MODIFY COLUMN plate_no VARCHAR(32) DEFAULT '' COMMENT '车牌号（轨迹文件无此字段，从业务库 waybill 表回填）';
