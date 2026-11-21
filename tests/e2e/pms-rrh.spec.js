const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H08 延伸: 风险复审历史执行只读派生 (免迁移, 无新命令/新kind/新路由).
// "需求与治理 -> 风险与问题" 在"风险复审到期节奏"之后新增只读"风险复审历史执行"面板:
// 按每个风险最新有效版本(s/latest)从 workflow_history 统计 review_decided 决策次数, 给出
// 已复审数/复审缺口(未关闭且尚无已决策复审)/批准与驳回次数及批准率/重新评分次数/已批准结论分布.
// 与"复审到期节奏"(下次何时到期)互补: 本项看过去复审到底做了什么.
// 流程: 登记两条风险 -> 面板 风险总数2/已复审0/复审缺口2 -> 对一条提交复审并独立批准(已缓解)
//       -> 已复审1/批准率100%/已缓解1/复审缺口1 -> 对另一条提交后被驳回 -> 驳回1/批准率50%. 空态单独截图.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/rrh');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const TITLE = '风险复审历史执行';
const dayOffset = n => { const d = new Date(); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };

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

async function mutate(page, id, suffix, data = {}, expected = 200) {
  const project = await api(page, 'GET', base(id));
  return api(page, 'POST', base(id) + suffix, { ...data, version: project.version }, expected);
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

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function panelShot(page, title, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  const card = panel(page, title);
  await expect(card.getByRole('heading', { name: title, exact: true })).toBeVisible();
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function createRisk(page, id, { title, due }) {
  await open(page, id, '需求与治理', '风险与问题');
  await drawer(page).getByRole('button', { name: '登记项目风险', exact: true }).click();
  const form = modal(page, '登记项目风险');
  await form.locator('#title').fill(title);
  await form.locator('#probability').fill('2');
  await form.locator('#impact').fill('3');
  await choose(page, form, 'owner_id', 'admin');
  await form.locator('#mitigation').fill('例会持续跟踪并预留纠偏窗口.');
  await form.locator('#due_date').fill(due);
  return save(page, '登记项目风险');
}

test.use({ viewport: { width: 1600, height: 1000 } });

test.describe('H08 风险复审历史执行只读派生浏览器验收', () => {
  test.setTimeout(180000);

  test('复审决策次数/批准率/结论分布随独立批准与驳回翻转, 面板与 :risk_review_history 一致', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `RRH-${suffix}`, name: `复审历史验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 0) 空态: 无风险时面板提示暂无风险.
    await open(page, id, '需求与治理', '风险与问题');
    await expect(panel(page, TITLE).getByText('暂无项目风险, 登记后可在此查看复审历史执行情况.', { exact: true })).toBeVisible();
    await panelShot(page, TITLE, 'rrh-1-empty.png');
    expect((await api(page, 'GET', base(id) + '/governance')).risk_review_history.available).toBe(false);

    // 1) 两条风险都未复审 -> 已复审 0 / 复审缺口 2.
    const t1 = `来料延迟风险-${suffix}`;
    const t2 = `供应商产能风险-${suffix}`;
    const r1 = (await createRisk(page, id, { title: t1, due: dayOffset(5) })).result;
    const r2 = (await createRisk(page, id, { title: t2, due: dayOffset(9) })).result;
    await open(page, id, '需求与治理', '风险与问题');
    const p1 = panel(page, TITLE);
    await expect(p1.getByText('风险总数 2', { exact: true })).toBeVisible();
    await expect(p1.getByText('已复审 0', { exact: true })).toBeVisible();
    await expect(p1.getByText('复审缺口 2', { exact: true })).toBeVisible();
    await expect(p1.getByText('已批准结论')).toBeVisible();
    await panelShot(page, TITLE, 'rrh-2-none.png');
    let rh = (await api(page, 'GET', base(id) + '/governance')).risk_review_history;
    expect(rh.total).toBe(2);
    expect(rh.reviewed).toBe(0);
    expect(rh['review-gap']).toBe(2);
    expect(rh['by-outcome'].map(x => x.outcome)).toEqual(['active', 'mitigated', 'closed']);

    // 2) 登记证据文档, 对 r1 提交"已缓解"复审并由独立核查人批准 -> 批准 1 / 批准率 100% / 已缓解 1.
    const evidence = (await mutate(page, id, '/governance/documents', { code: `RRH-EV-${suffix}`, title: '复审证据',
      filename: 'rrh.txt', content: '合成复审证据.' })).result;
    // owner 是 admin, 不能自审: 先建一位独立核查人.
    const reviewer = await (async () => {
      const menus = await api(page, 'GET', '/api/system/menu');
      const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
      const menuIds = menus.filter(i => perms.has(i.perms) || i.path === 'pms').map(i => i.menu_id);
      const userName = `rrh_${suffix}`;
      const password = `E2e!${suffix}`;
      await api(page, 'POST', '/api/system/role', { role_name: `复审核查${suffix}`, role_key: `pms_rrh_${suffix}`, 'menu-ids': menuIds });
      const roles = await api(page, 'GET', '/api/system/role');
      const roleId = roles.find(i => i.role_key === `pms_rrh_${suffix}`).role_id;
      await api(page, 'POST', '/api/system/user', { user_name: userName, nick_name: '独立复审核查人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
      const uid = (await api(page, 'GET', '/api/pms/options')).users.find(i => i.user_name === userName).user_id;
      await api(page, 'POST', base(id) + '/members', { user_id: uid, role: 'viewer' });
      const token = await (async () => {
        const r = await page.request.post('/api/auth/login', { data: { username: userName, password } });
        return (await r.json()).data.token;
      })();
      return { uid, token };
    })();
    await mutate(page, id, `/governance/risks/${r1.id}/review`, { outcome: 'mitigated', review_note: '已验证缓解措施有效',
      reviewer_id: reviewer.uid, evidence_ids: [evidence.id], next_review_date: dayOffset(21) });
    // 登记人自行批准须 403 (职责分离), 再由独立核查人经真实 HTTP 批准.
    await api(page, 'POST', base(id) + `/governance/risks/${r1.id}/decision`,
      { decision: 'approved', reason: '登记人自行确认', version: (await api(page, 'GET', base(id))).version }, 403);
    const pv = (await api(page, 'GET', base(id))).version;
    const approve = await page.request.post(base(id) + `/governance/risks/${r1.id}/decision`, {
      headers: { Authorization: `Bearer ${reviewer.token}` }, data: { decision: 'approved', reason: '独立确认缓解', version: pv } });
    expect((await approve.json()).code, '独立核查人批准').toBe(200);

    await open(page, id, '需求与治理', '风险与问题');
    const p2 = panel(page, TITLE);
    await expect(p2.getByText('已复审 1', { exact: true })).toBeVisible();
    await expect(p2.getByText('复审批准率 100% (1/1)', { exact: true })).toBeVisible();
    await expect(p2.getByText('已缓解 1', { exact: true })).toBeVisible();
    await expect(p2.getByText('复审缺口 1', { exact: true })).toBeVisible();
    await panelShot(page, TITLE, 'rrh-3-approved.png');
    await shot(page, 'rrh-4-page.png');
    rh = (await api(page, 'GET', base(id) + '/governance')).risk_review_history;
    expect(rh.approved).toBe(1);
    expect(rh.reviewed).toBe(1);
    expect(rh['approval-pct']).toBe(100);
    expect(rh['review-gap']).toBe(1);

    // 3) 对 r2 提交后被驳回 -> 驳回 1, 批准率回落到 50%.
    await mutate(page, id, `/governance/risks/${r2.id}/review`, { outcome: 'active', review_note: '证据不足需补充',
      reviewer_id: reviewer.uid, evidence_ids: [evidence.id], next_review_date: dayOffset(30) });
    const pv2 = (await api(page, 'GET', base(id))).version;
    const reject = await page.request.post(base(id) + `/governance/risks/${r2.id}/decision`, {
      headers: { Authorization: `Bearer ${reviewer.token}` }, data: { decision: 'rejected', reason: '证据不足退回', version: pv2 } });
    expect((await reject.json()).code, '独立核查人驳回').toBe(200);

    await open(page, id, '需求与治理', '风险与问题');
    const p3 = panel(page, TITLE);
    await expect(p3.getByText('复审批准率 50% (1/2)', { exact: true })).toBeVisible();
    await expect(p3.getByText('已驳回 1', { exact: true })).toBeVisible();
    await panelShot(page, TITLE, 'rrh-5-rejected.png');
    rh = (await api(page, 'GET', base(id) + '/governance')).risk_review_history;
    expect(rh.approved).toBe(1);
    expect(rh.rejected).toBe(1);
    expect(rh['approval-pct']).toBe(50);
    expect(rh['rejected-risks']).toBe(1);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});