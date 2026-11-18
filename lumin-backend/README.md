# lumin-backend · 启明书院后端

> **2026-11-18 模块化重构：本后端已从单 Maven 模块拆为 5 个模块，唯一可执行 Boot JAR 由 `lumin-bootstrap` 产出。**
> **模块**：`lumin-domain`（纯领域）→ `lumin-application`（用例/端口/MyBatis 契约）→
> `lumin-infrastructure`（Mapper 扫描/文件存储/Outbox/AI 持久化）→ `lumin-bootstrap`（Controller/启动/资源/测试）；
> `lumin-ai` 依赖 domain/application，承载供应商、workflow、multi-agent 与长期记忆。
> **原“单模块内按六层包分层”的描述（2026-11-17）已作为历史记录保留在文末证据表，不再代表当前结构。**
> 详细设计见 `docs/18-后端多模块与AI模块设计（Java+Ollama）.md`。

## 离线命令

全部依赖与插件为本地已缓存版本（02 §2.1 / 08 §4.1）：

```text
cd lumin-backend
mvn test                        # 主验收命令：全量多模块 JUnit 回归（bootstrap 3478 + ai 3）
mvn -pl lumin-bootstrap -am spring-boot:run   # 启动唯一可执行模块
mvn -DskipTests package         # 产出 lumin-bootstrap/target/*.jar
mvn verify -Pcoverage           # 可选：JaCoCo 0.8.15 覆盖率报告（各模块 target/site/jacoco/）
```

首次构建需联网或由 IDE 下载 `spring-boot-starter-parent:4.0.9` 及其依赖；**本机已于 2026-11-15 联网完成首次下载并全量回归，
`mvn test` 现可完全离线复跑**（`mvn -o test`）。

回到项目根目录执行只读静态校验：

```text
python libs/tools/validate_contract.py
python libs/tools/check_dependency_cycles.py
python libs/tools/check_dependency_cycles.py --inject "foundation->identity"
python libs/tools/check_dependency_cycles.py --inject "moderation->authoring.repository"
```

## 依赖清单（全部钉版本）

| 项 | 版本 | 用途 |
| --- | --- | --- |
| JDK | 21.0.6（`release=21`） | `record` / `sealed` / `switch` 表达式 |
| Spring Boot | 4.0.9（`spring-boot-starter-parent`） | Web 运行时、依赖管理 |
| Spring Web MVC | `spring-boot-starter-webmvc` | HTTP 入口（后续批次落地） |
| MyBatis | `mybatis-spring-boot-starter` 4.0.1 | 数据访问（后续批次落地） |
| MySQL 驱动 | `mysql-connector-j`（BOM 管理，runtime） | 数据源 |
| `junit-jupiter` | 5.12.2（test） | 唯一测试框架；`@ParameterizedTest` + `@MethodSource` 穷举 |
| `junit-platform-launcher` | 1.12.2（test） | Surefire JUnit Platform provider |
| `maven-surefire-plugin` | 3.5.6 | 执行测试（`@{argLine}` 延迟替换） |
| `jacoco-maven-plugin` | 0.8.15（**仅 `-Pcoverage`**） | 可选覆盖率；缺件不阻断 `mvn test` |

**领域包明确不引入**：任何 Spring 运行时注解、JDBC / PostgreSQL / MyBatis、Redis / RabbitMQ 客户端、
HTTP 服务器或客户端、OIDC / 对象存储 / 机审供应商 SDK、任何对象模拟类库、PyYAML、任何 Node 或前端依赖。
02 §2 的全栈版本锁定对整个 P0 有效，P0-A 只作为目录与依赖方向约束，不落地为运行时依赖。

## 模块与目录

| 模块 / 路径 | 内容 |
| --- | --- |
| `lumin-domain/` | 纯领域：聚合、值对象、表驱动状态机、领域策略；零 Spring/MyBatis；唯一例外 `ConfigPayloadValidator` 使用 Jackson |
| `lumin-application/` | 用例编排、事务边界、DTO/VO、出站端口、MyBatis Mapper 契约与 Row；`foundation/contract/ApiEnvelope` 在此 |
| `lumin-infrastructure/` | `@MapperScan` 配置、本地对象存储、Outbox 调度、AI 持久化（`MyBatisAiRunStore`/`MyBatisAiMemoryStore` 及 5 个 Mapper） |
| `lumin-ai/` | `AiProvider`（Ollama/DeepSeek）、`AiProviderRouter`、workflow + 4 Agent、长期记忆端口、AI 视图对象 |
| `lumin-bootstrap/` | `LuminBackendApplication`、全部 Controller、`application.yaml`、`db/migration/V1–V7`、3478 个测试 |
| `lumin-bootstrap/src/main/resources/db/migration/` | 版本化迁移脚本：V1 核心 16 表、V2 role_assignment、V3 治理/申诉/通知、V4 billing 骨架、V5 阅读进度、V6 审计、V7 AI 五表 |
| `lumin-bootstrap/src/test/java/com/leyon/lumin/**` | 纯函数穷举、架构无环、契约骨架、MockMvc、真实 MySQL 集成（凭据 `LUMIN_TEST_DB_*`） |

模块方向：`bootstrap -> {domain, application, infrastructure, ai}`；`infrastructure -> {application, ai}`；
`ai -> {domain, application}`；`application -> domain`；`domain` 不依赖任何框架。
唯一真实跨业务域边 `publication -> identity` 仍在允许列表内；AI 模块允许 `ai -> foundation`（共享错误码/时间/契约）。
`DependencyAcyclicTest` 与 `libs/tools/check_dependency_cycles.py` 同时扫描五模块，双证据。

## AI 模块（2026-11-18）

```text
lumin-ai/src/main/java/com/leyon/lumin/ai/
├── domain/      AiMode / AiRunState / AiStageType / AiCandidateState / MemoryKind / MemoryStatus ...
├── port/        AiRunStore / AiMemoryStore（由 infrastructure 实现）
├── provider/    AiProvider / OllamaAiProvider / DeepSeekAiProvider / AiProviderRouter
├── config/      AiProperties / AiConfiguration（固定 2 线程 daemon 池）
└── workflow/    AiWorkflowService / AiMemoryService / 4 Agent / PromptLibrary / AiMemoryParser
```

- 文本供应商默认 Ollama：`LUMIN_AI_PROVIDER=ollama`，`OLLAMA_MODEL=deepseek-r1:8b`。
- 切换 DeepSeek：`LUMIN_AI_PROVIDER=deepseek` + `DEEPSEEK_API_KEY`，workflow 不变。
- AI 只产出 `ai_candidate`，不直接写 `draft/chapter`；记忆先 `CANDIDATE`，作者确认后 `ACTIVE`。
- 异步 API：`POST /api/v1/ai/runs`（202）→ `GET /api/v1/ai/runs/{runId}` 轮询；候选 `accept/reject` 只改状态，正文写入仍走既有草稿接口。
- 真实联调：本地 MySQL 已应用 V7；一次 ASSISTED 运行产出 1319 字候选并进入 `WAITING_AUTHOR`，`accept` 端点验证为 `ACCEPTED`。R1 对 `CONSISTENCY`/`MEMORY_EXTRACT` 可能空输出，当前按降级策略继续，不阻断正文候选。
- 迁移：`V7__ai_authoring.sql`（`ai_run`、`ai_run_stage`、`ai_candidate`、`memory_fact`、`memory_event`）。

```bash
curl http://127.0.0.1:8080/api/v1/ai/health
curl -X POST http://127.0.0.1:8080/api/v1/ai/runs \
  -H 'Content-Type: application/json' -H 'X-Demo-Subject: demo-author' \
  -d '{"workId":"<work-id>","chapterId":"<chapter-id>","mode":"ASSISTED","instruction":"写一场雪夜对峙"}'
```

## 时间口径

**任何时间都不得读取当前系统时间或使用实时系统时钟**：全部由 `FixedFixtures.BUSINESS_CLOCK`
（`2026-11-11T16:00:00Z` = `2026-11-12T00:00:00+08:00`）注入；业务自然日 `Asia/Shanghai`，
周岁按公历生日当天变更，`2/29` 出生非闰年按 `3/1`（01 §3.2 工程候选，法务 W-01 冻结前不开放年龄升级）。

## 边界与诚实声明

- 测试通过**只证明纯函数在合成夹具下的行为正确**，不等于接口联调、三平台旅程或上线验收（07 §1、05 §1、N-05）。
- 契约 `contracts/p0.openapi.yaml` **只读**，禁止修改；校验脚本前后比对 SHA256。
- 不执行 DDL / 建表 / 写库，不暴露交易入口，不授予 `BILLING_*` / `SETTLEMENT_EXECUTE`，不执行 B1–B6 / R1–R10 / A4。
- 状态机只做 `(state, event, ctx) → 目标态` 纯映射，**不含机审阈值/分级/自动放行、不产生公开指针**（N-02 / K-03）；
  `raiseIncident` 仅布尔信号，事件由调用方追加。
- **不做任何 git 操作**（`init` / `commit` / `push` / `tag`）；每批提交须另获用户逐批授权（03 §8、08 §6.3、N-04）。
- 未进行浏览器或设备验收；本模块不产生任何前端代码（A.6）。

## 本轮执行证据与口径差异（如实登记）

| 项 | 实测结果 |
| --- | --- |
| **多模块重构 + AI 模块（2026-11-18）** | **3481 用例 / 0 失败 / 0 错误 / 0 跳过**（bootstrap 44 套件 3478 用例 + ai 1 套件 3 用例；本轮注入 `LUMIN_TEST_DB_PASSWORD`，61 个 MySQL 集成测试全部执行）；`mvn -DskipTests package` 产出 `lumin-bootstrap-0.0.1-SNAPSHOT.jar`（26,211,170 bytes）；Ollama `deepseek-r1:8b` 实际 `/api/chat` 返回中文；启动 JAR 后 `GET /api/v1/ai/health` 返回 `provider=ollama, model=deepseek-r1:8b, available=true`；真实 AI run 达 `WAITING_AUTHOR` 并生成 1319 字候选；架构校验扫描 257 Java / 0 非法边 / 0 环 |
| 领域内核回归（2026-11-13，迁移至 `lumin-backend` 后） | **3322 用例 / 0 失败 / 0 跳过**；`javac --release 21` 编译 38 个主源码 + 10 个测试源码全通过，JUnit Platform 1.12.2 + Jupiter 5.12.2 直接驱动 |
| `mvn test` / `mvn verify -Pcoverage` | ~~本机未能执行~~ **已解决（2026-11-15）**：联网缓存 `spring-boot-starter-parent:4.0.9` 后全量执行通过，现可离线复跑 |
| P0-B 增量一回归（2026-11-16） | **3367 用例 / 0 失败 / 0 错误 / 0 跳过**（`mvn test` 全量）；`contextLoads` 修复——`application.yaml` 显式排除 `DataSourceAutoConfiguration` / `MybatisAutoConfiguration`（持久化单元落地时移除）；新增 `foundation/event`、`catalog/domain`、`authoring/domain`、`moderation/domain` 六包 15 个主类 + 9 个测试类，全部为纯函数/值对象，无 Controller / Repository / DDL |
| P0-B 增量二回归（2026-11-16） | **3387 用例 / 0 失败 / 0 错误 / 0 跳过**（`mvn test` 全量，完全离线）；新增 `IdempotencyRules`（幂等作用域/SHA-256 摘要/24h 过期，T09 前置）、`OutboxBackoff`（退避 1/5/30/60s + 超 24h 人工处置）、`SubmissionGate`（提交前提闸门，03 §2.2）、`WorkflowSuspension`（挂起原因一致性）、`ReadingProgressValidator`（进度边界，T29）及 `CopyrightStatus` / `SuspensionReason` 枚举；依赖无环校验 PASS（60 源文件，0 非法边 / 0 环） |
| P0-B 增量三回归（2026-11-16） | **3414 用例 / 0 失败 / 0 错误 / 0 跳过**（`mvn test` 全量，离线）；新增 `StableBuckets`（万分比稳定分桶抽取，`QualitySampler` 委托重构、行为不变）、`CursorCodec`（游标 HMAC 签名/15min 过期/过滤摘要绑定/limit 边界，04 §7 / 05 §2）、三套状态机 `RestrictionStateMachine`（终局/解约禁 REVOKED）/`AppealStateMachine`/`ConfigRevisionStateMachine` 全组合穷举、`GrayRolloutPolicy`（灰度双槽激活校验 0 禁/10000 全量/灰度需基线 + HMAC 分桶选择 fail-closed 走全量）及 7 个值域枚举；依赖无环 PASS（73 源文件，0 非法边 / 0 环） |
| P0-B 增量四回归（2026-11-17） | **3428 用例 / 0 失败 / 0 错误 / 0 跳过**——其中 **14 个为真实 MySQL 集成测试**（本机 MySQL84 服务 8.4.6，`lumin_test` 专用库，每测试独立重建 schema；凭据经 `LUMIN_TEST_DB_*` 环境变量注入不落盘，未配置时按 Assumption 诚实跳过）。交付：① Owner 裁决 MySQL 落地为文档修订（docs/02 §2/§2.2、04 §1 映射表、07 §1.2、08 §7.1，修订留痕）；② `V1__p0b_core_schema.sql` 16 表（docs/04 → MySQL 8.4 方言：DATETIME(6) 存 UTC / JSON / CHAR(36) UUID / 生成列模拟部分唯一索引与双槽 / CHECK 白名单与 grade 配对兜底 / 外键 RESTRICT）；③ MyBatis Mapper×6（draft/content_version/workflow/pointer/outbox/idempotency，`arg-name-based-constructor-auto-mapping` 支持 record）；④ 集成测试覆盖：outbox 聚合修订唯一、同目标单开流程（open_slot）、SUSPENDED⇔原因 CHECK、指针目标唯一、grade 配对 CHECK、draft 形态 CHECK、单 EDITABLE 草稿、config 双槽唯一、幂等复合主键、乐观锁指针交换。依赖无环 PASS。**登记**：`target_key/rev_key` 生成列宽 96（81 字符键实测修正）；MySQL CHECK 违规（3819/HY000）在 Spring 翻译为 `UncategorizedSQLException`，与 1062 唯一冲突（`DuplicateKeyException`）区分断言 |
| P0-B 增量六回归（2026-11-17） | **3442 用例 / 0 失败 / 0 错误 / 0 跳过**——含 8 个提交主链路 MockMvc 集成测试（真实 MySQL + 本地对象存储适配器）。交付：① `POST /api/v1/drafts/{id}/submissions`（**202 受理语义**；SubmissionGate 闸门→正文对象事务外写入→INV-06 同事务五写：content_version+publication_workflow+Outbox(ContentSubmitted.v1)+audit+draft=SUBMITTED→SubmissionReceipt）；② `POST /api/v1/works/{id}/chapters`（If-Match 作品行锁原子取号，完结/下架/终止禁新章 B2001）；③ 架构修正：提交编排权归 publication 域（authoring→publication 非法边被架构测试抓出并消除，authoring 提供 DraftLifecycleService 端口，publication→authoring 为允许边）；④ foundation 新增 storage 端口 + 本地适配器（O24 不可覆盖）、audit 端口 + audit 模块适配器、IfMatch 解析器、IdempotentExecutor 通用幂等执行器；⑤ **登记**：MySQL JSON 列规范化存储（冒号加空格/键字典序）——断言用 JSON_EXTRACT、幂等回放比对语义字段而非原文；policyVersion 骨架期固定 1（configuration 落地后替换）；机审案件创建待 Outbox 消费者批次 |
| P0-B 增量七回归（2026-11-17） | **3446 用例 / 0 失败 / 0 错误 / 0 跳过**——含 4 个 Outbox 投递集成测试。交付：① `foundation/event/OutboxDispatcher`（1s 批量扫描、成功记 delivered_at、失败退避 1/5/30/60s 不删事件、累计超 24h 告警转人工，03 §4.3）+ `OutboxScheduler`（`lumin.outbox.enabled` 门控，测试环境关闭、直调 dispatcher 保证确定性）；② `DomainEventSubscriber` 端口 + `InboxReceiptMapper`（消费者事务内写收据，U(consumer,event_id) 去重，至少一次语义）；③ `foundation/port/ReviewCasePort` + moderation `ReviewCasePortAdapter`（QUEUED 案件，source=SUBMISSION、stage=1、risk/priority 初值 LOW/P2、SLA P2 2h/24h；queue_id 固定合成队列登记）；④ `publication/event/PublicationSubmissionConsumer`（N1 加载：核验 versionId/workflowId/materialSha256/policyVersion → 建案 + 状态机 SUBMITTED→AUTO_REVIEW（AUTO_TASK_CLAIMED）+ workflow.case_id 回填；核验失败案件不建 + 告警审计，**登记**：N1 失败停流 SUSPENDED 需状态机扩展行延后 Owner 裁决）；⑤ authoring `findVersionMaterial` 材料核验端口数据。依赖无环 PASS（119 源文件）。**登记**：机审报告接收（/internal/v1/machine-reports）与 N4 路由执行为下一增量 |
| P0-B 增量八回归（2026-11-17） | **3451 用例 / 0 失败 / 0 错误 / 0 跳过**——含 5 个机审链路 MockMvc 集成测试。交付：① `POST /internal/v1/machine-reports`（agentAuth OIDC 接入前由内网隔离边界保护，登记）：MachineReport 契约校验 → 绑定核验（case/workflow/version/material/policy 全一致）→ 报告落库 → N4 路由；② N5 自动裁决执行：经 `foundation/port/PublicationWorkflowPort`（publication 适配器）——AUTO_PASS 发布事务（状态机裁决 + 版本/流程 APPROVED + 指针原子交换 + ContentPublished.v1；章节父作品无已通过版本停留 HUMAN_REVIEW）、AUTO_REVISE 驳回、ROUTE_HUMAN_REVIEW 人工队列；③ 抽检闸 `AutoSampleGate`（5% 命中转人工，默认 HMAC 实现 + seed 配置占位）；④ SYSTEM 裁决记录（append-only）+ 案件 QUEUED→DECIDED 状态机 + 风险/SLA 更新；⑤ 晚到报告 409 + 过期审计随事务留存（ReceiveResult 携带错误码避免回滚吞审计）；⑥ 架构修正：moderation→authoring 与 publication→foundation.repository 两条非法边消除——材料核验经 `foundation/port/ContentMaterialPort`（publication 适配器）、Outbox/Inbox 经 `foundation/event` 门面（OutboxAppender/InboxRecorder，行类型移入 event 包）。依赖无环 PASS（143 源文件）。**登记**：authorAgeBand 取权威源数据链 version→work→author→profile；calibration-valid 默认 false（K-03 未过全人工，测试显式开启）；agentAuth 为内网骨架；HOLD 挂起原因枚举待裁决；**教训登记**：此前增量六/七的依赖检查 grep 只过滤 PASS 行漏看 FAIL——本轮完整输出暴露两条非法边并全部修复 |
| P0-B 增量九回归（2026-11-17） | **3455 用例 / 0 失败 / 0 错误 / 0 跳过**——含 4 个人审闭环与读者链 MockMvc 集成测试。交付：① V2 迁移 `role_assignment` 表（04 §3 原文；NULLS NOT DISTINCT 唯一 → scope_slot/active_slot 生成列；grant_id FK 待 V2 role_grant）；② 人审闭环 `GET/POST /api/v1/review-cases`（listQueued 按 priority/SLA 排序、claim If-Match 原子领取 + 15 分钟租约 + 阶段 3 回避校验（原裁决主体集合）、decide grade 配对 + HIGH 双审 FIRST_DECISION 不终局 + ESCALATE 阶段 3 + 终局经端口驱动发布/驳回）；③ 读者链 `GET /api/v1/works`（AgePolicy 过滤）、`GET /works/{id}`、`GET /chapters/{id}/content`——**逐条三态裁决 VisibilityPolicy 八步**（404 统一防枚举、正文经对象存储受控读取）；④ 替身身份过滤器升级为有界集合（AUTHOR/REVIEWER_L1A/L2A → 主体 + 角色属性映射）；⑤ **登记**：角色授权以替身角色属性为准（RolePolicy.authorize 完整矩阵随 role_grant 授予链路接线）；adultContentEnabled=false（A 级默认关闭）；读者列表为 SQL 等价过滤（T27 覆盖）。依赖无环 PASS（149 源文件）。**教训登记**：HUMAN_FINAL_PASS 事件曾在重构中丢失（resolveEvent 缺 HUMAN_REVIEW 分支，架构测试与集成测试双重暴露后修复）——人审最终裁决状态迁移已验证 |
| P0-B 增量十回归（2026-11-17） | **3459 用例 / 0 失败 / 0 错误 / 0 跳过**——含 4 个治理/申诉集成测试。交付：① V3 迁移 appeal/restriction/report/notification 四表（含 deleted_at 补齐与 target_scope 生成列）；② 申诉闭环（本人案件校验经 CaseOwnershipPort、同案唯一在途、原审回避、UPHELD/OVERTURNED）；③ 治理措施（TEMPORARY 默认 30 天到期 CHECK、独立复核生效/驳回、REVOKED 改判、**FINAL_VIOLATION 不可改判**——状态机兜底）；④ 读者闸门闭环：restriction ACTIVE → VisibilityPolicy BLOCKED → 读者 404，改判后恢复可见；⑤ 裁决通知（NotificationPort + engagement 适配器，作者收 REVIEW_RESULT，U(recipient,event,category) 幂等）；⑥ 架构修正：moderation→catalog 归属校验经 CaseOwnershipPort；NotificationService 去重复接口实现。依赖无环 PASS（168 源文件）。**登记**：举报(report)建案骨架仅落表；UPDATE 类通知为 P1 追更占位；FINAL_VIOLATION 申诉 OVERTURNED 不恢复传播由状态机兜底 |
| P0-B 增量十一回归（2026-11-17） | **3460 用例 / 0 失败 / 0 错误 / 0 跳过**——含治理/申诉/举报集成测试事件化改造。交付：① **ReviewResolved.v1 事件化**（02 §5.2 正式设计落地）：moderation 终局裁决（机审 AUTO_* + 人审终局）写 outbox，`publication/event/ReviewResolvedConsumer` 消费执行状态机与发布事务（inbox 去重 + 过期/错绑定告警不执行）；② `POST /api/v1/reports` 举报端点（governance 域，24h 同主体同目标同理由去重 B2001，建案 source=REPORT 经 ReviewCasePort.createReportCase）；③ 前端接通骨架：vite proxy /api→8081 + `services/draftService` 切换真实 HTTP（createWork/createDraft/saveDraft/submitDraft/getDraft），页面级接线随联调批次；④ 架构修正：PublicationWorkflowPort 端口删 executeAutoDecision（跨域直调改事件），保留 routeToHumanReview；依赖无环 PASS（168 源文件）。**登记**：routeToHumanReview 仍为同步端口调用（事件化随事件批次统一）；reviewerDecisionNotifiesAuthor 验证通知经事件消费产生 |
| P0-B 增量十二回归（2026-11-17） | **3460 用例 / 0 失败 / 0 错误 / 0 跳过**。交付：`GET /api/v1/me/works`（listMyWorks：作者私有作品列表，含可编辑草稿与最新版本摘要，LEFT JOIN 单查询）；前端 author/reviewer 工作区经 vite proxy 接通真实 API（AuthorView 作品列表/新建作品/新建草稿/提交审校；ReviewView 队列/领取/裁决；MineView 通知列表）。运行环境验证：前端 5173 → proxy → 后端 8081 → MySQL 端到端创建/提交/建案全通（后端调度器 1s 自动投递）。依赖无环 PASS（173 源文件）。**登记**：8081 端口为 8080 被占时的运行选择（vite proxy 同步调整）；读者 GET /works 列表年龄过滤经 AgePolicy；notification 表已建（V3）但 GET /me/notifications 通知列表在 MineView 以 fetch 直连（统一走 services 随下一批） |
| P0-B 增量十三回归（2026-11-17） | **3460 用例 / 0 失败 / 0 错误 / 0 跳过**。交付：① V4 迁移 `billing_reserved_skeleton` 六表（04 §6.2 原文落地：billing_account/billing_order/ledger_entry/revenue_share/settlement/refund，schema-only 无入口——「空表 ≠ 交易已可用」T38 口径；CHECK 金额边界/币种 CNY/状态白名单）；② 后端 createChapter/章节草稿创建/保存端点已在增量十一/十二落地并在集成测试覆盖；③ 前端 AuthorView 接通真实创作流（作品列表 → 新建作品 → 提交审校，真实 API 经替身身份）；MineView 举报按钮接通 POST /reports（骨架）。依赖无环 PASS（173 文件）。**登记**：V4 六表为 schema-only 骨架——无创建者/无 HTTP 入口/无权限授予（T38 口径）；billing 骨架与 billing_reserved 只读模型一致 |
| P0-B 增量十四回归（2026-11-18） | **3460 用例 / 0 失败 / 0 错误 / 0 跳过**。交付：`GET /api/v1/me/works/{id}/chapters/me`（listMyChapters：作者作品章节列表，含可编辑草稿状态，归属校验经 WorkService）；前端 `services/chapterService`（listMyChapters/createChapter/createChapterDraft/submitChapterDraft）；AuthorView 接通章节创作流（选中作品 → 章节列表 → 新建章节 → 章节草稿编辑/提交）。依赖无环 PASS（173 文件）。**登记**：章节列表端点路径 /me/works/{id}/chapters/me 为骨架路径（正式契约 listMyChapters 为 GET /me/works/{id}/chapters，后续对齐）；章节草稿提交后进入机审/人审队列 |
| P0-B 增量十五回归（2026-11-18） | **3462 用例 / 0 失败 / 0 错误 / 0 跳过**——读者链真实数据接通。交付：① `GET /api/v1/works/{id}/chapters`（listChapters：读者可见章节目录，SQL 等价过滤仅含已发布指针章节，sequenceNo 排序）；② `GET /api/v1/works/{id}` 详情补作者昵称（WorkView.author，公开档案字段）；③ **缺陷修复 ×2**（均由真实章节阅读链暴露）：ReaderService.decide 的 `"CHAPTER".equals(targetType)` 误比枚举恒 false——章节正文裁决 versionTargetId 永取 workId → INCONSISTENT 404（潜伏缺陷）；PublicationWorkflowPortAdapter 裁决上下文 parentWorkHasVisibleApprovedVersion 硬编码 true——人审终局 PASS 绕过 03 §2.3 父作品门禁（机审路径有 resolveEvent 拦截，人审路径漏拦），现按 WORK 指针如实计算，门禁拒绝时审计 `review_resolved.rejected` 并保留流程/收据（不抛异常——回滚吞审计致事件无限重试，增量八 ReceiveResult 同一教训）；章节目标裁决同步改用章节自身业务态；④ 章节首版发布业务态 DRAFT→ACTIVE（markChapterPublished，此前章节发布后业务态停留 DRAFT）；⑤ 前端 `services/readerService` + `lib/realBook` 适配（grade→band 派生仅展示层灰显，真实门禁在后端）；CatalogView/BookDetailView/ReaderOverlay 接通真实读者链——书城真实作品列表（demo 夹具保留为 API 不可用兜底）、详情+章节目录、阅读器正文按章拉取；lint/build 全绿。集成测试：含已发布章节的目录/正文/未发布 404 + 人审 PASS 无父作品不发布（新门禁）。运行环境验证：5173→8081→MySQL 端到端——作品/章节经机审+人审发布，读者列表/详情/目录/正文全通。依赖无环 PASS（177 源文件）。**教训登记**：stdout 调试 print 在 surefire 输出中缺失导致误判「未执行」——改经 resolved exception 消息传递诊断后定位；README 证据表曾重复追加 增量十一–十四 各一次，本轮去重 |
| P0-B 增量十六回归（2026-11-18） | **3463 用例 / 0 失败 / 0 错误 / 0 跳过**——T27 读者链矩阵一致性。交付：① DemoAuthenticationFilter 有界替身扩 READER_C0–C4（固定 UUID，角色 READER 无特权；**登记**：仅 P0 演示/联调口径，真实 OIDC 接入时随过滤器整体移除）；② T27 集成测试：G/PG_12/PG_16/A × C0–C4 各 1 作品与章节，列表/详情/目录/正文四入口对 20 组判定一致（期望值取自 AgePolicy 纯函数——列表 SQL 等价过滤与逐条三态裁决同源），A 级 P0 默认关闭全带 404，列表信封仅含 data 数组不泄露被过滤作品计数；③ 前端：readerService 各调用显式携带年龄带替身头（READER_C0–C4，仅读者链请求；创作/审核替身不受影响），MineView 年龄带切换即真实后端口径，CatalogView 随带重取；lint/build 全绿。运行环境验证：PG_16 作品经机审+人审发布后——READER_C0/C4 仅见 G 作品，READER_C2/C3 见 PG_16+G，与矩阵一致。依赖无环 PASS（177 源文件）。**登记**：读者替身 C0–C4 为未核验演示档（declared=effective，无 verified）；T39 无障碍/多宽度与 T28 故障注入不在本增量 |
| P0-B 增量十七回归（2026-11-18） | **3465 用例 / 0 失败 / 0 错误 / 0 跳过**——T23 下架闸门闭环 + 契约路径对齐。交付：① **生效下架落业务态**（03 §2.1）：治理限制独立复核生效 → 经 foundation 端口 `PublicationGatePort`（publication 适配器，governance 不直接触碰业务行）驱动作品/章节业务态 →(TAKEDOWN_EFFECTIVE)→ REMOVED 并保存前态；改判解除 → 仅当所有生效限制均解除（T24「其他限制存在继续不可见」）且发布指针仍在（重新校验）时 →(TEMPORARY_APPEAL_UPHELD)→ 恢复记录的前态；FINAL_VIOLATION 生效后维持 REMOVED（状态机拒绝改判）；② T23 集成测试：生效 → REMOVED/prior=ACTIVE → 列表/详情/目录/正文四入口立即 404 → 改判 → 恢复 ACTIVE 四入口恢复；FINAL_VIOLATION 断言补业务态留痕；③ **契约路径对齐 + 缺陷修复**：作者私有列表实际服务于 `/works/me`、`/works/{id}/chapters/me` 骨架路径，而前端按 05 §3.2 契约路径 `/me/works`、`/me/works/{id}/chapters` 调用——作者作品/章节列表自增量十二/十四起在前端**静默 404**（错误兜底掩盖，create/submit 流路径正确故未察觉）；新增 `MeWorkspaceController` 对齐契约并移除旧路径，前端 chapterService 同步，新增契约路径集成测试。**教训登记**：此前「端到端全通」的运行验证只覆盖创建/提交流，未覆盖列表读取——骨架路径与前端调用路径从未被同一 HTTP 测试断言；④ MineView 通知列表由 fetch 直连统一为 `services/notificationService`；前端 lint/build 全绿。运行环境验证：契约路径 200；PG_16 作品下架 → 详情 404/列表移除 → 改判 → 恢复且 work 行 REMOVED/前态链留痕正确。依赖无环 PASS（178 源文件）。**登记**：T23 的 60 秒口径在 P0 无边缘缓存骨架下为 0 秒（逐请求裁决），边缘缓存随部署批次接入；下架不产生作者通知（治理通知随通知批次）；RESTORE 的 COPYRIGHT/ACCOUNT 前提为骨架恒真值 |
| P0-B 增量十八回归（2026-11-18） | **3467 用例 / 0 失败 / 0 错误 / 0 跳过**（连续两轮全绿）——T29 阅读进度契约。交付：① V5 迁移 `reading_progress`（04 §5.1 原文落地：U(user_id,chapter_id)、CHECK 位置 0–10000 bp/时长 ≥0、user/chapter/version 三 FK）；② `PUT/GET /me/reading-progress/{chapterId}`（本人且可读该章节——可读性经 foundation 端口 `ChapterAccessPort` 由 publication 三态裁决单源提供；边界校验经 ReadingProgressValidator 纯函数：-1/10001/负时长/客户端时钟超前 >5 分钟一律 400；乐观锁 If-Match 首次写 "0"，错版本 412/B2002；按主体查询天然隔离越权 404）；③ **版本绑定**（04 §5.1「版本变化提示而不跨版本乱套位置」）：进度行记录所指 content_version，章节发布修订版后保存重绑至当前公开版本并返回 versionChanged=true，GET 暴露 versionId 供 UI 比对提示（V1 位置从不被呈现为 V2 位置）；前端 RealReaderOverlay 打开章节时比对已有进度版本并播报版本变更；④ **缺陷修复 ×2**（均由 T29 编排暴露）：content_version.revision_no 硬编码 1——同目标第二版必撞 `uq_content_version_target_revision`（修订永远无法发布，03「允许已存在章节修订」被破坏），改按 rev_key 取 MAX+1；首版发布业务态迁移对非 DRAFT 目标拒绝——修订发布事务整体回滚吞掉指针交换，现非 DRAFT 跳过业务态迁移；⑤ **偶发失败根因修复**：Governance/T29 两类未钉抽检闸（AutoSampleGate 5% HMAC 命中把 LOW+PASS 路由人工→发布链路非确定，此前全量回归偶发 1–2 例失败且位置漂移），补 NeverSampledGate 配置与其他发布链类对齐。集成测试：T29 边界/时钟偏差/乐观锁/越权/可读闸门/修订重绑全断言。运行环境验证：首写→读回→越权 404→越界 400→时钟偏差 400→更新 etag 递进全通。依赖无环 PASS（182 源文件）。**教训登记**：偶发失败的正确排查姿势是把「哪一步没发生」变成数据断言（rr=[] 无 ReviewResolved 事件一眼定位抽检路由），而不是重跑碰运气；测试类依赖机审 AUTO 链时必须显式钉住抽检闸与校准门禁两个环境开关 |
| P0-B 增量十九回归（2026-11-18） | **3470 用例 / 0 失败 / 0 错误 / 0 跳过**——T30–T32 配置修订链。交付：① `POST/GET /config-revisions`（提案 OPERATOR/REVIEWER_L2、初建即 IN_REVIEW；审查列表先过滤后 limit，仅配置授权角色域可见）；`POST /{id}/reviews`（独立 ADMIN，申请人不得自批——服务层 A2001 + DB ck_config_reviewer 双口径；REVIEW_ROUTING/ACCESS_POLICY 批准须 legalConfirmation=true，05「必要合规证明」；If-Match 乐观锁，批后 payload 冻结无更新端点）；`POST /{id}/activations`（ADMIN 仅触发已批准计划；提前激活 409/B2001「ACTIVE 只在到时出现」；双槽取代——全量激活取代原全量+灰度、灰度取代原灰度，经 ConfigRevisionStateMachine ACTIVE→SUPERSEDED）；② payload 类型化校验 `ConfigPayloadValidator`（T31：字段白名单；降低置信度/抽检底线相对既有 ACTIVE 基线与绝对下限双重拒绝；关闭未成年保护/开启成年内容（P0 A 级默认关闭）/启用 UPDATE 聚合（P1 占位）→ 全拒绝且不落行）；③ T32 回滚：rollback_of 指向历史修订 → 新提案取其冻结 payload → 重复审批激活，历史不改写，revision 单调；④ DemoAuthenticationFilter 扩 OPERATOR/ADMIN 替身（配置域演示口径）。集成测试：T30 自批拒绝/非 ADMIN 403/缺法务确认 400/提前激活 409/到时激活；T31 五类削弱提案全拒 + 行数不变断言 + 旧锁 412；T32 回滚链全断言。运行环境验证：提案→自批 403→ADMIN 审批→激活 ACTIVE→降置信 400→列表可见全通。依赖无环 PASS（186 源文件）。**缺陷修复/教训登记**：ConfigRevisionRow 字段序未按表列序排列——SELECT * 映射按列序对位（arg-name 并不按名匹配），rollbackOf/rolloutBasisPoints 读取互换；**本仓库 SELECT * mapper 的 record 字段顺序必须与表列序完全一致**（既有记录类均恰巧满足故此前未暴露），已在类注释与教训登记中固化 |
| P0-B 增量二十回归（2026-11-18） | **3472 用例 / 0 失败 / 0 错误 / 0 跳过**——T33 通知幂等 + 运营工作区接线。交付：① T33 集成测试：同一 ReviewResolved 事件重复投递 100 次 → 每接收者/源事件/类别仅 1 条站内通知（inbox 去重 + U(recipient,source_event,category) 双层幂等，inbox_receipt 恰 1 行）；标记已读幂等语义修正——重复已读由 404 改为**无副作用 200**（read_at 非空守卫前置判断，并发已读同样幂等），已读后 listUnread 不再返回；非本人已读 404/B2004（越权隔离）；② 前端运营工作区接真实配置链（增量十九后端）：`services/configService`（列表 ADMIN 头、提案 OPERATOR 头——职责分离在请求头体现）+ OperatorView 修订列表（key/revision/state/payload/灰度标注）+ 新建提案表单 + 审批/驳回/激活/回滚按钮（审批 If-Match 取视图 lockVersion，视图补 lockVersion 字段）；lint/build 全绿。运行环境验证：机审 REVISE 路由人工 → 人审 REVISE 终局 → 「内容审核未通过」通知 → 已读两次 200 → 未读列表移除 → 非本人 404 全通。依赖无环 PASS（186 源文件）。**教训登记**：连排调试时 `mvn … > /dev/null 2>&1` 吞掉 BUILD FAILURE 会让人对着**陈旧 surefire 报告**推理不存在的现象——本轮「DBG 打印缺失」谜题实为测试文件一处未导入 StandardCharsets 的编译错误；日志 grep 无输出时先用 `grep -a`（Windows 下重定向日志为宽字节编码） |
| P0-B 增量二十一回归（2026-11-18） | **3473 用例 / 0 失败 / 0 错误 / 0 跳过**——T35 审计脱敏与写失败闸门。交付：① `AuditRecorder.recordTransition` 端口扩展：高危状态迁移审计携带 before/after **脱敏快照**（调用方仅传业务态类字段——不传正文/令牌/证件），digest 覆盖前后快照（键排序规范化 + SHA-256，满足 ck_audit_digest hex64）；接线三处高危迁移：治理下架/恢复（PublicationGateAdapter：业务态 ACTIVE→REMOVED→ACTIVE）、限制生效/驳回/改判（RestrictionService）、配置审批/激活（ConfigRevisionService）；② T35 集成测试：下架+恢复审计 before/after JSON 与 digest 全断言 + 审计不含正文章节串（脱敏）；**审计写失败闸门**——选择性失败桩（restriction.activated/takedown.* 抛出）验证措施生效事务整体回滚（限制停留 PENDING、业务态未迁移、无下架审计、读者不受影响，03 §6「审计写失败时高危事务回滚」INV-06）；③ 结构性登记：P0 无业务账号 UPDATE/DELETE 端点、无任何信息导出端点（未成年人信息批量导出在路由层不存在，T35 的「权限拒绝」由端点缺席结构性满足；注销/匿名化随后续批次）。运行环境验证：治理下架→改判恢复的审计快照落库（before/after JSON + digest hex64）。依赖无环 PASS（186 源文件）。**登记**：审计读取端点（AUDITOR 作用域）随审计查询批次；业务账号注销（T36）未在本增量 |
| P0-B 增量三十一回归（2026-11-19） | **3494 用例 / 0 失败 / 0 错误 / 0 跳过**——UGC-029 一阶段：写操作限流。交付：① `RateLimitFilter`（bootstrap，`lumin.rate-limit.enabled` 条件装配——默认关闭，**生产部署 profile 显式开启**；开发/测试连续性）：写方法（POST/PUT/PATCH/DELETE）按 **主体 + 路径组** 滑动窗口限流（60s 窗口，默认 120 次/分钟可配）——超限 429/A2003 + `Retry-After`（窗口剩余秒数）；读方法不限；429 信封不含内部细节；② 集成测试（限值=3 独立上下文）：三次写通过 → 第四次 429 + Retry-After ∈ [1,60] + 信封无堆栈；主体隔离（B 不受 A 配额影响）；读不限。依赖无环 PASS、契约校验通过、前端 eslint/build 通过。**登记**：内存滑动窗口为**单实例 P0 口径**——多副本部署随网关级限流替换（O25）；上传滥用/重放已由幂等层覆盖，注入边界由参数化查询覆盖（结构性登记）；举报 24h 去重已由增量二十六/十覆盖；生产 profile 须与 `lumin.auth.mode=session`、`lumin.closure.promotion.enabled` 一并置定 |
| P0-B 增量三十回归（2026-11-19） | **3493 用例 / 0 失败 / 0 错误 / 0 跳过**——UGC-005 收口：匿名化推进调度器。交付：① `AnonymizationPromotionScheduler`（lumin-infrastructure；`lumin.closure.promotion.enabled` 条件装配——集成测试显式关闭、直调 promoteOnce() 保证确定性，与 Outbox 调度同口径；默认间隔 1 小时）：SYSTEM 定时扫描——注销满 6 个月且无 ACTIVE 保全 → 内部关联匿名化（promoteDue）+ HELD 的 ERASE 权利请求保全解除后续跑（retryHeld，UGC-006 收口）；② 集成测试（调度器启用上下文）：未到期 + 保全未解除 → 不推进（PENDING/HELD 原样）；释保全 → 调度续跑 ERASE 完成（注销行仍未到期不推进——到期语义精确）；到期（due 前移模拟）→ 调度匿名化完成；空跑不报错。依赖无环 PASS、契约校验通过、前端 eslint/build 通过。运行环境验证（8081）：调度器启用需生产 profile 显式开启（开发默认关闭保持联调连续性）。**登记**：UGC-005 全项收口（注销公开匿名/HELD/到期推进/调度器均在案；会话撤销已于增量二十四联动；真实会话撤销随 OIDC）；生产部署 profile 须开启 `lumin.closure.promotion.enabled` |
| P0-B 增量二十九回归（2026-11-19） | **3491 用例 / 0 失败 / 0 错误 / 0 跳过**——UGC-008 配置到业务消费者闭环。交付：① `ReviewRoutingPolicy.route(input, autoConfidenceThresholdBp)` 配置驱动路由重载（置信比较 bp 口径；原单参重载保留默认 9800bp=0.98 供既有调用）；② `foundation/port.ReviewRoutingConfigPort` + `ReviewRoutingConfigAdapter`（configuration 域实现：REVIEW_ROUTING ACTIVE 修订按案件 ID 稳定分桶解析全量/灰度修订，payload 缺字段回退既有值，无基线回退安全默认 9800/500/未成年保护开）；③ **抽检闸接配置**：DefaultAutoSampleGate 抽检比例取 resolveForCase(caseId).sampleRateBp（StableBuckets fail-closed——seed 缺失/非法案件标识全量复核）；**机审置信下限接配置**：MachineReportService 路由阈值取 resolveForCase(caseId).minAutoConfidenceBp；④ ConfigPayloadValidator 置信默认对齐策略口径（9900→9800）。集成测试（确定性——HUMAN 路径不经抽检闸）：置信下限升至 9950 → 0.99 报告转人工（QUEUED/DRAFT/零决策行）；抽检比例 10000 → 自动决定必转人工（QUEUED/DRAFT/无指针）。依赖无环 PASS、契约校验通过、前端 eslint/build 通过。**缺陷修复/教训登记**：MachineReportService 曾调用单参 route 重载（构造器传了阈值但路由未用上——DBG-ROUTE 打印 outcome 暴露）；测试 payload JSON 转义用了双反斜杠（`\\\"` 应为 `\"`）——与既有 T30 写法比对定位。**登记**：未成年保护开关（minorProtectionEnabled）已随配置校验与解析，接入具体业务规则随对应批次；灰度分桶的灰度修订测试随 GrayRolloutPolicy 单测覆盖（集成层分桶断言因随机桶位不做确定性断言）|
| P0-B 增量二十八回归（2026-11-19） | **3489 用例 / 0 失败 / 0 错误 / 0 跳过**——UGC-006 数据主体权利请求。交付：① `V9__rights_request` 迁移（docs/04 §5.2 原文落地：kind=ACCESS/EXPORT/CORRECT/ERASE；state=OPEN/PROCESSING/COMPLETED/HELD；due_at；hold_id；result_ref_cipher 经 FieldCipher 加密）；② `POST/GET /me/rights-requests` + `/{id}`（仅创建者本人可见，他人 404 越权隔离；ERASE 受法律保全闸门：ACTIVE 保全 → HELD 可观察、解除后 retryHeld 续跑匿名化完成——03 §2.4「HELD 仅法律保全阻止匿名化」；ACCESS 本人资料快照；EXPORT 引用加密留痕 + 24h 到期（下载二次认证随 UGC-002 后续）；CORRECT 更正公开昵称；**result_ref 加密留痕不回显——不返回公开导出地址**）；③ 审计闭环：每类完成/暂缓均留痕（审计事实不删）。集成测试：ACCESS/EXPORT（24h 断言）/CORRECT（昵称生效）自助闭环 + 仅本人 404；ERASE 无保全即完成（匿名化）+ 保全 HELD 暂缓 → 释放续跑；审计事实在。依赖无环 PASS、契约校验通过、前端 eslint/build 通过。**登记**：导出下载二次认证随 UGC-002 后续批次；索引/缓存/分析关联清理随对应基础设施批次（UGC-030）；ERASE 对已注销账户为幂等（匿名化不可逆）|
| P0-B 增量二十七回归（2026-11-19） | **3486 用例 / 0 失败 / 0 错误 / 0 跳过**——UGC-007 一阶段：分级字段加密。交付：① `foundation/crypto.FieldCipher`（AES-256-GCM：96 位随机 IV 密文自包含 + 128 位认证标签；AAD 绑定字段用途——跨字段密文互换拒绝；密钥经 `LUMIN_CRYPTO_KEY` 注入、SHA-256 派生，缺钥 fail-fast——生产经 Secret Manager，登记本地为派生占位口径）；② legal_hold.basis_ref_cipher 加密接线（**整改 docs/13 EV-13「UTF-8 字节非加密」**：立保依据以 GCM 密文落库，随机 IV 防频率分析）；③ FieldCipher 单元测试：加解密回环/AAD 跨字段拒绝/密文非明文/篡改（翻转比特）与截断拒绝/错误密钥拒绝/缺钥 fail-fast。运行环境验证：立保后 basis_ref_cipher 为二进制 GCM 密文（非明文字节）。依赖无环 PASS、契约校验通过、前端 eslint/build 通过。**登记**：birth_date_cipher/核验证据/请求快照接同管线随对应批次；密钥轮换与 KMS 托管随部署批次（04 §8 密钥不落业务库/日志）|
| P0-B 增量二十六回归（2026-11-19） | **3486 用例 / 0 失败 / 0 错误 / 0 跳过**——UGC-004 角色授予链一阶段。交付：① `V8__role_grant` 迁移（docs/04 §5.2 原文落地：state=PENDING/APPROVED/REJECTED、requested_by≠reviewed_by 由服务层+审批口径双守卫；role_assignment.grant_id 外键 V8 补建——V2 登记的待办）；② `POST /api/v1/role-grants`（提案 OPERATOR/ADMIN）+ `/{id}/reviews`（独立 ADMIN；**RolePolicy.canAssign 互斥校验**——AUTHOR/OPERATOR × REVIEWER_L1/L2 不得并存，6.8 审核员禁止兼任作者、运营与审核严格分离；APPROVED 即建 role_assignment 绑定，到期授予仅登记历史不产生有效角色）；③ 过滤器有效角色解析：**已授予角色优先于替身基础角色**（role_grant APPROVED 且未过期；服务端权威——不信客户端自报）；集成测试：未授权领取 403 → 授予链（提案/异人审批/绑定）→ 同主体领取 200 CLAIMED（角色能力实证）；自批拒绝 A2001；到期授予（expires_at 过去）APPROVED 但领取仍 403；互斥（已授 AUTHOR 再授 REVIEWER_L1）400/P1001。运行环境验证：授予前领取 403 → OPERATOR 提案 → ADMIN 审批 → 同主体领取 200 CLAIMED 全通。依赖无环 PASS、契约校验通过、前端 eslint/build 通过。**登记**：失效/撤销端点与互斥组配置化随 UGC-004 后续批次；替身基础角色映射随正式 OIDC 移除（UGC-002）|
| P0-B 增量二十五回归（2026-11-19） | **3484 用例 / 0 失败 / 0 错误 / 0 跳过**——UGC-009 签名游标分页。交付：① `GET /works` 升级为**授权 keyset 分页**（05 §2 游标口径；CursorCodec 全量接线）：`PublicationPointerMapper.listVisibleWorks` 授权过滤 SQL 化（G/PG_12 全带可见；PG_16 仅 bandVar∈C2/C3——C4 按 C1 同 AgeBand.readingBand；A 级默认关闭；等级口径与 AgePolicy.canReadPublic 同源等价），keyset `(published_at, id)` 稳定排序跨页无漏重；② 签名游标：HMAC 签名 + 主体绑定 + 过滤摘要（band 变化须重新分页）+ 15 分钟过期 + limit 默认 20/上限 100（显式越界 400/P1001）；**data 契约不变**（数组），nextCursor 经响应头 `X-Lumin-Next-Cursor` 传递（不泄露隐藏总数）；③ 集成测试：5 作品 limit=2 三页遍历恰好全部可见作品各一次；A 级作品（grade=A 报告发布）对 C3 不可见且响应无 total/count 字段；篡改/过期（issuedAt 前移 16 分钟）/跨主体（C2 携 C3 游标）/limit=101 全 400。运行环境验证：limit=1 游标遍历两件可见作品 + limit=101 400 全通（注意：五模块结构下 `mvn spring-boot:run -pl lumin-bootstrap` 需先 `mvn install` 刷新兄弟模块 jar，否则运行时用旧 application jar——NoSuchMethodError 教训登记）。依赖无环 PASS、契约校验通过、前端 eslint/build 通过。**登记**：游标签名密钥生产必须经 Secret Manager 注入（`LUMIN_CURSOR_KEY`）；前端 CatalogView 游标接线随 UGC-017/018 批次 |
| P0-B 增量二十四回归（2026-11-18） | **3481 用例 / 0 失败 / 0 错误 / 0 跳过**——UGC-002 一阶段：服务端权威会话认证。交付：① `V7__auth_session` 迁移（token_hash=SHA-256 唯一，**原始令牌不落库**；撤销留痕 revoked_at）；② `POST /api/v1/auth/sessions`（签发：有界替身集合内成员 → 不透明持有令牌双 UUID + 12h 有效期；未知替身 401/A0001——不接受任意自报身份）；`POST /api/v1/auth/sessions/revocations`（登出：幂等撤销，已撤销令牌重复登出同样 200 不产生副作用）；③ 过滤器升级：`Authorization: Bearer` **优先**（服务端权威解析——缺失/伪造/过期/撤销一律 401/A0001，过滤器层直接写 401 信封）；`lumin.auth.mode=session` 时演示身份头被忽略（**生产无法使用演示头的结构性闸门**——部署 profile 置 session）；`demo` 模式（开发/测试骨架）保留替身头，令牌路径两模式一致；④ 注销联动撤销全部会话（T36 撤销会话钩子实证：注销后全部令牌 401 + 审计 reason 记撤销数）；⑤ 签发端为 OIDC/PKCE 的替换点（UGC-002 后续阶段），解析/撤销语义不变。集成测试 T37（session 模式上下文）：签发→使用→伪造/缺失/演示头忽略/过期（expires_at 前移）/未知替身全 401；登出幂等 + 撤销后失效 + 注销联动撤销 ≥2 会话 + 审计在。运行环境验证：签发→Bearer 200→登出→401→伪造 401→demo 头（mode=demo）仍可用全通。依赖无环 PASS、契约校验通过、前端 eslint/build 通过。**登记**：前端会话接线（登出 UI/令牌存储）随 UGC-002 后续批次；生产部署 profile 必须置 `lumin.auth.mode=session` |
| P0-B 增量二十三（2026-11-18） | **3478 用例 / 0 失败 / 0 错误 / 0 跳过**——五模块重构验证 + T36 注销链收口。交付：① T36 注销与匿名化（03 §8 / docs/13 UGC-005 实现）：`V6__legal_hold_account_closure` 迁移（legal_hold 按 04 §5.2 原文落地 + account_closure 推进状态行）；`POST/GET /api/v1/me/closure`——注销**立即公开匿名**（CLOSED + 昵称匿名 + closed_at，重复注销 409），内部关联匿名化到期（+6 个月）由 SYSTEM 推进（oidc 关联断开不可逆，审计事实不删）；有 ACTIVE 法律保全 → 推进跳过（closure 视图 holdActive=true 可见，保全转 HELD 语义）；`POST /api/v1/legal-holds`（立/释 ADMIN + 全部保全读取留痕审计，03 §8）；② **五模块重构验证**（Owner 重构 ac5e903 后首证）：lumin-domain/application/infrastructure/ai/bootstrap 新结构下 `mvn clean test` 3478 全绿（含 lumin_test 真实 MySQL 集成测试与 T36），依赖无环 PASS（0 非法边 0 环）、`validate_contract.py` 通过、前端 eslint/build 通过——闭合 docs/13 §2.2「未执行数据库测试」缺口（复核行已登记 docs/13）。集成测试：T36 立即公开匿名/到期推进/保全暂缓与解除续推/重复注销 409/审计事实不删 + 非 ADMIN 立/释保全 403 全断言。**登记**：会话撤销随 OIDC 接入落地；6 个月推进调度器随部署批次（当前以可注入时钟推进方法验证）；UGC-005 剩余为 UGC-002/004 依赖项 |
| P0-B 增量二十二回归（2026-11-18） | **3476 用例 / 0 失败 / 0 错误 / 0 跳过**——T34 追更聚合契约 + 审计读取端点。交付：① `GET /api/v1/audit-records`（05 §3.4 listAuditRecords：AUDITOR 授权作用域只读；resourceType/resourceId/actorId/action 先过滤后 limit（≤200）、时间倒序；脱敏快照与 digest 原样返回；**无更新/删除/全文导出接口**——append-only T35 口径）；DemoAuthenticationFilter 扩 AUDITOR 替身；集成测试：下架审计读取断言（含快照/digest 字段）+ 非 AUDITOR 403；② **T34 追更聚合投影契约**（04 §6.1 P1 表 bookmark/subscription/notification_digest 强制预留不启用——仅去重设计验收，无产品入口/HTTP 端点）：`NotificationDigestPolicy` 纯函数 + 单元契约测试——合成新章/修订/公告事件：仅新章触发 UPDATE 候选（修订/公告 NOT_NEW_CHAPTER）；同人同作品同自然日至多 1 条（ALREADY_DIGESTED_TODAY，次日新章新候选）；退订 UNSUBSCRIBED/免打扰 QUIET_HOURS/下架 WORK_HIDDEN 不推。运行环境验证：AUDITOR 读取下架审计（before/after 快照可见）+ 非 AUDITOR 403 全通。依赖无环 PASS（188 源文件）。**登记**：T34 为契约级而非 P1 功能已交付（07 原文口径）；审计端点无写路径（append-only） |
| `mvn -o verify -Pcoverage`（迁移前旧模块记录） | JaCoCo 0.8.15：行 **97.43%**（531/545）、分支 **92.12%**（339/368），对齐 07 §1.2 行≥90% / 分支≥85% |
| T44 真实边 | `identity -> foundation`、`publication -> foundation`、`publication -> identity`（唯一跨业务域边命中 02 §3.1 允许列表，非空跑）；脚本与 `DependencyAcyclicTest` 双证据 |

### 口径差异（2026-11-15 已对齐；历史登记保留）

> 以下 1–6 为 2026-11-13 执行时如实登记的口径差异；**1、2、3、6 已由 Owner 授权于 2026-11-15 修复**
> （docs/02、docs/03、docs/08、docs/09、docs/10、项目基线就地对齐 + 各文档修订记录留痕），4、5 保留为运行时注意事项。

1. **operationId 基数**（✅ 已修复 2026-11-15）：只读契约原文实测 **63 个且全部唯一**，`docs/10` 原 A.8 与阶段三任务书的 74 已修订为 63（契约 SHA256 与只读基线完全一致，契约从未改动）。`validate_contract.py` 与 `ContractSkeletonTest` 的 `DOC_OPERATION_ID_COUNT` 同步改为 63，基数对照逻辑保留为「文档-契约漂移」监控。
2. **文件数**（✅ 已修复 2026-11-15）：`docs/10` A.2 汇总原写「新建 57 个文件」，逐项实为
   `7(F01–F07) + 17(F10–F26) + 9(F27–F35) + 7(F36–F42) + 6(F43–F48) + 10(F49–F58) = 56`；docs/10 已按 56 改正，未臆造第 57 个文件。
3. **B2005 语义**（✅ 已澄清 2026-11-15）：03 §7 表后已加口径澄清注记——纯状态机统一按 `docs/10` A.3.1「未命中一律 B2001」
   返回（`TERMINATED` 的所有恢复事件均拒绝），B2005 的治理语义映射属 P0-B 服务层，两层口径不冲突。
4. **ErrorCodes 文件形态**：Java 要求 public 顶层类型名与文件名一致，故 `ErrorCodes.java` 为顶层容器 +
   嵌套 `ErrorCode` 枚举，引用路径即 B.3.3 的 `foundation.ErrorCodes.ErrorCode`。（无需文档改动）
5. **JaCoCo 路径**：项目路径含中文时 agent 写 `destfile` 失败；覆盖率 profile 把执行数据写到纯 ASCII
   临时目录（`${java.io.tmpdir}/lumin-jacoco.exec`），未改动任何业务代码。（运行时注意事项，保留登记）
6. **目录基线差异**（✅ 已修复 2026-11-15）：`docs/02 §4`、`docs/09 §5`、`docs/10 A/B`、`启明书院_项目基线.md §3`
   已按 2026-11-13 整理后的 `lumin-backend`（后端）与 `lumin-frontend`（前端）布局修订正文路径；
   `services/agent/`、`mobile/`、`infra/` 标注「规划，尚未创建」。
7. **数据库驱动与 docs/02 锁定不一致**（✅ 已裁决 2026-11-16，Owner：**数据库使用 MySQL**）：
   docs/02 §2 / docs/04 §1 / docs/07 §1.2 已修订为 **MySQL 8.4 LTS**（修订记录留痕）；
   本机 MySQL84 服务实测 8.4.6（127.0.0.1:3306），`mysql-connector-j` 由 Boot BOM 管理继续使用。
   docs/04 的 PostgreSQL 方言按 §1 映射表落为 MySQL（DATETIME(6) 存 UTC / JSON / CHAR(36) UUID /
   生成列模拟部分唯一索引），持久化集成测试直连本机真实 MySQL（不使用 Docker，凭据经
   `LUMIN_TEST_DB_*` 环境变量注入、不落盘）。

### 未执行 / 不能验证项

HTTP 状态与响应 schema 运行断言（T42 的结构部分已做）、下架 60 秒全入口（T23）、T39–T41 双端无障碍与真机、
T43 Outbox/Inbox 崩溃恢复、T45–T49 性能/限流/灾备/成本、B1–B6 与 R1–R10 及 A4 真实交易、
consumer/provider 契约测试、真实供应商接入、K-02 / K-03 / K-04 / K-05 / K-06 上线复核。
**测试通过只证明纯函数与状态机在合成夹具下的行为正确，不等于接口联调、三平台旅程或上线验收。**
