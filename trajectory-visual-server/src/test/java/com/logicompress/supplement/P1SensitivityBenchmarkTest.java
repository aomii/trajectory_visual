package com.logicompress.supplement;

import com.logicompress.experiment.config.ExperimentConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** Database-free P1 sensitivity benchmark. */
public class P1SensitivityBenchmarkTest {
    @Test
    public void runFullData() throws Exception {
        Path source = Paths.get(System.getProperty("p1.sourceDir", "../00数据处理-260909/source_data_full"));
        Path output = Paths.get(System.getProperty("p1.outDir", "src/main/resources/export/supplement"));
        int limit = Integer.getInteger("p1.limit", 0);
        ExperimentConfig cfg = new ExperimentConfig();
        cfg.stationarySameM = 10; cfg.dMinS = 180;
        cfg.dbscanEpsM = 50; cfg.dbscanEpsTS = 300; cfg.dbscanMinPts = 3;
        cfg.driftSpeedKph = 120; cfg.breakGapS = 600;
        List<SupplementContext.WaybillCtx> ctxs = SupplementContext.load(
                cfg, source, SupplementContext.emptyMeta(), limit);
        P1SensitivityBenchmark.run(cfg, ctxs, output);
    }
}
