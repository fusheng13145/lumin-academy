#!/usr/bin/env bash
# 最小可复现本地检查（UGC-034 一阶段）：不连数据库、不写秘密、不启动服务。
# 覆盖：后端 test-compile + 依赖无环 + 契约结构校验 + 前端 lint + build。
# 用法：bash scripts/verify-local.sh（仓库根目录执行）
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== [1/4] 后端 test-compile =="
(cd lumin-backend && mvn -q test-compile)
echo "OK"

echo "== [2/4] 依赖无环（显式 import 边） =="
python libs/tools/check_dependency_cycles.py
echo "OK"

echo "== [3/4] 契约结构校验 =="
python libs/tools/validate_contract.py
echo "OK"

echo "== [4/4] 前端 lint + build =="
(cd lumin-frontend && npm run lint && npm run build)
echo "OK"

echo "== 全部通过 =="
