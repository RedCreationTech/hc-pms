const { test, expect } = require('@playwright/test');
const path = require('node:path');

// F09 季度经营目标达成组合级只读汇总 (只读派生): 经营目标看板页内"季度经营目标达成组合级汇总"面板.
// 免迁移/无新命令/无新路由. 面板由 portfolio/targets 读模型的 :attainment 派生: 按目标编码取当前最新版本
// (剔除已退役) 后依达成率分档 (达标/接近/落后) 统计目标数与达标率, 并按收入/毛利/结项数/准时率四指标给出分布.
// 真实达成率取季度内已关闭项目的已批准决算, 隔离新库无关闭项目 -> 全部落在"落后"档; 三档阈值与颜色由纯函数单元测试覆盖.
const output = path.resolve(__dirname, '../../reports/f09gas');
// 面板由 shared/panel 渲染为 <section>, 外层标题 h3 "季度目标与达成" 恒在; 汇总子标题仅在有在跟踪目标时出现.
const panel = page => page.locator('section').filter({ has: page.getByRole('heading', { name: '季度目标与达成', exact: true }) }).first();
const target = (page, quarter, metricLabel) => page.locator('tbody tr').filter({ hasText: `2026 ${quarter}` }).filter({ hasText: metricLabel });

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

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await input.press('Escape');
}

async function save(page, title) {
  const form = page.getByRole('dialog', { name: title, exact: true });
  const response = page.waitForResponse(r => r.url().includes('/api/pms/') && ['POST', 'PUT', 'DELETE'].includes(r.request().method()));
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(200);
  await expect(form).toBeHidden();
  await page.waitForLoadState('networkidle');
  return body.data;
}

async function panelShot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  const el = panel(page);
  await expect(el).toBeVisible();
  await el.scrollIntoViewIfNeeded();
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// HTTP 下达并发布一个季度目标, 返回该版本记录 (含 id/code/revision/status).
async function publishTarget(page, { year, quarter, metric, target_value, currency }) {
  const body = { year, quarter, metric, target_value, basis: 'E2E 组合达成汇总' };
  if (currency) body.currency = currency;
  const created = await api(page, 'POST', '/api/pms/config/quarterly-target', body);
  return api(page, 'POST', `/api/pms/config/quarterly-target/${created.id}/publish`, { reason: 'E2E 下达' });
}

// 清理此前运行遗留的非退役版本, 保证同库可重复执行.
async function cleanup(page, codes) {
  const rows = (await api(page, 'GET', '/api/pms/config/quarterly-target')).rows;
  for (const t of rows) {
    if (codes.includes(t.code) && ['draft', 'published'].includes(t.status)) {
      await api(page, 'POST', `/api/pms/config/quarterly-target/${t.id}/retire`, { reason: 'E2E 清理' });
    }
  }
}

const attainment = async page => (await api(page, 'GET', '/api/pms/targets/board')).attainment;
const metricRow = (att, m) => att['by-metric'].find(x => x.metric === m);

test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

test.describe('F09 季度经营目标达成组合级只读汇总', () => {
  test.setTimeout(240000);

  test('下达目标 -> 组合达成汇总面板 (在跟踪/落后/四指标分布), 修订去重与退役剔除', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const codes = ['2026Q1-revenue', '2026Q1-gross_margin', '2026Q1-closed_projects', '2026Q1-on_time_rate', '2026Q2-revenue', '2026Q2-gross_margin'];
    await login(page);
    await cleanup(page, codes);

    // 0. 空态: 无任何目标时面板显示引导文案.
    await page.goto('/pms/targets');
    await expect(page.getByRole('heading', { name: '经营目标看板' })).toBeVisible();
    await expect(panel(page).getByText('尚无在跟踪的经营目标, 下达目标后此处自动汇总组合达成情况.')).toBeVisible();
    await panelShot(page, 'f09-0-empty.png');
    expect((await attainment(page)).available).toBe(false);

    // 1. HTTP 下达并发布 5 个目标 (Q1 四指标 + Q2 收入), UI 下达并发布 1 个 (Q2 毛利), 共 6 个编码.
    await publishTarget(page, { year: 2026, quarter: 1, metric: 'revenue', target_value: '5000000.00', currency: 'CNY' });
    await publishTarget(page, { year: 2026, quarter: 1, metric: 'gross_margin', target_value: '1200000.00', currency: 'CNY' });
    await publishTarget(page, { year: 2026, quarter: 1, metric: 'closed_projects', target_value: 3 });
    await publishTarget(page, { year: 2026, quarter: 1, metric: 'on_time_rate', target_value: 90 });
    await publishTarget(page, { year: 2026, quarter: 2, metric: 'revenue', target_value: '6000000.00', currency: 'CNY' });
    // UI 路径: 下达季度目标 -> 发布目标版本, 证明界面写路径同样驱动只读面板刷新.
    await page.reload();
    await page.getByRole('button', { name: '下达季度目标' }).click();
    const form = page.getByRole('dialog', { name: '下达季度目标', exact: true });
    await form.locator('#year').fill('2026');
    await choose(page, form, 'quarter', 'Q2');
    await choose(page, form, 'metric', '毛利');
    await form.locator('#target_value').fill('1500000.00');
    await form.locator('#basis').fill('E2E 界面下达');
    const uiCreated = await save(page, '下达季度目标');
    const uiRow = target(page, 'Q2', '毛利').filter({ hasText: '草稿' }).first();
    await expect(uiRow).toBeVisible();
    await uiRow.getByRole('button', { name: '发布' }).click();
    await save(page, '发布目标版本');

    await page.reload();
    // 面板聚合 6 个在跟踪目标; 隔离新库无关闭项目 -> 达成率均 0 -> 全部"落后", 达标/接近为 0.
    await expect(panel(page).getByText('在跟踪目标 6')).toBeVisible();
    await expect(panel(page).getByText('落后 6 个')).toBeVisible();
    await expect(panel(page).getByText('达标 0 个 · 达标率 0%')).toBeVisible();
    await expect(panel(page).getByText('接近目标 0 个')).toBeVisible();
    // 四指标分布标签: 收入 2 / 毛利 2 / 结项数 1 / 准时率 1, 均达标 0.
    await expect(panel(page).getByText('收入 · 目标 2 个, 达标 0 个, 平均达成 0%')).toBeVisible();
    await expect(panel(page).getByText('毛利 · 目标 2 个, 达标 0 个, 平均达成 0%')).toBeVisible();
    await expect(panel(page).getByText('结项数 · 目标 1 个, 达标 0 个, 平均达成 0%')).toBeVisible();
    await expect(panel(page).getByText('准时结项率% · 目标 1 个, 达标 0 个, 平均达成 0%')).toBeVisible();
    await panelShot(page, 'f09-1-summary-six-tracked.png');
    let att = await attainment(page);
    expect(att.total).toBe(6);
    expect(att.met).toBe(0);
    expect(att.near).toBe(0);
    expect(att.behind).toBe(6);
    expect(att['met-pct']).toBe(0);
    expect(metricRow(att, 'revenue').total).toBe(2);
    expect(metricRow(att, 'gross_margin').total).toBe(2);
    expect(metricRow(att, 'closed_projects').total).toBe(1);
    expect(metricRow(att, 'on_time_rate').total).toBe(1);

    // 2. 修订去重: 对最新版本 Q1 收入 修订并发布 -> 同编码只按最新版本计一次, 在跟踪数不变 (6 而非 7).
    const publishedRev = (await api(page, 'GET', '/api/pms/config/quarterly-target')).rows.find(t => t.code === '2026Q1-revenue' && t.status === 'published');
    const newRev = await api(page, 'POST', `/api/pms/config/quarterly-target/${publishedRev.id}/revisions`, { year: 2026, quarter: 1, metric: 'revenue', target_value: '5500000.00', currency: 'CNY', basis: 'E2E 修订' });
    expect(newRev.revision).toBe(publishedRev.revision + 1);
    await api(page, 'POST', `/api/pms/config/quarterly-target/${newRev.id}/publish`, { reason: 'E2E 修订发布' });
    // 编码历史: 旧生效版本被取代为 retired, 新版本 published.
    const hist = (await api(page, 'GET', '/api/pms/config/quarterly-target')).rows.filter(t => t.code === '2026Q1-revenue');
    expect(hist.find(t => t.id === publishedRev.id).status).toBe('retired');
    expect(hist.find(t => t.id === newRev.id).status).toBe('published');
    await page.reload();
    await expect(panel(page).getByText('在跟踪目标 6')).toBeVisible(); // 未因新修订而增加
    await panelShot(page, 'f09-2-revision-dedup.png');
    att = await attainment(page);
    expect(att.total).toBe(6);
    expect(metricRow(att, 'revenue').total).toBe(2); // 仍按编码计, 不因两个版本而变 3

    // 3. 退役剔除: 退役 Q1 准时率 -> 面板在跟踪 5, 准时率分布标签消失.
    const ontime = (await api(page, 'GET', '/api/pms/config/quarterly-target')).rows.find(t => t.code === '2026Q1-on_time_rate' && t.status === 'published');
    await api(page, 'POST', `/api/pms/config/quarterly-target/${ontime.id}/retire`, { reason: 'E2E 退役' });
    await page.reload();
    await expect(panel(page).getByText('在跟踪目标 5')).toBeVisible();
    await expect(panel(page).getByText('落后 5 个')).toBeVisible();
    await expect(panel(page).getByText('收入 · 目标 2 个, 达标 0 个, 平均达成 0%')).toBeVisible();
    // 该指标全部退役 -> 汇总分布标签不再出现 (台账列仍保留该退役行的"准时率%"指标文本, 故按分布标签特征串断言).
    await expect(page.getByText(/准时结项率% · 目标/)).toHaveCount(0);
    await panelShot(page, 'f09-3-after-retire.png');
    att = await attainment(page);
    expect(att.total).toBe(5);
    expect(metricRow(att, 'on_time_rate').total).toBe(0);

    expect(errors).toEqual([]);
    expect(uiCreated.code).toBe('2026Q2-gross_margin');
  });
});
