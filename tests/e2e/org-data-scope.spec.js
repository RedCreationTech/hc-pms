const { test, expect } = require('@playwright/test');
const path = require('node:path');

// S2 组织, 数据权限与菜单: 部门树与负责人, 角色数据权限 (本部门及以下), 部门经理只看到本部门的用户,
// 未授权页面显示 403, 收回菜单后侧边栏与页面随之更新, 隐藏菜单可访问但不出现在侧边栏.
const output = path.resolve(__dirname, '../../reports/s2-org');
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;

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
  if (expected !== null) expect(body.code ?? response.status(), `${method} ${url}: ${body.msg}`).toBe(expected);
  return body;
}

async function shot(page, file) {
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

function flattenMenus(nodes, out = []) {
  for (const n of nodes || []) { out.push(n); flattenMenus(n.children, out); }
  return out;
}

test.describe.configure({ mode: 'serial' });

test('部门经理按 "本部门及以下" 只管理本部门用户, 菜单收回后页面 403', async ({ page, browser }) => {
  test.setTimeout(180000);
  const tag = serial();
  await login(page);

  // 1. 部门: 在红创科技下建 "研发中心" 与下级 "测试组", 负责人选用户
  const leaderName = `leader${tag}`;
  await api(page, 'POST', '/api/system/user', { user_name: leaderName, nick_name: `王经理${tag.slice(-3)}`, password: 'Pass-1234', dept_id: 1, roles: [2] });
  const leader = (await api(page, 'GET', `/api/system/user?user_name=${leaderName}&page=1&size=5`)).data.rows[0];
  const center = `研发中心${tag.slice(-4)}`;
  await api(page, 'POST', '/api/system/dept', { parent_id: 1, dept_name: center, order_num: 1, leader_id: leader.user_id });
  const depts = (await api(page, 'GET', '/api/system/dept')).data;
  const centerId = depts.find(d => d.dept_name === center).dept_id;
  await api(page, 'POST', '/api/system/dept', { parent_id: centerId, dept_name: `测试组${tag.slice(-4)}`, order_num: 1 });
  const groupId = (await api(page, 'GET', '/api/system/dept')).data.find(d => d.dept_name === `测试组${tag.slice(-4)}`).dept_id;
  await api(page, 'PUT', `/api/system/user/${leader.user_id}`, { dept_id: centerId });

  await page.goto('/system/dept');
  const centerRow = page.locator('tbody tr').filter({ hasText: center }).first();
  await expect(centerRow).toContainText(`王经理${tag.slice(-3)}`);
  await centerRow.getByRole('button', { name: '修改' }).click();
  await expect(page.getByRole('dialog').getByText('负责人可作为审批规则中的"部门负责人"')).toBeVisible();
  await shot(page, 's2-1-dept-leader.png');
  await page.getByRole('dialog').getByRole('button', { name: /取\s*消/ }).click();

  // 2. 角色: 部门经理 = 用户管理 + 部门管理菜单, 数据权限 "本部门及以下"
  const menus = flattenMenus((await api(page, 'GET', '/api/system/menu')).data);
  const pick = perms => menus.filter(m => perms.includes(m.perms)).map(m => m.menu_id);
  const menuIds = [1, ...pick(['system:user:list', 'system:user:query', 'system:user:add', 'system:user:edit',
    'system:dept:list', 'system:dept:query'])];
  const roleKey = `dept_mgr_${tag}`;
  await api(page, 'POST', '/api/system/role', { role_name: `部门经理${tag.slice(-4)}`, role_key: roleKey, role_sort: 3, status: '0', 'menu-ids': menuIds });
  const role = (await api(page, 'GET', '/api/system/role')).data.find(r => r.role_key === roleKey);

  await page.goto('/system/role');
  await page.getByPlaceholder('请输入权限字符').fill(roleKey);
  await page.getByRole('button', { name: /搜\s*索/ }).click();
  const roleRow = page.locator('tbody tr').filter({ hasText: roleKey }).first();
  await roleRow.getByRole('button', { name: '更多' }).hover();
  await page.getByRole('menuitem', { name: '数据权限' }).click();
  const scopeModal = page.getByRole('dialog').filter({ hasText: '分配数据权限' });
  await scopeModal.getByText('本部门及以下数据权限').click();
  await shot(page, 's2-2-role-data-scope.png');
  await scopeModal.getByRole('button', { name: /确\s*定/ }).click();
  await expect(page.getByText('数据权限设置成功')).toBeVisible();
  expect((await api(page, 'GET', `/api/system/role/${role.role_id}`)).data.data_scope).toBe('4');

  // 3. 部门经理与组员: 经理在研发中心, 组员在测试组, 另有市场部门同事
  const mgrName = `mgr${tag}`;
  await api(page, 'POST', '/api/system/user', { user_name: mgrName, nick_name: '研发经理', password: 'Pass-1234', dept_id: centerId, roles: [role.role_id] });
  await api(page, 'POST', '/api/system/user', { user_name: `tester${tag}`, nick_name: '测试组员', password: 'Pass-1234', dept_id: groupId, roles: [2] });
  await api(page, 'POST', '/api/system/user', { user_name: `market${tag}`, nick_name: '市场同事', password: 'Pass-1234', dept_id: 5, roles: [2] });

  const mgr = await browser.newPage();
  await login(mgr, mgrName, 'Pass-1234');
  const sider = mgr.locator('.ant-layout-sider');
  await expect(sider.getByText('用户管理')).toBeVisible();
  await expect(sider.getByText('角色管理')).toHaveCount(0);
  await mgr.goto('/system/user');
  const table = mgr.locator('.ant-table-tbody');
  await expect(table).toContainText(`tester${tag}`);
  await expect(table).toContainText(mgrName);
  await expect(table).not.toContainText(`market${tag}`);
  await shot(mgr, 's2-3-manager-sees-own-dept.png');
  const rows = (await api(mgr, 'GET', '/api/system/user?page=1&size=100')).data.rows;
  expect(rows.every(r => [centerId, groupId].includes(r.dept_id))).toBeTruthy();
  const market = (await api(page, 'GET', `/api/system/user?user_name=market${tag}&page=1&size=5`)).data.rows[0];
  await api(mgr, 'GET', `/api/system/user/${market.user_id}`, null, 403);
  // 部门经理看不到新增按钮以外的越权入口: 没有删除权限时不显示删除
  await expect(mgr.getByRole('button', { name: '删除' }).first()).toHaveCount(0);

  // 4. 未授权页面直接输入地址: 403
  await mgr.goto('/system/role');
  await expect(mgr.getByText('无权访问该页面')).toBeVisible();
  await shot(mgr, 's2-4-route-guard-403.png');

  // 5. 管理员收回部门管理菜单: 经理切换页面后侧边栏不再显示, 直接访问 403
  const deptMenuIds = new Set(pick(['system:dept:list', 'system:dept:query']));
  await api(page, 'PUT', `/api/system/role/${role.role_id}`, { role_id: role.role_id, 'menu-ids': menuIds.filter(id => !deptMenuIds.has(id)) });
  await mgr.goto('/system/user');
  await mgr.getByText('研发经理').first().waitFor();
  await mgr.waitForTimeout(3500);
  await mgr.locator('.ant-layout-sider').getByText('用户管理').click();
  await expect(sider.getByText('部门管理')).toHaveCount(0);
  await mgr.goto('/system/dept');
  await expect(mgr.getByText('无权访问该页面')).toBeVisible();
  await shot(mgr, 's2-5-menu-revoked.png');

  // 6. 隐藏菜单: 侧边栏不显示, 仍可按地址访问
  const userMenu = menus.find(m => m.perms === 'system:user:list');
  await api(page, 'PUT', `/api/system/menu/${userMenu.menu_id}`, { ...userMenu, visible: '1', children: undefined });
  try {
    await page.reload();
    await page.getByText('红创PMS').nth(1).waitFor();
    await expect(page.locator('.ant-layout-sider').getByText('用户管理')).toHaveCount(0);
    await page.goto('/system/user');
    await expect(page.locator('.ant-table-tbody')).toBeVisible();
    await shot(page, 's2-6-hidden-menu-still-routable.png');
  } finally {
    await api(page, 'PUT', `/api/system/menu/${userMenu.menu_id}`, { ...userMenu, visible: '0', children: undefined });
  }
  await mgr.close();
});
