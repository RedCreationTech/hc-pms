const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H02 延伸: 沟通受众覆盖度项目级只读派生洞察 (免迁移, 无新命令/新kind/新路由).
// 界面"登记干系人"+"登记沟通计划"(受众选已登记干系人) -> "干系人与沟通"页签新增只读"沟通受众覆盖度"面板:
// 按每个干系人与沟通计划最新有效版本(store/latest 折叠修订链), 统计有多少有效干系人被至少一条活动沟通计划受众覆盖:
// 干系人总数 / 沟通计划数 / 已覆盖 / 未覆盖 / 覆盖率并列出未覆盖干系人; 再登记覆盖新干系人的计划后各桶与缺件清单实时翻转.
// 只读派生复用沟通计划受众口径, 不落库不投递, 不改变任何不可变版本, 不构成任何门控.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h02cac');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
// shared/panel 渲染 <section> 内含 <h3> 标题; 按面板标题作用域定位避免 strict-mode 串台.
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

// 打开"登记干系人"填必填项并保存 (责任人 admin), 返回受众标签 "编号 / 名称".
async function createStakeholder(page, code) {
  await drawer(page).getByRole('button', { name: '登记干系人', exact: true }).click();
  const form = modal(page, '登记干系人');
  await form.locator('#code').fill(code);
  await form.locator('#name').fill('进度对接人');
  await form.locator('#role').fill('需求方接口');
  await choose(page, form, 'category', '客户');
  await choose(page, form, 'interest', 'high');
  await choose(page, form, 'influence', 'high');
  await choose(page, form, 'owner_id', 'admin');
  await save(page, '登记干系人');
  return `${code} / 进度对接人`;
}

// 打开"登记沟通计划"填必填项: 指定编号/频率/受众(单个干系人)/下次沟通日期, 保存.
async function createCommPlan(page, { code, audienceLabel }) {
  await drawer(page).getByRole('button', { name: '登记沟通计划', exact: true }).click();
  const form = modal(page, '登记沟通计划');
  await form.locator('#code').fill(code);
  await form.locator('#objective').fill('同步项目进度与风险');
  await choose(page, form, 'channel', '会议');
  await choose(page, form, 'frequency', 'weekly');
  await choose(page, form, 'audience', audienceLabel);
  await form.locator('#next_date').fill(isoDate(30));
  await choose(page, form, 'owner_id', 'admin');
  await save(page, '登记沟通计划');
}

test.describe('H02 沟通受众覆盖度只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('沟通受众覆盖度面板按最新计划受众聚合已覆盖/未覆盖/覆盖率并列出未覆盖干系人, 再登记覆盖新干系人的计划后各桶翻转', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `CAC-${suffix}`, name: `沟通受众覆盖度验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '干系人与沟通');

    // 先登记四个干系人 (编号唯一, 名称一致, 受众标签靠编号区分).
    const codes = ['A', 'B', 'C', 'D'].map(x => `SH-${x}-${suffix}`);
    const labels = [];
    for (const code of codes) labels.push(await createStakeholder(page, code));

    // 1) 尚无沟通计划: 总数4, 计划0, 已覆盖0, 未覆盖4, 覆盖率0%, 四个干系人全部列在缺件清单.
    const cov0 = panel(page, '沟通受众覆盖度');
    await expect(cov0).toBeVisible();
    await expect(cov0.getByText('干系人总数 4', { exact: true })).toBeVisible();
    await expect(cov0.getByText('沟通计划 0', { exact: true })).toBeVisible();
    await expect(cov0.getByText('已覆盖 0', { exact: true })).toBeVisible();
    await expect(cov0.getByText('未覆盖 4', { exact: true })).toBeVisible();
    await expect(cov0.getByText('覆盖率 0%', { exact: true })).toBeVisible();
    for (const code of codes) await expect(cov0.getByText(`${code} · 进度对接人`, { exact: true })).toBeVisible();
    await panelShot(cov0, 'h02cac-1-none-covered.png');

    // 2) 界面登记两条沟通计划, 受众分别覆盖 SH-A / SH-B.
    await createCommPlan(page, { code: `CP-A-${suffix}`, audienceLabel: labels[0] });
    await createCommPlan(page, { code: `CP-B-${suffix}`, audienceLabel: labels[1] });

    // 3) 面板翻转: 总数4, 计划2, 已覆盖2, 未覆盖2, 覆盖率50%; 缺件清单只剩 SH-C / SH-D, SH-A/SH-B 消失.
    await open(page, id, '需求与治理', '干系人与沟通');
    const cov1 = panel(page, '沟通受众覆盖度');
    await expect(cov1.getByText('干系人总数 4', { exact: true })).toBeVisible();
    await expect(cov1.getByText('沟通计划 2', { exact: true })).toBeVisible();
    await expect(cov1.getByText('已覆盖 2', { exact: true })).toBeVisible();
    await expect(cov1.getByText('未覆盖 2', { exact: true })).toBeVisible();
    await expect(cov1.getByText('覆盖率 50%', { exact: true })).toBeVisible();
    await expect(cov1.getByText(`${codes[2]} · 进度对接人`, { exact: true })).toBeVisible();
    await expect(cov1.getByText(`${codes[3]} · 进度对接人`, { exact: true })).toBeVisible();
    await expect(cov1.getByText(`${codes[0]} · 进度对接人`, { exact: true })).toHaveCount(0);
    await expect(cov1.getByText(`${codes[1]} · 进度对接人`, { exact: true })).toHaveCount(0);
    await panelShot(cov1, 'h02cac-2-two-covered.png');

    // 4) 真实HTTP读模型回显 comm_audience_coverage 与面板同源.
    const ws = await api(page, 'GET', base(id) + '/governance');
    const cc = ws.comm_audience_coverage;
    expect(cc.available, '有干系人').toBe(true);
    expect(cc.total, '分母=最新有效版本干系数').toBe(4);
    expect(cc.plans, '计划数=最新有效版本计划数').toBe(2);
    expect(cc.covered, '已覆盖').toBe(2);
    expect(cc.uncovered, '未覆盖').toBe(2);
    expect(cc['coverage-pct'], '覆盖率').toBe(50);
    const missingCodes = cc['uncovered-stakeholders'].map(x => x.code);
    expect(missingCodes, '缺件清单为 C/D').toEqual(expect.arrayContaining([codes[2], codes[3]]));
    expect(missingCodes, 'A 不在缺件清单').not.toContain(codes[0]);

    // 5) 再登记一条覆盖 SH-C 的计划 -> 已覆盖升到3, 未覆盖降到1, 覆盖率75%, 缺件只剩 SH-D.
    await createCommPlan(page, { code: `CP-C-${suffix}`, audienceLabel: labels[2] });
    await open(page, id, '需求与治理', '干系人与沟通');
    const cov2 = panel(page, '沟通受众覆盖度');
    await expect(cov2.getByText('沟通计划 3', { exact: true })).toBeVisible();
    await expect(cov2.getByText('已覆盖 3', { exact: true })).toBeVisible();
    await expect(cov2.getByText('未覆盖 1', { exact: true })).toBeVisible();
    await expect(cov2.getByText('覆盖率 75%', { exact: true })).toBeVisible();
    await expect(cov2.getByText(`${codes[3]} · 进度对接人`, { exact: true })).toBeVisible();
    await expect(cov2.getByText(`${codes[2]} · 进度对接人`, { exact: true })).toHaveCount(0);
    await panelShot(cov2, 'h02cac-3-three-covered.png');

    // 6) 真实HTTP回显翻转后一致.
    const ws2 = await api(page, 'GET', base(id) + '/governance');
    expect(ws2.comm_audience_coverage.total, '总数仍4').toBe(4);
    expect(ws2.comm_audience_coverage.plans, '计划升到3').toBe(3);
    expect(ws2.comm_audience_coverage.covered, '已覆盖升到3').toBe(3);
    expect(ws2.comm_audience_coverage.uncovered, '未覆盖降到1').toBe(1);
    expect(ws2.comm_audience_coverage['coverage-pct'], '覆盖率升到75').toBe(75);
    expect(ws2.comm_audience_coverage['uncovered-stakeholders'].map(x => x.code), '缺件只剩 D').toEqual([codes[3]]);

    // 7) 只读派生不改变干系人状态: 已覆盖干系人仍为登记态.
    const shA = ws2.stakeholders.find(s => s.code === codes[0]);
    expect(shA.status, '干系人仍为登记态(只读派生不改状态)').toBe('active');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
