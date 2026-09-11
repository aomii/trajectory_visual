package com.fkhwl.nfs.biz.service.visual.impl;

import com.fkhwl.nfs.biz.entity.dto.visual.ChunkVO;
import com.fkhwl.nfs.biz.entity.dto.visual.CompressResultVO;
import com.fkhwl.nfs.biz.entity.dto.visual.CompressTaskVO;
import com.fkhwl.nfs.biz.entity.dto.visual.GpsPointVO;
import com.fkhwl.nfs.biz.entity.dto.visual.StayVO;
import com.fkhwl.nfs.biz.entity.dto.visual.TrackViewVO;
import com.fkhwl.nfs.biz.entity.dto.visual.WindowSearchForm;
import com.fkhwl.nfs.biz.entity.dto.visual.WindowSearchVO;
import com.fkhwl.nfs.biz.entity.mongo.TrajectoryChunkDoc;
import com.fkhwl.nfs.biz.entity.po.TrajectoryWaybill;
import com.fkhwl.nfs.biz.experiment.CompressionPipeline;
import com.fkhwl.nfs.biz.experiment.ExperimentConfigs;
import com.fkhwl.nfs.biz.experiment.PipelineOutcome;
import com.fkhwl.nfs.biz.experiment.TrackSourceUtil;
import com.fkhwl.nfs.biz.mapper.TrajectoryWaybillMapper;
import com.fkhwl.nfs.biz.service.visual.TrajectoryCompressService;
import com.fkhwl.nfs.biz.util.CoordinateTransformUtil;
import com.fkhwl.nfs.biz.util.TimeFmt;
import com.fkhwl.nfs.common.ApiException;
import com.fkhwl.nfs.config.ExperimentProperties;
import com.fkhwl.nfs.config.VisualProperties;
import com.logicompress.experiment.baseline.Dps;
import com.logicompress.experiment.baseline.LossyBaseline;
import com.logicompress.experiment.baseline.PlainDp;
import com.logicompress.experiment.baseline.TdTr;
import com.logicompress.experiment.baseline.Trajic;
import com.logicompress.experiment.clean.CleanedTrack;
import com.logicompress.experiment.clean.DriftFilter;
import com.logicompress.experiment.compress.SegmentedDp;
import com.logicompress.experiment.config.ExperimentConfig;
import com.logicompress.experiment.encode.BlockOffsetCodec;
import com.logicompress.experiment.eval.Metrics;
import com.logicompress.experiment.model.SemanticResult;
import com.logicompress.experiment.model.StopUnit;
import com.logicompress.experiment.model.TrackPoint;
import com.logicompress.experiment.model.Waybill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 轨迹压缩与检索服务实现。
 *
 * <p>压缩：单运单/全量任务都复用 {@link CompressionPipeline}（v2 算法：静态停留识别 + 静止段锚点
 * 保留 + 移动段 DP(默认10m) + 分块偏移量无损编码）。得到 EncodedStore 后按块切片写 Mongo
 * {@code trajectory_chunk} 分片文档（每片一条，块时间跨度即时间索引）；停留视觉元数据挂在
 * chunkIndex==0 分片上。原始轨迹点不写 Mongo。
 *
 * <p>部分解压：按时间窗只查/只读命中分片的压缩负载并解码，统计读取字节/耗时/一致性。
 *
 * <p>坐标系：源 JSON 与业务库地址坐标**已经是 GCJ-02**（见 trajectory.coord.source-already-gcj02），
 * 展示层不再二次转换，直接叠加高德底图；压缩与指标计算不依赖坐标基准，保持原样。
 */
@Slf4j
@Service
public class TrajectoryCompressServiceImpl implements TrajectoryCompressService {

    private final MongoTemplate mongo;
    private final VisualProperties vprops;
    private final ExperimentProperties eprops;
    private final TrajectoryWaybillMapper waybillMapper;

    private final ConcurrentMap<String, CompressTaskVO> tasks = new ConcurrentHashMap<>();

    public TrajectoryCompressServiceImpl(MongoTemplate mongo, VisualProperties vprops,
                                         ExperimentProperties eprops,
                                         TrajectoryWaybillMapper waybillMapper) {
        this.mongo = mongo;
        this.vprops = vprops;
        this.eprops = eprops;
        this.waybillMapper = waybillMapper;
    }

    // ------------------------------------------------------------------
    // 单运单压缩
    // ------------------------------------------------------------------

    @Override
    public CompressResultVO compressOne(long waybillId) {
        Waybill w = loadWaybill(waybillId);
        if (w == null) throw new ApiException(404, "未找到运单: " + waybillId + "（源文件不存在或未导入）");
        long t0 = System.currentTimeMillis();
        CompressResultVO vo = doCompress(w);
        vo.durationMs = System.currentTimeMillis() - t0;
        return vo;
    }

    /** 解析到算法模型 Waybill：优先 DB 元数据里的 sourceFile，否则目录按 waybillId 扫描 */
    private Waybill loadWaybill(long waybillId) {
        try {
            TrajectoryWaybill meta = waybillMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TrajectoryWaybill>()
                            .eq(TrajectoryWaybill::getWaybillId, waybillId).last("limit 1"));
            String srcFile = meta == null ? null : meta.getSourceFile();
            Path dir = Paths.get(vprops.getSource().getFullDataDir());
            Path file = TrackSourceUtil.resolveFile(dir, srcFile, waybillId);
            if (file == null) return null;
            return TrackSourceUtil.parseFile(file);
        } catch (Exception e) {
            throw new ApiException(500, "读取原始轨迹失败: " + e.getMessage());
        }
    }

    /** 核心压缩：算法跑一遍 → 切片写 Mongo 分片 → 更新运单状态 → 返回指标 */
    CompressResultVO doCompress(Waybill w) {
        long t0 = System.currentTimeMillis();
        ExperimentConfig cfg = ExperimentConfigs.from(eprops);
        PipelineOutcome o = CompressionPipeline.process(w, cfg, true, false, false);
        if (o == null) throw new ApiException("运单 " + w.waybillNo + " 清洗后点数不足，无法压缩");
        if (o.store == null || o.store.blocks.isEmpty()) {
            throw new ApiException("运单 " + w.waybillNo + " 编码无有效分片");
        }

        // 删除旧分片（先删后插，保证幂等；一个运单的最新压缩覆盖旧的）
        mongo.remove(Query.query(Criteria.where("waybillId").is(w.waybillId)), TrajectoryChunkDoc.class);

        boolean[] keptMask = Metrics.indexMask(o.keptIdx, o.cleanedPoints.size());
        List<TrajectoryChunkDoc.MongoStopUnit> stopUnits = toMongoStops(o.cleanedPoints, o.semantic, keptMask);

        int precision = cfg.precision;
        List<TrajectoryChunkDoc> docs = new ArrayList<>();
        Date now = new Date();
        int totalChunks = o.store.blocks.size();
        long waybillStartMs = o.cleanedPoints.isEmpty() ? 0
                : o.cleanedPoints.get(0).gtmEpoch * 1000L;
        for (int i = 0; i < totalChunks; i++) {
            com.logicompress.experiment.model.EncodeBlock b = o.store.blocks.get(i);
            TrajectoryChunkDoc doc = new TrajectoryChunkDoc();
            doc.setWaybillId(w.waybillId);
            doc.setWaybillNo(w.waybillNo);
            doc.setChunkIndex(i);
            doc.setStartTime(b.tStart * 1000L);          // 块起（epoch ms）→ 时间索引
            doc.setEndTime(b.tEnd * 1000L);
            doc.setPointCount(b.pointCount);
            doc.setHasAnchor(b.hasKeyPoint);
            doc.setChunkCountTotal(totalChunks);
            doc.setRawPointCount(o.nRaw);
            doc.setKeptPointCount(o.nKept);
            doc.setStayPointCount(o.nStops);
            doc.setAlgorithmCode("PROPOSED");
            doc.setMoveToleranceM(cfg.dpEpsNonKeyM);
            doc.setPrecision(precision);
            doc.setBlockWindowS(cfg.blockWindowS);
            // 该块 zip 负载 = store.blob[byteOffset, byteOffset+length)
            byte[] payload = Arrays.copyOfRange(o.store.blob, (int) b.byteOffset, (int) b.byteOffset + b.length);
            doc.setCompressedPayload(payload);
            doc.setCreateTime(now);
            if (i == 0) {
                doc.setStopUnits(stopUnits);
                doc.setMetrics(toMongoMetrics(o, totalChunks, cfg, System.currentTimeMillis() - t0));
            }
            docs.add(doc);
        }
        mongo.insert(docs, TrajectoryChunkDoc.class);

        // 更新运单元数据状态
        TrajectoryWaybill meta = waybillMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TrajectoryWaybill>()
                        .eq(TrajectoryWaybill::getWaybillId, w.waybillId).last("limit 1"));
        if (meta != null) {
            meta.setDataStatus("COMPRESSED");
            meta.setUpdateTime(java.time.LocalDateTime.now());
            waybillMapper.updateById(meta);
        }

        CompressResultVO vo = new CompressResultVO();
        vo.waybillId = w.waybillId;
        vo.waybillNo = w.waybillNo;
        vo.rawPointCount = o.nRaw;
        vo.cleanedPointCount = o.nClean;
        vo.keptPointCount = o.nKept;
        vo.stopCount = o.nStops;
        vo.anchorCount = o.nAnchors;
        vo.chunkCount = totalChunks;
        vo.crLossy = o.crLossy;
        vo.crLossless = o.crLossless;
        vo.crTotal = o.crTotal;
        vo.naiveBytes = o.naiveBytes;
        vo.storageBytes = o.zippedBytes;
        vo.pedAvgM = o.pedAvgM;
        vo.pedMaxM = o.pedMaxM;
        vo.sedAvgM = o.sedAvgM;
        vo.sedMaxM = o.sedMaxM;
        vo.sr = o.sr;
        vo.unitIntegrity = o.unitIntegrity;
        vo.dwellFidelity = o.dwellFidelity;
        vo.encodeMs = o.encodeMs;
        vo.decodeMs = o.decodeMs;
        vo.moveToleranceM = cfg.dpEpsNonKeyM;
        vo.precision = cfg.precision;
        vo.blockWindowS = cfg.blockWindowS;
        vo.durationMs = System.currentTimeMillis() - t0;
        return vo;
    }

    /** 压缩产出 → Mongo 指标快照（与 CompressResultVO 同口径，见 TrajectoryChunkDoc.Metrics） */
    private TrajectoryChunkDoc.Metrics toMongoMetrics(PipelineOutcome o, int totalChunks,
                                                      ExperimentConfig cfg, long durationMs) {
        TrajectoryChunkDoc.Metrics m = new TrajectoryChunkDoc.Metrics();
        m.setRawPointCount(o.nRaw);
        m.setCleanedPointCount(o.nClean);
        m.setKeptPointCount(o.nKept);
        m.setStopCount(o.nStops);
        m.setAnchorCount(o.nAnchors);
        m.setChunkCount(totalChunks);
        m.setCrLossy(o.crLossy);
        m.setCrLossless(o.crLossless);
        m.setCrTotal(o.crTotal);
        m.setNaiveBytes(o.naiveBytes);
        m.setZippedBytes(o.zippedBytes);
        m.setPedAvgM(o.pedAvgM);
        m.setPedMaxM(o.pedMaxM);
        m.setSedAvgM(o.sedAvgM);
        m.setSedMaxM(o.sedMaxM);
        m.setSr(o.sr);
        m.setUnitIntegrity(o.unitIntegrity);
        m.setDwellFidelity(o.dwellFidelity);
        m.setEncodeMs(o.encodeMs);
        m.setDecodeMs(o.decodeMs);
        m.setCompressDurationMs(durationMs);
        m.setMoveToleranceM(cfg.dpEpsNonKeyM);
        m.setPrecision(cfg.precision);
        m.setBlockWindowS(cfg.blockWindowS);
        return m;
    }

    /** 停留单元 → Mongo 视觉元数据（坐标 WGS84，起终锚点成对保留标记） */
    private List<TrajectoryChunkDoc.MongoStopUnit> toMongoStops(List<TrackPoint> cleaned,
                                                                 SemanticResult sem, boolean[] keptMask) {
        List<TrajectoryChunkDoc.MongoStopUnit> out = new ArrayList<>();
        int seq = 0;
        for (StopUnit u : sem.stops) {
            TrajectoryChunkDoc.MongoStopUnit s = new TrajectoryChunkDoc.MongoStopUnit();
            s.setSeq(seq++);
            s.setStartTime(u.tStart);
            s.setEndTime(u.tEnd);
            s.setDurationS(u.durationS());
            s.setLabel(u.label);
            boolean sOk = u.startIdx >= 0 && u.startIdx < cleaned.size();
            boolean eOk = u.endIdx >= 0 && u.endIdx < cleaned.size();
            s.setStartLat(sOk ? cleaned.get(u.startIdx).lat : 0);
            s.setStartLon(sOk ? cleaned.get(u.startIdx).lon : 0);
            s.setEndLat(eOk ? cleaned.get(u.endIdx).lat : 0);
            s.setEndLon(eOk ? cleaned.get(u.endIdx).lon : 0);
            boolean sKept = sOk && u.startIdx < keptMask.length && keptMask[u.startIdx];
            boolean eKept = eOk && u.endIdx < keptMask.length && keptMask[u.endIdx];
            s.setRetainedBoth(sKept && eKept);
            out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 全量压缩任务
    // ------------------------------------------------------------------

    @Override
    public String startCompressAll(Integer limitOverride) {
        Path dir = Paths.get(vprops.getSource().getFullDataDir());
        List<Path> files;
        try {
            files = TrackSourceUtil.listTrackFiles(dir);
        } catch (Exception e) {
            throw new ApiException("全量轨迹源目录读取失败: " + dir, e);
        }
        int limit = limitOverride != null && limitOverride > 0
                ? limitOverride : vprops.getSource().getMaxFilesPerJob();
        if (limit > 0 && files.size() > limit) files = files.subList(0, limit);

        String taskId = UUID.randomUUID().toString();
        CompressTaskVO task = new CompressTaskVO();
        task.taskId = taskId;
        task.status = "RUNNING";
        task.totalFiles = files.size();
        task.startTimeMs = System.currentTimeMillis();
        tasks.put(taskId, task);
        runCompressAllAsync(taskId, files);
        return taskId;
    }

    @Async("trajectoryTaskExecutor")
    void runCompressAllAsync(String taskId, List<Path> files) {
        CompressTaskVO task = tasks.get(taskId);
        if (task == null) return;
        try {
            for (Path f : files) {
                if (Thread.currentThread().isInterrupted()) break;
                task.currentWaybillNo = TrackSourceUtil.parseWaybillNo(f);
                task.elapsedMs = System.currentTimeMillis() - task.startTimeMs;
                task.processed++;
                try {
                    Waybill w = TrackSourceUtil.parseFile(f);
                    // 全量压缩（仅 PROPOSED 无损层 + Mongo 分片）
                    doCompress(w);
                    task.successCount++;
                } catch (Exception e) {
                    task.failedCount++;
                    if (task.failures.size() < 50) {
                        CompressTaskVO.Failure fr = new CompressTaskVO.Failure();
                        fr.waybillNo = TrackSourceUtil.parseWaybillNo(f);
                        fr.file = f.getFileName().toString();
                        fr.error = String.valueOf(e);
                        task.failures.add(fr);
                    }
                }
            }
            task.elapsedMs = System.currentTimeMillis() - task.startTimeMs;
            task.status = "SUCCESS";
        } catch (Throwable ex) {
            task.status = "FAILED";
            task.message = String.valueOf(ex);
            log.error("全量压缩任务异常", ex);
        }
    }

    @Override
    public CompressTaskVO getTask(String taskId) {
        CompressTaskVO t = tasks.get(taskId);
        if (t == null) throw new ApiException(404, "任务不存在或已过期: " + taskId);
        return t;
    }

    // ------------------------------------------------------------------
    // 原始轨迹（清洗后全量点 + 停留）
    // ------------------------------------------------------------------

    @Override
    public TrackViewVO originalTrack(long waybillId) {
        Waybill w = loadWaybill(waybillId);
        if (w == null) throw new ApiException(404, "未找到运单: " + waybillId);
        ExperimentConfig cfg = ExperimentConfigs.from(eprops);
        CleanedTrack cleaned = new DriftFilter().clean(w.rawPoints, cfg.driftSpeedKph, cfg.breakGapS);
        SemanticResult sem = new com.logicompress.experiment.semantic.SemanticAnalyzer()
                .analyze(w, cleaned, cfg);

        TrackViewVO vo = new TrackViewVO();
        vo.waybillId = waybillId;
        vo.waybillNo = w.waybillNo;
        vo.lineType = "original";
        vo.algorithmCode = null;
        vo.pointCount = cleaned.points.size();
        vo.stopCount = sem.stops.size();
        fillTrackPoints(vo, cleaned.points, null);
        fillStays(vo, cleaned.points, sem.stops, null);

        // "被有损压缩删除的点"是压缩的产物：只有该运单**已经压缩过**（Mongo 有分片）才谈得上删除点。
        // 未压缩时保持 kept=null、deletedPointCount=null，图上就只有一条原始线，不会凭空冒出高亮点。
        int keptCount = cleaned.points.size();
        TrajectoryChunkDoc first = chunk0Of(waybillId);
        if (first == null) {
            vo.notice = "原始线 = 清洗后全量点（源数据即 GCJ-02）。该运单尚未压缩，"
                    + "因此没有「被删除的点」；执行压缩后即可看到被删点高亮与压缩线对比。";
        } else {
            boolean[] keptMask = keptMaskOf(cleaned, sem, first);
            if (keptMask != null) {
                keptCount = 0;
                for (int i = 0; i < vo.points.size(); i++) {
                    boolean kept = i < keptMask.length && keptMask[i];
                    vo.points.get(i).kept = kept;
                    if (kept) keptCount++;
                }
                vo.keptPointCount = keptCount;
                vo.deletedPointCount = cleaned.points.size() - keptCount;
                vo.notice = "原始线 = 清洗后全量点（源数据即 GCJ-02）；橙色高亮圆点 = 被本文有损压缩删除的点，"
                        + "绿色压缩线只保留其中 " + keptCount + " 个点（含静止段首末锚点）。";
            } else {
                vo.notice = "原始线 = 清洗后全量点。该运单的压缩参数与当前配置不一致（无法复现删除点标记），"
                        + "暂不显示被删点；重新压缩该运单后即可显示。";
            }
        }

        // 未压缩运单也给一份"点数口径"指标，供工作台指标卡显示原始侧数字（压缩率留空）
        CompressResultVO base = new CompressResultVO();
        base.waybillId = waybillId;
        base.waybillNo = w.waybillNo;
        base.rawPointCount = w.rawPoints == null ? 0 : w.rawPoints.size();
        base.cleanedPointCount = cleaned.points.size();
        base.keptPointCount = keptCount;   // 未压缩时=全量（一个点都还没删）
        base.stopCount = sem.stops.size();
        base.anchorCount = sem.anchors == null ? 0 : sem.anchors.size();
        base.moveToleranceM = cfg.dpEpsNonKeyM;
        base.precision = cfg.precision;
        base.blockWindowS = cfg.blockWindowS;
        base.message = first == null ? "未压缩：以上为清洗后全量点" : null;
        vo.metrics = base;
        return vo;
    }

    /** 该运单的分片 0（含停留语义与压缩指标快照）；未压缩返回 null */
    private TrajectoryChunkDoc chunk0Of(long waybillId) {
        return mongo.findOne(Query.query(
                        Criteria.where("waybillId").is(waybillId).and("chunkIndex").is(0)),
                TrajectoryChunkDoc.class);
    }

    /**
     * 复现"哪些原始点被有损压缩删除"：按分片里落库的同一套参数重跑一次分级抽稀。
     *
     * <p>为什么重跑而不是直接读库：Mongo 只存压缩**结果**（保留点），不存保留点与原始点的对应关系；
     * 而抽稀过程本身是确定性的，只要参数一致就能复现同样的保留集合。
     *
     * <p>安全阀：跑出来的保留点数必须与分片记录的 keptPointCount 相等，否则说明参数对不上
     * （例如中途改过配置），返回 null 让上层不显示被删点，而不是画一份可能是错的标记。
     */
    private boolean[] keptMaskOf(CleanedTrack cleaned, SemanticResult sem, TrajectoryChunkDoc first) {
        ExperimentConfig c = ExperimentConfigs.from(eprops);
        if (first.getMoveToleranceM() != null) {
            c.dpEpsNonKeyM = first.getMoveToleranceM();
            c.dpEpsKeyM = first.getMoveToleranceM();
        }
        if (first.getPrecision() != null) c.precision = first.getPrecision();
        if (first.getBlockWindowS() != null) c.blockWindowS = first.getBlockWindowS();
        List<Integer> keptIdx = new SegmentedDp().compress(cleaned, sem, c);
        Integer recorded = first.getMetrics() == null ? first.getKeptPointCount()
                : first.getMetrics().getKeptPointCount();
        if (recorded != null && recorded.intValue() != keptIdx.size()) {
            log.warn("运单 {} 删除点复现失败：重算保留 {} 点，分片记录 {} 点，参数可能已变更",
                    first.getWaybillNo(), keptIdx.size(), recorded);
            return null;
        }
        return Metrics.indexMask(keptIdx, cleaned.points.size());
    }

    // ------------------------------------------------------------------
    // 压缩后轨迹（解码 Mongo 分片）
    // ------------------------------------------------------------------

    @Override
    public TrackViewVO compressedTrack(long waybillId) {
        List<TrajectoryChunkDoc> docs = chunkDocsOf(waybillId);
        if (docs.isEmpty()) {
            TrackViewVO vo = new TrackViewVO();
            vo.waybillId = waybillId;
            vo.lineType = "compressed";
            vo.notice = "该运单尚未压缩，请先在轨迹压缩工作台执行“压缩当前运单”或“全量轨迹压缩”";
            return vo;
        }
        int precision = docs.get(0).getPrecision() == null ? eprops.getPrecision() : docs.get(0).getPrecision();
        TrackViewVO vo = new TrackViewVO();
        vo.waybillId = waybillId;
        vo.waybillNo = docs.get(0).getWaybillNo();
        vo.lineType = "compressed";
        vo.algorithmCode = "PROPOSED";
        vo.chunkCount = docs.get(0).getChunkCountTotal();
        vo.keptPointCount = docs.get(0).getKeptPointCount();
        vo.stopCount = docs.get(0).getStayPointCount();
        List<TrackPoint> kept = new ArrayList<>();
        for (TrajectoryChunkDoc d : docs) {
            kept.addAll(decodeChunk(d, precision));
        }
        fillTrackPoints(vo, kept, null);
        // 停留视觉元数据与压缩指标都在 chunk0
        TrajectoryChunkDoc first = null;
        for (TrajectoryChunkDoc d : docs) if (d.getChunkIndex() == 0) first = d;
        if (first != null) {
            vo.metrics = toResultMetrics(first, docs);
        }
        if (first != null && first.getStopUnits() != null) {
            for (TrajectoryChunkDoc.MongoStopUnit s : first.getStopUnits()) {
                StayVO sv = new StayVO();
                sv.seq = s.getSeq() == null ? 0 : s.getSeq();
                sv.label = s.getLabel();
                sv.startEpoch = s.getStartTime();
                sv.endEpoch = s.getEndTime();
                sv.startTime = TimeFmt.secToStr(s.getStartTime());
                sv.endTime = TimeFmt.secToStr(s.getEndTime());
                sv.durationS = s.getDurationS();
                double[] g1 = display(s.getStartLat(), s.getStartLon());
                double[] g2 = display(s.getEndLat(), s.getEndLon());
                sv.startLon = g1[0]; sv.startLat = g1[1];
                sv.endLon = g2[0]; sv.endLat = g2[1];
                sv.retainedBoth = Boolean.TRUE.equals(s.getRetainedBoth());
                vo.stays.add(sv);
            }
        }
        return vo;
    }

    /**
     * 分片 → 指标 VO。指标快照存在 chunk0 的 {@code metrics} 里；旧分片（2026-09-10 之前压的）
     * 无该字段，则用分片自身冗余的计数兜底（压缩字节可由负载长度求和得到，压缩率留空）。
     */
    private CompressResultVO toResultMetrics(TrajectoryChunkDoc first, List<TrajectoryChunkDoc> docs) {
        CompressResultVO m = new CompressResultVO();
        m.waybillId = first.getWaybillId();
        m.waybillNo = first.getWaybillNo();
        TrajectoryChunkDoc.Metrics src = first.getMetrics();
        long zipped = 0;
        for (TrajectoryChunkDoc d : docs) {
            zipped += d.getCompressedPayload() == null ? 0 : d.getCompressedPayload().length;
        }
        m.storageBytes = zipped;
        if (src != null) {
            m.rawPointCount = nz(src.getRawPointCount());
            m.cleanedPointCount = nz(src.getCleanedPointCount());
            m.keptPointCount = nz(src.getKeptPointCount());
            m.stopCount = nz(src.getStopCount());
            m.anchorCount = nz(src.getAnchorCount());
            m.chunkCount = nz(src.getChunkCount());
            m.crLossy = src.getCrLossy();
            m.crLossless = src.getCrLossless();
            m.crTotal = src.getCrTotal();
            m.naiveBytes = src.getNaiveBytes() == null ? 0 : src.getNaiveBytes();
            m.pedAvgM = src.getPedAvgM();
            m.pedMaxM = src.getPedMaxM();
            m.sedAvgM = src.getSedAvgM();
            m.sedMaxM = src.getSedMaxM();
            m.sr = src.getSr();
            m.unitIntegrity = src.getUnitIntegrity();
            m.dwellFidelity = src.getDwellFidelity();
            m.encodeMs = src.getEncodeMs();
            m.decodeMs = src.getDecodeMs();
            m.moveToleranceM = src.getMoveToleranceM();
            m.precision = src.getPrecision();
            m.blockWindowS = src.getBlockWindowS();
            m.durationMs = src.getCompressDurationMs() == null ? 0 : src.getCompressDurationMs();
        } else {
            m.rawPointCount = nz(first.getRawPointCount());
            m.keptPointCount = nz(first.getKeptPointCount());
            m.stopCount = nz(first.getStayPointCount());
            m.chunkCount = nz(first.getChunkCountTotal());
            m.moveToleranceM = first.getMoveToleranceM();
            m.precision = first.getPrecision();
            m.blockWindowS = first.getBlockWindowS();
            m.message = "该运单为早期分片，未落库压缩指标；重新压缩后可显示完整指标";
        }
        return m;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    // ------------------------------------------------------------------
    // 单运单 × 各算法 保留轨迹（对比页地图下钻）
    // ------------------------------------------------------------------

    /**
     * 同一运单下，本文与 4 条几何基线各自"保留点"的完整轨迹，供对比页地图叠加。
     *
     * <p><b>为什么现算而不是查库</b>：主实验只把各算法的**指标**写进结果表，没有持久化基线的保留点；
     * 单运单重跑一遍链路是毫秒级（同一清洗轨迹 + 同一容差），比重建一套"基线保留点"存储划算得多，
     * 且用的是与第 6 章实验同一批算法类，口径天然一致。
     *
     * <p>停留单元只随本文算法返回（几何基线不识别语义），前端据此在图上标出停留点与停留时长。
     */
    @Override
    public Map<String, Object> algorithmTracksOf(long waybillId) {
        Waybill w = loadWaybill(waybillId);
        if (w == null) throw new ApiException(404, "未找到运单: " + waybillId + "（源文件不存在或未导入）");
        ExperimentConfig cfg = ExperimentConfigs.from(eprops);
        CleanedTrack cleaned = new DriftFilter().clean(w.rawPoints, cfg.driftSpeedKph, cfg.breakGapS);
        if (cleaned.points.size() < cfg.minPoints) {
            throw new ApiException(400, "运单 " + w.waybillNo + " 清洗后点数不足，无法对比");
        }
        SemanticResult sem = new com.logicompress.experiment.semantic.SemanticAnalyzer()
                .analyze(w, cleaned, cfg);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("waybillId", waybillId);
        out.put("waybillNo", w.waybillNo);
        out.put("rawPointCount", w.rawPoints == null ? 0 : w.rawPoints.size());
        out.put("cleanedPointCount", cleaned.points.size());
        out.put("baselineToleranceM", cfg.baselineDpM);

        List<Map<String, Object>> algs = new ArrayList<>();
        // 本文：静止段锚点强制保留 + 移动段 DP
        List<Integer> ours = new SegmentedDp().compress(cleaned, sem, cfg);
        algs.add(algTrack("PROPOSED", "本文方法", cleaned, ours, sem, true));
        // 四条几何基线：同一清洗轨迹、同一容差
        LossyBaseline[] baselines = {new PlainDp(), new Dps(), new TdTr(), new Trajic()};
        for (int i = 0; i < baselines.length; i++) {
            List<Integer> bk = baselines[i].compress(cleaned, cfg.baselineDpM);
            algs.add(algTrack(CompressionPipeline.BASELINE_CODES[i],
                    CompressionPipeline.BASELINE_CODES[i], cleaned, bk, null, false));
        }
        out.put("algorithms", algs);
        return out;
    }

    /** 单个算法的保留轨迹块：保留点数 / 有损层压缩率 / 保留点坐标；本文另带停留单元（含时长） */
    private Map<String, Object> algTrack(String code, String name, CleanedTrack cleaned,
                                         List<Integer> kept, SemanticResult sem, boolean withStops) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("algorithmCode", code);
        m.put("algorithmName", name);
        m.put("keptPointCount", kept == null ? 0 : kept.size());
        m.put("crLossy", kept == null || kept.isEmpty() ? null
                : round4((double) cleaned.points.size() / kept.size()));

        List<Map<String, Object>> pts = new ArrayList<>();
        if (kept != null) {
            int i = 0;
            for (int idx : kept) {
                TrackPoint p = cleaned.points.get(idx);
                double[] g = display(p.lat, p.lon);
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("lon", g[0]);
                pm.put("lat", g[1]);
                pm.put("time", TimeFmt.secToStr(p.gtmEpoch));
                pm.put("epoch", p.gtmEpoch);
                pm.put("spd", p.spdKph);
                pm.put("seq", i++);
                pts.add(pm);
            }
        }
        m.put("points", pts);

        if (withStops && sem != null) {
            List<Map<String, Object>> stops = new ArrayList<>();
            int seq = 0;
            for (StopUnit u : sem.stops) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("seq", seq++);
                sm.put("label", u.label);
                sm.put("startTime", TimeFmt.secToStr(u.tStart));
                sm.put("endTime", TimeFmt.secToStr(u.tEnd));
                sm.put("durationS", u.durationS());
                if (u.startIdx >= 0 && u.startIdx < cleaned.points.size()) {
                    TrackPoint sp = cleaned.points.get(u.startIdx);
                    double[] g = display(sp.lat, sp.lon);
                    sm.put("startLon", g[0]);
                    sm.put("startLat", g[1]);
                }
                if (u.endIdx >= 0 && u.endIdx < cleaned.points.size()) {
                    TrackPoint ep = cleaned.points.get(u.endIdx);
                    double[] g = display(ep.lat, ep.lon);
                    sm.put("endLon", g[0]);
                    sm.put("endLat", g[1]);
                }
                // 本文锚点强制保留，起终锚点恒成对
                sm.put("retainedBoth", true);
                stops.add(sm);
            }
            m.put("stopCount", stops.size());
            m.put("stops", stops);
        }
        return m;
    }

    // ------------------------------------------------------------------
    // Mongo 分片清单 / 时间窗部分检索
    // ------------------------------------------------------------------

    @Override
    public List<ChunkVO> chunks(long waybillId) {
        List<TrajectoryChunkDoc> docs = chunkDocsOf(waybillId);
        List<ChunkVO> out = new ArrayList<>();
        for (TrajectoryChunkDoc d : docs) {
            ChunkVO c = new ChunkVO();
            c.chunkIndex = d.getChunkIndex();
            c.startTime = TimeFmt.msToStr(d.getStartTime());
            c.endTime = TimeFmt.msToStr(d.getEndTime());
            c.pointCount = d.getPointCount() == null ? 0 : d.getPointCount();
            c.hasAnchor = Boolean.TRUE.equals(d.getHasAnchor());
            c.payloadBytes = d.getCompressedPayload() == null ? 0 : d.getCompressedPayload().length;
            c.chunkCountTotal = d.getChunkCountTotal() == null ? docs.size() : d.getChunkCountTotal();
            c.blockWindowS = d.getBlockWindowS();
            c.rawPointCount = d.getRawPointCount() == null ? 0 : d.getRawPointCount();
            c.keptPointCount = d.getKeptPointCount() == null ? 0 : d.getKeptPointCount();
            c.stayPointCount = d.getStayPointCount() == null ? 0 : d.getStayPointCount();
            c.rawStartMs = d.getStartTime();
            c.rawEndMs = d.getEndTime();
            out.add(c);
        }
        return out;
    }

    @Override
    public WindowSearchVO searchByTime(WindowSearchForm form) {
        if (form.waybillId == null) throw new ApiException(400, "waybillId 必填");
        if (form.startTime == null || form.endTime == null) {
            throw new ApiException(400, "startTime/endTime 必填(yyyy-MM-dd HH:mm:ss)");
        }
        long t1 = TimeFmt.strToSec(form.startTime);
        long t2 = TimeFmt.strToSec(form.endTime);
        if (t2 < t1) throw new ApiException(400, "endTime 不能早于 startTime");

        List<TrajectoryChunkDoc> all = chunkDocsOf(form.waybillId);
        WindowSearchVO vo = new WindowSearchVO();
        vo.waybillId = form.waybillId;
        vo.t1 = TimeFmt.secToStr(t1);
        vo.t2 = TimeFmt.secToStr(t2);
        vo.t1Epoch = t1;
        vo.t2Epoch = t2;
        if (all.isEmpty()) {
            vo.dataStatus = "NOT_COMPRESSED";
            return vo;
        }
        int precision = all.get(0).getPrecision() == null ? eprops.getPrecision() : all.get(0).getPrecision();
        vo.totalChunks = all.size();
        long t0 = System.nanoTime();
        // 部分解压：只取时间索引命中的分片（块与窗口相交），只读其负载
        List<TrajectoryChunkDoc> hit = mongo.find(Query.query(
                        new Criteria().andOperator(
                                Criteria.where("waybillId").is(form.waybillId),
                                Criteria.where("startTime").lte(t2 * 1000L),
                                Criteria.where("endTime").gte(t1 * 1000L)))
                        .with(Sort.by(Sort.Direction.ASC, "chunkIndex")),
                TrajectoryChunkDoc.class);
        int hitBytes = 0;
        List<TrackPoint> window = new ArrayList<>();
        for (TrajectoryChunkDoc d : hit) {
            hitBytes += d.getCompressedPayload() == null ? 0 : d.getCompressedPayload().length;
            for (TrackPoint p : decodeChunk(d, precision)) {
                if (p.gtmEpoch >= t1 && p.gtmEpoch <= t2) window.add(p);
            }
        }
        window.sort((a, b) -> Long.compare(a.gtmEpoch, b.gtmEpoch));
        vo.hitChunks = hit.size();
        vo.bytesRead = hitBytes;
        vo.totalBytes = all.stream().mapToLong(d -> d.getCompressedPayload() == null ? 0
                : d.getCompressedPayload().length).sum();
        vo.readRatio = vo.totalBytes <= 0 ? 0 : round4((double) hitBytes / vo.totalBytes);
        vo.blockHitRatio = vo.totalChunks <= 0 ? 0 : round4((double) hit.size() / vo.totalChunks);
        vo.partialPoints = window.size();
        vo.partialMs = Math.round((System.nanoTime() - t0) / 1e6 * 100.0) / 100.0;

        // 全量解压对比（正确性基准 + 耗时对比）
        long f0 = System.nanoTime();
        int full = 0;
        for (TrajectoryChunkDoc d : all) {
            for (TrackPoint p : decodeChunk(d, precision)) {
                if (p.gtmEpoch >= t1 && p.gtmEpoch <= t2) full++;
            }
        }
        vo.fullMs = Math.round((System.nanoTime() - f0) / 1e6 * 100.0) / 100.0;
        vo.fullPoints = full;
        vo.consistent = vo.partialPoints == vo.fullPoints;

        // 展示点：源坐标即 GCJ-02，直接返回（见 trajectory.coord.source-already-gcj02）
        for (TrackPoint p : window) {
            GpsPointVO gp = new GpsPointVO();
            double[] g = display(p.lat, p.lon);
            gp.lon = g[0];
            gp.lat = g[1];
            gp.time = TimeFmt.secToStr(p.gtmEpoch);
            gp.epoch = p.gtmEpoch;
            gp.spd = p.spdKph;
            vo.points.add(gp);
        }
        TrajectoryChunkDoc first = all.get(0);
        vo.waybillNo = first.getWaybillNo();
        // 分片时长与轨迹时间范围：给前端"顶部提示"用（时间范围是多少、一片多少秒）
        vo.blockWindowS = first.getBlockWindowS() == null ? eprops.getBlockWindowS() : first.getBlockWindowS();
        vo.trackStartTime = TimeFmt.msToStr(all.get(0).getStartTime());
        vo.trackEndTime = TimeFmt.msToStr(all.get(all.size() - 1).getEndTime());
        return vo;
    }

    @Override
    public Map<String, Object> trackConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("blockWindowS", eprops.getBlockWindowS());
        m.put("precision", eprops.getPrecision());
        m.put("moveToleranceM", eprops.getDpMoveToleranceM());
        m.put("baselineToleranceM", eprops.getBaselineToleranceM());
        m.put("coordSystem", "GCJ-02");
        m.put("sourceAlreadyGcj02", vprops.getCoord().isSourceAlreadyGcj02());
        m.put("sourceDir", vprops.getSource().getFullDataDir());
        return m;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 该运单全部分片（chunkIndex 升序） */
    private List<TrajectoryChunkDoc> chunkDocsOf(long waybillId) {
        return mongo.find(Query.query(Criteria.where("waybillId").is(waybillId))
                        .with(Sort.by(Sort.Direction.ASC, "chunkIndex")),
                TrajectoryChunkDoc.class);
    }

    /** 解码单个分片负载（DEFLATE → ASCII → 本文分块解码），坐标保持 WGS84 */
    private List<TrackPoint> decodeChunk(TrajectoryChunkDoc d, int precision) {
        byte[] payload = d.getCompressedPayload();
        if (payload == null || payload.length == 0) return new ArrayList<>();
        String ascii = BlockOffsetCodec.inflate(payload);
        return BlockOffsetCodec.decodeToPoints(ascii, precision);
    }

    /** 把点序列填入 VO（可选 stops 直接标注）；坐标按配置口径转展示坐标 */
    private void fillTrackPoints(TrackViewVO vo, List<TrackPoint> pts, List<TrackPoint> ignored) {
        vo.points = new ArrayList<>(pts.size());
        int i = 0;
        for (TrackPoint p : pts) {
            GpsPointVO gp = new GpsPointVO();
            double[] g = display(p.lat, p.lon);
            gp.lon = g[0];
            gp.lat = g[1];
            gp.time = TimeFmt.secToStr(p.gtmEpoch);
            gp.epoch = p.gtmEpoch;
            gp.spd = p.spdKph;
            gp.seq = i++;
            vo.points.add(gp);
        }
    }

    /** 展示坐标：源已是 GCJ-02 时原样返回（当前口径），否则按 WGS84→GCJ-02 转换 */
    private double[] display(double lat, double lon) {
        return CoordinateTransformUtil.toDisplay(lat, lon, vprops.getCoord().isSourceAlreadyGcj02());
    }

    private void fillStays(TrackViewVO vo, List<TrackPoint> cleaned, List<StopUnit> stops, Void v) {
        vo.stays = new ArrayList<>();
        int seq = 0;
        for (StopUnit u : stops) {
            StayVO sv = new StayVO();
            sv.seq = seq++;
            sv.label = u.label;
            sv.startEpoch = u.tStart;
            sv.endEpoch = u.tEnd;
            sv.startTime = TimeFmt.secToStr(u.tStart);
            sv.endTime = TimeFmt.secToStr(u.tEnd);
            sv.durationS = u.durationS();
            if (u.startIdx >= 0 && u.startIdx < cleaned.size()) {
                double[] g = display(cleaned.get(u.startIdx).lat, cleaned.get(u.startIdx).lon);
                sv.startLon = g[0];
                sv.startLat = g[1];
            }
            if (u.endIdx >= 0 && u.endIdx < cleaned.size()) {
                double[] g = display(cleaned.get(u.endIdx).lat, cleaned.get(u.endIdx).lon);
                sv.endLon = g[0];
                sv.endLat = g[1];
            }
            sv.retainedBoth = true; // 原始线为全量点，起终天然保留
            vo.stays.add(sv);
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
