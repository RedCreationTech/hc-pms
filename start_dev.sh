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
# shadow-cljs 默认监听 9630，被占用时自动向后顺延，实际端口启动后从日志解析
SHADOW_PORT=9630
NREPL_PORT_FILE="$PROJECT_DIR/.nrepl-port"
DEV_PID_FILE="$PROJECT_DIR/.dev-pids"
export PORT="$HTTP_PORT"

# 检查端口是否已被占用。只清理当前项目目录下启动的旧进程，避免误杀系统或其它应用。
kill_project_pids_on_port "$HTTP_PORT" "HTTP"
if [ -n "$(pids_on_port "$HTTP_PORT")" ]; then
  OLD_HTTP_PORT="$HTTP_PORT"
  HTTP_PORT="$(find_free_port "$((HTTP_PORT + 1))")"
  export PORT="$HTTP_PORT"
  echo "ℹ️  HTTP 端口 $OLD_HTTP_PORT 被外部进程占用，改用 $HTTP_PORT"
fi

kill_project_pids_on_port "$NREPL_PORT" "nREPL"
if [ -n "$(pids_on_port "$NREPL_PORT")" ]; then
  OLD_NREPL_PORT="$NREPL_PORT"
  NREPL_PORT="$(find_free_port "$((NREPL_PORT + 1))")"
  export NREPL_PORT
  echo "ℹ️  nREPL 端口 $OLD_NREPL_PORT 被外部进程占用，改用 $NREPL_PORT"
else
  export NREPL_PORT
fi
printf "%s\n" "$NREPL_PORT" > "$NREPL_PORT_FILE"

# shadow-cljs 默认从 9630 起监听，被占用会自动顺延；只清理本项目残留，不强制端口空闲
for sp in 9630 9631; do
  kill_project_pids_on_port "$sp" "shadow-cljs"
done

mkdir -p logs

# 1. 启动后端
echo ""
echo "📦 启动后端 (Clojure, port $HTTP_PORT, nREPL port $NREPL_PORT)..."
echo "   JDBC_URL=${JDBC_URL:-jdbc:sqlite:rouyi.db}"
echo "   MIGRATION_DIR=${MIGRATION_DIR:-migrations-sqlite}"
rm -f rouyi.db
clojure -M:dev -m com.ruoyi.core > logs/backend.log 2>&1 &
BACKEND_PID=$!

# 等待后端就绪（首次启动需下载 Maven 依赖，可能耗时数分钟）
echo "⏳ 等待后端就绪（首次启动需下载依赖，可能需要几分钟）..."
for i in $(seq 1 180); do
  if curl -s "http://localhost:$HTTP_PORT/api/health" >/dev/null 2>&1; then
    echo "✅ 后端启动成功 (PID: $BACKEND_PID)"
    break
  fi
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "❌ 后端进程已退出，查看 logs/backend.log"
    tail -40 logs/backend.log
    exit 1
  fi
  if [ $i -eq 180 ]; then
    echo "❌ 后端启动超时，查看 logs/backend.log"
    tail -40 logs/backend.log
    exit 1
  fi
  sleep 1
done

echo "⏳ 检查 nREPL..."
for i in $(seq 1 30); do
  if [ -n "$(pids_on_port "$NREPL_PORT")" ]; then
    echo "✅ nREPL 启动成功 (port: $NREPL_PORT)"
    break
  fi
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "❌ 后端进程已退出，nREPL 未启动，查看 logs/backend.log"
    tail -40 logs/backend.log
    exit 1
  fi
  if [ $i -eq 30 ]; then
    echo "❌ nREPL 启动超时，预期端口 $NREPL_PORT，查看 logs/backend.log"
    tail -40 logs/backend.log
    exit 1
  fi
  sleep 1
done

# 2. 启动前端
echo ""
echo "📦 启动前端 (shadow-cljs watch)..."
# 清掉残留的 shadow-cljs server，避免 watch 连到旧实例导致 Stale Output
# shadow-cljs 默认从 9630 起监听，被占用会自动顺延；只清理本项目残留，不强制端口空闲
for sp in 9630 9631; do
  kill_project_pids_on_port "$sp" "shadow-cljs"
done
rm -f logs/frontend.log
pnpm exec shadow-cljs watch app > logs/frontend.log 2>&1 &
FRONTEND_PID=$!

# 记录本次启动的进程与端口，供 stop_dev.sh 精确停止
cat > "$DEV_PID_FILE" <<EOF
BACKEND_PID=$BACKEND_PID
FRONTEND_PID=$FRONTEND_PID
HTTP_PORT=$HTTP_PORT
NREPL_PORT=$NREPL_PORT
SHADOW_PORT=$SHADOW_PORT
EOF

# 等待首次编译完成（首次运行 shadow-cljs 还需下载 ClojureScript 依赖，可能更久）
echo "⏳ 等待前端首次编译完成（首次编译约 1-3 分钟，首次运行下载依赖可能更久）..."
for i in $(seq 1 600); do
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
  if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
    echo "❌ 前端进程已退出，查看 logs/frontend.log"
    tail -40 logs/frontend.log
    exit 1
  fi
  if [ $i -eq 600 ]; then
    echo "❌ 前端编译超时，查看 logs/frontend.log"
    tail -40 logs/frontend.log
    exit 1
  fi
  sleep 1
done

# 从日志解析 shadow-cljs 实际监听的端口（默认 9630，被占用时自动顺延），并更新 .dev-pids
DETECTED_SHADOW_PORT="$(grep -oE 'running at http://localhost:[0-9]+' logs/frontend.log | tail -1 | grep -oE '[0-9]+$')"
if [ -n "$DETECTED_SHADOW_PORT" ] && [ "$DETECTED_SHADOW_PORT" != "$SHADOW_PORT" ]; then
  SHADOW_PORT="$DETECTED_SHADOW_PORT"
  sed -i '' "s/^SHADOW_PORT=.*/SHADOW_PORT=$SHADOW_PORT/" "$DEV_PID_FILE"
fi
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
  # 先终止子进程（如 clojure 启动器派生的 java），避免父进程被杀后子进程成为孤儿继续占用端口
  pkill -TERM -P $BACKEND_PID 2>/dev/null || true
  pkill -TERM -P $FRONTEND_PID 2>/dev/null || true
  kill $BACKEND_PID 2>/dev/null || true
  kill $FRONTEND_PID 2>/dev/null || true
  if [ -f "$NREPL_PORT_FILE" ] && [ "$(cat "$NREPL_PORT_FILE" 2>/dev/null)" = "$NREPL_PORT" ]; then
    rm -f "$NREPL_PORT_FILE"
  fi
  if [ -f "$DEV_PID_FILE" ] && grep -q "^BACKEND_PID=$BACKEND_PID$" "$DEV_PID_FILE" 2>/dev/null; then
    rm -f "$DEV_PID_FILE"
  fi
  kill_project_pids_on_port "$HTTP_PORT" "HTTP"
  kill_project_pids_on_port "$NREPL_PORT" "nREPL"
  for sp in "$SHADOW_PORT" 9630 9631; do
    kill_project_pids_on_port "$sp" "shadow-cljs"
  done
  wait 2>/dev/null || true
  echo "👋 已停止"
}
trap cleanup EXIT INT TERM

while true; do
  if [ -z "$(pids_on_port "$HTTP_PORT")" ]; then
    echo "❌ 后端监听端口 $HTTP_PORT 已停止，退出启动脚本。"
    exit 1
  fi
  if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
    echo "❌ 前端 shadow-cljs 进程已退出，退出启动脚本。"
    exit 1
  fi
  if [ -z "$(pids_on_port "$SHADOW_PORT")" ]; then
    echo "❌ shadow-cljs 监听端口 $SHADOW_PORT 已停止，退出启动脚本。"
    exit 1
  fi
  sleep 2
done
