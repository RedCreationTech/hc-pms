const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H15 经验教训类别分布与作者覆盖度只读汇总 (只读派生): 结项与移交 页签内"经验复盘分布"面板, 聚合项目经验的
// 分类覆盖/分布与集中度/参与人数 (免迁移/无门控/无新命令/无新路由). 数据来自 closure workspace 顶层 :lesson_summary,
// 由 pms_lesson 现有行派生; 经验登记本身与结项审批门控仍由服务端状态机在写入时强制, 本面板不放宽任何约束.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h15ls');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const base = id => `/api/pms/projects/${id}`;
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '经验复盘分布', exact: true }) }).first();

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

async function registerLesson(page, id, { title, category, content }) {
  await open(page, id, '结项与移交');
  await drawer(page).getByRole('button', { name: '登记项目经验', exact: true }).click();
  await fill(page.getByRole('dialog', { name: '登记项目经验', exact: true }), { title, category, content });
  await save(page, '登记项目经验');
}

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function shotPanel(page, file) {
  const el = panel(page);
  await el.scrollIntoViewIfNeeded();
  await expect(el.getByRole('heading', { name: '经验复盘分布', exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function fixture(page, label) {
  const suffix = serial();
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H15-${suffix}`, name: `${label} ${suffix}`,
    project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  return { id: project.project_id, suffix, adminId: options.currentUserId };
}

test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

test.describe('H15 经验复盘分布只读汇总', () => {
  test.setTimeout(240000);

  test('空态 -> 登记4条经验(交付x3, 质量x1), 面板分类分布/集中度/作者与 :lesson_summary 一致且只读不门控', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f = await fixture(page, '经验复盘分布');
    const id = f.id;

    // 1. 尚无经验 -> 面板空态, 顶层 :lesson_summary.available=false.
    await open(page, id, '结项与移交');
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('尚无项目经验, 登记后跟踪复盘分类分布与贡献覆盖')).toBeVisible();
    await shot(page, 'h15ls-1-empty.png');
    await shotPanel(page, 'h15ls-1-empty-panel.png');
    let ls = (await api(page, 'GET', base(id) + '/closure')).lesson_summary;
    expect(ls.available).toBe(false);
    expect(ls.total).toBe(0);
    expect(ls['concentration-pct']).toBe(0);
    expect(ls['dominant-category']).toBeNull();
    expect(ls['by-category']).toEqual([]);

    // 2. 界面登记 4 条经验: 交付 x3, 质量 x1 (全部由 admin 登记 -> 作者覆盖 1 人).
    await registerLesson(page, id, { title: `交付经验甲-${f.suffix}`, category: '交付', content: '首版交付节奏偏慢.' });
    await registerLesson(page, id, { title: `交付经验乙-${f.suffix}`, category: '交付', content: '提前对齐验收标准可提速.' });
    await registerLesson(page, id, { title: `交付经验丙-${f.suffix}`, category: '交付', content: '交付清单模板化.' });
    await registerLesson(page, id, { title: `质量经验-${f.suffix}`, category: '质量', content: '增加出厂抽检.' });

    // 3. 聚合面板: 经验总数4/分类覆盖2类/参与人数1/最活跃作者4条/主导类别交付3/集中度75%; 分类分布表含交付(3)与质量(1).
    await open(page, id, '结项与移交');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('经验总数 4', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('分类覆盖 2 类', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('参与人数 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('最活跃作者 4 条', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('主导类别 交付 3 条', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('分类集中度 75%', { exact: true })).toBeVisible();
    await expect(panel(page).getByRole('cell', { name: '交付', exact: true })).toBeVisible();
    await expect(panel(page).getByRole('cell', { name: '质量', exact: true })).toBeVisible();
    await expect(panel(page).getByRole('columnheader', { name: '经验类别', exact: true })).toBeVisible();
    await expect(panel(page).getByRole('columnheader', { name: '占比', exact: true })).toBeVisible();
    await shot(page, 'h15ls-2-aggregated.png');
    await shotPanel(page, 'h15ls-2-aggregated-panel.png');

    // 4. 真实 HTTP 读回 :lesson_summary, 与界面一致 (只读派生).
    ls = (await api(page, 'GET', base(id) + '/closure')).lesson_summary;
    expect(ls).toMatchObject({ available: true, total: 4, 'distinct-categories': 2, categorized: 4, uncategorized: 0,
      'dominant-category': '交付', 'dominant-count': 3, 'concentration-pct': 75, 'author-count': 1, 'top-author-count': 4 });
    expect(ls['by-category'].map(x => x.category)).toEqual(['交付', '质量']);
    expect(ls['by-category'][0]).toMatchObject({ category: '交付', count: 3 });
    expect(ls['by-category'][1]).toMatchObject({ category: '质量', count: 1 });

    // 5. 只读不门控: 经验登记不改变 progress 经验计数一致性, 也不放宽结项审批门控 -> 非 closing 提交仍 409.
    const ov = await api(page, 'GET', base(id) + '/closure');
    expect(ov.progress.lessons).toBe(4);
    expect(ov.lessons.length).toBe(4);
    const project = await api(page, 'GET', base(id));
    await api(page, 'POST', base(id) + '/closure/submit', { reviewer_id: f.adminId, version: project.version }, 409);

    expect(errors).toEqual([]);
  });
});
