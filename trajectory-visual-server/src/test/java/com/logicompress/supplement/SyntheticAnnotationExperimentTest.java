package com.logicompress.supplement;

import com.logicompress.experiment.config.ExperimentConfig;
import com.logicompress.experiment.model.StopUnit;
import com.logicompress.experiment.model.TrackPoint;
import com.logicompress.experiment.semantic.StationaryMerger;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** 确定性合成情景：验证事件匹配流程，不充当企业轨迹的人工标注真值。 */
public class SyntheticAnnotationExperimentTest {
    private static final long SEED = 20260925L;
    private static final int REPLICATES = 30;

    @Test
    public void runSyntheticAnnotationExperiment() throws Exception {
        ExperimentConfig cfg = new ExperimentConfig();
        Random random = new Random(SEED);
        Path dir = Paths.get(System.getProperty("synthetic.outDir", "target/synthetic-annotation"));
        Files.createDirectories(dir);
        List<String> details = new ArrayList<>();
        details.add("scenario,replicate,truth_start_s,truth_end_s,static_start_s,static_end_s,dbscan_start_s,dbscan_end_s");
        List<String> summary = new ArrayList<>();
        summary.add("scenario,method,trajectories,truth_events,predicted_events,TP,FP,FN,precision,recall,F1");
        String[] scenarios = {"dense_180s", "heartbeat_300s", "sparse_900s", "return_after_unobserved_gap"};
        for (String scenario : scenarios) {
            int[] staticCounts = new int[3], dbCounts = new int[3];
            int truthCount = 0;
            for (int rep = 0; rep < REPLICATES; rep++) {
                SyntheticCase sample = generate(scenario, rep, random);
                List<StopUnit> a = StationaryMerger.merge(sample.points, cfg);
                List<StopUnit> b = StopRecognitionCompare.dbscanDetect(sample.points, cfg);
                count(sample.truth, a, staticCounts);
                count(sample.truth, b, dbCounts);
                truthCount += sample.truth.size();
                details.add(String.format(Locale.ROOT, "%s,%d,%s,%s,%s,%s,%s,%s", scenario, rep,
                        firstStart(sample.truth), firstEnd(sample.truth), firstStart(a), firstEnd(a),
                        firstStart(b), firstEnd(b)));
            }
            summary.add(row(scenario, "stationary_merge", truthCount, staticCounts));
            summary.add(row(scenario, "st_dbscan", truthCount, dbCounts));
        }
        Files.write(dir.resolve("simulated_labels_and_predictions.csv"), details, StandardCharsets.UTF_8);
        Files.write(dir.resolve("simulated_event_metrics.csv"), summary, StandardCharsets.UTF_8);
        Files.write(dir.resolve("README.md"), Arrays.asList(
                "# 合成情景事件验证（非人工标注）", "",
                "随机种子：" + SEED + "；每情景 " + REPLICATES + " 条轨迹。事件一对一匹配：时间交并比（IoU）≥ 0.5。",
                "dense_180s：真实静止 15 分钟、180 秒心跳；heartbeat_300s：真实静止 60 分钟、300 秒心跳；",
                "sparse_900s：真实静止 60 分钟、900 秒心跳；return_after_unobserved_gap：两次同位置零速上报间隔 60 分钟，",
                "隐含过程为离开后返回，真值中无连续静止事件。移动前后各有一个非零速点。GPS 坐标扰动由固定种子生成。",
                "有真值但无预测时约定 Precision/F1 为 0；无真值时 Recall/F1 记 NA。",
                "本实验只验证在预设机制下的输出特征与失联误并风险，不评估企业轨迹真实停留识别精度。"), StandardCharsets.UTF_8);
    }

    private static SyntheticCase generate(String scenario, int rep, Random random) {
        long base = 1_700_000_000L + rep * 10_000L;
        double lat = 30 + random.nextDouble(), lon = 110 + random.nextDouble();
        List<TrackPoint> points = new ArrayList<>();
        points.add(point(lat - .01, lon - .01, base - 30, 40));
        long duration = scenario.equals("dense_180s") ? 900 : 3600;
        long interval = scenario.equals("dense_180s") ? 180 : scenario.equals("sparse_900s") ? 900 : 300;
        if (scenario.equals("return_after_unobserved_gap")) interval = 3600;
        for (long t = 0; t <= duration; t += interval) {
            double jitter = random.nextDouble() * .000002;
            points.add(point(lat + jitter, lon + jitter, base + t, 0));
        }
        points.add(point(lat + .01, lon + .01, base + duration + 30, 40));
        List<StopUnit> truth = new ArrayList<>();
        if (!scenario.equals("return_after_unobserved_gap")) {
            StopUnit u = new StopUnit();
            u.startIdx = 1; u.endIdx = points.size() - 2;
            u.tStart = base; u.tEnd = base + duration;
            truth.add(u);
        }
        return new SyntheticCase(points, truth);
    }

    private static TrackPoint point(double lat, double lon, long time, double speed) {
        TrackPoint p = new TrackPoint(lat, lon, time);
        p.spdKph = speed;
        return p;
    }

    /** counts = TP, FP, FN; greedy one-to-one overlap matching, fixed temporal order. */
    private static void count(List<StopUnit> truth, List<StopUnit> predicted, int[] counts) {
        boolean[] used = new boolean[truth.size()];
        for (StopUnit p : predicted) {
            boolean matched = false;
            for (int j = 0; j < truth.size(); j++) {
                if (!used[j] && temporalIoU(p, truth.get(j)) >= 0.5) {
                    used[j] = true;
                    matched = true;
                    break;
                }
            }
            counts[matched ? 0 : 1]++;
        }
        for (boolean m : used) if (!m) counts[2]++;
    }

    private static double temporalIoU(StopUnit a, StopUnit b) {
        long intersection = Math.max(0, Math.min(a.tEnd, b.tEnd) - Math.max(a.tStart, b.tStart));
        long union = Math.max(a.tEnd, b.tEnd) - Math.min(a.tStart, b.tStart);
        return union <= 0 ? 0 : (double) intersection / union;
    }

    private static String row(String scenario, String method, int truth, int[] c) {
        int predicted = c[0] + c[1];
        String precision = predicted == 0 ? (truth == 0 ? "NA" : "0.0000")
                : String.format(Locale.ROOT, "%.4f", (double) c[0] / predicted);
        String recall = truth == 0 ? "NA" : String.format(Locale.ROOT, "%.4f", (double) c[0] / truth);
        String f1;
        if (truth == 0) {
            f1 = "NA";
        } else if (predicted == 0) {
            f1 = "0.0000";
        } else {
            double p = (double) c[0] / predicted, r = (double) c[0] / truth;
            f1 = String.format(Locale.ROOT, "%.4f", p + r == 0 ? 0 : 2 * p * r / (p + r));
        }
        return String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%d,%s,%s,%s", scenario,
                method, REPLICATES, truth, predicted, c[0], c[1], c[2], precision, recall, f1);
    }

    private static String firstStart(List<StopUnit> events) {
        return events.isEmpty() ? "" : String.valueOf(events.get(0).tStart);
    }

    private static String firstEnd(List<StopUnit> events) {
        return events.isEmpty() ? "" : String.valueOf(events.get(0).tEnd);
    }

    private static class SyntheticCase {
        final List<TrackPoint> points;
        final List<StopUnit> truth;
        SyntheticCase(List<TrackPoint> points, List<StopUnit> truth) {
            this.points = points;
            this.truth = truth;
        }
    }
}
