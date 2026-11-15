# libs：错误码字典与只读校验工具

本目录只放**机器可读字典**与**只读静态校验脚本**，不放业务实现。

## 错误码字典（`error-codes.json`）

- 唯一来源：`docs/05-接口设计文档.md` §5「错误码全集」，共 **14 条**（`OK` + 13 个错误码）。
- 三处必须逐条一致：`libs/error-codes.json`、`contracts/p0.openapi.yaml` 的 `ErrorResponse.code` 枚举（13 条）、`lumin-backend/src/main/java/com/leyon/lumin/foundation/ErrorCodes.java` 的 `ErrorCode` 枚举（14 条）。
- 维护规约（团队守则 §2.4）：**禁止在源码中散落错误码字符串字面量**；新增/变更错误码必须同时更新字典、契约（契约只读，变更须另行审批）与 Java 枚举，并跑 `validate_contract.py` + `ErrorCodesTest`。
- `OK` 的 HTTP 记为 200（05 §5 写「200/202」，语义由操作决定）；契约 `SuccessBase.code` 枚举为 `[OK]`。

## `tools/`

见 `tools/README.md`。两个脚本均为 **Python 3 标准库**实现，只读 `contracts/p0.openapi.yaml` 与源码，不落盘、不改文件、不联网。

## 边界声明

本目录的字典与脚本只提供**结构级静态证据**，不构成接口联调、功能验收或上线验收（05 §6、N-05）。
