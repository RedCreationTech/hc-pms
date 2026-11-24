#!/usr/bin/env node
// HC-PMS 数据迁移对账 (H20 上线切换用)
//
// 用途: 历史数据从源库 (通常是 SQLite) 迁到目标库 (通常是 MySQL) 之后, 用同一份
// 对账口径在两边各算一遍, 逐项比较, 不一致就非 0 退出。切换是否成功不以"服务能起来"
// 为准, 以本脚本的比对结果为准。
//
// 对账三层:
//   1. 结构层: 表清单、每表列集合、schema_migrations 已应用集合
//   2. 数据层: 每表行数 (:all 全表, 忽略 SQLite/MySQL 都有但语义为运行态的日志表)
//   3. 语义层: 关键业务聚合 (项目状态分布、审批流与水印、费用合计、成员数、事件数)
//      —— 行数相同不代表内容相同, 聚合能抓住"行数没变但值被截断/归一"的问题
//
// 用法:
//   node scripts/migration-reconcile.mjs --source sqlite:rouyi.db --target mysql://root:password@127.0.0.1:3308/ruoyi
//   node scripts/migration-reconcile.mjs --source ... --target ... --json reports/h20a/reconcile.json
//   node scripts/migration-reconcile.mjs --source sqlite:rouyi.db --self   # 单库自检 (只打印)
//
// 退出码: 0 全部一致; 1 存在不一致; 2 参数/连接错误。

import { execFileSync } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

// ── 参数 ────────────────────────────────────────────────────────────────────

const argv = process.argv.slice(2);
const arg = (name, fallback) => {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
};
const SOURCE = arg('--source', null);
const TARGET = arg('--target', null);
const JSON_OUT = arg('--json', null);
const SELF = argv.includes('--self');

if (!SOURCE) {
  console.error('缺少 --source (例如 sqlite:rouyi.db 或 mysql://user:pass@host:port/db)');
  process.exit(2);
}
if (!TARGET && !SELF) {
  console.error('缺少 --target, 或加 --self 只做单库自检');
  process.exit(2);
}

// ── 驱动: 统一成 query(sql) -> 行数组, 每行是字段名到字符串的对象 ─────────────

function sqliteDriver(spec) {
  const file = spec.replace(/^sqlite:/, '');
  return {
    name: `sqlite:${file}`,
    query(sql) {
      // -json 让结果自带字段名; -readonly 防止对账脚本误改源库
      const out = execFileSync('sqlite3', ['-readonly', '-json', file, sql], {
        encoding: 'utf8', maxBuffer: 64 * 1024 * 1024,
      });
      const text = out.trim();
      return text ? JSON.parse(text) : [];
    },
  };
}

function mysqlDriver(spec) {
  const m = /^mysql:\/\/(?:([^:@]*)(?::([^@]*))?@)?([^:/]+)(?::(\d+))?\/([^?]+)/.exec(spec);
  if (!m) throw new Error(`无法解析 MySQL 连接串: ${spec}`);
  const [, user = 'root', pass = '', host = '127.0.0.1', port = '3306', db] = m;
  const baseArgs = ['-h', host, '-P', port, '-u', user, '--batch', '--raw', '-D', db];
  return {
    name: `mysql:${db}@${host}:${port}`,
    query(sql) {
      // --batch 输出制表符分隔且不转义, 用 -N 去表头; 只读会话防止误改目标库
      const out = execFileSync('mysql', [...baseArgs, '-N', '-e', `SET SESSION TRANSACTION READ ONLY; ${sql}`], {
        encoding: 'utf8', maxBuffer: 64 * 1024 * 1024,
        env: { ...process.env, MYSQL_PWD: pass },
      });
      const lines = out.split('\n').filter((l) => l.length);
      return lines.map((line) => {
        const cells = line.split('\t');
        const row = {};
        // 对账语句自己产出 "k, v" 两列, 由调用方决定键名, 这里用统一列名
        cells.forEach((c, i) => { row[i === 0 ? 'k' : 'v'] = c; });
        return row;
      });
    },
  };
}

const makeDriver = (spec) => (spec.startsWith('sqlite:') ? sqliteDriver(spec) : mysqlDriver(spec));

// 单值查询包装: 取第一行第一个单元格
const scalar = (drv, sql) => {
  const rows = drv.query(sql);
  if (!rows.length) return null;
  const first = rows[0];
  return first.k !== undefined ? first.k : Object.values(first)[0];
};

// ── 对账项 ──────────────────────────────────────────────────────────────────

// 不参与行数对账的表: 只是运行态/日志, 迁移后允许不同
const RUNTIME_TABLES = new Set([
  'sys_online',       // 在线会话 (重启即空)
  'sys_login_log',    // 登录日志 (迁移期间的新登录不该算差异)
  'sys_oper_log',     // 操作日志 (同上)
  'sys_job_log',      // 定时任务日志
  'sys_token_revoke', // 令牌撤销表 (重启后无意义)
  'schema_migrations', // 已在结构层按集合严格比对, 这里不重复计一次行数差
]);

// 语义层聚合: 每项给出 sqlite 与 mysql 两份等价 SQL, 都产出 "k, v" 两列。
//
// 为什么要两份而不是一份 (实测踩坑): SQLite 原生只有 `||`, 没有 CONCAT;
// MySQL 默认 sql_mode 下 `||` 是**逻辑或**而不是字符串拼接 (缺 PIPES_AS_CONCAT),
// 于是 `'instance=' || status` 在 MySQL 上会被算成 0/1 -- 脚本不报错, 只是静默
// 给出错误键, 对账结果看起来"不一致"其实是口径错了。这类差异必须显式写两份。
const SEMANTIC = {
  '项目状态分布': {
    sqlite: `SELECT 'status=' || status AS k, COUNT(*) AS v FROM pms_project GROUP BY status ORDER BY status`,
    mysql: `SELECT CONCAT('status=', status) AS k, COUNT(*) AS v FROM pms_project GROUP BY status ORDER BY status`,
  },
  '项目总数与非空项目编号': {
    sqlite: `SELECT 'projects' AS k, COUNT(*) AS v FROM pms_project
      UNION ALL SELECT 'distinct_no', COUNT(DISTINCT project_no) FROM pms_project`,
    mysql: `SELECT 'projects' AS k, COUNT(*) AS v FROM pms_project
      UNION ALL SELECT 'distinct_no', COUNT(DISTINCT project_no) FROM pms_project`,
  },
  // 列名对照真实 schema: pms_approval_flow 用 current_level, 不是 current_step
  '审批流状态与当前层级': {
    sqlite: `SELECT 'flow=' || status || ',level=' || current_level AS k, COUNT(*) AS v
      FROM pms_approval_flow GROUP BY status, current_level ORDER BY status, current_level`,
    mysql: `SELECT CONCAT('flow=', status, ',level=', current_level) AS k, COUNT(*) AS v
      FROM pms_approval_flow GROUP BY status, current_level ORDER BY status, current_level`,
  },
  '审批步骤状态': {
    sqlite: `SELECT 'step=' || status AS k, COUNT(*) AS v FROM pms_approval_step GROUP BY status ORDER BY status`,
    mysql: `SELECT CONCAT('step=', status) AS k, COUNT(*) AS v FROM pms_approval_step GROUP BY status ORDER BY status`,
  },
  '费用版本状态与种类': {
    sqlite: `SELECT 'ver=' || status || ',kind=' || kind AS k, COUNT(*) AS v
      FROM pms_cost_version GROUP BY status, kind ORDER BY status, kind`,
    mysql: `SELECT CONCAT('ver=', status, ',kind=', kind) AS k, COUNT(*) AS v
      FROM pms_cost_version GROUP BY status, kind ORDER BY status, kind`,
  },
  '项目成员去重人数': {
    sqlite: `SELECT 'member_rows' AS k, COUNT(*) AS v FROM pms_member
      UNION ALL SELECT 'distinct_users', COUNT(DISTINCT user_id) FROM pms_member`,
    mysql: `SELECT 'member_rows' AS k, COUNT(*) AS v FROM pms_member
      UNION ALL SELECT 'distinct_users', COUNT(DISTINCT user_id) FROM pms_member`,
  },
  'BPM 实例状态': {
    sqlite: `SELECT 'instance=' || status AS k, COUNT(*) AS v FROM biz_bpm_instance GROUP BY status ORDER BY status`,
    mysql: `SELECT CONCAT('instance=', status) AS k, COUNT(*) AS v FROM biz_bpm_instance GROUP BY status ORDER BY status`,
  },
  'BPM 模型与实例与抄送': {
    sqlite: `SELECT 'models' AS k, COUNT(*) AS v FROM biz_bpm_model
      UNION ALL SELECT 'instances', COUNT(*) FROM biz_bpm_instance
      UNION ALL SELECT 'copies', COUNT(*) FROM biz_bpm_copy`,
    mysql: `SELECT 'models' AS k, COUNT(*) AS v FROM biz_bpm_model
      UNION ALL SELECT 'instances', COUNT(*) FROM biz_bpm_instance
      UNION ALL SELECT 'copies', COUNT(*) FROM biz_bpm_copy`,
  },
  '菜单与角色权限行数': {
    sqlite: `SELECT 'menus' AS k, COUNT(*) AS v FROM sys_menu
      UNION ALL SELECT 'role_menu', COUNT(*) FROM sys_role_menu
      UNION ALL SELECT 'users', COUNT(*) FROM sys_user`,
    mysql: `SELECT 'menus' AS k, COUNT(*) AS v FROM sys_menu
      UNION ALL SELECT 'role_menu', COUNT(*) FROM sys_role_menu
      UNION ALL SELECT 'users', COUNT(*) FROM sys_user`,
  },
};

// 按驱动挑对应方言的那份 SQL
const semanticSql = (spec, drv) => (isSqlite(drv) ? spec.sqlite : spec.mysql);

// ── 执行 ────────────────────────────────────────────────────────────────────

const src = makeDriver(SOURCE);
// --self 单库自检: target 回落到 source, 这样结构/行数/语义三段代码路径与真实对账完全一致,
// 只是比较的两边同源, 用来验证脚本自身不会产生假差异
const tgt = makeDriver(TARGET || SOURCE);
const selfMode = !TARGET;

const report = { source: src.name, target: selfMode ? null : tgt.name, generated_at: new Date().toISOString(), sections: [], diffs: [] };

const record = (section, key, a, b, note) => {
  const same = String(a) === String(b);
  report.sections.push({ section, key, source: a, target: b, same, note: note || null });
  if (!same) report.diffs.push({ section, key, source: a, target: b, note: note || null });
  return same;
};

// 统一用 "AS k" 取第一列: sqlite 驱动走 -json 带真实列名, mysql 驱动走 -N 只有位置
function tableList(drv) {
  const rows = drv.query(
    `SELECT name AS k FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name`,
  );
  return rows.map((r) => r.k);
}

function tableListMysql(drv) {
  const rows = drv.query(`SELECT table_name AS k FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name`);
  return rows.map((r) => r.k);
}

function isSqlite(drv) { return drv.name.startsWith('sqlite:'); }

const listTables = (drv) => (isSqlite(drv) ? tableList(drv) : tableListMysql(drv));

function columnsOf(drv, table) {
  if (isSqlite(drv)) {
    return drv.query(`SELECT name AS k FROM pragma_table_info('${table}')`).map((r) => r.k).sort();
  }
  return drv.query(
    `SELECT column_name AS k FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = '${table}'`,
  ).map((r) => r.k).sort();
}

// 1. 结构层
const srcTables = listTables(src);
const tgtTables = listTables(tgt);
record('结构.表清单', '表数量', srcTables.length, tgtTables.length);
for (const t of srcTables) record('结构.表清单', `存在:${t}`, 'yes', tgtTables.includes(t) ? 'yes' : 'no');
for (const t of tgtTables) if (!srcTables.includes(t)) record('结构.表清单', `目标独有:${t}`, 'no', 'yes');

for (const t of srcTables) {
  if (!tgtTables.includes(t)) continue;
  const a = columnsOf(src, t).join(',');
  const b = columnsOf(tgt, t).join(',');
  record('结构.列集合', t, a, b);
}

// schema_migrations 已应用集合 (迁移完整性的直接证据)
// 必须统一转字符串: sqlite3 -json 会把纯数字 id 解析成 JS number, 而 mysql -N 只给文本,
// 不转的话 Set.has / includes 永远为 false, 会造出"目标缺失全部迁移"这种假差异。
const migRows = (drv) => {
  try {
    return drv.query(`SELECT id AS k FROM schema_migrations ORDER BY id`).map((r) => String(r.k));
  } catch { return ['<读取失败>']; }
};
const srcMig = migRows(src);
const tgtMig = migRows(tgt);
record('结构.迁移', '已应用迁移数', srcMig.length, tgtMig.length);
const missing = srcMig.filter((m) => !tgtMig.includes(m));
record('结构.迁移', '目标缺失的迁移', 'none', missing.length ? missing.join(',') : 'none');

// 2. 数据层
for (const t of srcTables) {
  if (!tgtTables.includes(t)) continue;
  const a = scalar(src, `SELECT COUNT(*) AS c FROM ${t}`);
  const b = scalar(tgt, `SELECT COUNT(*) AS c FROM ${t}`);
  const runtime = RUNTIME_TABLES.has(t);
  if (runtime) {
    // 运行态表只记录不判失败
    report.sections.push({ section: '数据.行数(运行态, 不计入判定)', key: t, source: a, target: b, same: String(a) === String(b) });
  } else {
    record('数据.行数', t, a, b);
  }
}

// 3. 语义层
for (const [label, spec] of Object.entries(SEMANTIC)) {
  let aRows;
  let bRows;
  try {
    aRows = src.query(semanticSql(spec, src));
  } catch (e) {
    record('语义.' + label, '<查询失败>', 'ok', 'fail:' + e.message.slice(0, 80));
    continue;
  }
  bRows = tgt.query(semanticSql(spec, tgt));
  const toMap = (rows) => {
    const m = new Map();
    for (const r of rows) m.set(String(r.k), String(r.v ?? ''));
    return m;
  };
  const am = toMap(aRows);
  const bm = toMap(bRows);
  const keys = [...new Set([...am.keys(), ...bm.keys()])].sort();
  for (const k of keys) record('语义.' + label, k, am.get(k) ?? '<缺失>', bm.get(k) ?? '<缺失>');
}

// ── 输出 ────────────────────────────────────────────────────────────────────

const pad = (s, n) => String(s).padEnd(n).slice(0, n);
const sections = new Map();
for (const s of report.sections) {
  if (!sections.has(s.section)) sections.set(s.section, []);
  sections.get(s.section).push(s);
}

if (!JSON_OUT) {
  for (const [section, rows] of sections) {
    const bad = rows.filter((r) => !r.same);
    console.log(`\n## ${section}  (${rows.length} 项, 不一致 ${bad.length})`);
    for (const r of bad) console.log(`  ✖ ${r.key}: 源=${r.source} 目标=${r.target}`);
    if (!bad.length && !section.includes('运行态')) console.log('  ✔ 全部一致');
  }
}

if (JSON_OUT) {
  mkdirSync(dirname(JSON_OUT), { recursive: true });
  writeFileSync(JSON_OUT, JSON.stringify(report, null, 2));
  console.log(`\n对账明细已写入 ${JSON_OUT}`);
}

const total = report.sections.filter((s) => !s.section.includes('运行态')).length;
console.log(`\n源: ${src.name}`);
console.log(`目标: ${selfMode ? '(self 自检)' : tgt.name}`);
console.log(`对账项 ${total} 项, 不一致 ${report.diffs.length} 项`);

if (report.diffs.length) {
  console.log('\n不一致项:');
  for (const d of report.diffs.slice(0, 40)) console.log(`  - [${d.section}] ${d.key}: 源=${d.source} 目标=${d.target}`);
  if (report.diffs.length > 40) console.log(`  ... 另有 ${report.diffs.length - 40} 项, 见 JSON 明细`);
  process.exit(1);
}
console.log('\n对账通过: 源与目标在结构 / 行数 / 语义三个层面完全一致');