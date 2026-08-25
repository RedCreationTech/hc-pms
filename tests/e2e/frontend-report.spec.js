// 办公一体化前端功能截图报告：登录 → 请假 → 待办审批 → 状态 → 报表
// 运行: npx playwright test tests/e2e/frontend-report.spec.js
// 截图输出到: test-results/frontend-report/
const { test, expect } = require('playwright/test');
const { login } = require('./auth-helper');
const fs = require('fs');

const SHOT_DIR = 'test-results/frontend-report';
fs.mkdirSync(SHOT_DIR, { recursive: true });

async function shot(page, name) {
  await page.waitForTimeout(500); // 等待渲染稳定
  await page.screenshot({ path: `${SHOT_DIR}/${name}.png`, fullPage: true });
  console.log(`📸 ${SHOT_DIR}/${name}.png`);
}

test('前端功能截图报告', async ({ page }) => {
  // 1. 登录页
  await page.goto('/');
  await page.getByPlaceholder('用户名').waitFor();
  await shot(page, '01-登录页');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await page.getByText('若依管理系统').nth(1).waitFor();
  await shot(page, '02-首页');

  // 2. 请假申请列表页
  await page.goto('/office/oa/leave');
  await expect(page.getByText('请假申请').first()).toBeVisible();
  await shot(page, '03-请假申请列表');

  // 3. 发起请假弹窗
  await page.getByRole('button', { name: '发起请假' }).click();
  const modal = page.getByRole('dialog', { name: /发起请假申请/ });
  await expect(modal).toBeVisible();
  await shot(page, '04-发起请假弹窗');
  const ts = Date.now().toString();
  await modal.locator('input[type="number"], input[role="spinbutton"]').first().fill('3');
  await modal.locator('textarea').fill(`前端截图请假_${ts}`);
  await modal.locator('form').evaluate(form => form.requestSubmit());
  await expect(page.getByText('请假申请已提交，进入审批')).toBeVisible({ timeout: 10000 });
  await shot(page, '05-提交成功');

  // 4. 请假列表出现新单（审批中）
  const newRow = page.locator('table tbody tr:not(.ant-table-measure-row)', { hasText: `前端截图请假_${ts}` });
  await expect(newRow).toBeVisible({ timeout: 10000 });
  await shot(page, '06-请假单审批中');

  // 5. 我的待办
  await page.goto('/office/bpm/todo');
  await expect(page.getByText('我的待办').first()).toBeVisible();
  await shot(page, '07-我的待办');

  // 6. 审批通过弹窗
  const taskRow = page.locator('table tbody tr:not(.ant-table-measure-row)', { hasText: '部门经理审批' }).first();
  await taskRow.getByRole('button', { name: '通过' }).click();
  const approveModal = page.getByRole('dialog', { name: /审批通过/ });
  await expect(approveModal).toBeVisible();
  await shot(page, '08-审批通过弹窗');
  await approveModal.locator('textarea').fill('同意');
  await approveModal.locator('form').evaluate(form => form.requestSubmit());
  await expect(approveModal).toBeHidden({ timeout: 10000 });

  // 7. 我的已办
  await page.goto('/office/bpm/done');
  await expect(page.getByText('我的已办').first()).toBeVisible();
  await shot(page, '09-我的已办');

  // 8. 我的流程（含流程图按钮）
  await page.goto('/office/bpm/instance');
  await expect(page.getByText('我的流程').first()).toBeVisible();
  await shot(page, '10-我的流程');

  // 9. 请假状态已通过
  await page.goto('/office/oa/leave');
  await expect(page.getByText(`前端截图请假_${ts}`).first()).toBeVisible();
  await shot(page, '11-请假已通过');

  // 10. 办公报表
  await page.goto('/office/report');
  await expect(page.getByText('办公报表').first()).toBeVisible();
  await expect(page.getByText('请假单总数').first()).toBeVisible({ timeout: 15000 });
  await shot(page, '12-办公报表');
});

test('流程模型页与设计器', async ({ page }) => {
  await login(page);
  await page.goto('/office/bpm/model');
  await expect(page.getByText('流程模型').first()).toBeVisible();
  await shot(page, '13-流程模型列表');

  // 打开设计器（bpmn-js 画布）——已修复 BPMNDI 渲染
  await page.locator('table tbody tr:not(.ant-table-measure-row)').first()
    .getByRole('button', { name: '设计' }).click();
  const designer = page.getByRole('dialog', { name: /流程设计/ });
  await expect(designer).toBeVisible({ timeout: 15000 });
  await expect(page.locator('.djs-container svg').first()).toBeVisible({ timeout: 15000 });
  await page.waitForTimeout(1500); // 等 bpmn-js 渲染
  await shot(page, '14-流程设计器bpmn-js');
});

test('HRM员工 / OA日程 / CRM客户 页面', async ({ page }) => {
  await login(page);
  await page.goto('/office/hrm/employee');
  await expect(page.getByText('新增员工').first()).toBeVisible();
  await shot(page, '15-HRM员工');
  await page.goto('/office/oa/meeting');
  await expect(page.getByText('新增会议').first()).toBeVisible();
  await shot(page, '16-OA会议');
  await page.goto('/office/crm/customer');
  await expect(page.getByText('新增客户').first()).toBeVisible();
  await shot(page, '17-CRM客户');
});
