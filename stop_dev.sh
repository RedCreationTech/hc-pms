#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"
PROJECT_DIR="$(pwd)"

echo "🛑 停止若依管理系统..."

DEV_PID_FILE="$PROJECT_DIR/.dev-pids"
NREPL_PORT_FILE="$PROJECT_DIR/.nrepl-port"

pids_on_port() {
  lsof -tiTCP:"$1" -sTCP:LISTEN 2>/dev/null || true
}

pid_cwd() {
  lsof -a -p "$1" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -1
}

pid_command() {
  lsof -nP -p "$1" -Fnc 2>/dev/null | sed -n 's/^c//p' | head -1
}

# PID 可能被系统回收复用，只终止 cwd 属于本项目目录的进程，避免误杀外部应用
is_project_pid() {
  local cwd
  cwd="$(pid_cwd "$1")"
  [[ "$cwd" == "$PROJECT_DIR"* ]]
}

# 优雅终止指定 PID：先杀子进程（如 clojure 派生的 java），再 TERM，超时后 KILL
stop_pid() {
  local pid="$1"
  local label="$2"

  if ! kill -0 "$pid" 2>/dev/null; then
    return 0
  fi
  if ! is_project_pid "$pid"; then
    echo "⚠️  跳过 PID $pid ($label)：不属于本项目 (cwd: $(pid_cwd "$pid"))"
    return 0
  fi

  echo "   终止 $label (PID $pid, $(pid_command "$pid"))"
  pkill -TERM -P "$pid" 2>/dev/null || true
  kill -TERM "$pid" 2>/dev/null || true

  for _ in $(seq 1 10); do
    kill -0 "$pid" 2>/dev/null || return 0
    sleep 1
  done
  echo "   $label 未在 10 秒内退出，强制终止 (PID $pid)"
  pkill -KILL -P "$pid" 2>/dev/null || true
  kill -9 "$pid" 2>/dev/null || true
}

# 按端口兜底清理（覆盖 .dev-pids 缺失、或进程已脱离父进程成为孤儿的情况）
stop_port() {
  local port="$1"
  local label="$2"
  local pid
  for pid in $(pids_on_port "$port"); do
    if is_project_pid "$pid"; then
      stop_pid "$pid" "$label (端口 $port)"
    else
      echo "⚠️  端口 $port ($label) 被外部进程 PID $pid ($(pid_command "$pid")) 占用，跳过"
    fi
  done
}

HTTP_PORT="${PORT:-3000}"
NREPL_PORT="${NREPL_PORT:-}"
SHADOW_PORT=9630

# 1. 优先按 start_dev.sh 留下的 .dev-pids 精确停止
if [ -f "$DEV_PID_FILE" ]; then
  echo "📄 发现 .dev-pids，按记录停止服务..."
  # 文件由 start_dev.sh 生成，内容为 KEY=VALUE（含 BACKEND_PID / FRONTEND_PID / 各端口）
  . "$DEV_PID_FILE"
  stop_pid "$FRONTEND_PID" "前端 (shadow-cljs watch)"
  stop_pid "$BACKEND_PID" "后端 (clojure)"
else
  echo "ℹ️  未找到 .dev-pids，按端口扫描停止..."
fi

# 2. 端口兜底（进程已脱离父进程、仍占用端口的孤儿）
[ -z "$NREPL_PORT" ] && [ -f "$NREPL_PORT_FILE" ] && NREPL_PORT="$(cat "$NREPL_PORT_FILE" 2>/dev/null)"
NREPL_PORT="${NREPL_PORT:-7000}"

stop_port "$HTTP_PORT" "HTTP"
stop_port "$NREPL_PORT" "nREPL"
# shadow-cljs 默认 9630，被占用时自动顺延，9630/9631 都扫一遍
for sp in "$SHADOW_PORT" 9630 9631; do
  stop_port "$sp" "shadow-cljs"
done

# 3. 清理不监听端口的 shadow-cljs watch node 进程
for pid in $(pgrep -f "shadow-cljs watch" 2>/dev/null || true); do
  if is_project_pid "$pid"; then
    stop_pid "$pid" "shadow-cljs watch"
  fi
done

# 4. 清理记录文件
rm -f "$DEV_PID_FILE" "$NREPL_PORT_FILE"

echo ""
echo "👋 若依管理系统已停止"
