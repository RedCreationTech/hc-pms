const { test, expect } = require('@playwright/test');
const path = require('node:path');

// S3 可配置审批: 在 "模板与规则 > 审批策略" 用编辑器配置三级费用审批并发布; 项目经理提交后,
// 部门负责人与 (非项目成员的) 财务在 "我的待办 > 逐级审批" 逐级通过, 事业部负责人末级批准, 费用版本自动生效.
const output = path.resolve(__dirname, '../../reports/s3-approval');
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const PASSWORD = 'Pass-1234';

async function login(page, user = 'admin', password = 'admin123') {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill(user);
  await page.getByPlaceholder('密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ruoyi_token'))).toBeTruthy();
  await page.getByText('红创PMS').nth(1).waitFor();
}

async function api(page, method, url, data, expected = 200) {
  const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
  const response = await page.request.fetch(url, { method, headers: { Authorization: `Bearer ${token}` }, data });
  const body = await response.json().catch(() => ({}));
  expect(body.code ?? response.status(), `${method} ${url}: ${body.msg}`).toBe(expected);
  return body.data;
}

const shot = (page, file) => page.screenshot({ path: path.join(output, file), animations: 'disabled' });
const dialog = (page, title) => page.getByRole('dialog').filter({ hasText: title }).last();

// 先等上一个下拉框完全收起 (antd 收起动画期间旧选项仍可见), 再展开并选中.
const openDropdowns = page => page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
async function pick(page, select, text) {
  await expect(openDropdowns(page)).toHaveCount(0);
  await select.click();
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option:visible').filter({ hasText: text }).last().click();
}

test.describe.configure({ mode: 'serial' });

test('审批策略可视化配置并逐级审批, 末级通过后费用版本生效', async ({ page, browser }) => {
  test.setTimeout(240000);
  const tag = serial();
  await login(page);

  // 组织与账号: 事业部 (负责人 赵) > 研发部 (负责人 周); 财务角色 (钱, 不是项目成员); 项目经理 孙
  const walk = (nodes, out = []) => { for (const n of nodes || []) { out.push(n); walk(n.children, out); } return out; };
  const pmsMenus = walk(await api(page, 'GET', '/api/system/menu')).filter(m => m.path === 'pms' || (m.perms || '').startsWith('pms:')).map(m => m.menu_id);
  const role = async key => {
    await api(page, 'POST', '/api/system/role', { role_name: `审批${key}`, role_key: key, role_sort: 9, status: '0', 'menu-ids': pmsMenus });
    return (await api(page, 'GET', `/api/system/role?role_key=${key}`)).find(r => r.role_key === key).role_id;
  };
  const financeKey = `fin_${tag}`;
  const financeRole = await role(financeKey);
  const pmRole = await role(`pm_${tag}`);
  const dept = async (parent, name, extra = {}) => {
    await api(page, 'POST', '/api/system/dept', { parent_id: parent, dept_name: name, order_num: 1, ...extra });
    return (await api(page, 'GET', '/api/system/dept')).find(d => d.dept_name === name).dept_id;
  };
  const user = async (name, nick, deptId, roles) => {
    await api(page, 'POST', '/api/system/user', { user_name: name, nick_name: nick, password: PASSWORD, dept_id: deptId, roles });
    return (await api(page, 'GET', `/api/system/user?user_name=${name}&page=1&size=5`)).rows[0].user_id;
  };
  const division = await dept(1, `事业部${tag}`);
  const rd = await dept(division, `研发部${tag}`);
  const zhao = await user(`zhao${tag}`, `赵总${tag.slice(-3)}`, division, [2, pmRole]);
  const zhou = await user(`zhou${tag}`, `周经理${tag.slice(-3)}`, rd, [2, pmRole]);
  const sun = await user(`sun${tag}`, `孙工${tag.slice(-3)}`, rd, [2, pmRole]);
  const li = await user(`li${tag}`, `李工${tag.slice(-3)}`, rd, [2, pmRole]);
  await user(`qian${tag}`, `钱会计${tag.slice(-3)}`, 6, [2, financeRole]);
  await api(page, 'PUT', `/api/system/dept/${division}`, { leader_id: zhao });
  await api(page, 'PUT', `/api/system/dept/${rd}`, { leader_id: zhou });

  // 1. 管理员在审批策略编辑器中配置三级审批 (若已有生效的费用版本策略, 先修订; 否则新建)
  await page.goto('/pms/config');
  await page.getByRole('tab', { name: '审批策略' }).click();
  await page.getByText('费用版本两级审批').first().waitFor();
  const existing = (await api(page, 'GET', '/api/pms/config/approval-policy')).rows.find(r => r.code === 'cost-version' && ['published', 'retired'].includes(r.status));
  if (existing) {
    await page.locator('tbody tr').filter({ hasText: existing.name }).first().getByRole('button', { name: '修订' }).click();
  } else {
    await page.getByRole('button', { name: '新建审批策略' }).click();
  }
  const editor = page.getByRole('dialog').filter({ hasText: '审批策略' }).last();
  await editor.getByLabel('策略名称').fill(`研发费用三级审批${tag}`);
  // 修订时先清空已有级别到一级
  while (await editor.getByRole('button', { name: '删除本级' }).count() > 1) await editor.getByRole('button', { name: '删除本级' }).last().click();
  await editor.getByLabel('第1级名称').fill('部门负责人审批');
  await pick(page, editor.getByLabel('第1级审批人规则'), '项目所属部门负责人');
  await editor.getByRole('button', { name: '+ 添加一级审批' }).click();
  await editor.getByLabel('第2级名称').fill('财务审批');
  await pick(page, editor.getByLabel('第2级审批人规则'), '指定角色');
  await expect(openDropdowns(page)).toHaveCount(0);
  await editor.getByLabel('审批角色').click();
  await page.keyboard.type(`审批${financeKey}`);
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option:visible').filter({ hasText: `审批${financeKey}` }).last().click();
  await editor.getByRole('button', { name: '+ 添加一级审批' }).click();
  await editor.getByLabel('第3级名称').fill('事业部负责人审批');
  await pick(page, editor.getByLabel('上溯级数').last(), '上溯1级');
  await editor.getByLabel('金额条件').last().fill('100000');
  await expect(editor).toContainText('项目所属部门上溯1级的负责人 · 或签 · 金额 >= 100000');
  await shot(page, 's3-1-policy-editor.png');
  await editor.getByRole('button', { name: '保存为草稿' }).click();
  await expect(page.getByText('审批策略已保存为草稿')).toBeVisible();
  const row = page.locator('tbody tr').filter({ hasText: `研发费用三级审批${tag}` }).first();
  await row.getByRole('button', { name: '发布' }).click();
  const publish = dialog(page, '发布审批策略');
  await publish.locator('#reason').fill('启用三级审批');
  await publish.getByRole('button', { name: /保\s*存/ }).click();
  await expect(row).toContainText('已发布');
  await row.scrollIntoViewIfNeeded();
  await shot(page, 's3-2-policy-published.png');

  // 2. 项目经理提交 15 万元预算版本 (经 HTTP 建项目与明细, 界面提交)
  const pm = await browser.newPage();
  await login(pm, `sun${tag}`, PASSWORD);
  const project = await api(pm, 'POST', '/api/pms/projects', { project_no: `S3-${tag}`, name: `审批链验收${tag}`, manager_id: sun, dept_id: rd,
    start_date: '2026-10-01', end_date: '2027-03-31', project_type: 'new_product' });
  const pid = project.project_id;
  const version = async () => (await api(pm, 'GET', `/api/pms/projects/${pid}`)).version;
  await api(pm, 'POST', `/api/pms/projects/${pid}/members`, { user_id: li, role: 'viewer' });
  const cost = (await api(pm, 'POST', `/api/pms/projects/${pid}/cost-versions`, { kind: 'budget', period: '2026-10', currency: 'CNY', name: '研发预算', revenue: '0.00', reviewer_id: li, version: await version() })).result;
  await api(pm, 'POST', `/api/pms/projects/${pid}/cost-versions/${cost.id}/entries`, { category: 'material', label: '控制器', amount: '150000.00', version: await version() });
  await pm.goto(`/pms/project?id=${pid}`);
  const drawer = pm.getByRole('dialog').filter({ has: pm.getByRole('tab', { name: '项目概况', exact: true }) });
  await drawer.getByRole('tab', { name: '项目费用', exact: true }).click();
  const costRow = () => drawer.locator('tbody tr').filter({ hasText: '研发预算' }).first();
  await costRow().getByRole('button', { name: '提交审批' }).click();
  await dialog(pm, '提交成本版本审批').getByRole('button', { name: /保\s*存/ }).click();
  await expect(dialog(pm, '提交成本版本审批')).toBeHidden();
  await costRow().getByRole('button', { name: '查看明细' }).click();
  await expect(drawer.locator('.approval-flow .ant-steps')).toContainText('部门负责人审批');
  await expect(drawer.locator('.approval-flow .ant-steps')).toContainText('事业部负责人审批');
  await expect(costRow().getByRole('button', { name: '批准' })).toHaveCount(0);
  await drawer.locator('.approval-flow').first().scrollIntoViewIfNeeded();
  await shot(pm, 's3-3-submitted-progress.png');

  // 3. 部门负责人与财务在 "我的待办 > 逐级审批" 逐级通过; 提交人不能审批
  for (const [name, comment, screenshot] of [[`zhou${tag}`, '同意', 's3-4-leader-todo.png'], [`qian${tag}`, '金额核对一致', 's3-5-finance-decide.png']]) {
    const approver = await browser.newPage();
    await login(approver, name, PASSWORD);
    await approver.goto('/pms/todo');
    const item = approver.locator('tbody tr').filter({ hasText: `审批链验收${tag}` }).first();
    await item.getByRole('button', { name: /^审\s*批$/ }).click();
    const decide = dialog(approver, '审批 · 费用版本');
    await decide.locator('.approval-flow .ant-steps').waitFor();
    await decide.getByLabel('审批意见').fill(comment);
    await shot(approver, screenshot);
    await decide.getByRole('button', { name: /^通\s*过$/ }).click();
    await expect(approver.getByText('已通过').first()).toBeVisible();
    await approver.close();
  }
  const flow = (await api(pm, 'GET', `/api/pms/projects/${pid}/approvals?biz_type=cost-version`))[0];
  await api(pm, 'POST', `/api/pms/approvals/${flow.flow_id}/decide`, { decision: 'approved' }, 403);

  // 4. 事业部负责人末级批准后费用版本自动生效
  const vp = await browser.newPage();
  await login(vp, `zhao${tag}`, PASSWORD);
  await api(vp, 'POST', `/api/pms/approvals/${flow.flow_id}/decide`, { decision: 'approved', comment: '同意' });
  await vp.close();
  await pm.reload();
  await drawer.getByRole('tab', { name: '项目费用', exact: true }).click();
  await expect(costRow()).toContainText('已批准');
  await costRow().getByRole('button', { name: '查看明细' }).click();
  await expect(drawer.locator('.approval-flow .ant-steps-item-finish')).toHaveCount(3);
  await drawer.locator('.approval-flow').first().scrollIntoViewIfNeeded();
  await shot(pm, 's3-6-approved.png');

  // 收尾: 退役本用例的策略, 不影响其他用例
  const mine = (await api(page, 'GET', '/api/pms/config/approval-policy')).rows.find(r => r.name === `研发费用三级审批${tag}`);
  await api(page, 'POST', `/api/pms/config/approval-policy/${mine.id}/retire`, { reason: '用例结束' });
  await pm.close();
});
