#!/bin/bash
# 可移植一键启动：编译 + 可选服务自检 + 后端无链模式启动。
# 用法：
#   bash scripts/dev/start.sh                # 仅启动后端（dev profile，无链）
#   bash scripts/dev/start.sh --build        # 启动前先编译
#   bash scripts/dev/start.sh --tests        # 编译后先跑全量测试
# 环境变量：JAVA_HOME（须 JDK21）、ROOT（仓库根，默认脚本上两级）、PROFILE（默认 dev）
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="${ROOT:-$(cd "$HERE/../.." && pwd)}"
PROFILE="${PROFILE:-dev}"
LOG="${ROOT}/scripts/dev/backend.log"
BUILD=0; TESTS=0
for a in "$@"; do
  case "$a" in --build) BUILD=1;; --tests) TESTS=1;; *) echo "未知参数: $a"; exit 1;; esac
done

if [ -n "${JAVA_HOME:-}" ]; then PATH="$JAVA_HOME/bin:$PATH"; fi
java -version 2>&1 | head -1 | grep -q '21' || echo "[WARN] 需要 JDK21，enforcer 强制 [21,22)"

echo "===== 启动 $(date '+%Y-%m-%d %H:%M:%S') ROOT=$ROOT PROFILE=$PROFILE ====="
if [ "$BUILD" -eq 1 ]; then
  echo ">>> 编译后端"
  (cd "$ROOT/backend" && mvn -q install -DskipTests) || { echo "[FAIL] 编译失败"; exit 1; }
fi
if [ "$TESTS" -eq 1 ]; then
  echo ">>> 全量测试"
  (cd "$ROOT/backend" && mvn -q test) || { echo "[FAIL] 测试失败"; exit 1; }
fi

JAR=$(ls "$ROOT"/backend/timecoin-web/target/timecoin-web-*.jar 2>/dev/null | head -1 || true)
if [ -z "$JAR" ]; then echo "[FAIL] 未找到后端 jar，请先 --build"; exit 1; fi
echo ">>> 启动后端 $JAR"
nohup java -jar "$JAR" --spring.profiles.active="$PROFILE" > "$LOG" 2>&1 &
echo "  PID=$! LOG=$LOG"
i=0
while [ "$i" -lt 90 ]; do
  if grep -q "Started TimecoinWebApplication" "$LOG" 2>/dev/null; then
    grep "Started TimecoinWebApplication" "$LOG" | tail -1; echo "===== 后端就绪 ====="; exit 0
  fi
  sleep 1; i=$((i+1))
done
echo "[FAIL] 90s 内未就绪，请查看 $LOG"; exit 1
