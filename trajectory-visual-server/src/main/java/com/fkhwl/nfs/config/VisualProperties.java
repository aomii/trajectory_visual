package com.fkhwl.nfs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可视化系统自有配置（application-local.yml 的 trajectory.* 段）。
 * 老项目风格：配置键集中在自有前缀下，业务代码不硬编码路径。
 */
@ConfigurationProperties(prefix = "trajectory")
public class VisualProperties {

    /** 全量轨迹源配置 */
    private Source source = new Source();
    /** 导出目录配置 */
    private Export export = new Export();
    /** 压缩/任务线程池 */
    private Job job = new Job();
    /** 坐标系口径 */
    private Coord coord = new Coord();

    /** 全量轨迹源（原始轨迹文件，GCJ-02） */
    public static class Source {
        /** 全量轨迹源目录，例如 D:/.../trajectory-visual-server/source_data_full */
        private String fullDataDir;
        /** 文件名模式：track_{运单号}_{waybillId}.json */
        private String filePattern = "track_*.json";
        /** 元数据导入/全量压缩单次扫描上限：-1=全部（7709）；演示可给上限 */
        private int maxFilesPerJob = -1;

        public String getFullDataDir() { return fullDataDir; }
        public void setFullDataDir(String fullDataDir) { this.fullDataDir = fullDataDir; }
        public String getFilePattern() { return filePattern; }
        public void setFilePattern(String filePattern) { this.filePattern = filePattern; }
        public int getMaxFilesPerJob() { return maxFilesPerJob; }
        public void setMaxFilesPerJob(int maxFilesPerJob) { this.maxFilesPerJob = maxFilesPerJob; }
    }

    /**
     * 坐标系口径。
     *
     * <p>源 JSON（source_data_full）与业务库 waybill 的收发货地址坐标**已经是 GCJ-02**
     * （入湖前做过一次 WGS84→GCJ-02，2026-09-10 用户确认）。因此展示层不得再转一次，
     * 否则会整体偏移约 300~600m，且与高德底图、收发货地址标记对不上。
     * 若日后换成真正的 WGS84 源，把 source-already-gcj02 改为 false 即可恢复转换。
     */
    public static class Coord {
        /** true=源数据已是 GCJ-02，展示层不做转换（默认）；false=源为 WGS84，展示时转 GCJ-02 */
        private boolean sourceAlreadyGcj02 = true;

        public boolean isSourceAlreadyGcj02() { return sourceAlreadyGcj02; }
        public void setSourceAlreadyGcj02(boolean sourceAlreadyGcj02) { this.sourceAlreadyGcj02 = sourceAlreadyGcj02; }
    }

    /** 导出目录（Dashboard PNG / 指标 CSV） */
    public static class Export {
        private String dashboardDir;
        private String csvDir;

        public String getDashboardDir() { return dashboardDir; }
        public void setDashboardDir(String dashboardDir) { this.dashboardDir = dashboardDir; }
        public String getCsvDir() { return csvDir; }
        public void setCsvDir(String csvDir) { this.csvDir = csvDir; }
    }

    /** 后台任务线程池 */
    public static class Job {
        private int poolSize = 1;

        public int getPoolSize() { return poolSize; }
        public void setPoolSize(int poolSize) { this.poolSize = poolSize; }
    }

    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
    public Export getExport() { return export; }
    public void setExport(Export export) { this.export = export; }
    public Job getJob() { return job; }
    public void setJob(Job job) { this.job = job; }
    public Coord getCoord() { return coord; }
    public void setCoord(Coord coord) { this.coord = coord; }
}
