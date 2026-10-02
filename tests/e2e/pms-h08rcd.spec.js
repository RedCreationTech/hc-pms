const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H08 延伸: 风险复审到期节奏项目级只读派生洞察 (免迁移, 无新命令/新kind/新路由).
// 界面"登记项目风险"里填不同到期日 -> "风险与问题"页签新增只读"风险复审到期节奏"面板:
// 按每个风险最新有效版本把未关闭风险三分已逾期(到期日<=今天)/临期(1-3天内)/未来到期(距今>临期窗口),
// 并给出待复审/已关闭计数; 再登记不同到期日风险后各桶实时翻转. 只读派生与逐条台账到期倒计时同源, 不改变风险状态.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h08rcd');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
// shared/panel 渲染 <section> 内含 <h3> 标题; "风险总数"等标签与姊妹面板重名, 须按面板标题作用域定位避免 strict-mode 串台.
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) });
const base = id => `/api/pms/projects/${id}`;

// test.use 必须置于文件顶层 (放进 describe 会强制新 worker 报错).
test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

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

// 同表单多个 antd Select 的下拉面板会同时留在 DOM, 用 combobox 自身 aria-controls 过滤其下拉避免串台.
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

// 面板在抽屉自身滚动容器里靠下, 通用整页截不到 -> 滚动到面板元素并做元素级截图.
async function panelShot(cov, file) {
  await cov.page().waitForLoadState('networkidle');
  await cov.scrollIntoViewIfNeeded();
  await cov.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 相对今天偏移 offsetDays 天算出 YYYY-MM-DD (用本地日历, 免疫 today 漂移).
function isoDate(offsetDays) {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${m}-${day}`;
}

// 打开"登记项目风险"填必填项并按给定到期日登记一条低风险 (1x3=3 不触发升级门控, 只影响到期节奏).
async function fillRisk(page, { title, due }) {
  await drawer(page).getByRole('button', { name: '登记项目风险', exact: true }).click();
  const form = modal(page, '登记项目风险');
  await form.locator('#title').fill(title);
  await form.locator('#probability').fill('1');
  await form.locator('#impact').fill('3');
  await choose(page, form, 'owner_id', 'admin');
  await form.locator('#mitigation').fill('持续监控并按到期节奏复审.');
  await form.locator('#due_date').fill(due);
}

test.describe('H08 风险复审到期节奏只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('到期节奏面板按最新风险三分已逾期/临期/未来到期, 再登记不同到期日风险后各桶翻转', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `HRCD-${suffix}`, name: `风险复审到期节奏验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '风险与问题');

    const overdue = isoDate(-5);
    const soon = isoDate(2);
    const far = isoDate(30);

    // 1) 界面登记三条未关闭风险: 到期日 -5(已逾期) / +2(临期<=3天) / +30(未来到期).
    await fillRisk(page, { title: `已逾期复审风险-${suffix}`, due: overdue });
    await save(page, '登记项目风险');
    await fillRisk(page, { title: `临期复审风险-${suffix}`, due: soon });
    await save(page, '登记项目风险');
    await fillRisk(page, { title: `未来复审风险-${suffix}`, due: far });
    await save(page, '登记项目风险');

    // 2) 到期节奏面板: 总数3, 待复审3, 无已关闭, 已逾期1 / 临期1 / 未来到期1.
    await open(page, id, '需求与治理', '风险与问题');
    const cad = panel(page, '风险复审到期节奏');
    await expect(cad).toBeVisible();
    await expect(cad.getByText('风险总数 3', { exact: true })).toBeVisible();
    await expect(cad.getByText('待复审 3', { exact: true })).toBeVisible();
    await expect(cad.getByText('已逾期 1', { exact: true })).toBeVisible();
    await expect(cad.getByText('临期 1', { exact: true })).toBeVisible();
    await expect(cad.getByText('未来到期 1', { exact: true })).toBeVisible();
    await expect(cad.getByText(/已关闭\s+\d+/)).toHaveCount(0);
    await panelShot(cad, 'h08rcd-1-cadence.png');

    // 3) 真实HTTP读模型回显 risk_review_cadence.
    const ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.risk_review_cadence.available, '有数据').toBe(true);
    expect(ws.risk_review_cadence.total, '分母=最新有效版本风险数').toBe(3);
    expect(ws.risk_review_cadence.open, '待复审').toBe(3);
    expect(ws.risk_review_cadence.closed, '已关闭').toBe(0);
    expect(ws.risk_review_cadence.overdue, '已逾期').toBe(1);
    expect(ws.risk_review_cadence['due-soon'], '临期').toBe(1);
    expect(ws.risk_review_cadence.upcoming, '未来到期').toBe(1);

    // 4) 界面再登记一条临期(+1)风险 -> 总数4, 待复审4, 临期2, 已逾期1, 未来到期1.
    await fillRisk(page, { title: `又临期风险-${suffix}`, due: isoDate(1) });
    await save(page, '登记项目风险');
    await open(page, id, '需求与治理', '风险与问题');
    const cad2 = panel(page, '风险复审到期节奏');
    await expect(cad2.getByText('风险总数 4', { exact: true })).toBeVisible();
    await expect(cad2.getByText('待复审 4', { exact: true })).toBeVisible();
    await expect(cad2.getByText('已逾期 1', { exact: true })).toBeVisible();
    await expect(cad2.getByText('临期 2', { exact: true })).toBeVisible();
    await expect(cad2.getByText('未来到期 1', { exact: true })).toBeVisible();
    await panelShot(cad2, 'h08rcd-2-after-more-risks.png');

    // 5) 真实HTTP回显翻转后一致.
    const ws2 = await api(page, 'GET', base(id) + '/governance');
    expect(ws2.risk_review_cadence.total, '总数翻到4').toBe(4);
    expect(ws2.risk_review_cadence['due-soon'], '临期升到2').toBe(2);
    expect(ws2.risk_review_cadence.open, '待复审翻到4').toBe(4);

    // 6) 只读派生不改变风险状态: 已逾期风险仍为登记态且到期倒计时标记不漂移.
    const od = ws2.risks.find(r => r.title === `已逾期复审风险-${suffix}`);
    expect(od.status, '既有风险仍为登记态(只读派生不改状态)').toBe('open');
    expect(od.review_overdue, '逐条到期倒计时标记与汇总同源').toBe(true);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
