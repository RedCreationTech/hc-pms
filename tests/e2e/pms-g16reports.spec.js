const { test, expect } = require('playwright/test');
const path = require('node:path');

// G16 报表指标定义与导出范围一致: 在 模板与规则 - 报表指标 建立并发布一个计数口径与一个金额口径的指标,
// 报表中心只对生效版本求值, 草稿如实列入未生效, 导出 CSV 的列与页面同列同序.
// 精确算式, 授权范围与状态过滤由 pms_report_test 断言, 本用例只验证界面闭环与真实 HTTP 合同.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/g16reports');

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

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => {
    document.querySelectorAll('.ant-message, .ant-message-notice').forEach(node => node.remove());
  });
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.describe('G16 报表指标定义与导出', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('定义并发布指标 -> 报表中心求值 -> 草稿不参与 -> 导出 CSV 同列', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const label = `验收进度均值${serial()}`;
    const draftLabel = `验收草稿指标${serial()}`;
    const existing = (await api(page, 'GET', '/api/pms/reports')).rows.map(row => row.code);

    // 生效指标: 项目字段聚合口径; 编码由 对象/聚合/字段/状态 派生, 同口径重复时后端如实 409.
    // 验收库可能已定义过同类口径, 因此按候选字段逐个尝试, 只用真实 200 的结果, 不用假数据绕过门控.
    let defined = null;
    for (const [aggregate, field] of [['avg', 'kit_percent'], ['sum', 'open_risks'], ['avg', 'days_to_end'],
                                      ['sum', 'overall_percent'], ['sum', 'kit_percent'], ['avg', 'open_issues']]) {
      const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
      const response = await page.request.fetch('/api/pms/config/report-metric', {
        method: 'POST', headers: { Authorization: `Bearer ${token}` },
        data: { object_type: 'project', aggregate, field, unit: 'percent', label },
      });
      const body = await response.json();
      if (body.code === 200) { defined = body.data; break; }
      expect(body.code).toBe(409);
    }
    expect(defined, '没有可用的新口径: 全部候选状态均已被定义').not.toBeNull();
    expect(defined.status).toBe('draft');
    const published = await api(page, 'POST', `/api/pms/config/report-metric/${defined.id}/publish`, { reason: 'G16 验收发布' });
    // 草稿只建不发布: 同样是真实命令, 报表中心必须把它排除在求值之外.
    let draft = null;
    for (const object of ['task', 'project', 'cost-entry']) {
      const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
      const response = await page.request.fetch('/api/pms/config/report-metric', {
        method: 'POST', headers: { Authorization: `Bearer ${token}` },
        data: { object_type: object, aggregate: 'count', status: 'draft', unit: 'count', label: `${draftLabel}${object}` },
      });
      const body = await response.json();
      if (body.code === 200) { draft = body.data; break; }
      expect(body.code).toBe(409);
    }
    expect(draft, '没有可用的草稿口径: 候选对象计数口径均已被定义').not.toBeNull();

    expect(published.status).toBe('published');
    expect(published.code).toBe(defined.code);
    expect(draft.status).toBe('draft');

    await page.goto('/pms/config');
    await page.getByRole('tab', { name: '报表指标', exact: true }).click();
    await expect(page.getByText(label, { exact: true }).first()).toBeVisible();
    await expect(page.getByText(draft.label, { exact: true }).first()).toBeVisible();
    await shot(page, 'pms-g16-config-metrics.png');

    await page.goto('/pms/reports');
    await expect(page.getByRole('heading', { name: '报表中心', exact: true })).toBeVisible();
    await expect(page.getByText(published.code, { exact: true }).first()).toBeVisible();
    await expect(page.getByText(draft.code, { exact: true })).toHaveCount(0);
    await expect(page.getByText(new RegExp(`${draft.code} V1`))).toBeVisible();
    await shot(page, 'pms-g16-reports.png');

    // 页面可见的列标题与 CSV 表头同序, 行数与指标数一致.
    const headers = page.locator('.ant-table-thead th');
    const titles = (await headers.allInnerTexts()).map(text => text.trim()).filter(Boolean);
    const data = await api(page, 'GET', '/api/pms/reports');
    expect(data.available).toBe(true);
    expect(titles).toEqual(data.columns.map(column => column.title));
    expect(data.rows.length).toBe(data.metric_count);
    expect(data.excluded.map(row => row.code)).toContain(draft.code);
    expect(data.rows.map(row => row.code)).not.toContain(draft.code);

    const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
    const response = await page.request.get('/api/pms/reports/export', { headers: { Authorization: `Bearer ${token}` } });
    expect(response.status()).toBe(200);
    const csv = (await response.body()).toString('utf8').replace(/^\uFEFF/, '');
    const lines = csv.trim().split(/\r?\n/);
    expect(lines[0]).toBe(data.columns.map(column => column.title).join(','));
    expect(lines.length).toBe(data.rows.length + 1);
    expect(csv).toContain(published.code);
    expect(csv).not.toContain(draft.code);

    // 退役后报表中心不再求值, 如实出现在未生效清单.
    await api(page, 'POST', `/api/pms/config/report-metric/${defined.id}/retire`, { reason: 'G16 验收退役' });
    const after = await api(page, 'GET', '/api/pms/reports');
    expect(after.rows.map(row => row.code)).not.toContain(published.code);
    expect(after.excluded.map(row => row.code)).toContain(published.code);
    expect(after.rows.map(row => row.code)).toEqual(existing.filter(code => after.rows.some(row => row.code === code)));

    expect(errors).toEqual([]);
  });
});