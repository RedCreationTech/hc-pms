const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H02 延伸: 沟通渠道使用分布项目级只读派生洞察 (免迁移, 无新命令/新kind/新路由).
// 界面"登记沟通计划" -> 多次"标记已沟通"并各自选本次实际渠道 -> "干系人与沟通"页签新增只读"沟通渠道使用分布"面板:
// 按每个沟通计划最新有效版本(store/latest 折叠修订链)汇聚其全部沟通留痕的 :channel, 固定会议/邮件/看板/报告/评审五档各自使用次数,
// 给出沟通留痕总数 / 渠道覆盖率 / 未标注渠道 / 主导渠道(使用次数最多者); 每多一次带渠道的沟通留痕各档实时翻转, 主导渠道可被反超.
// 只读派生复用 log-communication! 已落库的 :channel 口径, 与"沟通计划执行落地覆盖度"面板互补(执行看计划是否已落地而本项看执行时渠道的使用结构),
// 不落库不投递, 不改变任何不可变版本, 不构成任何门控.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/ccu');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
// shared/panel 渲染 <section> 内含 <h3> 标题; 按面板标题作用域定位避免 strict-mode 串台.
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

// 相对今天偏移 offsetDays 天算出 YYYY-MM-DD (用本地日历, 免疫 today 漂移).
function isoDate(offsetDays) {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${m}-${day}`;
}

// 打开"登记干系人"填必填项并保存 (责任人 admin), 返回受众标签 "编号 / 名称".
async function createStakeholder(page, code) {
  await drawer(page).getByRole('button', { name: '登记干系人', exact: true }).click();
  const form = modal(page, '登记干系人');
  await form.locator('#code').fill(code);
  await form.locator('#name').fill('进度对接人');
  await form.locator('#role').fill('需求方接口');
  await choose(page, form, 'category', '客户');
  await choose(page, form, 'interest', 'high');
  await choose(page, form, 'influence', 'high');
  await choose(page, form, 'owner_id', 'admin');
  await save(page, '登记干系人');
  return `${code} / 进度对接人`;
}

// 打开"登记沟通计划"填必填项: 指定编号/默认渠道/频率/受众(单个干系人)/下次沟通日期, 保存.
async function createCommPlan(page, { code, channelLabel, audienceLabel }) {
  await drawer(page).getByRole('button', { name: '登记沟通计划', exact: true }).click();
  const form = modal(page, '登记沟通计划');
  await form.locator('#code').fill(code);
  await form.locator('#objective').fill('同步项目进度与风险');
  await choose(page, form, 'channel', channelLabel);
  await choose(page, form, 'frequency', 'weekly');
  await choose(page, form, 'audience', audienceLabel);
  await form.locator('#next_date').fill(isoDate(30));
  await choose(page, form, 'owner_id', 'admin');
  await save(page, '登记沟通计划');
}

// 在"沟通计划"台账中定位指定编号所在行, 点击"标记已沟通"并在弹窗填纪要 + 选本次实际渠道后保存.
// channelLabel 为 null 时不选渠道, 服务端回退沿用计划默认渠道.
async function logCommunication(page, code, channelLabel) {
  await open(page, global.__pid, '需求与治理', '干系人与沟通');
  const row = panel(page, '沟通计划').locator('tr').filter({ hasText: code });
  await row.getByRole('button', { name: '标记已沟通', exact: true }).click();
  const form = modal(page, '标记已沟通');
  await expect(form).toBeVisible();
  await form.locator('#note').fill(`已就 ${code} 完成一次进度对齐`);
  if (channelLabel) await choose(page, form, 'channel', channelLabel);
  await save(page, '标记已沟通');
}

test.describe('H02 沟通渠道使用分布只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('渠道使用分布面板按最新计划聚合各渠道留痕次数与主导渠道, 补登记带渠道留痕后主导渠道被反超', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `CU-${suffix}`, name: `沟通渠道使用分布验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    global.__pid = id;
    await open(page, id, '需求与治理', '干系人与沟通');

    // 先登记一个干系人作为两条沟通计划的受众.
    const shCode = `SH-${suffix}`;
    const audienceLabel = await createStakeholder(page, shCode);

    // 两条沟通计划: A 默认渠道会议, B 默认渠道邮件.
    const aCode = `CU-A-${suffix}`;
    const bCode = `CU-B-${suffix}`;
    await createCommPlan(page, { code: aCode, channelLabel: '会议', audienceLabel });
    await createCommPlan(page, { code: bCode, channelLabel: '邮件', audienceLabel });

    // 面板初始态: 尚无任何沟通留痕 -> 展示空态占位文案, 不出现"沟通留痕总数"标签.
    await open(page, id, '需求与治理', '干系人与沟通');
    const covEmpty = panel(page, '沟通渠道使用分布');
    await expect(covEmpty).toBeVisible();
    await expect(covEmpty.getByText('暂无沟通留痕, 标记已沟通后可在此查看渠道使用分布.', { exact: true })).toBeVisible();
    await expect(covEmpty.getByText('沟通留痕总数', { exact: false })).toHaveCount(0);

    // 1) 阶段一 三条留痕: A 两次会议 + B 一次邮件(显式选邮件) -> 总数3, 会议2 邮件1, 覆盖率100%, 主导渠道 会议·2.
    await logCommunication(page, aCode, '会议');
    await logCommunication(page, aCode, '会议');
    await logCommunication(page, bCode, '邮件');
    await open(page, id, '需求与治理', '干系人与沟通');
    const cov1 = panel(page, '沟通渠道使用分布');
    await expect(cov1.getByText('沟通留痕总数 3', { exact: true })).toBeVisible();
    await expect(cov1.getByText('渠道覆盖率 100%', { exact: true })).toBeVisible();
    await expect(cov1.getByText('主导渠道 会议 · 2', { exact: true })).toBeVisible();
    await expect(cov1.getByText('会议 · 2', { exact: true })).toBeVisible();
    await expect(cov1.getByText('邮件 · 1', { exact: true })).toBeVisible();
    // 看板/报告/评审当前无留痕, 不渲染对应彩色标签; 全部已标注故无"未标注渠道".
    await expect(cov1.getByText('看板 ·', { exact: false })).toHaveCount(0);
    await expect(cov1.getByText('未标注渠道', { exact: false })).toHaveCount(0);
    await panelShot(cov1, 'ccu-1-meeting-dominant.png');

    // 2) 阶段二 再给 B 补两次邮件 -> 总数5, 邮件反超为3, 会议仍2, 主导渠道翻转为 邮件·3, 覆盖率仍100%.
    await logCommunication(page, bCode, '邮件');
    await logCommunication(page, bCode, '邮件');
    await open(page, id, '需求与治理', '干系人与沟通');
    const cov2 = panel(page, '沟通渠道使用分布');
    await expect(cov2.getByText('沟通留痕总数 5', { exact: true })).toBeVisible();
    await expect(cov2.getByText('渠道覆盖率 100%', { exact: true })).toBeVisible();
    await expect(cov2.getByText('主导渠道 邮件 · 3', { exact: true })).toBeVisible();
    await expect(cov2.getByText('邮件 · 3', { exact: true })).toBeVisible();
    await expect(cov2.getByText('会议 · 2', { exact: true })).toBeVisible();
    await panelShot(cov2, 'ccu-2-email-dominant.png');

    // 3) 真实HTTP读模型回显 comm_channel_usage 与面板同源.
    const ws = await api(page, 'GET', base(id) + '/governance');
    const cu = ws.comm_channel_usage;
    const countOf = ch => (cu['by-channel'].find(x => x.channel === ch) || {}).count;
    expect(cu.available, '有沟通留痕').toBe(true);
    expect(cu['total-logs'], '留痕总数').toBe(5);
    expect(cu.declared, '已标注渠道数').toBe(5);
    expect(cu.unassigned, '未标注渠道').toBe(0);
    expect(cu['coverage-pct'], '渠道覆盖率').toBe(100);
    expect(cu['dominant-channel'], '主导渠道为邮件').toBe('email');
    expect(cu['dominant-count'], '邮件使用次数').toBe(3);
    expect(countOf('meeting'), '会议留痕数').toBe(2);
    expect(countOf('email'), '邮件留痕数').toBe(3);
    expect(countOf('dashboard'), '看板留痕数').toBe(0);
    expect(countOf('report'), '报告留痕数').toBe(0);
    expect(countOf('review'), '评审留痕数').toBe(0);
    expect(cu['by-channel'].map(x => x.channel), '五档固定顺序').toEqual(['meeting', 'email', 'dashboard', 'report', 'review']);

    // 4) 只读派生不改变记录: 重复读取稳定, 两条计划仍 active.
    const ws2 = await api(page, 'GET', base(id) + '/governance');
    expect(ws2.comm_channel_usage, '只读派生重复读取稳定').toEqual(cu);
    expect(ws2.comm_plans.find(p => p.code === aCode).status, '计划A仍登记态').toBe('active');
    expect(ws2.comm_plans.find(p => p.code === bCode).status, '计划B仍登记态').toBe('active');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
