const { test, expect } = require('@playwright/test');
const path = require('node:path');

// A 节: 模板配置 (A09), 编码规则 (A10), 项目类别 (A11) 与项目网络实例化 (A07/B03) 的真实浏览器验收.
// 流程: 模板与规则页导入内置模板并发布 -> 导入编码规则并发布, 预览下一编号 ->
// 项目中心按规则生成编号建项目 -> 项目详情应用模板 -> 结构节点/Gate模板/计划容器/进度卷积/收尾清单可见 ->
// 研发类项目应用研发模板得到不同阶段; 重复应用经真实 HTTP 被 409 拒绝.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/a-templates');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
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

async function tab(page, name) {
  await drawer(page).getByRole('tab', { name, exact: true }).click();
  await page.waitForLoadState('networkidle');
}

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await input.press('Escape');
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

async function publishLatest(page, kind, code) {
  const rows = (await api(page, 'GET', `/api/pms/config/${kind}`)).rows.filter(r => r.code === code);
  const draft = rows.find(r => r.status === 'draft');
  if (draft) await api(page, 'POST', `/api/pms/config/${kind}/${draft.id}/publish`, { reason: 'E2E 发布' });
  return (await api(page, 'GET', `/api/pms/config/${kind}`)).rows.find(r => r.code === code && r.status === 'published');
}

test.describe('A 节 模板/编码规则/项目类别/项目网络实例化', () => {
  test.setTimeout(240000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('模板页导入发布, 编码规则生成编号, 项目应用模板生成结构/Gate/计划容器并卷积进度, 研发类模板阶段不同, 重复应用409', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const suffix = serial();

    // 1. 模板与规则页: 内置目录可见, 导入单机设备模板为草稿并在界面发布.
    await page.goto('/pms/config');
    await expect(page.getByRole('heading', { name: '模板与规则' })).toBeVisible();
    await expect(page.getByText('单机设备订单项目', { exact: true }).first()).toBeVisible();
    const existing = (await api(page, 'GET', '/api/pms/config/project-template')).rows.filter(r => r.code === 'TPL-EQUIPMENT');
    if (existing.length === 0) {
      await page.locator('div').filter({ hasText: /^单机设备订单项目/ }).getByRole('button', { name: '导入为草稿' }).first().click();
      await save(page, '导入模板 TPL-EQUIPMENT');
      const row = page.locator('tbody tr').filter({ hasText: 'TPL-EQUIPMENT' }).first();
      await expect(row.getByText('草稿')).toBeVisible();
      await row.getByRole('button', { name: '发布' }).click();
      await modal(page, '发布版本').locator('#reason').fill('E2E 首版发布');
      await save(page, '发布版本');
    }
    const equipmentTemplate = await publishLatest(page, 'project-template', 'TPL-EQUIPMENT');
    expect(equipmentTemplate.status).toBe('published');
    await page.reload();
    await expect(page.locator('tbody tr').filter({ hasText: 'TPL-EQUIPMENT' }).first().getByText('已发布')).toBeVisible();
    await shot(page, 'a-1-template-published.png');

    // 2. 编码规则: 界面新建强制规则并发布, 预览下一编号.
    await page.getByRole('tab', { name: '编码与版本规则' }).click();
    const ruleCode = `RULE-E2E-${suffix}`;
    await page.getByRole('button', { name: '新建编码规则' }).click();
    const ruleForm = modal(page, '新建编码规则');
    await ruleForm.locator('#code').fill(ruleCode);
    await ruleForm.locator('#pattern').fill('HCE-{YYYY}-{SEQ:4}');
    await choose(page, ruleForm, 'enforced', '强制');
    await ruleForm.locator('#description').fill('E2E 强制项目编号规则');
    const rule = await save(page, '新建编码规则');
    expect(rule.enforced).toBe(true);
    // 让规则生效前先退役其它可能生效的项目规则, 保证唯一口径.
    for (const r of (await api(page, 'GET', '/api/pms/config/coding-rule')).rows) {
      if (r.object_type === 'project' && r.status === 'published') await api(page, 'POST', `/api/pms/config/coding-rule/${r.id}/retire`, { reason: 'E2E 让位' });
    }
    await page.locator('tbody tr').filter({ hasText: ruleCode }).first().getByRole('button', { name: '发布' }).click();
    await save(page, '发布版本');
    await page.getByRole('button', { name: '预览下一编号' }).click();
    const year = new Date().getFullYear();
    await expect(page.getByText(new RegExp(`建议编号 HCE-${year}-\\d{4} \\(强制\\)`))).toBeVisible();
    await shot(page, 'a-2-coding-rule-next-code.png');

    // 3. 项目中心: 按规则生成编号建立设备项目, 不合规编号经真实 HTTP 400.
    await api(page, 'POST', '/api/pms/projects', { project_no: `FREE-${suffix}`, name: '不合规编号', project_type: 'equipment', manager_id: 1, dept_id: 4 }, 400);
    await page.goto('/pms/project');
    await page.getByRole('button', { name: /创建项目|新建项目/ }).first().click();
    const projectDrawer = page.getByRole('dialog').filter({ hasText: '创建项目' });
    await projectDrawer.getByRole('button', { name: '按规则生成编号' }).click();
    await expect(projectDrawer.locator('#project_no')).toHaveValue(new RegExp(`HCE-${year}-\\d{4}`));
    const generated = await projectDrawer.locator('#project_no').inputValue();
    await projectDrawer.locator('#name').fill(`模板实例化 ${suffix}`);
    await choose(page, projectDrawer, 'dept_id', '研发部门');
    await projectDrawer.locator('#start_date').fill('2026-10-01');
    await projectDrawer.locator('#end_date').fill('2027-03-31');
    const created = page.waitForResponse(r => r.url().endsWith('/api/pms/projects') && r.request().method() === 'POST');
    await projectDrawer.getByRole('button', { name: '保存项目' }).click();
    const project = (await (await created).json()).data;
    expect(project.project_no).toBe(generated);
    const id = project.project_id;

    // 4. 项目详情: 应用模板 -> 概况显示模板快照, 结构出现子项目/单机.
    await page.goto(`/pms/project?id=${id}`);
    await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
    await drawer(page).getByRole('button', { name: '应用项目模板' }).click();
    const applyForm = modal(page, '应用项目模板');
    await choose(page, applyForm, 'template_id', '单机设备订单项目');
    await applyForm.locator('#reason').fill('E2E 按设备模板建网');
    const instance = (await save(page, '应用项目模板')).result;
    expect(instance.node_count).toBe(3);
    expect(instance.gate_template_count).toBe(8);
    expect(instance.task_count).toBe(11);
    await expect(drawer(page).getByText(/项目模板 单机设备订单项目 · TPL-EQUIPMENT v\d+/)).toBeVisible();
    await expect(drawer(page).getByText('结构节点 3')).toBeVisible();
    await expect(drawer(page).getByRole('button', { name: '应用项目模板' })).toHaveCount(0);
    await expect(drawer(page).getByText(`${generated}-U1`, { exact: true }).first()).toBeVisible();
    await shot(page, 'a-3-template-applied-overview.png');

    // 5. 计划: 任务表带节点/阶段列, 进度卷积展示 8 个阶段与 3 个结构节点.
    await tab(page, '计划与执行');
    await expect(drawer(page).getByRole('cell', { name: '设计准备', exact: true })).toBeVisible();
    await tab(page, '进度卷积');
    await expect(drawer(page).getByText('权重来自已实例化项目模板的阶段定义')).toBeVisible();
    await expect(drawer(page).getByText('总体进度 0%')).toBeVisible();
    await expect(drawer(page).getByRole('cell', { name: '主机#1', exact: true })).toBeVisible();
    await shot(page, 'a-4-progress-rollup.png');

    // 6. 治理 Gate: 8 个类型化模板与进展汇总, 齐套Gate标注阻断装配开工.
    await tab(page, '需求与治理');
    await tab(page, 'Gate评审');
    await expect(drawer(page).getByText('零件齐套Gate (G4)').first()).toBeVisible();
    await expect(drawer(page).getByText('阻断 装配开工').first()).toBeVisible();
    await shot(page, 'a-5-gate-catalog-progress.png');
    const ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.template_instances).toHaveLength(1);
    expect(ws.gate_progress).toHaveLength(8);
    expect(ws.gate_templates.find(t => t.gate_type === 'kitting').blocks).toEqual(['assembly.start']);
    const closure = await api(page, 'GET', base(id) + '/closure');
    expect(closure.checks.map(c => c.title)).toContain('四算决算已批准');
    const delivery = await api(page, 'GET', base(id) + '/delivery');
    expect(delivery.configuration.source).toBe('project_template');
    // 重复应用 -> 409.
    const current = await api(page, 'GET', base(id));
    await api(page, 'POST', base(id) + '/governance/template-instances', { template_id: equipmentTemplate.id, version: current.version }, 409);

    // 7. A11 研发类项目: 类别下拉新增研发/部门事务, 应用研发模板得到 5 个研发阶段且无发运环节.
    await page.goto('/pms/config');
    const rdExisting = (await api(page, 'GET', '/api/pms/config/project-template')).rows.filter(r => r.code === 'TPL-NEW-PRODUCT');
    if (rdExisting.length === 0) await api(page, 'POST', '/api/pms/config/project-template/import', { code: 'TPL-NEW-PRODUCT' });
    const rdTemplate = await publishLatest(page, 'project-template', 'TPL-NEW-PRODUCT');
    for (const r of (await api(page, 'GET', '/api/pms/config/coding-rule')).rows) {
      if (r.code === ruleCode && r.status === 'published') await api(page, 'POST', `/api/pms/config/coding-rule/${r.id}/retire`, { reason: 'E2E 结束强制' });
    }
    await page.goto('/pms/project');
    await page.getByRole('button', { name: /创建项目|新建项目/ }).first().click();
    const rdDrawer = page.getByRole('dialog').filter({ hasText: '创建项目' });
    await rdDrawer.locator('#project_no').fill(`RD-${suffix}`);
    await rdDrawer.locator('#name').fill(`新产品研发 ${suffix}`);
    await choose(page, rdDrawer, 'dept_id', '研发部门');
    await choose(page, rdDrawer, 'project_type', '新产品研发');
    const rdCreated = page.waitForResponse(r => r.url().endsWith('/api/pms/projects') && r.request().method() === 'POST');
    await rdDrawer.getByRole('button', { name: '保存项目' }).click();
    const rd = (await (await rdCreated).json()).data;
    expect(rd.project_type).toBe('new_product');
    const rdVersion = (await api(page, 'GET', base(rd.project_id))).version;
    // 设备模板对研发项目不适用 -> 409; 研发模板适用.
    await api(page, 'POST', base(rd.project_id) + '/governance/template-instances', { template_id: equipmentTemplate.id, version: rdVersion }, 409);
    await page.goto(`/pms/project?id=${rd.project_id}`);
    await drawer(page).getByRole('button', { name: '应用项目模板' }).click();
    await choose(page, modal(page, '应用项目模板'), 'template_id', '新产品研发项目');
    const rdInstance = (await save(page, '应用项目模板')).result;
    expect(rdInstance.stages.map(s => s.name)).toEqual(['立项论证', '方案设计', '样机试制', '测试验证', '总结归档']);
    await expect(drawer(page).getByText('项目类别')).toBeVisible();
    await expect(drawer(page).getByText('新产品研发', { exact: true }).first()).toBeVisible();
    await tab(page, '计划与执行');
    await tab(page, '进度卷积');
    await expect(drawer(page).getByRole('cell', { name: '样机试制', exact: true })).toBeVisible();
    await shot(page, 'a-6-rd-project-stages.png');
    expect((await api(page, 'GET', base(rd.project_id) + '/delivery')).configuration.required_stages).toEqual(['materials', 'assembly', 'quality']);
    expect(rdTemplate.status).toBe('published');
    expect(errors).toEqual([]);
  });
});
