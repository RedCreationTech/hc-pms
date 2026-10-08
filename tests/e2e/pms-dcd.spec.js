const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C04/C05 延伸: 文档密级分布项目级只读派生洞察 (免迁移, 无新命令/新kind/新路由).
// 界面"登记证据文档"选密级(classification, 必填受控枚举 公开/内部/机密 public/internal/confidential) ->
// "证据版本"页签新增只读"文档密级分布"面板: 按每个文档业务编码最新有效版本(store/latest 折叠修订链, 剔除已作废)
// 只读聚合公开/内部/机密三档的项目级分布, 给出文档总数 / 已用档位 / 缺档档位 / 密级覆盖率 / 主导密级,
// 以及固定三档(公开/内部/机密)各自文档数与占比. classification 未选择时服务端默认记为内部, 恒为三档之一,
// 故三档之和恒等于文档总数(无未设定档), 与需求优先级/会议类型/干系人类别/象限分布同族,
// 区别于文档多层下钻归集(本项看密级构成占比而非层级嵌套), 更不据密级做任何门控.
// 主导密级翻转由单个操作者追加"登记证据文档"达成(单上下文可见的纯只读加性翻转, 依 Rule B 可进 B 列):
//   并列时 apply max-key 取固定顺序 [public internal confidential] 的靠后者 => dominant confidential;
//   机密反超公开 => 主导由 公开 翻转为 机密.
// 面板逐档标签取自后端 distribution 标签(公开/内部/机密), 与登记表单下拉标签同口径(governance_forms.cljs options).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/dcd');
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

// 打开"登记证据文档"填必填项(编号/标题/密级/文件名/正文), classLabel 取表单下拉标签(公开/内部/机密), 保存.
async function createDocument(page, code, title, classLabel) {
  await drawer(page).getByRole('button', { name: '登记证据文档', exact: true }).click();
  const form = modal(page, '登记证据文档');
  await form.locator('#code').fill(code);
  await form.locator('#title').fill(title);
  await choose(page, form, 'classification', classLabel);
  await form.locator('#filename').fill(`${code}.txt`);
  await form.locator('#content').fill(`实际证据文本 / ${code} / ${classLabel} ${title}`);
  return save(page, '登记证据文档');
}

test.describe('C04/C05 文档密级分布只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('密级分布面板按最新文档聚合公开/内部/机密三档与覆盖率/主导密级, 追加登记使主导密级严格翻转', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `DCD-${suffix}`, name: `文档密级分布验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '证据版本');

    // 面板初始态: 尚无文档 -> 展示空态占位文案, 不出现"文档总数"标签与主导徽标.
    const distEmpty = panel(page, '文档密级分布');
    await expect(distEmpty).toBeVisible();
    await expect(distEmpty.getByText('暂无证据文档, 登记后此处按公开/内部/机密三档聚合密级分布.', { exact: true })).toBeVisible();
    await expect(distEmpty.getByText(/^文档总数 \d+$/)).toHaveCount(0);
    await expect(distEmpty.getByText(/^主导密级 /)).toHaveCount(0);

    // 阶段一 三条文档: 公开x2, 内部x1, 机密x0. total3 covered2 uncovered1 pct67, 主导公开(2)唯一最大.
    await createDocument(page, `DOC-DCD-PUB-A-${suffix}`, `公开设计说明甲 ${suffix}`, '公开');
    await createDocument(page, `DOC-DCD-PUB-B-${suffix}`, `公开接口清单乙 ${suffix}`, '公开');
    await createDocument(page, `DOC-DCD-INT-A-${suffix}`, `内部会议纪要丙 ${suffix}`, '内部');

    await open(page, id, '需求与治理', '证据版本');
    const dist1 = panel(page, '文档密级分布');
    await expect(dist1.getByText('文档总数 3', { exact: true })).toBeVisible();
    await expect(dist1.getByText('密级覆盖率 67%', { exact: true })).toBeVisible();
    await expect(dist1.getByText('已用档位 2', { exact: true })).toBeVisible();
    await expect(dist1.getByText('缺档档位 1', { exact: true })).toBeVisible();
    await expect(dist1.getByText('主导密级 公开 · 2', { exact: true })).toBeVisible();
    await expect(dist1.getByText('公开 · 2 (67%)', { exact: true })).toBeVisible();
    await expect(dist1.getByText('内部 · 1 (33%)', { exact: true })).toBeVisible();
    await expect(dist1.getByText('机密 · 0 (0%)', { exact: true })).toBeVisible();
    await panelShot(dist1, 'dcd-1-public-dominant.png');

    // 阶段二 追加两条"机密"文档 -> 公开2 与 机密2 并列, apply max-key 取固定序 [public internal confidential] 靠后者 => 主导翻转为 机密·2.
    await createDocument(page, `DOC-DCD-CON-A-${suffix}`, `机密核心算法丁 ${suffix}`, '机密');
    await createDocument(page, `DOC-DCD-CON-B-${suffix}`, `机密客户名单戊 ${suffix}`, '机密');

    await open(page, id, '需求与治理', '证据版本');
    const dist2 = panel(page, '文档密级分布');
    await expect(dist2.getByText('文档总数 5', { exact: true })).toBeVisible();
    await expect(dist2.getByText('密级覆盖率 100%', { exact: true })).toBeVisible();
    await expect(dist2.getByText('已用档位 3', { exact: true })).toBeVisible();
    // 三档齐备 -> 不出现"缺档档位"徽标.
    await expect(dist2.getByText(/^缺档档位 /)).toHaveCount(0);
    await expect(dist2.getByText('主导密级 机密 · 2', { exact: true })).toBeVisible();
    await expect(dist2.getByText('公开 · 2 (40%)', { exact: true })).toBeVisible();
    await expect(dist2.getByText('内部 · 1 (20%)', { exact: true })).toBeVisible();
    await expect(dist2.getByText('机密 · 2 (40%)', { exact: true })).toBeVisible();
    // 主导标签已翻转: 不再出现旧的"主导密级 公开"徽标.
    await expect(dist2.getByText('主导密级 公开 · 2', { exact: true })).toHaveCount(0);
    await panelShot(dist2, 'dcd-2-confidential-dominant.png');

    // 阶段三 再追加一条"机密"文档 -> 机密3 反超 公开2 => 主导 机密·3 (单操作者纯只读加性翻转, 机密确立唯一最大).
    await createDocument(page, `DOC-DCD-CON-C-${suffix}`, `机密收购预案己 ${suffix}`, '机密');

    await open(page, id, '需求与治理', '证据版本');
    const dist3 = panel(page, '文档密级分布');
    await expect(dist3.getByText('文档总数 6', { exact: true })).toBeVisible();
    await expect(dist3.getByText('密级覆盖率 100%', { exact: true })).toBeVisible();
    await expect(dist3.getByText('主导密级 机密 · 3', { exact: true })).toBeVisible();
    await expect(dist3.getByText('公开 · 2 (33%)', { exact: true })).toBeVisible();
    await expect(dist3.getByText('内部 · 1 (17%)', { exact: true })).toBeVisible();
    await expect(dist3.getByText('机密 · 3 (50%)', { exact: true })).toBeVisible();
    await panelShot(dist3, 'dcd-3-confidential-again.png');

    // 真实 HTTP 交叉核对: GET governance 回显 document_classification_distribution 与界面同源.
    const ws = await api(page, 'GET', `${base(id)}/governance`);
    const dist = ws.document_classification_distribution;
    expect(dist.available).toBe(true);
    expect(dist.total).toBe(6);
    expect(dist.covered).toBe(3);
    expect(dist.uncovered).toBe(0);
    expect(dist['coverage-pct']).toBe(100);
    expect(dist['dominant-classification']).toBe('confidential');
    expect(dist['dominant-count']).toBe(3);
    // 固定三档顺序 [public, internal, confidential], 各档 count 之和 = total, covered + uncovered = 3.
    const order = dist['by-classification'].map(x => x.classification);
    expect(order).toEqual(['public', 'internal', 'confidential']);
    expect(dist['by-classification'].reduce((s, x) => s + x.count, 0)).toBe(6);
    expect(dist['by-classification'][0].label).toBe('公开');
    expect(dist['by-classification'][1].label).toBe('内部');
    expect(dist['by-classification'][2].label).toBe('机密');

    // 只读稳定性: 再次 GET 分布不漂移, 六条文档仍为登记态(未被只读聚合写回).
    const ws2 = await api(page, 'GET', `${base(id)}/governance`);
    expect(ws2.document_classification_distribution).toEqual(dist);
    const statuses = ws.documents.map(r => r.status);
    expect(statuses.every(s => s !== 'discarded')).toBe(true);

    expect(errors, `未捕获 JS 错误: ${errors.join('; ')}`).toEqual([]);
  });
});
