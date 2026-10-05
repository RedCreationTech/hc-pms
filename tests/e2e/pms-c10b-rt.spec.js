const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C10b 自定义风险模板 (可复用 CRUD + 实例化复用 H08 超阈值自动升级门控 + 受控作废):
//   写路径 — "新建自定义风险模板" 对话框固化 标题/概率x影响/应对措施/可选类别与适用阶段, 落 pms_risk_template 独立表 (非治理 kind, 免动 CHECK(kind IN 14));
//   台账 — "升级预判" 列按评分分层 (<16 低于升级阈值 / >=16 实例化将超阈值升级 / >=20 实例化将升级至决策层);
//   编辑 — "编辑自定义风险模板" 改评分, 只影响此后实例化, 不追溯历史风险;
//   实例化 — "从自定义模板实例化" 复用 insert-risk! 按模板概率x影响自动评分, 高模板(4x5=20)自动进入超阈值升级待独立确认门控 (escalated/steering), source_key=custom:<id>;
//   作废 — "作废风险模板" 软置 discarded 从有效列表移除; 作废后实例化经真实 HTTP 返回 404.
//   门控翻转的"确认升级处置"须独立第二审批人上下文, 按 Rule B 不进 B 列, 此处只保留单上下文可见事实.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c10b-rt');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const templatePanel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '自定义风险模板', exact: true }) }).first();
const riskPanel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '项目风险', exact: true }) }).first();
const templateRow = (page, text) => templatePanel(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const riskRow = (page, text) => riskPanel(page).locator('tbody tr:visible').filter({ hasText: text }).first();

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

async function fill(form, values) {
  for (const [key, value] of Object.entries(values)) await form.locator(`#${key}`).fill(String(value));
}

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  if (typeof text === 'string' && await input.isEditable()) await input.fill(text);
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${list}"]`) });
  const option = dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first();
  await expect(option).toBeVisible();
  await option.click();
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

async function newTemplate(page, id, { title, probability, impact, mitigation, category, stage }) {
  await open(page, id, '需求与治理', '风险与问题');
  await drawer(page).getByRole('button', { name: '新建自定义风险模板', exact: true }).click();
  const form = modal(page, '新建自定义风险模板');
  await fill(form, { title, probability, impact, mitigation });
  if (category) await choose(page, form, 'category', category);
  if (stage) await form.locator('#stage').fill(stage);
  return await save(page, '新建自定义风险模板');
}

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function shotSection(page, el, file) {
  await el.scrollIntoViewIfNeeded();
  await page.waitForLoadState('networkidle');
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.describe('C10b 自定义风险模板 CRUD + 实例化升级门控 + 受控作废', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('建模板 -> 升级预判分层 -> 编辑改分 -> 实例化高风险自动升级并标来源 -> 作废移除, 非法/作废 HTTP 拒绝', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `C10B-${suffix}`, name: `自定义风险模板验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    const lowTitle = `低分模板-${suffix}`;
    const midTitle = `中分模板-${suffix}`;
    const highTitle = `高分供应商断供模板-${suffix}`;

    // 1. 建三份模板: low 3x2=6 / mid 4x4=16 / high 4x5=20, high 带类别与适用阶段.
    const low = (await newTemplate(page, id, { title: lowTitle, probability: 3, impact: 2, mitigation: '提前排产即可消化.' })).result;
    const mid = (await newTemplate(page, id, { title: midTitle, probability: 4, impact: 4, mitigation: '增加冗余设计与备选件.' })).result;
    const high = (await newTemplate(page, id, { title: highTitle, probability: 4, impact: 5, mitigation: '锁定双供应商并签交期违约条款.', category: '技术', stage: '采购' })).result;
    expect(low.score, '低模板评分按概率x影响').toBe(6);
    expect(mid.score).toBe(16);
    expect(high.score).toBe(20);
    expect(high.category).toBe('technical');
    expect(high.stage).toBe('采购');

    // 2. 台账 "升级预判" 三档分层可见.
    await open(page, id, '需求与治理', '风险与问题');
    await templatePanel(page).scrollIntoViewIfNeeded();
    await expect(templateRow(page, lowTitle).getByText('低于升级阈值')).toBeVisible();
    await expect(templateRow(page, midTitle).getByText('实例化将超阈值升级')).toBeVisible();
    await expect(templateRow(page, highTitle).getByText('实例化将升级至决策层')).toBeVisible();
    await expect(templateRow(page, highTitle).getByText('技术')).toBeVisible();
    await shotSection(page, templatePanel(page), 'c10b-1-tiers.png');
    await shot(page, 'c10b-1-ledger.png');

    // 3. 编辑 low 模板把评分抬到 5x4=20, 升级预判即时翻到 "决策层".
    await templateRow(page, lowTitle).getByRole('button', { name: '编辑', exact: true }).click();
    const editForm = modal(page, '编辑自定义风险模板');
    await fill(editForm, { probability: 5, impact: 4 });
    const edited = await save(page, '编辑自定义风险模板');
    expect(edited.result.score, '编辑后评分重算').toBe(20);
    await open(page, id, '需求与治理', '风险与问题');
    await expect(templateRow(page, lowTitle).getByText('实例化将升级至决策层')).toBeVisible();
    await shotSection(page, templatePanel(page), 'c10b-2-edited.png');

    // 4. 实例化 high 模板 -> 真实风险继承评分并触发超阈值自动升级门控.
    await open(page, id, '需求与治理', '风险与问题');
    await templateRow(page, highTitle).getByRole('button', { name: '实例化为风险', exact: true }).click();
    const instForm = modal(page, '从自定义模板实例化');
    await choose(page, instForm, 'template_id', highTitle);
    await choose(page, instForm, 'owner_id', 'admin');
    await instForm.locator('#due_date').fill('2026-10-20');
    const inst = await save(page, '从自定义模板实例化');
    expect(inst.result.title, '继承模板标题').toBe(highTitle);
    expect(inst.result.score, '按模板概率x影响').toBe(20);
    expect(inst.result.escalated, '达阈值自动升级').toBe(true);
    expect(inst.result.escalation_state).toBe('pending');
    expect(inst.result.escalation_level).toBe('steering');
    expect(inst.result.source_key).toBe(`custom:${high.id}`);
    expect(inst.result.source_category).toBe('technical');
    expect(inst.result.stage, '继承模板适用阶段').toBe('采购');

    // 5. 风险台账单上下文可见: 徽标 "待升级确认 / steering" 且来源列标记来自自定义模板.
    await open(page, id, '需求与治理', '风险与问题');
    await riskPanel(page).scrollIntoViewIfNeeded();
    await expect(riskRow(page, highTitle).getByText('待升级确认 / steering')).toBeVisible();
    await shotSection(page, riskPanel(page), 'c10b-3-risk-escalated.png');

    // 6. 作废 mid 模板 -> 从有效列表消失; 历史风险不受影响.
    await open(page, id, '需求与治理', '风险与问题');
    await templateRow(page, midTitle).getByRole('button', { name: '作废', exact: true }).click();
    const discardForm = modal(page, '作废风险模板');
    await discardForm.locator('#reason').fill('该模板与高分模板重复, 收敛为单一来源.');
    const discarded = await save(page, '作废风险模板');
    expect(discarded.result.status, '软置为已作废').toBe('discarded');
    await open(page, id, '需求与治理', '风险与问题');
    await expect(templatePanel(page).getByText(midTitle)).toHaveCount(0);
    await shotSection(page, templatePanel(page), 'c10b-4-after-discard.png');

    // 7. 真实 HTTP 合同探针: 作废后实例化 404; 概率越界 400; 缺标题 400.
    const version = (await api(page, 'GET', base(id))).version;
    await api(page, 'POST', base(id) + '/governance/risks/from-custom-template',
      { template_id: mid.id, owner_id: adminId, due_date: '2026-10-30', version }, 404);
    await api(page, 'POST', base(id) + '/governance/risk-templates',
      { title: `越界-${suffix}`, probability: 6, impact: 3, mitigation: 'x', version }, 400);
    await api(page, 'POST', base(id) + '/governance/risk-templates',
      { probability: 3, impact: 3, mitigation: '缺标题', version }, 400);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
