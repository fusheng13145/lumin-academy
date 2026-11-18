import { chromium } from 'playwright-core';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const outDir = path.resolve(root, '..', 'docs', 'assets', 'screenshots');
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
await page.goto('http://127.0.0.1:5174/', { waitUntil: 'networkidle' });
await page.waitForTimeout(800);

const metrics = await page.evaluate(() => {
  const rect = (selector) => {
    const el = document.querySelector(selector);
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { x: r.x, y: r.y, width: r.width, height: r.height, right: r.right };
  };
  return {
    innerWidth: window.innerWidth,
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
    bodyScrollWidth: document.body.scrollWidth,
    page: rect('.m-page'),
    hero: rect('.m-hero'),
    heroBody: rect('.m-hero__body'),
    cover: rect('.m-hero__cover'),
    text: rect('.m-hero__text'),
    nav: rect('.m-bottom-nav__inner'),
    shelf: rect('.m-shelf'),
  };
});
console.log(JSON.stringify(metrics, null, 2));

await page.screenshot({ path: path.join(outDir, 'playwright-home.png') });
await browser.close();
