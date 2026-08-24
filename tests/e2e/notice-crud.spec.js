const { test, expect } = require('playwright/test');
const { login } = require('./auth-helper');

async function fillNoticeForm(page, modal, title) {
  // 填写标题（类型默认"通知"、状态默认"正常"，无需再选）
  await modal.locator('input[type="text"]').first().fill(title);

  // 显式选择类型"通知"，确保表单值正确注册
  // antd Select 下拉选项渲染在 body 级 portal 中，需用 page 级定位
  const typeSelect = modal.locator('.ant-select').first();
  await typeSelect.click();
  await page.locator('.ant-select-item-option').filter({ hasText: '通知' }).click();
}

async function submitNoticeModal(page, modal) {
  // 通过 form.requestSubmit 提交，兼容 antd Modal 在自定义事件下的提交
  await modal.locator('form').evaluate(form => form.requestSubmit());
  await expect(page.getByText('创建成功').or(page.getByText('操作成功'))).toBeVisible({ timeout: 10000 });
  await expect(modal).toBeHidden({ timeout: 10000 });
}

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

    await fillNoticeForm(page, modal, title);
    await submitNoticeModal(page, modal);

    // 验证出现新记录
    const newRow = page.locator('table tbody tr:not(.ant-table-measure-row)', { hasText: title });
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
    await fillNoticeForm(page, addModal, title);
    await submitNoticeModal(page, addModal);

    const row = page.locator('table tbody tr:not(.ant-table-measure-row)', { hasText: title });
    await expect(row).toBeVisible({ timeout: 10000 });

    // 编辑（notice 操作列按钮文本为"编辑"）
    await row.locator('button').filter({ hasText: '编辑' }).click();
    const editModal = page.getByRole('dialog', { name: /编辑通知公告/ });
    await expect(editModal).toBeVisible();

    // 修改标题
    const titleInput = editModal.locator('input[type="text"]').first();
    await titleInput.clear();
    await titleInput.fill(updatedTitle);

    await editModal.locator('form').evaluate(form => form.requestSubmit());
    await expect(page.getByText('更新成功').or(page.getByText('操作成功'))).toBeVisible({ timeout: 10000 });
    await expect(editModal).toBeHidden({ timeout: 10000 });

    const updatedRow = page.locator('table tbody tr:not(.ant-table-measure-row)', { hasText: updatedTitle });
    await expect(updatedRow).toBeVisible({ timeout: 10000 });
  });

  test('删除通知公告', async ({ page }) => {
    const timestamp = Date.now().toString();
    const title = `测试公告_del_${timestamp}`;

    // 先新增
    await page.getByRole('button', { name: '新增' }).click();
    const addModal = page.getByRole('dialog', { name: /新增通知公告/ });
    await expect(addModal).toBeVisible();
    await fillNoticeForm(page, addModal, title);
    await submitNoticeModal(page, addModal);

    const row = page.locator('table tbody tr:not(.ant-table-measure-row)', { hasText: title });
    await expect(row).toBeVisible({ timeout: 10000 });

    // 删除 — 注意 notice 页面的删除按钮是 danger button，没有 popconfirm
    // 注意：公告删除没有确认弹窗，直接调用 API 删除
    await row.locator('button').filter({ hasText: '删除' }).click();
    await expect(row).toBeHidden({ timeout: 10000 });
  });
});
