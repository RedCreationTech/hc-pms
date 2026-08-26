// 完整流程演示：配置动态表单 → 配置流程 → 发起提交 → 审批通过 → 详情追踪
// 运行: npx playwright test tests/e2e/full-demo-report.spec.js
// 截图: test-results/full-demo/
const { test, expect } = require('playwright/test');
const { login } = require('./auth-helper');
const fs = require('fs');

const SHOT_DIR = 'test-results/full-demo';
fs.mkdirSync(SHOT_DIR, { recursive: true });

async function shot(page, name) {
  await page.waitForTimeout(700);
  await page.screenshot({ path: `${SHOT_DIR}/${name}.png`, fullPage: true });
  console.log(`📸 ${SHOT_DIR}/${name}.png`);
}

test('完整流程演示：配置表单+流程 → 发起 → 审批通过', async ({ page }) => {
  test.setTimeout(300000);
  const errs = [];
  page.on('pageerror', e => errs.push('PE: ' + e.message.slice(0, 120)));

  await login(page);

  // ═══ 1. 配置动态表单 ═══
  await page.goto('/office/bpm/form');
  await page.getByText('流程表单').first().waitFor({ timeout: 25000 });
  await page.waitForTimeout(1200);
  await shot(page, '01-流程表单列表');

  // 打开表单设计器
  await page.locator('table tbody tr:not(.ant-table-measure-row)').first()
    .getByRole('button', { name: '设计' }).click();
  await page.waitForTimeout(2500);
  await shot(page, '02-表单设计器-组件库画布');

  // 添加"多行文本"字段（备注）
  await page.locator('.bpm-fd-lib-item').filter({ hasText: '多行文本' }).click();
  await page.waitForTimeout(800);
  await shot(page, '03-添加多行文本字段');

  // 配置字段属性（标题/必填）
  const cards = page.locator('.bpm-fd-card');
  const n = await cards.count();
  await cards.nth(n - 1).click();
  await page.waitForTimeout(800);
  const fp = page.locator('.ant-modal-wrap .ant-modal-body, .ant-drawer-body').first();
  const titleInputs = page.locator('input').all();
  // 标题输入（属性面板第一个 input）
  const propInputs = page.locator('.bpm-fd').locator('input');
  await propInputs.first().fill('备注说明');
  await page.waitForTimeout(400);
  await shot(page, '04-字段属性配置(标题/必填)');

  // 保存表单
  await page.locator('.bpm-fd').getByRole('button', { name: /保\s*存/ }).click().catch(() => {
    return page.getByRole('button', { name: /保存/ }).click();
  });
  await page.waitForTimeout(1500);
  await shot(page, '05-表单保存成功');

  // ═══ 2. 配置流程（模型设计）═══
  await page.goto('/office/bpm/model');
  await page.getByText('流程模型').first().waitFor({ timeout: 25000 });
  await page.waitForTimeout(1200);
  await shot(page, '06-流程模型列表');

  // 打开请假流程设计器
  const leaveRow = page.locator('table tbody tr:not(.ant-table-measure-row)').filter({ hasText: 'leaveApproval' }).first();
  await leaveRow.getByRole('button', { name: '设计' }).click();
  const wrap = page.getByRole('dialog');
  await expect(wrap).toBeVisible({ timeout: 15000 });
  await wrap.getByText('流程设计', { exact: true }).first().click();
  await expect(page.locator('.bpm-flow-root').first()).toBeVisible({ timeout: 15000 });
  await page.waitForTimeout(1500);
  await shot(page, '07-流程设计器-流程图');

  // 打开审批节点配置（部门经理审批）
  await page.locator('.bpm-node-card').first().click();
  await expect(page.locator('.bpm-config').first()).toBeVisible({ timeout: 8000 });
  await page.waitForTimeout(800);
  await shot(page, '08-审批节点配置(审批人/字段权限)');

  // 保存配置 → 保存流程
  await page.locator('.bpm-config').getByRole('button', { name: '保存配置' }).click();
  await page.waitForTimeout(800);
  await page.getByRole('button', { name: '保存流程' }).click();
  await page.waitForTimeout(2000);
  await shot(page, '09-保存流程');

  // ═══ 3. 发起提交 ═══
  await page.goto('/office/bpm/start');
  await page.getByText('发起流程').first().waitFor({ timeout: 25000 });
  await page.waitForTimeout(1200);
  await shot(page, '10-发起流程列表');

  await page.locator('table tbody tr:not(.ant-table-measure-row)').filter({ hasText: 'leaveApproval' }).first()
    .getByRole('button', { name: '发起' }).click();
  await page.waitForTimeout(2200);
  await shot(page, '11-动态表单发起弹窗');

  // 填表
  const inp = page.locator('.ant-modal input');
  const ta = page.locator('.ant-modal textarea');
  await inp.nth(0).fill('出差广州参加客户会议');
  await inp.nth(1).fill('3');
  if (await ta.count()) await ta.first().fill('客户现场演示系统');
  await page.waitForTimeout(500);
  await shot(page, '12-填表完成');

  const ts = Date.now().toString().slice(-6);
  await page.getByRole('button', { name: /确\s*定/ }).click();
  await expect(page.getByText('流程发起成功')).toBeVisible({ timeout: 10000 });
  await shot(page, '13-提交成功');

  // ═══ 4. 审批通过 ═══
  await page.goto('/office/bpm/todo');
  await page.getByText('我的待办').first().waitFor({ timeout: 25000 });
  await page.waitForTimeout(2500);
  await shot(page, '14-我的待办');

  const taskRows = page.locator('table tbody tr.ant-table-row');
  const total = await taskRows.count();
  await taskRows.last().getByRole('button', { name: '通过' }).click();
  await page.waitForTimeout(3000);
  await shot(page, '15-审批弹窗(表单回显+意见)');

  const modal = page.locator('.ant-modal');
  const ta2 = modal.locator('textarea[placeholder*="审批意见"]');
  if (await ta2.count()) await ta2.first().fill('同意，祝顺利');
  await page.waitForTimeout(400);
  await modal.getByRole('button', { name: /确\s*定/ }).click();
  await page.waitForTimeout(2500);
  await shot(page, '16-审批通过');

  // ═══ 5. 已办 + 详情 ═══
  await page.goto('/office/bpm/done');
  await page.getByText('我的已办').first().waitFor({ timeout: 25000 });
  await page.waitForTimeout(2000);
  await shot(page, '17-我的已办');

  await page.goto('/office/bpm/instance');
  await page.getByText('我的流程').first().waitFor({ timeout: 25000 });
  await page.waitForTimeout(2000);
  await shot(page, '18-我的流程列表');

  // 详情（流程追踪：已完成高亮）
  await page.locator('table tbody tr.ant-table-row').first()
    .getByRole('button', { name: '详情' }).click();
  await expect(page.getByText('流程详情 · ').first()).toBeVisible({ timeout: 10000 });
  await page.waitForTimeout(2500);
  await shot(page, '19-流程详情-审批历史');
  await page.locator('.ant-drawer-body').evaluate(el => el.scrollTo(0, el.scrollHeight));
  await page.waitForTimeout(2000);
  await shot(page, '20-流程追踪-流程图(已完成高亮)');

  console.log('errs:', JSON.stringify(errs));
});
