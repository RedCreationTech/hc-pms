const { test, expect } = require('playwright/test');
const path = require('node:path');

// B17 受控取消: 取消申请必须关联已批准变更依据, 批准后立即停止该范围后续下发,
// 未执行的外发记录列为待补偿对账项; 外部补偿回执如实标注 not_configured.
// 范围隔离 (取消单机不影响兄弟设备) 与职责分离由 pms_cancellation_test 断言.
const output = path.resolve(__dirname, '../../reports/b17cancellation');

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

// 审批人由管理员经真实接口建角色与独立账号, 保证提交人与审批人真实分离.
async function ensureReviewer(page, username, password) {
  const users = await api(page, `/api/system/user?page=1&size=50&user_name=${username}`);
  const found = (users.rows || []).find(user => user.user_name === username);
  if (found) return found.user_id;
  const menus = await api(page, '/api/system/menu/treeselect');
  const menuIds = [];
  const walk = nodes => (nodes || []).forEach(node => { menuIds.push(node.menu_id); walk(node.children); });
  walk(menus);
  const roleKey = `b17-review-${Date.now()}`;
  await api(page, '/api/system/role', {
    method: 'POST',
    data: { role_name: `B17审批${Date.now()}`, role_key: roleKey, role_sort: 50, status: '0', menu_ids: menuIds, menu_check_strictly: true }
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

test.describe('B17 项目/单机受控取消', () => {
  test.setTimeout(240000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('无变更依据阻断 -> 关联已批准变更 -> 独立审批 -> 停止下发与待补偿', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const stamp = Date.now();
    const project = await api(page, '/api/pms/projects', {
      method: 'POST',
      data: {
        project_no: `E2E-B17-${stamp}`,
        name: '受控取消验证项目',
        customer: '本地客户',
        contract_no: `E2E-B17-${stamp}`,
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

    // 独立审批人: 按真实项目成员关系加入, 保证职责分离成立.
    const reviewerName = `b17review${stamp}`;
    const reviewerPassword = 'Review123';
    const reviewerId = await ensureReviewer(page, reviewerName, reviewerPassword);
    expect(reviewerId).not.toBe(1);
    await api(page, `/api/pms/projects/${id}/members`, { method: 'POST', data: { user_id: reviewerId, role: 'viewer' } });

    // 界面: 无已批准变更时, 登记按钮真实禁用, 且说明阻断原因.
    await page.goto(`/pms/project?id=${id}`);
    await expect(page.getByText('受控取消验证项目').first()).toBeVisible();
    await page.getByRole('tab', { name: '取消申请' }).click();
    await expect(page.getByText('取消范围与外部补偿')).toBeVisible();
    await expect(page.getByText(/尚无已批准变更/)).toBeVisible();
    await expect(page.getByRole('button', { name: '登记取消申请' })).toBeDisabled();
    await expect(page.getByText('外部补偿回执待合同 (not_configured)')).toBeVisible();
    await shot(page, 'b17-no-basis.png');

    // 服务端同样阻断: 无变更依据的取消申请被拒 (400), 不臆造可用依据.
    const workspace = await api(page, `/api/pms/projects/${id}/cancellations`);
    const noBasis = await raw(page, `/api/pms/projects/${id}/cancellations`, {
      method: 'POST',
      data: { target: 'node', node_id: root.node_id, reason: '客户取消', version: workspace.version }
    });
    expect(noBasis.status).toBe(400);

    // 真实走一遍变更审批: 登记变更 -> 提交独立审核人 -> 审核人批准.
    const drafts = await api(page, `/api/pms/projects/${id}/governance`);
    const change = await api(page, `/api/pms/projects/${id}/governance/changes`, {
      method: 'POST',
      data: {
        title: '客户取消单机采购',
        reason: '客户来函取消该设备',
        scope_impact: '仅该单机及其下级',
        schedule_impact: '交付提前结束',
        cost_impact: '合同额减少',
        quality_impact: '无',
        resource_impact: '释放装配人力',
        version: drafts.project_version
      }
    });
    const changeSub = await api(page, `/api/pms/projects/${id}/governance/changes/${change.record_id}/submit`, {
      method: 'POST',
      data: { reviewer_id: reviewerId, version: change.project_version }
    });
    const token = await reviewerToken(page.request, reviewerName, reviewerPassword);
    const decidedResp = await page.request.fetch(`/api/pms/projects/${id}/governance/changes/${change.record_id}/decision`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      data: { decision: 'approved', reason: '同意取消', version: changeSub.project_version }
    });
    const decidedBody = await decidedResp.json();
    expect(decidedBody.code, `变更批准: ${decidedBody.msg}`).toBe(200);

    // 登记取消申请 (范围: 该单机), 确认冻结受影响节点与外部补偿待合同.
    const ws2 = await api(page, `/api/pms/projects/${id}/cancellations`);
    const created = await api(page, `/api/pms/projects/${id}/cancellations`, {
      method: 'POST',
      data: {
        target: 'node',
        node_id: root.node_id,
        basis_change_id: change.record_id,
        reason: '按批准变更取消该单机',
        version: ws2.version
      }
    });
    expect(created.status).toBe('pending');
    expect(created.external_compensation).toBe('not_configured');
    expect(created.affected_nodes.length).toBeGreaterThan(0);

    // 提交后应由独立审批人决定: 非指定审批人 (提交人本人) 不能批准.
    const submitted = await api(page, `/api/pms/projects/${id}/cancellations/${created.record_id}/submit`, {
      method: 'POST',
      data: { reviewer_id: reviewerId, reason: '请审批取消', version: created.project_version }
    });
    expect(submitted.status).toBe('submitted');
    const selfReview = await raw(page, `/api/pms/projects/${id}/cancellations/${created.record_id}/review`, {
      method: 'POST',
      data: { decision: 'approved', reason: '自己批准', version: submitted.project_version }
    });
    expect(selfReview.status).toBe(403);

    const approvedResp = await page.request.fetch(`/api/pms/projects/${id}/cancellations/${created.record_id}/review`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      data: { decision: 'approved', reason: '同意取消并停止下发', version: submitted.project_version }
    });
    const approvedBody = await approvedResp.json();
    expect(approvedBody.code, `独立审批批准取消: ${approvedBody.msg}`).toBe(200);
    const approved = approvedBody.data.result;
    expect(approved.status).toBe('approved');
    // 批准即停止下发, 未执行外发记录进入待补偿对账.
    expect(approved.dispatch_stopped).toBe(true);
    expect(approved.external_compensation).toBe('not_configured');
    expect(approved.pending_compensation).toBe(approved.outbound.length);

    await page.reload();
    await page.getByRole('tab', { name: '取消申请' }).click();
    await expect(page.getByText('已批准 1')).toBeVisible();
    await expect(page.getByText('已停止', { exact: true })).toBeVisible();
    // 依据列展示变更标题而不是裸编号, 便于审批人核对.
    await expect(page.getByText('客户取消单机采购')).toBeVisible();
    await shot(page, 'b17-approved.png');

    // 已批准的取消覆盖的节点可被交付/计划侧读取, 供停止后续下发.
    expect(approved.affected_nodes.map(node => node.id)).toContain(root.node_id);
    expect(errors).toEqual([]);
  });
});