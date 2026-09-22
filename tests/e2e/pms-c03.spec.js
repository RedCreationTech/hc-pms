const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C03 URS追踪矩阵与缺链检查: 界面登记证据文档 + API 登记需求/追踪链后打开"URS与追踪"页,
// 追踪矩阵按需求最新版本逐行显示设计满足数/验证证据数与缺链标签, 汇总整链齐备/缺链计数,
// 未追踪需求显示"缺设计满足"+"缺验证证据", 已满足+已验证需求显示"追踪完整";
// GET 治理读模型回显 traceability/trace_summary 与界面一致.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c03');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const matrixRow = (page, code, marker) =>
  drawer(page).locator('tbody tr:visible').filter({ hasText: code }).filter({ hasText: marker }).first();
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

// 携带当前项目版本发起一次治理写命令, 返回 data.result.
async function cmd(page, id, segment, body) {
  const proj = await api(page, 'GET', base(id));
  return (await api(page, 'POST', `${base(id)}/governance/${segment}`, { ...body, version: proj.version })).result;
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

async function registerDocument(page, code, filename, content) {
  await drawer(page).getByRole('button', { name: '登记证据文档', exact: true }).click();
  const form = modal(page, '登记证据文档');
  await form.locator('#code').fill(code);
  await form.locator('#title').fill(`${code} 追踪证据`);
  await form.locator('#filename').fill(filename);
  await form.locator('#content').fill(content);
  return (await save(page, '登记证据文档')).result.id;
}

test.describe('C03 URS追踪矩阵缺链检查浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('界面追踪矩阵按需求版本显示缺链与汇总, 读模型回显一致', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `C03-${suffix}`, name: `URS追踪矩阵验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 1) 证据版本页签界面登记两份真实文本证据: 设计满足证据 + 验证证据.
    await open(page, id, '需求与治理', '证据版本');
    const designDoc = await registerDocument(page, `DESIGN-${suffix}`, 'design.txt', '设计说明: 架构与接口定义满足需求. ');
    const testDoc = await registerDocument(page, `TEST-${suffix}`, 'test-report.txt', '验证报告: SIT 用例通过, 覆盖验收标准.');

    // 2) API 登记两条需求, REQ-1 建立满足+验证双向追踪, REQ-2 保持未追踪.
    const req1 = `RS-${suffix}-1`;
    const req2 = `RS-${suffix}-2`;
    const r1 = await cmd(page, id, 'requirements', { code: req1, text: '需求一: 支持治理追踪矩阵', category: '功能', priority: 'required', owner_id: adminId });
    const r2 = await cmd(page, id, 'requirements', { code: req2, text: '需求二: 支持缺链提醒', category: '功能', priority: 'desired', owner_id: adminId });
    await cmd(page, id, 'traces', { requirement_id: r1.id, target_kind: 'document', target_id: designDoc, relation: 'satisfies' });
    await cmd(page, id, 'traces', { requirement_id: r1.id, target_kind: 'document', target_id: testDoc, relation: 'verifies' });

    // 3) 读模型回显: traceability + trace_summary 与预期一致.
    const gov = await api(page, 'GET', base(id) + '/governance');
    const t1 = gov.traceability.find(r => r.code === req1);
    const t2 = gov.traceability.find(r => r.code === req2);
    expect(t1, 'REQ-1 应出现在追踪矩阵').toBeTruthy();
    expect(t1.missing, 'REQ-1 已满足+已验证, 无缺链').toEqual([]);
    expect(t1.design_links).toBe(1);
    expect(t1.verification_links).toBe(1);
    expect(t2.missing.sort(), 'REQ-2 缺设计满足与验证证据').toEqual(['satisfies', 'verifies']);
    expect(gov.trace_summary).toMatchObject({ requirements: 2, 'fully-traced': 1, 'missing-design': 1, 'missing-verification': 1 });

    // 4) 打开"URS与追踪"页签, 界面矩阵显示汇总与逐需求缺链标签.
    await open(page, id, '需求与治理', 'URS与追踪');
    await expect(drawer(page).getByText('URS追踪完整性检查')).toBeVisible();
    await expect(drawer(page).getByText('需求版本 2')).toBeVisible();
    await expect(drawer(page).getByText('整链齐备 1')).toBeVisible();
    await expect(matrixRow(page, req1, '追踪完整')).toBeVisible();
    await expect(matrixRow(page, req2, '缺设计满足')).toBeVisible();
    await expect(matrixRow(page, req2, '缺验证证据')).toBeVisible();
    await shot(page, 'c03-1-traceability-matrix.png');

    // 5) 修订 REQ-1 产生新版本后, 新版本尚未追踪, 矩阵应回到双缺链, 整链齐备归零.
    await cmd(page, id, `requirements/${r1.id}/revisions`, { code: req1, text: '需求一(修订): 补充追踪矩阵验收标准', category: '功能', priority: 'required', owner_id: adminId });
    const gov2 = await api(page, 'GET', base(id) + '/governance');
    const t1v2 = gov2.traceability.find(r => r.code === req1);
    expect(t1v2.revision, '应指向最新修订版本').toBe(2);
    expect(t1v2.missing.sort(), '修订产生的新版本尚未追踪, 应重新提示双缺链').toEqual(['satisfies', 'verifies']);
    expect(gov2.trace_summary['fully-traced'], '整链齐备应归零').toBe(0);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
