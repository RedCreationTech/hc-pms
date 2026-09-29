const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H18 延伸: DQ 签认快照只读标注其绑定交付件"业务编码最新版本"是否已被受控作废(discarded) (免迁移, 无新命令/新kind/新路由/不门控).
// admin 建项目 -> 真实HTTP建交付件文档 v1 并登记一个把该文档绑定为交付件的 DQ 关键任务 ->
//   重载 DQ 台账"交付件作废"列显示绿色"未作废", "版本失效"列绿色"版本有效";
//   真实HTTP新增不可变修订 v2(同编号) -> 因存在更新版本"版本失效"翻红"交付件已更新", 但尚未作废故"交付件作废"仍"未作废";
//   再作废 v2 -> 该编号最新版本现为 discarded -> 重载"交付件作废"列追加红色"已作废 1", 而 DQ 快照仍指向旧 v1(deliverable_ids 不漂移);
//   真实HTTP受控恢复 v2 -> 红色"已作废"标注回落消失. 全程真实点击+真实HTTP+真实浏览器截图, 只读派生不改不可变版本.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h18dq');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const dqCard = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: 'DQ 编制与确认', exact: true }) });
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

async function command(page, id, suffix, data = {}) {
  const project = await api(page, 'GET', base(id));
  const result = await api(page, 'POST', base(id) + '/governance' + suffix, { ...data, version: project.version });
  return result.result;
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

async function shotCard(page, card, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.describe('H18 DQ 交付件作废只读标注浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('交付件编码最新版本被作废 -> DQ"交付件作废"列追加"已作废", 恢复后消失', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H18DQ-${suffix}`, name: `DQ交付件作废标注验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 1) 真实HTTP: 建交付件文档 v1.
    const codeD = `DDOC-${suffix}`;
    const docV1 = await command(page, id, '/documents', { code: codeD, title: `交付件-${suffix}`, filename: '交付.txt', content: '确定版本交付件\n' });

    // 2) 真实HTTP: 登记一个把 v1 绑定为交付件的 DQ 关键任务.
    const dqTitle = `交付件作废确认-${suffix}`;
    const dq = await command(page, id, '/dqs', { code: `DQ-${suffix}`, title: dqTitle, owner_id: adminId,
      checklist: [{ code: 'C-1', title: '交付件核对', required: true }], deliverable_ids: [docV1.id] });

    // 3) 重载: 编码最新版本未作废 -> "交付件作废"绿色"未作废", "版本失效"绿色"版本有效".
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    await expect(dqCard(page).getByRole('columnheader', { name: '交付件作废', exact: true })).toBeVisible();
    await expect(row(page, dqTitle).getByText('未作废', { exact: true })).toBeVisible();
    await expect(row(page, dqTitle).getByText('版本有效', { exact: true })).toBeVisible();
    await expect(row(page, dqTitle).getByText('已作废 1', { exact: true })).toHaveCount(0);
    await shotCard(page, dqCard(page), 'h18dq-1-clean.png');

    // 4) 真实HTTP回显: 未作废 -> dq_deliverable_voided false / dq_voided_deliverables 0.
    const gov1 = await api(page, 'GET', base(id) + '/governance');
    const d1 = gov1.dqs.find(d => d.id === dq.id);
    expect(d1.dq_deliverable_voided, '未作废时不标注').toBe(false);
    expect(d1.dq_voided_deliverables, '未作废时计数 0').toBe(0);

    // 5) 真实HTTP: 新增不可变修订 v2(同编号). 有更新版本 -> "版本失效"翻红, 但 v2 尚未作废 -> "交付件作废"仍绿.
    const docV2 = await command(page, id, `/documents/${docV1.id}/revisions`, { code: codeD, title: `交付件-${suffix}`, filename: '交付v2.txt', content: '第二版交付件\n' });
    expect(docV2.revision, '修订递增').toBe(2);
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    await expect(row(page, dqTitle).getByText('交付件已更新', { exact: true })).toBeVisible();
    await expect(row(page, dqTitle).getByText('未作废', { exact: true })).toBeVisible();

    // 6) 真实HTTP: 作废 v2 -> 该编号最新版本为 discarded -> 重载"交付件作废"列追加红色"已作废 1".
    await command(page, id, `/documents/${docV2.id}/discard`, { reason: '上传错误版本作废' });
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    await expect(row(page, dqTitle).getByText('已作废 1', { exact: true })).toBeVisible();
    await shotCard(page, dqCard(page), 'h18dq-2-voided.png');

    // 7) 真实HTTP回显: DQ 快照仍指向 v1(deliverable_ids 不漂移) 但 dq_deliverable_voided 翻 true.
    const gov2 = await api(page, 'GET', base(id) + '/governance');
    const d2 = gov2.dqs.find(d => d.id === dq.id);
    expect(d2.dq_deliverable_voided, '编码最新版本作废 -> DQ 标注 true').toBe(true);
    expect(d2.dq_voided_deliverables, '作废命中 1 个交付件').toBe(1);
    expect(d2.deliverable_ids, 'DQ 快照所指向的 v1 id 不漂移').toEqual([docV1.id]);

    // 8) 真实HTTP: 受控恢复 v2 -> 最新版本回到非作废 -> 红色"已作废"标注回落消失.
    await command(page, id, `/documents/${docV2.id}/restore`, { reason: '误作废恢复' });
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    await expect(row(page, dqTitle).getByText('已作废 1', { exact: true })).toHaveCount(0);
    await expect(row(page, dqTitle).getByText('未作废', { exact: true })).toBeVisible();
    await shotCard(page, dqCard(page), 'h18dq-3-restored.png');

    const gov3 = await api(page, 'GET', base(id) + '/governance');
    const d3 = gov3.dqs.find(d => d.id === dq.id);
    expect(d3.dq_deliverable_voided, '恢复后标注回落 false').toBe(false);
    expect(d3.dq_voided_deliverables, '恢复后计数 0').toBe(0);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
