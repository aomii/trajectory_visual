# 给 Claude Code 的可视化评估系统实现说明

## 1. 项目目标

请实现硕士论文第 5.3 节“可视化子系统”，并落地第 7.4 节展望第 7 条“可视化评估系统的在线化与规模化验证”。

本系统不是单条轨迹演示页面，而是“轨迹压缩展示 + 第 6 章实验结果持久化 + 多算法压缩效果对比”的在线化验证系统。实现时请参考公司项目 `fkh-network-freight` 的编码规范、包结构、DTO 风格、接口返回风格和已有轨迹业务逻辑。

核心目标：

- 展示压缩前后的轨迹对比图。
- 显示停留点，并展示停留时长。
- 支持按时间窗口检索部分轨迹。
- 前端提供“全量轨迹压缩”按钮，用于触发批量压缩。
- 第 6 章评估结果由测试启动类离线运行并写入 MySQL，前端页面读取 MySQL 中的实验结果。
- 展示本文方法与 DP、DPS、TD-TR、Trajic 等算法的压缩效果对比。
- 压缩分片持久化存入 MongoDB。
- 对接本地 MySQL 与本地 MongoDB。

## 2. 项目边界

数据边界必须严格遵守：

- MySQL：存储运单、车辆、业务信息以及已有原始 GPS 轨迹点位。本系统不生成业务原始轨迹。
- 本地轨迹源目录：用于本地实验环境读取全量轨迹源文件，路径为 `D:\aoming\电科\毕业论文 - claude\00数据处理-260909\source\_data\_full`。
- MongoDB：只保存压缩后的分块 chunk 分片文档 `TrajectoryChunkDoc`。
- 原始轨迹点数组不要写入 MongoDB。
- 第 6 章评估结果写入 MySQL，避免每次打开页面都重新跑全量数据。
- MySQL 只保存实验批次、算法参数、汇总指标、单运单指标、失败记录等结果数据，不保存原始轨迹点数组。

源数据目录说明：

- `D:\aoming\电科\毕业论文 - claude\00数据处理-260909\source\_data\_full` 是当前全量轨迹源数据位置。
- 后端应提供配置项读取该目录，不要在代码中硬编码路径。
- 可以在 `application-local.yml` 中增加配置，例如：

```yaml
trajectory:
  source:
    full-data-dir: D:/aoming/电科/毕业论文 - claude/00数据处理-260909/source/_data_full
```

## 3. 输出目录与交付物位置

请按以下目录输出代码和交付物，不要自行新建不相关目录。所有新建后端、前端、SQL、README、接口文档、导出文件等交付物统一输出到 `D:\aoming\电科\毕业论文 - claude\05可视化系统` 目录下。

老项目 `fkh-network-freight` 只作为业务逻辑、编码规范和接口风格参考，不作为本次新系统的代码输出目录。

后端代码输出目录：

- `D:\aoming\电科\毕业论文 - claude\05可视化系统\trajectory-visual-server`

后端项目名：

- `trajectory-visual-server`

后端新增代码应放在独立 Spring Boot 项目 `trajectory-visual-server` 内：

- Controller：`src/main/java/com/fkhwl/nfs/biz/controller/visual`
- Service：`src/main/java/com/fkhwl/nfs/biz/service/visual`
- ServiceImpl：`src/main/java/com/fkhwl/nfs/biz/service/visual/impl`
- DTO / VO / Form：`src/main/java/com/fkhwl/nfs/biz/entity/dto/visual`
- MySQL PO：`src/main/java/com/fkhwl/nfs/biz/entity/po`
- Mongo 文档对象：`src/main/java/com/fkhwl/nfs/biz/entity/mongo` 或沿用项目已有 Mongo PO 目录
- Mapper：`src/main/java/com/fkhwl/nfs/biz/mapper`
- Mapper XML：`src/main/resources/mapper`
- 第 6 章实验测试启动类：`src/test/java/com/fkhwl/nfs/biz/experiment/TrajectoryChapter6ExperimentTest.java`

前端代码输出目录：

- `D:\aoming\电科\毕业论文 - claude\05可视化系统\trajectory-visual-web`

前端项目名：

- `trajectory-visual-web`

目录关系要求：

- `trajectory-visual-server` 与 `trajectory-visual-web` 必须位于同一个父目录 `D:\aoming\电科\毕业论文 - claude\05可视化系统` 下。
- 后端项目名和 Maven `artifactId` 使用 `trajectory-visual-server`。
- 前端项目名和 `package.json` 项目名使用 `trajectory-visual-web`。
- 不要把后端项目嵌套到前端目录，也不要把前端代码嵌套到后端项目。

前端目录建议：

- 页面：`src/views`
- 组件：`src/components`
- 接口封装：`src/api`
- 高德地图配置：`src/config/amap.ts`
- ECharts 图表配置：`src/charts`
- 工具函数：`src/utils`
- 类型定义：`src/types`

SQL 和文档输出目录：

- MySQL 建表脚本：`D:\aoming\电科\毕业论文 - claude\05可视化系统\sql`
- 接口说明文档：`D:\aoming\电科\毕业论文 - claude\05可视化系统\docs`

项目 README 必须分别维护：

- 后端 README：`D:\aoming\电科\毕业论文 - claude\05可视化系统\trajectory-visual-server\README.md`
- 前端 README：`D:\aoming\电科\毕业论文 - claude\05可视化系统\trajectory-visual-web\README.md`
- 不要求在父目录额外创建总 README；如确实需要总览文档，命名为 `PROJECT_OVERVIEW.md`，不要替代两个项目自己的 README。

后端 README 至少包含：

- 后端项目名称和职责。
- JDK 8、Maven、MySQL、MongoDB 前置环境。
- `application-local.yml` 配置说明。
- 全量轨迹源目录配置。
- MySQL 实验结果表初始化方式。
- Spring Boot 启动命令。
- 第 6 章测试启动类运行方式。
- 主要接口列表。
- MongoDB 只保存压缩 chunk 的数据边界。

前端 README 至少包含：

- 前端项目名称和职责。
- Node.js 16、npm 前置环境。
- `npm install` 和 `npm run dev` 启动方式。
- `VITE_API_BASE_URL` 配置说明。
- 高德地图 Key 的配置位置和替换方式。
- 页面路由和主要功能。
- 答辩 Dashboard 展示模式和 PNG 导出方式。
- 前端如何联调后端地址。

导出文件默认目录：

- Dashboard 截图导出：`D:\aoming\电科\毕业论文 - claude\05可视化系统\exports\dashboard`
- 指标 CSV 导出：`D:\aoming\电科\毕业论文 - claude\05可视化系统\exports\csv`
- 导出目录应支持通过配置项修改，不要硬编码在业务逻辑中。

## 4. 技术栈

后端：

- Spring Boot
- JDK 8
- Maven
- MySQL
- MongoDB
- MyBatis / MyBatis-Plus，沿用老项目风格
- Spring Data MongoDB 或项目已有 Mongo starter
- Lombok
- Swagger / Knife4j / 项目现有接口文档方式

前端：

- Vue 3
- Vite
- Element Plus
- ECharts
- 高德地图 JS API
- Axios
- Day.js

## 5. 前置环境

本地环境：

- JDK 8
- Maven
- Node.js 16
- Vue 3 / Vite
- MySQL：`ml_network_freight`
- MySQL 账号：`root`
- MySQL 密码：`root`
- MongoDB：`127.0.0.1:27017/ml_network_freight`
- MongoDB 无密码

数据库配置目标：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/ml_network_freight?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai&useAffectedRows=true
    username: root
    password: root

fkh:
  mongo:
    datasource:
      default: mongodb://127.0.0.1:27017/ml_network_freight
```

如果老项目当前使用的是 `application-local.yml`，优先修改本地配置文件，不要污染生产配置。

## 6. 旧项目参考

请参考以下老项目逻辑：

- 轨迹压缩入口：`D:\aoming\电科\毕业论文260828-开题-codex\01开题\公司FKH\fkh-network-freight\src\main\java\com\fkhwl\nfs\biz\service\impl\WaybillBillServiceImpl.java#compressTracks`
- 轨迹查询入口：`D:\aoming\电科\毕业论文260828-开题-codex\01开题\公司FKH\fkh-network-freight\src\main\java\com\fkhwl\nfs\biz\service\impl\WaybillServiceImpl.java#gps`
- 轨迹点 DTO：`D:\aoming\电科\毕业论文260828-开题-codex\01开题\公司FKH\fkh-network-freight\src\main\java\com\fkhwl\nfs\biz\entity\dto\open\platform\LocationDTO.java`

注意：

- `WaybillServiceImpl#gps` 中已有 WGS84 转 GCJ-02 的逻辑，前端高德地图展示应使用 GCJ-02。
- `LocationDTO` 中 `mlg` 字段基本为空，不要把里程字段作为核心指标来源。
- 旧逻辑里已有已完成运单压缩、Mongo 查询和轨迹抽稀逻辑，新系统应复用思想，但需要扩展为分片持久化和可视化评估。

## 7. MongoDB 分片文档

新增 Mongo 文档对象：`TrajectoryChunkDoc`。

建议字段：

```java
private String id;
private Long waybillId;
private String waybillNo;
private Integer chunkIndex;
private Date startTime;
private Date endTime;
private Integer rawPointCount;
private Integer keptPointCount;
private Integer stayPointCount;
private byte[] compressedPayload;
private List<StayPointDTO> stayPoints;
private CompressionMetaDTO compressionMeta;
private Date createdAt;
```

索引建议：

- `waybillId + chunkIndex`
- `waybillNo`
- `waybillId + startTime + endTime`

Mongo 只保存压缩结果，不保存原始轨迹。

## 8. MySQL 实验结果表

第 6 章实验结果需要持久化到 MySQL。建议新增以下表，字段可按项目规范微调：

1. `trajectory_eval_run`
- 保存一次实验运行批次。
- 字段建议：`id`、`run_no`、`run_name`、`data_source_dir`、`waybill_count`、`raw_point_count`、`status`、`started_at`、`finished_at`、`duration_ms`、`remark`、`create_time`、`update_time`。

2. `trajectory_eval_algorithm_result`
- 保存每个实验批次下，每种算法的聚合指标。
- 字段建议：`id`、`run_id`、`algorithm_code`、`algorithm_name`、`parameter_json`、`waybill_count`、`raw_point_count`、`kept_point_count`、`chunk_count`、`cr_lossy_avg`、`cr_lossless_avg`、`cr_total_avg`、`ped_avg`、`sed_avg`、`sr_avg`、`semantic_unit_complete_rate`、`stay_duration_preserve_rate`、`encode_time_ms_avg`、`decode_time_ms_avg`、`storage_bytes`、`query_time_ms_avg`、`partial_read_ratio_avg`、`create_time`。

3. `trajectory_eval_waybill_result`
- 保存单个运单在某个算法下的实验指标，用于前端下钻。
- 字段建议：`id`、`run_id`、`waybill_id`、`waybill_no`、`algorithm_code`、`parameter_json`、`raw_point_count`、`kept_point_count`、`chunk_count`、`cr_lossy`、`cr_lossless`、`cr_total`、`ped_avg`、`ped_max`、`sed_avg`、`sed_max`、`sr`、`semantic_unit_complete_rate`、`stay_duration_preserve_rate`、`encode_time_ms`、`decode_time_ms`、`storage_bytes`、`query_time_ms`、`partial_read_ratio`、`create_time`。

4. `trajectory_eval_ablation_result`
- 保存消融实验结果。
- 字段建议：`id`、`run_id`、`ablation_code`、`ablation_name`、`parameter_json`、`cr_total_avg`、`sr_avg`、`semantic_unit_complete_rate`、`query_time_ms_avg`、`storage_bytes`、`remark`、`create_time`。

5. `trajectory_eval_param_result`
- 保存参数敏感性结果。
- 字段建议：`id`、`run_id`、`param_type`、`param_value`、`algorithm_code`、`cr_lossy_avg`、`cr_total_avg`、`ped_avg`、`sed_avg`、`sr_avg`、`query_time_ms_avg`、`partial_read_ratio_avg`、`create_time`。

6. `trajectory_eval_error_record`
- 保存实验运行中的失败文件或失败运单。
- 字段建议：`id`、`run_id`、`waybill_no`、`source_file`、`experiment_code`、`error_message`、`create_time`。

## 9. 第 6 章实验测试启动类

请提供一个测试启动类，用于跑第 6 章实验并将结果写入 MySQL。建议放在 `src/test/java` 下，例如：

```java
@SpringBootTest
@ActiveProfiles("local")
public class TrajectoryChapter6ExperimentTest {

    @Test
    public void runAllExperiments() {
        // 依次执行第 6 章全部实验
    }

    @Test
    public void runStopRecognitionExperiment() {
        // 对应 6.2 语义识别结果与分析
    }

    @Test
    public void runLossyCompressionCompareExperiment() {
        // 对应 6.3 有损层：分级压缩对比实验
    }

    @Test
    public void runLosslessEncodingExperiment() {
        // 对应 6.4 无损层：编码对比实验
    }

    @Test
    public void runPartialDecompressionExperiment() {
        // 对应 6.5 时间索引与部分解压实验
    }

    @Test
    public void runAblationExperiment() {
        // 对应 6.6 消融实验
    }

    @Test
    public void runParameterSensitivityExperiment() {
        // DP 容差扫描、chunk 时长扫描
    }
}
```

实现要求：

- 每个实验单独一个 `@Test` 方法，便于单独复跑。
- 提供 `runAllExperiments()` 总方法，一键运行全部实验。
- 每次运行生成一个 `trajectory_eval_run` 批次记录。
- 每个方法执行完成后写入对应 MySQL 结果表。
- 运行失败时写入 `trajectory_eval_error_record`，不要中断整个总实验，除非是数据库连接、源目录不存在等全局错误。
- 实验读取的数据来源优先使用配置项 `trajectory.source.full-data-dir`。
- 实验结果必须可复现：算法参数、数据目录、运行时间、算法版本或配置都要记录。
- 前端页面只读取 MySQL 中的最近一次成功实验结果，或允许用户选择历史实验批次。

## 10. 后端功能

后端至少实现以下能力：

1. 运单查询
- 按运单号、车牌号、时间范围分页查询。
- 返回前端展示需要的运单基础字段。

2. 单运单轨迹查询
- 查询原始轨迹点。
- 查询压缩后轨迹点。
- 查询 Mongo 中的 chunk 分片。
- 返回停留点和停留时长。

3. 单运单压缩
- 对指定运单执行压缩。
- 压缩结果写入 MongoDB 的 `TrajectoryChunkDoc`。
- 返回压缩前点数、压缩后点数、chunk 数、压缩率、停留点数量等指标。

4. 全量轨迹压缩
- 前端提供“全量轨迹压缩”按钮。
- 后端提供批量压缩接口。
- 数据来源优先支持本地全量轨迹源目录 `trajectory.source.full-data-dir`。
- 批量任务应返回任务状态、总文件数、已处理数、成功数、失败数、当前运单号、耗时。
- 批量压缩过程中将压缩分片写入 MongoDB。
- 失败文件要记录错误原因，并在接口中返回失败列表。

5. 时间窗口部分搜索
- 输入运单 ID、开始时间、结束时间。
- 使用 chunk 时间索引定位命中分片。
- 只解压命中分片并返回窗口内轨迹点。
- 返回读取 chunk 数、读取字节数、全量字节数、部分读取比例、耗时。

6. 第 6 章评估结果查询
- 评估结果由测试启动类离线运行并写入 MySQL。
- 页面请求时从 MySQL 查询最近一次成功实验批次，或按 `runId` 查询指定批次。
- 不允许在前端硬编码第 6 章指标数字。
- 如果某个算法暂未实现，接口返回 `not_available`，不要伪造结果。

## 11. 推荐接口

运单与轨迹：

- `GET /api/visual/waybill/page`
- `GET /api/visual/waybill/detail/{waybillId}`
- `GET /api/visual/trajectory/original/{waybillId}`
- `GET /api/visual/trajectory/compressed/{waybillId}`
- `GET /api/visual/trajectory/chunks/{waybillId}`
- `POST /api/visual/trajectory/compress/{waybillId}`
- `POST /api/visual/trajectory/compress-all`
- `GET /api/visual/trajectory/compress-task/{taskId}`
- `POST /api/visual/trajectory/search-by-time`

评估与算法对比：

- `GET /api/visual/evaluation/runs`
- `GET /api/visual/evaluation/summary`
- `GET /api/visual/evaluation/algorithms`
- `GET /api/visual/evaluation/algorithm-compare`
- `GET /api/visual/evaluation/waybill/{waybillId}`
- `GET /api/visual/evaluation/ablation`
- `GET /api/visual/evaluation/partial-decompression`
- `GET /api/visual/evaluation/parameter-sensitivity`

说明：

- 评估接口默认读取最近一次成功的 `trajectory_eval_run`。
- 支持传入 `runId` 查询历史批次，例如 `/api/visual/evaluation/summary?runId=xxx`。
- 接口返回风格请沿用老项目统一 `Result<T>` 或已有响应包装。

## 12. 评估指标

第 6 章评估结果需要在线展示，数据来源为 MySQL 中已保存的实验结果。

总体指标：

- 运单数量
- 原始轨迹点总数
- 压缩后点总数
- chunk 总数
- 平均 `CR_lossy`
- 平均 `CR_lossless`
- 平均 `CR_total`
- 平均 `PED`
- 平均 `SED`
- 语义点保留率 `SR`
- 语义单元完整率
- 停留时长保真度
- 全量解压耗时
- 部分解压耗时
- 部分读取字节比例
- 查询加速比

算法对比：

- 本文方法
- DP
- DPS
- TD-TR
- Trajic

对比维度：

- 压缩率
- 平均位置误差 `PED_avg`
- 平均同步欧氏距离 `SED_avg`
- 语义点保留率 `SR`
- 语义单元完整率
- 停留时长保真度
- 编码耗时
- 解码耗时
- 查询耗时
- 存储字节数

消融实验：

- A0 完整方法
- 去掉锚点强制保留
- 去掉无损编码
- 收紧移动段 DP 容差
- 去掉 chunk 时间索引

参数敏感性：

- DP 容差扫描：5m、10m、15m、20m、30m
- chunk 时长扫描：5min、10min、30min、60min

## 13. 前端页面案例

请按以下页面案例实现，界面用 Element Plus 做后台管理风格，地图和图表清晰即可，不要做成营销页。

### 页面一：轨迹压缩工作台

页面布局：

- 顶部筛选区：运单号、车牌号、开始时间、结束时间、压缩状态。
- 左侧表格：运单列表。
- 右侧地图：原始轨迹与压缩轨迹叠加展示。
- 右侧或底部指标卡：原始点数、压缩后点数、压缩率、停留点数、chunk 数。
- 操作按钮：
  - 查询
  - 查看轨迹
  - 压缩当前运单
  - 全量轨迹压缩
  - 清空地图

交互要求：

- 原始轨迹用灰色线。
- 压缩后轨迹用蓝色线。
- 停留点用醒目标记。
- 鼠标悬停停留点时展示停留开始时间、结束时间、停留时长。
- 点击“全量轨迹压缩”后弹出确认框，确认后调用 `/api/visual/trajectory/compress-all`。
- 全量压缩执行中显示进度条、成功数、失败数、当前处理文件或运单。

### 页面二：时间窗口部分检索

页面布局：

- 运单选择区。
- 时间范围选择器。
- 查询按钮。
- 地图展示窗口内轨迹。
- 指标面板展示命中 chunk 数、读取字节数、全量字节数、读取比例、查询耗时。

交互要求：

- 时间窗口查询只展示窗口内轨迹。
- 页面同时展示“全量解压查询”和“部分解压查询”的耗时对比。
- 若部分解压结果与全量过滤结果不一致，要在页面上提示。

### 页面三：第 6 章评估总览

页面布局：

- 顶部指标卡：有效运单数、原始点数、压缩后点数、总压缩率、SR、部分解压加速比。
- 中部图表：
  - 压缩率柱状图
  - PED / SED 对比柱状图
  - SR 语义保真对比图
  - chunk 时长权衡曲线
- 底部表格：每个算法的指标明细。

交互要求：

- 提供实验批次选择器，默认展示最近一次成功实验。
- 可提供“刷新结果”按钮，仅重新读取 MySQL，不直接触发全量实验计算。
- 每个指标标注单位，例如 `m`、`ms`、`%`、`x`。

### 页面四：算法压缩效果对比

页面布局：

- 算法多选框：本文方法、DP、DPS、TD-TR、Trajic。
- 指标选择器：压缩率、PED、SED、SR、耗时、存储字节。
- 图表区：按所选指标展示多算法对比。
- 表格区：展示每个算法的均值、中位数、最大值、最小值。

交互要求：

- 支持切换柱状图、折线图、雷达图。
- 点击某个算法后，地图展示该算法压缩后的轨迹。
- 支持查看单个运单在不同算法下的压缩效果。

### 页面五：消融实验与参数敏感性

页面布局：

- 消融实验柱状图。
- DP 容差扫描曲线。
- chunk 时长扫描曲线。
- 结论表格。

交互要求：

- 展示不同配置下 `CR_total`、`SR`、查询耗时的变化。
- 参数变化要能体现“压缩率 vs 保真度 vs 查询效率”的权衡。

## 14. 答辩级实验结果 Dashboard 要求

第 6 章实验结果 Dashboard 是答辩演示和论文截图的核心页面，必须按“可直接展示研究结论”的标准实现，不能只做默认的几张柱状图。

### 13.1 页面定位

- 页面名称建议为“轨迹压缩实验评估中心”。
- 默认打开即加载最近一次 `status = SUCCESS` 的实验批次。
- 页面顶部显示实验批次号、运行时间、数据源目录、有效运单数和算法版本。
- 所有数字都来自 MySQL 查询结果，禁止在前端写死论文中的示例数字。
- 如果数据库没有实验结果，页面应显示明确的空状态和运行测试类的提示，不要显示伪造的 0 值。
- 页面要支持“答辩展示模式”和“日常分析模式”：
  - 答辩展示模式隐藏筛选和调试信息，突出核心结论，适合 16:9 截图。
  - 日常分析模式保留实验批次选择、指标筛选、运单下钻和详细表格。

### 13.2 答辩展示模式布局

建议按 1440×900 或 1920×1080 设计，首屏不需要滚动即可看到主要结论：

第一行：页面标题、实验批次选择、数据状态和操作区。

- 标题：`轨迹压缩实验评估中心`
- 副标题：`语义保持、压缩效率与局部查询性能综合评估`
- 批次信息：批次号、实验完成时间、数据集规模、成功 / 失败文件数
- 操作按钮：
  - 刷新结果
  - 切换答辩展示模式
  - 导出当前 Dashboard 图片
  - 导出指标明细 CSV

第二行：六个核心 KPI 卡片，必须显示数值、单位、指标解释和数据来源：

- 有效运单数
- 原始轨迹点数
- 总压缩率 `CR_total`
- 有损层压缩率 `CR_lossy`
- 语义点保留率 `SR`
- 部分解压加速比

KPI 卡片要求：

- 卡片颜色只用于表达状态，不要整页使用渐变。
- `SR = 100%`、语义单元完整率 `= 100%` 等关键结论使用高对比强调。
- 数值格式统一，例如点数使用千分位，耗时使用 `ms` 或 `s`，比例使用 `%` 或 `x`。
- 每张卡片显示“较基线提升 / 下降”或“无可比基线”。
- 鼠标悬停显示指标公式和统计口径，例如“逐运单均值”或“全局合计”。

第三行：核心结论区域，占页面宽度约 2/3：

- 左侧为算法综合对比图。
- 右侧为“研究结论摘要”面板。

算法综合对比图要求：

- 默认使用分组柱状图，横轴为算法，纵轴为选定指标。
- 默认指标为 `CR_total`，支持切换 `CR_lossy`、`PED_avg`、`SED_avg`、`SR`、查询耗时和存储字节数。
- 本文方法使用固定强调色，其他算法使用低饱和对比色。
- 图表标题必须带单位，例如“各算法总压缩率对比（x）”。
- 禁止把不同量纲指标放在同一个普通纵轴上；多指标比较使用分面图、雷达图或独立图表。

研究结论摘要面板要求：

- 根据接口返回的指标动态生成 3 条以内的结论。
- 结论应包含数值和比较对象，例如：
  - “本文方法总压缩率最高。”
  - “语义点保留率保持 100%。”
  - “部分解压读取字节比例低于全量读取。”
- 结论生成必须基于真实返回值，不能把论文结论写成固定文案。
- 无法计算时显示“当前批次暂无可用结论”。

第四行：语义保真与几何误差双视图：

- 左图：`SR`、语义单元完整率、停留时长保真度对比。
- 右图：`PED_avg`、`SED_avg` 对比。
- 语义指标使用百分比轴，误差指标使用米轴。
- 每张图下方显示一句口径说明，避免答辩时误解。

第五行：存储与查询性能区域：

- 左图：chunk 时长与部分读取字节比例折线图。
- 右图：全量解压与部分解压耗时对比。
- 图中标出当前系统默认 chunk 参数。
- 显示“压缩率—读取比例—查询耗时”的权衡关系。

### 13.3 日常分析模式功能

日常分析模式增加以下能力：

- 实验批次下拉框，可切换历史成功批次。
- 算法多选和指标多选。
- 按运单号、车牌号筛选单运单结果。
- 点击图表中的算法进入算法详情。
- 点击表格中的运单进入单运单轨迹对比页。
- 查看失败文件和失败原因。
- 查看参数 JSON、数据源目录和实验运行耗时。

### 13.4 图表视觉规范

- 使用统一的中文字体、字号和数字格式。
- 所有图表标题、坐标轴、图例和 tooltip 使用中文。
- 颜色要有固定语义：
  - 本文方法：深蓝或青绿色强调色。
  - DP / DPS / TD-TR / Trajic：不同但低饱和的对比色。
  - 语义保真：绿色系。
  - 误差和耗时：橙色或红色系。
- 不使用 3D 饼图、立体柱状图和装饰性动画。
- 动画时间要短，答辩截图时允许关闭动画。
- 空数据、部分算法不可用、实验失败时必须有清晰的状态标签。
- 所有图表支持下载 PNG；下载图片应包含标题、批次号和数据时间。
- 图表尺寸固定，避免切换筛选条件后页面抖动或文字溢出。

### 13.5 答辩截图专用功能

增加一个“答辩截图”按钮，生成适合论文和答辩使用的干净视图：

- 隐藏侧边菜单、调试按钮和接口状态详情。
- 保留标题、实验批次、核心 KPI、算法对比、语义保真、查询性能和结论摘要。
- 固定 16:9 布局，确保一次截图能包含主要结果。
- 图片底部显示“数据批次、运行时间、数据规模、参数配置”。
- 支持导出 `PNG`，文件名包含实验批次号和日期。
- 导出前检查图表是否加载完成，禁止导出空白图或未完成渲染的图。

### 13.6 Dashboard 数据接口建议

建议后端提供一个聚合接口，减少前端多次请求和页面闪烁：

- `GET /api/visual/evaluation/dashboard?runId=xxx`

返回结构建议包括：

```json
{
  "run": {},
  "kpi": {},
  "algorithmComparison": [],
  "semanticComparison": [],
  "errorComparison": [],
  "storageQueryComparison": [],
  "ablation": [],
  "parameterSensitivity": [],
  "conclusions": [],
  "dataStatus": "SUCCESS"
}
```

前端仍可在下钻时调用明细接口，但首屏 Dashboard 尽量使用一次聚合请求完成。

## 15. 前端启动说明

前端使用 Vue 3 + Vite。

启动步骤：

```bash
npm install
npm run dev
```

高德地图 key 配置：

- 放在 `.env.development` 或 `src/config/amap.ts`。
- 不要直接写死在 Vue 组件中。
- README 中写清楚替换位置。
- 当前本地开发 Key：`4bac814632514d8f89264f690ca8d4eb`
- 优先写入 `.env.development` 的 `VITE_AMAP_KEY`，并在生产环境改为独立环境变量。

示例：

```env
VITE_AMAP_KEY=4bac814632514d8f89264f690ca8d4eb
VITE_API_BASE_URL=http://127.0.0.1:8080
```

## 16. 后端启动说明

后端项目目录：

```text
D:\aoming\电科\毕业论文 - claude\05可视化系统\trajectory-visual-server
```

启动步骤：

1. 确认 JDK 8。
2. 确认 Maven 可用。
3. 启动本地 MySQL，并确保库名为 `ml_network_freight`。
4. 启动本地 MongoDB。
5. 修改 `application-local.yml` 的 MySQL、MongoDB 和全量轨迹源目录。
6. 进入 `trajectory-visual-server` 目录，使用 local profile 启动 Spring Boot，例如：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

也可以使用 IDE 启动主启动类，并激活 `local` profile。

7. 打开接口文档或前端页面验证接口。

## 17. 实现顺序

建议按下面顺序实现：

1. 新增 `TrajectoryChunkDoc`。
2. 新增压缩分片 Mongo service。
3. 新增轨迹可视化 DTO。
4. 新增单运单轨迹查询与压缩接口。
5. 新增全量轨迹源目录读取配置。
6. 新增全量压缩任务接口。
7. 新增压缩任务进度查询接口。
8. 新增时间窗口部分检索接口。
9. 新增第 6 章实验结果 MySQL 表、PO、Mapper、Service。
10. 新增第 6 章实验测试启动类，每个实验一个方法，并提供 `runAllExperiments()`。
11. 新增评估结果查询 service。
12. 新增算法对比结果查询 service。
13. 新增 Vue 3 前端页面。
14. 接入高德地图。
15. 接入 ECharts 图表。
16. 分别编写后端 `trajectory-visual-server/README.md` 和前端 `trajectory-visual-web/README.md`，说明各自启动、配置、接口联调和使用方式。

## 18. 关键代码注释与可复现性要求

关键步骤必须增加详细、面向论文复现的中文注释。注释不能只写“遍历列表”“保存数据”这类无信息量内容，也不要对每一行代码机械翻译。应重点解释“为什么这样做、输入输出是什么、参数代表什么、和其他算法有什么区别、会影响哪些评估指标”。

### 16.1 所有核心流程都要注释

以下代码必须有类级、方法级和关键分支注释：

- 全量轨迹源文件扫描、JSON 解析和异常文件处理。
- 坐标系统一以及 WGS84 转 GCJ-02。
- 轨迹点清洗、时间排序、重复时间戳处理和异常速度处理。
- 停留段识别、停留时长计算、起止锚点构建。
- 本文方法的“静止段锚点保留 + 移动段 DP 抽稀”。
- chunk 切分、时间索引建立、差分 / ZigZag / 变长编码、压缩和解压。
- MongoDB `TrajectoryChunkDoc` 写入、查询和按时间窗口部分解压。
- `PED`、`SED`、`CR_lossy`、`CR_lossless`、`CR_total`、`SR`、语义单元完整率等指标计算。
- 第 6 章测试启动类中每个实验方法的数据范围、参数、输出表和异常处理。

方法级注释至少说明：

- 方法用途。
- 输入数据及单位。
- 输出结果及统计口径。
- 关键参数及默认值。
- 时间复杂度和空间复杂度（适用时）。
- 是否会读 MySQL、读 MongoDB 或写 MongoDB。
- 失败时的处理方式。

### 16.2 其他压缩算法必须详细注释

DP、DPS、TD-TR、Trajic 等对比算法必须单独封装，不要把多种算法揉进一个难以辨认的“大方法”。每个算法至少包含：

- 独立的算法类或策略实现。
- 统一的压缩接口，例如 `compress(List<LocationDTO> points, CompressionContext context)`。
- 算法名称、版本、参数和实现状态。
- 单元测试或最小可运行示例。
- 与本文方法一致的输出结构，便于统一计算指标。

每种算法实现前，应在类注释中说明：

1. 算法的基本思想。
2. 论文中采用的输入和输出。
3. 本实现与原始论文算法的差异或工程化简化。
4. 主要参数及单位，例如 DP 容差使用米。
5. 是否支持在线处理、是否需要完整轨迹、是否使用时间信息。
6. 是否显式保护停留锚点。
7. 该算法可能影响的指标，例如压缩率、PED、SED、SR。
8. 预期时间复杂度、空间复杂度和适用场景。

关键代码分支必须解释：

- DP 如何选择端点、计算点到线段距离、寻找最大偏差点以及递归 / 栈式分裂。
- DPS 如何加入方向角约束，方向阈值如何影响保留点。
- TD-TR 如何使用时间同步误差，时间插值和空间误差如何计算。
- Trajic 如何进行预测、残差编码或等价工程化处理；如果当前仓库没有完整实现，必须明确标注 `not_available`，不要伪造论文结果。
- 对比算法为什么可能删除停留段起止锚点，以及这会如何影响 `SR` 和语义单元完整率。
- 本文方法为什么把停留段从移动段中切出，以及锚点强制保留如何保证停留时长可恢复。

推荐注释形式：

```java
/**
 * 使用 Douglas-Peucker 对移动段进行几何抽稀。
 *
 * 这里仅处理移动段，不处理停留单元内部点。停留单元已经由
 * 语义识别阶段切分，并通过起止锚点保留停留时长，因此不能把
 * 停留点直接交给普通 DP，否则可能只剩一个点，导致停留时长无法恢复。
 *
 * @param points 移动段轨迹点，时间升序，坐标已统一为 GCJ-02
 * @param toleranceMeters 垂直距离容差，单位：米
 * @return 抽稀后的轨迹点，始终保留输入段首尾点
 */
public List<LocationDTO> compressMovingSegment(
        List<LocationDTO> points, double toleranceMeters) {
    // ...
}
```

注释要求：

- 公式、阈值和单位要写清楚。
- 说明边界条件：空列表、单点、双点、重复坐标、时间倒序、缺失速度。
- 说明参数变化对结果的影响。
- 说明为什么该实现与本文评估口径兼容。
- 算法注释、实验注释、配置注释统一使用中文。

### 16.3 实验代码注释

`TrajectoryChapter6ExperimentTest` 中每个实验方法开头要写清楚：

- 对应论文第几章、第几节、第几张表或哪一类图。
- 数据源目录或 MySQL 查询范围。
- 使用的算法和参数。
- 汇总方式：均值、中位数、最大值、逐运单平均或全局合计。
- 结果写入哪张 MySQL 表。
- 如何从数据库记录还原前端图表。

总入口 `runAllExperiments()` 要注明执行顺序、实验之间的数据依赖和失败隔离策略。

## 19. Claude Code 自检与自我修正要求

实现完成后，Claude Code 必须结合本文档进行自检，并根据自检结果主动修正，不要只输出“已完成”。

自检流程：

1. 检查输出目录是否正确。
- 后端必须在 `D:\aoming\电科\毕业论文 - claude\05可视化系统\trajectory-visual-server`。
- 前端必须在 `D:\aoming\电科\毕业论文 - claude\05可视化系统\trajectory-visual-web`。
- SQL、README、接口文档、导出目录必须在 `D:\aoming\电科\毕业论文 - claude\05可视化系统` 下。
- 不允许把新系统代码写入老项目 `fkh-network-freight`。

2. 检查配置是否正确。
- MySQL 指向 `127.0.0.1:3306/ml_network_freight`，账号密码 `root/root`。
- MongoDB 指向 `127.0.0.1:27017/ml_network_freight`，无密码。
- 全量轨迹源目录指向 `D:\aoming\电科\毕业论文 - claude\00数据处理-260909\source\_data\_full`。
- 高德 Key 放在前端环境变量或配置文件中，不直接写死在组件里。

3. 检查后端能力是否完整。
- 单运单轨迹查询。
- 单运单压缩。
- 全量轨迹压缩。
- 压缩任务进度查询。
- MongoDB chunk 写入和查询。
- 时间窗口部分解压。
- 第 6 章实验结果 MySQL 写入。
- 第 6 章评估结果查询接口。

4. 检查第 6 章实验测试类。
- 是否存在 `TrajectoryChapter6ExperimentTest`。
- 每个实验是否有单独方法。
- 是否存在 `runAllExperiments()` 总入口。
- 每个实验是否写入对应 MySQL 表。
- 失败记录是否写入 `trajectory_eval_error_record`。

5. 检查前端页面。
- 轨迹压缩工作台。
- 时间窗口部分检索。
- 第 6 章评估 Dashboard。
- 算法压缩效果对比。
- 消融实验与参数敏感性。
- Dashboard 是否达到答辩截图级效果，并支持 PNG 导出。

6. 检查注释。
- 本文方法、DP、DPS、TD-TR、Trajic、分块编码、指标计算和测试启动类是否有中文关键注释。
- 注释是否说明算法原理、参数单位、边界条件、复杂度和评估影响。

7. 检查启动与文档。
- 后端 README 是否说明启动步骤。
- 前端 README 是否说明启动步骤。
- SQL 建表脚本是否完整。
- 接口文档是否覆盖主要接口。
- 是否说明如何运行第 6 章测试启动类。

自我修正要求：

- 如果任一项不满足，Claude Code 必须继续修改，直到满足本文档要求。
- 如果某项因缺少数据、缺少依赖或算法未实现而无法完成，必须在 README 和最终说明中明确列出原因、影响范围和后续补齐方式。
- 不允许用假数据、硬编码指标或空实现冒充完成。
- 最终输出必须包含自检清单结果，按“已完成 / 未完成 / 原因 / 文件位置”说明。

## 20. 验收标准

完成后应满足：

- 可以搜索运单。
- 可以查看单条运单原始轨迹。
- 可以执行单条运单压缩。
- 可以点击前端“全量轨迹压缩”按钮触发批量压缩。
- 批量压缩能读取 `D:\aoming\电科\毕业论文 - claude\00数据处理-260909\source\_data\_full` 下的轨迹源文件。
- 压缩后 chunk 分片进入 MongoDB。
- 原始轨迹不进入 MongoDB。
- 可以展示压缩前后轨迹对比。
- 可以展示停留点和停留时长。
- 可以按时间窗口检索部分轨迹。
- 可以展示第 6 章评估总览。
- 第 6 章 Dashboard 达到答辩截图要求，首屏能展示核心 KPI、算法对比、语义保真、误差和查询性能。
- Dashboard 支持答辩展示模式、日常分析模式和 PNG 导出。
- Dashboard 能显示实验批次、数据规模、运行时间、参数和数据状态。
- 可以展示不同算法压缩效果对比。
- 可以展示消融实验和参数敏感性图表。
- 第 6 章实验可以通过测试启动类运行。
- 每个实验都可以单独启动。
- `runAllExperiments()` 可以一键启动全部第 6 章实验。
- 实验结果可以写入 MySQL。
- 前端评估页面从 MySQL 读取实验结果，不硬编码指标。
- 新系统后端代码位于 `trajectory-visual-server`，没有误写入老项目 `fkh-network-freight`。
- 新系统前后端位于 `D:\aoming\电科\毕业论文 - claude\05可视化系统` 下的两个同级项目目录。
- DP、DPS、TD-TR、Trajic 等对比算法有独立实现或明确的 `not_available` 状态。
- 本文方法、对比算法、分块编码、指标计算和测试启动类均有足够的中文关键注释。
- 注释能够说明算法原理、参数单位、边界条件、复杂度和对评估结果的影响。
- 后端项目有独立的 `trajectory-visual-server/README.md`，能让开发者单独启动后端并运行第 6 章实验。
- 前端项目有独立的 `trajectory-visual-web/README.md`，能让开发者单独启动前端并配置高德地图。
