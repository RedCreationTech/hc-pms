const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H18 延伸: 历史资料保留策略只读派生 (免迁移, 无新命令/新kind/新路由).
// "需求与治理 -> URS与追踪" 新增只读"历史资料保留策略"面板: 按各类可作废对象(需求/文档/干系人/会议)最新有效版本
// 只读聚合 在册/已作废/已作废中处于保留期(默认90天)内/已满保留期待归档/已恢复与恢复率.
// 造 需求A(正常登记) / 需求B(作废) / 需求C(作废后恢复) -> 期望 在册3 / 已作废1 / 保留期内1 / 待归档0 / 已恢复1(恢复率50%).
// 再作废需求A 使 已作废2 / 保留期内2 -> 面板与 GET /governance 回显一致. 到期记录仍可恢复, 保留策略不构成门控.
// 空态(无治理记录)单独截图.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h18rp');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) }).first();
const base = id => `/api/pms/projects/${id}`;
const TITLE = '历史资料保留策略';

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

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function panelShot(page, title, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  const card = panel(page, title);
  await expect(card.getByRole('heading', { name: title, exact: true })).toBeVisible();
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.use({ viewport: { width: 1600, height: 1000 } });

test.describe('H18 历史资料保留策略只读派生浏览器验收', () => {
  test.setTimeout(180000);

  test('已作废记录按保留期分档, 恢复后计入恢复率, 再作废后分档上翻, 面板与服务端回显一致', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H18RP-${suffix}`, name: `保留策略验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 1) 登记三条需求, 通过真实治理命令作废 B / 作废再恢复 C.
    const cmd = async (kind, suffixPath, data) => {
      const version = (await api(page, 'GET', base(id))).version;
      const res = await api(page, 'POST', base(id) + `/governance/${kind}${suffixPath}`, { ...data, version });
      return res.result;
    };
    const a = await cmd('requirements', '', { code: 'RP-A', text: '正常登记需求', category: '功能', priority: 'required', owner_id: adminId });
    const b = await cmd('requirements', '', { code: 'RP-B', text: '作废需求', category: '功能', priority: 'required', owner_id: adminId });
    const c = await cmd('requirements', '', { code: 'RP-C', text: '作废后恢复需求', category: '功能', priority: 'required', owner_id: adminId });
    expect((await cmd('requirements', `/${b.id}/discard`, { reason: '保留策略验收作废' })).status).toBe('discarded');
    expect((await cmd('requirements', `/${c.id}/discard`, { reason: '保留策略验收临时作废' })).status).toBe('discarded');
    expect((await cmd('requirements', `/${c.id}/restore`, { reason: '保留策略验收恢复' })).status).toBe('registered');

    // 2) 面板: 在册3 / 已作废1 / 保留期内1 / 待归档0 / 已恢复1 (恢复率 50%).
    await open(page, id, '需求与治理', 'URS与追踪');
    const rpPanel = panel(page, TITLE);
    await expect(rpPanel.getByText('保留期 90 天', { exact: true })).toBeVisible();
    await expect(rpPanel.getByText('在册 3', { exact: true })).toBeVisible();
    await expect(rpPanel.getByText('已作废 1', { exact: true })).toBeVisible();
    await expect(rpPanel.getByText('保留期内 1', { exact: true })).toBeVisible();
    await expect(rpPanel.getByText('待归档 0', { exact: true })).toBeVisible();
    await expect(rpPanel.getByText('已恢复 1 (恢复率 50%)', { exact: true })).toBeVisible();
    await expect(rpPanel.getByText(/URS需求 · 在册 3 · 已作废 1 · 待归档 0/)).toBeVisible();
    await panelShot(page, TITLE, 'h18rp-1-panel.png');
    await shot(page, 'h18rp-2-page.png');

    // 3) 真实HTTP读模型回显 retention_summary 与服务端派生一致.
    const ws = await api(page, 'GET', base(id) + '/governance');
    const sm = ws.retention_summary;
    expect(sm['retention-days']).toBe(90);
    expect(sm.total).toBe(3);
    expect(sm.discarded).toBe(1);
    expect(sm['within-retention']).toBe(1);
    expect(sm['due-for-archive']).toBe(0);
    expect(sm.restored).toBe(1);
    expect(sm['recovery-pct']).toBe(50);
    const req = sm['by-kind'].find(k => k.kind === 'requirement');
    expect(req.total).toBe(3);
    expect(req.discarded).toBe(1);

    // 4) 再作废 A -> 已作废2 / 保留期内2, 面板上翻证明读取时派生而非落库快照.
    expect((await cmd('requirements', `/${a.id}/discard`, { reason: '保留策略验收追加作废' })).status).toBe('discarded');
    await open(page, id, '需求与治理', 'URS与追踪');
    const rpPanel2 = panel(page, TITLE);
    await expect(rpPanel2.getByText('已作废 2', { exact: true })).toBeVisible();
    await expect(rpPanel2.getByText('保留期内 2', { exact: true })).toBeVisible();
    await expect(rpPanel2.getByText('待归档 0', { exact: true })).toBeVisible();
    const ws2 = await api(page, 'GET', base(id) + '/governance');
    expect(ws2.retention_summary.discarded).toBe(2);
    expect(ws2.retention_summary['within-retention']).toBe(2);
    expect(ws2.retention_summary['due-for-archive']).toBe(0);

    // 5) 保留期内不门控: 已作废记录仍可恢复 (恢复后 discarded 回落, restored 上升).
    expect((await cmd('requirements', `/${a.id}/restore`, { reason: '保留期内仍可恢复' })).status).toBe('registered');
    const ws3 = await api(page, 'GET', base(id) + '/governance');
    expect(ws3.retention_summary.discarded).toBe(1);
    expect(ws3.retention_summary.restored).toBe(2);

    // 6) 空态: 新项目无治理记录时面板提示暂无记录.
    const empty = await api(page, 'POST', '/api/pms/projects', { project_no: `H18RP0-${suffix}`, name: `保留策略空态 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    await open(page, empty.project_id, '需求与治理', 'URS与追踪');
    const emptyPanel = panel(page, TITLE);
    await expect(emptyPanel.getByText('暂无治理记录, 登记需求/文档/干系人/会议后可在此查看保留策略执行情况.', { exact: true })).toBeVisible();
    await panelShot(page, TITLE, 'h18rp-3-empty.png');
    const emptyWs = await api(page, 'GET', base(empty.project_id) + '/governance');
    expect(emptyWs.retention_summary.available).toBe(false);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});