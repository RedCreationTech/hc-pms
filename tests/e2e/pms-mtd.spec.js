const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H04 延伸: 会议类型分布项目级只读派生洞察 (免迁移, 无新命令/新kind/新路由).
// 界面"登记项目会议"选会议类型(meeting_type, 必填受控枚举: 常规会议/项目启动会/评审会/FAT启动会/FAT总结会) ->
// "会议行动"页签新增只读"会议类型分布"面板: 按每个会议最新有效版本(store/latest 折叠修订链, 剔除已作废)
// 只读聚合五档会议类型的项目级分布, 给出会议总数 / 已用类型档数 / 缺档类型档数 / 类型覆盖率 / 主导类型,
// 以及固定五档(例会/启动会/评审会/FAT启动/FAT总结)各自会议数与占比.
// meeting_type 为必填(缺省 regular)受控枚举, 故五档会议数之和恒等于会议总数(无未设定档), 与干系人类别/象限分布同族.
// 与会议参会概览(看出席)和节奏分布(看时间间隔)互补, 只读派生不落库不投递, 不改变任何不可变版本, 不构成任何门控.
// 主导类型翻转由单个操作者追加"登记项目会议"达成(单上下文可见的纯只读加性翻转, 依 Rule B 可进 B 列).
// 说明: 面板逐档标签取自后端 distribution 标签(例会/启动会/评审会/FAT启动/FAT总结), 与登记表单下拉标签
// (常规会议/项目启动会/评审会/FAT启动会/FAT总结会)不同名; 本用例避开 kickoff(启动会会前包/主计划基线 409 门控),
// 只用 regular/review/fat-kickoff/fat-summary 四类构造.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/mtd');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
// shared/panel 渲染 <section> 内含 <h3> 标题; 按面板标题作用域定位避免 strict-mode 串台.
// 注意: 同页签的"会议参会覆盖与出勤分布"面板正文含"会议类型分布:"字样(非标题), 用 heading exact 只命中本面板.
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) });
const base = id => `/api/pms/projects/${id}`;

// test.use 必须置于文件顶层 (放进 describe 会强制新 worker 报错).
test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

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

// 同表单多个 antd Select 的下拉面板会同时留在 DOM, 用 combobox 自身 aria-controls 过滤其下拉避免串台.
async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  if (typeof text === 'string' && await input.isEditable()) await input.fill(text);
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await form.locator(`#${key}`).press('Escape');
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

// 面板在抽屉自身滚动容器里靠下, 通用整页截不到 -> 滚动到面板元素并做元素级截图.
async function panelShot(cov, file) {
  await cov.page().waitForLoadState('networkidle');
  await cov.scrollIntoViewIfNeeded();
  await cov.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 打开"登记项目会议"填必填项(主题/类型/日期/参会人/纪要), typeLabel 取表单下拉标签, 保存.
// 只用 regular/review/fat-kickoff/fat-summary 四类, 规避 kickoff 的会前包+主计划基线强制关联 409 门控.
async function createMeeting(page, title, typeLabel, heldOn) {
  await drawer(page).getByRole('button', { name: '登记项目会议', exact: true }).click();
  const form = modal(page, '登记项目会议');
  await form.locator('#title').fill(title);
  await choose(page, form, 'meeting_type', typeLabel);
  await form.locator('#held_on').fill(heldOn);
  await choose(page, form, 'attendee_ids', 'admin');
  await form.locator('#minutes').fill(`会议结论: 类型分布验收 ${title}.`);
  await save(page, '登记项目会议');
}

test.describe('H04 会议类型分布只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('类型分布面板按最新会议聚合五档类型与已用/缺档/覆盖/主导类型, 追加登记使主导类型严格翻转', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `MTD-${suffix}`, name: `会议类型分布验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '会议行动');

    // 面板初始态: 尚无会议 -> 展示空态占位文案, 不出现"会议总数"标签.
    const distEmpty = panel(page, '会议类型分布');
    await expect(distEmpty).toBeVisible();
    await expect(distEmpty.getByText('暂无有效会议, 登记会议后可在此查看五档类型分布.', { exact: true })).toBeVisible();
    await expect(distEmpty.getByText(/^会议总数 \d+$/)).toHaveCount(0);
    await expect(distEmpty.getByText(/^主导类型 /)).toHaveCount(0);

    // 阶段一 三场会议: 例会x2, 评审会x1. total3 covered2 uncovered3 pct40, 主导例会(2)唯一最大.
    await createMeeting(page, `例会甲 ${suffix}`, '常规会议', '2026-09-10');
    await createMeeting(page, `评审会甲 ${suffix}`, '评审会', '2026-09-15');
    await createMeeting(page, `例会乙 ${suffix}`, '常规会议', '2026-09-20');

    await open(page, id, '需求与治理', '会议行动');
    const dist1 = panel(page, '会议类型分布');
    await expect(dist1.getByText('会议总数 3', { exact: true })).toBeVisible();
    await expect(dist1.getByText('类型覆盖率 40%', { exact: true })).toBeVisible();
    await expect(dist1.getByText('已用类型 2', { exact: true })).toBeVisible();
    await expect(dist1.getByText('缺档类型 3', { exact: true })).toBeVisible();
    await expect(dist1.getByText('主导类型 例会 · 2', { exact: true })).toBeVisible();
    await expect(dist1.getByText('例会 · 2 (67%)', { exact: true })).toBeVisible();
    await expect(dist1.getByText('启动会 · 0 (0%)', { exact: true })).toBeVisible();
    await expect(dist1.getByText('评审会 · 1 (33%)', { exact: true })).toBeVisible();
    await expect(dist1.getByText('FAT启动 · 0 (0%)', { exact: true })).toBeVisible();
    await expect(dist1.getByText('FAT总结 · 0 (0%)', { exact: true })).toBeVisible();
    await panelShot(dist1, 'mtd-1-regular-dominant.png');

    // 阶段二 追加登记 FAT启动会 + FAT总结会 -> 覆盖4, 缺档1, 覆盖率 40->80, 主导仍例会(2)唯一最大.
    await createMeeting(page, `FAT启动甲 ${suffix}`, 'FAT启动会', '2026-09-25');
    await createMeeting(page, `FAT总结甲 ${suffix}`, 'FAT总结会', '2026-09-28');

    await open(page, id, '需求与治理', '会议行动');
    const dist2 = panel(page, '会议类型分布');
    await expect(dist2.getByText('会议总数 5', { exact: true })).toBeVisible();
    await expect(dist2.getByText('类型覆盖率 80%', { exact: true })).toBeVisible();
    await expect(dist2.getByText('已用类型 4', { exact: true })).toBeVisible();
    await expect(dist2.getByText('缺档类型 1', { exact: true })).toBeVisible();
    await expect(dist2.getByText('主导类型 例会 · 2', { exact: true })).toBeVisible();
    await expect(dist2.getByText('例会 · 2 (40%)', { exact: true })).toBeVisible();
    await expect(dist2.getByText('评审会 · 1 (20%)', { exact: true })).toBeVisible();
    await expect(dist2.getByText('FAT启动 · 1 (20%)', { exact: true })).toBeVisible();
    await expect(dist2.getByText('FAT总结 · 1 (20%)', { exact: true })).toBeVisible();
    await panelShot(dist2, 'mtd-2-covered-four.png');

    // 阶段三 追加登记一场"评审会" -> 例会与评审会各2并列, max-key 取固定序靠后者 -> 主导翻转为 评审会·2.
    await createMeeting(page, `评审会乙 ${suffix}`, '评审会', '2026-10-02');

    await open(page, id, '需求与治理', '会议行动');
    const dist3 = panel(page, '会议类型分布');
    await expect(dist3.getByText('会议总数 6', { exact: true })).toBeVisible();
    await expect(dist3.getByText('类型覆盖率 80%', { exact: true })).toBeVisible();
    await expect(dist3.getByText('已用类型 4', { exact: true })).toBeVisible();
    await expect(dist3.getByText('缺档类型 1', { exact: true })).toBeVisible();
    await expect(dist3.getByText('主导类型 评审会 · 2', { exact: true })).toBeVisible();
    // 主导标签已翻转: 不再出现旧的"主导类型 例会"徽标.
    await expect(dist3.getByText('主导类型 例会 · 2', { exact: true })).toHaveCount(0);
    await expect(dist3.getByText('例会 · 2 (33%)', { exact: true })).toBeVisible();
    await expect(dist3.getByText('评审会 · 2 (33%)', { exact: true })).toBeVisible();
    await expect(dist3.getByText('FAT启动 · 1 (17%)', { exact: true })).toBeVisible();
    await expect(dist3.getByText('FAT总结 · 1 (17%)', { exact: true })).toBeVisible();
    await panelShot(dist3, 'mtd-3-review-dominant.png');

    // 真实HTTP读模型回显 meeting_type_distribution 与面板同源.
    const ws = await api(page, 'GET', base(id) + '/governance');
    const dist = ws.meeting_type_distribution;
    const tc = k => (dist['by-type'].find(x => x.type === k) || {});
    expect(dist.available, '有会议').toBe(true);
    expect(dist.total, '会议总数').toBe(6);
    expect(dist.covered, '已用类型档数').toBe(4);
    expect(dist.uncovered, '缺档类型档数').toBe(1);
    expect(dist['coverage-pct'], '类型覆盖率').toBe(80);
    expect(dist['dominant-type'], '主导类型').toBe('review');
    expect(dist['dominant-count'], '主导类型会议数').toBe(2);
    expect(tc('regular').count, '例会').toBe(2);
    expect(tc('kickoff').count, '启动会').toBe(0);
    expect(tc('review').count, '评审会').toBe(2);
    expect(tc('fat-kickoff').count, 'FAT启动').toBe(1);
    expect(tc('fat-summary').count, 'FAT总结').toBe(1);
    expect(dist['by-type'].map(x => x.type), '五档固定顺序')
      .toEqual(['regular', 'kickoff', 'review', 'fat-kickoff', 'fat-summary']);
    expect(dist.covered + dist.uncovered, '覆盖+缺档=类型档总数5').toBe(5);
    expect(dist['by-type'].reduce((s, x) => s + x.count, 0), '五档之和=会议总数').toBe(dist.total);

    // 只读派生不改变记录: 重复读取稳定, 六场会议仍 recorded.
    const ws2 = await api(page, 'GET', base(id) + '/governance');
    expect(ws2.meeting_type_distribution, '只读派生重复读取稳定').toEqual(dist);
    expect(ws2.meetings.filter(m => m.status !== 'discarded').length, '有效会议数').toBe(6);
    for (const m of ws2.meetings) expect(m.status, `会议 ${m.title} 仍登记态`).toBe('recorded');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
