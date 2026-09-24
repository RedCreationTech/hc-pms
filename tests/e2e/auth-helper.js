// 登录辅助函数
// 后端在 uuid 存在但 captcha 为空时跳过验证码校验，因此直接提交即可登录。

const LOGIN_URL = '/';

/**
 * 执行登录操作。
 * @param {import('playwright/test').Page} page
 * @param {string} [username]
 * @param {string} [password]
 */
async function login(page, username = 'admin', password = 'admin123') {
  await page.goto(LOGIN_URL);

  // 等待登录页渲染
  await page.getByPlaceholder('用户名').waitFor();

  await page.getByPlaceholder('用户名').fill(username);
  await page.getByPlaceholder('密码').fill(password);
  // 验证码留空即可通过后端校验

  await page.getByRole('button', { name: /登\s*录/ }).click();

  // 等待进入主布局
  await page.getByText('红创PMS').nth(1).waitFor();
}

/**
 * 执行登出操作。
 * @param {import('playwright/test').Page} page
 */
async function logout(page) {
  // 点击头像下拉 (页头显示当前用户昵称)
  await page.locator('.ant-layout-header .header-user').click();
  await page.getByText('退出登录').click();
  await page.getByRole('button', { name: /登\s*录/ }).waitFor();
}

module.exports = { login, logout };
