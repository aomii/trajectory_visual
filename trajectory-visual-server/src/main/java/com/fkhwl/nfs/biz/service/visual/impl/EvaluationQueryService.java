package com.fkhwl.nfs.biz.service.visual.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fkhwl.nfs.biz.entity.po.TrajectoryEvalAblationResult;
import com.fkhwl.nfs.biz.entity.po.TrajectoryEvalAlgorithmResult;
import com.fkhwl.nfs.biz.entity.po.TrajectoryEvalErrorRecord;
import com.fkhwl.nfs.biz.entity.po.TrajectoryEvalParamResult;
import com.fkhwl.nfs.biz.entity.po.TrajectoryEvalRun;
import com.fkhwl.nfs.biz.entity.po.TrajectoryEvalWaybillResult;
import com.fkhwl.nfs.biz.experiment.CompressionPipeline;
import com.fkhwl.nfs.biz.experiment.Stats;
import com.fkhwl.nfs.biz.mapper.TrajectoryEvalAblationResultMapper;
import com.fkhwl.nfs.biz.mapper.TrajectoryEvalAlgorithmResultMapper;
import com.fkhwl.nfs.biz.mapper.TrajectoryEvalErrorRecordMapper;
import com.fkhwl.nfs.biz.mapper.TrajectoryEvalParamResultMapper;
import com.fkhwl.nfs.biz.mapper.TrajectoryEvalRunMapper;
import com.fkhwl.nfs.biz.mapper.TrajectoryEvalWaybillResultMapper;
import com.fkhwl.nfs.common.ApiException;
import com.fkhwl.nfs.config.ExperimentProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第 6 章评估结果查询服务（数据源 = MySQL 结果表，禁止硬编码论文示例数字）。
 * Dashboard 首屏建议走一次 {@link #dashboard} 聚合；下钻用本类其余方法。
 */
@Service
public class EvaluationQueryService {

    private static final List<String> ALG_ORDER = java.util.Arrays.asList(
            "PROPOSED", "DP", "DPS", "TD-TR", "Trajic");

    /** 对比基线代码：某批次里出现这些，说明它跑了完整的算法对比（6.3 主实验） */
    private static final List<String> BASELINE_CODES = java.util.Arrays.asList(
            CompressionPipeline.BASELINE_CODES);

    private final TrajectoryEvalRunMapper runMapper;
    private final TrajectoryEvalAlgorithmResultMapper algMapper;
    private final TrajectoryEvalWaybillResultMapper wbMapper;
    private final TrajectoryEvalAblationResultMapper abMapper;
    private final TrajectoryEvalParamResultMapper paramMapper;
    private final TrajectoryEvalErrorRecordMapper errMapper;
    /** 分块时长等运行参数：Dashboard 上"默认分片时长"标注线取自这里，不写死 */
    private final ExperimentProperties eprops;

    public EvaluationQueryService(TrajectoryEvalRunMapper runMapper,
                                  TrajectoryEvalAlgorithmResultMapper algMapper,
                                  TrajectoryEvalWaybillResultMapper wbMapper,
                                  TrajectoryEvalAblationResultMapper abMapper,
                                  TrajectoryEvalParamResultMapper paramMapper,
                                  TrajectoryEvalErrorRecordMapper errMapper,
                                  ExperimentProperties eprops) {
        this.runMapper = runMapper;
        this.algMapper = algMapper;
        this.wbMapper = wbMapper;
        this.abMapper = abMapper;
        this.paramMapper = paramMapper;
        this.errMapper = errMapper;
        this.eprops = eprops;
    }

    /** 全部实验批次（时间倒序） */
    public List<TrajectoryEvalRun> runs() {
        return runMapper.selectList(new LambdaQueryWrapper<TrajectoryEvalRun>()
                .orderByDesc(TrajectoryEvalRun::getId));
    }

    /**
     * 解析批次：null → 默认主实验批。
     *
     * <p>默认批的选取规则（2026-09-11 修正）：**优先"含完整算法对比"的批次**，即同时写了
     * PROPOSED 与至少一个基线的批次（6.3 主实验）。
     * 原来只按"含任意算法行"取最新批，结果 6.4/6.5 这类只写 PROPOSED 单算法行的批次
     * 会盖掉 6.3，导致评估中心与算法对比页只剩一条线、看不到算法间对比。
     * 找不到完整对比批时再退而求其次取"含任意算法行"的最新批，保证页面不至于全空。
     */
    public TrajectoryEvalRun resolveRun(Long runId) {
        if (runId != null) {
            TrajectoryEvalRun run = runMapper.selectById(runId);
            if (run == null) throw new ApiException(404, "实验批次不存在: " + runId);
            return run;
        }
        List<TrajectoryEvalRun> success = runMapper.selectList(
                new LambdaQueryWrapper<TrajectoryEvalRun>()
                        .eq(TrajectoryEvalRun::getStatus, "SUCCESS")
                        .orderByDesc(TrajectoryEvalRun::getId));
        // ① 最新一个"跑了完整对比（含基线）"的成功批 = 主实验批
        for (TrajectoryEvalRun r : success) {
            Long baseCnt = algMapper.selectCount(
                    new LambdaQueryWrapper<TrajectoryEvalAlgorithmResult>()
                            .eq(TrajectoryEvalAlgorithmResult::getRunId, r.getId())
                            .in(TrajectoryEvalAlgorithmResult::getAlgorithmCode, BASELINE_CODES));
            if (baseCnt != null && baseCnt > 0) return r;
        }
        // ② 退守：任何含算法行的成功批（单算法，页面不空但无对比）
        for (TrajectoryEvalRun r : success) {
            Long cnt = algMapper.selectCount(
                    new LambdaQueryWrapper<TrajectoryEvalAlgorithmResult>()
                            .eq(TrajectoryEvalAlgorithmResult::getRunId, r.getId()));
            if (cnt != null && cnt > 0) return r;
        }
        if (!success.isEmpty()) return success.get(0);
        throw new ApiException(404, "没有可用的实验批次（status=SUCCESS）。请先运行第 6 章实验测试类 TrajectoryChapter6ExperimentTest");
    }

    public List<TrajectoryEvalAlgorithmResult> algorithmsOf(Long runId) {
        TrajectoryEvalRun run = resolveRun(runId);
        List<TrajectoryEvalAlgorithmResult> list = algMapper.selectList(
                new LambdaQueryWrapper<TrajectoryEvalAlgorithmResult>().eq(TrajectoryEvalAlgorithmResult::getRunId, run.getId()));
        list.sort((a, b) -> Integer.compare(ALG_ORDER.indexOf(a.getAlgorithmCode()) < 0 ? 99
                : ALG_ORDER.indexOf(a.getAlgorithmCode()),
                ALG_ORDER.indexOf(b.getAlgorithmCode()) < 0 ? 99 : ALG_ORDER.indexOf(b.getAlgorithmCode())));
        return list;
    }

    public List<TrajectoryEvalWaybillResult> waybillOf(Long runId, String algorithmCode) {
        TrajectoryEvalRun run = resolveRun(runId);
        return wbMapper.selectList(new LambdaQueryWrapper<TrajectoryEvalWaybillResult>()
                .eq(TrajectoryEvalWaybillResult::getRunId, run.getId())
                .eq(algorithmCode != null && !algorithmCode.isEmpty(),
                        TrajectoryEvalWaybillResult::getAlgorithmCode, algorithmCode)
                .orderByDesc(TrajectoryEvalWaybillResult::getWaybillId));
    }

    /**
     * 消融实验行。**与 dashboard() 同口径**：默认批（6.3 主实验）里没有消融数据时，
     * 回退到"最近一个含消融数据的批次"（6.6）。否则 /evaluation/ablation 直接返回空，
     * 消融页就没图了（2026-09-11 用户报障）。
     */
    public List<TrajectoryEvalAblationResult> ablationOf(Long runId) {
        TrajectoryEvalRun run = resolveRun(runId);
        return ablationRowsLatest(run.getId());
    }

    /**
     * 参数敏感性行（按 paramType）。
     * 同 {@link #ablationOf}：默认批里没有该类型数据时，回退到最近含该类型数据的批次。
     */
    public List<TrajectoryEvalParamResult> paramOf(Long runId, String paramType) {
        TrajectoryEvalRun run = resolveRun(runId);
        if (paramType == null || paramType.isEmpty()) {
            // 不指定类型 = 该批全部参数行（不做回退，避免把不同实验的参数混在一起）
            return paramMapper.selectList(new LambdaQueryWrapper<TrajectoryEvalParamResult>()
                    .eq(TrajectoryEvalParamResult::getRunId, run.getId())
                    .orderByAsc(TrajectoryEvalParamResult::getParamType)
                    .orderByAsc(TrajectoryEvalParamResult::getParamValue));
        }
        return paramRowsLatest(run.getId(), paramType);
    }

    public List<TrajectoryEvalErrorRecord> errorsOf(Long runId) {
        TrajectoryEvalRun run = resolveRun(runId);
        return errMapper.selectList(new LambdaQueryWrapper<TrajectoryEvalErrorRecord>()
                .eq(TrajectoryEvalErrorRecord::getRunId, run.getId()));
    }

    /** 单运单在最近/指定批次下、各算法的结果（页面四下钻） */
    public List<TrajectoryEvalWaybillResult> waybillAcrossAlgorithms(Long runId, long waybillId) {
        TrajectoryEvalRun run = resolveRun(runId);
        List<TrajectoryEvalWaybillResult> list = wbMapper.selectList(
                new LambdaQueryWrapper<TrajectoryEvalWaybillResult>()
                        .eq(TrajectoryEvalWaybillResult::getRunId, run.getId())
                        .eq(TrajectoryEvalWaybillResult::getWaybillId, waybillId));
        list.sort((a, b) -> Integer.compare(ALG_ORDER.indexOf(a.getAlgorithmCode()) < 0 ? 99
                : ALG_ORDER.indexOf(a.getAlgorithmCode()),
                ALG_ORDER.indexOf(b.getAlgorithmCode()) < 0 ? 99 : ALG_ORDER.indexOf(b.getAlgorithmCode())));
        return list;
    }

    // ------------------------------------------------------------------
    // Dashboard 聚合
    // ------------------------------------------------------------------

    /**
     * 答辩 Dashboard 一次聚合返回。返回结构含 run/kpi/algorithmComparison/semanticComparison/
     * errorComparison/storageQueryComparison/ablation/parameterSensitivity/conclusions/dataStatus。
     */
    public Map<String, Object> dashboard(Long runId) {
        TrajectoryEvalRun run = resolveRun(runId);
        List<TrajectoryEvalAlgorithmResult> algs = algMapper.selectList(
                new LambdaQueryWrapper<TrajectoryEvalAlgorithmResult>().eq(TrajectoryEvalAlgorithmResult::getRunId, run.getId()));

        Map<String, Object> root = new LinkedHashMap<>();
        // ---- run ----
        Map<String, Object> runM = new LinkedHashMap<>();
        runM.put("id", run.getId());
        runM.put("runNo", run.getRunNo());
        runM.put("runName", run.getRunName());
        runM.put("status", run.getStatus());
        runM.put("startedAt", run.getStartedAt() == null ? null : run.getStartedAt().toString());
        runM.put("finishedAt", run.getFinishedAt() == null ? null : run.getFinishedAt().toString());
        runM.put("waybillCount", run.getWaybillCount());
        runM.put("rawPointCount", run.getRawPointCount());
        runM.put("durationMs", run.getDurationMs());
        runM.put("dataSourceDir", run.getDataSourceDir());
        runM.put("remark", run.getRemark());
        root.put("run", runM);

        // ---- kpi（本文 PROPOSED 聚合行）----
        TrajectoryEvalAlgorithmResult proposed = algs.stream()
                .filter(a -> "PROPOSED".equals(a.getAlgorithmCode()))
                .findFirst().orElse(null);
        Map<String, Object> kpi = new LinkedHashMap<>();
        if (proposed != null) {
            kpi.put("waybillCount", proposed.getWaybillCount());
            kpi.put("rawPointCount", proposed.getRawPointCount());
            kpi.put("keptPointCount", proposed.getKeptPointCount());
            kpi.put("chunkCount", proposed.getChunkCount());
            kpi.put("crTotalAvg", proposed.getCrTotalAvg());
            kpi.put("crLossyAvg", proposed.getCrLossyAvg());
            kpi.put("crLosslessAvg", proposed.getCrLosslessAvg());
            kpi.put("srAvg", proposed.getSrAvg());
            kpi.put("semanticUnitCompleteRate", proposed.getSemanticUnitCompleteRate());
            kpi.put("stayDurationPreserveRate", proposed.getStayDurationPreserveRate());
            kpi.put("partialReadRatioAvg", proposed.getPartialReadRatioAvg());
            kpi.put("queryTimeMsAvg", proposed.getQueryTimeMsAvg());
            kpi.put("encodeTimeMsAvg", proposed.getEncodeTimeMsAvg());
            kpi.put("decodeTimeMsAvg", proposed.getDecodeTimeMsAvg());
            kpi.put("storageBytes", proposed.getStorageBytes());
            kpi.put("pedAvg", proposed.getPedAvg());
            kpi.put("sedAvg", proposed.getSedAvg());
        }
        kpi.put("dataStatus", proposed == null ? "EMPTY" : "OK");
        root.put("kpi", kpi);

        // ---- algorithmComparison（含统计：mean/median/min/max 由明细实时算）----
        List<Map<String, Object>> algComp = new ArrayList<>();
        for (TrajectoryEvalAlgorithmResult a : algs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("algorithmCode", a.getAlgorithmCode());
            m.put("algorithmName", a.getAlgorithmName());
            m.put("waybillCount", a.getWaybillCount());
            m.put("crTotalAvg", a.getCrTotalAvg());
            m.put("crLossyAvg", a.getCrLossyAvg());
            m.put("crLosslessAvg", a.getCrLosslessAvg());
            m.put("pedAvg", a.getPedAvg());
            m.put("sedAvg", a.getSedAvg());
            m.put("srAvg", a.getSrAvg());
            m.put("semanticUnitCompleteRate", a.getSemanticUnitCompleteRate());
            m.put("stayDurationPreserveRate", a.getStayDurationPreserveRate());
            m.put("encodeTimeMsAvg", a.getEncodeTimeMsAvg());
            m.put("decodeTimeMsAvg", a.getDecodeTimeMsAvg());
            m.put("queryTimeMsAvg", a.getQueryTimeMsAvg());
            m.put("storageBytes", a.getStorageBytes());
            m.put("partialReadRatioAvg", a.getPartialReadRatioAvg());
            // 明细统计：median/min/max（同一运单口径）
            List<TrajectoryEvalWaybillResult> wbs = wbMapper.selectList(
                    new LambdaQueryWrapper<TrajectoryEvalWaybillResult>()
                            .eq(TrajectoryEvalWaybillResult::getRunId, run.getId())
                            .eq(TrajectoryEvalWaybillResult::getAlgorithmCode, a.getAlgorithmCode()));
            m.put("stats", waybillStats(wbs));
            algComp.add(m);
        }
        algComp.sort((x, y) -> Integer.compare(rank(x), rank(y)));
        root.put("algorithmComparison", algComp);

        // ---- semanticComparison / errorComparison（多算法，供双视图）----
        root.put("semanticComparison", buildSemantic(algs));
        root.put("errorComparison", buildError(algs));

        // ---- storageQueryComparison（block 时长扫描；当前批没有则回退最新含该数据的批次）----
        List<Map<String, Object>> storage = new ArrayList<>();
        List<TrajectoryEvalParamResult> blk = paramRowsLatest(run.getId(), "block_window");
        for (TrajectoryEvalParamResult p : blk) {
            if (!"PROPOSED".equals(p.getAlgorithmCode())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("blockWindowS", Long.parseLong(p.getParamValue()));
            m.put("crTotalAvg", p.getCrTotalAvg());
            m.put("crLossyAvg", p.getCrLossyAvg());
            m.put("pedAvg", p.getPedAvg());
            m.put("sedAvg", p.getSedAvg());
            m.put("srAvg", p.getSrAvg());
            m.put("queryTimeMsAvg", p.getQueryTimeMsAvg());
            m.put("partialReadRatioAvg", p.getPartialReadRatioAvg());
            storage.add(m);
        }
        Map<String, Object> storageQuery = new LinkedHashMap<>();
        storageQuery.put("series", storage);
        // 默认分片时长取自运行配置（2026-09-11 起为 3600s=1h），前端据此在扫描曲线上标"默认值"
        storageQuery.put("defaultBlockWindowS", eprops.getBlockWindowS());
        root.put("storageQueryComparison", storageQuery);

        // ---- ablation（当前批没有则回退最新含消融数据的批次）----
        List<TrajectoryEvalAblationResult> abs = ablationRowsLatest(run.getId());
        List<Map<String, Object>> abList = new ArrayList<>();
        for (TrajectoryEvalAblationResult a : abs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ablationCode", a.getAblationCode());
            m.put("ablationName", a.getAblationName());
            m.put("crTotalAvg", a.getCrTotalAvg());
            m.put("crLossyAvg", a.getCrLossyAvg());
            m.put("srAvg", a.getSrAvg());
            m.put("semanticUnitCompleteRate", a.getSemanticUnitCompleteRate());
            m.put("stayDurationPreserveRate", a.getStayDurationPreserveRate());
            m.put("pedAvg", a.getPedAvg());
            m.put("sedAvg", a.getSedAvg());
            m.put("queryTimeMsAvg", a.getQueryTimeMsAvg());
            m.put("storageBytes", a.getStorageBytes());
            m.put("remark", a.getRemark());
            abList.add(m);
        }
        root.put("ablation", abList);

        // ---- parameterSensitivity ----
        Map<String, Object> ps = new LinkedHashMap<>();
        ps.put("dpTolerance", buildDpTolerance(run.getId()));
        ps.put("blockWindow", blkRows(run.getId()));
        root.put("parameterSensitivity", ps);

        // ---- conclusions（动态生成，≤3 条，基于真实返回值）----
        root.put("conclusions", buildConclusions(algs, proposed, storage));

        root.put("dataStatus", proposed == null ? "EMPTY" : "SUCCESS");
        return root;
    }

    private List<Map<String, Object>> buildSemantic(List<TrajectoryEvalAlgorithmResult> algs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TrajectoryEvalAlgorithmResult a : algs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("algorithmCode", a.getAlgorithmCode());
            m.put("srAvg", a.getSrAvg());
            m.put("semanticUnitCompleteRate", a.getSemanticUnitCompleteRate());
            m.put("stayDurationPreserveRate", a.getStayDurationPreserveRate());
            out.add(m);
        }
        out.sort((x, y) -> Integer.compare(rank(x), rank(y)));
        return out;
    }

    private List<Map<String, Object>> buildError(List<TrajectoryEvalAlgorithmResult> algs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TrajectoryEvalAlgorithmResult a : algs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("algorithmCode", a.getAlgorithmCode());
            m.put("pedAvg", a.getPedAvg());
            m.put("sedAvg", a.getSedAvg());
            out.add(m);
        }
        out.sort((x, y) -> Integer.compare(rank(x), rank(y)));
        return out;
    }

    // ------------------------------------------------------------------
    // 数据集回退：指定批次若无某类结果(消融/参数)，回退到"最近包含该类结果"的 SUCCESS 批次
    // （保证默认首屏一次聚合能看到各实验图；切换批次后仍精确展示该批次内容）
    // ------------------------------------------------------------------

    private List<TrajectoryEvalParamResult> paramRowsLatest(Long runId, String type) {
        List<TrajectoryEvalParamResult> rows = paramMapper.selectList(
                new LambdaQueryWrapper<TrajectoryEvalParamResult>()
                        .eq(TrajectoryEvalParamResult::getRunId, runId)
                        .eq(TrajectoryEvalParamResult::getParamType, type)
                        .orderByAsc(TrajectoryEvalParamResult::getParamValue));
        if (!rows.isEmpty()) return rows;
        TrajectoryEvalParamResult last = paramMapper.selectOne(
                new LambdaQueryWrapper<TrajectoryEvalParamResult>()
                        .eq(TrajectoryEvalParamResult::getParamType, type)
                        .orderByDesc(TrajectoryEvalParamResult::getId).last("limit 1"));
        if (last == null) return rows;
        return paramMapper.selectList(new LambdaQueryWrapper<TrajectoryEvalParamResult>()
                .eq(TrajectoryEvalParamResult::getRunId, last.getRunId())
                .eq(TrajectoryEvalParamResult::getParamType, type)
                .orderByAsc(TrajectoryEvalParamResult::getParamValue));
    }

    private List<TrajectoryEvalAblationResult> ablationRowsLatest(Long runId) {
        List<TrajectoryEvalAblationResult> rows = abMapper.selectList(
                new LambdaQueryWrapper<TrajectoryEvalAblationResult>()
                        .eq(TrajectoryEvalAblationResult::getRunId, runId));
        if (!rows.isEmpty()) return rows;
        TrajectoryEvalAblationResult last = abMapper.selectOne(
                new LambdaQueryWrapper<TrajectoryEvalAblationResult>()
                        .orderByDesc(TrajectoryEvalAblationResult::getId).last("limit 1"));
        if (last == null) return rows;
        return abMapper.selectList(new LambdaQueryWrapper<TrajectoryEvalAblationResult>()
                .eq(TrajectoryEvalAblationResult::getRunId, last.getRunId()));
    }

    private List<Map<String, Object>> buildDpTolerance(Long runId) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<TrajectoryEvalParamResult> rows = paramRowsLatest(runId, "dp_tolerance");
        for (TrajectoryEvalParamResult p : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("paramValue", p.getParamValue());
            m.put("algorithmCode", p.getAlgorithmCode());
            m.put("crLossyAvg", p.getCrLossyAvg());
            m.put("crTotalAvg", p.getCrTotalAvg());
            m.put("srAvg", p.getSrAvg());
            m.put("pedAvg", p.getPedAvg());
            m.put("sedAvg", p.getSedAvg());
            m.put("queryTimeMsAvg", p.getQueryTimeMsAvg());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> blkRows(Long runId) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<TrajectoryEvalParamResult> rows = paramRowsLatest(runId, "block_window");
        for (TrajectoryEvalParamResult p : rows) {
            if (!"PROPOSED".equals(p.getAlgorithmCode())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("paramValue", p.getParamValue());
            m.put("crTotalAvg", p.getCrTotalAvg());
            m.put("srAvg", p.getSrAvg());
            m.put("queryTimeMsAvg", p.getQueryTimeMsAvg());
            m.put("partialReadRatioAvg", p.getPartialReadRatioAvg());
            m.put("pedAvg", p.getPedAvg());
            m.put("sedAvg", p.getSedAvg());
            out.add(m);
        }
        return out;
    }

    /** 每条算法明细统计：mean/median/min/max（对 CR_total、CR_lossy、PED、SED、SR、查询耗时） */
    private Map<String, Object> waybillStats(List<TrajectoryEvalWaybillResult> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("crTotal", statMap(col(rows, r -> r.getCrTotal())));
        m.put("crLossy", statMap(col(rows, r -> r.getCrLossy())));
        m.put("pedAvg", statMap(col(rows, r -> r.getPedAvg())));
        m.put("sedAvg", statMap(col(rows, r -> r.getSedAvg())));
        m.put("sr", statMap(col(rows, r -> r.getSr())));
        m.put("queryMs", statMap(col(rows, r -> r.getQueryTimeMs())));
        return m;
    }

    private Map<String, Object> statMap(List<Double> vals) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mean", vals.isEmpty() ? null : Stats.mean(vals));
        m.put("median", vals.isEmpty() ? null : Stats.median(vals));
        m.put("min", vals.isEmpty() ? null : Stats.min(vals));
        m.put("max", vals.isEmpty() ? null : Stats.max(vals));
        return m;
    }

    private List<Double> col(List<TrajectoryEvalWaybillResult> rows,
                             java.util.function.Function<TrajectoryEvalWaybillResult, Double> f) {
        List<Double> out = new ArrayList<>(rows.size());
        for (TrajectoryEvalWaybillResult r : rows) {
            Double v = f.apply(r);
            if (v != null) out.add(v);
        }
        return out;
    }

    /** 结论摘要：基于真实返回值动态生成 ≤3 条 */
    private List<String> buildConclusions(List<TrajectoryEvalAlgorithmResult> algs,
                                          TrajectoryEvalAlgorithmResult proposed,
                                          List<Map<String, Object>> storage) {
        List<String> out = new ArrayList<>();
        if (proposed == null) {
            out.add("当前批次暂无可用结论（无 PROPOSED 算法结果行）");
            return out;
        }
        // 1. CR_total 最高
        double best = Double.MIN_VALUE;
        String bestAlg = "";
        for (TrajectoryEvalAlgorithmResult a : algs) {
            if (a.getCrTotalAvg() == null) continue;
            if (a.getCrTotalAvg() > best) {
                best = a.getCrTotalAvg();
                bestAlg = a.getAlgorithmCode();
            }
        }
        if ("PROPOSED".equals(bestAlg)) {
            out.add(String.format("本文方法总压缩率 CR_total=%.2f，为全部算法中最高", proposed.getCrTotalAvg()));
        } else if (proposed.getCrTotalAvg() != null) {
            out.add(String.format("本文方法 CR_total=%.2f（基线最高 %s=%.2f）", proposed.getCrTotalAvg(), bestAlg, best));
        }
        // 2. SR 语义保真
        if (proposed.getSrAvg() != null && Math.abs(proposed.getSrAvg() - 1.0) < 1e-9) {
            out.add(String.format("语义点保留率 SR=%.1f%%（100%%），语义单元完整率=%.1f%%", proposed.getSrAvg() * 100,
                    proposed.getSemanticUnitCompleteRate() == null ? 100 : proposed.getSemanticUnitCompleteRate() * 100));
        } else if (proposed.getSrAvg() != null) {
            out.add(String.format("语义点保留率 SR=%.1f%%", proposed.getSrAvg() * 100));
        }
        // 3. 部分解压读取比例 < 全量
        if (proposed.getPartialReadRatioAvg() != null) {
            out.add(String.format("按时间窗部分解压平均仅读取 %.1f%% 的存储字节，即可返回窗口内轨迹", proposed.getPartialReadRatioAvg() * 100));
        }
        while (out.size() > 3) out.remove(out.size() - 1);
        return out;
    }

    private int rank(Map<String, Object> m) {
        String c = String.valueOf(m.get("algorithmCode"));
        int i = ALG_ORDER.indexOf(c);
        return i < 0 ? 99 : i;
    }
}
