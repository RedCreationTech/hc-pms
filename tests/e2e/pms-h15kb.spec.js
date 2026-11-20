const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H15 跨项目经验复用: 全局检索页新增"跨项目知识复用"只读面板.
// 服务端 GET /api/pms/knowledge 对当前用户全部授权项目批量读取经验, 派生覆盖度/按场景分布/回链项目台账;
// 免迁移/无新命令/不写存储/不改变任何门控, 越权项目经验不出现在结果内.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h15kb');
const panel = page => page.locator('section').filter({ has: page.getByRole('heading', { name: '跨项目知识复用', exact: true }) }).first();

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

async function newProject(page, suffix, name) {
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H15KB-${suffix}`, name,
    project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  return { id: project.project_id, ownerId: options.users.find(u => u.user_id !== options.currentUserId)?.user_id || options.currentUserId };
}

async function lesson(page, id, body) {
  const project = await api(page, 'GET', `/api/pms/projects/${id}`);
  return api(page, 'POST', `/api/pms/projects/${id}/closure/lessons`, { ...body, version: project.version });
}

async function knowledge(page, query = {}) {
  const search = new URLSearchParams(query).toString();
  return api(page, 'GET', `/api/pms/knowledge${search ? `?${search}` : ''}`);
}

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function shotPanel(page, file) {
  const el = panel(page);
  await el.scrollIntoViewIfNeeded();
  await expect(el.getByRole('heading', { name: '跨项目知识复用', exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.describe('H15 跨项目经验复用', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('两个项目的经验在检索页汇总, 按场景/关键字选择性检索, 服务端与界面同源且写入仍走收尾台账', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const suffix = serial();
    const a = await newProject(page, `${suffix}A`, `知识复用甲 ${suffix}`);
    const b = await newProject(page, `${suffix}B`, `知识复用乙 ${suffix}`);
    const titleA = `装配线节拍瓶颈 ${suffix}`;
    const titleB = `客户验收表模板 ${suffix}`;
    await lesson(page, a.id, { title: titleA, category: '质量', content: '先做工位平衡表再排产', applicable_stage: '执行', owner_id: a.ownerId });
    await lesson(page, b.id, { title: titleB, category: '交付', content: '验收前统一模板并预演', applicable_stage: '收尾' });

    // 1. 服务端汇总两个项目的经验, 覆盖度/项目数派生自真实行.
    const all = await knowledge(page, { q: suffix });
    expect(all.available).toBe(true);
    expect(all.total).toBe(2);
    expect(all['project-count']).toBe(2);
    expect(all.rows.map(r => r.title).sort()).toEqual([titleA, titleB].sort());
    expect(all.rows.every(r => r.project_no && r.project_name)).toBe(true);

    // 2. 按场景/关键字选择性检索, 关键字同时匹配正文, 非法场景 400.
    expect((await knowledge(page, { q: suffix, stage: '执行' })).rows.map(r => r.title)).toEqual([titleA]);
    expect((await knowledge(page, { q: suffix, category: '交付' })).rows.map(r => r.title)).toEqual([titleB]);
    expect((await knowledge(page, { q: '工位平衡表' })).rows.map(r => r.title)).toContain(titleA);
    const bad = await page.evaluate(async () => {
      const token = localStorage.getItem('ruoyi_token');
      const res = await fetch('/api/pms/knowledge?stage=' + encodeURIComponent('不存在的场景'), { headers: { Authorization: `Bearer ${token}` } });
      return (await res.json()).code;
    });
    expect(bad).toBe(400);

    // 3. 界面与服务端同源: 面板标签与回链表格呈现同一批经验.
    await page.goto('/pms/search');
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByRole('cell', { name: titleA })).toBeVisible();
    await expect(panel(page).getByRole('cell', { name: titleB })).toBeVisible();
    await expect(panel(page).getByText('已标注适用场景', { exact: false })).toBeVisible();
    await shot(page, 'h15kb-1-search.png');
    await shotPanel(page, 'h15kb-1-panel.png');

    // 面板按关键字收窄后, 标签数值与同一条件下的服务端响应一致 (同源同口径).
    await panel(page).getByLabel('经验关键字').fill(suffix);
    await panel(page).getByRole('button', { name: '知识检索', exact: true }).click();
    await expect(panel(page).getByText(`可复用经验 ${all.total}`, { exact: true })).toBeVisible();
    await expect(panel(page).getByText(`涉及项目 ${all['project-count']}`, { exact: true })).toBeVisible();

    // 4. 面板内按场景检索只留下对应经验.
    await panel(page).getByLabel('适用场景筛选').click();
    await page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: /^执行$/ }).click();
    await panel(page).getByRole('button', { name: '知识检索', exact: true }).click();
    await expect(panel(page).getByRole('cell', { name: titleA })).toBeVisible();
    await expect(panel(page).getByRole('cell', { name: titleB })).toHaveCount(0);
    await shotPanel(page, 'h15kb-2-stage.png');

    // 5. 只读派生不取代写入路径: 经验仍由收尾台账登记 (缺标题被拒), 面板不新增任何写接口.
    const project = await api(page, 'GET', `/api/pms/projects/${a.id}`);
    await api(page, 'POST', `/api/pms/projects/${a.id}/closure/lessons`, { version: project.version, category: '质量' }, 400);
    expect((await knowledge(page, { q: suffix })).total).toBe(2);

    expect(errors).toEqual([]);
  });
});