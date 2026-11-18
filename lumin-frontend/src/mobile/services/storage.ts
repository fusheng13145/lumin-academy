import { Preferences } from '@capacitor/preferences';

export const STORAGE_KEYS = {
  draft: 'lumin.mobile.draft.v1',
  reader: 'lumin.mobile.reader.v1',
  progress: 'lumin.mobile.progress.v1',
  memory: 'lumin.mobile.memory.v1',
  mode: 'lumin.mobile.mode.v1',
  theme: 'lumin.mobile.theme.v1',
} as const;

export async function readStored<T>(key: string, fallback: T): Promise<T> {
  try {
    const { value } = await Preferences.get({ key });
    if (!value) {
      return fallback;
    }
    return JSON.parse(value) as T;
  } catch {
    // 存储损坏或被系统清理时，回退到安全默认值，不阻塞首屏。
    return fallback;
  }
}

export async function writeStored(key: string, value: unknown): Promise<void> {
  try {
    await Preferences.set({ key, value: JSON.stringify(value) });
  } catch {
    // 本地演示允许写失败；真实环境应上报埋点并提示用户。
  }
}

export async function removeStored(key: string): Promise<void> {
  try {
    await Preferences.remove({ key });
  } catch {
    // 同上。
  }
}
