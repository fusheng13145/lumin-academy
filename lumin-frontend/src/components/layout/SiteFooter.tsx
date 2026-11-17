interface SiteFooterProps {
  onGoMine: () => void;
}

// 页脚：单行，仅品牌与入口
export function SiteFooter({ onGoMine }: SiteFooterProps) {
  return (
    <footer className="site-footer">
      <div className="foot-inner">
        <span className="foot-name">启明书院</span>
        <button className="foot-link" type="button" onClick={onGoMine}>设置</button>
      </div>
    </footer>
  );
}
