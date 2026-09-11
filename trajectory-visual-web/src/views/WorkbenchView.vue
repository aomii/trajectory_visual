<template>
  <div>
    <!-- 筛选区 -->
    <el-card shadow="never" class="mb8">
      <el-form inline>
        <el-form-item label="运单号">
          <el-input v-model="query.waybillNo" clearable placeholder="模糊" style="width: 180px" @keyup.enter="load" />
        </el-form-item>
        <el-form-item label="车牌号">
          <el-input v-model="query.plateNo" clearable placeholder="如 云G78398" style="width: 150px" @keyup.enter="load" />
        </el-form-item>
        <el-form-item label="车辆ID">
          <el-input-number v-model="query.vehicleId" :controls="false" placeholder="可空" style="width: 130px" />
        </el-form-item>
        <el-form-item label="时间范围">
          <el-date-picker v-model="timeRange" type="datetimerange" value-format="YYYY-MM-DD HH:mm:ss"
                          start-placeholder="开始" end-placeholder="结束" style="width: 340px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
      <el-alert v-if="notice" type="info" :closable="false" :title="notice" class="mt8" />
    </el-card>

    <el-row :gutter="10">
      <el-col :span="9">
        <el-card shadow="never" header="运单列表" style="height: 570px; overflow:auto">
          <div class="mb8">
            <el-button size="small" @click="openImport">导入运单源数据</el-button>
            <el-button size="small" type="danger" plain @click="compressAllClick">全量轨迹压缩</el-button>
          </div>
          <el-table :data="rows" highlight-current-row size="small" @row-click="onRowClick" :max-height="440">
            <el-table-column prop="waybillNo" label="运单号" width="118" show-overflow-tooltip />
            <el-table-column prop="plateNo" label="车牌" width="84" />
            <el-table-column prop="pointCount" label="点数" width="54" align="right" />
            <el-table-column label="收发货" min-width="110" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.sendAddrName || row.receiveAddrName">
                  {{ row.sendAddrName || '?' }} → {{ row.receiveAddrName || '?' }}
                </span>
                <span v-else class="muted">未回填</span>
              </template>
            </el-table-column>
            <el-table-column prop="gtmStart" label="开始" width="140">
              <template #default="{ row }">{{ fmtTime(row.gtmStart) }}</template>
            </el-table-column>
            <el-table-column prop="dataStatus" label="状态" width="62">
              <template #default="{ row }">
                <el-tag :type="row.dataStatus === 'COMPRESSED' ? 'success' : 'info'" size="small">
                  {{ row.dataStatus === 'COMPRESSED' ? '已压缩' : '未压缩' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination small layout="prev, pager, next, total" :total="total" :page-size="query.size"
                         :current-page="Number(query.current)" @current-change="onPage" style="margin-top:8px" />
        </el-card>
      </el-col>

      <el-col :span="15">
        <el-card shadow="never" header="压缩前后轨迹对比" style="height: 570px">
          <div class="mb8">
            <el-button size="small" type="primary" @click="loadTrack">查看轨迹</el-button>
            <el-button size="small" @click="compressCur">压缩当前运单</el-button>
            <el-button size="small" @click="clearMap">清空地图</el-button>
            <el-checkbox v-model="showOriginal" label="原始(蓝)" border size="small" style="margin-left:6px" @change="renderMap" />
            <el-checkbox v-model="showCompressed" label="压缩(绿)" border size="small" @change="renderMap" />
            <el-checkbox v-model="showDeleted" :label="deletedLabel" border size="small" @change="renderMap" v-if="deletedCount" />
            <el-checkbox v-model="showStops" label="停留锚点" border size="small" @change="renderMap" />
            <el-checkbox v-model="showPoi" label="收发地" border size="small" @change="renderMap" />
            <el-tag v-if="currentWaybill" type="info" effect="plain" style="margin-left:8px">
              {{ currentWaybill.waybillNo }}
            </el-tag>
          </div>
          <div v-if="dotsNote" class="muted" style="margin:-4px 0 6px">{{ dotsNote }}</div>
          <TrajectoryMap ref="mapRef" height="455px" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 指标卡：数字来自后端压缩指标快照（分片落库），刷新/切运单都不丢 -->
    <el-card shadow="never" class="mt8">
      <template #header>
        <span>压缩指标</span>
        <span class="muted" style="margin-left:8px;font-weight:400">口径：有损层减点数、无损层减每点字节，两者相乘为总压缩率</span>
      </template>
      <el-alert v-if="metricsHint" type="warning" :closable="false" :title="metricsHint" class="mb8" />
      <div class="stat-grid">
        <div v-for="s in metricItems" :key="s.label" class="stat-cell" :class="{ dim: s.value === null }">
          <div class="stat-label">{{ s.label }}</div>
          <div class="stat-value">
            <span v-if="s.value !== null">{{ s.value }}</span>
            <span v-else class="muted">—</span>
            <small v-if="s.unit && s.value !== null">{{ s.unit }}</small>
          </div>
          <div v-if="s.hint" class="stat-hint">{{ s.hint }}</div>
        </div>
      </div>
    </el-card>

    <!-- 运单业务元数据（来自业务库 waybill 表，导入时回填） -->
    <el-card shadow="never" class="mt8" header="运单收发货信息">
      <el-empty v-if="!currentWaybill" description="在左侧选择一条运单" :image-size="60" />
      <template v-else>
        <el-alert v-if="!currentWaybill.sendAddrName && !currentWaybill.receiveAddrName"
                  type="info" :closable="false" class="mb8"
                  title="该运单尚无收发货元数据：请执行一次“导入运单源数据”（导入时会按 waybillId 从业务库 waybill 表回填）。" />
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="运单号">{{ currentWaybill.waybillNo }}</el-descriptions-item>
          <el-descriptions-item label="车牌 / 货物">
            {{ currentWaybill.plateNo || '—' }}<span v-if="currentWaybill.materialName"> / {{ currentWaybill.materialName }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="发货地">
            <b>{{ currentWaybill.sendAddrName || '—' }}</b>
            <span class="muted">（{{ [currentWaybill.sendAddrArea, currentWaybill.sendAddrDetail].filter(Boolean).join(' ') || '—' }}）</span>
          </el-descriptions-item>
          <el-descriptions-item label="收货地">
            <b>{{ currentWaybill.receiveAddrName || '—' }}</b>
            <span class="muted">（{{ [currentWaybill.receiveAddrArea, currentWaybill.receiveAddrDetail].filter(Boolean).join(' ') || '—' }}）</span>
          </el-descriptions-item>
          <el-descriptions-item label="发货坐标">
            {{ fmtLatLon(currentWaybill.sendLat, currentWaybill.sendLon) }}
          </el-descriptions-item>
          <el-descriptions-item label="收货坐标">
            {{ fmtLatLon(currentWaybill.receiveLat, currentWaybill.receiveLon) }}
          </el-descriptions-item>
          <el-descriptions-item label="装货（发货）时间">{{ fmtTime(currentWaybill.loadTime) }}</el-descriptions-item>
          <el-descriptions-item label="卸货（收货）时间">{{ fmtTime(currentWaybill.unloadTime) }}</el-descriptions-item>
          <el-descriptions-item label="接单时间">{{ fmtTime(currentWaybill.orderTime) }}</el-descriptions-item>
          <el-descriptions-item label="轨迹时间范围">
            {{ fmtTime(currentWaybill.gtmStart) }} ~ {{ fmtTime(currentWaybill.gtmEnd) }}
          </el-descriptions-item>
        </el-descriptions>
        <div class="muted mt8">
          坐标口径：业务库收发地址与轨迹源文件同为 GCJ-02，地图直接叠加高德底图，不做坐标系转换。
        </div>
      </template>
    </el-card>

    <!-- 全量压缩进度 -->
    <el-dialog v-model="taskVisible" title="全量轨迹压缩任务" width="560px">
      <el-progress :percentage="taskPct" :status="taskStatus" />
      <p>处理 {{ task.processed }} / {{ task.totalFiles }}，成功 {{ task.successCount }}，失败 {{ task.failedCount }}
        {{ task.currentWaybillNo ? '，当前 ' + task.currentWaybillNo : '' }}</p>
      <p v-if="task.failures && task.failures.length" style="color:#d96a3c">失败文件：{{ task.failures.map((f: any) => f.file).join('、') }}</p>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import TrajectoryMap from '../components/TrajectoryMap.vue'
import { compressAll, compressOne, getCompressed, getCompressTask, getOriginal, getWaybillPage, importWaybills } from '../api'
import { MAP_COLOR } from '../charts/theme'
import { fmtTime } from '../utils/format'

const query = reactive<any>({ current: 1, size: 8, waybillNo: '', plateNo: '', vehicleId: null })
const timeRange = ref<any>(null)
const rows = ref<any[]>([])
const total = ref(0)
const currentWaybill = ref<any>(null)
const notice = ref('')

const mapRef = ref<any>()
const original: any = ref(null)
const compressed: any = ref(null)
const showOriginal = ref(true)
const showCompressed = ref(true)
const showDeleted = ref(true)
const showStops = ref(true)
const showPoi = ref(true)
/** 地图抽样提示（被删点过多时） */
const dotsNote = ref('')
/** 指标快照：优先压缩线 metrics，未压缩时退化用原始线 metrics（只有点数口径） */
const metrics = ref<any>(null)
/** 被删点数：优先取原始线的 deletedPointCount，回退用指标里的 清洗后-保留 */
const deletedCount = computed(() => {
  const fromLine = original.value?.deletedPointCount
  if (typeof fromLine === 'number') return fromLine
  const m = metrics.value
  if (m && m.cleanedPointCount != null && m.keptPointCount != null) return m.cleanedPointCount - m.keptPointCount
  return null
})
const deletedLabel = computed(() => (deletedCount.value ? `被删点(${deletedCount.value})` : '被删点'))

const taskVisible = ref(false)
const task = reactive<any>({ totalFiles: 0, processed: 0, successCount: 0, failedCount: 0, currentWaybillNo: '' })
let taskTimer: any = null

async function load() {
  const params: any = { current: query.current, size: query.size }
  if (query.waybillNo) params.waybillNo = query.waybillNo
  if (query.plateNo) params.plateNo = query.plateNo
  if (query.vehicleId) params.vehicleId = query.vehicleId
  if (timeRange.value && timeRange.value.length === 2) {
    params.startTime = timeRange.value[0]
    params.endTime = timeRange.value[1]
  }
  const data = await getWaybillPage(params)
  rows.value = data.records
  total.value = data.total
}
function reset() {
  Object.assign(query, { current: 1, size: 8, waybillNo: '', plateNo: '', vehicleId: null })
  timeRange.value = null
  load()
}
function onPage(p: number) { query.current = p; load() }
function onRowClick(row: any) {
  currentWaybill.value = row
  // 换运单后旧轨迹不再对应当前运单，先清掉，避免叠错；地图上只保留新运单的收发地标记
  original.value = null
  compressed.value = null
  metrics.value = null
  notice.value = '已切换运单：点“查看轨迹”加载该运单的原始/压缩轨迹与压缩指标。'
  mapRef.value?.clear()
  renderMap()
}

async function loadTrack() {
  if (!currentWaybill.value) { ElMessage.warning('请先在左侧选择一条运单'); return }
  const id = currentWaybill.value.waybillId
  try {
    original.value = await getOriginal(id)
    compressed.value = await getCompressed(id)
    if (compressed.value && !compressed.value.points) {
      notice.value = '该运单尚未压缩：压缩后轨迹为空，可点“压缩当前运单”后再查看叠加效果。'
    } else { notice.value = '' }
    metrics.value = (compressed.value && compressed.value.metrics) || (original.value && original.value.metrics) || null
    renderMap()
  } catch (e: any) {
    ElMessage.error(e?.message || '轨迹读取失败')
  }
}

function stopsOf(track: any) {
  return (track?.stays || []).map((s: any) => ({
    lat: s.startLat, lon: s.startLon, label: s.label,
    startTime: s.startTime, endTime: s.endTime, durationS: s.durationS, retainedBoth: s.retainedBoth
  }))
}

/** 有轨迹点的线 */
function hasPts(t: any) { return !!(t && t.points && t.points.length) }

/** 被有损压缩删除的点（原始线上 kept===false 的点） */
function deletedPointsOf(track: any) {
  return (track?.points || []).filter((p: any) => p.kept === false)
}
/** 地图上被删点最多画这么多（再多渲染会卡），超出按等间隔抽样 */
const MAX_DOTS = 3000

function renderMap() {
  if (!mapRef.value) return
  const lines: any[] = []
  const stops: any[] = []
  const marks: any[] = []
  const dots: any[] = []
  const showOrig = showOriginal.value && hasPts(original.value)
  const showComp = showCompressed.value && hasPts(compressed.value)

  if (showOrig) {
    lines.push({
      key: 'original', label: '原始轨迹(清洗后全量点)', color: MAP_COLOR.original, points: original.value.points,
      // 原始线画得比压缩线**宽**并压在下层 → 两者重合段呈"蓝边绿芯"，一眼能同时看到两条线；
      // 压缩线抄近道（DP 丢点）的地方，蓝色会绕着弯走、绿色走直线，"变化"直接看得见。
      width: 8, opacity: 0.85, z: 55,
      arrows: !showComp, ends: !showComp
    })
  }
  if (showComp) {
    lines.push({
      key: 'compressed', label: '本文压缩(保留点)', color: MAP_COLOR.compressed, points: compressed.value.points,
      width: 3.5, opacity: 1, z: 60, arrows: true, ends: true
    })
  }
  if (showStops.value && showComp) stops.push(...stopsOf(compressed.value))

  // 被有损压缩删除的点：橙色高亮散点（数据来自后端对原始线逐点的 kept 标记）
  dotsNote.value = ''
  if (showDeleted.value && showOrig) {
    const del = deletedPointsOf(original.value)
    if (del.length) {
      const picked = del.length > MAX_DOTS ? del.filter((_: any, i: number) => i % Math.ceil(del.length / MAX_DOTS) === 0) : del
      dots.push({
        key: 'deleted', label: '被删点', color: MAP_COLOR.deleted, radius: 4,
        points: picked.map((p: any) => ({ lon: p.lon, lat: p.lat, time: p.time }))
      })
      if (picked.length < del.length) {
        dotsNote.value = `被删点共 ${del.length} 个，地图按等间隔抽样显示 ${picked.length} 个（指标卡里的点数仍为全量口径）。`
      }
    }
  }

  // 收发地标记（坐标与轨迹同系，直接落图）
  const w = currentWaybill.value
  if (showPoi.value && w) {
    if (isNum(w.sendLat) && isNum(w.sendLon)) {
      marks.push({ lat: w.sendLat, lon: w.sendLon, label: w.sendAddrName || '', sub: w.sendAddrDetail, kind: 'send' })
    }
    if (isNum(w.receiveLat) && isNum(w.receiveLon)) {
      marks.push({ lat: w.receiveLat, lon: w.receiveLon, label: w.receiveAddrName || '', sub: w.receiveAddrDetail, kind: 'receive' })
    }
  }
  mapRef.value.draw({ lines, stops, marks, dots, fit: true })
}
function clearMap() { mapRef.value?.clear(); dotsNote.value = '' }

function isNum(v: any) { return v !== null && v !== undefined && !isNaN(Number(v)) && Number(v) !== 0 }
function fmtLatLon(lat: any, lon: any) {
  return isNum(lat) && isNum(lon) ? `${Number(lat).toFixed(6)}, ${Number(lon).toFixed(6)}` : '—'
}

// ---------------- 指标卡 ----------------
const n0 = (v: any, d = 0) =>
  v === null || v === undefined || v === '' ? null : Number(v).toFixed(d)
const n2 = (v: any) => n0(v, 2)
const pct = (v: any) => (v === null || v === undefined ? null : (Number(v) * 100).toFixed(2) + '%')
const mb = (v: any) =>
  v === null || v === undefined ? null : Number(v) >= 1048576
    ? (Number(v) / 1048576).toFixed(2) + ' MB'
    : Number(v).toLocaleString() + ' B'

const metricItems = computed<any[]>(() => {
  const m = metrics.value
  return [
    { label: '原始点数(文件)', value: n0(m?.rawPointCount), unit: '点' },
    { label: '清洗后点数', value: n0(m?.cleanedPointCount), unit: '点', hint: '压缩基准' },
    { label: '压缩后保留点数', value: n0(m?.keptPointCount), unit: '点' },
    { label: '有损层压缩率 CR_lossy', value: n2(m?.crLossy), unit: '×', hint: '清洗后点数 / 保留点数' },
    { label: '无损层压缩率 CR_lossless', value: n2(m?.crLossless), unit: '×', hint: '规范文本字节 / 压缩字节' },
    { label: '总压缩率 CR_total', value: n2(m?.crTotal), unit: '×', hint: 'CR_lossy × CR_lossless' },
    { label: '停留单元数', value: n0(m?.stopCount), unit: '个' },
    { label: '语义锚点数', value: n0(m?.anchorCount), unit: '个' },
    { label: '分片数(chunk)', value: n0(m?.chunkCount), unit: '片' },
    { label: '压缩前文本字节', value: mb(m?.naiveBytes) },
    { label: '压缩后存储字节', value: mb(m?.storageBytes) },
    { label: '位置误差 PED 均值', value: n2(m?.pedAvgM), unit: 'm' },
    { label: '位置误差 PED 最大', value: n2(m?.pedMaxM), unit: 'm' },
    { label: '同步欧氏距离 SED 均值', value: n2(m?.sedAvgM), unit: 'm' },
    { label: '语义点保留率 SR', value: pct(m?.sr) },
    { label: '语义单元完整率', value: pct(m?.unitIntegrity), hint: '起终锚点成对保留' },
    { label: '停留时长保真度', value: pct(m?.dwellFidelity) },
    { label: '编码耗时', value: n2(m?.encodeMs), unit: 'ms' },
    { label: '全量解压耗时', value: n2(m?.decodeMs), unit: 'ms' },
    { label: '移动段 DP 容差', value: n2(m?.moveToleranceM), unit: 'm' },
    { label: '量化位数 precision', value: n0(m?.precision), unit: '位' },
    { label: '分片时长', value: n0(m?.blockWindowS), unit: 's' }
  ]
})

const metricsHint = computed(() => {
  if (!currentWaybill.value) return '在左侧选择一条运单并点“查看轨迹”，即可看到该运单的压缩指标。'
  if (!metrics.value) return '尚未读取指标：点“查看轨迹”加载。未压缩运单只有点数口径，压缩后才有完整指标。'
  if (!hasPts(compressed.value)) return '该运单尚未压缩，以下仅为原始侧点数口径；压缩后可看到完整压缩指标。'
  if (metrics.value.message) return String(metrics.value.message)
  return ''
})

async function compressCur() {
  if (!currentWaybill.value) { ElMessage.warning('请先选择运单'); return }
  const r: any = await compressOne(currentWaybill.value.waybillId)
  ElMessage.success(`压缩完成：${r.cleanedPointCount}→${r.keptPointCount} 点，CR_total=${Number(r.crTotal).toFixed(2)}×，已写入 MongoDB 分片`)
  await loadTrack()
  refreshWaybillList()
}

async function compressAllClick() {
  await ElMessageBox.confirm('将读取全量轨迹源目录并对文件逐个压缩，压缩分片写入 MongoDB。是否继续？', '全量轨迹压缩', {
    confirmButtonText: '开始压缩', cancelButtonText: '取消', type: 'warning'
  })
  const res = await compressAll()
  taskVisible.value = true
  Object.assign(task, { totalFiles: 0, processed: 0, successCount: 0, failedCount: 0, currentWaybillNo: '' })
  taskTimer = setInterval(async () => {
    try {
      const t = await getCompressTask(res.taskId)
      Object.assign(task, t)
      if (t.status !== 'RUNNING') {
        clearInterval(taskTimer)
        taskTimer = null
        ElMessage.success('全量压缩完成')
        refreshWaybillList()
      }
    } catch (e) {
      clearInterval(taskTimer); taskTimer = null
    }
  }, 1500)
}
const taskPct = computed(() => (task.totalFiles ? Math.min(100, Math.round((task.processed / task.totalFiles) * 100)) : 0))
const taskStatus = computed(() => (task.status === 'SUCCESS' ? 'success' : task.status === 'FAILED' ? 'exception' : undefined))

async function refreshWaybillList() {
  const keep = currentWaybill.value
  await load()
  if (keep) currentWaybill.value = rows.value.find((r) => r.waybillId === keep.waybillId) || keep
}

async function openImport() {
  const { value } = await ElMessageBox.prompt('导入前 N 个源文件到运单元数据表（含车牌与收发货元数据回填）；留空使用配置上限', '导入运单源数据', {
    inputValue: '300', inputPlaceholder: '数量或留空'
  })
  const limit = value ? parseInt(value) : undefined
  const r: any = await importWaybills(isNaN(limit as any) ? undefined : limit)
  ElMessage.success(`导入完成：新增 ${r.imported} / 更新 ${r.updated} / 失败 ${r.failed}`)
  await load()
}

onMounted(load)
</script>

<style scoped>
.muted { color: #94a3b8; font-size: 12px; }
.stat-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(178px, 1fr)); gap: 10px; }
.stat-cell { border: 1px solid #e8eef5; border-radius: 6px; padding: 8px 10px; background: #fbfdff; }
.stat-cell.dim { background: #fafbfc; }
.stat-label { font-size: 12px; color: #64748b; line-height: 1.3; }
.stat-value { font-size: 18px; font-weight: 600; color: #0d5ea7; line-height: 1.4; }
.stat-value small { font-size: 12px; color: #64748b; margin-left: 3px; font-weight: 400; }
.stat-hint { font-size: 11px; color: #94a3b8; }
</style>
