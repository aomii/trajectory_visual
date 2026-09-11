package com.logicompress.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicompress.experiment.baseline.Dps;
import com.logicompress.experiment.baseline.LossyBaseline;
import com.logicompress.experiment.baseline.PlainDp;
import com.logicompress.experiment.baseline.PolylineEncoder;
import com.logicompress.experiment.baseline.TdTr;
import com.logicompress.experiment.baseline.Trajic;
import com.logicompress.experiment.clean.CleanedTrack;
import com.logicompress.experiment.clean.DriftFilter;
import com.logicompress.experiment.compress.SegmentedDp;
import com.logicompress.experiment.config.ExperimentConfig;
import com.logicompress.experiment.data.WaybillLoader;
import com.logicompress.experiment.encode.BlockEncoder;
import com.logicompress.experiment.encode.BlockOffsetCodec;
import com.logicompress.experiment.encode.EncodedStore;
import com.logicompress.experiment.encode.PartialDecoder;
import com.logicompress.experiment.eval.Metrics;
import com.logicompress.experiment.model.SemanticResult;
import com.logicompress.experiment.model.TrackPoint;
import com.logicompress.experiment.model.Waybill;
import com.logicompress.experiment.semantic.SemanticAnalyzer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LogiCompress 第 6 章实验入口（run.bat / run.sh 的启动类）。
 *
 * <p>三种运行模式：
 * <ul>
 *   <li>{@code --dump-config}：打印当前全部实验参数（ExperimentConfig.asMap()）；</li>
 *   <li>{@code --verify}：抽前 N 个有效运单跑通全链路并打印摘要（默认 3 个，--limit 调整），
 *       并校验"按时间窗部分解压"结果与全量解压一致；</li>
 *   <li>{@code --run-all}：全部运单全量实验，结果落盘 results/（逐运单 CSV + 聚合 JSON + 参数快照）。</li>
 * </ul>
 *
 * <p>单运单全管线见 {@link #processWaybill(Waybill, ExperimentConfig)}（本类核心方法）：
 * 清洗（DriftFilter）→ 语义识别（SemanticAnalyzer：L1 围栏强匹配 → L2 种子引导 ST-DBSCAN → L3 运动学精修）→
 * 分级 DP 抽稀（SegmentedDp，锚点强制保留）→ 有损误差/SR（Metrics）→
 * 分块偏移量无损编码（BlockEncoder）→ 压缩率 → 按时间窗部分解压验证（PartialDecoder）→
 * 有损层基线对比（PlainDp/Dps/TdTr/Trajic）。单运单结果聚合为 results/ 下的
 * waybill_metrics.csv、baseline_metrics.csv、aggregate.json、config_dump.json。
 *
 * <p>压缩率口径（论文口径）：有损层 CR_lossy = 清洗后点数 / 保留点数；
 * 无损层 CR_lossless = 规范文本字节 / 编码后字节；总压缩率 CR_total = CR_lossy × CR_lossless。
 */
public final class Main {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Google Polyline 以 1e-8 度高精度作无损层对照基线（本文分块编码器 precision=6，保留更高精度基线更保守） */
    private static final double POLYLINE_FACTOR = 1e8;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        // 补充实验模式（Supplementary）在参数解析之前拦截：
        // 模式 token（如 sweep-crsr）若流入下面的循环会被 ExperimentConfig.applyArgs 当配置吞掉
        if (args.length >= 1 && "--supplem".equals(args[0])) {
            Supplementary.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }

        // 先分离命令与参数，避免 ExperimentConfig.applyArgs 把后续 flag 误吞为参数值
        String command = "--verify";
        int limit = 3;
        int offset = 0;
        List<String> params = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--verify":
                case "--run-all":
                case "--dump-config":
                    command = a;
                    break;
                case "--limit":
                    limit = Integer.parseInt(args[++i]);
                    break;
                case "--offset":
                    offset = Integer.parseInt(args[++i]);
                    break;
                default:
                    params.add(a);
            }
        }

        ExperimentConfig cfg = new ExperimentConfig();
        cfg.applyArgs(params.toArray(new String[0]));

        switch (command) {
            case "--run-all":
                runAll(cfg);
                break;
            case "--dump-config":
                dumpConfig(cfg);
                break;
            default:
                runVerify(cfg, limit, offset);
        }
    }

    // ------------------------------------------------------------------
    // 模式一：打印参数
    // ------------------------------------------------------------------

    private static void dumpConfig(ExperimentConfig cfg) throws IOException {
        System.out.println("=== LogiCompress 实验参数 ===");
        for (Map.Entry<String, String> e : cfg.asMap().entrySet()) {
            System.out.printf("  %-24s %s%n", e.getKey(), e.getValue());
        }
        WaybillLoader loader = new WaybillLoader(cfg.trackDataDir, cfg.waybillDataDir);
        List<Waybill> all = loader.loadAll();
        int withTrack = 0, totalPts = 0;
        for (Waybill w : all) {
            if (w.rawPoints != null && !w.rawPoints.isEmpty()) {
                withTrack++;
                totalPts += w.rawPoints.size();
            }
        }
        System.out.printf("%n数据源：%s / %s%n", cfg.trackDataDir, cfg.waybillDataDir);
        System.out.printf("运单总数=%d，含轨迹=%d，轨迹点合计=%d%n", all.size(), withTrack, totalPts);
    }

    // ------------------------------------------------------------------
    // 模式二：冒烟验证（默认 3 个运单）
    // ------------------------------------------------------------------

    private static void runVerify(ExperimentConfig cfg, int limit, int offset) {
        List<Waybill> all = new WaybillLoader(cfg.trackDataDir, cfg.waybillDataDir).loadAll();
        List<WaybillResult> results = new ArrayList<>();
        int skipped = 0;
        for (int i = offset; i < all.size() && results.size() < limit; i++) {
            WaybillResult r = safeProcess(all.get(i), cfg);
            if (r == null) {
                skipped++;
            } else {
                results.add(r);
            }
        }
        System.out.printf("== 冒烟验证：%d 个运单（含 %d 个无效轨迹跳过）==%n", results.size(), skipped);
        printWaybillRows(results);
        if (!results.isEmpty()) {
            printPartialDecodeChecks(results);
        }
    }

    // ------------------------------------------------------------------
    // 模式三：全量实验 + 落盘
    // ------------------------------------------------------------------

    private static void runAll(ExperimentConfig cfg) throws IOException {
        Path resultsDir = Paths.get(cfg.resultsDir);
        Files.createDirectories(resultsDir);

        List<Waybill> all = new WaybillLoader(cfg.trackDataDir, cfg.waybillDataDir).loadAll();
        List<WaybillResult> results = new ArrayList<>();
        int skipped = 0;
        long t0 = System.currentTimeMillis();
        for (Waybill w : all) {
            WaybillResult r = safeProcess(w, cfg);
            if (r == null) {
                skipped++;
            } else {
                results.add(r);
            }
        }
        long sec = (System.currentTimeMillis() - t0) / 1000;

        // 落盘：逐运单指标、逐运单×基线指标、全量聚合、参数快照（供第 6 章表格与结果复现）
        writeWaybillCsv(resultsDir.resolve("waybill_metrics.csv"), results);
        writeBaselineCsv(resultsDir.resolve("baseline_metrics.csv"), results);
        writeAggregateJson(resultsDir.resolve("aggregate.json"), results, skipped, cfg);
        JSON.writerWithDefaultPrettyPrinter()
                .writeValue(resultsDir.resolve("config_dump.json").toFile(), new LinkedHashMap<>(cfg.asMap()));

        System.out.printf("== 全量实验完成：处理 %d 运单，跳过 %d（无效轨迹），耗时 %ds ==%n",
                results.size(), skipped, sec);
        System.out.printf("结果目录：%s%n", resultsDir.toAbsolutePath());
        printAggregateSummary(results, skipped);
    }

    /** 处理单个运单；异常或无效轨迹返回 null（不影响整批）。 */
    private static WaybillResult safeProcess(Waybill w, ExperimentConfig cfg) {
        try {
            return processWaybill(w, cfg);
        } catch (Exception ex) {
            System.err.println("[error] 运单 " + (w == null ? "null" : w.waybillNo) + " 处理失败: " + ex);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 单运单管线
    // ------------------------------------------------------------------

    /**
     * 处理单个运单的全链路（第 6 章实验的核心步骤）：
     * ①清洗 → ②语义识别 → ③分级DP抽稀 → ④有损误差/SR → ⑤无损层编码 →
     * ⑥压缩率 → ⑦按时间窗部分解压验证 → ⑧有损层基线对比。
     *
     * @param w   已加载并关联轨迹的运单（rawPoints 非空）
     * @param cfg 本次实验参数（ExperimentConfig，命令行可覆盖）
     * @return 该运单一行完整指标（WaybillResult，对应 waybill_metrics.csv 一行）；
     *         无效轨迹（无点/清洗后过短）返回 null，由调用方计入"跳过"。
     */
    static WaybillResult processWaybill(Waybill w, ExperimentConfig cfg) {
        if (w == null || w.rawPoints == null || w.rawPoints.isEmpty()) return null;

        WaybillResult r = new WaybillResult();
        r.waybillNo = w.waybillNo;
        r.waybillId = w.waybillId;
        r.nRaw = w.rawPoints.size(); // 原始点数（含漂移点），作为"接收到的原始数据量"参考

        // ── ① 数据清洗（论文 4.x：压缩前数据预处理）─────────────────────
        // 作用：把原始轨迹变成"干净、按连续子段组织"的点序列，作为后续全部算法的输入。
        //  - 漂移剔除：用经纬度推算速度（相邻位移/时间差）> driftSpeedKph(120km/h) 判为漂移点剔除
        //    （spd 字段本身不可靠，故用推算速度）；
        //  - 断线分段：相邻点时间差 > breakGapS(10min) 处切断成连续子段 cleaned.segments，
        //    不跨断线插值，避免把两次作业之间的间隔误判为低速段。
        // 产出：CleanedTrack{points, segments, removedDrift}；nClean = 清洗后点数。
        CleanedTrack cleaned = new DriftFilter().clean(w.rawPoints, cfg.driftSpeedKph, cfg.breakGapS);
        r.nClean = cleaned.points.size();
        r.removedDrift = cleaned.removedDrift;
        // 守卫：清洗后仍不足 minPoints(5) 点的视为无效轨迹（多为仅 1~2 点的空运单），
        // 返回 null 由调用方计入"跳过"，不进入指标统计。
        if (cleaned.points.size() < cfg.minPoints) return null;

        // ── ② 语义识别（论文第 3 章研究内容一：融合业务先验的停留事件识别）──
        // 作用：识别"哪些时段是业务/行为停留"，输出关键锚点集合，供第③步抽稀时强制保留。
        //  analyze 内部三层递进：
        //    L1 电子围栏强匹配（FenceMatcher）：收发货地址围栏命中 + 装卸货时窗吻合 → LOAD/UNLOAD
        //    L2 种子引导 ST-DBSCAN（StDbscan）：以 L1 命中点为种子做时空聚类 → 加油/休息/堵车等
        //    L3 运动学精修（KinematicRefine）：速度阈值扩展停留边界，修正起止时刻
        // 产出：SemanticResult{stops=停留单元[], anchors=关键锚点下标(指向清洗后点), ...}。
        SemanticAnalyzer analyzer = new SemanticAnalyzer();
        SemanticResult semantic = analyzer.analyze(w, cleaned, cfg);
        r.nStops = semantic.stops.size();
        r.nAnchors = semantic.anchors.size();

        // ── ③ 分级 DP 抽稀（论文第 4 章算法 4-1：语义感知的有损压缩层）────
        // 作用：把清洗后点序列抽稀。锚点（停留单元首末点 + 轨迹起止点）强制保留；
        //      静止段只保两端锚点、移动段用统一容差 ε=10m（2026-09-09 定稿，与基线同容差），
        //      实现"该保的保、该删的删"。
        // 产出：kept = 抽稀后保留的点在 cleaned.points 中的下标（时间升序），
        //      后续环节都通过 cleaned.points.get(idx) 取点。
        List<Integer> kept = new SegmentedDp().compress(cleaned, semantic, cfg);
        r.nKept = kept.size();
        // 有损层压缩率：清洗后点数 / 保留点数（"有损层减的是点数"这一半）。
        r.crLossy = (double) r.nClean / r.nKept;

        // ── ④ 有损层误差与语义保真（论文第 6 章评估指标）──────────────
        // 作用：量化本文压缩结果"压得多准、语义保没保住"。
        //  - pedSed：一次扫描同时算 PED（原始点到压缩折线的垂直距离）与
        //    SED（按时间比例在折线上插值同步点后的距离），各取 avg/max；
        //  - semanticRetention：SR = 压缩后保留的锚点数 / 锚点总数。
        //    本文锚点强制保留，故恒为 1.0，主要供第⑧步与"无语义基线"的 SR 做对比。
        Metrics.Error err = Metrics.pedSed(cleaned.points, kept);
        r.pedAvgM = err.avgPedM;
        r.pedMaxM = err.maxPedM;
        r.sedAvgM = err.avgSedM;
        r.sedMaxM = err.maxSedM;
        r.srOurs = Metrics.semanticRetention(semantic.anchors, kept);

        // ── ⑤ 无损层编码（论文第 4 章 4.3/4.4：分块偏移量变长编码 + 时间索引）──
        // 作用：对第③步抽稀后的"保留点序列"做无损编码，并测出各编码方式的字节数供对比（表 6-8）。
        //  1) keptPts：把保留下标物化为实际点列表——无损层编码的对象就是它；
        //  2) BlockEncoder.encode：按固定时间窗(blockWindowS，当前 1h)切块 → 每块块首绝对坐标、
        //     块内相对偏移 → 差分+ZigZag+5位分片变长编码 → DEFLATE，逐块 zip 拼成 blob，
        //     并建立"时间范围→块"稀疏索引（EncodedStore.blocks）。
        //     分块是第⑦步部分解压的前提：各块独立编码、可独立解压，无需全量解压。
        //  3) 各编码方式字节对比：
        //     - naiveBytes         朴素文本基准（每字段8位小数+gtm），无损层压缩前的"原始字节"
        //     - gzipNaiveBytes     通用 gzip 直接压缩朴素文本（通用熵编码基线）
        //     - asciiBytes         本文差分+ZigZag+5位分片（未 zip）
        //     - zippedBytes        本文存储形态 = ascii + DEFLATE
        //     - polylineBytes      Google Polyline（仅经纬度 2 字段，1e-8 度高精度对照）
        //     - polylineZippedBytes Polyline + DEFLATE
        List<TrackPoint> keptPts = new ArrayList<>(kept.size());
        for (int idx : kept) keptPts.add(cleaned.points.get(idx));
        EncodedStore store = new BlockEncoder().encode(cleaned.points, kept, semantic.anchors, cfg);
        String naive = Metrics.naiveAscii(keptPts, cfg.precision);
        r.naiveBytes = naive.getBytes(StandardCharsets.US_ASCII).length;
        r.gzipNaiveBytes = BlockOffsetCodec.deflate(naive, cfg.zipLevel).length;
        r.asciiBytes = store.totalAsciiBytes;
        r.zippedBytes = store.totalZippedBytes();
        String poly = PolylineEncoder.encode(keptPts, POLYLINE_FACTOR);
        r.polylineBytes = poly.getBytes(StandardCharsets.US_ASCII).length;
        r.polylineZippedBytes = BlockOffsetCodec.deflate(poly, cfg.zipLevel).length;

        // ── ⑥ 压缩率口径（论文口径：有损层减点数 × 无损层减每点字节数）──
        // 无损层 CR = 朴素文本字节 / 存储字节（"减每点字节数"这一半）；
        // 总压缩率 = 有损 × 无损，二者相乘才是完整口径。
        r.crLossless = (double) r.naiveBytes / r.zippedBytes;
        r.crTotal = r.crLossy * r.crLossless;

        // ── ⑦ 按时间窗部分解压验证（论文第 4 章 4.4：时间索引）─────────
        // 作用：证明"只解命中块、不整轨解压"的字节收益与正确性。
        //  - 查询窗：在轨迹约 queryWindowFraction(60%) 处取 queryWindowS(1h) 的 [t1,t2]；
        //  - decodeWindow：按索引命中块（块时间跨度与窗口相交的块），只读取命中块的字节
        //    解压并过滤边界点 → 记录命中块数/命中字节数/取回点数；
        //  - 正确性判据：decodeAll 全量解压后取同窗口子集点数 full，二者相等才通过
        //    （partialCorrect）。首跑 141/141 全部一致。
        r.blocks = store.blocks.size();
        long tStart = cleaned.points.get(0).gtmEpoch;
        long tEnd = cleaned.points.get(cleaned.points.size() - 1).gtmEpoch;
        long t1 = tStart + Math.round((tEnd - tStart) * cfg.queryWindowFraction);
        long t2 = t1 + cfg.queryWindowS;
        PartialDecoder decoder = new PartialDecoder();
        PartialDecoder.WindowResult wr = decoder.decodeWindow(store, t1, t2, cfg.precision);
        r.blocksHit = wr.blocksHit;
        r.bytesRead = wr.bytesRead;
        r.totalZipBytes = store.totalZippedBytes();
        r.partialBytesRatio = (double) wr.bytesRead / r.totalZipBytes;
        r.partialBlocksRatio = (double) wr.blocksHit / r.blocks;
        r.decodedInWindow = wr.points.size();

        // 正确性基准：全量解压后取同窗口子集，与部分解压结果对比
        List<TrackPoint> all = PartialDecoder.decodeAll(store, cfg.precision);
        int full = 0;
        for (TrackPoint p : all) {
            if (p.gtmEpoch >= t1 && p.gtmEpoch <= t2) full++;
        }
        r.fullInWindow = full;
        r.partialCorrect = wr.points.size() == full;

        // ── ⑧ 有损层基线对比（论文第 2 章基线算法）──────────────────────
        // 作用：4 种无语义的离线几何压缩在"同一清洗轨迹、同一容差 baselineDpM=10m"下抽稀，
        // 再算与本文完全相同的 CR/PED/SED/SR，构成第 6 章对比表（表 6-6）。
        // 注意：基线不知道语义，却用同一个 semantic.anchors 评 SR——这正是
        // "无语义信息会删掉多少业务锚点"的度量（首跑 DP 平均仅保留 55.8%，本文 100%）。
        LossyBaseline[] baselines = {new PlainDp(), new Dps(), new TdTr(), new Trajic()};
        String[] names = {"DP", "DPS", "TD-TR", "Trajic"};
        for (int i = 0; i < baselines.length; i++) {
            List<Integer> bKept = baselines[i].compress(cleaned, cfg.baselineDpM);
            if (bKept.isEmpty()) continue;
            BaselineMetric bm = new BaselineMetric();
            bm.name = names[i];
            bm.nKept = bKept.size();
            bm.crLossy = (double) r.nClean / bm.nKept;
            Metrics.Error be = Metrics.pedSed(cleaned.points, bKept);
            bm.pedAvgM = be.avgPedM;
            bm.pedMaxM = be.maxPedM;
            bm.sedAvgM = be.avgSedM;
            bm.sedMaxM = be.maxSedM;
            bm.sr = Metrics.semanticRetention(semantic.anchors, bKept);
            r.baselines.add(bm);
        }
        return r;
    }

    // ------------------------------------------------------------------
    // 输出：控制台摘要
    // ------------------------------------------------------------------

    private static void printWaybillRows(List<WaybillResult> results) {
        System.out.println();
        System.out.printf("%-20s %5s %6s %6s %8s %8s %8s %8s %6s %8s %8s %6s%n",
                "运单", "点数", "保留", "CR总", "CR有损", "CR无损", "PED均", "SED均", "SR%", "编码B", "分窗比", "正确");
        for (WaybillResult r : results) {
            System.out.printf("%-20s %5d %6d %8.2f %8.2f %8.2f %8.2f %8.2f %6.1f %8d %8.3f %6s%n",
                    r.waybillNo, r.nClean, r.nKept, r.crTotal, r.crLossy, r.crLossless,
                    r.pedAvgM, r.sedAvgM, r.srOurs * 100, r.zippedBytes,
                    r.partialBytesRatio, r.partialCorrect ? "OK" : "FAIL");
        }
        System.out.println();
    }

    private static void printPartialDecodeChecks(List<WaybillResult> results) {
        System.out.println("== 部分解压校验（按时间窗查询，命中块数/命中字节比）==");
        System.out.printf("%-20s %8s %8s %8s %6s %6s %8s%n",
                "运单", "块数", "命中块", "块命中比", "窗口点", "全量点", "一致");
        for (WaybillResult r : results) {
            System.out.printf("%-20s %8d %8d %8.3f %6d %6d %8s%n",
                    r.waybillNo, r.blocks, r.blocksHit, r.partialBlocksRatio,
                    r.decodedInWindow, r.fullInWindow, r.partialCorrect ? "一致" : "不一致");
        }
        System.out.println();
    }

    private static void printAggregateSummary(List<WaybillResult> results, int skipped) {
        if (results.isEmpty()) {
            System.out.println("无有效运单可聚合。");
            return;
        }
        System.out.println("== 全量聚合（全部运单）==");
        System.out.printf("处理 %d / 跳过 %d 运单%n", results.size(), skipped);
        Map<String, Double> cr = stats(col(results, r -> r.crTotal));
        Map<String, Double> ped = stats(col(results, r -> r.pedAvgM));
        Map<String, Double> sed = stats(col(results, r -> r.sedAvgM));
        Map<String, Double> sr = stats(col(results, r -> r.srOurs));
        System.out.printf("CR_total   平均 %.2f  中位 %.2f  最大 %.2f%n",
                cr.get("mean"), cr.get("median"), cr.get("max"));
        System.out.printf("PED_avg(m) 平均 %.2f  中位 %.2f  最大 %.2f%n",
                ped.get("mean"), ped.get("median"), ped.get("max"));
        System.out.printf("SED_avg(m) 平均 %.2f  中位 %.2f  最大 %.2f%n",
                sed.get("mean"), sed.get("median"), sed.get("max"));
        System.out.printf("SR         平均 %.1f%%  中位 %.1f%%%n",
                sr.get("mean") * 100, sr.get("median") * 100);

        System.out.println("== 有损层基线对比（CR_lossy 平均）==");
        String[] names = {"DP", "DPS", "TD-TR", "Trajic"};
        for (String name : names) {
            List<Double> crs = new ArrayList<>();
            for (WaybillResult r : results) {
                for (BaselineMetric bm : r.baselines) {
                    if (bm.name.equals(name)) crs.add(bm.crLossy);
                }
            }
            if (!crs.isEmpty()) {
                System.out.printf("  %-7s 平均 %.2f%n", name, stats(crs).get("mean"));
            }
        }
    }

    // ------------------------------------------------------------------
    // 输出：CSV / JSON 落盘
    // ------------------------------------------------------------------

    private static void writeWaybillCsv(Path path, List<WaybillResult> results) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("waybill_no,waybill_id,n_raw,n_clean,n_kept,removed_drift,n_stops,n_anchors,")
                .append("cr_lossy,cr_lossless,cr_total,")
                .append("ped_avg_m,ped_max_m,sed_avg_m,sed_max_m,sr_ours,")
                .append("naive_bytes,gzip_naive_bytes,ascii_bytes,zipped_bytes,polyline_bytes,polyline_zipped_bytes,")
                .append("blocks,blocks_hit,partial_bytes_ratio,partial_blocks_ratio,partial_correct,")
                .append("decoded_in_window,full_in_window\n");
        for (WaybillResult r : results) {
            sb.append(String.join(",", new String[]{
                    r.waybillNo, String.valueOf(r.waybillId),
                    String.valueOf(r.nRaw), String.valueOf(r.nClean), String.valueOf(r.nKept),
                    String.valueOf(r.removedDrift), String.valueOf(r.nStops), String.valueOf(r.nAnchors),
                    String.valueOf(round4(r.crLossy)), String.valueOf(round4(r.crLossless)), String.valueOf(round4(r.crTotal)),
                    String.valueOf(round2(r.pedAvgM)), String.valueOf(round2(r.pedMaxM)),
                    String.valueOf(round2(r.sedAvgM)), String.valueOf(round2(r.sedMaxM)), String.valueOf(round4(r.srOurs)),
                    String.valueOf(r.naiveBytes), String.valueOf(r.gzipNaiveBytes),
                    String.valueOf(r.asciiBytes), String.valueOf(r.zippedBytes),
                    String.valueOf(r.polylineBytes), String.valueOf(r.polylineZippedBytes),
                    String.valueOf(r.blocks), String.valueOf(r.blocksHit),
                    String.valueOf(round4(r.partialBytesRatio)), String.valueOf(round4(r.partialBlocksRatio)),
                    String.valueOf(r.partialCorrect),
                    String.valueOf(r.decodedInWindow), String.valueOf(r.fullInWindow)
            })).append('\n');
        }
        Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBaselineCsv(Path path, List<WaybillResult> results) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("waybill_no,baseline,n_kept,cr_lossy,ped_avg_m,ped_max_m,sed_avg_m,sed_max_m,sr\n");
        for (WaybillResult r : results) {
            for (BaselineMetric bm : r.baselines) {
                sb.append(String.join(",", new String[]{
                        r.waybillNo, bm.name, String.valueOf(bm.nKept), String.valueOf(round4(bm.crLossy)),
                        String.valueOf(round2(bm.pedAvgM)), String.valueOf(round2(bm.pedMaxM)),
                        String.valueOf(round2(bm.sedAvgM)), String.valueOf(round2(bm.sedMaxM)), String.valueOf(round4(bm.sr))
                })).append('\n');
            }
        }
        Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeAggregateJson(Path path, List<WaybillResult> results, int skipped,
                                           ExperimentConfig cfg) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("processed", results.size());
        root.put("skipped", skipped);
        root.put("note", "压缩率口径: CR_total = CR_lossy(清洗后点数/保留点数) × CR_lossless(规范文本字节/编码后字节)");

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("cr_total", stats(col(results, r -> r.crTotal)));
        metrics.put("cr_lossy", stats(col(results, r -> r.crLossy)));
        metrics.put("cr_lossless", stats(col(results, r -> r.crLossless)));
        metrics.put("ped_avg_m", stats(col(results, r -> r.pedAvgM)));
        metrics.put("ped_max_m", stats(col(results, r -> r.pedMaxM)));
        metrics.put("sed_avg_m", stats(col(results, r -> r.sedAvgM)));
        metrics.put("sed_max_m", stats(col(results, r -> r.sedMaxM)));
        metrics.put("sr_ours", stats(col(results, r -> r.srOurs)));
        metrics.put("partial_bytes_ratio", stats(col(results, r -> r.partialBytesRatio)));
        metrics.put("partial_blocks_ratio", stats(col(results, r -> r.partialBlocksRatio)));
        metrics.put("zipped_bytes_total", results.stream().mapToLong(r -> r.zippedBytes).sum());
        metrics.put("naive_bytes_total", results.stream().mapToLong(r -> r.naiveBytes).sum());
        root.put("metrics", metrics);

        Map<String, Object> baselines = new LinkedHashMap<>();
        String[] names = {"DP", "DPS", "TD-TR", "Trajic"};
        for (String name : names) {
            Map<String, Object> bmAgg = new LinkedHashMap<>();
            bmAgg.put("cr_lossy", stats(colBaseline(results, name, b -> b.crLossy)));
            bmAgg.put("ped_avg_m", stats(colBaseline(results, name, b -> b.pedAvgM)));
            bmAgg.put("sed_avg_m", stats(colBaseline(results, name, b -> b.sedAvgM)));
            bmAgg.put("sr", stats(colBaseline(results, name, b -> b.sr)));
            baselines.put(name, bmAgg);
        }
        root.put("baselines", baselines);
        JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), root);
    }

    private static List<Double> col(List<WaybillResult> results, java.util.function.ToDoubleFunction<WaybillResult> f) {
        List<Double> out = new ArrayList<>(results.size());
        for (WaybillResult r : results) out.add(f.applyAsDouble(r));
        return out;
    }

    private static List<Double> colBaseline(List<WaybillResult> results, String name,
                                            java.util.function.ToDoubleFunction<BaselineMetric> f) {
        List<Double> out = new ArrayList<>();
        for (WaybillResult r : results) {
            for (BaselineMetric bm : r.baselines) {
                if (bm.name.equals(name)) out.add(f.applyAsDouble(bm));
            }
        }
        return out;
    }

    private static Map<String, Double> stats(List<Double> vals) {
        double[] a = vals.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(a);
        Map<String, Double> m = new LinkedHashMap<>();
        if (a.length == 0) {
            m.put("mean", 0.0);
            m.put("median", 0.0);
            m.put("min", 0.0);
            m.put("max", 0.0);
            return m;
        }
        double sum = 0;
        for (double v : a) sum += v;
        m.put("mean", round4(sum / a.length));
        double med = a.length % 2 == 1
                ? a[a.length / 2]
                : (a[a.length / 2 - 1] + a[a.length / 2]) / 2.0;
        m.put("median", round4(med));
        m.put("min", round4(a[0]));
        m.put("max", round4(a[a.length - 1]));
        return m;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    // ------------------------------------------------------------------
    // 结果数据结构
    // ------------------------------------------------------------------

    /**
     * 单个运单的完整实验指标（对应 results/waybill_metrics.csv 一行）。
     * 由 {@link #processWaybill} 各步骤①~⑧逐段填充。
     */
    static class WaybillResult {
        // ---- 标识与数据量（①清洗 前/后） ----
        /** 运单号（track/waybill 关联键，如 Y260815984581） */
        String waybillNo;
        /** 运单内部 ID */
        long waybillId;
        /** 原始点数（含漂移点），即"接收到的原始数据量" */
        int nRaw;
        /** 清洗后点数（漂移剔除、断线分段后），是有损层压缩的基数 */
        int nClean;
        /** 分级抽稀后保留点数（①→③后） */
        int nKept;
        /** 剔除的漂移点数 */
        int removedDrift;
        /** 识别出的停留单元数（②语义识别，论文 6.2） */
        int nStops;
        /** 关键锚点数 = 停留单元首末点 + 轨迹起止点（②） */
        int nAnchors;

        // ---- 压缩率（⑥论文口径：有损 × 无损） ----
        /** 有损层压缩率 = nClean / nKept（"减的是点数"这一半） */
        double crLossy;
        /** 无损层压缩率 = naiveBytes / zippedBytes（"减每点字节数"这一半） */
        double crLossless;
        /** 总压缩率 = crLossy × crLossless */
        double crTotal;

        // ---- 有损层误差与语义保真（④） ----
        /** PED 均值：原始点到压缩折线的垂直距离，米 */
        double pedAvgM;
        /** PED 最大值，米（本文恒 ≤ 移动段容差 ε=10m） */
        double pedMaxM;
        /** SED 均值：按时间比例插值同步点后的距离，米 */
        double sedAvgM;
        /** SED 最大值，米 */
        double sedMaxM;
        /** 语义点保留率 = 保留锚点数 / 锚点总数；本文锚点强制保留，恒为 1.0 */
        double srOurs;

        // ---- 无损层各编码方式字节（⑤，同一保留点序列） ----
        /** 朴素文本基准字节（每字段 8 位小数 + gtm），无损层压缩前的"原始字节" */
        long naiveBytes;
        /** 通用 gzip 压缩朴素文本后的字节（通用熵编码基线） */
        long gzipNaiveBytes;
        /** 本文差分+ZigZag+5位分片（未 zip）字节 */
        long asciiBytes;
        /** 本文存储形态 = ascii + DEFLATE 的总字节 */
        long zippedBytes;
        /** Google Polyline 编码字节（仅经纬度 2 字段，1e8 精度） */
        long polylineBytes;
        /** Polyline + DEFLATE 字节 */
        long polylineZippedBytes;

        // ---- 时间索引部分解压（⑦） ----
        /** 总块数（稀疏时间索引行数，块长由 blockWindowS 决定，当前 1h） */
        int blocks;
        /** 查询窗命中的块数 */
        int blocksHit;
        /** 实际读取的命中块字节数 */
        int bytesRead;
        /** 编码后总字节（与 zippedBytes 相同，供比率分母） */
        long totalZipBytes;
        /** 命中块字节 / 总字节（部分解压字节收益，均值约 0.19） */
        double partialBytesRatio;
        /** 命中块数 / 总块数 */
        double partialBlocksRatio;
        /** 部分解压正确性：窗口内取回点数 == 全量解压同窗口点数 */
        boolean partialCorrect;
        /** 部分解压取回的窗口内点数 */
        int decodedInWindow;
        /** 全量解压后同窗口内的点数（正确性基准） */
        int fullInWindow;

        /** 有损层 4 种基线（⑧DP/DPS/TD-TR/Trajic）的同口径指标 */
        List<BaselineMetric> baselines = new ArrayList<>();
    }

    /**
     * 单个基线算法在单个运单上的有损层指标（对应 results/baseline_metrics.csv 一行）。
     * 由第⑧步循环填充，与本文方法共用 Metrics.pedSed / semanticRetention，口径一致。
     */
    static class BaselineMetric {
        /** 基线名称：DP / DPS / TD-TR / Trajic */
        String name;
        /** 该基线抽稀后保留点数 */
        int nKept;
        /** 有损层压缩率 = nClean / nKept */
        double crLossy;
        /** PED 均值，米 */
        double pedAvgM;
        /** PED 最大值，米 */
        double pedMaxM;
        /** SED 均值，米 */
        double sedAvgM;
        /** SED 最大值，米 */
        double sedMaxM;
        /** 语义点保留率（用与本文相同的锚点集合评测，无语义基线一般 < 1） */
        double sr;
    }
}
