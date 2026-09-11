<template>
  <div>
    <div ref="mapEl" :style="{ width: '100%', height }" class="traj-map"></div>
    <div v-if="loading" class="map-loading">地图加载中…（首次需访问高德，请联网）</div>
    <!-- 图例：两条线颜色不同、且被删点单独高亮，图例把"谁是谁"钉死 -->
    <div v-if="!loading && legend.length" class="map-legend">
      <span v-for="l in legend" :key="l.label" class="legend-item">
        <i class="legend-line" :style="l.style"></i>{{ l.label }}
      </span>
      <template v-if="hasDots">
        <span class="legend-item"><i class="legend-dot legend-del"></i>被有损压缩删除的点</span>
      </template>
      <span v-if="pendingLines.length" class="legend-item"><i class="legend-dot legend-start"></i>起点</span>
      <span v-if="pendingLines.length" class="legend-item"><i class="legend-dot legend-end"></i>终点</span>
      <span v-if="pendingStops.length" class="legend-item"><i class="legend-dot legend-stop"></i>停留锚点</span>
      <span class="legend-item">
        <svg width="12" height="12" viewBox="0 0 24 24"><path d="M12 2 L20 20 L12 15 L4 20 Z" fill="#64748b" stroke="#fff" stroke-width="1.6"/></svg>行驶方向
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { loadAmap } from '../utils/amap'

interface Pt { lon: number; lat: number; time?: string; spd?: number }
interface LineCfg {
  key?: string
  label?: string
  color: string
  points: Pt[]
  opacity?: number
  width?: number
  /** 线叠加层级：同屏画两条几乎重合的线时，用它明确谁在上面（默认虚线 50 / 实线 60） */
  z?: number
  dashed?: boolean
  /** 是否在白底上描一圈外发光（让压缩线从原始线上"浮"出来） */
  halo?: boolean
  /** 是否沿线绘制方向箭头 */
  arrows?: boolean
  /** 是否绘制起/终标记 */
  ends?: boolean
}
interface StopCfg {
  lat: number; lon: number; label?: string
  startTime?: string; endTime?: string; durationS?: number; retainedBoth?: boolean
  /** 是否在停留点旁常驻显示停留时长标签（默认否，悬停始终可看）。对比页需要一眼看到"停了多久"时开启 */
  showDuration?: boolean
}
/** 起讫点/收发地等 POI 标记 */
interface MarkCfg {
  lon: number; lat: number; label: string; sub?: string
  kind?: 'send' | 'receive' | 'start' | 'end' | 'other'
}
/** 一批散点（用于高亮"被有损压缩删除的点"） */
interface DotCfg {
  key?: string
  label?: string
  color: string
  radius?: number
  opacity?: number
  points: { lon: number; lat: number; time?: string }[]
}
/** draw 入参：可选字段全给默认值，调用方按需传 */
interface DrawPayload {
  lines?: LineCfg[]
  stops?: StopCfg[]
  marks?: MarkCfg[]
  dots?: DotCfg[]
  fit?: boolean
}

const props = defineProps<{ height?: string }>()
const emit = defineEmits<{ (e: 'ready'): void }>()

const mapEl = ref<HTMLDivElement>()
const loading = ref(true)
const legend = ref<any[]>([])
const hasDots = ref(false)

let map: any = null
let overlays: any[] = []
let pendingLines: LineCfg[] = []
let pendingStops: StopCfg[] = []
let pendingMarks: MarkCfg[] = []
let pendingDots: DotCfg[] = []
let pendingFit = true
let infoWin: any = null

const MARK_STYLE: Record<string, { text: string; bg: string }> = {
  send: { text: '发货', bg: '#2e9e63' },
  receive: { text: '收货', bg: '#c8a24b' },
  start: { text: '起', bg: '#2e9e63' },
  end: { text: '终', bg: '#d0392e' },
  other: { text: '点', bg: '#64748b' }
}

async function ensureMap() {
  if (map) return
  const AMap = await loadAmap()
  if (!mapEl.value) return
  map = new AMap.Map(mapEl.value, {
    zoom: 12,
    center: [103.42, 24.22],
    viewMode: '2D'
  })
  map.plugin(['AMap.ToolBar', 'AMap.Scale'], () => {
    map.addControl(new AMap.ToolBar({ position: 'RT' }))
    map.addControl(new AMap.Scale({ position: 'LB' }))
  })
  loading.value = false
  emit('ready')
  if (pendingLines.length || pendingStops.length || pendingMarks.length || pendingDots.length) drawAll()
}

function clearOverlays() {
  if (map && overlays.length) map.remove(overlays)
  overlays = []
}

function addPolyline(line: LineCfg, opts: { color: string; width: number; opacity: number; dashed?: boolean; z?: number }) {
  const path = line.points.map((p) => [p.lon, p.lat])
  const poly = new (window as any).AMap.Polyline({
    path,
    strokeColor: opts.color,
    strokeOpacity: opts.opacity,
    strokeWeight: opts.width,
    strokeStyle: opts.dashed ? 'dashed' : 'solid',
    lineJoin: 'round',
    zIndex: opts.z ?? (opts.dashed ? 50 : 60)
  })
  poly.setMap(map)
  overlays.push(poly)
}

/** 两点间近似平面距离（米，按纬度做经度收缩） */
function planarM(a: Pt, b: Pt) {
  const dy = (b.lat - a.lat) * 111320
  const dx = (b.lon - a.lon) * 111320 * Math.cos((a.lat * Math.PI) / 180)
  return Math.sqrt(dx * dx + dy * dy)
}

/** 从起点指向终点的屏幕方位角（度，正北为 0，顺时针） */
function bearingDeg(a: Pt, b: Pt) {
  const dx = (b.lon - a.lon) * Math.cos((a.lat * Math.PI) / 180)
  const dy = b.lat - a.lat
  return (Math.atan2(dx, dy) * 180) / Math.PI
}

/**
 * 沿线均匀放置方向箭头（SVG 箭头，按前进方位角旋转）。
 * 不依赖高德 Polyline 的 showDir（v1.4 各小版本支持不一），自绘更稳。
 * 箭头按"里程"均匀取点：先把总里程按 maxArrows 等分，再在每段内取中点，
 * 这样点稀疏/点密集的路段箭头间隔看起来一致。
 */
function drawArrows(line: LineCfg, AMap: any) {
  const pts = line.points
  if (!pts || pts.length < 2) return
  const cum: number[] = [0]
  for (let i = 1; i < pts.length; i++) cum.push(cum[i - 1] + planarM(pts[i - 1], pts[i]))
  const total = cum[cum.length - 1]
  if (total <= 0) return
  const n = Math.max(2, Math.min(8, Math.round(total / 1500) || 2))
  for (let k = 1; k <= n; k++) {
    const target = (total * k) / (n + 1)
    let i = 1
    while (i < cum.length - 1 && cum[i] < target) i++
    const a = pts[i - 1]
    const b = pts[i]
    const deg = bearingDeg(a, b)
    const html =
      `<div style="width:16px;height:16px;transform:rotate(${deg}deg);opacity:.95">` +
      `<svg viewBox="0 0 24 24" width="16" height="16">` +
      `<path d="M12 2 L20 20 L12 15 L4 20 Z" fill="${line.color}" stroke="#fff" stroke-width="1.8" stroke-linejoin="round"/>` +
      `</svg></div>`
    const marker = new AMap.Marker({
      position: [a.lon, a.lat], content: html,
      offset: new AMap.Pixel(-8, -8), zIndex: 105, bubble: false
    })
    marker.setMap(map)
    overlays.push(marker)
  }
}

/** 起/终标记：白底圆形徽标，描边用线颜色，和对应线条一眼对应 */
function drawEnds(line: LineCfg, AMap: any) {
  const pts = line.points
  if (!pts || pts.length < 1) return
  const badge = (text: string, color: string) =>
    `<div style="display:flex;align-items:center;justify-content:center;width:22px;height:22px;` +
    `border-radius:50%;background:#fff;border:2px solid ${color};color:${color};font-size:12px;` +
    `font-weight:700;box-shadow:0 1px 4px rgba(0,0,0,.35)">${text}</div>`
  const first = pts[0]
  const last = pts[pts.length - 1]
  const ms = new AMap.Marker({
    position: [first.lon, first.lat], content: badge('起', line.color),
    offset: new AMap.Pixel(-11, -11), zIndex: 120, title: `${line.label || ''} 起点`
  })
  ms.setMap(map)
  overlays.push(ms)
  // 单点轨迹不重复画终点
  if (pts.length > 1) {
    const me = new AMap.Marker({
      position: [last.lon, last.lat], content: badge('终', line.color),
      offset: new AMap.Pixel(-11, -11), zIndex: 120, title: `${line.label || ''} 终点`
    })
    me.setMap(map)
    overlays.push(me)
  }
}

/** 收发地等 POI 标记：小标签牌 */
function drawMarks(AMap: any) {
  pendingMarks.forEach((mk) => {
    const style = MARK_STYLE[mk.kind || 'other'] || MARK_STYLE.other
    const html =
      `<div style="display:flex;flex-direction:column;align-items:center;">` +
      `<div style="padding:1px 6px;border-radius:3px;background:${style.bg};color:#fff;font-size:11px;` +
      `white-space:nowrap;box-shadow:0 1px 4px rgba(0,0,0,.35);border:1px solid #fff">` +
      `${style.text}${mk.label ? '·' + mk.label : ''}</div>` +
      `<div style="width:0;height:0;border-left:4px solid transparent;border-right:4px solid transparent;` +
      `border-top:6px solid ${style.bg}"></div></div>`
    const marker = new AMap.Marker({
      position: [mk.lon, mk.lat], content: html,
      offset: new AMap.Pixel(-26, -28), zIndex: 115, title: mk.sub || mk.label
    })
    marker.setMap(map)
    overlays.push(marker)
  })
}

/** 高亮散点（被有损压缩删除的点）：白描边圆点，压在线上方 */
function drawDots(AMap: any) {
  pendingDots.forEach((dot) => {
    const r = dot.radius ?? 4
    dot.points.forEach((p) => {
      const cm = new AMap.CircleMarker({
        center: [p.lon, p.lat],
        radius: r,
        strokeColor: '#ffffff',
        // 白描边按半径缩放：半径小（保留点图层）时若仍用 1.5px 描边，颜色会被白边吃掉
        strokeWeight: Math.max(0.8, r * 0.35),
        fillColor: dot.color,
        fillOpacity: dot.opacity ?? 0.95,
        zIndex: 95,
        cursor: 'pointer'
      })
      cm.setMap(map)
      if (p.time) {
        cm.on('mouseover', () => {
          if (!infoWin) infoWin = new AMap.InfoWindow({ offset: new AMap.Pixel(0, -10) })
          infoWin.setContent(
            `<div style="padding:3px 7px;font-size:12px;">` +
            `<b>${dot.label || '点'}</b><br/>时间：${p.time}` +
            `</div>`)
          infoWin.open(map, [p.lon, p.lat])
        })
        cm.on('mouseout', () => { if (infoWin) infoWin.close() })
      }
      overlays.push(cm)
    })
  })
}

function buildLegend() {
  legend.value = pendingLines.map((l) => ({
    label: l.label || l.key || '',
    style: l.dashed
      ? { background: 'transparent', borderTop: `2px dashed ${l.color}`, height: '2px', width: '18px', display: 'inline-block' }
      : { background: l.color, height: `${Math.max(2, Math.min(5, l.width || 3))}px`, width: '18px', display: 'inline-block' }
  }))
  hasDots.value = (pendingDots || []).some((d) => d.points && d.points.length)
}

/** 秒 → 时分秒（停留时长统一按 HH:MM:SS 展示，超过 24h 继续累加小时数） */
function fmtDur(sec?: number) {
  if (sec === undefined || sec === null || isNaN(sec)) return '-'
  const t = Math.max(0, Math.round(sec))
  const h = Math.floor(t / 3600)
  const m = Math.floor((t % 3600) / 60)
  const s = t % 60
  return [h, m, s].map((v) => String(v).padStart(2, '0')).join(':')
}

function drawAll() {
  if (!map) return
  const AMap = (window as any).AMap
  clearOverlays()
  if (infoWin) { infoWin.close(); infoWin = null }

  pendingLines.forEach((line) => {
    if (!line.points || line.points.length < 1) return
    // 外发光：先画一条更宽的白色底线（z 更低），再画本色线（比 isOutline 更稳，且对比更明显）
    if (line.halo && line.points.length > 1) {
      addPolyline(line, { color: '#ffffff', width: (line.width ?? 4) + 4, opacity: 0.85, z: 55 })
    }
    addPolyline(line, {
      color: line.color,
      width: line.width ?? 3,
      opacity: line.opacity ?? 0.95,
      dashed: line.dashed,
      z: line.z ?? (line.dashed ? 50 : 60)
    })
  })
  pendingLines.forEach((line) => { if (line.arrows) drawArrows(line, AMap) })
  pendingLines.forEach((line) => { if (line.ends) drawEnds(line, AMap) })
  drawDots(AMap)
  drawMarks(AMap)

  pendingStops.forEach((s) => {
    const pos = [s.lon, s.lat]
    const isRest = s.label === 'REST'
    const fill = isRest ? '#e0392e' : (s.retainedBoth === false ? '#c20' : '#d8a018')
    const marker = new AMap.CircleMarker({
      center: pos,
      radius: 7,
      strokeColor: '#fff',
      strokeWeight: 2,
      fillColor: fill,
      fillOpacity: 0.95,
      zIndex: 100,
      cursor: 'pointer'
    })
    marker.setMap(map)
    // 常驻时长标签：停留点旁挂一个小牌，写 HH:MM:SS，避免逐个悬停才看得到
    if (s.showDuration) {
      const badge = new AMap.Marker({
        position: pos,
        content:
          `<div style="padding:0 5px;border-radius:3px;background:rgba(255,255,255,.94);` +
          `border:1px solid ${fill};color:#1f2937;font-size:11px;line-height:16px;white-space:nowrap;` +
          `box-shadow:0 1px 3px rgba(0,0,0,.25)">${fmtDur(s.durationS)}</div>`,
        offset: new AMap.Pixel(9, -9),
        zIndex: 108,
        bubble: false
      })
      badge.setMap(map)
      overlays.push(badge)
    }
    marker.on('mouseover', () => {
      const content =
        `<div style="padding:4px 8px;font-size:12px;min-width:200px;">` +
        `<div><b>停留单元 ${s.label || ''}</b>${s.retainedBoth === false ? '（锚点未成对保留）' : ''}</div>` +
        `<div>开始：${s.startTime || '-'}</div>` +
        `<div>结束：${s.endTime || '-'}</div>` +
        `<div>停留时长：${fmtDur(s.durationS)}</div>` +
        `</div>`
      if (!infoWin) infoWin = new AMap.InfoWindow({ offset: new AMap.Pixel(0, -14) })
      infoWin.setContent(content)
      infoWin.open(map, pos)
    })
    marker.on('mouseout', () => { if (infoWin) infoWin.close() })
    overlays.push(marker)
  })

  // 视野自适配：直接对所有覆盖物调用 setFitView。
  // 注意：AMap 1.4 下 Bounds#extend() 对普通数组 [lon, lat] 不生效，会得到 NaN 包围盒，
  // 导致 setBounds 抛错，异常冒泡到调用方会被误报为“轨迹读取失败”。
  if (pendingFit && overlays.length) {
    map.setFitView(overlays)
    pendingFit = false
  }
}

function draw(payload: DrawPayload = {}) {
  pendingLines = payload.lines || []
  pendingStops = payload.stops || []
  pendingMarks = payload.marks || []
  pendingDots = payload.dots || []
  pendingFit = payload.fit !== false
  buildLegend()
  drawAll()
}

function clear() {
  pendingLines = []
  pendingStops = []
  pendingMarks = []
  pendingDots = []
  legend.value = []
  hasDots.value = false
  if (infoWin) { infoWin.close(); infoWin = null }
  clearOverlays()
}

onMounted(ensureMap)
onBeforeUnmount(() => { if (map) map.destroy(); map = null })

defineExpose({ draw, clear })
</script>

<style scoped>
.traj-map { border-radius: 4px; background: #eef2f6; }
.map-loading { position: absolute; }
.map-legend {
  display: flex; flex-wrap: wrap; gap: 4px 14px; align-items: center;
  padding: 4px 8px; font-size: 12px; color: #475569; background: #f8fafc;
  border-top: 1px solid #e6ebf1; border-radius: 0 0 4px 4px;
}
.legend-item { display: inline-flex; align-items: center; gap: 4px; white-space: nowrap; }
.legend-line { display: inline-block; vertical-align: middle; }
.legend-dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; border: 2px solid #fff; }
.legend-start { background: #2e9e63; }
.legend-end { background: #d0392e; }
.legend-stop { background: #d8a018; }
.legend-del { background: #e8590c; }
</style>
