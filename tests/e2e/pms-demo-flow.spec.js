const { test, expect } = require('@playwright/test');
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');

// 完整展示流程 (演示剧本): 用同一个设备订单项目, 从平台模板发布走到正式关闭, 按顺序真实截图到 reports/demo-flow/.
// 覆盖: 模板与规则 -> 项目中心建项与应用模板 -> 团队/任命书 -> 章程/URS/证据/干系人/DQ/启动会 -> 派生计划/冲突/权重/基线/需求确认Gate ->
// 进入执行 -> 反馈实际日期/挣值/快照/系统提醒 -> 交付配置/备料/BOM/齐套Gate/装配步骤/交接Gate例外/SIT/FAT/发货前条件/放行/发运/签收 ->
// 交底/现场任务/工勘/SAT -> 工时/四算 -> 过程看板/组合看板/经营目标/全局检索/驾驶舱 -> 风险 -> 收尾Gate/清单/移交/经验/关闭审批 -> 正式关闭.
// 两个账号: admin (项目经理, 建项与提交) 与本次合成的独立审核人 (审批). 外部系统不接入, 接口运维显示未配置.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/demo-flow');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const today = () => new Date().toISOString().slice(0, 10);
const daysAgo = n => { const d = new Date(); d.setDate(d.getDate() - n); return d.toISOString().slice(0, 10); };

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

async function mutate(page, id, suffix, data = {}, expected = 200, method = 'POST') {
  const project = await api(page, 'GET', base(id));
  return api(page, method, base(id) + suffix, { ...data, version: project.version }, expected);
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

async function choose(page, form, key, text) {
  const input = form.locator(`[id="${key}"]`);
  await input.click();
  // 长列表 (如 27 个任务) 被 antd 虚拟滚动裁剪, 先按标签过滤再选.
  if (typeof text === 'string') await input.fill(text);
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await input.press('Escape');
}

async function fill(form, values) {
  for (const [key, value] of Object.entries(values)) await form.locator(`[id="${key}"]`).fill(String(value));
}

async function save(page, title, expected = 200, button = /^保\s*存$/) {
  const form = modal(page, title);
  const response = page.waitForResponse(r => r.url().includes('/api/pms/') && ['POST', 'PUT', 'DELETE'].includes(r.request().method()));
  await form.getByRole('button', { name: button }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(expected);
  if (expected === 200) { await expect(form).toBeHidden(); await page.waitForLoadState('networkidle'); }
  return body;
}

async function shot(page, file, anchor) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  if (anchor) await anchor.scrollIntoViewIfNeeded(); else await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

function makePdf(text) {
  const objects = [];
  objects.push('<< /Type /Catalog /Pages 2 0 R >>');
  objects.push('<< /Type /Pages /Kids [3 0 R] /Count 1 >>');
  objects.push('<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>');
  const stream = `BT /F1 18 Tf 24 120 Td (${text}) Tj ET`;
  objects.push(`<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`);
  objects.push('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>');
  let out = '%PDF-1.4\n';
  const offsets = [];
  objects.forEach((obj, i) => { offsets.push(out.length); out += `${i + 1} 0 obj\n${obj}\nendobj\n`; });
  const xref = out.length;
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const o of offsets) out += `${String(o).padStart(10, '0')} 00000 n \n`;
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(out, 'latin1');
}

// 已发布的设备模板必须带派生层级; 旧版无 levels 则按目录建修订并发布.
async function equipmentTemplate(page) {
  const listing = await api(page, 'GET', '/api/pms/config/project-template');
  const catalog = listing.catalog.find(item => item.code === 'TPL-EQUIPMENT');
  const rows = listing.rows.filter(r => r.code === 'TPL-EQUIPMENT');
  const published = rows.find(r => r.status === 'published');
  const withLevels = t => t && t.stages.every(s => Array.isArray(s.levels) && s.levels.length > 0);
  if (withLevels(published)) return published;
  const draft = rows.find(r => r.status === 'draft');
  if (draft) {
    await api(page, 'POST', `/api/pms/config/project-template/${draft.id}/publish`, { reason: '演示发布' });
  } else if (rows.length === 0) {
    await api(page, 'POST', '/api/pms/config/project-template/import', { code: 'TPL-EQUIPMENT' });
    const imported = (await api(page, 'GET', '/api/pms/config/project-template')).rows.find(r => r.code === 'TPL-EQUIPMENT' && r.status === 'draft');
    await api(page, 'POST', `/api/pms/config/project-template/${imported.id}/publish`, { reason: '演示发布' });
  } else {
    const latest = rows.reduce((a, b) => (a.revision > b.revision ? a : b));
    const revised = await api(page, 'POST', `/api/pms/config/project-template/${latest.id}/revisions`, { ...catalog, reason: '恢复目录阶段定义' });
    await api(page, 'POST', `/api/pms/config/project-template/${revised.id}/publish`, { reason: '演示发布' });
  }
  return (await api(page, 'GET', '/api/pms/config/project-template')).rows.find(r => r.code === 'TPL-EQUIPMENT' && r.status === 'published');
}

async function reviewerAccount(page, browser) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve', 'pms:time:approve',
    'pms:finance:query', 'pms:finance:approve', 'pms:project:close', 'pms:project:reopen', 'pms:document:confidential']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `演示审核${suffix}`, role_key: `pms_demo_${suffix}`, 'menu-ids': menuIds });
  const roleId = (await api(page, 'GET', '/api/system/role')).find(item => item.role_key === `pms_demo_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`, password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立审核人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: '演示' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
  const reviewer = await context.newPage();
  await login(reviewer, username, password);
  return { suffix, username, userId, adminId: options.currentUserId, deptId, reviewer, context };
}

// 用模板声明的关口: 建实例 -> 全部检查项通过并绑定证据 -> 提交 -> 独立审核人批准.
async function passGate(f, gateType, title, overrides = {}) {
  const ws = await api(f.page, 'GET', base(f.id) + '/governance');
  const template = ws.gate_templates.find(t => t.gate_type === gateType);
  const gate = await f.command('/governance/gates', { template_id: template.id, title, reviewer_id: f.userId });
  await f.command(`/governance/gates/${gate.id}/checks`, { checks: template.checks.map(c => ({ code: c.code, passed: true, evidence_ids: [f.evidence.id], ...(overrides[c.code] || {}) })) });
  await f.command(`/governance/gates/${gate.id}/submit`, {});
  await f.command(`/governance/gates/${gate.id}/decision`, { decision: 'approved', reason: '独立核验证据版本' }, f.reviewer);
  return gate;
}

async function approveDelivery(f, collection, item, action = 'submit') {
  await f.command(`/delivery/${collection}/${item.id}/${action}`, { evidence_ids: [f.evidence.id], ...(collection === 'shipments' ? {} : { reviewer_id: f.userId }) });
  return f.command(`/delivery/${collection}/${item.id}/decision`, { decision: 'approved', reason: '独立核查实际记录' }, f.reviewer);
}

async function transitionUi(page, id, button, title) {
  await open(page, id);
  await drawer(page).getByRole('button', { name: new RegExp(`${button}$`) }).click();
  const response = page.waitForResponse(r => r.url().endsWith(`/projects/${id}/transition`) && r.request().method() === 'POST');
  await modal(page, title).getByRole('button', { name: title, exact: true }).click();
  const result = await (await response).json();
  expect(result.code, result.msg).toBe(200);
  await expect(modal(page, title)).toBeHidden();
  await page.waitForLoadState('networkidle');
}

test.describe('完整展示流程 (单一演示项目, 模板到正式关闭)', () => {
  test.setTimeout(900000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('设备订单项目从平台模板走到正式关闭, 逐步真实截图', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    fs.mkdirSync(output, { recursive: true });
    await login(page);
    const template = await equipmentTemplate(page);
    const f = await reviewerAccount(page, browser);
    f.page = page;
    const { suffix, reviewer } = f;
    const projectNo = `DEMO-${suffix.toUpperCase()}`;
    // 上一轮用例可能留下强制编码规则, 演示用自填编号, 先退役之.
    for (const r of (await api(page, 'GET', '/api/pms/config/coding-rule')).rows) {
      if (r.object_type === 'project' && r.status === 'published' && r.enforced) await api(page, 'POST', `/api/pms/config/coding-rule/${r.id}/retire`, { reason: '演示让位' });
    }

    // ── 1 平台: 模板与规则 ──
    await page.goto('/pms/config');
    await expect(page.getByRole('heading', { name: '模板与规则' })).toBeVisible();
    await expect(page.locator('tbody tr').filter({ hasText: 'TPL-EQUIPMENT' }).first().getByText('已发布')).toBeVisible();
    await shot(page, '01-platform-templates.png');

    // ── 2 项目中心: 创建项目 ──
    await page.goto('/pms/project');
    await page.getByRole('button', { name: /创建项目|新建项目/ }).first().click();
    const projectDrawer = page.getByRole('dialog').filter({ hasText: '创建项目' });
    await projectDrawer.locator('#project_no').fill(projectNo);
    await projectDrawer.locator('#name').fill(`演示 智能装配单机 ${suffix}`);
    await choose(page, projectDrawer, 'dept_id', '研发部门');
    await projectDrawer.locator('#start_date').fill('2026-09-01');
    await projectDrawer.locator('#end_date').fill('2027-03-31');
    const created = page.waitForResponse(r => r.url().endsWith('/api/pms/projects') && r.request().method() === 'POST');
    await projectDrawer.getByRole('button', { name: '保存项目' }).click();
    const project = (await (await created).json()).data;
    const id = project.project_id;
    f.id = id;
    f.command = (suffix2, data, actor = page) => mutate(actor, id, suffix2, data).then(r => r.result);
    await expect(projectDrawer).toBeHidden();
    await expect(page.locator('tbody tr').filter({ hasText: projectNo }).first()).toBeVisible();
    await shot(page, '02-project-center-created.png');

    // ── 3 应用项目模板 -> 结构节点/Gate模板/计划容器/收尾清单 ──
    await open(page, id);
    await drawer(page).getByRole('button', { name: '应用项目模板' }).click();
    await choose(page, modal(page, '应用项目模板'), 'template_id', '单机设备订单项目');
    await fill(modal(page, '应用项目模板'), { reason: '演示: 按单机设备订单模板建网' });
    const instance = (await save(page, '应用项目模板')).data.result;
    expect(instance.node_count).toBe(3);
    await expect(drawer(page).getByText('结构节点 3')).toBeVisible();
    await shot(page, '03-template-applied-overview.png');

    // ── 4 团队与任命书 ──
    await drawer(page).getByRole('button', { name: '维护成员', exact: true }).click();
    const member = page.getByRole('dialog', { name: '维护项目成员', exact: true });
    await member.locator('#user_id').click();
    await member.locator('#user_id').fill(f.username);
    await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option').filter({ hasText: f.username }).first().click();
    await member.locator('#role').click();
    await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option').filter({ hasText: '只读成员' }).first().click();
    await member.getByRole('button', { name: '保存成员', exact: true }).click();
    await expect(member).toBeHidden();
    await expect(drawer(page).getByText('只读成员', { exact: true })).toBeVisible();
    await open(page, id, '需求与治理', '成员任命');
    await drawer(page).getByRole('button', { name: '签发任命书', exact: true }).click();
    const appt = modal(page, '签发项目成员任命书');
    await appt.locator('#issued_on').fill(today());
    await appt.locator('#note').fill('演示: 项目团队任命');
    await appt.getByRole('button', { name: /保\s*存/ }).click();
    await expect(appt).toBeHidden();
    await expect(drawer(page).getByRole('button', { name: '查看任命书', exact: true }).first()).toBeVisible();
    await shot(page, '04-team-appointment.png');

    // ── 5 章程: 编制 -> 提交独立审批 -> 审核人批准 ──
    await tab(page, '章程');
    await drawer(page).getByRole('button', { name: '编制项目章程', exact: true }).click();
    await fill(modal(page, '编制项目章程'), { title: '智能装配单机项目章程', objective: '按合同交付一台智能装配单机并通过 SAT', scope: '主机单元 (含主机#1) 与附件单元的设计, 制造, 测试, 发运与现场验收', success_criteria: 'FAT/SAT 一次通过, 决算不超预算' });
    await choose(page, modal(page, '编制项目章程'), 'sponsor_id', /\/ admin$/);
    await save(page, '编制项目章程');
    await row(page, '智能装配单机项目章程').getByRole('button', { name: '提交审批', exact: true }).click();
    await choose(page, modal(page, '提交独立审批'), 'reviewer_id', f.username);
    await save(page, '提交独立审批');
    await open(reviewer, id, '需求与治理', '章程');
    await row(reviewer, '智能装配单机项目章程').getByRole('button', { name: '批准', exact: true }).click();
    await fill(modal(reviewer, '批准评审'), { reason: '独立审核: 目标/范围/成功标准与合同一致' });
    await save(reviewer, '批准评审');
    await open(page, id, '需求与治理', '章程');
    await expect(row(page, '智能装配单机项目章程').getByText('已批准')).toBeVisible();
    await shot(page, '05-charter-approved.png');

    // ── 6 URS 需求与追踪 ──
    await tab(page, 'URS与追踪');
    for (const [code, text, method] of [['URS-01', '工站节拍不超过 60 秒', '测试'], ['URS-02', '连续运行 8 小时无故障', '测试'], ['URS-03', '安全门联锁符合 GB 标准', '检验']]) {
      await drawer(page).getByRole('button', { name: '新增URS需求', exact: true }).click();
      const urs = modal(page, '新增URS需求');
      await fill(urs, { code, text });
      await choose(page, urs, 'owner_id', /\/ admin$/);
      await choose(page, urs, 'verification_method', method);
      await save(page, '新增URS需求');
    }
    const gov0 = await api(page, 'GET', base(id) + '/governance');
    const requirement = gov0.requirements.find(r => r.code === 'URS-01');
    f.requirement = requirement;
    await expect(drawer(page).getByText('已声明验证方式 100%')).toBeVisible();
    await shot(page, '06-urs-requirements.png');

    // ── 7 证据文档: 文本证据 + 真实 PDF 上传 (机密, 类别 设计) ──
    await tab(page, '证据版本');
    await drawer(page).getByRole('button', { name: '登记证据文档', exact: true }).click();
    await fill(modal(page, '登记证据文档'), { code: 'DEMO-EV', title: '工程验收记录', filename: 'acceptance-record.txt', content: '演示证据: 实测记录, 检验与签收记录用于本地演示.' });
    const evidence = (await save(page, '登记证据文档')).data.result;
    f.evidence = evidence;
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'pms-demo-'));
    const pdfPath = path.join(tmp, `design-review-${suffix}.pdf`);
    fs.writeFileSync(pdfPath, makePdf(`Design review ${suffix}`));
    await drawer(page).getByRole('button', { name: '上传证据文件' }).click();
    const uploadForm = modal(page, '上传证据文件');
    await fill(uploadForm, { code: 'DR-01', title: '主机设计评审报告' });
    await choose(page, uploadForm, 'classification', '机密');
    await choose(page, uploadForm, 'category', '设计');
    await uploadForm.locator('#document_file').setInputFiles(pdfPath);
    const uploaded = page.waitForResponse(r => r.url().includes('/documents/upload') && r.request().method() === 'POST');
    await uploadForm.getByRole('button', { name: /^上\s*传$/ }).click();
    expect((await (await uploaded).json()).code).toBe(200);
    await expect(uploadForm).toBeHidden();
    await page.waitForLoadState('networkidle');
    await expect(row(page, 'DR-01').getByText('机密')).toBeVisible();
    await shot(page, '07-evidence-documents.png');
    // 需求追踪: URS-01 -> 证据 DEMO-EV.
    await tab(page, 'URS与追踪');
    await drawer(page).getByRole('button', { name: '建立需求追踪', exact: true }).click();
    const trace = modal(page, '建立需求追踪');
    await choose(page, trace, 'requirement_id', 'URS-01');
    await choose(page, trace, 'target', '工程验收记录');
    await save(page, '建立需求追踪');

    // ── 8 干系人与沟通 ──
    await tab(page, '干系人与沟通');
    for (const [code, name, role] of [['SH-01', '客户设备部经理', '客户'], ['SH-02', '质量经理', '内部质量'], ['SH-03', '采购接口人', '供应链']]) {
      await drawer(page).getByRole('button', { name: '登记干系人', exact: true }).click();
      const sh = modal(page, '登记干系人');
      await fill(sh, { code, name, role });
      await sh.getByRole('button', { name: /保\s*存/ }).click();
      await expect(sh).toBeHidden();
    }
    await expect(row(page, 'SH-01')).toBeVisible();
    await shot(page, '08-stakeholders.png');

    // ── 9 DQ 关键任务: 建立 -> 检查 -> 提交签认 -> 审核人签认 ──
    await tab(page, 'DQ与局部暂停');
    await drawer(page).getByRole('button', { name: '建立DQ关键任务', exact: true }).click();
    const dqForm = modal(page, '建立DQ关键任务');
    await fill(dqForm, { code: 'DQ-01', title: '主机 DQ 编制与签认', check_titles: '设计输入完整\n图纸编号规范\n关键件选型有依据' });
    await choose(page, dqForm, 'owner_id', /\/ admin$/);
    await choose(page, dqForm, 'deliverable_ids', 'DEMO-EV');
    const dq = (await save(page, '建立DQ关键任务')).data.result;
    await row(page, 'DQ-01').getByRole('button', { name: '填写检查' }).click();
    const dqCheck = modal(page, '填写DQ检查结果');
    for (const code of ['D1', 'D2', 'D3']) await choose(page, dqCheck, `passed_${code}`, '检查通过');
    await save(page, '填写DQ检查结果');
    await row(page, 'DQ-01').getByRole('button', { name: '提交签认' }).click();
    await choose(page, modal(page, '提交DQ签认'), 'reviewer_id', f.username);
    await save(page, '提交DQ签认');
    await mutate(reviewer, id, `/governance/dqs/${dq.id}/decision`, { decision: 'approved', reason: '独立签认' });
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    await expect(row(page, 'DQ-01').getByText('3/3')).toBeVisible();
    await shot(page, '09-dq-signed.png');

    // ── 10 计划: 派生主/子/单机计划 -> 主子冲突 -> 覆盖阶段权重 ──
    await open(page, id, '计划与执行', '进度卷积');
    await drawer(page).getByRole('button', { name: '派生主/子/单机计划', exact: true }).click();
    await fill(modal(page, '派生主/子/单机计划'), { reason: '演示: 按模板阶段层级派生' });
    const derived = (await save(page, '派生主/子/单机计划')).data.result;
    expect(derived).toMatchObject({ node_count: 3, main_stage_count: 5, derived_count: 15 });
    // 一个由审核人负责且已逾期的现场服务准备任务, 用于系统提醒演示.
    const svcTask = await f.command('/tasks', { wbs_code: 'SVC-1', name: '现场服务准备', owner_id: f.userId, task_type: 'task', duration_days: 3, start_date: '2026-09-01', stage_code: 'S7' });
    await open(page, id, '计划与执行', 'WBS与排程');
    await expect(drawer(page).getByRole('cell', { name: '模板派生', exact: true }).first()).toBeVisible();
    await shot(page, '10-plan-derived-wbs.png');
    const plan0 = await api(page, 'GET', base(id) + '/planning');
    const m1s4 = plan0.tasks.find(t => t.wbs_code === `${projectNo}-M1-S4`);
    await mutate(page, id, `/tasks/${m1s4.task_id}`, { duration_days: 60 }, 200, 'PUT');
    await open(page, id, '计划与执行', '进度卷积');
    await drawer(page).getByRole('button', { name: '覆盖阶段权重', exact: true }).click();
    await fill(modal(page, '覆盖阶段权重'), { w_S1: 5, w_S2: 15, w_S3: 10, w_S4: 30, w_S5: 15, w_S6: 10, w_S7: 10, w_S8: 5, reason: '装配调试为交付关键' });
    await save(page, '覆盖阶段权重');
    await expect(drawer(page).getByText('主子约束冲突 1', { exact: true })).toBeVisible();
    await expect(drawer(page).getByText('权重来自项目级覆盖 (模板快照不变)')).toBeVisible();
    await shot(page, '11-plan-rollup-conflict-weights.png', drawer(page).getByText('主子约束冲突', { exact: true }).first());

    // ── 11 需求确认 Gate (执行准入) 与设计阶段汇总 Gate ──
    await passGate(f, 'requirement-confirm', '需求确认 Gate 检查');
    await passGate(f, 'host-summary', '主机汇总 Gate 检查');
    await passGate(f, 'attachment-summary', '附件汇总 Gate 检查');
    await open(page, id, '需求与治理', 'Gate评审');
    await expect(drawer(page).getByText('检查 3/3', { exact: true }).first()).toBeVisible();
    await shot(page, '12-gates-requirement-confirmed.png');

    // ── 12 立项 -> 计划 -> 提交基线 -> 审核人批准 ──
    await transitionUi(page, id, '确认立项', '确认立项');
    await transitionUi(page, id, '进入计划', '进入计划阶段');
    await open(page, id, '计划与执行', '审批与基线');
    await drawer(page).getByRole('button', { name: '提交计划审批', exact: true }).click();
    await fill(modal(page, '提交计划审批'), { comment: '主/子/单机计划已派生并评审' });
    const baseline = (await save(page, '提交计划审批')).data.result;
    await open(reviewer, id, '计划与执行', '审批与基线');
    await drawer(reviewer).getByRole('button', { name: '批准', exact: true }).first().click();
    await fill(modal(reviewer, '批准计划基线'), { comment: '独立批准: 主子计划一致' });
    await save(reviewer, '批准计划基线');
    await open(page, id, '计划与执行', '审批与基线');
    await expect(drawer(page).getByText('已批准').first()).toBeVisible();
    await shot(page, '13-baseline-approved.png');

    // ── 13 交付配置 (执行前): 工勘 1 次, 发货前须入库确认, 交底 +2 天, 现场滞后 +1 天, 交底必需 ──
    await mutate(page, id, '/delivery/configuration', { required_stages: ['materials', 'assembly', 'quality', 'shipment'], required_test_types: ['SIT', 'FAT', 'SAT'], required_survey_visits: 1, pre_ship_conditions: ['warehouse_in'], handover_deadline_days: 2, site_lag_days: 1, handover_required: true, reason: '演示: 现场交付要求' });

    // ── 14 进入执行 ──
    await transitionUi(page, id, '进入执行', '进入执行阶段');
    await expect(drawer(page).getByText('执行中', { exact: true }).first()).toBeVisible();
    await shot(page, '14-execution-started.png');

    // ── 15 启动会 (会前包 + 基线) ──
    await tab(page, '需求与治理');
    await tab(page, '会议行动');
    await drawer(page).getByRole('button', { name: '登记项目会议', exact: true }).click();
    const meeting = modal(page, '登记项目会议');
    await fill(meeting, { title: '项目启动会', held_on: today(), minutes: '确认主计划, 交付要求与售前资料; 行动: 长周期件提前下单' });
    await choose(page, meeting, 'meeting_type', '项目启动会');
    await choose(page, meeting, 'attendee_ids', /\/ admin$/);
    await choose(page, meeting, 'material_ids', 'DEMO-EV');
    await choose(page, meeting, 'baseline_id', '计划修订');
    await save(page, '登记项目会议');
    await expect(row(page, '项目启动会').getByText(/计划修订 \d+/)).toBeVisible();
    await shot(page, '15-kickoff-meeting.png');

    // ── 16 风险登记 ──
    await tab(page, '风险与问题');
    await drawer(page).getByRole('button', { name: '登记项目风险', exact: true }).click();
    const risk = modal(page, '登记项目风险');
    await fill(risk, { title: '长周期件交期风险', probability: 2, impact: 3, mitigation: '提前下单并跟踪供应商进度, 预留替代料方案', due_date: '2026-10-31' });
    await choose(page, risk, 'owner_id', /\/ admin$/);
    await choose(page, risk, 'response_strategy', '减轻');
    await save(page, '登记项目风险');
    await expect(row(page, '长周期件交期风险')).toBeVisible();
    await shot(page, '16-risk-register.png');

    // ── 17 备料申请 (界面) -> 审批 -> BOM 冻结 -> 齐套卷积 (缺件) ──
    const refs = { task_id: m1s4.task_id, requirement_ids: [requirement.id] };
    await open(page, id, '工程交付', '备料与BOM');
    await drawer(page).getByRole('button', { name: '新建备料申请', exact: true }).click();
    const mr = modal(page, '新建备料申请');
    await fill(mr, { code: 'MR-01', title: '主机长周期件备料', needed_on: daysAgo(10), items_0_code: 'SRV-01', items_0_name: '伺服电机', items_0_quantity: 2 });
    await choose(page, mr, 'request_type', '长周期物料');
    await choose(page, mr, 'node_id', '主机#1');
    await choose(page, mr, 'owner_id', /\/ admin$/);
    await choose(page, mr, 'task_id', '主机#1 装配调试');
    await choose(page, mr, 'requirement_ids', 'URS-01');
    await mr.getByRole('button', { name: '添加明细行', exact: true }).click();
    await fill(mr, { items_1_code: 'PLC-01', items_1_name: 'PLC 控制器', items_1_quantity: 1 });
    const material = (await save(page, '新建备料申请')).data.result;
    await approveDelivery(f, 'material-requests', material);
    const bom = await f.command('/delivery/boms', { code: 'BOM-01', title: '主机#1 冻结配置', material_request_id: material.id });
    await approveDelivery(f, 'boms', bom, 'freeze');
    await f.command(`/delivery/boms/${bom.id}/kit`, { items: [{ code: 'SRV-01', available_quantity: 2 }, { code: 'PLC-01', available_quantity: 0 }], evidence_ids: [evidence.id] });
    await open(page, id, '工程交付', '备料与BOM');
    await expect(drawer(page).getByText(/整体齐套率 \d+%/)).toBeVisible();
    await drawer(page).getByText(/缺件清单 1 行/).click();
    await shot(page, '17-kitting-shortage.png');

    // ── 18 齐套 Gate 阻断装配开工 -> 补齐 -> 齐套 Gate 通过 -> 登记开工与装配步骤 ──
    await f.command(`/delivery/boms/${bom.id}/kit`, { items: [{ code: 'SRV-01', available_quantity: 2 }, { code: 'PLC-01', available_quantity: 1 }], evidence_ids: [evidence.id] });
    const assembly = await f.command('/delivery/assemblies', { ...refs, code: 'ASM-01', title: '主机#1 装配', bom_id: bom.id, owner_id: f.adminId });
    // 齐套 Gate 未通过 -> 登记开工被 409 阻断; 通过后才能开工.
    await mutate(page, id, `/delivery/assemblies/${assembly.id}/start`, { evidence_ids: [evidence.id] }, 409);
    await passGate(f, 'kitting', '零件齐套放行');
    await f.command(`/delivery/assemblies/${assembly.id}/start`, { evidence_ids: [evidence.id] });
    await open(page, id, '工程交付', '装配交检');
    for (const [label, d, note] of [['上岛', daysAgo(8), '设备上岛'], ['装配', daysAgo(6), '机械/电气装配'], ['单机交检', daysAgo(5), '单机交检合格'], ['连线交检', daysAgo(4), '连线交检合格']]) {
      await row(page, 'ASM-01').getByRole('button', { name: '登记步骤' }).click();
      await fill(modal(page, '登记装配步骤'), { actual_date: d, note });
      await save(page, '登记装配步骤');
      await expect(row(page, 'ASM-01').getByText(`${label} ${d}`)).toBeVisible();
    }
    await shot(page, '18-assembly-steps.png');
    await approveDelivery(f, 'assemblies', assembly);

    // ── 19 装配与测试交接 Gate: 界面填写检查, AT-4 例外放行 -> 审核人批准 ──
    await open(page, id, '需求与治理', 'Gate评审');
    await drawer(page).getByRole('button', { name: '发起Gate检查', exact: true }).click();
    const atForm = modal(page, '发起Gate检查');
    await fill(atForm, { title: '装配与测试交接检查' });
    await choose(page, atForm, 'template_id', '装配与测试交接Gate (G5)');
    await choose(page, atForm, 'reviewer_id', f.username);
    await save(page, '发起Gate检查');
    await row(page, '装配与测试交接检查').getByRole('button', { name: '填写检查', exact: true }).click();
    const atChecks = modal(page, '填写Gate检查结果');
    for (const code of ['AT-1', 'AT-2', 'AT-3']) {
      await choose(page, atChecks, `passed_${code}`, '检查通过');
      await choose(page, atChecks, `evidence_${code}`, 'DEMO-EV');
    }
    await choose(page, atChecks, 'passed_AT-4', '例外放行 (需说明)');
    await fill(atChecks, { 'waiver_AT-4': '测试工装下周到位, 接收责任人同意先行交接' });
    await save(page, '填写Gate检查结果');
    await expect(drawer(page).getByText('例外 1', { exact: true })).toBeVisible();
    await shot(page, '19-gate-waiver.png');
    const atGate = (await api(page, 'GET', base(id) + '/governance')).gates.find(g => g.title === '装配与测试交接检查');
    await f.command(`/governance/gates/${atGate.id}/submit`, {});
    await f.command(`/governance/gates/${atGate.id}/decision`, { decision: 'approved', reason: '例外项已登记, 同意交接' }, reviewer);

    // ── 20 SIT / FAT 试验 (界面建立 SIT) ──
    await open(page, id, '工程交付', '质量试验');
    await drawer(page).getByRole('button', { name: '建立质量试验', exact: true }).click();
    const sit = modal(page, '建立质量试验');
    await fill(sit, { code: 'SIT-01', title: '主机#1 SIT', criteria_0_title: '节拍不超过 60 秒且安全联锁有效' });
    await choose(page, sit, 'test_type', 'SIT');
    await choose(page, sit, 'assembly_id', '主机#1 装配');
    await choose(page, sit, 'owner_id', /\/ admin$/);
    await choose(page, sit, 'task_id', '主机#1 装配调试');
    await choose(page, sit, 'requirement_ids', 'URS-01');
    const sitRecord = (await save(page, '建立质量试验')).data.result;
    await f.command(`/delivery/tests/${sitRecord.id}/results`, { checks: [{ code: 'C01', passed: true, actual: '实测节拍 52 秒, 联锁有效', evidence_ids: [evidence.id] }], due_date: '2026-10-15' });
    await approveDelivery(f, 'tests', sitRecord);
    const fat = await f.command('/delivery/tests', { ...refs, code: 'FAT-01', title: '主机#1 FAT', assembly_id: assembly.id, test_type: 'FAT', owner_id: f.adminId, criteria: [{ code: 'F1', title: '客户见证 8 小时连续运行', required: true }] });
    await f.command(`/delivery/tests/${fat.id}/results`, { checks: [{ code: 'F1', passed: true, actual: '连续运行 8h 无故障', evidence_ids: [evidence.id] }], due_date: '2026-10-15' });
    await approveDelivery(f, 'tests', fat);
    await open(page, id, '工程交付', '质量试验');
    await expect(row(page, 'FAT-01').getByText('已批准')).toBeVisible();
    await shot(page, '20-sit-fat-approved.png');

    // ── 21 发运: 发货前条件 (入库确认) -> FAT 确认 Gate -> 放行 -> 发运 -> 审核人签收 ──
    const shipment = await f.command('/delivery/shipments', { ...refs, code: 'SHIP-01', title: '主机#1 发运', assembly_ids: [assembly.id], consignee: '客户设备部', delivery_address: '客户厂区 A 车间', planned_date: daysAgo(3), reviewer_id: f.userId });
    await open(page, id, '工程交付', '发运签收');
    await row(page, 'SHIP-01').getByRole('button', { name: '发货前条件' }).click();
    const cond = modal(page, '确认发货前条件');
    await choose(page, cond, 'warehouse_in_confirmed', '已确认');
    await fill(cond, { warehouse_note: 'WMS 入库单 IN-0921' });
    await save(page, '确认发货前条件');
    await passGate(f, 'fat-confirm', 'FAT 确认放行');
    await approveDelivery(f, 'shipments', shipment);
    await f.command(`/delivery/shipments/${shipment.id}/dispatch`, { shipped_on: daysAgo(3), tracking_no: 'DEMO-TRACK-001', evidence_ids: [evidence.id] });
    await f.command(`/delivery/shipments/${shipment.id}/receipt`, { received_on: daysAgo(2), receiver_name: '客户设备部经理', acceptance: 'accepted', evidence_ids: [evidence.id] }, reviewer);
    await open(page, id, '工程交付', '发运签收');
    await expect(row(page, 'SHIP-01').getByText('已完整接受')).toBeVisible();
    await shot(page, '21-shipment-received.png');

    // ── 22 交底 -> 现场任务 (定位/安装/调试/SAT) -> 工勘 ──
    await passGate(f, 'handover', '项目交底 Gate 检查');
    await open(page, id, '工程交付', '工勘与现场');
    await row(page, 'HO-SHIP-01').getByRole('button', { name: '完成交底' }).click();
    const ho = modal(page, '完成项目交底');
    await fill(ho, { completed_on: daysAgo(1), checklist_note: '交底清单 V1: 图纸/程序/操作手册/备件清单' });
    await choose(page, ho, 'document_ids', 'DEMO-EV');
    await save(page, '完成项目交底');
    await row(page, '现场定位').getByRole('button', { name: '开始' }).click();
    await fill(modal(page, '开始现场任务'), { actual_start: today() });
    await save(page, '开始现场任务');
    await row(page, '现场定位').getByRole('button', { name: '完成' }).click();
    const doneSite = modal(page, '完成现场任务');
    await fill(doneSite, { actual_end: today(), result: '定位完成, 地脚固定' });
    await choose(page, doneSite, 'evidence_ids', 'DEMO-EV');
    await save(page, '完成现场任务');
    const siteTasks = (await api(page, 'GET', base(id) + '/delivery')).site_tasks.filter(t => t.handover_id).sort((a, b) => a.sequence - b.sequence);
    for (const t of siteTasks.filter(t => t.status !== 'closed')) {
      await f.command(`/delivery/site-tasks/${t.id}/start`, { actual_start: today() });
      await f.command(`/delivery/site-tasks/${t.id}/complete`, { actual_end: today(), result: '完成', evidence_ids: [evidence.id] });
    }
    await open(page, id, '工程交付', '工勘与现场');
    await drawer(page).getByRole('button', { name: '登记工勘任务', exact: true }).click();
    const sv = modal(page, '登记工勘任务');
    await fill(sv, { code: 'SV-01', title: '现场工勘', planned_date: daysAgo(12), deliverable: '现场勘察报告' });
    await choose(page, sv, 'owner_id', /\/ admin$/);
    const survey = (await save(page, '登记工勘任务')).data.result;
    await row(page, 'SV-01').getByRole('button', { name: '提交工勘确认' }).click();
    const svSubmit = modal(page, '提交工勘确认');
    await fill(svSubmit, { actual_date: daysAgo(12), findings: '地基, 供电与气源满足安装要求' });
    await choose(page, svSubmit, 'evidence_ids', 'DEMO-EV');
    await choose(page, svSubmit, 'reviewer_id', f.username);
    await save(page, '提交工勘确认');
    await mutate(reviewer, id, `/delivery/surveys/${survey.id}/decision`, { decision: 'approved', reason: '交付物确认' });
    await open(page, id, '工程交付', '工勘与现场');
    await expect(row(page, 'SV-01').getByText('已批准')).toBeVisible();
    await expect(row(page, '现场SAT').getByText('已关闭')).toBeVisible();
    await shot(page, '22-handover-survey.png');
    await shot(page, '22b-site-tasks.png', row(page, '现场SAT'));

    // ── 23 SAT 试验 ──
    const sat = await f.command('/delivery/tests', { ...refs, code: 'SAT-01', title: '主机#1 SAT', assembly_id: assembly.id, test_type: 'SAT', owner_id: f.adminId, criteria: [{ code: 'S1', title: '现场连续生产 4 小时并由客户确认', required: true }] });
    await f.command(`/delivery/tests/${sat.id}/results`, { checks: [{ code: 'S1', passed: true, actual: '客户签字确认', evidence_ids: [evidence.id] }], due_date: '2026-10-15' });
    await approveDelivery(f, 'tests', sat);
    await open(page, id, '工程交付', '质量试验');
    await expect(row(page, 'SAT-01').getByText('已批准')).toBeVisible();
    await shot(page, '23-sat-approved.png');

    // ── 24 执行反馈: 实际日期 -> 批准工时 -> 定时扫描/手动快照 -> 挣值与趋势 ──
    await open(page, id, '计划与执行', 'WBS与排程');
    await row(page, 'S1-MAIN').getByRole('button', { name: '反馈进度', exact: true }).click();
    const fb = modal(page, '反馈任务进度');
    await choose(page, fb, 'status', '已完成');
    await fill(fb, { percent_complete: 100, remaining_days: 0, actual_start: '2026-09-01', actual_end: '2026-09-12', comment: '设计准备实际完成' });
    await save(page, '反馈任务进度');
    await expect(row(page, 'S1-MAIN').getByRole('cell', { name: '2026-09-01 / 2026-09-12', exact: true })).toBeVisible();
    await shot(page, '24-feedback-actual-dates.png');
    const workDate = `2026-09-0${1 + (Date.now() % 9)}`;
    const s1 = plan0.tasks.find(t => t.wbs_code === 'S1-MAIN');
    await open(page, id, '实际工时');
    await drawer(page).getByRole('button', { name: '提交实际工时', exact: true }).click();
    await fill(modal(page, '提交实际工时'), { work_date: workDate, hours: '2', note: '设计准备评审' });
    await choose(page, modal(page, '提交实际工时'), 'task_id', '设计准备 (主计划)');
    await choose(page, modal(page, '提交实际工时'), 'reviewer_id', f.username);
    await save(page, '提交实际工时');
    await open(reviewer, id, '实际工时');
    await row(reviewer, workDate).getByRole('button', { name: '批准', exact: true }).click();
    const timeApprove = modal(reviewer, '批准工时单');
    const timeReason = timeApprove.locator('textarea');
    if (await timeReason.count()) await timeReason.fill('核对工作日与任务, 同意');
    await save(reviewer, '批准工时单');
    await open(page, id, '实际工时');
    await expect(row(page, workDate).getByText('已批准')).toBeVisible();
    await shot(page, '25-time-entries-approved.png');
    await api(page, 'PUT', '/api/system/job/9001/run');
    await expect.poll(async () => (await api(page, 'GET', base(id) + '/planning')).progress_history.length, { timeout: 60000, intervals: [1000, 2000, 3000] }).toBe(1);
    await open(page, id, '计划与执行', '进度卷积');
    await drawer(page).getByRole('button', { name: '生成进度快照', exact: true }).click();
    await save(page, '生成进度快照');
    await expect(drawer(page).getByText('挣值与完工预测')).toBeVisible();
    await shot(page, '26-earned-value-snapshot.png', drawer(page).getByText('BAC 计划总工作日'));

    // ── 25 审核人的我的待办: 系统提醒 (逾期任务 SVC-1) ──
    await reviewer.goto('/pms/todo');
    await expect(reviewer.getByRole('heading', { name: '我的待办' })).toBeVisible();
    await expect(reviewer.getByText('SVC-1 现场服务准备')).toBeVisible();
    await reviewer.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    await reviewer.screenshot({ path: path.join(output, '27-my-todo-reminders.png'), animations: 'disabled' });

    // ── 26 四算: 概算/预算/核算 (工时分摊)/决算 独立批准 -> 四算拉通 ──
    const versions = {};
    for (const [kind, name, amount] of [['estimate', '合同概算', '3000'], ['budget', '执行预算', '3200'], ['actual', '期间核算', '3100'], ['settlement', '项目决算', '3150']]) {
      const v = await f.command('/cost-versions', { kind, period: '2026-09', currency: 'CNY', name, revenue: '10000', reviewer_id: f.userId });
      await f.command(`/cost-versions/${v.id}/entries`, { category: 'material', label: '材料与外购件', amount });
      await f.command(`/cost-versions/${v.id}/submit`, {});
      await f.command(`/cost-versions/${v.id}/review`, { decision: 'approved', reason: '独立审批' }, reviewer);
      versions[kind] = v;
    }
    await open(page, id, '项目费用');
    await expect(drawer(page).getByText('预算-概算 200.00')).toBeVisible();
    await shot(page, '28-four-count-finance.png');

    // ── 27 看板: 过程看板 / 项目组合看板 / 经营目标看板 / 全局检索 / 项目驾驶舱 ──
    await tab(page, '过程看板');
    await shot(page, '29-process-board.png');
    await page.goto('/pms/portfolio');
    await expect(page.getByRole('heading', { name: '项目组合看板' })).toBeVisible();
    const portfolioRow = page.locator('tbody tr').filter({ hasText: projectNo }).first();
    await expect(portfolioRow).toBeVisible();
    await portfolioRow.locator('.ant-table-row-expand-icon').click();
    await shot(page, '30-portfolio-board.png', portfolioRow);
    await page.goto('/pms/targets');
    await page.waitForLoadState('networkidle');
    await shot(page, '31-targets-board.png');
    await page.goto('/pms/search');
    await page.getByPlaceholder('输入关键字').fill('智能装配单机');
    await page.getByRole('button', { name: /^检\s*索$/ }).click();
    await expect(page.getByText(/检索结果 \d+/)).toBeVisible({ timeout: 30000 });
    await shot(page, '32-global-search.png');
    await page.goto('/pms/dashboard');
    await page.waitForLoadState('networkidle');
    await shot(page, '33-dashboard.png');

    // ── 28 接口运维: 八套外部系统未配置 (如实展示边界) ──
    await open(page, id, '接口运维');
    await expect(drawer(page).getByText('未配置', { exact: true }).first()).toBeVisible();
    await shot(page, '34-integration-not-configured.png');

    // ── 29 收尾: 全部叶任务完成 -> 进入收尾 -> SAT 确认 Gate -> 收尾清单/移交/经验 -> 关闭审批 -> 正式关闭 ──
    const planNow = await api(page, 'GET', base(id) + '/planning');
    for (const t of planNow.tasks.filter(t => t.task_type !== 'summary' && t.status !== 'done')) {
      await f.command(`/tasks/${t.task_id}/feedback`, { status: 'done', percent_complete: 100, remaining_days: 0, comment: '演示: 按计划完成' });
    }
    await passGate(f, 'sat-confirm', 'SAT 确认 Gate 检查');
    await transitionUi(page, id, '进入收尾', '进入收尾阶段');
    await open(page, id, '需求与治理', 'Gate评审');
    await expect(drawer(page).getByText('已通过').first()).toBeVisible();
    await shot(page, '35-gates-all-passed.png');
    await open(page, id, '结项与移交');
    for (const title of ['交付物清单归档', '四算决算已批准', '遗留问题移交安排']) {
      await row(page, title).getByRole('button', { name: '确认完成', exact: true }).click();
      await choose(page, modal(page, '完成收尾检查'), 'evidence_ref', 'DEMO-EV');
      await fill(modal(page, '完成收尾检查'), { comment: `已核对: ${title}` });
      await save(page, '完成收尾检查');
    }
    await drawer(page).getByRole('button', { name: '新增移交事项', exact: true }).click();
    const handoff = modal(page, '新增交付移交');
    await fill(handoff, { title: '备件清单与操作手册移交客户', due_date: today() });
    await choose(page, handoff, 'owner_id', /\/ admin$/);
    await save(page, '新增交付移交');
    await row(page, '备件清单与操作手册移交客户').getByRole('button', { name: '确认移交', exact: true }).click();
    const handoffDone = modal(page, '确认交付移交');
    await choose(page, handoffDone, 'evidence_ref', 'DEMO-EV');
    await fill(handoffDone, { comment: '客户设备部签收' });
    await save(page, '确认交付移交');
    await drawer(page).getByRole('button', { name: '登记项目经验', exact: true }).click();
    await fill(modal(page, '登记项目经验'), { title: '长周期件提前下单', category: '供应链', content: '伺服电机交期 10 周, 立项后两周内下单可避免装配等料.' });
    await save(page, '登记项目经验');
    await expect(drawer(page).getByText('当前结项前置检查已通过.', { exact: true })).toBeVisible();
    await shot(page, '36-closure-checklist-done.png');
    await drawer(page).getByRole('button', { name: '提交关闭审批', exact: true }).click();
    await choose(page, modal(page, '提交项目关闭审批'), 'reviewer_id', f.username);
    await save(page, '提交项目关闭审批');
    await open(reviewer, id, '结项与移交');
    await drawer(reviewer).getByRole('button', { name: '批准关闭', exact: true }).click();
    await fill(modal(reviewer, '批准项目关闭'), { reason: '交付, 质量, 工时, 决算与清单已独立核对' });
    await save(reviewer, '批准项目关闭');
    await transitionUi(page, id, '正式关闭', '正式关闭项目');
    await expect(drawer(page).getByText('项目已结束,当前为只读视图', { exact: true })).toBeVisible();
    await shot(page, '37-project-closed.png');
    expect((await api(page, 'GET', base(id))).status).toBe('closed');

    expect(errors).toEqual([]);
    await f.context.close();
  });
});
