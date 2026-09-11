package com.fkhwl.nfs.biz.service.visual;

import com.fkhwl.nfs.biz.entity.dto.visual.ChunkVO;
import com.fkhwl.nfs.biz.entity.dto.visual.CompressResultVO;
import com.fkhwl.nfs.biz.entity.dto.visual.CompressTaskVO;
import com.fkhwl.nfs.biz.entity.dto.visual.TrackViewVO;
import com.fkhwl.nfs.biz.entity.dto.visual.WindowSearchForm;
import com.fkhwl.nfs.biz.entity.dto.visual.WindowSearchVO;

import java.util.List;
import java.util.Map;

/**
 * 轨迹压缩与检索服务（页面一/页面二的核心后端）。
 * 压缩结果分片只写 MongoDB trajectory_chunk；原始轨迹来自本地源文件，不落库。
 */
public interface TrajectoryCompressService {

    /** 对指定运单执行压缩并写 Mongo 分片；返回压缩指标 */
    CompressResultVO compressOne(long waybillId);

    /**
     * 启动全量轨迹压缩后台任务（读取 trajectory.source.full-data-dir）。
     *
     * @param limitOverride 上限覆盖（>0）；null 用配置 trajectory.source.max-files-per-job
     * @return 任务 id（配合 {@link #getTask} 轮询进度）
     */
    String startCompressAll(Integer limitOverride);

    /** 查询全量压缩任务进度 */
    CompressTaskVO getTask(String taskId);

    /** 原始轨迹（清洗后全量点 + 停留单元），GCJ-02 供地图展示 */
    TrackViewVO originalTrack(long waybillId);

    /** 压缩后轨迹（解码 Mongo 分片），GCJ-02 */
    TrackViewVO compressedTrack(long waybillId);

    /** Mongo 分片元数据清单 */
    List<ChunkVO> chunks(long waybillId);

    /** 时间窗口部分检索（只解命中分片），返回性能对比与一致性 */
    WindowSearchVO searchByTime(WindowSearchForm form);

    /**
     * 当前压缩/坐标口径快照（供前端展示"一片多少秒""坐标系"等提示，避免前端硬编码）：
     * blockWindowS / precision / moveToleranceM / baselineToleranceM / coordSystem / sourceDir。
     */
    Map<String, Object> trackConfig();

    /**
     * 单运单 × 各算法的**保留轨迹**（本文 + DP/DPS/TD-TR/Trajic），供对比页地图下钻：
     * 同一运单下各算法最终保留的点叠加在一起，直观呈现"压缩差异"；本文额外返回停留单元
     * （含各单元的起止时间与停留时长），供图上标注。
     */
    Map<String, Object> algorithmTracksOf(long waybillId);
}
