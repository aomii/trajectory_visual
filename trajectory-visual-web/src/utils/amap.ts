/**
 * 高德地图 JS API 加载器。
 * 使用 v1.4.15（本地开发 key，无需安全密钥配置；生产可按需切换 v2 + securityJsCode）。
 * Key 从环境变量读取（.env.development 的 VITE_AMAP_KEY），不硬编码在组件。
 */
let cached: Promise<any> | null = null

export function loadAmap(): Promise<any> {
  if ((window as any).AMap) return Promise.resolve((window as any).AMap)
  if (cached) return cached
  cached = new Promise((resolve, reject) => {
    const key = import.meta.env.VITE_AMAP_KEY || ''
    const sec = import.meta.env.VITE_AMAP_SECURITY_CODE || ''
    if (sec) {
      ;(window as any)._AMapSecurityConfig = { securityJsCode: sec }
    }
    const script = document.createElement('script')
    script.src = `https://webapi.amap.com/maps?v=1.4.15&key=${key}&plugin=AMap.Scale,AMap.ToolBar`
    script.onload = () => {
      if ((window as any).AMap) resolve((window as any).AMap)
      else reject(new Error('高德地图加载失败'))
    }
    script.onerror = () => {
      cached = null
      reject(new Error('高德地图 SDK 网络加载失败，请检查网络与 Key'))
    }
    document.head.appendChild(script)
  })
  return cached
}
