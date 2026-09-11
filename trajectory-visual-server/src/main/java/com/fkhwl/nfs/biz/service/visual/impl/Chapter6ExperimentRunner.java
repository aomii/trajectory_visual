package com.fkhwl.nfs.biz.service.visual.impl;

import com.fkhwl.nfs.biz.entity.po.TrajectoryEvalAblationResult;
import com.fkhwl.nfs.biz.entity.po.TrajectoryEvalAlgorithmResult;
import com.fkhwl.nfs.biz.entity.po.TrajectoryEvalErrorRecord;
import com.fkhwl.nfs.biz.entity.po.TrajectoryEvalParamResult;
import com.fkhwl.nfs.biz.entity.po.TrajectoryEvalRun;
import com.fkhwl.nfs.biz.entity.po.TrajectoryEvalWaybillResult;
import com.fkhwl.nfs.biz.experiment.CompressionPipeline;
import com.fkhwl.nfs.biz.experiment.ExperimentConfigs;
import com.fkhwl.nfs.biz.experiment.PipelineOutcome;
import com.fkhwl.nfs.biz.experiment.TrackSourceUtil;
import com.fkhwl.nfs.biz.mapper.TrajectoryEvalAblationResultMapper;
import com.fkhwl.nfs.biz.mapper.TrajectoryEvalAlgorithmResultMapper;
import com.fkhwl.nfs.biz.mapper.TrajectoryEvalErrorRecordMapper;
import com.fkhwl.nfs.biz.mapper.TrajectoryEvalParamResultMapper;
import com.fkhwl.nfs.biz.mapper.TrajectoryEvalRunMapper;
import com.fkhwl.nfs.biz.mapper.TrajectoryEvalWaybillResultMapper;
import com.fkhwl.nfs.config.ExperimentProperties;
import com.fkhwl.nfs.config.VisualProperties;
import com.logicompress.experiment.config.ExperimentConfig;
import com.logicompress.experiment.model.SemanticResult;
import com.logicompress.experiment.model.StopUnit;
import com.logicompress.experiment.model.TrackPoint;
import com.logicompress.experiment.model.Waybill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第 6 章实验运行器（Spring 服务版，替代 04实验 的 Main + run.bat）。
 *
 * <p>把 04实验 的单运单管线（清洗→静态停留识别→分级抽稀→无损分块编码→部分解压→基线对比）
 * 编排为可复写的"批次 → MySQL 结果表"流程：
 * <ul>
 *   <li>每次运行生成一条 trajectory_eval_run（status RUNNING→SUCCESS/FAILED，含参数快照）；</li>
 *   <li>每批次每算法聚合 → trajectory_eval_algorithm_result；</li>
 *   <li>单运单×算法明细 → trajectory_eval_waybill_result（前端下钻/统计）；</li>
 *   <li>消融 → trajectory_eval_ablation_result；参数敏感性 → trajectory_eval_param_result；</li>
 *   <li>逐运单失败 → trajectory_eval_error_record（不中断整批）。</li>
 * </ul>
 * 数据源目录来自 trajectory.source.full-data-dir，运单范围受 trajectory.experiment.waybill-limit/offset 约束。
 *
 * <p>【口径】DP 容差默认统一 10m（2026-09-09 用户定稿，与 claude_15 论文现稿 15m/10m 不同，
 * 重跑后的第 6 章数字低于现稿，需要另行回填论文）。
 */
@Slf4j
@Service
public class Chapter6ExperimentRunner {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Map<String, String> ALG_NAMES = new LinkedHashMap<>();

    static {
        ALG_NAMES.put("PROPOSED", "本文方法");
        ALG_NAMES.put("DP", "道格拉斯-普克(DP)");
        ALG_NAMES.put("DPS", "方向保持(DPS)");
        ALG_NAMES.put("TD-TR", "时间同步(TD-TR)");
        ALG_NAMES.put("Trajic", "Trajic");
    }

    private final TrajectoryEvalRunMapper runMapper;
    private final TrajectoryEvalAlgorithmResultMapper algMapper;
    private final TrajectoryEvalWaybillResultMapper wbMapper;
    private final TrajectoryEvalAblationResultMapper abMapper;
    private final TrajectoryEvalParamResultMapper paramMapper;
    private final TrajectoryEvalErrorRecordMapper errMapper;
    private final ExperimentProperties props;
    private final VisualProperties vprops;

    public Chapter6ExperimentRunner(TrajectoryEvalRunMapper runMapper,
                                    TrajectoryEvalAlgorithmResultMapper algMapper,
                                    TrajectoryEvalWaybillResultMapper wbMapper,
                                    TrajectoryEvalAblationResultMapper abMapper,
                                    TrajectoryEvalParamResultMapper paramMapper,
                                    TrajectoryEvalErrorRecordMapper errMapper,
                                    ExperimentProperties props, VisualProperties vprops) {
        this.runMapper = runMapper;
        this.algMapper = algMapper;
        this.wbMapper = wbMapper;
        this.abMapper = abMapper;
        this.paramMapper = paramMapper;
        this.errMapper = errMapper;
        this.props = props;
        this.vprops = vprops;
    }

    // ------------------------------------------------------------------
    // 批次生命周期
    // ------------------------------------------------------------------

    /** 创建批次（status=RUNNING 落库），返回 run 实体（id 已生成） */
    public TrajectoryEvalRun startRun(String name) {
        TrajectoryEvalRun r = new TrajectoryEvalRun();
        r.setRunNo("TRAJECTORY-EVAL-" + LocalDateTime.now().format(DTF));
        r.setRunName(name);
        r.setDataSourceDir(vprops.getSource().getFullDataDir());
        r.setStatus("RUNNING");
        r.setStartedAt(LocalDateTime.now());
        r.setParameterJson(ExperimentConfigs.parameterJson(props));
        LocalDateTime now = LocalDateTime.now();
        r.setCreateTime(now);
        r.setUpdateTime(now);
        runMapper.insert(r);
        log.info("实验批次已创建: {} / {}", r.getRunNo(), name);
        return r;
    }

    /** 结束批次 */
    public void finishRun(TrajectoryEvalRun r, int waybillCount, long rawPointCount,
                          boolean success, String remark) {
        r.setWaybillCount(waybillCount);
        r.setRawPointCount(rawPointCount);
        r.setStatus(success ? "SUCCESS" : "FAILED");
        r.setFinishedAt(LocalDateTime.now());
        r.setDurationMs(System.currentTimeMillis()
                - java.sql.Timestamp.valueOf(r.getStartedAt()).getTime());
        r.setRemark(remark);
        r.setUpdateTime(LocalDateTime.now());
        runMapper.updateById(r);
    }

    /** 记录单个运单失败（不抛，继续批内下一个） */
    private void recordError(TrajectoryEvalRun run, String expCode, String waybillNo, Path file, Exception e) {
        try {
            TrajectoryEvalErrorRecord er = new TrajectoryEvalErrorRecord();
            er.setRunId(run.getId());
            er.setWaybillNo(waybillNo);
            er.setSourceFile(file == null ? null : file.toString());
            er.setExperimentCode(expCode);
            er.setErrorMessage(String.valueOf(e).length() > 900
                    ? String.valueOf(e).substring(0, 900) : String.valueOf(e));
            er.setCreateTime(LocalDateTime.now());
            errMapper.insert(er);
        } catch (Exception ignore) {
            log.warn("写失败记录异常(忽略): {}", ignore.toString());
        }
    }

    /** 按配置的 offset/limit 对文件列表切片（-1=全部） */
    private List<Path> slice(List<Path> files) {
        int offset = Math.max(0, props.getWaybillOffset());
        List<Path> list = new ArrayList<>(files);
        if (props.getWaybillLimit() > 0 && offset + props.getWaybillLimit() < list.size()) {
            return list.subList(offset, offset + props.getWaybillLimit());
        }
        return list.subList(offset, list.size());
    }

    private List<Path> sourceFiles() {
        Path dir = java.nio.file.Paths.get(vprops.getSource().getFullDataDir());
        try {
            return slice(TrackSourceUtil.listTrackFiles(dir));
        } catch (Exception e) {
            throw new IllegalStateException("全量轨迹源目录读取失败: " + dir + " -> " + e.getMessage());
        }
    }

    /** 进度打印间隔（运单数）。全量跑几十分钟，没有进度输出无法判断是否卡住、还要等多久 */
    private static final int PROGRESS_EVERY = 200;

    /**
     * 批量循环里定期打印进度：已处理/总数/百分比/已耗时/预计剩余。
     *
     * @param tag   实验代号（日志里用）
     * @param done  已处理运单数（含失败）
     * @param total 本批总运单数
     * @param t0    批次开始时间戳
     */
    private static void logProgress(String tag, int done, int total, long t0) {
        if (total <= 0 || done % PROGRESS_EVERY != 0) return;
        long sec = (System.currentTimeMillis() - t0) / 1000;
        String eta = done > 0 ? String.format("%.1f", sec / 60.0 * (total - done) / done) : "-";
        log.info("[{}] 进度 {}/{} ({}%)，已用 {}s，预计还需 {} 分钟",
                tag, done, total, Math.round(done * 100.0 / total), sec, eta);
    }

    // ------------------------------------------------------------------
    // 6.2 语义识别（静态同坐标停留识别结果与分析）
    // ------------------------------------------------------------------

    /**
     * 运行 6.2：仅做 清洗 + 静态停留识别，统计停留单元数/休息(≥30min)单元数/锚点数/时长分布。
     * 聚合结果写入批次 remark（JSON）；运单失败写 error_record。
     */
    public String runStopRecognition() {
        TrajectoryEvalRun run = startRun("6.2 语义识别-静态同坐标停留");
        String exp = "stop-recognition";
        List<Path> files = sourceFiles();
        int ok = 0;
        long rawTotal = 0;
        long stops = 0, rests = 0, traffic = 0, anchors = 0;
        double durSum = 0;
        long durMax = 0;
        StringBuilder sb = new StringBuilder();
        ExperimentConfig cfg = ExperimentConfigs.from(props);
        try {
            int done = 0;
            long t0 = System.currentTimeMillis();
            for (Path f : files) {
                String no = TrackSourceUtil.parseWaybillNo(f);
                logProgress("6.2", ++done, files.size(), t0);
                try {
                    Waybill w = TrackSourceUtil.parseFile(f);
                    // 只跑语义识别需要的最小链路：清洗 + 识别
                    com.logicompress.experiment.clean.DriftFilter df = new com.logicompress.experiment.clean.DriftFilter();
                    com.logicompress.experiment.clean.CleanedTrack cleaned = df.clean(w.rawPoints,
                            cfg.driftSpeedKph, cfg.breakGapS);
                    if (cleaned.points.size() < cfg.minPoints) continue; // 无效轨迹不计入
                    SemanticResult sem = new com.logicompress.experiment.semantic.SemanticAnalyzer()
                            .analyze(w, cleaned, cfg);
                    rawTotal += cleaned.points.size();
                    stops += sem.stops.size();
                    anchors += sem.anchors.size();
                    for (StopUnit u : sem.stops) {
                        if (u.durationS() >= com.logicompress.experiment.semantic.SemanticAnalyzer.REST_MIN_S) rests++;
                        else traffic++;
                        durSum += u.durationS();
                        if (u.durationS() > durMax) durMax = (long) u.durationS();
                    }
                    ok++;
                } catch (Exception e) {
                    recordError(run, exp, no, f, e);
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("processed_waybills", ok);
            m.put("total_raw_points_cleaned", rawTotal);
            m.put("total_stay_units", stops);
            m.put("rest_units_ge_30min", rests);
            m.put("traffic_units_lt_30min", traffic);
            m.put("total_anchor_pairs", anchors);
            m.put("avg_stay_duration_s", ok > 0 && stops > 0 ? String.format("%.1f", durSum / stops) : "0");
            m.put("max_stay_duration_s", durMax);
            m.put("note", "语义单元=REST(≥30min)/TRAFFIC; 锚点=停留单元首末+轨迹起止(与论文表6-2口径一致,但数据源为7709在线库非152实验集)");
            finishRun(run, ok, rawTotal, true, ExperimentConfigs.toJson(m));
            log.info("6.2 完成: {}", m);
            return run.getRunNo();
        } catch (Throwable ex) {
            finishRun(run, ok, rawTotal, false, String.valueOf(ex));
            throw ex;
        }
    }

    // ------------------------------------------------------------------
    // 6.3 有损层分级压缩对比（主实验：本文 + DP/DPS/TD-TR/Trajic）
    // ------------------------------------------------------------------

    /**
     * 主实验：每个运单跑完整链路（有损+无损+部分解压+基线），把本文与 4 种基线
     * 的 CR/PED/SED/SR/语义指标/字节/耗时写入 algorithm_result 与 waybill_result。
     * 对应论文 6.3~6.5 的数据主体与表 6-6/6-7/6-12。
     */
    public String runLossyCompressionCompare() {
        TrajectoryEvalRun run = startRun("6.3 有损层分级压缩对比(本文 vs DP/DPS/TD-TR/Trajic)");
        String exp = "lossy-compare";
        List<Path> files = sourceFiles();
        ExperimentConfig cfg = ExperimentConfigs.from(props);

        List<PipelineOutcome> proposed = new ArrayList<>();
        List<String> algoCodes = new ArrayList<>();
        algoCodes.add("PROPOSED");
        for (String c : CompressionPipeline.BASELINE_CODES) algoCodes.add(c);

        int ok = 0;
        long rawTotal = 0;
        long t0 = System.currentTimeMillis();
        try {
            int done = 0;
            for (Path f : files) {
                String no = TrackSourceUtil.parseWaybillNo(f);
                logProgress("6.3", ++done, files.size(), t0);
                try {
                    Waybill w = TrackSourceUtil.parseFile(f);
                    PipelineOutcome o = CompressionPipeline.process(w, cfg, true, true, true);
                    if (o == null) continue; // 无效轨迹（不足 minPoints）不计数
                    proposed.add(o);
                    rawTotal += o.nClean;
                    persistWaybillRow(run.getId(), o, "PROPOSED");
                    for (PipelineOutcome.Baseline b : o.baselines) {
                        persistBaselineRow(run.getId(), o, b);
                    }
                    ok++;
                } catch (Exception e) {
                    recordError(run, exp, no, f, e);
                }
            }
            // 按算法聚合（本文 + 4 基线）
            persistAlgorithmRow(run.getId(), aggregateProposed(proposed, ok, rawTotal));
            for (String c : CompressionPipeline.BASELINE_CODES) {
                persistAlgorithmRow(run.getId(), aggregateBaseline(proposed, c, ok, rawTotal));
            }
            finishRun(run, ok, rawTotal, true,
                    "处理运单 " + ok + "，耗时 " + (System.currentTimeMillis() - t0) / 1000 + "s");
            return run.getRunNo();
        } catch (Throwable ex) {
            finishRun(run, ok, rawTotal, false, String.valueOf(ex));
            throw ex;
        }
    }

    /** 本文(PROPOSED) 逐运单结果行 */
    private void persistWaybillRow(Long runId, PipelineOutcome o, String code) {
        TrajectoryEvalWaybillResult row = new TrajectoryEvalWaybillResult();
        row.setRunId(runId);
        row.setWaybillId(o.waybillId);
        row.setWaybillNo(o.waybillNo);
        row.setAlgorithmCode(code);
        row.setRawPointCount(o.nClean);
        row.setKeptPointCount(o.nKept);
        row.setChunkCount(o.store == null ? 0 : o.blocks);
        row.setCrLossy(o.crLossy);
        row.setCrLossless(o.store == null ? null : o.crLossless);
        row.setCrTotal(o.store == null ? null : o.crTotal);
        row.setPedAvg(o.pedAvgM);
        row.setPedMax(o.pedMaxM);
        row.setSedAvg(o.sedAvgM);
        row.setSedMax(o.sedMaxM);
        row.setSr(o.sr);
        row.setSemanticUnitCompleteRate(o.unitIntegrity);
        row.setStayDurationPreserveRate(o.dwellFidelity);
        row.setEncodeTimeMs(o.store == null ? null : (double) o.encodeMs);
        row.setDecodeTimeMs(o.store == null ? null : (double) o.decodeMs);
        row.setStorageBytes(o.store == null ? null : o.zippedBytes);
        row.setQueryTimeMs(o.store == null ? null : (double) o.queryMs);
        row.setPartialReadRatio(o.store == null ? null : o.partialBytesRatio);
        row.setStayCount(o.nStops);
        row.setAnchorCount(o.nAnchors);
        row.setCreateTime(LocalDateTime.now());
        wbMapper.insert(row);
    }

    /** 基线逐运单结果行（有损层指标；无损层不适用置空） */
    private void persistBaselineRow(Long runId, PipelineOutcome o, PipelineOutcome.Baseline b) {
        TrajectoryEvalWaybillResult row = new TrajectoryEvalWaybillResult();
        row.setRunId(runId);
        row.setWaybillId(o.waybillId);
        row.setWaybillNo(o.waybillNo);
        row.setAlgorithmCode(b.name);
        row.setRawPointCount(o.nClean);
        row.setKeptPointCount(b.nKept);
        row.setChunkCount(0);
        row.setCrLossy(b.crLossy);
        row.setCrLossless(null);
        row.setCrTotal(b.crLossy); // 基线无无损层，CR_total≈其有损压缩率（口径说明见文档）
        row.setPedAvg(b.pedAvgM);
        row.setPedMax(b.pedMaxM);
        row.setSedAvg(b.sedAvgM);
        row.setSedMax(b.sedMaxM);
        row.setSr(b.sr);
        row.setSemanticUnitCompleteRate(b.unitIntegrity);
        row.setStayDurationPreserveRate(b.dwellFidelity);
        row.setCreateTime(LocalDateTime.now());
        wbMapper.insert(row);
    }

    private TrajectoryEvalAlgorithmResult aggregateProposed(List<PipelineOutcome> os, int ok, long rawTotal) {
        TrajectoryEvalAlgorithmResult a = new TrajectoryEvalAlgorithmResult();
        a.setAlgorithmCode("PROPOSED");
        a.setAlgorithmName(ALG_NAMES.get("PROPOSED"));
        a.setWaybillCount(ok);
        a.setRawPointCount(rawTotal);
        a.setKeptPointCount(os.stream().mapToLong(o -> o.nKept).sum());
        a.setChunkCount(os.stream().mapToInt(o -> o.blocks).sum());
        a.setCrLossyAvg(avg(os, o -> o.crLossy));
        a.setCrLosslessAvg(avg(os, o -> o.crLossless));
        a.setCrTotalAvg(avg(os, o -> o.crTotal));
        a.setPedAvg(avg(os, o -> o.pedAvgM));
        a.setSedAvg(avg(os, o -> o.sedAvgM));
        a.setSrAvg(avg(os, o -> o.sr));
        a.setSemanticUnitCompleteRate(avg(os, o -> o.unitIntegrity));
        a.setStayDurationPreserveRate(avg(os, o -> o.dwellFidelity));
        a.setEncodeTimeMsAvg(avg(os, o -> o.encodeMs));
        a.setDecodeTimeMsAvg(avg(os, o -> o.decodeMs));
        a.setStorageBytes(os.stream().mapToLong(o -> o.zippedBytes).sum());
        a.setQueryTimeMsAvg(avg(os, o -> o.queryMs));
        a.setPartialReadRatioAvg(avg(os, o -> o.partialBytesRatio));
        a.setParameterJson(ExperimentConfigs.parameterJson(props));
        a.setCreateTime(LocalDateTime.now());
        return a;
    }

    private TrajectoryEvalAlgorithmResult aggregateBaseline(List<PipelineOutcome> os, String code,
                                                             int ok, long rawTotal) {
        List<PipelineOutcome.Baseline> bs = new ArrayList<>();
        for (PipelineOutcome o : os) {
            for (PipelineOutcome.Baseline b : o.baselines) {
                if (b.name.equals(code)) bs.add(b);
            }
        }
        TrajectoryEvalAlgorithmResult a = new TrajectoryEvalAlgorithmResult();
        a.setAlgorithmCode(code);
        a.setAlgorithmName(ALG_NAMES.get(code));
        a.setWaybillCount(bs.size());
        a.setRawPointCount(rawTotal);
        a.setKeptPointCount(bs.stream().mapToLong(b -> b.nKept).sum());
        a.setChunkCount(0);
        a.setCrLossyAvg(avgB(bs, b -> b.crLossy));
        a.setCrLosslessAvg(null);
        a.setCrTotalAvg(avgB(bs, b -> b.crLossy)); // 基线无无损层
        a.setPedAvg(avgB(bs, b -> b.pedAvgM));
        a.setSedAvg(avgB(bs, b -> b.sedAvgM));
        a.setSrAvg(avgB(bs, b -> b.sr));
        a.setSemanticUnitCompleteRate(avgB(bs, b -> b.unitIntegrity));
        a.setStayDurationPreserveRate(avgB(bs, b -> b.dwellFidelity));
        a.setParameterJson(ExperimentConfigs.parameterJson(props));
        a.setCreateTime(LocalDateTime.now());
        return a;
    }

    private void persistAlgorithmRow(TrajectoryEvalRun run, TrajectoryEvalAlgorithmResult a) {
        a.setRunId(run.getId());
        algMapper.insert(a);
    }

    private void persistAlgorithmRow(Long runId, TrajectoryEvalAlgorithmResult a) {
        a.setRunId(runId);
        algMapper.insert(a);
    }

    // ------------------------------------------------------------------
    // 6.4 无损层编码 / 6.5 部分解压（PROPOSED 单算法批，便于单独复跑复现）
    // ------------------------------------------------------------------

    /**
     * 6.4 无损层编码对比：只跑本文方法（有损+无损），输出字节/编码耗时等。
     * 数据其实在主实验(PROPOSED 行)已包含；本方法为"单独复跑 6.4"提供独立批次。
     */
    public String runLosslessEncoding() {
        return runProposedBatch("6.4 无损层：分块偏移量编码对比");
    }

    /**
     * 6.5 时间索引与部分解压：只跑本文方法并重点记录命中块/读取字节比/耗时/一致性。
     * 同上，为主实验之外的独立复跑批次。
     */
    public String runPartialDecompression() {
        return runProposedBatch("6.5 时间索引与部分解压");
    }

    private String runProposedBatch(String name) {
        TrajectoryEvalRun run = startRun(name);
        String exp = "proposed-batch";
        List<Path> files = sourceFiles();
        ExperimentConfig cfg = ExperimentConfigs.from(props);
        List<PipelineOutcome> os = new ArrayList<>();
        int ok = 0;
        long rawTotal = 0;
        try {
            int done = 0;
            long t0 = System.currentTimeMillis();
            for (Path f : files) {
                String no = TrackSourceUtil.parseWaybillNo(f);
                logProgress("6.4/6.5", ++done, files.size(), t0);
                try {
                    Waybill w = TrackSourceUtil.parseFile(f);
                    PipelineOutcome o = CompressionPipeline.process(w, cfg, true, true, false);
                    if (o == null) continue;
                    os.add(o);
                    rawTotal += o.nClean;
                    persistWaybillRow(run.getId(), o, "PROPOSED");
                    ok++;
                } catch (Exception e) {
                    recordError(run, exp, no, f, e);
                }
            }
            persistAlgorithmRow(run.getId(), aggregateProposed(os, ok, rawTotal));
            finishRun(run, ok, rawTotal, true, "PROPOSED 单算法批次");
            return run.getRunNo();
        } catch (Throwable ex) {
            finishRun(run, ok, rawTotal, false, String.valueOf(ex));
            throw ex;
        }
    }

    // ------------------------------------------------------------------
    // 6.6 消融实验
    // ------------------------------------------------------------------

    /**
     * 消融实验（论文表 6-17 口径，但本文主线已统一 10m）：
     * A0 完整方法 / A-TIGHT 收紧移动段容差(默认8m，见 ExperimentProperties) /
     * A2 去锚点强制保留 / A3 去无损层 / A4 去分块索引(整流单块编码)。
     */
    public String runAblation() {
        TrajectoryEvalRun run = startRun("6.6 消融实验(A0/A-TIGHT/A2/A3/A4)");
        String exp = "ablation";
        List<Path> files = sourceFiles();
        List<PipelineOutcome> os = new ArrayList<>();
        int ok = 0;
        long rawTotal = 0;
        try {
            // 先按主线配置跑一遍本文(PROPOSED)结果作为 A0 基线
            ExperimentConfig base = ExperimentConfigs.from(props);
            int done = 0;
            long t0 = System.currentTimeMillis();
            for (Path f : files) {
                String no = TrackSourceUtil.parseWaybillNo(f);
                logProgress("A0", ++done, files.size(), t0);
                try {
                    Waybill w = TrackSourceUtil.parseFile(f);
                    PipelineOutcome o = CompressionPipeline.process(w, base, true, true, false);
                    if (o == null) continue;
                    os.add(o);
                    rawTotal += o.nClean;
                    ok++;
                } catch (Exception e) {
                    recordError(run, exp, no, f, e);
                }
            }
            persistAblation(run, "A0", "完整方法(静态锚点+移动DP 10m+分块无损)", base,
                    os, ok, rawTotal, exp, "本文方法默认配置");

            // A-TIGHT：移动段容差收紧到 ablationTightToleranceM
            ExperimentConfig tight = ExperimentConfigs.from(props);
            tight.dpEpsNonKeyM = props.getAblationTightToleranceM();
            List<PipelineOutcome> tOs = rerun(files, tight, exp, run);
            persistAblation(run, "A-TIGHT", "收紧移动段容差(" + (int) tight.dpEpsNonKeyM + "m)",
                    tight, tOs, tOs.size(), sumClean(tOs), exp,
                    "统一10m口径下与A0重合，取更紧容差演示“保真升/压缩降”权衡");
            int okTight = tOs.size();

            // A2：去掉锚点强制保留
            ExperimentConfig noAnchor = ExperimentConfigs.from(props);
            noAnchor.noAnchorForce = true;
            List<PipelineOutcome> a2Os = rerun(files, noAnchor, exp, run);
            persistAblation(run, "A2", "去锚点强制保留", noAnchor, a2Os, a2Os.size(),
                    sumClean(a2Os), exp, "语义锚点不再强制保留(SR/单元完整率应显著下降)");

            // A3：去掉无损层（只存有损点数）
            List<PipelineOutcome> a3Os = rerunNoLossless(files, base, exp, run);
            persistAblation(run, "A3", "去无损层(整流DP) ", base, a3Os, a3Os.size(),
                    sumClean(a3Os), exp, "总压缩率退化为有损层压缩率");

            // A4：去分块索引——整流编码（整链单块）
            ExperimentConfig whole = ExperimentConfigs.from(props);
            whole.blockWindowS = Long.MAX_VALUE / 2; // 大块窗→整链一个块，时间索引失效
            List<PipelineOutcome> a4Os = rerun(files, whole, exp, run);
            persistAblation(run, "A4", "去分块索引(整流编码)", whole, a4Os, a4Os.size(),
                    sumClean(a4Os), exp, "单块整流编码，块长=整链，时间索引一行(不可部分解压)");

            finishRun(run, ok, rawTotal, true, "消融 5 变体完成");
            return run.getRunNo();
        } catch (Throwable ex) {
            finishRun(run, ok, rawTotal, false, String.valueOf(ex));
            throw ex;
        }
    }

    /** 用给定配置整体重跑一批运单（供消融变体，跑无损+部分解压以得到部分读取/查询耗时对比） */
    private List<PipelineOutcome> rerun(List<Path> files, ExperimentConfig cfg, String exp,
                                        TrajectoryEvalRun run) {
        List<PipelineOutcome> out = new ArrayList<>();
        int done = 0;
        long t0 = System.currentTimeMillis();
        for (Path f : files) {
            String no = TrackSourceUtil.parseWaybillNo(f);
            logProgress(exp, ++done, files.size(), t0);
            try {
                Waybill w = TrackSourceUtil.parseFile(f);
                PipelineOutcome o = CompressionPipeline.process(w, cfg, true, true, false);
                if (o != null) out.add(o);
            } catch (Exception e) {
                recordError(run, exp, no, f, e);
            }
        }
        return out;
    }

    /** 变体重跑（有损+无损，不做部分解压）：用于 DP 容差扫描(含基线对比) */
    private List<PipelineOutcome> rerunLosslessBaselines(List<Path> files, ExperimentConfig cfg,
                                                         String exp, TrajectoryEvalRun run) {
        List<PipelineOutcome> out = new ArrayList<>();
        int done = 0;
        long t0 = System.currentTimeMillis();
        for (Path f : files) {
            String no = TrackSourceUtil.parseWaybillNo(f);
            logProgress(exp, ++done, files.size(), t0);
            try {
                Waybill w = TrackSourceUtil.parseFile(f);
                PipelineOutcome o = CompressionPipeline.process(w, cfg, true, false, true);
                if (o != null) out.add(o);
            } catch (Exception e) {
                recordError(run, exp, no, f, e);
            }
        }
        return out;
    }

    private List<PipelineOutcome> rerunNoLossless(List<Path> files, ExperimentConfig cfg,
                                                  String exp, TrajectoryEvalRun run) {
        List<PipelineOutcome> out = new ArrayList<>();
        int done = 0;
        long t0 = System.currentTimeMillis();
        for (Path f : files) {
            String no = TrackSourceUtil.parseWaybillNo(f);
            logProgress(exp, ++done, files.size(), t0);
            try {
                Waybill w = TrackSourceUtil.parseFile(f);
                PipelineOutcome o = CompressionPipeline.process(w, cfg, false, false, false);
                if (o != null) out.add(o);
            } catch (Exception e) {
                recordError(run, exp, no, f, e);
            }
        }
        return out;
    }

    private void persistAblation(TrajectoryEvalRun run, String code, String name,
                                 ExperimentConfig cfg, List<PipelineOutcome> os, int ok,
                                 long rawTotal, String exp, String remark) {
        TrajectoryEvalAblationResult a = new TrajectoryEvalAblationResult();
        a.setRunId(run.getId());
        a.setAblationCode(code);
        a.setAblationName(name);
        a.setParameterJson(ExperimentConfigs.toJson(new LinkedHashMap<>(cfg.asMap())));
        a.setCrTotalAvg(avg(os, o -> o.crTotal));
        a.setCrLossyAvg(avg(os, o -> o.crLossy));
        a.setSrAvg(avg(os, o -> o.sr));
        a.setSemanticUnitCompleteRate(avg(os, o -> o.unitIntegrity));
        a.setStayDurationPreserveRate(avg(os, o -> o.dwellFidelity));
        a.setPedAvg(avg(os, o -> o.pedAvgM));
        a.setSedAvg(avg(os, o -> o.sedAvgM));
        a.setQueryTimeMsAvg(avg(os, o -> o.queryMs));
        a.setStorageBytes(os.stream().mapToLong(o -> o.zippedBytes).sum());
        a.setRemark(remark);
        a.setCreateTime(LocalDateTime.now());
        abMapper.insert(a);
    }

    // ------------------------------------------------------------------
    // 参数敏感性
    // ------------------------------------------------------------------

    /**
     * 参数敏感性：DP 容差扫描 5/10/15/20/30m（本文 PROPOSED 与各基线）+
     * 分块时长扫描 300/600/1800/3600s（本文）。结果写 trajectory_eval_param_result。
     */
    public String runParameterSensitivity() {
        TrajectoryEvalRun run = startRun("参数敏感性(DP容差扫描 + 分块时长扫描)");
        String exp = "param-sensitivity";
        List<Path> files = sourceFiles();
        double[] dpTols = {5, 10, 15, 20, 30};
        long[] blockWindows = {300, 600, 1800, 3600};

        // DP 容差扫描（记录 CR_lossy / SR / CR_total(本文才有)）
        try {
            for (double tol : dpTols) {
                ExperimentConfig cfg = ExperimentConfigs.from(props);
                cfg.dpEpsNonKeyM = tol;
                cfg.baselineDpM = tol;
                // DP 容差扫描关注有损层 CR/SR 随容差的变化，跑无损+4基线；部分解压指标对容差不敏感故不测
                List<PipelineOutcome> os = rerunLosslessBaselines(files, cfg, exp, run);
                // 本文行
                persistParamSlim(run, "dp_tolerance", fmt(tol), "PROPOSED", os);
                // 各基线行（SR 反映无语义基线在对应容差下的锚点丢失）
                for (String c : CompressionPipeline.BASELINE_CODES) {
                    persistParamBaseline(run, "dp_tolerance", fmt(tol), c, os);
                }
            }
            // 分块时长扫描
            for (long bs : blockWindows) {
                ExperimentConfig cfg = ExperimentConfigs.from(props);
                cfg.blockWindowS = bs;
                List<PipelineOutcome> os = rerun(files, cfg, exp, run);
                persistParam(run, "block_window", String.valueOf(bs), "PROPOSED", os);
            }
            finishRun(run, 0, 0, true, "参数敏感性完成(dp_tolerance×" + dpTols.length + "+block_window×" + blockWindows.length + ")");
            return run.getRunNo();
        } catch (Throwable ex) {
            finishRun(run, 0, 0, false, String.valueOf(ex));
            throw ex;
        }
    }

    /** DP 容差档本文行：只记有损/无损压缩率与保真误差，部分解压指标不适用置空 */
    private void persistParamSlim(TrajectoryEvalRun run, String type, String val, String code,
                                  List<PipelineOutcome> os) {
        TrajectoryEvalParamResult p = new TrajectoryEvalParamResult();
        p.setRunId(run.getId());
        p.setParamType(type);
        p.setParamValue(val);
        p.setAlgorithmCode(code);
        p.setCrLossyAvg(avg(os, o -> o.crLossy));
        p.setCrTotalAvg(avg(os, o -> o.crTotal));
        p.setPedAvg(avg(os, o -> o.pedAvgM));
        p.setSedAvg(avg(os, o -> o.sedAvgM));
        p.setSrAvg(avg(os, o -> o.sr));
        p.setCreateTime(LocalDateTime.now());
        paramMapper.insert(p);
    }

    private void persistParam(TrajectoryEvalRun run, String type, String val, String code,
                              List<PipelineOutcome> os) {        TrajectoryEvalParamResult p = new TrajectoryEvalParamResult();
        p.setRunId(run.getId());
        p.setParamType(type);
        p.setParamValue(val);
        p.setAlgorithmCode(code);
        p.setCrLossyAvg(avg(os, o -> o.crLossy));
        p.setCrTotalAvg(avg(os, o -> o.crTotal));
        p.setPedAvg(avg(os, o -> o.pedAvgM));
        p.setSedAvg(avg(os, o -> o.sedAvgM));
        p.setSrAvg(avg(os, o -> o.sr));
        p.setQueryTimeMsAvg(avg(os, o -> o.queryMs));
        p.setPartialReadRatioAvg(avg(os, o -> o.partialBytesRatio));
        p.setCreateTime(LocalDateTime.now());
        paramMapper.insert(p);
    }

    private void persistParamBaseline(TrajectoryEvalRun run, String type, String val, String code,
                                      List<PipelineOutcome> os) {
        List<Double> sr = new ArrayList<>();
        List<Double> cr = new ArrayList<>();
        for (PipelineOutcome o : os) {
            for (PipelineOutcome.Baseline b : o.baselines) {
                if (b.name.equals(code)) {
                    sr.add(b.sr);
                    cr.add(b.crLossy);
                }
            }
        }
        TrajectoryEvalParamResult p = new TrajectoryEvalParamResult();
        p.setRunId(run.getId());
        p.setParamType(type);
        p.setParamValue(val);
        p.setAlgorithmCode(code);
        p.setCrLossyAvg(sr.isEmpty() ? null : com.fkhwl.nfs.biz.experiment.Stats.mean(cr));
        p.setSrAvg(sr.isEmpty() ? null : com.fkhwl.nfs.biz.experiment.Stats.mean(sr));
        p.setCreateTime(LocalDateTime.now());
        paramMapper.insert(p);
    }

    // ------------------------------------------------------------------
    // 一键运行
    // ------------------------------------------------------------------

    /**
     * 依次执行 6.2~参数敏感性全部实验。每个实验独立建批次、独立落库、互不依赖；
     * 逐运单失败已内部隔离，不会中断总实验。注意：全量 7709 在线库上按顺序全部重跑耗时很长，
     * 建议先用 trajectory.experiment.waybill-limit 取子集跑通，或分别单独运行各 @Test。
     */
    public void runAllExperiments() {
        log.info("===== 开始一键运行全部第 6 章实验 =====");
        runStopRecognition();
        runLossyCompressionCompare();
        runLosslessEncoding();
        runPartialDecompression();
        runAblation();
        runParameterSensitivity();
        log.info("===== 全部实验完成 =====");
    }

    // ------------------------------------------------------------------
    // 统计辅助
    // ------------------------------------------------------------------

    private double avg(List<PipelineOutcome> os, java.util.function.ToDoubleFunction<PipelineOutcome> f) {
        if (os.isEmpty()) return 0;
        double s = 0;
        for (PipelineOutcome o : os) s += f.applyAsDouble(o);
        return com.fkhwl.nfs.biz.experiment.Stats.round4(s / os.size());
    }

    private double avgB(List<PipelineOutcome.Baseline> bs, java.util.function.ToDoubleFunction<PipelineOutcome.Baseline> f) {
        if (bs.isEmpty()) return 0;
        double s = 0;
        for (PipelineOutcome.Baseline b : bs) s += f.applyAsDouble(b);
        return com.fkhwl.nfs.biz.experiment.Stats.round4(s / bs.size());
    }

    private long sumClean(List<PipelineOutcome> os) {
        long s = 0;
        for (PipelineOutcome o : os) s += o.nClean;
        return s;
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
