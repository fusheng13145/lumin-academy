# billing_reserved · 只读付费实体骨架（B0）

> **空表 ≠ 交易已可用。** 本包只以 Java `record` / 常量静态描述 04 §6.2 的
> **6 张表**字段与约束，不执行 DDL、不建表、不写库、不暴露交易入口、不授予权限。

## 六张表（U-01 裁决：04 §6.2 原文恰好 6 张，无「第 7 张」）

`billing_account` / `billing_order` / `ledger_entry` / `revenue_share` / `settlement` / `refund`。
04 §6.1 的 `bookmark` / `subscription` / `notification_digest` 是 P1 关系表，不属付费骨架，本批次不建模。

## 金额边界

`MoneyMinor` 为 `long` 分整数，`0 ≤ x ≤ 9007199254740991`（2^53−1，与三端 JSON 精度一致）；
币种固定 `CNY`；**禁止 `double` / `float` / 小数 `BigDecimal`**。
负数仅 `settlement.net_minor` 允许（`ofSigned`，且 `|x| ≤ MAX`），其余字段一律 `ofNonNegative`。

## 恒为 false 的三个开关

- `BillingModelRegistry.hasHttpEntry()` → `false`：契约 `paths` 中无任何交易路由。
- `BillingModelRegistry.isTradingEnabled()` → `false`。
- 每张表的 `exposedViaHttp` / `ddlAllowed` / `creatorPresent` → `false`。

## 权限

`BILLING_PROPOSE` / `BILLING_APPROVE` / `SETTLEMENT_EXECUTE` **仅登记字典、不授予**，
`registeredPermissions()` 返回的是白名单，不是任何主体的授权结果。

## 不执行

B1–B6 交易用例、R1–R10 分账/结算规则、A4 真实退款均**未执行**（01 DEC-06、07 §6）。
本骨架只满足 T38 的「schema 符合 04、无交易端点、权限未授予」结构断言，
**不计作真实交易通过**，也不构成接口联调或功能验收（N-05）。
