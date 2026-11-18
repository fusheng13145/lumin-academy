#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""启明书院 P0-A 依赖无环校验（T44）。

扫描 lumin-backend/lumin-{domain,application,infrastructure,ai,bootstrap}/src/main/java
下所有 .java 的 package 与显式 import，按 02 §3.1 允许列表逐边比对 + DFS 环检测；
支持 --inject 注入非法边做负向对照（只改内存图，不改源码、不落盘）。

能力边界：只能验证已落地源码的显式 import 方向，不能替代运行时装配、
反射/字符串类名/注解处理器生成的隐式依赖、异步消息耦合与 CI 最终门禁。
输出一律标注「结构校验 PASS，非接口联调/功能验收」。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOTS = [
    ROOT / "lumin-backend" / "lumin-domain" / "src" / "main" / "java",
    ROOT / "lumin-backend" / "lumin-application" / "src" / "main" / "java",
    ROOT / "lumin-backend" / "lumin-infrastructure" / "src" / "main" / "java",
    ROOT / "lumin-backend" / "lumin-ai" / "src" / "main" / "java",
    ROOT / "lumin-backend" / "lumin-bootstrap" / "src" / "main" / "java",
]

PACKAGE_RE = re.compile(r"^[ \t]*package\s+(com\.leyon\.lumin\.[A-Za-z0-9_.]+)\s*;", re.MULTILINE)
IMPORT_RE = re.compile(r"^[ \t]*import\s+(com\.leyon\.lumin\.[A-Za-z0-9_.]+)[ \t]*;", re.MULTILINE)

# 02 §3.1「核心模块及唯一写入者」允许的同步依赖目标
ALLOWED_EDGES: dict[str, set[str]] = {
    "identity": {"foundation"},
    "catalog": {"foundation"},
    "authoring": {"catalog", "identity", "foundation"},
    "moderation": {"foundation"},
    "publication": {"authoring", "catalog", "identity", "foundation"},
    "governance": {"catalog", "identity", "foundation"},
    "configuration": {"identity", "foundation"},
    "engagement": {"publication", "identity", "foundation"},
    "audit": {"foundation"},
    "billing_reserved": {"foundation"},
    "ai": {"foundation"},
    "foundation": set(),
}
BUSINESS_MODULES = set(ALLOWED_EDGES) - {"foundation"}
FINAL_LINE = "结构校验 PASS，非接口联调/功能验收"


def module_of(package: str) -> str:
    """取 com.leyon.lumin.{module} 的第 4 段；foundation.values 归入 foundation。"""
    parts = package.split(".")
    return parts[3] if len(parts) >= 4 else ""


def scan_sources() -> tuple[dict[str, set[str]], list[str]]:
    """返回 模块有向图 与 扫描到的源码文件相对路径列表。"""
    graph: dict[str, set[str]] = {name: set() for name in ALLOWED_EDGES}
    files: list[str] = []
    for source_root in SOURCE_ROOTS:
        if not source_root.is_dir():
            continue
        for source in sorted(source_root.rglob("*.java")):
            text = source.read_text(encoding="utf-8")
            package_match = PACKAGE_RE.search(text)
            if not package_match:
                continue
            source_module = module_of(package_match.group(1))
            if source_module not in graph:
                graph[source_module] = set()
            files.append(str(source.relative_to(ROOT)))
            for imported in IMPORT_RE.findall(text):
                target_module = module_of(imported)
                if not target_module or target_module == source_module:
                    continue
                # 记录跨模块 Repository 直接访问（02 §3.2 显式禁止）
                if "repository" in imported.split("."):
                    graph.setdefault(source_module, set()).add(target_module + ".repository")
                else:
                    graph.setdefault(source_module, set()).add(target_module)
    return graph, files


def forbidden_edges(graph: dict[str, set[str]]) -> list[str]:
    """逐边比对允许列表，返回全部非法边描述。"""
    problems: list[str] = []
    for source, targets in sorted(graph.items()):
        for target in sorted(targets):
            base = target.split(".")[0]
            if target.endswith(".repository") and base != source:
                problems.append("%s -> %s（跨模块直接 Repository 访问，02 §3.2 禁止）"
                                % (source, target))
                continue
            if source == "foundation" and base in BUSINESS_MODULES:
                problems.append("%s -> %s（foundation 反向绑定业务域，02 §3.1 禁止）"
                                % (source, target))
                continue
            allowed = ALLOWED_EDGES.get(source, set())
            if base not in allowed:
                problems.append("%s -> %s（不在 02 §3.1 允许列表内）" % (source, target))
    return problems


def find_cycles(graph: dict[str, set[str]]) -> list[list[str]]:
    """DFS 求模块级有向图中的所有环（返回去重后的环路径）。"""
    cycles: list[list[str]] = []
    seen: set[tuple[str, ...]] = set()
    state: dict[str, int] = {}
    stack: list[str] = []

    def visit(node: str) -> None:
        state[node] = 1
        stack.append(node)
        for neighbour in sorted(graph.get(node, set())):
            base = neighbour.split(".")[0]
            if base not in graph:
                continue
            if state.get(base, 0) == 0:
                visit(base)
            elif state.get(base) == 1:
                start = stack.index(base)
                cycle = stack[start:] + [base]
                key = tuple(cycle)
                if key not in seen:
                    seen.add(key)
                    cycles.append(cycle)
        stack.pop()
        state[node] = 2

    for module in sorted(graph):
        if state.get(module, 0) == 0:
            visit(module)
    return cycles


def main() -> int:
    parser = argparse.ArgumentParser(description="P0-A 依赖无环校验（T44）")
    parser.add_argument(
        "--inject", action="append", default=[], metavar="A->B",
        help="向内存图注入一条边做负向对照，可重复；例如 foundation->identity",
    )
    args = parser.parse_args()

    graph, files = scan_sources()
    passed: list[str] = []
    errors: list[str] = []

    if not files:
        errors.append("未扫描到任何 Java 源文件：" + str(SOURCE_ROOT))
    else:
        passed.append("扫描 Java 源文件 %d 个" % len(files))

    for injection in args.inject:
        if "->" not in injection:
            errors.append("注入格式错误（应为 A->B）：" + injection)
            continue
        source, target = injection.split("->", 1)
        source, target = source.strip(), target.strip()
        if not source or not target:
            errors.append("注入格式错误（空模块名）：" + injection)
            continue
        graph.setdefault(source, set()).add(target)
        passed.append("已注入内存边（不改源码）：%s -> %s" % (source, target))

    real_edges = [
        "%s -> %s" % (source, target)
        for source, targets in sorted(graph.items())
        for target in sorted(targets)
    ]
    if real_edges:
        passed.append("跨模块显式 import 边：%s" % "; ".join(real_edges))
    else:
        passed.append("跨模块显式 import 边：0（骨架阶段允许）")

    illegal = forbidden_edges(graph)
    if illegal:
        errors.extend("非法边：" + item for item in illegal)
    else:
        passed.append("允许列表逐边比对：0 非法边")

    cycles = find_cycles(graph)
    if cycles:
        for cycle in cycles:
            errors.append("检测到环：" + " -> ".join(cycle))
    else:
        passed.append("DFS 环检测：0 环")

    for item in passed:
        print("[PASS] " + item)
    for item in errors:
        print("[FAIL] " + item)
    print("-" * 72)
    if errors:
        print("结构校验 FAIL：%d 项不通过；非接口联调/功能验收。" % len(errors))
        return 1
    print(FINAL_LINE)
    print("说明：本脚本仅验证已落地源码的显式 import 方向（A.7.3 能力边界），"
          "不替代运行时装配、反射隐式依赖、异步消息耦合与 CI 最终门禁。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
