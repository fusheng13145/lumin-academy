# 启明书院 · 后端多模块与 AI 模块设计（Java + Ollama / DeepSeek）

> 文档编号：18  
> 版本：v1.0（2026-11-18）  
> 适用范围：`lumin-backend` 多模块重构、`lumin-ai` 独立模块、长期记忆与三模式衔接  
> 关联文档：`docs/15-作者工作台详细设计.md`、`docs/17-Android移动端实施设计（Java+React+Capacitor）.md`、`docs/04`（数据库）、`docs/05`（接口）

---

## 1. 目标与约束

### 1.1 本次目标

1. 将后端从单 Maven 模块拆为 **5 个物理模块**，而不是仅拆包；
2. 采用 **DDD-lite**：核心域建模，支撑域保持简单，不引入全量六边形/CQRS/事件溯源；
3. 将 AI 能力独立为 `lumin-ai`，使其可独立替换供应商、独立测试、独立演进；
4. AI 先接本地 **Ollama `deepseek-r1:8b`**，后续通过配置切换 **DeepSeek 官方 API**；
5. 保持既有 API 行为、既有 3478 个 bootstrap 测试 + 3 个 AI 测试通过、单一可执行 Boot JAR；
6. 为“传统写作 / AI 辅助 / 纯 AI”三种模式提供统一但可区分的行为边界。

### 1.2 明确约束

| 约束 | 说明 |
| --- | --- |
| 单体部署 | 不拆微服务；多模块只是构建与依赖边界，运行时仍是一个 Boot 应用 |
| 唯一启动模块 | 只有 `lumin-bootstrap` 打可执行 JAR；其余模块为普通 jar |
| 领域零框架 | `lumin-domain` 不引入 Spring / MyBatis；当前仅保留 Jackson 用于配置 JSON 校验（已知例外） |
| AI 不直写正文 | AI 只产出候选；作者确认后由写作中心写草稿，AI 不持有 `chapter/draft` 写权限 |
| 记忆先候选 | AI 抽取的长期记忆先 `CANDIDATE`，作者确认后才 `ACTIVE` |
| 供应商可切换 | workflow/Agent 不感知 Ollama 与 DeepSeek 的协议差异 |

---

## 2. 架构总览

### 2.1 模块与依赖方向

```text
                    +----------------------+
                    |   lumin-bootstrap    |  唯一 Boot 启动 / Controller / 资源 / 测试
                    +----------+-----------+
                               |
          +--------------------+--------------------+
          |                    |                    |
+---------v--------+  +--------v---------+  +-------v--------+
| lumin-application|  |lumin-infrastructure|  |   lumin-ai    |
| 用例 / DTO / 端口 |  | Mapper/存储/Outbox |  | workflow/记忆  |
+---------+--------+  +--------+---------+  +-------+--------+
          |                    |                    |
          +----------+---------+----------+---------+
                     |                    |
              +------v------+      +------v------+
              |lumin-domain |<-----|  provider   |
              | 纯领域模型   |      | Ollama/DeepSeek |
              +-------------+      +-------------+
```

依赖规则（编译期）：

| 模块 | 允许依赖 | 禁止依赖 |
| --- | --- | --- |
| `lumin-domain` | 仅 JDK + Jackson（配置校验例外） | Spring、MyBatis、Web、AI |
| `lumin-application` | `lumin-domain`、Spring context/tx、MyBatis 注解、Jackson | Controller、Boot 启动、Infrastructure 实现 |
| `lumin-infrastructure` | `lumin-application`、`lumin-ai`、Spring、MyBatis | Controller、Boot 启动 |
| `lumin-ai` | `lumin-domain`、`lumin-application`、Spring context/tx、Jackson | Infrastructure 实现、Controller |
| `lumin-bootstrap` | 以上全部 + Web/MyBatis starter/MySQL | 不被任何模块依赖 |

`infrastructure -> ai` 的方向用于让 MyBatis 适配器实现 `AiRunStore` / `AiMemoryStore` 端口；`ai` 不知道数据库存在。

### 2.2 与原单模块的对应关系

| 原包 | 新模块 | 说明 |
| --- | --- | --- |
| `{module}/domain`、`billing_reserved`、`foundation/values`、错误/时间/桶 | `lumin-domain` | 纯函数与领域策略 |
| `{module}/service`、`repository`、API DTO、`publication/event`、`foundation/port`/`idempotency`/`cursor`/`audit` | `lumin-application` | 用例编排与出站契约 |
| `foundation/event/OutboxScheduler`、`foundation/storage/LocalStorageObjectStore` | `lumin-infrastructure` | 运行时适配器 |
| `{module}/api/*Controller`、`foundation/web`、启动类、资源、测试 | `lumin-bootstrap` | 接入层与运行时 |
| 新增 AI | `lumin-ai` + `infrastructure` 的 AI 持久化 | provider/workflow/memory/端口 |

### 2.3 运行时装配

- Spring Boot 组件扫描根仍为 `com.leyon.lumin`；各模块 jar 内的 `@Component`/`@Service`/`@Configuration` 均被扫描。
- MyBatis 扫描由 `PersistenceConfiguration` 统一声明：只扫描带 `@Mapper` 的接口，避免把 `AiRunStore` 等端口误注册成 Mapper。
- `lumin-bootstrap` 的 `application.yaml` 同时承载数据源、Outbox、AI 供应商与 workflow 预算配置。
- 包名保持 `com.leyon.lumin.*`，不因模块拆分改包；减少 Controller/测试的迁移成本。

---

## 3. 模块职责与边界

### 3.1 `lumin-domain`

职责：

- 聚合根、值对象、状态机、领域策略、业务不变量；
- 纯函数优先，不依赖数据库、HTTP、Spring；
- 示例：`PublicationStateMachine`、`VisibilityPolicy`、`DraftContentValidator`、`AgePolicy`、`RolePolicy`。

边界：

- 不出现 `@Service`、`@Transactional`、`Mapper`、`Controller`；
- 唯一已知外部依赖是 `ConfigPayloadValidator` 使用 Jackson 做 JSON 结构校验；这是当前 pragmatic 例外，后续可改为 `JsonPayload` 端口。

### 3.2 `lumin-application`

职责：

- 用例编排与事务边界；
- DTO/VO/请求对象；
- 出站端口：`ContentObjectStore`、`AuditRecorder`、`PublicationWorkflowPort` 等；
- MyBatis 仓储契约（Mapper 接口 + Row 记录）。

边界：

- 不依赖 Controller、Servlet、Boot 启动；
- 不直接实现外部供应商调用；AI 通过 `lumin-ai` 的端口反向注入。

> 说明：DDD-lite 不要求把 23 个既有 service 一次性端口化。当前保留 `service -> repository` 的直接依赖，是为了先完成模块边界与行为不变；端口化按 P1 分域收敛。

### 3.3 `lumin-infrastructure`

职责：

- MyBatis Mapper 扫描配置；
- 文件对象存储 `LocalStorageObjectStore`；
- Outbox 调度 `OutboxScheduler`；
- AI 运行/阶段/候选/记忆的持久化适配器。

边界：

- 不写业务规则；只做协议/存储转换；
- 不依赖 `lumin-bootstrap`。

### 3.4 `lumin-ai`

职责：

- `AiProvider` 端口与 Ollama/DeepSeek 适配器；
- `AiWorkflowService` 编排；
- 多 Agent：计划、起草、一致性、记忆抽取；
- 长期记忆端口 `AiRunStore`、`AiMemoryStore`；
- AI API 的视图对象（`AiRunView` 等）。

边界：

- 不直接访问数据库；
- 不直接写 `draft/chapter`；
- 不包含 Controller（Controller 留在 bootstrap，避免接入层污染 AI 模块）。

### 3.5 `lumin-bootstrap`

职责：

- 唯一 `LuminBackendApplication`；
- 所有 Controller、全局异常、TraceId、幂等执行器、骨架认证；
- `application.yaml`、`db/migration`、测试；
- 打包可执行 Boot JAR。

---

## 4. 为什么是 DDD-lite，而不是全量 DDD

| 方案 | 优点 | 代价 | 结论 |
| --- | --- | --- | --- |
| 按 9 个业务域拆 20+ Maven 模块 | 边界极清晰 | 构建复杂、循环依赖治理成本高、单人项目维护负担大 | 不采用 |
| 单模块只拆包 | 改动最小 | 无编译期边界，包依赖容易回退 | 不满足“模块化”要求 |
| 4 层模块 + 独立 AI | 编译期约束、职责清晰、可独立测试 | 需要处理少量跨层适配 | **采用** |
| 全量六边形 + CQRS + 事件溯源 | 理论完备 | 对当前业务量过度设计，学习/维护成本高 | 不采用 |
| DDD-lite：核心域聚合 + 支撑域简单 | 保留领域模型价值，避免过度抽象 | 部分 service 仍直接依赖 repository | **采用，分阶段收敛** |

DDD-lite 的落地判据：

1. 核心域（authoring/publication/moderation/governance/AI memory）有聚合、值对象、状态机、领域事件；
2. 看板/搜索/通知/配置/审计等支撑域允许简单 CRUD，不强行聚合；
3. 领域层不依赖框架；
4. 模块依赖用 ArchUnit/编译期约束固化，而不是靠约定。

---

## 5. AI 模块设计

### 5.1 供应商端口

```java
public interface AiProvider {
    String name();
    String model();
    boolean available();
    AiCompletion generate(AiRequest request);
}
```

统一返回：

```java
record AiCompletion(
    String content,
    String provider,
    String model,
    int promptTokens,
    int completionTokens,
    String finishReason
)
```

适配器：

| 供应商 | 协议 | 默认地址 | 默认模型 | 鉴权 |
| --- | --- | --- | --- | --- |
| Ollama | `POST /api/chat`，`stream=false` | `http://127.0.0.1:11434` | `deepseek-r1:8b` | 无 |
| DeepSeek | `POST /chat/completions`（OpenAI-compatible） | `https://api.deepseek.com` | `deepseek-chat` | `DEEPSEEK_API_KEY` |

切换只改配置，不改 workflow：

```yaml
lumin:
  ai:
    provider: ${LUMIN_AI_PROVIDER:ollama}
    timeout-seconds: ${LUMIN_AI_TIMEOUT_SECONDS:120}
    ollama:
      base-url: ${OLLAMA_BASE_URL:http://127.0.0.1:11434}
      model: ${OLLAMA_MODEL:deepseek-r1:8b}
    deepseek:
      base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com}
      model: ${DEEPSEEK_MODEL:deepseek-chat}
      api-key: ${DEEPSEEK_API_KEY:}
```

启用 DeepSeek：

```powershell
$env:LUMIN_AI_PROVIDER="deepseek"
$env:DEEPSEEK_API_KEY="sk-..."
```

### 5.2 Workflow 阶段

`AiWorkflowService` 的固定阶段：

| 顺序 | 阶段 | 执行者 | 输出 | 失败影响 |
| --- | --- | --- | --- | --- |
| 0 | 资格/预算 | Service | 运行记录 `QUEUED` | 直接拒绝（P1001/D2001） |
| 1 | 记忆上下文 | Service | 已确认记忆摘要 | 失败则整次失败 |
| 2 | 章节计划 | `PlanningAgent` | 开场钩子/冲突/结尾钩子 | 失败则整次失败 |
| 3 | 起草 | `DraftingAgent` | 1200–2000 字正文 | 失败则整次失败 |
| 4 | 一致性检查 | `ConsistencyAgent` | `[BLOCKER]/[WARN]/OK` | 供应商空输出/超时按 `OK` 继续，不阻断候选 |
| 5 | 有限修订 | `DraftingAgent` | 修订稿（可选） | 仅 `[BLOCKER]` 时触发，最多 1 次 |
| 6 | 记忆抽取 | `MemoryExtractionAgent` | JSON 记忆候选 | 失败不阻断正文，记录原始输出 |
| 7 | 候选包 | Service | `ai_candidate(PENDING)` | 失败则整次失败 |

阶段全部落 `ai_run_stage`，前端可逐阶段展示；`attempt` 区分首次与修订。

### 5.3 多 Agent 职责

| Agent | 输入 | 输出约束 |
| --- | --- | --- |
| `PlanningAgent` | 作品/章节 ID、作者指令、长期记忆 | 只输出中文计划，不写正文 |
| `DraftingAgent` | 计划、记忆、作者指令、修订意见 | 只输出正文，不加标题/Markdown/解释 |
| `ConsistencyAgent` | 计划、草稿、记忆 | 只标问题，不改写；`[BLOCKER]` 触发修订 |
| `MemoryExtractionAgent` | 草稿、已有记忆 | 只输出 JSON 数组；字段 `kind/subjectKey/content/confidence` |

`PromptLibrary` 集中管理提示词，禁止在 Controller/Provider 中拼业务提示词。

### 5.4 长期记忆模型

记忆分类：

| `kind` | 用途 | 示例 |
| --- | --- | --- |
| `WORLD` | 世界规则/地理/力量体系 | “北境灵脉每三十年苏醒一次” |
| `CHARACTER` | 人物形象/关系/能力边界 | “主角的旧伤在灵力过载时会复发” |
| `PLOT` | 已发生情节/时间线 | “主角已在第三章失去师尊” |
| `FORESHADOW` | 伏笔与回收状态 | “青铜铃铛尚未揭示来源” |
| `STYLE` | 文风/叙事视角 | “第三人称限知，禁止全知旁白” |
| `CONSTRAINT` | 硬约束/禁区 | “不能复活已死亡角色” |

状态流转：

```text
EXTRACTED -> CANDIDATE -> ACTIVE
                    \-> REJECTED
ACTIVE -> ARCHIVED
```

- 抽取：`MemoryExtractionAgent` 输出候选；
- 落库：`memory_fact(status=CANDIDATE)` + `memory_event(EXTRACTED)`；
- 确认：作者调用 `POST /api/v1/ai/memory/{workId}/facts/{factId}/confirm`；
- 注入：只把 `ACTIVE` 事实按 `updated_at DESC` 取前 `maxMemoryFacts` 条注入提示词；
- 版本：`sourceVersion` 预留章节/草稿版本；`memory_event` 追加式记录，可重建历史。

记忆注入格式：

```text
- [CHARACTER] 主角：旧伤在灵力过载时复发
- [WORLD] 北境灵脉：每三十年苏醒一次
```

### 5.5 一致性检查与有限修订

1. 默认只做一次起草；
2. 一致性输出包含 `[BLOCKER]` 且 `maxRevisionAttempts > 0` 时，执行一次 `REVISION`；
3. 修订后再次执行 `CONSISTENCY(attempt=2)`，但不再无限循环；
4. `[WARN]` 只展示，不触发重写；
5. 最终候选仍由作者决定是否采纳。

### 5.6 运行生命周期与异步

```text
POST /api/v1/ai/runs
  -> 创建 ai_run(QUEUED)
  -> 提交到 aiWorkflowExecutor（固定 2 线程）
  -> 202 Accepted 返回 runId
GET /api/v1/ai/runs/{runId}
  -> QUEUED / RUNNING / WAITING_AUTHOR / SUCCEEDED / FAILED
  -> stages[] + candidates[]
POST /api/v1/ai/runs/{runId}/candidates/{candidateId}/accept
POST /api/v1/ai/runs/{runId}/candidates/{candidateId}/reject
```

为什么异步：

- 本地 8B 模型生成 1200–2000 字可能超过 HTTP 默认超时；
- 阶段与候选需要可轮询、可恢复；
- 后续接消息队列时，只需替换 `Executor` 为 worker，不改 Controller 契约。

当前边界：

- 使用进程内 `ExecutorService`，不是持久化队列；应用重启会丢失排队中的运行；
- 未实现 SSE；前端用轮询；
- 单机并发上限 2，避免本地 Ollama 被并发打满。

### 5.7 AI 接口

| 方法 | 路径 | 说明 | 状态 |
| --- | --- | --- | --- |
| GET | `/api/v1/ai/health` | 当前供应商、模型、可用性 | 已实现 |
| POST | `/api/v1/ai/runs` | 创建异步运行 | 202 |
| GET | `/api/v1/ai/runs/{runId}` | 阶段/候选/状态 | 200 |
| POST | `/api/v1/ai/runs/{runId}/candidates/{candidateId}/accept` | 采纳候选（仅标记） | 200 |
| POST | `/api/v1/ai/runs/{runId}/candidates/{candidateId}/reject` | 拒绝候选 | 200 |
| GET | `/api/v1/ai/memory/{workId}` | 查询记忆（含候选） | 200 |
| POST | `/api/v1/ai/memory/{workId}/facts/{factId}/confirm` | 作者确认记忆 | 200 |

请求示例：

```json
{
  "workId": "8a7b6c5d-...",
  "chapterId": "9b8c7d6e-...",
  "mode": "ASSISTED",
  "instruction": "写一场雪夜对峙，主角不能直接获胜"
}
```

`mode` 仅接受 `ASSISTED` / `AUTONOMOUS`；`TRADITIONAL` 不进入 AI 运行。

### 5.8 数据模型（V7）

| 表 | 作用 | 关键约束 |
| --- | --- | --- |
| `ai_run` | 运行主记录 | `mode` 只能 ASSISTED/AUTONOMOUS；`state` 六态；FK work |
| `ai_run_stage` | 阶段记录 | `stage_type` 七类；`attempt` 支持修订；FK run |
| `ai_candidate` | 正文候选 | `state` PENDING/ACCEPTED/REJECTED；AI 不直接写 draft |
| `memory_fact` | 长期记忆 | `status` 四态；`confidence` 0–1；软删除 |
| `memory_event` | 记忆变更事件 | `EXTRACTED/CONFIRMED/REJECTED/ARCHIVED/EDITED` |

迁移文件：`lumin-bootstrap/src/main/resources/db/migration/V7__ai_authoring.sql`。

### 5.9 三种创作模式的衔接

| 维度 | 传统写作 | AI 辅助 | 纯 AI |
| --- | --- | --- | --- |
| 编辑器 | 纯文本编辑器；无 AI 按钮 | 编辑器 + 选区/段落 AI 菜单；候选插入 | 任务台 + 阶段视图；候选整体预览 |
| AI 介入 | 无 | 作者提出局部请求；AI 产出候选 | workflow 自动跑计划/起草/一致性/记忆 |
| 作者控制 | 全部 | 逐段采纳/拒绝 | 审阅候选、确认记忆、决定是否落稿 |
| 后端入口 | 不调用 `/api/v1/ai/*` | `POST /ai/runs` + `accept` | 同左，`mode=AUTONOMOUS` |
| 正文写入 | 直接写 draft | 采纳后由写作中心写 draft | 采纳后由写作中心写 draft |
| 记忆 | 不抽取 | 可选抽取 | 每轮抽取候选，作者确认后生效 |
| 版权归属 | 作者原创 | 作者原创 + AI 辅助 | 作者主导设定与指令；AI 生成部分按平台协议处理 |

内容版权处理原则：

1. **作者始终是作品责任人**；AI 不署名、不自动发布；
2. AI 输出在 `ai_candidate` 中保留 `source`（`ASSISTED_WORKFLOW` / `AUTONOMOUS_WORKFLOW`），可追溯；
3. 只有作者点击采纳，候选才进入草稿；拒绝/未处理不会进入正文；
4. 平台协议需明确：AI 生成内容的权利归属、平台展示许可、作者保证不侵权；
5. 记忆事实由作者确认，避免模型把错误设定固化为“事实”。

### 5.10 失败、可观测与安全

| 场景 | 行为 |
| --- | --- |
| Ollama 未启动 | `GET /health` 返回 `available=false`；`POST /runs` 返回 D2001 |
| 模型返回空（PLAN/DRAFT） | Provider 抛 `AiProviderException`；阶段 FAILED，运行 FAILED |
| 模型返回空（CONSISTENCY/MEMORY_EXTRACT） | 阶段 FAILED，但按降级策略继续，正文候选仍产出（本地 R1 常见：思考占满 token 预算） |
| 记忆 JSON 解析失败 | 记录原始输出，记忆候选为空，正文候选照常产出 |
| 模型超时 | 受 `timeout-seconds` 限制；阶段/运行 FAILED |
| API key 缺失 | DeepSeek `available=false`；启动运行被拒绝 |
| 重复采纳 | 非 PENDING 候选返回 B2001 |
| 越权记忆确认 | `workId` 与 `factId` 不匹配返回 B2004 |

安全：

- API key 只从环境变量注入，不写入 YAML 默认值/代码；
- 提示词不拼接数据库 ID 以外的敏感信息；
- 作者身份来自 `DemoAuthenticationFilter` 注入的请求属性，后续替换真实 OIDC；
- 日志只记录 runId/阶段/错误摘要，不打印完整正文或密钥。

---

## 6. 与现有系统的集成

### 6.1 与作者工作台

- `workId`/`chapterId` 复用现有 `catalog` 标识；
- 采纳候选后，客户端调用现有 `POST/PUT /api/v1/drafts` 写入草稿，再走 `submissions` 发布流程；
- AI 不直接调用 `DraftMapper`，保持发布链的既有权限与状态机。

### 6.2 与移动端

- 移动端只做任务台、阶段展示、候选确认，不运行 Agent 循环；
- 后端异步运行 + 轮询适合移动端网络抖动；
- 当前移动端仍是演示数据；接入时把 `src/mobile/services/aiDemo.ts` 替换为 `/api/v1/ai/*` 客户端即可，页面结构不需要重做。

### 6.3 与数据库/迁移

- `V7` 依赖 `work` 表；执行前需先完成 V1–V6；
- `ai_run`/`memory_fact` 对 `work` 使用 `ON DELETE RESTRICT`，避免作品删除后留下孤儿记忆；
- 阶段/候选/事件随 run/fact 级联删除。

---

## 7. 构建、运行与验证

### 7.1 命令

```powershell
cd lumin-backend
mvn test
mvn -DskipTests package
java -jar lumin-bootstrap/target/lumin-bootstrap-0.0.1-SNAPSHOT.jar
```

### 7.2 已验证证据（2026-11-18）

| 项 | 结果 |
| --- | --- |
| 多模块编译 | `mvn -q -DskipTests compile` 通过 |
| 全量测试 | `lumin-bootstrap` 44 套件 / 3478 用例 / 0 fail / 0 error / **0 skipped**（注入测试库密码后 61 个 MySQL 集成测试全部执行）；`lumin-ai` 1 套件 / 3 用例 / 0 fail / 0 error |
| AI 单测 | `AiWorkflowServiceTest` 3 用例通过（六阶段、BLOCKER 修订、候选仅可采纳一次） |
| 可执行 JAR | `lumin-bootstrap-0.0.1-SNAPSHOT.jar`，26,211,170 bytes |
| Ollama | `deepseek-r1:8b` 已安装；`/api/tags` 可用；实际 `/api/chat` 返回中文 |
| AI health | 启动 JAR 后 `GET /api/v1/ai/health` 返回 `provider=ollama, model=deepseek-r1:8b, available=true` |
| 真实 AI 联调 | 使用本地 MySQL（用户名 + 环境注入密码）应用 V7 后，`POST /api/v1/ai/runs` 写入成功；一次 ASSISTED 运行中 `PLAN`/`DRAFT`/`CANDIDATE` 成功，`CONSISTENCY`/`MEMORY_EXTRACT` 因本地 R1 空输出按降级策略继续，最终 `WAITING_AUTHOR`，候选正文 1319 字；`accept` 端点将候选置为 `ACCEPTED` |
| 架构校验 | `check_dependency_cycles.py` 扫描 257 个 Java 文件，0 非法边、0 环 |

### 7.3 已知跳过

61 个 MySQL 集成测试默认因 `LUMIN_TEST_DB_PASSWORD` 未配置而 `Assumption` 跳过；本轮已注入本机测试库凭据，61 个全部执行通过（0 skipped）。密码不写入仓库；CI/新机器仍需自行注入测试库凭据。真实 AI run 也已用同一数据库完成（`WAITING_AUTHOR` + 1319 字候选 + 采纳为 `ACCEPTED`）。

---

## 8. 优先级

### P0（已交付）

- 五模块骨架与依赖方向；
- 既有 API 行为不变、测试通过；
- AI 供应商端口 + Ollama 适配器；
- workflow + 4 Agent + 长期记忆候选/确认；
- V7 表与 AI API；
- 单一 Boot JAR。

### P1（建议下一阶段）

- 候选采纳后自动写 draft（需章节/草稿版本与权限校验）；
- 记忆编辑/拒绝/归档 API；
- 运行持久化队列（应用重启不丢任务）；
- SSE/WebSocket 阶段推送；
- AI 预算/限流/审计（token 统计已返回，未落库）；
- DeepSeek API 联调与失败重试策略；
- 23 个 service 的 repository 端口化，按域收敛。

### P2

- AI 插图 provider（独立图像模型，不复用文本 workflow）；
- 多版本记忆快照与“回滚到某章设定”；
- 多 Agent 评审投票/风格一致性评分；
- 运营侧 AI 内容审核看板。

---

## 9. 验收清单

- [x] 五模块可独立编译，`lumin-bootstrap` 为唯一可执行 JAR
- [x] `lumin-domain` 不依赖 Spring/MyBatis
- [x] `lumin-ai` 不依赖 `lumin-infrastructure`
- [x] Ollama `deepseek-r1:8b` 可调用
- [x] DeepSeek 可通过配置切换（适配器已实现，待密钥联调）
- [x] AI 只产出候选，不直接写正文
- [x] 记忆先 CANDIDATE，作者确认后 ACTIVE
- [x] 全量测试 0 fail / 0 error
- [x] 架构依赖 0 环 / 0 非法边
- [x] 真实 MySQL 应用 V7 + 一次 AI run/候选采纳（手工验证）
- [x] 全量 MySQL 集成测试（本轮注入 `LUMIN_TEST_DB_PASSWORD`，0 skipped）
- [ ] 移动端接入真实 AI API（当前为演示数据）
- [ ] 候选采纳自动写 draft（P1）
