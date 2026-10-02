const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H02 延伸: 沟通节奏到期汇总项目级只读派生洞察 (免迁移, 无新命令/新kind/新路由).
// 界面"登记沟通计划"里选不同沟通频率与下次沟通日期 -> "干系人与沟通"页签新增只读"沟通节奏到期汇总"面板:
// 按每个沟通计划最新有效版本把下次沟通三分已逾期(<=今天)/临期(1-7天内)/未来到期(距今>临期窗口),
// 并按五种沟通频率回显计划条数分布; 再登记不同到期日/频率计划后各桶实时翻转.
// 只读派生与逐条台账"沟通到期"预警同源(comm-plan-read-model 的 comm_overdue/comm_days_until), 不改变计划状态.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h02cc');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
// shared/panel 渲染 <section> 内含 <h3> 标题; "沟通计划总数"等标签唯一, 仍按面板标题作用域定位避免 strict-mode 串台.
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

// 打开"登记干系人"填必填项并保存 (责任人 admin), 供沟通计划受众引用.
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
}

// 打开"登记沟通计划"填必填项: 指定编号/频率/受众(唯一已登记干系人)/下次沟通日期, 停在保存前供截图或保存.
async function fillCommPlan(page, { code, frequency, audienceLabel, next }) {
  await drawer(page).getByRole('button', { name: '登记沟通计划', exact: true }).click();
  const form = modal(page, '登记沟通计划');
  await form.locator('#code').fill(code);
  await form.locator('#objective').fill('同步项目进度与风险');
  await choose(page, form, 'channel', '会议');
  await choose(page, form, 'frequency', frequency);
  await choose(page, form, 'audience', audienceLabel);
  await form.locator('#next_date').fill(next);
  await choose(page, form, 'owner_id', 'admin');
}

test.describe('H02 沟通节奏到期汇总只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('沟通节奏到期面板按最新计划三分已逾期/临期/未来到期并回显频率分布, 再登记不同到期/频率计划后各桶翻转', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `HCC-${suffix}`, name: `沟通节奏到期汇总验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '干系人与沟通');

    // 先登记一个干系人作为沟通计划受众 (受众标签 = "编号 / 名称").
    const shCode = `SH-${suffix}`;
    await createStakeholder(page, shCode);
    const audienceLabel = `${shCode} / 进度对接人`;

    // 1) 界面登记三条沟通计划: 下次沟通 -5(已逾期, 周频) / +2(临期<=7天, 月频) / +30(未来到期, 日频).
    await fillCommPlan(page, { code: `CP-OV-${suffix}`, frequency: 'weekly', audienceLabel, next: isoDate(-5) });
    await save(page, '登记沟通计划');
    await fillCommPlan(page, { code: `CP-SOON-${suffix}`, frequency: 'monthly', audienceLabel, next: isoDate(2) });
    await save(page, '登记沟通计划');
    await fillCommPlan(page, { code: `CP-FUT-${suffix}`, frequency: 'daily', audienceLabel, next: isoDate(30) });
    await save(page, '登记沟通计划');

    // 2) 沟通节奏到期面板: 总数3, 已逾期1 / 临期1 / 未来到期1, 频率分布 每周1 / 每月1 / 每日1, 双周/每季度不渲染.
    await open(page, id, '需求与治理', '干系人与沟通');
    const cad = panel(page, '沟通节奏到期汇总');
    await expect(cad).toBeVisible();
    await expect(cad.getByText('沟通计划总数 3', { exact: true })).toBeVisible();
    await expect(cad.getByText('已逾期 1', { exact: true })).toBeVisible();
    await expect(cad.getByText('临期 1', { exact: true })).toBeVisible();
    await expect(cad.getByText('未来到期 1', { exact: true })).toBeVisible();
    await expect(cad.getByText('每周 · 1', { exact: true })).toBeVisible();
    await expect(cad.getByText('每月 · 1', { exact: true })).toBeVisible();
    await expect(cad.getByText('每日 · 1', { exact: true })).toBeVisible();
    await expect(cad.getByText(/双周\s+·/)).toHaveCount(0);
    await expect(cad.getByText(/每季度\s+·/)).toHaveCount(0);
    await panelShot(cad, 'h02cc-1-cadence.png');

    // 3) 真实HTTP读模型回显 comm_cadence_summary.
    const ws = await api(page, 'GET', base(id) + '/governance');
    const cc = ws.comm_cadence_summary;
    expect(cc.available, '有数据').toBe(true);
    expect(cc.total, '分母=最新有效版本计划数').toBe(3);
    expect(cc.overdue, '已逾期').toBe(1);
    expect(cc['due-soon'], '临期').toBe(1);
    expect(cc.upcoming, '未来到期').toBe(1);
    const freq = f => (cc['by-frequency'].find(x => x.frequency === f) || {}).count;
    expect(freq('weekly'), '周频').toBe(1);
    expect(freq('monthly'), '月频').toBe(1);
    expect(freq('daily'), '日频').toBe(1);
    expect(freq('quarterly'), '季频').toBe(0);

    // 4) 界面再登记一条双周/未来(+40)计划 -> 总数4, 已逾期1/临期1/未来到期2, 双周 · 1 出现.
    await fillCommPlan(page, { code: `CP-BIW-${suffix}`, frequency: 'biweekly', audienceLabel, next: isoDate(40) });
    await save(page, '登记沟通计划');
    await open(page, id, '需求与治理', '干系人与沟通');
    const cad2 = panel(page, '沟通节奏到期汇总');
    await expect(cad2.getByText('沟通计划总数 4', { exact: true })).toBeVisible();
    await expect(cad2.getByText('已逾期 1', { exact: true })).toBeVisible();
    await expect(cad2.getByText('临期 1', { exact: true })).toBeVisible();
    await expect(cad2.getByText('未来到期 2', { exact: true })).toBeVisible();
    await expect(cad2.getByText('双周 · 1', { exact: true })).toBeVisible();
    await panelShot(cad2, 'h02cc-2-after-more-plans.png');

    // 5) 真实HTTP回显翻转后一致.
    const ws2 = await api(page, 'GET', base(id) + '/governance');
    expect(ws2.comm_cadence_summary.total, '总数翻到4').toBe(4);
    expect(ws2.comm_cadence_summary.upcoming, '未来到期升到2').toBe(2);
    expect(ws2.comm_cadence_summary.overdue, '已逾期仍1').toBe(1);

    // 6) 只读派生不改变计划状态: 已逾期计划仍为登记态且到期标记不漂移.
    const od = ws2.comm_plans.find(p => p.code === `CP-OV-${suffix}`);
    expect(od.status, '既有计划仍为登记态(只读派生不改状态)').toBe('active');
    expect(od.comm_overdue, '逐条沟通到期标记与汇总同源').toBe(true);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
