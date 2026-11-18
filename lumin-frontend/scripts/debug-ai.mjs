import { chromium } from 'playwright-core';

const browser = await chromium.launch({ executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', headless: true });
const page = await browser.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
await page.goto('http://127.0.0.1:5174/', { waitUntil: 'networkidle' });
await page.waitForTimeout(400);

async function click(name) {
  const button = page.getByRole('button', { name, exact: false }).first();
  await button.click();
  await page.waitForTimeout(600);
}

await click('AI');
console.log('header1', await page.locator('.m-topbar__title').allTextContents());
const ill = page.locator('.m-card').filter({ hasText: 'AI 插图' }).first();
console.log('ill count', await ill.count());
await ill.click();
await page.waitForTimeout(800);
console.log('header2', await page.locator('.m-topbar__title').allTextContents());
await page.screenshot({ path: '..\\docs\\assets\\screenshots\\flow\\17-illustration.png' });

await click('返回');
console.log('header3', await page.locator('.m-topbar__title').allTextContents());
console.log('buttons', await page.getByRole('button').allTextContents());
const mats = page.locator('.m-card').filter({ hasText: '素材库与灵感' }).first();
await mats.click();
await page.waitForTimeout(600);
console.log('header4', await page.locator('.m-topbar__title').allTextContents());
await click('返回');
console.log('header5', await page.locator('.m-topbar__title').allTextContents());
console.log('buttonsAfterBack', await page.getByRole('button').allTextContents());

await browser.close();
