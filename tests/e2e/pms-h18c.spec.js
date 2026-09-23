const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H18c 文档归集剔除 + 受控作废级联影响预览 浏览器端到端验收:
// 覆盖两类界面可见事实(免迁移, discarded 状态与级联预览均为只读派生):
//   (a) "文档归集视图" 按每个文档编号的最新版本聚合; 最新版本被受控作废后不再计入 total,
//       改以红色"已作废 N 未计入"标签透明呈现; 恢复后重新计入.
//   (b) 需求/证据文档/干系人行内"级联影响"按钮打开只读预览弹窗:
//       无引用时显示"可安全作废"+"未发现引用该记录的其它对象.";
//       文档被会议作为会前资料引用时显示"不可作废"并列出"会议 <标题>", 预览本身不改变任何状态.
// 复用 H18/H18b 的治理类型与命令框架.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h18c');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const previewBtn = page => page.getByRole('button', { name: /^级\s*联\s*影\s*响$/ });
const discardBtn = page => page.getByRole('button', { name: /^作\s*废$/ });

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

async function version(page, id) {
  const project = await api(page, 'GET', base(id));
  return project.version;
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
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 点击某行的"级联影响"按钮, 等待预览弹窗加载并返回其定位器.
async function openPreview(page, rowText) {
  await row(page, rowText).getByRole('button', { name: /^级\s*联\s*影\s*响$/ }).click();
  const dlg = page.getByRole('dialog').filter({ hasText: '级联影响预览' });
  await expect(dlg).toBeVisible();
  await expect(dlg.getByText(/当前状态:/)).toBeVisible();
  return dlg;
}

// 点击某行的"作废"按钮并成功作废.
async function discardRow(page, rowText, title, reason) {
  await row(page, rowText).getByRole('button', { name: /^作\s*废$/ }).click();
  const form = modal(page, title);
  await expect(form).toBeVisible();
  await form.locator('#reason').fill(reason);
  const response = page.waitForResponse(r => r.url().includes('/discard') && r.request().method() === 'POST');
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(200);
  await expect(form).toBeHidden();
  await page.waitForLoadState('networkidle');
}

async function createProject(page, prefix, label) {
  const suffix = serial();
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
  const adminId = options.currentUserId;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `${prefix}-${suffix}`, name: `归集级联验收-${label} / ${suffix}`,
    project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
  return { id: project.project_id, suffix, adminId };
}

test.describe('H18c 文档归集剔除与级联影响预览浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('证据文档: 级联预览显示可安全作废; 作废后从归集剔除并出现红色"已作废 N 未计入"', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const { id, suffix, adminId } = await createProject(page, 'H18C-A', '文档归集');

    // 两份证据文档: DOC-A(内部/设计准备/主机), DOC-B(公开/装配/附件), 各仅一个版本.
    await api(page, 'POST', base(id) + '/governance/documents', { code: `DOC-A-${suffix}`, title: `设计说明 ${suffix}`,
      filename: 'a.txt', content: '这是设计阶段证据\n', classification: 'internal', stage: '设计准备', structure_node: '主机',
      version: await version(page, id) });
    await api(page, 'POST', base(id) + '/governance/documents', { code: `DOC-B-${suffix}`, title: `装配记录 ${suffix}`,
      filename: 'b.txt', content: '这是装配阶段证据\n', classification: 'public', stage: '装配', structure_node: '附件',
      version: await version(page, id) });

    await open(page, id, '需求与治理', '证据版本');

    // 归集初始: 最新版本证据 2, 无"已作废"标签.
    const collection = drawer(page).getByText('文档归集视图', { exact: false }).first();
    await expect(collection).toBeVisible();
    await expect(drawer(page).getByText('最新版本证据 2', { exact: true })).toHaveCount(1);
    await expect(drawer(page).getByText(/已作废\s*\d+\s*未计入/)).toHaveCount(0);
    await shot(page, 'h18c-1-collection-before.png');

    // 级联影响预览: DOC-A 无引用 -> 可安全作废 + 未发现引用.
    const dlg = await openPreview(page, `DOC-A-${suffix}`);
    await expect(dlg.getByText('可安全作废')).toBeVisible();
    await expect(dlg.getByText('未发现引用该记录的其它对象.')).toBeVisible();
    await expect(dlg.getByText(/仍被以下/)).toHaveCount(0);
    await shot(page, 'h18c-2-preview-safe.png');
    await dlg.getByRole('button', { name: /Close|close|×/ }).click();
    await expect(dlg).toBeHidden();

    // 作废 DOC-A -> 归集 total 降到 1, 出现红色"已作废 1 未计入".
    await discardRow(page, `DOC-A-${suffix}`, '作废证据文档', '重复上传先作废');
    await expect(drawer(page).getByText('最新版本证据 1', { exact: true })).toHaveCount(1);
    await expect(drawer(page).getByText('最新版本证据 2', { exact: true })).toHaveCount(0);
    await expect(drawer(page).getByText(/已作废\s*1\s*未计入/)).toHaveCount(1);
    await shot(page, 'h18c-3-collection-after-discard.png');

    // 服务端二次确认 document_collection: total=1, discarded-count=1, 机密/内部/公开分布正确.
    const gov = await api(page, 'GET', base(id) + '/governance');
    const col = gov.document_collection;
    expect(col.total).toBe(1);
    expect(col['discarded-count']).toBe(1);
    const byClass = Object.fromEntries(col['by-classification'].map(c => [c.classification, c.count]));
    expect(byClass.public).toBe(1);   // DOC-B 仍在
    expect(byClass.internal || 0).toBe(0); // DOC-A 已作废不计入

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });

  test('需求/证据文档被引用时级联预览显示"不可作废"并列出引用对象', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const { id, suffix, adminId } = await createProject(page, 'H18C-B', '级联引用');

    // 一份证据文档, 被会议纪要作为会前资料引用.
    const doc = (await api(page, 'POST', base(id) + '/governance/documents', { code: `DOC-R-${suffix}`, title: `评审材料 ${suffix}`,
      filename: 'r.txt', content: '会议要用的会前材料\n', classification: 'internal', stage: '评审', structure_node: '主机',
      version: await version(page, id) })).result;
    await api(page, 'POST', base(id) + '/governance/meetings', { title: `阶段评审会 ${suffix}`, held_on: '2026-09-15',
      minutes: '本次评审会引用了下方材料.', attendee_ids: [adminId], material_ids: [doc.id],
      version: await version(page, id) });

    await open(page, id, '需求与治理', '证据版本');

    // 级联预览: DOC-R 被会议引用 -> 不可作废 + 列出"会议 <标题>".
    const dlg = await openPreview(page, `DOC-R-${suffix}`);
    await expect(dlg.getByText('不可作废')).toBeVisible();
    await expect(dlg.getByText('可安全作废')).toHaveCount(0);
    await expect(dlg.getByText(/仍被以下\s*1\s*个对象引用/)).toBeVisible();
    await expect(dlg.getByText(`会议 阶段评审会 ${suffix}`)).toBeVisible();
    await shot(page, 'h18c-4-preview-blocked.png');

    // 预览只读: 关闭弹窗后记录仍为"已登记"(未被作废).
    await dlg.getByRole('button', { name: /Close|close|×/ }).click();
    await expect(dlg).toBeHidden();
    await expect(row(page, `DOC-R-${suffix}`)).toContainText('已登记');

    const gov = await api(page, 'GET', base(id) + '/governance');
    const still = gov.documents.find(d => d.code === `DOC-R-${suffix}`);
    expect(still.status).toBe('registered');
    // 归集仍计入该文档: total>=1 且无"已作废"标签.
    expect(gov.document_collection.total).toBeGreaterThanOrEqual(1);
    expect(gov.document_collection['discarded-count']).toBe(0);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });

  test('干系人被 RACI 引用时级联预览显示"不可作废"', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const { id, suffix, adminId } = await createProject(page, 'H18C-C', '干系人引用');

    const sh = (await api(page, 'POST', base(id) + '/governance/stakeholders', { code: `SH-${suffix}`, name: '被引用干系人',
      role: '评审', category: 'internal', interest: 'high', influence: 'high', owner_id: adminId, version: await version(page, id) })).result;
    await api(page, 'POST', base(id) + '/governance/raci', { activity: `架构决策 ${suffix}`, stakeholder_id: sh.id, responsibility: 'A',
      version: await version(page, id) });

    await open(page, id, '需求与治理', '干系人与沟通');
    const dlg = await openPreview(page, `SH-${suffix}`);
    await expect(dlg.getByText('不可作废')).toBeVisible();
    await expect(dlg.getByText(/仍被以下\s*1\s*个对象引用/)).toBeVisible();
    await expect(dlg.getByText(`RACI 架构决策 ${suffix}`)).toBeVisible();
    await shot(page, 'h18c-5-preview-stakeholder-blocked.png');
    await dlg.getByRole('button', { name: /Close|close|×/ }).click();
    await expect(row(page, `SH-${suffix}`)).toContainText('有效');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
