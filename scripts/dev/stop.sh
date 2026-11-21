#!/bin/bash
# 关闭本脚本启动的后端进程（按 jar 名匹配），不误杀其它 Java 进程。
set -eu
PIDS=$(ps -eo pid,args 2>/dev/null | grep 'timecoin-web-[0-9].*\.jar' | grep -v grep | awk '{print $1}' || true)
if [ -z "$PIDS" ]; then echo "未发现运行中的后端进程"; exit 0; fi
echo "关闭后端 PID: $PIDS"
for p in $PIDS; do kill "$p" 2>/dev/null || true; done
echo "已发送 TERM；如未退出可再执行 kill -9 $PIDS"
