-- ============================================================
-- trajectory-visual-server MySQL 初始化脚本
-- 库：ml_network_freight（本地 root/root，serverTimezone=Asia/Shanghai）
-- 说明：
--   1) 六张 trajectory_eval_* 为第 6 章实验结果表（规格书 §8）
--   2) trajectory_waybill 为运单元数据表（来自 7709 在线库 source_data_full 的 import 扫描，
--      只存元数据/统计，原始轨迹点数组不落库，原始以本地 JSON 文件为准）
--   3) 全部为独立新表，避免与老业务库复杂 schema 冲突
-- ============================================================

CREATE DATABASE IF NOT EXISTS ml_network_freight
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE ml_network_freight;

-- ---------- 运单元数据 ----------
DROP TABLE IF EXISTS trajectory_waybill;
CREATE TABLE trajectory_waybill (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  waybill_no    VARCHAR(64)  NOT NULL COMMENT '脱敏运单号（文件名 track_{no}_{id}.json 解析）',
  waybill_id    BIGINT       NOT NULL COMMENT '源文件 waybillId',
  vehicle_id    BIGINT       DEFAULT NULL COMMENT '车辆内部id',
  from_type     INT          DEFAULT NULL COMMENT '来源类型',
  plate_no      VARCHAR(32)  DEFAULT '' COMMENT '车牌号（轨迹文件无此字段，从业务库 waybill 表回填）',
  material_name VARCHAR(120) DEFAULT '' COMMENT '货物名称（业务库 waybill 表回填）',
  -- 收发货元数据（业务库 waybill 表回填；地址坐标 lal 为 "lon,lat"，与轨迹同为 GCJ-02）
  send_addr_name      VARCHAR(60)  DEFAULT '' COMMENT '发货地点名称',
  send_addr_area      VARCHAR(60)  DEFAULT '' COMMENT '发货行政区划',
  send_addr_detail    VARCHAR(200) DEFAULT '' COMMENT '发货详细地址',
  send_lon            DOUBLE       DEFAULT NULL COMMENT '发货地经度（GCJ-02）',
  send_lat            DOUBLE       DEFAULT NULL COMMENT '发货地纬度（GCJ-02）',
  receive_addr_name   VARCHAR(60)  DEFAULT '' COMMENT '收货地点名称',
  receive_addr_area   VARCHAR(60)  DEFAULT '' COMMENT '收货行政区划',
  receive_addr_detail VARCHAR(200) DEFAULT '' COMMENT '收货详细地址',
  receive_lon         DOUBLE       DEFAULT NULL COMMENT '收货地经度（GCJ-02）',
  receive_lat         DOUBLE       DEFAULT NULL COMMENT '收货地纬度（GCJ-02）',
  load_time     DATETIME     DEFAULT NULL COMMENT '装货（发货）时间',
  unload_time   DATETIME     DEFAULT NULL COMMENT '卸货（收货）时间',
  order_time    DATETIME     DEFAULT NULL COMMENT '运单创建/接单时间',
  point_count   INT          DEFAULT NULL COMMENT '原始轨迹点数',
  gtm_start     DATETIME     DEFAULT NULL COMMENT '轨迹开始（北京时间）',
  gtm_end       DATETIME     DEFAULT NULL COMMENT '轨迹结束（北京时间）',
  source_file   VARCHAR(512) DEFAULT NULL COMMENT '源文件名',
  data_status   VARCHAR(16)  DEFAULT 'IMPORTED' COMMENT 'IMPORTED/COMPRESSED',
  create_time   DATETIME     DEFAULT NULL,
  update_time   DATETIME     DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_waybill_id (waybill_id),
  KEY idx_waybill_no (waybill_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='运单元数据（在线可视化库，原始轨迹以文件为准）';

-- ---------- 实验批次 ----------
DROP TABLE IF EXISTS trajectory_eval_run;
CREATE TABLE trajectory_eval_run (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  run_no           VARCHAR(64)  NOT NULL COMMENT '批次号',
  run_name         VARCHAR(128) DEFAULT NULL,
  data_source_dir  VARCHAR(512) DEFAULT NULL COMMENT '数据源目录（可复现）',
  waybill_count    INT          DEFAULT NULL COMMENT '有效运单数',
  raw_point_count  BIGINT       DEFAULT NULL COMMENT '有效运单清洗后轨迹点合计',
  status           VARCHAR(16)  DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/FAILED',
  started_at       DATETIME     DEFAULT NULL,
  finished_at      DATETIME     DEFAULT NULL,
  duration_ms      BIGINT       DEFAULT NULL,
  remark           VARCHAR(512) DEFAULT NULL,
  parameter_json   TEXT         COMMENT '实验参数快照JSON（可复现）',
  create_time      DATETIME     DEFAULT NULL,
  update_time      DATETIME     DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_run_no (run_no),
  KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='第6章实验批次';

-- ---------- 每批次每算法聚合指标 ----------
DROP TABLE IF EXISTS trajectory_eval_algorithm_result;
CREATE TABLE trajectory_eval_algorithm_result (
  id                           BIGINT   NOT NULL AUTO_INCREMENT,
  run_id                       BIGINT   NOT NULL,
  algorithm_code               VARCHAR(32) COMMENT 'PROPOSED/DP/DPS/TD-TR/Trajic',
  algorithm_name               VARCHAR(64),
  parameter_json               TEXT,
  waybill_count                INT,
  raw_point_count              BIGINT,
  kept_point_count             BIGINT,
  chunk_count                  INT,
  cr_lossy_avg                 DOUBLE,
  cr_lossless_avg              DOUBLE,
  cr_total_avg                 DOUBLE,
  cr_e2e_global                DOUBLE COMMENT '清洗后规范文本总字节/编码负载总字节',
  input_bytes                  BIGINT COMMENT '清洗后完整点列规范文本总字节',
  ped_avg                      DOUBLE COMMENT '米',
  sed_avg                      DOUBLE COMMENT '米',
  sr_avg                       DOUBLE,
  semantic_unit_complete_rate  DOUBLE,
  stay_duration_preserve_rate  DOUBLE,
  encode_time_ms_avg           DOUBLE,
  decode_time_ms_avg           DOUBLE,
  storage_bytes                BIGINT,
  query_time_ms_avg            DOUBLE,
  partial_read_ratio_avg       DOUBLE,
  create_time                  DATETIME,
  PRIMARY KEY (id),
  UNIQUE KEY uk_run_alg (run_id, algorithm_code),
  KEY idx_run (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='每批次每算法聚合指标';

-- ---------- 单运单×算法 明细 ----------
DROP TABLE IF EXISTS trajectory_eval_waybill_result;
CREATE TABLE trajectory_eval_waybill_result (
  id                           BIGINT   NOT NULL AUTO_INCREMENT,
  run_id                       BIGINT   NOT NULL,
  waybill_id                   BIGINT,
  waybill_no                   VARCHAR(64),
  algorithm_code               VARCHAR(32),
  parameter_json               TEXT,
  raw_point_count              INT,
  kept_point_count             INT,
  chunk_count                  INT,
  cr_lossy                     DOUBLE,
  cr_lossless                  DOUBLE,
  cr_total                     DOUBLE,
  cr_e2e                       DOUBLE COMMENT '单运单清洗后规范文本字节/编码负载字节',
  input_bytes                  BIGINT COMMENT '清洗后完整点列规范文本字节',
  ped_avg                      DOUBLE,
  ped_max                      DOUBLE,
  sed_avg                      DOUBLE,
  sed_max                      DOUBLE,
  sr                           DOUBLE,
  semantic_unit_complete_rate  DOUBLE,
  stay_duration_preserve_rate  DOUBLE,
  encode_time_ms               DOUBLE,
  decode_time_ms               DOUBLE,
  storage_bytes                BIGINT,
  query_time_ms                DOUBLE,
  partial_read_ratio           DOUBLE,
  stay_count                   INT,
  anchor_count                 INT,
  create_time                  DATETIME,
  PRIMARY KEY (id),
  KEY idx_run_waybill (run_id, waybill_id),
  KEY idx_run_alg (run_id, algorithm_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='单运单×算法实验明细（前端下钻/统计）';

-- ---------- 消融 ----------
DROP TABLE IF EXISTS trajectory_eval_ablation_result;
CREATE TABLE trajectory_eval_ablation_result (
  id                           BIGINT   NOT NULL AUTO_INCREMENT,
  run_id                       BIGINT   NOT NULL,
  ablation_code                VARCHAR(32) COMMENT 'A0/A-TIGHT/A2/A3/A4',
  ablation_name                VARCHAR(64),
  parameter_json               TEXT,
  cr_total_avg                 DOUBLE,
  cr_lossy_avg                 DOUBLE,
  sr_avg                       DOUBLE,
  semantic_unit_complete_rate  DOUBLE,
  stay_duration_preserve_rate  DOUBLE,
  ped_avg                      DOUBLE,
  sed_avg                      DOUBLE,
  query_time_ms_avg            DOUBLE,
  storage_bytes                BIGINT,
  remark                       VARCHAR(512),
  create_time                  DATETIME,
  PRIMARY KEY (id),
  KEY idx_run (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='消融实验（A0/A-TIGHT/A2/A3/A4）';

-- ---------- 参数敏感性 ----------
DROP TABLE IF EXISTS trajectory_eval_param_result;
CREATE TABLE trajectory_eval_param_result (
  id                     BIGINT   NOT NULL AUTO_INCREMENT,
  run_id                 BIGINT   NOT NULL,
  param_type             VARCHAR(32) COMMENT 'dp_tolerance/block_window',
  param_value            VARCHAR(32) COMMENT '容差米数或块长秒数',
  algorithm_code         VARCHAR(32),
  cr_lossy_avg           DOUBLE,
  cr_total_avg           DOUBLE,
  ped_avg                DOUBLE,
  sed_avg                DOUBLE,
  sr_avg                 DOUBLE,
  query_time_ms_avg      DOUBLE,
  partial_read_ratio_avg DOUBLE,
  create_time            DATETIME,
  PRIMARY KEY (id),
  KEY idx_run (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='参数敏感性实验';

-- ---------- 失败记录 ----------
DROP TABLE IF EXISTS trajectory_eval_error_record;
CREATE TABLE trajectory_eval_error_record (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  run_id          BIGINT,
  waybill_no      VARCHAR(64),
  source_file     VARCHAR(512),
  experiment_code VARCHAR(64),
  error_message   VARCHAR(1024),
  create_time     DATETIME,
  PRIMARY KEY (id),
  KEY idx_run (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='实验失败记录（不中断整批）';
