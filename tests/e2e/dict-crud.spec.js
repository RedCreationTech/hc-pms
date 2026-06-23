const { test, expect } = require('playwright/test');
const { login } = require('./auth-helper');

test.describe('字典管理 CRUD', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
    await page.goto('/system/dict');
    await expect(page.getByText('字典名称').first()).toBeVisible();
  });

  test('新增字典类型', async ({ page }) => {
    const timestamp = Date.now().toString();
    const dictName = `测试字典_${timestamp}`;
    const dictType = `e2e_dict_${timestamp}`;

    await page.getByRole('button', { name: '新增' }).click();
    const modal = page.getByRole('dialog', { name: /新增字典类型/ });
    await expect(modal).toBeVisible();

    // 填写表单
    const inputs = modal.locator('input');
    await inputs.nth(0).fill(dictName);
    await inputs.nth(1).fill(dictType);

    await modal.getByRole('button', { name: '确定' }).click();
    await expect(modal).toBeHidden();

    // 验证列表出现新记录
    const newRow = page.locator('table tbody tr', { hasText: dictName });
    await expect(newRow).toBeVisible({ timeout: 10000 });
  });

  test('修改字典类型', async ({ page }) => {
    const timestamp = Date.now().toString();
    const dictName = `测试字典_edit_${timestamp}`;
    const dictType = `e2e_edit_${timestamp}`;
    const updatedName = `${dictName}_updated`;

    // 先新增
    await page.getByRole('button', { name: '新增' }).click();
    const addModal = page.getByRole('dialog', { name: /新增字典类型/ });
    await expect(addModal).toBeVisible();
    const addInputs = addModal.locator('input');
    await addInputs.nth(0).fill(dictName);
    await addInputs.nth(1).fill(dictType);
    await addModal.getByRole('button', { name: '确定' }).click();
    await expect(addModal).toBeHidden();

    const row = page.locator('table tbody tr', { hasText: dictName });
    await expect(row).toBeVisible({ timeout: 10000 });

    // 修改
    await row.getByRole('button', { name: '编辑' }).click();
    const editModal = page.getByRole('dialog', { name: /编辑字典类型/ });
    await expect(editModal).toBeVisible();
    await editModal.locator('input').nth(0).fill(updatedName);
    await editModal.getByRole('button', { name: '确定' }).click();
    await expect(editModal).toBeHidden();

    const updatedRow = page.locator('table tbody tr', { hasText: updatedName });
    await expect(updatedRow).toBeVisible({ timeout: 10000 });
  });

  test('删除字典类型', async ({ page }) => {
    const timestamp = Date.now().toString();
    const dictName = `测试字典_del_${timestamp}`;
    const dictType = `e2e_del_${timestamp}`;

    // 先新增
    await page.getByRole('button', { name: '新增' }).click();
    const addModal = page.getByRole('dialog', { name: /新增字典类型/ });
    await expect(addModal).toBeVisible();
    const addInputs = addModal.locator('input');
    await addInputs.nth(0).fill(dictName);
    await addInputs.nth(1).fill(dictType);
    await addModal.getByRole('button', { name: '确定' }).click();
    await expect(addModal).toBeHidden();

    const row = page.locator('table tbody tr', { hasText: dictName });
    await expect(row).toBeVisible({ timeout: 10000 });

    // 删除
    await row.getByRole('button', { name: '删除' }).click();
    // Popconfirm — 确认按钮在 tooltip 层里
    await page.locator('.ant-popconfirm').getByRole('button', { name: '确认' }).click();
    await expect(row).toBeHidden({ timeout: 10000 });
  });

  test('字典数据 CRUD — 新增、修改、删除字典数据项', async ({ page }) => {
    const timestamp = Date.now().toString();
    const dictName = `测试字典_data_${timestamp}`;
    const dictType = `e2e_data_${timestamp}`;
    const label = `标签_${timestamp}`;
    const value = `val_${timestamp}`;
    const updatedLabel = `${label}_updated`;

    // 创建字典类型
    await page.getByRole('button', { name: '新增' }).click();
    const typeModal = page.getByRole('dialog', { name: /新增字典类型/ });
    await expect(typeModal).toBeVisible();
    await typeModal.locator('input').nth(0).fill(dictName);
    await typeModal.locator('input').nth(1).fill(dictType);
    await typeModal.getByRole('button', { name: '确定' }).click();
    await expect(typeModal).toBeHidden();

    const typeRow = page.locator('table tbody tr', { hasText: dictName });
    await expect(typeRow).toBeVisible({ timeout: 10000 });

    // 点击"字典数据"进入数据列表
    await typeRow.getByRole('button', { name: '字典数据' }).click();

    // 确认看到数据段标题
    await expect(page.getByText(`字典数据 — ${dictName}`).first()).toBeVisible({ timeout: 10000 });

    // 新增字典数据 — 数据段的"新增"按钮是页面上的第二个
    const addButtons = page.getByRole('button', { name: '新增' });
    await addButtons.last().click();
    const dataModal = page.getByRole('dialog', { name: /新增字典数据/ });
    await expect(dataModal).toBeVisible();
    await dataModal.locator('input').nth(0).fill(label);
    await dataModal.locator('input').nth(1).fill(value);
    await dataModal.getByRole('button', { name: '确定' }).click();
    await expect(dataModal).toBeHidden();

    // 验证数据出现 — 数据表是页面上的第二个 table
    const dataTable = page.locator('table').nth(1);
    await expect(dataTable.locator('tbody tr', { hasText: label })).toBeVisible({ timeout: 10000 });

    // 编辑字典数据
    const dataRow = dataTable.locator('tbody tr', { hasText: label });
    await dataRow.getByRole('button', { name: '编辑' }).click();
    const editDataModal = page.getByRole('dialog', { name: /编辑字典数据/ });
    await expect(editDataModal).toBeVisible();
    await editDataModal.locator('input').nth(0).fill(updatedLabel);
    await editDataModal.getByRole('button', { name: '确定' }).click();
    await expect(editDataModal).toBeHidden();

    const updatedDataRow = dataTable.locator('tbody tr', { hasText: updatedLabel });
    await expect(updatedDataRow).toBeVisible({ timeout: 10000 });

    // 删除字典数据
    await updatedDataRow.getByRole('button', { name: '删除' }).click();
    await page.locator('.ant-popconfirm').getByRole('button', { name: '确认' }).click();
    await expect(updatedDataRow).toBeHidden({ timeout: 10000 });
  });
});
