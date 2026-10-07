const { test, expect } = require('@playwright/test');
const path = require('node:path');

// 到期治理事项总览: 跨风险/问题/会议行动只读聚合未闭环事项的到期压力到一处面板.
// 后端 collaboration.clj due-workload-overview 纯函数在 GET /governance 时按服务器"今天"派生, 全局分档
//   已逾期(<=0)/临期(1-7)/未来到期(8-30)/更远期(>30)/无到期日 + 未闭环总数, 逐来源 open/overdue/due-soon/upcoming 计数,
//   并给出按剩余天数升序(逾期在前)截断 15 的最近到期清单. 只读派生, 不改记录, 不构成门控, 免迁移/免新命令.
// 未闭环口径: 行动排除 closed/converted, 风险与问题排除 closed; 风险优先复审到期日否则登记到期日, 问题/行动取到期日.
// 后端风险/问题/行动三类登记命令的到期日均为必填(store date!), 故浏览器真实登记路径无法造出"无到期日(undated)"事项;
//   undated 档由 collaboration.clj 纯函数的后端单测(#836)直接对无到期日 map 覆盖. 本用例专注真实可达的四档分桶:
//   已逾期(<=0)/临期(1-7)/未来到期(8-30)/更远期(>30), 且三个来源都至少出现一次:
//   风险-逾期(-5) / 风险-临期(+3) / 问题-未来到期(+15) / 行动-更远期(+60 via 会议行动).
// 期望全局: open-total 4, overdue 1, due-soon 1, upcoming 1, further 1, undated 0;
//   by-risk{open 2,overdue 1,due-soon 1,upcoming 0} / by-issue{open 1,upcoming 1} / by-action{open 1};
//   soonest 4 条全带到期日, 按剩余天数升序, 首条为逾期的风险, 末条为更远期的行动. 面板在"需求与治理 -> 会议行动"页签真实可见, 与服务端 due_workload_overview 同源.
// 闭环剔除(单上下文即可, 不依赖第二审批人): 把"更远期"的会议行动"转为WBS任务" -> 行动状态 converted -> 从未闭环总览中剔除.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/due-workload');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) }).first();
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const dayOffset = n => { const d = new Date(); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };

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

async function saveForm(page, title) {
  const form = modal(page, title);
  const response = page.waitForResponse(r => r.url().includes('/api/pms/') && ['POST', 'PUT', 'DELETE'].includes(r.request().method()));
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(200);
  await expect(form).toBeHidden();
  await page.waitForLoadState('networkidle');
  return body.data;
}

async function createProject(page, suffix, deptId, adminId) {
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `DWK-${suffix}`, name: `到期治理总览验收 / ${suffix}`,
    project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
  return project.project_id;
}

test.describe('到期治理事项总览只读面板浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('四档分桶/逐来源计数/最近到期升序界面真实可见, 与服务端 due_workload_overview 同源; 行动转任务后剔除', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const id = await createProject(page, suffix, deptId, adminId);

    const rOver = `交付延期风险-${suffix}`;
    const rSoon = `供应商来料风险-${suffix}`;
    const iUp = `现场整改问题-${suffix}`;
    const aFar = `长期跟踪行动-${suffix}`;

    // 两条风险: 逾期(-5) 与 临期(+3); 登记必填 mitigation; 低分(2x3=6)不触发 H08 自动升级, 保持 open 计入未闭环.
    await mutateData(page, id, '/governance/risks', { title: rOver, probability: 2, impact: 3, owner_id: adminId, mitigation: '追加缓冲并每周跟踪关键路径', due_date: dayOffset(-5) });
    await mutateData(page, id, '/governance/risks', { title: rSoon, probability: 2, impact: 3, owner_id: adminId, mitigation: '锁定备选供应商并加严来料检验', due_date: dayOffset(3) });

    // 一条问题: 未来到期(+15); 登记必填 due_date; major 级不触发升级.
    await mutateData(page, id, '/governance/issues', { title: iUp, severity: 'major', owner_id: adminId, due_date: dayOffset(15) });

    // 一条会议行动: 更远期(+60 > 30), 经会议 -> 行动路径写入.
    const meeting = (await mutateData(page, id, '/governance/meetings',
      { title: `总览复盘会-${suffix}`, held_on: dayOffset(0), minutes: '到期压力跟踪', attendee_ids: [adminId] })).result;
    await mutateData(page, id, `/governance/meetings/${meeting.id}/actions`,
      { title: aFar, owner_id: adminId, due_date: dayOffset(60) });

    // 服务端只读派生回显 (GET /governance 的 data.due_workload_overview).
    const gov = await api(page, 'GET', base(id) + '/governance');
    const ov = gov.due_workload_overview;
    expect(ov.available).toBe(true);
    expect(ov['open-total']).toBe(4);
    expect(ov.overdue).toBe(1);
    expect(ov['due-soon']).toBe(1);
    expect(ov.upcoming).toBe(1);
    expect(ov.further).toBe(1);
    expect(ov.undated).toBe(0);
    expect(ov['by-risk']).toEqual({ open: 2, overdue: 1, 'due-soon': 1, upcoming: 0 });
    expect(ov['by-issue']).toEqual({ open: 1, overdue: 0, 'due-soon': 0, upcoming: 1 });
    expect(ov['by-action']).toEqual({ open: 1, overdue: 0, 'due-soon': 0, upcoming: 0 });

    // soonest 4 条全带到期日, 按剩余天数升序, 逾期风险居首, 更远期行动居末.
    expect(ov.soonest).toHaveLength(4);
    expect(ov.soonest.map(x => x.source)).toEqual(['risk', 'risk', 'issue', 'action']);
    const soonDays = ov.soonest.map(x => x['due-days']);
    expect([...soonDays].sort((a, b) => a - b)).toEqual(soonDays);
    expect(soonDays[0]).toBeLessThanOrEqual(-4);
    expect(soonDays[3]).toBeGreaterThanOrEqual(59);

    // 界面: "需求与治理 -> 会议行动" 页签内面板真实渲染, 全局分档徽标与逐来源/最近到期可见.
    await open(page, id, '需求与治理', '会议行动');
    const card = panel(page, '到期治理事项总览');
    await expect(card.getByText('未闭环事项 4', { exact: true })).toBeVisible();
    await expect(card.getByText('已逾期 1', { exact: true })).toBeVisible();
    await expect(card.getByText('临期(7天内) 1', { exact: true })).toBeVisible();
    await expect(card.getByText('未来到期(30天内) 1', { exact: true })).toBeVisible();
    await expect(card.getByText('更远期 1', { exact: true })).toBeVisible();
    await expect(card.getByText(/无到期日 \d+/)).toHaveCount(0);
    await expect(card.getByText('按来源:', { exact: true })).toBeVisible();
    await expect(card.getByText(/风险 · 未闭环 2 · 逾期 1 · 临期 1/)).toBeVisible();
    await expect(card.getByText(/问题 · 未闭环 1 · 未来 1/)).toBeVisible();
    await expect(card.getByText(/行动 · 未闭环 1/)).toBeVisible();
    await expect(card.getByText('最近到期:', { exact: true })).toBeVisible();
    await expect(card.getByText(new RegExp(`\\[风险\\] ${rOver} · 已逾期 \\d+ 天`))).toBeVisible();
    await expect(card.getByText(new RegExp(`\\[行动\\] ${aFar} · 剩 \\d+ 天`))).toBeVisible();
    await panelShot(page, '到期治理事项总览', 'dwk-1-overview.png');

    // 闭环剔除(单上下文): 把"更远期"的会议行动"转为WBS任务" -> converted -> 从未闭环总览中剔除.
    await row(page, aFar).getByRole('button', { name: '转为WBS任务', exact: true }).click();
    const convForm = modal(page, '会议行动转WBS任务');
    await convForm.locator('#start_date').fill(dayOffset(0));
    await saveForm(page, '会议行动转WBS任务');

    const gov2 = await api(page, 'GET', base(id) + '/governance');
    const ov2 = gov2.due_workload_overview;
    expect(ov2['open-total']).toBe(3);
    expect(ov2.further).toBe(0); // 唯一"更远期"项正是刚转任务的行动
    expect(ov2['by-action']).toEqual({ open: 0, overdue: 0, 'due-soon': 0, upcoming: 0 });

    await open(page, id, '需求与治理', '会议行动');
    const card2 = panel(page, '到期治理事项总览');
    await expect(card2.getByText('未闭环事项 3', { exact: true })).toBeVisible();
    await expect(card2.getByText('更远期 1', { exact: true })).toHaveCount(0);
    await expect(card2.getByText(/行动 · 未闭环 1/)).toHaveCount(0);
    await expect(card2.getByText(new RegExp(`\\[行动\\] ${aFar}`))).toHaveCount(0);
    await panelShot(page, '到期治理事项总览', 'dwk-2-after-convert.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });

  test('空项目面板渲染空态提示 (无未闭环到期事项)', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const id = await createProject(page, `${suffix}e`, deptId, adminId);

    const gov = await api(page, 'GET', base(id) + '/governance');
    expect(gov.due_workload_overview.available).toBe(false);
    expect(gov.due_workload_overview['open-total']).toBe(0);

    await open(page, id, '需求与治理', '会议行动');
    const card = panel(page, '到期治理事项总览');
    await expect(card.getByText('暂无未闭环的到期事项', { exact: false })).toBeVisible();
    await panelShot(page, '到期治理事项总览', 'dwk-3-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
