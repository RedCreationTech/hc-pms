// BPM 办公一体化前端功能截图报告（第二辑）：发起流程/动态表单/流程详情/条件规则/字段权限
// 运行: npx playwright test tests/e2e/frontend-bpm-report.spec.js
// 截图输出到: test-results/frontend-report/
const { test, expect } = require('playwright/test');
const { login } = require('./auth-helper');
const fs = require('fs');

const SHOT_DIR = 'test-results/frontend-report';
fs.mkdirSync(SHOT_DIR, { recursive: true });

async function shot(page, name) {
  await page.waitForTimeout(600);
  await page.screenshot({ path: `${SHOT_DIR}/${name}.png`, fullPage: true });
  console.log(`📸 ${SHOT_DIR}/${name}.png`);
}

test('BPM 发起流程 + 流程详情截图', async ({ page }) => {
  await login(page);

  // 1. 发起流程页（模型列表）
  await page.goto('/office/bpm/start');
  await expect(page.getByText('发起流程').first()).toBeVisible();
  await page.waitForTimeout(1500);
  await shot(page, '18-发起流程列表');

  // 2. 打开请假动态表单发起弹窗
  const leaveRow = page.locator('table tbody tr:not(.ant-table-measure-row)').filter({ hasText: 'leaveApproval' }).first();
  await leaveRow.getByRole('button', { name: '发起' }).click();
  const modal = page.getByRole('dialog');
  await expect(modal).toBeVisible();
  await page.waitForTimeout(1500);
  await shot(page, '19-动态表单发起弹窗');

  // 3. 填表提交
  const ts = Date.now().toString();
  const reasonInp = modal.locator('input').first();
  await reasonInp.click();
  await reasonInp.pressSequentially(`前端BPM截图_${ts}`);
  const daysInp = modal.locator('input[role="spinbutton"], input[type="number"]').first();
  await daysInp.click();
  await daysInp.pressSequentially('2');
  await page.waitForTimeout(400);
  await shot(page, '20-填表完成');
  await modal.getByRole('button', { name: /确\s*定/ }).click();
  await expect(page.getByText('流程发起成功')).toBeVisible({ timeout: 10000 });
  await shot(page, '21-发起成功');

  // 4. 我的流程 → 流程详情抽屉
  await page.goto('/office/bpm/instance');
  await expect(page.getByText('我的流程').first()).toBeVisible();
  await page.waitForTimeout(1500);
  await shot(page, '22-我的流程列表');
  const irow = page.locator('table tbody tr:not(.ant-table-measure-row)').first();
  await irow.getByRole('button', { name: '详情' }).click();
  await expect(page.getByText('流程详情 · ').first()).toBeVisible({ timeout: 10000 });
  await page.waitForTimeout(1500);
  await shot(page, '23-流程详情-基本信息');
});

test('BPM 流程设计器：条件规则编辑器 + 字段权限截图', async ({ page }) => {
  await login(page);

  // 1. 流程模型列表
  await page.goto('/office/bpm/model');
  await expect(page.getByText('流程模型').first()).toBeVisible();
  await page.waitForTimeout(1000);
  await shot(page, '24-流程模型列表');

  // 2. 打开报销审批设计器 → 流程设计 tab
  const row = page.locator('table tbody tr:not(.ant-table-measure-row)').filter({ hasText: 'reimburse' }).first();
  await row.getByRole('button', { name: '设计' }).click();
  const wrap = page; // 模型设计器为独立页面 (原为弹窗)
  await expect(page.getByText('流程模型设计').first()).toBeVisible({ timeout: 15000 });
  await wrap.getByText('流程设计', { exact: true }).first().click();
  await expect(page.locator('.bpm-flow-root').first()).toBeVisible({ timeout: 15000 });
  await page.waitForTimeout(1500);
  await shot(page, '25-流程设计器');

  // 3. 点条件标签打开网关配置 → 条件规则模式
  await page.locator('.bpm-branch-label').first().click();
  await expect(page.locator('.bpm-config').first()).toBeVisible({ timeout: 8000 });
  await page.waitForTimeout(800);
  await page.locator('.bpm-config .ant-select').first().click();
  await page.waitForTimeout(600);
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option').filter({ hasText: '条件规则' }).click();
  await page.waitForTimeout(800);
  await shot(page, '26-条件规则编辑器');

  // 4. 关闭配置 → 切换到表单设计 tab → 字段权限
  await page.keyboard.press('Escape');
  await page.waitForTimeout(800);
  await wrap.getByText('表单设计', { exact: true }).first().click();
  await page.waitForTimeout(1000);
  // 选动态表单（leaveApproval 已关联请假申请单）
  await wrap.locator('.ant-radio-wrapper').filter({ hasText: '动态表单' }).click();
  await page.waitForTimeout(600);
  await wrap.locator('.ant-select:visible').first().click();
  await page.waitForTimeout(800);
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option').first().click();
  await page.waitForTimeout(1200);
  await shot(page, '27-字段权限配置');
});
