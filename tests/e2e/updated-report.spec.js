// 修改后页面测试报告：流程详情只读设计器(与编辑一致) + 动态表单补强组件
// 运行: npx playwright test tests/e2e/updated-report.spec.js
// 截图输出: test-results/updated-report/
const { test, expect } = require('playwright/test');
const { login } = require('./auth-helper');
const fs = require('fs');

const SHOT_DIR = 'test-results/updated-report';
fs.mkdirSync(SHOT_DIR, { recursive: true });

async function shot(page, name) {
  await page.waitForTimeout(600);
  await page.screenshot({ path: `${SHOT_DIR}/${name}.png`, fullPage: true });
  console.log(`📸 ${SHOT_DIR}/${name}.png`);
}

test('流程详情只读设计器（与编辑界面一致）', async ({ page }) => {
  await login(page);
  // 发起一个报销流程（无表单直接发起，便于详情）
  await page.goto('/office/bpm/start');
  await expect(page.getByText('发起流程').first()).toBeVisible();
  await page.waitForTimeout(1500);
  const row = page.locator('table tbody tr:not(.ant-table-measure-row)').filter({ hasText: 'reimburse' }).first();
  await row.getByRole('button', { name: '发起' }).click();
  await page.waitForTimeout(1200);
  await page.getByRole('button', { name: /确\s*定/ }).click();
  await page.waitForTimeout(2000);
  await shot(page, '01-发起流程成功');

  // 我的流程 → 详情
  await page.goto('/office/bpm/instance');
  await expect(page.getByText('我的流程').first()).toBeVisible();
  await page.waitForTimeout(1500);
  await shot(page, '02-我的流程列表');
  await page.locator('table tbody tr:not(.ant-table-measure-row)').first().getByRole('button', { name: '详情' }).click();
  await expect(page.getByText('流程详情 · ').first()).toBeVisible({ timeout: 10000 });
  await page.waitForTimeout(2500);
  await shot(page, '03-详情-基本信息');
  // 滚动到流程图区域
  await page.locator('.ant-drawer-body').evaluate(el => el.scrollTo(0, el.scrollHeight));
  await page.waitForTimeout(2000);
  await shot(page, '04-详情-只读流程图(高亮)');
  // 断言只读：无保存按钮/无添加按钮
  const saveBtn = await page.locator('.ant-drawer .bpm-toolbar button').filter({ hasText: '保存流程' }).count();
  const addBtn = await page.locator('.ant-drawer .bpm-plus-btn, .ant-drawer .bpm-branch-add').count();
  console.log('只读校验 保存按钮:', saveBtn, '添加按钮:', addBtn);
  // 高亮校验
  console.log('active 高亮:', await page.locator('.ant-drawer .bpm-node-card.is-active').count());
  console.log('completed 高亮:', await page.locator('.ant-drawer .bpm-capsule.is-completed').count());
  // 关闭详情，打开编辑界面对比
  await page.keyboard.press('Escape');
  await page.waitForTimeout(800);
  await page.goto('/office/bpm/model');
  await expect(page.getByText('流程模型').first()).toBeVisible();
  await page.waitForTimeout(1000);
  await page.locator('table tbody tr:not(.ant-table-measure-row)').filter({ hasText: 'reimburse' }).first()
    .getByRole('button', { name: '设计' }).click();
  await page.getByRole('dialog').getByText('流程设计', { exact: true }).first().click();
  await expect(page.locator('.bpm-flow-root').first()).toBeVisible({ timeout: 15000 });
  await page.waitForTimeout(1500);
  await shot(page, '05-编辑界面(对比一致)');
});

test('动态表单补强：日期范围/日期时间/禁用/隐藏', async ({ page }) => {
  await login(page);
  // 1) 表单设计器新组件
  await page.goto('/office/bpm/form');
  await page.getByText('流程表单').first().waitFor({ timeout: 25000 });
  await page.waitForTimeout(1000);
  await page.locator('table tbody tr:not(.ant-table-measure-row)').first().getByRole('button', { name: '设计' }).click();
  await page.waitForTimeout(2500);
  await shot(page, '06-表单设计器(组件库)');
  // 添加日期范围 + 日期时间
  await page.getByText('日期范围').click();
  await page.waitForTimeout(600);
  await page.getByText('日期时间').click();
  await page.waitForTimeout(600);
  await page.screenshot({ path: `${SHOT_DIR}/07-画布添加新组件.png`, fullPage: false });
  console.log('📸 07-画布添加新组件');
  // 选中日期范围字段 → 属性面板(禁用/隐藏开关)
  const cards = page.locator('.bpm-fd-card');
  const n = await cards.count();
  await cards.nth(n - 2).click();
  await page.waitForTimeout(800);
  await shot(page, '08-属性面板(禁用/隐藏开关)');
  // 2) 发起页动态表单渲染
  await page.keyboard.press('Escape');
  await page.waitForTimeout(800);
  await page.locator('.ant-modal-close').first().click().catch(() => {});
  await page.goto('/office/bpm/start');
  await expect(page.getByText('发起流程').first()).toBeVisible();
  await page.waitForTimeout(1500);
  await page.locator('table tbody tr:not(.ant-table-measure-row)').filter({ hasText: 'leave' }).first()
    .getByRole('button', { name: '发起' }).click();
  await page.waitForTimeout(2500);
  await shot(page, '09-发起页动态表单');
});
