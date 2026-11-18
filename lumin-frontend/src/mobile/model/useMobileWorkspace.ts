import { useEffect, useState } from 'react';
import type { CreationMode, DraftPayload, MemoryFact, ReadingProgress, ReaderSettings } from '../types';
import { DEFAULT_MEMORY } from '../data/studio';
import { DEFAULT_SETTINGS } from './drafts';
import { STORAGE_KEYS, readStored, writeStored } from '../services/storage';
import { normalizeCreationMode } from '../../lib/creationMode';

export type MobileTheme = 'light' | 'night';

/**
 * 移动端本地状态与持久化边界。
 * 只负责“可恢复的壳状态”，不包含路由、草稿业务动作或 UI。
 */
export function useMobileWorkspace() {
  const [settings, setSettings] = useState<ReaderSettings>(DEFAULT_SETTINGS);
  const [progressList, setProgressList] = useState<ReadingProgress[]>([]);
  const [memory, setMemory] = useState<MemoryFact[]>(DEFAULT_MEMORY);
  const [draft, setDraft] = useState<DraftPayload | null>(null);
  const [mode, setMode] = useState<CreationMode>('manual');
  const [theme, setTheme] = useState<MobileTheme>('light');
  const [booted, setBooted] = useState(false);

  useEffect(() => {
    let active = true;
    void Promise.all([
      readStored<ReaderSettings>(STORAGE_KEYS.reader, DEFAULT_SETTINGS),
      readStored<ReadingProgress[]>(STORAGE_KEYS.progress, []),
      readStored<MemoryFact[]>(STORAGE_KEYS.memory, DEFAULT_MEMORY),
      readStored<DraftPayload | null>(STORAGE_KEYS.draft, null),
      readStored<unknown>(STORAGE_KEYS.mode, 'manual'),
      readStored<MobileTheme>(STORAGE_KEYS.theme, 'light'),
    ])
      .then(([storedSettings, storedProgress, storedMemory, storedDraft, storedMode, storedTheme]) => {
        if (!active) {
          return;
        }
        setSettings(storedSettings);
        setProgressList(storedProgress);
        setMemory(storedMemory.length > 0 ? storedMemory : DEFAULT_MEMORY);
        setDraft(storedDraft);
        setMode(normalizeCreationMode(storedMode));
        setTheme(storedTheme);
        setBooted(true);
      })
      .catch(() => {
        if (active) {
          setBooted(true);
        }
      });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if (booted) {
      void writeStored(STORAGE_KEYS.reader, settings);
    }
  }, [booted, settings]);

  useEffect(() => {
    if (booted) {
      void writeStored(STORAGE_KEYS.progress, progressList);
    }
  }, [booted, progressList]);

  useEffect(() => {
    if (booted) {
      void writeStored(STORAGE_KEYS.memory, memory);
    }
  }, [booted, memory]);

  useEffect(() => {
    if (booted) {
      void writeStored(STORAGE_KEYS.draft, draft);
    }
  }, [booted, draft]);

  useEffect(() => {
    if (booted) {
      void writeStored(STORAGE_KEYS.mode, mode);
    }
  }, [booted, mode]);

  useEffect(() => {
    if (booted) {
      void writeStored(STORAGE_KEYS.theme, theme);
    }
  }, [booted, theme]);

  return {
    settings,
    setSettings,
    progressList,
    setProgressList,
    memory,
    setMemory,
    draft,
    setDraft,
    mode,
    setMode,
    theme,
    setTheme,
  };
}
