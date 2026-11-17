import { Icon } from '../../components/icons/Icon';
import { REVIEWS } from '../../mocks/mock';

const STATUS: Record<string, { text: string; cls: string }> = {
  pending: { text: '待处理', cls: 'badge--review' },
  passed: { text: '通过', cls: 'badge--published' },
  rejected: { text: '驳回', cls: 'badge--removed' },
};

// 审核：裁决队列
export function ReviewView() {
  return (
    <section aria-labelledby="review-h">
      <header className="page-head">
        <h1 id="review-h">审核</h1>
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
