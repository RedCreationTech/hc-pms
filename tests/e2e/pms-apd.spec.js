const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H08 延伸: 会议行动优先级申报分布只读派生洞察 (免迁移, 无新命令/新kind/新路由).
// 优先级取自登记行动时可选枚举(高/中/低), 未申报即计入"未设定"; 在"会议行动"页签新增只读"会议行动优先级分布"面板:
// 以全部行动项为分母, 逐档统计 计数/未完成(排除 closed 与 converted)/其中逾期未完成, 并给已申报覆盖率与高优先级未完成数;
// 转任务(converted)后该档未完成回落为0而计数不变; 再登记一条高优先级未完成行动使分档与覆盖率实时上翻.
// 只读派生不改变任何行动状态. 全程单上下文(仅登记+读取), 无独立审批门控.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/apd');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
// shared/panel 渲染 <section> 内含 <h3> 标题; 面板靠下且与姊妹面板标签重名, 须按标题作用域定位避免 strict-mode 串台.
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) }).first();
const base = id => `/api/pms/projects/${id}`;
const TITLE = '会议行动优先级分布';

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

// 面板在抽屉自身滚动容器里靠下, 通用整页截不到 -> 滚动到面板元素并做元素级截图.
async function panelShot(page, title, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  const card = panel(page, title);
  await expect(card.getByRole('heading', { name: title, exact: true })).toBeVisible();
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// test.use 必须置于文件顶层 (放进 describe 会强制新 worker 报错).
test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

test.describe('H08 会议行动优先级分布只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('优先级面板按行动分档计数, 转任务后未完成回落而计数不变, 追加高优先级未完成行动后覆盖率上翻', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `APD-${suffix}`, name: `会议行动优先级分布验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 1) 建一场会, 再派生五条行动: 两条高(一未来一逾期), 一条中(将转任务), 一条低, 一条未申报优先级.
    const meeting = (await mutateData(page, id, '/governance/meetings',
      { title: `优先级分布评审-${suffix}`, held_on: '2026-09-22', minutes: '统一优先级申报', attendee_ids: [adminId], meeting_type: 'regular' })).result;
    const mid = meeting.id;
    const act = (priority, due) => mutateData(page, id, `/governance/meetings/${mid}/actions`,
      Object.assign({ title: `行动项-${suffix}-${Math.random().toString(36).slice(2, 6)}`, owner_id: adminId, due_date: due }, priority ? { priority } : {}));
    const aHiFuture = (await act('high', '2099-01-01')).result;
    const aHiPast = (await act('high', '2020-01-01')).result;
    const aMed = (await act('medium', '2099-01-01')).result;
    const aLow = (await act('low', '2099-01-01')).result;
    const aNone = (await act(null, '2099-01-01')).result;
    expect([aHiFuture.id, aHiPast.id, aMed.id, aLow.id, aNone.id].filter(Boolean).length).toBe(5);

    // 2) 真实HTTP读模型: total5/declared4/unassigned1/pct80, 高档计2未完成2逾期1, 中档1未完成1, 低档1未完成1, 高优先级未完成2.
    let d = (await api(page, 'GET', base(id) + '/governance')).action_priority_distribution;
    const bpk = k => d['by-priority'].find(x => x.priority === k);
    expect(d.available, '有数据').toBe(true);
    expect(d.total, '分母=行动项数').toBe(5);
    expect(d.declared, '落在三档内').toBe(4);
    expect(d.unassigned, '未申报优先级').toBe(1);
    expect(d['declared-pct'], '覆盖率四舍五入').toBe(80);
    expect(d['open-high'], '高优先级未完成').toBe(2);
    expect(bpk('high'), '高档').toMatchObject({ count: 2, open: 2, overdue: 1 });
    expect(bpk('medium'), '中档').toMatchObject({ count: 1, open: 1, overdue: 0 });
    expect(bpk('low'), '低档').toMatchObject({ count: 1, open: 1, overdue: 0 });
    expect(d['by-priority'].reduce((s, x) => s + x.count, 0), '各档计数之和=已声明').toBe(d.declared);
    expect(d.declared + d.unassigned, '已声明+未设定=总数').toBe(d.total);

    // 3) 界面面板真实渲染: 汇总标签与逐档徽标可见.
    await open(page, id, '需求与治理', '会议行动');
    const dist = panel(page, TITLE);
    await expect(dist).toBeVisible();
    await expect(dist.getByText('行动总数 5', { exact: true })).toBeVisible();
    await expect(dist.getByText('已申报优先级 80% (4/5)', { exact: true })).toBeVisible();
    await expect(dist.getByText('未设定优先级 1', { exact: true })).toBeVisible();
    await expect(dist.getByText('高优先级未完成 2', { exact: true })).toBeVisible();
    await expect(dist.getByText('按优先级:', { exact: true })).toBeVisible();
    await expect(dist.getByText('高 · 2 项 · 未完成 2 · 逾期 1', { exact: true })).toBeVisible();
    await expect(dist.getByText('中 · 1 项 · 未完成 1', { exact: true })).toBeVisible();
    await expect(dist.getByText('低 · 1 项 · 未完成 1', { exact: true })).toBeVisible();
    await panelShot(page, TITLE, 'apd-1-priority-distribution.png');

    // 4) 中档行动转真实任务(converted 计入完成): 该档计数不变但未完成归零, 徽标回落为"中 · 1 项".
    await mutateData(page, id, `/governance/actions/${aMed.id}/task`, { start_date: '2026-09-23', duration_days: 2 });
    d = (await api(page, 'GET', base(id) + '/governance')).action_priority_distribution;
    expect(bpk('medium').count, '中档计数不变').toBe(1);
    expect(bpk('medium').open, '中档未完成归零(转任务视为完成)').toBe(0);
    await open(page, id, '需求与治理', '会议行动');
    await expect(panel(page, TITLE).getByText('中 · 1 项', { exact: true })).toBeVisible();
    await expect(panel(page, TITLE).getByText('中 · 1 项 · 未完成 1', { exact: true })).toHaveCount(0);

    // 5) 追加一条高优先级未完成行动: 高档计数/未完成与高优先级未完成数上翻, 覆盖率随之变化.
    await act('high', '2099-03-01');
    d = (await api(page, 'GET', base(id) + '/governance')).action_priority_distribution;
    expect(d.total, '总数翻到6').toBe(6);
    expect(d.declared, '已声明翻到5').toBe(5);
    expect(d.unassigned, '未设定仍为1').toBe(1);
    expect(d['declared-pct'], '覆盖率翻到83').toBe(83);
    expect(d['open-high'], '高优先级未完成翻到3').toBe(3);
    expect(bpk('high'), '高档翻到3未完成3逾期1').toMatchObject({ count: 3, open: 3, overdue: 1 });
    await open(page, id, '需求与治理', '会议行动');
    const dist2 = panel(page, TITLE);
    await expect(dist2.getByText('行动总数 6', { exact: true })).toBeVisible();
    await expect(dist2.getByText('已申报优先级 83% (5/6)', { exact: true })).toBeVisible();
    await expect(dist2.getByText('高优先级未完成 3', { exact: true })).toBeVisible();
    await expect(dist2.getByText('高 · 3 项 · 未完成 3 · 逾期 1', { exact: true })).toBeVisible();
    await panelShot(page, TITLE, 'apd-2-after-new-high.png');

    // 6) 只读派生不改变行动状态: 转任务的中档仍 converted, 未动的逾期高档仍 open.
    const gov = await api(page, 'GET', base(id) + '/governance');
    const acts = gov.actions;
    const byId = rid => acts.find(a => String(a.id) === String(rid));
    expect(byId(aMed.id).status, '转任务行动仍为 converted(只读派生不改状态)').toBe('converted');
    expect(byId(aHiPast.id).status, '未动的逾期高档仍为 open').toBe('open');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
