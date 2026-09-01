// 通用 antd 表单交互辅助函数
const { expect } = require('playwright/test');

/**
 * 向输入框填充值。
 * @param {import('playwright/test').Locator} input
 * @param {string} value
 */
async function fillInput(input, value) {
  await input.fill(value);
}

/**
 * 点击弹窗"确定"按钮。
 * @param {import('playwright/test').Locator} modal
 */
async function clickOk(modal) {
  await modal.getByRole('button', { name: /确\s*定/ }).click();
  await expect(modal).toBeHidden({ timeout: 10000 });
}

module.exports = { fillInput, clickOk };
// DOM 操作辅助函数，处理 Ant Design/Reagent 组件在 Playwright 中的兼容性问题

/**
 * 稳定填充输入框。
 * 先尝试标准 fill；若值未写入（常见于无 placeholder 的裸 input），
 * 则通过 JS 设置 value 并派发 input/change 事件。
 */
/**
 * 稳定填充输入框。
 * 该项目的 ClojureScript + Ant Design 输入框对 Playwright 的 fill/type 不响应，
 * 因此通过 JS 逐字符设置 value 并派发 input 事件。
 */
async function fillInput(input, value) {
  await input.evaluate(el => { el.value = ''; });
  for (const ch of value) {
    await input.evaluate((el, c) => {
      el.value += c;
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }, ch);
    await new Promise(r => setTimeout(r, 30));
  }
  await input.evaluate(el => {
    el.dispatchEvent(new Event('change', { bubbles: true }));
  });

  const actual = await input.inputValue().catch(() => '');
  if (actual !== value) {
    throw new Error(`Failed to fill input: expected "${value}", got "${actual}"`);
  }
}

/**
 * 点击确定按钮（适配 Ant Design Modal 中 "确 定" 带空格的 accessible name）。
 */
async function clickOk(scope) {
  const btn = scope.getByRole('button', { name: /确\s*定/ });
  await btn.click();
}

/**
 * 点击取消按钮。
 */
async function clickCancel(scope) {
  const btn = scope.getByRole('button', { name: /取\s*消/ });
  await btn.click();
}

/**
 * 在弹窗或页面范围内点击第一个匹配文本的按钮。
 */
async function clickButton(scope, nameRegex) {
  const btn = scope.getByRole('button', { name: nameRegex });
  await btn.click();
}

module.exports = { fillInput, clickOk, clickCancel, clickButton };
