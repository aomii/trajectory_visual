import dayjs from 'dayjs'

/** 千分位 */
export function fmtInt(v?: number | null): string {
  if (v === null || v === undefined || isNaN(Number(v))) return '-'
  return Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 0 })
}

/** 通用数字：最多 decimals 位小数 */
export function fmtNum(v?: number | null, decimals = 2): string {
  if (v === null || v === undefined || isNaN(Number(v))) return '-'
  return Number(v).toLocaleString('zh-CN', {
    maximumFractionDigits: decimals,
    minimumFractionDigits: 0
  })
}

export function fmtPct(v?: number | null, decimals = 1): string {
  if (v === null || v === undefined || isNaN(Number(v))) return '-'
  return (v * 100).toFixed(decimals) + '%'
}

/** 字节 → 人类可读 */
export function fmtBytes(v?: number | null): string {
  if (v === null || v === undefined) return '-'
  const b = Number(v)
  if (b < 1024) return b + ' B'
  if (b < 1024 * 1024) return (b / 1024).toFixed(1) + ' KB'
  return (b / 1024 / 1024).toFixed(2) + ' MB'
}

/** 毫秒 → 人类可读 */
export function fmtMs(v?: number | null): string {
  if (v === null || v === undefined) return '-'
  if (Number(v) < 1000) return fmtNum(v, 2) + ' ms'
  return fmtNum(v / 1000, 2) + ' s'
}

export function nowTag(): string {
  return dayjs().format('YYYYMMDD-HHmmss')
}

/**
 * 后端 LocalDateTime 经 JSON 序列化为 "2025-05-02T23:29:35"，
 * 页面上统一显示为 "2025-05-02 23:29:35"。
 */
export function fmtTime(v?: string | null): string {
  if (!v) return '-'
  const s = String(v).replace('T', ' ')
  return s.length > 19 ? s.slice(0, 19) : s
}

/** 触发浏览器下载文本文件 */
export function downloadText(filename: string, text: string, mime = 'text/csv;charset=utf-8') {
  const blob = new Blob(['﻿' + text], { type: mime })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/** CSV 转义 */
export function csvEsc(v: any): string {
  if (v === null || v === undefined) return ''
  const s = String(v)
  return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
}

/** 由对象数组生成 CSV 文本 */
export function toCSV(rows: any[]): string {
  if (!rows || !rows.length) return ''
  const keys = Object.keys(rows[0])
  const head = keys.map(csvEsc).join(',')
  const lines = rows.map((r) => keys.map((k) => csvEsc(r[k])).join(','))
  return [head, ...lines].join('\n')
}

/** 指标单位说明表（Dashboard 图标题后缀用） */
export const METRIC_META: Record<string, { label: string; unit: string; tooltip: string }> = {
  crTotalAvg: { label: '总压缩率 CR_total', unit: 'x', tooltip: '有损层压缩率 × 无损层压缩率（逐运单）' },
  crLossyAvg: { label: '有损层压缩率 CR_lossy', unit: 'x', tooltip: '清洗后点数 / 压缩后保留点数' },
  crLosslessAvg: { label: '无损层压缩率 CR_lossless', unit: 'x', tooltip: '规范文本字节 / 编码后字节' },
  pedAvg: { label: '平均位置误差 PED', unit: 'm', tooltip: '原始轨迹点到压缩折线的平均垂直距离（逐运单均值）' },
  sedAvg: { label: '平均同步误差 SED', unit: 'm', tooltip: '原始点到按时间插值同步点的平均距离（逐运单均值）' },
  srAvg: { label: '语义点保留率 SR', unit: '%', tooltip: '压缩后保留的关键锚点数 / 锚点总数' },
  semanticUnitCompleteRate: { label: '语义单元完整率', unit: '%', tooltip: '起终锚点成对保留的停留单元占比' },
  stayDurationPreserveRate: { label: '停留时长保真度', unit: '%', tooltip: '由成对锚点可恢复的停留时长 / 原始时长' },
  encodeTimeMsAvg: { label: '编码耗时', unit: 'ms', tooltip: '无损编码单运单平均耗时' },
  decodeTimeMsAvg: { label: '全量解压耗时', unit: 'ms', tooltip: '全量解压单运单平均耗时' },
  queryTimeMsAvg: { label: '部分解压耗时', unit: 'ms', tooltip: '按时间窗部分解压单运单平均耗时' },
  storageBytes: { label: '存储字节数', unit: 'B', tooltip: '本文无损层编码存储总字节' },
  partialReadRatioAvg: { label: '部分读取字节比例', unit: '%', tooltip: '命中分片字节 / 全部分片字节' }
}
