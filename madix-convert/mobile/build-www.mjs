// MADIX Convert — تجهيز ملفات التطبيق (www) من نفس واجهة الموقع
// Py | Sohaib
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const www = path.join(here, 'www');
const cfg = JSON.parse(fs.readFileSync(path.join(here, 'app.config.json'), 'utf8'));
const server = String(process.env.MADIX_SERVER_URL || cfg.server || '').trim().replace(/\/+$/, '').replace(/\/index\.php$/, '');
const version = process.env.MADIX_VERSION || ('1.0.' + Math.floor(Date.now() / 1000));

fs.rmSync(www, { recursive: true, force: true });
const copy = (from, to) => { fs.mkdirSync(path.dirname(to), { recursive: true }); fs.copyFileSync(from, to); };

for (const f of ['css/app.css', 'js/boot.js', 'js/core.js', 'js/imaging.js', 'js/analysis.js', 'js/xlsx.js', 'js/pdf.js', 'js/native.js', 'js/app.js',
  'img/icon-512.png', 'img/icon-192.png', 'img/apple-touch-icon.png', 'img/icon.svg']) {
  copy(path.join(root, 'assets', f), path.join(www, 'assets', f));
}
copy(path.join(here, 'node_modules/@capacitor/core/dist/capacitor.js'), path.join(www, 'capacitor.js'));

// الخط العربي محلياً (يشتغل بدون نت)
const fontDir = path.join(here, 'node_modules/@fontsource/ibm-plex-sans-arabic/files');
let css = '/* IBM Plex Sans Arabic — محلي */\n';
for (const w of [300, 400, 500, 600, 700]) {
  for (const [sub, range] of [['arabic', 'U+0600-06FF, U+0750-077F, U+0870-088E, U+0890-0891, U+0897-08E1, U+08E3-08FF, U+200C-200E, U+2010-2011, U+204F, U+2E41, U+FB50-FDFF, U+FE70-FE74, U+FE76-FEFC'], ['latin', 'U+0000-00FF, U+0131, U+0152-0153, U+02BB-02BC, U+02C6, U+02DA, U+02DC, U+0304, U+0308, U+0329, U+2000-206F, U+20AC, U+2122, U+2191, U+2193, U+2212, U+2215, U+FEFF, U+FFFD']]) {
    const name = `ibm-plex-sans-arabic-${sub}-${w}-normal.woff2`;
    copy(path.join(fontDir, name), path.join(www, 'assets/fonts', name));
    css += `@font-face{font-family:"IBM Plex Sans Arabic";font-style:normal;font-display:swap;font-weight:${w};src:url(${name}) format("woff2");unicode-range:${range};}\n`;
  }
}
fs.writeFileSync(path.join(www, 'assets/fonts/fonts.css'), css);

fs.writeFileSync(path.join(www, 'config.js'),
  '/* MADIX Convert — إعدادات التطبيق | Py | Sohaib */\nwindow.MADIX_CONFIG = ' + JSON.stringify({ server, version, appName: cfg.appName || 'MADIX Convert' }) + ';\n');
const html = fs.readFileSync(path.join(here, 'index.template.html'), 'utf8').replaceAll('__V__', version);
fs.writeFileSync(path.join(www, 'index.html'), html);

// صفحة "ما في اتصال" داخل التطبيق
const offline = fs.readFileSync(path.join(here, 'offline.template.html'), 'utf8').replaceAll('__SERVER__', server || '');
fs.writeFileSync(path.join(www, 'offline.html'), offline);

// إعدادات Capacitor: الواجهة كاملة داخل التطبيق، والاتصال بالسيرفر عبر شبكة الجوال الأصلية
const cap = {
  appId: cfg.appId || 'com.madix.convert',
  appName: cfg.appName || 'MADIX Convert',
  webDir: 'www',
  backgroundColor: '#0b1220',
  server: { androidScheme: 'https', cleartext: true, errorPath: 'offline.html' },
  android: { allowMixedContent: true, backgroundColor: '#0b1220' },
  plugins: {
    CapacitorHttp: { enabled: true },
    CapacitorCookies: { enabled: true },
    SplashScreen: { launchShowDuration: 900, launchAutoHide: true, launchFadeOutDuration: 300, backgroundColor: '#0b1220', androidScaleType: 'CENTER_CROP', showSpinner: false },
    SystemBars: { insetsHandling: 'native' }
  }
};
fs.writeFileSync(path.join(here, 'capacitor.config.json'), JSON.stringify(cap, null, 2) + '\n');
console.log('www ready — server:', server || '(بيطلبه التطبيق أول مرة)', '— version:', version);
