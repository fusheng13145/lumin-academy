#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""启明书院 P0-A 契约静态校验（T38 / T42）。

仅使用 Python 3 标准库（实测 PyYAML 未安装，故不依赖）。
对 contracts/p0.openapi.yaml 全程只读，校验前后比对 SHA256 证明契约未被修改。

能力边界：行级正则 + 缩进解析，不是完整 YAML 语义解析；不校验 oneOf /
additionalProperties 语义、不生成三端类型、不执行 HTTP 运行断言、不覆盖
consumer/provider 契约测试。输出一律标注「结构校验 PASS，非接口联调/功能验收」。
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "contracts" / "p0.openapi.yaml"
ERROR_JSON = ROOT / "libs" / "error-codes.json"
API_DOC = ROOT / "docs" / "05-接口设计文档.md"

BASELINE_SHA256 = "5add7f6b48fa314c9340c34b12ed4e2604328927f3f24bbb1658c469612dbcaf"
DOC_OPERATION_ID_COUNT = 63          # docs/10 A.8 口径（2026-11-15 文档口径对齐后，与契约原文实测一致）
MONEY_MINOR_MAX = 9007199254740991   # 2^53-1，04 §5.2 / 契约 MoneyMinor
TRADING_KEYWORD = re.compile(
    r"billing|payment|order|refund|settlement|ledger|gift|coin|payout|charge|transfer",
    re.IGNORECASE,
)
COMPONENT_SECTIONS = {
    "securitySchemes", "parameters", "headers", "requestBodies", "responses", "schemas"
}
FINAL_LINE = "结构校验 PASS，非接口联调/功能验收"


def sha256(path: Path) -> str:
    """返回文件字节内容的 SHA256 十六进制串。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def split_components(lines: list[str]) -> tuple[list[str], list[str]]:
    """把契约按 `components:` 声明切成 paths 段与 components 段。"""
    for index, line in enumerate(lines):
        if line.startswith("components:"):
            return lines[:index], lines[index:]
    return lines, []


def collect_component_keys(component_lines: list[str]) -> dict[str, set[str]]:
    """按缩进级收集 components 下各子段已定义的键名。"""
    keys: dict[str, set[str]] = {name: set() for name in COMPONENT_SECTIONS}
    section = ""
    for line in component_lines:
        section_match = re.match(r"^  ([A-Za-z]+):\s*$", line)
        if section_match and section_match.group(1) in COMPONENT_SECTIONS:
            section = section_match.group(1)
            continue
        key_match = re.match(r"^    ([A-Za-z0-9_]+):", line)
        if key_match and section:
            keys[section].add(key_match.group(1))
    return keys


def collect_paths(path_lines: list[str]) -> list[tuple[str, str]]:
    """返回 (路径, 该路径块文本) 列表。"""
    starts: list[int] = [
        index for index, line in enumerate(path_lines)
        if re.match(r"^  /((api|internal)/v1)[^:]*:\s*$", line)
    ]
    result: list[tuple[str, str]] = []
    for position, start in enumerate(starts):
        end = starts[position + 1] if position + 1 < len(starts) else len(path_lines)
        path = path_lines[start].strip().rstrip(":")
        result.append((path, "\n".join(path_lines[start:end])))
    return result


def main() -> int:
    errors: list[str] = []
    warnings: list[str] = []
    passed: list[str] = []

    if not CONTRACT.is_file():
        print("[FAIL] 契约文件不存在：" + str(CONTRACT))
        return 1
    before = sha256(CONTRACT)
    if before != BASELINE_SHA256:
        errors.append("契约 SHA256 与只读基线不一致：%s" % before)
    else:
        passed.append("契约 SHA256 前置比对一致：" + before)

    raw_bytes = CONTRACT.read_bytes()
    text = raw_bytes.decode("utf-8")
    lines = text.splitlines()
    path_lines, component_lines = split_components(lines)

    # 1) openapi 版本与 info
    if re.search(r"^openapi:\s*3\.0\.3\s*$", text, re.MULTILINE):
        passed.append("openapi: 3.0.3 存在")
    else:
        errors.append("缺少 openapi: 3.0.3")
    if re.search(r"^info:\s*$", text, re.MULTILINE):
        passed.append("info 段存在")
    else:
        errors.append("缺少 info 段")

    # 2) operationId 唯一（基数差异如实登记，不伪造）
    operation_ids = re.findall(
        r"^[ \t]*operationId:[ \t]*([A-Za-z0-9_]+)[ \t]*$", text, re.MULTILINE
    )
    unique_ids = set(operation_ids)
    if not operation_ids:
        errors.append("未解析到任何 operationId")
    elif len(operation_ids) != len(unique_ids):
        duplicates = sorted({item for item in operation_ids if operation_ids.count(item) > 1})
        errors.append("operationId 重复：" + ", ".join(duplicates))
    else:
        passed.append("operationId 全部唯一：%d 个" % len(operation_ids))
    if len(operation_ids) != DOC_OPERATION_ID_COUNT:
        warnings.append(
            "口径差异：docs/10 A.8 记录 %d 个 operationId，"
            "契约原文实测 %d 个；以契约原文为准（SHA256 与基线一致，契约未改动），"
            "不擅自修改契约、不伪造基数。请核查文档是否过期或契约是否被改动。"
            % (DOC_OPERATION_ID_COUNT, len(operation_ids))
        )

    # 3) 全部 $ref 可解析
    defined = collect_component_keys(component_lines)
    refs = re.findall(r"#/components/([A-Za-z]+)/([A-Za-z0-9_]+)", text)
    unresolved: set[str] = set()
    for group, name in refs:
        if group not in defined or name not in defined.get(group, set()):
            unresolved.add("#/components/%s/%s" % (group, name))
    if unresolved:
        errors.append("未解析的 $ref：" + ", ".join(sorted(unresolved)))
    else:
        passed.append("$ref 全部可解析：%d 处引用，%s" % (
            len(refs),
            ", ".join("%s=%d" % (key, len(value)) for key, value in sorted(defined.items())),
        ))

    # 4) 路径参数声明
    paths = collect_paths(path_lines)
    missing_param_paths: list[str] = []
    for path, block in paths:
        placeholders = set(re.findall(r"\{([A-Za-z0-9_]+)\}", path))
        if "id" in placeholders and "#/components/parameters/Id" not in block:
            missing_param_paths.append(path)
    if missing_param_paths:
        errors.append("含 {id} 但缺少 components.parameters.Id 声明的路径：" +
                      ", ".join(missing_param_paths))
    else:
        passed.append("路径参数声明齐全：%d 个 paths（含 {id} 的路径均已声明 Id）" % len(paths))

    # 5) 交易路由零命中 + BillingOrderReserved 不出现在 paths 段
    path_text = "\n".join(path_lines)
    trading_paths = [
        path for path, _ in paths if TRADING_KEYWORD.search(path)
    ]
    if trading_paths:
        errors.append("paths 命中交易关键字：" + ", ".join(trading_paths))
    else:
        passed.append("paths 交易关键字零命中（%d 个关键字族，%d 个路径全检）"
                      % (len(TRADING_KEYWORD.pattern.split("|")), len(paths)))
    if "BillingOrderReserved" in path_text:
        errors.append("BillingOrderReserved 出现在 paths 段（疑似暴露交易入口）")
    else:
        passed.append("BillingOrderReserved 未被任何 path 引用")

    # 6) MoneyMinor 精确边界
    money_match = re.search(
        r"^\s*MoneyMinor:\s*\{([^}]*)\}\s*$", text, re.MULTILINE
    )
    if not money_match:
        errors.append("未找到 MoneyMinor 定义")
    else:
        money = money_match.group(1)
        required = ["type: integer", "format: int64", "minimum: 0",
                    "maximum: %d" % MONEY_MINOR_MAX]
        missing = [item for item in required if item not in money]
        if missing:
            errors.append("MoneyMinor 缺少：" + ", ".join(missing))
        else:
            passed.append("MoneyMinor 边界精确：integer/int64/minimum 0/maximum %d" % MONEY_MINOR_MAX)

    # 7) BillingOrderReserved 骨架引用完整
    reserved = re.search(
        r"^    BillingOrderReserved:[\s\S]*?(?=^    [A-Za-z0-9_]+:|\Z)",
        text, re.MULTILINE,
    )
    if not reserved:
        errors.append("未找到 BillingOrderReserved 骨架定义")
    else:
        block = reserved.group(0)
        needed = ["#/components/schemas/MoneyMinor", "[CHAPTER, BOOK, MEMBERSHIP, GIFT]",
                  "[CNY]", "[CREATED, PAID, CANCELLED, REFUNDED]", "additionalProperties: false"]
        absent = [item for item in needed if item not in block]
        if absent:
            errors.append("BillingOrderReserved 引用不完整：" + ", ".join(absent))
        else:
            passed.append("BillingOrderReserved 引用 MoneyMinor 且字段/枚举完整")

    # 8) AccessPolicy.adultContentEnabled 恒 false
    if re.search(r"adultContentEnabled:\s*\{[^}]*enum:\s*\[false\]\}", text):
        passed.append("AccessPolicy.adultContentEnabled: [false]（A 级默认关闭）")
    else:
        errors.append("AccessPolicy.adultContentEnabled 未锁定为 [false]")

    # 9) 错误码三方一致：契约 / libs/error-codes.json / 05 §5
    error_enum = re.search(
        r"ErrorResponse:[\s\S]*?code:\s*\{type: string, enum:\s*\[([^\]]+)\]\}", text
    )
    if not error_enum:
        errors.append("未解析到 ErrorResponse.code 枚举")
    else:
        contract_codes = [item.strip() for item in error_enum.group(1).split(",")]
        try:
            dictionary = json.loads(ERROR_JSON.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            errors.append("读取错误码字典失败：%s" % exc)
            dictionary = {"codes": []}
        json_codes = [item["code"] for item in dictionary.get("codes", [])]
        json_errors = [code for code in json_codes if code != "OK"]
        doc_table = re.findall(
            r"^\|\s*([A-Z][0-9]{4})\s*\|\s*(\d+)\s*\|\s*(.+?)\s*\|\s*$",
            API_DOC.read_text(encoding="utf-8"), re.MULTILINE,
        )
        doc_codes = [row[0] for row in doc_table]
        if contract_codes != json_errors:
            errors.append("契约错误码与字典不一致：契约=%s 字典=%s" %
                          (contract_codes, json_errors))
        elif doc_codes != json_errors:
            errors.append("05 §5 表格与字典不一致：文档=%s 字典=%s" %
                          (doc_codes, json_errors))
        else:
            passed.append("错误码三方一致：13 条（契约 / 字典 / 05 §5），字典含 OK 共 14 条")
        for row in doc_table:
            code, http, message = row[0], int(row[1]), row[2]
            match = next((item for item in dictionary.get("codes", [])
                          if item["code"] == code), None)
            if match is None or match["http"] != http or match["message"] != message:
                errors.append("字典中 %s 的 HTTP/稳定文案与 05 §5 不一致" % code)

    # 10) SHA256 后置比对，证明契约未被修改
    after = sha256(CONTRACT)
    if after != before:
        errors.append("契约在校验过程中被修改：前置=%s 后置=%s" % (before, after))
    else:
        passed.append("契约 SHA256 后置比对一致（只读校验，文件未被修改）")

    for item in passed:
        print("[PASS] " + item.strip())
    for item in warnings:
        print("[WARN] " + item)
    for item in errors:
        print("[FAIL] " + item)
    print("-" * 72)
    if errors:
        print("结构校验 FAIL：%d 项不通过；非接口联调/功能验收。" % len(errors))
        return 1
    print(FINAL_LINE)
    print("说明：本脚本为行级正则 + 缩进级静态校验（无 PyYAML、非完整 YAML 语义解析），"
          "不执行 HTTP 运行断言、不生成三端类型、不覆盖 consumer/provider 契约测试（05 §6 / N-05）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
