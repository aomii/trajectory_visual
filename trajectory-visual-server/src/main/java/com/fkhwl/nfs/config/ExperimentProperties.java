package com.fkhwl.nfs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 第 6 章实验运行参数（application-local.yml 的 trajectory.experiment.* 段）。
 *
 * <p>负责把 yml 中的论文参数注入到移植算法 {@code com.logicompress.experiment.config.ExperimentConfig}
 * 上（见 Chapter6ExperimentRunner#buildConfig()）。不改动 ExperimentConfig 内部默认值，
 * 运行参数快照（experiment.parameterJson()）随每次实验批次写入 MySQL，保证可复现。
 *
 * <p>关键默认口径（2026-09-09 用户定稿）：
 * 本文移动段 DP 容差 = 基线容差 = 10m（公平对比；与 claude_15 论文现稿 15m/10m 不同，重跑后需回填论文）。
 */
@ConfigurationProperties(prefix = "trajectory.experiment")
public class ExperimentProperties {

    /** 本文移动段 DP 容差，米（默认 10，与基线一致） */
    private double dpMoveToleranceM = 10.0;
    /** 对比基线(DP/DPS/TD-TR/Trajic)容差，米 */
    private double baselineToleranceM = 10.0;
    /** 消融“收紧移动段容差”档容差，米 */
    private double ablationTightToleranceM = 8.0;

    /** 静止同坐标容差，米 */
    private double stationarySameM = 10.0;
    /** 最短停留时长，秒（≥180s 才成单元） */
    private long minStayS = 180;

    /**
     * 分块时间窗，秒（Mongo 分片的块时长）。
     * 【2026-09-11 用户定稿】10min(600) → 1h(3600)：块更大、块数更少，时间索引更稀疏；
     * 与 6.5 部分解压的查询窗（queryWindowS=3600）对齐——一个 1 小时查询窗≈一个分片。
     * yml 未配置时以此默认值为准，务必与 application-local.yml 的 block-window-s 保持一致。
     */
    private long blockWindowS = 3600;
    /** 量化精度 precision（默认 6，坐标量化误差≤7.5cm） */
    private int precision = 6;
    /** DEFLATE 压缩级别 */
    private int zipLevel = 6;

    /** 部分解压查询窗，秒 */
    private long queryWindowS = 3600;
    /** 查询窗位于轨迹时长的比例（0~1） */
    private double queryWindowFraction = 0.6;

    /** 清洗：漂移速度阈值 km/h */
    private double driftSpeedKph = 120.0;
    /** 清洗：断线间隔，秒 */
    private long breakGapS = 600;
    /** 清洗后最少点数 */
    private int minPoints = 5;

    /** 实验运单范围：-1=全部；>0 取前 N 个 */
    private int waybillLimit = -1;
    private int waybillOffset = 0;

    public double getDpMoveToleranceM() { return dpMoveToleranceM; }
    public void setDpMoveToleranceM(double v) { this.dpMoveToleranceM = v; }
    public double getBaselineToleranceM() { return baselineToleranceM; }
    public void setBaselineToleranceM(double v) { this.baselineToleranceM = v; }
    public double getAblationTightToleranceM() { return ablationTightToleranceM; }
    public void setAblationTightToleranceM(double v) { this.ablationTightToleranceM = v; }
    public double getStationarySameM() { return stationarySameM; }
    public void setStationarySameM(double v) { this.stationarySameM = v; }
    public long getMinStayS() { return minStayS; }
    public void setMinStayS(long v) { this.minStayS = v; }
    public long getBlockWindowS() { return blockWindowS; }
    public void setBlockWindowS(long v) { this.blockWindowS = v; }
    public int getPrecision() { return precision; }
    public void setPrecision(int v) { this.precision = v; }
    public int getZipLevel() { return zipLevel; }
    public void setZipLevel(int v) { this.zipLevel = v; }
    public long getQueryWindowS() { return queryWindowS; }
    public void setQueryWindowS(long v) { this.queryWindowS = v; }
    public double getQueryWindowFraction() { return queryWindowFraction; }
    public void setQueryWindowFraction(double v) { this.queryWindowFraction = v; }
    public double getDriftSpeedKph() { return driftSpeedKph; }
    public void setDriftSpeedKph(double v) { this.driftSpeedKph = v; }
    public long getBreakGapS() { return breakGapS; }
    public void setBreakGapS(long v) { this.breakGapS = v; }
    public int getMinPoints() { return minPoints; }
    public void setMinPoints(int v) { this.minPoints = v; }
    public int getWaybillLimit() { return waybillLimit; }
    public void setWaybillLimit(int v) { this.waybillLimit = v; }
    public int getWaybillOffset() { return waybillOffset; }
    public void setWaybillOffset(int v) { this.waybillOffset = v; }
}
