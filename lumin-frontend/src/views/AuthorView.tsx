import { Icon } from '../components/Icon';
import { DRAFTS } from '../data/mock';

const DRAFT_LABEL: Record<string, { text: string; cls: string }> = {
  EDITABLE: { text: '草稿', cls: 'badge--neutral' },
  SUBMITTED: { text: '已提交', cls: 'badge--review' },
  DISCARDED: { text: '已废弃', cls: 'badge--removed' },
};

// 创作：我的草稿（草稿三态：EDITABLE / SUBMITTED / DISCARDED）
export function AuthorView() {
  return (
    <section aria-labelledby="author-h">
      <header className="page-head">
        <h1 id="author-h">创作</h1>
        <p className="page-head__sub">管理你的草稿与提交状态</p>
      </header>

      <div className="toolbar-row">
        <button className="btn btn--primary">
          <Icon name="author" size={18} /> 新建草稿
        </button>
        <span className="toolbar-row__hint">草稿仅保存在本地示例，提交后将进入机审/人审流程</span>
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
