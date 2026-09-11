# trajectory-visual-web（前端）

硕士论文第 5.3 节"可视化子系统"的在线版前端：Vue 3 + Vite + Element Plus + ECharts + 高德地图 JS API。

页面与后端 `trajectory-visual-server` 联调；后端必须已启动且本地 MySQL/Mongo 可用。

## 1. 前置环境

- Node.js 16+（本地实测 Node v24 可跑 Vite 5）
- npm

## 2. 安装与启动

```bash
npm install
npm run dev        # 本地开发 http://127.0.0.1:5173
npm run build      # 生产构建 → dist/
npm run preview    # 预览构建产物
```

## 3. 配置说明

`.env.development`（本地）：

```env
VITE_AMAP_KEY=4bac814632514d8f89264f690ca8d4eb
VITE_API_BASE_URL=http://127.0.0.1:8080
```

- `VITE_API_BASE_URL`：后端地址（默认 8080，即 trajectory-visual-server local profile）。
- `VITE_AMAP_KEY`：高德地图 Key，**只在环境变量/配置中，不写死在组件**（见 `src/utils/amap.ts` 的加载逻辑）。
- 生产环境改 `.env.production`，勿提交真实生产 Key；如需高德 v2 + 安全密钥，设 `VITE_AMAP_SECURITY_CODE`。

## 4. 页面与路由

| 路由 | 页面 | 功能 |
|---|---|---|
| `/workbench` | 轨迹压缩工作台 | 运单筛选/分页（含收发货地点/时间与收发坐标）；地图叠加**原始(蓝实线,更宽作底衬)+压缩(绿实线)**、**被有损压缩删除的点(橙色高亮散点)**、停留锚点、收发地标记，带方向箭头与起/终标注；底部"压缩指标"卡回显 CR_lossy/CR_lossless/CR_total/PED/SED/SR 等；压缩当前运单 / 全量压缩（带进度） |
| `/timewindow` | 时间窗口部分检索 | 顶部提示该运单的轨迹时间范围、分片时长（几秒一片）与分片数；时间窗命中分片、部分 vs 全量解压的字节/耗时/一致性 |
| `/dashboard` | 实验评估中心 | 答辩级 Dashboard：KPI、算法对比（可切指标）、研究结论摘要、语义保真/几何误差双视图、chunk 权衡、性能对比；答辩展示/日常分析双模式；PNG 导出；指标 CSV 导出 |
| `/compare` | 算法压缩效果对比 | 指标多选、柱/折/雷达图切换、各算法 mean/median/min/max、单运单下钻 |
| `/ablation` | 消融与参数敏感性 | A0/A-TIGHT/A2/A3/A4 柱图与表格、DP 容差扫描 SR 曲线、chunk 时长权衡曲线 |

> 坐标口径：地图上的一切（轨迹、停留锚点、收发地标记）都是 GCJ-02，源数据本身即 GCJ-02，
> 前端与后端都不做坐标系转换，直接叠加高德底图。

## 5. Dashboard 答辩展示模式与 PNG 导出

- 进入 Dashboard 后默认加载最近一次含算法结果的成功实验批次（数据全部来自 MySQL）。
- 点击"切到答辩展示"隐藏分析类操作、固定版式便于 16:9 截图。
- "导出 Dashboard 图片"用 html2canvas 生成 PNG（文件名含批次号+日期）；导出前图表面板均已渲染完成。
- "导出指标 CSV"导出算法对比 + 消融明细（BOM UTF-8，Excel 可直接打开）。

## 6. 后端联调说明

前端请求直接打到 `VITE_API_BASE_URL`；开发时请确保后端已启动且：
- MySQL 结果表存在（`sql/init.sql`）并有实验批次（运行第 6 章测试类）；
- 需要地图叠加的数据对应运单已压缩（Mongo 分片存在，原始轨迹始终从后端源文件读取）。

若评估页显示空态："没有可用的实验批次 / 当前批次无算法结果"，请先运行后端 `TrajectoryChapter6ExperimentTest` 的 6.3 主实验。
