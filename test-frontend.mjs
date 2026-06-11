import { chromium } from 'playwright';

const BASE_URL = 'http://localhost:8700';

async function runTests() {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
  const page = await context.newPage();

  let passed = 0;
  let failed = 0;

  function check(name, condition) {
    if (condition) {
      console.log(`  ✅ ${name}`);
      passed++;
    } else {
      console.log(`  ❌ ${name}`);
      failed++;
    }
  }

  try {
    console.log('\n🧪 前端验证测试开始\n');

    // Test 1: 页面加载
    console.log('Test 1: 登录页加载');
    await page.goto(BASE_URL, { waitUntil: 'networkidle' });
    await page.screenshot({ path: '/tmp/rouyi-login.png', fullPage: false });
    const title = await page.title();
    check(`页面标题为 "若依管理系统"`, title === '若依管理系统');
    check(`页面加载成功 (URL: ${page.url()})`, page.url().includes('8700'));

    // Test 2: 登录表单元素
    console.log('\nTest 2: 登录表单元素检查');
    const usernameInput = page.locator('input[placeholder="用户名"]');
    const passwordInput = page.locator('input[placeholder="密码"]');
    const loginButton = page.locator('button[type="submit"]');

    check('用户名输入框存在', await usernameInput.isVisible().catch(() => false));
    check('密码输入框存在', await passwordInput.isVisible().catch(() => false));
    check('登录按钮存在', await loginButton.isVisible().catch(() => false));

    // Test 3: 表单交互
    console.log('\nTest 3: 表单交互测试');
    await usernameInput.fill('admin');
    await passwordInput.fill('admin123');
    const usernameValue = await usernameInput.inputValue();
    const passwordValue = await passwordInput.inputValue();
    check('用户名输入成功', usernameValue === 'admin');
    check('密码输入成功', passwordValue === 'admin123');

    // Test 4: 点击登录
    console.log('\nTest 4: 登录按钮点击');
    await loginButton.click();
    await page.waitForTimeout(1500);
    await page.screenshot({ path: '/tmp/rouyi-login-clicked.png', fullPage: false });
    check('登录按钮点击成功', true);

    // Test 5: React 应用挂载检查
    console.log('\nTest 5: React 应用挂载检查');
    const appRoot = await page.locator('#app').count();
    check('#app 根节点存在', appRoot > 0);
    const appHasChildren = await page.evaluate(() => {
      const app = document.getElementById('app');
      return app && app.children.length > 0;
    });
    check('React 应用已渲染内容到 #app', appHasChildren);

    // Test 6: CSS 加载检查
    console.log('\nTest 6: 资源加载检查');
    const cssLoaded = await page.evaluate(() => {
      return Array.from(document.styleSheets).some(s => s.href && s.href.includes('antd'));
    });
    check('Ant Design CSS 已加载', cssLoaded);

    console.log('\n📊 测试结果');
    console.log(`  通过: ${passed}`);
    console.log(`  失败: ${failed}`);
    console.log(`  截图: /tmp/rouyi-login.png, /tmp/rouyi-login-clicked.png`);

  } catch (e) {
    console.error('测试出错:', e.message);
    failed++;
  } finally {
    await browser.close();
  }

  process.exit(failed > 0 ? 1 : 0);
}

runTests();
