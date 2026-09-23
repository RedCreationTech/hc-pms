const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H09 变更量化影响: 界面在"提出项目变更"里可选填写工期影响(天)与成本影响金额 ->
// 台账"量化影响"列回显"工期 +N 天"/"成本 +X"标签, 达阈值(工期>=10天 或 成本>=100000)追加红色"高影响"徽标;
// 未量化行显示"未量化"; 高影响判定为读时派生(不落存储); 非法量化值经真实HTTP 400; 量化字段为变更专属, 章程体拒绝.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h09');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const changeForm = page => modal(page, '提出项目变更');

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

// 打开"提出项目变更"并填写五维必填影响; 若给定 days/cost 则再填可选量化字段; 停在保存前以便截图.
async function fillChange(page, { title, days, cost }) {
  await drawer(page).getByRole('button', { name: '提出项目变更', exact: true }).click();
  const form = changeForm(page);
  await form.locator('#title').fill(title);
  await form.locator('#reason').fill('合同范围调整');
  await form.locator('#scope_impact').fill('新增两台设备');
  await form.locator('#schedule_impact').fill('工期相应延长');
  await form.locator('#cost_impact').fill('需要重新估价');
  await form.locator('#quality_impact').fill('追加联调测试');
  await form.locator('#resource_impact').fill('追加一名工程师');
  if (days != null) await form.locator('#schedule_impact_days').fill(String(days));
  if (cost != null) await form.locator('#cost_impact_amount').fill(String(cost));
}

test.describe('H09 变更量化影响与高影响徽标浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('界面量化影响入台账列并派生高影响徽标, 未量化显示占位, 非法值与跨类字段经真实HTTP被拒', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H09-${suffix}`, name: `变更量化影响验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '变更控制');

    // 1) 界面登记高影响变更: 填工期12天 + 成本150000.5 (两者任一达阈值即为高影响), 保存前截图证明量化字段可选.
    const hiTitle = `高影响变更-${suffix}`;
    await fillChange(page, { title: hiTitle, days: 12, cost: '150000.5' });
    await shot(page, 'h09-1-dialog-quantify.png');
    const hi = await save(page, '提出项目变更');
    expect(hi.result.schedule_impact_days, '命令回显量化工期').toBe(12);
    expect(hi.result.cost_impact_amount, '成本规范化为两位小数').toBe('150000.50');
    const hiId = hi.result.id;

    // 2) 界面登记未量化变更: 只填五维文本, 不含量化字段.
    const plainTitle = `未量化变更-${suffix}`;
    await fillChange(page, { title: plainTitle });
    const plain = await save(page, '提出项目变更');
    expect(plain.result.schedule_impact_days == null, '未填工期则不含该键').toBeTruthy();
    expect(plain.result.cost_impact_amount == null, '未填成本则不含该键').toBeTruthy();
    const plainId = plain.result.id;

    // 3) 读模型派生高影响: 高影响行为 true, 未量化行为 false (读时计算, 不落存储).
    const ws = await api(page, 'GET', base(id) + '/governance');
    const hiRow = ws.changes.find(c => c.id === hiId);
    const plainRow = ws.changes.find(c => c.id === plainId);
    expect(hiRow.change_high_impact, '达阈值派生为高影响').toBe(true);
    expect(plainRow.change_high_impact, '未量化非高影响').toBe(false);
    expect(hiRow.schedule_impact_days, '版本冻结存量化工期').toBe(12);

    // 4) 低于阈值不为高影响 (经真实HTTP创建: 工期3天且成本99999.99 均不达标).
    const lowVersion = (await api(page, 'GET', base(id))).version;
    const low = await api(page, 'POST', `${base(id)}/governance/changes`, {
      title: `低影响变更-${suffix}`, reason: '微调', scope_impact: '小', schedule_impact: '三日', cost_impact: '小额',
      quality_impact: 'q', resource_impact: 'r', schedule_impact_days: 3, cost_impact_amount: '99999.99', version: lowVersion });
    const lowId = low.result.id;
    const ws2 = await api(page, 'GET', base(id) + '/governance');
    expect(ws2.changes.find(c => c.id === lowId).change_high_impact, '均低于阈值不为高影响').toBe(false);

    // 5) 台账界面 (重进拉最新读模型): "量化影响"列回显工期/成本标签与红色"高影响"徽标, 未量化行显示"未量化".
    //    限定到表格单元格, 避免与隐藏的项目概况描述项串台.
    await open(page, id, '需求与治理', '变更控制');
    await expect(drawer(page).locator('.ant-table-cell', { hasText: `工期 +12 天` }).first()).toBeVisible();
    await expect(drawer(page).locator('.ant-table-cell', { hasText: `成本 +150000.50` }).first()).toBeVisible();
    await expect(drawer(page).locator('.ant-table-cell', { hasText: '高影响' }).first()).toBeVisible();
    await expect(drawer(page).locator('.ant-table-cell', { hasText: '未量化' }).first()).toBeVisible();
    await shot(page, 'h09-2-ledger-column.png');

    // 6) 真实HTTP拒绝非法量化值: 非整数天 / 负成本 / 超两位小数成本.
    const v = (await api(page, 'GET', base(id))).version;
    const bad = async extra => (await page.evaluate(async ({ id, v, suffix, extra }) => {
      const token = localStorage.getItem('ruoyi_token');
      const r = await fetch(`/api/pms/projects/${id}/governance/changes`, { method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: `BAD-${suffix}`, reason: 'r', scope_impact: 's', schedule_impact: 's', cost_impact: 'c',
          quality_impact: 'q', resource_impact: 'r', version: v, ...extra }) });
      return (await r.json()).code;
    }, { id, v, suffix, extra }));
    expect(await bad({ schedule_impact_days: 'abc' }), '非整数天应被拒').toBe(400);
    expect(await bad({ schedule_impact_days: 4000 }), '超范围天应被拒').toBe(400);
    expect(await bad({ cost_impact_amount: '-5' }), '负成本应被拒').toBe(400);
    expect(await bad({ cost_impact_amount: '1.234' }), '超两位小数成本应被拒').toBe(400);

    // 7) 量化影响是变更专属字段: 追加到章程体被白名单拒绝 (400).
    const badCharter = await page.evaluate(async ({ id, suffix }) => {
      const token = localStorage.getItem('ruoyi_token');
      const version = (await (await fetch(`/api/pms/projects/${id}`, { headers: { Authorization: `Bearer ${token}` } })).json()).data.version;
      const r = await fetch(`/api/pms/projects/${id}/governance/charters`, { method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: `CHA-${suffix}`, objective: 'o', scope: 's', success_criteria: 'c', sponsor_id: 1, schedule_impact_days: 12, version }) });
      return (await r.json()).code;
    }, { id, suffix });
    expect(badCharter, '量化影响为变更专属字段, 章程体拒绝').toBe(400);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
