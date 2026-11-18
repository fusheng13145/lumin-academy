import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

// 双构建目标：Web 工作台与 Android Capacitor 壳共用一份 React 工程。
// 移动端只加载移动样式与 MobileApp，避免把桌面包体带进 APK。
async function boot(): Promise<void> {
  const root = createRoot(document.getElementById('root')!);
  const isMobile = import.meta.env.VITE_APP_TARGET === 'mobile';

  if (isMobile) {
    await Promise.all([
      import('./styles/tokens.css'),
      import('./styles/mobile.css'),
    ]);
    const { MobileApp } = await import('./mobile/MobileApp');
    root.render(
      <StrictMode>
        <MobileApp />
      </StrictMode>
    );
    return;
  }

  await Promise.all([
    import('./styles/tokens.css'),
    import('./styles/global.css'),
    import('./styles/components.css'),
  ]);
  const { App } = await import('./app/App');
  root.render(
    <StrictMode>
      <App />
    </StrictMode>
  );
}

void boot().catch((error: unknown) => {
  const message = error instanceof Error ? error.message : '应用启动失败';
  const root = document.getElementById('root');
  if (!root) {
    return;
  }
  root.textContent = '';
  const main = document.createElement('main');
  main.style.cssText = 'padding:32px;font-family:system-ui;color:#1d1d1f';
  const title = document.createElement('h1');
  title.textContent = '启动失败';
  const detail = document.createElement('p');
  detail.textContent = message;
  main.append(title, detail);
  root.append(main);
});
