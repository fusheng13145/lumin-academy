import { Icon } from '../../components/icons/Icon';

const STEPS: { title: string; text: string }[] = [
  { title: '配置修订对比', text: '并排展示当前生效配置与待审提案的差异，差异项以文字说明，不引入运营指标。' },
  { title: '提案', text: '创建新的配置修订提案，写明对象、影响范围与原因，提交后进入独立审批。' },
  { title: '独立审批', text: '审批人与提案人分离；通过后仍为「待生效」，需由激活步骤读取生效指针。' },
  { title: '激活与回滚', text: '激活高危操作需二次确认；回滚不复用旧提案，必须创建新的回滚提案并重新独立审批。' },
];

// 运营与配置：配置修订流程
export function OperatorView() {
  return (
    <section aria-labelledby="operator-h">
      <header className="page-head">
        <h1 id="operator-h" tabIndex={-1}>运营与配置</h1>
      </header>

      <div className="toolbar">
        <button className="btn btn--primary" type="button">
          <Icon name="config" size={18} /> 新建配置提案
        </button>
      </div>

      <ul className="draft-list">
        {STEPS.map((step) => (
          <li className="draft-item card" key={step.title}>
            <div className="draft-item__main">
              <h3>{step.title}</h3>
              <p className="draft-item__meta">{step.text}</p>
            </div>
          </li>
        ))}
      </ul>

    </section>
  );
}
