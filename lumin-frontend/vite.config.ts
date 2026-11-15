import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 启明书院 Web 前端构建配置
// 合规约束：不引入任何外链 CDN / 字体 / 图片；纯本地静态资源。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    host: true,
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
})
