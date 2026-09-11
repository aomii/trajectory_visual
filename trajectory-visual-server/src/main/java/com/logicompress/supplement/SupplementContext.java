package com.logicompress.supplement;

import com.fkhwl.nfs.biz.experiment.TrackSourceUtil;
import com.logicompress.experiment.clean.CleanedTrack;
import com.logicompress.experiment.clean.DriftFilter;
import com.logicompress.experiment.config.ExperimentConfig;
import com.logicompress.experiment.model.SemanticResult;
import com.logicompress.experiment.model.Waybill;
import com.logicompress.experiment.semantic.SemanticAnalyzer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 补充实验的数据上下文：从可视化系统的全量源目录（source_data_full）装载运单，
 * 一次清洗 + 语义识别后缓存，供多个补充实验复用。
 *
 * <p><b>为什么不复用 {@code com.logicompress.experiment.data.WaybillLoader}</b>：
 * 那个加载器靠 {@code track_*.json} 与 {@code waybill_*.json} 的**文件名配对**关联业务信息，
 * 而可视化系统的源目录只有 track_ 文件（业务信息在 MySQL 里）。直接跑会把所有运单判为"无配对"而跳过。
 * 因此本包改为：轨迹走 {@link TrackSourceUtil}（与第 6 章主实验同一条解析路径，口径一致），
 * 收发地址/装卸货时间由调用方从 MySQL 传入（见 {@link WaybillMeta}）。
 */
public final class SupplementContext {

    private SupplementContext() {
    }

    /**
     * 运单业务元数据（补充实验里只有两类用到）：
     * 围栏匹配（表 6-7）与凸包控制点（表 6-18）需要收发地址坐标；
     * 围栏时窗校验需要装货/卸货时间。
     */
    public static final class WaybillMeta {
        public long waybillId;
        public double sendLat, sendLon, receiveLat, receiveLon;
        public long loadEpoch, unloadEpoch;

        public boolean hasSend() {
            return sendLat != 0 || sendLon != 0;
        }

        public boolean hasReceive() {
            return receiveLat != 0 || receiveLon != 0;
        }
    }

    /** 运单上下文：原件 + 清洗后轨迹 + 语义识别结果 */
    public static final class WaybillCtx {
        public final Waybill waybill;
        public final CleanedTrack cleaned;
        public final SemanticResult semantic;
        public final int nClean;
        /** 原始点数（装载后即释放 rawPoints 引用，故单独留一份计数） */
        public final int nRaw;

        WaybillCtx(Waybill w, CleanedTrack c, SemanticResult s, int nRaw) {
            this.waybill = w;
            this.cleaned = c;
            this.semantic = s;
            this.nClean = c.points.size();
            this.nRaw = nRaw;
        }
    }

    /**
     * 装载并预处理运单。
     *
     * <p><b>内存</b>：全量 5224 运单约 428 万点，若同时保留 rawPoints 与 cleaned.points 会接近 GB 级；
     * 故语义识别跑完后立即把 {@code waybill.rawPoints} 置空（点对象仍被 cleaned.points 引用，不会丢），
     * 原始点数改由 {@link WaybillCtx#nRaw} 携带。补充实验都用清洗后轨迹，不依赖原始列表。
     *
     * @param cfg       实验参数（容差/阈值/块长等）
     * @param sourceDir 轨迹源目录（source_data_full）
     * @param metaById  运单业务元数据；无该运单时用空元数据（围栏相关实验会跳过该运单）
     * @param limit     运单上限（&lt;=0 表示全部）
     */
    public static List<WaybillCtx> load(ExperimentConfig cfg, Path sourceDir,
                                        Map<Long, WaybillMeta> metaById, int limit) throws IOException {
        List<Path> files = TrackSourceUtil.listTrackFiles(sourceDir);
        if (limit > 0 && files.size() > limit) files = files.subList(0, limit);
        List<WaybillCtx> ctxs = new ArrayList<>();
        int done = 0;
        long t0 = System.currentTimeMillis();
        for (Path f : files) {
            if ((++done) % 500 == 0) {
                long sec = (System.currentTimeMillis() - t0) / 1000;
                System.out.printf("[supplement] 装载 %d/%d，已用 %ds，预计还需 %.1f 分钟%n",
                        done, files.size(), sec, sec / 60.0 * (files.size() - done) / done);
            }
            try {
                Waybill w = TrackSourceUtil.parseFile(f);
                if (w.rawPoints == null || w.rawPoints.isEmpty()) continue;
                int nRaw = w.rawPoints.size();
                WaybillMeta m = metaById == null ? null : metaById.get(w.waybillId);
                if (m != null) {
                    w.sendLat = m.sendLat;
                    w.sendLon = m.sendLon;
                    w.receiveLat = m.receiveLat;
                    w.receiveLon = m.receiveLon;
                    w.loadEpoch = m.loadEpoch;
                    w.unloadEpoch = m.unloadEpoch;
                }
                CleanedTrack cleaned = new DriftFilter().clean(w.rawPoints, cfg.driftSpeedKph, cfg.breakGapS);
                if (cleaned.points.size() < cfg.minPoints) continue;
                SemanticResult sem = new SemanticAnalyzer().analyze(w, cleaned, cfg);
                w.rawPoints = null;   // 见方法注释：释放原始列表引用，控制内存
                ctxs.add(new WaybillCtx(w, cleaned, sem, nRaw));
            } catch (Exception e) {
                System.err.println("[supplement] 跳过 " + f.getFileName() + "：" + e);
            }
        }
        return ctxs;
    }

    /** 本文分级抽稀：用当前配置（key/non 容差 + 锚点开关）跑一遍 SegmentedDp */
    public static List<Integer> ourKept(WaybillCtx c, ExperimentConfig cfg) {
        return new com.logicompress.experiment.compress.SegmentedDp().compress(c.cleaned, c.semantic, cfg);
    }

    /** 保留点下标 → 实际点列表 */
    public static List<com.logicompress.experiment.model.TrackPoint> materialize(
            CleanedTrack cleaned, List<Integer> kept) {
        List<com.logicompress.experiment.model.TrackPoint> pts = new ArrayList<>(kept.size());
        for (int i : kept) pts.add(cleaned.points.get(i));
        return pts;
    }

    /** 固定查询窗（与主实验第 7 步口径一致）：轨迹 60% 处取 1 小时 */
    public static long[] queryWindow(WaybillCtx c, ExperimentConfig cfg) {
        long tStart = c.cleaned.points.get(0).gtmEpoch;
        long tEnd = c.cleaned.points.get(c.cleaned.points.size() - 1).gtmEpoch;
        long t1 = tStart + Math.round((tEnd - tStart) * cfg.queryWindowFraction);
        return new long[]{t1, t1 + cfg.queryWindowS};
    }

    /** 空元数据（调用方无该运单业务信息时用） */
    public static Map<Long, WaybillMeta> emptyMeta() {
        return Collections.emptyMap();
    }
}
