import { chromium } from 'playwright-core';
import path from 'node:path';
import fs from 'node:fs';

const root = path.resolve(import.meta.dirname, '..');
const outDir = path.resolve(root, '..', 'docs', 'assets', 'screenshots', 'flow');
fs.mkdirSync(outDir, { recursive: true });

const browser = await chromium.launch({
  executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  headless: true,
});
const page = await browser.newPage({
  viewport: { width: 390, height: 844 },
  deviceScaleFactor: 2,
  isMobile: true,
  hasTouch: true,
});
const errors = [];
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(`[console] ${message.text()}`);
});
page.on('pageerror', (error) => errors.push(`[pageerror] ${error.message}`));

async function shot(name) {
  await page.screenshot({ path: path.join(outDir, `${name}.png`) });
}

async function clickButton(name, options = {}) {
  const button = page.getByRole('button', { name, exact: options.exact ?? false }).first();
  await button.waitFor({ state: 'visible', timeout: 5000 });
  await button.click();
  await page.waitForTimeout(options.wait ?? 700);
}

async function home() {
  await page.goto('http://127.0.0.1:5174/', { waitUntil: 'networkidle' });
  await page.waitForTimeout(500);
}

await home();
await shot('01-home');

await page.locator('.m-shelf__item').first().click();
await page.waitForTimeout(700);
await shot('02-book');

await clickButton('开始阅读');
await shot('03-reader');

await clickButton('目录');
await shot('04-reader-catalog');
await page.getByRole('button', { name: '关闭' }).click();
await page.waitForTimeout(300);

await home();
await clickButton('创作');
await shot('05-studio');

await clickButton('继续写');
await shot('06-editor');

await page.getByLabel('章节正文').fill(
  '封冻前最后一班船靠岸时，码头上的灯还没有亮。\n\n林砚把新抄好的潮位表夹进值班簿，翻到最后一页，才发现纸背有一道很浅的铅笔痕。'
);
await page.waitForTimeout(1300);
await shot('07-editor-autosaved');

await clickButton('AI 协助');
await shot('08-ai');

await page.getByRole('tab', { name: 'AI 辅助' }).click();
await page.waitForTimeout(400);
await clickButton('开始生成候选');
await page.waitForTimeout(4200);
await shot('09-ai-workflow');

await clickButton('采纳并写入', { wait: 900 });
await shot('10-editor-after-insert');

await clickButton('返回');
await page.waitForTimeout(300);
await page.getByRole('tab', { name: '纯 AI' }).click();
await page.waitForTimeout(400);
await shot('11-ai-autonomous');
await clickButton('开始生成候选', { wait: 500 });
await page.waitForTimeout(5200);
await shot('12-ai-autonomous-result');

await clickButton('我的');
await shot('13-me');
await clickButton('评论管理');
await shot('14-comments');

await clickButton('返回');
await page.waitForTimeout(300);
await clickButton('书城');
await page.getByLabel('搜索作品').click();
await page.waitForTimeout(500);
await shot('15-search');

await clickButton('返回');
await page.waitForTimeout(300);
await clickButton('AI');
await page.waitForTimeout(300);
await page.locator('.m-card').filter({ hasText: '素材库与灵感' }).first().click();
await page.waitForTimeout(600);
await shot('16-materials');
await clickButton('返回');
await page.waitForTimeout(300);
await page.locator('.m-card').filter({ hasText: 'AI 插图' }).first().click();
await page.waitForTimeout(600);
await shot('17-illustration');

const metrics = await page.evaluate(() => ({
  innerWidth: window.innerWidth,
  scrollWidth: document.documentElement.scrollWidth,
}));
console.log(JSON.stringify({ metrics, errors }, null, 2));
await browser.close();
