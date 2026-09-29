# 04 版全量实验复现记录（2026-09-29）

数据输入为两批企业轨迹合并后的 5,224 条运单。原始点 4,374,309 个；漂移清洗后 4,280,695 个。实验只对清洗后点列计算停留候选、压缩和误差；这些数据不是人工标注真值。

在 `trajectory-visual-server` 目录下运行，Maven 配置固定为 `D:\develop\apache-maven-3.8.2\conf\settings-tuling.xml`：

```powershell
& 'D:\develop\apache-maven-3.8.2\bin\mvn.cmd' -s 'D:\develop\apache-maven-3.8.2\conf\settings-tuling.xml' -o '-Dtest=TrajectoryChapter6ExperimentTest#runAllExperiments' test
& 'D:\develop\apache-maven-3.8.2\bin\mvn.cmd' -s 'D:\develop\apache-maven-3.8.2\conf\settings-tuling.xml' -o '-Dtest=SupplementExperimentTest#runAllSupplementExperiments' '-Dsupplement.outDir=D:\aoming\电科\毕业论文260828-开题-codex\05可视化系统\experiments\v04_260929_full\supplement' test
```

主入口依次创建 6 个 MySQL 批次：6.2 候选停留统计；6.3 本文/DP/TD-TR 有损与统一编码对照；6.4 本文分块无损编码；6.6 时间窗部分解压及六字段顺序/SHA-256 一致性；消融 A0/A-TIGHT/A2/A3/A4；DP 容差 5/10/15/20/30 m 与块长 300/600/1800/3600/7200/14400 s 扫描。每个参数档均使用全量 5,224 运单。DP 使用垂距容差，TD-TR 使用同步欧氏距离阈值；相同数值不能当作同等误差预算。

本次主实验对应 MySQL 批次 ID 21–26，依上述顺序分别为 `TRAJECTORY-EVAL-20260929-113108`、`TRAJECTORY-EVAL-20260929-113310`、`TRAJECTORY-EVAL-20260929-113831`、`TRAJECTORY-EVAL-20260929-114033`、`TRAJECTORY-EVAL-20260929-114351`、`TRAJECTORY-EVAL-20260929-115937`，状态均为 `SUCCESS`。批次 22 每法各有 5,224 行逐运单结果；批次 25 有 5 行消融结果；批次 26 有 15 行 DP 容差结果和 6 行块长结果。前端 Dashboard 的块长曲线与消融/参数页分别读取相应成功批次，页面上显示来源。

补充入口一次装载数据，生成 ST-DBSCAN 输出特征对照、无损编码与熵统计、30 轮内存微基准、统一编码端到端表、近邻控制、候选参数敏感性、5 分钟至 4 小时块长扫描、随机窗口解码及凸包/重建边统计。补充输出在本目录 `supplement/`；其中逐运单和逐查询 CSV 含业务运单号，仅供本地核验，不应提交或公开。

主、补充全量任务并行执行会干扰耗时数据，因此两者结束后再单独运行 `SupplementExperimentTest#runTimedMicrobenchmarks`，覆盖表 6-13 与表 6-18 的计时列；非计时列仍取全量任务结果。

独立复测在 2026-09-29 15:00 完成（Maven `BUILD SUCCESS`）。表 6-13 的编码、全量解码、部分解码轮次总耗时 P50 分别为 1,938.68、596.90、125.61 ms，全量/部分解码比为 4.75；表 6-18 的 15/60/120 min 解码 P50 分别为 0.0072/0.0124/0.0212 ms。这些仅是 JVM 内存微基准，不是数据库 API 端到端延迟。

在本机临时端口 18080 启动新版后端做只读 API 冒烟检查：`result-sources` 返回消融 25、容差/块长 26；`ablation?runId=25` 返回 5 行，`parameter-sensitivity?runId=26` 返回 15 行容差与 6 行块长，`algorithms?runId=22` 仅返回 PROPOSED/DP/TD-TR，`dashboard?runId=26` 的块长曲线返回 6 点。检查后已停止临时服务，原有 8080 服务未触碰。

`db_export/` 是新批次的匿名化 MySQL 导出，`old_batches_archive/` 是删除旧批次前的匿名化备份。两个目录仅供本地复算；公开结果以聚合表为准。`main_maven.log` 与 `supplement_maven.log` 保存执行日志。论文中的 SR、候选单元完整率、R_span 都是对候选生成结果的压缩内部保持指标；没有独立人工/车辆状态真值，不报告停留检测 Precision、Recall 或 F1。

旧批次 1–19 已先匿名化归档，1–20 随后在核对 21–26 六个新批次均成功、主对照每法均为 5,224 运单、消融 5 行、参数 21 行后，从 MySQL 的评估结果表事务清理。清理后数据库仅保留 21–26；新批次匿名导出重新生成。旧实验的仓库内补充导出也已移除，避免其 DPS/Trajic 行与当前三方法表混用。
