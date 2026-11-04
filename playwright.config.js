// @ts-check
const { defineConfig, devices } = require('@playwright/test');

/**
 * @see https://playwright.dev/docs/test-configuration
 */
module.exports = defineConfig({
  testDir: './tests/e2e',

  /* Run tests in files in serial because they share the local SQLite DB */
  fullyParallel: false,

  /* Fail the build on CI if you accidentally left test.only in the source code */
  forbidOnly: !!process.env.CI,

  /* Test timeout */
  timeout: 60000,

  /* Retry on CI only */
  retries: process.env.CI ? 2 : 0,

  /* Opt out of parallel tests on CI */
  workers: 1,

  /* Reporter to use */
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
    ['json', { outputFile: 'playwright-report/report.json' }],
  ],

  /* Shared settings for all the projects below */
  use: {
    actionTimeout: 30000,
    navigationTimeout: 30000,
    /* Base URL to use in actions like `await page.goto('/')` */
    baseURL: process.env.BASE_URL || 'http://localhost:3000',

    /* Collect trace when retrying the failed test */
    trace: 'on-first-retry',

    /* Screenshot on failure */
    screenshot: 'only-on-failure',

    /* Record video on retry */
    video: 'on-first-retry',
  },

  /* Configure projects for major browsers */
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // PW_NO_CHANNEL=1 时退回 Playwright 内置 Chromium: 有头 Chrome 的窗口边框会让录像
        // 画面底部少约 84 像素 (补灰), 恰好盖住注入的时间码条, compose 逐帧对齐因此全部回退;
        // 无边框的内置 Chromium 录制可完整读出时间码 (演示视频录屏用).
        ...(process.env.PW_NO_CHANNEL ? {} : { channel: 'chrome' }),
      },
    },
  ],

  /* Run local dev server before starting the tests */
  // webServer: {
  //   command: 'clojure -M:dev -m com.ruoyi.core',
  //   url: 'http://localhost:3000/api/health',
  //   reuseExistingServer: true,
  //   timeout: 120 * 1000,
  // },
});
