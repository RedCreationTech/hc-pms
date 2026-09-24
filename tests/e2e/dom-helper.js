// 通用 antd 表单交互辅助函数.
// 原文件合并时留下了两份 fillInput/clickOk 声明 (语法错误, 导致整个 e2e 目录无法一次加载), 这里合并为一份.
const { expect } = require('playwright/test');

/**
 * 向输入框填充值 (Playwright fill 会派发 input 事件, antd 受控输入可正常响应).
 * @param {import('playwright/test').Locator} input
 * @param {string} value
 */
async function fillInput(input, value) {
  await input.fill(value);
  await expect(input).toHaveValue(value);
}

/**
 * 点击弹窗"确定"按钮 (适配 antd "确 定" 带空格的 accessible name) 并等待弹窗关闭.
 * @param {import('playwright/test').Locator} modal
 */
async function clickOk(modal) {
  await modal.getByRole('button', { name: /确\s*定/ }).click();
  await expect(modal).toBeHidden({ timeout: 10000 });
}

/**
 * 点击取消按钮.
 */
async function clickCancel(scope) {
  await scope.getByRole('button', { name: /取\s*消/ }).click();
}

/**
 * 在弹窗或页面范围内点击第一个匹配文本的按钮.
 */
async function clickButton(scope, nameRegex) {
  await scope.getByRole('button', { name: nameRegex }).click();
}

module.exports = { fillInput, clickOk, clickCancel, clickButton };
