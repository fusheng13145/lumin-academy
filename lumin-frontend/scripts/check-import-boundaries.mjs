#!/usr/bin/env node
/**
 * 前端依赖边界检查。
 * 这是轻量架构护栏，不引入 dependency-cruiser：
 * - features 是纵向切片，禁止跨 feature 私有文件；
 * - 共享层不得反向依赖 features / mobile / app；
 * - mobile 壳不得依赖 app 组合层；
 * - app 是唯一允许组合所有层的地方。
 */
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(process.cwd(), 'src');
const sharedLayers = new Set(['components', 'lib', 'mocks', 'services', 'styles', 'types']);
const codeExtensions = new Set(['.ts', '.tsx']);

function walk(dir) {
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  return entries.flatMap((entry) => {
    const absolute = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      return walk(absolute);
    }
    return codeExtensions.has(path.extname(entry.name)) ? [absolute] : [];
  });
}

function relative(file) {
  return path.relative(root, file).split(path.sep).join('/');
}

function layerOf(rel) {
  const first = rel.split('/')[0];
  return first === 'main.tsx' ? 'entry' : first;
}

function resolveTarget(file, specifier) {
  if (!specifier.startsWith('.')) {
    return null;
  }
  const absolute = path.resolve(path.dirname(file), specifier);
  const rel = path.relative(root, absolute).split(path.sep).join('/');
  if (rel.startsWith('..')) {
    return null;
  }
  return rel;
}

function readImports(file) {
  const text = fs.readFileSync(file, 'utf8');
  const patterns = [
    /import\s+(?:type\s+)?(?:[^'"]+?\s+from\s+)?['"]([^'"]+)['"]/g,
    /export\s+(?:type\s+)?(?:[^'"]+?\s+from\s+)?['"]([^'"]+)['"]/g,
  ];
  const imports = new Set();
  for (const pattern of patterns) {
    for (const match of text.matchAll(pattern)) {
      imports.add(match[1]);
    }
  }
  return [...imports];
}

const violations = [];
const files = walk(root);
for (const file of files) {
  const from = relative(file);
  const fromLayer = layerOf(from);
  for (const specifier of readImports(file)) {
    const target = resolveTarget(file, specifier);
    if (!target) {
      continue;
    }
    const targetLayer = layerOf(target);

    if (fromLayer === 'features') {
      const fromFeature = from.split('/')[1];
      if (targetLayer === 'features' && target.split('/')[1] !== fromFeature) {
        violations.push(`${from} 跨 feature 依赖 ${target}（请通过 feature 公开出口或下沉共享层）`);
      }
      if (targetLayer === 'app' || targetLayer === 'mobile') {
        violations.push(`${from} 反向依赖 ${target}（features 不得依赖展示壳/组合层）`);
      }
    } else if (sharedLayers.has(fromLayer)) {
      if (targetLayer === 'features' || targetLayer === 'mobile' || targetLayer === 'app') {
        violations.push(`${from} 反向依赖 ${target}（共享层只能被上层引用）`);
      }
    } else if (fromLayer === 'mobile') {
      if (targetLayer === 'app') {
        violations.push(`${from} 依赖 ${target}（mobile 壳不应依赖 app 组合层）`);
      }
    }
  }
}

if (violations.length > 0) {
  console.error(`前端依赖边界检查失败，共 ${violations.length} 处：`);
  for (const violation of violations) {
    console.error(`- ${violation}`);
  }
  process.exit(1);
}

console.log(`前端依赖边界检查通过：${files.length} 个源码文件。`);
