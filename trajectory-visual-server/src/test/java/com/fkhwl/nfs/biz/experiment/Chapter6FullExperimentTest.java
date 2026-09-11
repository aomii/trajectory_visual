package com.fkhwl.nfs.biz.experiment;

import com.fkhwl.nfs.biz.service.visual.impl.Chapter6ExperimentRunner;
import com.fkhwl.nfs.config.ExperimentProperties;
import com.fkhwl.nfs.config.VisualProperties;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 第 6 章实验【全量跑】入口 —— 手动运行用。
 *
 * <p>与 {@link TrajectoryChapter6ExperimentTest} 的区别：本类用 {@code @TestPropertySource}
 * 强制 {@code waybill-limit=-1}，**不受 application-local.yml 里试跑上限的影响**，
 * 永远对源目录全量运单跑一遍；并在开头/结尾打印数据规模与总耗时，便于判断进度。
 *
 * <p>数据源：{@code trajectory.source.full-data-dir}（当前 source_data_full，已并入 track_data
 * 的 133 个运单并剔除 &lt;100 点的轨迹，共 5224 个）。全量约 45–70 分钟，
 * 期间每 200 个运单打印一次进度（已处理/总数/已耗时/预计剩余）。
 *
 * <p><b>运行方式</b>
 * <ul>
 *   <li>IDEA：右键本类（或 runFullChapter6Experiments 方法）→ Run</li>
 *   <li>命令行：
 * <pre>
 *   mvn -s D:\develop\apache-maven-3.8.2\conf\settings-tuling.xml ^
 *       -o test -Dtest=Chapter6FullExperimentTest
 * </pre>
 *   </li>
 * </ul>
 *
 * <p>前置：本地 MySQL(ml_network_freight) 与 MongoDB 已启动、{@code sql/init.sql} 已建表。
 * 结果写 MySQL：{@code trajectory_eval_run} 起 6 张 {@code trajectory_eval_*} 表
 * （6.2 语义识别 / 6.3 有损层对比 / 6.4 无损编码 / 6.5 部分解压 / 6.6 消融 / 参数敏感性）。
 * 跑完后由论文侧读取这些表回填第 6 章数字。
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "trajectory.experiment.waybill-limit=-1",
        "trajectory.experiment.waybill-offset=0"
})
public class Chapter6FullExperimentTest {

    private static final Logger log = LoggerFactory.getLogger(Chapter6FullExperimentTest.class);

    @Autowired
    private Chapter6ExperimentRunner runner;
    @Autowired
    private VisualProperties vprops;
    @Autowired
    private ExperimentProperties eprops;

    /** 全量跑第 6 章 6.2–6.6 + 参数敏感性（每项一个独立批次） */
    @Test
    public void runFullChapter6Experiments() {
        printBanner();
        long t0 = System.currentTimeMillis();
        runner.runAllExperiments();
        long sec = (System.currentTimeMillis() - t0) / 1000;
        log.info("== 全量实验全部完成，总耗时 {} 分 {} 秒 ==", sec / 60, sec % 60);
        log.info("== 结果已写 MySQL trajectory_eval_* 表，可到可视化系统 /dashboard 查看 ==");
    }

    /** 只跑 6.3 主实验（有损层对比）——数字不对时可单独复跑，省时间 */
    @Test
    public void runLossyCompareOnly() {
        printBanner();
        long t0 = System.currentTimeMillis();
        log.info("批次: {}", runner.runLossyCompressionCompare());
        log.info("== 6.3 完成，耗时 {}s ==", (System.currentTimeMillis() - t0) / 1000);
    }

    /** 只跑 6.5 部分解压（分片时长 1h 口径下的读取比例/一致性） */
    @Test
    public void runPartialDecompressionOnly() {
        printBanner();
        long t0 = System.currentTimeMillis();
        log.info("批次: {}", runner.runPartialDecompression());
        log.info("== 6.5 完成，耗时 {}s ==", (System.currentTimeMillis() - t0) / 1000);
    }

    /** 开跑前把数据规模和关键参数打出来，避免"跑到一半才发现读错目录" */
    private void printBanner() {
        Path dir = Paths.get(vprops.getSource().getFullDataDir());
        long n = -1;
        try {
            if (Files.isDirectory(dir)) {
                try (java.util.stream.Stream<Path> s = Files.list(dir)) {
                    n = s.filter(p -> p.getFileName().toString().startsWith("track_")).count();
                }
            }
        } catch (Exception ignored) {
            // 目录不可读时交给 runner 抛错
        }
        log.info("================ 第 6 章实验（全量） ================");
        log.info("源目录        : {}", dir);
        log.info("运单文件数    : {}", n < 0 ? "目录不可读" : n);
        log.info("分片时长      : {} 秒/片", eprops.getBlockWindowS());
        log.info("量化位数      : {}（坐标误差 ≤ 7.5cm 量级）", eprops.getPrecision());
        log.info("移动段DP容差  : {} m；基线容差 {} m",
                eprops.getDpMoveToleranceM(), eprops.getBaselineToleranceM());
        log.info("预计耗时      : 45–70 分钟（每 {} 个运单打印一次进度）", 200);
        log.info("===================================================");
    }
}
