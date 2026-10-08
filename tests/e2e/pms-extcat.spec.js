const { test, expect } = require('@playwright/test');
const path = require('node:path');

// 三段指令第 (3) 项: "依赖外部的接口单独出一个页面, 供配置用".
// 这是一个只读静态清单页 (免迁移数据源, 免门控, 不查业务库, 不落库, 不做连通性测试):
//   后端 com.ruoyi.domain.pms.external-catalog/catalog 返回随代码发布的 22 条能力 (9 系统适配器 + 13 业务对接点,
//   20 待合同 + 2 待规则), 逐项给出外部系统 / 归类 / 方向 / 来源矩阵行 / 所需接口字段 / 责任方 / 依赖状态 / 说明.
//   路由 GET /api/pms/external-interfaces (挂载在已认证 /api/pms 内, 仅登录即可读, 未登录 401).
//   菜单 C 型叶子 (sys_menu 5045, parent 5000 path='pms' + child path='external-interfaces' => key 'pms/external-interfaces'),
//   前端独立页 com.ruoyi.frontend.pages.pms.external-interfaces/external-interfaces-page, 路由 :pms-external-interfaces.
// 本用例单上下文 (admin) 验证: 真实浏览器里左侧菜单出现"外部接口配置"入口 -> 点击进入独立页 ->
//   顶部指标卡 (总数/适配器/业务/待合同/待规则) 与清单表 (能力/系统/归类/方向/矩阵行/所需字段/责任方/状态/说明) 真实渲染,
//   并用 page.request 真实 HTTP 交叉核对前端展示与服务端 catalog 同源 (免鉴权 401 + 鉴权 200 + 计数不变量).
// 依 Rule B: 该页为纯只读清单, 无任何需第二审批人的门控翻转, 全部为单上下文可见事实, 可进 B 列.
const output = path.resolve(__dirname, '../../reports/extcat');
const catalog = () => '/api/pms/external-interfaces';

test.use({ viewport: { width: 1680, height: 1050 }, video: 'on' });

async function login(page, user = 'admin', password = 'admin123') {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill(user);
  await page.getByPlaceholder('密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ruoyi_token'))).toBeTruthy();
}

async function api(page, method, url, expected = 200) {
  const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
  const response = await page.request.fetch(url, { method, headers: { Authorization: `Bearer ${token}` } });
  const body = await response.json();
  expect(body.code, `${method} ${url}: ${body.msg}`).toBe(expected);
  return body.data;
}

test.describe('外部接口配置只读目录页浏览器验收', () => {
  test.setTimeout(120000);

  test('左侧菜单进入只读外部接口目录页, 指标卡与清单表真实渲染, 与服务端 catalog 同源', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    // 真实 HTTP 基线: 未登录 401 (路由挂载在已认证 /api/pms 下).
    const unauthorized = await page.request.fetch(catalog());
    expect(unauthorized.status(), '未登录应被拒绝').toBe(401);

    // 登录态拉取目录, 作为界面比对的同源基准.
    const data = await api(page, 'GET', catalog());
    expect(data.available).toBe(true);
    expect(data.total).toBe(22);
    expect(data['adapter-count']).toBe(9);
    expect(data['business-count']).toBe(13);
    expect(data['contract-count']).toBe(20);
    expect(data['rule-count']).toBe(2);
    // 分组不变量: adapter + business = total; 状态不变量: 待合同 + 待规则 = total.
    expect(data['adapter-count'] + data['business-count']).toBe(data.total);
    expect(data['contract-count'] + data['rule-count']).toBe(data.total);
    expect(data.rows).toHaveLength(22);
    // 每行 10 个必备字段齐全 (所需接口字段为非空数组).
    for (const row of data.rows) {
      for (const k of ['key', 'capability', 'system', 'group', 'direction', 'matrix-rows', 'required-fields', 'owner', 'status', 'notes']) {
        expect(row, `行 ${row && row.key} 缺字段 ${k}`).toHaveProperty(k);
      }
      expect(Array.isArray(row['required-fields']) && row['required-fields'].length > 0, `行 ${row.key} 所需字段应为非空`).toBe(true);
    }

    // 界面路径 1: 左侧动态权限菜单进入"外部接口配置". 先去同级"项目台账"(强制激活并展开"项目管理"子菜单),
    // 再点新叶子, 证明迁移菜单种子 + 路由 + page->menu-key 全链路打通.
    await page.goto('/pms/project');
    await expect(page.getByRole('menuitem', { name: '项目管理' })).toBeVisible();
    await page.waitForLoadState('networkidle');
    const leaf = page.getByRole('menuitem', { name: /外部接口配置/ });
    await expect(leaf).toBeVisible();
    await leaf.click();
    await expect(page).toHaveURL(/\/pms\/external-interfaces/);
    await page.waitForLoadState('networkidle');

    // 页面标题与只读提示真实渲染.
    await expect(page.getByRole('heading', { name: '外部接口配置', exact: true })).toBeVisible();
    await expect(page.getByText('逐项登记仍依赖真实外部系统合同/规则的能力')).toBeVisible();

    // 指标卡真实渲染, 数值与服务端一致.
    const metric = (label, value) => page.locator('div', { has: page.getByText(label, { exact: true }) }).filter({ has: page.getByText(String(value), { exact: true }) });
    await expect(page.getByText('能力总数', { exact: true })).toBeVisible();
    await expect(page.getByText('系统适配器', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('业务对接点', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('待合同', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('待规则', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('22', { exact: true }).first()).toBeVisible();

    // 清单表头 9 列真实渲染.
    const table = page.locator('section').filter({ has: page.getByRole('heading', { name: '依赖外部系统的能力清单', exact: true }) });
    await expect(table).toBeVisible();
    for (const col of ['能力', '外部系统', '归类', '方向', '矩阵行', '所需接口字段', '责任方', '依赖状态', '说明']) {
      await expect(table.getByRole('columnheader', { name: col, exact: true })).toBeVisible();
    }

    // 22 行数据真实呈现 (以首行 CRM 适配器与末行 ERP 预算科目映射为锚点).
    await expect(table.getByRole('row').filter({ hasText: 'CRM 适配器' })).toBeVisible();
    await expect(table.getByRole('row').filter({ hasText: '企业预算科目与 ERP 映射' })).toBeVisible();
    await expect(table.getByRole('row').filter({ hasText: '待规则' })).not.toHaveCount(0);

    // 截图 1: 指标卡区 (顶部概览).
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: path.join(output, 'extcat-1-top-metrics.png') });

    // 截图 2: 清单表首屏 (能力/系统/归类/方向/所需字段可见).
    await table.scrollIntoViewIfNeeded();
    await table.screenshot({ path: path.join(output, 'extcat-2-catalog-table.png') });

    // 截图 3: 依赖状态列聚焦 (待合同/待规则徽标) — 定位含"待规则"徽标的行并元素级截图.
    const ruleRow = table.getByRole('row').filter({ hasText: '企业预算科目与 ERP 映射' }).first();
    await ruleRow.scrollIntoViewIfNeeded();
    await ruleRow.screenshot({ path: path.join(output, 'extcat-3-status-badge.png') });

    // 只读稳定性: 再次 GET 目录内容不漂移 (静态清单, 无写回).
    const data2 = await api(page, 'GET', catalog());
    expect(data2).toEqual(data);

    expect(errors, `未捕获 JS 错误: ${errors.join('; ')}`).toEqual([]);
  });
});
