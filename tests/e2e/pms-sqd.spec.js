const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H02 延伸: 干系人权力-利益象限分布项目级只读派生洞察 (免迁移, 无新命令/新kind/新路由).
// 界面"登记干系人"选影响力(influence)与关注度(interest)组合 -> "干系人与沟通"页签新增只读"干系人权力-利益象限分布"面板:
// 按每个干系人最新有效版本(store/latest 折叠修订链, 剔除已作废)复用既有 quadrant-of 口径只读聚合四象限的项目级分布,
// 给出干系人总数 / 已用象限数 / 缺档象限数 / 象限覆盖率 / 主导象限, 以及固定四档(重点管理/保持满意/保持知会/持续监控)各自干系人数与占比.
// quadrant-of: 影响力 high 且 关注度 high -> 重点管理; 仅影响力 high -> 保持满意; 仅关注度 high -> 保持知会; 其余 -> 持续监控.
// 与逐条台账"权力-利益象限"列互补(列回显单个干系人落在哪一格而本项给出整个项目干系人四象限构成), 与刚交付的干系人类别分布同族,
// 只读派生不落库不投递, 不改变任何不可变版本, 不构成任何门控.
// 主导象限翻转与覆盖率翻转均由单个操作者追加"登记"干系人达成(单上下文可见的纯只读加性翻转, 依 Rule B 可进 B 列).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/sqd');
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

// 打开"登记干系人"填必填项并按 influence/interest 组合选权力与利益, 责任人 admin, 保存.
async function createStakeholder(page, code, name, influence, interest) {
  await drawer(page).getByRole('button', { name: '登记干系人', exact: true }).click();
  const form = modal(page, '登记干系人');
  await form.locator('#code').fill(code);
  await form.locator('#name').fill(name);
  await form.locator('#role').fill('需求方接口');
  await choose(page, form, 'category', '内部');
  await choose(page, form, 'influence', influence);
  await choose(page, form, 'interest', interest);
  await choose(page, form, 'owner_id', 'admin');
  await save(page, '登记干系人');
}

test.describe('H02 干系人权力-利益象限分布只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('象限分布面板按最新干系人聚合四档象限与覆盖/主导象限, 追加登记使主导象限与覆盖率严格唯一最大翻转', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `SQD-${suffix}`, name: `干系人权力-利益象限分布验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '干系人与沟通');

    // 面板初始态: 尚无干系人 -> 展示空态占位文案, 不出现"干系人总数"标签.
    const distEmpty = panel(page, '干系人权力-利益象限分布');
    await expect(distEmpty).toBeVisible();
    await expect(distEmpty.getByText('暂无干系人, 登记后可在此查看权力-利益象限分布.', { exact: true })).toBeVisible();
    // 面板说明文案含"给出干系人总数"字样, 用带数字的锚定正则只匹配标签本身, 空态下不出现该标签.
    await expect(distEmpty.getByText(/^干系人总数 \d+$/)).toHaveCount(0);
    await expect(distEmpty.getByText(/^主导象限 /)).toHaveCount(0);

    // 阶段一 四名干系人: A/B high+high=重点管理, C high+low=保持满意, D low+high=保持知会.
    await createStakeholder(page, `SQ-A-${suffix}`, '重点管理甲', 'high', 'high');
    await createStakeholder(page, `SQ-B-${suffix}`, '重点管理乙', 'high', 'high');
    await createStakeholder(page, `SQ-C-${suffix}`, '保持满意', 'high', 'low');
    await createStakeholder(page, `SQ-D-${suffix}`, '保持知会', 'low', 'high');

    // 总数4, 覆盖 mc/ks/ki 三象限, 缺档1, 覆盖率 round(3/4)=75, 主导 重点管理·2, 持续监控·0 仍渲染.
    await open(page, id, '需求与治理', '干系人与沟通');
    const dist1 = panel(page, '干系人权力-利益象限分布');
    await expect(dist1.getByText('干系人总数 4', { exact: true })).toBeVisible();
    await expect(dist1.getByText('象限覆盖率 75%', { exact: true })).toBeVisible();
    await expect(dist1.getByText('已用象限 3', { exact: true })).toBeVisible();
    await expect(dist1.getByText('缺档象限 1', { exact: true })).toBeVisible();
    await expect(dist1.getByText('主导象限 重点管理 · 2', { exact: true })).toBeVisible();
    await expect(dist1.getByText('重点管理 · 2 (50%)', { exact: true })).toBeVisible();
    await expect(dist1.getByText('保持满意 · 1 (25%)', { exact: true })).toBeVisible();
    await expect(dist1.getByText('保持知会 · 1 (25%)', { exact: true })).toBeVisible();
    await expect(dist1.getByText('持续监控 · 0 (0%)', { exact: true })).toBeVisible();
    await panelShot(dist1, 'sqd-1-manage-close-dominant.png');

    // 阶段二 单操作者追加登记两名"保持知会"(low/high) -> 保持知会 3 严格唯一最大, 主导象限由 重点管理 翻转为 保持知会; 覆盖率仍 75.
    await createStakeholder(page, `SQ-E-${suffix}`, '保持知会乙', 'low', 'high');
    await createStakeholder(page, `SQ-F-${suffix}`, '保持知会丙', 'low', 'high');
    await open(page, id, '需求与治理', '干系人与沟通');
    const dist2 = panel(page, '干系人权力-利益象限分布');
    await expect(dist2.getByText('干系人总数 6', { exact: true })).toBeVisible();
    await expect(dist2.getByText('象限覆盖率 75%', { exact: true })).toBeVisible();
    await expect(dist2.getByText('已用象限 3', { exact: true })).toBeVisible();
    await expect(dist2.getByText('缺档象限 1', { exact: true })).toBeVisible();
    await expect(dist2.getByText('主导象限 保持知会 · 3', { exact: true })).toBeVisible();
    await expect(dist2.getByText('重点管理 · 2 (33%)', { exact: true })).toBeVisible();
    await expect(dist2.getByText('保持满意 · 1 (17%)', { exact: true })).toBeVisible();
    await expect(dist2.getByText('保持知会 · 3 (50%)', { exact: true })).toBeVisible();
    await expect(dist2.getByText('持续监控 · 0 (0%)', { exact: true })).toBeVisible();
    // 主导标签已翻转: 不再出现旧的"主导象限 重点管理"徽标.
    await expect(dist2.getByText('主导象限 重点管理 · 2', { exact: true })).toHaveCount(0);
    await panelShot(dist2, 'sqd-2-keep-informed-dominant.png');

    // 阶段三 追加登记一名"持续监控"(low/low) -> 四象限全覆盖, 覆盖率 75->100, 缺档象限徽标消失; 主导仍 保持知会·3.
    await createStakeholder(page, `SQ-G-${suffix}`, '持续监控', 'low', 'low');
    await open(page, id, '需求与治理', '干系人与沟通');
    const dist3 = panel(page, '干系人权力-利益象限分布');
    await expect(dist3.getByText('干系人总数 7', { exact: true })).toBeVisible();
    await expect(dist3.getByText('象限覆盖率 100%', { exact: true })).toBeVisible();
    await expect(dist3.getByText('已用象限 4', { exact: true })).toBeVisible();
    await expect(dist3.getByText(/^缺档象限 \d+$/)).toHaveCount(0);
    await expect(dist3.getByText('主导象限 保持知会 · 3', { exact: true })).toBeVisible();
    await expect(dist3.getByText('重点管理 · 2 (29%)', { exact: true })).toBeVisible();
    await expect(dist3.getByText('保持满意 · 1 (14%)', { exact: true })).toBeVisible();
    await expect(dist3.getByText('保持知会 · 3 (43%)', { exact: true })).toBeVisible();
    await expect(dist3.getByText('持续监控 · 1 (14%)', { exact: true })).toBeVisible();
    await panelShot(dist3, 'sqd-3-all-quadrants.png');

    // 真实HTTP读模型回显 stakeholder_quadrant_distribution 与面板同源.
    const ws = await api(page, 'GET', base(id) + '/governance');
    const dist = ws.stakeholder_quadrant_distribution;
    const quadOf = k => (dist['by-quadrant'].find(x => x.quadrant === k) || {});
    expect(dist.available, '有干系人').toBe(true);
    expect(dist.total, '干系人总数').toBe(7);
    expect(dist.covered, '已用象限数').toBe(4);
    expect(dist.uncovered, '缺档象限数').toBe(0);
    expect(dist['coverage-pct'], '象限覆盖率').toBe(100);
    expect(dist['dominant-quadrant'], '主导象限').toBe('keep-informed');
    expect(dist['dominant-count'], '主导象限人数').toBe(3);
    expect(quadOf('manage-close').count, '重点管理').toBe(2);
    expect(quadOf('keep-satisfied').count, '保持满意').toBe(1);
    expect(quadOf('keep-informed').count, '保持知会').toBe(3);
    expect(quadOf('monitor').count, '持续监控').toBe(1);
    expect(quadOf('manage-close').pct, '重点管理占比').toBe(29);
    expect(quadOf('keep-informed').pct, '保持知会占比').toBe(43);
    expect(dist['by-quadrant'].map(x => x.quadrant), '四档固定顺序').toEqual(['manage-close', 'keep-satisfied', 'keep-informed', 'monitor']);
    expect(dist.covered + dist.uncovered, '覆盖+缺档=象限总数4').toBe(4);
    expect(dist['by-quadrant'].reduce((s, x) => s + x.count, 0), '四档之和=干系人总数').toBe(dist.total);

    // 只读派生不改变记录: 重复读取稳定, 七名干系人仍 active.
    const ws2 = await api(page, 'GET', base(id) + '/governance');
    expect(ws2.stakeholder_quadrant_distribution, '只读派生重复读取稳定').toEqual(dist);
    for (const code of ['SQ-A', 'SQ-B', 'SQ-C', 'SQ-D', 'SQ-E', 'SQ-F', 'SQ-G']) {
      expect(ws2.stakeholders.find(x => x.code.startsWith(code)).status, `干系人 ${code} 仍登记态`).toBe('active');
    }

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
