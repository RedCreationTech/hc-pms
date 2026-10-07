const { test, expect } = require('@playwright/test');
const path = require('node:path');

// 会议节奏与间隔分布: 按每个会议最新有效版本只读聚合举办时间节奏(首末跨度/相邻间隔最小平均最大/逐月分布与最热月份/星期分布).
// 后端 collaboration.clj meeting-cadence-summary 纯函数在 GET /governance 时派生, 以最新有效(非作废)且带 held_on 的会议为口径,
//   首末会议日期与跨天数 span-days, 相邻间隔 shortest/avg/longest-gap-days, 按自然月 YYYY-MM 回显 by-month 与最热月份 busiest-month 及跨月数,
//   按周一到周日固定序回显实际出现的星期 by-weekday; 作废最新版会议即退出全部时间口径; 只读派生, 免迁移/免新命令/不构成门控.
// 本用例单浏览器上下文(admin)登记三场会(跨两月): MC1 2026-05-05 例会 + MC2 2026-05-19 评审会 + MC3 2026-06-16 例会 (kickoff类型有会前包门控, 故只用未受门控类型).
//   作废前: total 3, span 42 天, 间隔 14/28 -> avg round(42/2)=21, shortest 14, longest 28, distinct-months 2,
//          by-month [{2026-05 2}{2026-06 1}], busiest-month {2026-05 2}, by-weekday 出现三档且计数和为 3.
//   作废 MC3(2026-06-16) 后 (单上下文受控作废): total 2, discarded 1, 只剩 2026-05 两场均间隔14 -> span 14, avg 14, longest 14,
//          distinct-months 1, by-month [{2026-05 2}], busiest-month {2026-05 2}; 面板"已作废会议 1"徽标出现, "跨 1 个月".
//   界面面板在"需求与治理 -> 会议行动"页签真实可见, 与服务端 meeting_cadence 同源.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/mtg-cadence');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) }).first();
const base = id => `/api/pms/projects/${id}`;
const TITLE = '会议节奏与间隔分布';

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

async function panelShot(page, title, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  const card = panel(page, title);
  await expect(card.getByRole('heading', { name: title, exact: true })).toBeVisible();
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.describe('会议节奏与间隔分布只读面板浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('三场跨两月会议的首末跨度/间隔/按月分布在界面真实可见, 作废末场后跨度与跨月数同步回落', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `MC-${suffix}`, name: `会议节奏验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-05-01', end_date: '2026-12-31' });
    const id = project.project_id;

    const mc1 = (await mutateData(page, id, '/governance/meetings',
      { title: `五月启动周会-${suffix}`, held_on: '2026-05-05', minutes: '范围对齐', attendee_ids: [adminId], meeting_type: 'regular' })).result;
    const mc2 = (await mutateData(page, id, '/governance/meetings',
      { title: `五月设计评审-${suffix}`, held_on: '2026-05-19', minutes: '方案评审', attendee_ids: [adminId], meeting_type: 'review' })).result;
    const mc3 = (await mutateData(page, id, '/governance/meetings',
      { title: `六月阶段例会-${suffix}`, held_on: '2026-06-16', minutes: '阶段复盘', attendee_ids: [adminId], meeting_type: 'regular' })).result;
    expect([mc1.id, mc2.id, mc3.id].filter(Boolean).length).toBe(3);

    // 服务端只读派生回显 (作废前).
    const gov = await api(page, 'GET', base(id) + '/governance');
    const cad = gov.meeting_cadence;
    expect(cad.available).toBe(true);
    expect(cad.total).toBe(3);
    expect(cad.discarded).toBe(0);
    expect(cad['first-held']).toBe('2026-05-05');
    expect(cad['last-held']).toBe('2026-06-16');
    expect(cad['span-days']).toBe(42);
    expect(cad['shortest-gap-days']).toBe(14);
    expect(cad['longest-gap-days']).toBe(28);
    expect(cad['avg-gap-days']).toBe(21);
    expect(cad['distinct-months']).toBe(2);
    expect(cad['by-month']).toEqual([
      { month: '2026-05', count: 2 },
      { month: '2026-06', count: 1 },
    ]);
    expect(cad['busiest-month']).toEqual({ month: '2026-05', count: 2 });
    // 星期分布: 三场各计一档, 计数和为 3.
    expect(cad['by-weekday'].reduce((a, w) => a + w.count, 0)).toBe(3);

    // 界面: 面板真实渲染, 汇总标签/举办区间/按月分布可见.
    await open(page, id, '需求与治理', '会议行动');
    const card = panel(page, TITLE);
    await expect(card.getByText('有效会议 3', { exact: true })).toBeVisible();
    await expect(card.getByText('首末跨度 42 天', { exact: true })).toBeVisible();
    await expect(card.getByText('平均间隔 21 天', { exact: true })).toBeVisible();
    await expect(card.getByText('最近间隔 14 天 · 最长间隔 28 天', { exact: true })).toBeVisible();
    await expect(card.getByText('跨 2 个月', { exact: true })).toBeVisible();
    await expect(card.getByText('最热月份 2026-05 · 2 场', { exact: true })).toBeVisible();
    // 作废徽标未出现 (discarded=0).
    await expect(card.getByText(/已作废会议\s+\d+/)).toHaveCount(0);
    // 举办区间与按月分布行.
    await expect(card.getByText('举办区间:', { exact: true })).toBeVisible();
    await expect(card.getByText('2026-05-05 ~ 2026-06-16', { exact: true })).toBeVisible();
    await expect(card.getByText('按月分布:', { exact: true })).toBeVisible();
    await expect(card.getByText('2026-05 · 2 场', { exact: true })).toBeVisible();
    await expect(card.getByText('2026-06 · 1 场', { exact: true })).toBeVisible();
    await expect(card.getByText('按星期分布:', { exact: true })).toBeVisible();
    await panelShot(page, TITLE, 'mtg-cadence-1-before.png');

    // 受控作废 MC3(2026-06-16) -> 退出时间口径 -> 跨度与跨月数回落 (单上下文可见).
    await mutateData(page, id, `/governance/meetings/${mc3.id}/discard`, { reason: '演示作废末场会议' });

    const gov2 = await api(page, 'GET', base(id) + '/governance');
    const cad2 = gov2.meeting_cadence;
    expect(cad2.total).toBe(2);
    expect(cad2.discarded).toBe(1);
    expect(cad2['last-held']).toBe('2026-05-19');
    expect(cad2['span-days']).toBe(14);
    expect(cad2['shortest-gap-days']).toBe(14);
    expect(cad2['longest-gap-days']).toBe(14);
    expect(cad2['avg-gap-days']).toBe(14);
    expect(cad2['distinct-months']).toBe(1);
    expect(cad2['by-month']).toEqual([{ month: '2026-05', count: 2 }]);
    expect(cad2['busiest-month']).toEqual({ month: '2026-05', count: 2 });

    await open(page, id, '需求与治理', '会议行动');
    const card2 = panel(page, TITLE);
    await expect(card2.getByText('有效会议 2', { exact: true })).toBeVisible();
    await expect(card2.getByText('首末跨度 14 天', { exact: true })).toBeVisible();
    await expect(card2.getByText('跨 1 个月', { exact: true })).toBeVisible();
    await expect(card2.getByText('已作废会议 1', { exact: true })).toBeVisible();
    await expect(card2.getByText('平均间隔 21 天', { exact: true })).toHaveCount(0);
    // 2026-06 已从按月分布消失.
    await expect(card2.getByText('2026-06 · 1 场', { exact: true })).toHaveCount(0);
    await panelShot(page, TITLE, 'mtg-cadence-2-after-discard.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });

  test('空项目面板渲染空态提示 (暂无带举办日期的有效会议)', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `MC-${suffix}e`, name: `会议节奏空态验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-05-01', end_date: '2026-12-31' });
    const id = project.project_id;

    const gov = await api(page, 'GET', base(id) + '/governance');
    expect(gov.meeting_cadence.available).toBe(false);
    expect(gov.meeting_cadence.total).toBe(0);
    expect(gov.meeting_cadence['by-month']).toEqual([]);
    expect(gov.meeting_cadence['by-weekday']).toEqual([]);

    await open(page, id, '需求与治理', '会议行动');
    const card = panel(page, TITLE);
    await expect(card.getByText('暂无带举办日期的有效会议, 登记会议并填写举办日期后可在此查看节奏与间隔分布.', { exact: false })).toBeVisible();
    await panelShot(page, TITLE, 'mtg-cadence-3-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
