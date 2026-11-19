const { test, expect } = require('playwright/test');
const path = require('node:path');

// D04 层流罩直发 / D05 非层流罩直发: 直发申请由真实任务发起, 非层流罩须逐项物料编码关联
// 采购订单, 提交须指定独立审核人, 批准后外部采购与物流状态如实标注 not_configured.
// 职责分离与冲突阻断的细粒度断言由 pms_direct_ship_test 覆盖.
const output = path.resolve(__dirname, '../../reports/d04d05directship');

async function login(page) {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ruoyi_token'))).toBeTruthy();
}

async function api(page, url, options = {}) {
  const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
  const response = await page.request.fetch(url, {
    method: options.method || 'GET',
    headers: { Authorization: `Bearer ${token}` },
    data: options.data,
    timeout: 60000
  });
  const body = await response.json();
  expect(body.code, `${options.method || 'GET'} ${url}: ${body.msg}`).toBe(200);
  return body.data && body.data.result ? { ...body.data.result, project_version: body.data.project_version } : body.data;
}

async function raw(page, url, options = {}) {
  const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
  const response = await page.request.fetch(url, {
    method: options.method || 'GET',
    headers: { Authorization: `Bearer ${token}` },
    data: options.data,
    timeout: 60000
  });
  return { status: response.status(), body: await response.json() };
}

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => document.querySelectorAll('.ant-message').forEach(node => node.remove()));
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function ensureReviewer(page, username, password) {
  const users = await api(page, `/api/system/user?page=1&size=50&user_name=${username}`);
  const found = (users.rows || []).find(user => user.user_name === username);
  if (found) return found.user_id;
  const menus = await api(page, '/api/system/menu/treeselect');
  const menuIds = [];
  const walk = nodes => (nodes || []).forEach(node => { menuIds.push(node.menu_id); walk(node.children); });
  walk(menus);
  const roleKey = `d04-review-${Date.now()}`;
  await api(page, '/api/system/role', {
    method: 'POST',
    data: { role_name: `D04审批${Date.now()}`, role_key: roleKey, role_sort: 50, status: '0', menu_ids: menuIds, menu_check_strictly: true }
  });
  const roles = await api(page, `/api/system/role?page=1&size=50&role_key=${roleKey}`);
  const roleRow = (Array.isArray(roles) ? roles : (roles.rows || [])).find(item => item.role_key === roleKey);
  expect(roleRow, '审批角色应已创建').toBeTruthy();
  await api(page, '/api/system/user', {
    method: 'POST',
    data: { user_name: username, nick_name: username, password, status: '0', dept_id: 103, roles: [roleRow.role_id] }
  });
  const created = await api(page, `/api/system/user?page=1&size=50&user_name=${username}`);
  const row = (created.rows || []).find(user => user.user_name === username);
  expect(row, '审批人用户应已创建').toBeTruthy();
  return row.user_id;
}

async function reviewerToken(request, username, password) {
  const response = await request.post('/api/auth/login', { data: { username, password } });
  const body = await response.json();
  expect(body.code, `审批人登录: ${body.msg}`).toBe(200);
  return body.data.token;
}

test.describe('D04/D05 直发申请', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('层流罩随任务发起 -> 非层流罩缺订单阻断 -> 补齐订单 -> 独立审批通过', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const stamp = Date.now();
    const project = await api(page, '/api/pms/projects', {
      method: 'POST',
      data: {
        project_no: `E2E-D04-${stamp}`,
        name: '直发申请验证项目',
        customer: '本地客户',
        contract_no: `E2E-D04-${stamp}`,
        project_type: 'equipment',
        manager_id: 1,
        dept_id: 1,
        start_date: '2026-09-01',
        end_date: '2026-12-31'
      }
    });
    const id = project.project_id;
    const nodes = await api(page, `/api/pms/projects/${id}/nodes`);
    const root = (nodes.rows || []).find(node => node.parent_id == null) || (nodes.rows || [])[0];

    const reviewerName = `d04review${stamp}`;
    const reviewerPassword = 'Review123';
    const reviewerId = await ensureReviewer(page, reviewerName, reviewerPassword);
    expect(reviewerId).not.toBe(1);
    await api(page, `/api/pms/projects/${id}/members`, { method: 'POST', data: { user_id: reviewerId, role: 'viewer' } });

    // 真实任务来源: 直发申请必须由项目内任务发起.
    const workspace0 = await api(page, `/api/pms/projects/${id}/planning`);
    const task = await api(page, `/api/pms/projects/${id}/tasks`, {
      method: 'POST',
      data: {
        wbs_code: 'DS-1', name: '层流罩随货直发任务', owner_id: 1,
        start_date: '2026-09-22', duration_days: 1, node_id: root.node_id,
        version: workspace0.project_version
      }
    });
    const taskId = task.task_id || (task.result && task.result.task_id);
    expect(taskId, '任务应已创建').toBeTruthy();

    // 界面: 直发申请页签与只读汇总真实可见, 外部系统标注未配置.
    await page.goto(`/pms/project?id=${id}`);
    await expect(page.getByText('直发申请验证项目').first()).toBeVisible();
    await page.getByRole('tab', { name: '工程交付', exact: true }).click();
    await page.getByRole('tab', { name: '直发申请', exact: true }).click();
    await expect(page.getByText('直发申请汇总')).toBeVisible();
    await expect(page.getByText('外部同步 not_configured')).toBeVisible();
    await expect(page.getByRole('button', { name: '新建直发申请' })).toBeEnabled();
    await shot(page, 'd04-empty.png');

    const items = [{ code: 'NP-1', name: '箱体', quantity: 2, unit: '台' }, { code: 'NP-2', name: '风机', quantity: 4, unit: '台' }];
    const ship = await api(page, `/api/pms/projects/${id}/delivery`);
    const deliveryVersion = ship.version || ship.project_version;

    // 直发申请须追溯真实 URS 需求版本与审批证据 (文档版本).
    const document = await api(page, `/api/pms/projects/${id}/governance/documents`, {
      method: 'POST',
      data: { code: 'D04-EV', title: '直发证据', filename: 'd04.txt', content: '直发记录', version: deliveryVersion }
    });
    const requirement = await api(page, `/api/pms/projects/${id}/governance/requirements`, {
      method: 'POST',
      data: { code: 'D04-URS', text: '直发物料须可追溯', category: '交付', priority: 'required', owner_id: 1, version: document.project_version }
    });
    const requirementIds = [requirement.id];

    // 层流罩直发 (D04): 无需采购订单, 节点与任务归属由服务端解析.
    const laminar = await api(page, `/api/pms/projects/${id}/delivery/direct-ships`, {
      method: 'POST',
      data: {
        code: 'DS-L1', title: '层流罩直发', direct_ship_type: 'laminar', owner_id: 1,
        task_id: taskId, requirement_ids: requirementIds,
        assembly_requirement: '随货附装配说明', ship_address: '客户现场 A 区',
        items: [{ code: 'LF-1', name: '层流罩', quantity: 1, unit: '台' }],
        version: requirement.project_version
      }
    });
    expect(laminar.status).toBe('draft');
    expect(laminar.direct_ship_type).toBe('laminar');
    expect(laminar.node_id).toBe(root.node_id);
    expect(laminar.external_sync_status).toBe('not_configured');

    // 非层流罩直发 (D05): 缺采购订单时服务端拒绝, 不臆造订单.
    const noOrders = await raw(page, `/api/pms/projects/${id}/delivery/direct-ships`, {
      method: 'POST',
      data: {
        code: 'DS-N0', title: '无订单直发', direct_ship_type: 'non_laminar', owner_id: 1,
        task_id: taskId, requirement_ids: requirementIds, items, version: laminar.project_version
      }
    });
    expect(noOrders.status).toBe(400);

    // 按物料编码逐项关联采购订单.
    const nonLaminar = await api(page, `/api/pms/projects/${id}/delivery/direct-ships`, {
      method: 'POST',
      data: {
        code: 'DS-N1', title: '非层流罩直发', direct_ship_type: 'non_laminar', owner_id: 1,
        task_id: taskId, requirement_ids: requirementIds, items,
        purchase_orders: { 'NP-1': 'PO-1001', 'NP-2': 'PO-1002' },
        ship_address: '客户现场 B 区', version: laminar.project_version
      }
    });
    expect(nonLaminar.status).toBe('draft');
    expect(nonLaminar.purchase_orders['NP-2']).toBe('PO-1002');

    // 只缺一项订单也阻断提交, 并点名缺单编码.
    const partial = await api(page, `/api/pms/projects/${id}/delivery/direct-ships/${nonLaminar.id}`, {
      method: 'POST',
      data: {
        title: '非层流罩直发', direct_ship_type: 'non_laminar', owner_id: 1,
        purchase_orders: { 'NP-1': 'PO-1001' }, items, version: nonLaminar.project_version
      }
    });
    const blocked = await raw(page, `/api/pms/projects/${id}/delivery/direct-ships/${nonLaminar.id}/submit`, {
      method: 'POST',
      data: { reviewer_id: reviewerId, evidence_ids: [document.id], version: partial.project_version }
    });
    expect(blocked.status).toBe(409);
    expect(blocked.body.msg).toContain('NP-2');

    // 补齐订单后提交, 由独立审核人批准.
    const complete = await api(page, `/api/pms/projects/${id}/delivery/direct-ships/${nonLaminar.id}`, {
      method: 'POST',
      data: {
        title: '非层流罩直发', direct_ship_type: 'non_laminar', owner_id: 1,
        purchase_orders: { 'NP-1': 'PO-1001', 'NP-2': 'PO-1002' }, items, version: partial.project_version
      }
    });
    const submitted = await api(page, `/api/pms/projects/${id}/delivery/direct-ships/${nonLaminar.id}/submit`, {
      method: 'POST',
      data: { reviewer_id: reviewerId, evidence_ids: [document.id], version: complete.project_version }
    });
    expect(submitted.status).toBe('in_review');

    const token = await reviewerToken(page.request, reviewerName, reviewerPassword);
    const decisionResp = await page.request.fetch(`/api/pms/projects/${id}/delivery/direct-ships/${nonLaminar.id}/decision`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      data: { decision: 'approved', reason: '订单齐备同意直发', version: submitted.project_version }
    });
    const decisionBody = await decisionResp.json();
    expect(decisionBody.code, `独立审批批准直发: ${decisionBody.msg}`).toBe(200);
    expect(decisionBody.data.result.status).toBe('approved');
    expect(decisionBody.data.result.external_sync_status).toBe('not_configured');

    // 工作台汇总反映真实类别分布与审批进度.
    await page.reload();
    await page.getByRole('tab', { name: '工程交付', exact: true }).click();
    await page.getByRole('tab', { name: '直发申请', exact: true }).click();
    await expect(page.getByText('直发申请 2', { exact: true })).toBeVisible();
    await expect(page.getByText('层流罩 1', { exact: true })).toBeVisible();
    await expect(page.getByText('非层流罩 1', { exact: true })).toBeVisible();
    await expect(page.getByText('已批准 1', { exact: true })).toBeVisible();
    await expect(page.getByText('PO-1002').first()).toBeVisible();
    await shot(page, 'd04-approved.png');

    // 只读对账台账: 逐行列出缺单物料与既有发运工单, 供审批人核对后再批准.
    await expect(page.getByText('直发申请汇总')).toBeVisible();
    // 汇总与对账台账在同一面板, 用较高视口完整截取.
    await page.setViewportSize({ width: 1600, height: 1900 });
    await page.getByText('直发申请汇总').scrollIntoViewIfNeeded();
    await shot(page, 'd04-summary.png');
    await page.setViewportSize({ width: 1600, height: 1000 });

    const ws = await api(page, `/api/pms/projects/${id}/delivery`);
    expect(ws.direct_ship_summary.total).toBe(2);
    expect(ws.direct_ship_summary.laminar).toBe(1);
    expect(ws.direct_ship_summary.non_laminar).toBe(1);
    expect(ws.direct_ship_summary.approved).toBe(1);
    expect(ws.direct_ship_summary.external_sync_status).toBe('not_configured');
    expect(errors).toEqual([]);
  });
});