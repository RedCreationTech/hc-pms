const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H12cc 承诺成本闭环汇总 (只读派生) 浏览器验收:
//  单上下文可见事实(计入矩阵 B 列): 空台账占位提示 -> 界面登记一条草稿后闭环面板实时刷新为"承诺总数1/草稿1/待处理1/审批完成率0%/本位总额0.00" ->
//  经真实 HTTP 追加 提交(待审批) + 取消(已取消) 状态 -> 面板按状态计数与审批完成率(仅"已取消"算已作决定)刷新 ->
//  真实 GET /finance 回显 commitment_closure 与界面同源一致, 且不改变任何逐条承诺状态(只读派生).
//  需要独立审批人第二上下文的翻转(批准->已批准, 转实付->已释放, 由此产生非零 base/released/release-pct)按约定不进 B 列,
//  仅作为额外证据由合成审批人上下文驱动后截图呈现.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h12cc');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const closurePanel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '承诺成本闭环汇总', exact: true }) });

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

async function command(page, method, url, data, expected = 200) {
  const d = await api(page, method, url, data, expected);
  return { result: d.result, version: d.project_version };
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
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await input.press('Escape');
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
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0, { timeout: 8000 });
  await closurePanel(page).scrollIntoViewIfNeeded();
  await closurePanel(page).screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function registerCommitment(page, id, code, gross, reviewerName) {
  await drawer(page).getByRole('button', { name: '登记承诺', exact: true }).click();
  const form = modal(page, '登记承诺');
  await form.locator('#code').fill(code);
  await choose(page, form, 'kind', '采购');
  await form.locator('#supplier').fill(`供应商-${code}`);
  await choose(page, form, 'currency', 'CNY');
  await form.locator('#gross').fill(gross);
  await choose(page, form, 'base_currency', 'CNY');
  await form.locator('#exchange_rate').fill('1');
  await form.locator('#description').fill('承诺闭环验收登记.');
  await choose(page, form, 'reviewer_id', reviewerName);
  return (await save(page, '登记承诺')).result;
}

test.describe('H12cc 承诺成本闭环汇总只读验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('空态 -> 界面登记草稿实时刷新 -> 提交/取消派生只读汇总 -> 独立批准转实付翻转 -> GET 同源回显', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:finance:query', 'pms:finance:approve']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    await api(page, 'POST', '/api/system/role', { role_name: `承诺闭环审核${suffix}`, role_key: `cc_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `cc_${suffix}`).role_id;

    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const reviewerName = `cc_${suffix}`;
    const reviewerPwd = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: reviewerName, nick_name: '承诺闭环审核人', password: reviewerPwd,
      dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'H12cc E2E合成审批人' });
    const reviewerId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === reviewerName).user_id;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H12CC-${suffix}`, name: `承诺闭环验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: reviewerId, role: 'editor' });

    // 1) 空态: 闭环面板给出占位提示 (单上下文可见).
    await open(page, id, '项目费用', '承诺与预算控制');
    await expect(closurePanel(page)).toBeVisible();
    await expect(closurePanel(page).getByText('尚无承诺登记')).toBeVisible();
    await shot(page, 'h12cc-1-empty.png');

    // 2) 界面登记一条草稿 -> 面板实时刷新为总数1/草稿1/待处理1/完成率0/本位总额0.00 (单上下文可见, 尚无已批准或已释放).
    const a = await registerCommitment(page, id, `CC-A-${suffix}`, '200.00', reviewerName);
    expect(a.status).toBe('draft');
    await open(page, id, '项目费用', '承诺与预算控制');
    await expect(closurePanel(page).getByText('承诺总数 1', { exact: true })).toBeVisible();
    await expect(closurePanel(page).getByText('草稿 1', { exact: true })).toBeVisible();
    await expect(closurePanel(page).getByText('本位承诺总额 0.00 元 / 已转实付 0.00 元 / 剩余未释放 0.00 元')).toBeVisible();
    await expect(closurePanel(page).getByText('释放闭环率 0%', { exact: true })).toBeVisible();
    await shot(page, 'h12cc-2-draft.png');

    // 3) 真实 HTTP 追加待审批与已取消: B 提交(待审批), C 登记后取消(已取消).
    const b = (await command(page, 'POST', base(id) + '/commitments', { version: (await api(page, 'GET', base(id))).version,
      kind: 'purchase', code: `CC-B-${suffix}`, supplier: '供应商-B', currency: 'CNY', gross: '70.00', reviewer_id: reviewerId })).result;
    await command(page, 'POST', `${base(id)}/commitments/${b.id}/submit`, { version: (await api(page, 'GET', base(id))).version, baseline: 'budget' });
    const c = (await command(page, 'POST', base(id) + '/commitments', { version: (await api(page, 'GET', base(id))).version,
      kind: 'purchase', code: `CC-C-${suffix}`, supplier: '供应商-C', currency: 'CNY', gross: '10.00', reviewer_id: reviewerId })).result;
    await command(page, 'POST', `${base(id)}/commitments/${c.id}/cancel`, { version: (await api(page, 'GET', base(id))).version, reason: '登记错误' });

    await open(page, id, '项目费用', '承诺与预算控制');
    await expect(closurePanel(page).getByText('承诺总数 3', { exact: true })).toBeVisible();
    await expect(closurePanel(page).getByText('草稿 1', { exact: true })).toBeVisible();
    await expect(closurePanel(page).getByText('审批中 1', { exact: true })).toBeVisible();
    await expect(closurePanel(page).getByText('已取消 1', { exact: true })).toBeVisible();
    await expect(closurePanel(page).getByText('共 3 条承诺, 已作决定 1 条, 待处理 2 条, 审批完成率 33%', { exact: true })).toBeVisible();
    // 尚无已批准或已释放 -> 金额聚合为 0, 只读不门控. (去掉 exact 以容忍行尾空格)
    await expect(closurePanel(page).getByText('本位承诺总额 0.00 元 / 已转实付 0.00 元 / 剩余未释放 0.00 元')).toBeVisible();
    await shot(page, 'h12cc-3-mixed-pending.png');

    // 4) 真实 GET /finance 回显 commitment_closure, 界面与后端同源一致.
    let fin = await api(page, 'GET', base(id) + '/finance');
    expect(fin.commitment_closure.total).toBe(3);
    expect(fin.commitment_closure.draft).toBe(1);
    expect(fin.commitment_closure.submitted).toBe(1);
    expect(fin.commitment_closure.cancelled).toBe(1);
    expect(fin.commitment_closure.processed).toBe(1);
    expect(fin.commitment_closure['review-pct']).toBe(33);
    expect(fin.commitment_closure['base-amount']).toBe('0.00');
    expect(fin.commitment_closure['release-pct']).toBe(0);
    // 只读派生不改变逐条承诺状态.
    expect(fin.commitments.find(x => x.id === a.id).status).toBe('draft');
    expect(fin.commitments.find(x => x.id === b.id).status).toBe('submitted');
    expect(fin.commitments.find(x => x.id === c.id).status).toBe('cancelled');

    // 5) [需第二审批人上下文, 按约定不进 B 列] 合成审批人批准 D 与 E, admin 部分/全额转实付 -> 非零 base/released/release-pct 与徽标翻转, 仅作额外证据截图.
    const d = (await command(page, 'POST', base(id) + '/commitments', { version: (await api(page, 'GET', base(id))).version,
      kind: 'purchase', code: `CC-D-${suffix}`, supplier: '供应商-D', currency: 'CNY', gross: '200.00', reviewer_id: reviewerId })).result;
    await command(page, 'POST', `${base(id)}/commitments/${d.id}/submit`, { version: (await api(page, 'GET', base(id))).version, baseline: 'budget' });
    const e = (await command(page, 'POST', base(id) + '/commitments', { version: (await api(page, 'GET', base(id))).version,
      kind: 'purchase', code: `CC-E-${suffix}`, supplier: '供应商-E', currency: 'CNY', gross: '100.00', reviewer_id: reviewerId })).result;
    await command(page, 'POST', `${base(id)}/commitments/${e.id}/submit`, { version: (await api(page, 'GET', base(id))).version, baseline: 'budget' });

    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3100', viewport: { width: 1600, height: 1000 } });
    const reviewer = await context.newPage();
    reviewer.on('pageerror', error => errors.push(error.message));
    await login(reviewer, reviewerName, reviewerPwd);
    await command(reviewer, 'POST', `${base(id)}/commitments/${d.id}/review`, { version: (await api(reviewer, 'GET', base(id))).version, decision: 'approved', reason: '独立核对批准.' });
    await command(reviewer, 'POST', `${base(id)}/commitments/${e.id}/review`, { version: (await api(reviewer, 'GET', base(id))).version, decision: 'approved', reason: '独立核对批准.' });

    // admin 部分转实付 D 50.00 (保持已批准), 全额转实付 E 100.00 (转已释放).
    await command(page, 'POST', `${base(id)}/commitments/${d.id}/release`, { version: (await api(page, 'GET', base(id))).version, amount: '50.00' });
    await command(page, 'POST', `${base(id)}/commitments/${e.id}/release`, { version: (await api(page, 'GET', base(id))).version, amount: '100.00' });

    await open(page, id, '项目费用', '承诺与预算控制');
    await expect(closurePanel(page).getByText('承诺总数 5', { exact: true })).toBeVisible();
    await expect(closurePanel(page).getByText('已批准 1', { exact: true })).toBeVisible();
    await expect(closurePanel(page).getByText('已释放 1', { exact: true })).toBeVisible();
    await expect(closurePanel(page).getByText('共 5 条承诺, 已作决定 3 条, 待处理 2 条, 审批完成率 60%', { exact: true })).toBeVisible();
    await expect(closurePanel(page).getByText('本位承诺总额 300.00 元 / 已转实付 150.00 元 / 剩余未释放 150.00 元')).toBeVisible();
    await expect(closurePanel(page).getByText('释放闭环率 50%', { exact: true })).toBeVisible();
    await shot(page, 'h12cc-4-released-flip.png');

    fin = await api(page, 'GET', base(id) + '/finance');
    expect(fin.commitment_closure.total).toBe(5);
    expect(fin.commitment_closure.approved).toBe(1);
    expect(fin.commitment_closure.released).toBe(1);
    expect(fin.commitment_closure['review-pct']).toBe(60);
    expect(fin.commitment_closure['base-amount']).toBe('300.00');
    expect(fin.commitment_closure['released-amount']).toBe('150.00');
    expect(fin.commitment_closure['remaining-amount']).toBe('150.00');
    expect(fin.commitment_closure['release-pct']).toBe(50);

    await context.close();
    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
