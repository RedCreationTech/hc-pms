const { test, expect } = require('@playwright/test');
const path = require('node:path');
const fs = require('node:fs/promises');

const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
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

async function mutate(page, id, suffix, data = {}, method = 'POST') {
  const project = await api(page, 'GET', base(id));
  return api(page, method, base(id) + suffix, { ...data, version: project.version });
}

async function fixture(page, browser, label) {
  // 每个用例创建独立的合成项目和最小审批角色,不修改任何现有系统用户.
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve',
    'pms:quality:approve', 'pms:finance:query', 'pms:finance:approve', 'pms:time:approve', 'pms:project:close', 'pms:project:reopen']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  expect(menuIds.length).toBeGreaterThanOrEqual(8);
  await api(page, 'POST', '/api/system/role', { role_name: `验收审批${suffix}`, role_key: `pms_review_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_review_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立验收审核人', password,
    dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: '工作台E2E合成审批用户' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `WB-${suffix}`, name: `工作台验收 / ${label} ${suffix}`,
    project_type: 'line', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  await mutate(page, id, '/transition', { status: 'initiated', reason: 'E2E立项前置' });
  await mutate(page, id, '/transition', { status: 'planning', reason: 'E2E计划前置' });
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
  const reviewer = await context.newPage();
  const errors = [];
  reviewer.on('pageerror', error => errors.push(error.message));
  await login(reviewer, username, password);
  return { id, userId, username, adminId: options.currentUserId, reviewer, context, errors };
}

async function open(page, id, section, subsection) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  if (section) await tab(page, section);
  if (subsection) await tab(page, subsection);
}

async function tab(page, name) {
  await drawer(page).getByRole('tab', { name, exact: true }).click();
  await page.waitForLoadState('networkidle');
}

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
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

async function task(page, code, name, days = 1) {
  await drawer(page).getByRole('button', { name: '新建WBS任务', exact: true }).click();
  const form = modal(page, '新建WBS任务');
  await fill(form, { wbs_code: code, name, duration_days: days, start_date: '2026-09-22' });
  await choose(page, form, 'owner_id', /\/ admin$/);
  return (await save(page, '新建WBS任务')).result;
}

async function screenshot(page, name) {
  await page.evaluate(() => window.scrollTo(0, 0));
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await page.screenshot({ path: path.resolve(__dirname, '../../..', name), fullPage: false, animations: 'disabled' });
}

async function approve(page, id, section, subsection, text, title) {
  await open(page, id, section, subsection);
  await row(page, text).getByRole('button', { name: '批准', exact: true }).click();
  const form = modal(page, title);
  const input = form.locator('textarea');
  if (await input.count()) await input.fill('独立审核已核对真实记录和证据,同意批准');
  await save(page, title);
}

async function executionPrerequisites(page, f) {
  // 通过公开API构建已批准前置条件,界面用例仍验证实际执行和关闭命令.
  const command = (suffix, data, actor = page) => mutate(actor, f.id, suffix, data).then(r => r.result);
  const tasks = [];
  for (const [code, name] of [['1', '交付执行任务'], ['2', '调试验证任务']]) tasks.push(await command('/tasks', {
    wbs_code: code, name, owner_id: f.adminId, task_type: 'task', duration_days: 1, start_date: '2026-09-22' }));
  const evidence = await command('/governance/documents', { code: 'FLOW-EV', title: '闭环验证证据', filename: 'workflow.txt', content: '合成工程验收数据: 实测,签收和审核记录用于本地功能验证.' });
  const requirement = await command('/governance/requirements', { code: 'FLOW-URS', text: '工程链路完成实际验证与独立签收', category: '交付', priority: 'required', owner_id: f.adminId });
  const charter = await command('/governance/charters', { title: '闭环验收章程', objective: '完成受控交付', scope: '合成验收系统', success_criteria: '证据闭环', sponsor_id: f.adminId });
  await command(`/governance/charters/${charter.id}/submit`, { reviewer_id: f.userId });
  await command(`/governance/charters/${charter.id}/decision`, { decision: 'approved', reason: '独立确认前置章程' }, f.reviewer);
  for (const stage of ['execution', 'closure']) {
    const template = await command('/governance/gate-templates', { code: `FLOW-${stage}`, title: `${stage}工程评审`, stage, required: true, checks: [{ code: 'C1', title: '实际证据齐全', required: true }] });
    const gate = await command('/governance/gates', { template_id: template.id, title: `${stage}准入检查`, reviewer_id: f.userId });
    await command(`/governance/gates/${gate.id}/checks`, { checks: [{ code: 'C1', passed: true, evidence_ids: [evidence.id] }] });
    await command(`/governance/gates/${gate.id}/submit`, {});
    await command(`/governance/gates/${gate.id}/decision`, { decision: 'approved', reason: '独立核验指定版本证据' }, f.reviewer);
  }
  const baseline = await command('/planning/submit', { comment: '执行前置已批准计划' });
  await command(`/planning/baselines/${baseline.baseline_id}/review`, { decision: 'approved', comment: '独立批准' }, f.reviewer);
  await mutate(page, f.id, '/transition', { status: 'execution', reason: '前置章程,计划和Gate均已独立批准' });
  return { ...f, tasks, evidence, requirement, command };
}

async function deliveryApproval(page, f, collection, item, action = 'submit') {
  await mutate(page, f.id, `/delivery/${collection}/${item.id}/${action}`, {
    evidence_ids: [f.evidence.id], ...(collection === 'shipments' ? {} : { reviewer_id: f.userId }) });
  return (await mutate(f.reviewer, f.id, `/delivery/${collection}/${item.id}/decision`, { decision: 'approved', reason: '独立核查交付链及真实证据' })).result;
}

async function completeDelivery(page, f) {
  const refs = { task_id: f.tasks[0].task_id, requirement_ids: [f.requirement.id] };
  await open(page, f.id, '工程交付', '备料与BOM');
  await drawer(page).getByRole('button', { name: '新建备料申请', exact: true }).click();
  const materialForm = modal(page, '新建备料申请');
  await fill(materialForm, { code: 'FLOW-MR', title: '闭环备料申请', needed_on: '2026-09-22', items_0_code: 'PART-A', items_0_name: '验收部件A', items_0_quantity: 2 });
  await choose(page, materialForm, 'owner_id', /\/ admin$/);
  await choose(page, materialForm, 'task_id', '交付执行任务');
  await choose(page, materialForm, 'requirement_ids', 'FLOW-URS');
  await materialForm.getByRole('button', { name: '添加明细行', exact: true }).click();
  await fill(materialForm, { items_1_code: 'PART-B', items_1_name: '验收部件B', items_1_quantity: 4 });
  const material = (await save(page, '新建备料申请')).result;
  expect(material.items.map(item => item.quantity)).toEqual([2, 4]);
  await deliveryApproval(page, f, 'material-requests', material);
  const bom = await f.command('/delivery/boms', { code: 'FLOW-BOM', title: '冻结工程配置', material_request_id: material.id });
  await deliveryApproval(page, f, 'boms', bom, 'freeze');
  await f.command(`/delivery/boms/${bom.id}/kit`, { items: [{ code: 'PART-A', available_quantity: 2 }, { code: 'PART-B', available_quantity: 4 }], evidence_ids: [f.evidence.id] });
  const assembly = await f.command('/delivery/assemblies', { ...refs, code: 'FLOW-AS', title: '交付装配批次', bom_id: bom.id, owner_id: f.adminId });
  await f.command(`/delivery/assemblies/${assembly.id}/start`, { evidence_ids: [f.evidence.id] });
  await deliveryApproval(page, f, 'assemblies', assembly);
  for (const type of ['SIT', 'FAT']) await deliveryTest(page, f, assembly, refs, type);
  const shipment = await f.command('/delivery/shipments', { ...refs, code: 'FLOW-SHIP', title: '工程实际交付', assembly_ids: [assembly.id], consignee: '合成验收团队', delivery_address: '本地测试交付地址', planned_date: '2026-09-22', reviewer_id: f.userId });
  await deliveryApproval(page, f, 'shipments', shipment);
  await f.command(`/delivery/shipments/${shipment.id}/dispatch`, { shipped_on: '2026-09-22', tracking_no: 'FLOW-LOCAL-001', evidence_ids: [f.evidence.id] });
  await f.command(`/delivery/shipments/${shipment.id}/receipt`, { received_on: '2026-09-22', receiver_name: '合成验收接收人', acceptance: 'accepted', evidence_ids: [f.evidence.id] }, f.reviewer);
  await deliveryTest(page, f, assembly, refs, 'SAT');
  expect((await api(page, 'GET', base(f.id) + '/delivery')).blockers).toEqual([]);
}

async function deliveryTest(page, f, assembly, refs, type) {
  await open(page, f.id, '工程交付', '质量试验');
  await drawer(page).getByRole('button', { name: '建立质量试验', exact: true }).click();
  const form = modal(page, '建立质量试验');
  await fill(form, { code: `FLOW-${type}`, title: `${type}闭环试验`, criteria_0_title: '全部关键功能符合验收要求' });
  await choose(page, form, 'test_type', type);
  await choose(page, form, 'assembly_id', assembly.title);
  await choose(page, form, 'owner_id', /\/ admin$/);
  await choose(page, form, 'task_id', '交付执行任务');
  await choose(page, form, 'requirement_ids', 'FLOW-URS');
  const record = (await save(page, '建立质量试验')).result;
  expect(record.criteria[0].required).toBe(true);
  await f.command(`/delivery/tests/${record.id}/results`, { checks: [{ code: 'C01', passed: true, actual: '合成验收实测满足预设要求', evidence_ids: [f.evidence.id] }], due_date: '2026-09-30' });
  await deliveryApproval(page, f, 'tests', record);
}

async function transitionUi(page, id, button, title) {
  await open(page, id);
  await drawer(page).getByRole('button', { name: new RegExp(`${button}$`) }).click();
  const response = page.waitForResponse(r => r.url().endsWith(`/projects/${id}/transition`) && r.request().method() === 'POST');
  await modal(page, title).getByRole('button', { name: title, exact: true }).click();
  const result = await (await response).json();
  expect(result.code, result.msg).toBe(200);
  await expect(modal(page, title)).toBeHidden();
}

test.describe('PMS 工作台真实浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);
  test.beforeEach(async ({ page }) => {
    page.pmsErrors = [];
    page.on('pageerror', error => page.pmsErrors.push(error.message));
    await login(page);
  });
  test.afterEach(async ({ page }) => expect(page.pmsErrors, '页面没有未捕获异常').toEqual([]));

  test('WBS,工作日历和CPM依赖,计划冻结,独立审批与不可变基线差异', async ({ page, browser }) => {
    const f = await fixture(page, browser, '计划与资源');
    try {
      await open(page, f.id, '计划与执行');
      const a = await task(page, '1.1', '设计交付任务', 2);
      const b = await task(page, '1.2', '接口验收任务', 1);
      await drawer(page).getByRole('button', { name: '添加任务依赖', exact: true }).click();
      await choose(page, modal(page, '添加任务依赖'), 'predecessor_id', '设计交付任务');
      await choose(page, modal(page, '添加任务依赖'), 'successor_id', '接口验收任务');
      await save(page, '添加任务依赖');
      await tab(page, '资源与日历');
      await drawer(page).getByRole('button', { name: '编辑工作日历', exact: true }).click();
      await fill(modal(page, '编辑项目工作日历'), { holidays: '2026-09-23' });
      await save(page, '编辑项目工作日历');
      await drawer(page).getByRole('button', { name: '新增项目资源', exact: true }).click();
      const resourceForm = modal(page, '新增项目资源');
      await fill(resourceForm, { name: '验收工作站', daily_capacity: 8 });
      await choose(page, resourceForm, 'resource_type', '设备');
      await save(page, '新增项目资源');
      await drawer(page).getByRole('button', { name: '分配任务资源', exact: true }).click();
      const allocation = modal(page, '分配任务资源');
      await choose(page, allocation, 'task_id', '设计交付任务');
      await choose(page, allocation, 'resource_id', '验收工作站');
      await fill(allocation, { hours_per_day: 4 });
      await save(page, '分配任务资源');
      let plan = await api(page, 'GET', base(f.id) + '/planning');
      expect(plan.schedule.tasks.find(t => t.task_id === a.task_id).end_date).toBe('2026-09-24');
      expect(plan.schedule.tasks.find(t => t.task_id === b.task_id).start_date).toBe('2026-09-25');
      expect(plan.schedule.critical_path).toEqual(expect.arrayContaining([a.task_id, b.task_id]));
      expect(plan.overallocations).toEqual([]);
      await tab(page, '审批与基线');
      await drawer(page).getByRole('button', { name: '提交计划审批', exact: true }).click();
      await fill(modal(page, '提交计划审批'), { comment: '设计和接口验收计划提交独立评审' });
      const submitted = (await save(page, '提交计划审批')).result;
      await expect(drawer(page).getByText('计划已冻结,等待独立审批.')).toBeVisible();
      await expect(drawer(page).getByRole('button', { name: '批准', exact: true })).toHaveCount(0);
      await tab(page, 'WBS与排程');
      await expect(drawer(page).getByRole('button', { name: '新建WBS任务', exact: true })).toHaveCount(0);
      await approve(f.reviewer, f.id, '计划与执行', '审批与基线', '待审批', '批准计划基线');
      await tab(f.reviewer, 'WBS与排程');
      await expect(drawer(f.reviewer).getByRole('button', { name: '新建WBS任务', exact: true })).toHaveCount(0);
      await open(page, f.id, '计划与执行');
      await row(page, '设计交付任务').getByRole('button', { name: '编辑', exact: true }).click();
      await fill(modal(page, '编辑WBS任务'), { duration_days: 3 });
      await save(page, '编辑WBS任务');
      const snapshot = await api(page, 'GET', base(f.id) + `/planning/baselines/${submitted.baseline_id}`);
      const diff = await api(page, 'GET', base(f.id) + `/planning/baselines/${submitted.baseline_id}/diff`);
      expect(snapshot.snapshot.tasks.find(t => t.task_id === a.task_id).duration_days).toBe(2);
      expect(diff.changed).toBe(true);
      expect(diff.changes.some(change => change.entity_id === a.task_id)).toBe(true);
      await screenshot(page, 'pms-planning-preview.png');
      await tab(page, '审批与基线');
      await drawer(page).getByRole('button', { name: '查看差异', exact: true }).click();
      await expect(page.getByRole('dialog').last()).toContainText('设计交付任务');
      expect(f.errors).toEqual([]);
    } finally { await f.context.close(); }
  });

  test('章程独立审批,URS版本追踪,真实证据下载,Gate与会议行动转WBS', async ({ page, browser }) => {
    const f = await fixture(page, browser, '需求与治理');
    try {
      await open(page, f.id, '需求与治理');
      await drawer(page).getByRole('button', { name: '编制项目章程', exact: true }).click();
      await fill(modal(page, '编制项目章程'), { title: '验收项目章程', objective: '交付可验证的工程系统', scope: '设计,联调和验收', success_criteria: '全部关键需求有测试证据' });
      await choose(page, modal(page, '编制项目章程'), 'sponsor_id', /\/ admin$/);
      await save(page, '编制项目章程');
      await row(page, '验收项目章程').getByRole('button', { name: '提交审批', exact: true }).click();
      await choose(page, modal(page, '提交独立审批'), 'reviewer_id', f.username);
      await save(page, '提交独立审批');
      await approve(f.reviewer, f.id, '需求与治理', '章程', '验收项目章程', '批准评审');
      await open(page, f.id, '需求与治理', '证据版本');
      await drawer(page).getByRole('button', { name: '登记证据文档', exact: true }).click();
      const content = '\n工程验收证据: 实测节拍满足要求.\n';
      await fill(modal(page, '登记证据文档'), { code: 'EV-01', title: '工程测试记录', filename: 'acceptance-evidence.txt', content });
      const evidence = (await save(page, '登记证据文档')).result;
      await row(page, 'EV-01').getByRole('button', { name: '查看内容', exact: true }).click();
      const preview = modal(page, '工程测试记录 / V1');
      await expect(preview.locator('pre')).toHaveText(content);
      const downloadEvent = page.waitForEvent('download');
      await preview.getByRole('button', { name: '下载此版本', exact: true }).click();
      const download = await downloadEvent;
      expect(download.suggestedFilename()).toBe('acceptance-evidence.txt');
      expect(await fs.readFile(await download.path(), 'utf8')).toBe(content);
      await preview.getByRole('button', { name: 'Close', exact: true }).click();
      await tab(page, 'URS与追踪');
      await drawer(page).getByRole('button', { name: '新增URS需求', exact: true }).click();
      await fill(modal(page, '新增URS需求'), { code: 'URS-01', text: '工站节拍不超过60秒' });
      await choose(page, modal(page, '新增URS需求'), 'owner_id', /\/ admin$/);
      await save(page, '新增URS需求');
      await row(page, 'URS-01').getByRole('button', { name: '新修订', exact: true }).click();
      await fill(modal(page, '修订URS需求'), { text: '工站节拍不超过55秒,连续运行8小时' });
      await save(page, '修订URS需求');
      await drawer(page).getByRole('button', { name: '建立需求追踪', exact: true }).click();
      await choose(page, modal(page, '建立需求追踪'), 'requirement_id', 'URS-01 / V2');
      await choose(page, modal(page, '建立需求追踪'), 'target', '证据 / 工程测试记录 V1');
      await save(page, '建立需求追踪');
      const governance = await api(page, 'GET', base(f.id) + '/governance');
      expect(governance.requirements.filter(item => item.code === 'URS-01')).toHaveLength(2);
      expect(governance.traces[0].target_id).toBe(evidence.id);
      await tab(page, 'Gate评审');
      await drawer(page).getByRole('button', { name: '建立Gate模板', exact: true }).click();
      await fill(modal(page, '建立Gate模板'), { code: 'G-EXEC', title: '执行准入评审', check_titles: '测试证据完整' });
      await save(page, '建立Gate模板');
      await drawer(page).getByRole('button', { name: '发起Gate检查', exact: true }).click();
      await fill(modal(page, '发起Gate检查'), { title: '执行准入检查01' });
      await choose(page, modal(page, '发起Gate检查'), 'template_id', '执行准入评审');
      await choose(page, modal(page, '发起Gate检查'), 'reviewer_id', f.username);
      await save(page, '发起Gate检查');
      await row(page, '执行准入检查01').getByRole('button', { name: '填写检查', exact: true }).click();
      await choose(page, modal(page, '填写Gate检查结果'), 'passed_C1', '检查通过');
      await choose(page, modal(page, '填写Gate检查结果'), 'evidence_C1', 'EV-01');
      await save(page, '填写Gate检查结果');
      await row(page, '执行准入检查01').getByRole('button', { name: '提交评审', exact: true }).click();
      await save(page, '提交Gate评审');
      await open(f.reviewer, f.id, '需求与治理', 'Gate评审');
      await row(f.reviewer, '执行准入检查01').getByRole('button', { name: '通过', exact: true }).click();
      await fill(modal(f.reviewer, '批准Gate'), { reason: '已独立核查文档版本和检查结果' });
      await save(f.reviewer, '批准Gate');
      await open(page, f.id, '需求与治理', '会议行动');
      await drawer(page).getByRole('button', { name: '登记项目会议', exact: true }).click();
      await fill(modal(page, '登记项目会议'), { title: '工程推进会', held_on: '2026-09-22', minutes: '完成接口方案复核并输出交付记录' });
      await choose(page, modal(page, '登记项目会议'), 'attendee_ids', /\/ admin$/);
      await save(page, '登记项目会议');
      await row(page, '工程推进会').getByRole('button', { name: '形成行动', exact: true }).click();
      await fill(modal(page, '新增会议行动'), { title: '复核接口方案', due_date: '2026-09-30' });
      await choose(page, modal(page, '新增会议行动'), 'owner_id', /\/ admin$/);
      await save(page, '新增会议行动');
      await row(page, '复核接口方案').getByRole('button', { name: '转为WBS任务', exact: true }).click();
      await fill(modal(page, '会议行动转WBS任务'), { start_date: '2026-09-24', duration_days: 2, wbs_code: 'ACT-01' });
      await save(page, '会议行动转WBS任务');
      expect((await api(page, 'GET', base(f.id) + '/planning')).tasks.some(t => t.name === '复核接口方案')).toBe(true);
      await open(page, f.id, '需求与治理', 'Gate评审');
      await expect(row(page, '执行准入检查01')).toContainText('已批准');
      await screenshot(page, 'pms-governance-preview.png');
      expect(f.errors).toEqual([]);
    } finally { await f.context.close(); }
  });

  test('精确费用版本,删除草稿条目,独立审批与修订保留,结项证据和阻断条件', async ({ page, browser }) => {
    const f = await fixture(page, browser, '费用与收尾');
    try {
      await open(page, f.id, '项目费用');
      await drawer(page).getByRole('button', { name: '新建成本版本', exact: true }).click();
      await fill(modal(page, '新建成本版本'), { name: '预算验收版本', period: '2026-09', revenue: '1.00' });
      await choose(page, modal(page, '新建成本版本'), 'reviewer_id', f.username);
      const cost = (await save(page, '新建成本版本')).result;
      for (const [label, amount] of [['材料验收项', '0.10'], ['制造验收项', '0.20'], ['待移除项', '0.01']]) {
        await row(page, '预算验收版本').getByRole('button', { name: '添加条目', exact: true }).click();
        await fill(modal(page, '添加成本条目'), { label, amount, source_ref: `E2E-${label}` });
        await save(page, '添加成本条目');
      }
      await row(page, '预算验收版本').getByRole('button', { name: '查看明细', exact: true }).click();
      await row(page, '待移除项').getByRole('button', { name: '删除条目', exact: true }).click();
      await save(page, '删除成本条目');
      await expect(row(page, '预算验收版本')).toContainText('0.30');
      await row(page, '预算验收版本').getByRole('button', { name: '提交审批', exact: true }).click();
      await save(page, '提交成本版本审批');
      await expect(drawer(page).getByRole('button', { name: '批准', exact: true })).toHaveCount(0);
      await approve(f.reviewer, f.id, '项目费用', '成本与分摊', '预算验收版本', '批准成本版本');
      await open(page, f.id, '项目费用');
      await row(page, '预算验收版本').getByRole('button', { name: '新修订', exact: true }).click();
      await choose(page, modal(page, '修订成本版本'), 'reviewer_id', f.username);
      const revised = (await save(page, '修订成本版本')).result;
      const finance = await api(page, 'GET', base(f.id) + '/finance');
      expect(finance.cost_versions.find(v => v.id === cost.id).status).toBe('approved');
      expect(finance.cost_versions.find(v => v.id === revised.id).total).toBe('0.30');
      expect(finance.summary.budget.amount).toBe('0.30');
      await screenshot(page, 'pms-finance-preview.png');
      const evidence = (await mutate(page, f.id, '/governance/documents', { code: 'CLOSE-EV', title: '交付签收单', filename: 'handoff.txt', content: '合成验收记录: 文档已签收' })).result;
      await open(page, f.id, '结项与移交');
      await expect(drawer(page).getByText('结项准入检查', { exact: true })).toBeVisible();
      await expect(drawer(page).getByRole('button', { name: '提交关闭审批', exact: true })).toHaveCount(0);
      await drawer(page).getByRole('button', { name: '添加检查项', exact: true }).click();
      await fill(modal(page, '添加收尾检查项'), { title: '确认签收凭证' });
      await save(page, '添加收尾检查项');
      await row(page, '确认签收凭证').getByRole('button', { name: '确认完成', exact: true }).click();
      await choose(page, modal(page, '完成收尾检查'), 'evidence_ref', 'CLOSE-EV');
      await fill(modal(page, '完成收尾检查'), { comment: '核对不可变文档版本与签收内容' });
      await save(page, '完成收尾检查');
      await drawer(page).getByRole('button', { name: '登记项目经验', exact: true }).click();
      await fill(modal(page, '登记项目经验'), { title: '独立审批防止自批', category: '流程', content: '审批角色与执行角色分离,核对版本后再决策' });
      await save(page, '登记项目经验');
      const closure = await api(page, 'GET', base(f.id) + '/closure');
      expect(closure.checks[0].evidence_ref).toBe(evidence.id);
      expect(closure.checks[0].status).toBe('completed');
      expect(closure.blockers.length).toBeGreaterThan(0);
      await page.reload();
      await tab(page, '结项与移交');
      await expect(row(page, '确认签收凭证')).toContainText('已完成');
      await expect(row(page, '独立审批防止自批')).toBeVisible();
      expect(f.errors).toEqual([]);
    } finally { await f.context.close(); }
  });
  test('完整交付,实际工时审批分摊,接口去重,正式关闭和独立受控重开', async ({ page, browser }) => {
    test.setTimeout(360000);
    const seed = await fixture(page, browser, '完整交付闭环');
    try {
      const f = await executionPrerequisites(page, seed);
      await completeDelivery(page, f);
      await open(page, f.id, '工程交付', '发运签收');
      await expect(row(page, '工程实际交付')).toContainText('已完整接受');
      await screenshot(page, 'pms-delivery-preview.png');
      for (const [taskRecord, hours] of [[f.tasks[0], '0.25'], [f.tasks[1], '0.75']]) {
        await open(page, f.id, '实际工时');
        await drawer(page).getByRole('button', { name: '提交实际工时', exact: true }).click();
        await fill(modal(page, '提交实际工时'), { work_date: '2026-09-22', hours, note: `完成${taskRecord.name}实际记录` });
        await choose(page, modal(page, '提交实际工时'), 'task_id', taskRecord.name);
        await choose(page, modal(page, '提交实际工时'), 'reviewer_id', f.username);
        await save(page, '提交实际工时');
        await approve(f.reviewer, f.id, '实际工时', null, taskRecord.name, '批准工时单');
      }
      await open(page, f.id, '项目费用');
      await drawer(page).getByRole('button', { name: '新建成本版本', exact: true }).click();
      await fill(modal(page, '新建成本版本'), { name: '最终交付决算', period: '2026-09', revenue: '1.00' });
      await choose(page, modal(page, '新建成本版本'), 'kind', '决算');
      await choose(page, modal(page, '新建成本版本'), 'reviewer_id', f.username);
      await save(page, '新建成本版本');
      await row(page, '最终交付决算').getByRole('button', { name: '工时分摊', exact: true }).click();
      await fill(modal(page, '按批准工时分摊'), { label: '交付人工分摊', amount: '0.30', from_date: '2026-09-22', to_date: '2026-09-22', idempotency_key: 'FLOW-COST-BATCH' });
      await save(page, '按批准工时分摊');
      const money = await api(page, 'GET', base(f.id) + '/finance');
      expect(money.allocations[0].rows.map(item => Number(item.amount)).reduce((a, b) => a + b, 0)).toBeCloseTo(0.30, 8);
      expect(money.cost_versions[0].total).toBe('0.30');
      expect(money.allocations[0].inputs.times).toHaveLength(2);
      await row(page, '最终交付决算').getByRole('button', { name: '提交审批', exact: true }).click();
      await save(page, '提交成本版本审批');
      await approve(f.reviewer, f.id, '项目费用', '成本与分摊', '最终交付决算', '批准成本版本');
      await open(page, f.id, '计划与执行');
      for (const taskRecord of f.tasks) {
        await row(page, taskRecord.name).getByRole('button', { name: '反馈进度', exact: true }).click();
        await choose(page, modal(page, '反馈任务进度'), 'status', '已完成');
        await fill(modal(page, '反馈任务进度'), { percent_complete: 100, remaining_days: 0, comment: '交付和调试已完成,实际证据已归档' });
        await save(page, '反馈任务进度');
      }
      await open(page, f.id, '接口运维');
      await expect(drawer(page).getByText('未配置', { exact: true })).toHaveCount(8);
      await drawer(page).getByRole('button', { name: '建立外发消息', exact: true }).click();
      await choose(page, modal(page, '建立外发消息'), 'target', 'ERP / SAP');
      await choose(page, modal(page, '建立外发消息'), 'source', '文档 / FLOW-EV');
      await fill(modal(page, '建立外发消息'), { idempotency_key: 'FLOW-DOC-BATCH' });
      const queued = (await save(page, '建立外发消息')).result;
      await expect(row(page, 'FLOW-DOC-BATCH')).toContainText('目标未配置');
      await expect(drawer(page).getByRole('button', { name: '发送消息', exact: true })).toHaveCount(0);
      const current = await api(page, 'GET', base(f.id));
      await api(page, 'POST', base(f.id) + `/integration/outbox/${queued.id}/deliver`, { version: current.version }, 503);
      const source = { source: 'erp', event_id: 'FLOW-SRC-V2', entity_type: 'order', external_key: 'FLOW-ORDER', source_revision: 2, data: { order_no: 'FLOW-ORDER', status: 'confirmed' } };
      const received = (await mutate(page, f.id, '/integration/inbox', source)).result;
      const duplicate = (await mutate(page, f.id, '/integration/inbox', source)).result;
      expect(duplicate.id).toBe(received.id);
      await mutate(page, f.id, '/integration/inbox', { ...source, event_id: 'FLOW-SRC-V1', source_revision: 1, data: { order_no: 'FLOW-ORDER', status: 'draft' } });
      const integration = await api(page, 'GET', base(f.id) + '/integration');
      expect(integration.facts[0].source_revision).toBe(2);
      expect(integration.reconciliation).toMatchObject({ received: 2, applied: 1, ignored: 1, queued: 1, delivered: 0, unconfirmed: 1 });
      await open(page, f.id, '接口运维', '来源收件与事实');
      await expect(row(page, 'FLOW-SRC-V1')).toContainText('历史版本');
      await screenshot(page, 'pms-integration-preview.png');
      await transitionUi(page, f.id, '进入收尾', '进入收尾阶段');
      await tab(page, '结项与移交');
      await drawer(page).getByRole('button', { name: '添加检查项', exact: true }).click();
      await fill(modal(page, '添加收尾检查项'), { title: '最终验收资料完整' });
      await save(page, '添加收尾检查项');
      await row(page, '最终验收资料完整').getByRole('button', { name: '确认完成', exact: true }).click();
      await choose(page, modal(page, '完成收尾检查'), 'evidence_ref', 'FLOW-EV');
      await fill(modal(page, '完成收尾检查'), { comment: '核对客户签收,质量证据和决算' });
      await save(page, '完成收尾检查');
      await expect(drawer(page).getByText('当前结项前置检查已通过.', { exact: true })).toBeVisible();
      await drawer(page).getByRole('button', { name: '提交关闭审批', exact: true }).click();
      await choose(page, modal(page, '提交项目关闭审批'), 'reviewer_id', f.username);
      await save(page, '提交项目关闭审批');
      await open(f.reviewer, f.id, '结项与移交');
      await drawer(f.reviewer).getByRole('button', { name: '批准关闭', exact: true }).click();
      await fill(modal(f.reviewer, '批准项目关闭'), { reason: '全部交付,质量,工时,决算和清单已独立核对' });
      await save(f.reviewer, '批准项目关闭');
      await transitionUi(page, f.id, '正式关闭', '正式关闭项目');
      await expect(drawer(page).getByText('项目已结束,当前为只读视图', { exact: true })).toBeVisible();
      await tab(page, '结项与移交');
      await drawer(page).getByRole('button', { name: '申请受控重开', exact: true }).click();
      await fill(modal(page, '申请受控重开'), { reason: '补充验收记录说明', scope: '仅补充复盘内容,保留原始归档' });
      await choose(page, modal(page, '申请受控重开'), 'reviewer_id', f.username);
      await save(page, '申请受控重开');
      await expect(drawer(page).getByRole('button', { name: '批准重开', exact: true })).toHaveCount(0);
      await open(f.reviewer, f.id, '结项与移交');
      await drawer(f.reviewer).getByRole('button', { name: '批准重开', exact: true }).click();
      await fill(modal(f.reviewer, '批准项目重开'), { reason: '批准受限范围修正,重新走关闭审批' });
      await save(f.reviewer, '批准项目重开');
      expect((await api(page, 'GET', base(f.id))).status).toBe('closing');
      const reopened = await api(page, 'GET', base(f.id));
      await api(page, 'POST', base(f.id) + '/transition', { status: 'closed', version: reopened.version }, 409);
      expect(f.errors).toEqual([]);
    } finally { await seed.context.close(); }
  });

  test('风险证据复评独立关闭,问题关闭后凭新证据独立重开', async ({ page, browser }) => {
    const f = await fixture(page, browser, '风险复评和问题重开');
    try {
      await mutate(page, f.id, '/governance/documents', { code: 'REVIEW-EV', title: '风险和问题验证记录', filename: 'review.txt', content: '合成验证记录: 新故障迹象及复测结论' });
      await open(page, f.id, '需求与治理', '风险与问题');
      await drawer(page).getByRole('button', { name: '登记项目风险', exact: true }).click();
      await fill(modal(page, '登记项目风险'), { title: '验收资源延误风险', mitigation: '已完成实际备份资源验证', due_date: '2026-10-01' });
      await choose(page, modal(page, '登记项目风险'), 'owner_id', /\/ admin$/);
      await save(page, '登记项目风险');
      await row(page, '验收资源延误风险').getByRole('button', { name: '提交复评', exact: true }).click();
      await choose(page, modal(page, '提交风险复评'), 'outcome', '关闭风险');
      await fill(modal(page, '提交风险复评'), { review_note: '风险条件已消失且替代资源实际验证完成' });
      await choose(page, modal(page, '提交风险复评'), 'evidence_ids', 'REVIEW-EV');
      await choose(page, modal(page, '提交风险复评'), 'reviewer_id', f.username);
      await save(page, '提交风险复评');
      await expect(row(page, '验收资源延误风险').getByRole('button', { name: '批准', exact: true })).toHaveCount(0);
      await approve(f.reviewer, f.id, '需求与治理', '风险与问题', '验收资源延误风险', '批准评审');
      await open(page, f.id, '需求与治理', '风险与问题');
      await expect(row(page, '验收资源延误风险')).toContainText('已关闭');
      await drawer(page).getByRole('button', { name: '登记项目问题', exact: true }).click();
      await fill(modal(page, '登记项目问题'), { title: '重复出现的接口异常', due_date: '2026-10-02' });
      await choose(page, modal(page, '登记项目问题'), 'owner_id', /\/ admin$/);
      const issue = (await save(page, '登记项目问题')).result;
      await row(page, '重复出现的接口异常').getByRole('button', { name: '提交解决证据', exact: true }).click();
      await fill(modal(page, '提交问题解决验证'), { resolution: '修复接口并完成独立复测' });
      await choose(page, modal(page, '提交问题解决验证'), 'evidence_ids', 'REVIEW-EV');
      await choose(page, modal(page, '提交问题解决验证'), 'reviewer_id', f.username);
      await save(page, '提交问题解决验证');
      await approve(f.reviewer, f.id, '需求与治理', '风险与问题', '重复出现的接口异常', '批准评审');
      await open(page, f.id, '需求与治理', '风险与问题');
      await row(page, '重复出现的接口异常').getByRole('button', { name: '申请重开', exact: true }).click();
      await fill(modal(page, '申请问题重开'), { reason: '新运行条件下再次出现同一异常,需要继续调查' });
      await choose(page, modal(page, '申请问题重开'), 'evidence_ids', 'REVIEW-EV');
      await choose(page, modal(page, '申请问题重开'), 'reviewer_id', f.username);
      await save(page, '申请问题重开');
      await approve(f.reviewer, f.id, '需求与治理', '风险与问题', '重复出现的接口异常', '批准问题重开');
      const state = (await api(page, 'GET', base(f.id) + '/governance')).issues.find(item => item.id === issue.id);
      expect(state.status).toBe('open');
      expect(state.reopen_reason).toContain('再次出现');
      expect(state.workflow_history.length).toBeGreaterThanOrEqual(2);
      await open(page, f.id, '需求与治理', '风险与问题');
      await expect(row(page, '重复出现的接口异常').getByRole('button', { name: '提交解决证据', exact: true })).toBeVisible();
      expect(f.errors).toEqual([]);
    } finally { await f.context.close(); }
  });

});
