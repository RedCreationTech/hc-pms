// G18 无障碍: 核心页面不存在"项目自己造出来的无名字控件".
//
// 判定口径 (为什么不是简单的 "input/button 有没有 aria-label"):
//   AntD 内部装饰节点自身没有独立语义, 强行要求它们带名字只会制造假失败:
//     - ant-checkbox-input: 真实名字在包住它的 <label> 上, 由 label 关联提供;
//     - ant-input-clear-icon: 清空图标, 不可聚焦, 由输入框自身承担语义;
//     - ant-select 的 aria 落在 .ant-select 容器上而不是内部只读 input 上.
//   所以这里只断言"项目真正写给用户的控件"有可访问名: 可见且可聚焦的
//   input/textarea/button 以及 role=switch 的按钮, 且这些元素不是 AntD 内部节点.
//
// 运行: npx playwright test tests/e2e/pms-g18a11y.spec.js --project=chromium
const { test, expect } = require('playwright/test');

// AntD 内部装饰节点: 语义由父容器/关联 label 提供, 可聚焦性由外层承担, 不计入无名字控件.
// ant-pagination-item-link 是上一页/下一页的"装饰图标", 由外层 li[title] 承担语义且 tabIndex=-1,
// 键盘用户通过 li 访问; AntD 只有在 itemRender 自渲染时才给它 aria-label, 属组件内建行为.
const ANTD_INTERNAL = /ant-checkbox-input|ant-input-clear-icon|ant-pagination-item-link/;

async function login(page) {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ruoyi_token'))).toBeTruthy();
}

// 返回项目自造控件里没有可访问名的元素 (应为空)
async function unnamedControls(page) {
  return page.evaluate((internalSource) => {
    const internal = new RegExp(internalSource);
    const found = [];
    const selector = 'input,textarea,select,button,[role=switch]';
    for (const el of document.querySelectorAll(selector)) {
      if (internal.test(el.className)) continue;
      if (el.closest('[aria-hidden="true"]')) continue;
      const labelledBy = el.getAttribute('aria-labelledby');
      const name =
        el.getAttribute('aria-label') ||
        (labelledBy && document.getElementById(labelledBy)?.textContent) ||
        (el.id && document.querySelector(`label[for="${el.id}"]`)?.textContent) ||
        el.getAttribute('placeholder') ||
        el.getAttribute('title') ||
        el.textContent.trim();
      if (!name) {
        found.push({ tag: el.tagName.toLowerCase(), cls: el.className, role: el.getAttribute('role') });
      }
    }
    return found;
  }, ANTD_INTERNAL.source);
}

test.describe('G18 无障碍: 核心页面无名字控件', () => {
  test.setTimeout(180000);

  test('用户在系统用户页与待办页的每个控件都有可访问名', async ({ page }) => {
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.message));
    await login(page);

    // ── 系统用户页: 曾有 2 个下拉 / 1 个分页输入 / 10 个开关 / 10 个图标按钮无名 ──
    await page.goto('/system/user');
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible();

    // 显隐列下拉里有 10 个开关, 展开一次确保它们也被覆盖
    await page.getByRole('button', { name: '显隐列' }).click();
    await expect(page.locator('.ant-dropdown:not(.ant-dropdown-hidden) .ant-switch').first()).toBeVisible();
    expect(await unnamedControls(page)).toEqual([]);
    await page.keyboard.press('Escape');

    // 开关必须能被辅助技术按名字定位到 (而不是只按位置)
    await expect(page.getByRole('switch', { name: /^用户状态 admin$/ }).first()).toBeVisible();
    await expect(page.getByRole('combobox', { name: '每页条数' })).toBeVisible();
    await expect(page.getByRole('textbox', { name: '页码' })).toBeVisible();
    await expect(page.getByRole('combobox', { name: '用户状态' })).toBeVisible();

    // ── 待办页: 任务列表与筛选控件 ──
    await page.goto('/office/bpm/todo');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForTimeout(1000);
    expect(await unnamedControls(page)).toEqual([]);
    // 共享工具栏 round-tool-button 由 title 派生 aria-label, 按名字可达
    await expect(page.getByRole('button', { name: '刷新' })).toBeVisible();

    expect(pageErrors).toEqual([]);
  });
});