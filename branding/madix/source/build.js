// MADIX identity — seven styles, generated as self-contained SVGs (fonts embedded).
const fs = require('fs');
const path = require('path');

const OUT = path.join(__dirname, 'out');
fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });
const font = f => fs.readFileSync(path.join(__dirname, 'fonts', f)).toString('base64');
const r = n => +(+n).toFixed(2);
const stops = (list, op = 1) => list.map(([o, c]) => `<stop offset="${o}" stop-color="${c}"${op !== 1 ? ` stop-opacity="${op}"` : ''}/>`).join('');
const esc = s => s.replace(/&/g, '&amp;');

// =============================================================== geometry
function diag(x1, y1, x2, y2, w, extra = 0) {
  const s = Math.sin(Math.atan2(Math.abs(y2 - y1), Math.abs(x2 - x1)));
  const h = (w + extra * 2) / s;
  return `M${r(x1 - h / 2)} ${r(y1)}L${r(x1 + h / 2)} ${r(y1)}L${r(x2 + h / 2)} ${r(y2)}L${r(x2 - h / 2)} ${r(y2)}Z`;
}
const rect = (x, y, w, h) => `M${r(x)} ${r(y)}h${r(w)}v${r(h)}h${r(-w)}Z`;

const CFG = { slope: 300 / 120, H: 300, w: 36, valley: 196, gap: 9 };
const ANGLE = (Math.atan(CFG.slope) * 180) / Math.PI;

// One unbroken M that runs straight into the X, with a crossing stroke woven over it.
function monogram({ slope, H, w, valley, gap }) {
  const h = w / Math.sin(Math.atan(slope));
  const xv = h / 2 + valley / slope, xa = xv + valley / slope, xr = xa + H / slope;
  return {
    width: xr + h / 2, height: H, w, valley,
    base: [rect(0, 0, w, H), diag(h / 2, 0, xv, valley, w), diag(xv, valley, xa, 0, w), diag(xa, 0, xr, H, w)],
    cross: diag(xr, 0, xa, H, w),
    cut: diag(xr + (H * 0.2) / slope, -H * 0.2, xa - (H * 0.2) / slope, H * 1.2, w, gap),
    pts: { xv, xa, xr, h, cx: (xa + xr) / 2, cy: H / 2 },
  };
}
const M = monogram(CFG);

// Wordmark from the same rules: flat cuts, one angle, woven X.
function wordmark({ slope, H = 100, w = 12.5, tracking = 0.34 }) {
  const h = w / Math.sin(Math.atan(slope));
  const g = [];
  let x = 0;
  const T = H * tracking;
  { const xv = h / 2 + H / slope, xa = xv + H / slope, width = xa + h / 2;
    g.push({ x, width, paths: [rect(0, 0, w, H), diag(h / 2, 0, xv, H, w), diag(xv, H, xa, 0, w), rect(width - w, 0, w, H)] });
    x += width + T * 0.94; }
  { const dx = H / slope, cx = dx + h / 2, width = 2 * dx + h;
    g.push({ x, width, paths: [diag(cx, 0, cx - dx, H, w), diag(cx, 0, cx + dx, H, w)] });
    x += width + T * 0.92; }
  { const R = H / 2, width = H * 0.74, sx = width - R, ri = R - w;
    g.push({ x, width, evenodd: true, paths: [`M0 0H${r(sx)}A${R} ${R} 0 0 1 ${r(sx)} ${H}H0Z M${r(w)} ${r(w)}V${r(H - w)}H${r(sx)}A${r(ri)} ${r(ri)} 0 0 0 ${r(sx)} ${r(w)}Z`] });
    x += width + T * 1.02; }
  { g.push({ x, width: w, paths: [rect(0, 0, w, H)] }); x += w + T * 1.02; }
  { const dx = (H / slope) * 1.18, width = dx + h;
    g.push({ x, width, isX: true, paths: [diag(h / 2, 0, h / 2 + dx, H, w)], cross: diag(h / 2 + dx, 0, h / 2, H, w),
      cut: diag(h / 2 + dx * 1.2, -H * 0.2, h / 2 - dx * 0.2, H * 1.2, w, w * 0.3) });
    x += width; }
  return { glyphs: g, width: x, height: H };
}
const WM = wordmark({ slope: CFG.slope });

// =============================================================== palettes
const P = {
  aurora: [['0', '#FF2D75'], ['0.38', '#E23BD8'], ['0.68', '#8B3DFF'], ['1', '#3C5BFF']],
  auroraX: [['0', '#7B3BFF'], ['0.55', '#3E7BFF'], ['1', '#3FE0FF']],
  gold: [['0', '#6E4514'], ['0.14', '#B9852F'], ['0.29', '#F3D58E'], ['0.37', '#FFF7DC'], ['0.46', '#E7BC62'], ['0.6', '#9C6820'], ['0.76', '#DDB463'], ['0.88', '#FFEDBE'], ['1', '#BB8636']],
  goldX: [['0', '#7D5019'], ['0.3', '#C99A45'], ['0.58', '#FFF4D2'], ['0.7', '#F0CF85'], ['1', '#A9752B']],
  goldText: [['0', '#B98A3E'], ['0.22', '#F1D597'], ['0.4', '#FFF3D1'], ['0.58', '#D9AE5C'], ['0.8', '#F6DFA4'], ['1', '#B07D33']],
  chrome: [['0', '#4B5059'], ['0.18', '#AEB5BF'], ['0.33', '#F4F7FA'], ['0.4', '#FFFFFF'], ['0.5', '#C3C9D1'], ['0.64', '#6C727B'], ['0.8', '#D9DEE4'], ['0.9', '#F7F9FB'], ['1', '#8D949E']],
  chromeX: [['0', '#555B64'], ['0.35', '#B9C0C9'], ['0.6', '#FFFFFF'], ['0.75', '#D6DBE1'], ['1', '#7E858F']],
  chromeText: [['0', '#9AA1AB'], ['0.3', '#F2F5F8'], ['0.5', '#FFFFFF'], ['0.7', '#BFC6CE'], ['1', '#E6EAEE']],
  white: [['0', '#FFFFFF'], ['1', '#EFE9FF']],
  whiteX: [['0', '#E9F4FF'], ['1', '#FFFFFF']],
};

// =============================================================== shared pieces
function fontFaces(extra = []) {
  const list = [
    ['MX Inter', 'normal', 500, 'inter-500.woff2'],
    ['MX Inter', 'normal', 600, 'inter-600.woff2'],
    ['MX Serif', 'italic', 500, 'cormorant-italic-500.woff2'],
    ...extra,
  ];
  return list.map(([fam, st, wt, f]) => `@font-face{font-family:'${fam}';font-style:${st};font-weight:${wt};src:url(data:font/woff2;base64,${font(f)}) format('woff2');}`).join('\n');
}
const MONO = ['MX Mono', 'normal', 500, 'jbmono-500.woff2'];
const SANS = `'MX Inter',Inter,'Helvetica Neue',Arial,sans-serif`;
const SERIF = `'MX Serif','Cormorant Garamond',Georgia,serif`;
const MONOF = `'MX Mono','JetBrains Mono',Menlo,monospace`;

// t: { base, cross, sheen, sweep, glow, glowColorless }
function monogramDefs(id, t) {
  const m = M, { xa, xr } = m.pts;
  const box = `x="-100" y="-100" width="${r(m.width + 200)}" height="${m.height + 200}"`;
  return `
    <linearGradient id="${id}-g" gradientUnits="userSpaceOnUse" x1="0" y1="${m.height}" x2="${r(m.width)}" y2="0">${stops(t.base)}</linearGradient>
    <linearGradient id="${id}-gx" gradientUnits="userSpaceOnUse" x1="${r(xa)}" y1="${m.height}" x2="${r(xr)}" y2="0">${stops(t.cross)}</linearGradient>
    <linearGradient id="${id}-sheen" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0" y2="${m.height}">
      <stop offset="0" stop-color="#fff" stop-opacity="${t.sheen ?? 0}"/><stop offset="0.42" stop-color="#fff" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity="${t.shade ?? 0.12}"/>
    </linearGradient>
    <linearGradient id="${id}-sweep" gradientUnits="userSpaceOnUse" x1="${r(m.width * 0.1)}" y1="${m.height * 0.05}" x2="${r(m.width * 0.75)}" y2="${m.height * 0.95}">
      <stop offset="0.30" stop-color="#fff" stop-opacity="0"/><stop offset="0.36" stop-color="#fff" stop-opacity="${t.sweep ?? 0}"/>
      <stop offset="0.40" stop-color="#fff" stop-opacity="0"/><stop offset="0.44" stop-color="#fff" stop-opacity="${r((t.sweep ?? 0) * 0.5)}"/><stop offset="0.47" stop-color="#fff" stop-opacity="0"/>
    </linearGradient>
    <mask id="${id}-weave" maskUnits="userSpaceOnUse" ${box}><rect ${box} fill="#fff"/><path d="${m.cut}" fill="#000"/></mask>
    <mask id="${id}-solid" maskUnits="userSpaceOnUse" ${box}>
      <g fill="#fff">${m.base.map(d => `<path d="${d}"/>`).join('')}</g><path d="${m.cut}" fill="#000"/><path d="${m.cross}" fill="#fff"/>
    </mask>
    <filter id="${id}-blur" x="-60%" y="-60%" width="220%" height="220%"><feGaussianBlur stdDeviation="${t.glowBlur ?? 26}"/></filter>
    <filter id="${id}-shadow" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="5"/></filter>`;
}
function monogramBody(id, t, { glow = true } = {}) {
  const m = M;
  const shapes = `<g mask="url(#${id}-weave)" fill="url(#${id}-g)">${m.base.map(d => `<path d="${d}"/>`).join('')}</g><path d="${m.cross}" fill="url(#${id}-gx)"/>`;
  const big = `x="-10" y="-10" width="${r(m.width + 20)}" height="${m.height + 20}"`;
  return `
    ${glow && t.glow ? `<g opacity="${t.glow}" filter="url(#${id}-blur)">${shapes}</g>` : ''}
    ${shapes}
    <g mask="url(#${id}-weave)"><g mask="url(#${id}-solid)"><path d="${m.cross}" transform="translate(5 4)" fill="#000" opacity="${t.contact ?? 0.28}" filter="url(#${id}-shadow)"/></g></g>
    <path d="${m.cross}" fill="url(#${id}-gx)"/>
    ${t.sheen || t.shade ? `<rect ${big} fill="url(#${id}-sheen)" mask="url(#${id}-solid)"/>` : ''}
    ${t.sweep ? `<rect ${big} fill="url(#${id}-sweep)" mask="url(#${id}-solid)"/>` : ''}`;
}
function placeMark(id, t, x, y, height, opts) {
  const k = height / M.height;
  return { k, w: M.width * k, svg: `<g transform="translate(${r(x)} ${r(y)}) scale(${r(k * 10000) / 10000})">${monogramBody(id, t, opts)}</g>` };
}

// wd: { ink (solid) | inkGrad (stops), wx, wx2 }
function wordmarkSvg(id, wd, x, y, height) {
  const wm = WM, k = height / wm.height;
  const xg = wm.glyphs.find(g => g.isX);
  const letters = wm.glyphs.filter(g => !g.isX).map(g => `<g transform="translate(${r(g.x)} 0)"${g.evenodd ? ' fill-rule="evenodd"' : ''}>${g.paths.map(d => `<path d="${d}"/>`).join('')}</g>`).join('');
  const bb = `x="-20" y="-20" width="${r(wm.width + 40)}" height="${wm.height + 40}"`;
  const xb = `x="-50" y="-50" width="${r(xg.width + 100)}" height="${wm.height + 100}"`;
  return {
    width: wm.width * k,
    svg: `<g transform="translate(${r(x)} ${r(y)}) scale(${r(k * 10000) / 10000})">
    <defs>
      <linearGradient id="${id}-ink" gradientUnits="userSpaceOnUse" x1="0" y1="${wm.height}" x2="${r(wm.width)}" y2="0">${wd.inkGrad ? stops(wd.inkGrad) : `<stop offset="0" stop-color="${wd.ink}"/>`}</linearGradient>
      <linearGradient id="${id}-wx" gradientUnits="userSpaceOnUse" x1="0" y1="${wm.height}" x2="${r(xg.width)}" y2="0">${stops(wd.wx)}</linearGradient>
      <linearGradient id="${id}-wx2" gradientUnits="userSpaceOnUse" x1="0" y1="${wm.height}" x2="${r(xg.width)}" y2="0">${stops(wd.wx2)}</linearGradient>
      <mask id="${id}-letters" maskUnits="userSpaceOnUse" ${bb}><g fill="#fff">${letters}</g></mask>
      <mask id="${id}-xm" maskUnits="userSpaceOnUse" ${xb}><rect ${xb} fill="#fff"/><path d="${xg.cut}" fill="#000"/></mask>
    </defs>
    <rect ${bb} fill="url(#${id}-ink)" mask="url(#${id}-letters)"/>
    <g transform="translate(${r(xg.x)} 0)"><path d="${xg.paths[0]}" fill="url(#${id}-wx)" mask="url(#${id}-xm)"/><path d="${xg.cross}" fill="url(#${id}-wx2)"/></g>
  </g>`,
  };
}
const wmWidth = h => WM.width * (h / WM.height);

function sparkle(cx, cy, s, fill, op = 1) {
  const k = s * 0.16;
  return `<path d="M${r(cx)} ${r(cy - s)}C${r(cx + k * 0.3)} ${r(cy - k)} ${r(cx + k)} ${r(cy - k * 0.3)} ${r(cx + s)} ${r(cy)}C${r(cx + k)} ${r(cy + k * 0.3)} ${r(cx + k * 0.3)} ${r(cy + k)} ${r(cx)} ${r(cy + s)}C${r(cx - k * 0.3)} ${r(cy + k)} ${r(cx - k)} ${r(cy + k * 0.3)} ${r(cx - s)} ${r(cy)}C${r(cx - k)} ${r(cy - k * 0.3)} ${r(cx - k * 0.3)} ${r(cy - k)} ${r(cx)} ${r(cy - s)}Z" fill="${fill}" opacity="${op}"/>`;
}
const diamond = (x, y, s, fill, op = 1) => `<path d="M${r(x)} ${r(y - s)}L${r(x + s)} ${r(y)}L${r(x)} ${r(y + s)}L${r(x - s)} ${r(y)}Z" fill="${fill}" opacity="${op}"/>`;

const GRAIN = `<filter id="grain" x="0" y="0" width="100%" height="100%">
      <feTurbulence type="fractalNoise" baseFrequency="0.8" numOctaves="3" seed="7" stitchTiles="stitch"/>
      <feColorMatrix type="matrix" values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 -1.6 1.1"/>
    </filter>`;
function radialBg(id, cx, cy, rad, cols) {
  return `<radialGradient id="${id}" gradientUnits="userSpaceOnUse" cx="${r(cx)}" cy="${r(cy)}" r="${r(rad)}"><stop offset="0" stop-color="${cols[0]}"/><stop offset="0.55" stop-color="${cols[1]}"/><stop offset="1" stop-color="${cols[2]}"/></radialGradient>`;
}
function dotGrid(W, H, cx, cy, color, op) {
  return {
    defs: `<pattern id="dots" width="26" height="26" patternUnits="userSpaceOnUse"><circle cx="13" cy="13" r="1.05" fill="${color}" fill-opacity="${op}"/></pattern>
    <radialGradient id="fade" gradientUnits="userSpaceOnUse" cx="${r(cx)}" cy="${r(cy)}" r="${Math.max(W, H) * 0.55}"><stop offset="0" stop-color="#fff"/><stop offset="0.6" stop-color="#fff" stop-opacity="0.45"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>
    <mask id="dotmask" maskUnits="userSpaceOnUse" x="0" y="0" width="${W}" height="${H}"><rect width="${W}" height="${H}" fill="url(#fade)"/></mask>`,
    body: `<rect width="${W}" height="${H}" fill="url(#dots)" mask="url(#dotmask)"/>`,
  };
}
function bloom(id, cx, cy, rx, ry, list, op, blur = 95) {
  return {
    defs: `<linearGradient id="${id}" x1="0" y1="1" x2="1" y2="0">${stops(list)}</linearGradient><filter id="${id}-f" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="${blur}"/></filter>`,
    body: `<ellipse cx="${r(cx)}" cy="${r(cy)}" rx="${rx}" ry="${ry}" fill="url(#${id})" opacity="${op}" filter="url(#${id}-f)"/>`,
  };
}
function hairlines(cx, y, half, len, color, op, deco = false) {
  return `
    <linearGradient id="hl${Math.round(y)}" x1="0" x2="1"><stop offset="0" stop-color="${color}" stop-opacity="0"/><stop offset="1" stop-color="${color}" stop-opacity="${op}"/></linearGradient>
    <linearGradient id="hr${Math.round(y)}" x1="0" x2="1"><stop offset="0" stop-color="${color}" stop-opacity="${op}"/><stop offset="1" stop-color="${color}" stop-opacity="0"/></linearGradient>
    <rect x="${r(cx - half - len)}" y="${r(y - 0.6)}" width="${len}" height="1.2" fill="url(#hl${Math.round(y)})"/>
    <rect x="${r(cx + half)}" y="${r(y - 0.6)}" width="${len}" height="1.2" fill="url(#hr${Math.round(y)})"/>
    ${deco ? diamond(cx - half, y, 2.6, color, 0.9) + diamond(cx + half, y, 2.6, color, 0.9) : ''}`;
}
function tickFrame(W, H, color, a = 34, tick = 22, op = 0.34, rectOp = 0.07) {
  const t = [[a, a, 1, 1], [W - a, a, -1, 1], [a, H - a, 1, -1], [W - a, H - a, -1, -1]]
    .map(([x, y, sx, sy]) => `<path d="M${x} ${y + sy * tick}V${y}H${x + sx * tick}" fill="none" stroke="${color}" stroke-opacity="${op}" stroke-width="1.4"/>`).join('');
  return `<rect x="${a}" y="${a}" width="${W - 2 * a}" height="${H - 2 * a}" fill="none" stroke="${color}" stroke-opacity="${rectOp}"/>${t}`;
}
function doubleFrame(W, H, c, a = 30, b = 40) {
  return `
    <rect x="${a}" y="${a}" width="${W - 2 * a}" height="${H - 2 * a}" fill="none" stroke="${c}" stroke-opacity="0.42" stroke-width="1.2"/>
    <rect x="${b}" y="${b}" width="${W - 2 * b}" height="${H - 2 * b}" fill="none" stroke="${c}" stroke-opacity="0.16"/>
    ${[[a, a], [W - a, a], [a, H - a], [W - a, H - a]].map(([x, y]) => diamond(x, y, 5, c, 0.85)).join('')}
    ${diamond(W / 2, a, 4, c, 0.75)}${diamond(W / 2, H - a, 4, c, 0.75)}`;
}
function tagline({ x, y, size, color, ampFill, anchor = 'middle', ls = 0.46, length, family = SANS, weight = 500, text1 = 'FOUNDER', text2 = 'SYSTEMS DEVELOPER', ampFamily = SERIF }) {
  const L = size * ls;
  const ax = anchor === 'middle' ? x + L / 2 : x;
  return `<text x="${r(ax)}" y="${r(y)}" text-anchor="${anchor}" font-family="${family}" font-weight="${weight}" font-size="${size}" letter-spacing="${r(L)}" word-spacing="${r(-L * 0.75)}" fill="${color}"${length ? ` textLength="${r(length)}" lengthAdjust="spacing"` : ''}>${text1}<tspan font-family="${ampFamily}" font-style="${ampFamily === SERIF ? 'italic' : 'normal'}" font-size="${r(ampFamily === SERIF ? size * 1.75 : size)}" fill="${ampFill}" dx="${r(L * (ampFamily === SERIF ? 0.6 : 1.4))}" dy="${ampFamily === SERIF ? 2 : 0}">&amp;</tspan><tspan dx="${r(L * (ampFamily === SERIF ? 0.6 : 1.4))}" dy="${ampFamily === SERIF ? -2 : 0}">${text2}</tspan></text>`;
}
const atText = (x, y, size, fill, anchor = 'middle') => `<text x="${r(x)}" y="${r(y + size * 0.16)}" text-anchor="${anchor}" font-family="${SERIF}" font-style="italic" font-weight="500" font-size="${size}" fill="${fill}">at</text>`;

function doc(W, H, title, defs, body, extraFonts = []) {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}">
  <title>${esc(title)}</title>
  <defs><style>${fontFaces(extraFonts)}</style>${defs}</defs>
${body}
</svg>`;
}

const W = 1500, H = 1000, CX = W / 2;

// Vertical centred lockup used by styles 1, 2 and (scaled) 7.
function stackLayout(top, markH, wmH, gap1 = 104, gap2 = 58, gap3 = 54) {
  const markY = top, tagY = markY + markH + gap1, atY = tagY + gap2, wmY = atY + gap3;
  return { markY, tagY, atY, wmY, bottom: wmY + wmH };
}
const stackTop = (markH, wmH, gap1 = 104, gap2 = 58, gap3 = 54, area = H, offset = 0) => (area - (markH + gap1 + gap2 + gap3 + wmH)) / 2 - 6 + offset;

// =============================================================== 1 · NOIR & GOLD
function styleNoirGold() {
  const markH = 268, wmH = 84;
  const L = stackLayout(stackTop(markH, wmH), markH, wmH);
  const glowY = L.markY + markH * 0.55;
  const t = { base: P.gold, cross: P.goldX, sheen: 0.12, sweep: 0.22, glow: 0.3 };
  const dg = dotGrid(W, H, CX, glowY, '#E8CF8F', 0.045);
  const bl = bloom('bloom', CX, glowY, 330, 210, [['0', '#8A5A1C'], ['0.5', '#E2B458'], ['1', '#8A5A1C']], 0.2);
  const mk = placeMark('mk', t, CX - (M.width * markH / M.height) / 2, L.markY, markH);
  const wm = wordmarkSvg('wm', { inkGrad: P.goldText, wx: P.gold, wx2: P.goldX }, CX - wmWidth(wmH) / 2, L.wmY, wmH);
  return doc(W, H, 'MADIX — Noir & Gold',
    `${radialBg('bg', CX, glowY, W * 0.78, ['#1C1811', '#0A0907', '#020202'])}${dg.defs}${bl.defs}${GRAIN}${monogramDefs('mk', t)}<linearGradient id="amp" x1="0" y1="1" x2="1" y2="0">${stops(P.goldText)}</linearGradient>`,
    `<rect width="${W}" height="${H}" fill="url(#bg)"/>${dg.body}${bl.body}<rect width="${W}" height="${H}" filter="url(#grain)" opacity="0.07"/>
    ${doubleFrame(W, H, '#D9B56A')}
    ${mk.svg}
    ${sparkle(CX - mk.w / 2 + M.pts.xr * mk.k + 46, L.markY - 6, 15, '#FFF4D6', 0.95)}${sparkle(CX - mk.w / 2 + M.pts.xr * mk.k + 72, L.markY + 26, 6, '#FFF4D6', 0.7)}
    ${tagline({ x: CX, y: L.tagY, size: 21, color: '#D8CCAF', ampFill: 'url(#amp)' })}
    ${hairlines(CX, L.atY, 46, 210, '#E3C27A', 0.7, true)}
    ${atText(CX, L.atY, 34, '#E9CB86')}
    ${wm.svg}`);
}

// =============================================================== 2 · AURORA
function styleAurora() {
  const markH = 268, wmH = 84;
  const L = stackLayout(stackTop(markH, wmH), markH, wmH);
  const glowY = L.markY + markH * 0.55;
  const t = { base: P.aurora, cross: P.auroraX, sheen: 0.24, sweep: 0.16, glow: 0.62 };
  const dg = dotGrid(W, H, CX, glowY, '#FFFFFF', 0.075);
  const bl = bloom('bloom', CX, glowY, 330, 210, P.aurora, 0.42);
  const mk = placeMark('mk', t, CX - (M.width * markH / M.height) / 2, L.markY, markH);
  const wm = wordmarkSvg('wm', { ink: '#F5F3FF', wx: P.aurora, wx2: P.auroraX }, CX - wmWidth(wmH) / 2, L.wmY, wmH);
  return doc(W, H, 'MADIX — Aurora',
    `${radialBg('bg', CX, glowY, W * 0.78, ['#16122F', '#08070F', '#030307'])}${dg.defs}${bl.defs}${GRAIN}${monogramDefs('mk', t)}<linearGradient id="amp" x1="0" y1="1" x2="1" y2="0">${stops(P.aurora)}</linearGradient>`,
    `<rect width="${W}" height="${H}" fill="url(#bg)"/>${dg.body}${bl.body}<rect width="${W}" height="${H}" filter="url(#grain)" opacity="0.05"/>
    ${tickFrame(W, H, '#FFFFFF')}
    ${mk.svg}
    ${sparkle(CX - mk.w / 2 + M.pts.xr * mk.k + 46, L.markY - 6, 15, '#E9FBFF', 0.95)}${sparkle(CX - mk.w / 2 + M.pts.xr * mk.k + 72, L.markY + 26, 6, '#E9FBFF', 0.7)}
    ${tagline({ x: CX, y: L.tagY, size: 21, color: '#B9B4D9', ampFill: 'url(#amp)' })}
    ${hairlines(CX, L.atY, 46, 210, '#FFFFFF', 0.55)}
    ${atText(CX, L.atY, 34, '#F5F3FF')}
    ${wm.svg}`);
}

// =============================================================== 3 · PEARL (horizontal)
function stylePearl() {
  const markH = 290, wmH = 92;
  const wmW = wmWidth(wmH);
  const gap = 76;
  const totalW = M.width * markH / M.height + gap * 2 + wmW;
  const x0 = (W - totalW) / 2, y0 = (H - markH) / 2;
  const markW = M.width * markH / M.height;
  const divX = x0 + markW + gap;
  const tx = divX + gap;
  const blockH = 182, by = y0 + (markH - blockH) / 2;
  const t = { base: P.aurora, cross: P.auroraX, sheen: 0.3, sweep: 0.14, glow: 0.32 };
  const bl = bloom('bloom', x0 + markW / 2, y0 + markH / 2, 240, 220, P.aurora, 0.24, 85);
  const mk = placeMark('mk', t, x0, y0, markH);
  const wm = wordmarkSvg('wm', { ink: '#100C26', wx: P.aurora, wx2: P.auroraX }, tx, by + blockH - wmH, wmH);
  const tagY = by + 14;
  const atY = by + 57;
  return doc(W, H, 'MADIX — Pearl',
    `${radialBg('bg', W * 0.42, H * 0.45, W * 0.8, ['#FFFFFF', '#F8F6F2', '#ECE8E1'])}${bl.defs}${GRAIN}${monogramDefs('mk', t)}<linearGradient id="amp" x1="0" y1="1" x2="1" y2="0">${stops(P.aurora)}</linearGradient>
     <linearGradient id="div" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#100C26" stop-opacity="0"/><stop offset="0.5" stop-color="#100C26" stop-opacity="0.28"/><stop offset="1" stop-color="#100C26" stop-opacity="0"/></linearGradient>
     <linearGradient id="atl" x1="0" x2="1"><stop offset="0" stop-color="#100C26" stop-opacity="0.45"/><stop offset="1" stop-color="#100C26" stop-opacity="0"/></linearGradient>`,
    `<rect width="${W}" height="${H}" fill="url(#bg)"/>${bl.body}<rect width="${W}" height="${H}" filter="url(#grain)" opacity="0.035"/>
    ${tickFrame(W, H, '#100C26', 34, 22, 0.3, 0.06)}
    ${mk.svg}
    <rect x="${r(divX)}" y="${r(y0 - 10)}" width="1.2" height="${markH + 20}" fill="url(#div)"/>
    ${diamond(divX + 0.6, y0 + markH / 2, 3.2, '#8B3DFF', 0.9)}
    ${tagline({ x: tx, y: tagY, size: 15.5, color: '#4A4568', ampFill: 'url(#amp)', anchor: 'start', length: wmW, ls: 0.36 })}
    ${atText(tx, atY, 30, '#100C26', 'start')}
    <rect x="${r(tx + 34)}" y="${r(atY - 0.6)}" width="${r(wmW - 34)}" height="1.2" fill="url(#atl)"/>
    ${wm.svg}
    ${sparkle(x0 + M.pts.xr * mk.k + 40, y0 - 4, 13, '#3FB8FF', 0.95)}`);
}

// =============================================================== 4 · EMERALD ROYALE (seal)
function sealRing({ cx, cy, R, c, grad, phrase, fontSize = 25 }) {
  const R1 = R, R2 = R - 8, Rt = R - 50, R3 = R - 90, R4 = R - 100;
  const circ = 2 * Math.PI * Rt;
  const ticks = Array.from({ length: 120 }, (_, i) => {
    const a = (i / 120) * Math.PI * 2, major = i % 10 === 0;
    const ri = R4 - (major ? 16 : 8), ro = R4 - 2;
    return `<line x1="${r(cx + Math.cos(a) * ri)}" y1="${r(cy + Math.sin(a) * ri)}" x2="${r(cx + Math.cos(a) * ro)}" y2="${r(cy + Math.sin(a) * ro)}" stroke="${c}" stroke-opacity="${major ? 0.8 : 0.34}" stroke-width="${major ? 2 : 1.1}"/>`;
  }).join('');
  return {
    defs: `<path id="ringpath" d="M${cx} ${cy + Rt}A${Rt} ${Rt} 0 1 1 ${cx} ${cy - Rt}A${Rt} ${Rt} 0 1 1 ${cx} ${cy + Rt}"/>
      <linearGradient id="ringg" gradientUnits="userSpaceOnUse" x1="${cx - R1}" y1="${cy + R1}" x2="${cx + R1}" y2="${cy - R1}">${stops(grad)}</linearGradient>`,
    body: `
      <circle cx="${cx}" cy="${cy}" r="${R1}" fill="none" stroke="url(#ringg)" stroke-width="2.6"/>
      <circle cx="${cx}" cy="${cy}" r="${R2}" fill="none" stroke="${c}" stroke-opacity="0.32"/>
      <circle cx="${cx}" cy="${cy}" r="${R3}" fill="none" stroke="url(#ringg)" stroke-width="1.6"/>
      ${ticks}
      <text font-family="${SANS}" font-weight="600" font-size="${fontSize}" fill="url(#ringg)" dy="${r(fontSize * 0.36)}"><textPath href="#ringpath" textLength="${r(circ - 2)}" lengthAdjust="spacing">${esc(phrase + phrase)}</textPath></text>`,
  };
}
function styleEmerald() {
  const cy = H / 2, R = 400;
  const t = { base: P.gold, cross: P.goldX, sheen: 0.12, sweep: 0.22, glow: 0.28, contact: 0.35 };
  const ring = sealRing({ cx: CX, cy, R, c: '#D9B56A', grad: P.goldText, phrase: 'FOUNDER & SYSTEMS DEVELOPER  •  AT MADIX  •  ' });
  const markH = 300, markW = M.width * markH / M.height;
  const mk = placeMark('mk', t, CX - markW / 2 + 2, cy - markH / 2, markH);
  const bl = bloom('bloom', CX, cy, 280, 260, [['0', '#1E9E74'], ['1', '#0C5E45']], 0.45, 110);
  const side = (dir) => {
    const x1 = CX + dir * (R + 18), x2 = CX + dir * (R + 250);
    return `<line x1="${x1}" y1="${cy}" x2="${x2}" y2="${cy}" stroke="url(#side${dir > 0 ? 'R' : 'L'})" stroke-width="1.2"/>
      ${diamond(x1 + dir * 2, cy, 5, '#E3C27A', 0.95)}${diamond(x1 + dir * 26, cy, 2.6, '#E3C27A', 0.8)}`;
  };
  return doc(W, H, 'MADIX — Emerald Royale',
    `${radialBg('bg', CX, cy, W * 0.72, ['#0F4A39', '#06261D', '#010D09'])}${bl.defs}${GRAIN}${monogramDefs('mk', t)}${ring.defs}
     <radialGradient id="disc" cx="0.5" cy="0.45" r="0.6"><stop offset="0" stop-color="#145A45"/><stop offset="1" stop-color="#062A20"/></radialGradient>
     <linearGradient id="sideR" x1="0" x2="1"><stop offset="0" stop-color="#E3C27A" stop-opacity="0.8"/><stop offset="1" stop-color="#E3C27A" stop-opacity="0"/></linearGradient>
     <linearGradient id="sideL" x1="1" x2="0"><stop offset="0" stop-color="#E3C27A" stop-opacity="0.8"/><stop offset="1" stop-color="#E3C27A" stop-opacity="0"/></linearGradient>
     <filter id="discsh" x="-20%" y="-20%" width="140%" height="140%"><feGaussianBlur stdDeviation="22"/></filter>`,
    `<rect width="${W}" height="${H}" fill="url(#bg)"/>${bl.body}
    <circle cx="${CX}" cy="${cy + 14}" r="${R}" fill="#000" opacity="0.45" filter="url(#discsh)"/>
    <circle cx="${CX}" cy="${cy}" r="${R}" fill="url(#disc)"/>
    <rect width="${W}" height="${H}" filter="url(#grain)" opacity="0.07"/>
    ${doubleFrame(W, H, '#D9B56A')}
    ${side(1)}${side(-1)}
    ${ring.body}
    ${mk.svg}
    ${sparkle(CX - markW / 2 + 2 + M.pts.xr * mk.k + 40, cy - markH / 2 - 4, 15, '#FFF4D6', 0.95)}`);
}

// =============================================================== 5 · PLATINUM MONOLITH (reflection)
function stylePlatinum() {
  const markH = 300, markW = M.width * markH / M.height;
  const markY = 168, floorY = markY + markH;
  const t = { base: P.chrome, cross: P.chromeX, sheen: 0.1, shade: 0.18, sweep: 0.3, glow: 0.18, glowBlur: 30 };
  const mx = CX - markW / 2;
  const mk = placeMark('mk', t, mx, markY, markH);
  const refl = placeMark('rf', t, 0, 0, markH, { glow: false });
  const wmH = 78, wmY = floorY + 168;
  const wm = wordmarkSvg('wm', { inkGrad: P.chromeText, wx: P.chrome, wx2: P.chromeX }, CX - wmWidth(wmH) / 2, wmY, wmH);
  const tagY = wmY + wmH + 74;
  return doc(W, H, 'MADIX — Platinum',
    `${radialBg('bg', CX, floorY - 60, W * 0.75, ['#2B2E35', '#121317', '#040405'])}${GRAIN}${monogramDefs('mk', t)}${monogramDefs('rf', t)}
     <linearGradient id="reflfade" gradientUnits="userSpaceOnUse" x1="0" y1="${floorY}" x2="0" y2="${floorY + markH * 0.5}"><stop offset="0" stop-color="#fff" stop-opacity="0.34"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></linearGradient>
     <mask id="reflmask" maskUnits="userSpaceOnUse" x="0" y="${floorY}" width="${W}" height="${markH}"><rect x="0" y="${floorY}" width="${W}" height="${markH}" fill="url(#reflfade)"/></mask>
     <linearGradient id="floor" x1="0" x2="1"><stop offset="0" stop-color="#fff" stop-opacity="0"/><stop offset="0.5" stop-color="#fff" stop-opacity="0.5"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></linearGradient>
     <radialGradient id="spot" cx="0.5" cy="0" r="1"><stop offset="0" stop-color="#fff" stop-opacity="0.15"/><stop offset="0.7" stop-color="#fff" stop-opacity="0.04"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>
     <filter id="spotblur" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="46"/></filter>
     <radialGradient id="pool" cx="0.5" cy="0.5" r="0.5"><stop offset="0" stop-color="#C9D4E3" stop-opacity="0.22"/><stop offset="1" stop-color="#C9D4E3" stop-opacity="0"/></radialGradient>
     <linearGradient id="amp" x1="0" y1="1" x2="1" y2="0">${stops(P.chromeText)}</linearGradient>`,
    `<rect width="${W}" height="${H}" fill="url(#bg)"/>
    <path d="M${CX - 110} -40L${CX + 110} -40L${CX + 470} ${floorY}L${CX - 470} ${floorY}Z" fill="url(#spot)" filter="url(#spotblur)"/>
    <ellipse cx="${CX}" cy="${floorY}" rx="430" ry="46" fill="url(#pool)"/>
    <rect x="${CX - 560}" y="${r(floorY - 0.6)}" width="1120" height="1.2" fill="url(#floor)"/>
    <g mask="url(#reflmask)">
      <g transform="translate(${r(mx)} ${r(floorY + markH + 6)}) scale(${r(refl.k * 10000) / 10000} ${-r(refl.k * 10000) / 10000})">${monogramBody('rf', t, { glow: false })}</g></g>
    <rect width="${W}" height="${H}" filter="url(#grain)" opacity="0.06"/>
    ${tickFrame(W, H, '#FFFFFF', 34, 22, 0.3, 0.06)}
    ${mk.svg}
    ${sparkle(mx + M.pts.xr * mk.k + 46, markY - 6, 15, '#FFFFFF', 0.95)}${sparkle(mx + M.pts.xr * mk.k + 72, markY + 26, 6, '#FFFFFF', 0.7)}
    ${wm.svg}
    ${hairlines(CX, wmY + wmH + 36, 10, 180, '#FFFFFF', 0.45)}${diamond(CX, wmY + wmH + 36, 3, '#E6EAEE', 0.9)}
    ${tagline({ x: CX, y: tagY, size: 19, color: '#AEB4BE', ampFill: 'url(#amp)' })}`);
}

// =============================================================== 6 · BLUEPRINT
function styleBlueprint() {
  const markH = 300, k = markH / M.height, markW = M.width * k;
  const mx = CX - markW / 2 - 10, my = 150;
  const p = (x, y) => [r(mx + x * k), r(my + y * k)];
  const C = '#8FD8FF', C2 = '#5BB8F0';
  const { xv, xa, xr, h } = M.pts;
  const sw = 1.3;
  const outline = (d) => `<path d="${d}" fill="none" stroke="${C}" stroke-width="${r(sw / k)}" stroke-linejoin="miter"/>`;
  const ext = 0.32;
  const lineThrough = (x1, y1, x2, y2) => {
    const dx = x2 - x1, dy = y2 - y1;
    return `<line x1="${p(x1 - dx * ext, y1 - dy * ext).join('" y1="')}" x2="${p(x2 + dx * ext, y2 + dy * ext).join('" y2="')}" stroke="${C2}" stroke-opacity="0.45" stroke-width="1" stroke-dasharray="6 6"/>`;
  };
  const node = (x, y, rr = 4.5) => `<circle cx="${p(x, y)[0]}" cy="${p(x, y)[1]}" r="${rr}" fill="#0B1F3A" stroke="${C}" stroke-width="1.3"/>`;
  const label = (x, y, s, anchor = 'start', op = 0.75, size = 12) => `<text x="${r(x)}" y="${r(y)}" font-family="${MONOF}" font-size="${size}" letter-spacing="1.5" fill="${C}" fill-opacity="${op}" text-anchor="${anchor}">${esc(s)}</text>`;
  const [bx0] = p(0, 0), [bx1] = p(M.width, 0);
  const [, yTop] = p(0, 0), [, yBase] = p(0, M.height), [, yVal] = p(0, M.valley);
  // angle arc at the foot of the crossing stroke
  const [fx, fy] = p(xa, M.height);
  const ar = 54, a0 = 0, a1 = -ANGLE * Math.PI / 180;
  const arc = `<path d="M${r(fx + ar)} ${r(fy)}A${ar} ${ar} 0 0 0 ${r(fx + ar * Math.cos(a1))} ${r(fy + ar * Math.sin(a1))}" fill="none" stroke="#FFD27A" stroke-width="1.3"/>`;
  // stroke-width dimension above the stem
  const [sx0] = p(0, 0), [sx1] = p(M.w, 0);
  const dimY = yTop - 26;
  const wmH = 76, wmY = 650;
  const wm = wordmarkSvg('wm', { ink: '#EAF6FF', wx: [['0', '#8FD8FF'], ['1', '#FFFFFF']], wx2: [['0', '#FFD27A'], ['1', '#FFE9B8']] }, CX - wmWidth(wmH) / 2, wmY, wmH);
  const tagY = 588;
  const bodyGrid = `<rect width="${W}" height="${H}" fill="url(#gridMinor)"/><rect width="${W}" height="${H}" fill="url(#gridMajor)"/>`;
  const box = { x: W - 34 - 330, y: H - 34 - 112, w: 330, h: 112 };
  const rows = [['PROJECT', 'MADIX IDENTITY'], ['ELEMENT', 'MX MONOGRAM'], ['GEOMETRY', `${ANGLE.toFixed(1)}°  ·  STROKE 0.12H`], ['SHEET', '01 / 01']];
  return doc(W, H, 'MADIX — Blueprint',
    `${radialBg('bg', CX, H * 0.4, W * 0.8, ['#0F2A52', '#0A1C38', '#050E1E'])}
     <pattern id="gridMinor" width="25" height="25" patternUnits="userSpaceOnUse"><path d="M25 0H0V25" fill="none" stroke="#9FD6FF" stroke-opacity="0.06"/></pattern>
     <pattern id="gridMajor" width="125" height="125" patternUnits="userSpaceOnUse"><path d="M125 0H0V125" fill="none" stroke="#9FD6FF" stroke-opacity="0.12"/></pattern>
     ${monogramDefs('mk', { base: [['0', C], ['1', C]], cross: [['0', C], ['1', C]] })}
     <linearGradient id="fillg" gradientUnits="userSpaceOnUse" x1="0" y1="${M.height}" x2="${r(M.width)}" y2="0"><stop offset="0" stop-color="#5BB8F0" stop-opacity="0.16"/><stop offset="1" stop-color="#9FE3FF" stop-opacity="0.26"/></linearGradient>
     <linearGradient id="fillx" gradientUnits="userSpaceOnUse" x1="${r(xa)}" y1="${M.height}" x2="${r(xr)}" y2="0"><stop offset="0" stop-color="#FFD27A" stop-opacity="0.18"/><stop offset="1" stop-color="#FFD27A" stop-opacity="0.34"/></linearGradient>
     <marker id="arw" viewBox="0 0 10 10" refX="5" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0L10 5L0 10" fill="none" stroke="${C}" stroke-width="1.4"/></marker>`,
    `<rect width="${W}" height="${H}" fill="url(#bg)"/>${bodyGrid}
    <rect x="34" y="34" width="${W - 68}" height="${H - 68}" fill="none" stroke="${C}" stroke-opacity="0.4"/>
    <rect x="42" y="42" width="${W - 84}" height="${H - 84}" fill="none" stroke="${C}" stroke-opacity="0.14"/>

    <!-- guides -->
    ${[[yTop, 'CAP'], [yVal, 'VALLEY · 0.65H'], [yBase, 'BASELINE']].map(([y, s]) => `<line x1="140" y1="${y}" x2="${W - 140}" y2="${y}" stroke="${C2}" stroke-opacity="0.32" stroke-dasharray="2 6"/>${label(W - 140, y - 8, s, 'end', 0.6, 11)}`).join('')}
    ${lineThrough(xa, 0, xr, M.height)}${lineThrough(xr, 0, xa, M.height)}${lineThrough(h / 2, 0, xv, M.valley)}
    <circle cx="${p(M.pts.cx, M.pts.cy)[0]}" cy="${p(M.pts.cx, M.pts.cy)[1]}" r="${r(78)}" fill="none" stroke="${C2}" stroke-opacity="0.35" stroke-dasharray="3 5"/>

    <!-- the mark, as a construction drawing -->
    <g transform="translate(${r(mx)} ${r(my)}) scale(${r(k * 10000) / 10000})">
      <g mask="url(#mk-weave)" fill="url(#fillg)">${M.base.map(d => `<path d="${d}"/>`).join('')}</g>
      <path d="${M.cross}" fill="url(#fillx)"/>
      <g mask="url(#mk-weave)">${M.base.map(outline).join('')}</g>
      <path d="${M.cross}" fill="none" stroke="#FFD27A" stroke-width="${r(1.6 / k)}"/>
    </g>
    ${node(0, M.height)}${node(0, 0)}${node(xv, M.valley)}${node(xa, 0)}${node(xr, M.height)}${node(xr, 0, 5)}${node(xa, M.height, 5)}
    <circle cx="${p(M.pts.cx, M.pts.cy)[0]}" cy="${p(M.pts.cx, M.pts.cy)[1]}" r="6" fill="#FFD27A"/>

    <!-- dimensions -->
    <line x1="${sx0}" y1="${dimY}" x2="${sx1}" y2="${dimY}" stroke="${C}" stroke-width="1" marker-start="url(#arw)" marker-end="url(#arw)"/>
    <line x1="${sx0}" y1="${dimY - 8}" x2="${sx0}" y2="${yTop - 4}" stroke="${C}" stroke-opacity="0.6"/><line x1="${sx1}" y1="${dimY - 8}" x2="${sx1}" y2="${yTop - 4}" stroke="${C}" stroke-opacity="0.6"/>
    ${label((sx0 + sx1) / 2, dimY - 10, 'w', 'middle', 0.9, 13)}
    <line x1="${bx0 - 40}" y1="${yTop}" x2="${bx0 - 40}" y2="${yBase}" stroke="${C}" stroke-width="1" marker-start="url(#arw)" marker-end="url(#arw)"/>
    ${label(bx0 - 52, (yTop + yBase) / 2 + 4, 'H', 'end', 0.9, 13)}
    ${arc}${label(fx + ar + 10, fy - 12, ANGLE.toFixed(1) + '°', 'start', 1, 13).replace(`fill="${C}"`, 'fill="#FFD27A"')}
    ${label(bx1 + 30, yTop + 4, 'MX—01', 'start', 0.55, 11)}

    <!-- type -->
    ${tagline({ x: CX, y: tagY, size: 17, color: '#BFE6FF', ampFill: '#FFD27A', family: MONOF, ampFamily: MONOF, ls: 0.32 })}
    ${hairlines(CX, wmY - 32, 26, 200, C, 0.5)}
    <text x="${CX}" y="${r(wmY - 32 + 6)}" text-anchor="middle" font-family="${MONOF}" font-size="18" fill="#FFD27A">@</text>
    ${wm.svg}

    <!-- title block -->
    <g font-family="${MONOF}" font-size="11" letter-spacing="1.4">
      <rect x="${box.x}" y="${box.y}" width="${box.w}" height="${box.h}" fill="#071428" fill-opacity="0.6" stroke="${C}" stroke-opacity="0.5"/>
      ${rows.map(([a, b], i) => `<text x="${box.x + 14}" y="${box.y + 24 + i * 25}" fill="${C}" fill-opacity="0.5">${a}</text><text x="${box.x + 112}" y="${box.y + 24 + i * 25}" fill="#EAF6FF" fill-opacity="0.9">${esc(b)}</text>${i < rows.length - 1 ? `<line x1="${box.x}" y1="${box.y + 32 + i * 25}" x2="${box.x + box.w}" y2="${box.y + 32 + i * 25}" stroke="${C}" stroke-opacity="0.2"/>` : ''}`).join('')}
      <line x1="${box.x + 100}" y1="${box.y}" x2="${box.x + 100}" y2="${box.y + box.h}" stroke="${C}" stroke-opacity="0.2"/>
    </g>
    ${label(58, H - 50, 'REV. 01  ·  SCALE 1:1  ·  ALL STROKES CUT FLAT', 'start', 0.45, 11)}`, [MONO]);
}

// =============================================================== 7 · PRISM GLASS
function styleGlass() {
  const card = { x: 300, y: 130, w: 900, h: 740, rx: 40 };
  const blobs = [
    [330, 260, 260, '#FF2D75'], [1180, 210, 280, '#3C5BFF'], [760, 880, 300, '#8B3DFF'],
    [1320, 760, 220, '#3FE0FF'], [170, 820, 220, '#E23BD8'], [760, 420, 200, '#5B3DFF'],
  ];
  const blobSvg = (f) => blobs.map(([x, y, rr, c]) => `<circle cx="${x}" cy="${y}" r="${rr}" fill="${c}"/>`).join('') ;
  const markH = 236, wmH = 72;
  const gap1 = 92, gap2 = 50, gap3 = 46;
  const top = card.y + (card.h - (markH + gap1 + gap2 + gap3 + wmH)) / 2 - 4;
  const L = stackLayout(top, markH, wmH, gap1, gap2, gap3);
  const t = { base: P.white, cross: P.whiteX, sheen: 0, shade: 0.1, sweep: 0, glow: 0.45, glowBlur: 22, contact: 0.22 };
  const mk = placeMark('mk', t, CX - (M.width * markH / M.height) / 2, L.markY, markH);
  const wm = wordmarkSvg('wm', { ink: '#FFFFFF', wx: [['0', '#FFFFFF'], ['1', '#FFFFFF']], wx2: [['0', '#FFB3D1'], ['1', '#BFF3FF']] }, CX - wmWidth(wmH) / 2, L.wmY, wmH);
  return doc(W, H, 'MADIX — Prism Glass',
    `<linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#140F33"/><stop offset="1" stop-color="#07061A"/></linearGradient>
     <filter id="b1" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="90"/></filter>
     <filter id="b2" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="140"/></filter>
     <clipPath id="cardclip"><rect x="${card.x}" y="${card.y}" width="${card.w}" height="${card.h}" rx="${card.rx}"/></clipPath>
     <linearGradient id="edge" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#fff" stop-opacity="0.7"/><stop offset="0.45" stop-color="#fff" stop-opacity="0.12"/><stop offset="1" stop-color="#fff" stop-opacity="0.4"/></linearGradient>
     <linearGradient id="gloss" x1="0" y1="0" x2="0.6" y2="1"><stop offset="0" stop-color="#fff" stop-opacity="0.22"/><stop offset="0.4" stop-color="#fff" stop-opacity="0.06"/><stop offset="1" stop-color="#fff" stop-opacity="0.02"/></linearGradient>
     <filter id="cardsh" x="-20%" y="-20%" width="140%" height="150%"><feGaussianBlur stdDeviation="30"/></filter>
     ${GRAIN}${monogramDefs('mk', t)}`,
    `<rect width="${W}" height="${H}" fill="url(#bg)"/>
    <g filter="url(#b1)" opacity="0.78">${blobSvg()}</g>
    <rect x="${card.x + 20}" y="${card.y + 40}" width="${card.w - 40}" height="${card.h}" rx="${card.rx}" fill="#05041A" opacity="0.55" filter="url(#cardsh)"/>
    <g clip-path="url(#cardclip)">
      <rect width="${W}" height="${H}" fill="url(#bg)"/>
      <g filter="url(#b2)" opacity="0.95">${blobSvg()}</g>
      <rect x="${card.x}" y="${card.y}" width="${card.w}" height="${card.h}" fill="#FFFFFF" opacity="0.07"/>
      <rect x="${card.x}" y="${card.y}" width="${card.w}" height="${card.h}" fill="url(#gloss)"/>
      <rect width="${W}" height="${H}" filter="url(#grain)" opacity="0.08"/>
    </g>
    <rect x="${card.x + 0.6}" y="${card.y + 0.6}" width="${card.w - 1.2}" height="${card.h - 1.2}" rx="${card.rx}" fill="none" stroke="url(#edge)" stroke-width="1.4"/>
    ${mk.svg}
    ${sparkle(CX - mk.w / 2 + M.pts.xr * mk.k + 40, L.markY - 4, 13, '#FFFFFF', 0.95)}${sparkle(CX - mk.w / 2 + M.pts.xr * mk.k + 62, L.markY + 22, 5, '#FFFFFF', 0.75)}
    ${tagline({ x: CX, y: L.tagY, size: 18, color: '#FFFFFF', ampFill: '#FFC2DA' }).replace('fill="#FFFFFF"', 'fill="#FFFFFF" fill-opacity="0.86"')}
    ${hairlines(CX, L.atY, 40, 170, '#FFFFFF', 0.6)}
    ${atText(CX, L.atY, 30, '#FFFFFF')}
    ${wm.svg}`);
}

// =============================================================== extras: avatar seal + transparent marks
function avatarSeal() {
  const S = 1024, c = S / 2;
  const t = { base: P.gold, cross: P.goldX, sheen: 0.12, sweep: 0.22, glow: 0.28, contact: 0.35 };
  const ring = sealRing({ cx: c, cy: c, R: 470, c: '#D9B56A', grad: P.goldText, phrase: 'FOUNDER & SYSTEMS DEVELOPER  •  AT MADIX  •  ', fontSize: 28 });
  const markH = 330, markW = M.width * markH / M.height;
  const mk = placeMark('mk', t, c - markW / 2 + 2, c - markH / 2, markH);
  return doc(S, S, 'MADIX — Seal',
    `${radialBg('bg', c, c, S * 0.75, ['#0F4A39', '#06261D', '#010D09'])}${GRAIN}${monogramDefs('mk', t)}${ring.defs}`,
    `<rect width="${S}" height="${S}" fill="url(#bg)"/><rect width="${S}" height="${S}" filter="url(#grain)" opacity="0.07"/>${ring.body}${mk.svg}`);
}
function markOnly(name, t) {
  const pad = 20;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${-pad} ${-pad} ${r(M.width + pad * 2)} ${M.height + pad * 2}" width="${r((M.width + pad * 2) * 3)}" height="${(M.height + pad * 2) * 3}">
  <title>MADIX monogram — ${name}</title><defs>${monogramDefs('mk', t)}</defs>${monogramBody('mk', t, { glow: false })}
</svg>`;
}

const files = {
  '01-noir-gold.svg': styleNoirGold(),
  '02-aurora.svg': styleAurora(),
  '03-pearl.svg': stylePearl(),
  '04-emerald-royale.svg': styleEmerald(),
  '05-platinum.svg': stylePlatinum(),
  '06-blueprint.svg': styleBlueprint(),
  '07-prism-glass.svg': styleGlass(),
  'extra-avatar-seal.svg': avatarSeal(),
  'extra-mark-aurora.svg': markOnly('aurora', { base: P.aurora, cross: P.auroraX, sheen: 0.2, sweep: 0.12 }),
  'extra-mark-gold.svg': markOnly('gold', { base: P.gold, cross: P.goldX, sheen: 0.12, sweep: 0.2 }),
};
for (const [f, s] of Object.entries(files)) fs.writeFileSync(path.join(OUT, f), s);
console.log('wrote', Object.keys(files).length, 'svgs');
