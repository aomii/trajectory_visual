package com.fkhwl.nfs.biz.controller.visual;

import com.fkhwl.nfs.biz.entity.po.TrajectoryEvalAblationResult;
import com.fkhwl.nfs.biz.entity.po.TrajectoryEvalAlgorithmResult;
import com.fkhwl.nfs.biz.entity.po.TrajectoryEvalErrorRecord;
import com.fkhwl.nfs.biz.entity.po.TrajectoryEvalParamResult;
import com.fkhwl.nfs.biz.entity.po.TrajectoryEvalRun;
import com.fkhwl.nfs.biz.entity.po.TrajectoryEvalWaybillResult;
import com.fkhwl.nfs.biz.service.visual.impl.EvaluationQueryService;
import com.fkhwl.nfs.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第 6 章评估结果查询（数据来自 MySQL 实验表，禁止硬编码论文示例数字）。
 * 默认读取最近一次 status=SUCCESS 批次；支持 ?runId=xxx 查历史。
 */
@Tag(name = "可视化-评估")
@RestController
@RequestMapping("/api/visual/evaluation")
public class VisualEvaluationController {

    private final EvaluationQueryService svc;

    public VisualEvaluationController(EvaluationQueryService svc) {
        this.svc = svc;
    }

    @Operation(summary = "实验批次列表")
    @GetMapping("/runs")
    public Result<List<TrajectoryEvalRun>> runs() {
        return Result.ok(svc.runs());
    }

    @Operation(summary = "最近成功批次指标汇总")
    @GetMapping("/summary")
    public Result<Map<String, Object>> summary(@RequestParam(required = false) Long runId) {
        Map<String, Object> d = svc.dashboard(runId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", d.get("run"));
        out.put("kpi", d.get("kpi"));
        out.put("conclusions", d.get("conclusions"));
        out.put("dataStatus", d.get("dataStatus"));
        return Result.ok(out);
    }

    @Operation(summary = "算法列表（聚合行）")
    @GetMapping("/algorithms")
    public Result<List<TrajectoryEvalAlgorithmResult>> algorithms(@RequestParam(required = false) Long runId) {
        return Result.ok(svc.algorithmsOf(runId));
    }

    @Operation(summary = "算法对比（聚合 + 明细统计）")
    @GetMapping("/algorithm-compare")
    public Result<Map<String, Object>> algorithmCompare(@RequestParam(required = false) Long runId) {
        Map<String, Object> d = svc.dashboard(runId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", d.get("run"));
        out.put("algorithmComparison", d.get("algorithmComparison"));
        out.put("dataStatus", d.get("dataStatus"));
        return Result.ok(out);
    }

    @Operation(summary = "单运单×算法结果（页面四下钻）")
    @GetMapping("/waybill/{waybillId}")
    public Result<List<TrajectoryEvalWaybillResult>> waybill(@PathVariable Long waybillId,
                                                             @RequestParam(required = false) Long runId) {
        return Result.ok(svc.waybillAcrossAlgorithms(runId, waybillId));
    }

    @Operation(summary = "消融实验")
    @GetMapping("/ablation")
    public Result<List<TrajectoryEvalAblationResult>> ablation(@RequestParam(required = false) Long runId) {
        return Result.ok(svc.ablationOf(runId));
    }

    @Operation(summary = "部分解压性能对比（本文 PROPOSED）")
    @GetMapping("/partial-decompression")
    public Result<Map<String, Object>> partial(@RequestParam(required = false) Long runId) {
        Map<String, Object> d = svc.dashboard(runId);
        List<TrajectoryEvalParamResult> blk = svc.paramOf(runId, "block_window");
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> kpi = (Map<String, Object>) d.get("kpi");
        out.put("run", d.get("run"));
        out.put("kpi", kpi);
        out.put("partialReadRatioAvg", kpi.get("partialReadRatioAvg"));
        out.put("queryTimeMsAvg", kpi.get("queryTimeMsAvg"));
        out.put("decodeTimeMsAvg", kpi.get("decodeTimeMsAvg"));
        // block 时长扫描曲线数据
        java.util.List<Map<String, Object>> series = new java.util.ArrayList<>();
        for (TrajectoryEvalParamResult p : blk) {
            if (!"PROPOSED".equals(p.getAlgorithmCode())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("blockWindowS", Long.parseLong(p.getParamValue()));
            m.put("partialReadRatioAvg", p.getPartialReadRatioAvg());
            m.put("queryTimeMsAvg", p.getQueryTimeMsAvg());
            m.put("crTotalAvg", p.getCrTotalAvg());
            series.add(m);
        }
        out.put("series", series);
        out.put("dataStatus", d.get("dataStatus"));
        return Result.ok(out);
    }

    @Operation(summary = "参数敏感性（DP 容差 + 分块时长扫描）")
    @GetMapping("/parameter-sensitivity")
    public Result<Map<String, Object>> paramSensitivity(@RequestParam(required = false) Long runId) {
        Map<String, Object> d = svc.dashboard(runId);
        return Result.ok((Map<String, Object>) d.get("parameterSensitivity"));
    }

    @Operation(summary = "失败记录")
    @GetMapping("/errors")
    public Result<List<TrajectoryEvalErrorRecord>> errors(@RequestParam(required = false) Long runId) {
        return Result.ok(svc.errorsOf(runId));
    }

    @Operation(summary = "Dashboard 一次聚合（答辩页首屏推荐）")
    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard(@RequestParam(required = false) Long runId) {
        return Result.ok(svc.dashboard(runId));
    }
}
