const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C04 文档归集视图与密级过滤: 界面登记跨阶段/结构/密级证据 -> 修订改变最新版本归集 -> 归集视图按最新版本聚合(修订不重复计, 旧密级计数归零)
// -> 密级过滤表格 -> 真实HTTP GET 回显 document_collection.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c04b');
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

// 通过密级过滤下拉选择密级(该页签唯一 .ant-select).
async function filterBy(page, label) {
  await drawer(page).locator('.ant-select').first().click();
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option', { hasText: label }).click();
  await page.waitForLoadState('networkidle');
}

test.describe('C04 文档归集视图与密级过滤浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('归集视图按最新版本聚合且修订不漂移, 密级过滤表格, GET回显document_collection', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `C04B-${suffix}`, name: `文档归集视图验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    await open(page, id, '需求与治理', '证据版本');

    // 四份文档: A 机密/设计/主机, B 内部(缺省)/设计/无节点, C 公开/无阶段(未归集)/附件, D 内部/装配/主机.
    const codeA = `DOC-A-${suffix}`, codeB = `DOC-B-${suffix}`, codeC = `DOC-C-${suffix}`, codeD = `DOC-D-${suffix}`;
    await registerDoc(page, { code: codeA, filename: 'a.txt', content: '涉密设计正文A\n', classification: '机密', stage: '设计', structure: '主机' });
    await registerDoc(page, { code: codeB, filename: 'b.txt', content: '设计过程正文B\n', stage: '设计' });
    await registerDoc(page, { code: codeC, filename: 'c.txt', content: '公开附件正文C\n', classification: '公开', structure: '附件' });
    await registerDoc(page, { code: codeD, filename: 'd.txt', content: '装配记录正文D\n', stage: '装配', structure: '主机' });

    // 对 DOC-A 产生新修订: 改为 测试/公开/主机, 使其最新版本离开"设计/机密"进入"测试/公开".
    const ws0 = await api(page, 'GET', base(id) + '/governance');
    const a0 = ws0.documents.find(d => d.code === codeA);
    const v0 = (await api(page, 'GET', base(id))).version;
    await api(page, 'POST', `${base(id)}/governance/documents/${a0.id}/revisions`,
      { code: codeA, title: `${codeA} 证据`, filename: 'a.txt', content: '测试阶段公开正文A2\n', classification: 'public', stage: '测试', structure_node: '主机', version: v0 });

    // 重新加载页签, 让归集视图按最新版本刷新.
    await open(page, id, '需求与治理', '证据版本');

    // 归集视图: 最新版本总数与三类聚合(未归集排最后, 机密因修订而归零).
    await expect(drawer(page).getByText('文档归集视图', { exact: true })).toBeVisible();
    await expect(drawer(page).getByText(/最新版本证据\s*4/)).toBeVisible();
    await expect(drawer(page).getByText(/公开\s*2/)).toBeVisible();
    await expect(drawer(page).getByText(/内部\s*2/)).toBeVisible();
    await expect(drawer(page).getByText(/机密\s*0/, { exact: true })).toBeVisible();
    await expect(drawer(page).getByText(/设计\s*·\s*1/)).toBeVisible();
    await expect(drawer(page).getByText(/测试\s*·\s*1/)).toBeVisible();
    // 未归集同时出现在"按阶段"(文档C无阶段)与"按结构节点"(文档B无节点)两处, 各计 1.
    await expect(drawer(page).getByText(/未归集\s*·\s*1/).first()).toBeVisible();
    await expect(drawer(page).getByText(/未归集\s*·\s*1/)).toHaveCount(2);
    await shot(page, 'c04b-1-collection.png');

    // 真实HTTP GET 回显 document_collection, 与界面一致(键为 kebab-case).
    const ws = await api(page, 'GET', base(id) + '/governance');
    const col = ws.document_collection;
    expect(col.total, '最新版本总数应为4').toBe(4);
    const byClass = Object.fromEntries(col['by-classification'].map(x => [x.classification, x.count]));
    expect(byClass, '机密因DOC-A修订应归零').toEqual({ public: 2, internal: 2, confidential: 0 });
    const byStage = Object.fromEntries(col['by-stage'].map(x => [x.key, x.count]));
    expect(byStage).toEqual({ '设计': 1, '测试': 1, '装配': 1, '': 1 });
    const stageKeys = col['by-stage'].map(x => x.key);
    expect(stageKeys[stageKeys.length - 1], '未归集应排最后').toBe('');
    const byNode = Object.fromEntries(col['by-structure-node'].map(x => [x.key, x.count]));
    expect(byNode).toEqual({ '主机': 2, '附件': 1, '': 1 });

    // 密级过滤: 选公开 -> 隐藏内部文档B, 显示公开文档C; 选内部 -> 隐藏公开文档C, 显示文档B.
    // exact 匹配"文档编号"单元格, 避免命中"标题"单元格(其文本含 code 后接" 证据").
    await filterBy(page, '公开');
    await expect(drawer(page).getByText(codeB, { exact: true })).toHaveCount(0);
    await expect(drawer(page).getByText(codeC, { exact: true })).toBeVisible();
    await shot(page, 'c04b-2-filter-public.png');
    await filterBy(page, '内部');
    await expect(drawer(page).getByText(codeC, { exact: true })).toHaveCount(0);
    await expect(drawer(page).getByText(codeB, { exact: true })).toBeVisible();

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
