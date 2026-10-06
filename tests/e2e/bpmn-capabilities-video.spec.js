const { test, expect } = require('@playwright/test');
const path = require('node:path');
const fs = require('node:fs');
const S = require('../../scripts/demo-video/storyboard-bpmn.js');
const kit = require('./demo-video-kit.js');

// "BPMN 建模能力全景" 独立演示视频的自动录屏 (分镜与字幕见 scripts/demo-video/storyboard-bpmn.js).
// 与"BPM 与办公一体化"业务生命周期片互补: 本片聚焦流程设计器本身, 按"类别全覆盖"逐项点开真实界面,
//   而非逐下拉穷举. 全程单一 admin 上下文 (既是被授权建模的管理员, 又可只读预览发起流程).
//
// 数据基础: prep 阶段 (不录像) 确保"富能力模型"(model_key=bpmnCapDemo, 覆盖全部节点类型) 存在 --
//   若列表里已有则复用其 id; 否则 POST /bpm/model 新建再把 scripts/demo-video/cap-tree.json 通过
//   POST /bpm/model/:id/tree 落库 (tree->bpmn 回写, 设计器 GET /:id/tree 即可完整还原并点开各类抽屉).
//   全程只"查看/点开"节点配置, 绝不点"保存配置/保存流程", 因此模型树不被录屏污染; 需要脏改的镜头
//   (添加条件/唤出加号面板) 用 Escape/reload 复原. 末章发布内置请假模型(500)并在发起页只读预览.
//
// 结构对齐 bpm-demo-video.spec.js: 每章按分镜用到的账号各开一个录像上下文 (可见光标, 底边时间码),
//   镜头起止与字幕时刻写入 reports/bpmn-video/timeline.json, 由 compose.py
//   (DEMO_STORYBOARD=scripts/demo-video/storyboard-bpmn.js, DEMO_VIDEO_DIR=reports/bpmn-video) 剪辑合成.
// 默认跳过, 仅在 BPMN_CAP_VIDEO=1 时运行. 需隔离后端已起 (BASE_URL, 独立 SQLite + 独立 Flowable H2).
test.skip(!process.env.BPMN_CAP_VIDEO, 'BPMN 能力录屏较慢, 仅在 BPMN_CAP_VIDEO=1 时运行');

const OUT = path.resolve(__dirname, '../../reports/bpmn-video');
const RAW = path.join(OUT, 'raw');
const CAP_TREE = path.resolve(__dirname, '../../scripts/demo-video/cap-tree.json');
const { VIEWPORT, wait, installCursor, installClock, glide, tap, settle, login, api } = kit;
const BASE_URL = process.env.BASE_URL || 'http://localhost:3100';
const LEAVE_MODEL_ID = process.env.LEAVE_MODEL_ID || '500';

const tbody = page => page.locator('.ant-table-tbody tr:not(.ant-table-measure-row)');
const contentArea = page => page.locator('.bpm-flow-wrap, .ant-table-wrapper, .ant-pro-page-container, main').first();

// 进入模型编辑器的"流程设计"步骤, 等待设计器渲染出节点卡片/胶囊.
async function openDesigner(page, id) {
  await page.goto(`/office/bpm/model/edit?id=${id}`);
  await page.waitForLoadState('networkidle');
  await settle(page);
  await tap(page, page.getByText('流程设计', { exact: true }).first());
  await page.waitForSelector('.bpm-flow-wrap', { timeout: 20000 });
  await page.waitForSelector('.bpm-node-card, .bpm-capsule', { timeout: 20000 });
  await settle(page);
}

// 点某节点卡片打开其配置抽屉 (nameRe 命中节点名), 返回抽屉体 locator.
async function openNodeDrawer(page, nameRe) {
  const card = page.locator('.bpm-node-card').filter({ hasText: nameRe }).first();
  await expect(card, `设计器应含节点: ${nameRe}`).toBeVisible({ timeout: 15000 });
  await tap(page, card);
  const drawer = page.locator('.ant-drawer-open');
  await expect(drawer, `应打开节点配置抽屉: ${nameRe}`).toBeVisible({ timeout: 15000 });
  await settle(page);
  return drawer;
}

async function closeDrawer(page) {
  await page.locator('.ant-drawer-close').first().click().catch(() => page.keyboard.press('Escape'));
  await page.waitForTimeout(500);
  await expect(page.locator('.ant-drawer-open'), '抽屉应已关闭').toHaveCount(0, { timeout: 8000 });
}

// 在打开的抽屉内滑到某个分区标题 (真实滚动 + 光标移动), 让该能力类别进入画面.
async function glideDrawerSection(page, drawer, label) {
  const target = drawer.locator(`text=${label}`).first();
  await expect(target, `抽屉应含配置分区: ${label}`).toBeVisible({ timeout: 10000 });
  await target.evaluate(el => el.scrollIntoView({ behavior: 'smooth', block: 'center' })).catch(() => {});
  await page.waitForTimeout(500);
  await glide(page, target);
}

// ── 一次性准备 (不录像): 确保富能力模型存在并返回其 id. ──
async function prepare(page) {
  const list = await api(page, 'GET', '/api/business/bpm/model?page=1&size=100');
  const rows = (list && list.rows) || [];
  let cap = rows.find(r => r.model_key === 'bpmnCapDemo');
  if (!cap) {
    await api(page, 'POST', '/api/business/bpm/model', { model_key: 'bpmnCapDemo', model_name: 'BPMN 能力全景演示', category_id: 0 });
    const again = await api(page, 'GET', '/api/business/bpm/model?page=1&size=100');
    cap = (again.rows || []).find(r => r.model_key === 'bpmnCapDemo');
    if (!cap) throw new Error('创建富能力模型失败');
  }
  const capId = String(cap.model_id);
  // 无论新建还是复用, 都确保节点树已落库 (GET /:id/tree 应能还原出审批人节点).
  const tree = JSON.parse(fs.readFileSync(CAP_TREE, 'utf8'));
  let okTree = await page.request.get(`${BASE_URL}/api/business/bpm/model/${capId}/tree`,
    { headers: { Authorization: `Bearer ${await page.evaluate(() => localStorage.getItem('ruoyi_token'))}` } })
    .then(r => r.json()).catch(() => null);
  const hasUserTask = okTree && okTree.code === 200 && okTree.data && /部门经理审批/.test(JSON.stringify(okTree.data));
  if (!hasUserTask) {
    await api(page, 'POST', `/api/business/bpm/model/${capId}/tree`, tree);
  }
  return { capId };
}

// ── 章节: 每章进入分镜指定的真实界面, 用 kit.glide/tap 逐步点开各类配置, 三拍走完字幕. ──
const CHAPTERS = {
  // 01 能力总览: 富能力模型渲染全部节点类型 + "+" 十种添加节点面板.
  '01': async ({ pages, rec, st }) => {
    const page = pages.admin;
    // 01-1 进入设计器, 滚动展示汇聚了全部节点类型的总体流程.
    await openDesigner(page, st.capId);
    await rec.shot('01-1');
    await glide(page, page.locator('.bpm-node-card').first());
    const flow = page.locator('.bpm-flow-wrap').first();
    await flow.evaluate(el => el.scrollTo({ top: el.scrollHeight / 2, behavior: 'smooth' })).catch(() => {});
    await page.waitForTimeout(600);
    await rec.next();
    await flow.evaluate(el => el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })).catch(() => {});
    await page.waitForTimeout(600);
    await glide(page, page.locator('.bpm-node-card').filter({ hasText: /子流程|结束/ }).last()).catch(() => {});
    await rec.end();

    // 01-2 点节点间加号唤出"在此添加节点"十种类型面板 (看完 Escape 复原, 不真正添加).
    await rec.shot('01-2');
    const plus = page.locator('.bpm-plus-btn').first();
    await expect(plus, '设计器应含加号按钮').toBeVisible({ timeout: 10000 });
    await tap(page, plus);
    const pal = page.getByRole('dialog').filter({ hasText: '在此添加节点' }).last();
    await expect(pal, '应弹出"在此添加节点"面板').toBeVisible({ timeout: 10000 });
    await glide(page, pal.getByText('审批人').first());
    await rec.next();
    await glide(page, pal.getByText('子流程').first());
    await page.keyboard.press('Escape');
    await page.waitForTimeout(400);
    await rec.end();
  },

  // 02 审批人配置抽屉: 候选人策略 / 会签方式 / 超时, 为空, 按钮, 字段权限.
  '02': async ({ pages, rec, st }) => {
    const page = pages.admin;
    await openDesigner(page, st.capId);
    // 02-1 审批类型 + 十四种候选人策略.
    await rec.shot('02-1');
    const d = await openNodeDrawer(page, /部门经理审批/);
    await glideDrawerSection(page, d, '审批人设置');
    await rec.next();
    await glide(page, d.locator('text=指定角色').first());
    await rec.end();

    // 02-2 多人审批(会签)方式.
    await rec.shot('02-2');
    await glideDrawerSection(page, d, '多人审批方式');
    await rec.next();
    await glide(page, d.locator('text=会签（所有人同意）').first()).catch(() => {});
    await rec.end();

    // 02-3 超时 / 候选人为空 / 操作按钮 (均为已验证存在的分区; 字段权限不在该抽屉故不点).
    await rec.shot('02-3');
    await glideDrawerSection(page, d, '审批人超时未处理');
    await rec.next();
    await glideDrawerSection(page, d, '操作按钮配置');
    await rec.end();
    await closeDrawer(page);
  },

  // 03 办理人 + 抄送抽屉.
  '03': async ({ pages, rec, st }) => {
    const page = pages.admin;
    await openDesigner(page, st.capId);
    // 03-1 办理人节点 (TRANSACTOR): 角色候选. 该抽屉无"审批人设置"分区标题, 直接滑过抽屉体与候选策略项.
    await rec.shot('03-1');
    const dt = await openNodeDrawer(page, /总经理办理/);
    await glide(page, dt.locator('.ant-drawer-body').first());
    await rec.next();
    await glide(page, dt.locator('text=指定角色').first()).catch(() => glide(page, dt.locator('.ant-drawer-title').first()));
    await rec.end();
    await closeDrawer(page);

    // 03-2 抄送节点 (COPY): 自动知会.
    await rec.shot('03-2');
    const dc = await openNodeDrawer(page, /抄送人事备案/);
    await glide(page, dc.locator('.ant-drawer-body').first());
    await rec.next();
    await glide(page, dc.locator('text=抄送').first()).catch(() => glide(page, dc.locator('.ant-drawer-title').first()));
    await rec.end();
    await closeDrawer(page);
  },

  // 04 网关与条件: 条件规则编辑器 / 添加条件 / 并行, 包容分支渲染.
  '04': async ({ pages, rec, st }) => {
    const page = pages.admin;
    await openDesigner(page, st.capId);
    // 04-1 点条件标签打开条件规则编辑器 (CONDITION 按表达式路由).
    await rec.shot('04-1');
    const label = page.locator('.bpm-branch-label').first();
    await expect(label, '应含条件分支标签').toBeVisible({ timeout: 12000 });
    await tap(page, label);
    const dg = page.locator('.ant-drawer-open');
    await expect(dg, '应打开条件规则抽屉').toBeVisible({ timeout: 12000 });
    await settle(page);
    await glide(page, dg.locator('.ant-drawer-body').first());
    await rec.next();
    await page.waitForTimeout(300);
    await rec.end();
    await closeDrawer(page);

    // 04-2 "添加条件"追加一条并列分支 (脏改, 看完 reload 复原).
    await rec.shot('04-2');
    const before = await page.locator('.bpm-branch-item').count();
    const addBtn = page.locator('.bpm-branch-add').first();
    await expect(addBtn, '应含"添加条件"入口').toBeVisible({ timeout: 10000 });
    await tap(page, addBtn);
    await page.waitForTimeout(600);
    await expect.poll(() => page.locator('.bpm-branch-item').count(),
      { timeout: 8000, message: '添加条件后并列分支数应增加' }).toBeGreaterThan(before);
    await glide(page, page.locator('.bpm-branch-item').last());
    await rec.next();
    await page.waitForTimeout(300);
    await rec.end();

    // 04-3 并行 / 包容分支网关渲染. 网关本身以分支区呈现 (无独立节点卡片), 故滑到其代表条件标签.
    //   实测: 并行/包容网关的分支标签统一渲染为 "条件1/条件2 + 表达式" (自定义名 分支1/条件A 不进 DOM),
    //   故按各网关独有的表达式文本区分: 并行评审 分支表达式含 approved, 包容分流 分支表达式含 risk / needFinance.
    await openDesigner(page, st.capId); // reload 丢弃 04-2 脏改, 重新渲染全部网关
    await rec.shot('04-3');
    const par = page.locator('.bpm-branch-label').filter({ hasText: /approved/ }).first();
    await expect(par, '应含并行分支条件标签 (approved 表达式)').toBeVisible({ timeout: 12000 });
    await par.evaluate(el => el.scrollIntoView({ block: 'center' })).catch(() => {});
    await glide(page, par);
    await page.waitForTimeout(400);
    await rec.next();
    const inc = page.locator('.bpm-branch-label').filter({ hasText: /risk|needFinance/ }).first();
    await expect(inc, '应含包容分支条件标签 (risk/needFinance 表达式)').toBeVisible({ timeout: 12000 });
    await inc.evaluate(el => el.scrollIntoView({ block: 'center' })).catch(() => {});
    await glide(page, inc);
    await page.waitForTimeout(400);
    await rec.end();
  },

  // 05 延迟器 / 触发器 / 子流程. 每镜头先 reload 设计器到干净态再操作 (隔离互不影响).
  '05': async ({ pages, rec, st }) => {
    const page = pages.admin;
    // 05-1 延迟器 (DELAY) 边界定时器抽屉 (已验证可正常打开).
    await openDesigner(page, st.capId);
    await rec.shot('05-1');
    const dd = await openNodeDrawer(page, /等待两小时/);
    await glide(page, dd.locator('.ant-drawer-body').first());
    await rec.next();
    await glide(page, dd.locator('text=延迟').first()).catch(() => glide(page, dd.locator('.ant-drawer-title').first()));
    await rec.end();
    await closeDrawer(page);

    // 05-2 触发器 (TRIGGER) 节点: 该节点点开抽屉会令设计器整体重渲染 (实测, 非脚本 bug),
    //   故本镜头不点开抽屉, 只在干净设计器里滑到并停留展示该节点卡片本身 (其 show-text 显示触发类型).
    await openDesigner(page, st.capId);
    await rec.shot('05-2');
    const trCard = page.locator('.bpm-node-card').filter({ hasText: /触发通知/ }).first();
    await expect(trCard, '设计器应含触发器节点').toBeVisible({ timeout: 12000 });
    await trCard.evaluate(el => el.scrollIntoView({ block: 'center' })).catch(() => {});
    await glide(page, trCard);
    await page.waitForTimeout(500);
    await rec.next();
    await glide(page, trCard.locator('.bpm-node-text').first()).catch(() => {});
    await rec.end();

    // 05-3 子流程 (CHILD_PROCESS) 调用独立请假流程 (已验证抽屉可正常打开).
    await openDesigner(page, st.capId);
    await rec.shot('05-3');
    const dch = await openNodeDrawer(page, /调用请假子流程/);
    await glide(page, dch.locator('.ant-drawer-body').first());
    await rec.next();
    await glide(page, dch.locator('text=子流程').first()).catch(() => glide(page, dch.locator('.ant-drawer-title').first()));
    await rec.end();
    await closeDrawer(page);
  },

  // 06 追踪与发起可见: 发布运行定义 -> 发起页只读流程图预览.
  '06': async ({ pages, rec }) => {
    const page = pages.admin;
    // 06-1 打开请假模型编辑器点"发布"生成运行时定义, 再进发起流程页.
    await openDesigner(page, LEAVE_MODEL_ID);
    await rec.shot('06-1');
    const deployResp = page.waitForResponse(r => r.url().includes(`/bpm/model/deploy/${LEAVE_MODEL_ID}`), { timeout: 25000 }).catch(() => null);
    const pub = page.getByRole('button', { name: '发布' }).first();
    await expect(pub, '编辑器应含"发布"按钮').toBeVisible({ timeout: 10000 });
    await tap(page, pub);
    const resp = await deployResp;
    if (resp) { const body = await resp.json().catch(() => ({})); expect(body.code, `发布模型: ${body.msg}`).toBe(200); }
    await page.goto('/office/bpm/start');
    await page.waitForLoadState('networkidle');
    await settle(page);
    await glide(page, contentArea(page));
    await rec.next();
    await glide(page, tbody(page).filter({ hasText: /请假审批/ }).first());
    await rec.end();

    // 06-2 点"发起"打开弹窗, 右侧只读流程图预览完整审批链与审批人.
    await rec.shot('06-2');
    const leaveRow = tbody(page).filter({ hasText: /请假审批/ }).first();
    await expect(leaveRow, '发起列表应含"请假审批"').toBeVisible({ timeout: 15000 });
    await tap(page, leaveRow.getByRole('button', { name: /发\s*起/ }));
    // 发起弹窗内可能再嵌套子 dialog, 裸 .last() 会命中错的层 -> 用只读预览独有的"流程追踪"标题锚定外层弹窗.
    const modal = page.getByRole('dialog').filter({ hasText: '流程追踪' }).last();
    await expect(modal, '发起弹窗应打开').toBeVisible({ timeout: 15000 });
    await expect(modal.locator('.bpm-toolbar-title'), '只读预览标题应为"流程追踪"')
      .toHaveText('流程追踪', { timeout: 12000 });
    await rec.next();
    for (const nm of ['部门经理审批', '分管领导审批', 'HR确认']) {
      await expect(modal.locator('.bpm-node-name', { hasText: nm }).first(), `预览审批链应含: ${nm}`)
        .toBeVisible({ timeout: 12000 });
    }
    await glide(page, modal.locator('.bpm-node-text', { hasText: '审批人：' }).first()).catch(() => {});
    await rec.end();
  },
};

test.describe('BPMN 建模能力全景录屏 (真实逐步操作)', () => {
  test.setTimeout(2400000);

  test('能力总览 / 审批人配置 / 办理抄送 / 网关条件 / 延迟触发子流程 / 发起可见 (录屏 + 时间轴)', async ({ browser }) => {
    // DEMO_CHAPTERS="02,04" 只重录指定章: 不清空整个 raw, 仅删被重录章的 webm.
    const only = process.env.DEMO_CHAPTERS ? process.env.DEMO_CHAPTERS.split(',').map(s => s.trim()).filter(Boolean) : null;
    fs.mkdirSync(RAW, { recursive: true });
    if (only) {
      for (const f of fs.readdirSync(RAW)) {
        if (only.some(c => f.startsWith(`ch-${c}-`) || f.startsWith(`ch-${c.slice(1)}-`))) fs.rmSync(path.join(RAW, f), { force: true });
      }
    } else {
      fs.rmSync(RAW, { recursive: true, force: true });
      fs.mkdirSync(RAW, { recursive: true });
    }

    const recorded = async (withVideo = true) => {
      const opts = { baseURL: BASE_URL, viewport: VIEWPORT };
      if (withVideo) opts.recordVideo = { dir: RAW, size: VIEWPORT };
      const context = await browser.newContext(opts);
      await context.addInitScript(installCursor);
      await context.addInitScript(installClock);
      const page = await context.newPage();
      return { context, page, t0: Date.now() };
    };

    // 准备 (不录像): 确保富能力模型存在, 记录 capId.
    const prepCtx = await browser.newContext({ baseURL: BASE_URL, viewport: VIEWPORT });
    const prep = await prepCtx.newPage();
    await login(prep);
    const st = await prepare(prep);
    await prepCtx.close();

    const creds = { admin: ['admin', 'admin123'] };
    const clips = [];
    for (const chapter of S.chapters) {
      if (only && !only.includes(chapter.no)) continue;
      const whos = [...new Set(S.shots.filter(s => s.chapter === chapter.no).map(s => s.who))];
      const ctx = {};
      const errors = [];
      for (const who of whos) {
        ctx[who] = await recorded();
        ctx[who].page.on('pageerror', error => errors.push(`${who}: ${error.message}`));
        await login(ctx[who].page, ...creds[who]);
      }
      const rec = kit.recorder(S, ctx);
      const pages = Object.fromEntries(Object.entries(ctx).map(([k, v]) => [k, v.page]));
      try {
        await CHAPTERS[chapter.no]({ pages, rec, st });
        expect(errors, `第 ${chapter.no} 章页面错误`).toEqual([]);
      } finally {
        await wait(2500);
        for (const [who, c] of Object.entries(ctx)) {
          await c.context.close();
          const rel = `raw/ch-${chapter.no}-${who}.webm`;
          await c.page.video().saveAs(path.join(OUT, rel));
          await c.page.video().delete();
          c.rel = rel;
        }
      }
      expect([...new Set(rec.timeline.clips.map(c => c.shot))], `第 ${chapter.no} 章镜头须与分镜一致`)
        .toEqual(S.shots.filter(s => s.chapter === chapter.no).map(s => s.id));
      clips.push(...rec.timeline.clips.map(c => ({ ...c, video: ctx[c.who].rel, t0: ctx[c.who].t0 })));
    }
    const expectedShots = only ? S.shots.filter(s => only.includes(s.chapter)).map(s => s.id) : S.shots.map(s => s.id);
    expect([...new Set(clips.map(c => c.shot))]).toEqual(expectedShots);
    const outName = only ? 'timeline.partial.json' : 'timeline.json';
    fs.writeFileSync(path.join(OUT, outName),
      JSON.stringify({ viewport: VIEWPORT, recorded_at: new Date().toISOString(), clips }, null, 2));
    fs.writeFileSync(path.join(OUT, 'state.json'), JSON.stringify(st, null, 2));
  });
});
