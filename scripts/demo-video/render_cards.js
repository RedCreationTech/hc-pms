// 渲染演示视频的静态画面 (1920x1080 PNG): 片头卡, 11 张章节卡, 片尾卡, 以及每章一张带左侧章节导航栏的录屏背景.
// 录屏画面 (1600x900) 由 compose.py 叠加在背景的 (280, 40) 处, 底部 140 像素留给字幕.
// 同时把分镜导出为 reports/demo-video/storyboard.json 供 Python 合成脚本读取.
// 用法: node scripts/demo-video/render_cards.js
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
// DEMO_STORYBOARD 选择分镜数据源 (默认完整业务流程), DEMO_VIDEO_DIR 选择产物目录.
const S = require(process.env.DEMO_STORYBOARD ? path.resolve(process.env.DEMO_STORYBOARD) : './storyboard.js');

const OUT = process.env.DEMO_VIDEO_DIR ? path.resolve(process.env.DEMO_VIDEO_DIR) : path.resolve(__dirname, '../../reports/demo-video');
const CARDS = path.join(OUT, 'cards');
const W = 1920;
const H = 1080;
const SCREEN = { x: 280, y: 40, w: 1600, h: 900 };

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
`;

function openCard() {
  return `<div class="grid"></div>
  <div class="brand"><div class="mark">红</div>红创PMS</div>
  <div style="position:absolute;left:96px;top:330px;right:120px">
    <div style="width:120px;height:8px;border-radius:4px;background:linear-gradient(90deg,#ef5350,#f59e0b);margin-bottom:44px"></div>
    <div style="font-size:92px;font-weight:800;line-height:1.12;letter-spacing:.01em">${esc(S.title.heading)}</div>
    <div style="font-size:44px;font-weight:500;color:#cbd5e1;margin-top:30px">${esc(S.title.subheading)}</div>
    <div style="font-size:28px;color:#94a3b8;margin-top:56px;display:flex;gap:18px">
      ${S.title.meta.split(' · ').map(t => `<span style="padding:10px 22px;border:1px solid rgba(148,163,184,.35);border-radius:999px;background:rgba(15,23,42,.55)">${esc(t)}</span>`).join('')}
    </div>
  </div>
  <div class="mono" style="position:absolute;right:96px;bottom:72px;font-size:22px;color:#64748b">${S.chapters.length} 个功能节点 · ${S.shots.length} 个镜头</div>`;
}

function dots(active) {
  return `<div style="display:flex;gap:14px;align-items:center">${S.chapters.map(c => {
    const on = c.no === active;
    const done = Number(c.no) < Number(active);
    return `<div style="width:${on ? 64 : 22}px;height:10px;border-radius:5px;background:${on ? 'linear-gradient(90deg,#ef5350,#f59e0b)' : done ? 'rgba(248,250,252,.55)' : 'rgba(148,163,184,.25)'}"></div>`;
  }).join('')}</div>`;
}

function chapterCard(c) {
  return `<div class="grid"></div>
  <div class="brand"><div class="mark">红</div>红创PMS</div>
  <div class="mono" style="position:absolute;right:96px;top:84px;font-size:26px;color:#94a3b8">CHAPTER ${c.no} / ${String(S.chapters.length).padStart(2, '0')}</div>
  <div style="position:absolute;left:96px;top:250px;right:120px;display:flex;gap:64px;align-items:flex-start">
    <div class="mono" style="font-size:240px;font-weight:800;line-height:.9;color:transparent;-webkit-text-stroke:3px rgba(239,83,80,.95);text-shadow:0 0 60px rgba(229,57,53,.25)">${c.no}</div>
    <div style="padding-top:18px">
      <div style="font-size:112px;font-weight:800;line-height:1.08;letter-spacing:.02em">${esc(c.title)}</div>
      <div style="font-size:42px;color:#cbd5e1;margin-top:26px;font-weight:500">${esc(c.subtitle)}</div>
      <div style="display:flex;flex-wrap:wrap;gap:16px;margin-top:48px">
        ${c.points.map(p => `<span style="font-size:30px;padding:12px 26px;border-radius:14px;background:rgba(229,57,53,.14);border:1px solid rgba(239,83,80,.45);color:#fee2e2">${esc(p)}</span>`).join('')}
      </div>
    </div>
  </div>
  <div style="position:absolute;left:96px;bottom:88px">${dots(c.no)}</div>`;
}

function endCard() {
  return `<div class="grid"></div>
  <div class="brand"><div class="mark">红</div>红创PMS</div>
  <div style="position:absolute;left:96px;top:280px;right:120px">
    <div style="width:120px;height:8px;border-radius:4px;background:linear-gradient(90deg,#22c55e,#14b8a6);margin-bottom:44px"></div>
    <div style="font-size:88px;font-weight:800">${esc(S.ending.heading)}</div>
    <div style="margin-top:56px;display:grid;gap:24px">
      ${S.ending.points.map(p => `<div style="font-size:32px;color:#cbd5e1;display:flex;gap:18px;align-items:baseline"><span style="color:#ef5350;font-size:26px">&#9632;</span><span>${esc(p)}</span></div>`).join('')}
    </div>
  </div>
  <div style="position:absolute;left:96px;bottom:88px">${dots('99')}</div>`;
}

function frame(active) {
  const items = S.chapters.map(c => {
    const on = c.no === active;
    const done = Number(c.no) < Number(active);
    const color = on ? '#ffffff' : done ? '#cbd5e1' : '#64748b';
    const bg = on ? 'linear-gradient(90deg, rgba(229,57,53,.95), rgba(183,28,28,.85))' : 'transparent';
    return `<div style="display:flex;align-items:center;gap:12px;height:56px;padding:0 14px;border-radius:12px;background:${bg};color:${color};${on ? 'box-shadow:0 8px 22px rgba(229,57,53,.35);' : ''}">
      <span class="mono" style="font-size:18px;opacity:${on ? 1 : .8};width:26px">${c.no}</span>
      <span style="font-size:23px;font-weight:${on ? 700 : 500}">${esc(c.short)}</span>
      ${done ? '<span style="margin-left:auto;color:#22c55e;font-size:20px">&#10003;</span>' : ''}
    </div>`;
  }).join('');
  return `<div class="grid" style="opacity:.6"></div>
  <div style="position:absolute;left:36px;top:40px;width:220px;display:flex;align-items:center;gap:10px;font-size:24px;font-weight:700">
    <div class="mark" style="width:36px;height:36px;font-size:19px;border-radius:9px">红</div>红创PMS</div>
  <div style="position:absolute;left:28px;top:110px;width:232px;display:grid;gap:10px">${items}</div>
  <div style="position:absolute;left:${SCREEN.x - 6}px;top:${SCREEN.y - 6}px;width:${SCREEN.w + 12}px;height:${SCREEN.h + 12}px;border-radius:14px;
    background:#1e293b;box-shadow:0 18px 60px rgba(0,0,0,.55), 0 0 0 1px rgba(148,163,184,.25)"></div>`;
}

async function main() {
  fs.mkdirSync(CARDS, { recursive: true });
  const executablePath = process.env.PW_CHROMIUM || (fs.existsSync('/opt/pw-browsers/chromium') ? '/opt/pw-browsers/chromium' : undefined);
  const browser = await chromium.launch(executablePath ? { executablePath } : {});
  const page = await browser.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 1 });
  const render = async (name, body) => {
    await page.setContent(`<!doctype html><html><head><meta charset="utf-8"><style>${BASE_CSS}</style></head><body>${body}</body></html>`);
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: path.join(CARDS, name) });
  };
  await render('open.png', openCard());
  for (const c of S.chapters) {
    await render(`ch-${c.no}.png`, chapterCard(c));
    await render(`bg-${c.no}.png`, frame(c.no));
  }
  await render('end.png', endCard());
  await browser.close();
  fs.writeFileSync(path.join(OUT, 'storyboard.json'), JSON.stringify({ title: S.title, ending: S.ending, chapters: S.chapters, shots: S.shots, accounts: S.accounts || {}, screen: SCREEN, size: { w: W, h: H } }, null, 2));
  console.log(`rendered ${2 + S.chapters.length * 2} images to ${CARDS}`);
}

main().catch(err => { console.error(err); process.exit(1); });
