# 启明书院 · Lumin Academy

P0 阶段仓库（P0-A 已交付，P0-B/C 实现中，P0-D 未开始）。后端与前端分别收敛在 `lumin-backend/` 与 `lumin-frontend/` 两个目录；`contracts/`、`docs/`、
`libs/` 是只读契约、设计文档与静态校验工具，不参与运行时。

## 目录结构

```text
lumin-academy/
├── lumin-backend/            # 后端：Spring Boot 4.0.9 + Java 21，模块化单体（2026-11-18）
│   ├── pom.xml               #   parent POM（pom packaging，五模块 + JUnit + 可选 JaCoCo）
│   ├── README.md             #   分层口径、依赖清单、边界与执行证据
│   ├── lumin-domain/         #   纯领域：聚合/值对象/状态机/领域策略（零 Spring/MyBatis）
│   ├── lumin-application/    #   应用层：用例编排、DTO、出站端口与 MyBatis 仓储契约
│   ├── lumin-infrastructure/ #   基础设施：Mapper 扫描、文件存储、Outbox、AI 持久化适配
│   ├── lumin-ai/             #   AI：供应商路由、workflow + multi-agent、长期记忆端口
│   ├── lumin-bootstrap/      #   唯一启动模块：Controller、application.yaml、db/migration、测试
│   │   ├── src/main/java/com/leyon/lumin/
│   │   │   ├── LuminBackendApplication.java   #   Spring Boot 启动类
│   │   │   └── {module}/api      #   接入层 Controller（catalog/authoring/.../ai）
│   │   └── src/main/resources/    #   application.yaml + db/migration/V1–V14（15 个脚本，V7 重号待并）
│   └── （旧单模块 src/ 已移除）
├── lumin-frontend/           # 前端：React 19 + TypeScript + Vite，纵向 feature + 共享层 + 展示壳（见 docs/19）
│   ├── android/              # Android 壳：Capacitor 8 + Gradle 8.14.3（2026-11-18）
│   ├── src/mobile/           # Android 移动端页面/组件/演示服务（2026-11-18）
│   ├── dist-mobile/          # 移动端构建产物（npm run build:mobile）
│   ├── capacitor.config.ts   # App ID / WebView / Android 配置
│   ├── scripts/              # Playwright 截图、Android 品牌资源生成
│   ├── index.html            # 入口（lang=zh-CN，主题色 #F4F0E7）
│   ├── vite.config.ts        #   dev 5173 / host 开 / 构建出 dist + sourcemap
│   ├── eslint.config.js      #   ESLint 10 + typescript-eslint
│   ├── src/
│   │   ├── main.tsx          #   挂载入口
│   │   ├── app/              #   App.tsx 根组件（状态驱动路由、#live 播报）
│   │   ├── features/         #   按业务域：reader(R01–R03)/author/reviewer/operator/account
│   │   ├── components/       #   跨域通用件：layout(TopNav/BottomTab/SiteFooter)、icons/Icon
│   │   ├── lib/              #   demoPolicy 唯一判定模块
│   │   ├── services/         #   API 客户端层骨架（契约信封/http 封装，未接线待联调）
│   │   ├── mocks/            #   合成夹具（不发网络请求）
│   │   ├── types/            #   展示类型
│   │   └── styles/           #   tokens/global/components（精确令牌不变）
│   └── README.md             #   目录职责、与后端边界、合规约束
├── contracts/
│   └── p0.openapi.yaml       # P0 唯一机器契约（只读，禁止修改；校验脚本比对 SHA256）
├── docs/                     # 01–22 设计文档（需求/概要/详细/库表/接口/原型/测试/部署/阶段规划/P0-A/工作台/移动端/多模块与AI/契约对账）
├── libs/
│   ├── error-codes.json      # 错误码字典（14 条 = OK + 13）
│   ├── contract-route-baseline.json  # 契约—路由对账基线（UGC-001/026）
│   └── tools/                # validate_contract.py / check_dependency_cycles.py / route_contract_matrix.py
├── ui-demo/                  # 单文件 HTML UI 原型（历史物料，不参与构建）
├── 启明书院_设计方案.md
├── 启明书院_团队守则.md
└── 启明书院_项目基线.md
```

> **模块化重构（2026-11-18）**：`lumin-backend` 从单 Maven 模块拆为 `lumin-domain`、`lumin-application`、`lumin-infrastructure`、`lumin-ai`、`lumin-bootstrap` 五模块；`lumin-bootstrap` 是唯一可执行 Boot JAR，`domain` 不依赖 Spring，AI 可独立替换供应商。旧的“单模块内按六层包分层”口径已被本结构取代。

## 常用命令

```text
# 后端（默认在 lumin-backend/ 下执行）
cd lumin-backend
mvn test                    # 全量多模块 JUnit 回归（bootstrap 3514 + application 3 + ai 3；增量三十六至三十八另加身份/年龄/监护集成用例）
mvn -pl lumin-bootstrap -am spring-boot:run   # 启动唯一可执行模块
mvn verify -Pcoverage       # 追加 JaCoCo 0.8.15 覆盖率报告（各模块 target/site/jacoco/）

# 前端（默认在 lumin-frontend/ 下执行）
cd lumin-frontend
npm run dev                 # http://localhost:5173
npm run build               # tsc -b 类型检查 + vite 生产构建
npm run lint                # eslint + 前端依赖边界检查
npm run check:architecture  # 只检查 feature / 共享层 / 展示壳依赖方向

# Android 移动端
npm run dev:mobile          # http://localhost:5174
npm run build:mobile        # 输出 dist-mobile
npx cap sync android
cd android
.\gradlew.bat assembleDebug

# 只读静态校验（在仓库根目录执行）
python libs/tools/validate_contract.py
python libs/tools/check_dependency_cycles.py
python libs/tools/route_contract_matrix.py --check   # 契约—路由基线（UGC-001/026）
python libs/tools/check_dependency_cycles.py --inject "foundation->identity"   # 负向对照，预期退出码 1
```

后端首次构建需联网或由 IDE 下载 `spring-boot-starter-parent:4.0.9` 及其依赖；本机 `~/.m2` 只缓存到
4.0.6 / 4.1.x，离线 `mvn -o` 会在父 POM 解析阶段失败，缓存就绪后即可完全离线。

## Android 移动端（2026-11-18）

- 技术路线：**Java 21 + Spring Boot 4.0.9 后端 + React 19 + TypeScript + Capacitor 8 Android 壳**；iOS 仅在 `capacitor.config.ts` 与设计文档中预留，不实现。
- 当前范围：读者 + 作者 + 账号；审核/运营保留 Web 工作台。
- 设计文档：`docs/17-Android移动端实施设计（Java+React+Capacitor）.md`；产品/页面/三模式补充见 `docs/16`，作者工作台见 `docs/15`；前端分级与依赖边界见 `docs/19`。
- APK：`lumin-frontend/android/app/build/outputs/apk/debug/app-debug.apk`；debug 包名 `com.leyon.lumin`，minSdk 24，targetSdk 36。
- Android 工具链：SDK 36 + build-tools 36.0.0 + AGP 8.13.0 + Gradle wrapper 8.14.3；中文项目路径已用 `android.overridePathCheck=true` 放行，Maven 依赖优先走阿里云镜像。
- 移动端构建与 Web 构建互不影响：`npm run build:mobile` 输出 `dist-mobile`，`npm run build` 输出 `dist`。
- 演示边界：移动端当前使用本地原创书目/作者/AI 演示数据，不调用真实 LLM、图像模型或 Java API；替换方式见 `docs/17` §11 与 `src/mobile/services/`。

## 后端模块与 AI（2026-11-18）

- 模块边界：`lumin-domain`（零 Spring/MyBatis）→ `lumin-application`（用例、DTO、出站端口与 MyBatis 仓储契约）→ `lumin-infrastructure`（Mapper 扫描/文件存储/Outbox/AI 持久化）→ `lumin-bootstrap`（唯一 Boot 启动与 Controller）。`lumin-ai` 依赖 domain/application，不被 infrastructure 反向依赖。
- 构建产物：仅 `lumin-bootstrap` 打可执行 Boot JAR；其余模块为普通 jar；`mvn test` 由父 POM 统一执行。
- AI 供应商：`lumin.ai.provider=ollama`（默认，`deepseek-r1:8b`，`http://127.0.0.1:11434`）；切换 DeepSeek 官方 API 只改 `LUMIN_AI_PROVIDER=deepseek` + `DEEPSEEK_API_KEY`，workflow 与 Agent 不变。
- AI 端点：`GET /api/v1/ai/health`、`POST /api/v1/ai/runs`（异步 202）、`GET /api/v1/ai/runs/{runId}`、`POST /api/v1/ai/runs/{runId}/candidates/{candidateId}/accept|reject`、`GET /api/v1/ai/memory/{workId}`、`POST /api/v1/ai/memory/{workId}/facts/{factId}/confirm`。
- 运行原则：AI 只产出 `ai_candidate`，不直接改写 `draft/chapter`；长期记忆抽取先落 `memory_fact(status=CANDIDATE)`，作者确认后 `ACTIVE`。
- 数据库：`V7__ai_authoring.sql` 新增 `ai_run`、`ai_run_stage`、`ai_candidate`、`memory_fact`、`memory_event` 五表。
- 设计文档：`docs/18-后端多模块与AI模块设计（Java+Ollama）.md`。

## 本次整理记录（2026-11-13，历史记录）

> 以下路径/命令为 2026-11-13 时点口径，已被 2026-11-18 五模块结构取代，仅作历史留痕。

| 项 | 整理前 | 整理后 |
| --- | --- | --- |
| 后端领域代码 | `services/core/src/{main,test}/java/com/leyon/lumin/**`（独立 jar 模块，`lumin-core-domain-0.1.0-P0A`） | `lumin-backend/src/{main,test}/java/com/leyon/lumin/**`；**包名未改** |
| 后端包根 | `com.leyon` 下并列 `lumin`（领域内核）与 `lumin_backend`（启动类）两个包 | 合并为单一根包 `com.leyon.lumin`：启动类移到 `com.leyon.lumin.LuminBackendApplication`，删除 `lumin_backend` 包（下划线包名也不符合 Java 命名规范）；组件扫描根即 `com.leyon.lumin` |
| 后端构建 | `services/core/pom.xml`（纯 Java + JUnit + JaCoCo） | 合并进 `lumin-backend/pom.xml`：保留 Spring Boot 父 POM、Web/MyBatis/MySQL 依赖，新增 `junit-jupiter` / `junit-platform-launcher`（版本由 Boot BOM 管理）、`surefire` 的 `@{argLine}`、可选 `coverage` profile |
| 后端文档 | `services/core/README.md` | 改为 `lumin-backend/README.md`，补写「同模块内含 Spring Boot 应用、领域包零 Spring 依赖」并更新命令与路径 |
| 前端应用 | `web/src/**` + `web/index.html`（`qiming-shuyuan-web`） | `lumin-frontend/src/**` + `lumin-frontend/index.html`，整体接管脚手架模板文件（`App.tsx` / `App.css` / `index.css` / `assets/*.svg` / `public/*.svg` 已移除） |
| 前端构建 | `web/vite.config.ts` + 单份 `tsconfig.json` | 沿用脚手架工具链（Vite 8 / TypeScript 6 / ESLint 10，`tsc -b && vite build`），`vite.config.ts` 补回 dev 端口 5173、`host: true`、`sourcemap: true` |
| 工具脚本 | `check_dependency_cycles.py` 扫描 `services/core/src/main/java` | 改为扫描 `lumin-backend/src/main/java`（`libs/README.md`、`libs/tools/README.md` 同步更新） |
| 旧目录 | `services/`、`web/`（仅剩 `target/`、`dist/`、`node_modules/`、旧 `package.json`/锁文件） | 已删除；旧构建产物与旧依赖锁不再保留，依赖版本以 `lumin-frontend/package.json` + `package-lock.json` 为准 |
| 单测路径 | `DependencyAcyclicTest.scan()` 硬编码 `services/core/src/main/java` | 改为 `lumin-backend/src/main/java`，否则架构测试会「扫不到源码」而失败 |

## 口径对齐修订（2026-11-15，Owner 授权）

2026-11-13 整理时登记的「文档与仓库口径差异」已逐项修复：文档正文就地对齐 + 各文档头部/对应章节附修订记录留痕，2026-11-12 的历史实测记录语义不改写。

| 差异 | 修复方式 | 涉及 |
| --- | --- | --- |
| 目录基线：docs/02 §4、docs/09 §5、docs/10 A/B、项目基线 §3 按旧 `services/core/** + web/**` 书写 | 正文路径改为 `lumin-backend/**` / `lumin-frontend/**`；`services/agent/`、`mobile/`、`infra/` 标注「规划，尚未创建」；各文档加修订记录 | docs/02、docs/09、docs/10、项目基线 |
| operationId 基数：docs/10 七处写 74，契约实测 63（唯一） | docs/10 全部改为 63（契约未动，SHA256 校验 PASS）；`validate_contract.py` 与 `ContractSkeletonTest` 的 `DOC_OPERATION_ID_COUNT` 同步 74→63，WARN 文案改为「核查文档是否过期或契约是否被改动」 | docs/10、libs/tools、lumin-backend 测试 |
| 文件数：docs/10 A.2 汇总「新建 57 个」 | 按逐项求和 7+17+9+7+6+10 改为 **56**，注明原汇总误写 | docs/10 |
| 版本口径：docs/02 §2 / docs/09 头部 / docs/10 §0.2 写 Spring Boot 4.1.2 / TS 7.0.2 / Vite 8.3.8 | 按实际交付对齐（Spring Boot 4.0.9、TS ~6.0.3、Vite ^8.3.6）；docs/02 新增 §2.2 对齐表保留原锁定书写 | docs/02、docs/09、docs/10 |
| B2005 语义两读：docs/03 §7「不可恢复传播→B2005」与状态机「未命中一律 B2001」 | docs/03 §7 表后加口径澄清：B2001 为 P0-A 状态机层、B2005 为 P0-B 服务层对外映射，两层不冲突 | docs/03 |
| 环境版本（W-07）：2026-11-12 实测 Node v22.22.2 / Python 3.13.14 与 08 §4.2 不一致 | docs/08 §4.2 与 docs/10 §0.4 补 2026-11-15 复核行（Node v24.15.0 / Python 3.11.9 / npm 11.12.1，与 08 §4.2 一致），差异判断为终端/PATH 环境差异，W-07 收敛为「npm 小版本漂移」；版本锁定一律以 lockfile/BOM 为准 | docs/08、docs/09、docs/10 |
| docs/09 §4.2/§4.3「无业务代码、用例未执行」状态过时 | 就地加 2026-11-15 修订注记：P0-A 已交付（3322 用例全绿），P0-B/C/D 未开始 | docs/09 |

## 与设计文档的口径差异（历史登记，2026-11-13）

> 本节为 2026-11-13 整理时的原始登记，所列差异已于 2026-11-15 全部修复（见上表），原文保留备查。

`docs/02-概要设计说明书.md §4`、`docs/09-阶段规划解读.md`、`docs/10-P0A-设计与任务分解.md`、
`启明书院_项目基线.md` 仍按历史的 `services/core/**` + `web/**` + `services/agent/**` 布局书写。
这些文档是阶段基线（含已交付任务清单与文件编号），本次整理**未改动其正文**，以免篡改验收记录；
实际目录以本 README 为准。若需让文档与仓库一致，属于基线变更，应由 Owner / 架构师单独裁决后批量修订。

## 阶段边界

- 契约 `contracts/p0.openapi.yaml` 只读；校验脚本前后比对 SHA256 证明未被修改。
- **2026-11-19 更新**：P0-B/C 已落地 Controller / Service / Repository / V1–V14 DDL；`docs/22` 对账为契约 63 operationId、路由实现 50、缺失 13、契约外 20。P0-A「只落地领域层纯函数」仅代表 2026-11-15 时点。
- 前端书城/作者/审核/运营/账号已接真实 API，但仍保留 `src/mocks` 合成夹具与移动端本地演示路径；服务端类型应从契约生成，不在前端手写业务判定。
- 未做浏览器全旅程验收、真机验收与生产接口联调；3520 个后端测试通过只代表当前工作区自动化证据（增量三十六至三十八另加身份/年龄/监护集成用例，未配置 `lumin_test` 时跳过）。2026-11-18 更新：移动端已完成 Playwright 17 张流程截图与 `npm run build:mobile` / `npm run build`；Android 真机与 Java API 联调仍未完成。
