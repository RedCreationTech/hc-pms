const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H02 延伸: RACI 角色结构分布项目级只读派生洞察 (免迁移, 无新命令/新kind/新路由).
// 界面"登记干系人" -> 逐活动"指派RACI职责"(执行R/负责A/咨询C/知会I) -> "干系人与沟通"页签新增只读"RACI角色结构分布"面板:
// 按职责角色(R/A/C/I四档固定枚举)只读聚合整个项目的RACI指派结构分布, 逐角色统计被指派次数与占总数百分比,
// 给出职责指派总数 / 已用角色数 / 缺档角色数 / 角色覆盖率 / 主导角色(被指派次数最多者).
// 与逐活动"RACI职责分配完整度"(每项活动是否同时指派A与R)与逐活动"RACI咨询知会覆盖度"(每项活动是否同时配C与I)互补:
// 前两者按活动看是否配齐相应职责, 本项按角色看整个项目职责结构由哪些角色构成、哪个角色主导; 只读派生不落库不投递,
// 不改变任何不可变版本, 不构成任何门控. 翻转通过单操作者逐条追加"指派RACI职责"达成(单上下文可见的纯只读翻转, 依 Rule B 可进 B 列).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/rrd');
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
  await form.locator('#name').fill('角色结构对接人');
  await form.locator('#role').fill('需求方接口');
  await choose(page, form, 'category', '客户');
  await choose(page, form, 'interest', 'high');
  await choose(page, form, 'influence', 'high');
  await choose(page, form, 'owner_id', 'admin');
  await save(page, '登记干系人');
  return `${code} / 角色结构对接人`;
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

test.describe('H02 RACI角色结构分布只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('角色结构分布面板按角色聚合总数/已用/缺档/覆盖率/主导角色, 追加不同角色指派后各桶与主导角色实时翻转', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `RRD-${suffix}`, name: `RACI角色结构分布验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '干系人与沟通');

    // 面板初始态: 尚无RACI指派 -> 展示空态占位文案, 不出现"职责指派总数"标签.
    const distEmpty = panel(page, 'RACI角色结构分布');
    await expect(distEmpty).toBeVisible();
    await expect(distEmpty.getByText('暂无RACI职责指派, 指派后可在此查看角色结构分布.', { exact: true })).toBeVisible();
    // 面板说明文案含"给出职责指派总数"字样, 用带数字的锚定正则只匹配标签本身, 空态下不出现该标签.
    await expect(distEmpty.getByText(/^职责指派总数 \d+$/)).toHaveCount(0);

    // 登记四名干系人作为 RACI 职责承担者 (同一活动同一干系人不得重复, 每活动不同职责用不同人).
    const sh1 = await createStakeholder(page, `SH1-${suffix}`);
    const sh2 = await createStakeholder(page, `SH2-${suffix}`);
    const sh3 = await createStakeholder(page, `SH3-${suffix}`);
    const sh4 = await createStakeholder(page, `SH4-${suffix}`);

    const ACT_A = '需求评审';
    const ACT_B = '方案设计';
    const ACT_C = '编码实现';

    // 阶段一 三条指派: ACT_A R=s1, ACT_A A=s2, ACT_B R=s3 -> 总数3, R=2 A=1 C=0 I=0, 已用2, 缺档2, 覆盖50, 主导 R·2 (唯一严格最大).
    await assignRaci(page, { activity: ACT_A, shLabel: sh1, responsibility: '执行 R' });
    await assignRaci(page, { activity: ACT_A, shLabel: sh2, responsibility: '负责 A' });
    await assignRaci(page, { activity: ACT_B, shLabel: sh3, responsibility: '执行 R' });

    await open(page, id, '需求与治理', '干系人与沟通');
    const dist1 = panel(page, 'RACI角色结构分布');
    await expect(dist1.getByText('职责指派总数 3', { exact: true })).toBeVisible();
    await expect(dist1.getByText('角色覆盖率 50%', { exact: true })).toBeVisible();
    await expect(dist1.getByText('已用角色 2', { exact: true })).toBeVisible();
    await expect(dist1.getByText('缺档角色 2', { exact: true })).toBeVisible();
    await expect(dist1.getByText('主导角色 执行 · 2', { exact: true })).toBeVisible();
    await expect(dist1.getByText('执行 · 2 (67%)', { exact: true })).toBeVisible();
    await expect(dist1.getByText('负责 · 1 (33%)', { exact: true })).toBeVisible();
    // 咨询/知会尚无指派, 计数为 0 仍固定渲染.
    await expect(dist1.getByText('咨询 · 0 (0%)', { exact: true })).toBeVisible();
    await expect(dist1.getByText('知会 · 0 (0%)', { exact: true })).toBeVisible();
    await panelShot(dist1, 'rrd-1-r-dominant.png');

    // 真实HTTP读模型回显 raci_role_distribution 与面板同源 (阶段一).
    const ws1 = await api(page, 'GET', base(id) + '/governance');
    const d1 = ws1.raci_role_distribution;
    expect(d1.available, '有RACI指派').toBe(true);
    expect(d1.total).toBe(3);
    expect(d1.covered).toBe(2);
    expect(d1.uncovered).toBe(2);
    expect(d1['role-coverage-pct']).toBe(50);
    expect(d1['dominant-role']).toBe('R');
    expect(d1['dominant-count']).toBe(2);
    expect(d1['by-role'].map(x => x.role), '四档固定顺序').toEqual(['R', 'A', 'C', 'I']);
    const cnt1 = k => (d1['by-role'].find(x => x.role === k) || {}).count;
    expect(cnt1('R')).toBe(2);
    expect(cnt1('A')).toBe(1);
    expect(cnt1('C')).toBe(0);
    expect(cnt1('I')).toBe(0);
    expect(d1.covered + d1.uncovered, '已用+缺档=角色总数4').toBe(4);
    expect(d1['by-role'].reduce((s, x) => s + x.count, 0), '四档之和=指派总数').toBe(d1.total);

    // 阶段二 ACT_A 补一个 C=s3 -> 总数4, R=2 A=1 C=1 I=0, 已用3 缺档1, 覆盖75, 主导仍 R·2.
    await assignRaci(page, { activity: ACT_A, shLabel: sh3, responsibility: '咨询 C' });
    await open(page, id, '需求与治理', '干系人与沟通');
    const dist2 = panel(page, 'RACI角色结构分布');
    await expect(dist2.getByText('职责指派总数 4', { exact: true })).toBeVisible();
    await expect(dist2.getByText('角色覆盖率 75%', { exact: true })).toBeVisible();
    await expect(dist2.getByText('已用角色 3', { exact: true })).toBeVisible();
    await expect(dist2.getByText('缺档角色 1', { exact: true })).toBeVisible();
    await expect(dist2.getByText('主导角色 执行 · 2', { exact: true })).toBeVisible();
    await expect(dist2.getByText('咨询 · 1 (25%)', { exact: true })).toBeVisible();

    // 阶段三 ACT_B 补 C=s1, ACT_C 补 C=s2 -> 总数6, R=2 A=1 C=3 I=0, 已用3 缺档1, 覆盖75, 主导翻到 咨询·3 (严格唯一最大).
    await assignRaci(page, { activity: ACT_B, shLabel: sh1, responsibility: '咨询 C' });
    await assignRaci(page, { activity: ACT_C, shLabel: sh2, responsibility: '咨询 C' });
    await open(page, id, '需求与治理', '干系人与沟通');
    const dist3 = panel(page, 'RACI角色结构分布');
    await expect(dist3.getByText('职责指派总数 6', { exact: true })).toBeVisible();
    await expect(dist3.getByText('已用角色 3', { exact: true })).toBeVisible();
    await expect(dist3.getByText('缺档角色 1', { exact: true })).toBeVisible();
    await expect(dist3.getByText('角色覆盖率 75%', { exact: true })).toBeVisible();
    await expect(dist3.getByText('主导角色 咨询 · 3', { exact: true })).toBeVisible();
    await expect(dist3.getByText('咨询 · 3 (50%)', { exact: true })).toBeVisible();

    // 阶段四 ACT_B 补 I=s4 (I 首次出现) -> 总数7, R=2 A=1 C=3 I=1, 已用4 缺档0, 覆盖100, 主导仍 咨询·3.
    await assignRaci(page, { activity: ACT_B, shLabel: sh4, responsibility: '知会 I' });
    await open(page, id, '需求与治理', '干系人与沟通');
    const dist4 = panel(page, 'RACI角色结构分布');
    await expect(dist4.getByText('职责指派总数 7', { exact: true })).toBeVisible();
    await expect(dist4.getByText('角色覆盖率 100%', { exact: true })).toBeVisible();
    await expect(dist4.getByText('已用角色 4', { exact: true })).toBeVisible();
    // 全四档均已用到 -> "缺档角色"标签不再渲染 (未出现).
    await expect(dist4.getByText(/^缺档角色 \d+$/)).toHaveCount(0);
    await expect(dist4.getByText('主导角色 咨询 · 3', { exact: true })).toBeVisible();
    await expect(dist4.getByText('执行 · 2 (29%)', { exact: true })).toBeVisible();
    await expect(dist4.getByText('负责 · 1 (14%)', { exact: true })).toBeVisible();
    await expect(dist4.getByText('咨询 · 3 (43%)', { exact: true })).toBeVisible();
    await expect(dist4.getByText('知会 · 1 (14%)', { exact: true })).toBeVisible();
    await panelShot(dist4, 'rrd2-all-covered.png');

    // 真实HTTP读模型最终回显 + 只读派生不改变RACI记录状态(7条原始行仍为指派态).
    const ws = await api(page, 'GET', base(id) + '/governance');
    const rc = ws.raci_role_distribution;
    expect(rc.total).toBe(7);
    expect(rc.covered).toBe(4);
    expect(rc.uncovered).toBe(0);
    expect(rc['role-coverage-pct']).toBe(100);
    expect(rc['dominant-role']).toBe('C');
    expect(rc['dominant-count']).toBe(3);
    const cnt = k => (rc['by-role'].find(x => x.role === k) || {}).count;
    expect(cnt('R')).toBe(2);
    expect(cnt('A')).toBe(1);
    expect(cnt('C')).toBe(3);
    expect(cnt('I')).toBe(1);
    expect(ws.raci.length, '7条RACI指派原始行').toBe(7);
    expect(ws.raci.every(r => r.status === 'assigned'), '只读派生不改RACI记录状态').toBe(true);

    // 只读派生重复读取稳定.
    const ws2 = await api(page, 'GET', base(id) + '/governance');
    expect(ws2.raci_role_distribution, '只读派生重复读取稳定').toEqual(rc);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
