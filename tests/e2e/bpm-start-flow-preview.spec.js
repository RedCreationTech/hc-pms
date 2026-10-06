const { test, expect } = require('@playwright/test');
const path = require('path');

// BPM 发起页审批流程可视化 -- 真实浏览器 E2E (非 mock).
// 目标: 证明在 /office/bpm/start 点击某模型"发起"后, 弹窗右侧只读流程图预览
//       展示"完整审批链"(节点名) + "完整审批人"(flowable candidateUsers 解析为 nick_name).
// 用真实表单登录 admin, 真实路由渲染, 真实截图. 默认 BASE_URL=http://localhost:3100 (隔离冷启动后端).
test.skip(!process.env.BPM_FP, '发起页流程预览功能实测, 仅在 BPM_FP=1 时运行');

const BASE_URL = process.env.BASE_URL || 'http://localhost:3100';
const SHOT_DIR = process.env.SHOT_DIR || path.resolve(__dirname, '../../reports/bpm-start-flow-preview');
const PASSWORD = process.env.BPM_FP_PASSWORD || 'admin123';

async function login(page) {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill(PASSWORD);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect
    .poll(() => page.evaluate(() => localStorage.getItem('ruoyi_token')), { timeout: 15000 })
    .toBeTruthy();
}

// 截图助手: 把整页 + 弹窗内右侧预览卡分别捕获为真实浏览器帧.
async function shot(page, name) {
  await page.screenshot({ path: path.join(SHOT_DIR, `${name}.png`), fullPage: false });
}

test('发起弹窗右侧只读流程图展示完整审批链与审批人', async ({ browser }) => {
  const context = await browser.newContext({ viewport: { width: 1600, height: 900 }, locale: 'zh-CN' });
  const page = await context.newPage();
  await login(page);

  await page.goto('/office/bpm/start');
  // 定位"请假审批"这一行 (流程名称列)
  const leaveRow = page.locator('tr', { hasText: '请假审批' }).first();
  await expect(leaveRow, '列表应包含种子"请假审批"流程模型').toBeVisible({ timeout: 15000 });
  await shot(page, '01-start-list');

  // 点击该行"发起"按钮 (antd 2 字按钮会自动加空格)
  await leaveRow.getByRole('button', { name: /发\s*起/ }).click();

  // 等待弹窗出现 (antd Modal 用 role=dialog 定位最稳, .ant-modal-content class 匹配不稳)
  const modal = page.getByRole('dialog').last();
  await expect(modal, '发起弹窗应打开').toBeVisible({ timeout: 15000 });
  await expect(modal, '弹窗标题应含流程名').toContainText('请假审批', { timeout: 10000 });

  // 只读预览工具栏标题应为"流程追踪" (read-only 设计器)
  await expect(modal.locator('.bpm-toolbar-title'), '只读预览标题应为"流程追踪"')
    .toHaveText('流程追踪', { timeout: 10000 });

  // 完整审批链: 三个审批节点名都要出现 (部门经理审批 / 分管领导审批 / HR确认)
  for (const nodeName of ['部门经理审批', '分管领导审批', 'HR确认']) {
    await expect(
      modal.locator('.bpm-node-name', { hasText: nodeName }).first(),
      `审批链应包含节点: ${nodeName}`,
    ).toBeVisible({ timeout: 10000 });
  }

  // 完整审批人: 审批人标签应解析 flowable candidateUsers
  //   admin -> nick_name "红创管理员";  hr 未播种 -> 原样回退 "hr"
  // 审批人标签依赖异步加载的 user-options, 用轮询等待.
  await expect(
    modal.locator('.bpm-node-text', { hasText: '审批人：红创管理员' }).first(),
    '审批人标签应把 admin 解析为 nick_name 红创管理员',
  ).toBeVisible({ timeout: 15000 });
  await expect(
    modal.locator('.bpm-node-text', { hasText: '审批人：hr' }).first(),
    'HR确认审批人应显示 hr (未播种用户原样回退)',
  ).toBeVisible({ timeout: 15000 });

  // 断言页面已无任何"请配置审批人"占位 (说明审批人确实被带出)
  await expect(
    modal.locator('.bpm-node-text', { hasText: '请配置审批人' }),
    '不应残留"请配置审批人"占位',
  ).toHaveCount(0);

  await shot(page, '02-modal-preview');
  // 仅预览卡区域截图 (右侧 bordered div)
  const previewCard = modal.locator('div[style*="border"]').last();
  await previewCard.screenshot({ path: path.join(SHOT_DIR, '03-preview-card.png') }).catch(() => shot(page, '03-preview-card'));

  // 把预览容器滚到底, 再截一帧展示链条末端 (HR确认 + 审批人：hr)
  await previewCard.evaluate(el => { el.scrollTop = el.scrollHeight; }).catch(() => {});
  await page.waitForTimeout(400);
  await shot(page, '04-modal-preview-bottom');

  console.log('[bpm-fp] PASS: 完整审批链 + 审批人 nick_name 均已在发起弹窗预览中可见');
  await context.close();
});
