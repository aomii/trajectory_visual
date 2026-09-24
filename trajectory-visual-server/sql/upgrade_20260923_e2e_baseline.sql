-- ============================================================
-- 2026-09-23 增量升级：统一编码器端到端评估口径
-- 用法：已有数据库只执行本脚本；不要重跑会 DROP 表的 init.sql。
-- ============================================================
USE ml_network_freight;

ALTER TABLE trajectory_eval_algorithm_result
  ADD COLUMN cr_e2e_global DOUBLE DEFAULT NULL
    COMMENT '清洗后规范文本总字节/编码负载总字节' AFTER cr_total_avg,
  ADD COLUMN input_bytes BIGINT DEFAULT NULL
    COMMENT '清洗后完整点列规范文本总字节' AFTER cr_e2e_global;

ALTER TABLE trajectory_eval_waybill_result
  ADD COLUMN cr_e2e DOUBLE DEFAULT NULL
    COMMENT '单运单清洗后规范文本字节/编码负载字节' AFTER cr_total,
  ADD COLUMN input_bytes BIGINT DEFAULT NULL
    COMMENT '清洗后完整点列规范文本字节' AFTER cr_e2e;

-- 旧批次没有基线无损编码结果，新增字段保持 NULL；执行新的 6.3 主实验后回填新批次。
