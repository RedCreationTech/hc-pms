const { test, expect } = require('@playwright/test');
const path = require('node:path');

// 风险<->问题双向来源关联只读洞察: 风险经"发生转问题"后, 问题台账"来源风险"列回显来源风险标题(geekblue 标签),
// 风险台账"转出问题"列回显转出问题标题(cyan 标签); 手工登记问题显示"手工登记", 未转出的风险显示"未转出".
// 关联字段(source_risk_id / issue_id)本已持久化, 本用例验证其在读取时互相标注对方标题并界面可见. 标题用互不含子串的词避免 getByText 严格模式误命中.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/ri');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
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
  // 共享库累积用户/任务后长列表被 antd 虚拟滚动裁剪, 可搜索的下拉先按标签过滤再选.
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

const sevLabel = { blocker: '阻断', major: '严重', minor: '一般' };

// 登记一条 3x5=15 的风险(低于阈值16, 不触发超阈值升级), 到期日取远期避免倒计时徽标干扰标题匹配.
async function createRisk(page, id, { title, due }) {
  await open(page, id, '需求与治理', '风险与问题');
  await drawer(page).getByRole('button', { name: '登记项目风险', exact: true }).click();
  const form = modal(page, '登记项目风险');
  await form.locator('#title').fill(title);
  await form.locator('#probability').fill('3');
  await form.locator('#impact').fill('5');
  await choose(page, form, 'owner_id', 'admin');
  await form.locator('#mitigation').fill('备选供应商并持续跟踪.');
  await form.locator('#due_date').fill(due);
  return save(page, '登记项目风险');
}

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

// 在风险台账点"风险发生,转问题", 用独立标题创建转出问题(默认预填风险标题, 覆盖为可区分标题).
async function materialize(page, riskTitle, issueTitle) {
  await row(page, riskTitle).getByRole('button', { name: '风险发生,转问题', exact: true }).click();
  const form = modal(page, '风险转问题');
  await form.locator('#title').fill(issueTitle);
  return save(page, '风险转问题');
}

async function createProject(page, suffix, deptId, adminId) {
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `RI-${suffix}`, name: `来源关联验收 / ${suffix}`,
    project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
  return project.project_id;
}

test.describe('风险与问题双向来源关联只读洞察浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('风险转问题后两台账互显来源/转出标题; 手工问题标"手工登记"; HTTP回显双向 id 与标题', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const id = await createProject(page, suffix, deptId, adminId);

    const riskTitle = `供应商交付风险-${suffix}`;
    const materializedTitle = `到货延迟整改-${suffix}`;
    const manualTitle = `独立登记缺陷-${suffix}`;

    await createRisk(page, id, { title: riskTitle, due: dayOffset(60) });
    await createIssue(page, id, { title: manualTitle, severity: 'minor', due: dayOffset(80) });
    await materialize(page, riskTitle, materializedTitle);

    // 截图1 + 界面断言: 问题台账"来源风险"geekblue 标签 = 风险标题; 风险台账"转出问题"cyan 标签 = 转出问题标题.
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, materializedTitle).getByText(riskTitle, { exact: true })).toBeVisible();
    await expect(row(page, manualTitle).getByText('手工登记')).toBeVisible();
    await expect(row(page, riskTitle).getByText(materializedTitle, { exact: true })).toBeVisible();
    await shot(page, 'ri-1-bidirectional-source-link.png');

    // 服务端回显二次确认: 转出问题带 issue_source_risk_id/title 指回风险; 风险带 risk_issue_id/title 指回问题; 手工问题无来源.
    const gov = await api(page, 'GET', base(id) + '/governance');
    const materialized = gov.issues.find(r => r.title === materializedTitle);
    const manual = gov.issues.find(r => r.title === manualTitle);
    const risk = gov.risks.find(r => r.title === riskTitle);
    expect(materialized.issue_source_risk_id).toBe(risk.id);
    expect(materialized.issue_source_risk_title).toBe(riskTitle);
    expect(risk.risk_issue_id).toBe(materialized.id);
    expect(risk.risk_issue_title).toBe(materializedTitle);
    expect(manual.issue_source_risk_id).toBeFalsy();
    expect(manual.issue_source_risk_title).toBeFalsy();

    // 幂等: 再点同一风险的"转问题"若按钮仍在, 应命中同一问题(转出问题标题不变); 此处只回显断言已覆盖来源关联稳定.
    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
