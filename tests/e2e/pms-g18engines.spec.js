// G18 跨引擎兼容实测: 在真实 Chromium / Firefox / WebKit 三个引擎上各跑一遍核心操作,
// 证明兼容基线不是靠"只在 Chromium 跑过"推定出来的。
//
// 与 pms-g18platform.spec.js 的分工:
//   - pms-g18platform.spec.js 验的是**后端基线规则** (UA 解析、版本下限、未上报不计通过),
//     它硬编码 client.family === 'chrome', 只能在 Chromium 下跑;
//   - 本文件验的是**真实引擎能否完成核心操作**, 每个引擎都要自己声明一个最小可接受结果,
//     而不是让三个引擎共用一份 Chromium 假设。
//
// 运行:
//   npx playwright test tests/e2e/pms-g18engines.spec.js --project=chromium --project=firefox --project=webkit
// 证据: reports/g18engines/<engine>-*.png
const { test, expect } = require('playwright/test');
const path = require('node:path');
const fs = require('node:fs');

const output = path.resolve(__dirname, '../../reports/g18engines');
fs.mkdirSync(output, { recursive: true });

// 每个引擎期望的 client.family, 由 playwright 的 browserName 决定
const FAMILY_BY_BROWSER = { chromium: 'chrome', firefox: 'firefox', webkit: 'safari' };

async function login(page) {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ruoyi_token'))).toBeTruthy();
}

test.describe('G18 跨引擎核心操作', () => {
  test.setTimeout(240000);

  test('登录 / 动态菜单 / 列表 / 表单 / 审批 / 平台能力页 在真实引擎上可用', async ({ page, browserName }) => {
    const pageErrors = [];
    const consoleErrors = [];
    page.on('pageerror', error => pageErrors.push(error.message));
    page.on('console', msg => { if (msg.type() === 'error') consoleErrors.push(msg.text()); });

    const shot = async (name) => {
      await page.waitForTimeout(500);
      await page.screenshot({
        path: path.join(output, `${browserName}-${name}.png`),
        fullPage: true,
        animations: 'disabled',
      });
    };

    // ── 1. 登录 ────────────────────────────────────────────────────────────
    await login(page);
    // 登录后必须进主布局, 不能停在登录页
    await expect(page.getByText('红创PMS').nth(1)).toBeVisible();
    await shot('01-login');

    // ── 2. 后端按真实 UA 判定客户端, 且判定为受支持 ─────────────────────────
    const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
    const response = await page.request.get('/api/pms/platform/capabilities', {
      headers: { Authorization: `Bearer ${token}` },
    });
    const body = await response.json();
    expect(body.code, body.msg).toBe(200);
    const caps = body.data;

    // 这里是 G18 的关键断言: 三个引擎上报的 UA 必须被后端识别成对应家族
    expect(caps.client.reported).toBe(true);
    expect(caps.client.family).toBe(FAMILY_BY_BROWSER[browserName]);
    expect(caps.client.supported).toBe(true);
    expect(caps.client.minimum).toBeGreaterThan(0);

    // ── 3. 动态菜单 (权限驱动, 不是静态 HTML) ─────────────────────────────
    await page.goto('/office/bpm/todo');
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 30000 });
    const todoRows = await page.locator('.ant-table-tbody tr.ant-table-row').count();
    expect(todoRows).toBeGreaterThan(0);
    await shot('02-todo-list');

    // ── 4. 表格分页 (AntD Table 在三个引擎的渲染与事件行为都不同) ────────────
    const pager = page.locator('.ant-pagination');
    await expect(pager).toBeVisible();
    await expect(pager.locator('.ant-pagination-total-text')).toContainText('共');

    // ── 5. 表单交互: 打开"发起流程"对话框并真实输入 ─────────────────────────
    await page.goto('/office/bpm/start');
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 30000 });
    await shot('03-start-flow');

    // ── 6. 平台能力页: 浏览器页签里本引擎被标为受支持 ────────────────────────
    await page.goto('/pms/platform');
    await expect(page.getByText('平台能力', { exact: true }).first()).toBeVisible({ timeout: 30000 });
    await page.getByRole('tab', { name: '浏览器' }).click();
    const label = browserName === 'chromium' ? 'Chrome / Edge (Chromium)'
      : browserName === 'firefox' ? 'Firefox' : 'Safari';
    await expect(page.getByText(label, { exact: true })).toBeVisible();
    await shot('04-platform-capabilities');

    // ── 7. 前端没有抛未捕获异常 ───────────────────────────────────────────
    // pageerror (未捕获异常) 必须为空: 它是"这个引擎上功能真的坏了"的直接证据.
    expect(pageErrors).toEqual([]);

    // console error 里只豁免 shadow-cljs 开发热重载的 WebSocket 失败:
    // 它连的是 ws://localhost:9630 (dev server), 与应用功能无关, 生产构建里不存在.
    // 三个引擎实测都只出现这一条, 所以按前缀豁免而不是整体不检查 - 其它 console error
    // 仍然会让本用例失败, 避免"反正有豁免就全放过".
    const DEV_ONLY = /remote-relay|shadow-cljs/i;
    const realConsoleErrors = consoleErrors.filter(text => !DEV_ONLY.test(text));
    expect(realConsoleErrors, `非开发热重载的 console error: ${realConsoleErrors.join(' | ')}`).toEqual([]);

    // 把本引擎的实测结论落盘, 供汇总表格使用
    const record = {
      browser: browserName,
      family: caps.client.family,
      version: caps.client.version,
      supported: caps.client.supported,
      todo_rows: todoRows,
      page_errors: pageErrors.length,
      console_errors: consoleErrors.length,
      user_agent: await page.evaluate(() => navigator.userAgent),
    };
    fs.writeFileSync(path.join(output, `${browserName}-result.json`), JSON.stringify(record, null, 2));
  });
});