package com.logicompress.supplement;

import com.fkhwl.nfs.config.ExperimentProperties;
import com.fkhwl.nfs.config.VisualProperties;
import com.logicompress.experiment.config.ExperimentConfig;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 补充实验【手动运行】入口 —— 产出论文第 6 章里主实验覆盖不到的那几张表。
 *
 * <p>结果直接写到 {@code src/main/resources/export/supplement/}（即
 * {@code D:\aoming\电科\毕业论文 - claude\05可视化系统\trajectory-visual-server\src\main\resources\export\supplement}），
 * 每张表同时给 {@code .csv}（数据，可复核）与 {@code .md}（论文可直接粘贴的表格）两种格式。
 *
 * <p><b>产出的表 ↔ 论文表号</b>
 * <pre>
 *   table6_7_static_vs_dbscan        → 表 6-7  静态合并 vs ST-DBSCAN
 *   table6_12_lossless_summary       → 表 6-12 无损层压缩结果
 *   table6_13_bench_summary          → 表 6-13 编码/解码耗时基准
 *   table6_14_entropy_summary        → 表 6-14 偏移量经验熵 vs 编码后字节率
 *   table6_16_block_tradeoff         → 表 6-16 块长权衡
 *   table6_18_hull_summary           → 表 6-18 凸包保形度与压缩失真速度比
 * </pre>
 * （表 6-19 方案 A/B 已按用户 2026-09-11 决定取消，不再产出。）
 *
 * <p><b>前置</b>：本地 MySQL(ml_network_freight) 已启动，且 {@code trajectory_waybill} 已导入运单
 * （收发坐标与装卸货时间供表 6-7 的围栏对照、表 6-18 的凸包控制点使用）。源目录取
 * {@code trajectory.source.full-data-dir}。全量 5224 运单预计数分钟到十几分钟。
 *
 * <p><b>运行方式</b>
 * <ul>
 *   <li>IDEA：右键本类（或某个方法）→ Run</li>
 *   <li>命令行：
 * <pre>
 *   mvn -s D:\develop\apache-maven-3.8.2\conf\settings-tuling.xml ^
 *       -o test -Dtest=SupplementExperimentTest
 * </pre>
 *   </li>
 * </ul>
 */
@SpringBootTest(classes = com.fkhwl.nfs.TrajectoryVisualApplication.class)
@ActiveProfiles("local")
public class SupplementExperimentTest {

    private static final Logger log = LoggerFactory.getLogger(SupplementExperimentTest.class);

    /** 导出目录（相对模块根；绝对路径见类注释）。可用 -Dsupplement.outDir=... 覆盖 */
    private static final Path OUT_DIR = Paths.get(
            System.getProperty("supplement.outDir", "src/main/resources/export/supplement"));

    /**
     * 运单上限（&lt;=0 全部）。试跑用：{@code -Dsupplement.limit=200}。
     * 注意试跑结果会写进同一个导出目录，正式出表前请用全量重跑一次。
     */
    private static int limit() {
        return Integer.getInteger("supplement.limit", 0);
    }

    @Autowired
    private ExperimentProperties eprops;
    @Autowired
    private VisualProperties vprops;
    @Autowired
    private JdbcTemplate jdbc;

    /** 一键跑全部补充实验（推荐） */
    @Test
    public void runAllSupplementExperiments() throws Exception {
        SupplementRunner.runAll(buildConfig(), sourceDir(), loadMeta(), limit(), OUT_DIR);
        listExported();
    }

    /** 只跑表 6-7（静态合并 vs ST-DBSCAN） */
    @Test
    public void runStopRecognitionCompare() throws Exception {
        List<SupplementContext.WaybillCtx> ctxs =
                SupplementContext.load(buildConfig(), sourceDir(), loadMeta(), limit());
        StopRecognitionCompare.run(buildConfig(), ctxs, OUT_DIR);
        listExported();
    }

    /** 只跑表 6-12/6-13（无损层规模 + 编解码耗时基准） */
    @Test
    public void runLosslessBenchmark() throws Exception {
        List<SupplementContext.WaybillCtx> ctxs =
                SupplementContext.load(buildConfig(), sourceDir(), loadMeta(), limit());
        LosslessBenchmark.runSize(buildConfig(), ctxs, OUT_DIR);
        LosslessBenchmark.runBench(buildConfig(), ctxs, OUT_DIR);
        listExported();
    }

    /** 完整组合方案：所有有损方法叠加同一分块无损编码器。 */
    @Test
    public void runEndToEndBenchmark() throws Exception {
        List<SupplementContext.WaybillCtx> ctxs =
                SupplementContext.load(buildConfig(), sourceDir(), loadMeta(), limit());
        EndToEndBenchmark.run(buildConfig(), ctxs, OUT_DIR);
        listExported();
    }

    /** 只跑表 6-14（偏移量经验熵） */
    @Test
    public void runEntropyBenchmark() throws Exception {
        List<SupplementContext.WaybillCtx> ctxs =
                SupplementContext.load(buildConfig(), sourceDir(), loadMeta(), limit());
        EntropyBenchmark.run(buildConfig(), ctxs, OUT_DIR);
        listExported();
    }

    /** 只跑表 6-16（块长权衡） */
    @Test
    public void runBlockLengthSweep() throws Exception {
        List<SupplementContext.WaybillCtx> ctxs =
                SupplementContext.load(buildConfig(), sourceDir(), loadMeta(), limit());
        BlockLengthSweep.run(buildConfig(), ctxs, OUT_DIR);
        listExported();
    }

    /** 只跑表 6-18（凸包保形度与压缩失真速度比） */
    @Test
    public void runHullShapeBenchmark() throws Exception {
        List<SupplementContext.WaybillCtx> ctxs =
                SupplementContext.load(buildConfig(), sourceDir(), loadMeta(), limit());
        HullShapeBenchmark.run(buildConfig(), ctxs, OUT_DIR);
        listExported();
    }

    // ------------------------------------------------------------------

    /** 与第 6 章主实验同一份口径（参数全来自 trajectory.experiment.*，不在此另设默认值） */
    private ExperimentConfig buildConfig() {
        return com.fkhwl.nfs.biz.experiment.ExperimentConfigs.from(eprops);
    }

    private Path sourceDir() {
        return Paths.get(vprops.getSource().getFullDataDir());
    }

    /**
     * 从 MySQL 取运单业务元数据：收发坐标（GCJ-02，与轨迹同系）与装卸货时间。
     * 表 6-7 的"与围栏装卸货单元时间重叠数"、表 6-18 的凸包控制点依赖它；
     * trajectory_waybill 为空时这两处会退化为 0，其余实验不受影响。
     */
    private Map<Long, SupplementContext.WaybillMeta> loadMeta() {
        Map<Long, SupplementContext.WaybillMeta> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT waybill_id, send_lat, send_lon, receive_lat, receive_lon, load_time, unload_time"
                        + " FROM trajectory_waybill");
        for (Map<String, Object> r : rows) {
            SupplementContext.WaybillMeta m = new SupplementContext.WaybillMeta();
            m.waybillId = ((Number) r.get("waybill_id")).longValue();
            m.sendLat = num(r.get("send_lat"));
            m.sendLon = num(r.get("send_lon"));
            m.receiveLat = num(r.get("receive_lat"));
            m.receiveLon = num(r.get("receive_lon"));
            m.loadEpoch = epoch(r.get("load_time"));
            m.unloadEpoch = epoch(r.get("unload_time"));
            out.put(m.waybillId, m);
        }
        long withSend = out.values().stream().filter(x -> x.hasSend() || x.hasReceive()).count();
        log.info("业务元数据装载：{} 条，其中含收发坐标 {} 条", out.size(), withSend);
        if (out.isEmpty()) {
            log.warn("trajectory_waybill 为空：表 6-7 的围栏重叠数、表 6-18 的凸包控制点会退化为 0。"
                    + "请先在工作台执行一次“导入运单源数据”。");
        }
        return out;
    }

    private static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }

    /**
     * 业务时间 → 北京时间 epoch 秒。
     * 注意：MySQL Connector/J 8.x 下 {@code queryForList} 对 DATETIME 列返回的是
     * {@code java.time.LocalDateTime}（不是 {@code java.sql.Timestamp}），这里必须都兼容——
     * 只判 Timestamp 会静默拿到 0，导致围栏时窗永远匹配不上、表 6-7 的围栏重叠数恒为 0。
     */
    private static long epoch(Object o) {
        if (o == null) return 0;
        java.time.LocalDateTime ldt;
        if (o instanceof java.time.LocalDateTime) {
            ldt = (java.time.LocalDateTime) o;
        } else if (o instanceof Timestamp) {
            ldt = ((Timestamp) o).toLocalDateTime();
        } else if (o instanceof java.util.Date) {
            return ((java.util.Date) o).getTime() / 1000;
        } else if (o instanceof CharSequence) {
            try {
                ldt = java.time.LocalDateTime.parse(o.toString().replace(' ', 'T'));
            } catch (Exception e) {
                return 0;
            }
        } else {
            return 0;
        }
        return ldt.atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond();
    }

    private void listExported() throws Exception {
        if (!Files.isDirectory(OUT_DIR)) return;
        List<String> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(OUT_DIR)) {
            s.sorted().forEach(p -> files.add(p.getFileName().toString()));
        }
        log.info("== 导出目录 {} ==", OUT_DIR.toAbsolutePath());
        for (String f : files) log.info("   {}", f);
    }
}
