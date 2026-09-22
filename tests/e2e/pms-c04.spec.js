const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C04 文档密级/阶段/结构节点归集追踪: 界面登记带密级的证据->台账显示密级与阶段->读模型回显字段; 非法密级经真实HTTP被拒; 缺省记为内部.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c04');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
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

// 登记证据文档; classification 为下拉(公开/内部/机密), stage/structure 为文本.
async function registerDoc(page, { code, filename, content, classification, stage, structure }) {
  const title = '登记证据文档';
  await drawer(page).getByRole('button', { name: title, exact: true }).click();
  const form = modal(page, title);
  await form.locator('#code').fill(code);
  await form.locator('#title').fill(`${code} 证据`);
  await form.locator('#filename').fill(filename);
  if (classification) {
    await form.locator('.ant-form-item', { hasText: '密级' }).first().locator('.ant-select').click();
    await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option-content', { hasText: classification }).click();
  }
  if (stage) await form.locator('#stage').fill(stage);
  if (structure) await form.locator('#structure_node').fill(structure);
  await form.locator('#content').fill(content);
  return save(page, title);
}

test.describe('C04 文档密级与阶段归集浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('界面登记带密级的证据并归集追踪, 非法密级经真实HTTP被拒', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `C04-${suffix}`, name: `文档密级归集验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 进入 需求与治理 -> 证据版本.
    await open(page, id, '需求与治理', '证据版本');

    // 文档A: 机密 / 设计 / 主机-控制柜; 文档B: 未选密级(缺省内部).
    const codeA = `DOC-A-${suffix}`;
    const codeB = `DOC-B-${suffix}`;
    await registerDoc(page, { code: codeA, filename: 'design-a.txt', content: '涉密设计正文A\n', classification: '机密', stage: '设计', structure: '主机/控制柜' });
    await registerDoc(page, { code: codeB, filename: 'process-b.txt', content: '过程记录正文B\n', stage: '验证' });

    // 读模型回显: 密级/阶段/结构节点随不可变版本持久化.
    const ws = await api(page, 'GET', base(id) + '/governance');
    const a = ws.documents.find(d => d.code === codeA);
    const b = ws.documents.find(d => d.code === codeB);
    expect(a.classification, '文档A密级应为机密').toBe('confidential');
    expect(a.stage).toBe('设计');
    expect(a.structure_node).toBe('主机/控制柜');
    expect(b.classification, '未选密级应缺省为内部').toBe('internal');
    expect(b.stage).toBe('验证');

    // 台账界面显示密级与阶段列.
    await expect(drawer(page).getByText('机密', { exact: true }).first()).toBeVisible();
    await expect(drawer(page).getByText('内部', { exact: true }).first()).toBeVisible();
    await expect(drawer(page).getByText('设计', { exact: true }).first()).toBeVisible();
    await shot(page, 'c04-1-classified-documents.png');

    // 真实HTTP拒绝路径: 非法密级枚举 400.
    const version = (await api(page, 'GET', base(id))).version;
    const badCode = await page.evaluate(async ({ id, version, suffix }) => {
      const token = localStorage.getItem('ruoyi_token');
      const r = await fetch(`/api/pms/projects/${id}/governance/documents`, { method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ code: `BAD-${suffix}`, title: '非法密级', filename: 'x.txt', content: '正文', classification: 'top-secret', version }) });
      return (await r.json()).code;
    }, { id, version, suffix });
    expect(badCode, '非法密级应被拒').toBe(400);

    // 修订保持编号不可改, 新版本可独立设定密级(此处经真实HTTP验证编号不可改).
    const revCode = await page.evaluate(async ({ id, version, rid }) => {
      const token = localStorage.getItem('ruoyi_token');
      const r = await fetch(`/api/pms/projects/${id}/governance/documents/${rid}/revisions`, { method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ code: 'CHANGED', title: '改码', filename: 'x.txt', content: '正文', classification: 'public', version }) });
      return (await r.json()).code;
    }, { id, version, rid: a.id });
    expect(revCode, '修订不得改变业务编号').toBe(400);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
