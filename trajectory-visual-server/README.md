# trajectory-visual-server（后端）

硕士论文第 5.3 节"可视化子系统"的**在线化版本**后端。职责：

- 运单元数据导入与分页查询（按运单号/车牌/车辆/时间范围）。
- 原始轨迹 / 压缩后轨迹 / Mongo 分片查询与地图展示数据（GCJ-02；源数据本身即 GCJ-02，展示层不再转换）。
- 单运单压缩与"全量轨迹压缩"后台任务：压缩分片**只写 MongoDB**。
- 时间窗口部分解压检索（只解命中分片）。
- 第 6 章实验离线运行（`TrajectoryChapter6ExperimentTest`）并把结果**写 MySQL**；前端评估页面读 MySQL 展示。

> 参考老项目 `fkh-network-freight` 的分层与命名风格，但替换其依赖的自研 `com.fkhwl.starter.*` 为
> Spring Boot + MyBatis-Plus + Spring Data MongoDB + 自写 `Result<T>`（老框架依赖内网 Nexus，无法脱离环境构建）。

## 1. 前置环境

- JDK 8+（本地实测：Maven 3.8.2 + JDK 11 编译 target=1.8 通过）
- Maven（本地 `D:\develop\apache-maven-3.8.2`，使用 `-s ...\conf\settings-tuling.xml` 镜像）
- MySQL `127.0.0.1:3306`（账号 `root/root`，库 `ml_network_freight`）
- MongoDB `127.0.0.1:27017`（库 `ml_network_freight`，无密码）
- 全量轨迹源目录（配置项 `trajectory.source.full-data-dir`）

## 2. 配置说明（application-local.yml）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `spring.datasource.*` | 127.0.0.1:3306/ml_network_freight root/root | MySQL |
| `spring.data.mongodb.uri` | mongodb://127.0.0.1:27017/ml_network_freight | Mongo（只存压缩 chunk） |
| `trajectory.source.full-data-dir` | `D:/aoming/电科/毕业论文 - claude/00数据处理-260909/source_data_full` | 全量轨迹源目录（**实际目录名 source_data_full**，规格书笔误 `source\_data\_full`） |
| `trajectory.source.file-pattern` | `track_*.json` | 文件命名 |
| `trajectory.source.max-files-per-job` | -1（全部 7709） | 导入/全量压缩单次上限；演示可给 300 |
| `trajectory.export.dashboard-dir / csv-dir` | `05可视化系统/exports/...` | 导出目录（前端 PNG/CSV 由浏览器下载，本配置供后端预留） |
| `trajectory.experiment.dp-move-tolerance-m / baseline-tolerance-m` | **10 / 10** | 【用户 2026-09-09 定稿】本文移动段与基线容差统一 10m |
| `trajectory.experiment.block-window-s / precision / zip-level` | 600 / 6 / 6 | 分块时长 / 量化精度 / DEFLATE 级别 |
| `trajectory.experiment.waybill-limit / waybill-offset` | -1 / 0 | 实验运单范围（-1=全部） |

> ⚠️ **容差口径**：系统默认"本文移动段 DP 容差 = 基线容差 = 10m"（公平对比），
> 与 claude_15 论文现稿"本文 15m / 基线 10m"不一致。按此口径重跑后第 6 章数字低于现稿，需另行回填论文表 6-4/6-17 等。
> 如需复现论文口径，把 `dp-move-tolerance-m` 改为 15 即可（实验参数随批次写入 MySQL，保证可复现）。

## 3. MySQL 建表

```bash
mysql -uroot -proot -h127.0.0.1 --default-character-set=utf8mb4 < sql/init.sql
```

建表：`trajectory_waybill` + 六张 `trajectory_eval_*` 结果表（规格书 §8）。`ml_network_freight` 中已有的老业务表（waybill 等）不动；系统按 waybill_id 从老 `waybill` 表回填车牌、货物、收发货地点/坐标（`send_addr_lal`/`receive_addr_lal`）与装货/卸货/接单时间（源 JSON 文件无这些字段）。

> 老库已有 `trajectory_waybill` 时执行增量脚本 `sql/upgrade_20260910_waybill_meta.sql` 补列，并**重跑一次导入**才会回填历史运单的收发货元数据。
> 2026-09-23 以前建立的实验结果表还需执行 `sql/upgrade_20260923_e2e_baseline.sql`，新增统一编码器端到端指标列；执行后重跑主实验生成新批次，旧批次保持不变。

## 4. 启动

```bash
# 方式一：本地 Maven
mvn -s D:\develop\apache-maven-3.8.2\conf\settings-tuling.xml spring-boot:run -Dspring-boot.run.profiles=local
# 方式二：IDE 运行 TrajectoryVisualApplication 并激活 local profile
```

接口文档（SpringDoc）：启动后访问 `http://127.0.0.1:8080/swagger-ui.html`。

## 5. 使用流程（本地验证）

1. 导入运单元数据（一次即可，含车牌与收发货元数据回填）：
   `curl -X POST "http://127.0.0.1:8080/api/visual/waybill/admin/import-waybills?limit=300"`
2. 运行第 6 章实验（见下）写入 MySQL 结果表。
3. 对单个运单压缩写 Mongo：`POST /api/visual/trajectory/compress/{waybillId}`，
   或前端"全量轨迹压缩"按钮触发后台任务（`POST /compress-all` → 轮询 `/compress-task/{taskId}`）。
4. 打开前端页面读取以上接口。

## 6. 第 6 章实验测试启动类

`src/test/java/com/fkhwl/nfs/biz/experiment/TrajectoryChapter6ExperimentTest`（@SpringBootTest, local profile）

每个实验一个 @Test，并含总入口 `runAllExperiments()`：

- `runStopRecognitionExperiment` → 6.2（静态同坐标停留识别聚合，写 run.remark）
- `runLossyCompressionCompareExperiment` → **多算法主实验**（层次一比较有损指标；层次三把本文 + DP/DPS/TD-TR/Trajic 全部接入同一编码器，写端到端负载指标）
- `runLosslessEncodingExperiment` → 6.4（PROPOSED 无损层独立批次）
- `runPartialDecompressionExperiment` → 6.5（PROPOSED 部分解压独立批次）
- `runAblationExperiment` → 6.6.1（A0 / A-TIGHT / A2 / A3 / A4 → trajectory_eval_ablation_result）
- `runParameterSensitivityExperiment` → DP 容差扫描 5/10/15/20/30 + chunk 时长扫描 300/600/1800/3600 → trajectory_eval_param_result

单测运行示例（先设好运单范围）：

```bash
mvn -s D:\develop\apache-maven-3.8.2\conf\settings-tuling.xml \
  -Dtrajectory.experiment.waybill-limit=50 \
  -Dtest=TrajectoryChapter6ExperimentTest#runLossyCompressionCompareExperiment test
```

> **跑全量 5224 需较长时间**（尤其 runAll 顺序跑全部实验）；建议先以 `waybill-limit` 取子集跑通，
> 再做全量长跑。每次运行独立批次（run_no 唯一），逐运单失败写 `trajectory_eval_error_record` 不中断整批。

## 7. 主要接口列表

- 运单：`GET /api/visual/waybill/page`、`/detail/{waybillId}`、`POST /admin/import-waybills`
- 轨迹：`GET /trajectory/original/{id}`、`/compressed/{id}`、`/chunks/{id}`、`/config`；
  `POST /trajectory/compress/{id}`、`/compress-all`；`GET /compress-task/{taskId}`；`POST /search-by-time`
- 评估：`GET /evaluation/runs|summary|algorithms|algorithm-compare|waybill/{id}|ablation|partial-decompression|parameter-sensitivity|errors|dashboard`

详见 `docs/接口说明文档.md`。

## 8. 数据边界（重要）

- **MySQL**：运单元数据（元数据 + 车牌/货物/收发货时间地点回填）+ 第 6 章实验结果表（批次/聚合/单运单/消融/参数/失败）。不存原始轨迹点数组。
- **原始轨迹**：以本地源文件（source_data_full）为准，按需现读（单运单 ≤ 万点级，毫秒级）。
- **MongoDB**：只保存压缩分片 `trajectory_chunk`（规格书 §7 TrajectoryChunkDoc），不存原始点。
- 坐标一律 GCJ-02：源 JSON（source_data_full）与业务库收发地址坐标**本身已是 GCJ-02**（入湖前已做过 WGS84→GCJ-02），展示层**不做二次转换**（开关 `trajectory.coord.source-already-gcj02`，默认 true）；压缩与指标计算不依赖坐标基准，与 04实验 口径一致。
