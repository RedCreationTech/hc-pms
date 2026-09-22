const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H02 沟通节奏可执行: 沟通计划下次日期已过期 -> 台账"沟通到期"列显示"沟通已到期" ->
// 界面"标记已沟通"记录一次实际沟通 -> 服务端按既定周频自动顺延下次日期(2026-09-22 + 7 = 2026-09-29)
// 并写入可审计沟通留痕 -> 台账徽标转为"N 天后沟通". 复用既有 comm-plan 类型与只读读模型, 免迁移.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h02c');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
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

async function version(page, id) {
  const project = await api(page, 'GET', base(id));
  return project.version;
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

test.describe('H02 沟通节奏标记已沟通浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('沟通到期预警 -> 标记已沟通按周频顺延 -> 徽标转为剩余天数', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H02C-${suffix}`, name: `沟通节奏验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    const stakeholder = (await api(page, 'POST', base(id) + '/governance/stakeholders', { code: `SH-${suffix}`, name: '客户代表', role: '验收',
      category: 'customer', interest: 'high', influence: 'high', owner_id: adminId, version: await version(page, id) })).result;
    const objective = `周度设备进展同步-${suffix}`;
    const plan = (await api(page, 'POST', base(id) + '/governance/comm-plans', { code: `CP-${suffix}`, objective, channel: 'meeting',
      frequency: 'weekly', audience: [stakeholder.id], next_date: '2026-01-05', owner_id: adminId, version: await version(page, id) })).result;
    const pid = plan.id;

    // 打开沟通计划台账, 过期下次日期 -> "沟通已到期" 预警徽标.
    await open(page, id, '需求与治理', '干系人与沟通');
    await expect(row(page, objective).getByText('沟通已到期')).toBeVisible();
    await shot(page, 'h02c-1-overdue.png');

    // 界面标记已沟通: 记录实际沟通日期与纪要.
    await row(page, objective).getByRole('button', { name: '标记已沟通', exact: true }).click();
    const logForm = modal(page, '标记已沟通');
    await logForm.locator('#on').fill('2026-09-22');
    await logForm.locator('#note').fill('已召开周会同步设备到货与装配进展, 确认下周继续.');
    const logged = await save(page, '标记已沟通');
    expect(logged.result.next_date, '按周频顺延下次沟通日期').toBe('2026-09-29');
    expect(logged.result.last_communicated_on).toBe('2026-09-22');
    expect(logged.result.last_communication_note).toContain('已召开周会');
    expect(logged.result.communication_log.length).toBe(1);

    // 台账徽标翻转为"N 天后沟通", 不再是"沟通已到期".
    await open(page, id, '需求与治理', '干系人与沟通');
    await expect(row(page, objective).getByText('沟通已到期')).toHaveCount(0);
    await expect(row(page, objective).getByText(/天后沟通/)).toBeVisible();
    await shot(page, 'h02c-2-logged.png');

    // 服务端读模型二次确认.
    const gov = await api(page, 'GET', base(id) + '/governance');
    const after = gov.comm_plans.find(c => c.id === pid);
    expect(after.next_date).toBe('2026-09-29');
    expect(after.comm_overdue, '顺延后不再到期').toBe(false);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
