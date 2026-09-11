package com.logicompress.experiment.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实验全部参数集中配置。
 *
 * <p><b>口径唯一来源</b>：可视化系统运行时的真实参数由 `trajectory.experiment.*`
 * （application-local.yml → {@code ExperimentProperties}）决定，经
 * {@code ExperimentConfigs.apply()} 覆盖到本类实例上——可视化系统的每条链路
 * （在线压缩 / 第 6 章实验）都是 {@code ExperimentConfigs.from(eprops)}，字段默认值不参与运行。
 *
 * <p>因此本类的默认值**只对独立入口 {@code Main} / {@code Supplementary} 生效**
 * （这两个类不接入 Spring，可视化系统内无引用）。但默认值必须与 yml 口径保持一致——
 * 否则谁直接 {@code new ExperimentConfig()} 跑一把，就会静默用上另一套参数。
 * 改动 yml 口径时，请同步本文件对应默认值（2026-09-11 已按此对齐）。
 */
public class ExperimentConfig {

    // ---- 数据与输出路径 ----
    public String trackDataDir = "D:/aoming/电科/毕业论文 - claude/01开题/公司FKH/track_data";
    public String waybillDataDir = "D:/aoming/电科/毕业论文 - claude/01开题/公司FKH/waybill_data";
    public String resultsDir = "D:/aoming/电科/毕业论文 - claude/04实验/results";

    // ---- 数据清洗 ----
    /** 剔除点数小于该值的运单（单点/极短运单） */
    public int minPoints = 5;
    /** 经纬度推算速度超过该值判为漂移点，km/h（数据 q99.9=83.6，>120 仅 56 对） */
    public double driftSpeedKph = 120.0;
    /** 相邻点时间差超过该值视为断线，秒（>10min 断线，不插值） */
    public long breakGapS = 600;

    // ---- L1 电子围栏强匹配 ----
    /** 围栏默认半径，米（用户指定；后续在"地址基础信息"中按地址配置） */
    public double fenceRadiusM = 2000.0;
    /** 围栏命中段与 loadTime/unloadTime 的时窗容差，秒（±30min） */
    public long l1TimeWindowToleranceS = 1800;

    // ---- L2 ST-DBSCAN ----
    /** 空间邻域半径，米（论文未定值，实验默认经数据网格搜索定标） */
    public double dbscanEpsM = 50.0;
    /** 时间邻域半径，秒 */
    public long dbscanEpsTS = 300;
    /** 簇最少点数 */
    public int dbscanMinPts = 3;

    // ---- L3 运动学精修 ----
    /** 停留速度阈值，km/h（≈3m/s） */
    public double vThKph = 11.0;
    /** 停留空间半径，米 */
    public double rSM = 40.0;
    /** 最短停留时长，秒（3min） */
    public long dMinS = 180;
    /** 进入停留连续低速点数 */
    public int nEnter = 3;
    /** 离开停留连续高速点数 */
    public int mLeave = 3;

    // ---- 静态停留识别（v2 方案1，见 04实验/停留识别方案变更_给claudecode_v2.md）----
    /** 是否启用静态停留识别（v2 主路径）：连续 spd=0 且位移≤ε_same 的点跨大间隔合并为停留段；false=旧分层方法(L1/L2/L3) */
    public boolean stationaryMergeEnabled = true;
    /** 静止段空间阈值 ε_same，米（容忍 GPS 抖动；默认 10m） */
    public double stationarySameM = 10.0;

    // ---- 分级压缩 ----
    /**
     * 关键段（停留单元区段）DP 容差，米。
     * 与 {@code trajectory.experiment.dp-move-tolerance-m} 保持一致（2026-09-09 用户定稿：本文与基线统一 10m）。
     * 注：v2 的 SegmentedDp 静止段整段只保两端锚点，本值实际不参与抽稀，仅为口径记录。
     */
    public double dpEpsKeyM = 10.0;
    /**
     * 非关键段（移动段）DP 容差，米。
     * 与 {@code trajectory.experiment.dp-move-tolerance-m} 保持一致（本文与 DP/DPS/TD-TR/Trajic 基线同容差公平对比）。
     */
    public double dpEpsNonKeyM = 10.0;
    /** 纯 DP 基线容差，米（对齐公司生产默认） */
    public double baselineDpM = 10.0;
    /** 消融：是否不强制保留语义锚点（仅保留断线分段端点），默认 false=强制保留 */
    public boolean noAnchorForce = false;

    // ---- 分块无损编码 ----
    /**
     * 块长（固定时间窗），秒。与 {@code trajectory.experiment.block-window-s} 保持一致
     * （2026-09-11 用户定稿：10min(600) → 1h(3600)，Mongo 分片与部分解压查询窗同为 1 小时）。
     */
    public long blockWindowS = 3600;
    /** 量化精度（×10^precision；默认 6，坐标量化误差≤0.08 m，见论文 6.4.3） */
    public int precision = 6;
    /** zip(DEFLATE) 压缩级别 */
    public int zipLevel = 6;

    // ---- 评估 ----
    /** 压缩失真速度比上限，km/h */
    public double speedLimitKph = 90.0;
    /** 部分解压查询时间窗，秒（1h） */
    public long queryWindowS = 3600;
    /** 查询时间窗位置：轨迹时长的比例（0~1，默认约 60% 处） */
    public double queryWindowFraction = 0.6;

    /** 从命令行覆盖参数，形如 --key=value / --key value */
    public void applyArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) continue;
            String kv = a.substring(2);
            int eq = kv.indexOf('=');
            String key = eq >= 0 ? kv.substring(0, eq) : kv;
            String val;
            if (eq >= 0) {
                val = kv.substring(eq + 1);
            } else if ("no-anchor-force".equals(key) || "stationary-merge-enabled".equals(key)) {
                // 裸布尔 flag（无 =value）视为 true，且不消费下一 token
                val = "true";
            } else {
                val = (i + 1 < args.length ? args[++i] : "");
            }
            if (val.isEmpty()) continue;
            setByKey(key, val);
        }
    }

    public void setByKey(String key, String val) {
        switch (key) {
            case "track-data-dir": trackDataDir = val; break;
            case "waybill-data-dir": waybillDataDir = val; break;
            case "results-dir": resultsDir = val; break;
            case "min-points": minPoints = Integer.parseInt(val); break;
            case "drift-speed-kph": driftSpeedKph = Double.parseDouble(val); break;
            case "break-gap-s": breakGapS = Long.parseLong(val); break;
            case "fence-radius-m": fenceRadiusM = Double.parseDouble(val); break;
            case "l1-window-s": l1TimeWindowToleranceS = Long.parseLong(val); break;
            case "dbscan-eps-m": dbscanEpsM = Double.parseDouble(val); break;
            case "dbscan-eps-t-s": dbscanEpsTS = Long.parseLong(val); break;
            case "dbscan-min-pts": dbscanMinPts = Integer.parseInt(val); break;
            case "vth-kph": vThKph = Double.parseDouble(val); break;
            case "rs-m": rSM = Double.parseDouble(val); break;
            case "dmin-s": dMinS = Long.parseLong(val); break;
            case "n-enter": nEnter = Integer.parseInt(val); break;
            case "m-leave": mLeave = Integer.parseInt(val); break;
            case "static-merge-enabled": stationaryMergeEnabled = Boolean.parseBoolean(val); break;
            case "stationary-merge-enabled": stationaryMergeEnabled = Boolean.parseBoolean(val); break;
            case "static-merge-eps-m": stationarySameM = Double.parseDouble(val); break;
            case "stationary-same-m": stationarySameM = Double.parseDouble(val); break;
            case "dp-eps-key-m": dpEpsKeyM = Double.parseDouble(val); break;
            case "dp-eps-nonkey-m": dpEpsNonKeyM = Double.parseDouble(val); break;
            case "baseline-dp-m": baselineDpM = Double.parseDouble(val); break;
            case "no-anchor-force": noAnchorForce = Boolean.parseBoolean(val); break;
            case "block-window-s": blockWindowS = Long.parseLong(val); break;
            case "precision": precision = Integer.parseInt(val); break;
            case "zip-level": zipLevel = Integer.parseInt(val); break;
            case "speed-limit-kph": speedLimitKph = Double.parseDouble(val); break;
            case "query-window-s": queryWindowS = Long.parseLong(val); break;
            case "query-window-frac": queryWindowFraction = Double.parseDouble(val); break;
            default: System.err.println("[warn] 未知参数: " + key);
        }
    }

    public Map<String, String> asMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("track-data-dir", trackDataDir);
        m.put("waybill-data-dir", waybillDataDir);
        m.put("results-dir", resultsDir);
        m.put("min-points", String.valueOf(minPoints));
        m.put("drift-speed-kph", String.valueOf(driftSpeedKph));
        m.put("break-gap-s", String.valueOf(breakGapS));
        m.put("fence-radius-m", String.valueOf(fenceRadiusM));
        m.put("l1-window-s", String.valueOf(l1TimeWindowToleranceS));
        m.put("dbscan-eps-m", String.valueOf(dbscanEpsM));
        m.put("dbscan-eps-t-s", String.valueOf(dbscanEpsTS));
        m.put("dbscan-min-pts", String.valueOf(dbscanMinPts));
        m.put("vth-kph", String.valueOf(vThKph));
        m.put("rs-m", String.valueOf(rSM));
        m.put("dmin-s", String.valueOf(dMinS));
        m.put("n-enter", String.valueOf(nEnter));
        m.put("m-leave", String.valueOf(mLeave));
        m.put("stationary-merge-enabled", String.valueOf(stationaryMergeEnabled));
        m.put("stationary-same-m", String.valueOf(stationarySameM));
        m.put("dp-eps-key-m", String.valueOf(dpEpsKeyM));
        m.put("dp-eps-nonkey-m", String.valueOf(dpEpsNonKeyM));
        m.put("baseline-dp-m", String.valueOf(baselineDpM));
        m.put("no-anchor-force", String.valueOf(noAnchorForce));
        m.put("block-window-s", String.valueOf(blockWindowS));
        m.put("precision", String.valueOf(precision));
        m.put("zip-level", String.valueOf(zipLevel));
        m.put("speed-limit-kph", String.valueOf(speedLimitKph));
        m.put("query-window-s", String.valueOf(queryWindowS));
        m.put("query-window-frac", String.valueOf(queryWindowFraction));
        return m;
    }
}
