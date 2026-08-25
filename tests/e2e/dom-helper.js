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
