const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H02 延伸: RACI 咨询(C)与知会(I)角色配置覆盖度项目级只读派生洞察 (免迁移, 无新命令/新kind/新路由).
// 界面"登记干系人" -> 逐活动"指派RACI职责"(执行R/负责A/咨询C/知会I) -> "干系人与沟通"页签新增只读"RACI咨询知会覆盖度"面板:
// 按活动(不按修订链折叠, 与逐条冲突检查conflicts及职责分配完整度同口径直接消费原始RACI行)判断每项活动是否同时指派了咨询(C)与知会(I),
// 给出活动总数 / 咨询知会齐备 / 含咨询C / 含知会I / 咨询知会覆盖度, 并列出配置单薄活动及其缺项; 每补一个C或I各桶实时翻转.
// 只读派生不落库不投递, 不改变任何不可变版本, 不构成任何门控; 与只查负责A执行R的"职责分配完整度"互补(完整度回答谁做谁负责, 本项回答决策有没有被充分咨询、相关方有没有被知会).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h02raciie');
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

test.describe('H02 RACI咨询知会覆盖度只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('RACI咨询知会面板按活动聚合齐备/含咨询C/含知会I/覆盖度, 每补C或I各桶实时翻转', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `RC-${suffix}`, name: `RACI咨询知会覆盖度验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '干系人与沟通');

    // 登记四个干系人作为 RACI 职责承担者 (面板分母是活动数; 同活动同人不可重复指派, 故每活动各职责用不同人).
    const sh1 = await createStakeholder(page, `SH1-${suffix}`);
    const sh2 = await createStakeholder(page, `SH2-${suffix}`);
    const sh3 = await createStakeholder(page, `SH3-${suffix}`);
    const sh4 = await createStakeholder(page, `SH4-${suffix}`);

    const ACT_FULL = '需求评审';     // R+A+C+I -> 咨询知会齐备
    const ACT_MISS_I = '方案设计';   // R+A+C -> 有咨询缺知会
    const ACT_MISS_C = '编码实现';   // R+A+I -> 有知会缺咨询
    const ACT_MISS_BOTH = '联调测试'; // R+A -> 既缺咨询又缺知会

    // 初始 12 条指派: 每活动都有 R 与 A (保证职责分配完整度全齐备), 只在 C/I 维度制造差异.
    await assignRaci(page, { activity: ACT_FULL, shLabel: sh1, responsibility: '执行 R' });
    await assignRaci(page, { activity: ACT_FULL, shLabel: sh2, responsibility: '负责 A' });
    await assignRaci(page, { activity: ACT_FULL, shLabel: sh3, responsibility: '咨询 C' });
    await assignRaci(page, { activity: ACT_FULL, shLabel: sh4, responsibility: '知会 I' });
    await assignRaci(page, { activity: ACT_MISS_I, shLabel: sh1, responsibility: '执行 R' });
    await assignRaci(page, { activity: ACT_MISS_I, shLabel: sh2, responsibility: '负责 A' });
    await assignRaci(page, { activity: ACT_MISS_I, shLabel: sh3, responsibility: '咨询 C' });
    await assignRaci(page, { activity: ACT_MISS_C, shLabel: sh1, responsibility: '执行 R' });
    await assignRaci(page, { activity: ACT_MISS_C, shLabel: sh2, responsibility: '负责 A' });
    await assignRaci(page, { activity: ACT_MISS_C, shLabel: sh3, responsibility: '知会 I' });
    await assignRaci(page, { activity: ACT_MISS_BOTH, shLabel: sh1, responsibility: '执行 R' });
    await assignRaci(page, { activity: ACT_MISS_BOTH, shLabel: sh2, responsibility: '负责 A' });

    // 1) 活动总数4/咨询知会齐备1/含咨询C 2/含知会I 2/覆盖度25%; 单薄清单含三种缺项标签, 齐备活动不列.
    await open(page, id, '需求与治理', '干系人与沟通');
    const cov0 = panel(page, 'RACI咨询知会覆盖度');
    await expect(cov0).toBeVisible();
    await expect(cov0.getByText('活动总数 4', { exact: true })).toBeVisible();
    await expect(cov0.getByText('咨询知会齐备 1', { exact: true })).toBeVisible();
    await expect(cov0.getByText('含咨询C 2', { exact: true })).toBeVisible();
    await expect(cov0.getByText('含知会I 2', { exact: true })).toBeVisible();
    await expect(cov0.getByText('咨询知会覆盖度 25%', { exact: true })).toBeVisible();
    await expect(cov0.getByText(`${ACT_MISS_I} · 缺知会I`, { exact: true })).toBeVisible();
    await expect(cov0.getByText(`${ACT_MISS_C} · 缺咨询C`, { exact: true })).toBeVisible();
    await expect(cov0.getByText(`${ACT_MISS_BOTH} · 缺咨询C、缺知会I`, { exact: true })).toBeVisible();
    await expect(cov0.getByText(`${ACT_FULL} ·`, { exact: false })).toHaveCount(0);
    await panelShot(cov0, 'h02raciie-1-initial-25.png');

    // 2) 给"方案设计"补一个知会I -> 齐备2/含知会I 3/覆盖度50%; 方案设计从单薄清单消失.
    await assignRaci(page, { activity: ACT_MISS_I, shLabel: sh4, responsibility: '知会 I' });
    await open(page, id, '需求与治理', '干系人与沟通');
    const cov1 = panel(page, 'RACI咨询知会覆盖度');
    await expect(cov1.getByText('咨询知会齐备 2', { exact: true })).toBeVisible();
    await expect(cov1.getByText('含咨询C 2', { exact: true })).toBeVisible();
    await expect(cov1.getByText('含知会I 3', { exact: true })).toBeVisible();
    await expect(cov1.getByText('咨询知会覆盖度 50%', { exact: true })).toBeVisible();
    await expect(cov1.getByText(`${ACT_MISS_I} ·`, { exact: false })).toHaveCount(0);
    await expect(cov1.getByText(`${ACT_MISS_C} · 缺咨询C`, { exact: true })).toBeVisible();
    await expect(cov1.getByText(`${ACT_MISS_BOTH} · 缺咨询C、缺知会I`, { exact: true })).toBeVisible();
    await panelShot(cov1, 'h02raciie-2-partial-50.png');

    // 真实HTTP读模型回显 raci_engagement_coverage 与面板同源 (齐备2/覆盖50).
    const mid = await api(page, 'GET', base(id) + '/governance');
    const mc = mid.raci_engagement_coverage;
    expect(mc.available, '有RACI指派').toBe(true);
    expect(mc.total, '分母=活动数').toBe(4);
    expect(mc['with-consult'], '含咨询C活动数').toBe(2);
    expect(mc['with-inform'], '含知会I活动数').toBe(3);
    expect(mc['fully-engaged'], '齐备活动数').toBe(2);
    expect(mc['engagement-pct'], '覆盖度').toBe(50);
    const midThin = mc['thin-activities'].map(x => x.activity).sort();
    expect(midThin, '单薄清单=编码实现+联调测试').toEqual([ACT_MISS_C, ACT_MISS_BOTH].sort());

    // 3) 补齐余下缺项: 编码实现补咨询C, 联调测试补咨询C与知会I -> 四项齐备, 覆盖度100%, 缺项标签消失, 单薄清单清空.
    await assignRaci(page, { activity: ACT_MISS_C, shLabel: sh4, responsibility: '咨询 C' });
    await assignRaci(page, { activity: ACT_MISS_BOTH, shLabel: sh3, responsibility: '咨询 C' });
    await assignRaci(page, { activity: ACT_MISS_BOTH, shLabel: sh4, responsibility: '知会 I' });
    await open(page, id, '需求与治理', '干系人与沟通');
    const cov2 = panel(page, 'RACI咨询知会覆盖度');
    await expect(cov2.getByText('活动总数 4', { exact: true })).toBeVisible();
    await expect(cov2.getByText('咨询知会齐备 4', { exact: true })).toBeVisible();
    await expect(cov2.getByText('含咨询C 4', { exact: true })).toBeVisible();
    await expect(cov2.getByText('含知会I 4', { exact: true })).toBeVisible();
    await expect(cov2.getByText('咨询知会覆盖度 100%', { exact: true })).toBeVisible();
    await expect(cov2.getByText(/缺咨询C/, { exact: false })).toHaveCount(0);
    await expect(cov2.getByText(/缺知会I/, { exact: false })).toHaveCount(0);
    await panelShot(cov2, 'h02raciie-3-complete-100.png');

    // 4) 真实HTTP读模型最终回显 + 只读派生不改变RACI记录状态(16条原始行仍为指派态).
    const ws = await api(page, 'GET', base(id) + '/governance');
    const rc = ws.raci_engagement_coverage;
    expect(rc.total).toBe(4);
    expect(rc['with-consult']).toBe(4);
    expect(rc['with-inform']).toBe(4);
    expect(rc['fully-engaged']).toBe(4);
    expect(rc['engagement-pct']).toBe(100);
    expect(rc['thin-activities'], '单薄清单清空').toEqual([]);
    expect(ws.raci.length, '16条RACI指派原始行').toBe(16);
    expect(ws.raci.every(r => r.status === 'assigned'), '只读派生不改RACI记录状态').toBe(true);

    // 与"职责分配完整度"互补核对: 每活动都含R与A -> 完整度也应100%齐备, 证明本项只补C/I维度而不改动A/R口径.
    expect(ws.raci_assignment_coverage['coverage-pct'], '职责分配完整度全齐备(互补旁证)').toBe(100);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
