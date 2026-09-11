import * as echarts from 'echarts'

// 图表颜色语义（规格书 §13.4）
export const COLOR = {
  PROPOSED: '#0d5ea7', // 本文方法：深蓝强调
  DP: '#9db4c8',
  DPS: '#6f9e7a',
  'TD-TR': '#b58a5a',
  Trajic: '#9a7fb0',
  semantic: '#2e9e63', // 语义保真：绿
  error: '#d96a3c', // 误差/耗时：橙红
  accent: '#c8a24b'
}

export const ALG_NAMES: Record<string, string> = {
  PROPOSED: '本文方法',
  DP: 'DP',
  DPS: 'DPS',
  'TD-TR': 'TD-TR',
  Trajic: 'Trajic'
}

/**
 * 地图配色（2026-09-10 用户定稿）：原始轨迹=蓝、本文压缩=绿、被有损压缩删除的点=橙（高亮）。
 *
 * <p>与图表配色刻意不同：图表里"本文方法"是深蓝，但地图上要同时看"原始 vs 压缩"两条线，
 * 蓝色留给原始线更符合直觉；橙点与蓝线/绿线在常见色觉缺陷下仍可区分（避开红绿对）。
 */
export const MAP_COLOR = {
  /** 原始轨迹（清洗后全量点）：蓝 */
  original: '#1e6fd9',
  /** 本文压缩（保留点）：绿 */
  compressed: '#12a150',
  /** 被有损压缩删除的点：橙 */
  deleted: '#e8590c'
}

export function colorOf(code: string): string {
  return (COLOR as any)[code] || '#999'
}

// 统一中文文本风格
export const baseText = {
  fontFamily: 'Microsoft YaHei, PingFang SC, sans-serif',
  color: '#334155'
}

export function createChart(el: HTMLElement | null): echarts.ECharts | null {
  if (!el) return null
  const inst = echarts.init(el)
  return inst
}

export function setOption(inst: any, option: any) {
  if (inst) inst.setOption(option, true)
}

/** 清空容器下的图表实例 */
export function disposeChart(inst: any) {
  if (inst) inst.dispose()
}

export function baseGrid() {
  return { left: 16, right: 24, top: 44, bottom: 8, containLabel: true }
}
