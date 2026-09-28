const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C01 延伸: 需求验证方式与验证(verifies)证据关联一致性只读派生 (免迁移, 无新命令/新kind).
// 界面登记一条声明验证方式(测试)的需求与一条不声明的需求 -> "URS与追踪"页签:
//   需求台账"验证对齐"列对前者显"声明方式·缺验证关联", 对后者显"未声明方式";
//   只读"验证方式与验证关联对齐"面板显"已声明验证方式 1 / 已配验证关联 0% / 缺验证关联 1".
// 再为该需求补一条 verifies 追踪关联 -> 列翻"已配验证关联", 面板升到"已配验证关联 100% / 对齐 1"且"缺验证关联"消失.
// 全程真实点击 + 真实 HTTP 回显 verification_evidence_alignment + verification_alignment, 只读不门控不改任何状态机.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c01va');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const reqCard = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: 'URS 需求版本', exact: true }) });
const reqRow = (page, code) => reqCard(page).locator('tbody tr:visible').filter({ hasText: code }).first();
const alignCard = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '验证方式与验证关联对齐', exact: true }) });
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

async function shotPage(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function shotCard(page, card, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.describe('C01 延伸 需求验证方式与验证关联对齐只读洞察浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('验证对齐面板与台账列: 声明方式缺验证关联 -> 补 verifies 关联后翻为已配验证关联', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `CVA-${suffix}`, name: `验证对齐验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', 'URS与追踪');

    // 1) 界面登记两条需求: URS-G 声明验证方式"测试"但不选关联, URS-N 完全不声明验证方式.
    const codeG = `URS-G-${suffix}`;
    const codeN = `URS-N-${suffix}`;
    await fillRequirement(page, { code: codeG, text: '控制器固件须支持远程升级并通过回滚校验.', methodLabel: '测试' });
    await save(page, '新增URS需求');
    await fillRequirement(page, { code: codeN, text: '整机噪声须低于阈值.' });
    await save(page, '新增URS需求');

    // 2) 面板: 声明方式者进入分母但尚无 verifies 关联 -> 已声明1 / 已配0% / 缺验证关联1.
    await expect(alignCard(page).getByText('验证方式与验证关联对齐', { exact: true })).toBeVisible();
    await expect(alignCard(page).getByText(/已声明验证方式\s*1/)).toBeVisible();
    await expect(alignCard(page).getByText(/已配验证关联\s*0%/)).toBeVisible();
    await expect(alignCard(page).getByText(/缺验证关联\s*1/)).toBeVisible();
    await shotPage(page, 'c01va-1-gap-panel.png');

    // 3) 台账"验证对齐"列: 声明方式缺关联 -> "声明方式·缺验证关联"; 未声明方式 -> "未声明方式".
    const colG = reqRow(page, codeG).getByText(/声明方式·缺验证关联/);
    await expect(colG).toBeVisible();
    await expect(reqRow(page, codeN).getByText('未声明方式', { exact: true })).toBeVisible();
    await expect(reqCard(page).getByRole('columnheader', { name: '验证对齐', exact: true })).toBeVisible();
    await shotCard(page, reqCard(page), 'c01va-2-gap-column.png');

    // 4) 服务端真实HTTP: 先取 URS-G 版本 id, 建证据文档并挂一条 verifies 追踪关联到该需求.
    const govBefore = await api(page, 'GET', base(id) + '/governance');
    const reqG = govBefore.requirements.find(r => r.code === codeG);
    expect(reqG.verification_alignment, '声明方式缺验证关联').toBe('declared-unverified');
    expect(govBefore.requirements.find(r => r.code === codeN).verification_alignment, '未声明方式').toBe('not-applicable');
    expect(govBefore.verification_evidence_alignment.declared, '声明分母').toBe(1);
    expect(govBefore.verification_evidence_alignment.aligned, '已对齐').toBe(0);
    expect(govBefore.verification_evidence_alignment.gap, '缺关联').toBe(1);
    expect(govBefore.verification_evidence_alignment['alignment-pct'], '对齐率').toBe(0);

    const doc = await command(page, id, '/documents', { code: `DOC-${suffix}`, title: `远程升级验证记录-${suffix}`, filename: '验证.txt', content: ' 已执行远程升级回滚测试\n' });
    await command(page, id, '/traces', { requirement_id: reqG.id, target_kind: 'document', target_id: doc.id, relation: 'verifies' });

    // 5) 重载页签: 面板升到 已配验证关联 100% / 对齐 1, "缺验证关联"标签消失; 列翻"已配验证关联".
    await open(page, id, '需求与治理', 'URS与追踪');
    await expect(alignCard(page).getByText(/已声明验证方式\s*1/)).toBeVisible();
    await expect(alignCard(page).getByText(/已配验证关联\s*100%/)).toBeVisible();
    await expect(alignCard(page).getByText(/对齐\s*1/)).toBeVisible();
    await expect(alignCard(page).getByText(/缺验证关联\s*\d+/)).toHaveCount(0);
    await expect(reqRow(page, codeG).getByText('已配验证关联', { exact: true })).toBeVisible();
    await expect(reqRow(page, codeN).getByText('未声明方式', { exact: true })).toBeVisible();
    await shotCard(page, reqCard(page), 'c01va-3-aligned-column.png');
    await shotPage(page, 'c01va-4-aligned-panel.png');

    // 6) 真实HTTP回显: 对齐翻转且只读不改变不可变字段.
    const govAfter = await api(page, 'GET', base(id) + '/governance');
    const reqGAfter = govAfter.requirements.find(r => r.code === codeG);
    expect(reqGAfter.verification_alignment, '补关联后翻 aligned').toBe('aligned');
    expect(reqGAfter.code, '编号不漂移').toBe(codeG);
    expect(reqGAfter.verification_method, '验证方式仍为测试').toBe('test');
    expect(govAfter.verification_evidence_alignment.declared, '分母不变').toBe(1);
    expect(govAfter.verification_evidence_alignment.aligned, '已对齐1').toBe(1);
    expect(govAfter.verification_evidence_alignment.gap, '缺口清零').toBe(0);
    expect(govAfter.verification_evidence_alignment['alignment-pct'], '对齐率100').toBe(100);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
