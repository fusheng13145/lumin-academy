import { useCallback, useEffect, useRef, useState } from 'react';
import { App as CapacitorApp } from '@capacitor/app';
import { SplashScreen } from '@capacitor/splash-screen';
import type { CreationMode, DraftPayload, MemoryFact, ReadingProgress, ReaderSettings } from './types';
import { DEFAULT_MEMORY, DEFAULT_DRAFT, STUDIO_WORKS } from './data/studio';
import { MOBILE_BOOKS, findBook } from './data/library';
import { MODE_LABEL } from './services/aiDemo';
import { STORAGE_KEYS, readStored, writeStored } from './services/storage';
import { BottomNav, Toast } from './components/ui';
import { isTabRoute, type MobileRoute, type MobileTab } from './navigation';
import { HomeScreen } from './screens/HomeScreen';
import { SearchScreen } from './screens/SearchScreen';
import { BookDetailScreen } from './screens/BookDetailScreen';
import { ReaderScreen } from './screens/ReaderScreen';
import { StudioScreen } from './screens/StudioScreen';
import { WorkScreen } from './screens/WorkScreen';
import { EditorScreen } from './screens/EditorScreen';
import { AiScreen } from './screens/AiScreen';
import { MaterialsScreen } from './screens/MaterialsScreen';
import { IllustrationScreen } from './screens/IllustrationScreen';
import { MeScreen } from './screens/MeScreen';
import { SettingsScreen } from './screens/SettingsScreen';
import { CommentsScreen } from './screens/CommentsScreen';

const DEFAULT_SETTINGS: ReaderSettings = {
  theme: 'rice',
  fontSize: 19,
  lineHeight: 1.95,
  fontFamily: 'serif',
};

function buildDraft(
  workId: string,
  chapterId: string | undefined,
  mode: CreationMode
): DraftPayload {
  const work = STUDIO_WORKS.find((item) => item.id === workId) ?? STUDIO_WORKS[0];
  const book = findBook(work.bookId);
  const chapter = chapterId ? book?.chapters.find((item) => item.id === chapterId) : undefined;
  return {
    workId,
    chapterId: chapter?.id ?? null,
    title: chapter ? `第 ${chapter.no} 章 · ${chapter.title}` : DEFAULT_DRAFT.title,
    body: chapter ? chapter.body.join('\n\n') : DEFAULT_DRAFT.body,
    mode,
    updatedAt: new Date().toISOString(),
  };
}

export function MobileApp() {
  const [route, setRoute] = useState<MobileRoute>({ name: 'home' });
  const [theme, setTheme] = useState<'light' | 'night'>('light');
  const [settings, setSettings] = useState<ReaderSettings>(DEFAULT_SETTINGS);
  const [progressList, setProgressList] = useState<ReadingProgress[]>([]);
  const [memory, setMemory] = useState<MemoryFact[]>(DEFAULT_MEMORY);
  const [draft, setDraft] = useState<DraftPayload | null>(null);
  const [mode, setMode] = useState<CreationMode>('manual');
  const [editorDraft, setEditorDraft] = useState<DraftPayload | null>(null);
  const [booted, setBooted] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const history = useRef<MobileRoute[]>([]);
  const toastTimer = useRef<number | null>(null);

  const showToast = useCallback((message: string) => {
    setToast(message);
    if (toastTimer.current) {
      window.clearTimeout(toastTimer.current);
    }
    toastTimer.current = window.setTimeout(() => setToast(null), 2400);
  }, []);

  useEffect(() => {
    document.documentElement.dataset.mApp = 'true';
    return () => {
      delete document.documentElement.dataset.mApp;
    };
  }, []);

  useEffect(() => {
    document.documentElement.dataset.mTheme = theme;
    return () => {
      delete document.documentElement.dataset.mTheme;
    };
  }, [theme]);

  useEffect(() => {
    let active = true;
    void Promise.all([
      readStored<ReaderSettings>(STORAGE_KEYS.reader, DEFAULT_SETTINGS),
      readStored<ReadingProgress[]>(STORAGE_KEYS.progress, []),
      readStored<MemoryFact[]>(STORAGE_KEYS.memory, DEFAULT_MEMORY),
      readStored<DraftPayload | null>(STORAGE_KEYS.draft, null),
      readStored<CreationMode>(STORAGE_KEYS.mode, 'manual'),
      readStored<'light' | 'night'>(STORAGE_KEYS.theme, 'light'),
    ])
      .then(([storedSettings, storedProgress, storedMemory, storedDraft, storedMode, storedTheme]) => {
        if (!active) {
          return;
        }
        setSettings(storedSettings);
        setProgressList(storedProgress);
        setMemory(storedMemory.length > 0 ? storedMemory : DEFAULT_MEMORY);
        setDraft(storedDraft);
        setMode(storedMode);
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
    if (!booted) {
      return;
    }
    void writeStored(STORAGE_KEYS.reader, settings);
  }, [booted, settings]);

  useEffect(() => {
    if (!booted) {
      return;
    }
    void writeStored(STORAGE_KEYS.progress, progressList);
  }, [booted, progressList]);

  useEffect(() => {
    if (!booted) {
      return;
    }
    void writeStored(STORAGE_KEYS.memory, memory);
  }, [booted, memory]);

  useEffect(() => {
    if (!booted) {
      return;
    }
    void writeStored(STORAGE_KEYS.draft, draft);
  }, [booted, draft]);

  useEffect(() => {
    if (!booted) {
      return;
    }
    void writeStored(STORAGE_KEYS.mode, mode);
  }, [booted, mode]);

  useEffect(() => {
    if (!booted) {
      return;
    }
    void writeStored(STORAGE_KEYS.theme, theme);
  }, [booted, theme]);

  useEffect(() => {
    void SplashScreen.hide().catch(() => undefined);
  }, []);

  useEffect(() => {
    if (route.name !== 'reader') {
      window.scrollTo({ top: 0, behavior: 'auto' });
    }
  }, [route.name]);

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

  const selectTab = (tab: MobileTab): void => {
    history.current = [];
    setRoute({ name: tab } as MobileRoute);
  };

  const openBook = (bookId: string): void => {
    navigate({ name: 'book', bookId });
  };

  const openReader = (bookId: string, chapterNo: number): void => {
    navigate({ name: 'reader', bookId, chapterNo });
  };

  const handleProgress = useCallback(
    (bookId: string, chapterNo: number, percent: number) => {
      setProgressList((items) => {
        const next: ReadingProgress = {
          bookId,
          chapterNo,
          percent,
          updatedAt: new Date().toISOString(),
        };
        return [next, ...items.filter((item) => item.bookId !== bookId)];
      });
    },
    []
  );

  const openEditor = (workId: string, chapterId?: string): void => {
    const work = STUDIO_WORKS.find((item) => item.id === workId) ?? STUDIO_WORKS[0];
    const existing =
      draft && draft.workId === work.id && (draft.chapterId ?? undefined) === (chapterId ?? undefined)
        ? draft
        : null;
    const nextDraft = existing ?? buildDraft(work.id, chapterId, mode);
    setEditorDraft(nextDraft);
    setDraft(nextDraft);
    navigate({
      name: 'editor',
      workId: work.id,
      chapterId: nextDraft.chapterId ?? undefined,
      mode,
    });
  };

  const saveDraft = useCallback((next: DraftPayload) => {
    setDraft(next);
    setEditorDraft(next);
  }, []);

  const openAi = (nextMode?: CreationMode): void => {
    if (nextMode) {
      setMode(nextMode);
    }
    navigate({ name: 'ai' });
  };

  const insertCandidate = (text: string): void => {
    const base = editorDraft ?? draft ?? buildDraft('w1', undefined, 'assisted');
    const freshParagraphs = text
      .split(/\n\n+/)
      .map((paragraph) => paragraph.trim())
      .filter((paragraph) => paragraph.length > 0 && !base.body.includes(paragraph));
    const addition = freshParagraphs.join('\n\n');
    const next: DraftPayload = {
      ...base,
      body: addition ? (base.body ? `${base.body}\n\n${addition}` : addition) : base.body,
      mode: base.mode === 'manual' ? 'assisted' : base.mode,
      updatedAt: new Date().toISOString(),
    };
    setDraft(next);
    setEditorDraft(next);
    navigate({
      name: 'editor',
      workId: next.workId,
      chapterId: next.chapterId ?? undefined,
      mode: next.mode,
    });
  };

  const publish = (next: DraftPayload): void => {
    setDraft(next);
    setEditorDraft(next);
    showToast('已提交审核：草稿 → 自检 → 审核 → 发布');
  };

  const addMemory = (facts: MemoryFact[]): void => {
    setMemory((items) => {
      const existingLabels = new Set(items.map((item) => item.label));
      return [...facts.filter((fact) => !existingLabels.has(fact.label)), ...items];
    });
  };

  const latestProgress = progressList[0] ?? null;
  const activeTab = isTabRoute(route.name) ? route.name : 'home';

  function renderScreen() {
    switch (route.name) {
      case 'home':
        return (
          <HomeScreen
            progress={latestProgress}
            onOpenBook={openBook}
            onOpenReader={openReader}
            onSearch={() => navigate({ name: 'search' })}
            onStudio={() => selectTab('studio')}
            onToast={showToast}
          />
        );
      case 'search':
        return <SearchScreen onBack={back} onOpenBook={openBook} />;
      case 'book': {
        const book = findBook(route.bookId);
        if (!book) {
          return <HomeScreen progress={latestProgress} onOpenBook={openBook} onOpenReader={openReader} onSearch={() => navigate({ name: 'search' })} onStudio={() => selectTab('studio')} onToast={showToast} />;
        }
        return (
          <BookDetailScreen
            book={book}
            onBack={back}
            onRead={(chapterNo) => openReader(book.id, chapterNo)}
            onOpenBook={openBook}
            onToast={showToast}
          />
        );
      }
      case 'reader': {
        const book = findBook(route.bookId);
        if (!book) {
          return <HomeScreen progress={latestProgress} onOpenBook={openBook} onOpenReader={openReader} onSearch={() => navigate({ name: 'search' })} onStudio={() => selectTab('studio')} onToast={showToast} />;
        }
        return (
          <ReaderScreen
            book={book}
            chapterNo={route.chapterNo}
            settings={settings}
            onBack={back}
            onChapterChange={(chapterNo) => setRoute({ ...route, chapterNo })}
            onSettingsChange={setSettings}
            onProgress={(chapterNo, percent) => handleProgress(book.id, chapterNo, percent)}
          />
        );
      }
      case 'studio':
        return (
          <StudioScreen
            onOpenWork={(workId) => navigate({ name: 'work', workId })}
            onOpenEditor={openEditor}
            onOpenAi={() => openAi()}
            onComments={() => navigate({ name: 'comments' })}
            onMaterials={() => navigate({ name: 'materials' })}
            onToast={showToast}
          />
        );
      case 'work': {
        const work = STUDIO_WORKS.find((item) => item.id === route.workId);
        if (!work) {
          return <StudioScreen onOpenWork={(workId) => navigate({ name: 'work', workId })} onOpenEditor={openEditor} onOpenAi={() => openAi()} onComments={() => navigate({ name: 'comments' })} onMaterials={() => navigate({ name: 'materials' })} onToast={showToast} />;
        }
        const book = findBook(work.bookId);
        if (!book) {
          return <StudioScreen onOpenWork={(workId) => navigate({ name: 'work', workId })} onOpenEditor={openEditor} onOpenAi={() => openAi()} onComments={() => navigate({ name: 'comments' })} onMaterials={() => navigate({ name: 'materials' })} onToast={showToast} />;
        }
        return (
          <WorkScreen
            work={work}
            book={book}
            onBack={back}
            onOpenEditor={openEditor}
            onOpenAi={() => openAi()}
            onToast={showToast}
          />
        );
      }
      case 'editor': {
        const work = STUDIO_WORKS.find((item) => item.id === route.workId) ?? STUDIO_WORKS[0];
        const book = findBook(work.bookId);
        const chapter = route.chapterId
          ? book?.chapters.find((item) => item.id === route.chapterId) ?? null
          : null;
        const initial = editorDraft ?? (draft && draft.workId === work.id ? draft : null) ?? buildDraft(work.id, route.chapterId, mode);
        return (
          <EditorScreen
            key={`${work.id}-${route.chapterId ?? 'new'}-${route.mode ?? mode}`}
            work={work}
            chapter={chapter}
            mode={route.mode ?? mode}
            initialDraft={initial}
            onBack={back}
            onSaveDraft={saveDraft}
            onOpenAi={(nextMode) => openAi(nextMode)}
            onPublish={publish}
            onModeChange={setMode}
            onToast={showToast}
          />
        );
      }
      case 'ai':
        return (
          <AiScreen
            mode={mode}
            memory={memory}
            onModeChange={setMode}
            onBack={back}
            onInsert={insertCandidate}
            onMemoryAdd={addMemory}
            onToast={showToast}
            onMaterials={() => navigate({ name: 'materials' })}
            onIllustration={() => navigate({ name: 'illustration' })}
          />
        );
      case 'materials':
        return <MaterialsScreen onBack={back} onToast={showToast} />;
      case 'illustration':
        return <IllustrationScreen onBack={back} onToast={showToast} />
      case 'me':
        return (
          <MeScreen
            theme={theme}
            settings={settings}
            onThemeToggle={() => setTheme((value) => (value === 'night' ? 'light' : 'night'))}
            onOpenSettings={() => navigate({ name: 'settings' })}
            onOpenComments={() => navigate({ name: 'comments' })}
            onOpenBook={openBook}
            onToast={showToast}
          />
        );
      case 'settings':
        return <SettingsScreen settings={settings} onChange={setSettings} onBack={back} onToast={showToast} />;
      case 'comments':
        return <CommentsScreen onBack={back} onToast={showToast} />;
      default:
        return null;
    }
  }

  return (
    <div className="m-app">
      {renderScreen()}
      {isTabRoute(route.name) ? <BottomNav active={activeTab} onSelect={selectTab} /> : null}
      <Toast message={toast} />
      <span className="m-sr-only">{MOBILE_BOOKS.length} 部作品 · {MODE_LABEL[mode]}</span>
    </div>
  );
}
