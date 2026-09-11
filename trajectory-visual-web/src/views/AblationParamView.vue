<template>
  <div>
    <el-card shadow="never" class="mb8" header="消融实验（A0 完整方法 / A-TIGHT 收紧容差 / A2 去锚点 / A3 去无损 / A4 去分块索引）">
      <el-row :gutter="10">
        <el-col :span="14">
          <div ref="elAbA" style="height: 300px"></div>
          <div class="note">左轴 CR_total（×），右轴 SR / 语义单元完整率（%）。A2 去锚点后语义指标骤降；A4 整流编码压缩最高但失去按块随机访问（见下表查询耗时）。</div>
        </el-col>
        <el-col :span="10">
          <el-table :data="ablationRows" size="small" border>
            <el-table-column prop="ablationCode" label="变体" width="90" />
            <el-table-column label="CR_total"><template #default="{ row }">{{ num(row.crTotalAvg, 2) }}</template></el-table-column>
            <el-table-column label="SR"><template #default="{ row }">{{ row.srAvg == null ? '-' : pct(row.srAvg) }}</template></el-table-column>
            <el-table-column label="单元完整率"><template #default="{ row }">{{ row.semanticUnitCompleteRate == null ? '-' : pct(row.semanticUnitCompleteRate) }}</template></el-table-column>
            <el-table-column label="查询耗时"><template #default="{ row }">{{ row.queryTimeMsAvg == null ? '-' : num(row.queryTimeMsAvg, 3) + 'ms' }}</template></el-table-column>
          </el-table>
          <div class="note" v-if="ablRemarks.length">备注：{{ ablRemarks.join('；') }}</div>
        </el-col>
      </el-row>
    </el-card>

    <el-row :gutter="10">
      <el-col :span="12">
        <el-card shadow="never" header="DP 容差扫描：各算法语义点保留率 SR（%）随容差变化" class="mb8">
          <div ref="elDp" style="height: 300px"></div>
          <div class="note">本文锚点强制保留，SR 恒 100%；基线随容差增大 SR 下降（删掉更多停留段起终锚点）。</div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never" header="chunk 时长扫描：压缩率 vs 部分读取比例权衡" class="mb8">
          <div ref="elBlk" style="height: 300px"></div>
          <div class="note">块长越大 CR_total 越高但部分读取字节比例越大（分块随机访问粒度变粗）；当前系统默认分片时长 <b>{{ defBlockLabel }}</b>。</div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getAblation, getParamSensitivity, getTrackConfig } from '../api'
import { ALG_NAMES, COLOR, colorOf, createChart, disposeChart, setOption } from '../charts/theme'
import { fmtNum } from '../utils/format'

const ablationRows = ref<any[]>([])
const dpRows = ref<any[]>([])
const blkRows = ref<any[]>([])
const ablRemarks = ref<string[]>([])
/** 后端配置的默认分片时长标签（如 "1h"）——不写死数字，跟随后端口径 */
const defBlockLabel = ref('—')
const elAbA = ref<HTMLDivElement>(), elDp = ref<HTMLDivElement>(), elBlk = ref<HTMLDivElement>()
let charts: any = {}

function num(v: any, d = 2) { return v == null ? '-' : fmtNum(v, d) }
function pct(v: any) { return v == null ? '-' : (v * 100).toFixed(1) + '%' }
/** 秒 → 块长标签：3600→"1h"、600→"10min" */
function blockLabel(sec?: number | null) {
  if (!sec) return '—'
  return sec % 3600 === 0 ? (sec / 3600) + 'h' : Math.round(sec / 60) + 'min'
}
function ensure(key: string, el: any) {
  if (!charts[key] && el.value) charts[key] = createChart(el.value)
  return charts[key]
}

async function load() {
  try {
    ablationRows.value = await getAblation()
    ablRemarks.value = ablationRows.value.map((a: any) => a.remark).filter(Boolean)
    const ps: any = await getParamSensitivity()
    dpRows.value = ps?.dpTolerance || []
    blkRows.value = ps?.blockWindow || []
    // 默认分片时长从后端配置取（与 Mongo 分片口径同源），不在前端写死
    try { defBlockLabel.value = blockLabel((await getTrackConfig())?.blockWindowS) } catch (e) { /* 拿不到就显示 — */ }
    draw()
  } catch (e: any) {
    ElMessage.error(e?.message || '数据加载失败（请先运行消融/参数敏感性实验）')
  }
}

function draw() {
  // 消融：CR_total 柱 + SR% 线
  if (ablationRows.value.length && elAbA.value) {
    const rows = ablationRows.value
    const names = rows.map((a: any) => a.ablationCode)
    const cr = rows.map((a: any) => a.crTotalAvg ?? 0)
    const sr = rows.map((a: any) => (a.srAvg ?? 0) * 100)
    const ui = rows.map((a: any) => (a.semanticUnitCompleteRate ?? 0) * 100)
    setOption(ensure('ab', elAbA), {
      color: [COLOR.PROPOSED, COLOR.semantic, '#b0c975'],
      tooltip: { trigger: 'axis' },
      legend: { bottom: 0, data: ['CR_total', 'SR', '语义单元完整率'] },
      grid: { left: 16, right: 44, top: 20, bottom: 30, containLabel: true },
      xAxis: { type: 'category', data: names, axisLabel: { color: '#334155' } },
      yAxis: [
        { type: 'value', name: 'CR_total(x)', axisLabel: { color: '#334155' } },
        { type: 'value', name: '%', axisLabel: { color: '#334155', formatter: '{value}%' } }
      ],
      series: [
        { name: 'CR_total', type: 'bar', data: cr.map((v) => +v.toFixed(1)), barWidth: 34, itemStyle: { color: COLOR.PROPOSED }, label: { show: true, position: 'top', formatter: (p: any) => p.value } },
        { name: 'SR', type: 'line', yAxisIndex: 1, data: sr.map((v) => +v.toFixed(1)), symbolSize: 7 },
        { name: '语义单元完整率', type: 'line', yAxisIndex: 1, data: ui.map((v) => +v.toFixed(1)), symbolSize: 7 }
      ]
    })
  }
  // DP 容差扫描 SR
  if (dpRows.value.length && elDp.value) {
    const tols = [...new Set(dpRows.value.map((r: any) => Number(r.paramValue)))].sort((a, b) => a - b)
    const codes = [...new Set(dpRows.value.map((r: any) => r.algorithmCode))]
    const names = codes.map((c) => ALG_NAMES[c] || c)
    const series = codes.map((c: any) => ({
      name: ALG_NAMES[c] || c,
      type: 'line',
      smooth: true,
      symbolSize: 6,
      lineStyle: { width: c === 'PROPOSED' ? 3.5 : 2 },
      itemStyle: { color: c === 'PROPOSED' ? COLOR.PROPOSED : colorOf(c) },
      data: tols.map((t) => {
        const row = dpRows.value.find((r: any) => r.algorithmCode === c && Number(r.paramValue) === t)
        return row?.srAvg == null ? null : +(row.srAvg * 100).toFixed(1)
      })
    }))
    setOption(ensure('dp', elDp), {
      tooltip: { trigger: 'axis' },
      legend: { bottom: 0, data: names },
      grid: { left: 16, right: 24, top: 20, bottom: 30, containLabel: true },
      xAxis: { type: 'category', data: tols.map((t) => t + 'm'), axisLabel: { color: '#334155' } },
      yAxis: { type: 'value', name: 'SR(%)', axisLabel: { color: '#334155', formatter: '{value}%' } },
      series
    })
  }
  // chunk 时长扫描
  if (blkRows.value.length && elBlk.value) {
    const rows = blkRows.value
    const x = rows.map((r: any) => (Number(r.paramValue) / 60) + 'min')
    const cr = rows.map((r: any) => r.crTotalAvg ?? 0)
    const rr = rows.map((r: any) => (r.partialReadRatioAvg ?? 0) * 100)
    setOption(ensure('blk', elBlk), {
      color: [COLOR.PROPOSED, COLOR.error],
      tooltip: { trigger: 'axis' },
      legend: { bottom: 0, data: ['CR_total', '部分读取字节比例'] },
      grid: { left: 16, right: 44, top: 20, bottom: 30, containLabel: true },
      xAxis: { type: 'category', data: x, axisLabel: { color: '#334155' } },
      yAxis: [
        { type: 'value', name: 'CR_total(x)', axisLabel: { color: '#334155' } },
        { type: 'value', name: '%', axisLabel: { color: '#334155', formatter: '{value}%' } }
      ],
      series: [
        { name: 'CR_total', type: 'line', data: cr.map((v) => +Number(v).toFixed(1)), smooth: true, symbolSize: 7, lineStyle: { width: 3, color: COLOR.PROPOSED }, itemStyle: { color: COLOR.PROPOSED } },
        { name: '部分读取字节比例', type: 'line', yAxisIndex: 1, data: rr.map((v) => +v.toFixed(1)), smooth: true, symbolSize: 7, lineStyle: { color: COLOR.error }, itemStyle: { color: COLOR.error } }
      ]
    })
  }
}

function resize() { Object.values(charts).forEach((c: any) => c && c.resize()) }
window.addEventListener('resize', resize)

onMounted(async () => { await load() })
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  Object.values(charts).forEach((c: any) => disposeChart(c))
  charts = {}
})
</script>

<style scoped>
.note { font-size: 11px; color: #98a5b3; margin-top: 4px; }
</style>
