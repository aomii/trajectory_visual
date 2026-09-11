import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 配置：本地开发联调后端 http://127.0.0.1:8080（见 .env.development 的 VITE_API_BASE_URL）
export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    open: false,
    // 代理 /api 请求到后端，解决开发环境 CORS 问题
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    sourcemap: false
  }
})