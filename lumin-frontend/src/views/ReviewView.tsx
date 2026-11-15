import { Icon } from '../components/Icon';
import { REVIEWS } from '../data/mock';

const STATUS: Record<string, { text: string; cls: string }> = {
  pending: { text: '待处理', cls: 'badge--review' },
  passed: { text: '通过', cls: 'badge--published' },
  rejected: { text: '驳回', cls: 'badge--removed' },
};

// 审核：机审/人审队列（仅运营/审核角色可见，按 RBAC 由后端裁决）
export function ReviewView() {
  return (
    <section aria-labelledby="review-h">
      <header className="page-head">
        <h1 id="review-h">审核</h1>
        <p className="page-head__sub">机审命中与人审裁决队列（角色受限视图）</p>
      </header>

      <ul className="review-list">
        {REVIEWS.map((r) => {
          const st = STATUS[r.status];
          return (
            <li key={r.id} className="review-item card">
              <div className="review-item__head">
                <h3>{r.title}</h3>
                <span className={'badge ' + st.cls}>{st.text}</span>
              </div>
              <p className="review-item__meta">
                <Icon name="author" size={14} /> {r.author} · {r.kind}
              </p>
              {r.reason && <p className="review-item__reason">{r.reason}</p>}
            </li>
          );
        })}
      </ul>
    </section>
  );
}
