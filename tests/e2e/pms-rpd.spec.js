const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C02/C03 延伸: 需求优先级分布项目级只读派生洞察 (免迁移, 无新命令/新kind/新路由).
// 界面"新增URS需求"选需求优先级(priority, 必填受控枚举 必需/期望 required/desired) ->
// "URS与追踪"页签新增只读"需求优先级分布"面板: 按每个需求最新有效版本(store/latest 折叠修订链, 剔除已作废)
// 只读聚合必需/期望两档的项目级分布, 给出需求总数 / 已用档位 / 缺档档位 / 优先级覆盖率 / 主导优先级,
// 以及固定两档(必需/期望)各自需求数与占比. priority 为必填受控枚举, 故两档之和恒等于需求总数(无未设定档),
// 与会议类型/干系人类别/象限分布同族, 区别于验证方式覆盖度(看多少需求声明验证方式).
// 主导优先级翻转由单个操作者追加"新增URS需求"达成(单上下文可见的纯只读加性翻转, 依 Rule B 可进 B 列):
//   并列时 apply max-key 取固定顺序 [required desired] 的靠后者 => dominant desired;
//   再多登记一条必需 => required 反超 => dominant 翻回 required.
// 面板逐档标签取自后端 distribution 标签(必需/期望), 与登记表单下拉标签同口径(widgets.cljs w/labels).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/rpd');
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

// 打开"新增URS需求"填必填项(编号/描述/优先级/责任人), priorityLabel 取表单下拉标签(必需/期望), 保存.
async function createRequirement(page, code, text, priorityLabel) {
  await drawer(page).getByRole('button', { name: '新增URS需求', exact: true }).click();
  const form = modal(page, '新增URS需求');
  await form.locator('#code').fill(code);
  await form.locator('#text').fill(text);
  await choose(page, form, 'priority', priorityLabel);
  await choose(page, form, 'owner_id', 'admin');
  return save(page, '新增URS需求');
}

test.describe('C02/C03 需求优先级分布只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('优先级分布面板按最新需求聚合必需/期望两档与覆盖率/主导优先级, 追加登记使主导优先级严格双向翻转', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `RPD-${suffix}`, name: `需求优先级分布验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', 'URS与追踪');

    // 面板初始态: 尚无需求 -> 展示空态占位文案, 不出现"需求总数"标签与主导徽标.
    const distEmpty = panel(page, '需求优先级分布');
    await expect(distEmpty).toBeVisible();
    await expect(distEmpty.getByText('暂无URS需求, 登记需求后可在此查看必需/期望两档优先级分布.', { exact: true })).toBeVisible();
    await expect(distEmpty.getByText(/^需求总数 \d+$/)).toHaveCount(0);
    await expect(distEmpty.getByText(/^主导优先级 /)).toHaveCount(0);

    // 阶段一 三条需求: 必需x2, 期望x1. total3 covered2 uncovered0 pct100, 主导必需(2)唯一最大.
    await createRequirement(page, `URS-RPD-REQ-A-${suffix}`, `验收标准甲: 系统必须支持批量导出 ${suffix}`, '必需');
    await createRequirement(page, `URS-RPD-REQ-B-${suffix}`, `验收标准乙: 系统必须支持权限控制 ${suffix}`, '必需');
    await createRequirement(page, `URS-RPD-DES-A-${suffix}`, `验收标准丙: 期望支持个性化主题 ${suffix}`, '期望');

    await open(page, id, '需求与治理', 'URS与追踪');
    const dist1 = panel(page, '需求优先级分布');
    await expect(dist1.getByText('需求总数 3', { exact: true })).toBeVisible();
    await expect(dist1.getByText('优先级覆盖率 100%', { exact: true })).toBeVisible();
    await expect(dist1.getByText('已用档位 2', { exact: true })).toBeVisible();
    await expect(dist1.getByText('主导优先级 必需 · 2', { exact: true })).toBeVisible();
    await expect(dist1.getByText('必需 · 2 (67%)', { exact: true })).toBeVisible();
    await expect(dist1.getByText('期望 · 1 (33%)', { exact: true })).toBeVisible();
    // 两档齐备 -> 不出现"缺档档位"徽标.
    await expect(dist1.getByText(/^缺档档位 /)).toHaveCount(0);
    await panelShot(dist1, 'rpd-1-required-dominant.png');

    // 阶段二 追加一条"期望"需求 -> 必需2 与 期望2 并列, apply max-key 取固定序靠后者 => 主导翻转为 期望·2.
    await createRequirement(page, `URS-RPD-DES-B-${suffix}`, `验收标准丁: 期望支持暗色模式 ${suffix}`, '期望');

    await open(page, id, '需求与治理', 'URS与追踪');
    const dist2 = panel(page, '需求优先级分布');
    await expect(dist2.getByText('需求总数 4', { exact: true })).toBeVisible();
    await expect(dist2.getByText('优先级覆盖率 100%', { exact: true })).toBeVisible();
    await expect(dist2.getByText('已用档位 2', { exact: true })).toBeVisible();
    await expect(dist2.getByText('主导优先级 期望 · 2', { exact: true })).toBeVisible();
    await expect(dist2.getByText('必需 · 2 (50%)', { exact: true })).toBeVisible();
    await expect(dist2.getByText('期望 · 2 (50%)', { exact: true })).toBeVisible();
    // 主导标签已翻转: 不再出现旧的"主导优先级 必需"徽标.
    await expect(dist2.getByText('主导优先级 必需 · 2', { exact: true })).toHaveCount(0);
    await panelShot(dist2, 'rpd-2-desired-dominant.png');

    // 阶段三 再追加一条"必需"需求 -> 必需3 反超 期望2 => 主导翻回 必需·3 (单操作者纯只读加性翻转).
    await createRequirement(page, `URS-RPD-REQ-C-${suffix}`, `验收标准戊: 系统必须支持审计日志 ${suffix}`, '必需');

    await open(page, id, '需求与治理', 'URS与追踪');
    const dist3 = panel(page, '需求优先级分布');
    await expect(dist3.getByText('需求总数 5', { exact: true })).toBeVisible();
    await expect(dist3.getByText('优先级覆盖率 100%', { exact: true })).toBeVisible();
    await expect(dist3.getByText('主导优先级 必需 · 3', { exact: true })).toBeVisible();
    await expect(dist3.getByText('必需 · 3 (60%)', { exact: true })).toBeVisible();
    await expect(dist3.getByText('期望 · 2 (40%)', { exact: true })).toBeVisible();
    await panelShot(dist3, 'rpd-3-required-dominant-again.png');

    // 真实 HTTP 交叉核对: GET governance 回显 requirement_priority_distribution 与界面同源.
    const ws = await api(page, 'GET', `${base(id)}/governance`);
    const dist = ws.requirement_priority_distribution;
    expect(dist.available).toBe(true);
    expect(dist.total).toBe(5);
    expect(dist.covered).toBe(2);
    expect(dist.uncovered).toBe(0);
    expect(dist['coverage-pct']).toBe(100);
    expect(dist['dominant-priority']).toBe('required');
    expect(dist['dominant-count']).toBe(3);
    // 固定两档顺序 [required, desired], 各档 count 之和 = total, covered + uncovered = 2.
    const order = dist['by-priority'].map(x => x.priority);
    expect(order).toEqual(['required', 'desired']);
    expect(dist['by-priority'].reduce((s, x) => s + x.count, 0)).toBe(5);
    expect(dist['by-priority'][0].label).toBe('必需');
    expect(dist['by-priority'][1].label).toBe('期望');

    // 只读稳定性: 再次 GET 分布不漂移, 五条需求仍为登记态(未被只读聚合写回).
    const ws2 = await api(page, 'GET', `${base(id)}/governance`);
    expect(ws2.requirement_priority_distribution).toEqual(dist);
    const statuses = ws.requirements.map(r => r.status);
    expect(statuses.every(s => s !== 'discarded')).toBe(true);

    expect(errors, `未捕获 JS 错误: ${errors.join('; ')}`).toEqual([]);
  });
});
