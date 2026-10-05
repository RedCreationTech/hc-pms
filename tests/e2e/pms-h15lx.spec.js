const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H15a 经验教训标记适用场景与跟进责任人 (真实持久化写路径字段 + 只读派生):
//   写路径 — "登记项目经验" 对话框新增可选 applicable_stage (受控枚举 12 项) 与 owner_id (须为有效项目成员/管理者);
//   读路径 — closure/lessons LEFT JOIN sys_user 回显 owner_name; :lesson_summary 追加只读派生 stages-declared/distinct-stages/by-stage/owner-assigned/owner-coverage-pct.
//   台账新增 "适用场景" 与 "责任人" 两列, "经验复盘分布" 面板追加场景覆盖/责任人落地徽标与按场景分布表.
//   门控不变: 经验登记不放宽结项审批 (非 closing 提交仍 409). 免新 kind/免新命令/免新路由.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h15lx');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const base = id => `/api/pms/projects/${id}`;
const summaryPanel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '经验复盘分布', exact: true }) }).first();
const lessonTable = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '经验复盘', exact: true }) }).first();

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

async function open(page, id, section) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  if (section) await tab(page, section);
}

async function fill(form, values) {
  for (const [key, value] of Object.entries(values)) await form.locator(`#${key}`).fill(String(value));
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
  const form = page.getByRole('dialog', { name: title, exact: true });
  const response = page.waitForResponse(r => r.url().includes('/api/pms/') && ['POST', 'PUT', 'DELETE'].includes(r.request().method()));
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(200);
  await expect(form).toBeHidden();
  await page.waitForLoadState('networkidle');
  return body.data;
}

async function registerLesson(page, id, { title, category, content, stage, owner }) {
  await open(page, id, '结项与移交');
  await drawer(page).getByRole('button', { name: '登记项目经验', exact: true }).click();
  const form = page.getByRole('dialog', { name: '登记项目经验', exact: true });
  await fill(form, { title, category, content });
  if (stage) await choose(page, form, 'applicable_stage', stage);
  if (owner) await choose(page, form, 'owner_id', owner);
  await save(page, '登记项目经验');
}

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function shotSection(page, el, file) {
  await el.scrollIntoViewIfNeeded();
  await page.waitForLoadState('networkidle');
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function fixture(page, label) {
  const suffix = serial();
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H15X-${suffix}`, name: `${label} ${suffix}`,
    project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  return { id: project.project_id, suffix, adminId: options.currentUserId };
}

test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

test.describe('H15a 经验适用场景与跟进责任人', () => {
  test.setTimeout(300000);

  test('登记经验标注场景+责任人 -> 台账两列可见, 面板场景覆盖/责任人落地与 :lesson_summary 一致, 非法场景 400', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f = await fixture(page, '经验场景责任人');
    const id = f.id;

    // 1. 空态: 场景/责任人派生为 0, 面板提示尚无经验.
    await open(page, id, '结项与移交');
    await expect(summaryPanel(page)).toBeVisible();
    await expect(summaryPanel(page).getByText('尚无项目经验, 登记后跟踪复盘分类分布与贡献覆盖')).toBeVisible();
    await shot(page, 'h15lx-1-empty.png');
    await shotSection(page, summaryPanel(page), 'h15lx-1-empty-panel.png');
    let ov = await api(page, 'GET', base(id) + '/closure');
    expect(ov.lesson_summary.available).toBe(false);
    expect(ov.lesson_summary['stages-declared']).toBe(0);
    expect(ov.lesson_summary['owner-assigned']).toBe(0);

    // 2. 台账新增两列头: 适用场景 / 责任人.
    await expect(lessonTable(page).getByRole('columnheader', { name: '适用场景', exact: true })).toBeVisible();
    await expect(lessonTable(page).getByRole('columnheader', { name: '责任人', exact: true })).toBeVisible();

    // 3. 界面登记 4 条经验: A/B 场景=执行 + 责任人=admin; C 场景=收尾 无责任人; D 无场景无责任人.
    await registerLesson(page, id, { title: `到货延迟经验-${f.suffix}`, category: '交付', content: '设备到货延迟需前置确认交期.', stage: '执行', owner: 'admin' });
    await registerLesson(page, id, { title: `验收对齐经验-${f.suffix}`, category: '质量', content: '提前对齐验收标准可提速.', stage: '执行', owner: 'admin' });
    await registerLesson(page, id, { title: `复盘会议经验-${f.suffix}`, category: '质量', content: '评审不足需增加抽检.', stage: '收尾' });
    await registerLesson(page, id, { title: `留空兼容经验-${f.suffix}`, category: '成本', content: '不标注场景与责任人.' });

    // 4. 台账可见: 场景与责任人单元格真实渲染.
    await open(page, id, '结项与移交');
    await lessonTable(page).scrollIntoViewIfNeeded();
    await expect(lessonTable(page).getByRole('cell', { name: '执行', exact: true }).first()).toBeVisible();
    await expect(lessonTable(page).getByRole('cell', { name: '收尾', exact: true })).toBeVisible();
    await shot(page, 'h15lx-2-registered.png');
    await shotSection(page, lessonTable(page), 'h15lx-2-table.png');

    // 5. 面板派生: 场景覆盖 3 条/2 类, 责任人落地 2/4 (50%).
    await summaryPanel(page).scrollIntoViewIfNeeded();
    await expect(summaryPanel(page).getByText('适用场景覆盖 3 条 / 2 类', { exact: true })).toBeVisible();
    await expect(summaryPanel(page).getByText('责任人落地 2/4 (50%)', { exact: true })).toBeVisible();
    await expect(summaryPanel(page).getByRole('columnheader', { name: '适用场景', exact: true }).first()).toBeVisible();
    await expect(summaryPanel(page).getByRole('cell', { name: '执行', exact: true }).first()).toBeVisible();
    await shotSection(page, summaryPanel(page), 'h15lx-3-summary-panel.png');

    // 6. 真实 HTTP 读回逐行 applicable_stage/owner_id/owner_name 与 :lesson_summary 派生一致.
    ov = await api(page, 'GET', base(id) + '/closure');
    const byTitle = Object.fromEntries(ov.lessons.map(l => [l.title, l]));
    const a = byTitle[`到货延迟经验-${f.suffix}`];
    const c = byTitle[`复盘会议经验-${f.suffix}`];
    const d = byTitle[`留空兼容经验-${f.suffix}`];
    expect(a.applicable_stage).toBe('执行');
    expect(a.owner_id).toBe(f.adminId);
    expect(typeof a.owner_name).toBe('string');
    expect(a.owner_name.length).toBeGreaterThan(0);
    expect(c.applicable_stage).toBe('收尾');
    expect(c.owner_id).toBeNull();
    expect(c.owner_name).toBeNull();
    expect(d.applicable_stage).toBeNull();
    expect(d.owner_id).toBeNull();
    const ls = ov.lesson_summary;
    expect(ls).toMatchObject({ available: true, total: 4, 'stages-declared': 3, 'distinct-stages': 2, 'owner-assigned': 2, 'owner-coverage-pct': 50 });
    expect(ls['by-stage'].map(x => x.stage)).toEqual(['执行', '收尾']);
    expect(ls['by-stage'][0]).toMatchObject({ stage: '执行', count: 2 });

    // 7. 非法场景经真实 HTTP 写路径 -> 400 (受控枚举白名单).
    const project = await api(page, 'GET', base(id));
    await api(page, 'POST', base(id) + '/closure/lessons',
      { title: '非法场景', category: '风险', content: 'x', applicable_stage: '未知场景', version: project.version }, 400);

    // 8. 只读派生不放宽结项门控: 非 closing 提交关闭审批仍 409.
    await api(page, 'POST', base(id) + '/closure/submit', { reviewer_id: f.adminId, version: project.version }, 409);

    expect(errors).toEqual([]);
  });
});
