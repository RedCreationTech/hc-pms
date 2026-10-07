const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H09chicp 延伸: 变更量化影响申报模式分布只读汇总面板.
// 后端 approval.clj change-impact-pattern 纯函数在 GET /governance 时派生, 按每个变更最新有效版本(s/latest, 修订链只计最新版)
//   对工期(schedule_impact_days)与成本(cost_impact_amount)两个可选量化影响字段做联合申报模式分布:
//   both(两维皆量化) / schedule-only(仅工期) / cost-only(仅成本) / neither(两维皆未量化, 仅文字描述) 构成对 total 的穷尽分区
//   (both+schedule-only+cost-only+neither=total); full-pct=round(100*both/total) 两维齐全率; pattern-level 三档
//   (both 过半 -> thorough / 否则至少半数量化 -> partial / 否则 -> sparse, total=0 时 nil). 只读派生,
//   免迁移/免新命令/免新路由/不构成门控; 与"变更量化影响覆盖度"(两维各自计数)正交互补: 覆盖度看两维分别量化了多少,
//   本项看同一条变更是否两维齐全(联合模式), 揭示"报了工期却漏报成本"这类半量化.
// 本用例单浏览器上下文(admin): 建项目 -> "提出项目变更"分别登记 两维量化 / 仅工期 / 仅成本 / 工期=0(仍算申报) / 仅文字,
//   断言服务端 change_impact_pattern 与界面面板"变更量化影响申报模式分布"同源可见(总数/两维齐全率/量化模式档/两维皆量化/仅工期/仅成本/两维皆未量化);
//   再补登一条"两维皆量化"变更 -> both+1 总数+1 两维齐全率随之上调(单上下文受控新增即翻转, 非双审批); 空项目态渲染占位.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/chicp');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const changeForm = page => modal(page, '提出项目变更');
const base = id => `/api/pms/projects/${id}`;
const TITLE = '变更量化影响申报模式分布';

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

test.describe('变更量化影响申报模式分布只读面板浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('联合申报模式分布在界面真实可见, 服务端同源回显, 补登两维量化后齐全率同步上调', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `CHICP-${suffix}`, name: `变更量化影响申报模式分布验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '变更控制');

    // 1) 界面登记四种联合模式: 两维皆量化(工期12+成本) / 仅工期(3天) / 仅成本(99999.99) / 仅工期=0(仍算申报) / 仅文字(两维皆未量化).
    const chBoth = await registerChange(page, { title: `两维量化变更-${suffix}`, days: 12, cost: '150000.50' });
    const chSched = await registerChange(page, { title: `仅工期量化变更-${suffix}`, days: 3 });
    const chCost = await registerChange(page, { title: `仅成本量化变更-${suffix}`, cost: '99999.99' });
    const chZero = await registerChange(page, { title: `零工期量化变更-${suffix}`, days: 0 });
    const chPlain = await registerChange(page, { title: `仅文字描述变更-${suffix}` });

    // 命令回显验证量化字段写入与规范化 (0 是有效申报值, 留空则不含该键), 供模式判定依据.
    expect(chBoth.schedule_impact_days, '两维: 量化工期回显').toBe(12);
    expect(chBoth.cost_impact_amount, '两维: 成本规范化两位小数').toBe('150000.50');
    expect(chSched.schedule_impact_days).toBe(3);
    expect(chSched.cost_impact_amount == null, '仅工期: 成本不含该键').toBeTruthy();
    expect(chCost.cost_impact_amount).toBe('99999.99');
    expect(chCost.schedule_impact_days == null, '仅成本: 工期不含该键').toBeTruthy();
    expect(chZero.schedule_impact_days, '工期0仍算申报').toBe(0);
    expect(chPlain.schedule_impact_days == null && chPlain.cost_impact_amount == null, '仅文字: 两量化键均不含').toBeTruthy();

    // 2) 服务端只读派生回显 (补登前): 总数5, both 1(chBoth), schedule-only 2(chSched, chZero), cost-only 1(chCost), neither 1(chPlain);
    //    穷尽分区 both+schedule-only+cost-only+neither=5; full-pct round(100*1/5)=20; quantified=both+schedule-only+cost-only=4,
    //    (* 2 both)=2<5 但 (* 2 quantified)=8>=5 -> pattern-level partial.
    const gov = await api(page, 'GET', base(id) + '/governance');
    const pat = gov.change_impact_pattern;
    expect(pat.available).toBe(true);
    expect(pat.total).toBe(5);
    expect(pat.both).toBe(1);
    expect(pat['schedule-only']).toBe(2);
    expect(pat['cost-only']).toBe(1);
    expect(pat.neither).toBe(1);
    expect(pat.both + pat['schedule-only'] + pat['cost-only'] + pat.neither, '四模式穷尽分区等于总数').toBe(pat.total);
    expect(pat['full-pct']).toBe(20);
    expect(pat['pattern-level']).toBe('partial');

    // 3) 界面面板真实渲染: 汇总标签可见 (与 change_impact_pattern 同源).
    await open(page, id, '需求与治理', '变更控制');
    const card = panel(page, TITLE);
    await expect(card.getByText('变更总数 5', { exact: true })).toBeVisible();
    await expect(card.getByText('两维齐全 20%', { exact: true })).toBeVisible();
    await expect(card.getByText('量化模式 部分量化', { exact: true })).toBeVisible();
    await expect(card.getByText('两维皆量化 1', { exact: true })).toBeVisible();
    await expect(card.getByText('仅工期 2', { exact: true })).toBeVisible();
    await expect(card.getByText('仅成本 1', { exact: true })).toBeVisible();
    await expect(card.getByText('两维皆未量化 1', { exact: true })).toBeVisible();
    await panelShot(page, TITLE, 'chicp-1-before.png');

    // 4) 单上下文受控补登一条"两维皆量化"变更 -> both 1->2, 总数 5->6, full-pct round(100*2/6)=33 (可见翻转),
    //    两维皆未量化仍1; quantified=both+schedule-only+cost-only=5, (* 2 both)=4<6 但 (* 2 quantified)=10>=6 -> 仍 partial.
    await open(page, id, '需求与治理', '变更控制');
    const chBoth2 = await registerChange(page, { title: `两维量化变更B-${suffix}`, days: 8, cost: '120000.00' });
    expect(chBoth2.schedule_impact_days).toBe(8);
    expect(chBoth2.cost_impact_amount).toBe('120000.00');

    const gov2 = await api(page, 'GET', base(id) + '/governance');
    const pat2 = gov2.change_impact_pattern;
    expect(pat2.total).toBe(6);
    expect(pat2.both).toBe(2);
    expect(pat2['schedule-only']).toBe(2);
    expect(pat2['cost-only']).toBe(1);
    expect(pat2.neither).toBe(1);
    expect(pat2.both + pat2['schedule-only'] + pat2['cost-only'] + pat2.neither).toBe(pat2.total);
    expect(pat2['full-pct']).toBe(33);
    expect(pat2['pattern-level']).toBe('partial');

    await open(page, id, '需求与治理', '变更控制');
    const card2 = panel(page, TITLE);
    await expect(card2.getByText('变更总数 6', { exact: true })).toBeVisible();
    await expect(card2.getByText('两维齐全 33%', { exact: true })).toBeVisible();
    await expect(card2.getByText('两维皆量化 2', { exact: true })).toBeVisible();
    await expect(card2.getByText('两维皆量化 1', { exact: true })).toHaveCount(0);
    await panelShot(page, TITLE, 'chicp-2-after-extra.png');

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
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `CHICP-${suffix}e`, name: `变更量化影响申报模式分布空态验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    const gov = await api(page, 'GET', base(id) + '/governance');
    expect(gov.change_impact_pattern.available).toBe(false);
    expect(gov.change_impact_pattern.total).toBe(0);
    expect(gov.change_impact_pattern.both).toBe(0);
    expect(gov.change_impact_pattern['schedule-only']).toBe(0);
    expect(gov.change_impact_pattern['cost-only']).toBe(0);
    expect(gov.change_impact_pattern.neither).toBe(0);
    expect(gov.change_impact_pattern['full-pct']).toBe(0);
    expect(gov.change_impact_pattern['pattern-level'], '空态 pattern-level 为 null').toBeNull();

    await open(page, id, '需求与治理', '变更控制');
    const card = panel(page, TITLE);
    await expect(card.getByText('登记变更后可在此查看量化影响申报模式分布', { exact: false })).toBeVisible();
    await panelShot(page, TITLE, 'chicp-3-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
