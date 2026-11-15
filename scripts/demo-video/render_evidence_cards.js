// 渲染进展证据视频的静态画面 (1920x1080 PNG): 片头卡 + 章节卡 + 片尾卡.
// 数据源 scripts/demo-video/evidence_video.json; 产物 reports/demo-video/evidence/cards/NN.png.
// 用法: node scripts/demo-video/render_evidence_cards.js
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const DATA = JSON.parse(fs.readFileSync(path.join(__dirname, 'evidence_video.json'), 'utf8'));
const OUT = process.env.EVIDENCE_VIDEO_DIR
  ? path.resolve(process.env.EVIDENCE_VIDEO_DIR)
  : path.resolve(__dirname, '../../reports/demo-video/evidence');
const CARDS = path.join(OUT, 'cards');
const W = 1920;
const H = 1080;

const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

const BASE_CSS = `
* { box-sizing: border-box; margin: 0; padding: 0; }
html, body { width: ${W}px; height: ${H}px; overflow: hidden; }
body { font-family: 'Noto Sans CJK SC', 'Noto Sans SC', 'PingFang SC', 'Microsoft YaHei', sans-serif; color: #f8fafc;
  background: radial-gradient(1200px 700px at 78% 18%, rgba(229,57,53,.20), transparent 60%),
              radial-gradient(900px 600px at 10% 90%, rgba(59,130,246,.14), transparent 60%),
              linear-gradient(160deg, #0b1220 0%, #111a2e 55%, #0b1220 100%); }
.grid { position: absolute; inset: 0; background-image:
  linear-gradient(rgba(148,163,184,.07) 1px, transparent 1px), linear-gradient(90deg, rgba(148,163,184,.07) 1px, transparent 1px);
  background-size: 48px 48px; mask-image: radial-gradient(ellipse at 60% 40%, #000 30%, transparent 75%); }
.brand { position: absolute; left: 96px; top: 72px; display: flex; align-items: center; gap: 14px; font-size: 30px; font-weight: 700; letter-spacing: .02em; }
.mark { width: 44px; height: 44px; border-radius: 11px; display: grid; place-items: center; color: #fff; font-weight: 800; font-size: 24px;
  background: linear-gradient(135deg, #ef5350, #b71c1c); box-shadow: 0 8px 24px rgba(229,57,53,.35); }
.mono { font-family: 'DejaVu Sans Mono', 'Noto Sans Mono', monospace; }
.kicker { position: absolute; right: 96px; top: 84px; font-size: 26px; color: #94a3b8; letter-spacing: .14em; }
.rule { width: 120px; height: 8px; border-radius: 4px; background: linear-gradient(90deg,#ef5350,#f59e0b); margin-bottom: 40px; }
.heading { font-size: 76px; font-weight: 800; line-height: 1.14; letter-spacing: .01em; }
.sub { font-size: 38px; font-weight: 500; color: #cbd5e1; margin-top: 24px; }
.metrics { display: flex; gap: 28px; margin-top: 44px; }
.metric { flex: 1; padding: 26px 30px; border: 1px solid rgba(148,163,184,.30); border-radius: 16px; background: rgba(15,23,42,.55); }
.metric .v { font-size: 46px; font-weight: 800; background: linear-gradient(90deg,#f87171,#fbbf24); -webkit-background-clip: text; background-clip: text; color: transparent; }
.metric .l { font-size: 22px; color: #94a3b8; margin-top: 10px; }
.bullets { margin-top: 40px; display: flex; flex-direction: column; gap: 22px; }
.bullets li { list-style: none; font-size: 31px; color: #e2e8f0; padding-left: 36px; position: relative; line-height: 1.45; }
.bullets li::before { content: ''; position: absolute; left: 0; top: 15px; width: 14px; height: 14px; border-radius: 4px;
  background: linear-gradient(135deg,#ef5350,#f59e0b); }
.meta { position: absolute; left: 96px; top: 330px; right: 120px; }
.chips { display: flex; gap: 18px; margin-top: 56px; font-size: 26px; color: #94a3b8; }
.chips span { padding: 10px 22px; border: 1px solid rgba(148,163,184,.35); border-radius: 999px; background: rgba(15,23,42,.55); }
.footer { position: absolute; right: 96px; bottom: 72px; font-size: 22px; color: #64748b; }
`;

function cardHtml(seg, idx, total) {
  const isTitle = seg.type === 'title';
  const kicker = seg.kicker || (isTitle ? 'PROGRESS EVIDENCE' : `EVIDENCE ${String(idx + 1).padStart(2, '0')} / ${String(total).padStart(2, '0')}`);
  const metrics = (seg.metrics || []).map(m =>
    `<div class="metric"><div class="v">${esc(m.value)}</div><div class="l">${esc(m.label)}</div></div>`).join('');
  const bullets = (seg.bullets || []).length
    ? `<ul class="bullets">${seg.bullets.map(b => `<li>${esc(b)}</li>`).join('')}</ul>` : '';
  const meta = (seg.meta || []).length
    ? `<div class="chips">${seg.meta.map(m => `<span>${esc(m)}</span>`).join('')}</div>` : '';
  const bodyTop = isTitle ? 300 : 250;
  return `<!doctype html><html><head><meta charset="utf-8"><style>${BASE_CSS}</style></head><body>
    <div class="grid"></div>
    <div class="brand"><div class="mark">红</div>红创PMS</div>
    <div class="kicker">${esc(kicker)}</div>
    <div class="meta" style="top:${bodyTop}px">
      <div class="rule"></div>
      <div class="heading">${esc(seg.heading)}</div>
      ${seg.sub ? `<div class="sub">${esc(seg.sub)}</div>` : ''}
      ${metrics ? `<div class="metrics">${metrics}</div>` : ''}
      ${bullets}
      ${meta}
    </div>
    <div class="footer mono">hc-pms · ub-mode batch 45-81 · git main</div>
  </body></html>`;
}

(async () => {
  fs.mkdirSync(CARDS, { recursive: true });
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 1 });
  const segs = DATA.segments;
  for (let i = 0; i < segs.length; i++) {
    const file = path.join(CARDS, `${String(i + 1).padStart(2, '0')}.png`);
    await page.setContent(cardHtml(segs[i], i, segs.length), { waitUntil: 'load' });
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: file, type: 'png' });
    console.log('rendered', path.basename(file), '-', segs[i].id);
  }
  await browser.close();
  fs.writeFileSync(path.join(OUT, 'evidence.json'), JSON.stringify(DATA, null, 2));
  console.log('cards ->', CARDS);
})().catch(e => { console.error(e); process.exit(1); });
