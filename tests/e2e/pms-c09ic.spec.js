const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C09 延伸: 问题闭环率与严重度分布只读派生汇总 (免迁移, 无新命令/新kind/新路由/不构成门控).
// 界面"风险与问题"页签登记 6 条问题 (open/逾期open/in_review/closed/rejected/blocker), 其中 closed/in_review/rejected
// 经真实 HTTP resolve + 独立审批人 decision 推进 -> 只读"问题闭环与严重度分布汇总"面板按最新有效版本聚合:
// 问题总数/已闭环率/待处理/验证中/已驳回/逾期未关闭/未关闭阻断级及按严重度分布; 只读派生不改变问题状态, 与"问题升级处置汇总"面板正交.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c09ic');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
// shared/panel 渲染 <section>, 按面板标题(heading)定位到具体面板, 避免"问题总数 N"跨面板文本串台.
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) });
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

async function mutateData(page, id, suffix, data = {}) {
  const project = await api(page, 'GET', base(id));
  return api(page, 'POST', base(id) + suffix, { ...data, version: project.version });
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
  const input = form.locator(`#${key}`);
  await input.click();
  if (typeof text === 'string' && await input.isEditable()) await input.fill(text);
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await form.locator(`#${key}`).press('Escape');
  await page.waitForLoadState('networkidle');
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

// 闭环汇总面板在抽屉自身滚动容器内靠下位置, window.scrollTo 无效 -> 先滚动进视图再对面板元素截图, 确保翻转内容被真实捕获.
async function shotPanel(page, panelLoc, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await panelLoc.scrollIntoViewIfNeeded();
  await panelLoc.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

const sevLabel = { blocker: '阻断', major: '严重', minor: '一般' };

// 界面"登记项目问题": 走真实表单, 返回命令结果 (记录在 result 下).
async function registerIssue(page, id, { title, severity, due }) {
  await open(page, id, '需求与治理', '风险与问题');
  await drawer(page).getByRole('button', { name: '登记项目问题', exact: true }).click();
  const form = modal(page, '登记项目问题');
  await form.locator('#title').fill(title);
  await choose(page, form, 'severity', sevLabel[severity]);
  await choose(page, form, 'owner_id', 'admin');
  await form.locator('#due_date').fill(due);
  return save(page, '登记项目问题');
}

async function makeApprover(page, id, suffix, deptId) {
  const menus = await api(page, 'GET', '/api/system/menu');
  const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
  const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `问题闭环审批${suffix}`, role_key: `iss_clo_${suffix}`, 'menu-ids': menuIds });
  const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `iss_clo_${suffix}`).role_id;
  const approverName = `issclo_${suffix}`;
  const approverPwd = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: approverName, nick_name: '问题闭环独立验证人', password: approverPwd,
    dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'C09 问题闭环 E2E 合成独立验证人' });
  const approverId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === approverName).user_id;
  await api(page, 'POST', base(id) + '/members', { user_id: approverId, role: 'viewer' });
  return { approverName, approverPwd, approverId };
}

test.describe('C09 问题闭环率与严重度分布只读汇总浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('闭环汇总面板聚合闭环率/未关闭构成/严重度分布, 独立验证人推进闭环后面板翻转且问题状态不漂移', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const suffix = serial();
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `ICLO-${suffix}`, name: `问题闭环汇总验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    const { approverName, approverPwd, approverId } = await makeApprover(page, id, suffix, deptId);

    // 证据文档: 供提交解决时引用.
    const evidence = (await mutateData(page, id, '/governance/documents',
      { code: `ICLO-${suffix}`, title: '闭环整改复验记录', filename: 'close.txt', content: '问题整改与独立复验证据已归档.' })).result;

    // 界面登记 6 条问题: A major在办, B major逾期, C minor待验证, D minor待闭环, E minor将被驳回, F blocker在办.
    const A = `待处理一般项-${suffix}`;
    const B = `逾期未关闭-${suffix}`;
    const C = `验证中-${suffix}`;
    const D = `已闭环-${suffix}`;
    const E = `验证驳回-${suffix}`;
    const F = `阻断未闭环-${suffix}`;
    const rA = await registerIssue(page, id, { title: A, severity: 'major', due: '2099-12-31' });
    const rB = await registerIssue(page, id, { title: B, severity: 'major', due: '2020-01-10' });
    const rC = await registerIssue(page, id, { title: C, severity: 'minor', due: '2099-12-31' });
    const rD = await registerIssue(page, id, { title: D, severity: 'minor', due: '2099-12-31' });
    const rE = await registerIssue(page, id, { title: E, severity: 'minor', due: '2099-12-31' });
    const rF = await registerIssue(page, id, { title: F, severity: 'blocker', due: '2099-12-31' });

    // 初态: 全部 open. 面板应显闭环率 0%, 待处理 6, 逾期 1, 未关闭阻断级 1, 严重度 阻断·1 严重·2 一般·3.
    await open(page, id, '需求与治理', '风险与问题');
    const cl = panel(page, '问题闭环与严重度分布汇总');
    await expect(cl).toBeVisible();
    await expect(cl.getByText(/问题总数\s*6/)).toBeVisible();
    await expect(cl.getByText(/已闭环\s*0%/)).toBeVisible();
    await expect(cl.getByText(/待处理\s*6/)).toBeVisible();
    await expect(cl.getByText(/逾期未关闭\s*1/)).toBeVisible();
    await expect(cl.getByText(/未关闭阻断级\s*1/)).toBeVisible();
    await expect(cl.getByText(/严重\s*·\s*2/)).toBeVisible();
    await expect(cl.getByText(/一般\s*·\s*3/)).toBeVisible();
    // 描述文案含"验证中/已驳回"字样, 故初态负向断言用带计数的标签文本(此时不应出现"验证中 N"/"已驳回 N"标签).
    await expect(cl.getByText(/验证中\s*1/)).toHaveCount(0);
    await expect(cl.getByText(/已驳回\s*1/)).toHaveCount(0);
    await shotPanel(page, cl, 'c09ic-1-initial-open.png');

    // 真实HTTP推进生命周期 (界面"提交解决证据/批准/驳回"入口在台账, 但闭环面板只呈现聚合; 与 C09d 门控事实同源, 用真实 HTTP 打路由):
    // C/D/E 由登记人提交解决 -> in_review; D 独立验证人批准 -> closed; E 独立验证人驳回 -> rejected; C 停留 in_review.
    for (const rid of [rC.result.id, rD.result.id, rE.result.id]) {
      await mutateData(page, id, `/governance/issues/${rid}/resolve`,
        { resolution: '已按根因整改并复测', reviewer_id: approverId, evidence_ids: [evidence.id] });
    }

    // 独立验证人第二真实上下文亲自核验: 批准 D -> closed, 驳回 E -> rejected.
    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const verifier = await context.newPage();
    verifier.on('pageerror', error => errors.push(error.message));
    await login(verifier, approverName, approverPwd);
    await mutateData(verifier, id, `/governance/issues/${rD.result.id}/decision`, { decision: 'approved', reason: '独立复验通过.' });
    await mutateData(verifier, id, `/governance/issues/${rE.result.id}/decision`, { decision: 'rejected', reason: '证据不足, 暂不关闭.' });
    await context.close();

    // 回到登记人视角重载面板: 闭环率 17%(1/6), 待处理 3, 验证中 1, 已驳回 1, 逾期仍 1, 未关闭阻断级仍 1.
    await open(page, id, '需求与治理', '风险与问题');
    await expect(cl.getByText(/已闭环\s*17%/)).toBeVisible();
    await expect(cl.getByText(/待处理\s*3/)).toBeVisible();
    await expect(cl.getByText(/验证中\s*1/)).toBeVisible();
    await expect(cl.getByText(/已驳回\s*1/)).toBeVisible();
    await expect(cl.getByText(/逾期未关闭\s*1/)).toBeVisible();
    await expect(cl.getByText(/未关闭阻断级\s*1/)).toBeVisible();
    await expect(cl.getByText(/阻断\s*·\s*1/)).toBeVisible();
    await shotPanel(page, cl, 'c09ic-2-mixed-closure.png');

    // 真实HTTP读模型回显 issue_closure, 且只读派生不改变任何逐条问题状态/项目聚合版本.
    const ws = await api(page, 'GET', base(id) + '/governance');
    const s = ws.issue_closure;
    expect(s.available, '有问题时 available=true').toBe(true);
    expect(s.total, '分母=最新有效版本问题数').toBe(6);
    expect(s.closed, '已闭环 1').toBe(1);
    expect(s.open, '未关闭 5').toBe(5);
    expect(s.pending, '待处理(open) 3').toBe(3);
    expect(s['in-review'], '验证中 1').toBe(1);
    expect(s.rejected, '已驳回 1').toBe(1);
    expect(s.overdue, '逾期未关闭 1').toBe(1);
    expect(s['blocker-open'], '未关闭阻断级 1').toBe(1);
    expect(s['closure-pct'], '闭环率 round(100/6)=17').toBe(17);
    expect(s['by-severity'].find(x => x.severity === 'major').count, 'major 2').toBe(2);
    expect(s['by-severity'].find(x => x.severity === 'minor').count, 'minor 3').toBe(3);
    const dRow = ws.issues.find(r => r.title === D);
    const eRow = ws.issues.find(r => r.title === E);
    const fRow = ws.issues.find(r => r.title === F);
    expect(dRow.status, '闭环问题状态由流程推进, 非汇总改写').toBe('closed');
    expect(eRow.status).toBe('rejected');
    expect(fRow.escalated, '阻断级问题升级态与闭环汇总正交').toBe(true);
    const ver = await api(page, 'GET', base(id));
    expect(ws.project_version, '治理读模型回显当前聚合版本').toBe(ver.version);

    // 空项目: 新建成项目未登记问题 -> 面板显"暂无项目问题"占位.
    const empty = await api(page, 'POST', '/api/pms/projects', { project_no: `ICLE-${suffix}`, name: `问题闭环汇总空态 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    await open(page, empty.project_id, '需求与治理', '风险与问题');
    const emptyPanel = panel(page, '问题闭环与严重度分布汇总');
    await expect(emptyPanel.getByText(/暂无项目问题/)).toBeVisible();
    const ew = await api(page, 'GET', base(empty.project_id) + '/governance');
    expect(ew.issue_closure.available, '空项目 available=false').toBe(false);
    expect(ew.issue_closure.total).toBe(0);
    expect(ew.issue_closure['closure-pct'], '空集闭环率 0').toBe(0);
    await shotPanel(page, emptyPanel, 'c09ic-3-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
