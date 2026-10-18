#!/usr/bin/env bash
# 手机端（proot Ubuntu 24.04 arm64 容器内）一键安装。
#
# 与仓库里的 install.sh / deploy-all.sh 的区别（重要）：
#   那两个是**服务器**路线，硬依赖 systemd 用户服务（deploy.sh 在 `systemctl --user
#   show-environment` 失败时直接 exit 1）。手机上的 proot 容器起不了 init/systemd，
#   所以这里完全不碰 systemd：装 Node + 装依赖 + 写配置，进程交给 phone/ctl.sh 的守护脚本。
#
# 做的事：
#   1) 环境自检（架构 / 是否有 systemd / 磁盘空间），手机上大部分情况只是打印提示；
#   2) 找一个 >=22.19 的 Node（找不到就下载官方 arm64 tar.xz 解到 <安装目录>/.runtime）；
#   3) 校验 node:sqlite 可用（本项目硬依赖，缺了会在启动时就报错）；
#   4) 装依赖：npm ci；或用 --offline-node-modules 直接拷一份准备好的 node_modules
#      （手机流量/弱网时推荐在电脑上装好再拷）；
#   5) 写 data/config.json（已存在就**只补缺省字段**，不覆盖你填过的值）与
#      .phone-deployment.json（安装记录，ctl.sh 依赖它）；
#   6) 打印接下来三步（NapCat 配置、启动、控制台入口）。
#
# 用法：
#   bash scripts/phone/install.sh                        # 全默认
#   bash scripts/phone/install.sh --data-dir /qq-agent/data
#   bash scripts/phone/install.sh --offline-node-modules /mnt/node_modules
#   bash scripts/phone/install.sh --model-base-url https://api.deepseek.com/v1 \
#                                 --model-api-key sk-xxx --model deepseek-chat
#   bash scripts/phone/install.sh --help
#
# 目录约定（全部可用参数改）：
#   <安装目录>           默认 = 本仓库根目录（脚本所在 scripts/phone 的上两级）
#   <安装目录>/.runtime  自带的 Node 运行时
#   <安装目录>/data      数据目录（= QQ_AGENT_DATA_DIR）
#   <安装目录>/run       PID 与日志（= ctl.sh 的工作目录）
set -uo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd "$SELF_DIR/../.." && pwd -P)"

INSTALL_DIR="$REPO_ROOT"
DATA_DIR=""
NODE_VERSION="22.23.2"
NODE_DIR=""
NPM_REGISTRY=""
OFFLINE_MODULES=""
SKIP_DEPS=0
CONSOLE_PORT=3210
WS_PORT=3001
HTTP_PORT=3000
TOKEN=""
MODEL_BASE_URL=""
MODEL_API_KEY=""
MODEL_NAME=""
ALLOW_GROUP=""
QUIET=0

die()  { printf '\n[phone-install] %s\n' "$*" >&2; exit 1; }
info() { [ "$QUIET" = "1" ] || printf '%s\n' "$*"; }
step() { [ "$QUIET" = "1" ] || printf '\n=== %s ===\n' "$*"; }

usage() {
  sed -n '2,32p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}

while [ $# -gt 0 ]; do
  case "$1" in
    --install-dir) INSTALL_DIR="${2:?}"; shift 2 ;;
    --data-dir) DATA_DIR="${2:?}"; shift 2 ;;
    --node-version) NODE_VERSION="${2:?}"; shift 2 ;;
    --node-dir) NODE_DIR="${2:?}"; shift 2 ;;
    --npm-registry) NPM_REGISTRY="${2:?}"; shift 2 ;;
    --offline-node-modules) OFFLINE_MODULES="${2:?}"; shift 2 ;;
    --skip-deps) SKIP_DEPS=1; shift ;;
    --port) CONSOLE_PORT="${2:?}"; shift 2 ;;
    --ws-port) WS_PORT="${2:?}"; shift 2 ;;
    --http-port) HTTP_PORT="${2:?}"; shift 2 ;;
    --token) TOKEN="${2:?}"; shift 2 ;;
    --model-base-url) MODEL_BASE_URL="${2:?}"; shift 2 ;;
    --model-api-key) MODEL_API_KEY="${2:?}"; shift 2 ;;
    --model) MODEL_NAME="${2:?}"; shift 2 ;;
    --allow-group) ALLOW_GROUP="${2:?}"; shift 2 ;;
    --quiet|-q) QUIET=1; shift ;;
    -h|--help) usage ;;
    *) die "未知参数：$1（--help 看用法）" ;;
  esac
done

INSTALL_DIR="$(mkdir -p "$INSTALL_DIR" && cd "$INSTALL_DIR" && pwd -P)"
[ -n "$DATA_DIR" ] || DATA_DIR="$INSTALL_DIR/data"
DATA_DIR="$(mkdir -p "$DATA_DIR" && cd "$DATA_DIR" && pwd -P)"
RUN_DIR="$INSTALL_DIR/run"
mkdir -p "$RUN_DIR"
MARKER="$INSTALL_DIR/.phone-deployment.json"

for port in "$CONSOLE_PORT" "$WS_PORT" "$HTTP_PORT"; do
  case "$port" in ''|*[!0-9]*) die "端口必须是数字：$port" ;; esac
done

step "1. 环境自检"
ARCH="$(uname -m)"
info "架构        : $ARCH"
info "系统        : $(. /etc/os-release 2>/dev/null && printf '%s' "${PRETTY_NAME:-unknown}" || uname -s)"
info "安装目录    : $INSTALL_DIR"
info "数据目录    : $DATA_DIR"
info "当前用户    : $(id -un 2>/dev/null || echo '?') (uid $(id -u 2>/dev/null || echo '?'))"
if [ "$ARCH" != "aarch64" ] && [ "$ARCH" != "arm64" ]; then
  info "提示        : 非 arm64（$ARCH）。手机应是 aarch64；在电脑上预演时会看到这条，可继续。"
fi
if command -v systemctl > /dev/null 2>&1 && systemctl --user show-environment > /dev/null 2>&1; then
  info "systemd     : 可用（手机 proot 里通常不可用；本脚本不需要它）"
else
  info "systemd     : 不可用 —— 正常，手机端就是靠 phone/ctl.sh 守护，不走 systemd"
fi
if [ -f "$INSTALL_DIR/package.json" ]; then
  info "源码        : 已就位（$INSTALL_DIR/package.json）"
else
  die "在 $INSTALL_DIR 找不到 package.json。请把整个仓库放到安装目录后重跑：
  git clone <本仓库> $INSTALL_DIR
  或指定：bash scripts/phone/install.sh --install-dir /path/to/qq-agent-plus"
fi
FREE_KB="$(df -Pk "$INSTALL_DIR" | awk 'NR==2 {print $4}')"
info "剩余空间    : $(( FREE_KB / 1024 )) MB"
[ "${FREE_KB:-0}" -lt 200000 ] && info "警告        : 剩余空间不足 200MB，node_modules + 数据可能不够"

step "2. 准备 Node.js（需要 >= 22.19，本项目用 node:sqlite）"

node_version_of() {
  "$1" -v 2>/dev/null | sed 's/^v//'
}

# 比较 a.b.c：$1 >= $2 返回 0
version_ge() {
  local IFS=.
  local -a a=($1) b=($2)
  local i
  for i in 0 1 2; do
    local x="${a[i]:-0}" y="${b[i]:-0}"
    x="${x%%[!0-9]*}"; y="${y%%[!0-9]*}"
    x="${x:-0}"; y="${y:-0}"
    if [ "$x" -gt "$y" ]; then return 0; fi
    if [ "$x" -lt "$y" ]; then return 1; fi
  done
  return 0
}

node_ok() {
  local bin="$1"
  [ -n "$bin" ] && [ -x "$bin" ] || return 1
  local v; v="$(node_version_of "$bin")"
  [ -n "$v" ] || return 1
  version_ge "$v" "22.19.0" || return 1
  "$bin" --no-warnings -e 'import("node:sqlite").then(()=>process.exit(0)).catch(()=>process.exit(1))' > /dev/null 2>&1
}

NODE_BIN=""
# 找现成的：显式 --node-dir > 自带 .runtime > PATH
CANDIDATES=()
[ -n "$NODE_DIR" ] && CANDIDATES+=("$NODE_DIR/bin/node")
for d in "$INSTALL_DIR"/.runtime/node-v*-linux-*/bin/node; do
  [ -e "$d" ] && CANDIDATES+=("$d")
done
command -v node > /dev/null 2>&1 && CANDIDATES+=("$(command -v node)")
for c in "${CANDIDATES[@]:-}"; do
  if node_ok "$c"; then NODE_BIN="$c"; break; fi
done

if [ -n "$NODE_BIN" ]; then
  info "复用已有 Node: $NODE_BIN ($(node_version_of "$NODE_BIN"))，node:sqlite 可用"
else
  info "没找到可用的 Node，开始下载 v$NODE_VERSION ..."
  case "$ARCH" in
    aarch64|arm64) NODE_ARCH=arm64 ;;
    x86_64|amd64)  NODE_ARCH=x64 ;;
    *) die "不支持的架构：$ARCH（官方只发 arm64 / x64 包）" ;;
  esac
  ARCHIVE="node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.xz"
  RUNTIME_DIR="$INSTALL_DIR/.runtime"
  mkdir -p "$RUNTIME_DIR"
  TMP_NODE="$(mktemp -d)"
  GOT=0
  # 手机网络常常连不上 nodejs.org：内置几个国内镜像按顺序试
  for BASE in \
    "https://nodejs.org/dist/v${NODE_VERSION}" \
    "https://mirrors.nju.edu.cn/nodejs-release/v${NODE_VERSION}" \
    "https://registry.npmmirror.com/-/binary/node/v${NODE_VERSION}" \
    "https://mirrors.ustc.edu.cn/node/v${NODE_VERSION}" \
    "https://mirrors.aliyun.com/nodejs-release/v${NODE_VERSION}" ; do
    info "  尝试 $BASE"
    if curl -fsSL --retry 2 --connect-timeout 10 -o "$TMP_NODE/$ARCHIVE" "$BASE/$ARCHIVE" 2>/dev/null; then
      GOT=1
      if curl -fsSL --connect-timeout 8 -o "$TMP_NODE/SHASUMS256.txt" "$BASE/SHASUMS256.txt" 2>/dev/null; then
        if (cd "$TMP_NODE" && grep "  $ARCHIVE\$" SHASUMS256.txt | sha256sum -c - > /dev/null 2>&1); then
          info "  sha256 校验通过"
        else
          info "  sha256 校验失败，换下一个源"
          GOT=0
          continue
        fi
      fi
      break
    fi
  done
  [ "$GOT" = "1" ] || die "Node 下载失败（所有源都不通）。可手动下载 $ARCHIVE 后放进 $INSTALL_DIR/.runtime 再重跑：
  https://nodejs.org/dist/v${NODE_VERSION}/$ARCHIVE"
  info "解压到 $RUNTIME_DIR ..."
  tar -xJf "$TMP_NODE/$ARCHIVE" -C "$RUNTIME_DIR" || die "解压失败（需要 xz/tar，Ubuntu base 镜像自带）"
  rm -rf "$TMP_NODE"
  NODE_BIN="$RUNTIME_DIR/node-v${NODE_VERSION}-linux-${NODE_ARCH}/bin/node"
  node_ok "$NODE_BIN" || die "刚装的 Node 不可用（node:sqlite 探测失败）：$NODE_BIN"
  info "Node 就绪: $NODE_BIN ($(node_version_of "$NODE_BIN"))"
fi
NODE_BIN_DIR="$(cd "$(dirname "$NODE_BIN")" && pwd -P)"
export PATH="$NODE_BIN_DIR:$PATH"

step "3. 安装依赖"
cd "$INSTALL_DIR" || die "进不去 $INSTALL_DIR"
if [ "$SKIP_DEPS" = "1" ]; then
  info "跳过（--skip-deps）—— 请确保 node_modules 已就位"
elif [ -n "$OFFLINE_MODULES" ]; then
  [ -d "$OFFLINE_MODULES/ws" ] || die "--offline-node-modules 指的目录下没有 ws，确认指向的是 node_modules 本身：
  /path/to/node_modules   （里面直接是 ws/ undici/ silk-wasm/ ...）"
  info "从 $OFFLINE_MODULES 拷 node_modules（离线/弱网推荐）"
  rm -rf "$INSTALL_DIR/node_modules"
  cp -a "$OFFLINE_MODULES" "$INSTALL_DIR/node_modules" || die "拷贝 node_modules 失败"
else
  if [ -d "$INSTALL_DIR/node_modules/ws" ]; then
    info "node_modules 已存在，跳过 npm ci（要强制重装就删掉后重跑）"
  else
    [ -n "$NPM_REGISTRY" ] && "$NODE_BIN_DIR/npm" config set registry "$NPM_REGISTRY" > /dev/null 2>&1
    info "npm ci（只装运行时依赖，不含 eslint/happy-dom 这些开发依赖）"
    ( cd "$INSTALL_DIR" && "$NODE_BIN_DIR/npm" ci --omit=dev --no-audit --no-fund ) || \
      die "npm ci 失败。常见原因：网络（换 --npm-registry https://registry.npmmirror.com）、
  或手机内存不足（可先 setsid/关掉其他 App）。也可以改用 --offline-node-modules。"
  fi
fi
[ -d "$INSTALL_DIR/node_modules/ws" ] || die "node_modules 里没有 ws，依赖不完整"
NATIVE_COUNT="$(find "$INSTALL_DIR/node_modules" -name '*.node' 2>/dev/null | wc -l | tr -d ' ')"
info "依赖就绪：$(find "$INSTALL_DIR/node_modules" -type f | wc -l | tr -d ' ') 个文件，原生 .node 文件 ${NATIVE_COUNT} 个（应为 0，纯 JS/wasm）"

step "4. 写配置"
if [ -f "$DATA_DIR/config.json" ]; then
  info "已存在 $DATA_DIR/config.json —— 只补缺省字段，不覆盖已有值"
fi
CFG_ARGS=(
  --data-dir "$DATA_DIR"
  --console-port "$CONSOLE_PORT"
  --ws-port "$WS_PORT"
  --http-port "$HTTP_PORT"
)
[ -n "$TOKEN" ] && CFG_ARGS+=(--token "$TOKEN")
[ -n "$MODEL_BASE_URL" ] && CFG_ARGS+=(--model-base-url "$MODEL_BASE_URL")
[ -n "$MODEL_API_KEY" ] && CFG_ARGS+=(--model-api-key "$MODEL_API_KEY")
[ -n "$MODEL_NAME" ] && CFG_ARGS+=(--model "$MODEL_NAME")
[ -n "$ALLOW_GROUP" ] && CFG_ARGS+=(--allow-group "$ALLOW_GROUP")
"$NODE_BIN" --no-warnings "$SELF_DIR/config-init.mjs" "${CFG_ARGS[@]}" || die "写配置失败"
REAL_TOKEN="$("$NODE_BIN" -e 'const c=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));process.stdout.write(c.onebot?.accessToken||"")' "$DATA_DIR/config.json")"
REAL_CONSOLE_PORT="$("$NODE_BIN" -e 'const c=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));process.stdout.write(String(c.server?.port||3210))' "$DATA_DIR/config.json")"

step "5. 写安装记录"
cat > "$MARKER" <<JSON
{
  "kind": "phone",
  "installedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "installDir": "$INSTALL_DIR",
  "dataDir": "$DATA_DIR",
  "runDir": "$RUN_DIR",
  "nodeBin": "$NODE_BIN",
  "nodeVersion": "$(node_version_of "$NODE_BIN")",
  "consolePort": $REAL_CONSOLE_PORT,
  "onebotWsPort": $WS_PORT,
  "onebotHttpPort": $HTTP_PORT,
  "arch": "$ARCH"
}
JSON
info "已写 $MARKER"

chmod +x "$SELF_DIR"/ctl.sh "$SELF_DIR"/supervise.sh 2>/dev/null || true

step "完成。接下来三步"
cat <<EOS

1) NapCat 侧：开「WebSocket 服务器(3001)」+「HTTP 服务器(3000)」，token 与下面一致
   （模板：docs/assets/napcat-onebot11.example.json；具体位置与启动见 docs/PHONE.md）

   onebot.wsUrl    = ws://127.0.0.1:$WS_PORT
   onebot.httpUrl  = http://127.0.0.1:$HTTP_PORT
   token           = ${REAL_TOKEN:-（未设置，建议补一个）}

2) 启动服务（自带守护，进程挂了 5 秒后自动拉起）：
   bash scripts/phone/ctl.sh start
   bash scripts/phone/ctl.sh status
   bash scripts/phone/ctl.sh logs

3) 打开控制台：http://127.0.0.1:$REAL_CONSOLE_PORT
   手机同机浏览器/WebView 直接访问即可（proot 共享宿主网络）。
   首次要填：模型（baseUrl/apiKey/model）、群白名单、把运行模式从 observe 切到 active，
   否则**只记录不发言**。命令行走 ctl.sh mode active / ctl.sh allow <群号> 也行。

排障：bash scripts/phone/ctl.sh doctor
EOS
