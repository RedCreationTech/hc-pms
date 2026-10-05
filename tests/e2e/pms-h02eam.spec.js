const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H02 延伸: 干系人"期望参与态度"可选枚举 + 投入度评估矩阵只读派生洞察 (PMBOK, 免迁移, 无新命令/新kind).
// 界面"登记干系人"可同时选"当前参与态度"和"期望参与态度"(未知晓/抵制/中立/支持/主导) ->
// 台账新增"期望态度"与"投入差距"两列: 达标 / 需提升 +N 档 / 需降低 N 档 / 未标注 彩色徽标回显;
// "干系人与沟通"页签新增只读"参与态度评估矩阵"面板: 总数/已标注/达标率/需提升(合计档)/需降低/未标注 + 需提升优先级/需降低清单;
// 非法期望态度经真实HTTP 400; 只读派生不改变干系人状态. 本用例单上下文可完整核验.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h02eam');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
// shared/panel 渲染 <section> 内含 <h3> 标题; "未标注"等标签在台账逐行也出现, 须按面板作用域定位避免 strict-mode 串台.
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
  const option = dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first();
  await expect(option).toBeVisible();
  await option.click();
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

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 打开"登记干系人"填必填项; currentLabel 选当前参与态度, desiredLabel 选期望参与态度 (均未选则留空 -> 未标注).
async function registerStakeholder(page, code, currentLabel, desiredLabel) {
  await drawer(page).getByRole('button', { name: '登记干系人', exact: true }).click();
  const form = modal(page, '登记干系人');
  await expect(form).toBeVisible();
  await form.locator('#code').fill(code);
  await form.locator('#name').fill(`干系人${code}`);
  await form.locator('#role').fill('需求方接口');
  await choose(page, form, 'category', '客户');
  await choose(page, form, 'interest', 'high');
  await choose(page, form, 'influence', 'high');
  if (currentLabel) await choose(page, form, 'engagement', currentLabel);
  if (desiredLabel) await choose(page, form, 'desired_engagement', desiredLabel);
}

test.describe('H02 期望参与态度枚举与投入度评估矩阵只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('台账期望态度/投入差距列回显, 评估矩阵面板统计与优先级清单正确, 非法期望态度经真实HTTP被拒, 只读派生不改状态', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `EAM-${suffix}`, name: `投入度评估矩阵验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '干系人与沟通');

    // 1) 界面登记"需提升"干系人: 当前=抵制, 期望=主导 (差 3 档) -> 保存前截图证明可选"期望参与态度"字段.
    const upCode = `EAM-UP-${suffix}`;
    await registerStakeholder(page, upCode, '抵制', '主导');
    await shot(page, 'h02eam-1-dialog-desired.png');
    const upSaved = await save(page, '登记干系人');
    expect(upSaved.result.engagement, '命令响应回显当前态度英文枚举').toBe('resistant');
    expect(upSaved.result.desired_engagement, '命令响应回显期望态度英文枚举').toBe('leading');

    // 2) "达标"干系人: 当前=支持, 期望=支持 (差 0 档).
    const onCode = `EAM-ON-${suffix}`;
    await registerStakeholder(page, onCode, '支持', '支持');
    await save(page, '登记干系人');

    // 3) "需降低"干系人: 当前=主导, 期望=中立 (差 -2 档).
    const downCode = `EAM-DOWN-${suffix}`;
    await registerStakeholder(page, downCode, '主导', '中立');
    await save(page, '登记干系人');

    // 4) "未标注"干系人: 当前=支持, 不选期望态度 -> transform 把空值 dissoc 掉, 不参与差距计算.
    const markCode = `EAM-MARK-${suffix}`;
    await registerStakeholder(page, markCode, '支持', null);
    await save(page, '登记干系人');

    // 5) 台账"期望态度"与"投入差距"列回显彩色徽标 (单上下文可见).
    await open(page, id, '需求与治理', '干系人与沟通');
    await expect(row(page, upCode).locator('.ant-table-cell').filter({ hasText: /^主导$/ })).toBeVisible();
    await expect(row(page, upCode).locator('.ant-table-cell').filter({ hasText: /^需提升 \+3 档$/ })).toBeVisible();
    await expect(row(page, onCode).locator('.ant-table-cell').filter({ hasText: /^达标$/ })).toBeVisible();
    await expect(row(page, downCode).locator('.ant-table-cell').filter({ hasText: /^需降低 2 档$/ })).toBeVisible();
    await expect(row(page, markCode).locator('.ant-table-cell').filter({ hasText: /^未标注$/ })).toBeVisible();
    await expect(row(page, markCode).locator('.ant-table-cell').filter({ hasText: /^未设定$/ })).toBeVisible();
    const table = row(page, upCode).locator('xpath=ancestor::table[1]');
    await table.scrollIntoViewIfNeeded();
    await page.waitForTimeout(200);
    await table.screenshot({ path: path.join(output, 'h02eam-2-ledger-columns.png'), animations: 'disabled' });

    // 6) 只读"参与态度评估矩阵"面板: 总数4, 已标注3, 达标率33% (1/3), 需提升1(合计3档), 需降低1, 未标注1; 优先级清单命中.
    const mx = panel(page, '参与态度评估矩阵');
    await expect(mx).toBeVisible();
    await expect(mx.getByText(/干系人总数\s*4/)).toBeVisible();
    await expect(mx.getByText(/已标注\s*3/)).toBeVisible();
    await expect(mx.getByText(/达标率\s*33%/)).toBeVisible();
    await expect(mx.getByText(/需提升\s*1\s*\(合计\s*3\s*档\)/)).toBeVisible();
    await expect(mx.getByText(/需降低\s*1/)).toBeVisible();
    await expect(mx.getByText(/未标注\s*1/)).toBeVisible();
    // 需提升优先级清单命中该干系人编号; 需降低清单命中 down 干系人.
    await expect(mx.locator('tbody tr:visible').filter({ hasText: upCode })).toBeVisible();
    await expect(mx.locator('tbody tr:visible').filter({ hasText: downCode })).toBeVisible();
    await mx.scrollIntoViewIfNeeded();
    await page.waitForTimeout(200);
    await shot(page, 'h02eam-3-matrix-panel.png');

    // 7) 真实HTTP读模型同源于面板: stakeholder_engagement_matrix 各计数一致.
    const ws = await api(page, 'GET', base(id) + '/governance');
    const m = ws.stakeholder_engagement_matrix;
    expect(m.total, '分母=最新有效版本干系人数').toBe(4);
    expect(m.marked, '已标注数(当前+期望均在枚举内)').toBe(3);
    expect(m['on-target'], '达标数').toBe(1);
    expect(m['need-up'], '需提升数').toBe(1);
    expect(m['need-down'], '需降低数').toBe(1);
    expect(m.unmarked, '未标注数').toBe(1);
    expect(m['up-steps'], '需提升合计档数').toBe(3);
    expect(m['on-target-pct'], '达标率百分比').toBe(33);
    expect(m['need-up-stakeholders'][0].code, '需提升优先级首位').toBe(upCode);
    expect(m['need-up-stakeholders'][0].current, '需提升首位当前').toBe('resistant');
    expect(m['need-up-stakeholders'][0].desired, '需提升首位期望').toBe('leading');
    expect(m['need-up-stakeholders'][0].gap, '需提升首位差距').toBe(3);
    expect(m['need-down-stakeholders'][0].code, '需降低首位').toBe(downCode);
    // 逐行读模型回显差距派生键.
    const upRow = ws.stakeholders.find(s => s.code === upCode);
    expect(upRow.desired_engagement, '逐行期望态度持久化').toBe('leading');
    expect(upRow.stakeholder_engagement_state, '逐行投入差距态').toBe('up');
    expect(upRow.stakeholder_engagement_gap, '逐行差距档数').toBe(3);

    // 8) 界面再修订"需提升"干系人期望态度为主导->中立(缩小差距), 矩阵随之更新; 老版本不漂移.
    const v = (await api(page, 'GET', base(id))).version;
    await open(page, id, '需求与治理', '干系人与沟通');
    await row(page, upCode).getByRole('button', { name: '新修订', exact: true }).click();
    const rform = modal(page, '修订干系人');
    await expect(rform).toBeVisible();
    await choose(page, rform, 'desired_engagement', '中立');
    await save(page, '修订干系人');
    const ws2 = await api(page, 'GET', base(id) + '/governance');
    const m2 = ws2.stakeholder_engagement_matrix;
    const upRow2 = ws2.stakeholders.find(s => s.code === upCode);
    expect(upRow2.revision, '修订号递增').toBe(2);
    expect(upRow2.desired_engagement, '修订后期望态度为中立').toBe('neutral');
    expect(upRow2.stakeholder_engagement_gap, '修订后差距缩小到1档').toBe(1);
    expect(m2['up-steps'], '需提升合计档数从3降为1').toBe(1);
    // 只读派生不改变干系人状态与当前态度.
    expect(upRow2.status, '只读派生不改状态').toBe('active');
    expect(upRow2.engagement, '当前态度不漂移').toBe('resistant');
    const mx2 = panel(page, '参与态度评估矩阵');
    await open(page, id, '需求与治理', '干系人与沟通');
    await expect(panel(page, '参与态度评估矩阵').getByText(/需提升\s*1\s*\(合计\s*1\s*档\)/)).toBeVisible();
    await shot(page, 'h02eam-4-after-revise.png');

    // 9) 真实HTTP拒绝非法期望态度枚举.
    const v3 = (await api(page, 'GET', base(id))).version;
    const badCode = await page.evaluate(async ({ pid, v }) => {
      const token = localStorage.getItem('ruoyi_token');
      const r = await fetch(`/api/pms/projects/${pid}/governance/stakeholders`, { method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ code: 'EAM-BAD', name: '非法期望态度', role: 'x', category: 'external',
          interest: 'high', influence: 'high', engagement: 'neutral', desired_engagement: 'champion', version: v }) });
      return (await r.json()).code;
    }, { pid: id, v: v3 });
    expect(badCode, '非法期望参与态度应被拒').toBe(400);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
