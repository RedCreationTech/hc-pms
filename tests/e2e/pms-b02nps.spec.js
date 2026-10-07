const { test, expect } = require('@playwright/test');
const path = require('node:path');

// B02/B16/B12 延伸: 结构节点暂停与复工概览 只读派生洞察 (免迁移, 无新命令/新kind/新路由/不构成门控).
// 界面在"局部暂停"台账基础上, 新增只读概览面板: 按 node-pause 台账最新记录聚合仍暂停/已复工节点数,
// 中断时项目状态分布, 最长暂停与平均复工时长(整天粒度), 并点名仍暂停节点.
// 造法: 建主项目->两子项目(SUB-A/SUB-B)->一单机(MACHINE-1 挂 SUB-A); 界面局部暂停三节点, 再恢复 SUB-B.
// 只读派生不改变任何暂停/复工状态; 单上下文即可闭环(暂停与复工均在 admin 单一上下文完成, 无独立审批门控), 属规则B的B列可标记事实.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/b02nps');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
// shared/panel 渲染 <section>, 按面板标题(heading)定位到具体面板, 避免跨面板文本串台.
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

// 概览面板在抽屉自身滚动容器内靠下位置, window.scrollTo 无效 -> 滚动进视图后对面板元素截图, 确保聚合内容被真实捕获.
async function shotPanel(page, panelLoc, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await panelLoc.scrollIntoViewIfNeeded();
  await panelLoc.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 界面局部暂停: 点击"局部暂停", 选结构节点(按 code 过滤), 填暂停原因, 保存.
async function pauseNode(page, code, reason) {
  await drawer(page).getByRole('button', { name: '局部暂停', exact: true }).click();
  const form = modal(page, '局部暂停单机/子项目');
  await choose(page, form, 'node_id', code);
  await form.locator('#reason').fill(reason);
  return save(page, '局部暂停单机/子项目');
}

// 界面恢复: 在暂停台账定位含 code 的行, 点该行"恢复", 填重排影响说明, 保存.
async function resumeNode(page, code, impactNote) {
  const row = drawer(page).locator('tr').filter({ hasText: code }).filter({ hasText: '暂停中' }).first();
  await row.getByRole('button', { name: '恢复', exact: true }).click();
  const form = modal(page, '恢复节点执行');
  await form.locator('#impact_note').fill(impactNote);
  return save(page, '恢复节点执行');
}

test.describe('B02 结构节点暂停与复工概览只读洞察浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('概览面板聚合暂停中/已复工/类型与状态分布, 只读派生不改变暂停与复工状态', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `NPS-${suffix}`, name: `节点暂停复工概览验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 结构树: 主项目根 -> 两子项目 SUB-A / SUB-B, SUB-A 下挂一单机 MACHINE-1.
    const nodes = await api(page, 'GET', base(id) + '/nodes');
    const mainId = nodes.rows.find(n => n.node_type === 'main').node_id;
    const codeA = `SUBA-${suffix}`;
    const codeB = `SUBB-${suffix}`;
    const codeM = `MCH1-${suffix}`;
    const nodeA = await api(page, 'POST', base(id) + '/nodes', { parent_id: mainId, node_type: 'sub', node_code: codeA, name: '子项目A' });
    await api(page, 'POST', base(id) + '/nodes', { parent_id: mainId, node_type: 'sub', node_code: codeB, name: '子项目B' });
    await api(page, 'POST', base(id) + '/nodes', { parent_id: nodeA.node_id, node_type: 'machine', node_code: codeM, name: '单机M1' });

    // 打开治理抽屉 DQ与局部暂停 页签, 界面局部暂停三节点.
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    await pauseNode(page, codeA, '子项目A等料停工, 暂停执行.');
    await pauseNode(page, codeB, '子项目B图纸变更, 暂停执行.');
    await pauseNode(page, codeM, '单机M1现场不具备安装条件, 暂停执行.');
    // 恢复子项目B (其暂停记录由 active 就地翻转为 closed), 仍暂停 SUB-A(子项目) 与 MACHINE-1(单机).
    await resumeNode(page, codeB, '图纸变更已闭环, 满足复工条件.');

    // 概览面板初态: 暂停记录总数 3, 暂停中 2 条 / 2 节点, 已复工 1, 最长暂停 0 天(实时暂停).
    const cov = panel(page, '结构节点暂停与复工概览');
    await expect(cov).toBeVisible();
    await expect(cov.getByText(/暂停记录总数\s*3/)).toBeVisible();
    await expect(cov.getByText(/暂停中\s*2\s*条\s*\/\s*2\s*节点/)).toBeVisible();
    await expect(cov.getByText(/已复工\s*1/)).toBeVisible();
    await expect(cov.getByText(/最长暂停\s*0\s*天/)).toBeVisible();
    // 类型分布: 子项目 1, 单机 1; 状态分布: 仍暂停两条按中断时项目状态归类.
    await expect(cov.getByText('暂停中按节点类型', { exact: false })).toBeVisible();
    await expect(cov.getByText(/子项目\s*1/)).toBeVisible();
    await expect(cov.getByText(/单机\s*1/)).toBeVisible();
    await expect(cov.getByText('暂停中按中断时项目状态', { exact: false })).toBeVisible();
    // 仍暂停节点点名: 出现 SUB-A / MACHINE-1, 不出现已复工的 SUB-B.
    await expect(cov.getByText('仍暂停节点', { exact: false })).toBeVisible();
    await expect(cov.getByText(new RegExp(`${codeA}`))).toBeVisible();
    await expect(cov.getByText(new RegExp(`${codeM}`))).toBeVisible();
    await expect(cov.getByText(new RegExp(`${codeB}`))).toHaveCount(0);
    await shotPanel(page, cov, 'b02nps-1-pause-summary-panel.png');

    // 真实HTTP读模型回显 node_pause_summary 聚合 (与界面面板同源).
    const ws = await api(page, 'GET', base(id) + '/governance');
    const s = ws.node_pause_summary;
    expect(s.available, '有暂停记录即应可用').toBe(true);
    expect(s.total, '分母=暂停台账记录数').toBe(3);
    expect(s.active, '仍暂停条数').toBe(2);
    expect(s.resumed, '已复工条数').toBe(1);
    expect(s['active-nodes'], '仍暂停去重节点数').toBe(2);
    expect(s['by-node-type'], '仍暂停按类型: 子项目1, 单机1').toEqual({ machine: 1, sub: 1 });
    expect(Object.values(s['by-project-status']).reduce((a, b) => a + b, 0), '仍暂停按中断时项目状态分布合计=仍暂停条数').toBe(2);
    expect(s['longest-active-days'], '实时暂停整天粒度=0').toBe(0);
    expect(s['avg-resume-days'], '实时复工整天粒度=0').toBe(0);
    const activeCodes = s['active-pauses'].map(p => p['node-code']).sort();
    expect(activeCodes, '仍暂停节点点名=A,M (B已复工)').toEqual([codeA, codeM].sort());

    // 只读派生不改变任何暂停记录状态: SUB-B 仍 closed, SUB-A/MACHINE-1 仍 active.
    const byCode = {};
    ws.node_pauses.forEach(p => { byCode[p.node_code] = p.status; });
    expect(byCode[codeA], '子项目A仍暂停').toBe('active');
    expect(byCode[codeM], '单机M仍暂停').toBe('active');
    expect(byCode[codeB], '子项目B已复工(就地翻转closed)').toBe('closed');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
