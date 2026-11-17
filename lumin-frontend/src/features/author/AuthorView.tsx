import { Icon } from '../../components/icons/Icon';
import { DRAFTS } from '../../mocks/mock';

const DRAFT_LABEL: Record<string, { text: string; cls: string }> = {
  EDITABLE: { text: '草稿', cls: 'badge--neutral' },
  SUBMITTED: { text: '已提交', cls: 'badge--review' },
  DISCARDED: { text: '已废弃', cls: 'badge--removed' },
};

// 创作：草稿列表
export function AuthorView() {
  return (
    <section aria-labelledby="author-h">
      <header className="page-head">
        <h1 id="author-h" tabIndex={-1}>创作</h1>
      </header>

      <div className="toolbar">
        <button className="btn btn--primary" type="button">
          <Icon name="author" size={18} /> 新建草稿
        </button>
      </div>

      <ul className="draft-list">
        {DRAFTS.map((d) => {
          const s = DRAFT_LABEL[d.state];
          return (
            <li key={d.id} className="draft-item card">
              <div className="draft-item__main">
                <h3>{d.title}</h3>
                <p className="draft-item__meta">
                  {d.words.toLocaleString()} 字 · 更新于 {d.updatedAt}
                </p>
              </div>
              <span className={'badge ' + s.cls}>{s.text}</span>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
