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

/**
 * 截图类报告用例需要 "流程表单" 列表里至少有一张表单; 全新库没有时经真实 HTTP 建一张通用申请表单.
 * @param {import('playwright/test').Page} page 已登录的管理员页面
 */
async function ensureBpmForm(page) {
  const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
  const headers = { Authorization: `Bearer ${token}` };
  const list = await (await page.request.get('/api/business/bpm/form', { headers })).json();
  if ((list.data && list.data.rows || []).length) return;
  const form_json = JSON.stringify({ fields: [
    { type: 'input', field: 'title', title: '申请标题', value: '', props: {}, validate: [{ required: true }] },
    { type: 'number', field: 'amount', title: '申请金额', value: 0, props: {}, validate: [] },
  ] });
  const created = await (await page.request.post('/api/business/bpm/form', {
    headers, data: { form_name: '通用申请表单', form_key: 'e2e_general_form', form_json, status: '0' } })).json();
  if (created.code !== 200) throw new Error(`创建流程表单失败: ${created.msg}`);
}

module.exports = { login, logout, ensureBpmForm };
