const { test, expect } = require('playwright/test');
const { login } = require('./auth-helper');

test.describe('通知公告 CRUD', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
    await page.goto('/system/notice');
    await expect(page.getByText('公告标题').first()).toBeVisible();
  });

  test('新增通知公告', async ({ page }) => {
    const timestamp = Date.now().toString();
    const title = `测试公告_${timestamp}`;

    await page.getByRole('button', { name: '新增' }).click();
    const modal = page.getByRole('dialog', { name: /新增通知公告/ });
    await expect(modal).toBeVisible();

    // 填写标题
    await modal.getByPlaceholder('请输入公告标题').fill(title);

    // 选择类型
    await modal.locator('.ant-select-selector').first().click();
    // 等待下拉选项出现，选择"通知"
    const option = page.locator('.ant-select-item-option').filter({ hasText: '通知' });
    await option.click();

    // 选择状态—默认是"正常"(radio)，不需要改

    // 提交
    await modal.getByRole('button', { name: 'OK' }).click();
    await expect(modal).toBeHidden();

    // 验证出现新记录
    const newRow = page.locator('table tbody tr', { hasText: title });
    await expect(newRow).toBeVisible({ timeout: 10000 });
  });

  test('编辑通知公告', async ({ page }) => {
    const timestamp = Date.now().toString();
    const title = `测试公告_edit_${timestamp}`;
    const updatedTitle = `${title}_updated`;

    // 先新增
    await page.getByRole('button', { name: '新增' }).click();
    const addModal = page.getByRole('dialog', { name: /新增通知公告/ });
    await expect(addModal).toBeVisible();
    await addModal.getByPlaceholder('请输入公告标题').fill(title);
    await addModal.locator('.ant-select-selector').first().click();
    await page.locator('.ant-select-item-option').filter({ hasText: '通知' }).click();
    await addModal.getByRole('button', { name: 'OK' }).click();
    await expect(addModal).toBeHidden();

    const row = page.locator('table tbody tr', { hasText: title });
    await expect(row).toBeVisible({ timeout: 10000 });

    // 编辑
    await row.getByRole('button', { name: '编辑' }).click();
    const editModal = page.getByRole('dialog', { name: /编辑通知公告/ });
    await expect(editModal).toBeVisible();

    // 修改标题
    const titleInput = editModal.getByPlaceholder('请输入公告标题');
    await titleInput.clear();
    await titleInput.fill(updatedTitle);

    await editModal.getByRole('button', { name: 'OK' }).click();
    await expect(editModal).toBeHidden();

    const updatedRow = page.locator('table tbody tr', { hasText: updatedTitle });
    await expect(updatedRow).toBeVisible({ timeout: 10000 });
  });

  test('删除通知公告', async ({ page }) => {
    const timestamp = Date.now().toString();
    const title = `测试公告_del_${timestamp}`;

    // 先新增
    await page.getByRole('button', { name: '新增' }).click();
    const addModal = page.getByRole('dialog', { name: /新增通知公告/ });
    await expect(addModal).toBeVisible();
    await addModal.getByPlaceholder('请输入公告标题').fill(title);
    await addModal.locator('.ant-select-selector').first().click();
    await page.locator('.ant-select-item-option').filter({ hasText: '通知' }).click();
    await addModal.getByRole('button', { name: 'OK' }).click();
    await expect(addModal).toBeHidden();

    const row = page.locator('table tbody tr', { hasText: title });
    await expect(row).toBeVisible({ timeout: 10000 });

    // 删除 — 注意 notice 页面的删除按钮是 danger button，没有 popconfirm
    // 注意：公告删除没有确认弹窗，直接调用 API 删除
    await row.getByRole('button', { name: '删除' }).click();
    await expect(row).toBeHidden({ timeout: 10000 });
  });
});
