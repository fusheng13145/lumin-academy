# 启明书院 · Lumin Academy

P0-A 阶段仓库。后端与前端分别收敛在 `lumin-backend/` 与 `lumin-frontend/` 两个目录；`contracts/`、`docs/`、
`libs/` 是只读契约、设计文档与静态校验工具，不参与运行时。

## 目录结构

```text
lumin-academy/
├── lumin-backend/            # 后端：Spring Boot 4.0.9 Web 应用 + P0-A 纯 Java 21 领域内核
│   ├── pom.xml               #   单一 Maven 模块（Web 依赖 + JUnit 5.12.2 + 可选 JaCoCo）
│   ├── README.md             #   领域内核口径、依赖清单、边界与执行证据
│   ├── src/main/java/com/leyon/lumin/
│   │   ├── LuminBackendApplication.java   #   Spring Boot 启动类（根包，后续 Controller / Service 放此包下）
│   │   ├── foundation/       #   错误码 / 业务时间 / 14 个共享枚举
│   │   ├── identity/domain/  #   AgePolicy / RolePolicy
│   │   ├── publication/domain/ #  VisibilityPolicy / PublicationStateMachine
│   │   └── billing_reserved/ #   04 §6.2 六表只读骨架
│   ├── src/main/resources/   #   application.yaml（spring.application.name=lumin-backend）
│   └── src/test/java/com/leyon/lumin/
│       ├── LuminBackendApplicationTests.java  # 启动类冒烟测试
│       └── {architecture,foundation,identity,publication,billing_reserved,contracts,support}/  # 3322 用例
├── lumin-frontend/           # 前端：React 19 + TypeScript + Vite
│   ├── index.html            #   入口（lang=zh-CN，主题色 #E8453B）
│   ├── vite.config.ts        #   dev 5173 / host 开 / 构建出 dist + sourcemap
│   ├── eslint.config.js      #   ESLint 10 + typescript-eslint
│   ├── src/                  #   App / views / components / data(mock) / styles(tokens)
│   └── README.md             #   目录职责、与后端边界、合规约束
├── contracts/
│   └── p0.openapi.yaml       # P0 唯一机器契约（只读，禁止修改；校验脚本比对 SHA256）
├── docs/                     # 01–11 设计文档（需求/概要/详细/库表/接口/原型/测试/部署/阶段规划/P0-A）
├── libs/
│   ├── error-codes.json      # 错误码字典（14 条 = OK + 13）
│   └── tools/                # validate_contract.py / check_dependency_cycles.py（Python3 标准库）
├── ui-demo/                  # 单文件 HTML UI 原型（历史物料，不参与构建）
├── 启明书院_设计方案.md
├── 启明书院_团队守则.md
└── 启明书院_项目基线.md
```

## 常用命令

```text
# 后端（默认在 lumin-backend/ 下执行）
cd lumin-backend
mvn test                    # 编译 + 全量 JUnit 回归
mvn verify -Pcoverage       # 追加 JaCoCo 0.8.15 覆盖率报告（target/site/jacoco/）

# 前端（默认在 lumin-frontend/ 下执行）
cd lumin-frontend
npm run dev                 # http://localhost:5173
npm run build               # tsc -b 类型检查 + vite 生产构建
npm run lint

# 只读静态校验（在仓库根目录执行）
python libs/tools/validate_contract.py
python libs/tools/check_dependency_cycles.py
python libs/tools/check_dependency_cycles.py --inject "foundation->identity"   # 负向对照，预期退出码 1
```

后端首次构建需联网或由 IDE 下载 `spring-boot-starter-parent:4.0.9` 及其依赖；本机 `~/.m2` 只缓存到
4.0.6 / 4.1.x，离线 `mvn -o` 会在父 POM 解析阶段失败，缓存就绪后即可完全离线。

## 本次整理记录（2026-11-13）

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
- P0-A 只落地领域层纯函数：无 Controller / Service / Repository 实现、无 DDL、无交易入口、无供应商 SDK。
- 前端当前使用 `src/data/mock.ts` 合成夹具，不发起网络请求；服务端类型应从契约生成，不在前端手写业务判定。
- 未做浏览器验收、真机验收与接口联调；单测通过只代表纯函数在合成夹具下行为正确。
