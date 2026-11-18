import { useState } from 'react';
import { Sheet, TopBar } from '../components/ui';
import { IconMessage, IconSend } from '../components/Icons';

interface CommentItem {
  id: string;
  name: string;
  time: string;
  book: string;
  content: string;
  reply?: string;
}

const INITIAL: CommentItem[] = [
  {
    id: 'c1',
    name: '潮生',
    time: '18 分钟前',
    book: '《晚潮记》第 12 章',
    content: '第三盏灯终于亮了。前面埋了那么多章，这一下没有用很大的力气去写，反而更难受。谢谢作者没有让它变成一个答案。',
  },
  {
    id: 'c2',
    name: '南枝',
    time: '2 小时前',
    book: '《晚潮记》第 11 章',
    content: '林砚把纸袋收进抽屉那一段，我反复看了三遍。她什么都没说，但读者已经知道她知道了。',
  },
  {
    id: 'c3',
    name: '未寄出的信',
    time: '昨天',
    book: '《雾港手记》草稿',
    content: '新作什么时候开？想看那个总把邮票贴倒的人后来去了哪里。',
  },
];

export function CommentsScreen({ onBack, onToast }: { onBack: () => void; onToast: (message: string) => void }) {
  const [comments, setComments] = useState<CommentItem[]>(INITIAL);
  const [active, setActive] = useState<CommentItem | null>(null);
  const [reply, setReply] = useState('');

  function sendReply(): void {
    if (!active || !reply.trim()) {
      return;
    }
    setComments((items) =>
      items.map((item) => (item.id === active.id ? { ...item, reply: reply.trim() } : item))
    );
    setActive(null);
    setReply('');
    onToast('回复已发送');
  }

  return (
    <div className="m-page m-enter">
      <TopBar title="评论管理" subtitle="COMMENTS" onBack={onBack} />

      <section className="m-section" style={{ marginTop: 8 }}>
        <div className="m-card m-card--pad" style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <div style={{ width: 36, height: 36, borderRadius: 10, background: 'var(--m-accent-soft)', color: 'var(--m-accent-deep)', display: 'grid', placeItems: 'center', flex: 'none' }}>
            <IconMessage size={19} />
          </div>
          <div style={{ flex: 1 }}>
            <p className="m-h3">{comments.length} 条待处理</p>
            <p className="m-caption" style={{ marginTop: 3 }}>优先回复长评与更新章节下的讨论。</p>
          </div>
        </div>
      </section>

      <section className="m-section">
        {comments.map((comment) => (
          <article className="m-comment" key={comment.id}>
            <div className="m-comment__head">
              <div className="m-avatar" style={{ width: 38, height: 38, fontSize: 16 }}>
                {comment.name.slice(0, 1)}
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <p className="m-comment__name">{comment.name}</p>
                <p className="m-caption">
                  {comment.time} · {comment.book}
                </p>
              </div>
              <button
                className="m-btn m-btn--sm m-btn--soft"
                type="button"
                onClick={() => {
                  setActive(comment);
                  setReply(comment.reply ?? '');
                }}
              >
                回复
              </button>
            </div>
            <p className="m-comment__body">{comment.content}</p>
            {comment.reply ? (
              <div className="m-comment__reply">
                <span className="m-eyebrow">作者回复</span>
                <p>{comment.reply}</p>
              </div>
            ) : null}
          </article>
        ))}
      </section>

      <Sheet open={Boolean(active)} onClose={() => setActive(null)} title={active ? `回复 ${active.name}` : '回复'}>
        {active ? <p className="m-caption" style={{ marginBottom: 10 }}>{active.content}</p> : null}
        <textarea
          className="m-ai-prompt"
          value={reply}
          onChange={(event) => setReply(event.target.value)}
          placeholder="写下你的回复…"
          aria-label="回复内容"
        />
        <button className="m-btn m-btn--primary m-btn--block" type="button" onClick={sendReply} style={{ marginTop: 12 }}>
          <IconSend size={16} />
          发送回复
        </button>
      </Sheet>
    </div>
  );
}
