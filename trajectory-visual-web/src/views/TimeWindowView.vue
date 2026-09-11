<template>
  <div>
    <el-card shadow="never" class="mb8">
      <el-form inline>
        <el-form-item label="运单选择">
          <el-select v-model="waybillId" filterable placeholder="选择运单（需已压缩）" style="width: 280px" @change="onWaybillChange">
            <el-option v-for="w in waybills" :key="w.waybillId" :label="w.waybillNo + ' / ' + (w.plateNo || '无牌') + ' / ' + w.pointCount + '点'"
                       :value="w.waybillId" />
          </el-select>
        </el-form-item>
        <el-form-item label="时间窗口">
          <el-date-picker v-model="range" type="datetimerange" value-format="YYYY-MM-DD HH:mm:ss"
                          start-placeholder="窗口开始" end-placeholder="窗口结束" style="width: 360px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="search">部分解压检索</el-button>
          <el-button :disabled="!rangeInfo.start" @click="quickWindow">取轨迹中段 1 小时</el-button>
        </el-form-item>
      </el-form>

      <!-- 顶部提示：这条运单的时间范围是什么、一片多少秒（分片按固定时间窗切分） -->
      <div class="hintbar">
        <template v-if="rangeInfo.start">
          <span class="hint-item"><b>轨迹时间范围</b>：{{ fmtTime(rangeInfo.start) }} ~ {{ fmtTime(rangeInfo.end) }}</span>
          <span class="hint-item"><b>分片时长</b>：每 {{ rangeInfo.blockWindowS }} 秒（{{ minutes(rangeInfo.blockWindowS) }} 分钟）一片，
            共 <b>{{ rangeInfo.chunkCount ?? '—' }}</b> 片</span>
          <span class="hint-item"><b>坐标系</b>：GCJ-02（与高德底图一致）</span>
          <span class="hint-item"><b>检索口径</b>：窗口与分片时间跨度相交即命中该片，只读命中分片的压缩负载</span>
        </template>
        <span v-else class="hint-item muted">选择运单后，这里会显示该运单的轨迹时间范围与分片时长（未压缩运单按默认分片时长展示，压缩后才生成实际分片）。</span>
      </div>

      <el-alert type="warning" :closable="false" title="仅支持对已压缩运单做部分解压检索；若数据状态为未压缩，请先在工作台对该运单执行压缩。"
                style="margin-top:6px" />
    </el-card>

    <el-row :gutter="10">
      <el-col :span="13">
        <el-card shadow="never" header="窗口内轨迹（部分解压返回，仅读取命中分片）" style="height: 520px">
          <TrajectoryMap ref="mapRef" height="425px" />
        </el-card>
      </el-col>
      <el-col :span="11">
        <el-card shadow="never" header="部分解压 vs 全量解压" style="height: 520px; overflow:auto">
          <template v-if="result">
            <el-alert v-if="result.dataStatus === 'NOT_COMPRESSED'" type="warning" :closable="false"
                      title="该运单尚未压缩，无 Mongo 分片。" />
            <div v-else>
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="运单轨迹时间范围">
                  {{ result.trackStartTime || '—' }} ~ {{ result.trackEndTime || '—' }}
                </el-descriptions-item>
                <el-descriptions-item label="分片时长">
                  {{ result.blockWindowS ? result.blockWindowS + ' 秒 / 片' : '—' }}
                </el-descriptions-item>
                <el-descriptions-item label="命中分片 / 总分片">
                  <b>{{ result.hitChunks }}</b> / {{ result.totalChunks }}
                </el-descriptions-item>
                <el-descriptions-item label="分块命中率">{{ pct(result.blockHitRatio) }}</el-descriptions-item>
                <el-descriptions-item label="读取字节 / 总字节">
                  <b>{{ result.bytesRead }}</b> / {{ result.totalBytes }}
                </el-descriptions-item>
                <el-descriptions-item label="部分读取字节比例">{{ pct(result.readRatio) }}</el-descriptions-item>
                <el-descriptions-item label="部分解压耗时">{{ result.partialMs }} ms</el-descriptions-item>
                <el-descriptions-item label="全量解压耗时">{{ result.fullMs }} ms</el-descriptions-item>
                <el-descriptions-item label="窗口内点数（部分）">{{ result.partialPoints }}</el-descriptions-item>
                <el-descriptions-item label="窗口内点数（全量过滤）">{{ result.fullPoints }}</el-descriptions-item>
              </el-descriptions>
              <el-tag :type="result.consistent ? 'success' : 'danger'" size="large" style="margin-top:12px">
                {{ result.consistent ? '✓ 部分解压与全量过滤结果一致' : '✗ 部分解压与全量过滤不一致！' }}
              </el-tag>
              <div class="perf" v-if="result.totalBytes">
                <div class="perf-title">字节读取对比</div>
                <el-progress :percentage="Math.round(result.readRatio * 100)" :stroke-width="18"
                             :format="() => '部分解压读取 ' + pct(result.readRatio)" status="success" />
                <el-progress :percentage="100" :stroke-width="18" :format="() => '全量解压读取 100%'" status="warning" style="margin-top:10px" />
              </div>
            </div>
          </template>
          <el-empty v-else description="选择运单与时间窗后点击“部分解压检索”" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import TrajectoryMap from '../components/TrajectoryMap.vue'
import { getChunks, getTrackConfig, getWaybillPage, searchByTime } from '../api'
import { MAP_COLOR } from '../charts/theme'
import { fmtTime } from '../utils/format'

const waybills = ref<any[]>([])
const waybillId = ref<any>(null)
const range = ref<any>(null)
const loading = ref(false)
const result = ref<any>(null)
const mapRef = ref<any>()
const config = ref<any>({})
/** 顶部提示：该运单的轨迹时间范围 / 分片时长 / 分片数 */
const rangeInfo = ref<any>({ start: '', end: '', blockWindowS: null, chunkCount: null, compressed: false })

function pct(v?: number) { return v === undefined || v === null ? '-' : (v * 100).toFixed(1) + '%' }
function minutes(s?: number) { return s ? (s / 60).toFixed(s % 60 === 0 ? 0 : 1) : '-' }

async function loadWaybills() {
  const data = await getWaybillPage({ current: 1, size: 200 })
  waybills.value = data.records
}
function currentRow() {
  return waybills.value.find((w) => w.waybillId === waybillId.value)
}

/** 选运单 → 拉分片清单，得出真实时间范围与分片时长（压缩过才拿得到） */
async function onWaybillChange() {
  const row = currentRow()
  result.value = null
  mapRef.value?.clear()
  const fallback = {
    start: row?.gtmStart || '', end: row?.gtmEnd || '',
    blockWindowS: config.value.blockWindowS ?? null, chunkCount: null, compressed: false
  }
  rangeInfo.value = fallback
  if (!waybillId.value) return
  try {
    const chunks: any[] = await getChunks(waybillId.value)
    if (!chunks || !chunks.length) return
    const sorted = [...chunks].sort((a, b) => a.chunkIndex - b.chunkIndex)
    const first = sorted[0]
    const last = sorted[sorted.length - 1]
    rangeInfo.value = {
      start: first.startTime,
      end: last.endTime,
      // 分片时长取压缩时所用的分块窗口（分片文档落库值），而不是相邻片起点之差——
      // 块起点是"块内首点时刻"，相邻片间隔会在窗口长度附近浮动，不等于窗口长度。
      blockWindowS: first.blockWindowS ?? config.value.blockWindowS ?? null,
      chunkCount: first.chunkCountTotal ?? sorted.length,
      compressed: true
    }
  } catch (e) {
    /* 未压缩/接口异常：保留 fallback 提示 */
  }
}

function quickWindow() {
  const { start, end } = rangeInfo.value
  if (!start || !end) { ElMessage.warning('请先选择已压缩运单'); return }
  // 取轨迹中段 1 小时：数据是历史轨迹，“最近 1 小时”永远落不到轨迹范围内
  const mid = dayjs(start).add(dayjs(end).diff(dayjs(start), 'second') / 2, 'second')
  range.value = [mid.subtract(30, 'minute').format('YYYY-MM-DD HH:mm:ss'), mid.add(30, 'minute').format('YYYY-MM-DD HH:mm:ss')]
}

async function search() {
  if (!waybillId.value) { ElMessage.warning('请选择运单'); return }
  if (!range.value || range.value.length !== 2) { ElMessage.warning('请选择时间窗口'); return }
  loading.value = true
  try {
    result.value = await searchByTime({ waybillId: waybillId.value, startTime: range.value[0], endTime: range.value[1] })
    // 检索结果里也带分片信息，反过来补全顶部提示（未压缩运单也能看到分片时长口径）
    if (result.value.blockWindowS) rangeInfo.value.blockWindowS = result.value.blockWindowS
    if (result.value.points && result.value.points.length) {
      mapRef.value?.draw({
        lines: [{
          key: 'compressed', label: '窗口轨迹(本文压缩)', color: MAP_COLOR.compressed,
          points: result.value.points, width: 3.5, opacity: 1, halo: true, arrows: true, ends: true
        }],
        fit: true
      })
    } else {
      mapRef.value?.clear()
    }
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try { config.value = await getTrackConfig() } catch (e) { config.value = {} }
  await loadWaybills()
})
</script>

<style scoped>
.hintbar {
  display: flex; flex-wrap: wrap; gap: 4px 22px; align-items: center;
  padding: 6px 10px; margin-top: 4px; font-size: 12.5px; color: #475569;
  background: #f5f9fd; border: 1px solid #e2ecf7; border-radius: 4px; line-height: 1.7;
}
.hint-item b { color: #0d5ea7; }
.hint-item.muted { color: #94a3b8; }
.perf { margin-top: 16px; }
.perf-title { font-weight: 600; margin-bottom: 8px; color: #334155; }
</style>
