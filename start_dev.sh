#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"
PROJECT_DIR="$(pwd)"

echo "🚀 启动若依管理系统..."

pids_on_port() {
  lsof -tiTCP:"$1" -sTCP:LISTEN 2>/dev/null || true
}

pid_cwd() {
  lsof -a -p "$1" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -1
}

pid_command() {
  lsof -nP -p "$1" -Fnc 2>/dev/null | sed -n 's/^c//p' | head -1
}

kill_project_pids_on_port() {
  local port="$1"
  local label="$2"
  local pids
  pids="$(pids_on_port "$port")"

  if [ -z "$pids" ]; then
    return 0
  fi

  echo "⚠️  端口 $port ($label) 已被占用，检查是否为本项目旧进程..."
  local killed=0
  local pid
  for pid in $pids; do
    local cwd
    local command
    cwd="$(pid_cwd "$pid")"
    command="$(pid_command "$pid")"
    if [[ "$cwd" == "$PROJECT_DIR"* ]]; then
      echo "   终止本项目旧进程 PID $pid ($command)"
      kill -9 "$pid" 2>/dev/null || true
      killed=1
    else
      echo "   跳过外部进程 PID $pid ($command, cwd: ${cwd:-unknown})"
    fi
  done

  if [ "$killed" -eq 1 ]; then
    sleep 1
  fi
}

require_free_port() {
  local port="$1"
  local label="$2"
  local pids
  pids="$(pids_on_port "$port")"
  if [ -n "$pids" ]; then
    echo "❌ 端口 $port ($label) 仍被外部进程占用，请换端口或手动处理。"
    return 1
  fi
}

find_free_port() {
  local port="$1"
  while [ -n "$(pids_on_port "$port")" ]; do
    port=$((port + 1))
  done
  echo "$port"
}

HTTP_PORT="${PORT:-3000}"
NREPL_PORT="${NREPL_PORT:-7000}"
SHADOW_PORT=9631

# 检查端口是否已被占用。只清理当前项目目录下启动的旧进程，避免误杀系统或其它应用。
kill_project_pids_on_port "$HTTP_PORT" "HTTP"
require_free_port "$HTTP_PORT" "HTTP"

kill_project_pids_on_port "$NREPL_PORT" "nREPL"
if [ -n "$(pids_on_port "$NREPL_PORT")" ]; then
  OLD_NREPL_PORT="$NREPL_PORT"
  NREPL_PORT="$(find_free_port "$((NREPL_PORT + 1))")"
  export NREPL_PORT
  echo "ℹ️  nREPL 端口 $OLD_NREPL_PORT 被外部进程占用，改用 $NREPL_PORT"
else
  export NREPL_PORT
fi

kill_project_pids_on_port "$SHADOW_PORT" "shadow-cljs"
require_free_port "$SHADOW_PORT" "shadow-cljs"

mkdir -p logs

# 1. 启动后端
echo ""
echo "📦 启动后端 (Clojure, port $HTTP_PORT, nREPL port $NREPL_PORT)..."
echo "   JDBC_URL=${JDBC_URL:-jdbc:sqlite:rouyi.db}"
echo "   MIGRATION_DIR=${MIGRATION_DIR:-migrations-sqlite}"
rm -f rouyi.db
clojure -M:dev -m com.ruoyi.core > logs/backend.log 2>&1 &
BACKEND_PID=$!

# 等待后端就绪
echo "⏳ 等待后端就绪..."
for i in $(seq 1 30); do
  if curl -s "http://localhost:$HTTP_PORT/api/health" >/dev/null 2>&1; then
    echo "✅ 后端启动成功 (PID: $BACKEND_PID)"
    break
  fi
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "❌ 后端进程已退出，查看 logs/backend.log"
    tail -40 logs/backend.log
    exit 1
  fi
  if [ $i -eq 30 ]; then
    echo "❌ 后端启动超时，查看 logs/backend.log"
    tail -40 logs/backend.log
    exit 1
  fi
  sleep 1
done

# 2. 启动前端
echo ""
echo "📦 启动前端 (shadow-cljs watch)..."
# 清掉残留的 shadow-cljs server，避免 watch 连到旧实例导致 Stale Output
kill_project_pids_on_port "$SHADOW_PORT" "shadow-cljs"
require_free_port "$SHADOW_PORT" "shadow-cljs"
rm -f logs/frontend.log
pnpm exec shadow-cljs watch app > logs/frontend.log 2>&1 &
FRONTEND_PID=$!

# 等待首次编译完成
echo "⏳ 等待前端首次编译完成（首次编译约 1-3 分钟）..."
for i in $(seq 1 300); do
  if grep -qE "Build completed|build completed" logs/frontend.log 2>/dev/null; then
    echo "✅ 前端首次编译完成 (PID: $FRONTEND_PID)"
    break
  fi
  # 只匹配 shadow-cljs 真正的编译失败标志，避免误伤 SLF4J 之类的告警
  if grep -qE "^\[:app\] Build failure" logs/frontend.log 2>/dev/null; then
    echo "❌ 前端编译失败，查看 logs/frontend.log"
    tail -40 logs/frontend.log
    exit 1
  fi
  if [ $i -eq 300 ]; then
    echo "❌ 前端编译超时，查看 logs/frontend.log"
    tail -40 logs/frontend.log
    exit 1
  fi
  sleep 1
done
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  🌐 若依管理系统已启动"
echo "  📍 http://localhost:$HTTP_PORT"
echo "  👤 admin / admin123"
echo "  📡 nREPL: localhost:$NREPL_PORT"
echo "  📜 日志: logs/backend.log  logs/frontend.log"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "按 Ctrl+C 停止所有服务"

# 捕获退出信号，清理子进程
cleanup() {
  echo ""
  echo "🛑 正在停止服务..."
  kill $BACKEND_PID 2>/dev/null || true
  kill $FRONTEND_PID 2>/dev/null || true
  kill_project_pids_on_port "$HTTP_PORT" "HTTP"
  kill_project_pids_on_port "$NREPL_PORT" "nREPL"
  kill_project_pids_on_port "$SHADOW_PORT" "shadow-cljs"
  wait 2>/dev/null || true
  echo "👋 已停止"
}
trap cleanup EXIT INT TERM

while true; do
  if [ -z "$(pids_on_port "$HTTP_PORT")" ]; then
    echo "❌ 后端监听端口 $HTTP_PORT 已停止，退出启动脚本。"
    exit 1
  fi
  if [ -z "$(pids_on_port "$SHADOW_PORT")" ]; then
    echo "❌ shadow-cljs 监听端口 $SHADOW_PORT 已停止，退出启动脚本。"
    exit 1
  fi
  sleep 2
done
