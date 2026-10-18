#!/usr/bin/env bash
# 手机端控制入口：启停 / 状态 / 日志 / 自检 / 改模式 / 改白名单 / 备份。
#
# 与 manage.sh 的区别：manage.sh 走 systemctl --user + .deployment.json（服务器路线），
# 手机上两样都没有。这个脚本用 PID 文件 + supervise.sh（systemd 的最小替代）。
#
# 用法：
#   bash scripts/phone/ctl.sh start            启动（后台守护，挂了自动拉起）
#   bash scripts/phone/ctl.sh stop             停止
#   bash scripts/phone/ctl.sh restart          重启
#   bash scripts/phone/ctl.sh status           状态（进程 + HTTP + OneBot 连接）
#   bash scripts/phone/ctl.sh logs [N]         服务日志（默认 60 行）
#   bash scripts/phone/ctl.sh logs -f          跟踪日志
#   bash scripts/phone/ctl.sh suplogs [N]      守护进程日志
#   bash scripts/phone/ctl.sh doctor           自检（Node / 依赖 / 配置 / 端口 / NapCat 连通）
#   bash scripts/phone/ctl.sh mode active      切运行模式（observe|active）
#   bash scripts/phone/ctl.sh allow 123456,789 覆盖群白名单（逗号分隔；空串 = 清空）
#   bash scripts/phone/ctl.sh token            打印控制台 URL（带 token）
#   bash scripts/phone/ctl.sh backup           备份数据目录到 backups/
#   bash scripts/phone/ctl.sh open             用手机浏览器打开控制台（termux-open-url / xdg-open）
#
# 环境变量：
#   QAP_INSTALL_DIR / QAP_DATA_DIR / QAP_RUN_DIR / QAP_NODE_BIN  覆盖自动探测结果
#   QAP_RESTART_DELAY  守护重起间隔（秒，默认 5）
set -uo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd "$SELF_DIR/../.." && pwd -P)"

info() { printf '%s\n' "$*"; }
warn() { printf '[warn] %s\n' "$*" >&2; }
die()  { printf '[ctl] %s\n' "$*" >&2; exit 1; }

# ── 定位安装信息：环境变量 > .phone-deployment.json > 默认值 ──
json_get() {
  # 用 node 读（不依赖 jq；手机上 jq 不一定有）
  local file="$1" expr="$2"
  [ -f "$file" ] || return 1
  "$NODE_BIN_FOR_CTL" -e '
    const fs = require("fs");
    try { const v = JSON.parse(fs.readFileSync(process.argv[1], "utf8")); const r = eval(process.argv[2]); if (r !== undefined && r !== null) process.stdout.write(String(r)); } catch (e) { process.exit(1); }
  ' "$file" "$expr" 2>/dev/null
}

pick_node() {
  local candidates=()
  [ -n "${QAP_NODE_BIN:-}" ] && candidates+=("$QAP_NODE_BIN")
  for d in "$INSTALL_DIR"/.runtime/node-v*-linux-*/bin/node; do [ -x "$d" ] && candidates+=("$d"); done
  command -v node > /dev/null 2>&1 && candidates+=("$(command -v node)")
  for c in "${candidates[@]:-}"; do
    [ -x "$c" ] || continue
    if "$c" --no-warnings -e 'import("node:sqlite").then(()=>process.exit(0)).catch(()=>process.exit(1))' > /dev/null 2>&1; then
      NODE_BIN="$c"; return 0
    fi
  done
  return 1
}

INSTALL_DIR="${QAP_INSTALL_DIR:-$REPO_ROOT}"
NODE_BIN_FOR_CTL="$(command -v node 2>/dev/null || echo /usr/bin/node)"
MARKER="$INSTALL_DIR/.phone-deployment.json"
if [ -f "$MARKER" ]; then
  v="$(json_get "$MARKER" 'v.installDir')" && [ -n "$v" ] && INSTALL_DIR="$v"
  v="$(json_get "$MARKER" 'v.nodeBin')" && [ -n "$v" ] && [ -x "$v" ] && NODE_BIN_FOR_CTL="$v"
fi
DATA_DIR="${QAP_DATA_DIR:-}"
[ -n "$DATA_DIR" ] || DATA_DIR="$(json_get "$MARKER" 'v.dataDir' 2>/dev/null || true)"
[ -n "$DATA_DIR" ] || DATA_DIR="$INSTALL_DIR/data"
RUN_DIR="${QAP_RUN_DIR:-}"
[ -n "$RUN_DIR" ] || RUN_DIR="$(json_get "$MARKER" 'v.runDir' 2>/dev/null || true)"
[ -n "$RUN_DIR" ] || RUN_DIR="$INSTALL_DIR/run"

[ -d "$INSTALL_DIR" ] || die "安装目录不存在：$INSTALL_DIR"
[ -f "$INSTALL_DIR/src/server.js" ] || die "$INSTALL_DIR 下没有 src/server.js —— 这不是 qq-agent-plus 安装目录
（在手机上请从仓库根目录运行：bash scripts/phone/ctl.sh ...）"

SUP_PID_FILE="$RUN_DIR/supervisor.pid"
SERVER_PID_FILE="$RUN_DIR/server.pid"
LOG="$RUN_DIR/server.log"
SUP_LOG="$RUN_DIR/supervisor.log"
mkdir -p "$RUN_DIR"

pick_node || die "找不到可用的 Node（>=22.19 且 node:sqlite 可用）。先跑：bash scripts/phone/install.sh"
NODE_BIN_FOR_CTL="$NODE_BIN"

cfg_get() {
  "$NODE_BIN" -e '
    const fs = require("fs");
    try { const c = JSON.parse(fs.readFileSync(process.argv[1], "utf8")); const r = eval(process.argv[2]); if (r !== undefined && r !== null) process.stdout.write(String(r)); } catch (e) { process.exit(1); }
  ' "$DATA_DIR/config.json" "$1" 2>/dev/null
}

CONSOLE_HOST="$(cfg_get 'c.server?.host' || echo 127.0.0.1)"
CONSOLE_PORT="$(cfg_get 'c.server?.port' || echo 3210)"
CONSOLE_TOKEN="$(cfg_get 'c.server?.token' || echo '')"
[ "$CONSOLE_HOST" = "0.0.0.0" ] && CONSOLE_HOST="127.0.0.1"
BASE_URL="http://$CONSOLE_HOST:$CONSOLE_PORT"

# 控制台鉴权：本机回环 + 无令牌时可裸访问；设了 server.token 就要带 x-console-token
api() {
  local method="$1" route="$2" body="${3:-}"
  local args=(-s -m 10 -X "$method" "$BASE_URL$route" -H 'content-type: application/json')
  [ -n "$CONSOLE_TOKEN" ] && args+=(-H "x-console-token: $CONSOLE_TOKEN")
  [ -n "$body" ] && args+=(-d "$body")
  curl "${args[@]}" 2>/dev/null
}

is_running() {
  local pid
  pid="$(cat "$SUP_PID_FILE" 2>/dev/null || true)"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

cmd_start() {
  if is_running; then info "已在运行（supervisor pid=$(cat "$SUP_PID_FILE")）"; return 0; fi
  # 残留 PID 文件
  rm -f "$SUP_PID_FILE" "$SERVER_PID_FILE"
  info "启动（守护模式，node=$NODE_BIN）"
  info "  安装目录 $INSTALL_DIR"
  info "  数据目录 $DATA_DIR"
  info "  运行目录 $RUN_DIR"
  setsid env \
    QAP_INSTALL_DIR="$INSTALL_DIR" \
    QAP_DATA_DIR="$DATA_DIR" \
    QAP_RUN_DIR="$RUN_DIR" \
    QAP_NODE_BIN="$NODE_BIN" \
    QAP_RESTART_DELAY="${QAP_RESTART_DELAY:-5}" \
    bash "$SELF_DIR/supervise.sh" > /dev/null 2>&1 &
  local sup=$!
  # setsid 下 $! 是 setsid 自己的 pid；supervise.sh 会把真实 pid 写进 supervisor.pid
  for _ in $(seq 1 30); do
    sleep 0.5
    if is_running; then break; fi
    kill -0 "$sup" 2>/dev/null || true
  done
  if ! is_running; then
    warn "守护进程没起来，看 $SUP_LOG"
    tail -n 20 "$SUP_LOG" 2>/dev/null || true
    return 1
  fi
  # 等控制台可访问
  local code=""
  for _ in $(seq 1 60); do
    sleep 1
    # curl 连不上时自己就会打印 000 且退出码非零；再加 `|| echo 000` 会拼成 000000
    code="$(curl -s -o /dev/null -m 3 -w '%{http_code}' "$BASE_URL/" 2>/dev/null)"
    code="${code:-000}"
    [ "$code" = "200" ] && break
    if ! is_running; then
      warn "服务进程退出了，看 $LOG"
      tail -n 30 "$LOG" 2>/dev/null || true
      return 1
    fi
  done
  if [ "$code" = "200" ]; then
    info "控制台就绪：$BASE_URL"
    info "提示：首次要在控制台填模型、群白名单，并把运行模式切到 active（或 ctl.sh mode active）"
    return 0
  fi
  warn "控制台 60 秒内没起来（最后一次 HTTP=$code），看 $LOG"
  tail -n 30 "$LOG" 2>/dev/null || true
  return 1
}

cmd_stop() {
  if ! is_running; then
    info "没在运行"
    rm -f "$SUP_PID_FILE" "$SERVER_PID_FILE"
    return 0
  fi
  local pid; pid="$(cat "$SUP_PID_FILE")"
  info "停止（supervisor pid=$pid）"
  kill -TERM "$pid" 2>/dev/null
  for _ in $(seq 1 40); do
    sleep 1
    kill -0 "$pid" 2>/dev/null || { info "已停止"; return 0; }
  done
  warn "30 秒没停，强制"
  kill -9 "$pid" 2>/dev/null
  [ -f "$SERVER_PID_FILE" ] && kill -9 "$(cat "$SERVER_PID_FILE")" 2>/dev/null
  rm -f "$SUP_PID_FILE" "$SERVER_PID_FILE"
  info "已强制停止"
}

cmd_restart() { cmd_stop; cmd_start; }

cmd_status() {
  local sup_pid="" srv_pid=""
  sup_pid="$(cat "$SUP_PID_FILE" 2>/dev/null || true)"
  srv_pid="$(cat "$SERVER_PID_FILE" 2>/dev/null || true)"
  info "安装目录 : $INSTALL_DIR"
  info "数据目录 : $DATA_DIR"
  info "Node     : $NODE_BIN ($("$NODE_BIN" -v 2>/dev/null))"
  if [ -n "$sup_pid" ] && kill -0 "$sup_pid" 2>/dev/null; then
    info "守护进程 : 运行中 (pid $sup_pid)"
  else
    info "守护进程 : 未运行"
  fi
  if [ -n "$srv_pid" ] && kill -0 "$srv_pid" 2>/dev/null; then
    info "服务进程 : 运行中 (pid $srv_pid)"
  else
    info "服务进程 : 未运行"
  fi
  # curl 连不上时会自己打印 000 且退出码非零；`|| echo 000` 会拼成 000000
  local code; code="$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$BASE_URL/" 2>/dev/null)"
  code="${code:-000}"
  info "控制台   : $BASE_URL → HTTP ${code:-000}"
  if [ "$code" = "200" ]; then
    local status; status="$(api GET /api/status)"
    if [ -n "$status" ]; then
      printf '%s' "$status" | "$NODE_BIN" -e '
        let raw = "";
        process.stdin.on("data", (c) => { raw += c; });
        process.stdin.on("end", () => {
          try {
            const s = JSON.parse(raw);
            const ob = s.onebot || {};
            const orch = s.orchestrator || {};
            console.log(`OneBot   : ${ob.connected ? "已连接" : "未连接"}${ob.connected ? "" : "（" + (ob.error || "无错误信息") + "）"}`);
            console.log(`运行模式 : ${orch.mode || "?"}${orch.paused ? "（已暂停）" : ""}`);
            console.log(`模型     : ${orch.model || "（未设置）"}`);
            const usage = s.usage || {};
            // /api/status 的用量字段是 estimatedYuan（当日估算，元）与 runs（运行次数）；
            // 没有 usage.cost —— 按 cost 读会一直显示 "?"，看着像坏了。
            const yuan = Number(usage.estimatedYuan);
            const runs = Number(usage.runs);
            const spend = Number.isFinite(yuan) ? `${yuan.toFixed(4)} 元` : "?";
            const count = Number.isFinite(runs) ? `${runs} 次` : "?";
            console.log(`今日用量 : ${spend}${usage.unpricedRuns ? `（另有 ${usage.unpricedRuns} 次未定价）` : ""} / 运行 ${count}`);
          } catch { console.log("（/api/status 解析失败）"); }
        });
      '
    fi
  else
    info "（控制台不可达：服务可能刚起/已挂。看 ctl.sh logs）"
  fi
  if [ -f "$LOG" ]; then
    info "日志     : $LOG ($(wc -l < "$LOG" | tr -d ' ') 行)"
  fi
}

cmd_logs() {
  local arg="${1:-60}"
  [ -f "$LOG" ] || die "还没有日志文件：$LOG（先 ctl.sh start）"
  if [ "$arg" = "-f" ]; then
    tail -n 60 -f "$LOG"
  else
    tail -n "$arg" "$LOG"
  fi
}

cmd_doctor() {
  local fail=0
  info "== 1. 环境 =="
  info "架构        : $(uname -m)"
  info "系统        : $(. /etc/os-release 2>/dev/null && printf '%s' "${PRETTY_NAME:-?}" || uname -s)"
  info "安装目录    : $INSTALL_DIR"
  info "数据目录    : $DATA_DIR"
  if command -v systemctl > /dev/null 2>&1 && systemctl --user show-environment > /dev/null 2>&1; then
    info "systemd     : 可用（本方案不用它）"
  else
    info "systemd     : 不可用 —— 正常（proot 容器里靠 supervise.sh 守护）"
  fi

  info ""
  info "== 2. Node =="
  local nv; nv="$("$NODE_BIN" -v 2>/dev/null || echo none)"
  info "node        : $NODE_BIN ($nv)"
  if "$NODE_BIN" --no-warnings -e 'import("node:sqlite").then(()=>process.exit(0)).catch(()=>process.exit(1))' > /dev/null 2>&1; then
    info "node:sqlite : 可用"
  else
    warn "node:sqlite 不可用 —— 必须升到 Node >= 22.19（Ubuntu 24.04 自带的 nodejs 是 18，不行）"
    fail=1
  fi

  info ""
  info "== 3. 依赖 =="
  if [ -d "$INSTALL_DIR/node_modules/ws" ]; then
    info "node_modules: 就位（$(find "$INSTALL_DIR/node_modules" -type f | wc -l | tr -d ' ') 个文件）"
    local native; native="$(find "$INSTALL_DIR/node_modules" -name '*.node' 2>/dev/null | wc -l | tr -d ' ')"
    info "原生模块    : $native 个（应为 0；>0 说明装进了不该有的包或平台不匹配）"
    [ "$native" != "0" ] && warn "出现了原生 .node 文件，arm64 上可能是给 x86 编的，注意"
  else
    warn "node_modules 缺失 —— 跑 bash scripts/phone/install.sh"
    fail=1
  fi

  info ""
  info "== 4. 配置 =="
  if [ -f "$DATA_DIR/config.json" ]; then
    info "config.json : $DATA_DIR/config.json"
    info "  控制台    : $BASE_URL"
    info "  OneBot WS : $(cfg_get 'c.onebot?.wsUrl' || echo '?')"
    info "  OneBot HTTP: $(cfg_get 'c.onebot?.httpUrl' || echo '?')"
    local tok; tok="$(cfg_get 'c.onebot?.accessToken')"
    info "  token     : $([ -n "$tok" ] && echo "已设置（${#tok} 字符）" || echo "未设置（NapCat 若设了 token 就连不上）")"
    local model; model="$(cfg_get 'c.api?.model')"
    info "  模型      : ${model:-未设置}"
    local mode; mode="$(cfg_get 'c.runtime?.mode' || echo '?')"
    info "  运行模式  : $mode$([ "$mode" = "observe" ] && echo "（只记录不发言，要发言：ctl.sh mode active）")"
    local groups; groups="$(cfg_get '(c.allow?.groups||[]).join(",")')"
    info "  群白名单  : ${groups:-（空 = 不回复任何群）}"
    [ -z "$model" ] && warn "没配模型 ⇒ 收到消息也不会回复"
  else
    warn "config.json 缺失 —— 跑 bash scripts/phone/install.sh"
    fail=1
  fi

  info ""
  info "== 5. 端口与 NapCat 连通 =="
  local ws_url http_url
  ws_url="$(cfg_get 'c.onebot?.wsUrl' || echo '')"
  http_url="$(cfg_get 'c.onebot?.httpUrl' || echo '')"
  local ws_hostport="${ws_url#ws://}"; ws_hostport="${ws_hostport%%/*}"
  local http_hostport="${http_url#http://}"; http_hostport="${http_hostport%%/*}"
  info "探测 WS   : $ws_hostport"
  if (exec 3<>"/dev/tcp/${ws_hostport%:*}/${ws_hostport##*:}") 2>/dev/null; then
    info "  → 端口开着（NapCat 的 websocketServers 在监听）"
    exec 3<&- 3>&-
  else
    warn "  → 连不上。NapCat 侧要开「WebSocket 服务器」并 enable=true，host/port 与 $ws_url 一致"
    fail=1
  fi
  info "探测 HTTP : $http_hostport"
  local napcat_code
  napcat_code="$(curl -s -o /dev/null -m 5 -w '%{http_code}' "http://$http_hostport/" 2>/dev/null)"
  napcat_code="${napcat_code:-000}"
  if [ "$napcat_code" = "200" ] || [ "$napcat_code" = "403" ]; then
    info "  → HTTP $napcat_code（403 = 开着但 token 不对，200 = 通）"
    [ "$napcat_code" = "403" ] && { warn "  NapCat 的 httpServers.token 与 config.json 的 httpAccessToken/accessToken 不一致"; fail=1; }
  else
    warn "  → 连不上。NapCat 侧要开「HTTP 服务器」并 enable=true"
    fail=1
  fi

  info ""
  info "== 6. 本机服务 =="
  is_running && info "守护进程    : 运行中 (pid $(cat "$SUP_PID_FILE"))" || info "守护进程    : 未运行（ctl.sh start）"
  local code; code="$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$BASE_URL/" 2>/dev/null)"
  info "控制台      : HTTP ${code:-000}"

  info ""
  if [ "$fail" = "0" ]; then
    info "自检结论：没有发现致命问题。"
  else
    info "自检结论：上面有 warn/失败项，按提示逐条处理。"
  fi
  return "$fail"
}

cmd_mode() {
  local mode="${1:-}"
  case "$mode" in
    observe|active) ;;
    *) die "用法：ctl.sh mode observe|active" ;;
  esac
  [ "$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$BASE_URL/" 2>/dev/null)" = "200" ] || die "控制台不可达（$BASE_URL），先 ctl.sh start"
  local body
  if [ "$mode" = "active" ]; then
    # 服务要求显式确认"旧实例已停"，否则 409
    body='{"mode":"active","confirmExclusive":true}'
  else
    body='{"mode":"observe"}'
  fi
  local resp
  resp="$(api POST /api/runtime "$body")"
  if printf '%s' "$resp" | grep -q '"ok":true'; then
    info "运行模式已切到 $mode"
    [ "$mode" = "active" ] && info "注意：现在收到符合触发条件的消息就会真的发言（且消耗 token）"
  else
    warn "切换失败：$resp"
    return 1
  fi
}

cmd_allow() {
  local raw="${1-}"
  [ "$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$BASE_URL/" 2>/dev/null)" = "200" ] || die "控制台不可达（$BASE_URL），先 ctl.sh start"
  local groups="[]"
  if [ -n "${raw// /}" ]; then
    groups="$(printf '%s' "$raw" | tr ',' '\n' | sed '/^$/d;s/^ *//;s/ *$//' | "$NODE_BIN" -e '
      let raw = ""; process.stdin.on("data", (c) => { raw += c; });
      process.stdin.on("end", () => {
        const list = raw.split("\n").map((s) => s.trim()).filter(Boolean);
        const bad = list.filter((s) => !/^\d{5,15}$/.test(s));
        if (bad.length) { console.error("不是合法群号：" + bad.join(", ")); process.exit(1); }
        process.stdout.write(JSON.stringify(list));
      });')" || die "群号格式不对（应为 5-15 位数字，逗号分隔）"
  fi
  local resp
  resp="$(api POST /api/config "{\"allow\":{\"groups\":$groups}}")"
  if printf '%s' "$resp" | grep -q '"ok"\|"config"'; then
    info "群白名单已更新：$groups"
    [ "$groups" = "[]" ] && info "（空 = 不回复任何群；要么加群号，要么在控制台把 allowAllWhenEmpty 打开）"
  else
    warn "更新失败：$resp"
    return 1
  fi
}

cmd_token() {
  local url="$BASE_URL"
  [ -n "$CONSOLE_TOKEN" ] && url="$BASE_URL/?token=$CONSOLE_TOKEN"
  info "$url"
  [ -z "$CONSOLE_TOKEN" ] && info "（server.token 为空 = 只允许本机访问；手机上同机浏览器可直接打开）"
}

cmd_backup() {
  [ -d "$DATA_DIR" ] || die "数据目录不存在：$DATA_DIR"
  local backup_dir="$INSTALL_DIR/backups"
  mkdir -p "$backup_dir"
  local stamp; stamp="$(date '+%Y%m%d-%H%M%S')"
  local target="$backup_dir/data-$stamp.tar.gz"
  info "备份 $DATA_DIR → $target"
  # SQLite 正在写时直接 tar 可能拿到不一致的快照：先 WAL checkpoint（node:sqlite 自带）
  if [ -f "$DATA_DIR/messages.sqlite" ]; then
    "$NODE_BIN" --no-warnings -e '
      const { DatabaseSync } = require("node:sqlite");
      const f = process.argv[1];
      try { const db = new DatabaseSync(f); db.exec("PRAGMA wal_checkpoint(TRUNCATE);"); db.close(); console.log("[backup] WAL 已 checkpoint"); }
      catch (e) { console.error("[backup] checkpoint 跳过：" + e.message); }
    ' "$DATA_DIR/messages.sqlite" 2>/dev/null || true
  fi
  tar -czf "$target" -C "$(dirname "$DATA_DIR")" "$(basename "$DATA_DIR")" && {
    info "完成：$target ($(du -h "$target" | cut -f1))"
    info "恢复：ctl.sh stop && tar -xzf $target -C $(dirname "$DATA_DIR") && ctl.sh start"
    info "注意：config.json 里有 API Key，备份文件自己保管好（别传到公共网盘）"
  } || die "备份失败"
}

cmd_open() {
  # 注意不能直接 `url="$(cmd_token)"`：cmd_token 在没有 server.token 时还会多打印一行说明，
  # 那样整个输出都会被当成 URL 传给浏览器。
  local url="$BASE_URL"
  [ -n "$CONSOLE_TOKEN" ] && url="$BASE_URL/?token=$CONSOLE_TOKEN"
  if command -v termux-open-url > /dev/null 2>&1; then
    termux-open-url "$url" && { info "已交给系统浏览器：$url"; return 0; }
  fi
  if command -v xdg-open > /dev/null 2>&1; then
    xdg-open "$url" > /dev/null 2>&1 && { info "已尝试用 xdg-open 打开：$url"; return 0; }
  fi
  info "没有可用的浏览器命令（容器内通常没有）。请直接在手机上打开："
  info "  $url"
  if [ -z "$CONSOLE_TOKEN" ]; then
    info "（server.token 为空 = 只允许本机访问；手机上同机浏览器可直接打开）"
  fi
}

cmd_help() { sed -n '2,24p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

case "${1:-help}" in
  start)   shift; cmd_start "$@" ;;
  stop)    shift; cmd_stop "$@" ;;
  restart) shift; cmd_restart "$@" ;;
  status)  shift; cmd_status "$@" ;;
  logs)    shift; cmd_logs "${1:-60}" ;;
  suplogs) shift; [ -f "$SUP_LOG" ] && tail -n "${1:-60}" "$SUP_LOG" || info "还没有守护日志" ;;
  doctor)  shift; cmd_doctor "$@" ;;
  mode)    shift; cmd_mode "$@" ;;
  allow)   shift; cmd_allow "$@" ;;
  token)   shift; cmd_token "$@" ;;
  backup)  shift; cmd_backup "$@" ;;
  open)    shift; cmd_open "$@" ;;
  help|-h|--help) cmd_help ;;
  *) info "未知子命令：$1"; info ""; cmd_help; exit 2 ;;
esac
