interface SiteFooterProps {
  onGoMine: () => void;
}

// 全站页脚：平台信息 / 治理公示（锚到「我的」）/ 规范与举报
// 合规：不写任何备案号、证照编号、电话、邮箱，只写站内入口与演示声明。
export function SiteFooter({ onGoMine }: SiteFooterProps) {
  return (
    <footer className="site-footer">
      <div className="foot-inner">
        <div className="foot-col">
          <h2>平台信息</h2>
          <p className="foot-name">启明书院</p>
          <p>
            面向青少年的适龄阅读平台（设计评审演示）。每一部作品都经过年龄分级审核后向适龄读者开放。
          </p>
        </div>
        <div className="foot-col">
          <h2>治理公示</h2>
          <button className="foot-link" type="button" onClick={onGoMine}>处置通知</button>
          <button className="foot-link" type="button" onClick={onGoMine}>审计公示</button>
          <button className="foot-link" type="button" onClick={onGoMine}>适龄分级说明</button>
        </div>
        <div className="foot-col">
          <h2>规范与举报</h2>
          <span className="foot-line">用户协议</span>
          <span className="foot-line">隐私政策</span>
          <span className="foot-line">未成年人保护</span>
          <span className="foot-line">举报渠道：站内举报入口见我的页</span>
          <button className="foot-link" type="button" onClick={onGoMine}>前往我的页</button>
        </div>
      </div>
      <p className="foot-bottom">
        本页面为设计评审演示，平台证照与备案信息以正式上线为准。
      </p>
    </footer>
  );
}
