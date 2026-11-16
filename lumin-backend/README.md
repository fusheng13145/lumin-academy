# lumin-backend · 启明书院后端

> **本模块 = Spring Boot Web 应用 + Java 21 分层后端（同一 Maven 模块、同一根包 `com.leyon.lumin`）。**
> **分层（2026-11-17 重构，对齐 02 §3.1/§4 与项目基线 §3.2）**：每个业务模块按
> `api`（接入层 Controller+DTO）→ `service`（应用服务，事务边界）→ `repository`（数据访问，
> 唯一 DB 出口）→ `domain`（领域模型与纯函数）组织；`event/config` 层随治理与事件批次落地。
> `foundation` 为共享内核：`values/event/idempotency/cursor` 与各模块 `domain` 包保持
> **零 Spring 依赖**（不可变 `record` + 显式枚举白名单 + 表驱动状态机，入口 `public static` 纯函数，
> 时间由 `Clock` 注入）；`foundation/web` 与 `foundation/repository` 为基础设施适配
> （统一信封、全局异常、TraceId、骨架认证过滤器、Mapper），属 Spring 技术层。
> 领域包零 Spring 依赖这一点在架构测试里可复核（依赖无环单测只扫 `com.leyon.lumin.*` 模块边）。

## 离线命令

全部依赖与插件为本地已缓存版本（02 §2.1 / 08 §4.1）：

```text
cd lumin-backend
mvn test                        # 主验收命令：编译 + 全量 JUnit 回归
mvn verify -Pcoverage           # 可选：JaCoCo 0.8.15 覆盖率报告（target/site/jacoco/）
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

## 目录与包

| 路径 / 包 | 内容 |
| --- | --- |
| `src/main/java/com/leyon/lumin/LuminBackendApplication.java` | Spring Boot 启动类（根包） |
| `src/main/java/com/leyon/lumin/foundation` | 共享内核：`ErrorCodes`/`BusinessTime`/`StableBuckets`/`LuminBusinessException`；`values/` 共享枚举白名单（零 Spring）；`event/` 事件信封 + Outbox 退避；`idempotency/` 幂等规则 + 适配服务；`cursor/` 游标签码；`repository/` outbox/inbox/idempotency Mapper；`web/` 统一信封/全局异常/TraceId/**骨架认证过滤器**（基础设施适配，Spring 技术层） |
| `src/main/java/com/leyon/lumin/{module}/api` | 接入层 Controller + DTO（已落地：`catalog/api/WorkController`、`authoring/api/DraftController`，其余随各批次落地） |
| `src/main/java/com/leyon/lumin/{module}/service` | 应用服务（编排与事务边界；已落地：`catalog/service/WorkService`、`authoring/service/DraftCommandService`） |
| `src/main/java/com/leyon/lumin/{module}/repository` | 数据访问层（MyBatis Mapper + 行记录，唯一 DB 出口；原 `persistence` 包已按基线六层更名） |
| `src/main/java/com/leyon/lumin/{module}/domain` | 领域模型与纯函数：identity（AgePolicy/RolePolicy）、catalog（TargetRef）、authoring（DraftContentValidator/MaterialSnapshot/SubmissionGate）、publication（VisibilityPolicy/StateMachine/WorkflowSuspension）、moderation（路由/采样/案件状态机/申诉状态机）、governance（RestrictionStateMachine）、configuration（ConfigRevisionStateMachine/GrayRolloutPolicy）、engagement（ReadingProgressValidator） |
| `src/main/java/com/leyon/lumin/billing_reserved` | 04 §6.2 六张表的**只读**骨架模型，无 DDL / 无入口 / 无授权 |
| `src/main/resources/db/migration/` | 版本化迁移脚本：`V1__p0b_core_schema.sql`（docs/04 → MySQL 8.4 方言 16 表） |
| `src/test/java/com/leyon/lumin/**` | 纯函数穷举、架构无环、契约骨架、切片端点集成（MockMvc）、真实 MySQL 集成（`support/` 基类与夹具，凭据经 `LUMIN_TEST_DB_*` 注入） |

模块方向：业务域 → `foundation` 单向；唯一真实跨域边 `publication → identity`
（`VisibilityPolicy` 复用 `AgePolicy.canRead`），在 02 §3.1 允许列表内，供 T44 校验
（`DependencyAcyclicTest` 与 `libs/tools/check_dependency_cycles.py` 双证据）。

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
