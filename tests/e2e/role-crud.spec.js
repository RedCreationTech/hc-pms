const { test, expect } = require('playwright/test');
const { login } = require('./auth-helper');

test.describe('角色管理 CRUD', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
    await page.goto('/system/role');
    await expect(page.getByText('角色名称').first()).toBeVisible({ timeout: 10000 });
  });

  test('列表页面正常渲染', async ({ page }) => {
    // 工具栏按钮可见
    await expect(page.getByRole('button', { name: '新增' }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: '修改' }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: '删除' }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: '导出' }).first()).toBeVisible();

    // 表格应该有数据行（排除 antd 隐藏的 measure row）
    const rows = page.locator('table tbody tr:not(.ant-table-measure-row)');
    await expect(rows.first()).toBeVisible({ timeout: 10000 });
    const rowCount = await rows.count();
    expect(rowCount).toBeGreaterThan(0);
  });

  test('搜索角色', async ({ page }) => {
    // 搜索框可见
    const roleNameInput = page.getByPlaceholder('请输入角色名称');
    await expect(roleNameInput).toBeVisible();

    // 搜索管理员角色
    await roleNameInput.fill('管理');
    await page.getByRole('button', { name: '搜索' }).first().click();
    await page.waitForTimeout(500);

    // 表格应有结果（排除 antd 隐藏的 measure row）
    const rows = page.locator('table tbody tr:not(.ant-table-measure-row)');
    await expect(rows.first()).toBeVisible({ timeout: 10000 });
  });

  test('新增、修改并删除角色', async ({ page }) => {
    const timestamp = Date.now().toString();
    const roleName = `e2e_role_${timestamp}`;
    const roleKey = `e2e_${timestamp}`;
    const roleNameUpdated = `${roleName}_updated`;

    // ── 新增角色 ──
    await page.getByRole('button', { name: '新增' }).click();

    // 等待弹窗出现
    const addModal = page.getByRole('dialog', { name: '新增角色' });
    await expect(addModal).toBeVisible({ timeout: 5000 });

    // 填写表单
    await addModal.getByPlaceholder('请输入角色名称').fill(roleName);
    await addModal.getByPlaceholder('请输入权限字符').fill(roleKey);
    await addModal.getByPlaceholder('请输入角色顺序').fill('1');

    // 点击确定
    await addModal.getByRole('button', { name: /确\s*定/ }).click();
    await expect(addModal).toBeHidden({ timeout: 5000 });

    // 角色较多时新记录可能不在第一页: 按名称检索后再确认（排除 antd 隐藏的 measure row）
    await page.locator('.ant-form, form, body').first().getByPlaceholder('请输入角色名称').first().fill(roleName);
    await page.getByRole('button', { name: /搜\s*索/ }).click();
    const newRow = page.locator('table tbody tr:not(.ant-table-measure-row)', { hasText: roleName });
    await expect(newRow).toBeVisible({ timeout: 10000 });

    // ── 修改角色 ──
    // antd 表格操作列按钮的 accessible name 通常包含图标 aria-label，使用 hasText 更稳定
    await newRow.locator('button').filter({ hasText: '修改' }).click();

    const editModal = page.getByRole('dialog', { name: '修改角色' });
    await expect(editModal).toBeVisible({ timeout: 5000 });

    // 修改角色名称
    const nameInput = editModal.getByPlaceholder('请输入角色名称');
    await nameInput.fill(roleNameUpdated);

    // 点击确定
    await editModal.getByRole('button', { name: /确\s*定/ }).click();
    await expect(editModal).toBeHidden({ timeout: 5000 });

    // 确认列表中出现了修改后的数据（排除 antd 隐藏的 measure row）
    const updatedRow = page.locator('table tbody tr:not(.ant-table-measure-row)', { hasText: roleNameUpdated });
    await expect(updatedRow).toBeVisible({ timeout: 10000 });

    // ── 删除角色 ──
    await updatedRow.locator('button').filter({ hasText: '删除' }).click();
    await page.waitForTimeout(500);

    // 确认删除 (Popconfirm)
    const deleteBtn = page.locator('.ant-popconfirm').getByRole('button', { name: /确\s*定/ });
    if (await deleteBtn.isVisible()) {
      await deleteBtn.click();
    } else {
      await page.locator('.ant-popover-inner').getByRole('button', { name: /确\s*定/ }).click();
    }

    await expect(updatedRow).toBeHidden({ timeout: 10000 });
  });

  test('数据权限分配', async ({ page }) => {
    // 等待表格数据加载完成，避免遍历时行未渲染导致误 skip
    await expect(page.locator('table tbody tr:not(.ant-table-measure-row)').first()).toBeVisible({ timeout: 10000 });

    // 找一个有"更多"按钮的角色行（排除 antd 隐藏的 measure row）
    const rows = page.locator('table tbody tr:not(.ant-table-measure-row)');
    const rowCount = await rows.count();

    let targetRow = null;
    for (let i = 0; i < rowCount; i++) {
      const row = rows.nth(i);
      const moreBtns = await row.locator('button').filter({ hasText: '更多' }).count();
      if (moreBtns > 0) {
        targetRow = row;
        break;
      }
    }

    if (!targetRow) {
      test.skip('没有找到可操作的角色');
      return;
    }

    // 点"更多" → 数据权限
    await targetRow.locator('button').filter({ hasText: '更多' }).click();
    const dataPermItem = page.locator('.ant-dropdown-menu').getByText('数据权限');
    await expect(dataPermItem).toBeVisible();
    await dataPermItem.click();

    // 等待数据权限弹窗
    await expect(page.getByText('数据权限').first()).toBeVisible({ timeout: 5000 });

    // 选择"全部数据权限"
    const allDataRadio = page.getByText('全部数据权限');
    await allDataRadio.click();
    await page.waitForTimeout(300);

    // 点击确定
    await page.locator('.ant-modal').last().getByRole('button', { name: /确\s*定/ }).click();
    await page.waitForTimeout(500);
  });

  test('分配菜单权限: 父子联动勾选, 半选的上级一并授权, 取消的按钮不授权', async ({ page }) => {
    const stamp = Date.now().toString(36);
    const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
    const call = async (method, url, data) => {
      const res = await page.request.fetch(url, { method, headers: { Authorization: `Bearer ${token}` }, data });
      const body = await res.json();
      expect(body.code, `${method} ${url}: ${body.msg}`).toBe(200);
      return body.data;
    };
    await call('POST', '/api/system/role', { role_name: `菜单树${stamp}`, role_key: `tree_${stamp}`, role_sort: 9, status: '0', 'menu-ids': [] });
    const roleId = (await call('GET', `/api/system/role?role_key=tree_${stamp}`)).find(r => r.role_key === `tree_${stamp}`).role_id;

    await page.getByPlaceholder('请输入角色名称').first().fill(`菜单树${stamp}`);
    await page.getByRole('button', { name: /搜\s*索/ }).click();
    const row = page.locator('table tbody tr:not(.ant-table-measure-row)', { hasText: `tree_${stamp}` });
    await row.locator('button').filter({ hasText: '更多' }).click();
    await page.locator('.ant-dropdown-menu').getByText('分配权限').click();
    const modal = page.getByRole('dialog').filter({ hasText: '分配菜单权限' });
    const node = title => modal.locator('.ant-tree-treenode').filter({ has: page.locator('.ant-tree-title', { hasText: new RegExp(`^${title}$`) }) }).first();
    await node('系统管理').locator('.ant-tree-switcher').click();
    await node('用户管理').locator('.ant-tree-checkbox').click();
    await node('用户管理').locator('.ant-tree-switcher').click();
    await expect(node('用户删除').locator('.ant-tree-checkbox')).toHaveClass(/ant-tree-checkbox-checked/);
    await node('用户删除').locator('.ant-tree-checkbox').click();
    await expect(node('用户管理').locator('.ant-tree-checkbox')).toHaveClass(/ant-tree-checkbox-indeterminate/);
    await modal.getByRole('button', { name: /确\s*定/ }).click();
    await expect(page.getByText('权限更新成功')).toBeVisible();

    const ids = (await call('GET', `/api/system/role/${roleId}`))['menu-ids'].map(Number);
    expect(ids).toEqual(expect.arrayContaining([1, 3, 100, 101, 102, 104]));
    expect(ids).not.toContain(103);
    expect(ids).not.toContain(4);

    // 重新打开: 用户管理为半选, 用户删除未勾选
    await row.locator('button').filter({ hasText: '更多' }).click();
    await page.locator('.ant-dropdown-menu').getByText('分配权限').click();
    await node('系统管理').locator('.ant-tree-switcher').click();
    await expect(node('用户管理').locator('.ant-tree-checkbox')).toHaveClass(/ant-tree-checkbox-indeterminate/);
    await modal.locator('.ant-modal-close').click();
    await call('DELETE', `/api/system/role/${roleId}`);
  });

  test('分配用户', async ({ page }) => {
    // 等待表格数据加载完成，避免遍历时行未渲染导致误 skip
    await expect(page.locator('table tbody tr:not(.ant-table-measure-row)').first()).toBeVisible({ timeout: 10000 });

    // 找一个有"更多"按钮的角色行（排除 antd 隐藏的 measure row）
    const rows = page.locator('table tbody tr:not(.ant-table-measure-row)');
    const rowCount = await rows.count();

    let targetRow = null;
    for (let i = 0; i < rowCount; i++) {
      const row = rows.nth(i);
      const moreBtns = await row.locator('button').filter({ hasText: '更多' }).count();
      if (moreBtns > 0) {
        targetRow = row;
        break;
      }
    }

    if (!targetRow) {
      test.skip('没有找到可操作的角色');
      return;
    }

    // 点"更多" → 分配用户
    await targetRow.locator('button').filter({ hasText: '更多' }).click();
    const allocItem = page.locator('.ant-dropdown-menu').getByText('分配用户');
    await expect(allocItem).toBeVisible();
    await allocItem.click();

    // 等待分配用户弹窗: "已分配用户" 与 "添加用户" 两个页签
    const modal = page.locator('.ant-modal').filter({ hasText: '分配用户' }).last();
    await expect(modal).toBeVisible({ timeout: 5000 });
    await modal.getByRole('tab', { name: '添加用户' }).click();
    const candidate = modal.locator('.ant-tabs-tabpane-active tbody tr:not(.ant-table-measure-row)').first();
    if (await candidate.isVisible().catch(() => false)) {
      const userName = (await candidate.locator('td').nth(2).innerText()).trim();
      await candidate.locator('.ant-checkbox-input').click({ force: true });
      await modal.getByRole('button', { name: '授权选中用户' }).click();
      await expect(page.getByText('授权成功')).toBeVisible();
      await modal.getByRole('tab', { name: '已分配用户' }).click();
      await expect(modal.locator('.ant-tabs-tabpane-active tbody')).toContainText(userName);
      // 取消授权后恢复原状
      await modal.locator('.ant-tabs-tabpane-active tbody tr').filter({ hasText: userName }).getByRole('button', { name: '取消授权' }).click();
      await expect(page.getByText('取消授权成功')).toBeVisible();
    }

    // 关闭弹窗
    await modal.locator('.ant-modal-close').click();
    await expect(modal).toBeHidden();
  });
});
