const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H02 延伸: 沟通计划受众广度分布项目级只读派生洞察 (免迁移, 无新命令/新kind/新路由).
// 界面"登记干系人" -> "登记沟通计划"多选受众(1..50个不重复干系人) -> "干系人与沟通"页签新增只读"沟通计划受众广度分布"面板:
// 按每个沟通计划最新有效版本(store/latest 折叠修订链, 剔除已作废)只读聚合每条计划面向受众人数的广度分布,
// 给出沟通计划总数 / 设定受众数 / 未设定受众数 / 受众引用总数 / 平均广度 / 最宽计划, 以及单受众/2-3人/4人及以上三档各自计划数.
// 与"沟通受众覆盖度"面板互补(覆盖度从干系人侧看谁被触达而本项从计划侧看每条计划面向多广),
// 只读派生复用 create-comm-plan! 已落库的 :audience 口径, 不落库不投递, 不改变任何不可变版本, 不构成任何门控.
// 翻转通过单个操作者追加"登记"一条更宽受众的沟通计划达成(单上下文可见的纯只读翻转, 依 Rule B 可进 B 列).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/cab');
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

// 多选受众: 打开下拉后逐个点选标签(选项点击后下拉保持展开), 全部选完再 Escape 收起.
async function chooseMulti(page, form, key, labels) {
  const input = form.locator(`#${key}`);
  await input.click();
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${list}"]`) });
  for (const label of labels) {
    await dropdown.locator('.ant-select-item-option').filter({ hasText: label }).first().click();
  }
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
async function createStakeholder(page, code, name) {
  await drawer(page).getByRole('button', { name: '登记干系人', exact: true }).click();
  const form = modal(page, '登记干系人');
  await form.locator('#code').fill(code);
  await form.locator('#name').fill(name);
  await form.locator('#role').fill('需求方接口');
  await choose(page, form, 'category', '客户');
  await choose(page, form, 'interest', 'high');
  await choose(page, form, 'influence', 'high');
  await choose(page, form, 'owner_id', 'admin');
  await save(page, '登记干系人');
  return `${code} / ${name}`;
}

// 打开"登记沟通计划"填必填项: 指定编号/渠道/频率/受众(多选干系人标签数组)/下次沟通日期, 保存.
async function createCommPlan(page, { code, channelLabel, audienceLabels }) {
  await drawer(page).getByRole('button', { name: '登记沟通计划', exact: true }).click();
  const form = modal(page, '登记沟通计划');
  await form.locator('#code').fill(code);
  await form.locator('#objective').fill('同步项目进度与风险');
  await choose(page, form, 'channel', channelLabel);
  await choose(page, form, 'frequency', 'weekly');
  await chooseMulti(page, form, 'audience', audienceLabels);
  await form.locator('#next_date').fill(isoDate(30));
  await choose(page, form, 'owner_id', 'admin');
  await save(page, '登记沟通计划');
}

test.describe('H02 沟通计划受众广度分布只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('受众广度分布面板按最新计划聚合三档广度与平均/最宽计划, 追加一条更宽受众计划后最宽计划与4人及以上档翻转', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `AB-${suffix}`, name: `沟通计划受众广度分布验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    global.__pid = id;
    await open(page, id, '需求与治理', '干系人与沟通');

    // 先登记 5 个干系人作为各沟通计划的可选受众 (标签唯一, 便于多选下拉精确匹配).
    const s = [];
    for (let i = 1; i <= 5; i++) s.push(await createStakeholder(page, `SH-${suffix}-${i}`, `干系人${i}`));

    // 面板初始态: 尚无沟通计划 -> 展示空态占位文案, 不出现"沟通计划总数"标签.
    await open(page, id, '需求与治理', '干系人与沟通');
    const covEmpty = panel(page, '沟通计划受众广度分布');
    await expect(covEmpty).toBeVisible();
    await expect(covEmpty.getByText('暂无沟通计划, 登记后可在此查看受众广度分布.', { exact: true })).toBeVisible();
    await expect(covEmpty.getByText('沟通计划总数', { exact: false })).toHaveCount(0);

    // 阶段一 三条计划: P1 单受众, P2 两人(2-3人档), P3 四人(4人及以上档).
    const p1Code = `AB-P1-${suffix}`;
    const p2Code = `AB-P2-${suffix}`;
    const p3Code = `AB-P3-${suffix}`;
    await createCommPlan(page, { code: p1Code, channelLabel: '会议', audienceLabels: [s[0]] });
    await createCommPlan(page, { code: p2Code, channelLabel: '邮件', audienceLabels: [s[0], s[1]] });
    await createCommPlan(page, { code: p3Code, channelLabel: '会议', audienceLabels: [s[0], s[1], s[2], s[3]] });

    // 总数3, 全设定受众(无未设定), 引用7 -> 平均 round(7/3)=2, 最大4 最宽计划 P3, 三档各1.
    await open(page, id, '需求与治理', '干系人与沟通');
    const cov1 = panel(page, '沟通计划受众广度分布');
    await expect(cov1.getByText('沟通计划总数 3', { exact: true })).toBeVisible();
    await expect(cov1.getByText('设定受众 3', { exact: true })).toBeVisible();
    await expect(cov1.getByText('受众引用总数 7', { exact: true })).toBeVisible();
    await expect(cov1.getByText('平均广度 2 人', { exact: true })).toBeVisible();
    await expect(cov1.getByText(`最宽计划 ${p3Code} · 4 人`, { exact: true })).toBeVisible();
    await expect(cov1.getByText('单受众 · 1', { exact: true })).toBeVisible();
    await expect(cov1.getByText('2-3 人 · 1', { exact: true })).toBeVisible();
    await expect(cov1.getByText('4 人及以上 · 1', { exact: true })).toBeVisible();
    // 全部计划均已设定受众, 不渲染"未设定受众 N"橙色标签; 面板说明文案含"未设定受众计划数"字样, 故用带数字的锚定正则只匹配标签本身.
    await expect(cov1.getByText(/^未设定受众 \d+$/)).toHaveCount(0);
    await panelShot(cov1, 'cab-1-p3-widest.png');

    // 阶段二 单操作者追加登记 P4 五人受众 -> 总数4, 引用12 平均3, 最宽计划翻转为 P4·5, 4人及以上档 1->2.
    const p4Code = `AB-P4-${suffix}`;
    await createCommPlan(page, { code: p4Code, channelLabel: '评审', audienceLabels: [s[0], s[1], s[2], s[3], s[4]] });
    await open(page, id, '需求与治理', '干系人与沟通');
    const cov2 = panel(page, '沟通计划受众广度分布');
    await expect(cov2.getByText('沟通计划总数 4', { exact: true })).toBeVisible();
    await expect(cov2.getByText('设定受众 4', { exact: true })).toBeVisible();
    await expect(cov2.getByText('受众引用总数 12', { exact: true })).toBeVisible();
    await expect(cov2.getByText('平均广度 3 人', { exact: true })).toBeVisible();
    await expect(cov2.getByText(`最宽计划 ${p4Code} · 5 人`, { exact: true })).toBeVisible();
    await expect(cov2.getByText('4 人及以上 · 2', { exact: true })).toBeVisible();
    await expect(cov2.getByText('单受众 · 1', { exact: true })).toBeVisible();
    await expect(cov2.getByText('2-3 人 · 1', { exact: true })).toBeVisible();
    await panelShot(cov2, 'cab-2-p4-widest.png');

    // 真实HTTP读模型回显 comm_audience_breadth 与面板同源.
    const ws = await api(page, 'GET', base(id) + '/governance');
    const ab = ws.comm_audience_breadth;
    const bucketOf = k => (ab['by-breadth'].find(x => x.bucket === k) || {}).count;
    expect(ab.available, '有沟通计划').toBe(true);
    expect(ab.total, '计划总数').toBe(4);
    expect(ab.targeted, '设定受众数').toBe(4);
    expect(ab.untargeted, '未设定受众数').toBe(0);
    expect(ab['total-refs'], '受众引用总数').toBe(12);
    expect(ab['avg-size'], '平均广度').toBe(3);
    expect(ab['max-size'], '最大广度').toBe(5);
    expect(ab['widest-plan'].code, '最宽计划编码').toBe(p4Code);
    expect(ab['widest-plan'].size, '最宽计划人数').toBe(5);
    expect(bucketOf('solo'), '单受众档').toBe(1);
    expect(bucketOf('few'), '2-3人档').toBe(1);
    expect(bucketOf('many'), '4人及以上档').toBe(2);
    expect(ab['by-breadth'].map(x => x.bucket), '三档固定顺序').toEqual(['solo', 'few', 'many']);
    expect(ab.targeted + ab.untargeted, '设定+未设定=总数').toBe(ab.total);
    expect(bucketOf('solo') + bucketOf('few') + bucketOf('many'), '三档之和=设定受众数').toBe(ab.targeted);

    // 只读派生不改变记录: 重复读取稳定, 四条计划仍 active.
    const ws2 = await api(page, 'GET', base(id) + '/governance');
    expect(ws2.comm_audience_breadth, '只读派生重复读取稳定').toEqual(ab);
    for (const code of [p1Code, p2Code, p3Code, p4Code]) {
      expect(ws2.comm_plans.find(p => p.code === code).status, `计划 ${code} 仍登记态`).toBe('active');
    }

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
