const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H10 延伸: DQ 未通过的必需检查项"落实整改行动"(写命令, 复用行动类型与既有完成/转任务生命周期, 免迁移, payload 存字段)
//   + DQ 台账"整改情况"只读派生列 + 汇总"已落实整改/整改未完成"计数 + 门控.
// 真实HTTP建交付件+建 DQ(两个必需项均未通过 -> 草稿) ->
//   界面"落实整改"按钮打开对话框 -> 指定 R-2 + 责任人 admin + 到期日 -> 保存生成一条开放整改行动(来源 DQ + source_check_code R-2) ->
//   重载台账"整改情况"列显示金色"整改中", 汇总"已落实整改 1"/"整改未完成 1";
//   真实HTTP把该整改行动转为WBS任务(converted) -> 重载台账"整改完成", 汇总"整改未完成"消失(remediation-open 0);
//   真实HTTP读模型回显: dq.dq_remediation_total/state + action.source_dq_id/source_check_code + dq_summary.remediated/remediation-open, 与界面一致;
//   防御性门控(真实HTTP): 无未通过必需项的 DQ -> 409, 非法 check_code -> 400, 缺到期日 -> 400, 跨项目 DQ -> 404 (写入前抛出不产生记录).
// 全程真实点击 + 真实HTTP + 真实浏览器截图, 免迁移写路径 + 只读派生均界面可见.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h10dqra');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) });
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

// 命令写操作: 拉取当前项目版本, 带版本发命令. 返回命令结果记录 (data.result).
async function command(page, id, suffix, data = {}, expected = 200) {
  const project = await api(page, 'GET', base(id));
  const result = await api(page, 'POST', base(id) + '/governance' + suffix, { ...data, version: project.version }, expected);
  return result && result.result;
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
}

async function fill(form, values) {
  for (const [key, value] of Object.entries(values)) await form.locator(`#${key}`).fill(String(value));
}

async function save(page, title, expected = 200) {
  const form = modal(page, title);
  const response = page.waitForResponse(r => r.url().includes('/api/pms/') && ['POST', 'PUT', 'DELETE'].includes(r.request().method()));
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(expected);
  if (expected === 200) { await expect(form).toBeHidden(); await page.waitForLoadState('networkidle'); }
  return body;
}

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function shotCard(page, card, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

test.describe('H10 延伸 DQ 检查不通过落实整改行动浏览器验收', () => {
  test.setTimeout(180000);

  test('未通过必需项 -> 界面落实整改 -> 台账整改情况列与汇总计数 -> 转任务完成 -> 门控', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `RA-${suffix}`, name: `DQ整改落实验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    const doc = await command(page, id, '/documents', { code: `RADC-${suffix}`, title: `交付件-${suffix}`, filename: '交付.txt', content: '确定版本交付件\n' });

    // 真实HTTP建 DQ: 两个必需项, 全部未通过 -> 草稿, dq_required_missing=2, 出现"落实整改"按钮.
    // 注意: 命令写响应 data.result 是【原始存储记录】, 不含只读派生键 (dq_required_missing 仅在 /governance 读模型里算),
    // 故须回读工作区并按 id 找到该 DQ 再断言派生键.
    const dqTitle = `H10整改主任务-${suffix}`;
    const dq = await command(page, id, '/dqs', { code: `RADQ-${suffix}`, title: dqTitle, owner_id: adminId,
      checklist: [{ code: 'R-1', title: '首件检验', required: true }, { code: 'R-2', title: '绝缘耐压', required: true }],
      deliverable_ids: [doc.id] });
    const dqAfterCreate = (await api(page, 'GET', base(id) + '/governance')).dqs.find(x => x.id === dq.id);
    expect(dqAfterCreate.dq_required_missing, '初始两个必需项均未通过').toBe(2);

    // 防御性门控 (真实HTTP, 写入前抛出 -> 不产生记录):
    // (1) 无未通过必需项的 DQ (仅可选项, 仍草稿) -> 409.
    const naDq = await command(page, id, '/dqs', { code: `RANA-${suffix}`, title: `无必需项-${suffix}`, owner_id: adminId,
      checklist: [{ code: 'O', title: '仅可选', required: false }], deliverable_ids: [] });
    await command(page, id, `/dqs/${naDq.id}/remediation-action`, { due_date: '2026-12-01' }, 409);
    // (2) 非法 check_code (可选/不存在) -> 400.
    await command(page, id, `/dqs/${dq.id}/remediation-action`, { check_code: 'ZZZ', due_date: '2026-12-01' }, 400);
    // (3) 缺必填到期日 -> 400.
    await command(page, id, `/dqs/${dq.id}/remediation-action`, { title: '缺期整改' }, 400);
    // (4) 跨项目 DQ -> 404.
    const otherProject = await api(page, 'POST', '/api/pms/projects', { project_no: `RAO-${suffix}`, name: `他项目-${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const otherDq = await command(page, otherProject.project_id, '/dqs', { code: `RAOT-${suffix}`, title: `他项目DQ-${suffix}`, owner_id: adminId,
      checklist: [{ code: 'R', title: '必需', required: true }], deliverable_ids: [] });
    await command(page, id, `/dqs/${otherDq.id}/remediation-action`, { due_date: '2026-12-01' }, 404);

    // 界面第一轮: 打开 DQ 台账 -> 点击"落实整改" -> 指定 R-2 + admin + 到期日 -> 保存.
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    const ledger = panel(page, 'DQ 编制与确认');
    await expect(ledger.getByRole('columnheader', { name: '整改情况', exact: true })).toBeVisible();
    const dqRow = row(page, dqTitle);
    // 未落实整改时"整改情况"列显示红色"待落实整改" (有未通过必需项).
    await expect(dqRow.getByText('待落实整改', { exact: true })).toBeVisible();
    await dqRow.getByRole('button', { name: /落\s*实\s*整\s*改/ }).click();
    const form = modal(page, '落实整改行动');
    await choose(page, form, 'check_code', 'R-2');
    await fill(form, { title: `补做绝缘耐压复测-${suffix}` });
    await choose(page, form, 'owner_id', 'admin');
    await fill(form, { due_date: '2026-12-01' });
    await shot(page, 'h10dqra-1-dialog.png');
    const action = (await save(page, '落实整改行动')).data.result;
    expect(action.source_dq_id, '整改行动记录来源 DQ').toBe(dq.id);
    expect(action.source_check_code, '整改行动绑定所选必需检查项 R-2').toBe('R-2');
    expect(action.status, '整改行动新建为开放项').toBe('open');

    // 重载: "整改情况"列金色"整改中"; 汇总"已落实整改 1"/"整改未完成 1".
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    const ledger2 = panel(page, 'DQ 编制与确认');
    const dqRow2 = row(page, dqTitle);
    await expect(dqRow2.getByText(/整改中/)).toBeVisible();
    await shotCard(page, ledger2, 'h10dqra-2-ledger-inprogress.png');
    const sum = panel(page, 'DQ 质量检查闭环汇总');
    await expect(sum.getByText(/已落实整改\s*1/)).toBeVisible();
    await expect(sum.getByText(/整改未完成\s*1/)).toBeVisible();
    await shotCard(page, sum, 'h10dqra-3-summary-open.png');

    // 真实HTTP读模型回显, 与界面一致.
    let ws = await api(page, 'GET', base(id) + '/governance');
    let d1 = ws.dqs.find(x => x.id === dq.id);
    expect(d1.dq_remediation_total, '已落实整改总数 1').toBe(1);
    expect(d1.dq_remediation_open, '未完成整改 1').toBe(1);
    expect(d1.dq_remediation_state, '状态 in-progress').toBe('in-progress');
    let act = ws.actions.find(x => x.id === action.id);
    expect(act.source_dq_id, 'HTTP 回显来源 DQ').toBe(dq.id);
    expect(act.source_check_code, 'HTTP 回显检查项编码').toBe('R-2');
    expect(ws.dq_summary.remediated, '汇总已落实整改 1').toBe(1);
    expect(ws.dq_summary['remediation-open'], '汇总整改未完成 1').toBe(1);

    // 真实HTTP把整改行动转为WBS任务(converted=完成) -> 重载台账"整改完成", 汇总"整改未完成"消失.
    await command(page, id, `/actions/${action.id}/task`, { start_date: '2026-09-23', duration_days: 2 });
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    const ledger3 = panel(page, 'DQ 编制与确认');
    const dqRow3 = row(page, dqTitle);
    await expect(dqRow3.getByText(/整改完成\s*1\/1/)).toBeVisible();
    await shotCard(page, ledger3, 'h10dqra-4-ledger-completed.png');
    const sum3 = panel(page, 'DQ 质量检查闭环汇总');
    await expect(sum3.getByText(/已落实整改\s*1/)).toBeVisible();
    await expect(sum3.getByText(/整改未完成/)).toHaveCount(0);

    ws = await api(page, 'GET', base(id) + '/governance');
    d1 = ws.dqs.find(x => x.id === dq.id);
    expect(d1.dq_remediation_state, '转任务后 completed').toBe('completed');
    expect(d1.dq_remediation_open, '转任务后 open 归 0').toBe(0);
    expect(ws.dq_summary['remediation-open'], '完成后整改未完成 0').toBe(0);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
