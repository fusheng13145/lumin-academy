import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 启明书院双构建目标：
// - web：现有 Web 工作台，输出 dist，绝对资源路径；
// - mobile：Capacitor Android 壳，输出 dist-mobile，相对资源路径。
// 合规约束：不引入任何外链 CDN / 字体 / 图片；纯本地静态资源。
export default defineConfig(({ mode }) => {
  const isMobile = mode === 'mobile'

  return {
    plugins: [react()],
    base: isMobile ? './' : '/',
    server: {
      port: isMobile ? 5174 : 5173,
      host: true,
      // 后端 API 代理（骨架联调：Spring Boot 8081；OIDC 接入前为替身身份）
      proxy: {
        '/api': {
          target: 'http://localhost:8081',
          changeOrigin: true,
        },
        '/internal': {
          target: 'http://localhost:8081',
          changeOrigin: true,
        },
      },
    },
    build: {
      outDir: isMobile ? 'dist-mobile' : 'dist',
      sourcemap: !isMobile,
      emptyOutDir: true,
    },
  }
})
