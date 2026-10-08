const { test, expect } = require('@playwright/test');
const path = require('node:path');
const fs = require('node:fs');

// H08 延伸: 风险复审频率(:review_frequency)分布只读派生洞察 (免迁移, 无新命令/新kind/新路由).
// 手工"登记项目风险"可选声明复审频率(每周/双周/每月/每季度), 留空计入未设定 ->
// "风险与问题"页签新增只读"风险复审频率分布"面板: 按每个风险最新有效版本聚合四档条数与未闭环/复审逾期/临期计数,
// 并给出已设定覆盖率(declared/total/pct)与未设定数; 到期口径复用逐条台账复审到期倒计时(同源不漂移).
// 再补登记同档风险后覆盖率与分档计数实时翻转. 只读派生不改变任何风险状态. 全程单上下文(仅登记+读取), 无独立审批门控.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/rrfd');
fs.mkdirSync(output, { recursive: true });
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
// shared/panel 渲染 <section> 内含 <h3> 标题; 面板靠下且与姊妹面板标签重名, 须按标题作用域定位避免 strict-mode 串台.
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) });
const base = id => `/api/pms/projects/${id}`;
// 到期口径以服务器系统日为基准(与本地同时区), 用相对当天偏移生成 due_date, 免疫跨日期运行导致的逾期/临期漂移.
const dayOffset = n => {
  const d = new Date();
  d.setDate(d.getDate() + n);
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${m}-${dd}`;
};

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

// 打开"登记项目风险"手工登记一条风险, 可选声明复审频率(留空即未设定); due_date 用相对当天偏移.
async function registerRisk(page, { title, due, freq }) {
  await drawer(page).getByRole('button', { name: '登记项目风险', exact: true }).click();
  const form = modal(page, '登记项目风险');
  await form.locator('#title').fill(title);
  await form.locator('#probability').fill('2');
  await form.locator('#impact').fill('3');
  await choose(page, form, 'owner_id', 'admin');
  await form.locator('#mitigation').fill('持续监控并按复审节奏复核.');
  await form.locator('#due_date').fill(due);
  if (freq) await choose(page, form, 'review_frequency', freq);
  return save(page, '登记项目风险');
}

// test.use 必须置于文件顶层 (放进 describe 会强制新 worker 报错).
test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

test.describe('H08 风险复审频率分布只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('复审频率面板按四档分条计数与到期标记, 未设频率计入未设定, 补登记同档后覆盖率翻转', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `RRFD-${suffix}`, name: `风险复审频率分布验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 1) 界面登记五条: 每周(到期已过->逾期)/双周(+2天->临期)/每月(+60天)/每季度(+90天)/未设定(+30天).
    await open(page, id, '需求与治理', '风险与问题');
    await registerRisk(page, { title: `技术方案风险-${suffix}`, due: dayOffset(-30), freq: '每周' });
    await registerRisk(page, { title: `关键物料风险-${suffix}`, due: dayOffset(2), freq: '双周' });
    await registerRisk(page, { title: `进度偏差风险-${suffix}`, due: dayOffset(60), freq: '每月' });
    await registerRisk(page, { title: `成本超支风险-${suffix}`, due: dayOffset(90), freq: '每季度' });
    await registerRisk(page, { title: `常规观察风险-${suffix}`, due: dayOffset(30) });

    // 2) 复审频率面板: 总数5, 已设定80%(4/5), 未设定1; 每周1条逾期, 双周1条临期, 每月/每季度各1条无到期标记.
    await open(page, id, '需求与治理', '风险与问题');
    const dist = panel(page, '风险复审频率分布');
    await expect(dist).toBeVisible();
    await expect(dist.getByText('风险总数 5', { exact: true })).toBeVisible();
    await expect(dist.getByText('已设定复审频率 80% (4/5)', { exact: true })).toBeVisible();
    await expect(dist.getByText('未设定复审频率 1', { exact: true })).toBeVisible();
    await expect(dist.getByText('每周 · 1 条 · 未闭环 1 · 复审逾期 1', { exact: true })).toBeVisible();
    await expect(dist.getByText('双周 · 1 条 · 未闭环 1 · 临期 1', { exact: true })).toBeVisible();
    await expect(dist.getByText('每月 · 1 条 · 未闭环 1', { exact: true })).toBeVisible();
    await expect(dist.getByText('每季度 · 1 条 · 未闭环 1', { exact: true })).toBeVisible();
    await panelShot(dist, 'rrfd-1-frequency-distribution.png');

    // 3) 真实HTTP读模型回显 risk_review_frequency_distribution (穷尽分区不变式 + 四档固定顺序 + 各档到期计数).
    const ws = await api(page, 'GET', base(id) + '/governance');
    const d = ws.risk_review_frequency_distribution;
    expect(d.available, '有数据').toBe(true);
    expect(d.total, '分母=最新有效版本风险数').toBe(5);
    expect(d.declared, '已设定数').toBe(4);
    expect(d.unassigned, '未设定数').toBe(1);
    expect(d['declared-pct'], '覆盖率取整').toBe(80);
    expect(d.declared + d.unassigned, '已设定+未设定=总数').toBe(d.total);
    expect(d['by-frequency'].reduce((s, x) => s + x.count, 0), '各档计数之和=已设定数').toBe(d.declared);
    expect(d['by-frequency'].map(x => x.frequency), '四档固定顺序').toEqual(['weekly', 'biweekly', 'monthly', 'quarterly']);
    const bf = k => d['by-frequency'].find(x => x.frequency === k);
    expect(bf('weekly'), '每周档').toMatchObject({ count: 1, open: 1, overdue: 1, 'due-soon': 0 });
    expect(bf('biweekly'), '双周档').toMatchObject({ count: 1, open: 1, overdue: 0, 'due-soon': 1 });
    expect(bf('monthly'), '每月档').toMatchObject({ count: 1, open: 1, overdue: 0, 'due-soon': 0 });
    expect(bf('quarterly'), '每季度档').toMatchObject({ count: 1, open: 1, overdue: 0, 'due-soon': 0 });

    // 4) 界面再登记一条每周(未来到期)风险: 每周档增至2条(逾期仍1), 总数6, 覆盖率降至83%(5/6).
    await open(page, id, '需求与治理', '风险与问题');
    await registerRisk(page, { title: `人员流失风险-${suffix}`, due: dayOffset(45), freq: '每周' });
    await open(page, id, '需求与治理', '风险与问题');
    const dist2 = panel(page, '风险复审频率分布');
    await expect(dist2.getByText('风险总数 6', { exact: true })).toBeVisible();
    await expect(dist2.getByText('已设定复审频率 83% (5/6)', { exact: true })).toBeVisible();
    await expect(dist2.getByText('未设定复审频率 1', { exact: true })).toBeVisible();
    await expect(dist2.getByText('每周 · 2 条 · 未闭环 2 · 复审逾期 1', { exact: true })).toBeVisible();
    await panelShot(dist2, 'rrfd-2-after-second-weekly.png');

    // 5) 真实HTTP回显翻转: 每周档增至2条(逾期仍1, 无临期), 覆盖率降至83%.
    const ws2 = await api(page, 'GET', base(id) + '/governance');
    const d2 = ws2.risk_review_frequency_distribution;
    expect(d2.total, '总数翻到6').toBe(6);
    expect(d2.declared, '已设定翻到5').toBe(5);
    expect(d2.unassigned, '未设定仍为1').toBe(1);
    expect(d2['declared-pct'], '覆盖率降至83%').toBe(83);
    expect(bfFrom(d2, 'weekly'), '每周档翻到2条').toMatchObject({ count: 2, open: 2, overdue: 1, 'due-soon': 0 });

    // 6) 只读派生不改变风险状态: 既有每周风险仍登记态且评分不漂移.
    const weekly = ws2.risks.find(r => r.title === `技术方案风险-${suffix}`);
    expect(weekly.status, '既有风险仍非关闭(只读派生不改状态)').not.toBe('closed');
    expect(weekly.review_frequency, '复审频率字段原样保留').toBe('weekly');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});

const bfFrom = (d, k) => d['by-frequency'].find(x => x.frequency === k);
