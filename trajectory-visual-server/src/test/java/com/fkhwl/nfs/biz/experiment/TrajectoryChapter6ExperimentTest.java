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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 第 6 章实验测试启动类（规格书 §9）。
 *
 * <p>对应论文：候选单元识别 / 三层压缩评估 / 无损层编码 / 部分解压 /
 * 6.6 消融 + 参数敏感性。每个实验独立 @Test（可单独复跑），总入口 {@link #runAllExperiments()}。
 *
 * <p>这是第 6 章数据库实验的唯一入口。默认配置已为全量（{@code waybill-limit=-1}）；
 * 若需试跑，可在 IDEA VM options 或 Maven 中设置 {@code -Dtrajectory.experiment.waybill-limit=50}。
 * 数据源：trajectory.source.full-data-dir（application-local.yml，当前 source_data_full）；
 * 运单范围受 trajectory.experiment.waybill-limit / waybill-offset 约束（-1=全部）。
 * 每个方法生成一个 trajectory_eval_run 批次并把结果写 MySQL；逐运单失败写 error_record，不中断整批。
 *
 * <p>【口径】DP 容差统一 10m（2026-09-09 用户定稿）：本文移动段与 DP/DPS/TD-TR/Trajic 基线一致。
 * 运行前请确认本地 MySQL 已建表（sql/init.sql）与源目录存在。
 *
 * <p>运行方式（激活 local profile）：
 * <pre>
 *   mvn -s D:\develop\apache-maven-3.8.2\conf\settings-tuling.xml \
 *       -Dspring.profiles.active=local test -Dtest=TrajectoryChapter6ExperimentTest#runLossyCompressionCompareExperiment
 * </pre>
 * 或 IDE 中直接运行本类单个 @Test。
 */
@SpringBootTest
@ActiveProfiles("local")
public class TrajectoryChapter6ExperimentTest {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryChapter6ExperimentTest.class);

    @Autowired
    private Chapter6ExperimentRunner runner;
    @Autowired
    private VisualProperties vprops;
    @Autowired
    private ExperimentProperties eprops;

    /** 一键运行全部第 6 章实验（每个实验独立批次；默认全量，建议先限制 waybill-limit 试跑）。 */
    @Test
    public void runAllExperiments() {
        printBanner();
        runner.runAllExperiments();
    }

    /** 6.2 语义识别结果与分析：静态同坐标停留识别聚合（停留单元数/REST分布/锚点数） */
    @Test
    public void runStopRecognitionExperiment() {
        log.info("== 6.2 语义识别（静态同坐标停留） ==");
        String runNo = runner.runStopRecognition();
        log.info("批次: {}", runNo);
    }

    /** 主实验：有损层比较 + 所有方法叠加相同分块编码器后的端到端比较。 */
    @Test
    public void runLossyCompressionCompareExperiment() {
        printBanner();
        log.info("== 多算法三层评估（统一分块编码器） ==");
        String runNo = runner.runLossyCompressionCompare();
        log.info("批次: {}", runNo);
    }

    /** 6.4 无损层：分块偏移量可变长编码对比（独立复跑批次） */
    @Test
    public void runLosslessEncodingExperiment() {
        log.info("== 6.4 无损层编码 ==");
        String runNo = runner.runLosslessEncoding();
        log.info("批次: {}", runNo);
    }

    /** 6.5 时间索引与部分解压（独立复跑批次） */
    @Test
    public void runPartialDecompressionExperiment() {
        printBanner();
        log.info("== 6.5 时间索引与部分解压 ==");
        String runNo = runner.runPartialDecompression();
        log.info("批次: {}", runNo);
    }

    /** 6.6.1 消融实验：A0 / A-TIGHT / A2 / A3 / A4 */
    @Test
    public void runAblationExperiment() {
        log.info("== 6.6 消融实验 ==");
        String runNo = runner.runAblation();
        log.info("批次: {}", runNo);
    }

    /** 参数敏感性：DP 容差扫描 + chunk 时长扫描 */
    @Test
    public void runParameterSensitivityExperiment() {
        log.info("== 参数敏感性（DP容差/chunk时长扫描） ==");
        String runNo = runner.runParameterSensitivity();
        log.info("批次: {}", runNo);
    }

    /** 输出全量/子集范围和关键口径，避免误读运行参数。 */
    private void printBanner() {
        Path dir = Paths.get(vprops.getSource().getFullDataDir());
        long files = -1;
        try {
            if (Files.isDirectory(dir)) {
                try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
                    files = stream.filter(p -> p.getFileName().toString().startsWith("track_")).count();
                }
            }
        } catch (Exception ignored) {
            // 路径异常交由实际实验抛出，banner 只负责辅助诊断。
        }
        log.info("================ 第 6 章实验 ================");
        log.info("源目录        : {}", dir);
        log.info("轨迹文件数    : {}", files < 0 ? "目录不可读" : files);
        log.info("运单上限      : {}（-1 表示全量）", eprops.getWaybillLimit());
        log.info("分片时长      : {} 秒；量化位数 {}", eprops.getBlockWindowS(), eprops.getPrecision());
        log.info("本文/基线容差 : {} m / {} m", eprops.getDpMoveToleranceM(), eprops.getBaselineToleranceM());
        log.info("=============================================");
    }
}
