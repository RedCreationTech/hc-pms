// 办公一体化 E2E：登录 → 发起请假 → 我的待办审批通过 → 请假状态 → 办公报表
const { test, expect } = require('playwright/test');
const { login } = require('./auth-helper');

test.describe('办公一体化业务流', () => {
  test('请假申请全流程', async ({ page }) => {
    // 1. 登录并进入请假申请
    await login(page);
    await page.goto('/office/oa/leave');
    await expect(page.getByText('请假申请').first()).toBeVisible();

    // 2. 发起请假
    const ts = Date.now().toString();
    await page.getByRole('button', { name: '发起请假' }).click();
    const modal = page.getByRole('dialog', { name: /发起请假申请/ });
    await expect(modal).toBeVisible();

    // 填天数（input-number 内嵌 input）
    await modal.locator('input[type="number"], input[role="spinbutton"]').first().fill('3');
    // 填原因（textarea）
    await modal.locator('textarea').fill(`E2E测试请假_${ts}`);
    await modal.locator('form').evaluate(form => form.requestSubmit());

    // 提交成功提示 + 弹窗关闭
    await expect(page.getByText('请假申请已提交，进入审批')).toBeVisible({ timeout: 10000 });
    await expect(modal).toBeHidden({ timeout: 10000 });

    // 3. 列表出现新请假单（审批中）
    const newRow = page.locator('table tbody tr:not(.ant-table-measure-row)', { hasText: `E2E测试请假_${ts}` });
    await expect(newRow).toBeVisible({ timeout: 10000 });
    await expect(newRow.getByText('审批中')).toBeVisible();

    // 4. 进入我的待办，审批通过第 1 级（部门经理）
    await page.goto('/office/bpm/todo');
    await expect(page.getByText('我的待办').first()).toBeVisible();
    const firstTask = page.locator('table tbody tr:not(.ant-table-measure-row)', { hasText: '部门经理审批' }).first();
    await firstTask.getByRole('button', { name: '通过' }).click();
    const approveModal = page.getByRole('dialog', { name: /审批通过/ });
    await expect(approveModal).toBeVisible();
    await approveModal.locator('textarea').fill('E2E同意');
    await approveModal.locator('form').evaluate(form => form.requestSubmit());
    await expect(page.getByText('审批通过').first()).toBeVisible({ timeout: 10000 });
    await expect(approveModal).toBeHidden({ timeout: 10000 });

    // 5. 回请假列表：多级审批中，状态仍为"审批中"（下一级分管领导待审）
    await page.goto('/office/oa/leave');
    await expect(page.getByText(`E2E测试请假_${ts}`).first()).toBeVisible();
    await expect(page.getByText('审批中').first()).toBeVisible();
    // 进入我的流程，能看到该流程仍进行中
    await page.goto('/office/bpm/instance');
    await expect(page.getByText('我的流程').first()).toBeVisible();
    await expect(page.getByText('审批中').first()).toBeVisible();
  });

  test('办公报表加载', async ({ page }) => {
    await login(page);
    await page.goto('/office/report');
    await expect(page.getByText('办公报表').first()).toBeVisible();
    // 首屏数据自动加载（token 已在 init 恢复），等待统计卡片出现
    await expect(page.getByText('请假单总数').first()).toBeVisible({ timeout: 15000 });
    await expect(page.getByText('报销单总数').first()).toBeVisible({ timeout: 15000 });
  });
});
