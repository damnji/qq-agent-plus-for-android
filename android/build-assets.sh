#!/usr/bin/env bash
# 生成 APK 所需的四个 assets 包。
#
# 需要在 WSL / Linux 上跑（用到 tar、xz、npm）。Android 侧只有 GZIPInputStream，
# 所以所有包必须是 .tar.gz，不能用 .tar.xz。
#
# 用法：
#   bash android/build-assets.sh              # 全量构建
#   bash android/build-assets.sh --force      # 忽略已有产物，强制重建
#
# 产出（写到 android/app/assets/）：
#   rootfs.tar.gz         Ubuntu 24.04 arm64 基础系统（约 28MB）
#   node-arm64.tar.gz     Node 22 运行时（约 42MB，已裁掉 include/npm）
#   app.tar.gz            项目源码（约 3MB）
#   node_modules.tar.gz   运行时依赖（约 1.2MB，只含 dependencies）
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO="$(cd "$HERE/.." && pwd -P)"
OUT="$HERE/app/assets"
WORK="${QAP_ASSET_WORK:-$HOME/.cache/qap-apk-assets}"
FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

NODE_VERSION="${QAP_NODE_VERSION:-22.23.2}"
UBUNTU_BASE_URL="${QAP_UBUNTU_BASE_URL:-https://mirror.nju.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz}"

info() { printf '[assets] %s\n' "$*"; }
die()  { printf '[assets] 错误：%s\n' "$*" >&2; exit 1; }

command -v tar > /dev/null || die '缺 tar'
command -v gzip > /dev/null || die '缺 gzip'
command -v xz > /dev/null || die '缺 xz（apt-get install -y xz-utils）'
command -v curl > /dev/null || die '缺 curl'

mkdir -p "$OUT" "$WORK"

# ── 1. rootfs ──
if [ "$FORCE" = "1" ] || [ ! -s "$OUT/rootfs.tar.gz" ]; then
  info "准备 Ubuntu base rootfs..."
  BASE_DIR="$WORK/ubuntu-base"
  if [ ! -d "$BASE_DIR/usr/bin" ]; then
    if [ ! -s "$WORK/ubuntu-base.tar.gz" ]; then
      info "下载 $UBUNTU_BASE_URL"
      curl -fL --retry 3 -m 900 -o "$WORK/ubuntu-base.tar.gz" "$UBUNTU_BASE_URL" \
        || die "下载失败。可换镜像：QAP_UBUNTU_BASE_URL=... bash android/build-assets.sh"
    fi
    rm -rf "$BASE_DIR" && mkdir -p "$BASE_DIR"
    tar -xzf "$WORK/ubuntu-base.tar.gz" -C "$BASE_DIR" || die '解压 rootfs 失败'
  fi
  info "打包 rootfs（排除 dev/proc/sys/run —— Android 上会 -b /dev 覆盖）"
  tar -C "$BASE_DIR" \
      --exclude='./dev/*' --exclude='./proc/*' --exclude='./sys/*' --exclude='./run/*' \
      --owner=0 --group=0 --numeric-owner \
      -czf "$OUT/rootfs.tar.gz" . 2>/dev/null
  info "rootfs.tar.gz = $(du -h "$OUT/rootfs.tar.gz" | cut -f1)"
else
  info "rootfs.tar.gz 已存在，跳过（--force 重建）"
fi

# ── 2. Node ──
if [ "$FORCE" = "1" ] || [ ! -s "$OUT/node-arm64.tar.gz" ]; then
  info "准备 Node v$NODE_VERSION arm64..."
  NODE_XZ="$WORK/node-v$NODE_VERSION-linux-arm64.tar.xz"
  if [ ! -s "$NODE_XZ" ]; then
    GOT=0
    for BASE in \
      "https://nodejs.org/dist/v$NODE_VERSION" \
      "https://mirrors.nju.edu.cn/nodejs-release/v$NODE_VERSION" \
      "https://mirrors.ustc.edu.cn/node/v$NODE_VERSION" ; do
      info "  尝试 $BASE"
      if curl -fL --retry 2 --connect-timeout 10 -m 900 \
           -o "$NODE_XZ" "$BASE/node-v$NODE_VERSION-linux-arm64.tar.xz"; then
        GOT=1; break
      fi
    done
    [ "$GOT" = "1" ] || die "Node 下载失败"
  fi
  NODE_MIN="$WORK/node-min"
  rm -rf "$NODE_MIN" && mkdir -p "$NODE_MIN"
  tar -xJf "$NODE_XZ" -C "$NODE_MIN" --strip-components=1 2>/dev/null
  # 裁掉 APK 里用不到的大件：include（头文件 65MB）、share、npm（19MB）
  # 我们用随包 node_modules，不需要 npm；但保留 corepack 以备调试
  rm -rf "$NODE_MIN/include" "$NODE_MIN/share" "$NODE_MIN/lib/node_modules/npm"
  rm -f "$NODE_MIN/bin/npm" "$NODE_MIN/bin/npx" "$NODE_MIN/CHANGELOG.md" "$NODE_MIN/README.md"
  info "  裁剪后 $(du -sh "$NODE_MIN" | cut -f1)，bin: $(ls "$NODE_MIN/bin" | tr '\n' ' ')"
  tar -C "$NODE_MIN" -czf "$OUT/node-arm64.tar.gz" . 2>/dev/null
  info "node-arm64.tar.gz = $(du -h "$OUT/node-arm64.tar.gz" | cut -f1)"
else
  info "node-arm64.tar.gz 已存在，跳过（--force 重建）"
fi

# ── 3. 项目源码 ──
if [ "$FORCE" = "1" ] || [ ! -s "$OUT/app.tar.gz" ]; then
  info "打包项目源码..."
  tar -C "$REPO" \
      --exclude=node_modules --exclude=.git --exclude=data \
      --exclude=android --exclude=.github --exclude=run --exclude=backups \
      -czf "$OUT/app.tar.gz" . 2>/dev/null
  info "app.tar.gz = $(du -h "$OUT/app.tar.gz" | cut -f1)"
else
  info "app.tar.gz 已存在，跳过"
fi

# ── 4. 运行时依赖 ──
if [ "$FORCE" = "1" ] || [ ! -s "$OUT/node_modules.tar.gz" ]; then
  info "安装运行时依赖（--omit=dev，不含 eslint 等开发依赖）..."
  NM_STAGE="$WORK/node-modules"
  rm -rf "$NM_STAGE" && mkdir -p "$NM_STAGE"
  cp "$REPO/package.json" "$REPO/package-lock.json" "$NM_STAGE/"
  ( cd "$NM_STAGE" && npm ci --omit=dev --no-audit --no-fund 2>&1 | tail -2 )
  [ -d "$NM_STAGE/node_modules/ws" ] || die 'npm ci 后没有 ws'
  # 确认没混进原生模块（本项目应 0 个，纯 JS/wasm）
  NATIVE=$(find "$NM_STAGE/node_modules" -name '*.node' 2>/dev/null | wc -l)
  info "  包数 $(ls "$NM_STAGE/node_modules" | wc -l)，原生 .node 文件 $NATIVE 个（应为 0）"
  tar -C "$NM_STAGE" -czf "$OUT/node_modules.tar.gz" node_modules 2>/dev/null
  info "node_modules.tar.gz = $(du -h "$OUT/node_modules.tar.gz" | cut -f1)"
else
  info "node_modules.tar.gz 已存在，跳过"
fi

info ''
info '===== 汇总 ====='
ls -lh "$OUT"/*.tar.gz 2>/dev/null | awk '{printf "  %-24s %s\n", $9, $5}'
TOTAL=$(du -cb "$OUT"/*.tar.gz 2>/dev/null | tail -1 | cut -f1)
info "合计 $(( TOTAL / 1024 / 1024 )) MB（APK 里还会再压一次）"
info ''
info '下一步：'
info '  Windows: .\android\build.ps1'
