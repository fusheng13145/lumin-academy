# lumin-frontend · 启明书院 Web 前端

> P0-A 阶段的 Web 端演示应用，已按 Owner 授权（用户本轮指令即编码授权，沿用 09 §4.3）同步到
> `ui-demo/index.html` 定稿基线（3370 行 / 184355 字节 / SHA256 `4ae9198e324e425f4…`，视觉改版「纸页与朱印」）。
> React 19 + TypeScript 6 + Vite 8；状态驱动路由，无外部 CDN / 字体 / 图片 / 网络请求。

## 命令

```text
cd lumin-frontend
npm install          # 首次安装（本机已装好 node_modules，可跳过）
npm run dev          # 开发服务器 localhost:5173（server.host=true）
npm run build        # tsc -b 类型检查 + vite 生产构建 → dist/
npm run preview      # 预览 dist 产物
npm run lint         # ESLint 10（eslint.config.js）
```

## 信息架构

- **5 个工作空间**：书城 / 创作工作区 / 审核工作区 / 运营与配置 / 我的（顶导 + ≤767px 底部标签同一组数据）。
- **读者主路径（状态驱动）**：R01 书城列表 → R02 书详情 → R03 全屏阅读覆盖层；无 URL 路由、无 react-router。
- **K-1 / K-2 已执行**：删除顶部胶囊搜索框、删除书架视图与书架导航 / mock 数据 / 图标。
- **R01**：静态适龄合规横幅（不轮播、无运营数字）+ 四维筛选（年龄带 C0–C4 / 状态 / 字数四档 / 排序三选，「与」组合，结果数经 `#live` 播报）+ 书卡网格；书卡整卡进 R02。
- **R02**：面包屑、书头（CSS 排版书封 / H1 / 适龄与状态徽章 / 标签 / 字数）、最近更新、作者卡、原创简介、卷分组章节目录（可点定位章）、CTA「开始阅读」。
- **R03**：章节元信息「本章字数：X 字 · 更新时间：Y（示例）」、双形态单 DOM 排版工具栏（≥1024px 右侧 sticky 常驻 / <1024px 底部 sheet 唤出）、键盘 ←/→ 切章与边界播报、一次性 tooltip（Esc 或 5 秒）、Esc 收起工具栏并回焦、焦点进入 / Tab 不逸出 / 关闭回焦、4 阅读主题（`rice/green/parchment/night`）。
- **全站页脚**：平台信息 / 治理公示（锚到「我的」）/ 规范与举报 + 底部演示声明；不含任何备案号、证照、电话、邮箱。
- **「我的」**：夜间开关、C0–C4 年龄带演示、治理公示 info/warning/danger 三卡、举报 / 申诉入口。

## 目录

> **结构重构（2026-11-17）**：feature 分层（业界主流 React 目录规范）——`app/` 根组件、
> `features/` 按业务域切分、`components/` 跨域通用件、`services/` API 客户端层、
> `mocks/` 合成夹具、`types/`、`lib/`、`styles/`。**行为与视觉不变**（纯迁移 + 导入更新）。

| 路径 | 内容 |
| --- | --- |
| `index.html` | 入口 HTML（`lang="zh-CN"`，主题色 `#E8453B`） |
| `src/main.tsx` | 挂载入口，按 `tokens → global → components` 顺序引入样式 |
| `src/app/App.tsx` | 根组件：工作空间 + 视图状态路由、`ageBand` / `uiTheme` 全局态、`announce` 去重、全站唯一 `role=status` `#live`（C-38） |
| `src/features/` | 按业务域切分：`reader/`（`CatalogView` R01、`BookDetailView` R02、`ReaderOverlay` R03、`components/BookCard`）、`author/`、`reviewer/`、`operator/`、`account/`（`MineView`） |
| `src/components/` | 跨域通用件：`layout/`（`TopNav` / `BottomTab` / `SiteFooter`）、`icons/Icon`（内联 SVG） |
| `src/lib/demoPolicy.ts` | **唯一判定模块**：`BAND_RANK`、`isAgeMuted()`、`bookBlockedReason()`、`filterBooks()`、`firstReadableBook()`；R01/R02/R03 三处门禁共用 |
| `src/services/` | API 客户端层骨架：`envelope.ts`（契约信封 + Receipt 类型，05 §2/§4.4）、`http.ts`（Bearer/Idempotency-Key/If-Match 协议封装，**未接线**）、`draftService.ts`（mock adapter，联调批次切换为真实调用） |
| `src/mocks/mock.ts` | 12 部原创书目（每部 8–12 章、标签、简介、签名、字数与相对时间）、草稿 / 审核 / 审计夹具；固定基准日期派生，不读系统时钟 |
| `src/types/index.ts` | `AgeBand` / `Book` / `Chapter` / `FilterState` / `WordBucket` / `SortKey` / `ReadThemeId` / `WorkspaceId` / `ViewId` 等展示类型 |
| `src/styles/` | `tokens.css`（精确令牌 + 派生变量 / 明暗 / 4 阅读主题）、`global.css`（基线 / a11y / 布局）、`components.css`（组件 / R02 / 阅读器断点 / 页脚） |

## 与后端的边界

- 本阶段前端**不调用接口**：`src/mocks/mock.ts` 提供确定性夹具，接口契约见仓库根 `contracts/p0.openapi.yaml`（只读）；`src/services/` 为 API 客户端层骨架（信封类型 + 协议封装 + mock adapter），**未接线**，联调批次把各 service 切换为真实调用即可、视图签名不变。后端已提供切片端点：`POST /api/v1/works`、`POST/GET/PUT /api/v1/drafts`（骨架认证替身，OIDC 接入前固定合成主体）。
- 服务端类型后续应从契约生成，不在前端手写业务判定；可见性、适龄、状态机判定一律由后端 `lumin-backend` 领域层决定。
- 年龄带、可见性在前端只是**演示态**：所有判定统一经 `src/lib/demoPolicy.ts`，页面统一标注「仅演示，不替代服务端 AgePolicy / VisibilityPolicy 裁决」；`Book.minAge` 仅作展示，不作为门禁来源。
- 演示年龄带切换导致当前书失效时，自动切到第一部可读作品并经 `#live` 播报。

## 合规与 A11Y

- 不引入外链 CDN / 字体 / 图片；书封用 CSS 排版（书脊 / 竖排书名 / 署名）生成，无外部图片。
- 不实现排行榜、阅读量、收藏数、轮播、付费墙、搜索框、书架；不虚构备案号 / 证照 / 电话 / 邮箱。
- 无障碍：跳转正文链接、视图切换焦点落 `h1`、`:focus-visible` 3px、`prefers-reduced-motion`、单一 `role=status` 播报区（C-38）、工具栏与阅读层焦点管理。
- 视觉基线「纸页与朱印」：基础令牌精确值不变（`--accent #E8453B` / `--radius-card 16px` / `--sp-*` / 120ms），新增宋体栈 `--font-title/--font-read`、`--text-action #B23A31`、`--hairline/-strong`、`--paper-soft`、`--cover-ink` 与更轻 `--shadow-card`；朱红只作标记色。

## 已知差异 / 未同步项（登记不改）

| 编号 | 差异 | 原因 |
| --- | --- | --- |
| S4 / S5 / S11 | 保留既有 `--sp-*` / `--radius-pill` / `data-ui-theme` 命名；阅读主题属性挂在 `:root[data-read-theme]`，demo 则挂在 `.reader-paper[data-read-theme]`；demo 用 `--space-*` / `--radius-control` / `data-theme` + `data-contrast` | 按裁决不做纯命名重构；属性名与作用域差异只登记不改；前端无 `data-contrast` 高对比度开关 |
| 四态面板 | demo 的 11 视图 × 4 态（44 面板）是**评审辅助机制**，前端未移植 | 主理人明确「不新增任何视图的加载/空/错误/成功四态切换器」 |
| A / M / O 子视图 | `AuthorView` / `ReviewView` / `OperatorView` 为最小静态视图，未拆 A01/A03/A04、M01/M02、O02 子页，也未移植 O02 的对比/审批/激活/回滚四态面板 | 实施范围明确排除 |
| 书目与章节数据 | 前端 12 部原创合成书目（书名 / 作者 / 简介 / 签名）与 demo 的 `BOOKS` 不是同一组；章节名由确定性名称池派生 | 二者均为原创合成、均覆盖五年龄带 × 四字数档，仅内容不同；不影响筛选门禁口径 |
| 演示年龄带切换（R02） | demo 在 R02 就地重渲染，让 CTA 与目录显示为禁用态；前端在 R02 切换到第一部可读作品，R03 则两者都会切换 | 任务文字「当前视图书失效则切到第一部可读作品」与 demo 行为存在口径歧义；R02 禁用态仍可由「R03 切换后返回 R02」路径复现，登记待主理人裁决 |
| `announce` 下传范围 | `announce` 仅下传给需要播报的 `CatalogView` / `ReaderOverlay`；其余视图无播报点，不接该 prop | 避免 `noUnusedParameters`；全站仍只有 App 一处 `role="status"`，文本去重集中在 App |
| 页脚与治理 | 页脚三列已同步；治理公示锚定 MineView `#me-governance` | 同步范围 |
| `docs/10` 事实错误 | `docs/10` 多处写「74 个 `operationId`」，只读契约实测 **63**——**✅ 已修复（2026-11-15 Owner 授权）**：`docs/10` 已改为 63，`validate_contract.py` 与 `ContractSkeletonTest` 同步；契约本身从未改动 | 历史登记保留备查 |
| 版本口径偏移 | `docs/10` §0.2 / docs/02 §2 原写「TS 7.0.2 / Vite 8.3.8 / Spring Boot 4.1.2」——**✅ 已修复（2026-11-15 Owner 授权）**：docs/02 新增 §2.2 对齐表，实际以 `lumin-frontend/package.json`（React `^19.3.1` / TypeScript `~6.0.3` / Vite `^8.3.6`，本机装 8.3.8）与 `lumin-backend/pom.xml`（Spring Boot 4.0.9）为准 | P0-A 领域内核不引入 Spring 运行时；模块级 Web 运行时见 `lumin-backend/README.md` |

## 构建工具链约束

- `tsconfig.json` 为 `files: [] + references(app/node)` 的 project references；`tsc -b` 依赖这两个引用，新增 `.tsx` 放在 `src/` 下自动纳入，**无需改配置**。
- TS 6：`verbatimModuleSyntax` → 类型导入必须 `import type`；`erasableSyntaxOnly` → 禁 enum / namespace / 参数属性；`noUnusedLocals/noUnusedParameters` → 删除入口后必须同步删净未用导入；`moduleResolution: bundler` → 不写 `.js` 后缀。
- ESLint 10 flat config + `react-hooks` + `react-refresh`：新增视图文件只导出组件。
- Vite 8 `build.sourcemap: true`、`outDir: dist`；`npm run build` 会写 `dist/`（已被 .gitignore 忽略）。
