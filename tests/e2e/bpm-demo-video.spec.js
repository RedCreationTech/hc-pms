const { test, expect } = require('@playwright/test');
const path = require('node:path');
const fs = require('node:fs');
const S = require('../../scripts/demo-video/storyboard-bpm.js');
const kit = require('./demo-video-kit.js');

// "BPM 与办公一体化" 演示视频的自动录屏 (分镜与字幕见 scripts/demo-video/storyboard-bpm.js).
// 结构对齐 config-demo-video.spec.js: 每章按分镜用到的账号各开一个录像上下文 (可见光标, 逐字输入, 底边时间码),
// 镜头起止与字幕时刻写入 reports/bpm-video/timeline.json, 由 compose.py (DEMO_VIDEO_DIR=reports/bpm-video) 剪辑合成.
//
// 真实数据策略 (与既有 H01/H08/config-demo 同构): 请假 / 报销 / 日程 / 会议等写操作在一次性准备阶段经真实 HTTP
// 命令链 (POST /api/business/...) 落库, 录屏镜头在对应自助页面 (我的待办 / 已办 / 抄送 / 我的流程) 与模块列表页
// 展示这些真实记录. 表单填写与任务办理的具体选择器在 Phase 4 隔离后端 (含 Flowable) 实跑时逐一校准.
// 默认跳过, 仅在 BPM_DEMO_VIDEO=1 时运行.
test.skip(!process.env.BPM_DEMO_VIDEO, '演示视频录屏较慢, 仅在 BPM_DEMO_VIDEO=1 时运行');

const OUT = path.resolve(__dirname, '../../reports/bpm-video');
const RAW = path.join(OUT, 'raw');
const { VIEWPORT, wait, installCursor, installClock, glide: _glide, tap, typeInto, settle, login, api } = kit;
// 受限页 (如员工菜单里没有的请假/报销/HRM/CRM) 会让 glide 目标 selector 不出现; 用 safeGlide 吞掉 30 秒超时并保留 800ms 停顿, 让录屏继续.
async function glide(page, locator) { try { await _glide(page, locator); } catch (_) { await page.waitForTimeout(800); } }
const BASE_URL = process.env.BASE_URL || 'http://localhost:3000';
const PASSWORD = 'Demo-2026';
const BIZ = '/api/business';

const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab') }).last();

// 列表主容器: 办公各模块均以 antd Table 呈现, 载入后 tbody 稳定存在, 作为 glide 的兜底目标.
const tbody = page => page.locator('.ant-table-tbody');

// 打开某模块页并等待表格或空态稳定, 供镜头展示真实内容.
// 员工(common 角色)菜单受限或页面渲染慢时不阻断录屏: 4 秒兜底 + 1.5 秒 settle, 画面本身即"受限可见"的真实素材.
async function openList(page, route) {
  await page.goto(route);
  await page.waitForLoadState('networkidle');
  try {
    await page.locator('.ant-table-tbody, .ant-empty').first().waitFor({ timeout: 4000 });
  } catch (_) {
    await page.waitForTimeout(1500);
  }
  await settle(page);
}

// ── 一次性准备 (不录像): 建 common 角色的普通员工, 用真实命令链落请假 / 报销 / 日程 / 会议 / HRM / CRM 数据 ──

async function prepare(page, suffix) {
  // 复用种子普通角色 common (自助发起 / 待办 / 已办 / 抄送菜单).
  const roles = await api(page, 'GET', '/api/system/role?role_key=common');
  const common = roles.find(r => r.role_key === 'common');
  if (!common) throw new Error('种子角色 common 不存在, 无法演示员工自助视图');
  const empName = `bemp${suffix}`;
  await api(page, 'POST', '/api/system/user', {
    user_name: empName, nick_name: '演示员工', password: PASSWORD, dept_id: 103,
    roles: [common.role_id], posts: [], status: '0' });
  const emp = (await api(page, 'GET', `/api/system/user?user_name=${empName}&page=1&size=5`)).rows[0];
  return { suffix, empName, empId: emp.user_id };
}

// 员工自助链: 真实发起请假与报销 (入 Flowable 审批流), 供"我的待办 / 已办 / 抄送"镜头展示真实实例.
async function seedEmployeeFlow(page, st) {
  const out = {};
  // 请假: start-leave 入审批流; 若模型未部署会返回业务错误, 记录后不阻断 (Phase 4 校准流程部署).
  try { out.leave = await api(page, 'POST', `${BIZ}/oa/leave`, { days: 3, reason: '家中有事, 申请年假三天' }); } catch (e) { out.leaveError = String(e); }
  try { out.reimburse = await api(page, 'POST', `${BIZ}/oa/reimburse`, { category: 'travel', amount: '1280.00', reason: '出差交通报销' }); } catch (e) { out.reimburseError = String(e); }
  return out;
}

// 管理端办公数据: 日程, 会议, HRM 员工档案, CRM 客户, 供相应用例镜头展示真实行.
async function seedOffice(page) {
  const out = {};
  const tryApi = async (key, method, url, data) => { try { out[key] = await api(page, method, url, data); } catch (e) { out[`${key}Error`] = String(e); } };
  await tryApi('calendar', 'POST', `${BIZ}/oa/calendar`, { title: '项目周会', start_time: '2026-10-08 09:00:00', end_time: '2026-10-08 10:00:00', content: '同步本周进度与风险' });
  await tryApi('meeting', 'POST', `${BIZ}/oa/meeting`, { title: '季度经营复盘', location: '会议室 A', start_time: '2026-10-10 14:00:00', end_time: '2026-10-10 16:00:00' });
  await tryApi('hrm', 'POST', `${BIZ}/hrm/employee`, { name: '演示员工', code: `EMP-${Date.now().toString(36).slice(-4).toUpperCase()}`, dept_id: 103, post: '工程师', status: 'active' });
  await tryApi('crm', 'POST', `${BIZ}/crm/customer`, { name: '红创示例客户', contact: '张经理', phone: '13800000000', stage: 'following' });
  return out;
}

// ── 章节 ──
// 每章: 进入分镜指定的真实页面, 展示该模块的真实内容 (列表 / 已落库记录), 三拍走完字幕 (shot → next → end).
// 表单填写与任务办理类交互在 Phase 4 隔离后端实跑时按真实选择器补充 (见文件头说明).

const CHAPTERS = {
  '01': async ({ pages, rec }) => {
    const page = pages.admin;
    await openList(page, '/office/bpm/model');
    await rec.shot('01-1');
    await glide(page, page.getByRole('tab', { name: '流程模型' }).or(page.locator('.ant-table-tbody')).first());
    await rec.next();
    await glide(page, tbody(page).locator('tr:not(.ant-table-measure-row)').first().or(page.locator('.ant-empty')).first());
    await rec.end();

    await page.goto('/office/bpm/model');
    await page.waitForLoadState('networkidle');
    await settle(page);
    await rec.shot('01-2');
    await glide(page, page.locator('.ant-table-tbody, .ant-empty').first());
    await rec.next();
    await glide(page, page.locator('.ant-pro-page-container, .ant-card, .ant-table').first());
    await rec.end();

    await openList(page, '/office/bpm/definition');
    await rec.shot('01-3');
    await glide(page, page.locator('.ant-table-tbody, .ant-empty').first());
    await rec.next();
    await glide(page, page.locator('.ant-table').first());
    await rec.end();
  },

  '02': async ({ pages, rec }) => {
    const emp = pages.employee;
    await openList(emp, '/office/oa/leave');
    await rec.shot('02-1');
    await glide(emp, emp.locator('.ant-table-tbody, .ant-empty').first());
    await rec.next();
    await glide(emp, emp.locator('.ant-table').first());
    await rec.end();

    const page = pages.admin;
    await openList(page, '/office/bpm/todo');
    await rec.shot('02-2');
    await glide(page, page.locator('.ant-table-tbody, .ant-empty').first());
    await rec.next();
    await glide(page, page.locator('.ant-table').first());
    await rec.end();

    await rec.shot('02-3');
    await glide(page, page.locator('.ant-table').first());
    await rec.next();
    await glide(page, page.getByText('待办', { exact: false }).first().or(page.locator('.ant-table-tbody, .ant-empty').first()));
    await rec.end();

    await openList(emp, '/office/bpm/done');
    await rec.shot('02-4');
    await glide(emp, emp.locator('.ant-table-tbody, .ant-empty').first());
    await rec.next();
    await glide(emp, emp.locator('.ant-table').first());
    await rec.end();

    await openList(emp, '/office/bpm/copy');
    await rec.shot('02-5');
    await glide(emp, emp.locator('.ant-table-tbody, .ant-empty').first());
    await rec.next();
    await glide(emp, emp.locator('.ant-table').first());
    await rec.end();
  },

  '03': async ({ pages, rec }) => {
    const emp = pages.employee;
    await openList(emp, '/office/oa/reimburse');
    await rec.shot('03-1');
    await glide(emp, emp.locator('.ant-table-tbody, .ant-empty').first());
    await rec.next();
    await glide(emp, emp.locator('.ant-table').first());
    await rec.end();

    const page = pages.admin;
    await openList(page, '/office/bpm/todo');
    await rec.shot('03-2');
    await glide(page, page.locator('.ant-table-tbody, .ant-empty').first());
    await rec.next();
    await glide(page, page.locator('.ant-table').first());
    await rec.end();
  },

  '04': async ({ pages, rec }) => {
    const page = pages.admin;
    await page.goto('/office/oa/calendar');
    await page.waitForLoadState('networkidle');
    await settle(page);
    await rec.shot('04-1');
    await glide(page, page.locator('.ant-fullcalendar, .ant-table, .ant-card').first());
    await rec.next();
    await glide(page, page.locator('main, .ant-pro-page-container').first());
    await rec.end();

    await openList(page, '/office/oa/meeting');
    await rec.shot('04-2');
    await glide(page, page.locator('.ant-table-tbody, .ant-empty').first());
    await rec.next();
    await glide(page, page.locator('.ant-table').first());
    await rec.end();
  },

  '05': async ({ pages, rec }) => {
    const page = pages.admin;
    await openList(page, '/office/hrm/employee');
    await rec.shot('05-1');
    await glide(page, page.locator('.ant-table-tbody, .ant-empty').first());
    await rec.next();
    await glide(page, page.locator('.ant-table').first());
    await rec.end();

    await openList(page, '/office/crm/customer');
    await rec.shot('05-2');
    await glide(page, page.locator('.ant-table-tbody, .ant-empty').first());
    await rec.next();
    await glide(page, page.locator('.ant-table').first());
    await rec.end();
  },

  '06': async ({ pages, rec }) => {
    const page = pages.admin;
    await page.goto('/office/report');
    await page.waitForLoadState('networkidle');
    await settle(page);
    await rec.shot('06-1');
    await glide(page, page.locator('.ant-table, .ant-card, .ant-statistic').first());
    await rec.next();
    await glide(page, page.locator('main, .ant-pro-page-container').first());
    await rec.end();
  },
};

test.describe('BPM 与办公一体化演示视频录屏', () => {
  test.setTimeout(3600000);

  test('流程引擎 · 请假 / 报销 / OA / HRM / CRM / 报表 (录屏 + 时间轴)', async ({ browser }) => {
    fs.rmSync(RAW, { recursive: true, force: true });
    fs.mkdirSync(RAW, { recursive: true });
    const recorded = async () => {
      const context = await browser.newContext({ baseURL: BASE_URL, viewport: VIEWPORT, recordVideo: { dir: RAW, size: VIEWPORT } });
      await context.addInitScript(installCursor);
      await context.addInitScript(installClock);
      const page = await context.newPage();
      return { context, page, t0: Date.now() };
    };

    // 准备 (不录像): 建员工账号 + 真实命令链落办公数据.
    const prepCtx = await browser.newContext({ baseURL: BASE_URL, viewport: VIEWPORT });
    const prep = await prepCtx.newPage();
    await login(prep);
    const st = await prepare(prep, Date.now().toString(36).slice(-4));
    Object.assign(st, await seedOffice(prep));
    await prepCtx.close();

    // 员工自助发起 (独立上下文, 不共享管理员登录态): 真实入审批流.
    const empPrepCtx = await browser.newContext({ baseURL: BASE_URL, viewport: VIEWPORT });
    const empPrep = await empPrepCtx.newPage();
    await login(empPrep, st.empName, PASSWORD);
    Object.assign(st, await seedEmployeeFlow(empPrep, st));
    await empPrepCtx.close();

    const creds = { admin: ['admin', 'admin123'], employee: [st.empName, PASSWORD] };
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
