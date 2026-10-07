const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H08 延伸: 风险项目阶段(:stage)分布只读派生洞察 (免迁移, 无新命令/新kind/新路由).
// 阶段取自"从典型风险库选用"实例化的条目(设计/采购/执行/全周期), 手工"登记项目风险"不带阶段(计入未标注) ->
// "风险与问题"页签新增只读"风险项目阶段分布"面板: 按每个风险最新有效版本聚合各阶段计数/平均评分/高危及以上/严重(达升级阈值)数,
// 附未标注阶段计数; 再实例化一条新阶段风险后阶段种类与分布实时翻转. 只读派生不改变任何风险状态. 全程单上下文(仅登记+读取), 无独立审批门控.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/rsd');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
// shared/panel 渲染 <section> 内含 <h3> 标题; 面板靠下且与姊妹面板标签重名, 须按标题作用域定位避免 strict-mode 串台.
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) });
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

// 打开"从典型风险库选用"按库条目标题实例化一条带阶段的风险 (阶段/评分固定来自库).
async function instantiate(page, id, label, due) {
  await open(page, id, '需求与治理', '风险与问题');
  await drawer(page).getByRole('button', { name: '从典型风险库选用', exact: true }).click();
  const form = modal(page, '从典型风险库选用');
  await choose(page, form, 'template_key', label);
  await choose(page, form, 'owner_id', 'admin');
  await form.locator('#due_date').fill(due || '2026-10-20');
  return save(page, '从典型风险库选用');
}

// 打开"登记项目风险"手工登记一条不带阶段的风险 (计入未标注).
async function fillManualRisk(page, { title, p, i }) {
  await drawer(page).getByRole('button', { name: '登记项目风险', exact: true }).click();
  const form = modal(page, '登记项目风险');
  await form.locator('#title').fill(title);
  await form.locator('#probability').fill(String(p));
  await form.locator('#impact').fill(String(i));
  await choose(page, form, 'owner_id', 'admin');
  await form.locator('#mitigation').fill('持续监控并预留纠偏窗口.');
  await form.locator('#due_date').fill('2026-10-20');
}

// test.use 必须置于文件顶层 (放进 describe 会强制新 worker 报错).
test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

test.describe('H08 风险项目阶段分布只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('阶段面板按库实例化风险分档计数, 手工登记计入未标注, 补实例化新阶段后分布翻转', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `RSD-${suffix}`, name: `风险项目阶段分布验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 1) 界面从典型风险库实例化四条(设计9/采购25/执行16/执行20), 再手工登记一条无阶段(1x2=2).
    await instantiate(page, id, '关键技术方案不成熟');   // 设计 3x3=9
    await instantiate(page, id, '关键物料断供');         // 采购 5x5=25 (达升级阈值仍计入)
    await instantiate(page, id, '关键路径进度延误');     // 执行 4x4=16
    await instantiate(page, id, '项目成本超支');         // 执行 4x5=20
    await open(page, id, '需求与治理', '风险与问题');
    await fillManualRisk(page, { title: `常规观察风险-${suffix}`, p: 1, i: 2 });
    await save(page, '登记项目风险');

    // 2) 阶段分布面板: 总数5, 涉及阶段3, 未标注1; 执行两条均分18高危2严重2, 采购均分25高危1严重1, 设计均分9无高危.
    await open(page, id, '需求与治理', '风险与问题');
    const dist = panel(page, '风险项目阶段分布');
    await expect(dist).toBeVisible();
    await expect(dist.getByText('风险总数 5', { exact: true })).toBeVisible();
    await expect(dist.getByText('涉及阶段 3', { exact: true })).toBeVisible();
    await expect(dist.getByText('未标注阶段 1', { exact: true })).toBeVisible();
    await expect(dist.getByText('执行 · 2 · 均分18 · 高危2 · 严重2', { exact: true })).toBeVisible();
    await expect(dist.getByText('采购 · 1 · 均分25 · 高危1 · 严重1', { exact: true })).toBeVisible();
    await expect(dist.getByText('设计 · 1 · 均分9', { exact: true })).toBeVisible();
    await panelShot(dist, 'rsd-1-stage-distribution.png');

    // 3) 真实HTTP读模型回显 risk_stage_distribution (穷尽分区不变式 + 排序 critical降序->high->count->阶段名).
    const ws = await api(page, 'GET', base(id) + '/governance');
    const d = ws.risk_stage_distribution;
    expect(d.available, '有数据').toBe(true);
    expect(d.total, '分母=最新有效版本风险数').toBe(5);
    expect(d['labeled-stages'], '涉及阶段数').toBe(3);
    expect(d.unclassified, '未标注阶段数').toBe(1);
    expect(d.total, '各阶段计数之和+未标注=总数').toBe(d.unclassified + d.stages.reduce((s, x) => s + x.count, 0));
    expect(d.stages.map(x => x.stage), '排序: 执行(严重2)>采购(严重1)>设计(严重0)').toEqual(['执行', '采购', '设计']);
    const st = k => d.stages.find(x => x.stage === k);
    expect(st('执行'), '执行阶段指标').toMatchObject({ count: 2, 'avg-score': 18, 'high-or-above': 2, critical: 2 });
    expect(st('采购'), '采购阶段指标').toMatchObject({ count: 1, 'avg-score': 25, 'high-or-above': 1, critical: 1 });
    expect(st('设计'), '设计阶段指标').toMatchObject({ count: 1, 'avg-score': 9, 'high-or-above': 0, critical: 0 });

    // 4) 界面再实例化一条全周期阶段低风险(2x3=6): 新增一个阶段种类, 阶段名升序使全周期排在同为0严重的"设计"之前.
    await instantiate(page, id, '关键人员流失');    // 全周期 2x3=6
    await open(page, id, '需求与治理', '风险与问题');
    const dist2 = panel(page, '风险项目阶段分布');
    await expect(dist2.getByText('风险总数 6', { exact: true })).toBeVisible();
    await expect(dist2.getByText('涉及阶段 4', { exact: true })).toBeVisible();
    await expect(dist2.getByText('全周期 · 1 · 均分6', { exact: true })).toBeVisible();
    await panelShot(dist2, 'rsd-2-after-new-stage.png');

    // 5) 真实HTTP回显翻转后阶段种类增至4, 排序含全周期.
    const ws2 = await api(page, 'GET', base(id) + '/governance');
    const d2 = ws2.risk_stage_distribution;
    expect(d2.total, '总数翻到6').toBe(6);
    expect(d2['labeled-stages'], '阶段种类翻到4').toBe(4);
    expect(d2.unclassified, '未标注仍为1').toBe(1);
    expect(d2.stages.map(x => x.stage), '排序含全周期').toEqual(['执行', '采购', '全周期', '设计']);

    // 6) 只读派生不改变风险状态: 采购风险(5x5=25)仍登记态且评分不漂移.
    const supply = ws2.risks.find(r => r.title === '关键物料断供');
    expect(supply.status, '既有风险仍为登记态(只读派生不改状态)').toBe('open');
    expect(supply.score, '风险评分不漂移').toBe(25);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
