const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H02 延伸: 干系人类别分布项目级只读派生洞察 (免迁移, 无新命令/新kind/新路由).
// 界面"登记干系人"选业务分类(内部/外部/供应商/客户/监管方五档固定枚举) -> "干系人与沟通"页签新增只读"干系人类别分布"面板:
// 按每个干系人最新有效版本(store/latest 折叠修订链, 剔除已作废)只读聚合业务分类的项目级分布,
// 给出干系人总数 / 覆盖类别数 / 未覆盖类别数 / 类别覆盖率 / 主导类别, 以及五档固定类别各自干系人数.
// 与"沟通受众覆盖度"/"参与态度覆盖度"/"权力-利益象限"互补(态度看投入意愿, 象限看影响力定位, 本项看干系人由哪些业务类型构成),
// 只读派生复用 create-stakeholder! 已落库的 :category 口径, 不落库不投递, 不改变任何不可变版本, 不构成任何门控.
// 翻转通过单个操作者追加"登记"一名新业务类别的干系人达成(单上下文可见的纯只读翻转, 依 Rule B 可进 B 列).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/scd');
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

// 打开"登记干系人"填必填项并按 categoryLabel 选业务分类, 责任人 admin, 保存.
async function createStakeholder(page, code, name, categoryLabel) {
  await drawer(page).getByRole('button', { name: '登记干系人', exact: true }).click();
  const form = modal(page, '登记干系人');
  await form.locator('#code').fill(code);
  await form.locator('#name').fill(name);
  await form.locator('#role').fill('需求方接口');
  await choose(page, form, 'category', categoryLabel);
  await choose(page, form, 'interest', 'high');
  await choose(page, form, 'influence', 'high');
  await choose(page, form, 'owner_id', 'admin');
  await save(page, '登记干系人');
}

test.describe('H02 干系人类别分布只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('类别分布面板按最新干系人聚合五档类别与覆盖/主导类别, 追加一名新类别干系人后覆盖类别数与覆盖率翻转', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `SCD-${suffix}`, name: `干系人类别分布验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '干系人与沟通');

    // 面板初始态: 尚无干系人 -> 展示空态占位文案, 不出现"干系人总数"标签.
    const distEmpty = panel(page, '干系人类别分布');
    await expect(distEmpty).toBeVisible();
    await expect(distEmpty.getByText('暂无干系人, 登记后可在此查看业务类别分布.', { exact: true })).toBeVisible();
    // 面板说明文案含"给出干系人总数"字样, 用带数字的锚定正则只匹配标签本身, 空态下不出现该标签.
    await expect(distEmpty.getByText(/^干系人总数 \d+$/)).toHaveCount(0);

    // 阶段一 四名干系人: SH-1 internal, SH-2 internal, SH-3 external, SH-4 supplier.
    await createStakeholder(page, `SH-1-${suffix}`, '内部干系人甲', '内部');
    await createStakeholder(page, `SH-2-${suffix}`, '内部干系人乙', '内部');
    await createStakeholder(page, `SH-3-${suffix}`, '外部干系人', '外部');
    await createStakeholder(page, `SH-4-${suffix}`, '供应商代表', '供应商');

    // 总数4, 覆盖 internal/external/supplier 三类, 未覆盖 2, 覆盖率 round(3/5)=60, 主导 内部·2.
    await open(page, id, '需求与治理', '干系人与沟通');
    const dist1 = panel(page, '干系人类别分布');
    await expect(dist1.getByText('干系人总数 4', { exact: true })).toBeVisible();
    await expect(dist1.getByText('覆盖类别 3', { exact: true })).toBeVisible();
    await expect(dist1.getByText('未覆盖类别 2', { exact: true })).toBeVisible();
    await expect(dist1.getByText('类别覆盖率 60%', { exact: true })).toBeVisible();
    await expect(dist1.getByText('主导类别 内部 · 2', { exact: true })).toBeVisible();
    await expect(dist1.getByText('内部 · 2', { exact: true })).toBeVisible();
    await expect(dist1.getByText('外部 · 1', { exact: true })).toBeVisible();
    await expect(dist1.getByText('供应商 · 1', { exact: true })).toBeVisible();
    // 客户/监管方两类尚无干系人, 不渲染对应徽标.
    await expect(dist1.getByText('客户 · 1', { exact: true })).toHaveCount(0);
    await expect(dist1.getByText('监管方 · 1', { exact: true })).toHaveCount(0);
    await panelShot(dist1, 'scd-1-internal-dominant.png');

    // 阶段二 单操作者追加登记一名 customer 干系人 -> 总数5, 覆盖类别 3->4, 未覆盖 2->1, 覆盖率 60->80, 主导仍 内部·2.
    await createStakeholder(page, `SH-5-${suffix}`, '客户代表', '客户');
    await open(page, id, '需求与治理', '干系人与沟通');
    const dist2 = panel(page, '干系人类别分布');
    await expect(dist2.getByText('干系人总数 5', { exact: true })).toBeVisible();
    await expect(dist2.getByText('覆盖类别 4', { exact: true })).toBeVisible();
    await expect(dist2.getByText('未覆盖类别 1', { exact: true })).toBeVisible();
    await expect(dist2.getByText('类别覆盖率 80%', { exact: true })).toBeVisible();
    await expect(dist2.getByText('主导类别 内部 · 2', { exact: true })).toBeVisible();
    await expect(dist2.getByText('客户 · 1', { exact: true })).toBeVisible();
    await panelShot(dist2, 'scd-2-four-categories.png');

    // 真实HTTP读模型回显 stakeholder_category_distribution 与面板同源.
    const ws = await api(page, 'GET', base(id) + '/governance');
    const dist = ws.stakeholder_category_distribution;
    const catOf = k => (dist['by-category'].find(x => x.category === k) || {}).count;
    expect(dist.available, '有干系人').toBe(true);
    expect(dist.total, '干系人总数').toBe(5);
    expect(dist.covered, '覆盖类别数').toBe(4);
    expect(dist.uncovered, '未覆盖类别数').toBe(1);
    expect(dist['coverage-pct'], '类别覆盖率').toBe(80);
    expect(dist['dominant-category'], '主导类别').toBe('internal');
    expect(dist['dominant-count'], '主导类别数').toBe(2);
    expect(catOf('internal'), '内部类').toBe(2);
    expect(catOf('external'), '外部类').toBe(1);
    expect(catOf('supplier'), '供应商类').toBe(1);
    expect(catOf('customer'), '客户类').toBe(1);
    expect(catOf('regulator'), '监管方类').toBe(0);
    expect(dist['by-category'].map(x => x.category), '五档固定顺序').toEqual(['internal', 'external', 'supplier', 'customer', 'regulator']);
    expect(dist.covered + dist.uncovered, '覆盖+未覆盖=类别总数5').toBe(5);
    expect(dist['by-category'].reduce((s, x) => s + x.count, 0), '五档之和=干系人总数').toBe(dist.total);

    // 只读派生不改变记录: 重复读取稳定, 五名干系人仍 active.
    const ws2 = await api(page, 'GET', base(id) + '/governance');
    expect(ws2.stakeholder_category_distribution, '只读派生重复读取稳定').toEqual(dist);
    for (const code of ['SH-1', 'SH-2', 'SH-3', 'SH-4', 'SH-5']) {
      expect(ws2.stakeholders.find(x => x.code.startsWith(code)).status, `干系人 ${code} 仍登记态`).toBe('active');
    }

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
