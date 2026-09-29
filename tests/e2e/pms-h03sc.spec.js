const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H03 范围基线覆盖性审查只读派生: 计划与执行 -> WBS与排程 顶部"范围覆盖性审查"面板与逐任务"范围覆盖"列,
// 均按需求 satisfies 追踪链在读取时只读派生(叶节点=非汇总任务, 被至少一条 satisfies 指向即视为已覆盖;
// verifies 只标注不计入覆盖), 不构成门控. 造 汇总"1" + 已覆盖叶"1.1"(satisfies+verifies) + 未覆盖叶"1.2",
// 期望 叶节点2 已覆盖1(50%) 未覆盖1 未覆盖WBS: 1.2. 面板与列界面可见, 并以 GET /planning 服务端回显二次确认.
// 最后提交计划审批成功(200)证明未覆盖叶不阻断计划冻结.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h03sc');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) }).first();
const base = id => `/api/pms/projects/${id}`;

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

async function mutate(page, id, suffix, data = {}) {
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

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  if (typeof text === 'string' && await input.isEditable()) await input.fill(text);
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await form.locator(`#${key}`).press('Escape');
}

async function fill(form, values) {
  for (const [key, value] of Object.entries(values)) await form.locator(`#${key}`).fill(String(value));
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
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 面板截图: 面板在抽屉自身滚动容器内, 需 scrollIntoViewIfNeeded 后取元素截图.
async function panelShot(page, title, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  const card = panel(page, title);
  await expect(card.getByRole('heading', { name: title, exact: true })).toBeVisible();
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 通过公开 UI 新建一条 WBS 任务; type 留空用默认"任务"(叶节点), 传"汇总"建汇总节点(工期0);
// parent 传汇总任务名则把本任务挂到该父节点下(避免提交审批时"空汇总任务"被拒, 与覆盖性无关).
async function wbsTask(page, code, name, days, type, parent) {
  await drawer(page).getByRole('button', { name: '新建WBS任务', exact: true }).click();
  const form = modal(page, '新建WBS任务');
  await fill(form, { wbs_code: code, name, duration_days: days, start_date: '2026-09-22' });
  if (type) await choose(page, form, 'task_type', type);
  if (parent) await choose(page, form, 'parent_id', parent);
  await choose(page, form, 'owner_id', /\/ admin$/);
  return (await save(page, '新建WBS任务')).result;
}

async function addRequirement(page, code, text) {
  await drawer(page).getByRole('button', { name: '新增URS需求', exact: true }).click();
  const form = modal(page, '新增URS需求');
  await fill(form, { code, text });
  await choose(page, form, 'owner_id', /\/ admin$/);
  return (await save(page, '新增URS需求')).result;
}

// requirementCode: 需求编号文本; targetName: 关联任务名; relationLabel: 满足需求 / 验证需求.
async function addTrace(page, requirementCode, targetName, relationLabel) {
  await drawer(page).getByRole('button', { name: '建立需求追踪', exact: true }).click();
  const form = modal(page, '建立需求追踪');
  await choose(page, form, 'requirement_id', requirementCode);
  await choose(page, form, 'target', targetName);
  await choose(page, form, 'relation', relationLabel);
  return (await save(page, '建立需求追踪')).result;
}

test.describe('H03 范围基线覆盖性审查只读派生浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('汇总+已覆盖叶+未覆盖叶 -> 面板与逐任务列界面可见, 服务端回显一致, 提交计划不受阻断', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H03SC-${suffix}`, name: `范围覆盖审查验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await mutate(page, id, '/transition', { status: 'initiated', reason: 'E2E立项前置' });
    await mutate(page, id, '/transition', { status: 'planning', reason: 'E2E计划前置' });

    // 三任务: 汇总"1"(不计入覆盖分母), 已覆盖叶"1.1", 未覆盖叶"1.2".
    await open(page, id, '计划与执行', 'WBS与排程');
    await wbsTask(page, '1', '交付汇总骨架', 0, '汇总');
    await wbsTask(page, '1.1', '受控设计任务', 2, null, '交付汇总骨架');
    await wbsTask(page, '1.2', '未覆盖验证任务', 3, null, '交付汇总骨架');

    // 需求 + 满足(satisfies)追踪指向 1.1, 再补一条验证(verifies)追踪(只标注不计覆盖), 1.2 故意不覆盖.
    const reqCode = `URS-H03-${suffix}`;
    await open(page, id, '需求与治理', 'URS与追踪');
    await addRequirement(page, reqCode, '受控设计产出须经独立评审并留痕');
    await addTrace(page, reqCode, '受控设计任务', '满足需求');
    await addTrace(page, reqCode, '受控设计任务', '验证需求');

    // 回 WBS与排程: 面板徽标 + 逐任务范围覆盖列(汇总/已覆盖 设计+验证/未覆盖).
    await open(page, id, '计划与执行', 'WBS与排程');
    const covPanel = panel(page, '范围覆盖性审查');
    await expect(covPanel.getByText('叶节点 2', { exact: true })).toBeVisible();
    await expect(covPanel.getByText(/已覆盖 1 \(50%\)/)).toBeVisible();
    await expect(covPanel.getByText('未覆盖 1', { exact: true })).toBeVisible();
    await expect(covPanel.getByText('未覆盖 WBS: 1.2', { exact: false })).toBeVisible();
    await panelShot(page, '范围覆盖性审查', 'h03sc-1-panel.png');

    // "汇总"同时出现在类型列与范围覆盖列, 用 .ant-tag 精确锁定范围覆盖列的徽标.
    await expect(row(page, '交付汇总骨架').locator('.ant-tag').filter({ hasText: '汇总' })).toBeVisible();
    await expect(row(page, '受控设计任务').locator('.ant-tag').filter({ hasText: '已覆盖 设计 1' })).toBeVisible();
    await expect(row(page, '受控设计任务').locator('.ant-tag').filter({ hasText: '验证 1' })).toBeVisible();
    await expect(row(page, '未覆盖验证任务').locator('.ant-tag').filter({ hasText: '未覆盖' })).toBeVisible();
    await shot(page, 'h03sc-2-columns.png');

    // 服务端读取时派生回显二次确认(不落库, 键为连字符 JSON).
    const plan = await api(page, 'GET', base(id) + '/planning');
    const cov = plan.scope_coverage;
    expect(cov['total-leaves']).toBe(2);
    expect(cov['covered-leaves']).toBe(1);
    expect(cov['uncovered-leaves']).toBe(1);
    expect(cov['coverage-pct']).toBe(50);
    expect(cov['uncovered-codes']).toEqual(['1.2']);
    const byCode = code => plan.tasks.find(t => t.wbs_code === code);
    expect(byCode('1').scope_leaf).toBe(false);
    expect(byCode('1.1').scope_leaf).toBe(true);
    expect(byCode('1.1').scope_covered).toBe(true);
    expect(byCode('1.1').scope_satisfies_count).toBe(1);
    expect(byCode('1.1').scope_verifies_count).toBe(1);
    expect(byCode('1.2').scope_covered).toBe(false);

    // 只读不门控: 存在未覆盖叶, 提交计划审批仍成功并冻结.
    await open(page, id, '计划与执行', '审批与基线');
    await drawer(page).getByRole('button', { name: '提交计划审批', exact: true }).click();
    await fill(modal(page, '提交计划审批'), { comment: '含未覆盖叶仍提交, 验证覆盖性审查不阻断冻结' });
    await save(page, '提交计划审批');
    await expect(drawer(page).getByText('计划已冻结,等待独立审批.')).toBeVisible();

    // 空态截图3: 新项目规划状态但无任何 WBS, 面板叶节点 0.
    const empty = await api(page, 'POST', '/api/pms/projects', { project_no: `H03SC0-${suffix}`, name: `范围覆盖空态 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    await mutate(page, empty.project_id, '/transition', { status: 'initiated', reason: 'E2E空态前置' });
    await mutate(page, empty.project_id, '/transition', { status: 'planning', reason: 'E2E空态前置' });
    await open(page, empty.project_id, '计划与执行', 'WBS与排程');
    const emptyPanel = panel(page, '范围覆盖性审查');
    await expect(emptyPanel.getByText('叶节点 0', { exact: true })).toBeVisible();
    await expect(emptyPanel.getByText(/已覆盖 1 \(50%\)/)).toHaveCount(0);
    await panelShot(page, '范围覆盖性审查', 'h03sc-3-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
