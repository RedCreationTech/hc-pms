const { test, expect } = require('playwright/test');
const path = require('node:path');

// A04 售前资料交接: 按项目产品线生效清单校验应交资料, 缺必交项在界面真实阻断提交并给出退回与补交,
// 齐备后经独立审批批准. 不同产品线必交项差异, 跨项目隔离与权限契约由 pms_presales_test 断言.
const output = path.resolve(__dirname, '../../reports/a04presales');

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
  // 业务命令返回 {result, project_version}, 只读接口直接返回载荷; 统一摊平便于断言.
  return body.data && body.data.result ? { ...body.data.result, project_version: body.data.project_version } : body.data;
}

// 建项目/文档需 pms:project:edit 等写权限, 审核人由系统管理员经真实接口建角色与用户并授予项目菜单,
// 保证提交人与审批人真实分离, 而不是绕过职责分离校验.
async function ensureReviewer(page, username, password) {
  const users = await api(page, `/api/system/user?page=1&size=50&user_name=${username}`);
  const rows = users.rows || users.data || [];
  const found = rows.find(user => (user.user_name || user.userName) === username);
  if (found) return (found.user_id || found.userId);
  const menus = await api(page, '/api/system/menu/treeselect');
  const menuIds = [];
  const walk = nodes => (nodes || []).forEach(node => { menuIds.push(node.menu_id); walk(node.children); });
  walk(menus);
  expect(menuIds.length, '菜单树应包含可授权菜单').toBeGreaterThan(0);
  const roleKey = `a4-review-${Date.now()}`;
  await api(page, '/api/system/role', {
    method: 'POST',
    data: { role_name: `A4审核${Date.now()}`, role_key: roleKey, role_sort: 50, status: '0', menu_ids: menuIds, menu_check_strictly: true }
  });
  const roles = await api(page, `/api/system/role?page=1&size=50&role_key=${roleKey}`);
  const roleRow = (Array.isArray(roles) ? roles : (roles.rows || roles.data || [])).find(item => item.role_key === roleKey);
  expect(roleRow, '审核角色应已创建').toBeTruthy();
  await api(page, '/api/system/user', {
    method: 'POST',
    data: { user_name: username, nick_name: username, password, status: '0', dept_id: 103, roles: [roleRow.role_id] }
  });
  const created = await api(page, `/api/system/user?page=1&size=50&user_name=${username}`);
  const row = (created.rows || created.data || []).find(user => (user.user_name || user.userName) === username);
  expect(row, '审核人用户应已创建').toBeTruthy();
  return row.user_id || row.userId;
}

// 以审核人身份取令牌, 保证批准请求真实来自独立账号.
async function reviewerToken(request, username, password) {
  const response = await request.post('/api/auth/login', { data: { username, password } });
  const body = await response.json();
  expect(body.code, `审核人登录: ${body.msg}`).toBe(200);
  return body.data.token || body.data.access_token;
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

test.describe('A04 售前资料交接', () => {
  test.setTimeout(180000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('产品线清单 -> 缺料阻断 -> 退回 -> 补交 -> 独立批准', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    // 每次运行使用独立产品线编码, 避免与历史配置冲突.
    const line = `E2E-LINE-${Date.now()}`;

    // 建项目 (合同号即本地可用的产品线维度), 并发布该产品线应交资料清单.
    const project = await api(page, '/api/pms/projects', {
      method: 'POST',
      data: {
        project_no: `E2E-A04-${Date.now()}`,
        name: '售前资料交接验证项目',
        customer: '本地客户',
        contract_no: line,
        project_type: 'equipment',
        manager_id: 1,
        dept_id: 1,
        start_date: '2026-09-01',
        end_date: '2026-12-31'
      }
    });
    const id = project.project_id;
    const checklist = await api(page, '/api/pms/config/presales-checklist', {
      method: 'POST',
      data: { code: line, name: `${line} 应交资料`, product_line: line, required_docs: ['技术协议', 'URS'], optional_docs: ['报价单'] }
    });
    await api(page, `/api/pms/config/presales-checklist/${checklist.config_id}/publish`, {
      method: 'POST',
      data: { reason: '验收生效' }
    });

    // 登记一份真实文本文档作为已收资料证据.
    const projectData = await api(page, `/api/pms/projects/${id}`);
    const doc = await api(page, `/api/pms/projects/${id}/governance/documents`, {
      method: 'POST',
      data: { code: 'URS-E2E', title: 'URS', filename: 'urs.txt', content: '客户 URS 全文', version: projectData.version }
    });
    const workspace = await api(page, `/api/pms/projects/${id}/presales`);
    const created = await api(page, `/api/pms/projects/${id}/presales`, {
      method: 'POST',
      data: { title: '合同交接', owner_id: 1, items: [{ name: 'URS', document_ids: [doc.record_id] }], version: workspace.version }
    });
    expect(created.rule_missing).toEqual(['技术协议']);

    // 独立审核人: 由管理员建独立账号, 不共用 admin, 保证职责分离真实成立.
    const reviewerName = `a4review${Date.now()}`;
    const reviewerPassword = 'Review123';
    const reviewerId = await ensureReviewer(page, reviewerName, reviewerPassword);
    expect(reviewerId).not.toBe(1);
    // 审核人须能看到该项目, 经成员维护接口真实加入项目.
    await api(page, `/api/pms/projects/${id}/members`, {
      method: 'POST',
      data: { user_id: reviewerId, role: 'viewer' }
    });

    // 界面: 项目工作台内打开售前资料交接页签.
    await page.goto(`/pms/project?id=${id}`);
    await expect(page.getByText('售前资料交接验证项目').first()).toBeVisible();
    await page.getByRole('tab', { name: '售前资料交接' }).click();
    await expect(page.getByText('产品线应交资料', { exact: true })).toBeVisible();
    await expect(page.getByText(`必交: 技术协议`)).toBeVisible();
    await expect(page.getByText('缺料 技术协议')).toBeVisible();
    await shot(page, 'a04-presales-missing.png');

    // 缺必交项直接提交: 服务端 409, 页面给出错误提示, 状态保持待补交.
    const beforeSubmit = await api(page, `/api/pms/projects/${id}/presales`);
    const blocked = await raw(page, `/api/pms/projects/${id}/presales/${created.record_id}/submit`, {
      method: 'POST',
      data: { reviewer_id: reviewerId, version: beforeSubmit.version }
    });
    expect(blocked.status).toBe(409);
    expect(blocked.body.msg).toContain('技术协议');

    // 补交缺料 (新增第二份文档证据) 后重新提交并由独立审核人批准.
    const second = await api(page, `/api/pms/projects/${id}/governance/documents`, {
      method: 'POST',
      data: { code: 'TA-E2E', title: '技术协议', filename: 'ta.txt', content: '技术协议全文', version: beforeSubmit.version }
    });
    const current = await api(page, `/api/pms/projects/${id}/presales`);
    const completed = await api(page, `/api/pms/projects/${id}/presales/${created.record_id}`, {
      method: 'POST',
      data: {
        items: [{ name: 'URS', document_ids: [doc.record_id] }, { name: '技术协议', document_ids: [second.record_id] }],
        version: current.version
      }
    });
    expect(completed.rule_missing).toEqual([]);
    // 资料齐备后, 提交人仍不能把自己指定为审核人, 职责分离由服务端阻断.
    const selfReview = await raw(page, `/api/pms/projects/${id}/presales/${created.record_id}/submit`, {
      method: 'POST',
      data: { reviewer_id: 1, version: completed.project_version }
    });
    expect(selfReview.status).toBe(409);
    expect(selfReview.body.msg).toContain('审核人不得为提交人');
    // 上一步被拒的提交尝试已推进项目版本, 重新读取后提交.
    const beforeSubmit2 = await api(page, `/api/pms/projects/${id}/presales`);
    const submitted = await api(page, `/api/pms/projects/${id}/presales/${created.record_id}/submit`, {
      method: 'POST',
      data: { reviewer_id: reviewerId, reason: '资料齐备', version: beforeSubmit2.version }
    });
    expect(submitted.status).toBe('submitted');
    // 非指定审核人 (admin 本人) 批准被拒, 只有指定审核人可决定.
    const notReviewer = await raw(page, `/api/pms/projects/${id}/presales/${created.record_id}/approve`, {
      method: 'POST',
      data: { reason: '资料齐备', version: submitted.project_version }
    });
    expect(notReviewer.status).toBe(403);
    const token = await reviewerToken(page.request, reviewerName, reviewerPassword);
    const approvedResp = await page.request.fetch(`/api/pms/projects/${id}/presales/${created.record_id}/approve`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      data: { reason: '资料齐备', version: submitted.project_version }
    });
    const approvedBody = await approvedResp.json();
    expect(approvedBody.code, `独立审核批准: ${approvedBody.msg}`).toBe(200);
    const approved = approvedBody.data.result;
    expect(approved.status).toBe('approved');
    expect(approved.source_sync).toBe('not_applicable');

    await page.reload();
    await page.getByRole('tab', { name: '售前资料交接' }).click();
    await expect(page.getByText('已批准 1')).toBeVisible();
    await expect(page.getByText('当前无缺料')).toBeVisible();
    await shot(page, 'a04-presales-approved.png');

    // 另一种产品线未发布清单时不臆造必交项.
    const other = await api(page, '/api/pms/projects', {
      method: 'POST',
      data: {
        project_no: `E2E-A04B-${Date.now()}`,
        name: '未发布清单项目',
        customer: '本地客户',
        contract_no: `${line}-NONE`,
        project_type: 'equipment',
        manager_id: 1,
        dept_id: 1,
        start_date: '2026-09-01',
        end_date: '2026-12-31'
      }
    });
    const none = await api(page, `/api/pms/projects/${other.project_id}/presales`);
    expect(none.checklist).toBeNull();
    expect(none.checklist_missing).toBe(true);
    await page.goto(`/pms/project?id=${other.project_id}`);
    await page.getByRole('tab', { name: '售前资料交接' }).click();
    await expect(page.getByText(/尚未发布应交清单/)).toBeVisible();
    await shot(page, 'a04-presales-no-checklist.png');

    expect(errors).toEqual([]);
  });
});