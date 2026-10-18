#!/usr/bin/env bash
# 从 **私有** 仓库取一份源码到当前机器（手机容器里也能用），不需要 git。
#
# 为什么需要它：本仓库是私人仓库，`git clone https://github.com/...` 会要凭据，
# 手机上直接 clone 会卡在 Username/Password。这个脚本改用 GitHub API 的 tarball 接口，
# 只要一个 token 就能拿源码；而且：
#   - token 从**环境变量**读，不走命令行参数（`ps` 里看不到，也不进 shell history）；
#   - 取完不会把 token 写到任何文件（不生成 .git，也没有 remote 可泄漏）；
#   - 只要源码、不要 472 条历史 → 下载量小很多（手机流量友好）。
#
# 用法：
#   GITHUB_TOKEN=ghp_xxx bash scripts/phone/fetch.sh                    # 取 main 到 ./qq-agent-plus
#   GITHUB_TOKEN=ghp_xxx bash scripts/phone/fetch.sh /root/qq-agent-plus
#   GITHUB_TOKEN=ghp_xxx bash scripts/phone/fetch.sh --ref v0.9.1 /root/qq-agent-plus
#   GITHUB_TOKEN=ghp_xxx bash scripts/phone/fetch.sh --owner me --repo my-fork /root/app
#
# 需要一个 **classic token 的 repo scope**，或 fine-grained token 的
# 「Contents: Read-only」权限（只读就够了，这个脚本不写远端）。
#
# 安全提醒：token 用完请去 https://github.com/settings/tokens 吊销；
# 只读 token 也建议设短有效期。
set -uo pipefail

die() { printf '[phone-fetch] %s\n' "$*" >&2; exit 1; }
info() { printf '[phone-fetch] %s\n' "$*"; }

OWNER="${QAP_REPO_OWNER:-damnji}"
REPO="${QAP_REPO_NAME:-qq-agent-plus-for-android}"
REF="main"
TARGET=""

while [ $# -gt 0 ]; do
  case "$1" in
    --ref) REF="${2:?--ref 需要取值}"; shift 2 ;;
    --owner) OWNER="${2:?--owner 需要取值}"; shift 2 ;;
    --repo) REPO="${2:?--repo 需要取值}"; shift 2 ;;
    -h|--help)
      sed -n '2,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    --*) die "未知参数：$1" ;;
    *) TARGET="$1"; shift ;;
  esac
done
[ -n "$TARGET" ] || TARGET="./$REPO"

# token 只从环境变量读：命令行参数会出现在 ps 输出和 shell history 里
TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-${QAP_GITHUB_TOKEN:-}}}"
if [ -z "$TOKEN" ]; then
  cat >&2 <<'EOS'
[phone-fetch] 缺少 token。

  这个仓库是**私人仓库**，取源码必须带凭据。任选一种：

  1) 临时给本次命令（推荐，不落盘）：
       GITHUB_TOKEN=ghp_你的token bash scripts/phone/fetch.sh /root/qq-agent-plus

  2) 先 export（同一个 shell 内后续命令都能用）：
       export GITHUB_TOKEN=ghp_你的token

  3) 完全不想在手机上放 token → 在电脑上打包再传（见 docs/PHONE.md §3.1）：
       git clone https://github.com/C你的用户名/qq-agent-plus-for-android.git
       tar -czf qap.tar.gz --exclude=.git --exclude=data qq-agent-plus-for-android
       # 传到手机后解压，再跑 scripts/phone/install.sh

  取 token：https://github.com/settings/tokens （classic 勾 repo；
  或 fine-grained：只选这个仓库 + Contents: Read-only）
EOS
  exit 2
fi

# ── 依赖检查（ubuntu-base 精简 rootfs 里 curl/tar 都要先装） ──
command -v curl > /dev/null 2>&1 || die "缺 curl：apt-get install -y curl ca-certificates"
command -v tar > /dev/null 2>&1 || die "缺 tar：apt-get install -y tar"

# ── token 自检：拿得到仓库说明权限没问题，拿不到就给出人话原因 ──
info "校验 token 与仓库访问权限..."
api_code="$(curl -s -o /dev/null -w '%{http_code}' -m 20 \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Accept: application/vnd.github+json' \
  "https://api.github.com/repos/$OWNER/$REPO" 2>/dev/null)"
case "$api_code" in
  200) info "权限 OK（$OWNER/$REPO）" ;;
  401) die "token 无效或已失效（HTTP 401）。去 https://github.com/settings/tokens 重新生成。" ;;
  403) die "token 有效但无权访问 $OWNER/$REPO（HTTP 403）。细粒度 token 要选中这个仓库并把 Contents 设为 Read-only。" ;;
  404) die "仓库不存在或 token 看不到它（HTTP 404）。检查 --owner/--repo，或 token 没有选中这个仓库。" ;;
  000) die "连不上 api.github.com（网络问题）。容器内需要先 \`apt-get install -y ca-certificates\`；
  若所在网络屏蔽了 api.github.com，请改用「电脑打包传手机」那条路（docs/PHONE.md §3.1）——
  注意：第三方 GitHub 加速（ghfast.top 等）**不带凭据**，对私人仓库无效。" ;;
  *) die "访问仓库失败（HTTP $api_code）" ;;
esac

# ── 下载 tarball ──
# codeload/github 的 tarball 接口跟随重定向；只取源码，不含 .git 历史
URL="https://api.github.com/repos/$OWNER/$REPO/tarball/$REF"
TMP_TAR="$(mktemp "${TMPDIR:-/tmp}/qap-fetch-XXXXXX.tar.gz")" || die "建不了临时文件"
cleanup() { rm -f "$TMP_TAR"; }
trap cleanup EXIT INT TERM

info "下载 $REF 源码（相比 clone 少了 .git，体积小很多）..."
# -L 跟随重定向；--fail 让 4xx/5xx 变成非零退出
if ! curl -fL -m 900 --retry 2 \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Accept: application/vnd.github+json' \
  -o "$TMP_TAR" "$URL" 2>/dev/null; then
  die "下载失败（$URL）。网络不稳可重试；或用 docs/PHONE.md §3.1 的电脑打包路线。"
fi
[ -s "$TMP_TAR" ] || die "下载到的文件是空的，可能是 token 在中途失效或被重定向到登录页"

BYTES="$(wc -c < "$TMP_TAR" | tr -d ' ')"
info "已下载 $(( BYTES / 1024 )) KB"

# ── 解压（GitHub tarball 顶层是 owner-repo-<sha>/，用 --strip-components=1 去掉） ──
if [ -d "$TARGET" ] && [ -n "$(ls -A "$TARGET" 2>/dev/null)" ]; then
  die "目标目录已存在且非空：$TARGET
  想覆盖就手动删掉它再重跑（脚本不替你删，怕误删你的 data/）：
    rm -rf '$TARGET'    # 确认里面没有你需要的东西"
fi
mkdir -p "$TARGET" || die "建不了目标目录：$TARGET"
info "解压到 $TARGET ..."
if ! tar -xzf "$TMP_TAR" -C "$TARGET" --strip-components=1 2>/dev/null; then
  # 极老的 tar 不支持 --strip-components 之外的解压路径问题；给出可执行的下一步
  die "解压失败。确认 tar 可用（apt-get install -y tar），或用：
  tar -xzf <下载的文件> -C '$TARGET' --strip-components=1"
fi

# ── 自检：确认拿到的是「带手机端工具链」的仓库，而不是上游原版 ──
if [ ! -f "$TARGET/scripts/phone/install.sh" ]; then
  die "解压完没找到 $TARGET/scripts/phone/install.sh。
  说明这个仓库/分支里没有手机端工具链（可能是上游原版）。
  本脚本默认取 $OWNER/$REPO@$REF，确认一下是不是你要的那个。"
fi

info "完成。校验通过：scripts/phone/install.sh 已就位"
info ""
info "接下来（在容器内）："
info "  cd $TARGET"
info "  bash scripts/phone/install.sh"
info ""
info "注意：这样取到的是**快照**，没有 .git，不能 git pull 更新；"
info "要更新就重跑本脚本（换目录）或改用 §3.2 的 PAT clone 路线。"
