#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"

echo "🚀 启动若依管理系统..."

# 检查端口是否已被占用
if lsof -ti :3000 >/dev/null 2>&1; then
  echo "⚠️  端口 3000 已被占用，尝试终止旧进程..."
  lsof -ti :3000 | xargs kill -9 2>/dev/null || true
  sleep 1
fi

if lsof -ti :7000 >/dev/null 2>&1; then
  echo "⚠️  端口 7000 已被占用，尝试终止旧进程..."
  lsof -ti :7000 | xargs kill -9 2>/dev/null || true
  sleep 1
fi

if lsof -ti :9630 >/dev/null 2>&1; then
  echo "⚠️  端口 9630 (shadow-cljs) 已被占用，尝试终止旧进程..."
  lsof -ti :9630 | xargs kill -9 2>/dev/null || true
  sleep 1
fi

mkdir -p logs

# 1. 启动后端
echo ""
echo "📦 启动后端 (Clojure, port 3000, nREPL port 7000)..."
rm -f rouyi.db
clojure -M:dev -m com.ruoyi.core > logs/backend.log 2>&1 &
BACKEND_PID=$!

# 等待后端就绪
echo "⏳ 等待后端就绪..."
for i in $(seq 1 30); do
  if curl -s http://localhost:3000/api/health >/dev/null 2>&1; then
    echo "✅ 后端启动成功 (PID: $BACKEND_PID)"
    break
  fi
  if [ $i -eq 30 ]; then
    echo "❌ 后端启动超时，查看 logs/backend.log"
    exit 1
  fi
  sleep 1
done

# 2. 启动前端
echo ""
echo "📦 启动前端 (shadow-cljs watch)..."
# 清掉残留的 shadow-cljs server，避免 watch 连到旧实例导致 Stale Output
lsof -ti :9630 2>/dev/null | xargs kill -9 2>/dev/null || true
rm -f logs/frontend.log
npx shadow-cljs watch app > logs/frontend.log 2>&1 &
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
echo "  📍 http://localhost:3000"
echo "  👤 admin / admin123"
echo "  📡 nREPL: localhost:7000"
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
  wait
  echo "👋 已停止"
}
trap cleanup EXIT INT TERM

wait
