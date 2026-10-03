const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H08 延伸: 风险复审频率 (review_frequency) 可选枚举字段 + 复评按声明节奏自动顺延下次复审日期 (免迁移, 写路径带行为效果).
// 界面"登记项目风险"里可选声明复审频率(每周/双周/每月/每季度) -> 命令响应回显英文枚举 -> 台账"复审频率"列中文标签徽标;
// 未选显示"未设定"; 非法枚举经真实HTTP 400. 复评"提交风险复评"对话框 hint 提示留空自动顺延;
// 真实HTTP: 对声明"每月"的风险提交复评且不手填下次复审日期 -> 200 且 result.next_review_date == 今天+30 (闭合 H08 复审频率声明与实际节奏);
// 对未声明频率的风险不手填日期提交复评 -> 400 (零回归, 仍要求下次复审日期).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const addDays = n => { const d = new Date(); d.setDate(d.getDate() + n);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; };
const output = path.resolve(__dirname, '../../reports/h08rf');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;

// test.use 必须置于文件顶层 (放进 describe 会强制新 worker 报错).
test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

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

// 同表单多个 antd Select 的下拉面板会同时留在 DOM, 用 combobox 自身 aria-controls 过滤其下拉避免串台.
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

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 界面"登记项目风险": 2x3=6 不触发升级; 若给定 frequencyLabel 则再选复审频率.
async function registerRisk(page, { title, frequencyLabel }) {
  await drawer(page).getByRole('button', { name: '登记项目风险', exact: true }).click();
  const form = modal(page, '登记项目风险');
  await form.locator('#title').fill(title);
  await form.locator('#probability').fill('2');
  await form.locator('#impact').fill('3');
  await choose(page, form, 'owner_id', 'admin');
  await form.locator('#mitigation').fill('持续监控并按节奏复评.');
  await form.locator('#due_date').fill('2026-10-20');
  if (frequencyLabel) await choose(page, form, 'review_frequency', frequencyLabel);
}

test.describe('H08 延伸 风险复审频率声明与自动顺延浏览器验收', () => {
  test.setTimeout(180000);

  test('界面声明复审频率入台账列回显中文, 复评留空按声明节奏自动顺延, 未声明仍要求日期, 非法枚举被拒', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    // 合成一个具备质量审批权限且加入项目的独立审批人 (复评 reviewer_id 须独立于登记人 admin, 且持 pms:quality:approve).
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    await api(page, 'POST', '/api/system/role', { role_name: `复审频率审批${suffix}`, role_key: `rfs_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `rfs_${suffix}`).role_id;
    const approverName = `rfs_${suffix}`;
    const approverPwd = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: approverName, nick_name: '复审频率独立审批人', password: approverPwd,
      dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'H08 复审频率 E2E 合成独立审批人' });
    const approverId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === approverName).user_id;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `RFQ-${suffix}`, name: `风险复审频率验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: approverId, role: 'viewer' });

    // 界面登记: 一条声明"每月"复审频率 (截图保存前对话框, 证明该可选枚举字段), 一条不声明频率.
    const monthlyTitle = `供应稳定性风险-${suffix}`;
    await open(page, id, '需求与治理', '风险与问题');
    await registerRisk(page, { title: monthlyTitle, frequencyLabel: '每月' });
    await shot(page, 'h08rf-1-dialog-frequency.png');
    const monthly = await save(page, '登记项目风险');
    expect(monthly.result.review_frequency, '命令响应回显英文枚举值').toBe('monthly');
    expect(monthly.result.escalated, '2x3=6 不触发升级').toBe(false);
    const monthlyId = monthly.result.id;

    const plainTitle = `常规观察风险-${suffix}`;
    await registerRisk(page, { title: plainTitle });
    const plain = await save(page, '登记项目风险');
    expect(plain.result.review_frequency == null, '未声明频率不含该键').toBeTruthy();
    const plainId = plain.result.id;

    // 读模型原样返回持久化字段.
    const ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.risks.find(r => r.id === monthlyId).review_frequency, '读模型回显频率').toBe('monthly');
    expect(ws.risks.find(r => r.id === plainId).review_frequency == null, '未声明读模型为空').toBeTruthy();

    // 台账"复审频率"列: 声明过的行回显中文标签"每月", 未声明行显示"未设定".
    await open(page, id, '需求与治理', '风险与问题');
    await expect(drawer(page).locator('.ant-tag', { hasText: '每月' }).first()).toBeVisible();
    await expect(drawer(page).locator('.ant-table-cell', { hasText: '未设定' }).first()).toBeVisible();
    await shot(page, 'h08rf-2-ledger-column.png');

    // "提交风险复评"对话框 hint 针对声明了频率的风险提示"留空自动顺延".
    await row(page, monthlyTitle).getByRole('button', { name: '提交复评', exact: true }).click();
    const reviewForm = modal(page, '提交风险复评');
    await expect(reviewForm).toBeVisible();
    await expect(reviewForm.getByText(/留空将按该风险声明的每月复审频率从今天自动顺延/)).toBeVisible();
    await shot(page, 'h08rf-3-review-hint.png');
    await reviewForm.getByRole('button', { name: /返\s*回/ }).click();
    await expect(reviewForm).toBeHidden();

    // 真实证据文档 (复评 evidence_ids 必填, 须同项目 document).
    let docVersion = (await api(page, 'GET', base(id))).version;
    const doc = await api(page, 'POST', base(id) + '/governance/documents',
      { code: `RF-EVID-${suffix}`, title: '复评依据记录', filename: '复评.txt', content: ' 独立复评证据\n', version: docVersion });
    const evidenceId = doc.result.id;

    // 自动顺延核心证明: 对声明"每月"的风险提交复评, 不手填下次复审日期 -> 200, 且 result.next_review_date == 今天+30.
    let v = (await api(page, 'GET', base(id))).version;
    const advanced = await api(page, 'POST', `${base(id)}/governance/risks/${monthlyId}/review`,
      { outcome: 'active', review_note: '按声明节奏自动排期', reviewer_id: approverId, evidence_ids: [evidenceId], version: v });
    expect(advanced.result.next_review_date, '留空时按每月节奏从今天顺延30天').toBe(addDays(30));

    // 零回归门控: 未声明频率的风险不手填下次复审日期 -> 400 (仍要求下次复审日期).
    v = (await api(page, 'GET', base(id))).version;
    await api(page, 'POST', `${base(id)}/governance/risks/${plainId}/review`,
      { outcome: 'active', review_note: '缺下次复审日期', reviewer_id: approverId, evidence_ids: [evidenceId], version: v }, 400);

    // 非法复审频率经真实HTTP被白名单校验拒绝.
    v = (await api(page, 'GET', base(id))).version;
    await api(page, 'POST', base(id) + '/governance/risks', { title: `非法频率-${suffix}`, probability: 2, impact: 3,
      owner_id: adminId, mitigation: 'm', due_date: '2026-10-20', review_frequency: 'yearly', version: v }, 400);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
