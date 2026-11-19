#!/usr/bin/env python3
"""UGC-001/026 契约—路由对账工具（只读，Python3 标准库）。

用途：
  1. 解析 contracts/p0.openapi.yaml 的 paths/operationId（行级正则，不依赖 PyYAML）；
  2. 解析 lumin-bootstrap 的 Spring Controller 路由（类级 @RequestMapping + 方法级注解）；
  3. 按 method + 归一化 path（{id} -> {}）比对，输出实现/缺失/契约外路由；
  4. --write-baseline 生成基线；--check 与基线比对，发生漂移时退出码 1。

边界：只做静态注解比对；不验证运行时装配、鉴权、请求体 schema 或响应契约。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "contracts" / "p0.openapi.yaml"
BOOTSTRAP = ROOT / "lumin-backend" / "lumin-bootstrap" / "src" / "main" / "java"
BASELINE = ROOT / "libs" / "contract-route-baseline.json"
HTTP_METHODS = ("get", "post", "put", "delete", "patch")


def normalize(path: str) -> str:
    """把路径变量归一为 {}，并压缩重复斜杠、去掉尾斜杠。"""
    path = re.sub(r"\{[^}]+\}", "{}", path)
    path = re.sub(r"/+", "/", path)
    return path.rstrip("/")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_contract() -> list[dict]:
    lines = CONTRACT.read_text(encoding="utf-8").splitlines()
    operations: list[dict] = []
    current: str | None = None
    for raw in lines:
        path_match = re.match(r"^  /((api|internal)/v1)[^:]*:\s*$", raw)
        if path_match:
            current = raw.strip().rstrip(":")
            continue
        method_match = re.match(r"^    (" + "|".join(HTTP_METHODS) + r"):\s*$", raw)
        if method_match and current:
            operations.append({"method": method_match.group(1).upper(),
                               "path": current, "operationId": ""})
            continue
        op_match = re.match(r"^      operationId:\s*(\S+)", raw)
        if op_match and operations and not operations[-1]["operationId"]:
            operations[-1]["operationId"] = op_match.group(1)
    return operations


def annotation_value(annotation: str) -> str:
    match = re.search(r'(?:value\s*=\s*|path\s*=\s*)?"([^"]*)"', annotation)
    return match.group(1) if match else ""


def method_name_after(source: str, position: int) -> str:
    """从注解放置点向后查找公开方法名（用于矩阵展示）。"""
    match = re.search(r"(?:public|protected|private)\s+[\w<>\[\], .?]+\s+(\w+)\s*\(",
                      source[position:position + 800])
    return match.group(1) if match else "?"


def parse_controllers() -> list[dict]:
    routes: list[dict] = []
    for file in sorted(BOOTSTRAP.rglob("*Controller.java")):
        source = file.read_text(encoding="utf-8")
        base_match = re.search(r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]+)"', source)
        base = base_match.group(1) if base_match else ""
        for match in re.finditer(r"@(Get|Post|Put|Delete|Patch)Mapping\b", source):
            method = match.group(1).upper()
            position = match.end()
            while position < len(source) and source[position] in " \t\r\n":
                position += 1
            sub = ""
            if position < len(source) and source[position] == "(":
                depth = 0
                start = position
                while position < len(source):
                    char = source[position]
                    if char == "(":
                        depth += 1
                    elif char == ")":
                        depth -= 1
                        if depth == 0:
                            break
                    position += 1
                sub = annotation_value(source[start:position + 1])
            routes.append({
                "method": method,
                "path": base + sub,
                "handler": f"{file.stem}#{method_name_after(source, position)}",
                "file": str(file.relative_to(ROOT)),
            })
    return routes


def build_matrix() -> dict:
    contract_ops = parse_contract()
    routes = parse_controllers()
    contract_keys = {(op["method"], normalize(op["path"])) for op in contract_ops}
    route_by_key: dict[tuple[str, str], dict] = {}
    for route in routes:
        route_by_key.setdefault((route["method"], normalize(route["path"])), route)

    implemented, missing = [], []
    for op in contract_ops:
        route = route_by_key.get((op["method"], normalize(op["path"])))
        entry = {
            "operationId": op["operationId"],
            "method": op["method"],
            "path": op["path"],
            "handler": route["handler"] if route else "",
        }
        (implemented if route else missing).append(entry)

    extra = []
    for route in routes:
        key = (route["method"], normalize(route["path"]))
        if key not in contract_keys:
            extra.append({"method": route["method"], "path": route["path"],
                          "handler": route["handler"]})

    return {
        "contract_sha256": sha256(CONTRACT),
        "contract_operations": len(contract_ops),
        "contract_paths": len({op["path"] for op in contract_ops}),
        "controller_routes": len(routes),
        "implemented_count": len(implemented),
        "missing_count": len(missing),
        "extra_count": len(extra),
        "implemented": implemented,
        "missing": missing,
        "extra": extra,
    }


def markdown(matrix: dict) -> str:
    lines = [
        "## 汇总",
        "",
        f"- 契约 operationId：{matrix['contract_operations']}（路径 {matrix['contract_paths']}）",
        f"- Controller 路由：{matrix['controller_routes']}",
        f"- 已实现：{matrix['implemented_count']}；缺失：{matrix['missing_count']}；契约外：{matrix['extra_count']}",
        "",
        "## 契约操作",
        "",
        "| operationId | method | path | 实现位置 |",
        "| --- | --- | --- | --- |",
    ]
    for op in matrix["implemented"] + matrix["missing"]:
        lines.append(f"| {op['operationId'] or '—'} | {op['method']} | `{op['path']}` | "
                     f"{op['handler'] or '—'} |")
    lines += ["", "## 契约外路由", "", "| method | path | 实现位置 |", "| --- | --- | --- |"]
    for route in matrix["extra"]:
        lines.append(f"| {route['method']} | `{route['path']}` | {route['handler']} |")
    return "\n".join(lines) + "\n"


def check(matrix: dict) -> int:
    if not BASELINE.is_file():
        print(f"[FAIL] 基线不存在：{BASELINE}（先执行 --write-baseline）")
        return 1
    baseline = json.loads(BASELINE.read_text(encoding="utf-8"))
    if baseline.get("contract_sha256") != matrix["contract_sha256"]:
        print("[FAIL] 契约 SHA256 与基线不一致：契约被改动或基线过期")
        return 1
    baseline_missing = {(item["method"], normalize(item["path"])) for item in baseline["missing"]}
    current_missing = {(item["method"], normalize(item["path"])) for item in matrix["missing"]}
    baseline_extra = {(item["method"], normalize(item["path"])) for item in baseline["extra"]}
    current_extra = {(item["method"], normalize(item["path"])) for item in matrix["extra"]}
    if baseline_missing != current_missing:
        only_baseline = sorted(baseline_missing - current_missing)
        only_current = sorted(current_missing - baseline_missing)
        print("[FAIL] 契约缺失集合漂移。已补实现：" + (", ".join(map(str, only_baseline)) or "无"))
        print("      新增缺口：" + (", ".join(map(str, only_current)) or "无"))
        return 1
    if baseline_extra != current_extra:
        only_baseline = sorted(baseline_extra - current_extra)
        only_current = sorted(current_extra - baseline_extra)
        print("[FAIL] 契约外路由漂移。已移除：" + (", ".join(map(str, only_baseline)) or "无"))
        print("      新增：" + (", ".join(map(str, only_current)) or "无"))
        return 1
    print(f"[PASS] 契约—路由基线一致：实现 {matrix['implemented_count']}/"
          f"{matrix['contract_operations']}，缺失 {matrix['missing_count']}，"
          f"契约外 {matrix['extra_count']}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="契约—Spring 路由静态对账")
    parser.add_argument("--format", choices=("json", "md"), default="json")
    parser.add_argument("--write-baseline", action="store_true")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    matrix = build_matrix()

    if args.write_baseline:
        BASELINE.write_text(json.dumps(matrix, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"[OK] 已写入基线：{BASELINE}")
        return 0
    if args.check:
        return check(matrix)
    if args.format == "md":
        print(markdown(matrix), end="")
    else:
        print(json.dumps(matrix, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
