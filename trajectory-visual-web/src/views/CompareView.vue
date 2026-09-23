<template>
  <div>
    <el-card shadow="never" class="mb8">
      <el-form inline>
        <el-form-item label="对比指标">
          <el-select v-model="metricKey" style="width: 200px">
            <el-option v-for="m in metricOptions" :key="m.key" :value="m.key" :label="m.label + '（' + m.unit + '）'" />
          </el-select>
        </el-form-item>
        <el-form-item label="图表类型">
          <el-radio-group v-model="chartType">
            <el-radio-button value="bar">柱状图</el-radio-button>
            <el-radio-button value="line">折线图</el-radio-button>
            <el-radio-button value="radar">雷达图</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="算法">
          <el-checkbox-group v-model="checkedAlgs">
            <el-checkbox-button v-for="a in allAlgs" :key="a" :value="a">{{ algName(a) }}</el-checkbox-button>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="render">刷新</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-row :gutter="10">
      <el-col :span="14">
        <el-card shadow="never" header="多算法对比" style="height: 420px">
          <div ref="elChart" class="chart-box" style="height: 350px"></div>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card shadow="never" header="单运单下钻（各算法同一运单指标）" style="height: 420px">
          <div class="mb8">
            <el-input-number v-model="drillId" :controls="false" placeholder="waybillId" style="width: 200px" />
            <el-button style="margin-left:6px" @click="loadDrill">查询</el-button>
          </div>
          <el-table :data="drillRows" size="small" :max-height="330" v-loading="drilling">
            <el-table-column prop="algorithmCode" label="算法" width="80">
              <template #default="{ row }"><el-tag size="small" :type="row.algorithmCode === 'PROPOSED' ? 'primary' : 'info'">{{ algName(row.algorithmCode) }}</el-tag></template>
            </el-table-column>
            <el-table-column label="端到端CR"><template #default="{ row }">{{ num(row.crE2e, 2) }}</template></el-table-column>
            <el-table-column label="CR_lossy"><template #default="{ row }">{{ num(row.crLossy, 2) }}</template></el-table-column>
            <el-table-column label="PED(m)"><template #default="{ row }">{{ num(row.pedAvg, 3) }}</template></el-table-column>
            <el-table-column label="SED(m)"><template #default="{ row }">{{ num(row.sedAvg, 3) }}</template></el-table-column>
            <el-table-column label="SR"><template #default="{ row }">{{ row.sr == null ? '-' : pct(row.sr) }}</template></el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 单运单下钻：各算法保留轨迹地图叠加 -->
    <el-card shadow="never" class="mt8">
      <template #header>
        <span>单运单下钻：各算法保留轨迹地图对比</span>
        <span class="muted" style="margin-left:8px;font-weight:400">
          同一运单、同一清洗轨迹、同一 10 m 容差。各算法几何高度重合，建议<b>只勾 2~3 个算法</b>、并关掉"轨迹线"只看"保留点"。
        </span>
      </template>
      <template v-if="trackData">
        <div class="ctl">
          <span class="ctl-label">地图显示算法</span>
          <el-checkbox-group v-model="mapAlgs" size="small">
            <el-checkbox-button v-for="a in allAlgs" :key="a" :value="a">{{ algName(a) }}</el-checkbox-button>
          </el-checkbox-group>
          <span class="ctl-label">图层</span>
          <el-checkbox-group v-model="mapLayers" size="small">
            <el-checkbox-button value="line">轨迹线</el-checkbox-button>
            <el-checkbox-button value="point">保留点</el-checkbox-button>
            <el-checkbox-button value="stop">停留锚点</el-checkbox-button>
          </el-checkbox-group>
          <el-button size="small" text type="primary" @click="mapAlgs = ['PROPOSED', 'DP']">只看本文 vs DP</el-button>
          <el-button size="small" text @click="mapAlgs = [...allAlgs]">全选算法</el-button>
        </div>

        <div class="algbar">
          <span v-for="a in trackData.algorithms" :key="a.algorithmCode"
                class="algchip" :class="{ off: !mapAlgs.includes(a.algorithmCode) }">
            <i :style="{ background: a.algorithmCode === 'PROPOSED' ? COLOR.PROPOSED : colorOf(a.algorithmCode) }"></i>
            {{ a.algorithmName }}<b>{{ a.keptPointCount }}</b>点
            <span class="muted">（CR_lossy {{ a.crLossy }}×）</span>
          </span>
          <span class="algchip muted">
            清洗后 {{ trackData.cleanedPointCount }} 点 ｜ 本文识别停留单元 {{ ourStopCount }} 个（灰字＝未在地图显示）
          </span>
        </div>

        <TrajectoryMap ref="mapRef" height="430px" />

        <div class="muted" style="margin-top:6px;line-height:1.7">
          说明：各算法共用同一条道路几何，整幅视图下轨迹线几乎完全重合——<b>差异主要体现在"保留点"的疏密与位置</b>。
          关掉"轨迹线"、只留"保留点"，即可看到每个算法在各路段留下了多少点；停留处本文保留成对的起止锚点（圆点旁数字＝停留时长，时:分:秒），
          而几何基线会把整簇重合点删剩一个。放大到有拐弯的路段观察更明显。
        </div>
      </template>
      <el-empty v-else description="在上方“单运单下钻”输入运单 ID 并点“查询”，即可查看该运单下各算法保留轨迹的叠加对比" :image-size="70" />
    </el-card>

    <el-card shadow="never" class="mt8" header="各算法指标统计（同一运单口径 mean / median / min / max）">
      <el-table :data="statRows" border size="small">
        <el-table-column prop="alg" label="算法" width="140" />
        <el-table-column prop="mean" label="均值" align="right" />
        <el-table-column prop="median" label="中位数" align="right" />
        <el-table-column prop="min" label="最小" align="right" />
        <el-table-column prop="max" label="最大" align="right" />
        <el-table-column label="单位" width="70"><template>{{ statUnit }}</template></el-table-column>
        <el-table-column prop="waybills" label="运单数" width="90" align="right" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { getAlgorithmCompare, getAlgorithmTracks, getEvalWaybill } from '../api'
import { ALG_NAMES, COLOR, colorOf, createChart, disposeChart, setOption } from '../charts/theme'
import { fmtNum, fmtPct, METRIC_META } from '../utils/format'
import { ElMessage } from 'element-plus'
import TrajectoryMap from '../components/TrajectoryMap.vue'

const metricKey = ref('crE2eGlobal')
const chartType = ref('bar')
const algorithms = ref<any[]>([])
const checkedAlgs = ref<string[]>(['PROPOSED', 'DP', 'DPS', 'TD-TR', 'Trajic'])
const elChart = ref<HTMLDivElement>()
let chart: any = null

const allAlgs = computed(() => algorithms.value.map((a: any) => a.algorithmCode))
function algName(c: string) { return ALG_NAMES[c] || c }
// 限制为可在"同一运单口径"给出 mean/median/min/max 的指标（统计行来自 waybill 明细）
const metricOptions = [
  { key: 'crE2eGlobal', label: METRIC_META.crE2eGlobal.label, unit: METRIC_META.crE2eGlobal.unit },
  { key: 'crTotalAvg', label: METRIC_META.crTotalAvg.label, unit: METRIC_META.crTotalAvg.unit },
  { key: 'crLossyAvg', label: METRIC_META.crLossyAvg.label, unit: METRIC_META.crLossyAvg.unit },
  { key: 'pedAvg', label: METRIC_META.pedAvg.label, unit: METRIC_META.pedAvg.unit },
  { key: 'sedAvg', label: METRIC_META.sedAvg.label, unit: METRIC_META.sedAvg.unit },
  { key: 'srAvg', label: METRIC_META.srAvg.label, unit: METRIC_META.srAvg.unit },
  { key: 'queryTimeMsAvg', label: METRIC_META.queryTimeMsAvg.label, unit: METRIC_META.queryTimeMsAvg.unit }
]
const statKeyMap: any = { crE2eGlobal: 'crE2e', crTotalAvg: 'crTotal', crLossyAvg: 'crLossy', pedAvg: 'pedAvg', sedAvg: 'sedAvg', srAvg: 'sr', queryTimeMsAvg: 'queryMs' }
const statUnit = computed(() => { const u = METRIC_META[metricKey.value]?.unit; return u === '%' ? '%' : u === 'x' ? 'x' : u === 'm' ? 'm' : u === 'ms' ? 'ms' : '' })

function num(v: any, d = 2) { return v == null ? '-' : fmtNum(v, d) }
function pct(v: any) { return fmtPct(v, 1) }

const statRows = computed(() => algorithms.value.map((a: any) => {
  const st = a.stats?.[statKeyMap[metricKey.value]]
  const isPct = METRIC_META[metricKey.value]?.unit === '%'
  const f = (x: number) => (x == null ? '-' : isPct ? pct(x) : fmtNum(x, isPct ? 1 : 3))
  return { alg: algName(a.algorithmCode), mean: f(st?.mean), median: f(st?.median), min: f(st?.min), max: f(st?.max), waybills: a.waybillCount }
}))

function rawOf(alg: any, key: string) { return alg[key] == null ? 0 : alg[key] }

async function load() {
  try {
    const d = await getAlgorithmCompare()
    algorithms.value = d.algorithmComparison || []
  } catch (e: any) { ElMessage.error(e?.message || '加载失败') }
}
async function render() {
  if (!elChart.value || !algorithms.value.length) return
  const meta = METRIC_META[metricKey.value] || { unit: '' }
  const sel = checkedAlgs.value.length ? checkedAlgs.value : allAlgs.value
  const rows = algorithms.value.filter((a: any) => sel.includes(a.algorithmCode))
  const names = rows.map((a: any) => algName(a.algorithmCode))
  const isPct = meta.unit === '%'
  const fmt = (v: number) => (isPct ? v * 100 : v)

  let option: any
  if (chartType.value === 'radar') {
    // 雷达图：对"越高越好"与"越低越好"指标归一化（0~100），避免量纲混用
    const dims = [
      { name: '端到端CR', field: 'crE2eGlobal', higher: 1 },
      { name: 'CR_lossy', field: 'crLossyAvg', higher: 1 },
      { name: 'SR', field: 'srAvg', higher: 1 },
      { name: 'PED(越低优)', field: 'pedAvg', higher: -1 },
      { name: 'SED(越低优)', field: 'sedAvg', higher: -1 },
      { name: '存储(越低优)', field: 'storageBytes', higher: -1 }
    ]
    const norm = (vals: number[], higher: number) => {
      const lo = Math.min(...vals), hi = Math.max(...vals)
      return vals.map((v) => (hi === lo ? 50 : ((higher * (v - lo)) / (hi - lo) + (higher < 0 ? 1 : 0)) * (higher < 0 ? 100 : 100)))
    }
    const series = rows.map((a: any) => ({
      name: algName(a.algorithmCode),
      value: dims.map((d) => {
        const list = algorithms.value.map((x: any) => rawOf(x, d.field))
        const nn = norm(list, d.higher)
        return +nn[algorithms.value.indexOf(a)].toFixed(1)
      })
    }))
    option = {
      animation: true,
      color: rows.map((a: any) => (a.algorithmCode === 'PROPOSED' ? COLOR.PROPOSED : colorOf(a.algorithmCode))),
      tooltip: {},
      legend: { bottom: 0 },
      radar: { indicator: dims.map((d) => ({ name: d.name, max: 100 })), radius: '62%' },
      series: [{ type: 'radar', data: series, symbolSize: 4, areaStyle: { opacity: 0.12 } }]
    }
  } else {
    const values = rows.map((a: any) => fmt(rawOf(a, metricKey.value)))
    option = {
      animation: true,
      tooltip: { trigger: 'axis', valueFormatter: (v: any) => (isPct ? Number(v).toFixed(1) + '%' : fmtNum(v, 3)) },
      grid: { left: 20, right: 20, top: 30, bottom: 20, containLabel: true },
      xAxis: { type: 'category', data: names, axisLabel: { color: '#334155' } },
      yAxis: { type: 'value', name: meta.unit, axisLabel: { color: '#334155', formatter: (v: number) => (isPct ? v + '%' : String(v)) } },
      series: [{
        name: meta.label,
        type: chartType.value,
        data: values,
        smooth: true,
        symbolSize: 8,
        lineStyle: { width: 3 },
        itemStyle: { color: (p: any) => (rows[p.dataIndex]?.algorithmCode === 'PROPOSED' ? COLOR.PROPOSED : colorOf(rows[p.dataIndex]?.algorithmCode)) },
        label: { show: false }
      }]
    }
  }
  if (!chart) chart = createChart(elChart.value)
  setOption(chart, option)
}

// ---- 单运单下钻 ----
const drillId = ref<number>()
const drillRows = ref<any[]>([])
const drilling = ref(false)
const mapRef = ref<any>()
/** 各算法保留轨迹（地图下钻数据） */
const trackData = ref<any>(null)

/**
 * 地图**独立**控制，与上方驱动图表的"算法"勾选互不干扰：
 * 各算法几何高度重合，全画会糊成一片，故默认只显示"本文 vs DP"这一组最有对比意义的组合。
 */
const mapAlgs = ref<string[]>(['PROPOSED', 'DP'])
/** 图层开关：轨迹线 / 保留点 / 停留锚点（本文） */
const mapLayers = ref<string[]>(['line', 'point', 'stop'])
/** 本文识别的停留单元（只有本文有语义信息） */
const ourStops = computed<any[]>(() =>
  (trackData.value?.algorithms || []).find((a: any) => a.algorithmCode === 'PROPOSED')?.stops || [])
const ourStopCount = computed(() => ourStops.value.length)

/**
 * 把各算法的保留轨迹画到同一张地图上。
 *
 * <p>画序很关键：先画基线（细、半透明、z 低），**本文最后画**（粗、不透明、z 高），
 * 否则粗线会被后画的细线盖住；本文的停留锚点再以圆点 + HH:MM:SS 时长牌压在最上层。
 *
 * <p>"保留点"图层把每个算法**最终留下的点**画成对应颜色的小圆点——各算法线条重合、
 * 但点集不同（本文在停留处保留成对锚点，几何基线把整簇重合点删剩一个），这层能直接看出差异。
 */
function renderTrackMap() {
  const d = trackData.value
  if (!mapRef.value || !d) return
  const layers = mapLayers.value
  const ordered = [...(d.algorithms || [])].sort(
    (a: any, b: any) => (a.algorithmCode === 'PROPOSED' ? 1 : 0) - (b.algorithmCode === 'PROPOSED' ? 1 : 0))
  const lines: any[] = []
  const stops: any[] = []
  const dots: any[] = []
  ordered.forEach((a: any) => {
    if (!mapAlgs.value.includes(a.algorithmCode)) return
    const isOurs = a.algorithmCode === 'PROPOSED'
    const color = isOurs ? COLOR.PROPOSED : colorOf(a.algorithmCode)
    const pts = a.points || []
    if (!pts.length) return
    if (layers.includes('line')) {
      lines.push({
        key: a.algorithmCode, label: a.algorithmName, color,
        points: pts, width: isOurs ? 5 : 2.5, opacity: isOurs ? 1 : 0.85,
        z: isOurs ? 60 : 40, arrows: false, ends: isOurs
      })
    }
    if (layers.includes('point')) {
      dots.push({
        key: a.algorithmCode, label: a.algorithmName + ' 保留点', color,
        radius: isOurs ? 3.5 : 2.5, opacity: 0.95,
        points: pts.map((p: any) => ({ lon: p.lon, lat: p.lat, time: p.time }))
      })
    }
    if (layers.includes('stop') && isOurs) {
      (a.stops || []).forEach((s: any) => stops.push({
        lat: s.startLat, lon: s.startLon, label: s.label,
        startTime: s.startTime, endTime: s.endTime, durationS: s.durationS,
        retainedBoth: s.retainedBoth, showDuration: true
      }))
    }
  })
  mapRef.value.draw({ lines, stops, dots, fit: true })
}

async function loadDrill() {
  if (!drillId.value) { ElMessage.warning('请输入运单 ID'); return }
  drilling.value = true
  try {
    drillRows.value = await getEvalWaybill(drillId.value)
    trackData.value = await getAlgorithmTracks(drillId.value)
    await nextTick()
    renderTrackMap()
  } catch (e: any) { ElMessage.error(e?.message || '未找到该运单实验结果') }
  finally { drilling.value = false }
}

watch([metricKey, chartType, checkedAlgs], render)
// 地图用独立控制（算法显隐 + 图层），不跟随上方图表勾选
watch([mapAlgs, mapLayers], () => renderTrackMap(), { deep: true })
onMounted(async () => { await load(); await render() })
onBeforeUnmount(() => disposeChart(chart))
</script>
<style scoped>
.muted { color: #94a3b8; font-size: 12px; }
.algbar { display: flex; flex-wrap: wrap; gap: 6px 14px; align-items: center; margin-bottom: 8px; font-size: 12px; color: #475569; }
.algchip { display: inline-flex; align-items: center; gap: 4px; white-space: nowrap; }
.algchip i { width: 10px; height: 10px; border-radius: 2px; display: inline-block; }
.algchip b { color: #0d5ea7; font-weight: 600; margin-left: 2px; }
.algchip.off { opacity: 0.45; }
.ctl { display: flex; flex-wrap: wrap; gap: 8px 12px; align-items: center; margin-bottom: 8px; }
.ctl-label { font-size: 12px; color: #64748b; }
</style>
