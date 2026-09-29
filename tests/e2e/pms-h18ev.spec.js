const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H18/C03 延伸: 追踪链只读标注其所引用证据文档"业务编码最新版本"是否已被受控作废(discarded) (免迁移, 无新命令/新kind/新状态).
// 界面登记一条声明验证方式(测试)的需求 -> 真实HTTP建证据文档 v1 并挂一条 verifies 追踪指向 v1 ->
//   追踪矩阵"证据发布"列与 URS"验证证据"列均无作废标注;
//   真实HTTP新增不可变修订 v2(同编号)后作废 v2 -> 因追踪指向旧 v1(不受引用守卫拦截), 该编号最新版本现为 discarded ->
//   重载后两列各追加红色"证据已作废"标注, 而追踪自身所指向的 v1 发布口径仍为待发布不漂移;
//   真实HTTP受控恢复 v2 -> 标注回落消失. 全程真实点击+真实HTTP+真实浏览器截图, 只读派生不门控不改不可变版本.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h18ev');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const reqCard = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: 'URS 需求版本', exact: true }) });
const reqRow = (page, code) => reqCard(page).locator('tbody tr:visible').filter({ hasText: code }).first();
const traceCard = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '需求追踪矩阵', exact: true }) });
const base = id => `/api/pms/projects/${id}`;

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

async function command(page, id, suffix, data = {}) {
  const project = await api(page, 'GET', base(id));
  const result = await api(page, 'POST', base(id) + '/governance' + suffix, { ...data, version: project.version });
  return result.result;
}

async function tab(page, name) {
  await drawer(page).getByRole('tab', { name, exact: true }).click();
  await page.waitForLoadState('networkidle');
}

async function open(page, id, section, subsection) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  if (section) await tab(page, section);
  if (subsection) await tab(page, subsection);
}

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  if (typeof text === 'string' && await input.isEditable()) await input.fill(text);
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await form.locator(`#${key}`).press('Escape');
  await page.waitForLoadState('networkidle');
}

async function save(page, title) {
  const form = modal(page, title);
  const response = page.waitForResponse(r => r.url().includes('/api/pms/') && ['POST', 'PUT', 'DELETE'].includes(r.request().method()));
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(200);
  await expect(form).toBeHidden();
  await page.waitForLoadState('networkidle');
  return body.data;
}

async function fillRequirement(page, { code, text, methodLabel }) {
  await drawer(page).getByRole('button', { name: '新增URS需求', exact: true }).click();
  const form = modal(page, '新增URS需求');
  await form.locator('#code').fill(code);
  await form.locator('#text').fill(text);
  await form.locator('#category').fill('功能');
  await choose(page, form, 'owner_id', 'admin');
  if (methodLabel) await choose(page, form, 'verification_method', methodLabel);
}

async function shotCard(page, card, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.describe('H18/C03 追踪证据作废只读标注浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('证据编码最新版本被作废 -> 追踪矩阵与URS列追加"证据已作废", 恢复后消失', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H18EV-${suffix}`, name: `证据作废标注验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    await open(page, id, '需求与治理', 'URS与追踪');

    // 1) 界面登记一条声明验证方式"测试"的需求.
    const codeR = `URS-EV-${suffix}`;
    await fillRequirement(page, { code: codeR, text: '控制器固件须支持远程升级并通过回滚校验.', methodLabel: '测试' });
    await save(page, '新增URS需求');

    // 2) 真实HTTP: 建证据文档 v1(已登记未发布) 并挂一条 verifies 追踪指向 v1.
    const gov0 = await api(page, 'GET', base(id) + '/governance');
    const reqR = gov0.requirements.find(r => r.code === codeR);
    const codeD = `DOC-${suffix}`;
    const docV1 = await command(page, id, '/documents', { code: codeD, title: `远程升级验证记录-${suffix}`, filename: '验证.txt', content: '已执行远程升级回滚测试\n' });
    await command(page, id, '/traces', { requirement_id: reqR.id, target_kind: 'document', target_id: docV1.id, relation: 'verifies' });

    // 3) 重载: 最新版本未作废 -> 两列均无"证据已作废"标注, 追踪仍显"待发布"/URS显"证据待发布".
    await open(page, id, '需求与治理', 'URS与追踪');
    await expect(traceCard(page).getByRole('columnheader', { name: '证据发布', exact: true })).toBeVisible();
    await expect(reqRow(page, codeR).getByText('证据待发布', { exact: true })).toBeVisible();
    await expect(reqRow(page, codeR).getByText('证据已作废', { exact: true })).toHaveCount(0);
    await expect(traceCard(page).getByText('证据已作废', { exact: true })).toHaveCount(0);
    await shotCard(page, reqCard(page), 'h18ev-1-urs-clean.png');
    await shotCard(page, traceCard(page), 'h18ev-2-trace-clean.png');

    // 4) 真实HTTP回显: 未作废 -> evidence_voided / verification_evidence_voided 均为 false.
    const gov1 = await api(page, 'GET', base(id) + '/governance');
    const trace1 = gov1.traces.find(t => t.target_id === docV1.id);
    const req1 = gov1.requirements.find(r => r.code === codeR);
    expect(trace1.evidence_voided, '未作废时追踪不标注').toBe(false);
    expect(req1.verification_evidence_voided, '未作废时URS不标注').toBe(false);

    // 5) 真实HTTP: 新增不可变修订 v2(同编号) 后作废 v2. 追踪指向旧 v1, 不构成对 v2 的引用 -> 守卫放行.
    const docV2 = await command(page, id, `/documents/${docV1.id}/revisions`, { code: codeD, title: `远程升级验证记录-${suffix}`, filename: '验证v2.txt', content: '第二版正文\n' });
    expect(docV2.revision, '修订递增').toBe(2);
    await command(page, id, `/documents/${docV2.id}/discard`, { reason: '上传错误版本作废' });

    // 6) 重载: 该编号最新版本被作废 -> 追踪"证据发布"列与URS"验证证据"列各追加红色"证据已发布"之外的"证据已作废".
    await open(page, id, '需求与治理', 'URS与追踪');
    await expect(reqRow(page, codeR).getByText('证据已作废', { exact: true })).toBeVisible();
    await expect(reqRow(page, codeR).getByText('证据待发布', { exact: true })).toBeVisible();
    await expect(traceCard(page).getByText('证据已作废', { exact: true })).toBeVisible();
    await shotCard(page, reqCard(page), 'h18ev-3-urs-voided.png');
    await shotCard(page, traceCard(page), 'h18ev-4-trace-voided.png');

    // 7) 真实HTTP回显: 追踪仍指向 v1(其发布口径 registered 不漂移) 但 evidence_voided 翻 true; URS 同步 true.
    const gov2 = await api(page, 'GET', base(id) + '/governance');
    const trace2 = gov2.traces.find(t => t.target_id === docV1.id);
    const req2 = gov2.requirements.find(r => r.code === codeR);
    expect(trace2.evidence_voided, '编码最新版本作废 -> 追踪标注 true').toBe(true);
    expect(trace2.evidence_status, '追踪所指向的 v1 状态不漂移').toBe('registered');
    expect(trace2.evidence_release_state, '所指向版本发布口径不漂移').toBe('pending');
    expect(req2.verification_evidence_voided, 'URS 验证证据标注 true').toBe(true);
    expect(req2.verification_evidence_state, 'URS 验证证据发布口径不漂移').toBe('pending');
    expect(req2.code, '需求编号不漂移').toBe(codeR);

    // 8) 真实HTTP: 受控恢复 v2 -> 该编号最新版本回到非作废 -> 标注回落消失.
    await command(page, id, `/documents/${docV2.id}/restore`, { reason: '误作废恢复' });
    await open(page, id, '需求与治理', 'URS与追踪');
    await expect(reqRow(page, codeR).getByText('证据已作废', { exact: true })).toHaveCount(0);
    await expect(traceCard(page).getByText('证据已作废', { exact: true })).toHaveCount(0);
    await shotCard(page, reqCard(page), 'h18ev-5-urs-restored.png');

    const gov3 = await api(page, 'GET', base(id) + '/governance');
    const trace3 = gov3.traces.find(t => t.target_id === docV1.id);
    const req3 = gov3.requirements.find(r => r.code === codeR);
    expect(trace3.evidence_voided, '恢复后追踪标注回落 false').toBe(false);
    expect(req3.verification_evidence_voided, '恢复后URS标注回落 false').toBe(false);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
