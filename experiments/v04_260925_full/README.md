# 04 版实验复现记录（2026-09-26）

本文所有结果按清洗后轨迹输入统计；原始数据和逐运单清单只留在本机，不纳入 Git。执行命令的工作目录是 `trajectory-visual-server`。Maven 固定使用 `D:\develop\apache-maven-3.8.2\conf\settings-tuling.xml`，Spring profile 为 `local`。`application-local.yml` 中的数据源、源轨迹目录与参数由实验代码读取；正式批次 `waybill-limit=-1`。对外分享前应清除配置中的数据库凭据、运单号、车牌、精确线路等字段。

## 执行顺序与方法

1. 主实验：`mvn -s D:\develop\apache-maven-3.8.2\conf\settings-tuling.xml -Dtest=TrajectoryChapter6ExperimentTest#runAllExperiments test`。入口顺次运行 6.2 候选生成及 ST-DBSCAN 输出特征比较、6.3 本方法与纯 DP / DP 加转角回插启发式 / TD-TR / 线性预测抽稀基线的有损比较、6.4 编码、6.6 时间窗部分解码、消融和参数扫描。每个阶段单独产生 MySQL 批次与参数快照。
2. 分块时长定向补测：`mvn -s D:\develop\apache-maven-3.8.2\conf\settings-tuling.xml -Dtest=TrajectoryChapter6ExperimentTest#runBlockWindowSensitivityExperiment test`。完整参数批次已扫描 300～3600 秒；本命令仅补测 7200、14400 秒（2 和 4 小时），不重复 DP 容差扫描。论文表格标明两批来源；两批使用同一输入、参数和编码器，内容级窗口验证的代码修订不改变编码负载或命中块字节。
3. 补充实验：`mvn -s D:\develop\apache-maven-3.8.2\conf\settings-tuling.xml -Dsupplement.outDir=<本机输出目录> -Dtest=SupplementExperimentTest#runAllSupplementExperiments test`。同一次装载运行无损层规模与耗时、统一编码端到端比较、同候选锚点 TD-TR 与 DP 加独立候选元数据近邻对照、候选阈值敏感性、经验零阶熵参考、含 2/4 小时的块长和逻辑元数据开销、15/60/120 分钟随机窗口微基准、纯轨迹凸包与围栏增强凸包分列对照。
4. 合成情景：`mvn -s D:\develop\apache-maven-3.8.2\conf\settings-tuling.xml -Dsynthetic.outDir=<本机输出目录> -Dtest=SyntheticAnnotationExperimentTest#runSyntheticAnnotationExperiment test`。四种预设情景各 30 条、固定随机种子 20260925，输出模拟事件标签与一对一匹配指标。它不是人工标注企业轨迹，不能用于声称真实检测优于 ST-DBSCAN。
5. 测试：`mvn -s D:\develop\apache-maven-3.8.2\conf\settings-tuling.xml -Dtest=WindowResultVerifierTest,CandidateWindowSupportTest,MetricsBoundarySpanTest,BlockOffsetCodecRoundTripTest test`；前端在 `trajectory-visual-web` 执行 `npm run build`。
6. 校正补充对照：`mvn -s D:\develop\apache-maven-3.8.2\conf\settings-tuling.xml -Dsupplement.outDir=<本机输出目录> -Dtest=SupplementExperimentTest#runEndToEndBenchmark test` 将补充表内部保持指标统一到 `boundary-span-v2`；`-Dtest=SupplementExperimentTest#runLosslessSizeBenchmark` 计算同保留点规范文本的整单 gzip 和同 1 h 分块 gzip 负载。主批次的点数/字节已用同编码器核对，不以补充表的旧指标覆盖新版主批次。

主比较的所有删点方法使用相同清洗输入和分块编码器。原始 Trajic 是完整预测编码系统；本文的 `Trajic` 类只实现线性预测触发保留点启发式，结果不能归为原论文系统性能。`Dps` 类为 DP 加转角回插启发式，不是原始 DPTS。近邻的事件侧车按 UTF-8 逻辑序列化计费，分块索引按字段长度下界估算；这些均非 MongoDB/BSON 物理实存。随机窗口基准是内存编码载荷的微基准，不代表数据库端到端延迟。凸包主结果只用轨迹点；加入收发货围栏中心的结果作为独立敏感性列。

## 数据口径与验收

- 主端到端字节压缩率为 `Σ清洗轨迹规范文本字节 / Σ编码 payload 字节`。它不含候选元数据、数据库文档与索引物理开销；点数压缩率另报。
- 候选单元完整率和边界观测跨度保持率属于压缩内部指标。无候选运单记为不适用；后者为 `Σ双边界保留单元的原观测跨度 / Σ所有有效候选原观测跨度`。均不能替代外部真值 Precision、Recall、F1。
- 部分解码和全量解码按六字段、顺序与规范序列 SHA-256 比对；编码往返对量化整数序列另测。查询计时是否包含数据库读取、索引扫描、解码与校验须分别标注。
- 复核每批运行状态、有效运单数、失败数、输入点数、参数快照、源码提交、输出 CSV 与论文表号。不得将旧批次均值填入新版定义字段；不得把模拟事件当作人工真值。

本目录中的 `main_maven.log`、`supplement`、`synthetic`、`db_export` 等本机产物可能含直接或间接业务标识，默认不提交。正式论文只引用核对后的聚合数据及公开范围内的文献。

## 已完成批次与关键验收

- 2026-09-25 主实验：识别 `TRAJECTORY-EVAL-20260925-231148`、有损及统一编码 `...-231424`、无损 `...-232222`、窗口初批 `...-232428`、消融 `...-232616`、参数扫描 `...-233830`，6 批均 `SUCCESS`。主批次含 5,224 运单、4,374,309 原始点、4,280,695 清洗点。
- 2026-09-26 内容级窗口独立重跑 `TRAJECTORY-EVAL-20260926-085217` 为 `SUCCESS`，备注明确记录六字段、顺序与 SHA-256 一致 `5224/5224`。旧窗口初批仅使用点数校验，不能代替本批。
- 2026-09-26 补充实验全量 `SupplementExperimentTest#runAllSupplementExperiments` 通过；块长 5 min 至 4 h 的汇总见 `supplement/table6_16_block_tradeoff.md`。2 h / 4 h 的编码负载分别为 15,654,126 / 14,880,376 B，对固定 1 h 窗的全局命中负载比分别为 0.2118 / 0.3755。负载和逻辑元数据下界均不等于物理数据库占用。
- 2 h/4 h 的最终数据库定向批次 `TRAJECTORY-EVAL-20260926-092558` 为 `SUCCESS`，每档 5,224 运单，批次原始点总数 4,374,309，逐运单平均命中负载比分别为 0.2503 / 0.4263。先前 `...-090424` 虽算出同档参数行，但批次汇总元数据误写为 0；修正后独立重跑为 `...-092558`，论文只引用最终批次。
- 2026-09-26 补充口径校正两项定向测试通过：`table6_end_to_end_common_encoder` 的候选单元完整率改为含候选运单宏均值、观测跨度改为全局秒数加权，与主批次 `boundary-span-v2` 一致；`table6_12_lossless_summary` 新增相同保留点规范文本整单 gzip 20,819,249 B、同 1 h 分块 gzip 21,903,269 B，对照本文分块整数负载 16,724,572 B。gzip 对照使用 Java 默认级别；这不是 packed binary 或数据库实存比较。
- 合成情景 4×30 条使用程序生成轨迹和标签、种子 20260925、事件一对一时间 IoU≥0.5；仅验证机制，企业数据无独立人工真值。前端 `npm run build` 已通过。
