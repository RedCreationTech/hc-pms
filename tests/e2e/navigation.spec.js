const { test, expect } = require('playwright/test');
const { login } = require('./auth-helper');

// 使用默认管理员登录后访问各主要菜单，验证页面正常渲染。
test.beforeEach(async ({ page }) => {
  await login(page);
});

const cases = [
  { path: '/system/user', text: '用户名称' },
  { path: '/system/role', text: '角色名称' },
  { path: '/system/menu', text: '菜单名称' },
  { path: '/system/dept', text: '部门名称' },
  { path: '/system/post', text: '岗位名称' },
  { path: '/system/dict', text: '字典类型' },
  { path: '/system/config', text: '参数名称' },
  { path: '/system/notice', text: '公告标题' },
  { path: '/monitor/operlog', text: '系统模块' },
  { path: '/monitor/logininfor', text: '登录地址' },
  { path: '/monitor/online', text: '强退' },
  { path: '/monitor/job', text: '任务名称' },
  { path: '/monitor/server', text: 'CPU' },
  { path: '/monitor/cache', text: '缓存名称' },
  { path: '/monitor/datasource', text: '连接池' },
  { path: '/monitor/swagger', text: 'Swagger' },
];

for (const { path, text } of cases) {
  test(`导航到 ${path}`, async ({ page }) => {
    await page.goto(path);
    if (path === '/monitor/swagger') {
      // Swagger 页面通过 iframe 嵌入 /api/index.html
      const iframe = page.locator('iframe[title="API 文档"]');
      await expect(iframe).toBeVisible({ timeout: 10000 });
      const body = iframe.contentFrame().locator('body');
      await expect(body).toBeVisible({ timeout: 10000 });
    } else {
      await expect(page.getByText(text).first()).toBeVisible({ timeout: 10000 });
    }
  });
}
