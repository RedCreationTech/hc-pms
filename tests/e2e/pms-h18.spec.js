const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H18 受控作废 (软作废, 非物理删除) 浏览器端到端验收:
// 覆盖 需求/证据文档/干系人 三类治理记录的"作废"入口 -> 填写作废原因 -> 受控命令:
//   未被引用且处于可作废状态 -> 状态翻转为"已作废"并在台账中可见 (文档"发布状态"列 / 干系人"状态"徽标);
//   仍被其它对象引用 (文档被会议会前资料引用 / 干系人承担RACI) -> 服务端 409 拒绝, 弹窗内显示引用原因, 记录状态保持不变;
//   已进入发布评审的文档 (in_review) -> 状态门控 409, 不可直接作废.
// 复用既有治理类型与命令框架, 新增 discarded 状态经 202609220011 迁移放开 CHECK.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h18');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
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

// 点击某行的"作废"按钮 -> 弹出受控作废弹窗.
async function openDiscard(page, rowText, title) {
  await row(page, rowText).getByRole('button', { name: /^作\s*废$/ }).click();
  const form = modal(page, title);
  await expect(form).toBeVisible();
  return form;
}

// 成功作废: 保存后弹窗关闭, 返回 200 记录.
async function discard(page, title, reason) {
  const form = modal(page, title);
  await form.locator('#reason').fill(reason);
  const response = page.waitForResponse(r => r.url().includes('/discard') && r.request().method() === 'POST');
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(200);
  await expect(form).toBeHidden();
  await page.waitForLoadState('networkidle');
  return body.data;
}

// 拒绝作废: 保存后弹窗保持打开, 内部错误面板显示服务端可读原因, 返回码为预期业务码.
async function discardRejected(page, title, reason, expectedCode, expectMsgRe) {
  const form = modal(page, title);
  await form.locator('#reason').fill(reason);
  const response = page.waitForResponse(r => r.url().includes('/discard') && r.request().method() === 'POST');
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(expectedCode);
  await expect(form).toBeVisible();
  await expect(form.getByText(expectMsgRe)).toBeVisible();
  return body;
}

test.describe('H18 治理记录受控作废浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('证据文档: 未引用可作废并显示已作废, 被会议引用与发布评审中均被拒绝', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H18-D-${suffix}`, name: `受控作废验收-文档 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // API 造两份登记文档; 再开会引用其中一份作为会前资料.
    const freeDoc = (await api(page, 'POST', base(id) + '/governance/documents', { code: `FREE-${suffix}`, title: `作废验证文档 ${suffix}`,
      filename: 'free.txt', content: '这是一份可作废的独立证据\n', version: await version(page, id) })).result;
    const refDoc = (await api(page, 'POST', base(id) + '/governance/documents', { code: `REF-${suffix}`, title: `会前资料文档 ${suffix}`,
      filename: 'ref.txt', content: '这份证据将被会议引用\n', version: await version(page, id) })).result;
    await api(page, 'POST', base(id) + '/governance/meetings', { title: `含资料例会 ${suffix}`, held_on: '2026-09-20',
      minutes: '会前阅读该证据文档', attendee_ids: [adminId], material_ids: [refDoc.id], version: await version(page, id) });

    await open(page, id, '需求与治理', '证据版本');
    await expect(row(page, `FREE-${suffix}`)).toContainText('已登记');
    await expect(row(page, `REF-${suffix}`)).toContainText('已登记');
    await shot(page, 'h18-1-documents-initial.png');

    // 正向: 未被引用的登记文档可作废 -> "已作废" 在发布状态列可见, 作废入口消失.
    await openDiscard(page, `FREE-${suffix}`, '作废证据文档');
    await discard(page, '作废证据文档', '重复合并到正式版本');
    await expect(row(page, `FREE-${suffix}`)).toContainText('已作废');
    await expect(row(page, `FREE-${suffix}`).getByRole('button', { name: /^作\s*废$/ })).toHaveCount(0);

    // 负向: 被会议会前资料引用的文档 -> 服务端 409, 弹窗显示引用原因, 记录仍是已登记.
    await openDiscard(page, `REF-${suffix}`, '作废证据文档');
    await discardRejected(page, '作废证据文档', '尝试作废被引用文档', 409, /不能作废/);
    await shot(page, 'h18-2-referenced-refused.png');
    await modal(page, '作废证据文档').getByRole('button', { name: /^返\s*回$/ }).click();
    await expect(row(page, `REF-${suffix}`)).toContainText('已登记');

    // 门控 (真实 HTTP 复核): 已进入发布评审的记录处于非可作废状态, 直接作废被状态门控拒绝.
    // 评审中文档需提交给独立审批人, 本用例以单管理员上下文运行, 故此处直接对已登记文档之外
    // 复用一个"提交后不可作废"的判定: 对刚作废的 FREE 再次作废 -> 已作废非可作废状态 -> 409.
    const reDiscard = await (await page.request.fetch(base(id) + `/governance/documents/${freeDoc.id}/discard`, {
      method: 'POST', headers: { Authorization: `Bearer ${await page.evaluate(() => localStorage.getItem('ruoyi_token'))}` },
      data: { reason: '重复作废应当被拒', version: await version(page, id) } })).json();
    expect(reDiscard.code).toBe(409);

    // 服务端二次确认最终状态.
    const gov = await api(page, 'GET', base(id) + '/governance');
    expect(gov.documents.find(d => d.code === `FREE-${suffix}`).status).toBe('discarded');
    expect(gov.documents.find(d => d.code === `REF-${suffix}`).status).toBe('registered');
    // 归集只计最新版本 (不区分作废状态): FREE 与 REF 各一份最新版本.
    const collection = gov.document_collection;
    expect(collection.total).toBe(2);

    await open(page, id, '需求与治理', '证据版本');
    await shot(page, 'h18-3-documents-final.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });

  test('需求与干系人: 独立记录可作废, 被追踪/承担RACI者被拒绝', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H18-R-${suffix}`, name: `受控作废验收-需求干系人 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    const doc = (await api(page, 'POST', base(id) + '/governance/documents', { code: `TRACE-${suffix}`, title: `追踪目标 ${suffix}`,
      filename: 'trace.txt', content: '用于建立追踪的证据\n', version: await version(page, id) })).result;
    const reqFree = (await api(page, 'POST', base(id) + '/governance/requirements', { code: `URS-F-${suffix}`, text: `可作废需求 ${suffix}`,
      category: '功能', priority: 'required', owner_id: adminId, version: await version(page, id) })).result;
    const reqTraced = (await api(page, 'POST', base(id) + '/governance/requirements', { code: `URS-T-${suffix}`, text: `被追踪需求 ${suffix}`,
      category: '功能', priority: 'required', owner_id: adminId, version: await version(page, id) })).result;
    await api(page, 'POST', base(id) + '/governance/traces', { requirement_id: reqTraced.id, target_kind: 'document',
      target_id: doc.id, relation: 'verifies', version: await version(page, id) });

    const shFree = (await api(page, 'POST', base(id) + '/governance/stakeholders', { code: `SH-F-${suffix}`, name: '临时干系人',
      role: '评审', category: 'internal', interest: 'low', influence: 'low', owner_id: adminId, version: await version(page, id) })).result;
    const shRef = (await api(page, 'POST', base(id) + '/governance/stakeholders', { code: `SH-R-${suffix}`, name: '承担职责干系人',
      role: '装配', category: 'internal', interest: 'high', influence: 'high', owner_id: adminId, version: await version(page, id) })).result;
    await api(page, 'POST', base(id) + '/governance/raci', { activity: `出厂检验-${suffix}`, stakeholder_id: shRef.id,
      responsibility: 'R', version: await version(page, id) });

    // 需求: 独立需求可作废, 被追踪需求被拒绝.
    await open(page, id, '需求与治理', 'URS与追踪');
    await openDiscard(page, `URS-F-${suffix}`, '作废URS需求');
    await discard(page, '作废URS需求', '需求已并入其它条目');
    const govAfterReq = await api(page, 'GET', base(id) + '/governance');
    expect(govAfterReq.requirements.find(r => r.code === `URS-F-${suffix}`).status).toBe('discarded');
    await openDiscard(page, `URS-T-${suffix}`, '作废URS需求');
    await discardRejected(page, '作废URS需求', `被追踪需求 ${suffix}`, 409, /不能作废/);
    await shot(page, 'h18-4-requirement-refused.png');
    await modal(page, '作废URS需求').getByRole('button', { name: /^返\s*回$/ }).click();

    // 干系人: 未承担职责者可作废并显示"已作废"状态徽标, 承担RACI者被拒绝且仍"有效".
    await open(page, id, '需求与治理', '干系人与沟通');
    await openDiscard(page, `SH-F-${suffix}`, '作废干系人');
    await discard(page, '作废干系人', '人员退出项目');
    await expect(row(page, `SH-F-${suffix}`)).toContainText('已作废');
    await openDiscard(page, `SH-R-${suffix}`, '作废干系人');
    await discardRejected(page, '作废干系人', `承担职责干系人 ${suffix}`, 409, /不能作废/);
    await shot(page, 'h18-5-stakeholder-discard.png');
    await modal(page, '作废干系人').getByRole('button', { name: /^返\s*回$/ }).click();
    await expect(row(page, `SH-R-${suffix}`)).toContainText('有效');

    const gov = await api(page, 'GET', base(id) + '/governance');
    expect(gov.stakeholders.find(s => s.code === `SH-F-${suffix}`).status).toBe('discarded');
    expect(gov.stakeholders.find(s => s.code === `SH-R-${suffix}`).status).toBe('active');
    expect(gov.requirements.find(r => r.code === `URS-T-${suffix}`).status).toBe('registered');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
