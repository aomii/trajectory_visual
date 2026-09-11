<template>
  <div class="dash" :class="{ 'export-mode': exportMode }">
    <!-- 顶栏 -->
    <el-card shadow="never" class="mb8">
      <div class="dash-head">
        <div>
          <div class="dash-title">轨迹压缩实验评估中心</div>
          <div class="dash-sub">语义保持、压缩效率与局部查询性能综合评估 · LogiCompress</div>
        </div>
        <div class="head-right">
          <el-select v-model="runId" size="small" style="width: 300px" @change="loadData">
            <el-option v-for="r in runs" :key="r.id" :value="r.id" :label="`${r.runNo} · ${r.runName} · ${r.status}`" />
          </el-select>
          <el-button size="small" @click="loadData">刷新结果</el-button>
          <el-button size="small" type="primary" plain @click="exportMode = !exportMode">
            {{ exportMode ? '切回日常分析' : '切到答辩展示' }}
          </el-button>
          <el-button size="small" type="success" plain @click="exportPNG">导出 Dashboard 图片</el-button>
          <el-button size="small" @click="exportCSV">导出指标 CSV</el-button>
        </div>
      </div>
      <template v-if="run">
        <div class="run-meta">
          批次 <b>{{ run.runNo }}</b> · {{ run.runName }} · 状态
          <el-tag :type="run.status === 'SUCCESS' ? 'success' : 'warning'" size="small">{{ run.status }}</el-tag>
          · 完成 {{ run.finishedAt }} · 有效运单 {{ run.waybillCount ?? '-' }} ·
          数据源 <span class="ellip">{{ run.dataSourceDir }}</span>
        </div>
        <div v-if="kpi.dataStatus === 'EMPTY'" class="empty-hint">
          当前批次没有算法结果（例如仅 6.2/参数敏感性批次）。请运行 6.3 主实验或切换批次。所有数字均来自 MySQL 实验表，未运行实验时不显示伪造值。
        </div>
      </template>
    </el-card>

    <!-- 数据主体：答辩展示模式下隐藏调试按钮与筛选，固定版式 -->
    <div class="dash-area">
      <!-- KPI 行 -->
      <el-row :gutter="10" class="mb8">
        <el-col v-for="c in kpiCards" :key="c.key" :span="4">
          <div class="kpi" :class="{ highlight: c.highlight }">
            <div class="kpi-label">{{ c.label }}</div>
            <div class="kpi-val num">{{ c.value }}<span class="kpi-unit">{{ c.unit }}</span></div>
            <div class="kpi-note">{{ c.note }}</div>
          </div>
        </el-col>
      </el-row>

      <!-- 核心结论行 -->
      <el-row :gutter="10" class="mb8">
        <el-col :span="16">
          <el-card shadow="never" header="算法综合对比" style="height: 330px">
            <div class="chart-tools">
              <el-radio-group v-model="metricKey" size="small" @change="renderAlgCompare">
                <el-radio-button v-for="m in metricOptions" :key="m.key" :value="m.key">{{ m.short }}</el-radio-button>
              </el-radio-group>
            </div>
            <div ref="elAlg" class="chart-box" style="height: 255px"></div>
          </el-card>
        </el-col>
        <el-col :span="8">
          <el-card shadow="never" header="研究结论摘要" style="height: 330px">
            <el-empty v-if="!conclusions.length" description="当前批次暂无可用结论" />
            <ul v-else class="concl">
              <li v-for="(c, i) in conclusions" :key="i">· {{ c }}</li>
            </ul>
          </el-card>
        </el-col>
      </el-row>

      <!-- 语义保真 + 几何误差 双视图 -->
      <el-row :gutter="10" class="mb8">
        <el-col :span="12">
          <el-card shadow="never" header="语义保真：SR / 单元完整率 / 时长保真度（%）" style="height: 300px">
            <div ref="elSem" class="chart-box" style="height: 240px"></div>
            <div class="axis-note">口径：SR=压缩后保留锚点/锚点总数；单元完整率=起终锚点成对保留单元占比；时长保真度=可恢复时长/原始时长（均为逐运单均值）。</div>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card shadow="never" header="几何误差：平均位置误差 PED / 同步误差 SED（m）" style="height: 300px">
            <div ref="elErr" class="chart-box" style="height: 240px"></div>
            <div class="axis-note">口径：PED=原始点到压缩折线垂直距离；SED=原始点到按时间插值同步点距离；越低调优（与"压得更少才更准"互为代价）。</div>
          </el-card>
        </el-col>
      </el-row>

      <!-- 存储与查询性能 -->
      <el-row :gutter="10">
        <el-col :span="14">
          <el-card shadow="never" :header="'chunk 时长权衡：压缩率 / 部分读取字节比例（标出默认 ' + blockLabel(defBlockWindowS) + '）'" style="height: 320px">
            <div ref="elStorage" class="chart-box" style="height: 260px"></div>
            <div class="axis-note">分块时长增大→总压缩率上升（块内差分更充分），但单次窗口命中读取字节比例上升。权衡曲线见上。</div>
          </el-card>
        </el-col>
        <el-col :span="10">
          <el-card shadow="never" header="解压性能：全量解压 vs 部分解压（本文方法）" style="height: 320px">
            <div ref="elPerf" class="chart-box" style="height: 200px"></div>
            <div class="accel">
              部分解压加速比 <b class="num">{{ accel }}</b>（全量解压均值 {{ ms(decodeMs) }} / 部分解压均值 {{ ms(queryMs) }}）；部分解压仅读取
              <b>{{ pct0(partialRatio) }}</b> 存储字节
            </div>
          </el-card>
        </el-col>
      </el-row>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import html2canvas from 'html2canvas'
import { getDashboard, getEvalRuns } from '../api'
import { ALG_NAMES, COLOR, colorOf, createChart, disposeChart, setOption } from '../charts/theme'
import { METRIC_META, downloadText, fmtInt, fmtNum, fmtPct, toCSV, nowTag } from '../utils/format'

const runs = ref<any[]>([])
const runId = ref<number | null>(null)
const data = ref<any>(null)
const exportMode = ref(false)
/** 后端配置里的默认分片时长（秒）；图题/标注线都用它，避免前端写死"600s" */
const defBlockWindowS = computed<number | null>(() => data.value?.storageQueryComparison?.defaultBlockWindowS ?? null)
/** 秒 → 人类可读的块长标签：3600→"1h"、600→"10min" */
function blockLabel(sec?: number | null) {
  if (!sec) return '—'
  return sec % 3600 === 0 ? (sec / 3600) + 'h' : Math.round(sec / 60) + 'min'
}
const metricKey = ref('crTotalAvg')

const elAlg = ref<HTMLDivElement>(), elSem = ref<HTMLDivElement>(), elErr = ref<HTMLDivElement>()
const elStorage = ref<HTMLDivElement>(), elPerf = ref<HTMLDivElement>()
const charts: any = {}

const run = computed(() => data.value?.run || null)
const kpi = computed(() => data.value?.kpi || {})
const conclusions = computed(() => data.value?.conclusions || [])
const algorithms = computed(() => data.value?.algorithmComparison || [])

const metricOptions = [
  { key: 'crTotalAvg', short: 'CR_total' },
  { key: 'crLossyAvg', short: 'CR_lossy' },
  { key: 'pedAvg', short: 'PED' },
  { key: 'sedAvg', short: 'SED' },
  { key: 'srAvg', short: 'SR' },
  { key: 'semanticUnitCompleteRate', short: '单元完整率' },
  { key: 'storageBytes', short: '存储' },
  { key: 'queryTimeMsAvg', short: '查询耗时' }
]

const srValue = computed(() => kpi.value.srAvg)
const decodeMs = computed(() => kpi.value.decodeTimeMsAvg)
const queryMs = computed(() => kpi.value.queryTimeMsAvg)
const partialRatio = computed(() => kpi.value.partialReadRatioAvg)
const accel = computed(() => {
  const d = decodeMs.value, q = queryMs.value
  if (d == null || q == null || q <= 0) return '-'
  return fmtNum(d / q, 1) + 'x'
})

const kpiCards = computed(() => {
  const k: any = kpi.value
  const proposed = algorithms.value.find((a: any) => a.algorithmCode === 'PROPOSED')
  const bestCr = algorithms.value.length ? Math.max(...algorithms.value.map((a: any) => a.crLossyAvg ?? 0)) : 0
  const kpiArr = [
    { key: 'waybillCount', label: '有效运单数', value: k.waybillCount == null ? '-' : fmtInt(k.waybillCount), unit: '', note: '参与统计的清洗后有效运单', highlight: false },
    { key: 'rawPointCount', label: '有效轨迹点数', value: k.rawPointCount == null ? '-' : fmtInt(k.rawPointCount), unit: '', note: '清洗后总点数（逐运单基准）', highlight: false },
    { key: 'crTotalAvg', label: '总压缩率 CR_total', value: k.crTotalAvg == null ? '-' : fmtNum(k.crTotalAvg, 2), unit: 'x', note: compNote('本文总压缩率', k.crTotalAvg, 'CR_total'), highlight: false },
    { key: 'crLossyAvg', label: '有损层压缩率', value: k.crLossyAvg == null ? '-' : fmtNum(k.crLossyAvg, 2), unit: 'x', note: proposed ? '有损层减点数（本文' + fmtNum(proposed.crLossyAvg, 1) + ' vs DP ' + fmtNum(bestCr, 1) + '）' : '—', highlight: false },
    { key: 'srAvg', label: '语义点保留率 SR', value: k.srAvg == null ? '-' : fmtPct(k.srAvg, 0), unit: '', note: '语义锚点压缩后保留比例', highlight: k.srAvg === 1 },
    { key: 'accel', label: '部分解压加速比', value: accel.value, unit: '', note: '全量解压/部分解压（1h窗）', highlight: false }
  ]
  return kpiArr.filter((x) => x.value !== '-')
})

function compNote(prefix: string, val: number, field: string) {
  const others = algorithms.value.filter((a: any) => a.algorithmCode !== 'PROPOSED' && a[field] != null)
  if (!others.length) return prefix
  const top = others.reduce((m: any, a: any) => (a[field] > (m?.[field] ?? -1) ? a : m), null)
  if (top && field === 'crTotalAvg' && val >= top[field]) return '为全部算法最高'
  return prefix + ' ' + fmtNum(val, 2)
}

function pct0(v?: number) { return v == null ? '-' : (v * 100).toFixed(1) + '%' }
function ms(v?: number) { return v == null ? '-' : fmtNum(v, 2) + ' ms' }

async function loadRuns() {
  runs.value = await getEvalRuns()
  // 首屏**不指定批次**：交给后端挑默认批（优先"含完整算法对比"的 6.3 主实验批，
  // 见 EvaluationQueryService.resolveRun）。原来在前端取"最新 SUCCESS 批"会选中
  // 6.4/6.5 这类只写单算法的批次，导致算法对比图只剩一条线。
  runId.value = null
}
async function loadData() {
  try {
    data.value = await getDashboard(runId.value ?? undefined)
    // 后端解析出的实际批次回填下拉框，让用户看得到当前是哪一批
    if (data.value?.run?.id != null) runId.value = data.value.run.id
    await nextTick()
    renderAll()
  } catch (e: any) {
    ElMessage.error(e?.message || 'Dashboard 数据加载失败')
  }
}

function renderAll() {
  renderAlgCompare()
  renderSemantic()
  renderError()
  renderStorage()
  renderPerf()
}

function renderAlgCompare() {
  if (!elAlg.value) return
  const meta = METRIC_META[metricKey.value] || { unit: '', label: metricKey.value }
  const rows = algorithms.value
  const names = rows.map((a: any) => ALG_NAMES[a.algorithmCode] || a.algorithmCode)
  const values = rows.map((a: any) => (a[metricKey.value] == null ? 0 : a[metricKey.value]))
  const isPct = metricKey.value === 'srAvg' || metricKey.value === 'semanticUnitCompleteRate'
  const fmtY = (v: number) => (isPct ? v * 100 : v)
  const showPct = isPct
  setOption(ensure('alg'), {
    animation: !exportMode.value,
    tooltip: {
      trigger: 'axis',
      valueFormatter: (v: any) => (showPct ? Number(v).toFixed(1) + '%' : typeof v === 'number' ? fmtNum(v, 2) : v)
    },
    grid: { left: 16, right: 24, top: 40, bottom: 8, containLabel: true },
    xAxis: { type: 'category', data: names, axisLabel: { color: '#334155' } },
    yAxis: { type: 'value', name: meta.unit, nameTextStyle: { color: '#7a8699' }, axisLabel: { color: '#334155', formatter: (v: number) => (showPct ? v + '%' : String(v)) } },
    series: [{
      name: meta.label,
      type: 'bar',
      data: values.map(fmtY),
      barWidth: 40,
      itemStyle: {
        color: (p: any) => (rows[p.dataIndex]?.algorithmCode === 'PROPOSED' ? COLOR.PROPOSED : colorOf(rows[p.dataIndex]?.algorithmCode))
      },
      label: { show: true, position: 'top', formatter: (p: any) => (showPct ? p.value.toFixed(1) + '%' : fmtNum(p.value, 1)) }
    }]
  })
}

function ensure(key: string) {
  if (!charts[key]) {
    const map: any = { alg: elAlg, sem: elSem, err: elErr, storage: elStorage, perf: elPerf }
    const el = map[key].value
    if (el) charts[key] = createChart(el)
  }
  return charts[key]
}

function renderSemantic() {
  if (!elSem.value) return
  const rows = algorithms.value
  const names = rows.map((a: any) => ALG_NAMES[a.algorithmCode] || a.algorithmCode)
  const s = (f: string) => rows.map((a: any) => ((a[f] == null ? 0 : a[f]) * 100).toFixed(1))
  setOption(ensure('sem'), {
    animation: !exportMode.value,
    color: [COLOR.semantic, '#58a55f', '#b0c975'],
    tooltip: { trigger: 'axis', valueFormatter: (v: any) => v + '%' },
    legend: { bottom: 0, data: ['语义点保留率 SR', '语义单元完整率', '停留时长保真度'] },
    grid: { left: 16, right: 24, top: 20, bottom: 28, containLabel: true },
    xAxis: { type: 'category', data: names, axisLabel: { color: '#334155' } },
    yAxis: { type: 'value', axisLabel: { formatter: '{value}%', color: '#334155' } },
    series: [
      { name: '语义点保留率 SR', type: 'bar', data: s('srAvg'), barWidth: 18, itemStyle: { color: (p: any) => (rows[p.dataIndex]?.algorithmCode === 'PROPOSED' ? COLOR.PROPOSED : COLOR.semantic) } },
      { name: '语义单元完整率', type: 'bar', data: s('semanticUnitCompleteRate'), barWidth: 18 },
      { name: '停留时长保真度', type: 'bar', data: s('stayDurationPreserveRate'), barWidth: 18 }
    ]
  })
}

function renderError() {
  if (!elErr.value) return
  const rows = algorithms.value
  const names = rows.map((a: any) => ALG_NAMES[a.algorithmCode] || a.algorithmCode)
  const ped = rows.map((a: any) => a.pedAvg == null ? 0 : a.pedAvg)
  const sed = rows.map((a: any) => a.sedAvg == null ? 0 : a.sedAvg)
  setOption(ensure('err'), {
    animation: !exportMode.value,
    color: [COLOR.error, '#b78a4a'],
    tooltip: { trigger: 'axis', valueFormatter: (v: any) => fmtNum(v, 3) + ' m' },
    legend: { bottom: 0, data: ['平均位置误差 PED', '平均同步误差 SED'] },
    grid: { left: 16, right: 24, top: 20, bottom: 28, containLabel: true },
    xAxis: { type: 'category', data: names, axisLabel: { color: '#334155' } },
    yAxis: { type: 'value', name: 'm', axisLabel: { color: '#334155' } },
    series: [
      { name: '平均位置误差 PED', type: 'bar', data: ped.map((v) => +Number(v).toFixed(2)), barWidth: 18 },
      { name: '平均同步误差 SED', type: 'bar', data: sed.map((v) => +Number(v).toFixed(2)), barWidth: 18 }
    ]
  })
}

function renderStorage() {
  if (!elStorage.value) return
  const series = data.value?.storageQueryComparison?.series || []
  // 默认分片时长由后端配置给出（2026-09-11 起 3600s=1h），前端不写死；拿不到就不画标注线
  const def = data.value?.storageQueryComparison?.defaultBlockWindowS ?? null
  const x = series.map((s: any) => (s.blockWindowS / 60) + 'min')
  const cr = series.map((s: any) => s.crTotalAvg ?? 0)
  const rr = series.map((s: any) => (s.partialReadRatioAvg ?? 0) * 100)
  const opt: any = {
    animation: !exportMode.value,
    tooltip: { trigger: 'axis', valueFormatter: (v: any) => String(v) },
    legend: { bottom: 0, data: ['总压缩率 CR_total', '部分读取字节比例'] },
    grid: { left: 16, right: 40, top: 30, bottom: 30, containLabel: true },
    xAxis: { type: 'category', data: x, axisLabel: { color: '#334155' } },
    yAxis: [
      { type: 'value', name: 'CR_total(x)', nameTextStyle: { color: '#7a8699' }, axisLabel: { color: '#334155' } },
      { type: 'value', name: '读取比例(%)', nameTextStyle: { color: '#7a8699' }, axisLabel: { color: '#334155', formatter: '{value}%' } }
    ],
    series: [
      { name: '总压缩率 CR_total', type: 'line', data: cr, smooth: true, symbolSize: 7, lineStyle: { color: COLOR.PROPOSED, width: 3 }, itemStyle: { color: COLOR.PROPOSED }, markLine: def != null && x.indexOf((def / 60) + 'min') >= 0 ? { symbol: 'none', label: { formatter: '默认 ' + blockLabel(def), position: 'insideEndTop' }, lineStyle: { type: 'dashed', color: '#d96a3c' }, data: [{ xAxis: (def / 60) + 'min' }] } : undefined },
      { name: '部分读取字节比例', type: 'line', yAxisIndex: 1, data: rr.map((v: number) => +v.toFixed(1)), smooth: true, symbolSize: 7, lineStyle: { color: COLOR.error, width: 2 }, itemStyle: { color: COLOR.error } }
    ]
  }
  setOption(ensure('storage'), opt)
}

function renderPerf() {
  if (!elPerf.value) return
  setOption(ensure('perf'), {
    animation: !exportMode.value,
    tooltip: { trigger: 'axis', valueFormatter: (v: any) => fmtNum(v, 2) + ' ms' },
    grid: { left: 16, right: 16, top: 20, bottom: 20, containLabel: true },
    xAxis: { type: 'category', data: ['全量解压', '部分解压(1h窗)'], axisLabel: { color: '#334155' } },
    yAxis: { type: 'value', name: 'ms', axisLabel: { color: '#334155' } },
    series: [{
      type: 'bar', barWidth: 44, data: [decodeMs.value ?? 0, queryMs.value ?? 0],
      itemStyle: { color: (p: any) => (p.dataIndex === 0 ? '#b78a4a' : COLOR.PROPOSED) },
      label: { show: true, position: 'top', formatter: (p: any) => fmtNum(p.value, 2) + ' ms' }
    }]
  })
}

async function exportPNG() {
  const area = document.querySelector('.dash-area') as HTMLElement
  if (!area) return
  ElMessage.info('正在渲染并导出 PNG…')
  const canvas = await html2canvas(area, { scale: 2, useCORS: true, backgroundColor: '#f2f4f7' })
  const a = document.createElement('a')
  a.download = `dashboard_${run.value?.runNo || 'batch'}_${nowTag()}.png`
  a.href = canvas.toDataURL('image/png')
  a.click()
}

function exportCSV() {
  const algRows = algorithms.value.map((a: any) => ({
    algorithm: ALG_NAMES[a.algorithmCode] || a.algorithmCode,
    crTotal: a.crTotalAvg, crLossy: a.crLossyAvg, ped: a.pedAvg, sed: a.sedAvg,
    sr: a.srAvg == null ? '' : a.srAvg, unitIntegrity: a.semanticUnitCompleteRate,
    dwellFidelity: a.stayDurationPreserveRate, queryMs: a.queryTimeMsAvg,
    storageBytes: a.storageBytes, waybills: a.waybillCount
  }))
  const ablRows = (data.value?.ablation || []).map((a: any) => ({
    code: a.ablationCode, name: a.ablationName, crTotal: a.crTotalAvg, sr: a.srAvg, remark: a.remark
  }))
  const text = '算法对比\n' + toCSV(algRows) + '\n\n消融实验\n' + toCSV(ablRows)
  downloadText(`eval_metrics_${run.value?.runNo || ''}_${nowTag()}.csv`, text)
}

function resize() { Object.values(charts).forEach((c: any) => c && c.resize()) }
window.addEventListener('resize', resize)

onMounted(async () => {
  await loadRuns()
  await loadData()
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  Object.values(charts).forEach((c: any) => disposeChart(c))
})
</script>

<style scoped>
.dash-area { }
.dash-head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.dash-title { font-size: 20px; font-weight: 700; color: #0b2b4a; }
.dash-sub { font-size: 12px; color: #7a8699; margin-top: 2px; }
.head-right { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
.run-meta { margin-top: 10px; font-size: 12px; color: #5a6a7d; display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.ellip { max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; display: inline-block; vertical-align: bottom; }
.empty-hint { margin-top: 8px; color: #b06b00; background: #fdf3e0; padding: 8px 10px; border-radius: 4px; font-size: 12px; }
.kpi { background: #fff; border: 1px solid #e6e9ee; border-radius: 6px; padding: 12px 14px; height: 92px; }
.kpi.highlight { border-color: #2e9e63; background: #eefaf3; box-shadow: 0 0 0 2px #cdeedd; }
.kpi-label { font-size: 12px; color: #7a8699; }
.kpi-val { font-size: 26px; font-weight: 700; color: #0b2b4a; margin: 4px 0 2px; }
.kpi.highlight .kpi-val { color: #16794a; }
.kpi-unit { font-size: 13px; color: #8b98a7; font-weight: 400; margin-left: 2px; }
.kpi-note { font-size: 11px; color: #9aa6b4; }
.chart-box { width: 100%; }
.chart-tools { position: absolute; top: 8px; right: 12px; z-index: 2; }
.concl { margin: 8px 0 0; padding-left: 4px; list-style: none; font-size: 13px; color: #334155; line-height: 1.9; }
.axis-note { font-size: 11px; color: #98a5b3; margin-top: 2px; }
.accel { margin-top: 8px; font-size: 13px; color: #334155; }
</style>
