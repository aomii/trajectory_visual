package com.fkhwl.nfs.biz.experiment;

import com.fkhwl.nfs.config.ExperimentProperties;
import com.logicompress.experiment.config.ExperimentConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实验配置工厂：把 application-local.yml 的 trajectory.experiment.* 映射到
 * 移植算法 {@link ExperimentConfig}。不改动 ExperimentConfig 内部默认值，
 * 仅在构建时按本系统口径（用户定稿：容差统一 10m）赋值。
 */
public final class ExperimentConfigs {

    private ExperimentConfigs() {
    }

    /**
     * 由配置属性构造默认实验参数对象（供第 6 章测试类与在线压缩使用）。
     */
    public static ExperimentConfig from(ExperimentProperties p) {
        ExperimentConfig c = new ExperimentConfig();
        apply(c, p);
        return c;
    }

    /** 把属性应用到已有 config（保留其余默认） */
    public static void apply(ExperimentConfig c, ExperimentProperties p) {
        // 【用户 2026-09-09 定稿】本文移动段与基线容差统一 = p.dpMoveToleranceM(=10m)
        c.dpEpsNonKeyM = p.getDpMoveToleranceM();
        c.baselineDpM = p.getBaselineToleranceM();
        // dpEpsKeyM 在 v2 SegmentedDp 中不参与抽稀（静止段整段保两端），置同值保持一致性记录
        c.dpEpsKeyM = p.getDpMoveToleranceM();

        // 语义识别（v2 口径，与 04实验 一致）
        c.stationaryMergeEnabled = true;
        c.stationarySameM = p.getStationarySameM();
        c.dMinS = p.getMinStayS();

        // 分块无损编码
        c.blockWindowS = p.getBlockWindowS();
        c.precision = p.getPrecision();
        c.zipLevel = p.getZipLevel();

        // 部分解压查询窗
        c.queryWindowS = p.getQueryWindowS();
        c.queryWindowFraction = p.getQueryWindowFraction();

        // 清洗
        c.driftSpeedKph = p.getDriftSpeedKph();
        c.breakGapS = p.getBreakGapS();
        c.minPoints = p.getMinPoints();
    }

    /** 实验参数快照（写入 run.parameter_json 保证可复现） */
    public static String parameterJson(ExperimentProperties p) {
        ExperimentConfig c = from(p);
        Map<String, String> m = new LinkedHashMap<>(c.asMap());
        return toJson(m);
    }

    /** 简单 JSON 序列化（避免引入依赖对象到该工具类） */
    public static String toJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
