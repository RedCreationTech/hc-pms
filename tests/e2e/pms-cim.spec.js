const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H09chicp 姊妹项: 变更量化影响数值分档分布只读汇总面板.
// 后端 approval.clj change-impact-magnitude 纯函数在 GET /governance 时派生, 按每个变更最新有效版本(s/latest, 修订链只计最新版)
//   对工期(schedule_impact_days)与成本(cost_impact_amount)两个可选量化影响字段的"取值大小"各自做穷举数值分档:
//   工期维五档 sched-unquantified(未量化) / sched-zero(=0) / sched-minor(1-4天) / sched-moderate(5-9天) / sched-high(10天及以上);
//   成本维五档 cost-unquantified(未量化) / cost-minor(<1万) / cost-moderate(1至5万) / cost-major(5至10万) / cost-high(10万及以上).
//   各维顶档由高影响阈值单一口径派生(工期阈值10天, 成本阈值10万=100000.00 最小单位一半作 major 门槛, 十分之一作 moderate 门槛),
//   与写路径 high-impact? 对齐; 每维五档之和恒等于 total; sched-quantified/cost-quantified 给出两维各自已量化数. 只读派生,
//   免迁移/免新命令/免新路由/不构成门控. 与"覆盖度"(是否申报)和"模式分布"(两维是否齐全)正交: 本项看已量化数值落在哪一档(大小分布),
//   回答"报了量化影响, 但到底是零敲碎打还是伤筋动骨".
// 本用例单浏览器上下文(admin): 建项目 -> 提出项目变更分档登记(高/高, 中/中, 轻/轻, 零/重大, 中/重大, 高/中, 仅文字, 仅工期),
//   断言服务端 change_impact_magnitude 与界面面板"变更量化影响数值分档分布"同源可见(总数/工期五档/成本五档/两维各自已量化数);
//   再补登一条"两维皆高影响"变更 -> sched-high+1 且 cost-high+1 (单上下文受控新增即翻转, 非双审批); 空项目态渲染占位.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/cim');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const changeForm = page => modal(page, '提出项目变更');
const base = id => `/api/pms/projects/${id}`;
const TITLE = '变更量化影响数值分档分布';

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

test.describe('变更量化影响数值分档分布只读面板浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('数值分档分布在界面真实可见, 服务端同源回显, 补登两维高影响变更后高档计数同步上调', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `CIM-${suffix}`, name: `变更量化影响数值分档分布验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '变更控制');

    // 1) 界面登记八条覆盖各档: (12天/10万)高低(1), (5天/3万)中中(2), (2天/5千)轻轻(3), (0天/5万)零/重大(4),
    //    (9天/99999.99)中/重大(5), (10天/1万)高/中(6), 仅文字(7), 仅工期4天不报成本(8).
    const c1 = await registerChange(page, { title: `高工期高成本变更-${suffix}`, days: 12, cost: '100000' });
    const c2 = await registerChange(page, { title: `中工期中成本变更-${suffix}`, days: 5, cost: '30000' });
    const c3 = await registerChange(page, { title: `轻工期轻成本变更-${suffix}`, days: 2, cost: '5000' });
    const c4 = await registerChange(page, { title: `零工期重大成本变更-${suffix}`, days: 0, cost: '50000' });
    const c5 = await registerChange(page, { title: `中工期重大成本变更-${suffix}`, days: 9, cost: '99999.99' });
    const c6 = await registerChange(page, { title: `高工期中成本变更-${suffix}`, days: 10, cost: '10000' });
    const c7 = await registerChange(page, { title: `仅文字描述变更-${suffix}` });
    const c8 = await registerChange(page, { title: `仅工期轻微变更-${suffix}`, days: 4 });

    // 命令回显验证量化字段写入与规范化(0 有效, 成本补两位小数, 留空不含键).
    expect(c1.schedule_impact_days).toBe(12);
    expect(c1.cost_impact_amount, '成本100000规范化').toBe('100000.00');
    expect(c4.schedule_impact_days, '工期0仍算申报').toBe(0);
    expect(c4.cost_impact_amount).toBe('50000.00');
    expect(c5.cost_impact_amount).toBe('99999.99');
    expect(c6.cost_impact_amount, '成本10000规范化').toBe('10000.00');
    expect(c7.schedule_impact_days == null && c7.cost_impact_amount == null, '仅文字: 两量化键均不含').toBeTruthy();
    expect(c8.schedule_impact_days).toBe(4);
    expect(c8.cost_impact_amount == null, '仅工期: 成本不含该键').toBeTruthy();

    // 2) 服务端只读派生回显(补登前): total 8.
    //    工期: unquantified{c7}=1, zero{c4}=1, minor{c3,c8}=2, moderate{c2,c5}=2, high{c1,c6}=2 -> sched-quantified=7.
    //    成本: unquantified{c7,c8}=2, minor{c3}=1, moderate{c2,c6}=2, major{c4,c5}=2, high{c1}=1 -> cost-quantified=6.
    const gov = await api(page, 'GET', base(id) + '/governance');
    const mag = gov.change_impact_magnitude;
    expect(mag.available).toBe(true);
    expect(mag.total).toBe(8);
    expect(mag['sched-unquantified']).toBe(1);
    expect(mag['sched-zero']).toBe(1);
    expect(mag['sched-minor']).toBe(2);
    expect(mag['sched-moderate']).toBe(2);
    expect(mag['sched-high']).toBe(2);
    expect(mag['sched-quantified']).toBe(7);
    expect(mag['sched-unquantified'] + mag['sched-zero'] + mag['sched-minor'] + mag['sched-moderate'] + mag['sched-high'], '工期五档穷尽等于总数').toBe(mag.total);
    expect(mag['cost-unquantified']).toBe(2);
    expect(mag['cost-minor']).toBe(1);
    expect(mag['cost-moderate']).toBe(2);
    expect(mag['cost-major']).toBe(2);
    expect(mag['cost-high']).toBe(1);
    expect(mag['cost-quantified']).toBe(6);
    expect(mag['cost-unquantified'] + mag['cost-minor'] + mag['cost-moderate'] + mag['cost-major'] + mag['cost-high'], '成本五档穷尽等于总数').toBe(mag.total);

    // 3) 界面面板真实渲染(与 change_impact_magnitude 同源).
    await open(page, id, '需求与治理', '变更控制');
    const card = panel(page, TITLE);
    await expect(card.getByText('变更总数 8', { exact: true })).toBeVisible();
    await expect(card.getByText('工期影响分档', { exact: true })).toBeVisible();
    await expect(card.getByText('成本影响分档', { exact: true })).toBeVisible();
    await expect(card.getByText('已量化 7', { exact: true })).toBeVisible();
    await expect(card.getByText('已量化 6', { exact: true })).toBeVisible();
    await expect(card.getByText('未量化 1', { exact: true })).toBeVisible();
    await expect(card.getByText('未量化 2', { exact: true })).toBeVisible();
    await expect(card.getByText('零影响 1', { exact: true })).toBeVisible();
    await expect(card.getByText('轻微 1-4 天 2', { exact: true })).toBeVisible();
    await expect(card.getByText('中等 5-9 天 2', { exact: true })).toBeVisible();
    await expect(card.getByText('高影响 10 天及以上 2', { exact: true })).toBeVisible();
    await expect(card.getByText('轻微 1 万以下 1', { exact: true })).toBeVisible();
    await expect(card.getByText('中等 1 至 5 万 2', { exact: true })).toBeVisible();
    await expect(card.getByText('重大 5 至 10 万 2', { exact: true })).toBeVisible();
    await expect(card.getByText('高影响 10 万及以上 1', { exact: true })).toBeVisible();
    await panelShot(page, TITLE, 'cim-1-before.png');

    // 4) 单上下文受控补登一条"两维皆高影响"变更 -> total 8->9, sched-high 2->3 且 cost-high 1->2,
    //    sched-quantified 7->8, cost-quantified 6->7, sched-unquantified 仍1, cost-unquantified 仍2.
    await open(page, id, '需求与治理', '变更控制');
    const c9 = await registerChange(page, { title: `两维皆高影响变更-${suffix}`, days: 15, cost: '200000' });
    expect(c9.schedule_impact_days).toBe(15);
    expect(c9.cost_impact_amount).toBe('200000.00');

    const gov2 = await api(page, 'GET', base(id) + '/governance');
    const mag2 = gov2.change_impact_magnitude;
    expect(mag2.total).toBe(9);
    expect(mag2['sched-high']).toBe(3);
    expect(mag2['cost-high']).toBe(2);
    expect(mag2['sched-quantified']).toBe(8);
    expect(mag2['cost-quantified']).toBe(7);
    expect(mag2['sched-unquantified']).toBe(1);
    expect(mag2['cost-unquantified']).toBe(2);

    await open(page, id, '需求与治理', '变更控制');
    const card2 = panel(page, TITLE);
    await expect(card2.getByText('变更总数 9', { exact: true })).toBeVisible();
    await expect(card2.getByText('高影响 10 天及以上 3', { exact: true })).toBeVisible();
    await expect(card2.getByText('高影响 10 万及以上 2', { exact: true })).toBeVisible();
    await expect(card2.getByText('高影响 10 万及以上 1', { exact: true })).toHaveCount(0);
    await panelShot(page, TITLE, 'cim-2-after-extra.png');

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
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `CIM-${suffix}e`, name: `变更量化影响数值分档分布空态验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    const gov = await api(page, 'GET', base(id) + '/governance');
    const mag = gov.change_impact_magnitude;
    expect(mag.available).toBe(false);
    expect(mag.total).toBe(0);
    expect(mag['sched-unquantified']).toBe(0);
    expect(mag['sched-zero']).toBe(0);
    expect(mag['sched-minor']).toBe(0);
    expect(mag['sched-moderate']).toBe(0);
    expect(mag['sched-high']).toBe(0);
    expect(mag['cost-unquantified']).toBe(0);
    expect(mag['cost-minor']).toBe(0);
    expect(mag['cost-moderate']).toBe(0);
    expect(mag['cost-major']).toBe(0);
    expect(mag['cost-high']).toBe(0);
    expect(mag['sched-quantified']).toBe(0);
    expect(mag['cost-quantified']).toBe(0);

    await open(page, id, '需求与治理', '变更控制');
    const card = panel(page, TITLE);
    await expect(card.getByText('登记变更后可在此查看量化影响数值分档分布', { exact: false })).toBeVisible();
    await panelShot(page, TITLE, 'cim-3-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
