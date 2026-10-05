const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H14 收尾归档就绪度分类只读汇总 (只读派生): 结项与移交 页签内"收尾归档就绪度"面板, 把结项缺口按
// 计划任务/治理与质量/财务决算/交付链/收尾清单 五类分别计数并标注是否已就绪, 给出归档就绪度百分比与下一步提示.
// 数据来自 closure workspace 顶层 :readiness (与 :blockers 同一份 blocker-groups 的只读投影);
// 免迁移/无新命令/无新路由/不门控任何写操作 (结项准入与独立关闭审批仍由真实状态机强制).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h14ar');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '收尾归档就绪度', exact: true }) }).first();

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

async function closureReadiness(page, id) {
  return (await api(page, 'GET', base(id) + '/closure')).readiness;
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

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  if (typeof text === 'string' && await input.isEditable()) await input.fill(text);
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await form.locator(`#${key}`).press('Escape');
}

async function fill(form, values) {
  for (const [key, value] of Object.entries(values)) await form.locator(`#${key}`).fill(String(value));
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

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function shotPanel(page, file) {
  const el = panel(page);
  await el.scrollIntoViewIfNeeded();
  await expect(el.getByRole('heading', { name: '收尾归档就绪度', exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function fixture(page, label) {
  const suffix = serial();
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H14-${suffix}`, name: `${label} ${suffix}`,
    project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  const evidence = (await api(page, 'POST', base(id) + '/governance/documents',
    { version: project.version, code: 'H14-EV', title: '归档就绪证据', filename: 'h14.txt', content: '合成结项证据.' })).result;
  return { id, suffix, adminId: options.currentUserId, evidence };
}

const catByKey = (rd, key) => rd.categories.find(c => c.key === key);

test.describe('H14 收尾归档就绪度分类只读汇总', () => {
  test.setTimeout(240000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('新建项目: 面板五类缺口可见, 服务端 :readiness 与 :blockers 一致; 完成必需检查后"收尾清单"类翻就绪且归档就绪度上升', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f = await fixture(page, '归档就绪度');
    const id = f.id;

    // 1. 新建项目 -> 面板可见, 展示五类缺口来源行 (计划任务/治理与质量/财务决算/交付链/收尾清单).
    await open(page, id, '结项与移交');
    await expect(panel(page)).toBeVisible();
    for (const label of ['计划任务', '治理与质量', '财务决算', '交付链', '收尾清单']) {
      await expect(panel(page).getByRole('cell', { name: label, exact: true })).toBeVisible();
    }
    await shot(page, 'h14ar-1-fresh.png');
    await shotPanel(page, 'h14ar-1-fresh-panel.png');

    // 2. 服务端只读投影一致性: readiness 派生自与 blockers 同一份 blocker-groups.
    const ov = await api(page, 'GET', base(id) + '/closure');
    const rd = ov.readiness;
    expect(rd.available).toBe(true);
    expect(rd.categories.map(c => c.key)).toEqual(['tasks', 'governance', 'finance', 'delivery', 'items']);
    // 每类 blocker-count 之和 == 总缺口 == 扁平 blockers 长度 (单一事实来源, 门控判定未变).
    expect(rd['total-blockers']).toBe(ov.blockers.length);
    expect(rd.categories.reduce((s, c) => s + c['blocker-count'], 0)).toBe(rd['total-blockers']);
    // 就绪/受阻类别计数与百分比自洽.
    expect(rd['clear-count']).toBe(rd.categories.filter(c => c.clear).length);
    expect(rd['blocked-count']).toBe(5 - rd['clear-count']);
    expect(rd['readiness-pct']).toBe(Math.round(100 * rd['clear-count'] / 5));
    // 新项目收尾清单类必有"缺少必需收尾清单"缺口, 计划任务类必"有阻塞" (无叶级任务).
    expect(catByKey(rd, 'items')['blocker-count']).toBeGreaterThan(0);
    expect(catByKey(rd, 'tasks')['clear']).toBe(false);
    // next-focus 为第一个仍受阻类别, 与 categories 顺序一致.
    const firstBlocked = rd.categories.find(c => !c.clear);
    expect(rd['next-focus']).toBe(firstBlocked.label);
    // 界面标签真实反映服务端派生值.
    await expect(panel(page).getByText(`归档就绪度 ${rd['readiness-pct']}%`, { exact: true })).toBeVisible();
    await expect(panel(page).getByText(`就绪类别 ${rd['clear-count']} / 5`, { exact: true })).toBeVisible();
    await expect(panel(page).getByText(`下一步优先: ${rd['next-focus']}`, { exact: true })).toBeVisible();
    await expect(panel(page).getByText(`尚有 ${rd['total-blockers']} 项缺口`, { exact: true })).toBeVisible();
    // 收尾清单类当前"有阻塞".
    await expect(row(page, '收尾清单').getByText('有阻塞', { exact: true })).toBeVisible();

    // 3. 界面新增 1 必需检查项 (transform 强制 required) 并完成 (绑定证据 + 说明) -> 收尾清单类缺口清零.
    await drawer(page).getByRole('button', { name: '添加检查项', exact: true }).click();
    const checkTitle = `归档就绪检查-${f.suffix}`;
    await fill(modal(page, '添加收尾检查项'), { title: checkTitle });
    await save(page, '添加收尾检查项');

    await open(page, id, '结项与移交');
    await drawer(page).getByRole('button', { name: '确认完成', exact: true }).first().click();
    const cform = modal(page, '完成收尾检查');
    await choose(page, cform, 'evidence_ref', 'H14-EV');
    await fill(cform, { comment: '已核对归档证据.' });
    await save(page, '完成收尾检查');

    // 4. 翻转后只读投影: 收尾清单类 blocker-count 归零 -> clear=true, 就绪类别 +1, 归档就绪度上升.
    await open(page, id, '结项与移交');
    await panel(page).scrollIntoViewIfNeeded();
    const rd2 = await closureReadiness(page, id);
    expect(catByKey(rd2, 'items')['blocker-count']).toBe(0);
    expect(catByKey(rd2, 'items')['clear']).toBe(true);
    expect(rd2['clear-count']).toBe(rd['clear-count'] + 1);
    expect(rd2['readiness-pct']).toBe(Math.round(100 * rd2['clear-count'] / 5));
    expect(rd2['readiness-pct']).toBeGreaterThan(rd['readiness-pct']);
    // 界面同步反映: 收尾清单行显示"就绪"与"无缺口", 归档就绪度百分比上调.
    await expect(row(page, '收尾清单').getByText('就绪', { exact: true })).toBeVisible();
    await expect(row(page, '收尾清单').getByText('无缺口', { exact: true })).toBeVisible();
    await expect(panel(page).getByText(`归档就绪度 ${rd2['readiness-pct']}%`, { exact: true })).toBeVisible();
    await expect(panel(page).getByText(`就绪类别 ${rd2['clear-count']} / 5`, { exact: true })).toBeVisible();
    await shot(page, 'h14ar-2-items-ready.png');
    await shotPanel(page, 'h14ar-2-items-ready-panel.png');

    // 5. 只读汇总不放宽写入约束: 项目仍在初始阶段, 提交关闭审批真实 409.
    const project = await api(page, 'GET', base(id));
    await api(page, 'POST', base(id) + '/closure/submit', { version: project.version, reviewer_id: f.adminId }, 409);

    expect(errors).toEqual([]);
  });
});
