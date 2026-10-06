import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'node:path'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 5173,
    // 开发时把 /api 代理到后端网关，避免 CORS。后端就绪前可开启 mock。
    proxy: {
      '/api': {
        target: 'http://localhost:9000',
        changeOrigin: true,
        // 后端接口挂在 /v1/...（无 /api 前缀），转发时去掉 /api。
        rewrite: (p) => p.replace(/^\/api/, ''),
      },
    },
  },
})
