const { test, expect } = require('playwright/test');
const { login } = require('./auth-helper');

test.describe('岗位管理 CRUD', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
    await page.goto('/system/post');
    await expect(page.getByText('岗位名称').first()).toBeVisible();
  });

  test('新增、修改并删除岗位', async ({ page }) => {
    const timestamp = Date.now().toString();
    const postCode = `E2E_${timestamp}`;
    const postName = `测试岗位_${timestamp}`;
    const postNameUpdated = `${postName}_updated`;

    // 新增
    await page.getByRole('button', { name: '新增' }).click();
    const addModal = page.getByRole('dialog', { name: '新增岗位' });
    await expect(addModal).toBeVisible();
    await addModal.getByPlaceholder('请输入岗位编码').fill(postCode);
    await addModal.getByPlaceholder('请输入岗位名称').fill(postName);
    await addModal.getByRole('button', { name: /确\s*定/ }).click();
    await expect(addModal).toBeHidden();

    // 确认列表中出现新增记录
    const newRow = page.locator('table tbody tr', { hasText: postName });
    await expect(newRow).toBeVisible({ timeout: 10000 });

    // 修改
    await newRow.getByRole('button', { name: '编辑' }).click();
    const editModal = page.getByRole('dialog', { name: '修改岗位' });
    await expect(editModal).toBeVisible();
    await editModal.getByPlaceholder('请输入岗位名称').fill(postNameUpdated);
    await editModal.getByRole('button', { name: /确\s*定/ }).click();
    await expect(editModal).toBeHidden();

    const updatedRow = page.locator('table tbody tr', { hasText: postNameUpdated });
    await expect(updatedRow).toBeVisible({ timeout: 10000 });

    // 删除
    await updatedRow.getByRole('button', { name: '删除' }).click();
    await page.getByRole('tooltip').getByRole('button', { name: /确\s*定/ }).click();
    await expect(updatedRow).toBeHidden({ timeout: 10000 });
  });
});
