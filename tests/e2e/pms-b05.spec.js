const { test, expect } = require('@playwright/test');
const path = require('node:path');

// B05/C07 会议会前资料: 界面登记一份证据文档 -> 登记会议时选择该文档版本为会前资料 ->
// 读模型回显 material_ids -> 台账"会前资料"列计数 -> 引用不存在的文档版本经真实HTTP被拒(404).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/b05');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;

async function login(page, user = 'admin', password = 'admin123') {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill(user);
  await page.getByPlaceholder('密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ruoyi_token'))).toBeTruthy();
}

async function api(page, method, url, data, expected = 200) {
  const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
  const response = await page.request.fetch(url, { method, headers: { Authorization: `Bearer ${token}` }, data });
  const body = await response.json();
  expect(body.code, `${method} ${url}: ${body.msg}`).toBe(expected);
  return body.data;
}

async function tab(page, name) {
  await drawer(page).getByRole('tab', { name, exact: true }).click();
  await page.waitForLoadState('networkidle');
}

async function open(page, id, section, subsection) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  if (section) await tab(page, section);
  if (subsection) await tab(page, subsection);
}

// 打开 #key 多选下拉, 按 label 文本命中一项, 关闭下拉.
async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  // 共享库累积用户/任务后长列表被 antd 虚拟滚动裁剪, 可搜索的下拉先按标签过滤再选.
  if (typeof text === 'string' && await input.isEditable()) await input.fill(text);
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await input.press('Escape');
}

async function save(page, title) {
  const form = modal(page, title);
  const response = page.waitForResponse(r => r.url().includes('/api/pms/') && ['POST', 'PUT', 'DELETE'].includes(r.request().method()));
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(200);
  await expect(form).toBeHidden();
  await page.waitForLoadState('networkidle');
  return body.data;
}

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function registerDocument(page, id, code, filename, content) {
  await drawer(page).getByRole('button', { name: '登记证据文档', exact: true }).click();
  const form = modal(page, '登记证据文档');
  await form.locator('#code').fill(code);
  await form.locator('#title').fill(`${code} 会前阅读材料`);
  await form.locator('#filename').fill(filename);
  await form.locator('#content').fill(content);
  return save(page, '登记证据文档');
}

test.describe('B05/C07 会议会前资料浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('界面把证据文档版本绑定为会前资料, 非法引用经真实HTTP被拒', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `B05-${suffix}`, name: `会议会前资料验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 1) 需求与治理 -> 证据版本: 界面登记一份真实文本证据.
    await open(page, id, '需求与治理', '证据版本');
    const doc = await registerDocument(page, id, `MAT-${suffix}`, 'agenda.txt', '会前阅读材料: 项目范围与里程碑基线, 关键接口清单.');
    const docId = doc.result.id;
    await expect(drawer(page).getByText(`MAT-${suffix}`, { exact: false }).first()).toBeVisible();

    // 2) 切到会议行动页签, 界面登记会议并把该证据版本选为会前资料.
    await tab(page, '会议行动');
    await drawer(page).getByRole('button', { name: '登记项目会议', exact: true }).click();
    const meetingTitle = `启动评审会-${suffix}`;
    const form = modal(page, '登记项目会议');
    await form.locator('#title').fill(meetingTitle);
    await form.locator('#held_on').fill('2026-09-20');
    await form.locator('#minutes').fill('依据会前资料评审项目范围与里程碑, 形成行动项.');
    await choose(page, form, 'attendee_ids', 'admin');
    await choose(page, form, 'material_ids', `MAT-${suffix}`);
    await save(page, '登记项目会议');

    // 3) 读模型回显: 会议 material_ids 恰为选中的文档版本ID.
    const gov = await api(page, 'GET', base(id) + '/governance');
    const readMeeting = gov.meetings.find(m => m.title === meetingTitle);
    expect(readMeeting, '会议应出现在治理读模型').toBeTruthy();
    expect(readMeeting.material_ids, '会前资料应回显为选中的文档版本').toEqual([docId]);

    // 4) 台账"会前资料"列显示计数 1.
    await open(page, id, '需求与治理', '会议行动');
    await expect(row(page, meetingTitle)).toBeVisible();
    await expect(row(page, meetingTitle).getByText('1', { exact: true })).toBeVisible();
    await shot(page, 'b05-1-meeting-with-materials.png');

    // 5) 真实HTTP: 引用不存在的文档版本 -> 404 (携带当前项目版本以通过乐观锁).
    const rejectBad = await page.evaluate(async ({ id, adminId }) => {
      const token = localStorage.getItem('ruoyi_token');
      const proj = await (await fetch(`/api/pms/projects/${id}`, { headers: { Authorization: `Bearer ${token}` } })).json();
      const r = await fetch(`/api/pms/projects/${id}/governance/meetings`, { method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: '坏资料会议', held_on: '2026-09-21', minutes: 'x', attendee_ids: [adminId],
          material_ids: ['not-a-real-record-id'], version: proj.data.version }) });
      return (await r.json()).code;
    }, { id, adminId });
    expect(rejectBad, '引用不存在文档版本应被拒').toBe(404);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
