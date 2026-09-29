const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H08/C10 风险侧预防措施落实只读派生浏览器验收:
// 在"风险与问题"台账新增"措施落实"只读列, 按每个风险派生的预防行动反向聚合出三态:
//   登记风险(有应对措施但未落实任何预防行动) -> 橙色"措施未落实";
//   点击"落实预防措施"生成一条开放行动 -> 金色"落实中, 1 项待办";
//   将该预防行动"转为WBS任务"(converted 计完成) -> 绿色"已落实 1 项".
// 全程只读派生(免迁移/免新kind/免新命令), 服务端治理读模型二次确认 mitigation_action_total/open/state.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h08mr');
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

test.describe('H08/C10 风险侧预防措施落实只读派生浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('风险台账"措施落实"列: 措施未落实 -> 落实中(待办) -> 已落实', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H08MR-${suffix}`, name: `风险措施落实验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 登记一条进行中风险(低分 2x3=6 不触发升级噪声), 带明确应对措施但未落实任何预防行动.
    const riskTitle = `关键物料断供风险-${suffix}`;
    const risk = await api(page, 'POST', base(id) + '/governance/risks', {
      title: riskTitle, probability: 2, impact: 3, owner_id: adminId,
      mitigation: '启用备选供应商并加严来料检验', due_date: '2026-11-30', version: await version(page, id) }).then(d => d.result);

    // 截图1: 风险台账"措施落实"列显示橙色"措施未落实" (有措施, 尚未落实预防行动).
    await open(page, id, '需求与治理', '风险与问题');
    const riskRow = row(page, riskTitle);
    await expect(riskRow).toBeVisible();
    await expect(riskRow.getByText('措施未落实', { exact: true })).toBeVisible();
    await shot(page, 'h08mr-1-unimplemented.png');

    // 服务端读模型二次确认: unimplemented, total 0 open 0.
    let gov = await api(page, 'GET', base(id) + '/governance');
    let readRisk = gov.risks.find(r => r.id === risk.id);
    expect(readRisk.mitigation_action_state).toBe('unimplemented');
    expect(readRisk.mitigation_action_total).toBe(0);
    expect(readRisk.mitigation_action_open).toBe(0);

    // 界面点击"落实预防措施" -> 生成一条开放预防行动.
    await riskRow.getByRole('button', { name: '落实预防措施', exact: true }).click();
    const form = modal(page, '落实为预防行动项');
    const actionTitle = `锁定备选供应商名单-${suffix}`;
    await form.locator('#title').fill(actionTitle);
    const action = (await save(page, '落实为预防行动项')).result;

    // 截图2: 风险台账"措施落实"列翻转为金色"落实中, 1 项待办" (in-progress).
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, riskTitle).getByText(/落实中.*待办/)).toBeVisible();
    await shot(page, 'h08mr-2-in-progress.png');

    gov = await api(page, 'GET', base(id) + '/governance');
    readRisk = gov.risks.find(r => r.id === risk.id);
    expect(readRisk.mitigation_action_state, '落实一条开放行动后进入 in-progress').toBe('in-progress');
    expect(readRisk.mitigation_action_total).toBe(1);
    expect(readRisk.mitigation_action_open).toBe(1);

    // 在会议行动台账把该预防行动"转为WBS任务" -> converted 计完成, 风险侧 open 归零.
    await open(page, id, '需求与治理', '会议行动');
    const actionRow = row(page, actionTitle);
    await expect(actionRow).toBeVisible();
    await actionRow.getByRole('button', { name: '转为WBS任务', exact: true }).click();
    const taskForm = modal(page, '会议行动转WBS任务');
    await taskForm.locator('#start_date').fill('2026-09-25');
    await taskForm.locator('#duration_days').fill('2');
    await save(page, '会议行动转WBS任务');

    // 截图3: 风险台账"措施落实"列翻转为绿色"已落实 1 项" (completed).
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, riskTitle).getByText(/已落实/)).toBeVisible();
    await shot(page, 'h08mr-3-completed.png');

    gov = await api(page, 'GET', base(id) + '/governance');
    readRisk = gov.risks.find(r => r.id === risk.id);
    expect(readRisk.mitigation_action_state, '预防行动转任务(converted)后计完成 -> completed').toBe('completed');
    expect(readRisk.mitigation_action_total).toBe(1);
    expect(readRisk.mitigation_action_open).toBe(0);
    expect(readRisk.status, '只读派生不回写风险本身状态').toBe('open');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
