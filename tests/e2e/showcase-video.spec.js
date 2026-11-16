// 功能演示视频录制 (配合 agent-evidence-video 技能的 compose.py).
// 分镜与台词: scripts/demo-video/showcase-storyboard.json (唯一数据源); 台词配音时长: reports/demo-video/showcase/durations.json.
// 每章一个浏览器上下文独立录制 (recordVideo), 逐句 beat() 记录台词起点 (墙钟), 结束时写 marks.json 供 manifest 生成.
// 章节间用真实 HTTP 完成重复性审批/预置 (不入镜), 界面操作全部真实录制; 两个账号: admin 项目经理 + 合成的独立审核人.
//
// PMS HTTP 命令不变量 (服务端 kernel/writable! 强制):
//   1. 所有 /api/pms/projects/:id/* 命令 (含 gates/time/closure/cost/delivery) body 必须带当前项目 version -> mutate/asReviewer 助手自动附带.
//   2. 审核人必须先加入项目成员 (POST /:id/members {user_id, role:'viewer'}, 此端点禁带 version), 否则读取与指定审批 403/400.
//   3. 指定审批类命令的 actor 必须恰为 reviewer_id (kernel/independent-review!).
//   4. 关键端点: 基线 POST /planning/baselines/:id/review {version, decision, comment}; 工时 POST /time-entries/:id/review {version, decision, reason};
//      关闭 POST /closure/review {version, decision, reason}; 费用 POST /cost-versions/:id/review {version, decision, reason}.
//   5. 交付链顺序: BOM kit 满 (ready) 才能建装配 -> 齐套Gate (G4) 放行才能开工 -> 装配交检 approved + 交接Gate (G5) 才能录 SIT 结果 ->
//      SIT approved 才能录 FAT -> FAT 确认Gate (G6) 才能发运 -> 收尾前需 SAT approved + sat-confirm (G8) + 叶任务全部完成.
// 运行:
//   SHOWCASE_DRY=1    npx playwright test tests/e2e/showcase-video.spec.js   # 干跑: 截图到 showcase/dry/, 校验选择器
//   SHOWCASE_VIDEO=1  npx playwright test tests/e2e/showcase-video.spec.js   # 正式录屏到 showcase/raw/
// PW_NO_CHANNEL=1 时用内置 Chromium 录制 (避免有头 Chrome 录像底部灰带).
const { test, expect } = require('@playwright/test');
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');

const SKILL = process.env.EVIDENCE_SKILL_DIR || path.join(os.homedir(), '.codex/skills/agent-evidence-video');
const visual = require(path.join(SKILL, 'scripts/interaction.js'));

const DRY = !!process.env.SHOWCASE_DRY;
const RECORD = !!process.env.SHOWCASE_VIDEO;
test.skip(!DRY && !RECORD, '设置 SHOWCASE_DRY=1 或 SHOWCASE_VIDEO=1 才运行');

const OUT = path.resolve(__dirname, '../../reports/demo-video/showcase');
const RAW = path.join(OUT, 'raw');
const DRY_DIR = path.join(OUT, 'dry');
const BASE_URL = process.env.BASE_URL || 'http://localhost:3000';
const ORIGIN = new URL(BASE_URL).origin;
const durations = JSON.parse(fs.readFileSync(path.join(OUT, 'durations.json'), 'utf8'));

const wait = ms => new Promise(r => setTimeout(r, ms));
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const localDate = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const today = () => localDate(new Date());
const daysAgo = n => { const d = new Date(); d.setDate(d.getDate() - n); return localDate(d); };

async function api(page, method, url, data, expected = 200) {
  const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
  const response = await page.request.fetch(url, { method, headers: { Authorization: `Bearer ${token}` }, data });
  const body = await response.json();
  expect(body.code, `${method} ${url}: ${body.msg}`).toBe(expected);
  return body.data;
}

// 项目命令一律先读当前 version 再提交 (服务端乐观锁强制).
async function mutate(page, id, suffix, data = {}, expected = 200, method = 'POST') {
  const project = await api(page, 'GET', base(id));
  return api(page, method, base(id) + suffix, { ...data, version: project.version }, expected);
}

// 审核人 token 版本的同款命令 (先以审核人身份读 version, 再带 version 提交).
async function asReviewer(page, method, url, data = {}, expected = 200) {
  const headers = { Authorization: `Bearer ${state.reviewerToken}` };
  let payload = data;
  if (method !== 'GET') {
    const pv = await page.request.fetch(`${BASE_URL}${base(state.id)}`, { method: 'GET', headers });
    expect((await pv.json()).code).toBe(200);
    const project = (await pv.json()).data;
    payload = { ...data, version: project.version };
  }
  const response = await page.request.fetch(`${BASE_URL}${url}`, { method, headers, data: payload });
  const body = await response.json();
  expect(body.code, `${method} ${url}: ${body.msg}`).toBe(expected);
  return body.data;
}

async function choose(page, form, key, text) {
  const input = form.locator(`[id="${key}"]`);
  const multiple = await input.evaluate(el => !!el.closest('.ant-select-multiple'));
  await input.click();
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').filter({ has: page.locator(`[id="${list}"]`) });
  for (const one of Array.isArray(text) ? text : [text]) {
    if (typeof one === 'string' && await input.isEditable()) {
      await input.fill(one);
      await wait(160);
    }
    const option = dropdown.locator('.ant-select-item-option').filter({ hasText: one }).first();
    // 多选再次点击已选项会取消选择 (表单可能带派生默认值), 已选则跳过.
    const selected = await option.evaluate(el => el.classList.contains('ant-select-item-option-selected')).catch(() => false);
    if (!selected) await option.click();
    if (!multiple) break;
  }
  await input.press('Escape'); // 多选也要收起下拉, 否则残留浮层挡住保存按钮.
}

async function fill(form, values) {
  for (const [key, value] of Object.entries(values)) await form.locator(`[id="${key}"]`).fill(String(value));
}

// 按可访问名称精确定位目标弹窗 (不能用 hasText 过滤: 项目抽屉含同名按钮会误匹配).
async function save(page, title, expected = 200, button = /^保\s*存$/) {
  const form = modal(page, title);
  const response = page.waitForResponse(r => r.url().includes('/api/pms/') && ['POST', 'PUT', 'DELETE'].includes(r.request().method()));
  await form.getByRole('button', { name: button }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(expected);
  if (expected === 200) { await expect(form).toBeHidden(); await page.waitForLoadState('networkidle'); }
  return body;
}

async function tab(page, name) {
  await drawer(page).getByRole('tab', { name, exact: true }).click();
  await page.waitForLoadState('networkidle');
}

async function open(page, id, section, subsection) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle'); // 等抽屉内子面板数据到位, 避免台词起点截到加载骨架
  if (section) await tab(page, section);
  if (subsection) await tab(page, subsection);
}

async function transitionUi(page, id, button, title) {
  await open(page, id);
  await drawer(page).getByRole('button', { name: new RegExp(`${button}$`) }).click();
  const response = page.waitForResponse(r => r.url().endsWith(`/projects/${id}/transition`) && r.request().method() === 'POST');
  await modal(page, title).getByRole('button', { name: title, exact: true }).click();
  const result = await (await response).json();
  expect(result.code, result.msg).toBe(200);
  await expect(modal(page, title)).toBeHidden();
  await page.waitForLoadState('networkidle');
}

async function login(page, user = 'admin', password = 'admin123') {
  await page.goto('/');
  await page.getByPlaceholder('用户名').waitFor();
  await visual.type(page.getByPlaceholder('用户名'), user);
  await visual.type(page.getByPlaceholder('密码'), password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await page.getByText('红创PMS').nth(1).waitFor();
}

// 每场景: 独立上下文录屏 (或干跑截图), beat 记录台词起点并保证句间不重叠.
async function runScene(browser, id, fn) {
  fs.mkdirSync(RECORD ? RAW : DRY_DIR, { recursive: true });
  const context = await browser.newContext({
    baseURL: BASE_URL,
    viewport: { width: 1560, height: 1080 },
    ...(RECORD ? { recordVideo: { dir: RAW, size: { width: 1560, height: 1080 } } } : {}),
    ...(id === '01' ? {} : { storageState: await adminState() }),
  });
  const page = await context.newPage();
  await visual.install(page, { showInput: true });
  const t0 = Date.now();
  const marks = {};
  let lastId = null;
  const elapsed = () => (Date.now() - t0) / 1000;
  async function pace() {
    if (!lastId) return;
    const need = (marks[lastId] + durations[lastId] + 0.4 - elapsed()) * 1000;
    if (need > 0) await wait(need);
  }
  // 在该句对应画面出现时调用: 等上一句配音结束 -> 记录本句起点 -> (干跑) 截图.
  async function beat(lineId) {
    await pace();
    marks[lineId] = Math.round(elapsed() * 100) / 100;
    lastId = lineId;
    if (DRY) await page.screenshot({ path: path.join(DRY_DIR, `${id}-${lineId}.png`), animations: 'disabled' });
  }
  const videoPath = RECORD ? await page.video().path() : null;
  try {
    await fn({ page, beat, id });
    await pace();
    await wait(1000); // 尾帧留白, 保证末句配音完整落在录制内
  } finally {
    await context.close();
    if (RECORD) fs.renameSync(videoPath, path.join(RAW, `${id}.webm`));
    sceneMarks[id] = { wall: Math.round(elapsed() * 100) / 100, lines: marks };
    console.log(`scene ${id}: wall=${sceneMarks[id].wall}s marks=${JSON.stringify(marks)}`);
  }
}

const sceneMarks = {};

// 管理员 storageState: 每场景前用真实 API 登录换新 token (场景 03 会界面退出撤销旧令牌).
async function adminState() {
  const resp = await fetch(`${BASE_URL}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' }),
  });
  const body = await resp.json();
  expect(body.code, body.msg).toBe(200);
  return {
    cookies: [],
    origins: [{ origin: ORIGIN, localStorage: [{ name: 'ruoyi_token', value: body.data.token }] }],
  };
}

async function reviewerToken() {
  const resp = await fetch(`${BASE_URL}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: state.who.username, password: state.who.password }),
  });
  const body = await resp.json();
  expect(body.code, body.msg).toBe(200);
  return body.data.token;
}

function makePdf(text) {
  const stream = `BT /F1 18 Tf 24 120 Td (${text}) Tj ET`;
  let out = '%PDF-1.4\n';
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ];
  const offsets = [];
  objects.forEach((obj, i) => { offsets.push(out.length); out += `${i + 1} 0 obj\n${obj}\nendobj\n`; });
  const xref = out.length;
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const off of offsets) out += `${String(off).padStart(10, '0')} 00000 n \n`;
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(out, 'latin1');
}

// 已发布设备模板必须带派生层级 (复制自 pms-demo-flow.spec.js 的已验证逻辑).
async function equipmentTemplate(page) {
  const listing = await api(page, 'GET', '/api/pms/config/project-template');
  const catalog = listing.catalog.find(item => item.code === 'TPL-EQUIPMENT');
  const rows = listing.rows.filter(r => r.code === 'TPL-EQUIPMENT');
  const published = rows.find(r => r.status === 'published');
  const withLevels = t => t && t.stages.every(s => Array.isArray(s.levels) && s.levels.length > 0);
  if (withLevels(published)) return published;
  const draft = rows.find(r => r.status === 'draft');
  if (draft) {
    await api(page, 'POST', `/api/pms/config/project-template/${draft.id}/publish`, { reason: '演示发布' });
  } else if (rows.length === 0) {
    await api(page, 'POST', '/api/pms/config/project-template/import', { code: 'TPL-EQUIPMENT' });
    const imported = (await api(page, 'GET', '/api/pms/config/project-template')).rows.find(r => r.code === 'TPL-EQUIPMENT' && r.status === 'draft');
    await api(page, 'POST', `/api/pms/config/project-template/${imported.id}/publish`, { reason: '演示发布' });
  } else {
    const latest = rows.reduce((a, b) => (a.revision > b.revision ? a : b));
    const revised = await api(page, 'POST', `/api/pms/config/project-template/${latest.id}/revisions`, { ...catalog, reason: '恢复目录阶段定义' });
    await api(page, 'POST', `/api/pms/config/project-template/${revised.id}/publish`, { reason: '演示发布' });
  }
  return (await api(page, 'GET', '/api/pms/config/project-template')).rows.find(r => r.code === 'TPL-EQUIPMENT' && r.status === 'published');
}

// 合成独立审核人 (角色带 PMS 审批所需权限), 返回凭据; 首次调用时创建.
async function ensureReviewer(page) {
  if (state.who) return state.who;
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve', 'pms:time:approve',
    'pms:finance:query', 'pms:finance:approve', 'pms:project:close', 'pms:project:reopen', 'pms:document:confidential']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `演示审核${suffix}`, role_key: `pms_demo_${suffix}`, 'menu-ids': menuIds });
  const roleId = (await api(page, 'GET', '/api/system/role')).find(item => item.role_key === `pms_demo_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`, password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立审核人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: '演示' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  state.who = { username, password, userId };
  return state.who;
}

const state = { who: null, reviewerToken: null, id: null, projectNo: null, suffix: null, evidence: null, requirement: null, bomId: null, assemblyId: null };

// 章节间预置: 用真实 HTTP 完成重复性审批与前置数据 (不入镜, 交付说明中如实标注).
async function setupPage(context) {
  const page = await context.newPage();
  await login(page);
  return page;
}

// 建关口实例 -> 全部检查项通过并绑定证据 -> 提交 -> 独立审核人批准 (全程带 version).
async function passGateHttp(page, gateType, title) {
  const ws = await api(page, 'GET', base(state.id) + '/governance');
  const template = ws.gate_templates.find(t => t.gate_type === gateType);
  expect(template, `关口模板 ${gateType}`).toBeTruthy();
  const gate = (await mutate(page, state.id, '/governance/gates', { template_id: template.id, title, reviewer_id: state.who.userId })).result;
  await mutate(page, state.id, `/governance/gates/${gate.id}/checks`, {
    checks: template.checks.map(c => ({ code: c.code, passed: true, evidence_ids: [state.evidence.id] })),
  });
  await mutate(page, state.id, `/governance/gates/${gate.id}/submit`, {});
  await asReviewer(page, 'POST', base(state.id) + `/governance/gates/${gate.id}/decision`, { decision: 'approved', reason: '独立核验证据版本' });
  return gate;
}

// 交付记录通用审批: submit (带指定审核人) -> 审核人 decision.
async function approveDelivery(page, collection, record, action = 'submit') {
  await mutate(page, state.id, `/delivery/${collection}/${record.id}/${action}`,
    collection === 'shipments' ? { evidence_ids: [state.evidence.id] } : { evidence_ids: [state.evidence.id], reviewer_id: state.who.userId });
  await asReviewer(page, 'POST', base(state.id) + `/delivery/${collection}/${record.id}/decision`, { decision: 'approved', reason: '独立核查实际记录' });
}

test.describe('功能演示视频录制 (showcase)', () => {
  test.setTimeout(2400000);

  test('按分镜逐章录制', async ({ browser }) => {
    test.skip(!DRY && !RECORD, '未设置运行模式');
    fs.mkdirSync(OUT, { recursive: true });
    const setup = await browser.newContext({ baseURL: BASE_URL, viewport: { width: 1280, height: 800 } });
    const sp = await setupPage(setup);
    state.suffix = serial();
    state.projectNo = `DEMO-${state.suffix.toUpperCase()}`;

    // 前置: 模板已发布; 让位强制编码规则; 创建审核人.
    await equipmentTemplate(sp);
    for (const r of (await api(sp, 'GET', '/api/pms/config/coding-rule')).rows) {
      if (r.object_type === 'project' && r.status === 'published' && r.enforced) {
        await api(sp, 'POST', `/api/pms/config/coding-rule/${r.id}/retire`, { reason: '演示让位' });
      }
    }
    await ensureReviewer(sp);
    state.reviewerToken = await reviewerToken();

    // ── 01 登录与工作台 ──
    await runScene(browser, '01', async ({ page, beat }) => {
      await beat('01-1');
      await login(page);
      await expect(page.getByText('用户总量')).toBeVisible();
      await beat('01-2');
      await expect(page.getByText('快捷操作')).toBeVisible();
      await beat('01-3');
      await page.getByRole('menuitem', { name: /项目管理/ }).click();
      await expect(page.getByText('项目台账').first()).toBeVisible();
    });

    // ── 02 立项与模板 ──
    await runScene(browser, '02', async ({ page, beat }) => {
      await page.goto('/pms/project');
      const createBtn = page.getByRole('button', { name: /创建项目|新建项目/ }).first();
      await expect(createBtn).toBeVisible(); // 先等页面渲染, 再记台词起点, 避免截图停在空页
      await beat('02-1');
      await createBtn.click();
      const pd = page.getByRole('dialog').filter({ hasText: '创建项目' });
      await visual.type(pd.locator('#project_no'), state.projectNo);
      await visual.type(pd.locator('#name'), `演示 智能装配单机 ${state.suffix}`);
      await choose(page, pd, 'dept_id', '研发部门');
      await pd.locator('#start_date').fill('2026-09-01');
      await pd.locator('#end_date').fill('2027-03-31');
      const created = page.waitForResponse(r => r.url().endsWith('/api/pms/projects') && r.request().method() === 'POST');
      await pd.getByRole('button', { name: '保存项目' }).click();
      const project = (await (await created).json()).data;
      state.id = project.project_id;
      await expect(pd).toBeHidden();
      await expect(page.locator('tbody tr').filter({ hasText: state.projectNo }).first()).toBeVisible();

      await open(page, state.id);
      await beat('02-2');
      await drawer(page).getByRole('button', { name: '应用项目模板' }).click();
      await choose(page, modal(page, '应用项目模板'), 'template_id', '单机设备订单项目');
      await fill(modal(page, '应用项目模板'), { reason: '演示: 按单机设备订单模板建网' });
      await save(page, '应用项目模板');
      await expect(drawer(page).getByText('结构节点 3')).toBeVisible();
      await beat('02-3');
      await expect(drawer(page).getByText(/Gate模板\s*8/)).toBeVisible();
    });

    // ── 章节间预置: 审核人加入项目成员 (读取与指定审批的前提, 不入镜) ──
    await api(sp, 'POST', base(state.id) + '/members', { user_id: state.who.userId, role: 'viewer' });

    // ── 03 需求与审批 (界面完成双人审批) ──
    await runScene(browser, '03', async ({ page, beat }) => {
      await open(page, state.id, '需求与治理', 'URS与追踪');
      await beat('03-1');
      await drawer(page).getByRole('button', { name: '新增URS需求', exact: true }).click();
      const urs = modal(page, '新增URS需求');
      await fill(urs, { code: 'URS-01', text: '工站节拍不超过 60 秒' });
      await choose(page, urs, 'owner_id', '/ admin');
      await choose(page, urs, 'verification_method', '测试');
      await save(page, '新增URS需求');
      const gov = await api(page, 'GET', base(state.id) + '/governance');
      state.requirement = gov.requirements.find(r => r.code === 'URS-01');
      state.evidence = (await mutate(page, state.id, '/governance/documents', {
        code: 'DEMO-EV', title: '工程验收记录', filename: 'acceptance-record.txt',
        content: '演示证据: 实测记录, 检验与签收记录用于本地演示.',
      })).result;
      const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'showcase-'));
      const pdfPath = path.join(tmp, `design-${state.suffix}.pdf`);
      fs.writeFileSync(pdfPath, makePdf(`Design review ${state.suffix}`));
      await open(page, state.id, '需求与治理', '证据版本'); // 重新打开以刷新项目版本 (证据经 HTTP 建立后抽屉版本会过期)
      await drawer(page).getByRole('button', { name: '上传证据文件' }).click();
      const upload = modal(page, '上传证据文件');
      await fill(upload, { code: 'DR-01', title: '主机设计评审报告' });
      await choose(page, upload, 'classification', '机密');
      await choose(page, upload, 'category', '设计');
      await upload.locator('#document_file').setInputFiles(pdfPath);
      const uploaded = page.waitForResponse(r => r.url().includes('/documents/upload') && r.request().method() === 'POST');
      await upload.getByRole('button', { name: /^上\s*传$/ }).click();
      const upBody = await (await uploaded).json();
      expect(upBody.code, `上传证据: ${upBody.msg}`).toBe(200);
      await expect(upload).toBeHidden();
      await page.waitForLoadState('networkidle');
      await expect(row(page, 'DR-01').getByText('机密')).toBeVisible();

      await tab(page, '章程');
      await beat('03-2');
      await drawer(page).getByRole('button', { name: '编制项目章程', exact: true }).click();
      await fill(modal(page, '编制项目章程'), {
        title: '智能装配单机项目章程', objective: '按合同交付一台智能装配单机并通过 SAT',
        scope: '主机单元 (含主机#1) 与附件单元的设计, 制造, 测试, 发运与现场验收',
        success_criteria: 'FAT/SAT 一次通过, 决算不超预算',
      });
      await choose(page, modal(page, '编制项目章程'), 'sponsor_id', '/ admin');
      await save(page, '编制项目章程');
      await row(page, '智能装配单机项目章程').getByRole('button', { name: '提交审批', exact: true }).click();
      await choose(page, modal(page, '提交独立审批'), 'reviewer_id', state.who.username);
      await save(page, '提交独立审批');

      // 切换到审核人账号: 从我的待办进入并批准 (提交人不能自审).
      await page.keyboard.press('Escape'); // 先关项目抽屉, 否则遮住右上角用户菜单
      await expect(drawer(page)).toBeHidden();
      await page.locator('.ant-layout-header .header-user').click();
      await page.getByText('退出登录').click();
      // 等退出真正落地 (re-frame dispatch 异步, 立即 goto 会中断清令牌事件).
      await page.getByPlaceholder('用户名').waitFor({ timeout: 15000 });
      await login(page, state.who.username, state.who.password);
      await page.goto('/pms/todo');
      await expect(page.getByRole('heading', { name: '我的待办' })).toBeVisible();
      // 待办数据到位后再记台词起点, 避免截到加载中的空页.
      const flowRow = page.locator('tbody tr:visible').filter({ hasText: '智能装配单机项目章程' }).first();
      await expect(flowRow).toBeVisible({ timeout: 20000 });
      await expect(page.locator('.ant-spin-spinning')).toHaveCount(0, { timeout: 20000 }); // 等表格加载态结束再记台词起点
      await beat('03-3');
      // 待办审批表直接通过; 否则从待办事项进入项目章程页批准 (审核人有项目成员读取权).
      const decideBtn = flowRow.getByRole('button', { name: '审批', exact: true });
      if (await decideBtn.count()) {
        await decideBtn.click();
        const decide = page.getByRole('dialog').filter({ has: page.getByRole('button', { name: '通过', exact: true }) }).last();
        await expect(decide).toBeVisible();
        await decide.getByRole('button', { name: '通过', exact: true }).click();
        await expect(decide).toBeHidden();
      } else {
        await flowRow.getByRole('button', { name: /智能装配单机/ }).click();
        await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
        await tab(page, '需求与治理');
        await tab(page, '章程');
        await row(page, '智能装配单机项目章程').getByRole('button', { name: '批准', exact: true }).click();
        await fill(modal(page, '批准评审'), { reason: '独立审核: 目标/范围/成功标准与合同一致' });
        await save(page, '批准评审');
      }
      await expect(page.locator('.ant-layout-header')).toBeVisible();
    });

    // ── 章节间预置: 三个执行准入 Gate + 生命周期推进 (真实 HTTP, 全部带 version) ──
    await passGateHttp(sp, 'requirement-confirm', '需求确认 Gate 检查');
    await passGateHttp(sp, 'host-summary', '主机汇总 Gate 检查');
    await passGateHttp(sp, 'attachment-summary', '附件汇总 Gate 检查');
    await transitionUi(sp, state.id, '确认立项', '确认立项');
    await transitionUi(sp, state.id, '进入计划', '进入计划阶段');

    // ── 04 计划与基线 ──
    await runScene(browser, '04', async ({ page, beat }) => {
      await open(page, state.id, '计划与执行', '进度卷积');
      await beat('04-1');
      await drawer(page).getByRole('button', { name: '派生主/子/单机计划', exact: true }).click();
      await fill(modal(page, '派生主/子/单机计划'), { reason: '演示: 按模板阶段层级派生' });
      const derived = (await save(page, '派生主/子/单机计划')).data.result;
      expect(derived).toMatchObject({ node_count: 3, main_stage_count: 5, derived_count: 15 });
      await open(page, state.id, '计划与执行', 'WBS与排程');
      await expect(drawer(page).getByRole('cell', { name: '模板派生', exact: true }).first()).toBeVisible();

      // 拉长单机装配工期 (真实 HTTP), 回到进度卷积看主子冲突.
      const plan = await api(page, 'GET', base(state.id) + '/planning');
      const m1s4 = plan.tasks.find(t => t.wbs_code === `${state.projectNo}-M1-S4`);
      await mutate(page, state.id, `/tasks/${m1s4.task_id}`, { duration_days: 60 }, 200, 'PUT');
      await open(page, state.id, '计划与执行', '进度卷积');
      await beat('04-2');
      await expect(drawer(page).getByText('主子约束冲突 1', { exact: true })).toBeVisible();

      await open(page, state.id, '计划与执行', '审批与基线');
      await drawer(page).getByRole('button', { name: '提交计划审批', exact: true }).click();
      await fill(modal(page, '提交计划审批'), { comment: '主/子/单机计划已派生并评审' });
      await save(page, '提交计划审批');
      // 审核人独立批准 (真实 HTTP: /planning/baselines/:id/review {version, decision, comment}), 结果在界面复核.
      const planning = await asReviewer(page, 'GET', base(state.id) + '/planning');
      const submitted = (planning.baselines || []).find(b => b.status === 'submitted');
      expect(submitted, '待审批的计划基线').toBeTruthy();
      await asReviewer(page, 'POST', base(state.id) + `/planning/baselines/${submitted.baseline_id || submitted.id}/review`,
        { decision: 'approved', comment: '独立批准: 主子计划一致' });
      await open(page, state.id, '计划与执行', '审批与基线');
      await beat('04-3');
      await expect(drawer(page).getByText('已批准').first()).toBeVisible();
    });

    // ── 章节间预置: 备料 -> BOM 冻结齐套 -> 装配记录 (真实 HTTP; 装配建立要求 BOM 已 ready) ──
    {
      const plan = await api(sp, 'GET', base(state.id) + '/planning');
      const m1s4 = plan.tasks.find(t => t.wbs_code === `${state.projectNo}-M1-S4`);
      const mainNode = plan.nodes.find(n => n.name === '主机#1');
      expect(mainNode, '结构节点 主机#1').toBeTruthy();
      state.m1s4TaskId = m1s4.task_id;
      const refs = { task_id: m1s4.task_id, requirement_ids: [state.requirement.id] };
      state.refs = refs;
      const material = (await mutate(sp, state.id, '/delivery/material-requests', {
        code: 'MR-01', title: '主机长周期件备料', needed_on: daysAgo(10), request_type: 'long_lead',
        node_id: mainNode.node_id, owner_id: 1, ...refs,
        items: [{ code: 'SRV-01', name: '伺服电机', quantity: 2, unit: '件' }, { code: 'PLC-01', name: 'PLC 控制器', quantity: 1, unit: '件' }],
      })).result;
      await approveDelivery(sp, 'material-requests', material);
      const bom = (await mutate(sp, state.id, '/delivery/boms', { code: 'BOM-01', title: '主机#1 冻结配置', material_request_id: material.id })).result;
      await approveDelivery(sp, 'boms', bom, 'freeze');
      state.bomId = bom.id;
      // 齐套与装配记录要求项目已进入执行, 在场景05 进入执行之后建立.
    }

    // ── 05 执行与交付 ──
    await runScene(browser, '05', async ({ page, beat }) => {
      await open(page, state.id, '工程交付');
      await beat('05-1');
      await drawer(page).getByRole('button', { name: '配置交付要求' }).click();
      const cfg = modal(page, '配置交付验收要求');
      await choose(page, cfg, 'required_stages', ['备料与BOM', '装配交检', '质量试验', '发运签收']);
      await choose(page, cfg, 'required_test_types', ['SIT', 'FAT', 'SAT']);
      await cfg.locator('#required_survey_visits').fill('0'); // 工勘不要求, 避免虚构现场记录
      await choose(page, cfg, 'pre_ship_conditions', '入库/装箱已确认');
      await cfg.locator('#handover_deadline_days').fill('2');
      await choose(page, cfg, 'handover_required', '仅提示');
      await cfg.locator('#site_lag_days').fill('1');
      await fill(cfg, { reason: '演示: 现场交付要求' });
      await save(page, '配置交付验收要求');
      await transitionUi(page, state.id, '进入执行', '进入执行阶段');
      await expect(drawer(page).getByText('执行中', { exact: true }).first()).toBeVisible();

      // 齐套与装配记录要求执行阶段, 在进入执行后以真实 HTTP 建立 (不入镜).
      await mutate(page, state.id, `/delivery/boms/${state.bomId}/kit`, { items: [{ code: 'SRV-01', available_quantity: 2 }, { code: 'PLC-01', available_quantity: 1 }], evidence_ids: [state.evidence.id] });
      const assembly = (await mutate(page, state.id, '/delivery/assemblies', { ...state.refs, code: 'ASM-01', title: '主机#1 装配', bom_id: state.bomId, owner_id: 1 })).result;
      state.assemblyId = assembly.id;

      // 齐套 Gate 未放行 -> 装配开工被系统阻断 (界面真实报错).
      await open(page, state.id, '工程交付', '装配交检');
      await beat('05-2');
      await row(page, 'ASM-01').getByRole('button', { name: '登记开工' }).click();
      const startDlg = modal(page, '登记装配开工');
      await choose(page, startDlg, 'evidence_ids', '工程验收记录');
      await startDlg.getByRole('button', { name: /^保\s*存$/ }).click();
      await expect(page.getByText(/阻断关口尚未通过/).first()).toBeVisible({ timeout: 15000 });
      await startDlg.press('Escape');

      // 齐套 Gate 放行 (真实 HTTP) -> 界面复核开工成功.
      await passGateHttp(page, 'kitting', '零件齐套放行');
      await open(page, state.id, '工程交付', '装配交检');
      await row(page, 'ASM-01').getByRole('button', { name: '登记开工' }).click();
      const startOk = modal(page, '登记装配开工');
      await choose(page, startOk, 'evidence_ids', '工程验收记录');
      await save(page, '登记装配开工');
      await expect(row(page, 'ASM-01').getByText(/进行中|开工|执行中/).first()).toBeVisible();

      // 装配交检通过 + 装配与测试交接 Gate (G5) 放行 (真实 HTTP), 打开 SIT 结果录入前提.
      await approveDelivery(page, 'assemblies', { id: state.assemblyId });
      await passGateHttp(page, 'assembly-test-handover', '装配与测试交接检查');

      // SIT 试验界面建立, 结果与批准经真实 HTTP; 界面复核.
      await open(page, state.id, '工程交付', '质量试验');
      await beat('05-3');
      await drawer(page).getByRole('button', { name: '建立质量试验', exact: true }).click();
      const sit = modal(page, '建立质量试验');
      await fill(sit, { code: 'SIT-01', title: '主机#1 SIT', criteria_0_title: '节拍不超过 60 秒且安全联锁有效' });
      await choose(page, sit, 'test_type', 'SIT');
      await choose(page, sit, 'assembly_id', '主机#1 装配');
      await choose(page, sit, 'owner_id', '/ admin');
      await choose(page, sit, 'task_id', '装配调试');
      await choose(page, sit, 'requirement_ids', 'URS-01');
      const sitRecord = (await save(page, '建立质量试验')).data.result;
      await mutate(page, state.id, `/delivery/tests/${sitRecord.id}/results`, {
        checks: [{ code: 'C01', passed: true, actual: '实测节拍 52 秒, 联锁有效', evidence_ids: [state.evidence.id] }], due_date: '2026-10-15',
      });
      await approveDelivery(page, 'tests', sitRecord);
      const fat = (await mutate(page, state.id, '/delivery/tests', {
        task_id: m1s4TaskId(), requirement_ids: [state.requirement.id], code: 'FAT-01', title: '主机#1 FAT',
        assembly_id: state.assemblyId, test_type: 'FAT', owner_id: 1,
        criteria: [{ code: 'F1', title: '客户见证 8 小时连续运行', required: true }],
      })).result;
      await mutate(page, state.id, `/delivery/tests/${fat.id}/results`, {
        checks: [{ code: 'F1', passed: true, actual: '连续运行 8h 无故障', evidence_ids: [state.evidence.id] }], due_date: '2026-10-15',
      });
      await approveDelivery(page, 'tests', fat);
      await open(page, state.id, '工程交付', '质量试验');
      await expect(row(page, 'FAT-01').getByText('已批准')).toBeVisible();

      // 发运: 发货前条件界面确认, 放行/发运/签收经真实 HTTP, 界面复核闭环.
      const shipment = (await mutate(page, state.id, '/delivery/shipments', {
        task_id: m1s4TaskId(), requirement_ids: [state.requirement.id], code: 'SHIP-01', title: '主机#1 发运',
        assembly_ids: [state.assemblyId], consignee: '客户设备部', delivery_address: '客户厂区 A 车间',
        planned_date: daysAgo(3), reviewer_id: state.who.userId,
      })).result;
      await open(page, state.id, '工程交付', '发运签收');
      await beat('05-4');
      await row(page, 'SHIP-01').getByRole('button', { name: '发货前条件' }).click();
      const cond = modal(page, '确认发货前条件');
      await choose(page, cond, 'warehouse_in_confirmed', '已确认');
      await fill(cond, { warehouse_note: 'WMS 入库单 IN-0921' });
      await save(page, '确认发货前条件');
      await passGateHttp(page, 'fat-confirm', 'FAT 确认放行');
      await approveDelivery(page, 'shipments', shipment);
      await mutate(page, state.id, `/delivery/shipments/${shipment.id}/dispatch`, { shipped_on: daysAgo(3), tracking_no: 'DEMO-TRACK-001', evidence_ids: [state.evidence.id] });
      await asReviewer(page, 'POST', base(state.id) + `/delivery/shipments/${shipment.id}/receipt`, { received_on: daysAgo(2), receiver_name: '客户设备部经理', acceptance: 'accepted', evidence_ids: [state.evidence.id] });
      await open(page, state.id, '工程交付', '发运签收');
      await expect(row(page, 'SHIP-01').getByText('已完整接受')).toBeVisible();
    });

    // ── 章节间预置: 四算四版 (真实 HTTP, 独立批准) ──
    for (const [kind, name, amount] of [['estimate', '合同概算', '3000'], ['budget', '执行预算', '3200'], ['actual', '期间核算', '3100'], ['settlement', '项目决算', '3150']]) {
      const v = (await mutate(sp, state.id, '/cost-versions', { kind, period: '2026-09', currency: 'CNY', name, revenue: '10000', reviewer_id: state.who.userId })).result;
      await mutate(sp, state.id, `/cost-versions/${v.id}/entries`, { category: 'material', label: '材料与外购件', amount });
      await mutate(sp, state.id, `/cost-versions/${v.id}/submit`, {});
      await asReviewer(sp, 'POST', base(state.id) + `/cost-versions/${v.id}/review`, { decision: 'approved', reason: '独立审批' });
    }

    // ── 06 进度与财务 ──
    await runScene(browser, '06', async ({ page, beat }) => {
      await open(page, state.id, '计划与执行', 'WBS与排程');
      await beat('06-1');
      await row(page, 'S1-MAIN').getByRole('button', { name: '反馈进度', exact: true }).click();
      const fb = modal(page, '反馈任务进度');
      await choose(page, fb, 'status', '已完成');
      await fill(fb, { percent_complete: 100, remaining_days: 0, actual_start: '2026-09-01', actual_end: '2026-09-12', comment: '设计准备实际完成' });
      await save(page, '反馈任务进度');
      // 定时扫描 + 手动快照 -> 挣值与完工预测 (与已验证 demo-flow 相同的顺序).
      await api(page, 'PUT', '/api/system/job/9001/run');
      await expect.poll(async () => (await api(page, 'GET', base(state.id) + '/planning')).progress_history.length,
        { timeout: 60000, intervals: [1000, 2000, 3000] }).toBeGreaterThan(0);
      await open(page, state.id, '计划与执行', '进度卷积');
      await drawer(page).getByRole('button', { name: '生成进度快照', exact: true }).click();
      await save(page, '生成进度快照');
      await expect(drawer(page).getByText('挣值与完工预测')).toBeVisible();

      await open(page, state.id, '实际工时');
      await beat('06-2');
      await drawer(page).getByRole('button', { name: '提交实际工时', exact: true }).click();
      const te = modal(page, '提交实际工时');
      await fill(te, { work_date: '2026-09-08', hours: '2', note: '设计准备评审' });
      await choose(page, te, 'task_id', '设计准备 (主计划)');
      await choose(page, te, 'reviewer_id', state.who.username);
      await save(page, '提交实际工时');
      // 审核人批准工时 (真实 HTTP: /time-entries/:id/review {version, decision, reason}).
      const times = await asReviewer(page, 'GET', base(state.id) + '/time-entries');
      const pendingTime = (times.rows || times.entries || []).find(t => (t.status === 'pending' || t.status === 'submitted'));
      expect(pendingTime, '待审核工时单').toBeTruthy();
      await asReviewer(page, 'POST', base(state.id) + `/time-entries/${pendingTime.entry_id || pendingTime.id}/review`,
        { decision: 'approved', reason: '核对工作日与任务, 同意' });
      await open(page, state.id, '实际工时');
      await expect(row(page, '2026-09-08').getByText('已批准')).toBeVisible();

      await open(page, state.id, '项目费用');
      await beat('06-3');
      await expect(drawer(page).getByText('四算拉通')).toBeVisible();
      await expect(drawer(page).getByText(/预算-概算/)).toBeVisible();
    });

    // ── 07 看板与检索 ──
    await runScene(browser, '07', async ({ page, beat }) => {
      await page.goto('/pms/portfolio');
      await expect(page.getByRole('heading', { name: '项目组合看板' })).toBeVisible();
      const prow = page.locator('tbody tr').filter({ hasText: state.projectNo }).first();
      await expect(prow).toBeVisible();
      await prow.locator('.ant-table-row-expand-icon').click();
      await beat('07-1');
      await expect(page.locator('.ant-table-expanded-row').first()).toBeVisible(); // 展开行是行的兄弟节点

      await page.goto('/pms/search');
      await expect(page.getByPlaceholder('输入关键字')).toBeVisible();
      await beat('07-2');
      await page.getByPlaceholder('输入关键字').fill('智能装配单机');
      await page.getByRole('button', { name: /^检\s*索$/ }).click();
      await expect(page.getByText(/检索结果 \d+/)).toBeVisible({ timeout: 30000 });

      await page.goto('/pms/dashboard');
      await expect(page.getByRole('heading', { name: '项目驾驶舱' }).or(page.getByText('项目总数')).first()).toBeVisible();
      await beat('07-3');
      await expect(page.getByText('已逾期').first()).toBeVisible();
    });

    // ── 章节间预置: 叶任务完成 + SAT 批准 + SAT 确认 Gate (真实 HTTP) ──
    {
      const planNow = await api(sp, 'GET', base(state.id) + '/planning');
      for (const t of planNow.tasks.filter(t => t.task_type !== 'summary' && t.status !== 'done')) {
        await mutate(sp, state.id, `/tasks/${t.task_id}/feedback`, { status: 'done', percent_complete: 100, remaining_days: 0, comment: '演示: 按计划完成' });
      }
      const sat = (await mutate(sp, state.id, '/delivery/tests', {
        task_id: m1s4TaskId(), code: 'SAT-01', title: '主机#1 SAT', assembly_id: state.assemblyId, test_type: 'SAT', owner_id: 1,
        requirement_ids: [state.requirement.id], criteria: [{ code: 'S1', title: '现场连续生产 4 小时并由客户确认', required: true }],
      })).result;
      await mutate(sp, state.id, `/delivery/tests/${sat.id}/results`, {
        checks: [{ code: 'S1', passed: true, actual: '客户签字确认', evidence_ids: [state.evidence.id] }], due_date: '2026-10-15',
      });
      await approveDelivery(sp, 'tests', sat);
      await passGateHttp(sp, 'sat-confirm', 'SAT 确认 Gate 检查');
    }

    // ── 08 收尾与关闭 ──
    await runScene(browser, '08', async ({ page, beat }) => {
      await open(page, state.id);
      await beat('08-1');
      await drawer(page).getByRole('button', { name: '进入收尾' }).click();
      const closeIn = modal(page, '进入收尾阶段');
      await closeIn.getByRole('button', { name: '进入收尾阶段', exact: true }).click();
      await expect(closeIn).toBeHidden();
      await expect(drawer(page).getByText('收尾中', { exact: true }).first()).toBeVisible();

      await open(page, state.id, '结项与移交');
      await beat('08-2');
      // 就绪度表的"具体缺口"单元格也含这些标题, 行必须限定为带确认完成按钮的清单行.
      const checklistRow = title => drawer(page).locator('tbody tr:visible')
        .filter({ has: page.getByRole('button', { name: '确认完成', exact: true }) })
        .filter({ hasText: title }).first();
      for (const title of ['交付物清单归档', '四算决算已批准', '遗留问题移交安排']) {
        await checklistRow(title).getByRole('button', { name: '确认完成', exact: true }).click();
        const done = modal(page, '完成收尾检查');
        await choose(page, done, 'evidence_ref', '工程验收记录');
        await fill(done, { comment: `已核对: ${title}` });
        await save(page, '完成收尾检查');
      }
      await expect(page.getByText('当前结项前置检查已通过.', { exact: true })).toBeVisible(); // 提交前确认无阻塞
      await drawer(page).getByRole('button', { name: '提交关闭审批', exact: true }).click();
      await choose(page, modal(page, '提交项目关闭审批'), 'reviewer_id', state.who.username);
      await save(page, '提交项目关闭审批');
      // 审核人批准关闭 (真实 HTTP: /closure/review {version, decision, reason}), 界面复核后正式关闭.
      await asReviewer(page, 'POST', base(state.id) + '/closure/review',
        { decision: 'approved', reason: '交付, 质量, 工时, 决算与清单已独立核对' });
      await open(page, state.id);
      await beat('08-3');
      await drawer(page).getByRole('button', { name: '正式关闭' }).click();
      const fin = modal(page, '正式关闭项目');
      await fin.getByRole('button', { name: '正式关闭项目', exact: true }).click();
      await expect(fin).toBeHidden();
      await expect(drawer(page).getByText('项目已结束,当前为只读视图', { exact: true })).toBeVisible();
    });

    fs.writeFileSync(path.join(OUT, 'marks.json'), JSON.stringify(sceneMarks, null, 2));
    console.log(`marks -> ${path.join(OUT, 'marks.json')}`);
    await setup.close();
  });
});

// 主计划 S4 (装配调试) 任务 id: 交付记录挂接用, 在场景04派生后可用.
function m1s4TaskId() {
  return state.m1s4TaskId;
}
