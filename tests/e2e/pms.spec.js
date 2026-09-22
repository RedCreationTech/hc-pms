const { test, expect } = require('@playwright/test');
const path = require('node:path');

const output = path.resolve(__dirname, '../../..');
const unique = () => `${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
const detail = page => page.getByRole('dialog').filter({ has: page.getByText('项目概况', { exact: true }) });
const option = (page, text) => page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: text }).first();

async function collapseNavigation(page) {
  for (const name of ['system 系统管理', 'monitor 系统监控', 'apartment 办公']) {
    const menu = page.getByRole('menuitem', { name, exact: true });
    if (await menu.getAttribute('aria-expanded') === 'true') await menu.click();
    await expect(menu).toHaveAttribute('aria-expanded', 'false');
  }
  await expect(page.getByRole('menuitem', { name: 'deployment-unit 流程管理', exact: true })).toBeHidden();
  await page.evaluate(() => window.scrollTo(0, 0));
}

async function preview(page, filename) {
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, filename), fullPage: false, animations: 'disabled' });
}

async function signIn(page) {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ruoyi_token'))).toBeTruthy();
  await page.goto('/pms/project');
  await expect(page.getByText('按项目追踪责任、计划与当前阶段')).toBeVisible();
  await expect(page.getByRole('button', { name: /创建项目$/ }).first()).toBeEnabled();
}

async function createProject(page, suffix, name) {
  const number = `DEMO-PMS-${suffix}`;
  await page.getByRole('button', { name: /创建项目$/ }).first().click();
  const form = page.getByRole('dialog', { name: '创建项目', exact: true });
  await form.locator('#project_no').fill(number);
  await form.locator('#name').fill(name);
  await form.locator('#project_type').click();
  await option(page, '整线工程').click();
  await form.locator('#contract_no').fill(`DEMO-HT-${suffix.slice(-8)}`);
  await form.locator('#customer').fill('演示客户 / 华川智能制造');
  await form.locator('#dept_id').click();
  await option(page, '研发部门').click();
  await form.locator('#start_date').fill('2026-09-22');
  await form.locator('#end_date').fill('2027-03-31');
  await form.getByRole('button', { name: '保存项目', exact: true }).click();
  await expect(form).toBeHidden();
  await expect(detail(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  await expect(page).toHaveURL(/id=[0-9a-f-]{36}/);
  return { number, name, id: new URL(page.url()).searchParams.get('id') };
}

async function closeDetail(page) {
  await detail(page).getByRole('button', { name: '关闭', exact: true }).click();
  await expect(detail(page)).toBeHidden();
  await expect(page).not.toHaveURL(/[?&]id=/);
}

async function advance(page, target) {
  const button = target === 'initiated' ? '确认立项' : '进入计划';
  const title = target === 'initiated' ? '确认立项' : '进入计划阶段';
  await detail(page).getByRole('button', { name: new RegExp(`${button}$`) }).click();
  const modal = page.getByRole('dialog', { name: title, exact: true });
  await modal.locator('#reason').fill('演示验收: 项目资料与责任人已确认');
  await modal.getByRole('button', { name: title, exact: true }).click();
  await expect(modal).toBeHidden();
  await expect(detail(page).getByText(target === 'initiated' ? '已立项' : '计划中', { exact: true }).first()).toBeVisible();
}

async function addNode(page, code, name, parentName) {
  await detail(page).getByRole('button', { name: /添加节点$/ }).click();
  const modal = page.getByRole('dialog', { name: '添加项目节点', exact: true });
  if (parentName) {
    await modal.locator('#parent_id').click();
    await option(page, parentName).click();
  }
  await modal.locator('#node_code').fill(code);
  await modal.locator('#name').fill(name);
  await modal.getByRole('button', { name: '添加节点', exact: true }).click();
  await expect(modal).toBeHidden();
  await expect(detail(page).getByText(name, { exact: true })).toBeVisible();
}

async function ensureDemoMember(page) {
  // 全新本地库只含 admin. 创建无系统角色的合成成员作为项目团队测试前置数据.
  const result = await page.evaluate(async () => {
    const headers = { Authorization: `Bearer ${localStorage.getItem('ruoyi_token')}`, 'Content-Type': 'application/json' };
    const options = (await (await fetch('/api/pms/options', { headers })).json()).data;
    if (options.users.some(user => user.user_name === 'pms_test_member')) return { code: 200 };
    const department = options.depts.find(dept => dept.dept_name === '研发部门');
    const response = await fetch('/api/system/user', { method: 'POST', headers, body: JSON.stringify({
      user_name: 'pms_test_member', nick_name: '演示协作成员', password: crypto.randomUUID(),
      dept_id: department.dept_id, roles: [], posts: [], remark: 'PMS E2E 合成测试成员,无系统角色'
    }) });
    return response.json();
  });
  expect(result.code, '演示成员前置数据创建成功').toBe(200);
  await page.reload();
  await expect(page.getByRole('button', { name: /创建项目$/ }).first()).toBeEnabled();
}

test.describe('PMS 第一阶段真实浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(120000);

  test.beforeEach(async ({ page }) => {
    page.pmsErrors = [];
    page.on('pageerror', error => page.pmsErrors.push(error.message));
    await signIn(page);
  });

  test.afterEach(async ({ page }) => {
    expect(page.pmsErrors, '页面不应出现未捕获的 JavaScript 错误').toEqual([]);
  });

  test('UI 创建项目,维护结构和团队,编辑,推进生命周期,刷新持久化并取消只读', async ({ page }) => {
    await ensureDemoMember(page);
    const suffix = unique();
    const project = await createProject(page, suffix, `演示项目 / 智能装配线 ${suffix.slice(-4)}`);
    await addNode(page, 'SUB-01', '演示子项目 / 装配工段');
    await addNode(page, 'EQ-01', '演示单机 / 自动拧紧工作站', '演示子项目 / 装配工段');
    await detail(page).getByRole('button', { name: '维护成员', exact: true }).click();
    const member = page.getByRole('dialog', { name: '维护项目成员', exact: true });
    await member.locator('#user_id').click();
    await member.locator('#user_id').fill('pms_test_member');
    await option(page, '/ pms_test_member').click();
    await member.locator('#role').click();
    await option(page, '只读成员').click();
    await member.getByRole('button', { name: '保存成员', exact: true }).click();
    await expect(member).toBeHidden();
    await expect(detail(page).getByText('只读成员', { exact: true })).toBeVisible();
    await detail(page).getByRole('button', { name: /编辑资料$/ }).click();
    const edit = page.getByRole('dialog', { name: '编辑项目', exact: true });
    await edit.locator('#customer').fill('演示客户 / 华川智能制造二厂');
    await edit.getByRole('button', { name: '保存项目', exact: true }).click();
    await expect(edit).toBeHidden();
    await expect(detail(page).getByText('演示客户 / 华川智能制造二厂', { exact: true })).toBeVisible();
    await advance(page, 'initiated');
    await advance(page, 'planning');
    await detail(page).getByRole('button', { name: /进入执行/ }).click();
    const execution = page.getByRole('dialog', { name: '进入执行阶段', exact: true });
    const rejection = page.waitForResponse(response => response.url().endsWith(`/projects/${project.id}/transition`) && response.request().method() === 'POST');
    await execution.getByRole('button', { name: '进入执行阶段', exact: true }).click();
    const rejected = await (await rejection).json();
    expect(rejected.code).toBe(409);
    expect(rejected.msg).toMatch(/基线|章程|Gate/);
    await expect(execution).toContainText(rejected.msg);
    await execution.getByRole('button', { name: /返\s*回/, exact: true }).click();
    await expect(detail(page).getByText('计划中', { exact: true }).first()).toBeVisible();
    await page.reload();
    await expect(detail(page).getByText('演示单机 / 自动拧紧工作站', { exact: true })).toBeVisible();
    await expect(detail(page).getByText('计划中', { exact: true }).first()).toBeVisible();
    await expect(detail(page).getByText('只读成员', { exact: true })).toBeVisible();
    await expect(detail(page).getByText('变更记录', { exact: true })).toBeVisible();
    await closeDetail(page);
    await collapseNavigation(page);
    await page.locator('tbody tr').filter({ hasText: project.number }).getByRole('button', { name: '详情', exact: true }).click();
    await expect(detail(page).getByText('演示单机 / 自动拧紧工作站', { exact: true })).toBeVisible();
    await preview(page, 'pms-detail-preview.png');
    await detail(page).getByRole('button', { name: '取消项目', exact: true }).click();
    const cancel = page.getByRole('dialog', { name: '取消项目', exact: true });
    await cancel.getByRole('button', { name: '取消项目', exact: true }).click();
    await expect(cancel.getByText('请填写取消原因', { exact: true })).toBeVisible();
    await cancel.locator('#reason').fill('演示验收结束,保留记录用于审计验证');
    await cancel.getByRole('button', { name: '取消项目', exact: true }).click();
    await expect(cancel).toBeHidden();
    await expect(detail(page).getByText('项目已结束,当前为只读视图', { exact: true })).toBeVisible();
    await expect(detail(page).getByRole('button', { name: /编辑资料|添加节点|维护成员|取消项目/ })).toHaveCount(0);
    await page.reload();
    await expect(detail(page).getByText('项目已结束,当前为只读视图', { exact: true })).toBeVisible();
    await expect(detail(page).getByText('演示验收结束,保留记录用于审计验证', { exact: true })).toBeVisible();
    expect(new URL(page.url()).searchParams.get('id')).toBe(project.id);
  });

  test('独立项目的查询和URL恢复,空态,动态菜单,驾驶舱真实统计', async ({ page }) => {
    const suffix = unique();
    const project = await createProject(page, suffix, `演示项目 / 视觉检测线 ${suffix.slice(-4)}`);
    await advance(page, 'initiated');
    await closeDetail(page);
    await page.getByLabel('搜索项目', { exact: true }).fill(project.number);
    await page.getByRole('button', { name: /查\s*询/, exact: true }).click();
    await page.locator('.ant-select').filter({ has: page.getByLabel('生命周期筛选') }).click();
    await option(page, '已立项').click();
    const row = page.locator('tbody tr').filter({ hasText: project.number });
    await expect(row).toBeVisible();
    await expect(page).toHaveURL(/status=initiated/);
    await expect(page).toHaveURL(new RegExp(`q=${project.number}`));
    await page.reload();
    await expect(page.getByLabel('搜索项目', { exact: true })).toHaveValue(project.number);
    await expect(row).toBeVisible();
    await row.getByRole('button', { name: '详情', exact: true }).click();
    await expect(detail(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
    await page.reload();
    await expect(detail(page).getByText(project.number, { exact: true }).first()).toBeVisible();
    expect(new URL(page.url()).searchParams.get('id')).toBe(project.id);
    await closeDetail(page);
    await page.getByLabel('搜索项目', { exact: true }).fill(`NO-MATCH-${suffix}`);
    await page.getByRole('button', { name: /查\s*询/, exact: true }).click();
    await expect(page.getByText('没有匹配的项目,试试调整搜索条件')).toBeVisible();
    await page.getByRole('button', { name: /重\s*置/, exact: true }).click();
    await expect(row).toBeVisible();
    await collapseNavigation(page);
    await preview(page, 'pms-project-preview.png');
    await page.locator('.ant-menu').getByText('项目驾驶舱', { exact: true }).click();
    await expect(page).toHaveURL(/\/pms\/dashboard$/);
    await expect(page.getByText('掌握项目组合的当前状态,把注意力放在需要推进的事情上.')).toBeVisible();
    await expect(page.getByRole('button', { name: project.name, exact: true })).toBeVisible();
    const metrics = await page.evaluate(async () => {
      const response = await fetch('/api/pms/dashboard', { headers: { Authorization: `Bearer ${localStorage.getItem('ruoyi_token')}` } });
      return (await response.json()).data;
    });
    expect(metrics.total).toBeGreaterThanOrEqual(1);
    expect(metrics.active).toBeGreaterThanOrEqual(1);
    await expect(page.getByText('项目总数', { exact: true }).locator('..').locator('..')).toContainText(String(metrics.total));
    await expect(page.getByText('进行中', { exact: true }).locator('..').locator('..')).toContainText(String(metrics.active));
    await preview(page, 'pms-dashboard-preview.png');
  });
});
