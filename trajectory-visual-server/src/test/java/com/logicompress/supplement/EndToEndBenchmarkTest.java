package com.logicompress.supplement;

import com.logicompress.experiment.config.ExperimentConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** 不依赖数据库的统一编码端到端对比复现实验。 */
public class EndToEndBenchmarkTest {

    @Test
    public void runFullData() throws Exception {
        // IDEA/Maven 的工作目录是 trajectory-visual-server，需返回两级到论文根目录。
        Path source = Paths.get(System.getProperty("e2e.sourceDir",
                "../../00数据处理-260909/source_data_full"));
        Path output = Paths.get(System.getProperty("e2e.outDir",
                "src/main/resources/export/supplement"));
        int limit = Integer.getInteger("e2e.limit", 0);

        ExperimentConfig cfg = new ExperimentConfig();
        cfg.dpEpsNonKeyM = 10.0;
        cfg.baselineDpM = 10.0;
        cfg.stationarySameM = 10.0;
        cfg.dMinS = 180;
        cfg.driftSpeedKph = 120.0;
        cfg.breakGapS = 600;
        cfg.blockWindowS = 3600;
        cfg.queryWindowS = 3600;
        cfg.precision = 6;
        cfg.zipLevel = 6;

        List<SupplementContext.WaybillCtx> ctxs = SupplementContext.load(
                cfg, source, SupplementContext.emptyMeta(), limit);
        if (!Files.isDirectory(source) || ctxs.isEmpty()) {
            throw new IllegalStateException("未加载到有效运单，sourceDir=" + source.toAbsolutePath()
                    + "。请检查目录，或在 IDEA VM options 中设置 -De2e.sourceDir=<source_data_full绝对路径>。");
        }
        System.out.println("[e2e] 已加载有效运单 " + ctxs.size() + " 条，源目录=" + source.toAbsolutePath());
        EndToEndBenchmark.run(cfg, ctxs, output);
    }
}
