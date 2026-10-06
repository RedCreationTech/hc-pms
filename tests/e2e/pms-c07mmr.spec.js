const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C07 延伸: 会议预读材料准备就绪度只读派生洞察 (免迁移, 无新命令/新kind/新路由/不构成门控).
// 界面登记两份证据文档(均停留 registered, 未经独立审批发布) + 三条会议: 一条不挂资料(未备), 一条挂1份(待发布1), 一条挂2份(待发布2).
// "会议行动"页签新增只读"会议预读材料准备就绪度"面板: 会议总数/已备预读覆盖率(with/total)/资料全部已发布率(published/total)/未备资料/含待发布资料,
// 并列出未准备会前资料的会议与预读资料待发布的会议(附未发布条数).
// 只读派生不改变任何会议或文档状态; 单上下文即可闭环验证聚合(资料"已发布"翻转需第二审批人上下文, 按规则B本片不纳入B列).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c07mmr');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
// shared/panel 渲染 <section>, 按面板标题(heading)定位到具体面板, 避免跨面板文本串台.
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) });
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

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  if (typeof text === 'string' && await input.isEditable()) await input.fill(text);
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await input.press('Escape');
  await page.waitForLoadState('networkidle');
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

// 就绪度面板在抽屉自身滚动容器内靠下位置, window.scrollTo 无效 -> 滚动进视图后对面板元素截图, 确保聚合内容被真实捕获.
async function shotPanel(page, panelLoc, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await panelLoc.scrollIntoViewIfNeeded();
  await panelLoc.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 界面登记证据文档: 登记后停留 registered(未发布), 即"待发布资料".
async function registerDocument(page, code, content) {
  await drawer(page).getByRole('button', { name: '登记证据文档', exact: true }).click();
  const form = modal(page, '登记证据文档');
  await form.locator('#code').fill(code);
  await form.locator('#title').fill(`${code} 会前阅读材料`);
  await form.locator('#filename').fill(`${code}.txt`);
  await form.locator('#content').fill(content);
  return save(page, '登记证据文档');
}

// 界面登记项目会议: 标题/会议日期/参会人/纪要, materials 为要挂的会前资料 code 数组(可空).
async function registerMeeting(page, { title, minutes, materials = [] }) {
  await drawer(page).getByRole('button', { name: '登记项目会议', exact: true }).click();
  const form = modal(page, '登记项目会议');
  await form.locator('#title').fill(title);
  await form.locator('#held_on').fill('2026-09-15');
  await form.locator('#minutes').fill(minutes);
  await choose(page, form, 'attendee_ids', 'admin');
  for (const code of materials) await choose(page, form, 'material_ids', code);
  return save(page, '登记项目会议');
}

test.describe('C07 延伸 会议预读材料准备就绪度只读洞察浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('就绪度面板聚合未备/待发布/已发布率, 只读派生不改变会议与文档状态', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `CMMR-${suffix}`, name: `会议预读材料就绪度验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 证据版本: 界面登记两份文档 (MRA, MRB), 均停留 registered(未发布) -> 挂到会议即为"待发布资料".
    const codeA = `MRA-${suffix}`;
    const codeB = `MRB-${suffix}`;
    await open(page, id, '需求与治理', '证据版本');
    await registerDocument(page, codeA, '会前阅读材料A: 项目范围与里程碑基线.');
    await registerDocument(page, codeB, '会前阅读材料B: 关键接口清单与验收标准.');

    // 会议行动: 登记三条会议覆盖三种就绪情形.
    const none = `内部例会无资料-${suffix}`;
    const one = `设计评审一份资料-${suffix}`;
    const two = `需求基线两份资料-${suffix}`;
    await tab(page, '会议行动');
    await registerMeeting(page, { title: none, minutes: '口头讨论, 未准备会前资料.', materials: [] });
    await registerMeeting(page, { title: one, minutes: '挂一份会前阅读材料.', materials: [codeA] });
    await registerMeeting(page, { title: two, minutes: '挂两份会前阅读材料.', materials: [codeA, codeB] });

    // 面板初态: 总数3, 已备预读 67%(2/3), 资料全部已发布 0%(0/3, 均待发布), 未备资料1, 含待发布资料2.
    await open(page, id, '需求与治理', '会议行动');
    const cov = panel(page, '会议预读材料准备就绪度');
    await expect(cov).toBeVisible();
    await expect(cov.getByText(/会议总数\s*3/)).toBeVisible();
    await expect(cov.getByText(/已备预读\s*67%\s*\(2\/3\)/)).toBeVisible();
    await expect(cov.getByText(/资料全部已发布\s*0%\s*\(0\/3\)/)).toBeVisible();
    await expect(cov.getByText(/未备资料\s*1/)).toBeVisible();
    await expect(cov.getByText(/含待发布资料\s*2/)).toBeVisible();
    // 未准备会前资料: 列出 none; 预读资料待发布: 列出 one(未发布1) 与 two(未发布2).
    await expect(cov.getByText('未准备会前资料', { exact: false })).toBeVisible();
    await expect(cov.getByText(none, { exact: false })).toBeVisible();
    await expect(cov.getByText('预读资料待发布', { exact: false })).toBeVisible();
    await expect(cov.getByText(new RegExp(`${one.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}.*未发布\\s*1`))).toBeVisible();
    await expect(cov.getByText(new RegExp(`${two.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}.*未发布\\s*2`))).toBeVisible();
    await shotPanel(page, cov, 'c07mmr-1-readiness-panel.png');

    // 真实HTTP读模型回显 meeting_material_readiness 聚合 (与界面面板同源).
    const ws = await api(page, 'GET', base(id) + '/governance');
    const read = ws.meeting_material_readiness;
    expect(read.available, '有会议即应可用').toBe(true);
    expect(read.total, '分母=最新有效版本会议数').toBe(3);
    expect(read.discarded, '无作废').toBe(0);
    expect(read['with-materials'], '挂资料的会议数').toBe(2);
    expect(read['without-materials'], '未挂资料的会议数').toBe(1);
    expect(read['readiness-pct'], '预读覆盖率 2/3').toBe(67);
    expect(read['materials-published'], '已发布资料会议数(单上下文无审批, 为0)').toBe(0);
    expect(read['materials-pending'], '含待发布资料会议数').toBe(2);
    expect(read['full-readiness-pct'], '全就绪率 0/3').toBe(0);
    expect(read['unprepared-meetings'].map(m => m.title).sort(), '未备资料会议(单条)').toEqual([none]);
    // pending-material-meetings 按 :code(UUID)排序, 顺序不定, 用 title->未发布条数 映射做顺序无关断言.
    expect(read['pending-material-meetings'], '含待发布资料会议共2条').toHaveLength(2);
    const pend = {};
    read['pending-material-meetings'].forEach(m => { pend[m.title] = m['pending-count']; });
    expect(pend, '待发布会议按未发布条数').toEqual({ [one]: 1, [two]: 2 });

    // 只读派生不改变任何记录状态: 会议仍 recorded(草稿), 文档仍 registered(未发布).
    expect(ws.meetings.find(m => m.title === none).status, '会议不因就绪度聚合而变状态').toBe('recorded');
    expect(ws.documents.find(d => d.code === codeA).status, '文档仍待发布').toBe('registered');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
