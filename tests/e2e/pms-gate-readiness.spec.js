const { test, expect } = require('@playwright/test');
const path = require('node:path');

// Gate 检查就绪度只读派生: admin 建项目 -> 真实HTTP建立含 2 必需 + 1 可选检查的模板与关口实例 ->
// 打开Gate页签, "检查就绪度"列对新建实例显示红色"检查 0/3"与橙色"待满足 R-1, R-2"(必需未满足项),
// 未达就绪不显示"可签核". 界面点"填写检查"把 R-1 选"检查通过", R-2 选"例外放行"(填说明), O-1 选"检查未通过" ->
// 重载后同列翻绿"检查 2/3"+蓝色"豁免 1"+绿色"可签核"(必需项全部通过或豁免, 可提交评审).
// 全程真实点击 + 真实HTTP + 真实浏览器截图, 免迁移读模型仅界面可见, 不改状态机.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/gate-readiness');
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

// 命令写操作: 拉取当前项目版本, 带版本发命令, 返回命令结果记录 (data.result).
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

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
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

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 合成一名具备质量审批权限且加入项目的独立审核人 (关口审核人不得为登记人).
async function reviewerUser(page, suffix, deptId) {
  const menus = await api(page, 'GET', '/api/system/menu');
  const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
  const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `关口审核${suffix}`, role_key: `gtrev_${suffix}`, 'menu-ids': menuIds });
  const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `gtrev_${suffix}`).role_id;
  const name = `gt_rev_${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: name, nick_name: '关口审核人', password: `E2e!${suffix}`,
    dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'Gate 就绪度 E2E 合成审核人' });
  return (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === name).user_id;
}

test.describe('Gate 检查就绪度只读派生浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('新建关口显示待满足必需项 -> 界面填写检查通过后翻可签核', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `GTRM-${suffix}`, name: `关口就绪度验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    const reviewerId = await reviewerUser(page, suffix, deptId);
    await api(page, 'POST', base(id) + '/members', { user_id: reviewerId, role: 'editor' });

    // 建立含 2 必需 + 1 可选检查的模板, 再从模板发起关口实例.
    const gateTitle = `关口就绪度评审-${suffix}`;
    const template = await command(page, id, '/gate-templates', {
      code: `GT-${suffix}`, title: `就绪度模板-${suffix}`, stage: 'execution', required: true,
      checks: [{ code: 'R-1', title: '设计评审记录齐全', required: true },
        { code: 'R-2', title: '关键测试工装到位', required: true },
        { code: 'O-1', title: '附加文档归档', required: false }] });
    await command(page, id, '/gates', { template_id: template.id, title: gateTitle, reviewer_id: reviewerId });

    // 打开Gate页签: 新建实例的"检查就绪度"列应显示待满足必需项且未可签核.
    await open(page, id, '需求与治理', 'Gate评审');
    await expect(row(page, gateTitle).getByText('检查 0/3')).toBeVisible();
    await expect(row(page, gateTitle).getByText('待满足 R-1, R-2')).toBeVisible();
    await expect(row(page, gateTitle).getByText('可签核')).toHaveCount(0);
    await shot(page, 'gate-readiness-1-pending.png');

    // 界面填写检查: R-1 通过, R-2 例外放行(填说明), O-1 未通过(可选项不计入门控).
    await row(page, gateTitle).getByRole('button', { name: '填写检查', exact: true }).click();
    const form = modal(page, '填写Gate检查结果');
    await choose(page, form, 'passed_R-1', '检查通过');
    await choose(page, form, 'passed_R-2', '例外放行');
    await form.locator('#waiver_R-2').fill('工装下周到位, 接收人同意先行放行.');
    await choose(page, form, 'passed_O-1', '检查未通过');
    await shot(page, 'gate-readiness-2-check-dialog.png');
    await save(page, '填写Gate检查结果');

    // 重载: 必需项全部通过或豁免 -> 翻绿可签核, 豁免计数可见.
    await open(page, id, '需求与治理', 'Gate评审');
    await expect(row(page, gateTitle).getByText('检查 2/3')).toBeVisible();
    await expect(row(page, gateTitle).getByText('豁免 1')).toBeVisible();
    await expect(row(page, gateTitle).getByText('可签核')).toBeVisible();
    await expect(row(page, gateTitle).getByText('待满足 R-1, R-2')).toHaveCount(0);
    await shot(page, 'gate-readiness-3-ready.png');

    // 后端读模型回显 (真实HTTP): ready_to_sign=true, blocking_checks 空, gate_waived=1.
    const gov = await api(page, 'GET', base(id) + '/governance');
    const gate = gov.gates.find(g => g.id && g.title === gateTitle);
    expect(gate.ready_to_sign).toBe(true);
    expect(gate.blocking_checks).toEqual([]);
    expect(gate.gate_waived).toBe(1);
    expect(gate.gate_passed).toBe(2);
    expect(gate.gate_total).toBe(3);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
