const { test, expect } = require('playwright/test');
const { login } = require('./auth-helper');
const { fillInput, clickOk } = require('./dom-helper');

// 参数较多时新记录可能不在第一页: 按名称检索 (新增/修改/删除后的刷新沿用检索条件)
async function searchByName(page, name) {
  await page.getByPlaceholder('请输入参数名称').first().fill(name);
  await page.getByRole('button', { name: /搜\s*索/ }).click();
}

test.describe('参数配置 CRUD', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
    await page.goto('/system/config');
    await expect(page.getByText('参数名称').first()).toBeVisible();
  });

  test('新增参数配置', async ({ page }) => {
    const timestamp = Date.now().toString();
    const configName = `测试参数_${timestamp}`;
    const configKey = `e2e.config.key.${timestamp}`;
    const configValue = `测试值_${timestamp}`;

    await page.getByRole('button', { name: '新增' }).click();
    const modal = page.getByRole('dialog', { name: '新增参数' });
    await expect(modal).toBeVisible();

    // 填写表单
    const inputs = modal.locator('input[type="text"]');
    await inputs.nth(0).fill(configName);
    await inputs.nth(1).fill(configKey);
    await inputs.nth(2).fill(configValue);

    await modal.getByRole('button', { name: /确\s*定/ }).click();
    await expect(modal).toBeHidden();

    // 验证列表出现新记录
    await searchByName(page, configName);
    const newRow = page.locator('table tbody tr', { hasText: configName });
    await expect(newRow).toBeVisible({ timeout: 10000 });
  });

  test('修改参数配置', async ({ page }) => {
    const timestamp = Date.now().toString();
    const configName = `测试参数_edit_${timestamp}`;
    const configKey = `e2e.edit.key.${timestamp}`;
    const configValue = `测试值_${timestamp}`;
    const updatedName = `${configName}_updated`;

    // 先新增
    await page.getByRole('button', { name: '新增' }).click();
    const addModal = page.getByRole('dialog', { name: '新增参数' });
    await expect(addModal).toBeVisible();
    const addInputs = addModal.locator('input[type="text"]');
    await addInputs.nth(0).fill(configName);
    await addInputs.nth(1).fill(configKey);
    await addInputs.nth(2).fill(configValue);
    await addModal.getByRole('button', { name: /确\s*定/ }).click();
    await expect(addModal).toBeHidden();

    await searchByName(page, configName);
    const row = page.locator('table tbody tr', { hasText: configName });
    await expect(row).toBeVisible({ timeout: 10000 });

    // 修改
    await row.getByRole('button', { name: '编辑' }).click();
    const editModal = page.getByRole('dialog', { name: '编辑参数' });
    await expect(editModal).toBeVisible();
    await editModal.locator('input[type="text"]').nth(0).fill(updatedName);
    await editModal.getByRole('button', { name: /确\s*定/ }).click();
    await expect(editModal).toBeHidden();

    const updatedRow = page.locator('table tbody tr', { hasText: updatedName });
    await expect(updatedRow).toBeVisible({ timeout: 10000 });
  });

  test('删除参数配置', async ({ page }) => {
    const timestamp = Date.now().toString();
    const configName = `测试参数_del_${timestamp}`;
    const configKey = `e2e.del.key.${timestamp}`;
    const configValue = `测试值_${timestamp}`;

    // 先新增
    await page.getByRole('button', { name: '新增' }).click();
    const addModal = page.getByRole('dialog', { name: '新增参数' });
    await expect(addModal).toBeVisible();
    const inputs = addModal.locator('input[type="text"]');
    await inputs.nth(0).fill(configName);
    await inputs.nth(1).fill(configKey);
    await inputs.nth(2).fill(configValue);
    await addModal.getByRole('button', { name: /确\s*定/ }).click();
    await expect(addModal).toBeHidden();

    await searchByName(page, configName);
    const row = page.locator('table tbody tr', { hasText: configName });
    await expect(row).toBeVisible({ timeout: 10000 });

    // 删除
    await row.getByRole('button', { name: '删除' }).click();
    await page.locator('.ant-popconfirm').getByRole('button', { name: /确\s*认/ }).click();
    await expect(row).toBeHidden({ timeout: 10000 });
  });
});
