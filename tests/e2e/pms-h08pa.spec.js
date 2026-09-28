const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H08/C10 风险应对措施落实为可追踪预防行动项: 在"风险与问题"台账对进行中/已缓解风险点击"落实预防措施" ->
// 弹出对话框(标题预填自风险应对措施) -> 保存后在"会议行动"台账新增一条开放行动, 其"来源风险"列以紫色标签回显来源风险标题 ->
// 服务端治理读模型二次确认 action_source_risk_id / action_source_risk_title. 复用既有 action 类型与完整生命周期, 免迁移.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h08pa');
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

test.describe('H08 风险预防措施落实为预防行动项浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('风险台账"落实预防措施" -> 行动台账出现来源风险标注的开放行动', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H08PA-${suffix}`, name: `预防行动落实验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 服务端登记一条进行中风险(低分 2x3=6 不触发升级噪声), 带明确应对措施.
    const riskTitle = `关键物料断供风险-${suffix}`;
    const risk = await api(page, 'POST', base(id) + '/governance/risks', {
      title: riskTitle, probability: 2, impact: 3, owner_id: adminId,
      mitigation: '启用备选供应商并加严来料检验', due_date: '2026-11-30', version: await version(page, id) }).then(d => d.result);

    // 截图1: 风险与问题台账出现该风险行与"落实预防措施"入口.
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, riskTitle)).toBeVisible();
    await expect(row(page, riskTitle).getByRole('button', { name: '落实预防措施', exact: true })).toBeVisible();
    await shot(page, 'h08pa-1-risk-button.png');

    // 界面点击"落实预防措施" -> 对话框标题预填自应对措施 -> 改为显式行动内容并保存.
    await row(page, riskTitle).getByRole('button', { name: '落实预防措施', exact: true }).click();
    const form = modal(page, '落实为预防行动项');
    await expect(form.locator('#title')).toHaveValue('启用备选供应商并加严来料检验');
    const actionTitle = `锁定备选供应商名单-${suffix}`;
    await form.locator('#title').fill(actionTitle);
    const action = (await save(page, '落实为预防行动项')).result;

    // 截图2: 会议行动台账新增该行动, "来源风险"列以紫色标签回显来源风险标题.
    await open(page, id, '需求与治理', '会议行动');
    const actionRow = row(page, actionTitle);
    await expect(actionRow).toBeVisible();
    await expect(actionRow.getByText(riskTitle)).toBeVisible();
    await shot(page, 'h08pa-2-action-source.png');

    // 服务端治理读模型二次确认: 行动携带来源风险 id 与回显标题, 默认沿用风险责任人与到期日, 风险未被改变.
    const gov = await api(page, 'GET', base(id) + '/governance');
    const read = gov.actions.find(a => a.id === action.id);
    expect(read.source_risk_id, '行动记录来源风险 id').toBe(risk.id);
    expect(read.action_source_risk_id, '只读标注来源风险 id').toBe(risk.id);
    expect(read.action_source_risk_title, '只读标注来源风险标题').toBe(riskTitle);
    expect(read.owner_id, '默认沿用风险责任人').toBe(adminId);
    expect(read.due_date, '默认沿用风险到期日').toBe('2026-11-30');
    expect(read.status, '新建为开放行动').toBe('open');
    const readRisk = gov.risks.find(r => r.id === risk.id);
    expect(readRisk.status, '落实行动不改变风险状态').toBe('open');

    // 会议派生的行动不应带来源风险标注, 台账显示"非风险来源".
    const meeting = await api(page, 'POST', base(id) + '/governance/meetings', {
      title: `设计评审会-${suffix}`, held_on: '2026-09-22', minutes: '形成会议行动', attendee_ids: [adminId], version: await version(page, id) }).then(d => d.result);
    const meetingAction = await api(page, 'POST', base(id) + '/governance/meetings/' + meeting.id + '/actions', {
      title: `补充接口说明-${suffix}`, owner_id: adminId, due_date: '2026-10-10', version: await version(page, id) }).then(d => d.result);
    const gov2 = await api(page, 'GET', base(id) + '/governance');
    const readMA = gov2.actions.find(a => a.id === meetingAction.id);
    expect(readMA.source_risk_id, '会议行动无来源风险').toBeFalsy();
    expect(readMA.action_source_risk_title, '会议行动无来源风险标注').toBeFalsy();

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
