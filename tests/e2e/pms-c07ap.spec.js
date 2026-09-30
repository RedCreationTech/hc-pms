const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C07 延伸: 会议行动优先级 (priority) 可选枚举字段.
// 界面"新增会议行动"里可选选一个优先级(高/中/低) -> 命令响应回显英文枚举值 ->
// 台账"优先级"列以彩色中文标签徽标回显; 未选/清空的行显示"未设定"; 非法枚举经真实HTTP 400;
// 免迁移随 payload 持久化, 转真实任务后不漂移.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/ap');
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

// 打开指定会议行的"形成行动"表单, 填必填项; 若给定 priorityLabel 则选优先级; 停在保存前以便截图.
async function fillAction(page, pid, meetingTitle, { title, due, priorityLabel }) {
  await open(page, pid, '需求与治理', '会议行动');
  await row(page, meetingTitle).getByRole('button', { name: '形成行动', exact: true }).click();
  const form = modal(page, '新增会议行动');
  await form.locator('#title').fill(title);
  await choose(page, form, 'owner_id', 'admin');
  await form.locator('#due_date').fill(due);
  if (priorityLabel) await choose(page, form, 'priority', priorityLabel);
}

test.describe('C07 延伸 会议行动优先级可选枚举浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('界面选优先级入台账列回显中文, 未选/清空显示未设定, 非法枚举经真实HTTP被拒', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `APP-${suffix}`, name: `行动优先级验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    const meetingTitle = `整改评审例会-${suffix}`;
    const meeting = (await mutate(page, id, '/governance/meetings',
      { title: meetingTitle, held_on: '2026-09-10', minutes: '按优先级分派整改行动并留痕.', attendee_ids: [adminId] })).result;

    // 1) 界面登记高优先级行动 (选"高"), 保存前截图证明该可选枚举字段.
    const highTitle = `供电中断整改-${suffix}`;
    await fillAction(page, id, meetingTitle, { title: highTitle, due: '2026-10-20', priorityLabel: '高' });
    await shot(page, 'ap-1-dialog-priority.png');
    const createdHigh = await save(page, '新增会议行动');
    expect(createdHigh.result.priority, '命令响应回显英文枚举值').toBe('high');
    const highId = createdHigh.result.id;

    // 2) 界面登记未选优先级的行动 -> 不含该键.
    const plainTitle = `常规跟进行动-${suffix}`;
    await fillAction(page, id, meetingTitle, { title: plainTitle, due: '2026-10-25' });
    const createdPlain = await save(page, '新增会议行动');
    expect(createdPlain.result.priority == null, '未选优先级不含该键').toBeTruthy();

    // 3) 低优先级行动 (三档枚举均界面可选).
    const lowTitle = `文档归档行动-${suffix}`;
    await fillAction(page, id, meetingTitle, { title: lowTitle, due: '2026-10-28', priorityLabel: '低' });
    const createdLow = await save(page, '新增会议行动');
    expect(createdLow.result.priority, '回显低优先级枚举值').toBe('low');

    // 4) 中优先级行动.
    const medTitle = `接线复核行动-${suffix}`;
    await fillAction(page, id, meetingTitle, { title: medTitle, due: '2026-11-05', priorityLabel: '中' });
    const createdMed = await save(page, '新增会议行动');
    expect(createdMed.result.priority).toBe('medium');

    // 5) 转真实任务后优先级随记录保留不漂移.
    await open(page, id, '需求与治理', '会议行动');
    await row(page, medTitle).getByRole('button', { name: '转为WBS任务', exact: true }).click();
    const convForm = modal(page, '会议行动转WBS任务');
    await convForm.locator('#start_date').fill('2026-09-23');
    await save(page, '会议行动转WBS任务');

    // 6) 读模型原样返回持久化字段.
    const ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.actions.find(a => a.id === highId).priority, '读模型回显优先级').toBe('high');
    expect(ws.actions.find(a => a.id === createdLow.result.id).priority, '读模型回显低优先级').toBe('low');
    expect(ws.actions.find(a => a.id === createdPlain.result.id).priority == null, '未选读模型为空').toBeTruthy();
    const convRow = ws.actions.find(a => a.id === createdMed.result.id);
    expect(convRow.priority, '转任务后优先级不漂移').toBe('medium');
    expect(convRow.status, '转任务后状态为 converted').toBe('converted');

    // 7) 台账"优先级"列: 选过优先级的行回显中文标签"高"/"中"/"低", 未选行显示"未设定".
    await open(page, id, '需求与治理', '会议行动');
    await expect(row(page, highTitle).locator('.ant-table-cell').filter({ hasText: /^高$/ })).toBeVisible();
    await expect(row(page, medTitle).locator('.ant-table-cell').filter({ hasText: /^中$/ })).toBeVisible();
    await expect(row(page, lowTitle).locator('.ant-table-cell').filter({ hasText: /^低$/ })).toBeVisible();
    await expect(row(page, plainTitle).locator('.ant-table-cell').filter({ hasText: '未设定' })).toBeVisible();
    // 台账在抽屉自身滚动容器内, 把行动表滚入视口再截, 保证四档优先级标签可见.
    await row(page, highTitle).scrollIntoViewIfNeeded();
    await page.waitForTimeout(200);
    await shot(page, 'ap-2-ledger-column.png');
    const table = row(page, highTitle).locator('xpath=ancestor::table[1]');
    await table.screenshot({ path: path.join(output, 'ap-3-priority-cells.png'), animations: 'disabled' });

    // 8) 真实HTTP拒绝非法枚举.
    const v = (await api(page, 'GET', base(id))).version;
    const badCode = await page.evaluate(async ({ id, mid, suffix, v }) => {
      const token = localStorage.getItem('ruoyi_token');
      const r = await fetch(`/api/pms/projects/${id}/governance/meetings/${mid}/actions`, { method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: `BAD-${suffix}`, owner_id: 1, due_date: '2026-10-20', priority: 'urgent', version: v }) });
      return (await r.json()).code;
    }, { id, mid: meeting.id, suffix, v });
    expect(badCode, '非法优先级应被拒').toBe(400);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
