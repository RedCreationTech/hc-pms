const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C03c 需求追踪状态内联只读派生: admin 建项目 -> 真实HTTP建三条需求 (无追踪 / 仅 satisfies / satisfies+verifies) ->
// 打开"URS与追踪"页签, URS 需求台账新增只读"追踪状态"列: 无关联显"未追踪", 仅设计满足关联显"缺验证关联", 齐备显"追踪完整".
// 再给"仅 satisfies"那条补一条 verifies 追踪 -> 重载翻"追踪完整". 全程真实点击 + 真实HTTP + 真实浏览器截图,
// 免迁移读取时派生, 只读可见, 不写存储, 不门控, 不改任何提交/批准状态机.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c03-trace-state');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const reqCard = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: 'URS 需求版本', exact: true }) });
const reqRow = (page, code) => reqCard(page).locator('tbody tr:visible').filter({ hasText: code }).first();
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

// URS 需求台账位于抽屉滚动区, 用元素截图确保"追踪状态"列进入画面.
async function shotReq(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  const card = reqCard(page);
  await card.scrollIntoViewIfNeeded();
  await expect(card.getByRole('columnheader', { name: '追踪状态', exact: true })).toBeVisible();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.describe('C03c 需求追踪状态内联只读派生浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('URS 台账"追踪状态"列按已登记关联显未追踪/缺验证关联/追踪完整, 补验证关联后翻转', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `TRST-${suffix}`, name: `追踪状态验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    const mkReq = code => command(page, id, '/requirements', { code, text: `需求 ${code}`, category: '功能', priority: 'required', owner_id: adminId });
    const mkDoc = code => command(page, id, '/documents', { code, title: `证据 ${code}`, filename: '验证.txt', content: ' 真实证据\n' });

    const codeA = `URSA-${suffix}`; // 无任何追踪关联 -> 未追踪
    const codeB = `URSB-${suffix}`; // satisfies + verifies -> 追踪完整
    const codeC = `URSC-${suffix}`; // 仅 satisfies -> 缺验证关联 (随后补 verifies 翻转)

    const reqA = await mkReq(codeA);
    const reqB = await mkReq(codeB);
    const reqC = await mkReq(codeC);
    const docB1 = await mkDoc(`DOCB1-${suffix}`);
    const docB2 = await mkDoc(`DOCB2-${suffix}`);
    const docC1 = await mkDoc(`DOCC1-${suffix}`);

    await command(page, id, '/traces', { requirement_id: reqB.id, target_kind: 'document', target_id: docB1.id, relation: 'satisfies' });
    await command(page, id, '/traces', { requirement_id: reqB.id, target_kind: 'document', target_id: docB2.id, relation: 'verifies' });
    await command(page, id, '/traces', { requirement_id: reqC.id, target_kind: 'document', target_id: docC1.id, relation: 'satisfies' });

    // 打开URS与追踪页签: 三种追踪状态在同一界面可见.
    await open(page, id, '需求与治理', 'URS与追踪');
    await expect(reqRow(page, codeA).getByText('未追踪')).toBeVisible();
    await expect(reqRow(page, codeB).getByText('追踪完整')).toBeVisible();
    await expect(reqRow(page, codeC).getByText('缺验证关联')).toBeVisible();
    await shotReq(page, 'c03-trace-state-1-states.png');

    // 后端读模型回显 (真实HTTP): 三条需求各自派生状态与计数一致.
    const gov = await api(page, 'GET', base(id) + '/governance');
    const byCode = code => gov.requirements.find(r => r.code === code);
    expect(byCode(codeA).trace_state).toBe('untracked');
    expect(byCode(codeA).trace_design_links).toBe(0);
    expect(byCode(codeA).trace_verification_links).toBe(0);
    expect(byCode(codeB).trace_state).toBe('complete');
    expect(byCode(codeB).trace_design_links).toBe(1);
    expect(byCode(codeB).trace_verification_links).toBe(1);
    expect(byCode(codeC).trace_state).toBe('missing-verification');
    expect(byCode(codeC).trace_design_links).toBe(1);
    expect(byCode(codeC).trace_verification_links).toBe(0);

    // 给"仅 satisfies"的需求补一条 verifies 追踪 -> 重载后该需求翻"追踪完整", "缺验证关联"消失.
    const docC2 = await mkDoc(`DOCC2-${suffix}`);
    await command(page, id, '/traces', { requirement_id: reqC.id, target_kind: 'document', target_id: docC2.id, relation: 'verifies' });
    await open(page, id, '需求与治理', 'URS与追踪');
    await expect(reqRow(page, codeC).getByText('追踪完整')).toBeVisible();
    await expect(reqRow(page, codeC).getByText('缺验证关联')).toHaveCount(0);
    await shotReq(page, 'c03-trace-state-2-complete.png');

    const after = await api(page, 'GET', base(id) + '/governance');
    const cAfter = after.requirements.find(r => r.code === codeC);
    expect(cAfter.trace_state).toBe('complete');
    expect(cAfter.trace_verification_links).toBe(1);

    // 只读派生不改变需求自身状态: codeA 需求仍 registered, 未因追踪读模型而漂移.
    expect(byCode(codeA).status).toBe('registered');

    expect(errors).toEqual([]);
  });
});
