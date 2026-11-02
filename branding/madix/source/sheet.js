const { chromium } = require('playwright');
const path = require('path');
const OUT = path.join(__dirname, 'out');
const tiles = [
  ['01-noir-gold', '01', 'Noir & Gold', 'أسود وذهبي فاخر'],
  ['02-aurora', '02', 'Aurora', 'أورورا — ألوانك الأصلية'],
  ['03-pearl', '03', 'Pearl', 'لؤلؤي أفقي'],
  ['04-emerald-royale', '04', 'Emerald Royale', 'زمردي ملكي — ختم'],
  ['05-platinum', '05', 'Platinum', 'بلاتيني مع انعكاس'],
  ['06-blueprint', '06', 'Blueprint', 'مخطط هندسي'],
  ['07-prism-glass', '07', 'Prism Glass', 'زجاج منشوري'],
];
const tile = ([f, n, en, ar], hero) => `<figure class="${hero ? 'hero' : ''}"><img src="file://${OUT}/${f}.png"><figcaption><b>${n}</b><span class="en">${en}</span><span class="ar">${ar}</span></figcaption></figure>`;
const html = `<!doctype html><html><head><meta charset="utf-8"><style>
@font-face{font-family:I;src:url(file://${__dirname}/fonts/inter-500.woff2)}
body{margin:0;background:#0B0A10;color:#EDEAF7;font-family:I,sans-serif;width:2000px;padding:56px 60px 70px;box-sizing:border-box}
header{display:flex;justify-content:space-between;align-items:baseline;margin-bottom:34px;border-bottom:1px solid #ffffff1f;padding-bottom:22px}
h1{font-size:30px;letter-spacing:.42em;margin:0;font-weight:500}
header p{margin:0;color:#9C97B8;font-size:17px;letter-spacing:.2em}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:34px 30px}
figure{margin:0}
figure.hero{grid-column:1/-1}
img{width:100%;display:block;border-radius:10px;box-shadow:0 18px 50px #0008}
figcaption{display:flex;gap:14px;align-items:baseline;margin-top:14px;font-size:18px}
figcaption b{color:#E3C27A;font-weight:500;letter-spacing:.1em}
.en{letter-spacing:.18em;text-transform:uppercase}
.ar{margin-left:auto;color:#9C97B8;font-family:'Noto Sans Arabic','DejaVu Sans',sans-serif;direction:rtl}
</style></head><body><header><h1>MADIX</h1><p>IDENTITY · SEVEN STYLES</p></header><div class="grid">${tiles.map((t, i) => tile(t, i === 0)).join('')}</div></body></html>`;
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 2000, height: 1000 }, deviceScaleFactor: 1 });
  require('fs').writeFileSync(path.join(__dirname, 'sheet.html'), html);
  await p.goto('file://' + path.join(__dirname, 'sheet.html'), { waitUntil: 'load' });
  await p.waitForTimeout(500);
  await p.screenshot({ path: path.join(OUT, '00-overview.jpg'), fullPage: true, type: 'jpeg', quality: 90 });
  await b.close();
})();
