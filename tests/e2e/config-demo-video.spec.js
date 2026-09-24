const { test, expect } = require('@playwright/test');
const path = require('node:path');
const fs = require('node:fs');
const S = require('../../scripts/demo-video/storyboard-config.js');
const kit = require('./demo-video-kit.js');

// "组织, 菜单与流程的灵活配置" 演示视频的自动录屏 (分镜与字幕见 scripts/demo-video/storyboard-config.js).
// 在全新的演示库上以一家公司的多个部门为例: 部门与负责人 -> 角色菜单/按钮/数据权限与权限效果 -> 审批策略 -> 逐级审批.
// 每章按分镜用到的账号各开一个录像上下文 (可见光标, 表单逐字输入, 底边时间码), 镜头起止与字幕时刻写入
// reports/config-video/timeline.json, 由 compose.py (DEMO_VIDEO_DIR=reports/config-video) 剪辑合成. 默认跳过.
test.skip(!process.env.CONFIG_DEMO_VIDEO, '演示视频录屏较慢, 仅在 CONFIG_DEMO_VIDEO=1 时运行');

const OUT = path.resolve(__dirname, '../../reports/config-video');
const RAW = path.join(OUT, 'raw');
const { VIEWPORT, wait, installCursor, installClock, glide, tap, typeInto, settle, login, api } = kit;
const BASE_URL = process.env.BASE_URL || 'http://localhost:3000';
const PASSWORD = 'Demo-2026';

const dialog = (page, title) => page.getByRole('dialog').filter({ hasText: title }).last();
const okButton = scope => scope.getByRole('button', { name: /^(确\s*定|OK)$/ });

// antd 下拉: 等上一个下拉收起后点开 (可逐字过滤), 点选可见选项; 单选选中后下拉自动关闭.
async function pick(page, select, text, typed) {
  await expect(page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')).toHaveCount(0);
  await tap(page, select);
  if (typed) await page.keyboard.type(typed, { delay: 70 });
  const option = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option:visible').filter({ hasText: text }).last();
  await tap(page, option);
  await page.waitForTimeout(250);
}

const treeNode = (page, scope, title) =>
  scope.locator('.ant-tree-treenode').filter({ has: page.locator('.ant-tree-title', { hasText: new RegExp(`^${title}$`) }) }).first();

async function menuTreeExpand(page, scope, title) {
  const switcher = treeNode(page, scope, title).locator('.ant-tree-switcher_close');
  if (await switcher.count()) await tap(page, switcher);
  await page.waitForTimeout(300);
}

// 勾选 (checked=true) 或取消菜单节点; 父子联动, 部分下级取消时上级为半选.
async function menuTreeCheck(page, scope, title, checked) {
  const box = treeNode(page, scope, title).locator('.ant-tree-checkbox').first();
  const isChecked = async () => (await box.getAttribute('class')).includes('ant-tree-checkbox-checked');
  if ((await isChecked()) !== checked) await tap(page, box);
  await expect.poll(isChecked).toBe(checked);
}

async function roleMore(page, roleKey, item) {
  const row = page.locator('tbody tr').filter({ hasText: roleKey }).first();
  await glide(page, row.getByRole('button', { name: '更多' }));
  await row.getByRole('button', { name: '更多' }).hover();
  await tap(page, page.getByRole('menuitem', { name: item }));
}

// ── 一次性准备 (不录像): 事业部与研发一部, 四个演示账号, 财务与项目经理角色, 研发一部的项目和 15 万元预算草稿 ──

async function prepare(page, suffix) {
  const menus = [];
  const walk = nodes => nodes.forEach(n => { menus.push(n); walk(n.children || []); });
  walk(await api(page, 'GET', '/api/system/menu'));
  const pmsIds = menus.filter(m => m.path === 'pms' || (m.perms || '').startsWith('pms:')).map(m => m.menu_id);
  const createRole = async (name, key, menuIds) => {
    await api(page, 'POST', '/api/system/role', { role_name: name, role_key: key, role_sort: 5, status: '0', 'menu-ids': menuIds });
    return (await api(page, 'GET', `/api/system/role?role_key=${key}`)).find(r => r.role_key === key).role_id;
  };
  const financeRole = await createRole('财务', 'finance', pmsIds);
  const pmRole = await createRole('项目经理', 'project_manager', pmsIds);
  const depts = async () => api(page, 'GET', '/api/system/dept');
  const deptId = async name => (await depts()).find(d => d.dept_name === name).dept_id;
  await api(page, 'POST', '/api/system/dept', { parent_id: 1, dept_name: '智能装备事业部', order_num: 1 });
  const division = await deptId('智能装备事业部');
  await api(page, 'POST', '/api/system/dept', { parent_id: division, dept_name: '研发一部', order_num: 1 });
  const rd = await deptId('研发一部');
  const finance = await deptId('财务部门');
  const user = async (userName, nick, dept, roles) => {
    await api(page, 'POST', '/api/system/user', { user_name: userName, nick_name: nick, password: PASSWORD, dept_id: dept, roles, posts: [], status: '0' });
    return (await api(page, 'GET', `/api/system/user?user_name=${userName}&page=1&size=5`)).rows[0].user_id;
  };
  const zhao = await user(`zhao${suffix}`, '赵总', division, [2]);
  const zhou = await user(`zhou${suffix}`, '周经理', rd, [2]);
  const sun = await user(`sun${suffix}`, '孙工', rd, [2, pmRole]);
  const li = await user(`li${suffix}`, '李工程师', rd, [2]);
  await user(`qian${suffix}`, '钱会计', finance, [2, financeRole]);
  await api(page, 'PUT', `/api/system/dept/${division}`, { leader_id: zhao });
  return { suffix, division, rd, zhao, zhou, sun, li, financeRole, pmRole,
           users: { zhou: `zhou${suffix}`, sun: `sun${suffix}`, qian: `qian${suffix}`, zhao: `zhao${suffix}` } };
}

async function prepareProject(pmPage, st) {
  const project = await api(pmPage, 'POST', '/api/pms/projects', {
    project_no: `RD-${st.suffix.slice(-4).toUpperCase()}`, name: '智能分拣线控制系统', customer: '内部研发',
    manager_id: st.sun, dept_id: st.rd, start_date: '2026-10-01', end_date: '2027-03-31', project_type: 'new_product' });
  const id = project.project_id || project.id;
  const version = async () => (await api(pmPage, 'GET', `/api/pms/projects/${id}`)).version;
  await api(pmPage, 'POST', `/api/pms/projects/${id}/members`, { user_id: st.li, role: 'viewer' });
  const cost = (await api(pmPage, 'POST', `/api/pms/projects/${id}/cost-versions`, {
    kind: 'budget', period: '2026-10', currency: 'CNY', name: '研发预算', revenue: '0.00', reviewer_id: st.li, version: await version() })).result;
  for (const [category, label, amount] of [['material', '控制器与传感器', '90000.00'], ['labor', '软件开发人工', '45000.00'], ['travel', '现场调试差旅', '15000.00']]) {
    await api(pmPage, 'POST', `/api/pms/projects/${id}/cost-versions/${cost.id}/entries`, { category, label, amount, version: await version() });
  }
  return { projectId: id, costId: cost.id };
}

// ── 章节 ──

const CHAPTERS = {
  async '01'({ pages, rec }) {
    const page = pages.admin;
    await page.goto('/system/dept');
    await page.locator('tbody tr').filter({ hasText: '研发一部' }).first().waitFor();
    await rec.shot('01-1');
    await glide(page, page.locator('tbody tr').filter({ hasText: '智能装备事业部' }).first());
    await rec.next();
    await tap(page, page.locator('tbody tr').filter({ hasText: '智能装备事业部' }).first().getByRole('button', { name: '新增' }));
    const create = dialog(page, '新增部门');
    await typeInto(page, create.locator('#dept_name'), '测试部');
    await typeInto(page, create.locator('#order_num'), '2');
    await tap(page, okButton(create));
    await expect(page.getByText('创建成功')).toBeVisible();
    await page.locator('tbody tr').filter({ hasText: '测试部' }).first().waitFor();
    await rec.next();
    await glide(page, page.locator('tbody tr').filter({ hasText: '测试部' }).first());
    await rec.end();

    await rec.shot('01-2');
    await tap(page, page.locator('tbody tr').filter({ hasText: '研发一部' }).first().getByRole('button', { name: '修改' }));
    const edit = dialog(page, '修改部门');
    await pick(page, edit.locator('#leader_id'), '周经理', '周');
    await rec.next();
    await glide(page, edit.getByText('负责人可作为审批规则中的"部门负责人"'));
    await tap(page, okButton(edit));
    await expect(page.getByText('更新成功')).toBeVisible();
    await expect(page.locator('tbody tr').filter({ hasText: '研发一部' }).first()).toContainText('周经理');
    await rec.end();

    await page.goto('/system/user');
    await page.locator('.ant-table-tbody').waitFor();
    await rec.shot('01-3');
    await tap(page, page.getByText('智能装备事业部', { exact: true }).first());
    await page.waitForLoadState('networkidle');
    await rec.next();
    await tap(page, page.getByText('研发一部', { exact: true }).first());
    await page.waitForLoadState('networkidle');
    await expect(page.locator('.ant-table-tbody')).toContainText('周经理');
    await rec.end();
  },

  async '02'({ pages, rec, st }) {
    const page = pages.admin;
    await page.goto('/system/role');
    await page.locator('tbody tr:not(.ant-table-measure-row)').first().waitFor();
    await rec.shot('02-1');
    await tap(page, page.getByRole('button', { name: '新增' }).first());
    const create = dialog(page, '新增角色');
    await typeInto(page, create.locator('#role_name'), '部门经理');
    await typeInto(page, create.locator('#role_key'), 'dept_manager');
    await tap(page, okButton(create));
    await expect(page.locator('tbody tr').filter({ hasText: 'dept_manager' })).toBeVisible();
    await rec.end();

    await rec.shot('02-2');
    await roleMore(page, 'dept_manager', '分配权限');
    const perm = dialog(page, '分配菜单权限');
    await perm.locator('.ant-tree').waitFor();
    await menuTreeExpand(page, perm, '系统管理');
    await menuTreeCheck(page, perm, '用户管理', true);
    await menuTreeExpand(page, perm, '用户管理');
    await rec.next();
    await menuTreeCheck(page, perm, '用户删除', false);
    await menuTreeCheck(page, perm, '部门管理', true);
    await rec.next();
    await menuTreeCheck(page, perm, '项目管理', true);
    await tap(page, okButton(perm));
    await expect(page.getByText('权限更新成功')).toBeVisible();
    await rec.end();

    await rec.shot('02-3');
    await roleMore(page, 'dept_manager', '数据权限');
    const scope = dialog(page, '分配数据权限');
    await tap(page, scope.getByText('本部门及以下数据权限'));
    await rec.next();
    await tap(page, okButton(scope));
    await expect(page.getByText('数据权限设置成功')).toBeVisible();
    await rec.end();

    await rec.shot('02-4');
    await roleMore(page, 'dept_manager', '分配用户');
    const alloc = dialog(page, '分配用户');
    await tap(page, alloc.getByRole('tab', { name: '添加用户' }));
    const row = alloc.locator('tbody tr').filter({ hasText: st.users.zhou }).first();
    await tap(page, row.locator('input[type=checkbox]'));
    await tap(page, alloc.getByRole('button', { name: '授权选中用户' }));
    await expect(page.getByText('授权成功')).toBeVisible();
    await tap(page, alloc.getByRole('tab', { name: '已分配用户' }));
    await expect(alloc.locator('.ant-tabs-tabpane-active tbody')).toContainText(st.users.zhou);
    await rec.end();
    await tap(page, alloc.locator('.ant-modal-close'));
  },

  async '03'({ pages, rec, st }) {
    const mgr = pages.mgr;
    await mgr.goto('/system/user');
    await mgr.locator('.ant-table-tbody').waitFor();
    await rec.shot('03-1');
    await glide(mgr, mgr.locator('.ant-layout-sider'));
    await rec.next();
    await glide(mgr, mgr.locator('.ant-table-tbody'));
    await expect(mgr.locator('.ant-table-tbody')).toContainText('孙工');
    await expect(mgr.locator('.ant-table-tbody')).not.toContainText('钱会计');
    await rec.next();
    await glide(mgr, mgr.locator('.ant-table-tbody tr:not(.ant-table-measure-row)').first());
    await expect(mgr.getByRole('button', { name: '删除' })).toHaveCount(0);
    await rec.end();

    await rec.shot('03-2');
    await mgr.goto('/system/role');
    await expect(mgr.getByText('无权访问该页面')).toBeVisible();
    await glide(mgr, mgr.getByText('无权访问该页面'));
    await rec.end();

    const admin = pages.admin;
    await admin.goto('/system/role');
    await admin.locator('tbody tr').filter({ hasText: 'dept_manager' }).waitFor();
    await rec.shot('03-3');
    await roleMore(admin, 'dept_manager', '分配权限');
    const perm = dialog(admin, '分配菜单权限');
    await perm.locator('.ant-tree').waitFor();
    await menuTreeExpand(admin, perm, '系统管理');
    await menuTreeCheck(admin, perm, '部门管理', false);
    await tap(admin, okButton(perm));
    await expect(admin.getByText('权限更新成功')).toBeVisible();
    await rec.end();

    await rec.shot('03-4');
    await wait(3200); // 权限刷新节流 3 秒
    await tap(mgr, mgr.getByRole('button', { name: '返回首页' }));
    await mgr.waitForLoadState('networkidle');
    await tap(mgr, mgr.locator('.ant-layout-sider').getByText('用户管理'));
    await expect(mgr.locator('.ant-layout-sider').getByText('部门管理')).toHaveCount(0);
    await rec.next();
    await mgr.goto('/system/dept');
    await expect(mgr.getByText('无权访问该页面')).toBeVisible();
    await rec.end();
    expect(st).toBeTruthy();
  },

  async '04'({ pages, rec }) {
    const page = pages.admin;
    await page.goto('/pms/config');
    await page.getByRole('tab', { name: '审批策略' }).waitFor();
    await rec.shot('04-1');
    await tap(page, page.getByRole('tab', { name: '审批策略' }));
    await page.getByText('费用版本两级审批').first().waitFor();
    await rec.next();
    await tap(page, page.getByRole('button', { name: '新建审批策略' }));
    const editor = dialog(page, '新建审批策略');
    await typeInto(page, editor.getByLabel('策略名称'), '研发费用三级审批');
    await rec.end();

    await rec.shot('04-2');
    await typeInto(page, editor.getByLabel('第1级名称'), '部门负责人审批');
    await rec.next();
    await tap(page, editor.getByRole('button', { name: '+ 添加一级审批' }));
    await typeInto(page, editor.getByLabel('第2级名称'), '财务审批');
    await pick(page, editor.getByLabel('第2级审批人规则'), '指定角色');
    await pick(page, editor.getByLabel('审批角色'), '财务', '财务');
    await rec.next();
    await tap(page, editor.getByRole('button', { name: '+ 添加一级审批' }));
    await typeInto(page, editor.getByLabel('第3级名称'), '事业部负责人审批');
    await pick(page, editor.getByLabel('上溯级数').nth(1), '上溯1级');
    await typeInto(page, editor.getByLabel('金额条件').nth(2), '100000');
    await glide(page, editor.getByText('预览'));
    await rec.next();
    await tap(page, editor.getByRole('button', { name: '保存为草稿' }));
    await expect(page.getByText('审批策略已保存为草稿')).toBeVisible();
    const row = page.locator('tbody tr').filter({ hasText: '研发费用三级审批' }).first();
    await tap(page, row.getByRole('button', { name: '发布' }));
    const publish = dialog(page, '发布审批策略');
    await typeInto(page, publish.locator('#reason'), '按研发部门组织启用');
    await tap(page, publish.getByRole('button', { name: /保\s*存/ }));
    await expect(row).toContainText('已发布');
    await rec.end();
  },

  async '05'({ pages, rec, st }) {
    const pm = pages.pm;
    const openCosts = async () => {
      await pm.goto(`/pms/project?id=${st.projectId}`);
      const drawer = pm.getByRole('dialog').filter({ has: pm.getByRole('tab', { name: '项目概况', exact: true }) });
      await drawer.getByRole('tab', { name: '项目费用', exact: true }).click();
      await pm.waitForLoadState('networkidle');
      await drawer.locator('tbody tr').filter({ hasText: '研发预算' }).first().waitFor();
      await settle(pm);
      return drawer;
    };
    let drawer = await openCosts();
    await rec.shot('05-1');
    const row = drawer.locator('tbody tr').filter({ hasText: '研发预算' }).first();
    await tap(pm, row.getByRole('button', { name: '提交审批' }));
    const submit = dialog(pm, '提交成本版本审批');
    await tap(pm, submit.getByRole('button', { name: /保\s*存/ }));
    await expect(submit).toBeHidden();
    await pm.waitForLoadState('networkidle');
    await rec.next();
    await tap(pm, drawer.locator('tbody tr').filter({ hasText: '研发预算' }).first().getByRole('button', { name: '查看明细' }));
    const steps = drawer.locator('.approval-flow .ant-steps').first();
    await steps.waitFor();
    await rec.next();
    await glide(pm, steps);
    await rec.end();

    const mgr = pages.mgr;
    await mgr.goto('/pms/todo');
    await mgr.getByText('逐级审批').first().waitFor();
    await rec.shot('05-2');
    const item = mgr.locator('tbody tr').filter({ hasText: '研发预算' }).first();
    await glide(mgr, item);
    await tap(mgr, item.getByRole('button', { name: /^审\s*批$/ }));
    const decide = dialog(mgr, '审批 · 费用版本');
    await decide.locator('.approval-flow .ant-steps').waitFor();
    await rec.next();
    await typeInto(mgr, decide.getByLabel('审批意见'), '预算构成合理, 同意');
    await tap(mgr, decide.getByRole('button', { name: /^通\s*过$/ }));
    await expect(mgr.getByText('已通过').first()).toBeVisible();
    await rec.end();

    const fin = pages.fin;
    await fin.goto('/pms/todo');
    await fin.getByText('逐级审批').first().waitFor();
    await rec.shot('05-3');
    const finItem = fin.locator('tbody tr').filter({ hasText: '研发预算' }).first();
    await tap(fin, finItem.getByRole('button', { name: /^审\s*批$/ }));
    const finDecide = dialog(fin, '审批 · 费用版本');
    await finDecide.locator('.approval-flow .ant-steps').waitFor();
    await rec.next();
    await typeInto(fin, finDecide.getByLabel('审批意见'), '金额与明细核对一致');
    await tap(fin, finDecide.getByRole('button', { name: /^通\s*过$/ }));
    await expect(fin.getByText('已通过').first()).toBeVisible();
    await rec.end();

    // 第三级: 事业部负责人批准 (不入镜, 经真实 HTTP 由其本人账号完成)
    const flow = (await api(pm, 'GET', `/api/pms/projects/${st.projectId}/approvals?biz_type=cost-version`))[0];
    const zhaoCtx = await pm.context().browser().newContext({ baseURL: BASE_URL });
    const zhao = await zhaoCtx.newPage();
    await login(zhao, st.users.zhao, PASSWORD);
    await api(zhao, 'POST', `/api/pms/approvals/${flow.flow_id}/decide`, { decision: 'approved', comment: '同意列入预算' });
    await zhaoCtx.close();

    drawer = await openCosts();
    await rec.shot('05-4');
    await tap(pm, drawer.locator('tbody tr').filter({ hasText: '研发预算' }).first().getByRole('button', { name: '查看明细' }));
    await drawer.locator('.approval-flow .ant-steps').first().waitFor();
    await glide(pm, drawer.locator('.approval-flow .ant-steps').first());
    await rec.next();
    await expect(drawer.locator('tbody tr').filter({ hasText: '研发预算' }).first()).toContainText('已批准');
    await glide(pm, drawer.locator('tbody tr').filter({ hasText: '研发预算' }).first());
    await rec.end();
  },
};

test.describe('组织, 菜单与流程配置演示视频录屏', () => {
  test.setTimeout(3600000);

  test('一家公司的多个部门: 组织, 角色菜单, 审批策略与逐级审批 (录屏 + 时间轴)', async ({ browser }) => {
    fs.rmSync(RAW, { recursive: true, force: true });
    fs.mkdirSync(RAW, { recursive: true });
    const recorded = async () => {
      const context = await browser.newContext({ baseURL: BASE_URL, viewport: VIEWPORT, recordVideo: { dir: RAW, size: VIEWPORT } });
      await context.addInitScript(installCursor);
      await context.addInitScript(installClock);
      const page = await context.newPage();
      return { context, page, t0: Date.now() };
    };

    // 准备 (不录像)
    const prepCtx = await browser.newContext({ baseURL: BASE_URL, viewport: VIEWPORT });
    const prep = await prepCtx.newPage();
    await login(prep);
    const st = await prepare(prep, `${Date.now().toString(36).slice(-4)}`);
    await prepCtx.close();
    // 项目与预算草稿由项目经理本人建立 (独立上下文, 不共享管理员的登录态)
    const pmCtx = await browser.newContext({ baseURL: BASE_URL, viewport: VIEWPORT });
    const pmPage = await pmCtx.newPage();
    await login(pmPage, st.users.sun, PASSWORD);
    Object.assign(st, await prepareProject(pmPage, st));
    await pmCtx.close();

    const creds = { admin: ['admin', 'admin123'], mgr: [st.users.zhou, PASSWORD], pm: [st.users.sun, PASSWORD], fin: [st.users.qian, PASSWORD] };
    const clips = [];
    for (const chapter of S.chapters) {
      const whos = [...new Set(S.shots.filter(s => s.chapter === chapter.no).map(s => s.who))];
      const ctx = {};
      const errors = [];
      for (const who of whos) {
        ctx[who] = await recorded();
        ctx[who].page.on('pageerror', error => errors.push(`${who}: ${error.message}`));
        await login(ctx[who].page, ...creds[who]);
      }
      const rec = kit.recorder(S, ctx);
      const pages = Object.fromEntries(Object.entries(ctx).map(([k, v]) => [k, v.page]));
      try {
        await CHAPTERS[chapter.no]({ pages, rec, st });
        expect(errors, `第 ${chapter.no} 章页面错误`).toEqual([]);
      } finally {
        await wait(2500);
        for (const [who, c] of Object.entries(ctx)) {
          await c.context.close();
          const rel = `raw/ch-${chapter.no}-${who}.webm`;
          await c.page.video().saveAs(path.join(OUT, rel));
          await c.page.video().delete();
          c.rel = rel;
        }
      }
      expect([...new Set(rec.timeline.clips.map(c => c.shot))], `第 ${chapter.no} 章镜头须与分镜一致`)
        .toEqual(S.shots.filter(s => s.chapter === chapter.no).map(s => s.id));
      clips.push(...rec.timeline.clips.map(c => ({ ...c, video: ctx[c.who].rel, t0: ctx[c.who].t0 })));
    }
    expect([...new Set(clips.map(c => c.shot))]).toEqual(S.shots.map(s => s.id));
    fs.writeFileSync(path.join(OUT, 'timeline.json'),
      JSON.stringify({ viewport: VIEWPORT, recorded_at: new Date().toISOString(), clips }, null, 2));
    fs.writeFileSync(path.join(OUT, 'state.json'), JSON.stringify(st, null, 2));
  });
});
