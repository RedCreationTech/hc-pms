const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H09ct 延伸: 变更请求类型分布只读覆盖度面板.
// 后端 approval.clj change-type-coverage 纯函数在 GET /governance 时派生, 按每个变更最新有效版本(s/latest, 修订链只计最新版)
//   统计 PMBOK 四类变更请求(纠错性/预防性/缺陷修复/更新)各自计数, 已声明/未设定与覆盖率(coverage-pct), 只读派生,
//   免迁移/免新命令/免新路由/不构成门控; 与既有"变更控制闭环汇总"(状态/升级/CCB)正交互补, 是合规口径的类型侧速览.
// 本用例单浏览器上下文(admin): 建项目 -> "提出项目变更"分别登记四类各一条 + 一条不选类型(未设定),
//   断言服务端 change_type_coverage 与界面面板"变更请求类型分布"同源可见(变更总数/已标注类型百分比/未设定/四类标签计数);
//   再补登一条"更新"变更 -> updates 计数 1->2, 总数 5->6, 覆盖率随之上调 (单上下文受控新增即翻转, 非双审批);
//   空项目态渲染"暂无项目变更"占位.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/chtc');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const changeForm = page => modal(page, '提出项目变更');
const base = id => `/api/pms/projects/${id}`;
const TITLE = '变更请求类型分布';

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

// 打开"提出项目变更"填五维必填影响; 若给定 typeLabel 则再选可选变更类型; 保存返回命令 result.
async function registerChange(page, { title, typeLabel }) {
  await drawer(page).getByRole('button', { name: '提出项目变更', exact: true }).click();
  const form = changeForm(page);
  await form.locator('#title').fill(title);
  await form.locator('#reason').fill('合同范围调整');
  await form.locator('#scope_impact').fill('新增两台设备');
  await form.locator('#schedule_impact').fill('工期相应延长');
  await form.locator('#cost_impact').fill('需要重新估价');
  await form.locator('#quality_impact').fill('追加联调测试');
  await form.locator('#resource_impact').fill('追加一名工程师');
  if (typeLabel) await choose(page, form, 'change_type', typeLabel);
  return (await save(page, '提出项目变更')).result;
}

async function panelShot(page, title, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  const card = panel(page, title);
  await expect(card.getByRole('heading', { name: title, exact: true })).toBeVisible();
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.describe('变更请求类型分布只读面板浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('四类各一加未设定在界面真实可见, 服务端同源回显, 补登更新后类型计数与覆盖率同步上调', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `CHTC-${suffix}`, name: `变更类型分布验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '变更控制');

    // 1) 界面登记四类变更各一条 + 一条不选类型(未设定).
    const c1 = await registerChange(page, { title: `设备纠错变更-${suffix}`, typeLabel: '纠错性' });
    const c2 = await registerChange(page, { title: `风险预防变更-${suffix}`, typeLabel: '预防性' });
    const c3 = await registerChange(page, { title: `缺陷修复变更-${suffix}`, typeLabel: '缺陷修复' });
    const c4 = await registerChange(page, { title: `流程更新变更-${suffix}`, typeLabel: '更新' });
    const c5 = await registerChange(page, { title: `常规观察变更-${suffix}` });
    expect([c1.change_type, c2.change_type, c3.change_type, c4.change_type], '四类命令回显英文枚举').toEqual(
      ['corrective', 'preventive', 'defect-repair', 'updates']);
    expect(c5.change_type == null, '未选类型不含该键').toBeTruthy();

    // 2) 服务端只读派生回显 (补登前): 总数5, 已声明4, 未设定1, 覆盖率 round(100*4/5)=80.
    const gov = await api(page, 'GET', base(id) + '/governance');
    const cov = gov.change_type_coverage;
    expect(cov.available).toBe(true);
    expect(cov.total).toBe(5);
    expect(cov.declared).toBe(4);
    expect(cov.undeclared).toBe(1);
    expect(cov['coverage-pct']).toBe(80);
    const countOf = t => cov['by-type'].find(x => x.type === t).count;
    expect(countOf('corrective')).toBe(1);
    expect(countOf('preventive')).toBe(1);
    expect(countOf('defect-repair')).toBe(1);
    expect(countOf('updates')).toBe(1);
    expect(cov['by-type'].reduce((a, x) => a + x.count, 0)).toBe(4);

    // 3) 界面面板真实渲染: 汇总标签 + 四类计数标签可见 (与 change_type_coverage 同源).
    await open(page, id, '需求与治理', '变更控制');
    const card = panel(page, TITLE);
    await expect(card.getByText('变更总数 5', { exact: true })).toBeVisible();
    await expect(card.getByText('已标注类型 80%', { exact: true })).toBeVisible();
    await expect(card.getByText('未设定 1', { exact: true })).toBeVisible();
    await expect(card.getByText('纠错性 · 1', { exact: true })).toBeVisible();
    await expect(card.getByText('预防性 · 1', { exact: true })).toBeVisible();
    await expect(card.getByText('缺陷修复 · 1', { exact: true })).toBeVisible();
    await expect(card.getByText('更新 · 1', { exact: true })).toBeVisible();
    await expect(card.getByText('按请求类型:', { exact: true })).toBeVisible();
    await panelShot(page, TITLE, 'chtc-1-before.png');

    // 4) 单上下文受控补登一条"更新"变更 -> updates 计数 1->2, 总数 5->6, 覆盖率 round(100*5/6)=83 (可见翻转).
    await open(page, id, '需求与治理', '变更控制');
    const c6 = await registerChange(page, { title: `二次更新变更-${suffix}`, typeLabel: '更新' });
    expect(c6.change_type).toBe('updates');

    const gov2 = await api(page, 'GET', base(id) + '/governance');
    const cov2 = gov2.change_type_coverage;
    expect(cov2.total).toBe(6);
    expect(cov2.declared).toBe(5);
    expect(cov2.undeclared).toBe(1);
    expect(cov2['coverage-pct']).toBe(83);
    expect(cov2['by-type'].find(x => x.type === 'updates').count).toBe(2);
    expect(cov2['by-type'].find(x => x.type === 'corrective').count).toBe(1);

    await open(page, id, '需求与治理', '变更控制');
    const card2 = panel(page, TITLE);
    await expect(card2.getByText('变更总数 6', { exact: true })).toBeVisible();
    await expect(card2.getByText('已标注类型 83%', { exact: true })).toBeVisible();
    await expect(card2.getByText('更新 · 2', { exact: true })).toBeVisible();
    await expect(card2.getByText('更新 · 1', { exact: true })).toHaveCount(0);
    await panelShot(page, TITLE, 'chtc-2-after-extra.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });

  test('空项目面板渲染空态提示 (暂无项目变更)', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `CHTC-${suffix}e`, name: `变更类型分布空态验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    const gov = await api(page, 'GET', base(id) + '/governance');
    expect(gov.change_type_coverage.available).toBe(false);
    expect(gov.change_type_coverage.total).toBe(0);
    expect(gov.change_type_coverage.declared).toBe(0);
    expect(gov.change_type_coverage['coverage-pct']).toBe(0);
    expect(gov.change_type_coverage['by-type'].every(x => x.count === 0)).toBe(true);

    await open(page, id, '需求与治理', '变更控制');
    const card = panel(page, TITLE);
    await expect(card.getByText('登记变更后可在此查看请求类型分布', { exact: false })).toBeVisible();
    await panelShot(page, TITLE, 'chtc-3-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
