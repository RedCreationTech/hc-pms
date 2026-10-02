const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H02 延伸: RACI 职责分配完整度项目级只读派生洞察 (免迁移, 无新命令/新kind/新路由).
// 界面"登记干系人" -> 逐活动"指派RACI职责"(执行R/负责A/咨询C/知会I) -> "干系人与沟通"页签新增只读"RACI职责分配完整度"面板:
// 按活动(不按修订链折叠, 与逐条冲突检查conflicts口径一致直接消费原始RACI行)判断每项活动是否同时指派了负责(A)与执行(R),
// 给出活动总数 / 职责齐备 / 缺负责A / 缺执行R / 完整覆盖率, 并列出未完整活动及其缺项; 每补齐一个A或R各桶实时翻转.
// 只读派生不落库不投递, 不改变任何不可变版本, 不构成任何门控; 与逐活动缺口冲突提示互补(冲突只列缺口子集而本项给出项目级正向覆盖率与完整分布).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h02raci');
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

// 打开"登记干系人"填必填项并保存 (责任人 admin), 返回下拉标签 "编号 / 名称".
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

// 打开"指派RACI职责"填活动 + 干系人 + 职责(R/A/C/I) 并保存.
async function assignRaci(page, { activity, shLabel, responsibility }) {
  await drawer(page).getByRole('button', { name: '指派RACI职责', exact: true }).click();
  const form = modal(page, '指派RACI职责');
  await expect(form).toBeVisible();
  await form.locator('#activity').fill(activity);
  await choose(page, form, 'stakeholder_id', shLabel);
  await choose(page, form, 'responsibility', responsibility);
  await save(page, '指派RACI职责');
}

test.describe('H02 RACI职责分配完整度只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('RACI完整度面板按活动聚合职责齐备/缺负责A/缺执行R/完整覆盖率, 每补齐A或R各桶实时翻转', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `RC-${suffix}`, name: `RACI职责分配完整度验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '干系人与沟通');

    // 登记三个干系人作为 RACI 职责承担者 (面板分母是活动数, 干系人仅需足够避免同活动同人重复指派).
    const sh1 = await createStakeholder(page, `SH1-${suffix}`);
    const sh2 = await createStakeholder(page, `SH2-${suffix}`);
    const sh3 = await createStakeholder(page, `SH3-${suffix}`);

    const ACT_OK = '需求评审';   // R+A -> 完整
    const ACT_MISS_R = '方案设计'; // 仅A -> 缺执行R
    const ACT_MISS_A = '编码实现'; // 仅R -> 缺负责A
    const ACT_MISS_BOTH = '联调测试'; // 仅C -> 双缺

    // 初始 5 条指派.
    await assignRaci(page, { activity: ACT_OK, shLabel: sh1, responsibility: '执行 R' });
    await assignRaci(page, { activity: ACT_OK, shLabel: sh2, responsibility: '负责 A' });
    await assignRaci(page, { activity: ACT_MISS_R, shLabel: sh1, responsibility: '负责 A' });
    await assignRaci(page, { activity: ACT_MISS_A, shLabel: sh2, responsibility: '执行 R' });
    await assignRaci(page, { activity: ACT_MISS_BOTH, shLabel: sh3, responsibility: '咨询 C' });

    // 1) 活动总数4/职责齐备1/缺负责A2/缺执行R2/完整覆盖率25%; 未完整清单含三种缺项标签.
    await open(page, id, '需求与治理', '干系人与沟通');
    const cov0 = panel(page, 'RACI职责分配完整度');
    await expect(cov0).toBeVisible();
    await expect(cov0.getByText('活动总数 4', { exact: true })).toBeVisible();
    await expect(cov0.getByText('职责齐备 1', { exact: true })).toBeVisible();
    await expect(cov0.getByText('缺负责A 2', { exact: true })).toBeVisible();
    await expect(cov0.getByText('缺执行R 2', { exact: true })).toBeVisible();
    await expect(cov0.getByText('完整覆盖率 25%', { exact: true })).toBeVisible();
    await expect(cov0.getByText(`${ACT_MISS_R} · 缺执行R`, { exact: true })).toBeVisible();
    await expect(cov0.getByText(`${ACT_MISS_A} · 缺负责A`, { exact: true })).toBeVisible();
    await expect(cov0.getByText(`${ACT_MISS_BOTH} · 缺负责A、缺执行R`, { exact: true })).toBeVisible();
    await expect(cov0.getByText(`${ACT_OK} ·`, { exact: false })).toHaveCount(0);
    await panelShot(cov0, 'h02raci-1-initial-25.png');

    // 2) 为"编码实现"补一个负责A -> 齐备2/缺负责A1/缺执行R2/覆盖率50%; 编码实现从未完整清单消失.
    await assignRaci(page, { activity: ACT_MISS_A, shLabel: sh3, responsibility: '负责 A' });
    await open(page, id, '需求与治理', '干系人与沟通');
    const cov1 = panel(page, 'RACI职责分配完整度');
    await expect(cov1.getByText('职责齐备 2', { exact: true })).toBeVisible();
    await expect(cov1.getByText('缺负责A 1', { exact: true })).toBeVisible();
    await expect(cov1.getByText('缺执行R 2', { exact: true })).toBeVisible();
    await expect(cov1.getByText('完整覆盖率 50%', { exact: true })).toBeVisible();
    await expect(cov1.getByText(`${ACT_MISS_A} ·`, { exact: false })).toHaveCount(0);
    await expect(cov1.getByText(`${ACT_MISS_R} · 缺执行R`, { exact: true })).toBeVisible();
    await expect(cov1.getByText(`${ACT_MISS_BOTH} · 缺负责A、缺执行R`, { exact: true })).toBeVisible();
    await panelShot(cov1, 'h02raci-2-partial-50.png');

    // 真实HTTP读模型回显 raci_assignment_coverage 与面板同源 (补齐前3项留一).
    const mid = await api(page, 'GET', base(id) + '/governance');
    const mc = mid.raci_assignment_coverage;
    expect(mc.available, '有RACI指派').toBe(true);
    expect(mc.total, '分母=活动数').toBe(4);
    expect(mc.complete, '职责齐备活动数').toBe(2);
    expect(mc['missing-accountable'], '缺负责A活动数').toBe(1);
    expect(mc['missing-responsible'], '缺执行R活动数').toBe(2);
    expect(mc['coverage-pct'], '覆盖率').toBe(50);
    const midInc = mc['incomplete-activities'].map(x => x.activity).sort();
    expect(midInc, '未完整清单=方案设计+联调测试').toEqual([ACT_MISS_BOTH, ACT_MISS_R].sort());

    // 3) 补齐余下缺项: 方案设计补R, 联调测试补A与R -> 全部四项职责齐备, 覆盖率100%, 缺项标签消失, 未完整清单清空.
    await assignRaci(page, { activity: ACT_MISS_R, shLabel: sh2, responsibility: '执行 R' });
    await assignRaci(page, { activity: ACT_MISS_BOTH, shLabel: sh1, responsibility: '负责 A' });
    await assignRaci(page, { activity: ACT_MISS_BOTH, shLabel: sh2, responsibility: '执行 R' });
    await open(page, id, '需求与治理', '干系人与沟通');
    const cov2 = panel(page, 'RACI职责分配完整度');
    await expect(cov2.getByText('活动总数 4', { exact: true })).toBeVisible();
    await expect(cov2.getByText('职责齐备 4', { exact: true })).toBeVisible();
    await expect(cov2.getByText('完整覆盖率 100%', { exact: true })).toBeVisible();
    await expect(cov2.getByText(/缺负责A/, { exact: false })).toHaveCount(0);
    await expect(cov2.getByText(/缺执行R/, { exact: false })).toHaveCount(0);
    await panelShot(cov2, 'h02raci-3-complete-100.png');

    // 4) 真实HTTP读模型最终回显 + 只读派生不改变RACI记录状态(9条原始行仍为指派态).
    const ws = await api(page, 'GET', base(id) + '/governance');
    const rc = ws.raci_assignment_coverage;
    expect(rc.total).toBe(4);
    expect(rc.complete).toBe(4);
    expect(rc['missing-accountable']).toBe(0);
    expect(rc['missing-responsible']).toBe(0);
    expect(rc['coverage-pct']).toBe(100);
    expect(rc['incomplete-activities'], '未完整清单清空').toEqual([]);
    expect(ws.raci.length, '9条RACI指派原始行').toBe(9);
    expect(ws.raci.every(r => r.status === 'assigned'), '只读派生不改RACI记录状态').toBe(true);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
