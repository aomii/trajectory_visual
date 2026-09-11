import axios from 'axios'

// 统一 HTTP 客户端：自动带 Result 解包；code!==200 抛异常（code 404 表示 not_available/无数据）
const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 120000
})

export class ApiErr extends Error {
  code: number
  constructor(code: number, msg: string) {
    super(msg)
    this.code = code
  }
}

export async function request<T>(config: any): Promise<T> {
  const res = await http.request(config)
  const body = res.data
  if (body && body.code === 200) return body.data as T
  const code = body?.code ?? -1
  throw new ApiErr(code, body?.message || '请求失败')
}

// ---------------- 运单与轨迹（页面一/二） ----------------
export const getWaybillPage = (params: any) =>
  request<any>({ method: 'get', url: '/api/visual/waybill/page', params })
export const importWaybills = (limit?: number) =>
  request<any>({ method: 'post', url: '/api/visual/waybill/admin/import-waybills', params: { limit } })
export const getOriginal = (waybillId: number) =>
  request<any>({ method: 'get', url: `/api/visual/trajectory/original/${waybillId}` })
export const getCompressed = (waybillId: number) =>
  request<any>({ method: 'get', url: `/api/visual/trajectory/compressed/${waybillId}` })
export const getChunks = (waybillId: number) =>
  request<any[]>({ method: 'get', url: `/api/visual/trajectory/chunks/${waybillId}` })
/** 压缩/坐标口径快照：blockWindowS / precision / moveToleranceM / coordSystem 等 */
export const getTrackConfig = () =>
  request<any>({ method: 'get', url: '/api/visual/trajectory/config' })
export const compressOne = (waybillId: number) =>
  request<any>({ method: 'post', url: `/api/visual/trajectory/compress/${waybillId}` })
export const compressAll = (limit?: number) =>
  request<any>({ method: 'post', url: '/api/visual/trajectory/compress-all', params: { limit } })
export const getCompressTask = (taskId: string) =>
  request<any>({ method: 'get', url: `/api/visual/trajectory/compress-task/${taskId}` })
export const searchByTime = (body: any) =>
  request<any>({ method: 'post', url: '/api/visual/trajectory/search-by-time', data: body })
/** 单运单 × 各算法保留轨迹（对比页地图下钻）：本文 + DP/DPS/TD-TR/Trajic，本文另带停留单元 */
export const getAlgorithmTracks = (waybillId: number) =>
  request<any>({ method: 'get', url: `/api/visual/trajectory/algorithm-tracks/${waybillId}` })

// ---------------- 评估（页面三/四/五） ----------------
export const getEvalRuns = () =>
  request<any[]>({ method: 'get', url: '/api/visual/evaluation/runs' })
export const getDashboard = (runId?: number) =>
  request<any>({ method: 'get', url: '/api/visual/evaluation/dashboard', params: { runId } })
export const getEvalSummary = (runId?: number) =>
  request<any>({ method: 'get', url: '/api/visual/evaluation/summary', params: { runId } })
export const getAlgorithms = (runId?: number) =>
  request<any[]>({ method: 'get', url: '/api/visual/evaluation/algorithms', params: { runId } })
export const getAlgorithmCompare = (runId?: number) =>
  request<any>({ method: 'get', url: '/api/visual/evaluation/algorithm-compare', params: { runId } })
export const getAblation = (runId?: number) =>
  request<any[]>({ method: 'get', url: '/api/visual/evaluation/ablation', params: { runId } })
export const getParamSensitivity = (runId?: number) =>
  request<any>({ method: 'get', url: '/api/visual/evaluation/parameter-sensitivity', params: { runId } })
export const getPartial = (runId?: number) =>
  request<any>({ method: 'get', url: '/api/visual/evaluation/partial-decompression', params: { runId } })
export const getEvalWaybill = (waybillId: number, runId?: number) =>
  request<any[]>({ method: 'get', url: `/api/visual/evaluation/waybill/${waybillId}`, params: { runId } })
export const getEvalErrors = (runId?: number) =>
  request<any[]>({ method: 'get', url: '/api/visual/evaluation/errors', params: { runId } })