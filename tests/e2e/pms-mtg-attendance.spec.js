const { test, expect } = require('@playwright/test');
const path = require('node:path');

// 会议参会覆盖与出勤分布: 按每个会议最新有效版本只读聚合参会情况(平均每场参会人数/出勤排行/会议类型分布/成员参会覆盖率/从未参会成员点名).
// 后端 collaboration.clj meeting-attendance-summary 纯函数在 GET /governance 时派生, 以最新有效(非作废)会议为分母,
//   出勤排行按参会次数降序 (含非成员出席者, 按用户ID回退显示名), 覆盖率仅以项目成员为口径 (从未被邀请的项目成员单独点名),
//   会议类型分布按固定枚举顺序只列出实际出现的类型; 只读派生, 不改记录, 不构成门控, 免迁移/免新命令.
// 本用例单浏览器上下文(admin)造出三位成员: admin(项目经理自动入成员, 场场出席), second(出席), third(从不出席, 项目成员但覆盖缺口).
//   三场会: M1 例会[admin,second] + M2 评审会[admin] + M3 FAT启动[admin,second] (kickoff类型有会前包门控, 故用未受门控的fat-kickoff演示多类型).
//   作废前: available true, total 3, discarded 0, member-count 3, attended-members 2, coverage 67%, avg 2,
//          top [admin 3@100, second 2@67], by-type [{例会 1}{评审会 1}{FAT启动 1}], unattended [third].
//   界面面板在"需求与治理 -> 会议行动"页签真实可见, 与服务端 meeting_attendance_summary 同源.
//   作废 M3(fat-kickoff) 后 (单上下文受控作废): total 2, discarded 1, avg round(3/2)=2, coverage 仍67%,
//          top [admin 2@100, second 1@50], by-type 去掉FAT启动[{例会 1}{评审会 1}], 面板"已作废会议 1"徽标出现.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/mtg-attendance');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) }).first();
const base = id => `/api/pms/projects/${id}`;
const dayOffset = n => { const d = new Date(); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };
const TITLE = '会议参会覆盖与出勤分布';

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

async function mutateData(page, id, suffix, data = {}) {
  const project = await api(page, 'GET', base(id));
  return api(page, 'POST', base(id) + suffix, { ...data, version: project.version });
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

async function panelShot(page, title, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  const card = panel(page, title);
  await expect(card.getByRole('heading', { name: title, exact: true })).toBeVisible();
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function addMember(page, id, nickName, deptId, suffix) {
  const menus = await api(page, 'GET', '/api/system/menu');
  const perms = new Set(['pms:project:list', 'pms:project:query']);
  const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `参会成员角${suffix}`, role_key: `mtga_${suffix}`, 'menu-ids': menuIds });
  const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `mtga_${suffix}`).role_id;
  const user_name = `mtga_${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name, nick_name: nickName, password: `E2e!${suffix}`,
    dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'meeting-attendance E2E 合成参会成员' });
  const userIdNew = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === user_name).user_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userIdNew, role: 'editor' });
  return userIdNew;
}

test.describe('会议参会覆盖与出勤分布只读面板浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('三人成员两出勤一缺席的覆盖/排行/类型分布在界面真实可见, 作废一场后分母与类型同步回落', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `MA-${suffix}`, name: `会议参会覆盖验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 出席成员(second)与缺席成员(third)入项目成员; admin 作为经理已是成员.
    const secondId = await addMember(page, id, `出席成员-${suffix}`, deptId, `${suffix}b`);
    const thirdId = await addMember(page, id, `缺席成员-${suffix}`, deptId, `${suffix}c`);
    expect(secondId).not.toBe(thirdId);

    const m1 = (await mutateData(page, id, '/governance/meetings',
      { title: `周例会-${suffix}`, held_on: dayOffset(-2), minutes: '进度对齐', attendee_ids: [adminId, secondId], meeting_type: 'regular' })).result;
    const m2 = (await mutateData(page, id, '/governance/meetings',
      { title: `设计评审-${suffix}`, held_on: dayOffset(-1), minutes: '方案评审', attendee_ids: [adminId], meeting_type: 'review' })).result;
    const m3 = (await mutateData(page, id, '/governance/meetings',
      { title: `FAT启动-${suffix}`, held_on: dayOffset(0), minutes: '出厂验收启动', attendee_ids: [adminId, secondId], meeting_type: 'fat-kickoff' })).result;

    // 服务端只读派生回显 (作废前).
    const gov = await api(page, 'GET', base(id) + '/governance');
    const cov = gov.meeting_attendance_summary;
    expect(cov.available).toBe(true);
    expect(cov.total).toBe(3);
    expect(cov.discarded).toBe(0);
    expect(cov['member-count']).toBe(3);
    expect(cov['attended-members']).toBe(2);
    expect(cov['coverage-pct']).toBe(67);
    expect(cov['avg-attendance']).toBe(2);
    expect(cov['by-type']).toEqual([
      { type: 'regular', count: 1 },
      { type: 'review', count: 1 },
      { type: 'fat-kickoff', count: 1 },
    ]);
    // 出勤排行: admin 3 次 100%, second 2 次 67% (attendee数降序).
    expect(cov['top-attendees'].map(o => o['user-id'])).toEqual([adminId, secondId]);
    expect(cov['top-attendees'].map(o => o.attended)).toEqual([3, 2]);
    expect(cov['top-attendees'].map(o => o['attendance-pct'])).toEqual([100, 67]);
    // 从未参会成员: 仅缺席成员(third).
    expect(cov['unattended-members'].map(o => o['user-id'])).toEqual([thirdId]);

    // 界面: 面板真实渲染, 全局汇总/类型分布/出勤排行/缺席成员可见.
    await open(page, id, '需求与治理', '会议行动');
    const card = panel(page, TITLE);
    await expect(card.getByText('有效会议 3', { exact: true })).toBeVisible();
    await expect(card.getByText('成员参会覆盖 67% (2/3)', { exact: true })).toBeVisible();
    await expect(card.getByText('平均每场参会 2 人', { exact: true })).toBeVisible();
    // 作废徽标未出现 (discarded=0).
    await expect(card.getByText(/已作废会议\s+\d+/)).toHaveCount(0);
    // 类型分布: 例会/评审会/FAT启动 各一场.
    await expect(card.getByText('会议类型分布:', { exact: true })).toBeVisible();
    await expect(card.getByText('例会 · 1 场', { exact: true })).toBeVisible();
    await expect(card.getByText('评审会 · 1 场', { exact: true })).toBeVisible();
    await expect(card.getByText('FAT启动 · 1 场', { exact: true })).toBeVisible();
    // 出勤排行与缺席点名.
    await expect(card.getByText('出勤排行:', { exact: true })).toBeVisible();
    await expect(card.getByText(/参会 3 次 · 100%/)).toBeVisible();
    await expect(card.getByText(/参会 2 次 · 67%/)).toBeVisible();
    await expect(card.getByText('从未参会成员:', { exact: true })).toBeVisible();
    await expect(card.getByText(`缺席成员-${suffix}`, { exact: true })).toBeVisible();
    await panelShot(page, TITLE, 'mtg-1-before.png');

    // 受控作废 M3(fat-kickoff) -> 最新有效版本状态翻为 discarded -> 分母与类型分布回落 (单上下文可见).
    await mutateData(page, id, `/governance/meetings/${m3.id}/discard`, { reason: '演示作废该场会议' });

    const gov2 = await api(page, 'GET', base(id) + '/governance');
    const cov2 = gov2.meeting_attendance_summary;
    expect(cov2.total).toBe(2);
    expect(cov2.discarded).toBe(1);
    expect(cov2['coverage-pct']).toBe(67);
    expect(cov2['avg-attendance']).toBe(2);
    expect(cov2['by-type']).toEqual([
      { type: 'regular', count: 1 },
      { type: 'review', count: 1 },
    ]);
    expect(cov2['top-attendees'].map(o => o.attended)).toEqual([2, 1]);
    expect(cov2['top-attendees'].map(o => o['attendance-pct'])).toEqual([100, 50]);
    expect(cov2['unattended-members'].map(o => o['user-id'])).toEqual([thirdId]);

    await open(page, id, '需求与治理', '会议行动');
    const card2 = panel(page, TITLE);
    await expect(card2.getByText('有效会议 2', { exact: true })).toBeVisible();
    await expect(card2.getByText('已作废会议 1', { exact: true })).toBeVisible();
    await expect(card2.getByText(/参会 2 次 · 100%/)).toBeVisible();
    await expect(card2.getByText(/参会 1 次 · 50%/)).toBeVisible();
    await expect(card2.getByText(/参会 3 次 · 100%/)).toHaveCount(0);
    // FAT启动 已从类型分布消失.
    await expect(card2.getByText('FAT启动 · 1 场', { exact: true })).toHaveCount(0);
    await panelShot(page, TITLE, 'mtg-2-after-discard.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });

  test('空项目面板渲染空态提示 (暂无有效会议)', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `MA-${suffix}e`, name: `会议参会空态验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    const gov = await api(page, 'GET', base(id) + '/governance');
    expect(gov.meeting_attendance_summary.available).toBe(false);
    expect(gov.meeting_attendance_summary.total).toBe(0);
    expect(gov.meeting_attendance_summary['top-attendees']).toEqual([]);
    expect(gov.meeting_attendance_summary['by-type']).toEqual([]);

    await open(page, id, '需求与治理', '会议行动');
    const card = panel(page, TITLE);
    await expect(card.getByText('暂无有效会议, 登记项目会议并填写参会人后可在此查看参会覆盖与出勤分布.', { exact: false })).toBeVisible();
    await panelShot(page, TITLE, 'mtg-3-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
