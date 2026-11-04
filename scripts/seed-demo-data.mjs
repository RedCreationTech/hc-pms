#!/usr/bin/env node
// HC-PMS 演示数据种子: 生成 DEMO-SEED-01..06 六个项目, 覆盖 草稿/立项/计划/执行/收尾/关闭 六个阶段.
//
// 设计:
//   - 幂等: 按 project_no 找到项目后继续按状态补齐 (不是整体跳过), 中断后重跑可续建.
//   - 独立审批: 管理员提交, qa_seed_2026 (演示评审员) 决定, 满足提交/审批职责分离.
//   - 模板: 应用已发布的 TPL-EQUIPMENT 单机设备订单项目模板.
//
// 前置: 后端已启动 (默认 http://localhost:3000), admin/admin123 可登录.
// 用法: node scripts/seed-demo-data.mjs [--base http://localhost:3000]

const args = process.argv.slice(2);
const argValue = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};
const BASE = argValue('--base', process.env.PMS_BASE || 'http://localhost:3000');

// ── 基础工具 ──────────────────────────────────────────────────────────────

const log = (...xs) => console.log(...xs);
const pad = n => String(n).padStart(2, '0');
const fmt = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const today = () => fmt(new Date());
const shift = n => { const d = new Date(); d.setDate(d.getDate() + n); return fmt(d); };
const daysAgo = n => shift(-n);

async function api(method, path, body, token) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let json;
  try { json = await res.json(); } catch { json = { code: res.status, msg: `HTTP ${res.status} 非JSON响应` }; }
  if (json.code !== 200) {
    const err = new Error(`${method} ${path} -> ${json.code}: ${json.msg}`);
    err.detail = json;
    throw err;
  }
  return json.data;
}

const T = { admin: null, rev: null };
const get = (path, token = T.admin) => api('GET', path, undefined, token);
const post = (path, body, token = T.admin) => api('POST', path, body, token);

// 项目级命令: 自动携带当前聚合版本.
async function cmd(pid, suffix, body, token = T.admin) {
  const project = await get(`/api/pms/projects/${pid}`);
  return post(`/api/pms/projects/${pid}${suffix}`, { ...body, version: project.version }, token);
}

// ── 全局准备 ──────────────────────────────────────────────────────────────

const S = { templateId: null, reviewerId: null, adminId: null, deptId: null, memberId: null, memberBid: null };

async function login(username, password) {
  const data = await api('POST', '/api/auth/login', { username, password }, undefined);
  return data.token;
}

async function ensureTemplate() {
  const tpl = await get('/api/pms/config/project-template');
  let row = (tpl.rows || []).find(r => r.code === 'TPL-EQUIPMENT' && r.status === 'published');
  if (!row) {
    const imp = (tpl.rows || []).find(r => r.code === 'TPL-EQUIPMENT');
    if (!imp) await post('/api/pms/config/project-template/import', { code: 'TPL-EQUIPMENT' });
    const again = await get('/api/pms/config/project-template');
    row = (again.rows || []).find(r => r.code === 'TPL-EQUIPMENT');
    if (row.status !== 'published') {
      await post(`/api/pms/config/project-template/${row.id}/publish`, { reason: '演示数据种子发布' });
      const final = await get('/api/pms/config/project-template');
      row = (final.rows || []).find(r => r.code === 'TPL-EQUIPMENT');
    }
  }
  S.templateId = row.id;
  log(`[prep] 模板 TPL-EQUIPMENT ${row.id} (${row.status} v${row.revision})`);
}

async function retireEnforcedCodingRules() {
  const cr = await get('/api/pms/config/coding-rule');
  for (const row of cr.rows || []) {
    if (!row.enforced) continue;
    log(`[prep] 退役强制编码规则 ${row.code}`);
    await post(`/api/pms/config/coding-rule/${row.id}/retire`, { reason: '演示数据不启用强制编码' });
  }
}

async function ensureReviewer() {
  const menus = await get('/api/system/menu');
  const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve',
    'pms:time:approve', 'pms:finance:query', 'pms:finance:approve', 'pms:project:close',
    'pms:project:reopen', 'pms:document:confidential', 'pms:dashboard:query']);
  const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
  const roles = await get('/api/system/role');
  let role = roles.find(r => r.role_key === 'pms_seed_reviewer');
  if (!role) {
    await post('/api/system/role', { role_name: '演示评审员', role_key: 'pms_seed_reviewer', 'menu-ids': menuIds });
    const again = await get('/api/system/role');
    role = again.find(r => r.role_key === 'pms_seed_reviewer');
    log(`[prep] 创建角色 演示评审员 (id=${role.role_id}, menus=${menuIds.length})`);
  }
  const userId = await ensureUser('qa_seed_2026', '演示评审员 王审', [role.role_id]);
  S.reviewerId = userId;
  log(`[prep] 评审员 qa_seed_2026 (user_id=${userId})`);
}

async function ensureUser(userName, nickName, roles) {
  const opts = await get('/api/pms/options');
  const exist = (opts.users || []).find(u => u.user_name === userName);
  if (exist) return exist.user_id;
  await post('/api/system/user', {
    user_name: userName, nick_name: nickName, password: 'E2e!seed2026',
    dept_id: S.deptId, roles, posts: [], status: '0', remark: '演示数据种子',
  });
  const again = await get('/api/pms/options');
  const created = (again.users || []).find(u => u.user_name === userName);
  if (!created) throw new Error(`用户 ${userName} 创建后未出现在选项中`);
  log(`[prep] 创建用户 ${userName} (user_id=${created.user_id})`);
  return created.user_id;
}

async function prep() {
  T.admin = await login('admin', 'admin123');
  const opts = await get('/api/pms/options');
  S.adminId = opts.currentUserId;
  S.deptId = (opts.depts || []).find(d => d.dept_name === '研发部门').dept_id;
  await ensureTemplate();
  await retireEnforcedCodingRules();
  await ensureReviewer();
  S.memberId = await ensureUser('seed_owner_a', '演示负责人 陈工', [2]);
  S.memberBid = await ensureUser('seed_owner_b', '演示参与人 李工', [2]);
  T.rev = await login('qa_seed_2026', 'E2e!seed2026');
  log(`[prep] 完成: dept=${S.deptId} admin=${S.adminId} reviewer=${S.reviewerId} members=${S.memberId},${S.memberBid}`);
}

// ── 状态读取 ──────────────────────────────────────────────────────────────

const project = pid => get(`/api/pms/projects/${pid}`);
const gov = pid => get(`/api/pms/projects/${pid}/governance`);
const plan = pid => get(`/api/pms/projects/${pid}/planning`);
const delivery = pid => get(`/api/pms/projects/${pid}/delivery`);
const finance = pid => get(`/api/pms/projects/${pid}/finance`);
const closure = pid => get(`/api/pms/projects/${pid}/closure`);

async function findProject(no) {
  const list = await get('/api/pms/projects?page=1&size=100');
  return (list.rows || []).find(r => r.project_no === no) || null;
}

// ── 治理辅助 ──────────────────────────────────────────────────────────────

async function evidenceId(pid) {
  const g = await gov(pid);
  const docs = g.documents || [];
  if (docs.length) return docs[0].id;
  const created = await cmd(pid, '/governance/documents', {
    code: 'EV-01', title: '工程验证证据记录', filename: 'evidence-01.txt',
    content: '演示种子证据: 检验, 测试与签收记录的受控文本版本.',
  });
  return created.result.id;
}

async function ensureCharter(pid) {
  const g = await gov(pid);
  const list = g.charters || [];
  if (list.some(c => c.status === 'approved')) return;
  let latest = list.slice().sort((a, b) => (b.revision || 0) - (a.revision || 0))[0];
  if (!latest) {
    const created = await cmd(pid, '/governance/charters', {
      title: '项目章程', objective: '按合同交付合格设备并通过 FAT / SAT 验收',
      scope: '设计, 制造, 测试, 现场验收与收尾移交全过程',
      success_criteria: 'FAT / SAT 一次通过, 决算不超预算, 遗留问题全部移交',
      sponsor_id: S.adminId,
    });
    latest = created.result;
  }
  if (['draft', 'rejected'].includes(latest.status)) {
    await cmd(pid, `/governance/charters/${latest.id}/submit`, { reviewer_id: S.reviewerId });
  }
  const after = await gov(pid);
  const cur = (after.charters || []).slice().sort((a, b) => (b.revision || 0) - (a.revision || 0))[0];
  if (cur.status === 'in_review') {
    await cmd(pid, `/governance/charters/${cur.id}/decision`,
      { decision: 'approved', reason: '章程目标, 范围与成功标准与合同一致' }, T.rev);
  }
}

async function ensureRequirements(pid) {
  const g = await gov(pid);
  const existing = new Set((g.requirements || []).map(r => r.code));
  const specs = [
    ['URS-01', '工站节拍不超过 60 秒', '性能', 'required', 'test'],
    ['URS-02', '连续运行 8 小时无故障', '性能', 'required', 'test'],
    ['URS-03', '安全门联锁符合国家标准', '安全', 'required', 'inspection'],
  ];
  for (const [code, text, category, priority, vm] of specs) {
    if (existing.has(code)) continue;
    await cmd(pid, '/governance/requirements', {
      code, text, category, priority, owner_id: S.memberId, verification_method: vm,
    });
  }
}

async function ensureDocumentsAndTraces(pid) {
  const g = await gov(pid);
  const docs = g.documents || [];
  const specs = [
    ['EV-01', '工程验证证据记录', 'evidence-01.txt', '检验与测试记录: 实测数据与结论.'],
    ['EV-02', '设计评审报告', 'design-review.txt', '设计评审: 评审意见与关闭结论.'],
    ['EV-03', '出厂验收记录', 'fat-record.txt', 'FAT 验收: 客户见证与签认结论.'],
  ];
  for (const [code, title, filename, content] of specs) {
    if (docs.some(d => d.code === code)) continue;
    await cmd(pid, '/governance/documents', { code, title, filename, content });
  }
  const after = await gov(pid);
  const docByCode = Object.fromEntries((after.documents || []).map(d => [d.code, d]));
  const traces = after.traces || [];
  for (const req of after.requirements || []) {
    if (traces.some(t => t.requirement_id === req.id)) continue;
    await cmd(pid, '/governance/traces', {
      requirement_id: req.id, target_kind: 'document', target_id: docByCode['EV-01'].id, relation: 'verifies',
    });
  }
}

async function ensureStakeholdersAndComms(pid) {
  const g = await gov(pid);
  const shSpecs = [
    ['SH-01', '客户项目总监', '客户代表', 'customer', 'high', 'high'],
    ['SH-02', '质量经理', '内部质量把关', 'internal', 'high', 'medium'],
    ['SH-03', '采购接口人', '供应链保障', 'supplier', 'medium', 'low'],
  ];
  for (const [code, name, role, category, interest, influence] of shSpecs) {
    if ((g.stakeholders || []).some(s => s.code === code)) continue;
    await cmd(pid, '/governance/stakeholders', {
      code, name, role, category, interest, influence, owner_id: S.memberId,
    });
  }
  const after = await gov(pid);
  const shByCode = Object.fromEntries((after.stakeholders || []).map(s => [s.code, s]));
  const commSpecs = [
    ['CP-01', '每周项目例会对齐进度与风险', 'meeting', 'weekly', ['SH-01', 'SH-02']],
    ['CP-02', '关键节点以正式报告通报', 'report', 'monthly', ['SH-01', 'SH-03']],
  ];
  for (const [code, objective, channel, frequency, audience] of commSpecs) {
    if ((after.comm_plans || []).some(c => c.code === code)) continue;
    await cmd(pid, '/governance/comm-plans', {
      code, objective, channel, frequency,
      audience: audience.map(c => shByCode[c].id), next_date: shift(14), owner_id: S.memberId,
    });
  }
}

async function ensureMeeting(pid) {
  const g = await gov(pid);
  if ((g.meetings || []).length) return;
  await cmd(pid, '/governance/meetings', {
    title: '项目启动例会', held_on: daysAgo(30),
    minutes: '确认交付范围, 里程碑与双方接口人; 风险与待办已登记跟踪.',
    attendee_ids: [S.adminId, S.memberId, S.reviewerId], meeting_type: 'regular',
  });
}

async function ensureRisksAndIssues(pid) {
  const g = await gov(pid);
  const risks = [
    ['R-01', '长周期伺服电机交期波动', 2, 3, '提前锁定备选供应商并预留安全库存', 'mitigate', 'schedule'],
    ['R-02', '现场土建移交滞后', 2, 2, '与客户确认移交计划并周度跟踪', 'transfer', 'external'],
  ];
  for (const [code, title, probability, impact, mitigation, response, cat] of risks) {
    if ((g.risks || []).some(r => r.title === title)) continue;
    await cmd(pid, '/governance/risks', {
      title, probability, impact, owner_id: S.memberId, mitigation,
      due_date: shift(60), response_strategy: response, risk_category: cat,
    });
  }
  if (!(g.issues || []).length) {
    await cmd(pid, '/governance/issues', {
      title: '来图纸接口尺寸需澄清', severity: 'major', owner_id: S.memberId, due_date: shift(30),
    });
  }
}

async function ensureGate(pid, gateType, title) {
  const g = await gov(pid);
  const t = (g.gate_templates || []).find(x => x.gate_type === gateType);
  if (!t) throw new Error(`项目缺少关口模板 ${gateType}`);
  let gate = (g.gates || []).find(x => x.template_id === t.id);
  if (gate && ['approved', 'waived'].includes(gate.status)) return gate;
  if (!gate) {
    const created = await cmd(pid, '/governance/gates', {
      template_id: t.id, title, reviewer_id: S.reviewerId,
    });
    gate = created.result;
  }
  const ev = await evidenceId(pid);
  if (['draft', 'ready', 'rejected'].includes(gate.status)) {
    const fresh = (await gov(pid)).gates.find(x => x.id === gate.id);
    const checks = (fresh.checks || []).map(c => ({
      code: c.code, passed: true, evidence_ids: c.required ? [ev] : [],
    }));
    await cmd(pid, `/governance/gates/${gate.id}/checks`, { checks });
    await cmd(pid, `/governance/gates/${gate.id}/submit`, {});
  }
  const cur = (await gov(pid)).gates.find(x => x.id === gate.id);
  if (cur.status === 'in_review') {
    await cmd(pid, `/governance/gates/${cur.id}/decision`,
      { decision: 'approved', reason: '独立核验证据版本与检查结论' }, T.rev);
  }
  const done = (await gov(pid)).gates.find(x => x.id === gate.id);
  if (!['approved', 'waived'].includes(done.status)) {
    throw new Error(`关口 ${title} 未能批准 (当前 ${done.status})`);
  }
  return done;
}

// ── 计划辅助 ──────────────────────────────────────────────────────────────

async function leafTasks(pid) {
  const p = await plan(pid);
  return (p.tasks || []).filter(t => t.task_type !== 'summary');
}

async function ensureBaseline(pid) {
  const p = await plan(pid);
  const baselines = p.baselines || [];
  if (!baselines.length) {
    await cmd(pid, '/planning/submit', { comment: '按模板阶段与结构节点派生的计划提请独立审批' });
  }
  const after = await plan(pid);
  const submitted = (after.baselines || []).find(b => b.status === 'submitted');
  if (submitted) {
    await cmd(pid, `/planning/baselines/${submitted.baseline_id}/review`,
      { decision: 'approved', comment: '计划范围, 工期与资源分配复核通过' }, T.rev);
  }
  const final = await plan(pid);
  const ok = (final.baselines || []).some(b => b.status === 'approved');
  if (!ok) throw new Error('计划基线未获得批准');
}

const STAGE_RANK = { draft: 0, initiated: 1, planning: 2, execution: 3, closing: 4, closed: 5 };

async function transition(pid, status, reason) {
  const p = await project(pid);
  if (p.status === status) return;
  const from = STAGE_RANK[p.status];
  const to = STAGE_RANK[status];
  if (from === undefined || to === undefined || from >= to) return; // 已越过或不可达, 由状态机拒绝
  await cmd(pid, '/transition', { status, reason });
  log(`  [status] ${p.status} -> ${status}`);
}

// ── 交付辅助 ──────────────────────────────────────────────────────────────

async function refreshDelivery(pid, collection, code) {
  const d = await delivery(pid);
  return (d[collection] || []).find(x => x.code === code) || null;
}

async function approveWhen(pid, path, rec, statuses, reason = '独立核查实际记录') {
  if (rec && statuses.includes(rec.status)) {
    await cmd(pid, path, { decision: 'approved', reason }, T.rev);
  }
}

async function ensureMaterial(pid) {
  let m = await refreshDelivery(pid, 'material_requests', 'MR-01');
  if (!m) {
    const created = await cmd(pid, '/delivery/material-requests', {
      code: 'MR-01', title: '主机长周期件备料', request_type: 'long_lead',
      owner_id: S.memberId, needed_on: shift(20),
      items: [
        { code: 'SRV-01', name: '伺服电机', quantity: 2, unit: '台' },
        { code: 'PLC-01', name: 'PLC 控制器', quantity: 1, unit: '套' },
      ],
      task_id: (await leafTasks(pid))[0].task_id,
      requirement_ids: (await gov(pid)).requirements.map(r => r.id),
    });
    m = created.result;
  }
  const ev = await evidenceId(pid);
  if (m.status === 'draft') {
    await cmd(pid, `/delivery/material-requests/${m.id}/submit`,
      { reviewer_id: S.reviewerId, evidence_ids: [ev] });
  }
  await approveWhen(pid, `/delivery/material-requests/${m.id}/decision`,
    await refreshDelivery(pid, 'material_requests', 'MR-01'), ['in_review']);
}

async function ensureBomAndKit(pid) {
  const ev = await evidenceId(pid);
  let bom = await refreshDelivery(pid, 'boms', 'BOM-01');
  if (!bom) {
    const material = await refreshDelivery(pid, 'material_requests', 'MR-01');
    const created = await cmd(pid, '/delivery/boms', {
      code: 'BOM-01', title: '主机冻结配置清单', material_request_id: material.id,
    });
    bom = created.result;
  }
  if (['draft', 'rejected'].includes(bom.status)) {
    await cmd(pid, `/delivery/boms/${bom.id}/freeze`, { reviewer_id: S.reviewerId, evidence_ids: [ev] });
  }
  await approveWhen(pid, `/delivery/boms/${bom.id}/decision`,
    await refreshDelivery(pid, 'boms', 'BOM-01'), ['in_review'], '冻结配置复核通过');
  const frozen = await refreshDelivery(pid, 'boms', 'BOM-01');
  if (['frozen', 'partial'].includes(frozen.status)) {
    await cmd(pid, `/delivery/boms/${frozen.id}/kit`, {
      items: (frozen.items || []).map(i => ({ code: i.code, available_quantity: i.quantity })),
      evidence_ids: [ev],
    });
  }
}

async function ensureAssembly(pid) {
  const ev = await evidenceId(pid);
  const task = (await leafTasks(pid))[0];
  const reqs = (await gov(pid)).requirements.map(r => r.id);
  let asm = await refreshDelivery(pid, 'assemblies', 'ASM-01');
  if (!asm) {
    const bom = await refreshDelivery(pid, 'boms', 'BOM-01');
    const created = await cmd(pid, '/delivery/assemblies', {
      code: 'ASM-01', title: '主机装配与交检', bom_id: bom.id, owner_id: S.memberId,
      task_id: task.task_id, requirement_ids: reqs,
    });
    asm = created.result;
  }
  if (['draft', 'rejected'].includes(asm.status)) {
    await cmd(pid, `/delivery/assemblies/${asm.id}/start`, { evidence_ids: [ev] });
  }
  const started = await refreshDelivery(pid, 'assemblies', 'ASM-01');
  const steps = [
    ['on_island', 14, '设备上岛就位'],
    ['assembling', 12, '机械与电气装配完成'],
    ['unit_inspection', 10, '单机交检合格'],
    ['wiring_inspection', 9, '连线交检合格'],
    ['off_island', 8, '下岛进入调试'],
    ['handover', 7, '装配交付测试'],
  ];
  const done = new Set((started.steps || []).map(s => s.step));
  for (const [step, ago, note] of steps) {
    if (done.has(step)) continue;
    await cmd(pid, `/delivery/assemblies/${started.id}/steps`, {
      step, actual_date: daysAgo(ago), note, evidence_ids: [ev],
    });
  }
  const stepped = await refreshDelivery(pid, 'assemblies', 'ASM-01');
  if (stepped.status === 'in_progress') {
    await cmd(pid, `/delivery/assemblies/${stepped.id}/submit`,
      { reviewer_id: S.reviewerId, evidence_ids: [ev] });
  }
  await approveWhen(pid, `/delivery/assemblies/${stepped.id}/decision`,
    await refreshDelivery(pid, 'assemblies', 'ASM-01'), ['in_review'], '装配交检记录复核通过');
}

async function ensureTest(pid, code, testType, assemblyCode, criteria) {
  const ev = await evidenceId(pid);
  const d0 = await delivery(pid);
  const assembly = (d0.assemblies || []).find(a => a.code === assemblyCode);
  const task = (await leafTasks(pid))[0];
  const reqs = (await gov(pid)).requirements.map(r => r.id);
  let test = (d0.tests || []).find(t => t.code === code);
  if (!test) {
    const created = await cmd(pid, '/delivery/tests', {
      code, title: `主机 ${testType} 试验`, assembly_id: assembly.id, test_type: testType,
      owner_id: S.memberId, criteria, task_id: task.task_id, requirement_ids: reqs,
    });
    test = created.result;
  }
  const fresh = async () => (await delivery(pid)).tests.find(t => t.code === code);
  if (['draft', 'rejected'].includes((await fresh()).status)) {
    await cmd(pid, `/delivery/tests/${test.id}/results`, {
      checks: criteria.map(c => ({
        code: c.code, passed: true,
        actual: testType === 'SIT' ? '实测节拍 52 秒, 联锁有效'
          : testType === 'FAT' ? '连续运行 8 小时无故障' : '现场连续生产 4 小时客户签认',
        evidence_ids: [ev],
      })),
      due_date: shift(30),
    });
  }
  const ready = await fresh();
  if (['ready', 'rejected'].includes(ready.status)) {
    await cmd(pid, `/delivery/tests/${ready.id}/submit`,
      { reviewer_id: S.reviewerId, evidence_ids: [ev] });
  }
  await approveWhen(pid, `/delivery/tests/${test.id}/decision`, await fresh(), ['in_review'],
    '试验结果与证据复核通过');
}

async function ensureShipment(pid) {
  const ev = await evidenceId(pid);
  const d = await delivery(pid);
  const assembly = (d.assemblies || []).find(a => a.code === 'ASM-01');
  const task = (await leafTasks(pid))[0];
  const reqs = (await gov(pid)).requirements.map(r => r.id);
  let ship = (d.shipments || []).find(s => s.code === 'SHIP-01');
  if (!ship) {
    const created = await cmd(pid, '/delivery/shipments', {
      code: 'SHIP-01', title: '主机发运交付', assembly_ids: [assembly.id],
      consignee: '客户设备部', delivery_address: '客户厂区 A 车间',
      planned_date: daysAgo(5), reviewer_id: S.reviewerId,
      task_id: task.task_id, requirement_ids: reqs,
    });
    ship = created.result;
  }
  const fresh = async () => (await delivery(pid)).shipments.find(s => s.code === 'SHIP-01');
  if (ship.status === 'draft') {
    await cmd(pid, `/delivery/shipments/${ship.id}/conditions`, {
      warehouse_in_confirmed: true, warehouse_note: '已入库装箱复核', evidence_ids: [ev],
    });
    await cmd(pid, `/delivery/shipments/${ship.id}/submit`, { evidence_ids: [ev] });
  }
  await approveWhen(pid, `/delivery/shipments/${ship.id}/decision`, await fresh(), ['in_review'],
    '发运条件与试验结论复核通过');
  const released = await fresh();
  if (released.status === 'released') {
    await cmd(pid, `/delivery/shipments/${released.id}/dispatch`, {
      shipped_on: daysAgo(4), tracking_no: 'HC-TRACK-SEED06', evidence_ids: [ev],
    });
  }
  const shipped = await fresh();
  if (shipped.status === 'shipped') {
    await cmd(pid, `/delivery/shipments/${shipped.id}/receipt`, {
      received_on: daysAgo(2), receiver_name: '客户设备部经理', acceptance: 'accepted',
      evidence_ids: [ev],
    }, T.rev);
  }
}

// ── 财务辅助 ──────────────────────────────────────────────────────────────

async function ensureCostVersion(pid, kind, name, revenue, entrySpec) {
  const f = await finance(pid);
  let v = (f.cost_versions || []).find(x => x.kind === kind);
  if (!v) {
    const created = await cmd(pid, '/cost-versions', {
      kind, period: '2026-09', currency: 'CNY', name, revenue, reviewer_id: S.reviewerId,
    });
    v = created.result;
  }
  const fresh = async () => (await finance(pid)).cost_versions.find(x => x.kind === kind);
  if (v.status === 'draft') {
    const cur = await fresh();
    if (!(cur.entries || []).length) {
      for (const [category, label, amount] of entrySpec) {
        await cmd(pid, `/cost-versions/${cur.id}/entries`, { category, label, amount });
      }
    }
    await cmd(pid, `/cost-versions/${v.id}/submit`, {});
  }
  await approveWhen(pid, `/cost-versions/${v.id}/review`, await fresh(), ['submitted'],
    '四算口径与明细复核通过');
}

async function ensureTimeEntry(pid, note) {
  const f = await finance(pid);
  if ((f.time_entries || []).length) return;
  const task = (await leafTasks(pid))[0];
  const created = await cmd(pid, '/time-entries', {
    task_id: task.task_id, work_date: daysAgo(6), hours: 6, note, reviewer_id: S.reviewerId,
  });
  await cmd(pid, `/time-entries/${created.result.id}/review`,
    { decision: 'approved', reason: '工时与任务记录一致' }, T.rev);
}

// ── 收尾辅助 ──────────────────────────────────────────────────────────────

async function completeChecks(pid, target) {
  const ev = await evidenceId(pid);
  const c = await closure(pid);
  const open = (c.checks || []).filter(x => x.status !== 'completed');
  let done = (c.checks || []).length - open.length;
  for (const item of open) {
    if (done >= target) break;
    await cmd(pid, `/closure/checks/${item.item_id}/complete`, {
      evidence_ref: ev, comment: `已核对并归档: ${item.title}`,
    });
    done++;
  }
}

async function ensureHandoff(pid) {
  const ev = await evidenceId(pid);
  const c = await closure(pid);
  let handoff = (c.handoffs || [])[0];
  if (!handoff) {
    const created = await cmd(pid, '/closure/handoffs', {
      title: '备件与运维资料移交售后团队', required: true,
      owner_id: S.adminId, due_date: shift(15),
    });
    handoff = created.result;
  }
  if (handoff.status !== 'completed') {
    await cmd(pid, `/closure/handoffs/${handoff.item_id || handoff.id}/complete`, {
      evidence_ref: ev, comment: '备件清单, 运维手册与培训记录已移交售后并签收.',
    });
  }
}

async function ensureLessons(pid, count) {
  const c = await closure(pid);
  const specs = [
    ['长周期件采购前置', '供应链', '长周期件在计划阶段即启动询价锁定, 避免挤压装配窗口.'],
    ['FAT 前置条件清单', '质量', 'FAT 前逐项核对工装, 程序与文档, 一次通过率显著提升.'],
  ];
  for (const [title, category, content] of specs.slice(0, Math.max(0, count - (c.lessons || []).length))) {
    await cmd(pid, '/closure/lessons', { title, category, content, owner_id: S.memberId });
  }
}

async function closeProject(pid) {
  const ev = await evidenceId(pid);
  const pre = await closure(pid);
  await completeChecks(pid, (pre.checks || []).length);
  await ensureHandoff(pid);
  await ensureLessons(pid, 2);
  let c = await closure(pid);
  if ((c.blockers || []).length) {
    throw new Error(`关闭阻塞未清空: ${c.blockers.join(' | ')}`);
  }
  if (!c.approval) {
    await cmd(pid, '/closure/submit', { reviewer_id: S.reviewerId });
  }
  c = await closure(pid);
  if (c.approval && c.approval.status === 'submitted') {
    await cmd(pid, '/closure/review', { decision: 'approved', reason: '关闭依据与收尾清单复核通过' }, T.rev);
  }
  await transition(pid, 'closed', '收尾清单, 决算与交付链全部完成, 独立关闭审批通过');
}

// ── 阶段构建 ──────────────────────────────────────────────────────────────

async function stage1(pid) {
  const p = await project(pid);
  if (!['closed', 'cancelled', 'paused'].includes(p.status)) {
    const members = await get(`/api/pms/projects/${pid}/members`);
    const have = new Set((members.rows || []).map(m => m.user_id));
    for (const [uid, role] of [[S.memberId, 'editor'], [S.memberBid, 'viewer'], [S.reviewerId, 'viewer']]) {
      if (!have.has(uid)) await post(`/api/pms/projects/${pid}/members`, { user_id: uid, role });
    }
  }
  const g = await gov(pid);
  if (!(g.template_instances || []).length) {
    await cmd(pid, '/governance/template-instances', {
      template_id: S.templateId, reason: '按单机设备订单模板建立项目结构与关口',
    });
  }
}

async function stage2(pid) {
  await transition(pid, 'initiated', '立项申请已受理, 进入立项阶段');
  await ensureCharter(pid);
  await ensureRisksAndIssues(pid);
}

async function stage3(pid) {
  await transition(pid, 'planning', '章程已批准, 进入计划阶段编制计划');
  await ensureRequirements(pid);
  await ensureDocumentsAndTraces(pid);
  await ensureStakeholdersAndComms(pid);
  await ensureMeeting(pid);
  const p = await plan(pid);
  const leaf = (p.tasks || []).filter(t => t.task_type !== 'summary');
  if (!leaf.length) {
    await cmd(pid, '/planning/derive', { reason: '按模板阶段层级派生主/子/单机计划' });
  }
  await ensureBaseline(pid);
}

async function stage4(pid) {
  await ensureGate(pid, 'requirement-confirm', '需求确认 Gate');
  await transition(pid, 'execution', '章程, 计划基线与需求确认关口齐备, 进入执行阶段');
  await ensureMaterial(pid);
  await ensureBomAndKit(pid);
  await ensureGate(pid, 'kitting', '零件齐套放行 Gate (G4)');
  await ensureAssembly(pid);
  await ensureGate(pid, 'assembly-test-handover', '装配与测试交接 Gate (G5)');
  await ensureTest(pid, 'SIT-01', 'SIT', 'ASM-01', [
    { code: 'C01', title: '节拍不超过 60 秒且安全联锁有效', required: true },
  ]);
  await ensureTest(pid, 'FAT-01', 'FAT', 'ASM-01', [
    { code: 'F01', title: '客户见证 8 小时连续运行', required: true },
  ]);
  await ensureCostVersion(pid, 'estimate', '项目概算', '100000',
    [['material', '材料与外购件', 42000], ['labor', '人工与制造', 38000]]);
  await ensureCostVersion(pid, 'budget', '项目预算', '96000',
    [['material', '材料与外购件', 40000], ['labor', '人工与制造', 36000]]);
  await ensureCostVersion(pid, 'actual', '核算成本', '92000',
    [['material', '材料与外购件', 38000], ['labor', '人工与制造', 35000]]);
  await ensureTimeEntry(pid, '装配调试与问题闭环');
  // 部分任务推进, 保留收尾空间.
  const p = await plan(pid);
  const leaves = (p.tasks || []).filter(t => t.task_type !== 'summary');
  const active = leaves.slice(0, Math.ceil(leaves.length / 3));
  for (const task of leaves) {
    if (!['pending', 'todo', 'planned', 'in_progress'].includes(task.status) || task.percent_complete > 0) continue;
    if (active.includes(task)) {
      await cmd(pid, `/tasks/${task.task_id}/feedback`, {
        status: 'in_progress', percent_complete: 60, remaining_days: 5,
        comment: '按计划推进中', actual_start: daysAgo(10),
      });
    }
  }
  const progress = await plan(pid);
  if (!(progress.progress_history || []).length) {
    await cmd(pid, '/planning/snapshot', {});
  }
}

async function stage5(pid) {
  const leaves = await leafTasks(pid);
  for (const task of leaves) {
    if (task.status === 'done') continue;
    await cmd(pid, `/tasks/${task.task_id}/feedback`, {
      status: 'done', percent_complete: 100, remaining_days: 0,
      comment: '任务完成并通过验收', actual_start: daysAgo(20), actual_end: daysAgo(3),
    });
  }
  await ensureGate(pid, 'fat-confirm', 'FAT 确认 Gate (G6)');
  await ensureShipment(pid);
  await ensureTest(pid, 'SAT-01', 'SAT', 'ASM-01', [
    { code: 'S01', title: '现场连续生产 4 小时并由客户确认', required: true },
  ]);
  await ensureGate(pid, 'sat-confirm', 'SAT 条件确认 Gate (G8)');
  await transition(pid, 'closing', '全部叶级任务完成且交付链闭环, 进入收尾阶段');
  await completeChecks(pid, 1);
  await ensureHandoff(pid);
  await ensureLessons(pid, 1);
}

async function stage6(pid) {
  await ensureCostVersion(pid, 'settlement', '项目决算', '94000',
    [['material', '材料与外购件结算', 39000], ['labor', '人工与制造结算', 36000], ['other', '管理与其他', 5000]]);
  await closeProject(pid);
}

// ── 主流程 ────────────────────────────────────────────────────────────────

const PROJECTS = [
  { no: 'DEMO-SEED-01', name: '智能视觉检测线项目', start: '2026-11-04', end: '2027-05-31', depth: 1 },
  { no: 'DEMO-SEED-02', name: '自动化装配工作站项目', start: '2026-10-20', end: '2027-04-30', depth: 2 },
  { no: 'DEMO-SEED-03', name: '精密贴标机组项目', start: '2026-09-15', end: '2027-03-31', depth: 3 },
  { no: 'DEMO-SEED-04', name: '工业上下料机器人单元项目', start: '2026-08-03', end: '2027-01-29', depth: 4 },
  { no: 'DEMO-SEED-05', name: '环保涂装生产线项目', start: '2026-06-01', end: '2026-12-18', depth: 5 },
  { no: 'DEMO-SEED-06', name: '物流分拣输送系统项目', start: '2026-04-06', end: '2026-11-27', depth: 6 },
];

const STAGES = [stage1, stage2, stage3, stage4, stage5, stage6];

async function build(spec) {
  let row = await findProject(spec.no);
  if (!row) {
    const created = await post('/api/pms/projects', {
      project_no: spec.no, name: spec.name, project_type: 'equipment',
      dept_id: S.deptId, manager_id: S.adminId, start_date: spec.start, end_date: spec.end,
    });
    row = created;
    log(`[create] ${spec.no} ${spec.name}`);
  } else {
    log(`[resume] ${spec.no} 当前状态 ${row.status}`);
  }
  const pid = row.project_id;
  for (let i = 0; i < spec.depth; i++) {
    log(`  [stage ${i + 1}/${spec.depth}] ${STAGES[i].name}`);
    await STAGES[i](pid);
  }
  const final = await project(pid);
  const c = await closure(pid).catch(() => null);
  log(`  [done] ${spec.no} status=${final.status}${c && c.blockers ? ` blockers=${c.blockers.length}` : ''}`);
}

async function main() {
  const t0 = Date.now();
  await prep();
  for (const spec of PROJECTS) {
    await build(spec);
  }
  log(`\n全部完成, 耗时 ${((Date.now() - t0) / 1000).toFixed(1)}s`);
}

main().catch(err => {
  console.error('\n[失败]', err.message);
  if (err.detail) console.error('[响应]', JSON.stringify(err.detail).slice(0, 500));
  process.exit(1);
});
