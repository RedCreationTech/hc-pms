const { test, expect } = require('@playwright/test');
const path = require('node:path');

// 责任人跨类负载只读派生列: 同一责任人(admin)在问题/风险/行动三张台账各承担若干未关闭事项 -> "责任人负载"列
// 在三张台账一致回显其跨类未关闭总数并达到阈值(4)显示红色"负载过重"; 通过界面把其中一条会议行动"转为WBS任务"
// (converted) 后跨类负载回落到阈值以下, "负载过重"消失; 真实HTTP GET governance 回显 owner_open_load 同步下降.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/g-load');
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

// 断言 200 并返回信封 data (命令类写操作的数据在 data.result 下).
async function mutateData(page, id, suffix, data = {}) {
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

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
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

const sevLabel = { blocker: '阻断', major: '严重', minor: '一般' };

// 界面在"风险与问题"页签登记一个问题(责任人 admin).
async function createIssue(page, id, { title, severity, due }) {
  await open(page, id, '需求与治理', '风险与问题');
  await drawer(page).getByRole('button', { name: '登记项目问题', exact: true }).click();
  const form = modal(page, '登记项目问题');
  await form.locator('#title').fill(title);
  await choose(page, form, 'severity', sevLabel[severity]);
  await choose(page, form, 'owner_id', 'admin');
  await form.locator('#due_date').fill(due);
  return save(page, '登记项目问题');
}

// 界面在"风险与问题"页签登记一个风险(责任人 admin, 低分不触发升级).
async function createRisk(page, id, { title, prob, impact, due }) {
  await open(page, id, '需求与治理', '风险与问题');
  await drawer(page).getByRole('button', { name: '登记项目风险', exact: true }).click();
  const form = modal(page, '登记项目风险');
  await form.locator('#title').fill(title);
  await form.locator('#probability').fill(String(prob));
  await form.locator('#impact').fill(String(impact));
  await choose(page, form, 'owner_id', 'admin');
  await form.locator('#mitigation').fill('例会持续跟踪并预留纠偏窗口.');
  await form.locator('#due_date').fill(due);
  return save(page, '登记项目风险');
}

async function createProject(page, suffix, deptId, adminId) {
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `GLOAD-${suffix}`, name: `责任人负载跨类验收 / ${suffix}`,
    project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
  return project.project_id;
}

test.describe('责任人跨类负载只读派生列浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('跨问题/风险/行动回显同一负载并在三台账同步; 转任务后回落到阈值下', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const id = await createProject(page, suffix, deptId, adminId);

    // 界面登记 2 个问题 + 1 个风险(均责任人 admin).
    const i1Title = `接线图缺失-${suffix}`;
    const i2Title = `参数标定偏差-${suffix}`;
    const rTitle = `关键物料到货延迟-${suffix}`;
    await createIssue(page, id, { title: i1Title, severity: 'major', due: '2026-12-20' });
    await createIssue(page, id, { title: i2Title, severity: 'major', due: '2026-12-20' });
    await createRisk(page, id, { title: rTitle, prob: 2, impact: 3, due: '2026-12-20' });

    // HTTP 登记会议 + 一条责任人 admin 的会议行动(避免多人下拉噪声; 仍计入同一台账派生).
    const actionTitle = `补齐来料检验规程-${suffix}`;
    const meeting = (await mutateData(page, id, '/governance/meetings',
      { title: `负载复盘会-${suffix}`, held_on: '2026-09-01', minutes: '分派来料检验行动', attendee_ids: [adminId] })).result;
    const action = (await mutateData(page, id, `/governance/meetings/${meeting.id}/actions`,
      { title: actionTitle, owner_id: adminId, due_date: '2026-10-01' })).result;

    // 截图1: "风险与问题"页签, 两条问题行与风险行均显示"未关闭 x 4" + "负载过重".
    await open(page, id, '需求与治理', '风险与问题');
    for (const title of [i1Title, i2Title, rTitle]) {
      await expect(row(page, title).getByText('未关闭 x 4')).toBeVisible();
      await expect(row(page, title).getByText('负载过重')).toBeVisible();
    }
    await shot(page, 'g-load-1-overloaded-issues-risk.png');

    // 截图2: 切到"会议行动"页签, 行动行也显示同一跨类负载"未关闭 x 4" + "负载过重"(跨台账一致).
    await open(page, id, '需求与治理', '会议行动');
    await expect(row(page, actionTitle).getByText('未关闭 x 4')).toBeVisible();
    await expect(row(page, actionTitle).getByText('负载过重')).toBeVisible();
    await shot(page, 'g-load-2-overloaded-action.png');

    // 界面把该会议行动"转为WBS任务" -> converted 后从负载剔除 -> admin 跨类未关闭降到 3, 解除过载.
    await row(page, actionTitle).getByRole('button', { name: '转为WBS任务', exact: true }).click();
    const convForm = modal(page, '会议行动转WBS任务');
    await convForm.locator('#start_date').fill('2026-09-23');
    await save(page, '会议行动转WBS任务');

    // 截图3: 行动台账回落到"未关闭 x 3"且不再显示"负载过重".
    await open(page, id, '需求与治理', '会议行动');
    await expect(row(page, actionTitle).getByText('未关闭 x 3')).toBeVisible();
    await expect(row(page, actionTitle).getByText('负载过重')).toHaveCount(0);
    await shot(page, 'g-load-3-after-convert.png');

    // 跨台账同步: 回到"风险与问题"页签, 问题/风险行也同步降到 3 且解除过载.
    await open(page, id, '需求与治理', '风险与问题');
    for (const title of [i1Title, i2Title, rTitle]) {
      await expect(row(page, title).getByText('未关闭 x 3')).toBeVisible();
      await expect(row(page, title).getByText('负载过重')).toHaveCount(0);
    }

    // 真实HTTP回显: 治理模型里 admin 三张台账各行 owner_open_load 均为 3.
    const gov = await api(page, 'GET', base(id) + '/governance');
    const adminLoad = r => r.owner_id === adminId ? r.owner_open_load : null;
    expect(gov.issues.filter(r => r.id).map(adminLoad).filter(v => v !== null)).toEqual([3, 3]);
    expect(gov.risks.map(adminLoad).filter(v => v !== null)).toEqual([3]);
    expect(gov.actions.map(adminLoad).filter(v => v !== null)).toEqual([3]);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
