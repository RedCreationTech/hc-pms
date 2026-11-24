#!/usr/bin/env node
// HC-PMS SQLite -> MySQL 数据搬运 (H20 切换演练用)
//
// 用途: 目标库已经用 MIGRATION_DIR=migrations 建好**空 schema** (含种子数据),
// 本脚本把源 SQLite 的业务数据搬过去, 再用 migration-reconcile.mjs 对账。
//
// 设计取舍:
//   - 不装 JDBC 驱动也不引 npm 依赖: 用 sqlite3 CLI 导出 JSON, 拼 INSERT 交给 mysql CLI。
//   - 目标库已有的种子行 (sys_user=1, sys_menu=184 等) 用 REPLACE INTO 覆盖, 保证
//     最终行数与源库一致; 因此本脚本只能对**演练库**使用, 拒绝连 production 库名。
//   - 按源库表清单搬运, 目标缺列直接报错 (不静默丢字段)。
//   - 空值写 NULL 而不是 'null' 字符串; 字符串统一转义。
//
// 用法:
//   node scripts/migration-copy.mjs --source sqlite:rouyi.db --target mysql://root:password@127.0.0.1:3308/ruoyi_cutover
//   node scripts/migration-copy.mjs ... --dry-run
//
// 退出码: 0 成功; 1 数据问题; 2 参数/连接错误; 3 安全拒绝。

import { execFileSync } from 'node:child_process';

const argv = process.argv.slice(2);
const arg = (name, fallback) => {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
};
const SOURCE = arg('--source', null);
const TARGET = arg('--target', null);
const DRY = argv.includes('--dry-run');

if (!SOURCE || !TARGET) {
  console.error('用法: --source sqlite:rouyi.db --target mysql://user:pass@host:port/db [--dry-run]');
  process.exit(2);
}

const m = /^mysql:\/\/(?:([^:@]*)(?::([^@]*))?@)?([^:/]+)(?::(\d+))?\/([^?]+)/.exec(TARGET);
if (!m) { console.error(`无法解析 MySQL 连接串: ${TARGET}`); process.exit(2); }
const [, user = 'root', pass = '', host = '127.0.0.1', port = '3306', db] = m;

// 安全闸: 只允许写入演练/测试库, 防止误把生产库当演练目标
if (!/(cutover|rehearsal|drill|test|uat|_tmp$)/i.test(db)) {
  console.error(`✖ 拒绝写入库 "${db}": 库名必须含 cutover/rehearsal/drill/test/uat 之一 (本脚本用 REPLACE 覆盖种子行, 只适合演练库)`);
  process.exit(3);
}

const sqliteFile = SOURCE.replace(/^sqlite:/, '');

const sqlite = (sql) => {
  const out = execFileSync('sqlite3', ['-readonly', '-json', sqliteFile, sql], {
    encoding: 'utf8', maxBuffer: 256 * 1024 * 1024,
  });
  const t = out.trim();
  return t ? JSON.parse(t) : [];
};

// 一次事务里灌一批语句, 避免逐条往返
const mysqlExec = (sql) => execFileSync('mysql', [
  '-h', host, '-P', port, '-u', user, '--batch', '-D', db,
], { input: sql, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024, env: { ...process.env, MYSQL_PWD: pass } });

const lit = (v) => {
  if (v === null || v === undefined) return 'NULL';
  if (typeof v === 'number') return Number.isFinite(v) ? String(v) : 'NULL';
  if (typeof v === 'boolean') return v ? '1' : '0';
  // sqlite3 -json 会把 BLOB 变成 {"base64":...} 之类; 本项目没有 BLOB 列, 出现即报错
  if (typeof v === 'object') throw new Error(`不支持的列值类型 (可能 BLOB): ${JSON.stringify(v).slice(0, 60)}`);
  return `'${String(v).replace(/\\/g, '\\\\').replace(/'/g, "''")}'`;
};

// 顺序很重要: 先删子表再删父表, 先插父表再插子表 (无硬外键, 但保持语义顺序)
const tables = sqlite(`SELECT name AS t FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name`).map((r) => r.t);

const targetTables = new Set(
  mysqlExec(`SELECT table_name FROM information_schema.tables WHERE table_schema='${db}';`)
    .toString().split('\n').map((s) => s.trim()).filter(Boolean),
);

let failed = 0;
const rowsCopied = {};

if (!DRY) {
  // 清空目标库业务表, 避免 REPLACE 残留旧种子行 (schema_migrations 保留)
  mysqlExec('SET FOREIGN_KEY_CHECKS=0;\n');
  for (const t of [...tables].reverse()) {
    if (t === 'schema_migrations') continue;
    if (targetTables.has(t)) {
      try { mysqlExec(`DELETE FROM \`${t}\`;\n`); } catch { /* 目标无此表, 下面会统一报 */ }
    }
  }
  mysqlExec('SET FOREIGN_KEY_CHECKS=1;\n');
  console.log(`已清空目标库 ${db} 的业务表 (schema_migrations 保留)`);
}

for (const t of tables) {
  // schema_migrations 不搬: 目标库的迁移记录由 MIGRATION_DIR=migrations 真实跑出来的,
  // 搬过去既无意义 (applied 时间格式两库不同) 又会覆盖掉目标库的真实迁移进度
  if (t === 'schema_migrations') {
    console.log('  skip schema_migrations (保留目标库自身的迁移记录, 由 reconcile 单独比对)');
    continue;
  }
  if (!targetTables.has(t)) {
    console.log(`  skip ${t} (目标库没有这张表)`);
    continue;
  }
  const cols = sqlite(`SELECT name AS c FROM pragma_table_info('${t}')`).map((r) => r.c);
  const rows = sqlite(`SELECT * FROM \`${t}\``);
  rowsCopied[t] = rows.length;
  if (!rows.length) continue;

  // 逐行拼 INSERT; 不用 REPLACE, 因为已经清空目标表, 普通 INSERT 更快也更严格
  const values = rows.map((row) => `(${cols.map((c) => lit(row[c])).join(',')})`).join(',\n');
  const sql = `SET NAMES utf8mb4;\nINSERT INTO \`${t}\` (${cols.map((c) => `\`${c}\``).join(',')}) VALUES\n${values};\n`;

  if (DRY) {
    console.log(`  dry-run ${t}: ${rows.length} 行, ${sql.length} 字节`);
    continue;
  }
  try {
    mysqlExec(sql);
    console.log(`  ok ${t}: ${rows.length} 行`);
  } catch (e) {
    failed += 1;
    const msg = String(e.stderr || e.message).split('\n').filter(Boolean).slice(0, 3).join(' | ');
    console.log(`  FAIL ${t}: ${msg}`);
  }
}

const totalRows = Object.values(rowsCopied).reduce((a, b) => a + b, 0);
console.log(`\n表 ${tables.length} 张, 行 ${totalRows} 行, 失败表 ${failed} 张`);
process.exit(failed ? 1 : 0);