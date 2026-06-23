const { test, expect } = require('playwright/test');
const { login } = require('./auth-helper');

test.describe('用户管理 CRUD', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
    await page.goto('/system/user');
    await expect(page.getByText('用户名称').first()).toBeVisible({ timeout: 10000 });
  });

  test('列表页面正常渲染', async ({ page }) => {
    // 左侧部门树可见
    await expect(page.getByText('组织机构')).toBeVisible();
    await expect(page.getByText('若依科技').first()).toBeVisible();

    // 工具栏按钮可见
    await expect(page.getByRole('button', { name: '新增' })).toBeVisible();
    await expect(page.getByRole('button', { name: '修改' })).toBeVisible();
    await expect(page.getByRole('button', { name: '删除' })).toBeVisible();
    await expect(page.getByRole('button', { name: '导入' })).toBeVisible();
    await expect(page.getByRole('button', { name: '导出' })).toBeVisible();

    // 表格应该有数据行
    const rows = page.locator('table tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 10000 });
    const rowCount = await rows.count();
    expect(rowCount).toBeGreaterThan(0);
  });

  test('搜索用户', async ({ page }) => {
    // 清空搜索条件
    const searchInput = page.getByPlaceholder('请输入用户名称').first();
    await expect(searchInput).toBeVisible();

    // 搜索 admin 用户
    await searchInput.fill('admin');

    // 点击搜索按钮
    await page.getByRole('button', { name: '搜索' }).first().click();

    // 等待表格刷新 - 应该至少有一个结果
    await page.waitForTimeout(500);
    const rows = page.locator('table tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 10000 });
  });

  test('新增、修改并删除用户', async ({ page }) => {
    const timestamp = Date.now().toString();
    const userName = `e2e_user_${timestamp}`;
    const nickName = `测试用户_${timestamp}`;
    const nickNameUpdated = `${nickName}_updated`;
    const phonenumber = `138${timestamp.slice(-8)}`;
    const email = `${userName}@test.com`;

    // ── 新增用户 ──
    await page.getByRole('button', { name: '新增' }).click();
    // 等待弹窗出现
    await expect(page.getByText('添加用户')).toBeVisible({ timeout: 5000 });

    // 填写表单
    await page.getByPlaceholder('请输入用户昵称').fill(nickName);
    await page.getByPlaceholder('请输入用户名称').fill(userName);
    await page.getByPlaceholder('请输入用户密码').fill('123456');
    await page.getByPlaceholder('请输入手机号码').fill(phonenumber);
    await page.getByPlaceholder('请输入邮箱').fill(email);

    // 提交表单 - 点击"确定"按钮
    await page.getByRole('button', { name: '确定' }).first().click();

    // 等待弹窗关闭
    await page.waitForTimeout(1000);

    // 确认列表中出现了新增记录
    const newRow = page.locator('table tbody tr', { hasText: userName });
    await expect(newRow).toBeVisible({ timeout: 10000 });

    // ── 修改用户 ──
    await newRow.getByRole('button', { name: '编辑' }).click();
    await expect(page.getByText('修改用户')).toBeVisible({ timeout: 5000 });

    // 修改用户昵称
    const nickInput = page.getByDisplayValue(nickName);
    await nickInput.fill(nickNameUpdated);

    // 提交修改
    await page.getByRole('button', { name: '确定' }).first().click();
    await page.waitForTimeout(1000);

    // 确认列表中出现了修改后的数据
    const updatedRow = page.locator('table tbody tr', { hasText: nickNameUpdated });
    await expect(updatedRow).toBeVisible({ timeout: 10000 });

    // ── 删除用户 ──
    await updatedRow.getByRole('button', { name: '删除' }).click();
    // 等待确认弹窗
    await page.waitForTimeout(500);

    // 确认删除弹窗的 OK 按钮 (antd Popconfirm)
    // Popconfirm 渲染在 tooltip 层
    const deleteConfirm = page.locator('.ant-popconfirm').getByRole('button', { name: 'OK' });
    if (await deleteConfirm.isVisible()) {
      await deleteConfirm.click();
    } else {
      // 备选: 查找确认按钮
      await page.locator('.ant-popover-inner').getByRole('button', { name: 'OK' }).click();
    }

    await expect(updatedRow).toBeHidden({ timeout: 10000 });
  });

  test('重置用户密码', async ({ page }) => {
    // 找第一个可操作的用户（不是 admin）
    const rows = page.locator('table tbody tr');
    const rowCount = await rows.count();

    let targetRow = null;
    for (let i = 0; i < rowCount; i++) {
      const row = rows.nth(i);
      const moreBtn = row.getByRole('button', { name: '更多' });
      if (await moreBtn.isVisible()) {
        targetRow = row;
        break;
      }
    }

    if (!targetRow) {
      test.skip('没有找到可操作的用户');
      return;
    }

    // 点"更多" → 重置密码
    await targetRow.getByRole('button', { name: '更多' }).click();

    // 点击下拉菜单中的"重置密码"
    const menuItem = page.locator('.ant-dropdown-menu').getByText('重置密码');
    await menuItem.click();

    // 等待重置密码弹窗
    await expect(page.getByText('新密码').first()).toBeVisible({ timeout: 3000 });

    // 输入新密码
    const passwordInput = page.locator('.ant-modal input[type="password"]');
    await passwordInput.fill('newpassword123');

    // 点击确定
    await page.getByRole('button', { name: '确定' }).last().click();

    // 等待弹窗关闭
    await page.waitForTimeout(1000);
  });

  test('分配用户角色', async ({ page }) => {
    // 找第一个有"更多"按钮的行
    const rows = page.locator('table tbody tr');
    const rowCount = await rows.count();

    let targetRow = null;
    for (let i = 0; i < rowCount; i++) {
      const row = rows.nth(i);
      const moreBtn = row.getByRole('button', { name: '更多' });
      if (await moreBtn.isVisible()) {
        targetRow = row;
        break;
      }
    }

    if (!targetRow) {
      test.skip('没有找到可操作的用户');
      return;
    }

    // 点"更多" → 分配角色
    await targetRow.getByRole('button', { name: '更多' }).click();

    // 点击下拉菜单中的"分配角色"
    const menuItem = page.locator('.ant-dropdown-menu').getByText('分配角色');
    await menuItem.click();

    // 等待分配角色弹窗
    await expect(page.getByText('分配角色').first()).toBeVisible({ timeout: 5000 });

    // 选择一个角色（如果有选项）
    const roleSelect = page.locator('.ant-select').last();
    await roleSelect.click();
    await page.waitForTimeout(300);

    // 选择第一个可用的角色
    const roleOption = page.locator('.ant-select-item-option').first();
    if (await roleOption.isVisible()) {
      await roleOption.click();
    }

    // 点击确定
    await page.locator('.ant-modal').last().getByRole('button', { name: '确定' }).click();

    // 等待弹窗关闭
    await page.waitForTimeout(1000);
  });
});
