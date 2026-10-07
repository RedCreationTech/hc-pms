const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H09chic 延伸: 变更量化影响覆盖度只读汇总面板.
// 后端 approval.clj change-impact-coverage 纯函数在 GET /governance 时派生, 按每个变更最新有效版本(s/latest, 修订链只计最新版)
//   统计两个可选量化影响字段(schedule_impact_days / cost_impact_amount)的申报覆盖: schedule-declared(含 0)/cost-declared/
//   quantified(至少量化一维)/narrative-only(仅文字描述未量化)/quantified-pct(quantified/total 四舍五入)与 high-impact
//   (复用写路径私有 high-impact? 口径: 工期达阈值天数>=10 或 成本达阈值最小单位>=10000000 即 100000.00), 只读派生,
//   免迁移/免新命令/免新路由/不构成门控; 与"变更请求类型分布"及"变更控制闭环汇总"正交互补, 是量化影响合规口径速览.
// 本用例单浏览器上下文(admin): 建项目 -> "提出项目变更"分别登记 两维量化(达高影响) / 仅工期 / 仅成本 / 工期=0(仍算申报) / 仅文字,
//   断言服务端 change_impact_coverage 与界面面板"变更量化影响覆盖度"同源可见(总数/量化影响百分比/工期已量化/成本已量化/仅文字/达高影响阈值);
//   再补登一条"成本达高影响阈值"的量化变更 -> 成本已量化+1, 量化+1, 达高影响阈值+1, 量化率随之上调(单上下文受控新增即翻转, 非双审批);
//   空项目态渲染"暂无项目变更"占位.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/chic');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const changeForm = page => modal(page, '提出项目变更');
const base = id => `/api/pms/projects/${id}`;
const TITLE = '变更量化影响覆盖度';

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

// 打开"提出项目变更"填五维必填影响; 若给定 days/cost 则再填对应可选量化影响字段(留空即不申报该维); 保存返回命令 result.
async function registerChange(page, { title, days, cost }) {
  await drawer(page).getByRole('button', { name: '提出项目变更', exact: true }).click();
  const form = changeForm(page);
  await form.locator('#title').fill(title);
  await form.locator('#reason').fill('合同范围调整');
  await form.locator('#scope_impact').fill('新增两台设备');
  await form.locator('#schedule_impact').fill('工期相应延长');
  await form.locator('#cost_impact').fill('需要重新估价');
  await form.locator('#quality_impact').fill('追加联调测试');
  await form.locator('#resource_impact').fill('追加一名工程师');
  if (days != null) await form.locator('#schedule_impact_days').fill(String(days));
  if (cost != null) await form.locator('#cost_impact_amount').fill(String(cost));
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

test.describe('变更量化影响覆盖度只读面板浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('多维量化在界面真实可见, 服务端同源回显, 补登成本达阈值后量化率与高影响数同步上调', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `CHIC-${suffix}`, name: `变更量化影响覆盖度验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '变更控制');

    // 1) 界面登记: 两维量化(工期12天, 达高影响) / 仅工期(3天, 未达阈值) / 仅成本(99999.99, 未达阈值) / 工期=0(仍算申报, 未达阈值) / 仅文字描述(未量化).
    const chBoth = await registerChange(page, { title: `两维量化变更-${suffix}`, days: 12, cost: '150000.50' });
    const chSched = await registerChange(page, { title: `仅工期量化变更-${suffix}`, days: 3 });
    const chCost = await registerChange(page, { title: `仅成本量化变更-${suffix}`, cost: '99999.99' });
    const chZero = await registerChange(page, { title: `零工期量化变更-${suffix}`, days: 0 });
    const chPlain = await registerChange(page, { title: `仅文字描述变更-${suffix}` });

    // 命令回显验证量化字段写入与规范化 (0 是有效申报值, 留空则不含该键).
    expect(chBoth.schedule_impact_days, '两维: 量化工期回显').toBe(12);
    expect(chBoth.cost_impact_amount, '两维: 成本规范化两位小数').toBe('150000.50');
    expect(chSched.schedule_impact_days).toBe(3);
    expect(chSched.cost_impact_amount == null, '仅工期: 成本不含该键').toBeTruthy();
    expect(chCost.cost_impact_amount).toBe('99999.99');
    expect(chCost.schedule_impact_days == null, '仅成本: 工期不含该键').toBeTruthy();
    expect(chZero.schedule_impact_days, '工期0仍算申报').toBe(0);
    expect(chPlain.schedule_impact_days == null && chPlain.cost_impact_amount == null, '仅文字: 两量化键均不含').toBeTruthy();

    // 2) 服务端只读派生回显 (补登前): 总数5, 工期已量化3(两维+仅工期+0), 成本已量化2(两维+仅成本),
    //    quantified 4(至少量化一维), narrative-only 1, quantified-pct round(100*4/5)=80, high-impact 1(仅两维12天达标).
    const gov = await api(page, 'GET', base(id) + '/governance');
    const cov = gov.change_impact_coverage;
    expect(cov.available).toBe(true);
    expect(cov.total).toBe(5);
    expect(cov['schedule-declared']).toBe(3);
    expect(cov['cost-declared']).toBe(2);
    expect(cov.quantified).toBe(4);
    expect(cov['narrative-only']).toBe(1);
    expect(cov['quantified-pct']).toBe(80);
    expect(cov['high-impact']).toBe(1);

    // 3) 界面面板真实渲染: 六枚汇总标签可见 (与 change_impact_coverage 同源).
    await open(page, id, '需求与治理', '变更控制');
    const card = panel(page, TITLE);
    await expect(card.getByText('变更总数 5', { exact: true })).toBeVisible();
    await expect(card.getByText('量化影响 80%', { exact: true })).toBeVisible();
    await expect(card.getByText('工期影响已量化 3', { exact: true })).toBeVisible();
    await expect(card.getByText('成本影响已量化 2', { exact: true })).toBeVisible();
    await expect(card.getByText('仅文字描述 1', { exact: true })).toBeVisible();
    await expect(card.getByText('达高影响阈值 1', { exact: true })).toBeVisible();
    await panelShot(page, TITLE, 'chic-1-before.png');

    // 4) 单上下文受控补登一条"仅成本且达高影响阈值"变更 -> 成本已量化 2->3, quantified 4->5, 达高影响 1->2,
    //    总数 5->6, quantified-pct round(100*5/6)=83 (可见翻转).
    await open(page, id, '需求与治理', '变更控制');
    const chCostHigh = await registerChange(page, { title: `高成本量化变更-${suffix}`, cost: '200000.00' });
    expect(chCostHigh.cost_impact_amount).toBe('200000.00');

    const gov2 = await api(page, 'GET', base(id) + '/governance');
    const cov2 = gov2.change_impact_coverage;
    expect(cov2.total).toBe(6);
    expect(cov2['schedule-declared']).toBe(3);
    expect(cov2['cost-declared']).toBe(3);
    expect(cov2.quantified).toBe(5);
    expect(cov2['narrative-only']).toBe(1);
    expect(cov2['quantified-pct']).toBe(83);
    expect(cov2['high-impact']).toBe(2);

    await open(page, id, '需求与治理', '变更控制');
    const card2 = panel(page, TITLE);
    await expect(card2.getByText('变更总数 6', { exact: true })).toBeVisible();
    await expect(card2.getByText('量化影响 83%', { exact: true })).toBeVisible();
    await expect(card2.getByText('成本影响已量化 3', { exact: true })).toBeVisible();
    await expect(card2.getByText('达高影响阈值 2', { exact: true })).toBeVisible();
    await expect(card2.getByText('成本影响已量化 2', { exact: true })).toHaveCount(0);
    await panelShot(page, TITLE, 'chic-2-after-extra.png');

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
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `CHIC-${suffix}e`, name: `变更量化影响覆盖度空态验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    const gov = await api(page, 'GET', base(id) + '/governance');
    expect(gov.change_impact_coverage.available).toBe(false);
    expect(gov.change_impact_coverage.total).toBe(0);
    expect(gov.change_impact_coverage.quantified).toBe(0);
    expect(gov.change_impact_coverage['quantified-pct']).toBe(0);
    expect(gov.change_impact_coverage['schedule-declared']).toBe(0);
    expect(gov.change_impact_coverage['cost-declared']).toBe(0);
    expect(gov.change_impact_coverage['high-impact']).toBe(0);

    await open(page, id, '需求与治理', '变更控制');
    const card = panel(page, TITLE);
    await expect(card.getByText('登记变更后可在此查看量化影响覆盖度', { exact: false })).toBeVisible();
    await panelShot(page, TITLE, 'chic-3-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
