package com.fkhwl.nfs.biz.controller.visual;

import com.fkhwl.nfs.biz.entity.dto.visual.ChunkVO;
import com.fkhwl.nfs.biz.entity.dto.visual.CompressResultVO;
import com.fkhwl.nfs.biz.entity.dto.visual.CompressTaskVO;
import com.fkhwl.nfs.biz.entity.dto.visual.TrackViewVO;
import com.fkhwl.nfs.biz.entity.dto.visual.WindowSearchForm;
import com.fkhwl.nfs.biz.entity.dto.visual.WindowSearchVO;
import com.fkhwl.nfs.biz.service.visual.TrajectoryCompressService;
import com.fkhwl.nfs.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 轨迹压缩与检索（页面一 叠加展示 / 页面二 时间窗口部分检索）。
 */
@Tag(name = "可视化-轨迹")
@RestController
@RequestMapping("/api/visual/trajectory")
public class VisualTrajectoryController {

    private final TrajectoryCompressService service;

    public VisualTrajectoryController(TrajectoryCompressService service) {
        this.service = service;
    }

    @Operation(summary = "原始轨迹（清洗后全量点+停留），坐标 GCJ-02")
    @GetMapping("/original/{waybillId}")
    public Result<TrackViewVO> original(@PathVariable Long waybillId) {
        return Result.ok(service.originalTrack(waybillId));
    }

    @Operation(summary = "压缩后轨迹（解码 Mongo 分片），坐标 GCJ-02")
    @GetMapping("/compressed/{waybillId}")
    public Result<TrackViewVO> compressed(@PathVariable Long waybillId) {
        return Result.ok(service.compressedTrack(waybillId));
    }

    @Operation(summary = "压缩/坐标口径快照（分片时长、精度、容差、坐标系）")
    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        return Result.ok(service.trackConfig());
    }

    @Operation(summary = "单运单 × 各算法保留轨迹（对比页地图下钻：本文 + DP/DPS/TD-TR/Trajic）")
    @GetMapping("/algorithm-tracks/{waybillId}")
    public Result<Map<String, Object>> algorithmTracks(@PathVariable Long waybillId) {
        return Result.ok(service.algorithmTracksOf(waybillId));
    }

    @Operation(summary = "Mongo 分片元数据清单")
    @GetMapping("/chunks/{waybillId}")
    public Result<List<ChunkVO>> chunks(@PathVariable Long waybillId) {
        return Result.ok(service.chunks(waybillId));
    }

    @Operation(summary = "压缩当前运单（写 Mongo 分片）")
    @PostMapping("/compress/{waybillId}")
    public Result<CompressResultVO> compressOne(@PathVariable Long waybillId) {
        return Result.ok(service.compressOne(waybillId));
    }

    @Operation(summary = "全量轨迹压缩（后台任务，返回 taskId）",
            description = "读取 trajectory.source.full-data-dir；limit>0 覆盖配置上限便于演示。")
    @PostMapping("/compress-all")
    public Result<CompressTaskVO> compressAll(@RequestParam(required = false) Integer limit) {
        CompressTaskVO task = new CompressTaskVO();
        task.taskId = service.startCompressAll(limit);
        task.status = "RUNNING";
        return Result.ok(task);
    }

    @Operation(summary = "全量压缩任务进度")
    @GetMapping("/compress-task/{taskId}")
    public Result<CompressTaskVO> task(@PathVariable String taskId) {
        return Result.ok(service.getTask(taskId));
    }

    @Operation(summary = "时间窗口部分检索（只解命中分片）")
    @PostMapping("/search-by-time")
    public Result<WindowSearchVO> searchByTime(@RequestBody WindowSearchForm form) {
        return Result.ok(service.searchByTime(form));
    }
}
