const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H16 成员委派到期与权限生命周期只读看板: 项目概况 -> 项目团队 面板的只读概览.
// pms_member 新增可空 ends_on 列 (委派到期日), GET /members 读取时对每个成员按今天派生 lifecycle-state + days-left,
// 并汇总 lifecycle {today/window-days/total/counts{active,expiring-soon,expired,open-ended}/by-role/expiring-soon[]/expired[]}.
// 纯只读派生 (免门控/免写): 本条覆盖"谁即将到期/已过期/长期"的可视化提醒.
// H17 起 rules/access! 只认仍在有效期的成员, 故同一 spec 追加"到期成员真实失去访问 / 续期后恢复"的断言.
// H16 撤销: DELETE /members/:user_id 撤销后成员行不再存活, GET /members 回显 revoked/revoked_user_ids,
// 界面"已撤销成员 N"留痕可见, 被撤销成员真实 HTTP 403.
// 状态口径: 无到期日->open-ended; days-left<0->expired; 0..window(14)->expiring-soon; >window->active.
// 免新kind/免新命令/免新路由/免门控, 仅迁移加一列 + GET 读模型扩展 + 前端只读面板.
// 流程: 建项目(admin 即项目经理, 自动成为 open-ended 成员) -> 造 4 个真实系统用户 ->
//   以真实 HTTP POST /members 分别打 expired(2000-01-01)/expiring-soon(today+5)/active(today+90)/open-ended(留空):
//   GET /members 的 lifecycle 断言 counts 命中, 且项目团队面板"成员权限生命周期"汇总与逐行徽标真实可见, 界面与服务端同源.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h16ml');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) }).first();
const base = id => `/api/pms/projects/${id}`;
const isoDay = offset => new Date(Date.now() + offset * 86400000).toISOString().slice(0, 10);

async function login(page, user = 'admin', password = 'admin123') {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill(user);
  await page.getByPlaceholder('密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ruoyi_token'))).toBeTruthy();
}

async function api(page, method, url, data, expected = 200) {
  const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
  const response = await page.request.fetch(url, { method, headers: { Authorization: `Bearer ${token}` }, data, timeout: 30000 });
  const body = await response.json();
  expect(body.code, `${method} ${url}: ${body.msg}`).toBe(expected);
  return body.data;
}

async function mutate(page, id, suffix, data = {}, expected = 200, verb = 'POST') {
  const project = await api(page, 'GET', base(id));
  return api(page, verb, base(id) + suffix, { ...data, version: project.version }, expected);
}

async function open(page, id) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
}

async function panelShot(page, title, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  const card = panel(page, title);
  await expect(card.getByRole('heading', { name: title, exact: true })).toBeVisible();
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function newProject(page, suffix) {
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
  const adminId = options.currentUserId;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H16ML-${suffix}`, name: `成员权限生命周期 / ${suffix}`,
    project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-21', end_date: '2026-12-31' });
  const id = project.project_id;
  await mutate(page, id, '/transition', { status: 'initiated', reason: 'E2E立项前置' });
  await mutate(page, id, '/transition', { status: 'planning', reason: 'E2E计划前置' });
  return { id, adminId, deptId };
}

async function newUser(page, suffix, tag, deptId) {
  const user_name = `${tag}_${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name, nick_name: `生命周期${tag}`, password: `E2e!${suffix}`,
    dept_id: deptId, roles: [], posts: [], status: '0', remark: 'H16 权限生命周期 E2E 合成成员' });
  const options = await api(page, 'GET', '/api/pms/options');
  return options.users.find(u => u.user_name === user_name).user_id;
}

test.describe('H16 成员权限生命周期只读看板浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('四种到期状态真实可见, 汇总与逐行徽标命中且与服务端 lifecycle 同源', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const { id, adminId, deptId } = await newProject(page, suffix);

    const expiredDate = '2000-01-01';
    const soonDate = isoDay(5);
    const farDate = isoDay(90);
    const uidExpired = await newUser(page, suffix, 'exp', deptId);
    const uidSoon = await newUser(page, suffix, 'soon', deptId);
    const uidFar = await newUser(page, suffix, 'far', deptId);
    const uidOpen = await newUser(page, suffix, 'open', deptId);

    // 成员命令体白名单仅 [:user_id :role :ends_on], object! 拒额外字段, 故不带 version 直接 POST (同 h09cc).
    await api(page, 'POST', base(id) + '/members', { user_id: uidExpired, role: 'editor', ends_on: expiredDate });
    await api(page, 'POST', base(id) + '/members', { user_id: uidSoon, role: 'viewer', ends_on: soonDate });
    await api(page, 'POST', base(id) + '/members', { user_id: uidFar, role: 'editor', ends_on: farDate });
    await api(page, 'POST', base(id) + '/members', { user_id: uidOpen, role: 'editor' }); // 留空 -> 长期有效

    // 服务端只读派生回显 (GET /members 的 data.lifecycle).
    const m = await api(page, 'GET', base(id) + '/members');
    const byId = Object.fromEntries(m.rows.map(r => [r.user_id, r]));
    expect(byId[uidExpired]['lifecycle-state']).toBe('expired');
    expect(byId[uidExpired].ends_on).toBe(expiredDate);
    expect(byId[uidSoon]['lifecycle-state']).toBe('expiring-soon');
    expect(byId[uidFar]['lifecycle-state']).toBe('active');
    expect(byId[uidOpen]['lifecycle-state']).toBe('open-ended');
    expect(byId[adminId]['lifecycle-state']).toBe('open-ended'); // 项目经理自动为长期

    const lc = m.lifecycle;
    expect(lc['window-days']).toBe(14);
    expect(lc.total).toBe(5);
    expect(lc.counts.expired).toBe(1);
    expect(lc.counts['expiring-soon']).toBe(1);
    expect(lc.counts.active).toBe(1);
    expect(lc.counts['open-ended']).toBe(2);
    expect(lc.expired.map(x => x.user_id)).toContain(uidExpired);
    expect(lc['expiring-soon'].map(x => x.user_id)).toContain(uidSoon);
    expect(lc['by-role'].viewer).toEqual({ 'expiring-soon': 1 });
    expect(lc['by-role'].manager).toEqual({ 'open-ended': 1 });

    // 界面: 项目团队面板"成员权限生命周期"只读汇总真实可见.
    await open(page, id);
    const card = panel(page, '项目团队');
    await expect(card.getByText('成员权限生命周期', { exact: true })).toBeVisible();
    await expect(card.getByText('成员 5', { exact: true })).toBeVisible();
    await expect(card.getByText('有效 1', { exact: true })).toBeVisible();
    await expect(card.getByText('即将到期 1', { exact: true })).toBeVisible();
    await expect(card.getByText('已过期 1', { exact: true })).toBeVisible();
    await expect(card.getByText('长期 2', { exact: true })).toBeVisible();
    await expect(card.getByText('即将到期 (未来 14 天内)', { exact: true })).toBeVisible();
    await expect(card.getByText('已过期, 访问资格自动失效, 建议复核或重新委派', { exact: true })).toBeVisible();
    await expect(card.getByText('访问资格已失效 1', { exact: true })).toBeVisible();
    // H17: 委派到期后成员行仍在但标"已失效", 且该成员真实失去访问资格.
    await expect(card.getByText('已失效', { exact: true }).first()).toBeVisible();
    // 逐行到期徽标 (与汇总同源).
    await expect(card.getByText(/即将到期\s*剩\d+天/).first()).toBeVisible();
    await expect(card.getByText(/已过期\s*2000-01-01/).first()).toBeVisible();
    await expect(card.getByText(new RegExp(`有效至\\s*${farDate}`)).first()).toBeVisible();
    await expect(card.getByText('长期有效', { exact: true }).first()).toBeVisible();
    await panelShot(page, '项目团队', 'h16ml-1-lifecycle.png');

    // 空态: 仅项目经理 (长期) 的新项目, 面板仍渲染但只有"长期"计数.
    const p2 = await newProject(page, `${suffix}e`);
    const m2 = await api(page, 'GET', base(p2.id) + '/members');
    expect(m2.lifecycle.total).toBe(1);
    expect(m2.lifecycle.counts['open-ended']).toBe(1);
    expect(m2.lifecycle.expired).toHaveLength(0);

    // H17 精细职责分离: 到期成员的 access-effective? 为假, 真实 HTTP 读写均 403; 续期后恢复.
    expect(byId[uidExpired]['access-effective?']).toBe(false);
    expect(byId[uidFar]['access-effective?']).toBe(true);
    expect(byId[uidOpen]['access-effective?']).toBe(true);
    expect(byId[uidSoon]['access-effective?']).toBe(true);
    const expiredLogin = await page.request.post('/api/auth/login', { data: { username: `exp_${suffix}`, password: `E2e!${suffix}` } });
    const expiredToken = (await expiredLogin.json()).data.token;
    const denied = await page.request.fetch(base(id), { headers: { Authorization: `Bearer ${expiredToken}` } });
    expect(denied.status(), '委派已到期的成员读取项目应被拒绝').toBe(403);
    // 续期后同一成员恢复访问资格, 证明拦截依据是委派到期而非永久撤权.
    // (合成用户没有 pms 功能角色, 故用管理员读取的 access-effective? 判定数据范围是否恢复.)
    await api(page, 'POST', base(id) + '/members', { user_id: uidExpired, role: 'editor', ends_on: isoDay(90) });
    const renewed = await api(page, 'GET', base(id) + '/members');
    const renewedRow = renewed.rows.find(r => r.user_id === uidExpired);
    expect(renewedRow['access-effective?'], '续期后应恢复访问资格').toBe(true);
    expect(renewedRow['lifecycle-state']).toBe('active');


    // H16 成员撤销与权限生命周期: 撤销后真实失去访问, 台账留下撤销留痕, 界面只读呈现已撤销成员.
    expect(m.revoked, '未撤销时无撤销留痕').toEqual([]);
    expect(m['revoked_user_ids']).toEqual([]);
    const project = await api(page, 'GET', base(id));
    const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
    const revoke = await page.request.fetch(base(id) + `/members/${uidFar}`, { method: 'DELETE',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: { version: project.version }, timeout: 30000 });
    expect(revoke.status(), '撤销成员应成功').toBe(200);
    const after = await api(page, 'GET', base(id) + '/members');
    expect(after.rows.map(r => r.user_id)).not.toContain(uidFar);
    expect(after['revoked_user_ids']).toEqual([uidFar]);
    expect(after.revoked[0].user_id).toBe(uidFar);
    expect(after.revoked[0].actor_id).toBe(adminId);
    const farLogin = await page.request.post('/api/auth/login', { data: { username: `far_${suffix}`, password: `E2e!${suffix}` } });
    const farToken = (await farLogin.json()).data.token;
    const revokedRead = await page.request.fetch(base(id), { headers: { Authorization: `Bearer ${farToken}` } });
    expect(revokedRead.status(), '被撤销成员读取项目应被拒绝').toBe(403);

    // 界面: 已撤销成员只读留痕与撤销提示文案真实可见.
    await open(page, id);
    const teamCard = panel(page, '项目团队');
    await expect(teamCard.getByText('已撤销成员 1', { exact: true })).toBeVisible();
    await expect(teamCard.getByText(/撤销后该成员立即失去访问资格/).first()).toBeVisible();
    await expect(teamCard.getByText(new RegExp(`${uidFar} · `)).first()).toBeVisible();
    await panelShot(page, '项目团队', 'h16ml-2-revoked.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
