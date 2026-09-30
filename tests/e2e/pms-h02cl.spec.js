const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H02 延伸: 沟通计划"标记已沟通"可选本次实际渠道 (channel) 枚举字段.
// 界面"标记已沟通"里可选选一个本次实际渠道(会议/邮件/看板/报告/评审) -> 命令响应回显英文枚举值 ->
// 台账"最近沟通方式"列以彩色中文标签徽标回显; 不选则沿用计划默认渠道(会议)回退;
// 非法枚举经真实HTTP 400; 免迁移随 payload 持久化.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h02cl');
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

async function mutate(page, id, suffix, data = {}) {
  const project = await api(page, 'GET', base(id));
  return api(page, 'POST', base(id) + suffix, { ...data, version: project.version });
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

// 同表单多个 antd Select 的下拉面板会同时留在 DOM, 用 combobox 自身 aria-controls 过滤其下拉避免串台.
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

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 打开指定沟通计划行的"标记已沟通"表单; 若给定 channelLabel 则选本次实际渠道; 停在保存前以便截图.
async function openLog(page, pid, planCode, channelLabel) {
  await open(page, pid, '需求与治理', '干系人与沟通');
  await row(page, planCode).getByRole('button', { name: '标记已沟通', exact: true }).click();
  const form = modal(page, '标记已沟通');
  await expect(form).toBeVisible();
  if (channelLabel) await choose(page, form, 'channel', channelLabel);
}

test.describe('H02 延伸 沟通日志可选实际渠道浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('界面选本次渠道入台账列回显中文, 不选回退计划渠道, 非法枚举经真实HTTP被拒', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `CL-${suffix}`, name: `沟通渠道验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 干系人 (责任人 admin) 与沟通计划 (计划默认渠道 meeting, 周频).
    const shCode = `SH-${suffix}`;
    const stakeholder = (await mutate(page, id, '/governance/stakeholders',
      { code: shCode, name: '进度对接人', role: '需求方接口', category: 'customer', interest: 'high', influence: 'high', owner_id: adminId })).result;

    const planCode = `CP-${suffix}`;
    const plan = (await mutate(page, id, '/governance/comm-plans',
      { code: planCode, objective: '每周同步进度与风险', channel: 'meeting', frequency: 'weekly',
        audience: [stakeholder.id], next_date: '2026-09-20', owner_id: adminId })).result;
    expect(plan.channel, '计划默认渠道为 meeting').toBe('meeting');

    // 1) 界面"标记已沟通"选本次实际渠道"邮件", 保存前截图证明该可选枚举字段.
    await openLog(page, id, planCode, '邮件');
    await shot(page, 'h02cl-1-dialog-channel.png');
    const firstLog = await save(page, '标记已沟通');
    expect(firstLog.result.last_communication_channel, '命令响应回显英文枚举值').toBe('email');
    expect(firstLog.result.communication_log.at(-1).channel, '留痕记录本次渠道').toBe('email');

    // 2) 台账"最近沟通方式"列回显中文标签"邮件".
    await open(page, id, '需求与治理', '干系人与沟通');
    await expect(row(page, planCode).locator('.ant-table-cell').filter({ hasText: /^邮件$/ })).toBeVisible();
    await row(page, planCode).scrollIntoViewIfNeeded();
    await page.waitForTimeout(200);
    await shot(page, 'h02cl-2-ledger-email.png');

    // 3) 再次"标记已沟通"不选渠道 -> 回退沿用计划默认渠道 meeting.
    await openLog(page, id, planCode, null);
    const secondLog = await save(page, '标记已沟通');
    expect(secondLog.result.last_communication_channel, '不选则回退计划渠道').toBe('meeting');
    expect(secondLog.result.communication_log.length, '两次留痕累加').toBe(2);

    // 4) 台账列回退显示"会议".
    await open(page, id, '需求与治理', '干系人与沟通');
    await expect(row(page, planCode).locator('.ant-table-cell').filter({ hasText: /^会议$/ })).toBeVisible();
    const table = row(page, planCode).locator('xpath=ancestor::table[1]');
    await table.screenshot({ path: path.join(output, 'h02cl-3-ledger-fallback.png'), animations: 'disabled' });

    // 5) 读模型原样返回持久化字段, 计划默认渠道不被污染.
    const ws = await api(page, 'GET', base(id) + '/governance');
    const wsPlan = ws.comm_plans.find(p => p.id === plan.id);
    expect(wsPlan.channel, '计划渠道仍为 meeting').toBe('meeting');
    expect(wsPlan.last_communication_channel, '读模型最近沟通方式为回退后的 meeting').toBe('meeting');
    expect(wsPlan.communication_log.map(l => l.channel), '留痕渠道序列').toEqual(['email', 'meeting']);

    // 6) 真实HTTP拒绝非法枚举.
    const v = (await api(page, 'GET', base(id))).version;
    const badCode = await page.evaluate(async ({ id, pid, v }) => {
      const token = localStorage.getItem('ruoyi_token');
      const r = await fetch(`/api/pms/projects/${pid}/governance/comm-plans/${id}/log`, { method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ on: '2026-09-25', channel: 'smoke-signal', version: v }) });
      return (await r.json()).code;
    }, { id: plan.id, pid: id, v });
    expect(badCode, '非法渠道应被拒').toBe(400);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
