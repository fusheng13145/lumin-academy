import type { CapacitorConfig } from '@capacitor/cli'

// 启明书院 Android 壳配置。
// 业务 UI 全部由 Vite React 构建产物承载；不使用远程 URL，避免审核与安全风险。
const config: CapacitorConfig = {
  appId: 'com.leyon.lumin',
  appName: '启明书院',
  webDir: 'dist-mobile',
  backgroundColor: '#F5F1E8',
  server: {
    androidScheme: 'https',
  },
  android: {
    allowMixedContent: false,
    backgroundColor: '#F5F1E8',
  },
  ios: {
    contentInset: 'always',
    backgroundColor: '#F5F1E8',
  },
}

export default config
