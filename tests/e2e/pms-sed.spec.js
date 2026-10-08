const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H02 延伸: 干系人参与态度分布项目级只读派生洞察 (免迁移, 无新命令/新kind/新路由).
// 界面"登记干系人"选当前参与态度(engagement, 可选: 未知晓/抵制/中立/支持/主导) -> "干系人与沟通"页签新增只读"干系人参与态度分布"面板:
// 按每个干系人最新有效版本(store/latest 折叠修订链, 剔除已作废)只读聚合五档参与态度的项目级分布,
// 给出干系人总数 / 已声明态度人数 / 未设定态度人数 / 已用态度档数 / 缺档态度档数 / 态度覆盖率 / 主导态度, 以及固定五档各自干系人数与占比.
// :engagement 为可选字段(留空则不落入任何档), 故 declared 可小于 total, unassigned=total-declared.
// 与参与态度覆盖度(看多少干系人声明了态度)和评估矩阵(看当前与期望差距)互补, 与刚交付的类别/象限分布同族,
// 只读派生不落库不投递, 不改变任何不可变版本, 不构成任何门控.
// 主导态度翻转与覆盖率翻转均由单个操作者追加"登记"干系人达成(单上下文可见的纯只读加性翻转, 依 Rule B 可进 B 列).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/sed');
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

// 打开"登记干系人"填必填项, 关注度/影响力保持默认 medium(与态度分布无关), 仅在给出 engagement 时选当前参与态度, 责任人 admin, 保存.
async function createStakeholder(page, code, name, engagement) {
  await drawer(page).getByRole('button', { name: '登记干系人', exact: true }).click();
  const form = modal(page, '登记干系人');
  await form.locator('#code').fill(code);
  await form.locator('#name').fill(name);
  await form.locator('#role').fill('需求方接口');
  await choose(page, form, 'category', '内部');
  if (engagement) await choose(page, form, 'engagement', engagement);
  await choose(page, form, 'owner_id', 'admin');
  await save(page, '登记干系人');
}

test.describe('H02 干系人参与态度分布只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('态度分布面板按最新干系人聚合五档态度与声明/未设定/覆盖/主导态度, 追加登记使主导态度与覆盖率严格唯一最大翻转', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `SED-${suffix}`, name: `干系人参与态度分布验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '干系人与沟通');

    // 面板初始态: 尚无干系人 -> 展示空态占位文案, 不出现"干系人总数"标签.
    const distEmpty = panel(page, '干系人参与态度分布');
    await expect(distEmpty).toBeVisible();
    await expect(distEmpty.getByText('暂无干系人, 登记并设定参与态度后可在此查看五档分布.', { exact: true })).toBeVisible();
    // 面板说明文案含"给出干系人总数"字样, 用带数字的锚定正则只匹配标签本身, 空态下不出现该标签.
    await expect(distEmpty.getByText(/^干系人总数 \d+$/)).toHaveCount(0);
    await expect(distEmpty.getByText(/^主导态度 /)).toHaveCount(0);

    // 阶段一 四名干系人: 未知晓/抵制/支持 各1, 再加一名"留空未设定态度"干系人(可选字段).
    await createStakeholder(page, `SE-A-${suffix}`, '未知晓甲', '未知晓');
    await createStakeholder(page, `SE-B-${suffix}`, '抵制乙', '抵制');
    await createStakeholder(page, `SE-C-${suffix}`, '支持丙', '支持');
    await createStakeholder(page, `SE-D-${suffix}`, '未设定态度', null);

    // 总数4, 声明3/未设定1, 覆盖 unaware/resistant/supportive 三档, 缺档2, 覆盖率 round(3/5)=60,
    // 三档各1并列 -> max-key 取 engagement-order 靠后者 支持·1 为主导; 中立/主导 ·0 仍渲染.
    await open(page, id, '需求与治理', '干系人与沟通');
    const dist1 = panel(page, '干系人参与态度分布');
    await expect(dist1.getByText('干系人总数 4', { exact: true })).toBeVisible();
    await expect(dist1.getByText('态度覆盖率 60%', { exact: true })).toBeVisible();
    await expect(dist1.getByText('已用态度 3', { exact: true })).toBeVisible();
    await expect(dist1.getByText('缺档态度 2', { exact: true })).toBeVisible();
    await expect(dist1.getByText('未设定态度 1', { exact: true })).toBeVisible();
    await expect(dist1.getByText('主导态度 支持 · 1', { exact: true })).toBeVisible();
    await expect(dist1.getByText('未知晓 · 1 (25%)', { exact: true })).toBeVisible();
    await expect(dist1.getByText('抵制 · 1 (25%)', { exact: true })).toBeVisible();
    await expect(dist1.getByText('中立 · 0 (0%)', { exact: true })).toBeVisible();
    await expect(dist1.getByText('支持 · 1 (25%)', { exact: true })).toBeVisible();
    await expect(dist1.getByText('主导 · 0 (0%)', { exact: true })).toBeVisible();
    await panelShot(dist1, 'sed-1-supportive-dominant.png');

    // 阶段二 单操作者追加登记一名"主导"干系人 -> leading 补上, 覆盖4, 缺档1, 覆盖率 60->80,
    // unaware/resistant/supportive/leading 各1并列 -> 主导翻转为 engagement-order 靠后的 主导·1.
    await createStakeholder(page, `SE-E-${suffix}`, '主导戊', '主导');
    await open(page, id, '需求与治理', '干系人与沟通');
    const dist2 = panel(page, '干系人参与态度分布');
    await expect(dist2.getByText('干系人总数 5', { exact: true })).toBeVisible();
    await expect(dist2.getByText('态度覆盖率 80%', { exact: true })).toBeVisible();
    await expect(dist2.getByText('已用态度 4', { exact: true })).toBeVisible();
    await expect(dist2.getByText('缺档态度 1', { exact: true })).toBeVisible();
    await expect(dist2.getByText('未设定态度 1', { exact: true })).toBeVisible();
    await expect(dist2.getByText('主导态度 主导 · 1', { exact: true })).toBeVisible();
    await expect(dist2.getByText('未知晓 · 1 (20%)', { exact: true })).toBeVisible();
    await expect(dist2.getByText('抵制 · 1 (20%)', { exact: true })).toBeVisible();
    await expect(dist2.getByText('中立 · 0 (0%)', { exact: true })).toBeVisible();
    await expect(dist2.getByText('支持 · 1 (20%)', { exact: true })).toBeVisible();
    await expect(dist2.getByText('主导 · 1 (20%)', { exact: true })).toBeVisible();
    // 主导标签已翻转: 不再出现旧的"主导态度 支持"徽标.
    await expect(dist2.getByText('主导态度 支持 · 1', { exact: true })).toHaveCount(0);
    await panelShot(dist2, 'sed-2-leading-dominant.png');

    // 阶段三 追加登记一名"中立"干系人 -> 五档全覆盖, 覆盖率 80->100, 缺档态度徽标消失.
    await createStakeholder(page, `SE-F-${suffix}`, '中立己', '中立');
    await open(page, id, '需求与治理', '干系人与沟通');
    const dist3 = panel(page, '干系人参与态度分布');
    await expect(dist3.getByText('干系人总数 6', { exact: true })).toBeVisible();
    await expect(dist3.getByText('态度覆盖率 100%', { exact: true })).toBeVisible();
    await expect(dist3.getByText('已用态度 5', { exact: true })).toBeVisible();
    await expect(dist3.getByText(/^缺档态度 \d+$/)).toHaveCount(0);
    await expect(dist3.getByText('未设定态度 1', { exact: true })).toBeVisible();
    await expect(dist3.getByText('中立 · 1 (17%)', { exact: true })).toBeVisible();
    await expect(dist3.getByText('主导 · 1 (17%)', { exact: true })).toBeVisible();
    await panelShot(dist3, 'sed-3-all-engagements.png');

    // 真实HTTP读模型回显 stakeholder_engagement_distribution 与面板同源.
    const ws = await api(page, 'GET', base(id) + '/governance');
    const dist = ws.stakeholder_engagement_distribution;
    const ec = k => (dist['by-engagement'].find(x => x.engagement === k) || {});
    expect(dist.available, '有干系人').toBe(true);
    expect(dist.total, '干系人总数').toBe(6);
    expect(dist.declared, '已声明态度人数').toBe(5);
    expect(dist.unassigned, '未设定态度人数').toBe(1);
    expect(dist.covered, '已用态度档数').toBe(5);
    expect(dist.uncovered, '缺档态度档数').toBe(0);
    expect(dist['coverage-pct'], '态度覆盖率').toBe(100);
    expect(dist['dominant-engagement'], '主导态度').toBe('leading');
    expect(dist['dominant-count'], '主导态度人数').toBe(1);
    expect(ec('unaware').count, '未知晓').toBe(1);
    expect(ec('resistant').count, '抵制').toBe(1);
    expect(ec('neutral').count, '中立').toBe(1);
    expect(ec('supportive').count, '支持').toBe(1);
    expect(ec('leading').count, '主导').toBe(1);
    expect(dist['by-engagement'].map(x => x.engagement), '五档固定顺序')
      .toEqual(['unaware', 'resistant', 'neutral', 'supportive', 'leading']);
    expect(dist.covered + dist.uncovered, '覆盖+缺档=态度档总数5').toBe(5);
    expect(dist['by-engagement'].reduce((s, x) => s + x.count, 0), '五档之和=已声明人数').toBe(dist.declared);

    // 只读派生不改变记录: 重复读取稳定, 六名干系人仍 active.
    const ws2 = await api(page, 'GET', base(id) + '/governance');
    expect(ws2.stakeholder_engagement_distribution, '只读派生重复读取稳定').toEqual(dist);
    for (const code of ['SE-A', 'SE-B', 'SE-C', 'SE-D', 'SE-E', 'SE-F']) {
      expect(ws2.stakeholders.find(x => x.code.startsWith(code)).status, `干系人 ${code} 仍登记态`).toBe('active');
    }

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
