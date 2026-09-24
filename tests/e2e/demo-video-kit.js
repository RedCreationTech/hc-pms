// 演示视频录屏共用工具: 可见光标与点击涟漪, 底边时间码条, 平滑移动/点击/逐字输入, 等画面稳定, 按分镜记录时间轴.
// 由 pms-demo-video.spec.js (完整业务流程) 与 config-demo-video.spec.js (组织, 菜单与流程配置) 共用.
const { expect } = require('@playwright/test');

const VIEWPORT = { width: 1600, height: 900 };
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));

// 注入到每个页面: 可见光标 (跟随真实鼠标事件), 点击涟漪, 隐藏无功能的装饰浮标; 跨页面导航保留光标位置.
function installCursor() {
  const KEY = '__demo_cursor';
  const install = () => {
    if (document.getElementById(KEY)) return;
    const style = document.createElement('style');
    style.textContent = '.app-layout-float{display:none!important}';
    document.head.appendChild(style);
    const el = document.createElement('div');
    el.id = KEY;
    el.innerHTML = '<svg width="28" height="28" viewBox="0 0 28 28"><path d="M4 2 L4 22 L9.4 17 L13 25.2 L16.6 23.6 L13.1 15.6 L20.6 15.6 Z" fill="#111827" stroke="#ffffff" stroke-width="1.8" stroke-linejoin="round"/></svg>';
    Object.assign(el.style, { position: 'fixed', left: '0px', top: '0px', zIndex: '2147483647', pointerEvents: 'none', transform: 'translate(-60px,-60px)', filter: 'drop-shadow(0 2px 3px rgba(0,0,0,.35))' });
    document.documentElement.appendChild(el);
    const move = (x, y) => { el.style.transform = `translate(${x - 4}px, ${y - 2}px)`; };
    try { const last = JSON.parse(sessionStorage.getItem(KEY) || 'null'); if (last) move(last.x, last.y); } catch (e) { /* 无存储时从屏外开始 */ }
    document.addEventListener('mousemove', e => {
      move(e.clientX, e.clientY);
      try { sessionStorage.setItem(KEY, JSON.stringify({ x: e.clientX, y: e.clientY })); } catch (err) { /* 忽略 */ }
    }, true);
    document.addEventListener('mousedown', e => {
      const r = document.createElement('div');
      Object.assign(r.style, { position: 'fixed', left: `${e.clientX - 20}px`, top: `${e.clientY - 20}px`, width: '40px', height: '40px', borderRadius: '50%',
        border: '3px solid rgba(220,38,38,.85)', background: 'rgba(239,68,68,.16)', zIndex: '2147483646', pointerEvents: 'none',
        transform: 'scale(.3)', opacity: '1', transition: 'transform .5s ease-out, opacity .5s ease-out' });
      document.documentElement.appendChild(r);
      requestAnimationFrame(() => { r.style.transform = 'scale(1.5)'; r.style.opacity = '0'; });
      setTimeout(() => r.remove(), 700);
    }, true);
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', install); else install();
}

// 注入到每个页面: 底边 8 像素的时间码条, 每 100 毫秒把 Date.now() 的低 24 位 (毫秒) 画成 27 个黑白块
// (起始 1,0 + 24 位 + 偶校验). 高负载下录像自身的时间与墙钟的偏差不均匀且会累积 (实测一章内可达 18 秒),
// compose.py 逐帧读出时间码, 把时间轴上的每个时刻映射到真正显示该时刻画面的那一帧; 合成时裁掉这 8 像素.
function installClock() {
  const put = () => {
    if (document.getElementById('__demo_clock')) return;
    const canvas = document.createElement('canvas');
    canvas.id = '__demo_clock';
    canvas.width = 1600;
    canvas.height = 8;
    Object.assign(canvas.style, { position: 'fixed', left: '0px', bottom: '0px', width: '1600px', height: '8px', zIndex: '2147483647', pointerEvents: 'none' });
    document.documentElement.appendChild(canvas);
    const g = canvas.getContext('2d');
    const draw = () => {
      const v = Date.now() % 16777216;
      const bits = [1, 0];
      for (let i = 23; i >= 0; i--) bits.push((v >> i) & 1);
      bits.push(bits.slice(2).reduce((a, b) => a ^ b, 0));
      g.fillStyle = '#ffffff';
      g.fillRect(0, 0, 1600, 8);
      g.fillStyle = '#000000';
      bits.forEach((b, i) => { if (b) g.fillRect(i * 56, 0, 56, 8); });
    };
    draw();
    setInterval(draw, 100);
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', put); else put();
}

// ── 可见的鼠标与键盘操作 ──

async function glide(page, locator) {
  await locator.waitFor({ state: 'visible' });
  let box = await locator.boundingBox();
  if (!box || box.y < 64 || box.y + box.height > VIEWPORT.height - 24) {
    await locator.evaluate(el => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));
    await page.waitForTimeout(700);
    box = await locator.boundingBox();
  }
  if (box) await page.mouse.move(box.x + Math.min(box.width / 2, 60), box.y + box.height / 2, { steps: 18 });
  await page.waitForTimeout(140);
}

async function tap(page, locator) {
  await glide(page, locator);
  await locator.click();
  await page.waitForTimeout(260);
}

async function typeInto(page, locator, text, delay = 55) {
  await tap(page, locator);
  await locator.fill('');
  await locator.pressSequentially(String(text), { delay });
  await page.waitForTimeout(160);
}

// 等画面追上 DOM: 可见的加载指示消失后再过两帧 requestAnimationFrame (此时最新 DOM 已绘制进录像帧).
// 录屏时 CPU 紧张, 绘制可能落后 DOM 1-2 秒; 镜头开始, 字幕切换与切段前都先等画面稳定, 字幕才不会抢在画面前面.
async function settle(page) {
  await page.waitForFunction(() => ![...document.querySelectorAll('.ant-spin-spinning, .ant-skeleton-active')].some(el => el.offsetParent !== null),
    null, { timeout: 8000 }).catch(() => {});
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

// ── 录屏时间轴 ──
// 片段 = 某个镜头在某个账号录像中的一段连续画面; rec.cut 在不入镜的后台操作前后切开同一镜头, 下一条字幕在新片段开始时出现.

function recorder(S, pages) {
  const timeline = { viewport: VIEWPORT, recorded_at: new Date().toISOString(), clips: [] };
  const now = who => (Date.now() - pages[who].t0) / 1000;
  let shot = null;
  let seg = null;
  const holdCaption = async () => {
    const text = shot.captions[shot.index];
    const remain = S.minDuration(text) * 1000 - (Date.now() - shot.cueAt);
    if (remain > 0) await wait(remain);
  };
  const begin = () => {
    seg = { shot: shot.id, chapter: shot.chapter, who: shot.who, start: Math.max(0, now(shot.who) - 0.12), cues: [{ t: now(shot.who), text: shot.captions[shot.index] }] };
    shot.cueAt = Date.now();
  };
  const close = () => { seg.end = now(shot.who); timeline.clips.push(seg); seg = null; };
  return {
    timeline,
    async shot(id) {
      expect(shot, `镜头 ${id} 开始前, 上一个镜头 ${shot && shot.id} 尚未结束`).toBeNull();
      const s = S.byId[id];
      expect(s, `分镜中不存在镜头 ${id}`).toBeTruthy();
      shot = { ...s, index: 0 };
      await settle(pages[s.who].page);
      await wait(250);
      begin();
    },
    async next() {
      expect(shot.index + 1, `镜头 ${shot.id} 的字幕已经用完`).toBeLessThan(shot.captions.length);
      await settle(pages[shot.who].page);
      await holdCaption();
      shot.index += 1;
      seg.cues.push({ t: now(shot.who), text: shot.captions[shot.index] });
      shot.cueAt = Date.now();
    },
    async cut(work) {
      expect(shot.index + 1, `镜头 ${shot.id} 切段后没有可显示的下一条字幕`).toBeLessThan(shot.captions.length);
      await settle(pages[shot.who].page);
      await holdCaption();
      await wait(250);
      close();
      await work();
      shot.index += 1;
      begin();
    },
    async end() {
      while (shot.index + 1 < shot.captions.length) await this.next();
      await settle(pages[shot.who].page);
      await holdCaption();
      await wait(450);
      close();
      shot = null;
    },
  };
}


async function login(page, user = 'admin', password = 'admin123') {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill(user);
  await page.getByPlaceholder('密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ruoyi_token'))).toBeTruthy();
}

async function api(page, method, url, data, expected = 200) {
  const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
  const response = await page.request.fetch(url, { method, headers: { Authorization: `Bearer ${token}` }, data });
  const body = await response.json();
  expect(body.code, `${method} ${url}: ${body.msg}`).toBe(expected);
  return body.data;
}

module.exports = { VIEWPORT, wait, installCursor, installClock, glide, tap, typeInto, settle, recorder, login, api };
