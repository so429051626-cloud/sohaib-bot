// Render each SVG in out/ to a 2x PNG with headless Chromium.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

(async () => {
  const OUT = path.join(__dirname, 'out');
  const only = process.argv.slice(2);
  const browser = await chromium.launch();
  for (const f of fs.readdirSync(OUT).filter(f => f.endsWith('.svg'))) {
    if (only.length && !only.some(o => f.includes(o))) continue;
    const svg = fs.readFileSync(path.join(OUT, f), 'utf8');
    const [, w, h] = svg.match(/width="([\d.]+)" height="([\d.]+)"/);
    const page = await browser.newPage({ viewport: { width: Math.ceil(+w), height: Math.ceil(+h) }, deviceScaleFactor: 2 });
    await page.goto('file://' + path.join(OUT, f));
    await page.evaluate(() => document.fonts && document.fonts.ready);
    await page.waitForTimeout(300);
    const transparent = f.includes('mark');
    await page.screenshot({ path: path.join(OUT, f.replace('.svg', '.png')), omitBackground: transparent, clip: { x: 0, y: 0, width: +w, height: +h } });
    await page.close();
    console.log('rendered', f);
  }
  await browser.close();
})();
