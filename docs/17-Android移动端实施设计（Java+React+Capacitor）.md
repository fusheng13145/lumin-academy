# 启明书院 Android 移动端实施设计（Java + React + Capacitor）

## 文档信息

| 项 | 内容 |
| --- | --- |
| 文档编号 | 17 |
| 版本 | v1.0（Android 实施版） |
| 编写日期 | 2026-11-18 |
| 状态 | 设计 + 当前工程实现 + Android 构建验证；真机验收记录见 §16 |
| 适用对象 | 移动端研发、Java 后端、React 前端、QA、设计与合规评审 |
| 上游产品设计 | `15-作者工作台详细设计.md`、`16-移动端APP设计方案.md`（产品/页面/三模式/数据/接口部分继续有效） |
| 技术基线 | Java 21 + Spring Boot 4.0.9 + Maven；React 19 + TypeScript 6 + Vite 8；Capacitor 8；Android SDK 36 |
| 机器契约 | `contracts/p0.openapi.yaml`（只读，当前 63 个 operationId） |
| 代码位置 | `lumin-frontend/src/mobile/`、`lumin-frontend/android/`、`lumin-backend/` |

> **2026-11-18 后端修订**：`lumin-backend` 已拆为 `domain/application/infrastructure/ai/bootstrap` 五模块，AI 独立为 `lumin-ai`（默认 Ollama `deepseek-r1:8b`，可切 DeepSeek API）；本文的移动端 AI 流程仍为本地演示数据，接真实接口时按 `docs/18` 的 `/api/v1/ai/*` 契约替换 `src/mobile/services/aiDemo.ts`。

### 技术路线声明

本项目从 2026-11-18 起采用 **Java + React 全栈、Android 优先、Capacitor Android 壳** 路线：

- 业务代码以 React + TypeScript 为主，Web 与 Android 共用一套页面与交互内核；
- Android 通过 Capacitor 承载本地构建产物，不加载远程 URL；
- iOS 只保留 `capacitor.config.ts` 中的 `ios` 配置与架构边界，不生成 iOS 工程、不做实现、不做签名；
- 当前项目用于简历展示，但代码质量、UI 与完成度按可发布标准执行；
- 本文不声称已满足 `docs/02`、`docs/09` 的原生双端门禁；原生方案已在 `docs/16` 中归档为历史评估，技术路线变更需在后续基线文档中单独确认。

---

## 1. 一页结论

| 编号 | 结论 | 落地方式 |
| --- | --- | --- |
| D-01 | 一个 App、四个一级入口：书城、创作、AI、我的 | 底部 Tab；审核/运营留在 Web，不挤进移动端 |
| D-02 | Android 优先，iOS 仅预留 | Capacitor `android/` 已生成；`ios` 配置只作为未来接口 |
| D-03 | 一套 React 代码，双构建目标 | `vite --mode mobile` 输出 `dist-mobile`；Web 仍输出 `dist` |
| D-04 | 三种创作模式共用同一编辑器内核 | 传统 / AI 辅助 / 纯 AI 只在 AI 介入、候选呈现、保存屏障上分叉 |
| D-05 | 纯 AI 是“任务控制台”，不是本地 Agent 运行时 | workflow + multi_agent 全部在服务端；移动端只发起、查看、确认 |
| D-06 | 长期记忆是“版本化事实”，不是无限上下文 | 人物、世界观、情节、伏笔按条目持久化；生成前检索最小必要子集 |
| D-07 | 所有 AI 结果必须先成为候选，再由作者确认 | 不自动改正文、不自动发布、不自动覆盖已锁定记忆 |
| D-08 | 版权归作者，AI 来源 append-only | 草稿/版本记录 AI 参与方式；平台不因提供工具主张正文著作权 |
| D-09 | 数据看板先做作者可理解的指标 | 阅读、收藏、收益、热度、趋势；日/周/月筛选；演示数据明确标注 |
| D-10 | P0 先做可运行闭环，再补真实服务 | 本地演示库可替换为现有 Java API；真实 LLM/图像模型作为 P1 |

---

## 2. 技术选型与基线变更

### 2.1 选型对照

| 维度 | 原生双端（历史方案） | Java + React + Capacitor（当前） |
| --- | --- | --- |
| Android 实现 | Kotlin + Jetpack Compose | React + TypeScript + Capacitor Android WebView |
| iOS 实现 | Swift + SwiftUI | 仅预留 `capacitor.config.ts`，不实现 |
| 代码复用 | 低，双端各写一套 | 高，Web 与 Android 共用 React 组件与业务模型 |
| 调试 | 模拟器/真机 | 浏览器 DevTools + Android WebView 调试 |
| 与 Java 后端关系 | 通过 HTTP 契约 | 同一套 HTTP 契约，前端 service 层可复用 |
| 简历叙事 | 双端原生 | Java 后端 + React 全栈 + 移动交付 |
| 主要风险 | 双端成本、iOS 工具链 | WebView 性能、键盘/安全区、商店审核与“Web 套壳”质疑 |

### 2.2 基线变更边界

1. 本文只覆盖 Android；iOS 端不生成工程，不承诺上架。
2. `docs/02` §2 与 `docs/09` A-02/K-05/W-04/Q-08 的原生门禁仍保留在历史文档中；本文不能替代原生验收，也不能声称“已满足双端原生要求”。
3. 若后续要回到原生双端，保留 `docs/16` 的产品/页面/数据/接口设计，技术章节按原生重写即可。
4. Capacitor 壳不加载远程页面；`server.androidScheme = https`，`allowMixedContent = false`，构建产物随 APK 打包。

### 2.3 运行环境

| 项 | 当前值 |
| --- | --- |
| Node | v22.23.2 |
| npm | 10.9.8 |
| Java | 21.0.6 |
| Spring Boot | 4.0.9 |
| React | 19.3.1 |
| TypeScript | 6.0.3 |
| Vite | 8.3.8 |
| Capacitor | core/android 8.6.2；CLI 8.6.2 |
| Android compileSdk / targetSdk | 36 / 36 |
| Android minSdk | 24 |
| Gradle wrapper | 8.14.3 |
| Android Gradle Plugin | 8.13.0 |
| Maven 镜像 | 阿里云 google/public（`dl.google.com` 不可达时兜底） |
| Android SDK | `C:\Users\ASUS\AppData\Local\Android\Sdk`；build-tools 36.0.0 / 35.0.0 |

---

## 3. 产品形态与信息架构

### 3.1 一个 App，四个一级入口

```text
启明书院 Android
├── 书城 home
│   ├── 主编推荐 / 继续阅读
│   ├── 新书横滑
│   ├── 编辑精选
│   └── 搜索
├── 创作 studio
│   ├── 作者工作室（数据看板 + 待办 + 作品）
│   ├── 作品管理
│   ├── 写作中心 / 编辑器
│   └── 素材库与灵感
├── AI ai
│   ├── 模式切换：传统 / AI 辅助 / 纯 AI
│   ├── 长期记忆
│   ├── 工作流进度
│   ├── 候选确认
│   ├── AI 插图
│   └── 素材库入口
└── 我的 me
    ├── 书架 / 阅读记录
    ├── 评论管理
    ├── 数据导出
    ├── 阅读设置
    └── 外观 / 关于
```

审核与运营不进入移动端 P0：这两类工作涉及批量表格、材料水印、裁决审计和配置对比，移动端做“能看不能批”反而增加安全风险。若未来需要移动审核，应单独立项。

### 3.2 角色与权限

| 角色 | 可见入口 | 服务端权限 |
| --- | --- | --- |
| 读者 | 书城、阅读器、书架、我的 | `getMe`、作品/章节读取、进度 |
| 作者 | 创作、AI、作品管理、评论 | `listMyWorks`、草稿、送审、工作流 |
| 审核 | Web 审核工作台 | 审核接口 + step-up |
| 运营 | Web 运营工作台 | 配置/治理/审计接口 |
| 未登录 | 书城只读 + 登录入口 | 不返回作者私有数据 |

### 3.3 页面地图与优先级

| 页面 | 路由 | 代码 | P0 | P1 | P2 |
| --- | --- | --- | --- | --- | --- |
| 书城 | `home` | `HomeScreen.tsx` | ✓ 本地演示 | 真实作品 API | 个性化推荐 |
| 搜索 | `search` | `SearchScreen.tsx` | ✓ 本地检索 | 服务端 `searchWorks` | 语义搜索 |
| 作品详情 | `book` | `BookDetailScreen.tsx` | ✓ | 收藏/追更 | 评论聚合 |
| 阅读器 | `reader` | `ReaderScreen.tsx` | ✓ | 进度同步/段评 | 离线/听书 |
| 作者工作室 | `studio` | `StudioScreen.tsx` | ✓ 演示看板 | 真实指标 API | 自定义看板 |
| 作品管理 | `work` | `WorkScreen.tsx` | ✓ | 章节批量/发布 | 多版本对比 |
| 写作中心 | `editor` | `EditorScreen.tsx` | ✓ 自动保存 | 真实草稿 API | 富文本/大纲 |
| AI 创作台 | `ai` | `AiScreen.tsx` | ✓ 演示工作流 | 真实 LLM/服务端 run | 批量多章 |
| 素材库/灵感 | `materials` | `MaterialsScreen.tsx` | ✓ | 服务端素材库 | 语义取材 |
| AI 插图 | `illustration` | `IllustrationScreen.tsx` | ✓ 概念图演示 | 真实图像模型 | 批量插图 |
| 我的 | `me` | `MeScreen.tsx` | ✓ | 账号/设备 | 权益/订阅 |
| 阅读设置 | `settings` | `SettingsScreen.tsx` | ✓ | 同步偏好 | 云端主题 |
| 评论管理 | `comments` | `CommentsScreen.tsx` | ✓ 演示 | 真实评论 API | 段评/自动审核 |

### 3.4 导航与返回

- 底部 Tab 只在 `home / studio / ai / me` 显示；阅读器、编辑器、素材、插图、评论、设置均隐藏 Tab，使用顶部返回。
- `MobileApp` 维护内存路由栈；硬件返回键优先回退一页，栈空时回首页，再按一次退出 App。
- 路由切换统一回到页首；阅读器章节切换由 `ReaderScreen` 自行滚到顶部。
- 不依赖 URL 路由；后续如需深链，使用 Capacitor App URL Open + 路由映射，不能让 WebView 直接加载外部 URL。

---

## 4. 页面布局与交互

### 4.1 视觉方向

**现代东方文学编辑部**：暖纸底（`#F4F0E7`）、墨黑（`#191816`）、朱印红（`#C2472F`）、宋体标题、细线分隔、低阴影、大量留白。封面全部由 CSS 排版生成，不使用外链图片、CDN 字体或随机图库。这个方向同时解决三个问题：启动快、离线可显示、视觉不与“AI 生成塑料感”混在一起。

### 4.2 书城 `home`

```text
┌──────────────────────────────┐
│ [启] 启明书院       LUMIN  ⌕ │  ← 品牌 + 搜索
├──────────────────────────────┤
│ 本周主编推荐 · 01      96%推荐│
│ ┌──────────────────┐         │
│ │   CSS 书封《晚潮记》│         │
│ └──────────────────┘         │
│ 晚潮记                        │
│ 沿海小城的潮位观测员…          │
│ [▶ 开始阅读] [加入书架]        │
├──────────────────────────────┤
│ 继续阅读 / 今日开始            │
│ 从《晚潮记》第一章读起          │
├──────────────────────────────┤
│ NEW & NOTEWORTHY    全部      │
│ [书封][书封][书封] → 横滑      │
├──────────────────────────────┤
│ EDITOR'S PICK                 │
│ [封面] 标题 / 编辑手记 / 标签  │
├──────────────────────────────┤
│ 作者专区：把故事写下去 [进入]   │
└──────────────────────────────┘
│ 书城   创作   AI   我的         │  ← 固定底部 Tab
```

交互要点：

1. 主视觉只有一个，不做多张营销 banner 轮播；轮播会抢走“开始阅读”。
2. 继续阅读卡来自本地进度（`Preferences`），没有进度时显示“从第一章读起”。
3. 书架横滑使用 `scroll-snap-type: x proximity`，卡片宽度 132px，保证一屏看到 2.5 本。
4. 所有书封由 `Cover` 组件以 `--cover-bg` / `--cover-ink` 生成；标题竖排，作者与机构字样保留。
5. 点击“加入书架”只做本地反馈；真实收藏接口属于 P1。

### 4.3 作品详情 `book`

```text
┌──────────────────────────────┐
│ ‹  作品详情                   │
│ ┌──────┐  纪事文学 · 连载中   │
│ │ 书封  │  晚潮记             │
│ │      │  著 · 林砚           │
│ └──────┘  [纪事][海边][值班簿] │
│ 128.4万阅读 | 3.2万收藏 | 96% │
│ [▶ 开始阅读] [♡ 收藏] [分享]  │
│ 作品简介（80–120 字）          │
│ 编辑手记（左侧朱红线）         │
│ 章节目录 01 潮水退去之后  ›    │
│ …                             │
│ 读者也在读 → 横滑              │
└──────────────────────────────┘
```

交互：收藏切换有即时反馈；章节目录默认 6 章，“全部”展开；点击章节进入阅读器；离线下载按钮明确标注 P1，不放假入口。

### 4.4 阅读器 `reader`

```text
┌──────────────────────────────┐
│ ‹   晚潮记 · 潮水退去之后   ⋯ │
│ ███████░░░░░░  (章节进度)     │
├──────────────────────────────┤
│ CHAPTER 01 / 12               │
│ 潮水退去之后                  │
│                               │
│ 沿海小城的潮位观测员在整理旧  │
│ 值班簿时，发现一行被墨迹盖住  │
│ 的记录……                     │
│                               │
│ ［上一章］      ［下一章］     │
│ 本章完 · 启明书院             │
├──────────────────────────────┤
│ 目录   主题   设置   评论      │
└──────────────────────────────┘
```

- 全屏触控优先：正文最大宽度 680px，左右 26px 内边距；行高 1.95，字号 19px 起。
- 主题支持纸白/米色/青绿/夜间；字号 16–24px、行距 1.70–2.30、宋体/黑体。
- 目录使用底部 Sheet；当前章节打勾；点击章节切换并关闭。
- “评论”在 P0 只做说明，P1 接段评/章评；不放置伪评论。
- 阅读进度按章节写入本地；真实环境接 `GET/PUT /me/reading-progress/{id}`，以服务端版本为准。

### 4.5 作者工作室 `studio`

```text
┌──────────────────────────────┐
│ [启] 作者工作室          ▣    │
│ 11月18日星期三                 │
│ 林砚，今天继续写。             │
│ 《晚潮记》连载中 · 热度上升 6 位│
│ 128.4万阅读 | 3.2万收藏 | ¥4,286│
│ 阅读趋势        [日][周][月]   │
│ ┌──────────────────────────┐ │
│ │ 8,426 阅读人次 · 周  +8.2%│ │
│ │  ▁ ▃ ▂ ▅ ▄ ▇ ▆           │ │
│ └──────────────────────────┘ │
│ 本周新书榜 · 第 12 名   [查看]│
│ 待办 3 项                     │
│ [继续写] [去确认] [查看评论]   │
│ 素材库与灵感            打开  │
│ 我的作品                      │
│ 晚潮记 连载中  12章 41.1万字  │
│ [管理作品] [继续写]           │
│ 本月可提现 ¥4,286             │
└──────────────────────────────┘
```

**数据口径**：

| 指标 | 定义 | 当前实现 | 真实接口 |
| --- | --- | --- | --- |
| 阅读量 | 去重阅读人数/阅读次数，按作品聚合 | 本地示例 `stats.reads` | 作者数据看板接口，P1 |
| 收藏数 | 书架收藏去重 | 本地示例 | 书架/收藏聚合，P1 |
| 收益 | 自然月可结算金额 | 本地示例 | 结算中心，P1 |
| 热度排名 | 周榜名次 | 本地示例 | 榜单接口，P2 |
| 趋势 | 日/周/月阅读人次 | CSS 柱状图 | 时间序列接口，P1 |

图表不编造“同比增长”结论；演示数据明确写“本地演示口径”。

### 4.6 作品管理 `work`

- 作品封面、状态、章节数、字数、阅读/收藏/收益。
- 章节列表按序号排列，点击进入编辑器；支持“写新章节”和“AI 续写”。
- 发布流程卡：草稿 → 自检 → 提交审核 → 发布；当前状态显示“草稿可编辑”。
- 作品设置：作品信息、更新计划、评论管理、数据导出。
- P0 不批量改章节、不批量发布。

### 4.7 写作中心 `editor`

```text
┌──────────────────────────────┐
│ ‹  晚潮记 · 写作中心   已保存 │
│    新章节 · 传统写作     ▣    │
├──────────────────────────────┤
│ ● 你写每一个字，AI 不介入正文  │
│ 第 13 章 · 封冻之前            │
│ 正文 · 170 字                  │
│                               │
│ 封冻前最后一班船靠岸时……       │
│                               │
├──────────────────────────────┤
│ 170 字   [模式] [AI 协助] [发布]│
└──────────────────────────────┘
```

**自动保存协议**：

1. 用户输入后 1 秒防抖触发本地保存；真实后端再加 2 秒防抖、15 秒最长串行。
2. 保存状态四态：`已保存` / `有未保存修改` / `正在保存…` / `保存失败`。
3. 保存前先取当前草稿基线；写接口带 `If-Match` / `revision`；412 冲突保留本地并提示“复制/覆盖/另存”，不静默覆盖。
4. 退出编辑器、切后台、发布前强制保存；发布确认页展示“AI 来源标记”。
5. 本地演示使用 `Preferences` 的 `lumin.mobile.draft.v1`；真实环境以服务端 `draft` 为权威。

**发布流程**：

```text
编辑 → 保存到 CLEAN
→ 自检（空标题/过短/敏感词/来源标记）
→ 发布确认 Sheet（章节、字数、模式、AI 来源）
→ submitDraft（服务端）
→ 回读 publication workflow
→ 显示“审核中/已发布/需修改”
```

发布确认页不写“保证通过”，不写“平台拥有版权”；只写“你确认拥有发布权利”和 AI 来源。

### 4.8 AI 创作台 `ai`

```text
┌──────────────────────────────┐
│ ‹  AI 创作台          [本地演示]│
│ [传统写作][AI 辅助][纯 AI]     │
│ AI 给候选段落，你决定是否采纳。 │
│ 长期记忆                查看全部│
│ [世界·已锁定][人物·已锁定]→   │
│ 这次想写什么                   │
│ ┌──────────────────────────┐ │
│ │ 让林砚在封冻前……          │ │
│ └──────────────────────────┘ │
│ [✦ 开始生成候选]              │
│ 工作流进度              已完成 │
│ ● 读取长期记忆  完成           │
│ ● 生成候选段落  完成           │
│ 候选章节  [采纳并写入][重生成]  │
└──────────────────────────────┘
```

交互：

1. 模式 Tab 是 `role=tab`，可键盘/读屏切换；切换只改变当前会话模式，不覆盖历史正文。
2. “开始生成候选”在传统模式禁用并提示；AI 辅助/纯 AI 才创建 run。
3. run 运行中显示业务阶段（读取记忆、生成、审校、归档），不显示内部 Agent 名称与提示词。
4. 候选卡显示“演示引擎”/“服务端”；候选可以采纳、重生成、退回；退回不改原稿。
5. 采纳时只把候选段落写入编辑器；纯 AI 的记忆 delta 先进入“待确认”，不直接覆盖锁定记忆。

### 4.9 素材库与灵感 `materials`

- 两个 Tab：素材库、灵感；顶部搜索与“记一条”。
- 素材类型：场景、人物、物件、地名；每条有标题、说明、标签、引用次数。
- 点击素材 → Sheet 查看详情 → “引用到当前章节”；真实环境写引用记录，不复制正文。
- 灵感支持新增、持久化（`lumin.mobile.notes.v1`）；列表显示标签与时间。
- 不把素材库做成网盘；不鼓励上传侵权图片；真实素材上传走 `asset-uploads` 受控接口。

### 4.10 AI 插图 `illustration`

- 提示词、风格（水墨/木刻/电影感）、比例（3:4 / 1:1 / 16:9）。
- “生成概念图”当前生成一张 CSS 概念图（本地演示），明确标注“不调用图像模型”。
- 真实环境由服务端调用图像模型，产出候选图、来源、提示词、审核状态；不直接发布。
- 可“用作章节插图/书封草案”，进入素材/草稿引用；版权与来源随稿记录。
- 插图不替代正文，不自动生成整章配图。

### 4.11 我的 / 设置 / 评论

- 我的：作者身份、作品数、累计阅读、本月收益、书架、评论管理、数据导出、阅读设置、创作权益、邀请读者、外观。
- 设置：阅读主题、字号、行距、字体、预览、恢复默认；外观（日/夜）在“我的”单独控制。
- 评论：待处理数、评论列表、作者回复 Sheet；演示数据，真实环境接评论接口与审核。
- 数据导出：当前导出书架 CSV（带 BOM，Excel 可读）；真实环境走服务端导出任务，不把正文/未公开内容打包。

---

## 5. 三种创作模式并存

### 5.1 定义

| 维度 | 传统 MANUAL | AI 辅助 ASSISTED | 纯 AI AUTONOMOUS |
| --- | --- | --- | --- |
| 作者角色 | 直接写 | 直接写 + 按需请求建议 | 定目标/约束，审阅候选 |
| AI 介入 | 无正文模型调用 | 选区/段落/单次任务 | workflow 调度多 Agent，整章候选 |
| 正文写入 | 作者输入 | 作者接受候选后写入 | 作者接受候选包后写入 |
| 编辑器 | 全可编辑 | 全可编辑 + 候选 | 运行期只读；接管后可编辑 |
| 长期记忆 | 手动维护 | 可提取，需确认 | 使用冻结记忆，接受时确认 delta |
| 发布 | 作者送审 | 同左，保留 AI 来源 | 同左，绝不自动发布 |
| 适合 | 通勤短写、隐私敏感 | 卡文、改写、校对 | 有大纲/设定，快速拿单章候选 |

### 5.2 作用域与 `modeEpoch`

1. 账号保存默认偏好，作品保存默认模式，章节 `creation_session` 保存当前有效模式。
2. `modeEpoch` 是单调递增世代；每次切换、接管、人工改稿都递增。
3. AI run 绑定 `workId / chapterId / sessionRevision / draftId / baseRevision / modeEpoch / memoryRevision`。
4. 旧世代 run 即使成功，也不能覆盖新草稿；移动端显示“STALE”，提供“从快照新建”。
5. 同一作品同一时间只允许一个会写章节候选/记忆的纯 AI 批次处于执行或待确认；辅助任务可并发只读候选。

### 5.3 切换流程

```text
MANUAL → ASSISTED
  保存当前稿 → 检查 AI 资格/预算 → modeEpoch+1
  → 编辑器保留原文 → AI 面板出现，不自动生成

ASSISTED → MANUAL
  停接受旧任务 → modeEpoch+1 → 取消后续
  → 已接受内容保留；未接受候选只读

MANUAL/ASSISTED → AUTONOMOUS
  选择“从空稿/现有稿续写” → 确认提纲/记忆/预算
  → 保存快照 → 创建有界计划 → 候选不替换正文

AUTONOMOUS → ASSISTED/MANUAL
  点“接管” → 选择原稿/已完成候选 → 冻结旧任务
  → modeEpoch+1 → 转可编辑；旧结果不得覆盖
```

失败处理：本地未保存、断网、412 冲突时切换不完成；UI 不先切成新模式再补服务端。原子接管：本地有未保存修改时禁止直接以候选接管；先保存或明确放弃。

### 5.4 编辑器行为矩阵

| 行为 | 传统 | AI 辅助 | 纯 AI 运行期 |
| --- | --- | --- | --- |
| 正文区 | 全屏可编辑 | 全屏可编辑 | 只读候选预览 |
| AI 按钮 | 保留入口，点击说明不调用 | 生成候选 | 任务状态/暂停/接管 |
| 自动保存 | 1 秒本地防抖 | 同左；接受前保存屏障 | 不写本地正文，只存任务快照 |
| 候选呈现 | 无 | 底部 Sheet / 段落差异 | 整章候选包 + 记忆 delta |
| 撤销 | 正常 | 接受后合并撤销栈 | 接管后恢复 |
| 送审 | CLEAN 后可送 | 保留 AI 来源 | 接受候选生成草稿后才可送 |
| 失败 | 保留本地稿 | 保留本地稿 + 候选 | 任务失败可重试，不写正文 |

### 5.5 纯 AI 的 workflow + multi_agent

**责任划分**：

- workflow：校验、计划、步骤派发、检查点、预算、返工上限、终态；
- 规划 Agent：章节目标、场景拆分、约束清单；
- 起草 Agent：按计划生成候选；
- 一致性 Agent：硬冲突、时间线、人物知情、伏笔；
- 记忆 Agent：事实/事件/人物时态候选；
- 插图 Agent（P2）：场景图候选；
- 移动端：任务台 + 候选确认器，不运行 Agent 循环。

**移动端可见阶段**：

| 阶段 | 服务端 | 移动端 |
| --- | --- | --- |
| 资格/预算 | 权限、许可、记忆水位、预算 | 校验中 |
| 章节计划 | 规划 Agent | 计划 |
| 记忆上下文 | 组装硬约束/人物时态/伏笔 | 记忆版本 |
| 起草 | 写作 Agent | 起草中 |
| 一致性检查 | 检查 Agent | 检查中/冲突数 |
| 有限修订 | 按上限修订 | 修订 1/2 |
| 候选包 | 正文、差异、来源、记忆 delta | 等待确认 |
| 作者决策 | 等接受/拒绝/接管 | 需要你确认 |
| 原子接受 | 草稿 + 来源 + 记忆 + 审计 | 已生成草稿，待送审 |

**状态机**：`QUEUED → RUNNING → (PAUSED) → WAITING_DECISION / WAITING_AUTHOR → ACCEPTED / REJECTED / FAILED / CANCELLED / STALE`。App 退后台不取消服务端任务；回到前台用 `getAuthorAiRun` 拉快照，SSE 断线用 `seq` 补齐，SSE 不是事实源。

### 5.6 长期记忆

**记忆层级**：

| 层 | 内容 | 移动端 |
| --- | --- | --- |
| 世界圣经 | 世界规则、视角、禁区 | 只读/确认，P1 |
| 人物档案 | 身份、外貌、性格、关系、别名 | 人物卡 |
| 人物时态 | 年龄、位置、伤势、持有物、知情 | 按故事时间展示 |
| 情节时间线 | 事件、参与者、因果、时间 | 冲突高亮 |
| 伏笔/承诺 | 埋设、预计回收、已回收 | 状态 + 证据 |
| 提纲 | 总纲/卷纲/章纲 | 标“计划，不是已发生” |
| 章节摘要 | 派生压缩 | 可失效，可回原文 |
| 文风偏好 | 授权样稿 | 只读，不存未授权语料 |
| 运行记忆 | 当前候选/临时判断 | run 隔离，拒绝不污染 |

**持久化原则**：

1. 记忆不是把全文塞进上下文，而是“事实条目 + 事件 + 来源 + 版本”；
2. 每条事实有 `status`（locked / candidate / superseded）、`valid_from/to`、`source_version`、`confidence`；
3. 生成前按人物、世界观、伏笔、时间线检索最小必要子集，并带 `memoryRevision`；
4. 作者确认后才写入；锁定条目只能由作者在 Web 端“记忆审阅”修改；
5. 人工改稿后 `sourceEpoch` 递增，旧候选/摘要标 STALE；记忆索引异步重建失败不回滚正文，但显示“记忆待就绪”；
6. 不承诺“永远记住/零幻觉”；显示版本、来源与覆盖水位，允许纠错。

### 5.7 版权归属与 AI 来源

| 问题 | 立场 | 移动端 |
| --- | --- | --- |
| 作者原创 | 作者保留著作权；平台按授权提供发布/分发 | 发布确认“你确认拥有发布权利” |
| AI 辅助 | 人工 + AI 混合；逐次记录 | 草稿/版本显示“AI 辅助” |
| 纯 AI | 主要 AI 生成、作者确认；不自动发布 | 显示“主要 AI 生成，作者确认” |
| 平台是否取得著作权 | 不因工具/发布服务取得 | 用户协议单独说明，不在 UI 作法律承诺 |
| 模型训练 | 未经单独可撤回授权，不用正文训练/微调 | 设置默认关闭，授权/撤回留审计 |
| AI 插图 | 记录提示词/来源/供应商标识 | 候选图显示“AI 生成”，P1/P2 |
| 删除/撤回 | 按治理规则；已下载明文不能保证远程删除 | 离线须知明确限制 |

来源记录 append-only：`content_version` 的 `provenance` 不因编辑擦除；审核可看到“人工/AI 混合/主要 AI”与供应商版本。

---

## 6. 数据看板设计

### 6.1 指标与时间维度

| 指标 | 口径 | 粒度 | P0 展示 |
| --- | --- | --- | --- |
| 阅读量 | 阅读事件去重/次数 | 日/周/月 | 累计 + 趋势 |
| 收藏数 | 书架收藏去重 | 日/周/月 | 累计 + 增量 |
| 收益 | 自然月可结算 | 月 | 本月 + 明细入口 |
| 热度排名 | 周榜名次 | 周 | 名次 + 变化 |
| 推荐度 | 读者推荐比例 | 作品 | 百分比 |
| 追更 | 订阅/提醒人数 | 日/周 | P1 |

### 6.2 查询与缓存

```text
GET /api/v1/me/author-dashboard?workId=&range=day|week|month
→ { totals, series[], rank, revenue, updatedAt, freshness }
```

- 服务端按 `range` 聚合，返回 `series` 时间点；客户端不自行求和。
- `freshness` 标明数据截止时间；超过 15 分钟显示“数据更新中”。
- 客户端缓存 5 分钟；切换 range 重新请求，不用旧数据冒充新周期。
- 真实接口未就绪时，演示库标注“本地演示口径”，不展示虚假同比。

---

## 7. 写作中心设计

### 7.1 章节与草稿

- 作品 → 章节 → 草稿；同一目标最多一个 `EDITABLE` 草稿。
- 编辑器只负责正文、标题、模式与本地保存状态；版本、审核、发布指针由服务端裁决。
- 新建章节默认标题 `第 N 章 · 未命名`；可改。
- 章节管理列表显示序号、标题、字数、更新时间；点击进入编辑。

### 7.2 自动保存状态机

```text
IDLE → DIRTY（输入）
DIRTY → SAVING（防抖 1s / 手动）
SAVING → SAVED（本地成功）
SAVING → ERROR（存储失败/网络失败）
ERROR → DIRTY（继续输入或重试）
```

- 本地保存：`Preferences.set`，键 `lumin.mobile.draft.v1`；失败不阻塞输入，但状态必须显示失败。
- 真实保存：`PUT /drafts/{id}`，`If-Match`；202 只表示受理，需回读。
- 发布前：保存到 CLEAN；空标题/正文 <20 字阻止。
- 冲突：412 保留本地，提供“复制本地/放弃本地/另存新稿”，不自动覆盖。

### 7.3 发布

```text
POST /drafts/{id}/submissions
→ 202 + workflowId
→ GET /publication-workflows/{id}
→ 显示 审核中 / 需修改 / 已发布
```

移动端不轮询过密；前台 5 秒一次，退后台停止，回前台立即拉一次。

---

## 8. 素材库与灵感设计

| 能力 | P0 | P1 | P2 |
| --- | --- | --- | --- |
| 素材浏览 | 本地示例 | 服务端素材库 | 语义检索 |
| 素材引用 | Toast 演示 | 写引用记录 | 自动关联章节 |
| 灵感记录 | 本地持久化 | 账号同步 | 多人协作 |
| 素材上传 | 不做 | `asset-uploads` | 自动标签 |
| 版权 | 提示作者负责 | 来源/授权字段 | 侵权检测 |

素材条目的最小字段：`id / workId / kind / title / detail / tags / source / license / usedCount / createdAt`。引用只记录 `materialId + chapterId + range`，不复制全文。

---

## 9. AI 辅助写作与 AI 插图

### 9.1 AI 辅助

- 入口：编辑器“AI 协助”、AI 创作台“AI 辅助”。
- 任务范围：选区/指定段落/单章续写/校对。
- 输出：候选段落 + 差异 + 来源；不直接写正文。
- 成本：每次任务前显示范围/上限；P0 演示不显示真实价格。
- 失败：保留原稿与候选；锚点失效标 STALE。

### 9.2 纯 AI

- 入口：AI 创作台“纯 AI”，或作品管理“AI 续写”。
- 必须先有目标/约束/提纲/记忆水位；没有则引导先建提纲。
- 移动端只做任务台：发起、暂停/取消（按能力）、查看业务阶段、确认候选、接管。
- 接受是原子命令；成功后草稿 revision 增加；失败保留候选供比较。

### 9.3 AI 插图

- 入口：AI 创作台“AI 插图”。
- 输入：场景描述、风格、比例、参考素材（合法来源）。
- 输出：单图候选 + 提示词 + 供应商标识 + 审核状态。
- 当前构建：本地 CSS 概念图，明确“不调用图像模型”；真实图像模型 P1。
- 版权：作者对合法素材负责；AI 生成图标识；平台不因工具主张著作权；不自动发布。

### 9.4 引擎切换

```text
本地演示引擎（当前）
  ├── 无网络依赖
  ├── 固定候选 + 可替换接口
  └── UI 明示“本地演示”

服务端引擎（P1）
  ├── createAuthorAiRun → 202
  ├── getAuthorAiRun / SSE
  ├── acceptAuthorAiCandidate
  └── 供应商/模型/预算全部服务端裁决
```

`src/mobile/services/aiDemo.ts` 只负责演示；真实接入时替换为 `aiService.ts`，页面不改。

---

## 10. 数据模型设计

### 10.1 复用现有表

| 表 | 移动端用途 | 约束 |
| --- | --- | --- |
| `user_profile` | 账号、年龄状态、角色 | 不展示生日/证件 |
| `work` | 作品元数据 | 读者只读公开字段 |
| `chapter` | 章节身份/序号/状态 | 不泄露未公开章节 |
| `draft` | 草稿正文 | 同目标最多一个 EDITABLE |
| `content_version` | 不可变版本/来源 | 正文不从公开对象地址取 |
| `publication_workflow` | 送审/审核/发布 | 202 不是发布 |
| `publication_pointer` | 当前公开版本 | 回读吻合才显示已发布 |
| `notification` | 站内通知 | P0 唯一通知事实源 |
| `reading_progress` | 阅读进度 | 0–10000bp，版本绑定 |

### 10.2 新增/拟议表

#### `creation_session`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | uuid | 主键 |
| user_id | uuid | 作者 |
| work_id | uuid | 作品 |
| chapter_id | uuid nullable | 章节；新章为空 |
| mode | enum | MANUAL / ASSISTED / AUTONOMOUS |
| mode_epoch | bigint | 单调世代 |
| session_revision | bigint | 会话版本 |
| draft_id | uuid nullable | 当前草稿 |
| base_revision | bigint | 基线 |
| memory_revision | bigint | 记忆水位 |
| created_at / updated_at | timestamptz | UTC |

#### `ai_run`

`id / session_id / mode / status / plan_json / budget_json / provider / model / started_at / finished_at / error_code / trace_id`。状态机见 §5.5。

#### `ai_candidate`

`id / run_id / kind(TEXT/IMAGE/MEMORY) / content_ref / diff_ref / status(CANDIDATE/ACCEPTED/REJECTED/STALE) / provenance_json / created_at`。

#### `memory_fact`

`id / work_id / kind(WORLD/CHARACTER/PLOT/FORESHADOW/STYLE) / key / value_json / status(LOCKED/CANDIDATE/SUPERSEDED) / valid_from / valid_to / source_version / confidence / created_at`。

#### `memory_event`（append-only）

`id / work_id / fact_id / event_type(CREATE/UPDATE/SUPERSEDE/CONFLICT/RESOLVE) / before_json / after_json / actor / source_run_id / created_at`。

#### `material_item` / `inspiration_note`

`id / work_id / kind / title / detail / tags_json / source / license / used_count / created_at`；灵感多 `text / tag / created_at`。

#### `illustration_asset`

`id / work_id / run_id / prompt / style / ratio / storage_key / provenance_json / moderation_status / created_at`。

#### `device_session`

`id / user_id / device_id / platform / app_version / push_token_hash / last_seen_at / revoked_at`；P1。

### 10.3 客户端本地状态

| 键 | 内容 | 生命周期 |
| --- | --- | --- |
| `lumin.mobile.draft.v1` | 当前草稿快照 | 覆盖；可丢弃 |
| `lumin.mobile.progress.v1` | 阅读进度数组 | 账号切换时清理 |
| `lumin.mobile.reader.v1` | 阅读设置 | 长期 |
| `lumin.mobile.memory.v1` | 记忆只读缓存 | 可重建，不作为权威 |
| `lumin.mobile.mode.v1` | 模式偏好 | 长期 |
| `lumin.mobile.theme.v1` | 日/夜外观 | 长期 |
| `lumin.mobile.notes.v1` | 灵感演示数据 | 长期；P1 迁服务端 |

### 10.4 关系

```text
user_profile
  └── work
       ├── chapter
       │    └── creation_session
       │         └── ai_run
       │              ├── ai_candidate
       │              └── memory_event
       ├── memory_fact
       ├── material_item
       ├── inspiration_note
       └── illustration_asset
```

---

## 11. 接口设计与系统集成

### 11.1 直接复用

以下为 `/api/v1` 现有接口（`contracts/p0.openapi.yaml` 口径）：

| 领域 | 方法与路径 | operationId | 移动端用途 |
| --- | --- | --- | --- |
| 身份 | GET /me | `getMe` | 角色/年龄/限制 |
| 读者 | GET /works；GET /works/{id} | `listWorks` / `getWork` | 书城/详情 |
| 读者 | GET /works/{id}/chapters | `listChapters` | 目录 |
| 读者 | GET /chapters/{id}/content | `readChapter` | 正文 |
| 读者 | GET/PUT /me/reading-progress/{id} | `getProgress` / `saveProgress` | 进度 |
| 作者 | GET /me/works；GET /me/works/{id} | `listMyWorks` / `getMyWork` | 作品 |
| 作者 | GET /me/works/{id}/chapters | `listMyChapters` | 章节 |
| 作者 | POST /works；POST /works/{id}/chapters | `createWork` / `createChapter` | 新建 |
| 作者 | GET /me/drafts；GET /drafts/{id} | `listMyDrafts` / `getDraft` | 草稿 |
| 作者 | POST /drafts；PUT /drafts/{id} | `createDraft` / `saveDraft` | 自动保存 |
| 作者 | POST /drafts/{id}/submissions | `submitDraft` | 送审 |
| 作者 | GET /me/publication-workflows；GET /publication-workflows/{id} | `listWorkflows` / `getWorkflow` | 发布状态 |
| 通知 | GET /me/notifications；POST /me/notifications/{id}/read-receipts | `listNotifications` / `markRead` | 站内通知 |
| 资产 | POST/GET /asset-uploads；POST /asset-uploads/{id}/completions；GET /assets/{id}/content | `createAssetUpload` 等 | 封面/头像 P1 |

**契约缺口**：`POST/GET /api/v1/me/closure` 后端已有 `ClosureController`，但契约未收录；App 内注销入口必须先补契约，不能按源码臆造。

### 11.2 新增接口（拟议）

| 路径 | 用途 | 优先级 |
| --- | --- | --- |
| GET /me/author-dashboard | 数据看板聚合 | P1 |
| GET /me/author-dashboard/series | 日/周/月趋势 | P1 |
| GET/POST /me/materials | 素材库 | P1 |
| GET/POST /me/inspirations | 灵感 | P1 |
| GET /me/bookshelf；POST/DELETE /me/bookshelf/{workId} | 书架 | P1 |
| GET /search/works | 搜索 | P1 |
| POST /me/devices；DELETE /me/devices/{id} | 设备会话 | P1 |
| PUT /me/devices/{id}/push-token | 推送令牌 | P1 |
| POST /me/download-tickets | 离线许可 | P2 |

AI 复用 `15` §13.2：`getCreationSession`、`transitionCreationMode`、`createAuthorAiRun`、`getAuthorAiRun`、`streamAuthorAiRunEvents`、`actOnAuthorAiRun`、`decideAuthorAiRun`、`acceptAuthorAiCandidate`、`getStoryMemory`、`commitStoryMemory`、`listMemoryConflicts`。移动端不新增“万能 AI 代理”，不传供应商/模型/工具名单。

### 11.3 契约与错误语义

1. 统一信封 `code / message / data / traceId`；客户端按 `code` 分支，不解析 message 做业务判断。
2. 写操作 `Idempotency-Key`；更新 `If-Match`；202 只表示受理，必须回读。
3. 分页 `{items, nextCursor}`；空列表返回 `[]`，不返回 `null`。
4. 429 按 `Retry-After` 退避；401 清会话回登录；412 保留本地；410 给“从快照新建”。
5. 新增错误码进入 `libs/error-codes.json`，不在客户端散落字符串。

### 11.4 集成矩阵

| 系统 | 移动端职责 | 集成 | 禁止 |
| --- | --- | --- | --- |
| identity | 登录、角色、设备 | OIDC PKCE + getMe | 自建密码；请求体自报 userId/角色 |
| catalog | 作品/章节读取 | listWorks/getWork | 本地拼可见性 |
| authoring | 草稿/版本/送审 | draft + ETag | 本地正文当权威 |
| publication | 工作流/发布指针 | submit + 回读 | 202 当发布 |
| engagement | 进度/通知/书架 | progress/notification/书架 P1 | 收藏=订阅；本地假已读 |
| audit | 只读审计 | audit projection | 删除/隐藏 |
| AI | run/候选/记忆 | 15 §13.2 + SSE | 本地编排；自动发布；密钥下发 |
| 推送 | 站内/外部 | push adapter + 厂商 | 推送当事实源；payload 放正文 |

---

## 12. 技术架构

### 12.1 总体

```text
┌────────────────────────────────────────────┐
│ Android APK (Capacitor)                    │
│  ├── WebView (https://localhost)           │
│  │    ├── React 19 + TypeScript            │
│  │    ├── MobileApp / screens / components│
│  │    ├── services (API / demo / storage)  │
│  │    └── styles (tokens + mobile.css)     │
│  ├── Capacitor plugins                     │
│  │    ├── App（硬件返回/退出）              │
│  │    ├── Preferences（本地状态）           │
│  │    ├── SplashScreen / StatusBar         │
│  │    ├── Keyboard / Network / Share       │
│  │    └── Haptics / Browser                │
│  └── AndroidManifest / Gradle              │
└──────────────────┬─────────────────────────┘
                   │ HTTPS /api/v1
┌──────────────────▼─────────────────────────┐
│ Java 21 + Spring Boot 4.0.9                │
│ identity / authoring / catalog / publication│
│ engagement / moderation / governance / audit│
│ AI orchestration (P1)                       │
└────────────────────────────────────────────┘
```

### 12.2 前端分层

```text
src/
├── main.tsx                 # 双入口：web / mobile
├── mobile/
│   ├── MobileApp.tsx        # 路由、状态、持久化、返回键
│   ├── navigation.ts        # MobileRoute / Tab / meta
│   ├── types.ts             # 移动端领域类型
│   ├── components/          # ui.tsx / Icons.tsx
│   ├── screens/             # 13 个页面
│   ├── data/                # library.ts / studio.ts
│   └── services/            # aiDemo.ts / storage.ts
├── styles/
│   ├── tokens.css           # 共享色彩/主题
│   └── mobile.css           # 移动设计系统
└── app/                     # 现有 Web 工作台
```

### 12.3 双构建

```json
{
  "dev": "vite",
  "dev:mobile": "vite --mode mobile",
  "build": "tsc -b && vite build",
  "build:mobile": "tsc -b && vite build --mode mobile",
  "cap:sync": "npm run build:mobile && cap sync android",
  "cap:open": "cap open android"
}
```

`vite.config.ts`：

- `mode === 'mobile'`：`base: './'`、`outDir: 'dist-mobile'`、`sourcemap: false`、dev 5174；
- Web：`base: '/'`、`outDir: 'dist'`、`sourcemap: true`、dev 5173；
- `/api`、`/internal` 代理 `http://localhost:8081`。

### 12.4 Android 壳

| 配置 | 值 |
| --- | --- |
| appId | `com.leyon.lumin` |
| appName | 启明书院 |
| webDir | `dist-mobile` |
| androidScheme | `https` |
| allowMixedContent | false |
| backgroundColor | `#F5F1E8` |
| iOS | `contentInset: 'always'` 仅预留，不生成工程 |

已安装插件：`@capacitor/app`、`browser`、`keyboard`、`network`、`preferences`、`share`、`splash-screen`、`status-bar`、`haptics`。

### 12.5 性能

| 项 | 当前 |
| --- | --- |
| MobileApp chunk | 约 124 KB（gzip 37.5 KB） |
| 共享 React 入口 | 约 222 KB（gzip 69.6 KB） |
| mobile.css | 约 34 KB（gzip 7.5 KB） |
| 外部资源 | 0（无 CDN/字体/图片） |
| 封面 | CSS 生成，无网络请求 |
| 首屏目标 | 冷启动 < 2.5s（中端 Android，P1 实测） |

优化次序：先删无用组件/重复数据，再做路由级懒加载；不要用 base64 塞大图。

### 12.6 安全

- 不加载远程 URL；`androidScheme=https`；`allowMixedContent=false`；WebView 只读本地资产。
- 不引入外链字体/图片/脚本；CSP 后续在 Android WebView 层加白名单。
- token 不写日志/崩溃报告；真实登录使用原生安全存储（Keystore），不使用 `Preferences` 存 refresh token。
- 裁剪/截屏：审核/敏感页面加 `FLAG_SECURE`（P1）；当前作者私密稿不展示给读者。
- 导出：只导出书架/公开元数据，不打包未公开正文。
- 日志：错误只上报 `traceId/code`，不上报正文与用户标识。

---

## 13. Android 工程

### 13.1 目录

```text
lumin-frontend/
├── android/                  # Capacitor 生成
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/leyon/lumin/MainActivity.java
│   │   ├── assets/public/    # dist-mobile 同步产物
│   │   └── res/              # 图标/启动/splash
│   ├── gradle/wrapper/
│   └── variables.gradle
├── capacitor.config.ts
├── .env.mobile
├── dist-mobile/
└── src/mobile/
```

### 13.2 构建

```powershell
# 1) 构建移动 Web 产物
npm run build:mobile

# 2) 同步到 Android
npx cap sync android

# 3) 编译 debug APK
cd android
.\gradlew.bat assembleDebug --no-daemon

# 4) 安装到已连接设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

环境变量：

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
```

`android/local.properties`（不提交）：

```properties
sdk.dir=C\:\\Users\\ASUS\\AppData\\Local\\Android\\Sdk
```

项目位于含中文的目录，`android/gradle.properties` 已加入：

```properties
android.overridePathCheck=true
```

国内网络下 `dl.google.com` 可能无法解析，`android/build.gradle` 与 `capacitor-cordova-android-plugins/build.gradle` 已优先使用阿里云 Maven 镜像，`google()/mavenCentral()` 作为兜底。不要删除镜像配置；换网络后可保留。

### 13.3 版本与包名

- 包名 `com.leyon.lumin`；应用名“启明书院”；版本 `1.0`（versionCode 1）。
- minSdk 24；compile/target 36。
- 发布签名：release keystore 不入库；CI 用 secret 注入。当前只验证 debug APK。
- 上架：应用备案、隐私政策、软著/资质由运营/法务单独确认；不在本文承诺过审。

### 13.4 设备能力

| 能力 | 当前 | 说明 |
| --- | --- | --- |
| 硬件返回 | `@capacitor/app` backButton | 先回退路由，再回首页，再退出 |
| 安全区 | CSS `env(safe-area-inset-*)` | 顶栏/底栏/阅读器 |
| 键盘 | `@capacitor/keyboard` | 编辑器与 Sheet 不遮挡输入 |
| 状态栏 | `@capacitor/status-bar` | 后续按主题切换深浅 |
| 启动屏 | `@capacitor/splash-screen` | 首屏加载后隐藏 |
| 网络 | `@capacitor/network` | 弱网提示 P1 |
| 分享 | `@capacitor/share` | 分享链接 P1 |
| 触感 | `@capacitor/haptics` | 关键确认轻触感 P1 |
| 推送 | 未接 | P1 厂商通道/APNs 不涉及 Android-only 本期 |

### 13.5 iOS 预留

- `capacitor.config.ts` 保留 `ios.contentInset` 与 `backgroundColor`；
- 不创建 `ios/` 工程，不签名，不写 Swift；
- 若未来启动 iOS，先做安全区/键盘/返回手势/分享/推送的差异评估，再决定是否复用同一套 React 页面。

---

## 14. 设计系统与无障碍

### 14.1 令牌

| 令牌 | 值 |
| --- | --- |
| paper | `#F4F0E7` |
| surface | `#FFFDF8` |
| ink | `#191816` |
| ink-2 | `#625D54` |
| accent | `#C2472F` |
| green | `#3E5B4B` |
| gold | `#B58A4B` |
| radius | 16 / 24 |
| nav height | 62px |
| touch | 44 / 48px |

### 14.2 字体

- 标题：`Songti SC / STSong / Noto Serif CJK SC / Source Han Serif SC / SimSun / serif`；
- UI：`-apple-system / PingFang SC / Noto Sans CJK SC / Microsoft YaHei / sans-serif`；
- 不使用外链字体；Android 回退到系统 Noto Serif/Sans。

### 14.3 无障碍

- 触控目标 ≥44px（底部 Tab 56px）。
- 正文可缩放 16–24px；行距 1.70–2.30。
- 阅读器/编辑器支持系统字体缩放；320px 视口不横向滚动。
- 图标按钮均有 `aria-label`；Tab 有 `aria-current`；Sheet 有 `role=dialog` + `aria-modal`。
- 暗色模式：`data-m-theme=night`；阅读主题独立，不强制跟随 App。

### 14.4 Android 品牌资源

- 图标：暖纸底 + 朱红方章 + 三条纸色横线；自适应图标前景为透明朱印，旧版 PNG 覆盖 mdpi–xxxhdpi。
- 启动图：暖纸底、淡朱砂晕染、朱印 + 宋体“启明书院” + `LUMIN ACADEMY` + “把故事写下去”；竖屏/横屏各密度均已生成。
- 生成脚本：`lumin-frontend/scripts/generate_android_brand.py`；无外部图片/字体依赖，可重复执行。
- Android 12+ 启动主题补充 `windowSplashScreenBackground` / `windowSplashScreenAnimatedIcon` / `postSplashScreenTheme`。

---

## 15. 功能优先级与交付切片

### 15.1 P0（可运行闭环）

| 编号 | 功能 | 验收 |
| --- | --- | --- |
| P0-01 | 书城/详情/阅读器 | 能从书城进入正文、切章、返回 |
| P0-02 | 作者工作室/作品/编辑器 | 进入作品、写章、自动保存、返回不丢 |
| P0-03 | 发布确认 | 空稿阻止；确认 Sheet；来源标记 |
| P0-04 | 三模式入口 | 传统/辅助/纯 AI 可切换；传统不调用 AI |
| P0-05 | AI 演示工作流 | 阶段可见；候选可采纳/退回/重生成 |
| P0-06 | 素材/灵感 | 浏览、搜索、记灵感、引用反馈 |
| P0-07 | 数据看板 | 阅读/收藏/收益/排名/趋势；日周月切换 |
| P0-08 | 我的/设置/评论 | 书架、设置、导出、回复演示 |
| P0-09 | Android 构建 | `assembleDebug` 已产出 APK；真机安装/启动待验证 |

### 15.2 P1（真实能力）

- 真实 API：作品/章节/草稿/送审/进度/通知。
- 登录：OIDC PKCE + 设备会话 + 安全存储。
- 数据看板：真实聚合接口与时间序列。
- AI：服务端 `createAuthorAiRun`、SSE、候选接受、记忆审阅。
- 素材/灵感：服务端同步、引用记录。
- 书架/搜索：服务端契约。
- AI 插图：真实图像模型、审核、来源。
- 导出：服务端任务与受控下载。

### 15.3 P2（明确不做/后置）

- 离线整本下载/加密许可。
- 外部推送/厂商通道。
- 支付/IAP/订阅/打赏。
- 音频/TTS/听书。
- 批量多章生成、桌面级大纲、富文本。
- 审核/运营移动端。
- iOS 实现与上架。
- 个性化推荐/行为画像。

### 15.4 里程碑

| 切片 | 内容 | 状态 |
| --- | --- | --- |
| M0 | React 移动壳 + 设计系统 + 书城/阅读器 | 已实现 |
| M1 | 作者工作室 + 编辑器 + 发布确认 + 素材 | 已实现 |
| M2 | 三模式 + AI 演示 + 插图概念图 + 评论 | 已实现 |
| M3 | Android 工程 + APK + 真机验收 | APK 已构建；真机验收待设备 |
| M4 | 真实 API/AI/登录/看板 | P1 |

---

## 16. 测试与验收

### 16.1 已执行

| 项 | 命令 | 结果 |
| --- | --- | --- |
| TypeScript + 移动构建 | `npm run build:mobile` | 通过；49 modules；dist-mobile 生成 |
| Web 构建回归 | `npm run build` | 通过；45 modules；dist 生成；存在既有动态导入警告 |
| Capacitor Android 生成 | `npx cap add android` | 成功；9 个插件识别 |
| 资产同步 | `npx cap sync android` | 成功；`index.html` 相对路径、favicon 已进入 APK 资产 |
| Android 构建 | `gradlew.bat assembleDebug --no-daemon` | **BUILD SUCCESSFUL**；363 tasks |
| APK 产物 | `app-debug.apk` | 4,717,095 bytes；SHA-256 `4AF9CA36C0DDB5F4638C9A7DA3A08FDA13315F4A2652C8C7FDC1BE79C367598A` |
| APK 元数据 | `aapt dump badging` | `com.leyon.lumin` / 1.0 / minSdk 24 / targetSdk 36 / 启明书院 |
| Capacitor Doctor | `npx cap doctor` | Android looking great |
| 品牌资源 | 图标 + 启动图 | 朱印图标、纸色启动图；已覆盖 Capacitor 默认资源 |
| 移动视口/流程 | Playwright 390×844，deviceScaleFactor 2 | 17 张流程截图；`innerWidth=390 / scrollWidth=390` |
| 控制台 | Playwright console | **0 错误**（favicon 404 已修复） |

### 16.2 待执行

| 项 | 验收 |
| --- | --- |
| 真机安装 | Android 24/36 各一台；安装启动、安全区/键盘/返回键/滚动 |
| WebView 性能 | 冷启动 <2.5s；长正文滚动 60fps；无 ANR |
| 弱网 | 断网显示本地/错误，不假装已同步 |
| 无障碍 | 200% 字体、320px 宽、TalkBack |
| release 签名 | keystore 不入库；CI secret 注入；商店物料 |


### 16.3 测试用例（节选）

| ID | 场景 | 期望 |
| --- | --- | --- |
| T-01 | 书城 → 详情 → 阅读 | 路由正确、返回栈正确、页首复位 |
| T-02 | 阅读器切章 | 标题/正文/进度更新，滚动到顶部 |
| T-03 | 编辑器输入 | 1s 后“已保存”；退出再进内容保留 |
| T-04 | 发布空稿 | 阻止并提示 |
| T-05 | 模式切换 | 传统不生成；辅助/纯 AI 生成候选 |
| T-06 | 采纳候选 | 只写入候选，不自动发布；重复段落去重 |
| T-07 | 记忆 delta | 进入待确认；锁定记忆不被覆盖 |
| T-08 | 素材引用 | Toast/引用记录；不改正文 |
| T-09 | 评论回复 | Sheet 发送，列表更新 |
| T-10 | 硬件返回 | 依次回退，栈空回首页，再退出 |

---

## 17. 风险与决策登记

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| WebView“套壳”质疑 | 简历/评审观感 | 强调一套 React、本地资产、原生插件、APK；不冒充原生 |
| Android 键盘遮挡 | 编辑器不可用 | Keyboard resize + safe-area + 底部 dock 抬升 |
| 长文滚动性能 | 低端机卡顿 | 纯文本、虚拟化/分段（P1）、避免大图 |
| 真实 AI 不可用 | 演示像假功能 | UI 明确“本地演示”；接口可替换 |
| 记忆一致性 | 长篇前后矛盾 | 事实条目 + 版本 + 冲突检测；不承诺零幻觉 |
| 版权/训练合规 | 法律风险 | 作者保留；训练默认关闭；来源 append-only |
| 商店审核 | 无法上架 | 备案/隐私/内容资质单独立项；不承诺过审 |
| 双基线冲突 | 文档不一致 | `docs/16` 标注历史；`docs/17` 为当前技术口径 |

**决策记录**：

- DEC-17-01：Android-only；iOS 只预留。
- DEC-17-02：Capacitor + React；不生成原生双端。
- DEC-17-03：AI 全部服务端；移动端只做任务台/候选确认。
- DEC-17-04：纯 AI 不自动发布；作者确认是唯一写稿入口。
- DEC-17-05：数据看板先做可解释指标，不做假同比。

---

## 18. 运行手册

```powershell
cd lumin-frontend

# 安装依赖
npm install

# 移动开发（浏览器）
npm run dev:mobile
# http://127.0.0.1:5174

# 构建 + 同步 + 打开 Android Studio
npm run build:mobile
npx cap sync android
npm run cap:open

# 只构建 APK
cd android
.\gradlew.bat assembleDebug
```

产物：

- `lumin-frontend/dist-mobile/`：移动 Web 资产；
- `lumin-frontend/android/app/build/outputs/apk/debug/app-debug.apk`：debug APK；
- Web：`lumin-frontend/dist/`，不受移动改造影响。

本地演示说明：

- 书目、章节正文、作者数据、评论、AI 候选均为原创演示数据；
- `src/mobile/services/aiDemo.ts` 是本地演示引擎，不调用真实模型；
- `src/mobile/services/storage.ts` 使用 Capacitor Preferences；
- 替换真实 API 时，保留屏幕组件与类型，替换 `services/` 与 `data/` 数据源。

---

## 附录 A. 页面与代码映射

| 页面 | 文件 |
| --- | --- |
| 书城 | `src/mobile/screens/HomeScreen.tsx` |
| 搜索 | `SearchScreen.tsx` |
| 作品详情 | `BookDetailScreen.tsx` |
| 阅读器 | `ReaderScreen.tsx` |
| 作者工作室 | `StudioScreen.tsx` |
| 作品管理 | `WorkScreen.tsx` |
| 写作中心 | `EditorScreen.tsx` |
| AI 创作台 | `AiScreen.tsx` |
| 素材/灵感 | `MaterialsScreen.tsx` |
| AI 插图 | `IllustrationScreen.tsx` |
| 我的 | `MeScreen.tsx` |
| 阅读设置 | `SettingsScreen.tsx` |
| 评论 | `CommentsScreen.tsx` |
| 路由/状态 | `MobileApp.tsx`、`navigation.ts` |
| 组件 | `components/ui.tsx`、`components/Icons.tsx` |
| 数据 | `data/library.ts`、`data/studio.ts` |
| 演示 AI | `services/aiDemo.ts` |
| 本地存储 | `services/storage.ts` |

## 附录 B. 与 `15` 的映射

| `15` 章节 | 本文 |
| --- | --- |
| §8 三模式 | §5 |
| §9 AI 能力 | §9 |
| §10 纯 AI workflow/Agent | §5.5 |
| §11 长期记忆 | §5.6 |
| §13 接口 | §11 |
| §15 优先级 | §15 |

## 附录 C. 未验证项

1. Android 真机/模拟器启动、键盘、安全区、硬件返回。
2. release 签名与商店审核。
3. 真实 Java API 联调（当前演示数据）。
4. 真实 LLM/图像模型、预算、供应商合规。
5. iOS 任何实现。
6. 离线、推送、支付、音频。
