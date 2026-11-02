# MADIX — Identity

Founder & Systems Developer at MADIX. One monogram (an unbroken **M** that runs into a woven **X**,
all strokes cut flat at a single 68.2° angle) and a matching custom wordmark, shown in seven styles.

| # | Style | File |
|---|-------|------|
| 01 | Noir & Gold — أسود وذهبي فاخر | `svg/01-noir-gold.svg` |
| 02 | Aurora — ألوانك الأصلية | `svg/02-aurora.svg` |
| 03 | Pearl — لؤلؤي أفقي | `svg/03-pearl.svg` |
| 04 | Emerald Royale — زمردي ملكي | `svg/04-emerald-royale.svg` |
| 05 | Platinum — بلاتيني مع انعكاس | `svg/05-platinum.svg` |
| 06 | Blueprint — مخطط هندسي | `svg/06-blueprint.svg` |
| 07 | Prism Glass — زجاج منشوري | `svg/07-prism-glass.svg` |

Extras: `svg/extra-avatar-seal.svg` (round profile seal), `svg/extra-mark-*.svg` (monogram on transparent).

- `svg/` — vector masters, fonts embedded, open in any browser or editor.
- `preview/` — JPG previews (3000×2000) and the overview sheet; transparent PNG marks.
- `source/` — generator. `node source/build.js && node source/render.js` writes SVG + 2× PNG to `source/out/`
  (needs Node and Playwright with Chromium). Fonts: Inter, Cormorant Garamond, JetBrains Mono (SIL OFL).
