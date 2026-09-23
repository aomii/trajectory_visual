# PROJECT_OVERVIEW — LogiCompress 可视化验证系统总览

硕士论文《面向大宗物流的轨迹停留语义保持与分级压缩方法研究》（2026-09-04 收敛题目）第 5.3 节
"可视化子系统" + 第 7.4 展望第 7 条的**在线化验证系统**交付。

## 目录结构

```
05可视化系统/
├─ trajectory-visual-server/   后端（Spring Boot 2.7 / MyBatis-Plus / Spring Data MongoDB / JDK8 语法）
│   ├─ README.md
│   └─ src/main/java/com/fkhwl/nfs|com/logicompress/experiment(算法移植)
├─ trajectory-visual-web/      前端（Vue3 + Vite + Element Plus + ECharts + 高德地图）
│   └─ README.md
├─ sql/init.sql                MySQL 建表（运单元数据 + 6 张第 6 章实验结果表）
├─ docs/接口说明文档.md         接口清单
├─ docs/自检清单.md             对照规格书 §19 的自检结果
├─ exports/dashboard|csv        导出默认目录
└─ 给claudecode的可视化系统实现说明.md（规格书）
```

## 架构与数据边界

- 原始轨迹 = 本地源文件（`trajectory.source.full-data-dir`，当前 source_data_full，7709 运单），坐标 **GCJ-02**。
- MySQL `ml_network_freight`：运单元数据（含从业务库 `waybill` 表回填的车牌、货物、收发货地点/坐标、装卸货时间）+ 第 6 章实验结果表。
- MongoDB：只存压缩分片 `trajectory_chunk`（TrajectoryChunkDoc），不存原始轨迹点数组；chunk0 额外挂停留语义元数据与**压缩指标快照**。
- 坐标一律 GCJ-02：源数据本身即 GCJ-02，展示层**不做二次转换**（开关 `trajectory.coord.source-already-gcj02`，默认 true）；压缩/指标计算不依赖坐标基准，与 04实验 口径一致。

## 2026-09-10 问题记录修复（问题记录.md #1–#5）

1. **坐标系**：源 JSON 已是 GCJ-02，去掉展示层的 WGS84→GCJ-02 二次转换（原会整体偏移约 300~600m）。
2. **运单收发元数据**：`trajectory_waybill` 补 14 列（货名 / 收发地点名称·区划·详址 / 收发经纬度 / 装货·卸货·接单时间），
   导入时按 waybillId 从业务库 `waybill` 表回填；工作台新增"运单收发货信息"卡片。**老库需跑 `sql/upgrade_20260910_waybill_meta.sql` 并重跑导入。**
3. **地图可读性**：原始线=**蓝色**实线（画得更宽、压在底层），压缩线=**绿色**实线（窄、压在上层）→
   重合段呈"蓝边绿芯"，DP 抄近道处蓝色绕弯、绿色走直线，两条曲线的差异一眼可见；
   沿线绘制方向箭头（自绘 SVG，按里程均匀布点），起/终点用"起/终"白底徽标标注；
   **被有损压缩删除的点用橙色圆点高亮**（后端对原始线逐点标 `kept`，前端散点绘制，超 3000 点按等间隔抽样并提示）；
   **「被删点」只在运单压缩后才出现**——没压缩就只是干净的一条源轨迹，不会凭空冒出高亮点。
   复现方式：按分片里落库的同一套参数重跑一次抽稀，并校验保留点数与分片记录一致，不一致就不显示（宁可不显示，也不画错的标记）。新增图例。
4. **压缩指标**：指标随分片落库（chunk0 `metrics`），工作台"压缩指标"卡从 `TrackViewVO.metrics` 回显，刷新/切运单不丢。
5. **时间窗口提示**：顶部提示条显示该运单轨迹时间范围、分片时长（几秒一片）与分片数（`/trajectory/config` + 分片清单）；"取最近1h"改为按轨迹中段取 1h（历史数据下原按钮永远空）。

## 2026-09-11 补充实验包（新）

主实验（`Chapter6ExperimentRunner`，结果落 MySQL）覆盖不到的表，由新包实现：

- 代码：`src/main/java/com/logicompress/supplement/`
  （`SupplementContext` 数据装载 / `SupplementTables` CSV+Markdown 导出 /
  `StopRecognitionCompare` 表6-7 / `LosslessBenchmark` 表6-12·6-13 / `EntropyBenchmark` 表6-14 /
  `BlockLengthSweep` 表6-16 / `HullShapeBenchmark` 表6-18 / `SupplementRunner` 编排）
- 手动运行入口：`src/test/java/com/logicompress/supplement/SupplementExperimentTest`（IDEA 右键或 `mvn test -Dtest=SupplementExperimentTest`）
- 导出目录：`src/main/resources/export/supplement/`，每张表同时给 `.csv`（数据）与 `.md`（论文可直接粘贴）
- 可调参数：`-Dsupplement.limit=N`（试跑）、`-Dsupplement.outDir=...`（改输出目录）
- 数据源：轨迹走 `source_data_full`（与主实验同一条解析路径），运单业务信息（收发坐标/装卸货时间）从 MySQL `trajectory_waybill` 取——**绕开 `WaybillLoader` 的 track_/waybill_ 文件名配对**（合并后的 5224 个运单没有 waybill_*.json）
- 表 6-19「方案 A vs 方案 B」按用户决定取消，本包不实现

## 2026-09-11 数据集合并与全量重跑（进行中）

- **源数据集**：`01开题/公司FKH/track_data` 的 133 个运单（≥100 点）已并入 `source_data_full`，
  格式规范化成同一套（补 `gtmMs`/`pointCount`/`waybillId`/`vehicleId`），脚本 `00数据处理-260909/脚本/step8_merge_track_data.py`。
- **过滤**：`source_data_full` 中 <100 点的 2618 个文件已删除（可回溯：`00数据处理-260909/source_data` 保有同名全量 7709 个文件）。
  最终源目录 **5224 个运单**（约 437 万点）。
- **分片时长**：`trajectory.experiment.block-window-s` 由 600 改为 **3600**（Mongo 库里 1 小时一片）。
- **已清空**：MySQL 7 张 `trajectory_*` 表 + Mongo `trajectory_chunk`。
- **全量实验入口**：`Chapter6FullExperimentTest`（新，手动跑；强制 `waybill-limit=-1`，不受 yml 试跑上限影响，
  每 200 个运单打印一次进度，全量约 45–70 分钟）。
- MyBatis SQL 打印已默认关闭（`application.yml` 的 `log-impl`），否则每条 INSERT 会刷爆控制台。

## 关键口径（2026-09-09 用户定稿，覆盖论文现稿）

- **DP 容差统一 10m**：本文移动段与 DP/DPS/TD-TR/Trajic 基线一致（公平对比）。与 claude_15
  现稿"本文 15m / 基线 10m"不一致 → 本系统重跑出的第 6 章数字需另行回填论文表。
- **端到端口径统一**：本文与 DP/DPS/TD-TR/Trajic 的有损输出全部进入同一分块偏移量编码器，
  以清洗后完整点列规范文本总字节 / 编码负载总字节计算全局端到端压缩率；`CR_total`
  只保留为逐运单内部诊断指标，不再与只有有损层的基线混比。
- 算法核心来自 `04实验`（com.logicompress.experiment）**verbatim 移植**，保证数字可复现；
  本文、DP/DPS/TD-TR/Trajic 四基线全部完整实现（无 not_available）。
- 数据源 7709 在线库；与论文第 6 章 152 运单实验集非同一批，故结果数字≠论文表 6-*（预期行为，用户已确认）。

## 本地验证结果（2026-09-09）

- 后端 `mvn compile`、`test-compile` 通过；20/15 运单子集跑通 6.3 主实验、6.6 消融、参数敏感性并写入 MySQL。
- HTTP 冒烟：运单导入（200，车牌回填成功）、单运单压缩（31363→39 分片写 Mongo）、原始/压缩轨迹、
  时间窗部分解压（命中 5/39 块、读取 16.8% 字节、与全量解压一致）、评估 runs/summary/dashboard/ablation/param/waybill 下钻。
- 前端 `npm install && npm run build` 通过（仅大 chunk 告警）。

## 运行速览

1. `mysql ... < sql/init.sql`（全新库）；**已有库**依次执行 `sql/upgrade_20260910_waybill_meta.sql` 与 `sql/upgrade_20260923_e2e_baseline.sql`
2. 启动后端 `mvn -s D:\develop\apache-maven-3.8.2\conf\settings-tuling.xml spring-boot:run -Dspring-boot.run.profiles=local`
3. `curl -X POST "http://127.0.0.1:8080/api/visual/waybill/admin/import-waybills?limit=300"`（重跑一次以回填历史运单的车牌/货物/收发时间地点）
4. 运行第 6 章测试类（建议先 `-Dtrajectory.experiment.waybill-limit=50`）
5. `cd trajectory-visual-web && npm install && npm run dev`
6. 浏览器 http://127.0.0.1:5173（默认进 /dashboard；如需地图先在 /workbench 压缩运单）
