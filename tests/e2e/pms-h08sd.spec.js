const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H08 延伸: 风险评分(概率 x 影响)热力分布只读派生洞察 (免迁移, 无新命令/新kind/新路由).
// 界面"登记项目风险"里填不同概率x影响 -> "风险与问题"页签新增只读"风险评分热力分布"面板:
// 按每个风险最新有效版本把评分(1-25)落入低(1-5)/中(6-9)/高(10-15)/极高(>=16, 与超阈值升级门控对齐)四档计数,
// 附平均评分与高档及以上/极高计数; 再登记不同评分风险后分档与平均值翻转. 只读派生不改变任何风险状态.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h08sd');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
// shared/panel 渲染 <section> 内含 <h3> 标题; "风险总数""平均评分"等标签可能与姊妹面板重名, 须按面板标题作用域定位避免 strict-mode 串台.
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

// 打开"登记项目风险"填必填项并按给定概率x影响登记一条风险 (不选风险类别, 与覆盖度面板无关).
async function fillRisk(page, { title, p, i }) {
  await drawer(page).getByRole('button', { name: '登记项目风险', exact: true }).click();
  const form = modal(page, '登记项目风险');
  await form.locator('#title').fill(title);
  await form.locator('#probability').fill(String(p));
  await form.locator('#impact').fill(String(i));
  await choose(page, form, 'owner_id', 'admin');
  await form.locator('#mitigation').fill('持续监控并预留纠偏窗口.');
  await form.locator('#due_date').fill('2026-10-20');
}

test.describe('H08 风险评分热力分布只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('评分面板按最新风险分四档计数, 再登记不同评分风险后分档与平均值翻转', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `HSD-${suffix}`, name: `风险评分热力分布验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '风险与问题');

    // 1) 界面登记三条风险: 1x2=2(低) / 2x4=8(中) / 4x4=16(极高, 恰好达升级阈值).
    await fillRisk(page, { title: `观察项-${suffix}`, p: 1, i: 2 });
    await save(page, '登记项目风险');
    await fillRisk(page, { title: `交付依赖风险-${suffix}`, p: 2, i: 4 });
    await save(page, '登记项目风险');
    await fillRisk(page, { title: `关键断供风险-${suffix}`, p: 4, i: 4 });
    await save(page, '登记项目风险');

    // 2) 评分热力分布面板: 总数3, 平均评分 round((2+8+16)/3)=9, 高档及以上(>=10)2, 极高1; 低·1 中·1 高·0 极高·1.
    await open(page, id, '需求与治理', '风险与问题');
    const dist = panel(page, '风险评分热力分布');
    await expect(dist).toBeVisible();
    await expect(dist.getByText('风险总数 3', { exact: true })).toBeVisible();
    await expect(dist.getByText('平均评分 9', { exact: true })).toBeVisible();
    await expect(dist.getByText('高档及以上 1', { exact: true })).toBeVisible();
    await expect(dist.getByText('极高(达升级阈值) 1', { exact: true })).toBeVisible();
    await expect(dist.getByText('低 · 1', { exact: true })).toBeVisible();
    await expect(dist.getByText('中 · 1', { exact: true })).toBeVisible();
    await expect(dist.getByText('高 · 0', { exact: true })).toBeVisible();
    await expect(dist.getByText('极高 · 1', { exact: true })).toBeVisible();
    await panelShot(dist, 'h08sd-1-distribution.png');

    // 3) 真实HTTP读模型回显 risk_score_distribution.
    const ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.risk_score_distribution.total, '分母=最新有效版本风险数').toBe(3);
    expect(ws.risk_score_distribution.available, '有数据').toBe(true);
    expect(ws.risk_score_distribution['high-or-above'], '高档及以上(>=10)').toBe(1);
    expect(ws.risk_score_distribution.critical, '极高(>=16)').toBe(1);
    expect(ws.risk_score_distribution['avg-score'], '平均评分').toBe(9);
    const bandCount = k => ws.risk_score_distribution['by-band'].find(x => x.band === k).count;
    expect(bandCount('low'), '低档计数').toBe(1);
    expect(bandCount('medium'), '中档计数').toBe(1);
    expect(bandCount('high'), '高档计数').toBe(0);
    expect(bandCount('critical'), '极高档计数').toBe(1);

    // 4) 界面再登记两条: 2x2=4(低) 与 3x4=12(高) -> 总数5, 低·2 高·1, 平均评分 round(42/5)=8, 高档及以上仍2.
    await fillRisk(page, { title: `人员波动风险-${suffix}`, p: 2, i: 2 });
    await save(page, '登记项目风险');
    await fillRisk(page, { title: `成本超支风险-${suffix}`, p: 3, i: 4 });
    await save(page, '登记项目风险');
    await open(page, id, '需求与治理', '风险与问题');
    const dist2 = panel(page, '风险评分热力分布');
    await expect(dist2.getByText('风险总数 5', { exact: true })).toBeVisible();
    await expect(dist2.getByText('平均评分 8', { exact: true })).toBeVisible();
    await expect(dist2.getByText('低 · 2', { exact: true })).toBeVisible();
    await expect(dist2.getByText('高 · 1', { exact: true })).toBeVisible();
    await expect(dist2.getByText('极高 · 1', { exact: true })).toBeVisible();
    await panelShot(dist2, 'h08sd-2-after-more-risks.png');

    // 5) 真实HTTP回显翻转后状态一致.
    const ws2 = await api(page, 'GET', base(id) + '/governance');
    expect(ws2.risk_score_distribution.total, '总数翻到5').toBe(5);
    expect(ws2.risk_score_distribution['avg-score'], '平均评分翻到8').toBe(8);
    const bc2 = k => ws2.risk_score_distribution['by-band'].find(x => x.band === k).count;
    expect(bc2('low'), '低档升到2').toBe(2);
    expect(bc2('high'), '高档升到1').toBe(1);

    // 6) 只读派生不改变风险状态: 关键断供风险(4x4=16)仍登记态且评分不漂移.
    const supply = ws2.risks.find(r => r.title === `关键断供风险-${suffix}`);
    expect(supply.status, '既有风险仍为登记态(只读派生不改状态)').toBe('open');
    expect(supply.score, '风险评分不漂移').toBe(16);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
