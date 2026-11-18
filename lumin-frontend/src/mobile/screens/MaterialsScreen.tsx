import { useEffect, useState } from 'react';
import { Sheet, TopBar } from '../components/ui';
import {
  IconBook,
  IconPlus,
  IconQuote,
  IconSearch,
  IconSparkles,
} from '../components/Icons';
import { readStored, writeStored } from '../services/storage';

interface Material {
  id: string;
  kind: '场景' | '人物' | '物件' | '地名';
  title: string;
  detail: string;
  tags: string[];
  used: number;
}

const MATERIALS: Material[] = [
  {
    id: 'mat1',
    kind: '场景',
    title: '封冻前的码头',
    detail: '最后一班船靠岸，灯未亮；缆绳收紧的声音比预报早十一分钟。',
    tags: ['海边', '冬季', '开场'],
    used: 3,
  },
  {
    id: 'mat2',
    kind: '物件',
    title: '被墨迹盖住的值班簿',
    detail: '旧纸、蓝黑墨水、纸背的铅笔痕；一行记录被反复涂改。',
    tags: ['线索', '父辈', '档案'],
    used: 2,
  },
  {
    id: 'mat3',
    kind: '人物',
    title: '不说话的值夜人',
    detail: '交接班时不解释，只把钥匙和记录本按顺序放好。',
    tags: ['群像', '克制', '守夜'],
    used: 1,
  },
  {
    id: 'mat4',
    kind: '地名',
    title: '堤上的第三盏灯',
    detail: '三座停用灯塔中最旧的一座；灯罩擦得很干净，却从未点亮。',
    tags: ['灯塔', '伏笔', '父亲'],
    used: 4,
  },
];

const INITIAL_NOTES = [
  {
    id: 'note1',
    text: '“所有反常都要写进当日的值班记录。”——可以用来做本章的规矩，也可以做结尾的回声。',
    tag: '句子',
    time: '昨天 23:10',
  },
  {
    id: 'note2',
    text: '纸袋上的名字不要正面写出，先让读者看见封口的胶带，再让林砚认出笔迹。',
    tag: '情节',
    time: '昨天 22:40',
  },
  {
    id: 'note3',
    text: '海雾不是氛围，是遮挡信息的东西；每一次雾散都应该让一个事实露出来。',
    tag: '世界观',
    time: '11 月 16 日',
  },
];

interface Note {
  id: string;
  text: string;
  tag: string;
  time: string;
}

interface MaterialsScreenProps {
  onBack: () => void;
  onToast: (message: string) => void;
}

export function MaterialsScreen({ onBack, onToast }: MaterialsScreenProps) {
  const [tab, setTab] = useState<'materials' | 'notes'>('materials');
  const [query, setQuery] = useState('');
  const [notes, setNotes] = useState<Note[]>(INITIAL_NOTES);
  const [noteOpen, setNoteOpen] = useState(false);
  const [noteText, setNoteText] = useState('');
  const [activeMaterial, setActiveMaterial] = useState<Material | null>(null);

  useEffect(() => {
    void readStored<Note[]>('lumin.mobile.notes.v1', INITIAL_NOTES).then(setNotes);
  }, []);

  useEffect(() => {
    void writeStored('lumin.mobile.notes.v1', notes);
  }, [notes]);

  const filtered = MATERIALS.filter((material) =>
    `${material.title} ${material.detail} ${material.tags.join(' ')}`.toLowerCase().includes(query.trim().toLowerCase())
  );

  function addNote(): void {
    if (!noteText.trim()) {
      return;
    }
    const next: Note = {
      id: `note-${Date.now()}`,
      text: noteText.trim(),
      tag: '灵感',
      time: '刚刚',
    };
    setNotes((items) => [next, ...items]);
    setNoteText('');
    setNoteOpen(false);
    onToast('灵感已收进素材库');
  }

  return (
    <div className="m-page m-enter">
      <TopBar title="素材库与灵感" subtitle="MATERIALS" onBack={onBack} />

      <div style={{ display: 'flex', gap: 6, marginTop: 10 }}>
        <button
          className="m-chip"
          type="button"
          aria-pressed={tab === 'materials'}
          onClick={() => setTab('materials')}
        >
          素材库 · {MATERIALS.length}
        </button>
        <button
          className="m-chip"
          type="button"
          aria-pressed={tab === 'notes'}
          onClick={() => setTab('notes')}
        >
          灵感 · {notes.length}
        </button>
        <button className="m-chip" type="button" onClick={() => setNoteOpen(true)} style={{ marginLeft: 'auto' }}>
          <IconPlus size={14} />
          记一条
        </button>
      </div>

      {tab === 'materials' ? (
        <>
          <div className="m-search" style={{ marginTop: 14 }}>
            <IconSearch />
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜场景、人物、物件或地名"
              aria-label="搜索素材"
            />
          </div>

          <section className="m-section">
            <div className="m-section-head">
              <div className="m-section-head__main">
                <p className="m-eyebrow">MATERIAL LIBRARY</p>
                <h2 className="m-h2">可取材的碎片</h2>
              </div>
              <span className="m-caption">{filtered.length} 条</span>
            </div>
            {filtered.map((material) => (
              <button
                key={material.id}
                className="m-material"
                type="button"
                onClick={() => setActiveMaterial(material)}
              >
                <div className="m-material__head">
                  <span className="m-badge m-badge--accent">{material.kind}</span>
                  <span className="m-caption">已引用 {material.used} 次</span>
                </div>
                <p className="m-h3" style={{ marginTop: 7 }}>{material.title}</p>
                <p className="m-caption" style={{ marginTop: 4 }}>{material.detail}</p>
                <div className="m-editorial__meta" style={{ marginTop: 8 }}>
                  {material.tags.map((tag) => (
                    <span key={tag} className="m-badge">{tag}</span>
                  ))}
                </div>
              </button>
            ))}
          </section>
        </>
      ) : (
        <section className="m-section">
          <div className="m-section-head">
            <div className="m-section-head__main">
              <p className="m-eyebrow">INSPIRATION</p>
              <h2 className="m-h2">灵感碎片</h2>
            </div>
            <button className="m-section-head__action" type="button" onClick={() => setNoteOpen(true)}>
              新建
            </button>
          </div>
          {notes.map((note) => (
            <article className="m-note" key={note.id}>
              <div className="m-note__rail">
                <IconQuote size={16} />
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <p className="m-note__text">{note.text}</p>
                <p className="m-caption" style={{ marginTop: 7 }}>
                  {note.tag} · {note.time}
                </p>
              </div>
            </article>
          ))}
        </section>
      )}

      <div className="m-card m-card--pad" style={{ marginTop: 22, display: 'flex', alignItems: 'center', gap: 12 }}>
        <div style={{ width: 38, height: 38, borderRadius: 10, background: 'var(--m-accent-soft)', color: 'var(--m-accent-deep)', display: 'grid', placeItems: 'center', flex: 'none' }}>
          <IconBook size={18} />
        </div>
        <div style={{ flex: 1 }}>
          <p className="m-h3">取材会留下引用记录</p>
          <p className="m-caption" style={{ marginTop: 3 }}>
            素材被写入正文后，仍保留来源与引用次数，便于回查。
          </p>
        </div>
      </div>

      <Sheet
        open={Boolean(activeMaterial)}
        onClose={() => setActiveMaterial(null)}
        title={activeMaterial ? activeMaterial.title : '素材'}
      >
        {activeMaterial ? (
          <>
            <p className="m-body">{activeMaterial.detail}</p>
            <div className="m-editorial__meta" style={{ marginTop: 12 }}>
              {activeMaterial.tags.map((tag) => (
                <span key={tag} className="m-badge">{tag}</span>
              ))}
            </div>
            <button
              className="m-btn m-btn--primary m-btn--block"
              type="button"
              onClick={() => {
                onToast('已引用到《晚潮记》第 13 章草稿');
                setActiveMaterial(null);
              }}
              style={{ marginTop: 16 }}
            >
              <IconSparkles size={16} />
              引用到当前章节
            </button>
          </>
        ) : null}
      </Sheet>

      <Sheet open={noteOpen} onClose={() => setNoteOpen(false)} title="记一条灵感">
        <textarea
          className="m-ai-prompt"
          value={noteText}
          onChange={(event) => setNoteText(event.target.value)}
          placeholder="一句话、一个画面、一个还没想好放哪儿的细节…"
          aria-label="灵感内容"
        />
        <button className="m-btn m-btn--primary m-btn--block" type="button" onClick={addNote} style={{ marginTop: 12 }}>
          <IconPlus size={16} />
          收进灵感库
        </button>
      </Sheet>
    </div>
  );
}
