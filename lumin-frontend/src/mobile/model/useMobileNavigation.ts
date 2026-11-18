import { useCallback, useEffect, useRef, useState } from 'react';
import { App as CapacitorApp } from '@capacitor/app';
import { type MobileRoute, type MobileTab } from '../navigation';

/**
 * 移动端路由边界：浏览器历史语义 + Android 返回键。
 * 屏幕组件只拿到 navigate/back，不直接操作 Capacitor。
 */
export function useMobileNavigation() {
  const [route, setRoute] = useState<MobileRoute>({ name: 'home' });
  const history = useRef<MobileRoute[]>([]);

  const navigate = useCallback((next: MobileRoute) => {
    setRoute((current) => {
      history.current.push(current);
      return next;
    });
  }, []);

  const back = useCallback(() => {
    const previous = history.current.pop();
    setRoute(previous ?? { name: 'home' });
  }, []);

  const selectTab = useCallback((tab: MobileTab): void => {
    history.current = [];
    setRoute({ name: tab } as MobileRoute);
  }, []);

  useEffect(() => {
    if (route.name !== 'reader') {
      window.scrollTo({ top: 0, behavior: 'auto' });
    }
  }, [route.name]);

  useEffect(() => {
    let remove: (() => Promise<void>) | undefined;
    void CapacitorApp.addListener('backButton', () => {
      if (history.current.length > 0) {
        back();
        return;
      }
      if (route.name !== 'home') {
        setRoute({ name: 'home' });
        return;
      }
      void CapacitorApp.exitApp();
    })
      .then((handle) => {
        remove = () => handle.remove();
      })
      .catch(() => undefined);
    return () => {
      if (remove) {
        void remove();
      }
    };
  }, [back, route.name]);

  return { route, setRoute, navigate, back, selectTab };
}
