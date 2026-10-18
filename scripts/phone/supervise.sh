#!/usr/bin/env bash
# 守护循环：被 ctl.sh start 以 nohup/setsid 方式拉起，负责"服务挂了就重起"。
#
# 为什么不用 systemd：手机上的 proot 容器没有 init/systemd（`systemctl --user` 不可用，
# deploy.sh 那种路线在手机上会直接 exit 1）。这个脚本就是 systemd 的最小替代：
#   - Restart=always 的等价物：node 退出后 sleep RESTART_DELAY 秒重起；
#   - 连续快速失败（< FAIL_WINDOW 秒内挂了 MAX_FAST_FAILS 次）就退避并在日志里说明，
#     避免配置写错时把手机 CPU 打满；
#   - 收到 SIGTERM（ctl.sh stop）时先转发给 node，再等它自己优雅退出。
#
# 环境变量（都由 ctl.sh 传入）：
#   QAP_INSTALL_DIR / QAP_DATA_DIR / QAP_RUN_DIR / QAP_NODE_BIN
#   QAP_RESTART_DELAY（默认 5）/ QAP_MAX_FAST_FAILS（默认 10）/ QAP_FAIL_WINDOW（默认 60）
set -uo pipefail

INSTALL_DIR="${QAP_INSTALL_DIR:?QAP_INSTALL_DIR 未设置}"
DATA_DIR="${QAP_DATA_DIR:?QAP_DATA_DIR 未设置}"
RUN_DIR="${QAP_RUN_DIR:?QAP_RUN_DIR 未设置}"
NODE_BIN="${QAP_NODE_BIN:-node}"
RESTART_DELAY="${QAP_RESTART_DELAY:-5}"
MAX_FAST_FAILS="${QAP_MAX_FAST_FAILS:-10}"
FAIL_WINDOW="${QAP_FAIL_WINDOW:-60}"

LOG="$RUN_DIR/server.log"
SUP_LOG="$RUN_DIR/supervisor.log"
CHILD_PID_FILE="$RUN_DIR/server.pid"
SUP_PID_FILE="$RUN_DIR/supervisor.pid"

log() {
  printf '%s [supervise] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$SUP_LOG"
}

mkdir -p "$RUN_DIR" "$DATA_DIR"
echo $$ > "$SUP_PID_FILE"
: > "$SUP_LOG"
log "守护启动：install=$INSTALL_DIR data=$DATA_DIR node=$NODE_BIN pid=$$"

child=""
stopping=0
shutdown() {
  [ "$stopping" = "1" ] && return
  stopping=1
  log "收到停止信号，转发给 node（pid=${child:-无}）"
  if [ -n "$child" ] && kill -0 "$child" 2>/dev/null; then
    kill -TERM "$child" 2>/dev/null
    # server.js 的 shutdown 有 25 秒兜底；这里给 30 秒
    for _ in $(seq 1 30); do
      kill -0 "$child" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$child" 2>/dev/null
  fi
  rm -f "$CHILD_PID_FILE" "$SUP_PID_FILE"
  log "守护退出"
  exit 0
}
trap shutdown TERM INT

fast_fails=0
window_start=0

while :; do
  # ── 起服务 ──
  started_at=$(date +%s)
  log "启动 node src/server.js"
  (
    cd "$INSTALL_DIR" || exit 1
    exec env QQ_AGENT_DATA_DIR="$DATA_DIR" \
      QQ_AGENT_LOG_LEVEL="${QQ_AGENT_LOG_LEVEL:-info}" \
      "$NODE_BIN" src/server.js
  ) >> "$LOG" 2>&1 &
  child=$!
  echo "$child" > "$CHILD_PID_FILE"
  log "node pid=$child"

  # ── 等它退出（wait 会被信号打断，所以循环） ──
  wait "$child"
  code=$?
  [ "$stopping" = "1" ] && break
  log "node 退出（code=$code）"

  # ── 快速失败保护：短时间内反复秒退说明配置有问题，退避更长并提示 ──
  now=$(date +%s)
  lived=$(( now - started_at ))
  if [ "$lived" -lt 10 ]; then
    if [ $(( now - window_start )) -gt "$FAIL_WINDOW" ]; then
      window_start=$now
      fast_fails=0
    fi
    fast_fails=$(( fast_fails + 1 ))
  else
    fast_fails=0
    window_start=$now
  fi

  if [ "$fast_fails" -ge "$MAX_FAST_FAILS" ]; then
    log "连续快速失败 ${fast_fails} 次（${FAIL_WINDOW}s 内）——配置大概有问题，暂停 60 秒后继续"
    log "排查：bash scripts/phone/ctl.sh logs   或   bash scripts/phone/ctl.sh doctor"
    log "最后 20 行服务日志："
    tail -n 20 "$LOG" | sed 's/^/    /' | tee -a "$SUP_LOG" > /dev/null
    sleep 60
    fast_fails=0
    window_start=$(date +%s)
    continue
  fi

  log "${RESTART_DELAY} 秒后重起"
  sleep "$RESTART_DELAY"
done

shutdown
