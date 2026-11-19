# 只读静态校验脚本（T38 / T42 / T44）

两个脚本均为 **Python 3 标准库**实现（`re / json / pathlib / hashlib / argparse`），实测 `import yaml` 失败，故采用纯文本 + 正则 + 缩进级解析，**不依赖 PyYAML**，不联网、不落盘、不改任何被校验文件。

## 用法

在项目根目录 `启明书院/` 下执行：

```text
python libs/tools/validate_contract.py
python libs/tools/check_dependency_cycles.py
python libs/tools/check_dependency_cycles.py --inject "foundation->identity"
python libs/tools/check_dependency_cycles.py --inject "moderation->authoring.repository"
```

## 退出码

| 退出码 | 含义 |
| --- | --- |
| `0` | 全部断言通过，输出以「结构校验 PASS，非接口联调/功能验收」结尾 |
| `1` | 至少一项断言失败（打印 `[FAIL]` 明细），或契约 SHA256 前后不一致 |

## `validate_contract.py` 校验项

1. 只读打开 `contracts/p0.openapi.yaml`，前置/后置比对 SHA256 基线 `5add7f6b48fa314c9340c34b12ed4e2604328927f3f24bbb1658c469612dbcaf`，证明契约未被修改。
2. `openapi: 3.0.3` 与 `info` 存在。
3. 全部 `operationId` 唯一；**基数差异如实登记**（见下）。
4. 所有 `#/components/...` `$ref` 可解析到已定义键（缩进级收集 six 个子段）。
5. 含 `{id}` 的路径均已声明 `#/components/parameters/Id`。
6. `paths` 段交易关键字 `billing|payment|order|refund|settlement|ledger|gift|coin|payout|charge|transfer` 零命中；`BillingOrderReserved` 未被任何 path 引用。
7. `MoneyMinor` 精确为 `integer / int64 / minimum 0 / maximum 9007199254740991`。
8. `BillingOrderReserved` 引用 `MoneyMinor` 且字段/枚举完整。
9. `AccessPolicy.adultContentEnabled: [false]`。
10. 错误码三方一致：契约 `ErrorResponse.code`（13 条）、`libs/error-codes.json`（13 条错误码 + `OK`）、`docs/05` §5 表格（HTTP 与稳定文案逐条比对）。

### 已知口径差异（不伪造、不掩盖）

`docs/10` A.8 与阶段三任务书写「74 个 operationId」，**只读契约原文实测为 63 个且全部唯一**。契约 SHA256 与架构师记录的只读基线完全一致，证明契约未被改动；63 是契约原始事实。脚本以「唯一性」为通过条件，把基数差异作为 `[WARN]` 显式打印并请 Owner / 架构师裁决，**绝不把 63 伪造成 74，也不擅自修改只读契约**。

## `check_dependency_cycles.py` 校验项

- 扫描 `lumin-backend/lumin-{domain,application,infrastructure,ai,bootstrap}/src/main/java/**/*.java` 的 `package` 与显式 `import com.leyon.lumin.*`，按第 4 段归一模块（`foundation.values` 归入 `foundation`），AI 模块允许 `ai -> foundation`。
- 按 02 §3.1 允许列表逐边比对；`foundation → 任意业务域`、`跨模块直接 Repository 访问` 为显式禁止边。
- 模块级有向图 DFS 环检测。
- `--inject` 把非法边写入**内存图**（不改源码、不落盘），用于 07 §2.4 T44 的「注入必失败 / 恢复合法必通过」对照：`foundation->identity` 与 `moderation->authoring.repository` 均必须退出码非 0；移除注入重跑必须退出码 0。

## 能力边界（强制）

输出结尾一律标注「**结构校验 PASS，非接口联调/功能验收**」（N-05 / 05 §6）。脚本**只能**提供：

- 显式 `import` 的模块方向合法、模块图无环、`foundation` 未反向绑定业务域、P0-A 唯一跨域边 `publication → identity` 命中允许列表、注入非法边后校验确实失败。
- 契约的行级正则 / 缩进级结构完整性。

脚本**不能**替代：运行时 DI 装配、反射与注解处理器隐式依赖、跨服务异步消息耦合、P0-B/C 的六层真实装配、HTTP 运行断言、consumer/provider 契约测试、CI 最终门禁、三端旅程与上线验收。
