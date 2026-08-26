// 设计器能力 + BPM 全链路综合测试报告
// 运行: npx playwright test tests/e2e/designer-report.spec.js
// 截图: test-results/designer-report/
const { test, expect } = require('playwright/test');
const { login } = require('./auth-helper');
const fs = require('fs');

const SHOT_DIR = 'test-results/designer-report';
fs.mkdirSync(SHOT_DIR, { recursive: true });

async function shot(page, name) {
  await page.waitForTimeout(700);
  await page.screenshot({ path: `${SHOT_DIR}/${name}.png`, fullPage: true });
  console.log(`📸 ${SHOT_DIR}/${name}.png`);
}

test('设计器能力 + BPM 全链路', async ({ page }) => {
  test.setTimeout(300000);
  const errs = [];
  page.on('pageerror', e => errs.push('PE: ' + e.message.slice(0, 120)));

  await login(page);

  // ═══ 1. 表单设计器总览 ═══
  await page.goto('/office/bpm/form');
  await page.getByText('流程表单').first().waitFor({ timeout: 25000 });
  await page.waitForTimeout(1000);
  await page.locator('table tbody tr:not(.ant-table-measure-row)').first()
    .getByRole('button', { name: '设计' }).click();
  await page.waitForTimeout(2500);
  await shot(page, '01-表单设计器总览(组件库23种+画布+属性+模板)');

  // ═══ 2. 模板一键填充 ═══
  await page.locator('.bpm-fd-header .ant-select').first().click();
  await page.waitForTimeout(800);
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')
    .filter({ hasText: '报销申请' }).click();
  await page.waitForTimeout(800);
  await shot(page, '02-模板一键填充(报销申请含子表单)');

  // ═══ 3. 拖拽添加字段 ═══
  const libItem = page.locator('.bpm-fd-lib-item').filter({ hasText: '富文本' });
  await libItem.dragTo(page.locator('.bpm-fd-canvas'));
  await page.waitForTimeout(800);
  await shot(page, '03-拖拽添加富文本字段');

  // ═══ 4. 属性配置（选中字段：校验/栅格/事件）═══
  const cards = page.locator('.bpm-fd-card');
  await cards.nth(1).click();  // 报销金额
  await page.waitForTimeout(600);
  await page.locator('.bpm-fd-props').evaluate(el => el.scrollTo(0, 0));
  await page.waitForTimeout(400);
  await shot(page, '04-属性配置(校验/栅格/事件)');

  // ═══ 5. 子表单配置 ═══
  await page.locator('.bpm-fd-card').filter({ hasText: '费用明细' }).first().click();
  await page.waitForTimeout(600);
  await page.locator('.bpm-fd-props').evaluate(el => el.scrollTo(0, el.scrollHeight));
  await page.waitForTimeout(400);
  await shot(page, '05-子表单子字段管理');

  // ═══ 6. 保存表单 ═══
  await page.locator('.bpm-fd-header').getByRole('button', { name: '保存表单' }).click();
  await page.waitForTimeout(1500);
  await shot(page, '06-表单保存成功');

  // ═══ 7. 发起页全组件渲染 ═══
  await page.goto('/office/bpm/start');
  await page.getByText('发起流程').first().waitFor({ timeout: 25000 });
  await page.waitForTimeout(1500);
  await page.locator('table tbody tr.ant-table-row').filter({ hasText: 'leaveApproval' })
    .getByRole('button', { name: '发起' }).click();
  await page.waitForTimeout(3000);
  await shot(page, '07-发起页全组件渲染(输入/数字/子表单/富文本)');

  // ═══ 8. 填表 + 校验 ═══
  const fi = page.locator('.ant-modal .ant-form-item');
  const reason = fi.filter({ hasText: '报销事由' }).first().locator('input');
  await reason.click();
  await reason.pressSequentially('客户招待费');
  const amount = fi.filter({ hasText: '报销金额' }).first().locator('input');
  await amount.click();
  await amount.pressSequentially('500');
  await page.getByRole('button', { name: /添加一行/ }).click();
  await page.waitForTimeout(600);
  const subInputs = fi.filter({ hasText: '费用明细' }).locator('input');
  await subInputs.first().click();
  await subInputs.first().pressSequentially('餐费');
  await page.waitForTimeout(600);
  await shot(page, '08-填表完成(子表单一行)');

  // ═══ 9. 提交成功 ═══
  await page.getByRole('button', { name: /确\s*定/ }).click();
  await expect(page.getByText('流程发起成功')).toBeVisible({ timeout: 10000 });
  await shot(page, '09-提交成功');

  // ═══ 10. 待办审批（表单回显）═══
  await page.goto('/office/bpm/todo');
  await page.getByText('我的待办').first().waitFor({ timeout: 25000 });
  await page.waitForTimeout(2500);
  const taskRows = page.locator('table tbody tr.ant-table-row');
  await taskRows.last().getByRole('button', { name: '通过' }).click();
  await page.waitForTimeout(3000);
  await shot(page, '10-审批弹窗(子表单回显+意见)');
  const opinion = page.locator('.ant-modal textarea[placeholder*="审批意见"]');
  await opinion.fill('同意');
  await page.getByRole('button', { name: /确\s*定/ }).click();
  await page.waitForTimeout(2000);

  // ═══ 11. 流程追踪 ═══
  await page.goto('/office/bpm/instance');
  await page.getByText('我的流程').first().waitFor({ timeout: 25000 });
  await page.waitForTimeout(2000);
  await page.locator('table tbody tr.ant-table-row').first()
    .getByRole('button', { name: '详情' }).click();
  await expect(page.getByText('流程详情 · ').first()).toBeVisible({ timeout: 10000 });
  await page.waitForTimeout(2500);
  await shot(page, '11-流程详情(审批历史+表单数据)');

  console.log('errs:', JSON.stringify(errs));
});
