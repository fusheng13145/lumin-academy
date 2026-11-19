import { useEffect, useState } from 'react';
import { BAND_LABEL } from '../../lib/demoPolicy';
import { Icon } from '../../components/icons/Icon';
import { notificationService, type NotificationItem } from '../../services/notificationService';
import { bookshelfService, type BookshelfItem, type ReadingHistoryItem } from '../../services/bookshelfService';
import { subscriptionService, type SubscriptionItem } from '../../services/subscriptionService';
import type { AgeBand } from '../../types';

interface MineViewProps {
  uiTheme: 'light' | 'night';
  onToggleUiTheme: () => void;
  ageBand: AgeBand;
  onAgeBandChange: (band: AgeBand) => void;
}

const AGE_BANDS: AgeBand[] = ['C0', 'C1', 'C2', 'C3', 'C4'];

// 我的：界面偏好与年龄带（演示评审开关）
export function MineView({
  uiTheme,
  onToggleUiTheme,
  ageBand,
  onAgeBandChange,
}: MineViewProps) {
  return (
    <section aria-labelledby="mine-h">
      <header className="page-head">
        <h1 id="mine-h" tabIndex={-1}>我的</h1>
      </header>

      <div className="card setting-block">
        <div className="setting-row">
          <span>夜间模式</span>
          <button
            className={'switch' + (uiTheme === 'night' ? ' is-on' : '')}
            role="switch"
            aria-checked={uiTheme === 'night'}
            onClick={onToggleUiTheme}
          >
            <span className="switch__thumb">
              <Icon name={uiTheme === 'night' ? 'moon' : 'sun'} size={14} />
            </span>
          </button>
        </div>

        <div className="setting-row">
          <span>年龄带</span>
          <select
            className="select"
            value={ageBand}
            onChange={(event) => onAgeBandChange(event.target.value as AgeBand)}
            aria-label="选择年龄带"
          >
            {AGE_BANDS.map((band) => (
              <option key={band} value={band}>{BAND_LABEL[band]}</option>
            ))}
          </select>
        </div>
      </div>

      <div className="card setting-block">
        <h2 className="setting-block__title">
          <Icon name="bell" size={18} /> 通知
        </h2>
        <NotificationList />
      </div>

      <div className="card setting-block">
        <h2 className="setting-block__title">
          <Icon name="catalog" size={18} /> 书架与历史
        </h2>
        <BookshelfList />
      </div>

      <div className="card setting-block">
        <h2 className="setting-block__title">
          <Icon name="bell" size={18} /> 追更订阅
        </h2>
        <SubscriptionList />
      </div>
    </section>
  );
}

function NotificationList() {
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const list = await notificationService.listNotifications();
        if (alive) {
          setItems(list);
        }
      } catch (e) {
        if (alive) {
          setError(e instanceof Error ? e.message : '加载失败');
        }
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  if (error) {
    return <p className="muted-note">{error}</p>;
  }
  if (items.length === 0) {
    return <p className="muted-note">暂无通知</p>;
  }
  return (
    <ul className="notice-list">
      {items.map((n) => (
        <li key={n.id} className="notice notice--info">
          <span className="badge badge--neutral">{n.category}</span>
          <p>{n.title}</p>
        </li>
      ))}
    </ul>
  );
}

function BookshelfList() {
  const [bookmarks, setBookmarks] = useState<BookshelfItem[] | null>(null);
  const [history, setHistory] = useState<ReadingHistoryItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const [b, h] = await Promise.all([
          bookshelfService.listBookmarks(),
          bookshelfService.listReadingHistory(),
        ]);
        if (alive) {
          setBookmarks(b);
          setHistory(h);
        }
      } catch (e) {
        if (alive) {
          setError(e instanceof Error ? e.message : '加载失败');
        }
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  const remove = async (workId: string): Promise<void> => {
    try {
      await bookshelfService.removeBookmark(workId);
      setBookmarks((prev) => (prev ?? []).filter((item) => item.workId !== workId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '移除失败');
    }
  };

  if (error) {
    return <p className="muted-note">{error}</p>;
  }
  const bookmarksEmpty = bookmarks !== null && bookmarks.length === 0;
  const historyEmpty = history !== null && history.length === 0;
  return (
    <div>
      {bookmarksEmpty ? <p className="muted-note">书架空空如也</p> : null}
      {bookmarks === null ? null : (
        <ul className="notice-list">
          {bookmarks.map((item) => (
            <li key={item.workId} className="notice notice--info">
              <span className={'badge ' + (item.readable ? 'badge--info' : 'badge--removed')}>
                {item.readable ? '可读' : '不可读'}
              </span>
              <p>《{item.title || item.workId}》 {item.author}</p>
              <button className="btn btn--sm" type="button"
                onClick={() => void remove(item.workId)}>
                移出书架
              </button>
            </li>
          ))}
        </ul>
      )}
      {historyEmpty ? null : (
        <ul className="notice-list" style={{ marginTop: 'var(--sp-4)' }}>
          {history === null ? null : history.map((item) => (
            <li key={item.chapterId} className="notice notice--info">
              <span className="badge badge--neutral">
                {item.readable ? `第 ${item.sequenceNo} 章 · ${item.positionBasisPoints / 100}%` : '不可读'}
              </span>
              <p>{item.workTitle} · {item.chapterTitle || '第一章'}</p>
            </li>
          ))}
        </ul>
      )}
      {bookmarksEmpty && historyEmpty ? <p className="muted-note">暂无阅读历史</p> : null}
    </div>
  );
}

function SubscriptionList() {
  const [items, setItems] = useState<SubscriptionItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const list = await subscriptionService.listSubscriptions();
        if (alive) {
          setItems(list);
        }
      } catch (e) {
        if (alive) {
          setError(e instanceof Error ? e.message : '加载失败');
        }
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  const remove = async (workId: string): Promise<void> => {
    try {
      await subscriptionService.unsubscribe(workId);
      setItems((prev) => (prev ?? []).filter((item) => item.workId !== workId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '退订失败');
    }
  };

  if (error) {
    return <p className="muted-note">{error}</p>;
  }
  if (items === null) {
    return null;
  }
  if (items.length === 0) {
    return <p className="muted-note">暂无追更——收藏≠订阅，订阅后在作品更新时聚合提醒</p>;
  }
  const removed = (state: string) => state === 'REMOVED';
  return (
    <ul className="notice-list">
      {items.map((item) => (
        <li key={item.workId} className="notice notice--info">
          <span className={'badge ' + (removed(item.workState) ? 'badge--removed' : 'badge--info')}>
            {removed(item.workState) ? '已下架' : item.pushEnabled ? '推送开' : '站内提醒'}
          </span>
          <p>
            《{item.title || item.workId}》 {item.author}
            {item.quietStart && item.quietEnd
              ? ` · 免打扰 ${item.quietStart}–${item.quietEnd}（${item.timezoneId}）`
              : ''}
          </p>
          <button className="btn btn--sm" type="button"
            onClick={() => void remove(item.workId)}>
            退订
          </button>
        </li>
      ))}
    </ul>
  );
}