import { useCallback, useEffect, useRef, useState } from 'react';
import { SplashScreen } from '@capacitor/splash-screen';
import type { MemoryFact, ReadingProgress } from './types';
import { STUDIO_WORKS } from './data/studio';
import { MOBILE_BOOKS, findBook } from './data/library';
import { MODE_LABEL } from '../lib/creationMode';
import { BottomNav, Toast } from './components/ui';
import { isTabRoute } from './navigation';
import { useMobileWorkspace } from './model/useMobileWorkspace';
import { useMobileNavigation } from './model/useMobileNavigation';
import { useDraftWorkflow } from './model/useDraftWorkflow';
import { buildDraft } from './model/drafts';
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

export function MobileApp() {
  const workspace = useMobileWorkspace();
  const { setProgressList } = workspace;
  const { route, setRoute, navigate, back, selectTab } = useMobileNavigation();
  const [toast, setToast] = useState<string | null>(null);
  const toastTimer = useRef<number | null>(null);

  const showToast = useCallback((message: string) => {
    setToast(message);
    if (toastTimer.current) {
      window.clearTimeout(toastTimer.current);
    }
    toastTimer.current = window.setTimeout(() => setToast(null), 2400);
  }, []);

  const draftWorkflow = useDraftWorkflow({
    draft: workspace.draft,
    mode: workspace.mode,
    setDraft: workspace.setDraft,
    setMode: workspace.setMode,
    navigate,
    showToast,
  });

  useEffect(() => {
    document.documentElement.dataset.mApp = 'true';
    return () => {
      delete document.documentElement.dataset.mApp;
    };
  }, []);

  useEffect(() => {
    document.documentElement.dataset.mTheme = workspace.theme;
    return () => {
      delete document.documentElement.dataset.mTheme;
    };
  }, [workspace.theme]);

  useEffect(() => {
    void SplashScreen.hide().catch(() => undefined);
  }, []);

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
    [setProgressList]
  );

  const addMemory = (facts: MemoryFact[]): void => {
    workspace.setMemory((items) => {
      const existingLabels = new Set(items.map((item) => item.label));
      return [...facts.filter((fact) => !existingLabels.has(fact.label)), ...items];
    });
  };

  const { editorDraft, openEditor, saveDraft, openAi, insertCandidate, publish } = draftWorkflow;

  const latestProgress = workspace.progressList[0] ?? null;
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
            settings={workspace.settings}
            onBack={back}
            onChapterChange={(chapterNo) => setRoute({ ...route, chapterNo })}
            onSettingsChange={workspace.setSettings}
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
        const initial = editorDraft ?? (workspace.draft && workspace.draft.workId === work.id ? workspace.draft : null) ?? buildDraft(work.id, route.chapterId, workspace.mode);
        return (
          <EditorScreen
            key={`${work.id}-${route.chapterId ?? 'new'}-${route.mode ?? workspace.mode}`}
            work={work}
            chapter={chapter}
            mode={route.mode ?? workspace.mode}
            initialDraft={initial}
            onBack={back}
            onSaveDraft={saveDraft}
            onOpenAi={(nextMode) => openAi(nextMode)}
            onPublish={publish}
            onModeChange={workspace.setMode}
            onToast={showToast}
          />
        );
      }
      case 'ai':
        return (
          <AiScreen
            mode={workspace.mode}
            memory={workspace.memory}
            onModeChange={workspace.setMode}
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
            theme={workspace.theme}
            settings={workspace.settings}
            onThemeToggle={() => workspace.setTheme((value) => (value === 'night' ? 'light' : 'night'))}
            onOpenSettings={() => navigate({ name: 'settings' })}
            onOpenComments={() => navigate({ name: 'comments' })}
            onOpenBook={openBook}
            onToast={showToast}
          />
        );
      case 'settings':
        return <SettingsScreen settings={workspace.settings} onChange={workspace.setSettings} onBack={back} onToast={showToast} />;
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
      <span className="m-sr-only">{MOBILE_BOOKS.length} 部作品 · {MODE_LABEL[workspace.mode]}</span>
    </div>
  );
}
