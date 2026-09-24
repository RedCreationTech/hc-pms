const { test, expect } = require('@playwright/test');
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');
const { execFileSync } = require('node:child_process');
const S = require('../../scripts/demo-video/storyboard.js');
const kit = require('./demo-video-kit.js');

const { installCursor, installClock, glide, tap, typeInto, settle } = kit;
const recorder = pages => kit.recorder(S, pages);

// 完整流程演示视频的自动录屏 (分镜与字幕见 scripts/demo-video/storyboard.js 与 docs/pms/15-demo-video-storyboard.md).
// 同一个设备订单项目从平台模板走到正式关闭; 项目经理与独立审核人各一个录像上下文, 画面带可见光标与点击涟漪, 表单逐字输入.
// 每个镜头记录在各自录像中的起止时间与字幕出现时刻, 写入 reports/demo-video/timeline.json, 由 compose.py 剪辑合成.
// 重复性的提交/批准经真实 HTTP 完成且不入镜 (rec.cut 切掉), 画面只保留操作与结果. 录制约 20 分钟, 默认跳过.
// 按章节分段录制并把跨章节状态写入 state.json: 中断后重跑会从未完成的章节继续 (PMS_DEMO_VIDEO_FRESH=1 强制从头开始).
// 设置 PMS_DEMO_DB (SQLite 库文件) 时, 每章开始前备份一次数据库到 raw/ckpt-XX.db, 便于中断后恢复到该章开始时的状态再续录.
test.skip(!process.env.PMS_DEMO_VIDEO, '演示视频录屏较慢, 仅在 PMS_DEMO_VIDEO=1 时运行');

const OUT = path.resolve(__dirname, '../../reports/demo-video');
const RAW = path.join(OUT, 'raw');
const VIEWPORT = kit.VIEWPORT;
const BASE_URL = process.env.BASE_URL || 'http://localhost:3000';

const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const localDate = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; // 本地日期 (与后端所在时区的 "今天" 一致, 避免 0-8 点 UTC 跨日)
const today = () => localDate(new Date());
const daysAgo = n => { const d = new Date(); d.setDate(d.getDate() - n); return localDate(d); };
const wait = kit.wait;

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

async function setDate(page, locator, value) {
  await tap(page, locator);
  await locator.fill(value);
  await page.waitForTimeout(160);
}

async function choose(page, form, key, text) {
  const input = form.locator(`[id="${key}"]`);
  const multiple = await input.evaluate(el => !!el.closest('.ant-select-multiple'));
  await tap(page, input);
  if (typeof text === 'string' && await input.isEditable()) await input.pressSequentially(text, { delay: 60 });
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').filter({ has: page.locator(`[id="${list}"]`) });
  await tap(page, dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first());
  // 单选选中后下拉自动关闭, 此时再按 Escape 会关闭外层弹窗/抽屉; 只有多选 (选中后下拉保持打开) 才按 Escape 收起.
  if (multiple) await input.press('Escape');
  await page.waitForTimeout(160);
}

async function save(page, title, expected = 200, button = /^保\s*存$/) {
  const form = modal(page, title);
  const response = page.waitForResponse(r => r.url().includes('/api/pms/') && ['POST', 'PUT', 'DELETE'].includes(r.request().method()));
  await tap(page, form.getByRole('button', { name: button }));
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(expected);
  if (expected === 200) { await expect(form).toBeHidden(); await page.waitForLoadState('networkidle'); }
  await page.waitForTimeout(350);
  return body;
}

async function tabUi(page, name) {
  await tap(page, drawer(page).getByRole('tab', { name, exact: true }));
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(300);
}

async function open(page, id, section, subsection) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  if (section) { await drawer(page).getByRole('tab', { name: section, exact: true }).click(); await page.waitForLoadState('networkidle'); }
  if (subsection) { await drawer(page).getByRole('tab', { name: subsection, exact: true }).click(); await page.waitForLoadState('networkidle'); }
  await page.waitForTimeout(400);
  await settle(page);
}

// 生命周期按钮在项目概况卡片右上角, 确认弹窗的确定按钮与标题同名.
async function transition(page, id, button, title) {
  await tap(page, drawer(page).getByRole('button', { name: new RegExp(`${button}$`) }));
  const response = page.waitForResponse(r => r.url().endsWith(`/projects/${id}/transition`) && r.request().method() === 'POST');
  await tap(page, modal(page, title).getByRole('button', { name: title, exact: true }));
  const result = await (await response).json();
  expect(result.code, result.msg).toBe(200);
  await expect(modal(page, title)).toBeHidden();
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(400);
}

async function login(page, user = 'admin', password = 'admin123') {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill(user);
  await page.getByPlaceholder('密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ruoyi_token'))).toBeTruthy();
}

function makePdf(text) {
  const objects = [];
  objects.push('<< /Type /Catalog /Pages 2 0 R >>');
  objects.push('<< /Type /Pages /Kids [3 0 R] /Count 1 >>');
  objects.push('<< /Type /Page /Parent 2 0 R /MediaBox [0 0 420 240] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>');
  const stream = `BT /F1 20 Tf 30 170 Td (${text}) Tj ET BT /F1 12 Tf 30 130 Td (Host unit design review - approved for manufacturing) Tj ET BT /F1 12 Tf 30 110 Td (Cycle time 52 s, safety interlock verified) Tj ET`;
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

async function createReviewer(page, suffix) {
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve', 'pms:time:approve',
    'pms:finance:query', 'pms:finance:approve', 'pms:project:close', 'pms:project:reopen', 'pms:document:confidential', 'pms:dashboard:query']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `演示审核${suffix}`, role_key: `pms_video_${suffix}`, 'menu-ids': menuIds });
  const roleId = (await api(page, 'GET', '/api/system/role')).find(item => item.role_key === `pms_video_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `qa_${suffix}`, password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '质量总监 王审', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: '演示视频' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  return { username, password, userId, adminId: options.currentUserId };
}

// ── 跨章节的后台操作 (不入镜) ──

function helpers(page, reviewer, st) {
  const command = (suffix, data, actor = page) => mutate(actor, st.id, suffix, data).then(r => r.result);
  const passGate = async (gateType, title, overrides = {}) => {
    const ws = await api(page, 'GET', base(st.id) + '/governance');
    const t = ws.gate_templates.find(x => x.gate_type === gateType);
    const gate = await command('/governance/gates', { template_id: t.id, title, reviewer_id: st.who.userId });
    await command(`/governance/gates/${gate.id}/checks`, { checks: t.checks.map(c => ({ code: c.code, passed: true, evidence_ids: [st.evidence.id], ...(overrides[c.code] || {}) })) });
    await command(`/governance/gates/${gate.id}/submit`, {});
    await command(`/governance/gates/${gate.id}/decision`, { decision: 'approved', reason: '独立核验证据版本' }, reviewer);
    return gate;
  };
  const approveDelivery = async (collection, item, action = 'submit') => {
    await command(`/delivery/${collection}/${item.id}/${action}`, { evidence_ids: [st.evidence.id], ...(collection === 'shipments' ? {} : { reviewer_id: st.who.userId }) });
    return command(`/delivery/${collection}/${item.id}/decision`, { decision: 'approved', reason: '独立核查实际记录' }, reviewer);
  };
  return { command, passGate, approveDelivery };
}

// 章节 → 录制函数; 章节之间只通过 st (state.json) 传递项目, 账号与关键对象 id.
const CHAPTERS = {
  '01': async ({ page, reviewer, rec, st, h }) => {
    await page.goto('/pms/config');
    await expect(page.getByRole('heading', { name: '模板与规则' })).toBeVisible();
    await page.waitForLoadState('networkidle');
    await rec.shot('01-1');
    await glide(page, page.getByRole('heading', { name: '模板与规则' }));
    await rec.next();
    await glide(page, page.getByText('单机设备订单项目', { exact: true }).first());
    await rec.next();
    await glide(page, page.locator('tbody tr').filter({ hasText: 'TPL-EQUIPMENT' }).first().getByText('已发布'));
    await rec.next();
    await tap(page, page.getByRole('tab', { name: '编码与版本规则' }));
    await page.waitForLoadState('networkidle');
    await rec.end();
  },
  '02': async ({ page, reviewer, rec, st, h }) => {
    let id;
    const { who, projectNo, short } = st;
    await page.goto('/pms/project');
    await page.waitForLoadState('networkidle');
    await rec.shot('02-1');
    await tap(page, page.getByRole('button', { name: /创建项目|新建项目/ }).first());
    const pd = page.getByRole('dialog').filter({ hasText: '创建项目' });
    await typeInto(page, pd.locator('#project_no'), projectNo, 70);
    await typeInto(page, pd.locator('#name'), `智能装配单机项目 ${short}`, 70);
    await rec.next();
    await choose(page, pd, 'dept_id', '研发部门');
    await setDate(page, pd.locator('#start_date'), '2026-09-01');
    await setDate(page, pd.locator('#end_date'), '2027-03-31');
    const created = page.waitForResponse(r => r.url().endsWith('/api/pms/projects') && r.request().method() === 'POST');
    await tap(page, pd.getByRole('button', { name: '保存项目' }));
    id = st.id = (await (await created).json()).data.project_id;
    await expect(pd).toBeHidden();
    await glide(page, page.locator('tbody tr').filter({ hasText: projectNo }).first());
    await rec.end();

    await open(page, id);
    await rec.shot('02-2');
    await tap(page, drawer(page).getByRole('button', { name: '应用项目模板' }));
    const af = modal(page, '应用项目模板');
    await choose(page, af, 'template_id', '单机设备订单项目');
    await typeInto(page, af.locator('[id="reason"]'), '按单机设备订单模板建立项目网络', 40);
    const instance = (await save(page, '应用项目模板')).data.result;
    expect(instance.node_count).toBe(3);
    await rec.next();
    await glide(page, drawer(page).getByText('主机#1', { exact: true }).first());
    await rec.next();
    await glide(page, drawer(page).getByText('Gate模板 8', { exact: true }));
    await rec.end();

    await rec.shot('02-3');
    await tap(page, drawer(page).getByRole('button', { name: '维护成员', exact: true }));
    const member = page.getByRole('dialog', { name: '维护项目成员', exact: true });
    await tap(page, member.locator('#user_id'));
    await member.locator('#user_id').pressSequentially(who.username, { delay: 60 });
    await tap(page, page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option').filter({ hasText: who.username }).first());
    await tap(page, member.locator('#role'));
    await tap(page, page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option').filter({ hasText: '只读成员' }).first());
    await tap(page, member.getByRole('button', { name: '保存成员', exact: true }));
    await expect(member).toBeHidden();
    await glide(page, drawer(page).getByText('只读成员', { exact: true }));
    await rec.next();
    await tabUi(page, '需求与治理');
    await tabUi(page, '成员任命');
    await tap(page, drawer(page).getByRole('button', { name: '签发任命书', exact: true }));
    const appt = modal(page, '签发项目成员任命书');
    await setDate(page, appt.locator('#issued_on'), today());
    await typeInto(page, appt.locator('#note'), '项目团队正式任命', 60);
    await tap(page, appt.getByRole('button', { name: /保\s*存/ }));
    await expect(appt).toBeHidden();
    await glide(page, drawer(page).getByRole('button', { name: '查看任命书', exact: true }).first());
    await rec.end();
  },
  '03': async ({ page, reviewer, rec, st, h }) => {
    const { id, who } = st;
    const { command } = h;
    await open(page, id, '需求与治理', '章程');
    await rec.shot('03-1');
    await tap(page, drawer(page).getByRole('button', { name: '编制项目章程', exact: true }));
    const cf = modal(page, '编制项目章程');
    await typeInto(page, cf.locator('[id="title"]'), '智能装配单机项目章程', 60);
    await typeInto(page, cf.locator('[id="objective"]'), '按合同交付一台智能装配单机并通过 SAT', 30);
    await typeInto(page, cf.locator('[id="scope"]'), '主机单元与附件单元的设计, 制造, 测试与现场验收', 22);
    await typeInto(page, cf.locator('[id="success_criteria"]'), 'FAT / SAT 一次通过, 决算不超预算', 26);
    await choose(page, cf, 'sponsor_id', /\/ admin$/);
    await save(page, '编制项目章程');
    await rec.next();
    await tap(page, row(page, '智能装配单机项目章程').getByRole('button', { name: '提交审批', exact: true }));
    await choose(page, modal(page, '提交独立审批'), 'reviewer_id', who.username);
    await save(page, '提交独立审批');
    await glide(page, row(page, '智能装配单机项目章程'));
    await rec.end();

    await open(reviewer, id, '需求与治理', '章程');
    await rec.shot('03-2');
    await tap(reviewer, row(reviewer, '智能装配单机项目章程').getByRole('button', { name: '批准', exact: true }));
    await typeInto(reviewer, modal(reviewer, '批准评审').locator('[id="reason"]'), '目标, 范围与成功标准与合同一致', 45);
    await save(reviewer, '批准评审');
    await glide(reviewer, row(reviewer, '智能装配单机项目章程').getByText('已批准'));
    await rec.end();

    await command('/governance/requirements', { code: 'URS-02', text: '连续运行 8 小时无故障', category: '性能', priority: 'required', owner_id: who.adminId, verification_method: 'test' });
    await command('/governance/requirements', { code: 'URS-03', text: '安全门联锁符合国家标准', category: '安全', priority: 'required', owner_id: who.adminId, verification_method: 'inspection' });
    await open(page, id, '需求与治理', 'URS与追踪');
    await rec.shot('03-3');
    await tap(page, drawer(page).getByRole('button', { name: '新增URS需求', exact: true }));
    const urs = modal(page, '新增URS需求');
    await typeInto(page, urs.locator('[id="code"]'), 'URS-01', 70);
    await typeInto(page, urs.locator('[id="text"]'), '工站节拍不超过 60 秒', 55);
    await choose(page, urs, 'owner_id', /\/ admin$/);
    await choose(page, urs, 'verification_method', '测试');
    await save(page, '新增URS需求');
    await rec.next();
    await glide(page, drawer(page).getByText('已声明验证方式 100%'));
    await rec.end();
    const requirement = (await api(page, 'GET', base(id) + '/governance')).requirements.find(r => r.code === 'URS-01');
    st.requirement = { id: requirement.id };

    const evidence = await command('/governance/documents', { code: 'DEMO-EV', title: '工程验收记录', filename: 'acceptance-record.txt', content: '演示证据: 实测, 检验与签收记录.' });
    st.evidence = { id: evidence.id };
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'pms-video-'));
    const pdfPath = path.join(tmp, 'host-design-review.pdf');
    fs.writeFileSync(pdfPath, makePdf('Host Design Review'));
    await open(page, id, '需求与治理', '证据版本');
    await rec.shot('03-4');
    await tap(page, drawer(page).getByRole('button', { name: '上传证据文件' }));
    const up = modal(page, '上传证据文件');
    await typeInto(page, up.locator('[id="code"]'), 'DR-01', 70);
    await typeInto(page, up.locator('[id="title"]'), '主机设计评审报告', 60);
    await choose(page, up, 'classification', '机密');
    await choose(page, up, 'category', '设计');
    await glide(page, up.locator('#document_file'));
    await up.locator('#document_file').setInputFiles(pdfPath);
    await expect(up.getByText('host-design-review.pdf')).toBeVisible();
    await page.waitForTimeout(500);
    const uploaded = page.waitForResponse(r => r.url().includes('/documents/upload') && r.request().method() === 'POST');
    await tap(page, up.getByRole('button', { name: /^上\s*传$/ }));
    expect((await (await uploaded).json()).code).toBe(200);
    await expect(up).toBeHidden();
    await page.waitForLoadState('networkidle');
    await rec.next();
    await glide(page, row(page, 'DR-01').getByText('机密'));
    await rec.next();
    await tap(page, row(page, 'DR-01').getByRole('button', { name: '预览/下载' }));
    await expect(page.getByText(/服务端复核: 一致/)).toBeVisible();
    await glide(page, page.getByText(/服务端复核: 一致/));
    await rec.end();
    await page.getByRole('dialog').filter({ hasText: '服务端复核' }).getByRole('button', { name: 'Close' }).click();

    await command('/governance/stakeholders', { code: 'SH-02', name: '质量经理', role: '内部质量', category: 'internal', interest: 'high', influence: 'medium', owner_id: who.adminId });
    await command('/governance/stakeholders', { code: 'SH-03', name: '采购接口人', role: '供应链', category: 'internal', interest: 'medium', influence: 'low', owner_id: who.adminId });
    await open(page, id, '需求与治理', 'URS与追踪');
    await rec.shot('03-5');
    await tap(page, drawer(page).getByRole('button', { name: '建立需求追踪', exact: true }));
    const trace = modal(page, '建立需求追踪');
    await choose(page, trace, 'requirement_id', 'URS-01');
    await choose(page, trace, 'target', '工程验收记录');
    await save(page, '建立需求追踪');
    await rec.next();
    await tabUi(page, '干系人与沟通');
    await tap(page, drawer(page).getByRole('button', { name: '登记干系人', exact: true }));
    const sh = modal(page, '登记干系人');
    await typeInto(page, sh.locator('#code'), 'SH-01', 70);
    await typeInto(page, sh.locator('#name'), '客户设备部经理', 60);
    await typeInto(page, sh.locator('#role'), '客户', 60);
    await tap(page, sh.getByRole('button', { name: /保\s*存/ }));
    await expect(sh).toBeHidden();
    await glide(page, row(page, 'SH-01'));
    await rec.end();

    await tabUi(page, 'DQ与局部暂停');
    await rec.shot('03-6');
    await tap(page, drawer(page).getByRole('button', { name: '建立DQ关键任务', exact: true }));
    const dqf = modal(page, '建立DQ关键任务');
    await typeInto(page, dqf.locator('[id="code"]'), 'DQ-01', 70);
    await typeInto(page, dqf.locator('[id="title"]'), '主机 DQ 编制与签认', 50);
    await typeInto(page, dqf.locator('[id="check_titles"]'), '设计输入完整\n图纸编号规范\n关键件选型有依据', 40);
    await choose(page, dqf, 'owner_id', /\/ admin$/);
    await choose(page, dqf, 'deliverable_ids', 'DEMO-EV');
    const dq = (await save(page, '建立DQ关键任务')).data.result;
    await tap(page, row(page, 'DQ-01').getByRole('button', { name: '填写检查' }));
    const dqc = modal(page, '填写DQ检查结果');
    for (const code of ['D1', 'D2', 'D3']) await choose(page, dqc, `passed_${code}`, '检查通过');
    await save(page, '填写DQ检查结果');
    await tap(page, row(page, 'DQ-01').getByRole('button', { name: '提交签认' }));
    await choose(page, modal(page, '提交DQ签认'), 'reviewer_id', who.username);
    await save(page, '提交DQ签认');
    await rec.cut(async () => {
      await mutate(reviewer, id, `/governance/dqs/${dq.id}/decision`, { decision: 'approved', reason: '独立签认' });
      await open(page, id, '需求与治理', 'DQ与局部暂停');
    });
    await glide(page, row(page, 'DQ-01').getByText('3/3'));
    await rec.end();
  },
  '04': async ({ page, reviewer, rec, st, h }) => {
    const { id, who, projectNo } = st;
    const { command, passGate } = h;
    await open(page, id, '计划与执行', '进度卷积');
    await rec.shot('04-1');
    await tap(page, drawer(page).getByRole('button', { name: '派生主/子/单机计划', exact: true }));
    await typeInto(page, modal(page, '派生主/子/单机计划').locator('[id="reason"]'), '按模板阶段层级派生', 50);
    const derived = (await save(page, '派生主/子/单机计划')).data.result;
    expect(derived).toMatchObject({ node_count: 3, main_stage_count: 5, derived_count: 15 });
    await rec.cut(async () => {
      await command('/tasks', { wbs_code: 'SVC-1', name: '现场服务准备', owner_id: who.userId, task_type: 'task', duration_days: 3, start_date: '2026-09-01', stage_code: 'S7' });
      await open(page, id, '计划与执行', 'WBS与排程');
    });
    await glide(page, drawer(page).getByRole('cell', { name: '模板派生', exact: true }).first());
    await glide(page, row(page, `${projectNo}-M1-S4`));
    await rec.end();

    const plan0 = await api(page, 'GET', base(id) + '/planning');
    const m1s4 = plan0.tasks.find(t => t.wbs_code === `${projectNo}-M1-S4`);
    st.m1s4 = { task_id: m1s4.task_id };
    const s1 = plan0.tasks.find(t => t.wbs_code === 'S1-MAIN');
    await rec.shot('04-2');
    await tap(page, row(page, `${projectNo}-M1-S4`).getByRole('button', { name: '编辑', exact: true }));
    await typeInto(page, modal(page, '编辑WBS任务').locator('[id="duration_days"]'), '60', 120);
    await save(page, '编辑WBS任务');
    await rec.next();
    await tabUi(page, '进度卷积');
    await glide(page, drawer(page).getByText('主子约束冲突 1', { exact: true }));
    await glide(page, drawer(page).locator('tbody tr:visible').filter({ hasText: `${projectNo}-M1-S7` }).first());
    await rec.next();
    await tap(page, drawer(page).getByRole('button', { name: '覆盖阶段权重', exact: true }));
    const wf = modal(page, '覆盖阶段权重');
    await typeInto(page, wf.locator('[id="w_S1"]'), '5', 120);
    await typeInto(page, wf.locator('[id="w_S2"]'), '15', 120);
    await typeInto(page, wf.locator('[id="w_S4"]'), '30', 120);
    await typeInto(page, wf.locator('[id="reason"]'), '装配调试是交付关键路径', 45);
    await save(page, '覆盖阶段权重');
    await glide(page, drawer(page).getByText('权重来自项目级覆盖 (模板快照不变)'));
    await rec.end();

    await passGate('requirement-confirm', '需求确认 Gate 检查');
    await passGate('host-summary', '主机汇总 Gate 检查');
    await passGate('attachment-summary', '附件汇总 Gate 检查');
    await open(page, id, '需求与治理', 'Gate评审');
    await rec.shot('04-3');
    await glide(page, drawer(page).getByText('需求确认Gate', { exact: true }).first());
    await rec.next();
    await glide(page, drawer(page).locator('tbody tr:visible').filter({ hasText: '需求确认 Gate 检查' }).first());
    await rec.end();

    await open(page, id);
    await rec.shot('04-4');
    await transition(page, id, '确认立项', '确认立项');
    await transition(page, id, '进入计划', '进入计划阶段');
    await rec.next();
    await tabUi(page, '计划与执行');
    await tabUi(page, '审批与基线');
    await tap(page, drawer(page).getByRole('button', { name: '提交计划审批', exact: true }));
    await typeInto(page, modal(page, '提交计划审批').locator('[id="comment"]'), '主, 子, 单机计划已派生并评审', 40);
    await save(page, '提交计划审批');
    await glide(page, drawer(page).locator('tbody tr:visible').first());
    await rec.end();

    await open(reviewer, id, '计划与执行', '审批与基线');
    await rec.shot('04-5');
    await tap(reviewer, drawer(reviewer).getByRole('button', { name: '批准', exact: true }).first());
    await typeInto(reviewer, modal(reviewer, '批准计划基线').locator('[id="comment"]'), '主子计划一致, 同意作为基线', 45);
    await save(reviewer, '批准计划基线');
    await glide(reviewer, drawer(reviewer).getByText('已批准').first());
    await rec.end();
  },
  '05': async ({ page, reviewer, rec, st, h }) => {
    const { id } = st;
    await open(page, id, '工程交付');
    await rec.shot('05-1');
    await tap(page, drawer(page).getByRole('button', { name: '配置交付要求', exact: true }));
    const cfg = modal(page, '配置交付验收要求');
    await typeInto(page, cfg.locator('#required_survey_visits'), '1', 120);
    await choose(page, cfg, 'pre_ship_conditions', '入库/装箱已确认');
    await typeInto(page, cfg.locator('#handover_deadline_days'), '2', 120);
    await typeInto(page, cfg.locator('#site_lag_days'), '1', 120);
    await typeInto(page, cfg.locator('[id="reason"]'), '按合同约定的现场交付要求', 45);
    await save(page, '配置交付验收要求');
    await rec.next();
    await glide(page, drawer(page).getByText('工勘 1 次'));
    await rec.end();

    await open(page, id);
    await rec.shot('05-2');
    await glide(page, drawer(page).getByRole('button', { name: /进入执行$/ }));
    await rec.next();
    await transition(page, id, '进入执行', '进入执行阶段');
    await glide(page, drawer(page).getByText('执行中', { exact: true }).first());
    await rec.end();

    await tabUi(page, '需求与治理');
    await tabUi(page, '会议行动');
    await rec.shot('05-3');
    await tap(page, drawer(page).getByRole('button', { name: '登记项目会议', exact: true }));
    const mt = modal(page, '登记项目会议');
    await typeInto(page, mt.locator('[id="title"]'), '项目启动会', 70);
    await choose(page, mt, 'meeting_type', '项目启动会');
    await setDate(page, mt.locator('[id="held_on"]'), today());
    await choose(page, mt, 'attendee_ids', /\/ admin$/);
    await choose(page, mt, 'material_ids', 'DEMO-EV');
    await choose(page, mt, 'baseline_id', '计划修订');
    await typeInto(page, mt.locator('[id="minutes"]'), '确认主计划与交付要求, 长周期件提前下单', 30);
    await save(page, '登记项目会议');
    await glide(page, row(page, '项目启动会').getByText(/计划修订 \d+/));
    await rec.end();

    await tabUi(page, '风险与问题');
    await rec.shot('05-4');
    await tap(page, drawer(page).getByRole('button', { name: '登记项目风险', exact: true }));
    const rk = modal(page, '登记项目风险');
    await typeInto(page, rk.locator('[id="title"]'), '长周期件交期风险', 60);
    await typeInto(page, rk.locator('[id="probability"]'), '2', 120);
    await typeInto(page, rk.locator('[id="impact"]'), '3', 120);
    await choose(page, rk, 'owner_id', /\/ admin$/);
    await typeInto(page, rk.locator('[id="mitigation"]'), '提前下单并跟踪供应商进度, 预留替代料', 30);
    await setDate(page, rk.locator('[id="due_date"]'), '2026-10-31');
    await choose(page, rk, 'response_strategy', '减轻');
    await save(page, '登记项目风险');
    await rec.next();
    await glide(page, row(page, '长周期件交期风险'));
    await rec.end();
  },
  '06': async ({ page, reviewer, rec, st, h }) => {
    const { id, who, evidence } = st;
    const { command, passGate, approveDelivery } = h;
    const refs = { task_id: st.m1s4.task_id, requirement_ids: [st.requirement.id] };
    await open(page, id, '工程交付', '备料与BOM');
    await rec.shot('06-1');
    await tap(page, drawer(page).getByRole('button', { name: '新建备料申请', exact: true }));
    const mr = modal(page, '新建备料申请');
    await typeInto(page, mr.locator('[id="code"]'), 'MR-01', 70);
    await typeInto(page, mr.locator('[id="title"]'), '主机长周期件备料', 60);
    await choose(page, mr, 'request_type', '长周期物料');
    await choose(page, mr, 'node_id', '主机#1');
    await choose(page, mr, 'owner_id', /\/ admin$/);
    await choose(page, mr, 'task_id', '主机#1 装配调试');
    await choose(page, mr, 'requirement_ids', 'URS-01');
    await setDate(page, mr.locator('[id="needed_on"]'), '2026-10-10');
    await typeInto(page, mr.locator('[id="items_0_code"]'), 'SRV-01', 60);
    await typeInto(page, mr.locator('[id="items_0_name"]'), '伺服电机', 60);
    await typeInto(page, mr.locator('[id="items_0_quantity"]'), '2', 120);
    await tap(page, mr.getByRole('button', { name: '添加明细行', exact: true }));
    await typeInto(page, mr.locator('[id="items_1_code"]'), 'PLC-01', 60);
    await typeInto(page, mr.locator('[id="items_1_name"]'), 'PLC 控制器', 60);
    await typeInto(page, mr.locator('[id="items_1_quantity"]'), '1', 120);
    const material = (await save(page, '新建备料申请')).data.result;
    await glide(page, row(page, 'MR-01'));
    await rec.end();

    await approveDelivery('material-requests', material);
    const bom = await command('/delivery/boms', { code: 'BOM-01', title: '主机#1 冻结配置', material_request_id: material.id });
    await approveDelivery('boms', bom, 'freeze');
    await command(`/delivery/boms/${bom.id}/kit`, { items: [{ code: 'SRV-01', available_quantity: 2 }, { code: 'PLC-01', available_quantity: 0 }], evidence_ids: [evidence.id] });
    await open(page, id, '工程交付', '备料与BOM');
    await rec.shot('06-2');
    await glide(page, drawer(page).getByText(/整体齐套率 \d+%/));
    await rec.next();
    await tap(page, drawer(page).getByText(/缺件清单 1 行/));
    await glide(page, drawer(page).getByRole('cell', { name: 'PLC-01', exact: true }).first());
    await rec.end();

    await command(`/delivery/boms/${bom.id}/kit`, { items: [{ code: 'SRV-01', available_quantity: 2 }, { code: 'PLC-01', available_quantity: 1 }], evidence_ids: [evidence.id] });
    const assembly = await command('/delivery/assemblies', { ...refs, code: 'ASM-01', title: '主机#1 装配', bom_id: bom.id, owner_id: who.adminId });
    st.assembly = { id: assembly.id };
    await open(page, id, '工程交付', '装配交检');
    await rec.shot('06-3');
    await tap(page, row(page, 'ASM-01').getByRole('button', { name: '登记开工' }));
    await choose(page, modal(page, '登记装配开工'), 'evidence_ids', 'DEMO-EV');
    await save(page, '登记装配开工', 409);
    await expect(modal(page, '登记装配开工').getByRole('alert')).toContainText('阻断关口尚未通过');
    await glide(page, modal(page, '登记装配开工').getByRole('alert'));
    await rec.end();
    await modal(page, '登记装配开工').getByRole('button', { name: /返\s*回/ }).click();

    await passGate('kitting', '零件齐套放行');
    await command(`/delivery/assemblies/${assembly.id}/start`, { evidence_ids: [evidence.id] });
    await open(page, id, '工程交付', '装配交检');
    await rec.shot('06-4');
    await glide(page, row(page, 'ASM-01'));
    await rec.next();
    for (const [label, d, note] of [['上岛', daysAgo(8), '设备上岛'], ['装配', daysAgo(6), '机械与电气装配'], ['单机交检', daysAgo(5), '单机交检合格'], ['连线交检', daysAgo(4), '连线交检合格']]) {
      await tap(page, row(page, 'ASM-01').getByRole('button', { name: '登记步骤' }));
      await setDate(page, modal(page, '登记装配步骤').locator('[id="actual_date"]'), d);
      await typeInto(page, modal(page, '登记装配步骤').locator('[id="note"]'), note, 45);
      await save(page, '登记装配步骤');
      await expect(row(page, 'ASM-01').getByText(`${label} ${d}`)).toBeVisible();
    }
    await glide(page, row(page, 'ASM-01').getByText(`连线交检 ${daysAgo(4)}`));
    await rec.end();
    await approveDelivery('assemblies', assembly);

    await open(page, id, '需求与治理', 'Gate评审');
    await rec.shot('06-5');
    await tap(page, drawer(page).getByRole('button', { name: '发起Gate检查', exact: true }));
    const gf = modal(page, '发起Gate检查');
    await typeInto(page, gf.locator('[id="title"]'), '装配与测试交接检查', 50);
    await choose(page, gf, 'template_id', '装配与测试交接Gate (G5)');
    await choose(page, gf, 'reviewer_id', who.username);
    await save(page, '发起Gate检查');
    await tap(page, row(page, '装配与测试交接检查').getByRole('button', { name: '填写检查', exact: true }));
    const gc = modal(page, '填写Gate检查结果');
    for (const code of ['AT-1', 'AT-2', 'AT-3']) {
      await choose(page, gc, `passed_${code}`, '检查通过');
      await choose(page, gc, `evidence_${code}`, 'DEMO-EV');
    }
    await rec.next();
    await choose(page, gc, 'passed_AT-4', '例外放行 (需说明)');
    await typeInto(page, gc.locator('[id="waiver_AT-4"]'), '测试工装下周到位, 接收人同意先行交接', 35);
    await save(page, '填写Gate检查结果');
    await rec.next();
    await glide(page, drawer(page).getByText('例外 1', { exact: true }));
    await rec.end();
    const atGate = (await api(page, 'GET', base(id) + '/governance')).gates.find(g => g.title === '装配与测试交接检查');
    await command(`/governance/gates/${atGate.id}/submit`, {});
    await command(`/governance/gates/${atGate.id}/decision`, { decision: 'approved', reason: '例外项已登记, 同意交接' }, reviewer);
  },
  '07': async ({ page, reviewer, rec, st, h }) => {
    const { id, who, evidence, assembly } = st;
    const { command, passGate, approveDelivery } = h;
    const refs = { task_id: st.m1s4.task_id, requirement_ids: [st.requirement.id] };
    await open(page, id, '工程交付', '质量试验');
    await rec.shot('07-1');
    await tap(page, drawer(page).getByRole('button', { name: '建立质量试验', exact: true }));
    const sf = modal(page, '建立质量试验');
    await typeInto(page, sf.locator('[id="code"]'), 'SIT-01', 70);
    await typeInto(page, sf.locator('[id="title"]'), '主机#1 SIT', 60);
    await choose(page, sf, 'test_type', 'SIT');
    await choose(page, sf, 'assembly_id', '主机#1 装配');
    await choose(page, sf, 'owner_id', /\/ admin$/);
    await choose(page, sf, 'task_id', '主机#1 装配调试');
    await choose(page, sf, 'requirement_ids', 'URS-01');
    await typeInto(page, sf.locator('[id="criteria_0_title"]'), '节拍不超过 60 秒且安全联锁有效', 35);
    const sit = (await save(page, '建立质量试验')).data.result;
    await glide(page, row(page, 'SIT-01'));
    await rec.end();

    await command(`/delivery/tests/${sit.id}/results`, { checks: [{ code: 'C01', passed: true, actual: '实测节拍 52 秒, 联锁有效', evidence_ids: [evidence.id] }], due_date: '2026-10-15' });
    await approveDelivery('tests', sit);
    const fat = await command('/delivery/tests', { ...refs, code: 'FAT-01', title: '主机#1 FAT', assembly_id: assembly.id, test_type: 'FAT', owner_id: who.adminId, criteria: [{ code: 'F1', title: '客户见证 8 小时连续运行', required: true }] });
    await command(`/delivery/tests/${fat.id}/results`, { checks: [{ code: 'F1', passed: true, actual: '连续运行 8 小时无故障', evidence_ids: [evidence.id] }], due_date: '2026-10-15' });
    await approveDelivery('tests', fat);
    await open(page, id, '工程交付', '质量试验');
    await rec.shot('07-2');
    await glide(page, row(page, 'SIT-01').getByText('已批准'));
    await rec.next();
    await glide(page, row(page, 'FAT-01').getByText('已批准'));
    await rec.end();

    const shipment = await command('/delivery/shipments', { ...refs, code: 'SHIP-01', title: '主机#1 发运', assembly_ids: [assembly.id], consignee: '客户设备部', delivery_address: '客户厂区 A 车间', planned_date: daysAgo(3), reviewer_id: who.userId });
    await open(page, id, '工程交付', '发运签收');
    await rec.shot('07-3');
    await tap(page, row(page, 'SHIP-01').getByRole('button', { name: '申请放行' }));
    await choose(page, modal(page, '提交发运放行'), 'evidence_ids', 'DEMO-EV');
    await save(page, '提交发运放行', 409);
    await glide(page, modal(page, '提交发运放行').getByRole('alert'));
    await rec.next();
    await tap(page, modal(page, '提交发运放行').getByRole('button', { name: /返\s*回/ }));
    await tap(page, row(page, 'SHIP-01').getByRole('button', { name: '发货前条件' }));
    const pc = modal(page, '确认发货前条件');
    await choose(page, pc, 'warehouse_in_confirmed', '已确认');
    await typeInto(page, pc.locator('[id="warehouse_note"]'), 'WMS 入库单 IN-0921', 55);
    await save(page, '确认发货前条件');
    await glide(page, row(page, 'SHIP-01'));
    await rec.end();

    await passGate('fat-confirm', 'FAT 确认放行');
    await approveDelivery('shipments', shipment);
    await command(`/delivery/shipments/${shipment.id}/dispatch`, { shipped_on: daysAgo(3), tracking_no: 'HC-TRACK-0921', evidence_ids: [evidence.id] });
    await command(`/delivery/shipments/${shipment.id}/receipt`, { received_on: daysAgo(2), receiver_name: '客户设备部经理', acceptance: 'accepted', evidence_ids: [evidence.id] }, reviewer);
    await open(page, id, '工程交付', '发运签收');
    await rec.shot('07-4');
    await glide(page, row(page, 'SHIP-01'));
    await rec.next();
    await glide(page, row(page, 'SHIP-01').getByText('已完整接受'));
    await rec.end();
  },
  '08': async ({ page, reviewer, rec, st, h }) => {
    const { id, who, evidence, assembly } = st;
    const { command, passGate, approveDelivery } = h;
    const refs = { task_id: st.m1s4.task_id, requirement_ids: [st.requirement.id] };
    await passGate('handover', '项目交底 Gate 检查');
    await open(page, id, '工程交付', '工勘与现场');
    await rec.shot('08-1');
    await glide(page, row(page, 'HO-SHIP-01'));
    await rec.next();
    await tap(page, row(page, 'HO-SHIP-01').getByRole('button', { name: '完成交底' }));
    const ho = modal(page, '完成项目交底');
    await setDate(page, ho.locator('[id="completed_on"]'), daysAgo(1));
    await typeInto(page, ho.locator('[id="checklist_note"]'), '交底清单 V1: 图纸, 程序, 操作手册, 备件清单', 28);
    await choose(page, ho, 'document_ids', 'DEMO-EV');
    await save(page, '完成项目交底');
    await glide(page, row(page, '现场定位'));
    await rec.end();

    await rec.shot('08-2');
    await tap(page, row(page, '现场定位').getByRole('button', { name: '开始' }));
    await setDate(page, modal(page, '开始现场任务').locator('[id="actual_start"]'), today());
    await save(page, '开始现场任务');
    await tap(page, row(page, '现场定位').getByRole('button', { name: '完成' }));
    const sd = modal(page, '完成现场任务');
    await setDate(page, sd.locator('[id="actual_end"]'), today());
    await typeInto(page, sd.locator('[id="result"]'), '定位完成, 地脚固定', 45);
    await choose(page, sd, 'evidence_ids', 'DEMO-EV');
    await save(page, '完成现场任务');
    await rec.cut(async () => {
      const siteTasks = (await api(page, 'GET', base(id) + '/delivery')).site_tasks.filter(t => t.handover_id).sort((a, b) => a.sequence - b.sequence);
      for (const t of siteTasks.filter(x => x.status !== 'closed')) {
        await command(`/delivery/site-tasks/${t.id}/start`, { actual_start: today() });
        await command(`/delivery/site-tasks/${t.id}/complete`, { actual_end: today(), result: '完成', evidence_ids: [evidence.id] });
      }
      await open(page, id, '工程交付', '工勘与现场');
    });
    await glide(page, row(page, '现场SAT').getByText('已关闭'));
    await rec.end();

    await rec.shot('08-3');
    await tap(page, drawer(page).getByRole('button', { name: '登记工勘任务', exact: true }));
    const sv = modal(page, '登记工勘任务');
    await typeInto(page, sv.locator('[id="code"]'), 'SV-01', 70);
    await typeInto(page, sv.locator('[id="title"]'), '现场工勘', 60);
    await setDate(page, sv.locator('[id="planned_date"]'), daysAgo(12));
    await typeInto(page, sv.locator('[id="deliverable"]'), '现场勘察报告', 55);
    await choose(page, sv, 'owner_id', /\/ admin$/);
    const survey = (await save(page, '登记工勘任务')).data.result;
    await tap(page, row(page, 'SV-01').getByRole('button', { name: '提交工勘确认' }));
    const svs = modal(page, '提交工勘确认');
    await setDate(page, svs.locator('[id="actual_date"]'), daysAgo(12));
    await typeInto(page, svs.locator('[id="findings"]'), '地基, 供电与气源满足安装要求', 35);
    await choose(page, svs, 'evidence_ids', 'DEMO-EV');
    await choose(page, svs, 'reviewer_id', who.username);
    await save(page, '提交工勘确认');
    await rec.cut(async () => {
      await mutate(reviewer, id, `/delivery/surveys/${survey.id}/decision`, { decision: 'approved', reason: '交付物确认' });
      await open(page, id, '工程交付', '工勘与现场');
    });
    await glide(page, row(page, 'SV-01').getByText('已批准'));
    await rec.end();

    const sat = await command('/delivery/tests', { ...refs, code: 'SAT-01', title: '主机#1 SAT', assembly_id: assembly.id, test_type: 'SAT', owner_id: who.adminId, criteria: [{ code: 'S1', title: '现场连续生产 4 小时并由客户确认', required: true }] });
    await command(`/delivery/tests/${sat.id}/results`, { checks: [{ code: 'S1', passed: true, actual: '客户签字确认', evidence_ids: [evidence.id] }], due_date: '2026-10-15' });
    await approveDelivery('tests', sat);
    await open(page, id, '工程交付', '质量试验');
    await rec.shot('08-4');
    await glide(page, row(page, 'SAT-01').getByText('已批准'));
    await rec.end();
  },
  '09': async ({ page, reviewer, rec, st, h }) => {
    const { id, who } = st;
    await open(page, id, '计划与执行', 'WBS与排程');
    await rec.shot('09-1');
    await tap(page, row(page, 'S1-MAIN').getByRole('button', { name: '反馈进度', exact: true }));
    const fb = modal(page, '反馈任务进度');
    await choose(page, fb, 'status', '已完成');
    await typeInto(page, fb.locator('[id="percent_complete"]'), '100', 110);
    await typeInto(page, fb.locator('[id="remaining_days"]'), '0', 110);
    await setDate(page, fb.locator('[id="actual_start"]'), '2026-09-01');
    await setDate(page, fb.locator('[id="actual_end"]'), '2026-09-12');
    await typeInto(page, fb.locator('[id="comment"]'), '设计准备实际完成', 55);
    await save(page, '反馈任务进度');
    await rec.next();
    await glide(page, row(page, 'S1-MAIN').getByRole('cell', { name: '2026-09-01 / 2026-09-12', exact: true }));
    await rec.end();

    const workDate = `2026-09-0${1 + (Date.now() % 9)}`;
    await open(page, id, '实际工时');
    await rec.shot('09-2');
    await tap(page, drawer(page).getByRole('button', { name: '提交实际工时', exact: true }));
    const tf = modal(page, '提交实际工时');
    await choose(page, tf, 'task_id', '设计准备 (主计划)');
    await setDate(page, tf.locator('[id="work_date"]'), workDate);
    await typeInto(page, tf.locator('[id="hours"]'), '2', 120);
    await typeInto(page, tf.locator('[id="note"]'), '设计准备评审', 55);
    await choose(page, tf, 'reviewer_id', who.username);
    await save(page, '提交实际工时');
    await glide(page, row(page, workDate));
    await rec.end();

    await open(reviewer, id, '实际工时');
    await rec.shot('09-3');
    await tap(reviewer, row(reviewer, workDate).getByRole('button', { name: '批准', exact: true }));
    const ta = modal(reviewer, '批准工时单');
    if (await ta.locator('textarea').count()) await typeInto(reviewer, ta.locator('textarea').first(), '工作日与任务核对无误', 45);
    await save(reviewer, '批准工时单');
    await glide(reviewer, row(reviewer, workDate).getByText('已批准'));
    await rec.end();

    await page.goto('/monitor/job');
    await page.waitForLoadState('networkidle');
    const jobRow = page.locator('tbody tr').filter({ hasText: 'PMS进度扫描' }).first();
    await expect(jobRow).toBeVisible();
    await rec.shot('09-4');
    await glide(page, jobRow.getByText('0 0 6 * * ?'));
    await rec.next();
    await tap(page, jobRow.locator('button').filter({ hasText: '执行' }));
    await tap(page, page.locator('.ant-popover:not(.ant-popover-hidden), .ant-popconfirm').getByRole('button', { name: /确\s*定|OK/ }).first());
    await expect.poll(async () => (await api(page, 'GET', base(id) + '/planning')).progress_history.length, { timeout: 60000, intervals: [1000, 2000] }).toBe(1);
    await rec.end();

    await open(page, id, '计划与执行', '进度卷积');
    await rec.shot('09-5');
    await tap(page, drawer(page).getByRole('button', { name: '生成进度快照', exact: true }));
    await save(page, '生成进度快照');
    await glide(page, drawer(page).getByText('BAC 计划总工作日'));
    await rec.next();
    await glide(page, drawer(page).getByText('进度趋势 (快照)'));
    await glide(page, drawer(page).locator('tbody tr:visible').filter({ hasText: today() }).first());
    await rec.end();

    await reviewer.goto('/pms/todo');
    await expect(reviewer.getByRole('heading', { name: '我的待办' })).toBeVisible();
    await reviewer.waitForLoadState('networkidle');
    await rec.shot('09-6');
    await glide(reviewer, reviewer.getByText(/^系统提醒 \d+$/).first());
    await rec.next();
    await glide(reviewer, reviewer.getByText('SVC-1 现场服务准备'));
    await rec.end();
  },
  '10': async ({ page, reviewer, rec, st, h }) => {
    const { id, who, projectNo } = st;
    const { command } = h;
    for (const [kind, name, amount] of [['estimate', '合同概算', '3000'], ['budget', '执行预算', '3200'], ['actual', '期间核算', '3100'], ['settlement', '项目决算', '3150']]) {
      const v = await command('/cost-versions', { kind, period: '2026-09', currency: 'CNY', name, revenue: '10000', reviewer_id: who.userId });
      await command(`/cost-versions/${v.id}/entries`, { category: 'material', label: '材料与外购件', amount });
      await command(`/cost-versions/${v.id}/submit`, {});
      await command(`/cost-versions/${v.id}/review`, { decision: 'approved', reason: '独立审批' }, reviewer);
    }
    await open(page, id, '项目费用');
    await rec.shot('10-1');
    await glide(page, drawer(page).getByText('预算-概算 200.00'));
    await rec.next();
    await glide(page, drawer(page).getByRole('cell', { name: '项目决算', exact: true }).first());
    await rec.end();

    await rec.shot('10-2');
    await tabUi(page, '过程看板');
    await glide(page, drawer(page).getByText('零件齐套', { exact: true }).first());
    await glide(page, drawer(page).getByText('SIT / FAT / SAT', { exact: true }).first());
    await rec.end();

    await page.goto('/pms/portfolio');
    await expect(page.getByRole('heading', { name: '项目组合看板' })).toBeVisible();
    await page.waitForLoadState('networkidle');
    const pr = page.locator('tbody tr').filter({ hasText: projectNo }).first();
    await rec.shot('10-3');
    await glide(page, pr);
    await rec.next();
    await tap(page, pr.locator('.ant-table-row-expand-icon'));
    await glide(page, page.getByRole('cell', { name: '主项目', exact: true }).first());
    await rec.end();

    await page.goto('/pms/search');
    await page.waitForLoadState('networkidle');
    await rec.shot('10-4');
    await typeInto(page, page.getByPlaceholder('输入关键字'), '智能装配单机', 90);
    await tap(page, page.getByRole('button', { name: /^检\s*索$/ }));
    await expect(page.getByText(/检索结果 \d+/)).toBeVisible({ timeout: 30000 });
    await glide(page, page.getByText(/检索结果 \d+/));
    await rec.cut(async () => { await page.goto('/pms/targets'); await page.waitForLoadState('networkidle'); await page.waitForTimeout(400); });
    await glide(page, page.getByRole('heading', { name: '经营目标看板' }));
    await rec.end();

    await open(page, id, '接口运维');
    await rec.shot('10-5');
    await glide(page, drawer(page).getByText('未配置', { exact: true }).first());
    await rec.end();
  },
  '11': async ({ page, reviewer, rec, st, h }) => {
    const { id, who, evidence } = st;
    const { command, passGate } = h;
    const planNow = await api(page, 'GET', base(id) + '/planning');
    for (const t of planNow.tasks.filter(x => x.task_type !== 'summary' && x.status !== 'done')) {
      await command(`/tasks/${t.task_id}/feedback`, { status: 'done', percent_complete: 100, remaining_days: 0, comment: '按计划完成' });
    }
    await passGate('sat-confirm', 'SAT 确认 Gate 检查');
    await open(page, id, '需求与治理', 'Gate评审');
    await rec.shot('11-1');
    await glide(page, drawer(page).getByText('SAT条件与SAT确认Gate (G8)').first());
    await rec.next();
    await tabUi(page, '项目概况');
    await transition(page, id, '进入收尾', '进入收尾阶段');
    await glide(page, drawer(page).getByText('收尾中', { exact: true }).first());
    await rec.end();

    await tabUi(page, '结项与移交');
    await rec.shot('11-2');
    const items = (await api(page, 'GET', base(id) + '/closure')).checks;
    const firstCheck = '交付物清单归档';
    await tap(page, row(page, firstCheck).getByRole('button', { name: '确认完成', exact: true }));
    await choose(page, modal(page, '完成收尾检查'), 'evidence_ref', 'DEMO-EV');
    await typeInto(page, modal(page, '完成收尾检查').locator('[id="comment"]'), '交付物清单已归档', 50);
    await save(page, '完成收尾检查');
    await rec.cut(async () => {
      for (const item of items.filter(x => x.title !== firstCheck && x.status !== 'completed')) {
        await command(`/closure/checks/${item.item_id}/complete`, { evidence_ref: evidence.id, comment: `已核对: ${item.title}` });
      }
      await open(page, id, '结项与移交');
    });
    await tap(page, drawer(page).getByRole('button', { name: '新增移交事项', exact: true }));
    const hf = modal(page, '新增交付移交');
    await typeInto(page, hf.locator('[id="title"]'), '备件清单与操作手册移交客户', 40);
    await choose(page, hf, 'owner_id', /\/ admin$/);
    await setDate(page, hf.locator('[id="due_date"]'), today());
    await save(page, '新增交付移交');
    await tap(page, row(page, '备件清单与操作手册移交客户').getByRole('button', { name: '确认移交', exact: true }));
    const hd = modal(page, '确认交付移交');
    await choose(page, hd, 'evidence_ref', 'DEMO-EV');
    await typeInto(page, hd.locator('[id="comment"]'), '客户设备部签收', 55);
    await save(page, '确认交付移交');
    await tap(page, drawer(page).getByRole('button', { name: '登记项目经验', exact: true }));
    const lf = modal(page, '登记项目经验');
    await typeInto(page, lf.locator('[id="title"]'), '长周期件提前下单', 55);
    await typeInto(page, lf.locator('[id="category"]'), '供应链', 60);
    await typeInto(page, lf.locator('[id="content"]'), '伺服电机交期 10 周, 立项后两周内下单可避免等料', 28);
    await save(page, '登记项目经验');
    await glide(page, drawer(page).getByText('当前结项前置检查已通过.', { exact: true }));
    await rec.end();

    await rec.shot('11-3');
    await tap(page, drawer(page).getByRole('button', { name: '提交关闭审批', exact: true }));
    await choose(page, modal(page, '提交项目关闭审批'), 'reviewer_id', who.username);
    await save(page, '提交项目关闭审批');
    await glide(page, drawer(page).getByRole('heading', { name: '正式关闭审批' }));
    await rec.end();

    await open(reviewer, id, '结项与移交');
    await rec.shot('11-4');
    await tap(reviewer, drawer(reviewer).getByRole('button', { name: '批准关闭', exact: true }));
    await typeInto(reviewer, modal(reviewer, '批准项目关闭').locator('[id="reason"]'), '交付, 质量, 工时, 决算与清单已独立核对', 35);
    await save(reviewer, '批准项目关闭');
    await glide(reviewer, drawer(reviewer).getByRole('heading', { name: '正式关闭审批' }));
    await rec.end();

    await open(page, id);
    await rec.shot('11-5');
    await transition(page, id, '正式关闭', '正式关闭项目');
    await rec.next();
    await glide(page, drawer(page).getByText('项目已结束,当前为只读视图', { exact: true }));
    await rec.end();
  },
};

function checkpoint(no) {
  const db = process.env.PMS_DEMO_DB;
  if (!db) return null;
  const dest = path.join(RAW, `ckpt-${no}.db`);
  // SQLite 在线备份 API: 后端运行中也能得到一致快照.
  execFileSync('python3', ['-c', 'import sqlite3,sys; s=sqlite3.connect(sys.argv[1]); d=sqlite3.connect(sys.argv[2]); s.backup(d); d.close(); s.close()', db, dest]);
  return path.relative(OUT, dest);
}

test.describe('完整业务流程演示视频录屏', () => {
  test.setTimeout(3600000);

  test('同一设备订单项目从平台模板到正式关闭 (录屏 + 时间轴)', async ({ browser }) => {
    const statePath = path.join(OUT, 'state.json');
    const save_state = st => fs.writeFileSync(statePath, JSON.stringify(st, null, 2));
    let st = !process.env.PMS_DEMO_VIDEO_FRESH && fs.existsSync(statePath) ? JSON.parse(fs.readFileSync(statePath, 'utf-8')) : null;
    if (!st || st.done.length === S.chapters.length) {
      fs.rmSync(RAW, { recursive: true, force: true });
      st = { done: [], clips: [], recorded_at: new Date().toISOString() };
    }
    fs.mkdirSync(RAW, { recursive: true });
    // t0 是时间轴零点 (epoch 毫秒); 片段与字幕时刻都相对 t0, compose.py 借助画面里的时间码映射到录像帧.
    const recorded = async () => {
      const context = await browser.newContext({ baseURL: BASE_URL, viewport: VIEWPORT, recordVideo: { dir: RAW, size: VIEWPORT } });
      await context.addInitScript(installCursor);
      await context.addInitScript(installClock);
      const page = await context.newPage();
      return { context, page, t0: Date.now() };
    };

    if (!st.who) {
      // 一次性准备 (不录像): 设备订单模板已发布, 让位强制编码规则, 创建独立审核人.
      const context = await browser.newContext({ baseURL: BASE_URL, viewport: VIEWPORT });
      const page = await context.newPage();
      await login(page);
      st.suffix = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 4)}`;
      st.short = st.suffix.slice(-5).toUpperCase();
      st.projectNo = `DEMO-${st.short}`;
      await equipmentTemplate(page);
      for (const r of (await api(page, 'GET', '/api/pms/config/coding-rule')).rows) {
        if (r.object_type === 'project' && r.status === 'published' && r.enforced) await api(page, 'POST', `/api/pms/config/coding-rule/${r.id}/retire`, { reason: '演示让位' });
      }
      st.who = await createReviewer(page, st.suffix);
      await context.close();
      save_state(st);
    }

    for (const chapter of S.chapters) {
      if (st.done.includes(chapter.no)) continue;
      st.inProgress = { chapter: chapter.no, checkpoint: checkpoint(chapter.no), started_at: new Date().toISOString() };
      save_state(st);
      const A = await recorded();
      const R = await recorded();
      const errors = [];
      A.page.on('pageerror', error => errors.push(error.message));
      R.page.on('pageerror', error => errors.push(error.message));
      await login(A.page);
      await login(R.page, st.who.username, st.who.password);
      const rec = recorder({ admin: A, reviewer: R });
      const videos = { admin: `raw/ch-${chapter.no}-admin.webm`, reviewer: `raw/ch-${chapter.no}-reviewer.webm` };
      try {
        await CHAPTERS[chapter.no]({ page: A.page, reviewer: R.page, rec, st, h: helpers(A.page, R.page, st) });
        expect(errors, `第 ${chapter.no} 章页面错误`).toEqual([]);
      } finally {
        await wait(2500); // 关闭前多录 2.5 秒, 保证最后一个镜头的画面完整写进录像
        await A.context.close();
        await R.context.close();
        for (const [ctx, rel] of [[A, videos.admin], [R, videos.reviewer]]) {
          await ctx.page.video().saveAs(path.join(OUT, rel));
          await ctx.page.video().delete();
        }
      }
      const shots = rec.timeline.clips.map(c => c.shot);
      expect([...new Set(shots)], `第 ${chapter.no} 章镜头须与分镜一致`).toEqual(S.shots.filter(s => s.chapter === chapter.no).map(s => s.id));
      const t0 = { admin: A.t0, reviewer: R.t0 };
      st.clips.push(...rec.timeline.clips.map(c => ({ ...c, video: videos[c.who], t0: t0[c.who] })));
      st.done.push(chapter.no);
      st.inProgress = null;
      save_state(st);
    }

    expect((await (async () => {
      const context = await browser.newContext({ baseURL: BASE_URL });
      const page = await context.newPage();
      await login(page);
      const project = await api(page, 'GET', base(st.id));
      await context.close();
      return project.status;
    })())).toBe('closed');
    expect([...new Set(st.clips.map(c => c.shot))], '录制的镜头须与分镜一一对应').toEqual(S.shots.map(s => s.id));
    const timeline = { viewport: VIEWPORT, recorded_at: st.recorded_at, project_no: st.projectNo, clips: st.clips };
    fs.writeFileSync(path.join(OUT, 'timeline.json'), JSON.stringify(timeline, null, 2));
  });
});
