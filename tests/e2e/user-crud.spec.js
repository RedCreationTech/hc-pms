const { test, expect } = require('playwright/test');
const { login } = require('./auth-helper');

// 用户弹窗是自定义实现（非 antd Modal），直接定位包含昵称输入框的 form 元素
function userModal(page, title) {
  return page.locator('form').filter({ has: page.locator('input[placeholder="请输入用户昵称"]') }).last();
}

test.describe('用户管理 CRUD', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
    await page.goto('/system/user');
    await expect(page.getByText('用户名称').first()).toBeVisible({ timeout: 10000 });
  });

  test('列表页面正常渲染', async ({ page }) => {
    // 左侧部门树可见
    await expect(page.getByText('组织机构')).toBeVisible();
    await expect(page.getByText('红创科技').first()).toBeVisible();

    // 工具栏按钮可见（表内行操作按钮也有"修改"文本，取第一个/限定工具栏）
    await expect(page.getByRole('button', { name: '新增' }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: '修改' }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: '删除' }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: '导入' }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: '导出' }).first()).toBeVisible();

    // 表格应该有数据行（排除 antd 隐藏的 measure row）
    const rows = page.locator('table tbody tr:not(.ant-table-measure-row)');
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

    // 等待表格刷新 - 应该至少有一个结果（排除 antd 隐藏的 measure row）
    await page.waitForTimeout(500);
    const rows = page.locator('table tbody tr:not(.ant-table-measure-row)');
    await expect(rows.first()).toBeVisible({ timeout: 10000 });
  });

  test('新增、修改并删除用户', async ({ page }) => {
    const timestamp = Date.now().toString();
    // user_name 后端限制 2-20 字符，e2e_user_+13位会超长，缩短生成
    const userName = `e2e_${timestamp}`;
    const nickName = `测试_${timestamp.slice(-8)}`;
    const nickNameUpdated = `${nickName}_u`;
    const phonenumber = `138${timestamp.slice(-8)}`;
    const email = `${userName}@test.com`;

    // ── 新增用户 ──
    await page.getByRole('button', { name: '新增' }).click();
    // 等待弹窗出现（通过自定义 h3 标题定位）
    await expect(page.getByRole('heading', { name: '添加用户' })).toBeVisible({ timeout: 5000 });
    const addModal = userModal(page, '添加用户');

    // 填写表单（在弹窗内定位，避免匹配到搜索框）
    await addModal.getByPlaceholder('请输入用户昵称').fill(nickName);
    await addModal.getByPlaceholder('请输入用户名称').fill(userName);
    await addModal.getByPlaceholder('请输入用户密码').fill('123456');
    await addModal.getByPlaceholder('请输入手机号码').fill(phonenumber);
    await addModal.getByPlaceholder('请输入邮箱').fill(email);

    // 提交表单 - 点击 submit 按钮（antd Form 只认按钮触发的 submit 事件）
    await addModal.locator('button[type="submit"]').click();

    // 等待弹窗关闭
    await expect(page.getByRole('heading', { name: '添加用户' })).toBeHidden({ timeout: 10000 });

    // 确认列表中出现了新增记录（排除 antd 隐藏的 measure row）
    const newRow = page.locator('table tbody tr:not(.ant-table-measure-row)', { hasText: userName });
    await expect(newRow).toBeVisible({ timeout: 10000 });

    // ── 修改用户 ──
    await newRow.locator('button').filter({ hasText: '修改' }).click();
    await expect(page.getByRole('heading', { name: '修改用户' })).toBeVisible({ timeout: 5000 });

    // 修改用户昵称（编辑弹窗内昵称输入框 placeholder 与新增一致）
    const editModal = userModal(page, '修改用户');
    const nickInput = editModal.getByPlaceholder('请输入用户昵称');
    await nickInput.fill(nickNameUpdated);

    // 提交修改
    await editModal.locator('button[type="submit"]').click();
    await expect(page.getByRole('heading', { name: '修改用户' })).toBeHidden({ timeout: 10000 });

    // 确认列表中出现了修改后的数据（排除 antd 隐藏的 measure row）
    const updatedRow = page.locator('table tbody tr:not(.ant-table-measure-row)', { hasText: nickNameUpdated });
    await expect(updatedRow).toBeVisible({ timeout: 10000 });

    // ── 删除用户 ──
    // 用户删除按钮直接触发（无 Popconfirm），点击后行即消失
    await updatedRow.locator('button').filter({ hasText: '删除' }).click();

    await expect(updatedRow).toBeHidden({ timeout: 10000 });
  });

  test('重置用户密码', async ({ page }) => {
    // 找第一个可操作的用户（不是 admin，排除 antd 隐藏的 measure row）
    const rows = page.locator('table tbody tr:not(.ant-table-measure-row)');
    const rowCount = await rows.count();

    let targetRow = null;
    for (let i = 0; i < rowCount; i++) {
      const row = rows.nth(i);
      const moreBtn = row.getByRole('button', { name: '更多' });
      if (await moreBtn.isVisible().catch(() => false)) {
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

    // 输入新密码（重置密码弹窗是自定义实现，非 antd Modal）
    const passwordInput = page.locator('input[type="password"]').last();
    await passwordInput.fill('newpassword123');

    // 点击确定
    await page.getByRole('button', { name: /确\s*定/ }).last().click();

    // 等待弹窗关闭
    await page.waitForTimeout(1000);
  });

  test('分配用户角色', async ({ page }) => {
    // 找第一个有"更多"按钮的行（排除 antd 隐藏的 measure row）
    const rows = page.locator('table tbody tr:not(.ant-table-measure-row)');
    const rowCount = await rows.count();

    let targetRow = null;
    for (let i = 0; i < rowCount; i++) {
      const row = rows.nth(i);
      const moreBtn = row.getByRole('button', { name: '更多' });
      if (await moreBtn.isVisible().catch(() => false)) {
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

    // 等待分配角色弹窗（自定义实现，通过 h3 标题定位弹窗容器）
    await expect(page.getByRole('heading', { name: /分配角色/ })).toBeVisible({ timeout: 5000 });
    const allocDialog = page.getByRole('heading', { name: /分配角色/ }).locator('xpath=ancestor::div[contains(@style,"position: fixed")]');

    // 选择第一个角色
    const roleSelect = allocDialog.locator('.ant-select').last();
    await roleSelect.click();
    await page.waitForTimeout(300);

    // 选择第一个可用的角色（dropdown 选项可能被遮罩层遮挡，force 点击）
    const roleOption = page.locator('.ant-select-item-option').first();
    if (await roleOption.isVisible().catch(() => false)) {
      await roleOption.click({ force: true });
    }

    // 点击确定
    await allocDialog.getByRole('button', { name: /确\s*定/ }).click();

    // 等待弹窗关闭
    await page.waitForTimeout(1000);
  });
});
