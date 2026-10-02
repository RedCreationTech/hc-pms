const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H02 延伸: 沟通计划执行落地覆盖度项目级只读派生洞察 (免迁移, 无新命令/新kind/新路由).
// 界面"登记沟通计划" -> 对某条"标记已沟通"(log) -> 对另一条"生成会议"(meeting) -> "干系人与沟通"页签新增只读"沟通计划执行落地覆盖度"面板:
// 按每个沟通计划最新有效版本(store/latest 折叠修订链), 统计有多少活动计划已实际执行落地(标记过至少一次沟通 OR 生成过至少一次会议):
// 总数 / 已执行落地 / 尚未执行 / 已标记沟通 / 已生成会议 / 执行率并列出尚未执行计划; 每多一次执行事实各桶实时翻转, 已执行计划的"或"关系不虚增.
// 只读派生复用沟通计划受众与执行留痕口径, 与"沟通节奏到期汇总"面板互补(到期看时间分布而本项看是否已执行到位), 不落库不投递, 不改变任何不可变版本, 不构成任何门控.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h02cx');
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

// 在"沟通计划"台账中定位指定编号所在行, 点击行内"标记已沟通"并在弹窗填纪要保存.
async function logCommunication(page, code) {
  const row = panel(page, '沟通计划').locator('tr').filter({ hasText: code });
  await row.getByRole('button', { name: '标记已沟通', exact: true }).click();
  const form = modal(page, '标记已沟通');
  await expect(form).toBeVisible();
  await form.locator('#note').fill(`已就 ${code} 完成一次进度对齐`);
  await save(page, '标记已沟通');
}

// 在"沟通计划"台账中定位指定编号所在行, 点击行内"生成会议"并在弹窗保存(会议日期留空沿用下次沟通日期).
async function generateMeeting(page, code) {
  const row = panel(page, '沟通计划').locator('tr').filter({ hasText: code });
  await row.getByRole('button', { name: '生成会议', exact: true }).click();
  const form = modal(page, '生成沟通计划会议');
  await expect(form).toBeVisible();
  await save(page, '生成沟通计划会议');
}

test.describe('H02 沟通计划执行落地覆盖度只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('执行落地覆盖度面板按最新计划聚合已执行/尚未执行/执行率, 标记沟通与生成会议各翻转计数且"或"关系不虚增', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `CX-${suffix}`, name: `沟通执行落地覆盖度验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '干系人与沟通');

    // 先登记一个干系人作为所有沟通计划的受众 (执行覆盖度分母是沟通计划, 受众仅需非空).
    const shCode = `SH-${suffix}`;
    const audienceLabel = await createStakeholder(page, shCode);

    // 登记三条沟通计划, 尚未产生任何执行事实.
    const codes = ['P1', 'P2', 'P3'].map(x => `CX-${x}-${suffix}`);
    for (const code of codes) await createCommPlan(page, { code, audienceLabel });

    // 1) 全部尚未执行: 总数3, 已执行落地0, 尚未执行3, 已标记沟通0, 已生成会议0, 执行率0%, 三条都列在尚未执行清单.
    await open(page, id, '需求与治理', '干系人与沟通');
    const cov0 = panel(page, '沟通计划执行落地覆盖度');
    await expect(cov0).toBeVisible();
    await expect(cov0.getByText('沟通计划总数 3', { exact: true })).toBeVisible();
    await expect(cov0.getByText('已执行落地 0', { exact: true })).toBeVisible();
    await expect(cov0.getByText('尚未执行 3', { exact: true })).toBeVisible();
    await expect(cov0.getByText('已标记沟通 0', { exact: true })).toBeVisible();
    await expect(cov0.getByText('已生成会议 0', { exact: true })).toBeVisible();
    await expect(cov0.getByText('执行率 0%', { exact: true })).toBeVisible();
    for (const code of codes) await expect(cov0.getByText(`${code} · 同步项目进度与风险`, { exact: true })).toBeVisible();
    await panelShot(cov0, 'h02cx-1-none-executed.png');

    // 2) 对 CX-P2 标记已沟通 -> 已执行落地1/尚未执行2/已标记沟通1/已生成会议0/执行率33%; 尚未执行清单只剩 P1/P3, P2 消失.
    await logCommunication(page, codes[1]);
    await open(page, id, '需求与治理', '干系人与沟通');
    const cov1 = panel(page, '沟通计划执行落地覆盖度');
    await expect(cov1.getByText('已执行落地 1', { exact: true })).toBeVisible();
    await expect(cov1.getByText('尚未执行 2', { exact: true })).toBeVisible();
    await expect(cov1.getByText('已标记沟通 1', { exact: true })).toBeVisible();
    await expect(cov1.getByText('已生成会议 0', { exact: true })).toBeVisible();
    await expect(cov1.getByText('执行率 33%', { exact: true })).toBeVisible();
    await expect(cov1.getByText(`${codes[1]} · 同步项目进度与风险`, { exact: true })).toHaveCount(0);
    await expect(cov1.getByText(`${codes[0]} · 同步项目进度与风险`, { exact: true })).toBeVisible();
    await expect(cov1.getByText(`${codes[2]} · 同步项目进度与风险`, { exact: true })).toBeVisible();

    // 3) 对 CX-P3 生成会议 -> 已执行落地2/尚未执行1/已标记沟通1/已生成会议1/执行率67%; 尚未执行只剩 P1.
    await generateMeeting(page, codes[2]);
    await open(page, id, '需求与治理', '干系人与沟通');
    const cov2 = panel(page, '沟通计划执行落地覆盖度');
    await expect(cov2.getByText('已执行落地 2', { exact: true })).toBeVisible();
    await expect(cov2.getByText('尚未执行 1', { exact: true })).toBeVisible();
    await expect(cov2.getByText('已标记沟通 1', { exact: true })).toBeVisible();
    await expect(cov2.getByText('已生成会议 1', { exact: true })).toBeVisible();
    await expect(cov2.getByText('执行率 67%', { exact: true })).toBeVisible();
    await expect(cov2.getByText(`${codes[2]} · 同步项目进度与风险`, { exact: true })).toHaveCount(0);
    await expect(cov2.getByText(`${codes[0]} · 同步项目进度与风险`, { exact: true })).toBeVisible();
    await panelShot(cov2, 'h02cx-2-two-executed.png');

    // 4) 真实HTTP读模型回显 comm_execution_coverage 与面板同源.
    const ws = await api(page, 'GET', base(id) + '/governance');
    const ec = ws.comm_execution_coverage;
    expect(ec.available, '有沟通计划').toBe(true);
    expect(ec.total, '分母=最新有效版本计划数').toBe(3);
    expect(ec.executed, '已执行落地').toBe(2);
    expect(ec['not-executed'], '尚未执行').toBe(1);
    expect(ec.logged, '已标记沟通').toBe(1);
    expect(ec.met, '已生成会议').toBe(1);
    expect(ec['execution-pct'], '执行率').toBe(67);
    const pend = ec['not-executed-plans'].map(x => x.code);
    expect(pend, '尚未执行只剩 P1').toEqual([codes[0]]);

    // 5) 再对已生成会议的 CX-P3 补标记一次沟通 -> "或"关系: 已执行落地仍2, 执行率仍67%, 而已标记沟通升到2/已生成会议仍1 (不虚增执行数).
    await logCommunication(page, codes[2]);
    await open(page, id, '需求与治理', '干系人与沟通');
    const cov3 = panel(page, '沟通计划执行落地覆盖度');
    await expect(cov3.getByText('已执行落地 2', { exact: true })).toBeVisible();
    await expect(cov3.getByText('执行率 67%', { exact: true })).toBeVisible();
    await expect(cov3.getByText('已标记沟通 2', { exact: true })).toBeVisible();
    await expect(cov3.getByText('已生成会议 1', { exact: true })).toBeVisible();
    await panelShot(cov3, 'h02cx-3-or-not-sum.png');

    const ws2 = await api(page, 'GET', base(id) + '/governance');
    expect(ws2.comm_execution_coverage.executed, 'P3 已执行不因二次沟通虚增').toBe(2);
    expect(ws2.comm_execution_coverage.logged, '已标记沟通升到2').toBe(2);
    expect(ws2.comm_execution_coverage.met, '已生成会议仍1').toBe(1);
    expect(ws2.comm_execution_coverage['execution-pct'], '执行率仍67').toBe(67);

    // 6) 只读派生不改变计划状态: 尚未执行的 CX-P1 仍为登记态.
    const p1 = ws2.comm_plans.find(p => p.code === codes[0]);
    expect(p1.status, '计划仍为登记态(只读派生不改状态)').toBe('active');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
