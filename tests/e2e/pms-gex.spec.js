const { test, expect } = require('@playwright/test');
const path = require('node:path');

// Gate 检查项例外放行治理只读派生 (免迁移, 无新命令/新kind/新路由/不门控): 按逐检查项豁免 (:waived + :waiver_reason) 聚合关口必需检查项中"靠例外放行而非真实通过"的占比,
// 以及与关口整体签核闭环 (gate_closure 按实例状态计数) 正交的 "含豁免关口" vs "仅靠豁免才就绪关口" 区分.
// admin 建项目 + 合成独立审核人 (仅作 reviewer_id, 本增量不涉及签核裁决故无需登录第二上下文) -> 建两个各含 R1/R2 两项必需检查的模板与三个关口实例:
//   阶段一 G1(R1真实通过 + R2例外放行) 与 G2(R1,R2均真实通过): 面板 "关口总数 2 / 必需检查项 4 / 例外放行 25% (1/4 · 真实通过 3) / 含豁免关口 1 / 仅靠豁免才就绪 1", HTTP 回显一致;
//   阶段二 新增 G3(R1例外放行 + R2未满足, 未就绪): "必需检查项 6 / 例外放行 33% (2/6 · 真实通过 3) / 含豁免关口 2" 但 "仅靠豁免才就绪" 仍 1 (G3 有豁免却未就绪, 二者正交的关键演示);
//   阶段三 把 G3 R2 补为真实通过 -> G3 就绪且仍含豁免 -> "仅靠豁免才就绪" 翻 2, "真实通过 4". 全程真实点击+真实HTTP+真实浏览器截图.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/gex');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '关口检查项例外放行治理', exact: true }) });
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

async function command(page, id, suffix, data = {}) {
  const project = await api(page, 'GET', base(id));
  const result = await api(page, 'POST', base(id) + '/governance' + suffix, { ...data, version: project.version });
  return result.result;
}

async function tab(page, name) {
  await drawer(page).getByRole('tab', { name, exact: true }).click();
  await page.waitForLoadState('networkidle');
}

async function open(page, id) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  await tab(page, '需求与治理');
  await tab(page, 'Gate评审');
  await expect(panel(page)).toBeVisible();
}

async function shotPanel(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await panel(page).scrollIntoViewIfNeeded();
  await panel(page).screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 合成一名具备质量审批权限且加入项目的独立审核人 (仅用于关口 reviewer_id, 本增量不涉及签核裁决).
async function approverUser(page, suffix, deptId) {
  const menus = await api(page, 'GET', '/api/system/menu');
  const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
  const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `关口例外审核${suffix}`, role_key: `gex_${suffix}`, 'menu-ids': menuIds });
  const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `gex_${suffix}`).role_id;
  const name = `gex_rev_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: name, nick_name: '关口例外审核人', password,
    dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'Gate 例外放行治理 E2E 合成审核人' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === name).user_id;
  return { name, password, userId };
}

const pass = code => ({ code, passed: true, waived: false, evidence_ids: [] });
const waiv = (code, reason) => ({ code, passed: false, waived: true, waiver_reason: reason, evidence_ids: [] });
const none = code => ({ code, passed: false, waived: false, evidence_ids: [] });

test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

test.describe('Gate 检查项例外放行治理只读派生浏览器验收', () => {
  test.setTimeout(180000);

  test('必需检查项逐条豁免聚合 -> 含豁免关口与仅靠豁免才就绪关口正交区分', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `GEX-${suffix}`, name: `关口例外放行治理验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    const reviewer = await approverUser(page, suffix, deptId);
    await api(page, 'POST', base(id) + '/members', { user_id: reviewer.userId, role: 'editor' });

    // 建立两个各含 R1/R2 两项必需检查的模板 (R 复用同结构; 用不同 code 便于将来扩展, 但每个关口只挂自身模板).
    const tplA = await command(page, id, '/gate-templates', {
      code: `GEXA-${suffix}`, title: `例外放行模板A-${suffix}`, stage: 'execution', required: true,
      checks: [{ code: 'R1', title: '评审记录齐备', required: true }, { code: 'R2', title: '测试通过', required: true }] });
    const tplB = await command(page, id, '/gate-templates', {
      code: `GEXB-${suffix}`, title: `例外放行模板B-${suffix}`, stage: 'execution', required: true,
      checks: [{ code: 'R1', title: '评审记录齐备', required: true }, { code: 'R2', title: '测试通过', required: true }] });

    const g1Title = `关口G1-依赖豁免-${suffix}`;
    const g2Title = `关口G2-真实通过-${suffix}`;
    const g1 = await command(page, id, '/gates', { template_id: tplA.id, title: g1Title, reviewer_id: reviewer.userId });
    const g2 = await command(page, id, '/gates', { template_id: tplA.id, title: g2Title, reviewer_id: reviewer.userId });

    // 阶段一: G1 R1 真实通过 + R2 例外放行; G2 两项均真实通过.
    await command(page, id, `/gates/${g1.id}/checks`, { checks: [pass('R1'), waiv('R2', '客户现场已具备等价条件, 经评审豁免')] });
    await command(page, id, `/gates/${g2.id}/checks`, { checks: [pass('R1'), pass('R2')] });

    await open(page, id);
    await expect(panel(page).getByText('关口总数 2', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('必需检查项 4', { exact: true })).toBeVisible();
    await expect(panel(page).getByText(/例外放行 25%/)).toBeVisible();
    await expect(panel(page).getByText('含豁免关口 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('仅靠豁免才就绪 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('缺失豁免理由 1', { exact: true })).toHaveCount(0);
    await shotPanel(page, 'gex-1-exception-25.png');

    let sum = (await api(page, 'GET', base(id) + '/governance')).gate_exception_summary;
    expect(sum.total, '关口实例数').toBe(2);
    expect(sum['required-checks'], '必需检查项总数 2x2').toBe(4);
    expect(sum['passed-checks'], '真实通过的必需项 G1:R1 + G2:R1 + G2:R2').toBe(3);
    expect(sum['exception-checks'], '例外放行的必需项 G1:R2').toBe(1);
    expect(sum['gates-with-exception'], '含豁免的关口 G1').toBe(1);
    expect(sum['gates-exception-dependent'], '仅靠豁免才就绪的关口 G1').toBe(1);
    expect(sum['reason-missing'], '写路径强制说明故缺失理由为 0').toBe(0);
    expect(sum['waiver-pct'], 'round(100*1/4)=25').toBe(25);

    // 阶段二: 新增 G3 R1 例外放行 + R2 未满足 -> G3 有豁免但尚未就绪.
    const g3Title = `关口G3-有豁免但未就绪-${suffix}`;
    const g3 = await command(page, id, '/gates', { template_id: tplB.id, title: g3Title, reviewer_id: reviewer.userId });
    await command(page, id, `/gates/${g3.id}/checks`, { checks: [waiv('R1', '该必需项以自动化门禁替代, 例外放行'), none('R2')] });

    await open(page, id);
    await expect(panel(page).getByText('关口总数 3', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('必需检查项 6', { exact: true })).toBeVisible();
    await expect(panel(page).getByText(/例外放行 33%/)).toBeVisible();
    await expect(panel(page).getByText('含豁免关口 2', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('仅靠豁免才就绪 1', { exact: true })).toBeVisible();
    await shotPanel(page, 'gex-2-orthogonal.png');

    sum = (await api(page, 'GET', base(id) + '/governance')).gate_exception_summary;
    expect(sum.total).toBe(3);
    expect(sum['required-checks'], '2+2+2').toBe(6);
    expect(sum['passed-checks'], 'G3 未新增真实通过项').toBe(3);
    expect(sum['exception-checks'], 'G1:R2 + G3:R1').toBe(2);
    expect(sum['gates-with-exception'], 'G1 与 G3 均含豁免').toBe(2);
    expect(sum['gates-exception-dependent'], 'G3 有豁免但未就绪故仅 G1 依赖豁免, 与含豁免关口正交').toBe(1);
    expect(sum['waiver-pct'], 'round(100*2/6)=33').toBe(33);

    // 阶段三: 把 G3 R2 补为真实通过 -> G3 就绪且仍含豁免 -> 仅靠豁免才就绪翻为 2.
    await command(page, id, `/gates/${g3.id}/checks`, { checks: [waiv('R1', '该必需项以自动化门禁替代, 例外放行'), pass('R2')] });

    await open(page, id);
    await expect(panel(page).getByText('仅靠豁免才就绪 2', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('含豁免关口 2', { exact: true })).toBeVisible();
    await expect(panel(page).getByText(/例外放行 33%/)).toBeVisible();
    await shotPanel(page, 'gex-3-dependent-2.png');

    sum = (await api(page, 'GET', base(id) + '/governance')).gate_exception_summary;
    expect(sum['passed-checks'], 'G3:R2 转真实通过').toBe(4);
    expect(sum['exception-checks'], '豁免项数不变仍 2').toBe(2);
    expect(sum['gates-with-exception'], '含豁免关口仍 2').toBe(2);
    expect(sum['gates-exception-dependent'], 'G3 现已就绪且依赖豁免 -> 翻为 2').toBe(2);
    expect(sum['waiver-pct'], 'round(100*2/6)=33 不变').toBe(33);
    // 关口逐条状态不因该只读汇总而漂移.
    const gov = (await api(page, 'GET', base(id) + '/governance')).gates;
    expect(gov.find(g => g.id === g3.id).status, 'G3 仍处检查就绪态, 未被签核裁决').toBe('ready');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
