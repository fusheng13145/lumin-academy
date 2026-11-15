import type {
  AgeBand,
  Book,
  BookState,
  FilterState,
  SortKey,
  WordBucket,
} from '../types';

// 演示态统一口径：所有适龄门禁、筛选与排序只在本模块实现。
// 组件内禁止重复写 BAND_RANK 比较（QA 第 2 轮 E-FAIL 的教训）。

export const BAND_RANK: Record<AgeBand, number> = {
  C0: 0,
  C1: 1,
  C2: 2,
  C3: 3,
  C4: 4,
};

export const BAND_LABEL: Record<AgeBand, string> = {
  C0: '学龄前 C0',
  C1: '小学 C1',
  C2: '初中 C2',
  C3: '高中 C3',
  C4: '成人 C4',
};

export const STATE_LABEL: Record<BookState, string> = {
  published: '已发布',
  inreview: '审核中',
  removed: '已下架',
};

export const WORD_BUCKET_LABEL: Record<WordBucket, string> = {
  all: '全部',
  lt10: '10 万以下',
  w10_30: '10–30 万',
  w30_60: '30–60 万',
  gt60: '60 万以上',
};

export const SORT_LABEL: Record<SortKey, string> = {
  recent: '最近更新',
  title: '书名字序',
  words: '字数最多',
};

export const MUTED_HINT =
  '超出当前演示年龄带，暂不可读。仅演示边界，不代表真实年龄政策。';
export const REMOVED_HINT = '内容暂不可访问';
export const POLICY_HINT =
  '年龄与可见性判定仅作演示，不替代服务端 AgePolicy / VisibilityPolicy 裁决。';

const WORD_BUCKETS: Record<
  Exclude<WordBucket, 'all'>,
  (words: number) => boolean
> = {
  lt10: (words) => words < 10,
  w10_30: (words) => words >= 10 && words < 30,
  w30_60: (words) => words >= 30 && words < 60,
  gt60: (words) => words >= 60,
};

/** 唯一适龄门禁：超出演示年龄带即灰显且不可访问 */
export function isAgeMuted(book: Book, ageBand: AgeBand): boolean {
  return BAND_RANK[book.band] > BAND_RANK[ageBand];
}

export type BlockedReason = 'removed' | 'muted' | null;

/** 统一返回拦截原因，供书卡 / R02 / R03 三处共用 */
export function bookBlockedReason(
  book: Book,
  ageBand: AgeBand
): BlockedReason {
  if (book.state === 'removed') {
    return 'removed';
  }
  if (isAgeMuted(book, ageBand)) {
    return 'muted';
  }
  return null;
}

export function bookBlockedHint(book: Book, ageBand: AgeBand): string {
  const reason = bookBlockedReason(book, ageBand);
  if (reason === 'removed') {
    return REMOVED_HINT;
  }
  if (reason === 'muted') {
    return MUTED_HINT;
  }
  return '';
}

/** 四维「与」组合筛选 + 单选排序 */
export function filterBooks(books: Book[], filters: FilterState): Book[] {
  const list = books.filter((book) => {
    if (filters.age !== 'all' && book.band !== filters.age) {
      return false;
    }
    if (filters.status !== 'all' && book.state !== filters.status) {
      return false;
    }
    if (filters.words !== 'all' && !WORD_BUCKETS[filters.words](book.words)) {
      return false;
    }
    return true;
  });

  if (filters.sort === 'recent') {
    return list.slice().sort((a, b) => a.hoursAgo - b.hoursAgo);
  }
  if (filters.sort === 'title') {
    return list.slice().sort((a, b) =>
      a.title.localeCompare(b.title, 'zh-Hans-CN')
    );
  }
  return list.slice().sort((a, b) => b.words - a.words);
}

/** 当前演示年龄带下第一部可读作品（未下架且适龄） */
export function firstReadableBook(
  books: Book[],
  ageBand: AgeBand
): Book | null {
  for (const book of books) {
    if (bookBlockedReason(book, ageBand) === null) {
      return book;
    }
  }
  return null;
}
