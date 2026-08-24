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

    // 确认列表中出现了新增记录（排除 antd 隐藏的 measure row）
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

    // 等待分配用户弹窗
    await expect(page.getByText('分配用户').first()).toBeVisible({ timeout: 5000 });

    // 切换到"未分配用户" tab（如果存在；当前 UI 可能直接展示已分配/未分配列表）
    const unallocatedTab = page.getByText('未分配用户');
    if (await unallocatedTab.isVisible().catch(() => false)) {
      await unallocatedTab.click();
      await page.waitForTimeout(500);
    }

    // 如果有未分配用户，选择第一个
    const userCheckbox = page.locator('table tbody tr .ant-checkbox-input').first();
    if (await userCheckbox.isVisible().catch(() => false)) {
      await userCheckbox.click({ force: true });
      await page.waitForTimeout(300);

      // 点击"批量选择授权"
      const batchAuthBtn = page.getByRole('button', { name: '批量选择授权' });
      if (await batchAuthBtn.isVisible().catch(() => false)) {
        await batchAuthBtn.click();
        await page.waitForTimeout(500);
      }
    }

    // 关闭弹窗
    await page.locator('.ant-modal').last().getByRole('button', { name: /取\s*消/ }).click();
    await page.waitForTimeout(500);
  });
});
