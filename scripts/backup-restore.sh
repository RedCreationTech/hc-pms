#!/usr/bin/env bash
# HC-PMS 备份与恢复 (H20 上线切换演练用)
#
# 为什么需要这个脚本: 应用有**三个独立的数据存储**, 缺一不可, 且其中两个在运行时
# 直接复制会得到损坏副本:
#   1. 业务库          SQLite rouyi.db 或 MySQL 库 (JDBC_URL)
#   2. Flowable 引擎库 H2 文件 flowable.mv.db (FLOWABLE_JDBC_URL), 或同上的 MySQL 库
#   3. PMS 证据文件    PMS_FILE_DIR (默认 data/pms-files), 内容寻址, 文件名即 SHA256
#
# 硬约束:
#   - SQLite 与 H2 文件都**必须停服后复制**. 运行中复制可能抓到不一致快照或损坏文件.
#   - 本脚本默认拒绝在服务运行时备份/恢复 (见 --force 例外说明).
#
# 用法:
#   ./scripts/backup-restore.sh backup [--out DIR] [--force]
#   ./scripts/backup-restore.sh restore DIR [--force]
#   ./scripts/backup-restore.sh verify DIR
#   ./scripts/backup-restore.sh list [--out DIR]
#
# 退出码: 0 成功; 1 参数/环境错误; 2 服务在运行而本次操作不安全; 3 校验失败.
set -euo pipefail

cd "$(dirname "$0")/.."
PROJECT_DIR="$(pwd)"

BACKUP_ROOT="${BACKUP_DIR:-$PROJECT_DIR/backups}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:${PORT:-3000}/api/health}"

# 与 resources/system.edn 一致的默认值, 可用同名环境变量覆盖
JDBC_URL="${JDBC_URL:-jdbc:sqlite:rouyi.db}"
FLOWABLE_JDBC_URL="${FLOWABLE_JDBC_URL:-jdbc:h2:file:./flowable}"
PMS_FILE_DIR="${PMS_FILE_DIR:-data/pms-files}"

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"

say()  { printf '%s\n' "$*"; }
# ok/warn 末尾显式 return 0: 调用方在条件里时它们返回非 0 会让 set -e 误判为失败
ok()   { printf '  ok   %s\n' "$*"; return 0; }
warn() { printf '  warn %s\n' "$*" >&2; return 0; }
# die MESSAGE [CODE]: 只打印第一参数, 状态码不拼进消息
die()  { printf '  FAIL %s\n' "$1" >&2; exit "${2:-1}"; }

usage() {
  sed -n '2,28p' "$0" | sed 's/^# \{0,1\}//'
  exit 1
}

# ── 运行状态探测 ────────────────────────────────────────────────────────────

service_running() {
  # 1) HTTP 健康检查能通, 2) 或本项目目录下有 java 进程, 二者任一即视为运行中
  if curl -fsS -m 2 -o /dev/null "$HEALTH_URL" 2>/dev/null; then
    return 0
  fi
  local pid
  for pid in $(lsof -tiTCP:"${PORT:-3000}" -sTCP:LISTEN 2>/dev/null || true); do
    if [[ "$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -1)" == "$PROJECT_DIR"* ]]; then
      return 0
    fi
  done
  return 1
}

require_stopped() {
  local mode="$1"
  if service_running; then
    # 必须单行: bash 3.2 在多字节字符紧邻跨行 ${var} 时会错切变量名
    die "服务正在运行 (健康检查 ${HEALTH_URL} 可达或端口 ${PORT:-3000} 被本项目进程占用); SQLite/H2 文件库运行中复制会得到不一致或损坏的快照, 请先 ./stop_dev.sh 再 ${mode}" 2
  fi
  ok "服务已停止 (可安全复制文件库)"
}

# ── 存储类型判定 ────────────────────────────────────────────────────────────

is_mysql() { [[ "$1" == jdbc:mysql://* ]]; }
is_h2file() { [[ "$1" == jdbc:h2:file:* ]]; }

# jdbc:h2:file:./flowable -> ./flowable.mv.db
h2_base_path() {
  local p="${1#jdbc:h2:file:}"
  p="${p%%;*}"
  printf '%s\n' "$p"
}

# jdbc:sqlite:rouyi.db -> rouyi.db
sqlite_path() { printf '%s\n' "${1#jdbc:sqlite:}"; }

# jdbc:mysql://user:pass@host:port/db?... -> db
mysql_db() {
  local p="${1#jdbc:mysql://}"
  p="${p%%\?*}"
  p="${p##*/}"
  printf '%s\n' "$p"
}

mysql_dump_cmd() {
  local db="$1"
  MYSQL_PWD="$MYSQL_PASSWORD" mysqldump \
    -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" \
    --single-transaction --routines --triggers --databases "$db"
}

mysql_import_cmd() {
  MYSQL_PWD="$MYSQL_PASSWORD" mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER"
}

# 备份里每个文件只有相对名, 统计大小时必须先 cd 进目录, 否则 du 会报 file not found
size_of() {
  ( cd "$1" && du -h "$2" 2>/dev/null | cut -f1 ) || echo "?"
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# ── 备份 ────────────────────────────────────────────────────────────────────

cmd_backup() {
  local out="" force=0
  local started ended db f fdb base main
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --out) out="$2"; shift 2 ;;
      --force) force=1; shift ;;
      *) die "未知参数: $1" ;;
    esac
  done
  [[ $force -eq 1 ]] || require_stopped backup

  out="${out:-$BACKUP_ROOT/$(date +%Y%m%d-%H%M%S)}"
  mkdir -p "$out"
  out="$(cd "$out" && pwd)"
  say "备份到 $out"

  started=$(date +%s)

  # 1. 业务库
  if is_mysql "$JDBC_URL"; then
    db="$(mysql_db "$JDBC_URL")"
    mysql_dump_cmd "$db" > "$out/business.sql"
    ok "业务库 MySQL $db -> business.sql ($(size_of "$out" business.sql))"
  else
    f="$(sqlite_path "$JDBC_URL")"
    [[ -f "$f" ]] || die "找不到业务库文件: $f"
    cp -p "$f" "$out/business.db"
    sqlite_consistent "$out/business.db" > "$out/business-integrity.txt" 2>&1 \
      || die "业务库一致性检查失败, 备份无效 (见 business-integrity.txt)" 3
    ok "业务库 SQLite $f -> business.db ($(size_of "$out" business.db), integrity_check ok)"
  fi

  # 2. Flowable 引擎库
  if is_mysql "$FLOWABLE_JDBC_URL"; then
    fdb="$(mysql_db "$FLOWABLE_JDBC_URL")"
    mysql_dump_cmd "$fdb" > "$out/flowable.sql"
    ok "引擎库 MySQL $fdb -> flowable.sql ($(size_of "$out" flowable.sql))"
  else
    base="$(h2_base_path "$FLOWABLE_JDBC_URL")"
    main="${base}.mv.db"
    [[ -f "$main" ]] || die "找不到引擎库文件: $main (H2 版本必须与运行库一致, 见 deployment.md 第 6.2 节)"
    cp -p "$main" "$out/flowable.mv.db"
    # trace 与 lock 文件不是数据, 但 trace 有助于排障, 存在就一并对齐
    if [[ -f "${base}.trace.db" ]]; then cp -p "${base}.trace.db" "$out/flowable.trace.db"; fi
    ok "引擎库 H2 $main -> flowable.mv.db ($(size_of "$out" flowable.mv.db))"
  fi

  # 3. PMS 证据文件 (内容寻址, 只增不改, 但必须与业务库同时点备份)
  if [[ -d "$PMS_FILE_DIR" ]]; then
    tar -czf "$out/pms-files.tgz" -C "$(dirname "$PMS_FILE_DIR")" "$(basename "$PMS_FILE_DIR")"
    ok "证据文件 $PMS_FILE_DIR -> pms-files.tgz ($(size_of "$out" pms-files.tgz))"
  else
    warn "证据文件目录不存在, 跳过: $PMS_FILE_DIR"
  fi

  ended=$(date +%s)

  # 4. 清单: 版本, 配置来源, 每个文件的 SHA256. 缺了清单就无法判断备份是否完整.
  {
    printf 'created_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'created_by=%s\n' "${USER:-unknown}"
    printf 'project_dir=%s\n' "$PROJECT_DIR"
    printf 'jdbc_url_kind=%s\n' "$(is_mysql "$JDBC_URL" && echo mysql || echo sqlite)"
    printf 'flowable_url_kind=%s\n' "$(is_mysql "$FLOWABLE_JDBC_URL" && echo mysql || echo ${FLOWABLE_JDBC_URL%%;*})"
    printf 'pms_file_dir=%s\n' "$PMS_FILE_DIR"
    printf 'elapsed_seconds=%s\n' "$((ended - started))"
    # JDBC 连接串可能含口令, 清单里只记库名与类型, 不记完整串
    printf 'business_db_name=%s\n' "$(is_mysql "$JDBC_URL" && mysql_db "$JDBC_URL" || sqlite_path "$JDBC_URL")"
  } > "$out/MANIFEST.txt"

  (
    cd "$out" || exit 1
    for f in business.db business.sql flowable.mv.db flowable.sql pms-files.tgz; do
      if [[ -f "$f" ]]; then printf '%s  %s\n' "$(sha256_of "$f")" "$f"; fi
    done
  ) > "$out/SHA256SUMS"
  ok "清单与校验和写入 MANIFEST.txt / SHA256SUMS"

  say ""
  say "备份完成, 用时 $((ended - started)) 秒"
  say "  目录: $out"
  say "  恢复: ./scripts/backup-restore.sh restore \"$out\""
}

sqlite_consistent() {
  command -v sqlite3 >/dev/null 2>&1 || { echo "sqlite3 不可用, 跳过一致性检查"; return 0; }
  # sqlite3 的 PRAGMA 输出带表头与行尾补齐空格 (ok 后面还有空格), 必须先取末行再 trim,
  # 否则 "ok" 永远匹配不上, 备份会被自己的一致性检查判死.
  local r
  r="$(sqlite3 "$1" 'PRAGMA integrity_check;' 2>&1 | tail -1 | sed 's/[[:space:]]*$//' || true)"
  printf '%s\n' "$r"
  [[ "$r" == "ok" ]]
}

# ── 校验 ────────────────────────────────────────────────────────────────────

cmd_verify() {
  local dir="${1:-}"
  local failed=0 sum="" name="" actual=""
  [[ -n "$dir" && -d "$dir" ]] || die "用法: verify DIR"
  say "校验 $dir"
  [[ -f "$dir/MANIFEST.txt" ]] || die "缺少 MANIFEST.txt, 这不是本脚本生成的备份" 3
  [[ -f "$dir/SHA256SUMS" ]] || die "缺少 SHA256SUMS" 3

  while read -r sum name; do
    [[ -n "${name:-}" ]] || continue
    if [[ ! -f "$dir/$name" ]]; then
      warn "清单里的文件不存在: $name"; failed=1; continue
    fi
    actual="$(sha256_of "$dir/$name")"
    if [[ "$actual" == "$sum" ]]; then ok "$name sha256 一致"
    else warn "$name sha256 不一致 (清单 $sum, 实际 $actual)"; failed=1; fi
  done < "$dir/SHA256SUMS"

  if [[ -f "$dir/business.db" ]]; then
    if sqlite_consistent "$dir/business.db" >/dev/null; then
      ok "business.db integrity_check ok"
    else
      warn "business.db integrity_check 失败"; failed=1
    fi
  fi

  sed 's/^/  /' "$dir/MANIFEST.txt"
  # 校验失败时返回 3, 但不要 exit: restore 里调用它, 需要保留 pre-restore 目录逻辑
  if [[ $failed -ne 0 ]]; then
    warn "校验未通过 (失败项见上)"
    VERIFY_FAILED=1
  else
    say "校验通过"
  fi
  return 0
}

# ── 恢复 ────────────────────────────────────────────────────────────────────

cmd_restore() {
  local dir="" force=0
  local started ended pre db f fdb base parent
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --force) force=1; shift ;;
      -*) die "未知参数: $1" ;;
      *) dir="$1"; shift ;;
    esac
  done
  [[ -n "$dir" && -d "$dir" ]] || die "用法: restore DIR [--force]"

  # 恢复前先证明这份备份是完整的, 不要把坏备份铺上去
  VERIFY_FAILED=0
  cmd_verify "$dir"
  if [[ ${VERIFY_FAILED:-0} -ne 0 ]]; then
    die "备份校验未通过, 拒绝恢复 (确认无误可先修文件再重试)" 3
  fi

  if [[ $force -eq 0 ]]; then
    require_stopped restore
  else
    warn "--force: 跳过运行状态检查, 由操作者负责已停服"
  fi

  started=$(date +%s)

  # 恢复前把当前数据另存一份, 恢复本身也可能是误操作
  pre="$BACKUP_ROOT/pre-restore-$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$pre"
  say "先把当前数据另存到 $pre (恢复也能回退)"
  if [[ -f "$dir/business.db" && -f "$(sqlite_path "$JDBC_URL")" ]]; then
    cp -p "$(sqlite_path "$JDBC_URL")" "$pre/business.db"
  fi
  if ! is_mysql "$FLOWABLE_JDBC_URL"; then
    base="$(h2_base_path "$FLOWABLE_JDBC_URL")"
    if [[ -f "${base}.mv.db" ]]; then cp -p "${base}.mv.db" "$pre/flowable.mv.db"; fi
  fi
  if [[ -d "$PMS_FILE_DIR" ]]; then
    tar -czf "$pre/pms-files.tgz" -C "$(dirname "$PMS_FILE_DIR")" "$(basename "$PMS_FILE_DIR")"
  fi

  # 1. 业务库
  if [[ -f "$dir/business.sql" ]]; then
    db="$(mysql_db "$JDBC_URL")"
    mysql_import_cmd < "$dir/business.sql"
    ok "业务库 MySQL $db 已导入 business.sql"
  elif [[ -f "$dir/business.db" ]]; then
    f="$(sqlite_path "$JDBC_URL")"
    cp -p "$dir/business.db" "$f"
    ok "业务库 SQLite $f 已覆盖"
  else
    die "备份里既没有 business.db 也没有 business.sql"
  fi

  # 2. 引擎库
  if [[ -f "$dir/flowable.sql" ]]; then
    fdb="$(mysql_db "$FLOWABLE_JDBC_URL")"
    mysql_import_cmd < "$dir/flowable.sql"
    ok "引擎库 MySQL $fdb 已导入 flowable.sql"
  elif [[ -f "$dir/flowable.mv.db" ]]; then
    base="$(h2_base_path "$FLOWABLE_JDBC_URL")"
    cp -p "$dir/flowable.mv.db" "${base}.mv.db"
    # 陈旧的 trace / lock 属于旧库, 留着会和恢复后的库对不上
    rm -f "${base}.trace.db" "${base}.lock.db"
    if [[ -f "$dir/flowable.trace.db" ]]; then cp -p "$dir/flowable.trace.db" "${base}.trace.db"; fi
    ok "引擎库 H2 ${base}.mv.db 已覆盖 (旧 trace/lock 已清理)"
  else
    die "备份里既没有 flowable.mv.db 也没有 flowable.sql"
  fi

  # 3. 证据文件
  if [[ -f "$dir/pms-files.tgz" ]]; then
    parent="$(dirname "$PMS_FILE_DIR")"
    mkdir -p "$parent"
    tar -xzf "$dir/pms-files.tgz" -C "$parent"
    ok "证据文件已解压到 $PMS_FILE_DIR"
  else
    warn "备份里没有 pms-files.tgz, 证据文件未恢复"
  fi

  ended=$(date +%s)
  say ""
  say "恢复完成, 用时 $((ended - started)) 秒"
  say "  启动后请立即 curl -s \$HEALTH_URL 并抽查业务数据, 确认后再对外发布"
  say "  恢复前的旧数据保留在 $pre"
}

# ── 列表 ────────────────────────────────────────────────────────────────────

cmd_list() {
  local out="$BACKUP_ROOT"
  local d created
  while [[ $# -gt 0 ]]; do
    case "$1" in --out) out="$2"; shift 2 ;; *) die "未知参数: $1" ;; esac
  done
  [[ -d "$out" ]] || { say "没有备份目录: $out"; return 0; }
  say "备份目录: $out"
  for d in $(ls -1dt "$out"/*/ 2>/dev/null); do
    created="$(grep -m1 '^created_at=' "$d/MANIFEST.txt" 2>/dev/null | cut -d= -f2- || true)"
    printf '  %-40s %s  %s\n' "$(basename "$d")" "$(du -sh "$d" | cut -f1)" "${created:-无清单}"
  done
}

main() {
  local cmd="${1:-}"; shift || true
  case "$cmd" in
    backup)  cmd_backup "$@" ;;
    restore) cmd_restore "$@" ;;
    verify)  cmd_verify "$@" ;;
    list)    cmd_list "$@" ;;
    *)       usage ;;
  esac
}

main "$@"