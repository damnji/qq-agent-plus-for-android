# 手机端部署（安卓 + proot Ubuntu arm64）

> 目标：在**一台安卓手机上**跑起 `qq-agent-plus`：App 内一个 proot 容器（Ubuntu 24.04 arm64）
> 里同时跑协议端 NapCat 和本项目，扫码登录 QQ 后群里就能对话。不需要电脑、不需要服务器。
>
> 本页只讲手机端这条路线。**服务器**上的常规部署（systemd + Docker + SnowLuma）见
> [LINUX.md](LINUX.md)；宝塔面板见 [BAOTA.md](BAOTA.md)。

## 0. 先读这四条（踩过才知道的坑）

1. **`install.sh` 有两个，别跑错**。
   本页所有命令里的 `install.sh` / `ctl.sh` 都指 `scripts/phone/` 下的手机版。
   仓库**根目录**也有一个 `install.sh`，那是**服务器**版（内部调 `deploy-all.sh`，
   依赖 systemd + Docker）。在手机上跑根目录那个会失败 —— 必须写全路径：
   `bash scripts/phone/install.sh`。
2. **别 clone 上游原版**。
   手机端改动在 `damnji/qq-agent-plus-for-android`（公开仓库，匿名可 clone）。
   上游 `sakurawwwxh/qq-agent-plus` **没有** `scripts/phone/`，clone 它会让本页所有命令失败。
3. **`proot-distro install ubuntu` 装的是 latest，不是 24.04**。
   实测 2026-10 时它给的是 **26.04.1**。本页按 24.04 写（验证过的版本），
   所以要**显式指定版本**：`proot-distro install ubuntu:24.04`（见 §2.3）。
4. **手机端默认不会说话**。装完是 `runtime.mode=observe`（只记录不发言）+ 白名单为空
   （不回复任何群）。要它真的回消息，必须做 §6 里那三件事（模型 / 白名单 / 切 active）。

## 0.1 先看结论：三条路线

| 路线 | 手机要跑什么 | 需要什么 | 适合谁 |
| --- | --- | --- | --- |
| **A. 全在手机（推荐）** | proot 容器里跑 NapCat + qq-agent-plus | 安卓 8+、arm64、6GB 内存建议、能装 App/用 Termux | 想「一部手机搞定」 |
| **B. 手机跑 Agent，协议端在服务器** | 只跑 qq-agent-plus | 一台能跑 NapCat 的机器（VPS/家里电脑） | 已有服务器，只想手机上看控制台 |
| **C. 只用手机看控制台** | 什么都不跑，浏览器开 | 服务器上已部署好 | 已有 LINUX.md 那套 |

本页主线是 **A**，并给出 A 的两种外壳：

- **A1：Termux + proot**（不需要写安卓代码，30 分钟能跑通）——**先做这个**；
- **A2：做成一个 APK**（内置 proot + rootfs + 一键装，像 AstrBot 泡泡版那样）——
  工作量在安卓侧，见 [§7](#7-进阶做成-apk壳-apk-壳路线)。

> 强烈建议先用 A1 验证「容器里能跑通」，再谈打 APK。
> A1 跑不通，APK 里同样跑不通，只是更难查。

## 1. 为什么能跑（三个前提，先确认）

1. **本项目与 CPU 架构无关**：运行时依赖只有 5 个，全部是纯 JS / wasm
   （`jce` / `node-html-parser` / `silk-wasm` / `undici` / `ws`），`node_modules` 里
   **一个 `.node` 原生模块都没有**。所以 arm64 上 `npm ci` 装出来就能用，不需要交叉编译。
2. **不需要 systemd / Docker**：`node src/server.js` 直接就能起。
   仓库里的 `deploy.sh` / `deploy-all.sh` / `manage.sh` 是**服务器**路线，硬依赖
   `systemctl --user`，**手机上不要用**（proot 里没有 init，会直接报错退出）。
3. **proot 没有网络隔离**：容器里的 `127.0.0.1:3210` 就是手机的 `127.0.0.1:3210`，
   所以手机浏览器/App 的 WebView 直接访问 `http://127.0.0.1:3210` 就能打开控制台，
   不需要端口转发。

剩下的全部难点只有两个：**NapCat 在 proot 里能不能稳定跑**，以及**安卓会不会杀掉后台**。
本页用「配置模板 + 守护脚本 + 前台服务」分别对付它们。

## 2. 准备

### 2.1 手机侧

- 安卓 8.0+，**arm64**（`uname -m` 应是 `aarch64`；32 位/armv7 不行）；
- 建议 6GB 内存以上（QQ 客户端本身就吃内存），存储留 3GB 以上；
- 关闭省电策略/B 电池优化，允许 App「后台运行」「自启动」（各家叫法不同：
  小米=自启动+省电策略无限制，华为=应用启动管理手动，OPPO/vivo=允许后台运行）。

### 2.2 装 Termux（走 F-Droid，别用 Play 商店那个旧版）

<https://f-droid.org/packages/com.termux/> → 下载安装。
Play 商店版 Termux 已停止更新且 API 太旧，`proot-distro` 会出问题。

打开 Termux 后先做三件事：

```bash
pkg update -y && pkg upgrade -y
pkg install -y proot-distro openssh curl nano
termux-wake-lock          # 让系统别在息屏时冻结 Termux（非常重要）
```

> `termux-wake-lock` 每次重启手机后要重打一次；也可以用 Termux 的通知栏「Acquire wakelock」。

### 2.3 装容器（**要指定版本**）

```bash
# ⚠️ 不要写 `proot-distro install ubuntu`：那装的是 latest（实测 2026-10 为 26.04.1），
#    不是本页验证过的 24.04。要显式带版本号。
proot-distro install ubuntu:24.04
proot-distro login ubuntu
```

进去以后确认身份：

```bash
uname -m          # aarch64
. /etc/os-release && echo "$PRETTY_NAME"   # 应是 Ubuntu 24.04.x LTS
id -u             # 0（proot 伪装 root，但并不是真的 root）
```

如果 `$PRETTY_NAME` 打出来是 26.04 或别的大版本，说明装成了 latest。
删掉重装：`proot-distro remove ubuntu && proot-distro install ubuntu:24.04`。

> 为什么强调版本：本项目的产物只在 24.04 上实测过（见 `docs/PHONE.md` 与仓库
> `scripts/phone/`）。26.04 的 `apt` 源、软件包名（例如 `libasound2t64`）与
> 24.04 有差异，NapCat 安装器的依赖列表是按 24.04 写的，跨版本容易卡在装依赖。
> 想用 26.04 的话，把 `scripts/phone/install.sh` 与 NapCat 安装器报的缺包逐个补齐——可以跑，但要自己排。
>
> `proot-distro install ubuntu:24.04` 需要你的 proot-distro 是**新版本**（OCI 镜像那一代）。
> 老版本（插件式，只有 `ubuntu` 一个名字、不带版本）请先在 Termux 里 `pkg upgrade proot-distro`。


> 容器里 `id -u` 是 0 只是伪装。**不要**在里面跑 `systemctl`（没有）、
> 也不要指望 `apt` 装的服务能开机自启（重启手机就没了，靠 §5 的守护脚本）。

## 3. 装本项目

> 仓库 `damnji/qq-agent-plus-for-android` 是**公开**的，匿名即可 clone，
> 不需要 token、也不需要账号。GitHub 加速站（ghfast.top / gh-proxy 等）同样可用。

### 3.1 方式一：容器内直接 clone（**推荐，最省事**）

```bash
apt-get update && apt-get install -y curl git ca-certificates tar xz-utils
git clone https://github.com/damnji/qq-agent-plus-for-android.git /root/qq-agent-plus
cd /root/qq-agent-plus

# 注意路径：是 scripts/phone/install.sh，不是根目录那个 install.sh（那是服务器版）
bash scripts/phone/install.sh
```

`scripts/phone/install.sh` 会自己下载 Node 22.22+（官方 arm64 包，多个国内镜像轮着试）、
`npm ci` 装依赖、写 `data/config.json`、写安装记录。

> **GitHub 直连不通时**（国内很常见），加个加速前缀即可：
>
> ```bash
> git clone https://ghfast.top/https://github.com/damnji/qq-agent-plus-for-android.git /root/qq-agent-plus
> ```
>
> 备注：**不要 clone 上游 `sakurawwwxh/qq-agent-plus`** —— 那是原版，
> **没有** `scripts/phone/` 这套手机端工具，本页所有命令都会失败。

> 判断有没有搞错仓库/脚本，跑完看输出：
>
> ```bash
> ls install.sh scripts/phone/install.sh    # 两个都应该在
> head -3 scripts/phone/install.sh          # 第 2 行应是「手机端（proot Ubuntu 24.04 ...）」
> ```
>
> - 只有 `install.sh`、没有 `scripts/phone/install.sh` ⇒ 拿到的是**上游原版**（没有手机端工具）。
> - 看到「一键安装入口（"小白版"）」⇒ 那是根目录的**服务器版**，路径写错了。

### 3.2 方式二：电脑打包传手机（**弱网 / 手机没 git 时**）

电脑上（Windows/macOS/Linux 都行；本项目依赖是纯 JS，x86 电脑装出来的 `node_modules`
在 arm64 手机上照样能用）：

```bash
# 1) 电脑上克隆（公开仓库，免登录）
git clone https://github.com/damnji/qq-agent-plus-for-android.git
cd qq-agent-plus-for-android

# 2) 装好运行时依赖再打包（省得手机上下载）
npm ci --omit=dev

# 3) 打包：排除 .git 和 data/（data 里可能有你的密钥，别带过去）
tar -czf qap.tar.gz --exclude=.git --exclude=data --exclude=node_modules .
#   想连 node_modules 一起打包（手机完全不用联网装依赖）就去掉上面 --exclude=node_modules
```

把 `qap.tar.gz` 传到手机（微信文件传输助手 / USB / `adb push` / 局域网 `python -m http.server`），
然后在**手机 Termux** 里：

```bash
# 放 Termux 私有目录（不要放 /sdcard，那里不能执行、权限也乱）
cp /sdcard/Download/qap.tar.gz ~/
mkdir -p ~/qq-agent-plus
tar -xzf ~/qap.tar.gz -C ~/qq-agent-plus

# 进容器，把目录 bind 进去（--bind 每次 login 都要带）
proot-distro login ubuntu --bind ~/qq-agent-plus:/root/qq-agent-plus
```

容器内：

```bash
cd /root/qq-agent-plus
# 带了 node_modules 就加 --offline-node-modules，否则让它联网装
bash scripts/phone/install.sh --offline-node-modules /root/qq-agent-plus/node_modules
```

> 优点：不需要手机上装 git、不怕 GitHub 被墙、包可以反复拷。
> 缺点：每次更新要重新打包传一次。

### 3.3 方式三：`fetch.sh` 取快照（不需要 git，一条命令）

仓库自带 `scripts/phone/fetch.sh`，用 GitHub API 拉 tarball。**公开仓库，不需要 token**：

```bash
# 容器内先具备基础工具
apt-get update && apt-get install -y curl ca-certificates tar

# 先拿这一个脚本（公开仓库，免 token；被墙就加 ghfast.top 前缀）
curl -fL -o /tmp/fetch.sh \
  https://raw.githubusercontent.com/damnji/qq-agent-plus-for-android/main/scripts/phone/fetch.sh

bash /tmp/fetch.sh /root/qq-agent-plus
```

也可以直接 clone 后用仓库里的那份：`bash scripts/phone/fetch.sh /root/app2`。

- 约 **2.8MB**（不要 `.git` 历史，比 clone 的 44MB 小很多，手机流量友好）；
- 解压后**没有 `.git`**，也就没有 remote 可残留凭据；
- 取到的是**快照**，不能 `git pull`；要跟更新请用方式一。

> 取**私有 fork** 时才需要 token（本仓库本身已公开）：
> `GITHUB_TOKEN=ghp_xxx bash scripts/phone/fetch.sh --owner me --repo my-fork /root/app`
> token 只从环境变量读，不进 `ps` 输出与 shell history，也不会落盘。

### 3.4 三种方式怎么选

| 场景 | 选 |
| --- | --- |
| 容器能连 GitHub（或加加速站） | **3.1 clone**（能 `git pull` 更新） |
| 网络差 / 不想在手机装 git | 3.2 电脑打包传手机 |
| 只想要源码、不想 clone 44MB 历史 | 3.3 `fetch.sh`（2.8MB） |

> 无论哪种，装完都做一次自检，确认拿到的是**带手机端工具链**的那份：
>
> ```bash
> ls install.sh scripts/phone/install.sh    # 两个都应该在
> head -3 scripts/phone/install.sh          # 第 2 行应是「手机端（proot Ubuntu 24.04 ...）」
> ```
>
> - 只有 `install.sh`、没有 `scripts/phone/install.sh` ⇒ 拿到的是**上游原版**（没有手机端工具）。
> - 看到「一键安装入口（"小白版"）」⇒ 那是根目录的**服务器版**，路径写错了。

### 3.5 装完你应该看到什么

```
=== 完成。接下来三步 ===
1) NapCat 侧：开「WebSocket 服务器(3001)」+「HTTP 服务器(3000)」，token 与下面一致
2) 启动服务（自带守护，进程挂了 5 秒后自动拉起）： bash scripts/phone/ctl.sh start
3) 打开控制台：http://127.0.0.1:3210
```

此时**先别急着 start**，NapCat 还没装。但可以先跑一次自检看哪几项没到位：

```bash
bash scripts/phone/ctl.sh doctor
```

## 4. 装并配置 NapCat（协议端）

### 4.1 装

官方 rootless 安装器（**在容器内**，以当前用户身份，不需要 sudo）：

```bash
apt-get install -y sudo curl git xz-utils jq
bash <(curl -fsSL https://ghfast.top/https://raw.githubusercontent.com/NapNeko/NapCat-Installer/main/script/install.sh)
```

安装器会问几件事，选：

- 安装方式 → **本地安装 (Rootless)** / Shell 安装；
- TUI-CLI → 可以选 `n`（少一个变量）；
- 它做的事：装系统依赖（含 Xvfb、libnss3、libgbm1、libasound2t64 等）→
  下载 **arm64 的 LinuxQQ** → `dpkg -x` 解到 `$HOME/Napcat/opt/QQ`（不装进系统）→
  把 `NapCat.Shell.zip` 解到 `resources/app/app_launcher/napcat/` →
  写 `loadNapCat.js` 并把 QQ 的 `package.json` 的 `main` 指过去。

> 装完 QQ 在 `~/Napcat/opt/QQ/qq`，NapCat 在 `~/Napcat/opt/QQ/resources/app/app_launcher/napcat/`。
> 想卸载/重装就删 `~/Napcat`。

### 4.2 让它把配置写到持久化目录（**关键，别跳过**）

NapCat 默认把 `config/`、`logs/`、`plugins/` 放在程序自己的目录里
（`NAPCAT_WORKDIR` 没设时 = NapCat 模块所在目录）。
容器重装/清数据时这些会跟着没，所以**一定要设 `NAPCAT_WORKDIR`**：

```bash
mkdir -p /root/napcat-work/config /root/napcat-work/logs
export NAPCAT_WORKDIR=/root/napcat-work
```

想更保险就指到手机的持久化目录（在 Termux 侧 bind 进容器）：

```bash
# Termux 里
proot-distro login ubuntu --bind /sdcard/qq-agent:/qq-agent
# 容器里
export NAPCAT_WORKDIR=/qq-agent/napcat-work
```

### 4.3 配 OneBot（**最容易错的一步**）

NapCat 的 OneBot 配置在 **`$NAPCAT_WORKDIR/config/onebot11.json`**，
首次登录 QQ 后会复制成 **`onebot11_<QQ号>.json`**（实际生效的是带 QQ 号那份）。

**本项目是 OneBot 的「客户端」角色**：它主动连出去收事件、再走 HTTP 发动作。
所以 NapCat 要开的是 **`websocketServers`（正向 WS 服务端）** 和 **`httpServers`**。

直接抄模板（`docs/assets/napcat-onebot11.example.json` 就是这个）：

```bash
cat > "$NAPCAT_WORKDIR/config/onebot11.json" <<'JSON'
{
  "network": {
    "httpServers": [
      { "name": "qq-agent-http", "enable": true, "host": "127.0.0.1", "port": 3000,
        "enableCors": true, "enableWebsocket": false, "messagePostFormat": "array",
        "token": "把这里换成一串长随机字符", "debug": false }
    ],
    "httpSseServers": [], "httpClients": [],
    "websocketServers": [
      { "name": "qq-agent-ws", "enable": true, "host": "127.0.0.1", "port": 3001,
        "messagePostFormat": "array", "reportSelfMessage": false,
        "token": "把这里换成一串长随机字符", "enableForcePushEvent": true,
        "debug": false, "heartInterval": 30000 }
    ],
    "websocketClients": [], "plugins": []
  },
  "musicSignUrl": "", "enableLocalFile2Url": false, "parseMultMsg": false,
  "imageDownloadProxy": "",
  "timeout": { "baseTimeout": 10000, "uploadSpeedKBps": 256, "downloadSpeedKBps": 256, "maxTimeout": 1800000 }
}
JSON
```

三个必查点：

1. **是 `websocketServers` 不是 `websocketClients`**。
   AstrBot 那类框架是「服务端角色」，NapCat 才配 `websocketClients`（反向 WS）；
   本项目是客户端，配成 `websocketClients` 会**一个事件都收不到**。
2. **两个 `token` 要和本项目一致**（见 §4.4）。留空 `""` = 不校验，手机上别这么干。
3. **WS 地址不要加 `/api`**。NapCat 里 `ws://host:3001/api` 是「只收动作、不收事件」的连接，
   `ws://host:3001` 才是又能收事件又能发动作。写错的表现是「连上了但没有消息」。

> 改完不用重启 QQ，NapCat 会热重载适配器；但**必须先登录 QQ**，登录前端口不会监听。

### 4.4 把 token 写进本项目

```bash
cd /root/qq-agent-plus
bash scripts/phone/ctl.sh stop 2>/dev/null
# 用安装脚本重跑一次最省事：它只补缺、不覆盖你填过的值
bash scripts/phone/install.sh --token "$(cat /dev/urandom | head -c 24 | base64 | tr -d '/+=')"
# 或者手工编辑 data/config.json 的 onebot.accessToken / httpAccessToken
```

`data/config.json` 里对应的是：

```json
"onebot": {
  "wsUrl": "ws://127.0.0.1:3001",
  "httpUrl": "http://127.0.0.1:3000",
  "accessToken": "<与 NapCat 的 token 一致>",
  "httpAccessToken": "<同上>",
  "wsHeartbeat": "auto"
}
```

`wsHeartbeat` **保持 `auto`**。NapCat 对协议层 PING 的处理有坑（收到就断连接），
`auto` 是「遇到这种对端之后不再发 ping」，是踩出来的默认值。

### 4.5 启动 QQ + NapCat

容器里没有 systemd，也没有图形界面，用 Xvfb 起一个虚拟屏幕：

```bash
export NAPCAT_WORKDIR=/root/napcat-work
cd ~/Napcat/opt/QQ
xvfb-run -a ./qq --no-sandbox -q <你的QQ号>
```

第一次要在手机上看二维码：`xvfb-run` 起的 QQ 没有窗口，扫码要另想办法——

- **推荐**：开 NapCat 的 WebUI（`$NAPCAT_WORKDIR/config/webui.json` 里有端口和 token），
  手机浏览器打开 `http://127.0.0.1:<webui端口>/webui`，里面能看到二维码并完成登录；
- 或者用 NapCat 的 `napcat` TUI/CLI（安装器里选 `y` 才会装）。

后台跑（关掉终端也不停）：

```bash
screen -dmS napcat bash -lc 'export NAPCAT_WORKDIR=/root/napcat-work; cd ~/Napcat/opt/QQ && xvfb-run -a ./qq --no-sandbox -q <QQ号>'
# 回到这个会话：screen -r napcat    离开：Ctrl+A 然后 D
```

> `--no-sandbox` 在 proot 下是必须的（QQ 内嵌 Chromium 的沙箱在 proot 里起不来）。
> 若 QQ 启动即崩，加环境变量 `NAPCAT_FORCE_NODE_PROCESS=1` 再试
> （让协议端跑在 Node 进程里，绕开 Worker/注入相关问题）。

## 5. 启动本项目

```bash
cd /root/qq-agent-plus
bash scripts/phone/ctl.sh start
bash scripts/phone/ctl.sh status
```

`start` 会把 `scripts/phone/supervise.sh` 挂到后台，它负责：

- 起 `node src/server.js`，退出就 5 秒后重起（≈ `Restart=always`）；
- 60 秒内连续秒退 10 次就退避 60 秒并打日志（配置写错时不会把手机烧热）；
- `scripts/phone/ctl.sh stop` 时先 `SIGTERM` 给 node（server.js 自己会优雅关闭 sqlite），30 秒不退才 `kill -9`。

常用命令：

```bash
bash scripts/phone/ctl.sh status      # 进程 + 控制台 + OneBot 连接 + 运行模式 + 模型
bash scripts/phone/ctl.sh logs 100    # 最近 100 行服务日志
bash scripts/phone/ctl.sh logs -f     # 跟踪
bash scripts/phone/ctl.sh doctor      # 全链路自检（Node/依赖/配置/端口/NapCat 连通）
bash scripts/phone/ctl.sh restart
bash scripts/phone/ctl.sh stop
bash scripts/phone/ctl.sh token       # 打印带 token 的控制台 URL
bash scripts/phone/ctl.sh backup      # 备份 data/（会先 WAL checkpoint）
```

## 6. 首次配置（**不做这步它不会说话**）

浏览器打开 `http://127.0.0.1:3210`（或 `scripts/phone/ctl.sh token` 打印的 URL）。

三件事必须做，少一件就「收得到消息但不回复」：

| 项目 | 在哪 | 不做的后果 |
| --- | --- | --- |
| **模型**（baseUrl / apiKey / model） | 控制台「设置 → 模型」，或 `scripts/phone/install.sh --model-base-url/--model-api-key/--model` | 日志打印 `模型: （未设置，请在设置里选择）`，永不回复 |
| **群白名单** | 控制台「允许的群」，或 `scripts/phone/ctl.sh allow <群号>` | 白名单为空时**默认不回复任何群**（安全默认） |
| **运行模式 → active** | 控制台右上角，或 `scripts/phone/ctl.sh mode active` | 默认 `observe`：只记录、不发言 |

命令行走一遍最快：

```bash
bash scripts/phone/ctl.sh allow 123456789
bash scripts/phone/ctl.sh mode active
bash scripts/phone/ctl.sh status
```

预期在 `status` 里看到：

```
OneBot   : 已连接
运行模式 : active
模型     : deepseek-chat
```

然后往群里发一句「在吗」，`scripts/phone/ctl.sh logs -f` 里应出现 `[发送 -> group:...] ...`。

> 触发概率默认是 **100%（任何消息都回）**。觉得太吵就去控制台把滑条往下调；
> 调到 0 就只剩「被 @」和「命中关键词」才回。**被 @ 永远必回，不受概率影响。**

## 7. 进阶：做成 APK（壳）路线

想做成「装个 App，点几下就能用」的样子（像 AstrBot 泡泡版），需要写一个安卓壳。
下面是把泡泡版拆开后**可直接照搬 / 必须改**的部分（完整规格见
`qq-agent-plus-research/bubble-findings.md`，含代码行号）。

### 7.1 直接照搬的部分

- **proot 启动参数**（含 `/proc` 假文件绑定、`/etc/group` 补 `android_gid_*`、
  `env -i` 白名单、`/dev/shm` 绑到 TMPDIR）；
- **jniLibs 里放可执行文件 + 建软链**：`libproot.so`/`libloader.so`/`libbash.so`/`libbusybox.so`
  改名为 `.so` 放进 `jniLibs/arm64-v8a/`，启动时在 `$BIN` 下建符号链接指回去
  （`targetSdk > 28` 后 App 私有目录里的文件不可执行，只有 native lib 目录可执行）；
- **目录布局**：`files/usr/bin`（proot 与软链）、`files/usr/tmp`（TMPDIR）、
  `files/home`、`files/usr/var/lib/proot-distro/installed-rootfs/ubuntu`（rootfs）；
- **前台服务保活**：`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` 权限，
  `flutter_foreground_task` / 原生 Service，5 秒 tick，掉了重建；
- **WebView 直接开 `http://127.0.0.1:3210`**（proot 共享宿主网络，不需要端口转发）；
- **清单里的三件套**：`android:usesCleartextTraffic="true"`、
  `android:extractNativeLibs="true"`、release 的 `doNotStrip '**/libsudo.so'`。

### 7.2 必须改的部分

| 项 | 泡泡版（AstrBot） | 改成 |
| --- | --- | --- |
| 协议端连接方向 | `websocketClients`（NapCat 连 AstrBot） | **`websocketServers` + `httpServers`**（本项目连 NapCat） |
| 端口 | dashboard 6185 / WS 6199 / WebUI 6099 | 控制台 **3210** / WS **3001** / HTTP **3000** / WebUI 自定 |
| 运行时安装 | `uv` + Python + venv（几分钟） | Node 22 官方 arm64 tar.xz（几十秒），或直接把 `node_modules` 打进 assets |
| 进程托管 | shell 里起 AstrBot | shell 里起 `scripts/phone/supervise.sh`（**必须自带守护**，壳的前台服务只保证进程不被杀，不负责拉起子进程） |
| 数据持久化 | `$UBUNTU_PATH/root/AstrBot/data` | **显式 `QQ_AGENT_DATA_DIR` + `NAPCAT_WORKDIR`**，指到持久化目录 |
| 配置模型 | 控制台点选 | 同样可以，但要提醒用户「白名单 + 运行模式 + 模型」三件事 |

### 7.3 最小可行壳（建议顺序）

1. **先不做 UI**：一个 Activity，启动后台 Service，Service 里 `Runtime.exec` 一段 shell：
   解压 rootfs（assets）→ 跑 `scripts/phone/install.sh`（把仓库打进 assets 或用 `--offline-node-modules`）→
   跑 `scripts/phone/ctl.sh start` → WebView 开 `127.0.0.1:3210`。这样 200 行 Java 就够。
2. 再加「环境管理」页（显示安装进度：`scripts/phone/install.sh` 已经把进度打到 stdout，
   壳里读流即可）。
3. 最后再考虑多账号/多实例（每实例独立 `HOME`/`NAPCAT_WORKDIR`/`DISPLAY`，
   泡泡版的 `launcher2.sh` 就是这套做法）。

> 注意：Flutter 版泡泡用的 5 个 git 依赖（xterm.dart / settings / flutter_pty /
> global_repository / behavior_api）在国内网络下很难装，且与本项目无关。
> **如果只是想有个 App 壳，用原生 Android + WebView 最省事，不要引入 Flutter。**

## 8. 保活：安卓一定会杀你，怎么办

| 层 | 做法 |
| --- | --- |
| Termux 本身 | `termux-wake-lock`；Termux 设置里「Acquire wakelock」；电池优化白名单 |
| 容器进程 | `scripts/phone/ctl.sh start` 的守护循环（5 秒重起 + 快速失败退避） |
| 安卓系统 | 允许 App/服务「后台运行」「自启动」；Android 13+ 要允许通知（前台服务通知） |
| 息屏断网 | 关掉「电池优化」「睡眠模式」「智能省流量」；Wi-Fi 设置里允许后台数据 |

自检方法：拔掉充电器、锁屏 30 分钟，然后回来看
`scripts/phone/ctl.sh status` 是否还是「已连接」、`/sdcard/qq-agent` 下的日志是否连续。

## 9. 备份与迁移

```bash
bash scripts/phone/ctl.sh backup
# → /root/qq-agent-plus/backups/data-YYYYmmdd-HHMMSS.tar.gz
```

备份里含 `config.json`（**有 API Key**），别往公共网盘丢。
要迁移到新手机：把整个仓库目录 + `backups/` 拷过去，解压备份到 `data/` 即可。

NapCat 侧要一起备份 `$NAPCAT_WORKDIR`（含登录态与 `onebot11_<uin>.json`）。

**强烈建议**长期方案：把 `QQ_AGENT_DATA_DIR` 和 `NAPCAT_WORKDIR` 都指到
`/sdcard/qq-agent/...`（Termux 侧 `--bind` 进容器），这样重装 App/容器都不丢。

## 10. 排障

先跑 **`bash scripts/phone/ctl.sh doctor`**，它会逐项检查并把结论打出来。
下面是常见症状对照。

### 10.1 控制台打不开 / `scripts/phone/ctl.sh status` 显示 HTTP 000

```bash
bash scripts/phone/ctl.sh logs 80
```

- `listen EADDRINUSE` → 端口被占：`scripts/phone/ctl.sh stop` 后 `ps aux | grep server.js` 清残留，
  或改 `data/config.json` 的 `server.port`；
- `当前 Node 不支持 node:sqlite` → Node 太旧（Ubuntu 24.04 自带的是 18）。
  跑 `scripts/phone/install.sh` 让它下 Node 22；或手动指 `--node-dir`；
- 直接 `Error: Cannot find package 'ws'` → `node_modules` 没装好，重跑 `scripts/phone/install.sh`。

### 10.2 控制台能开，但 `OneBot: 未连接`

按顺序排：

1. **NapCat 起来了吗**：`ps aux | grep -i napcat`、`ss -ltn | grep 3001`；
2. **配的是 `websocketServers` 吗**：配成 `websocketClients` 就是这个症状（NapCat 那边没监听）；
3. **token 一致吗**：NapCat 的 `onebot11_<uin>.json` 与 `data/config.json` 的
   `accessToken`/`httpAccessToken` 必须一字不差；
4. **URL 对不对**：`ws://127.0.0.1:3001`（**不要** `/api`）、`http://127.0.0.1:3000`；
5. **端口写反了吗**：HTTP 端口写成 `wsUrl` 会看到 `HTTP 426` 或反复重连
   （日志里有专门提示）。

### 10.3 连上了，但群里发消息没反应

按顺序排（这几条 `doctor` 都会提示）：

1. `runtime.mode` 还是 `observe` → `scripts/phone/ctl.sh mode active`；
2. 群不在白名单 → `scripts/phone/ctl.sh allow <群号>`；
3. 没配模型 → 控制台填 baseUrl/apiKey/model，或重跑 `scripts/phone/install.sh --model-*`；
4. 触发概率被调成 0 且没 @ 它 → 控制台把滑条往上（或直接 @ 一次试试，@ 必回）；
5. `chatKey` 不对（比如白名单填的是私聊号）→ 看日志里的 `group:<号>`。

日志特征（`scripts/phone/ctl.sh logs -f`）：

- `未命中触发条件（概率 N%）` = 白名单与模式都对，只是概率没抽中；
- `[wake]` 之后没有任何 `[发送 -> ...]` = 跑了模型但没产出（模型/Key 有问题）；
- `[发送 -> group:xxx]` 之后群里没有 = 发出去的动作失败了，看下一行错误。

### 10.4 自己回自己 / 重复回复

- NapCat 的 `reportSelfMessage` 保持 `false`；
- 确认 `get_login_info` 拿到了自己的 QQ（日志 `selfInfo`）；拿不到会导致自己发的
  `message_sent` 被当群友消息；
- 同一时刻只允许一个实例连同一个 NapCat（本项目 `mode active` 会要求确认）。

### 10.5 手机越用越卡 / 被系统杀掉

- 看 `scripts/phone/ctl.sh status` 里今日用量与内存：`free -m`；
- 关掉不用的功能（表情库、语音转写、主动发言）能显著降内存；
- `data/messages.sqlite` 会随聊天增长，定期 `scripts/phone/ctl.sh backup` 后清理旧会话
  （控制台「存档」页可删）。

## 11. 安全提示

- 控制台默认只听 `127.0.0.1`，**不要**为了「手机外面也能看」把 `server.host` 改成
  `0.0.0.0` 而不设 token（服务会拒绝启动，这是有意的）。要远程看请走 SSH 隧道
  （见 AGENTS.md 里的 `console-tunnel.bat` / `src/ops.js console`）。
- `token` 别用弱口令；NapCat 的 WebUI 也别暴露到公网。
- 用别人的 QQ 号跑机器人可能违反腾讯的服务条款，**风险自负**；建议用小号。
- 本项目会把聊天记录存进 `data/messages.sqlite`，手机丢了/借人前记得清数据。

## 12. 与服务器路线的对照

| 事项 | 手机（本文） | 服务器（[LINUX.md](LINUX.md)） |
| --- | --- | --- |
| 入口 | `scripts/phone/install.sh` | `bash install.sh`（= `deploy-all.sh --yes`） |
| 进程托管 | `scripts/phone/supervise.sh`（PID + 重启） | systemd user service |
| 协议端 | NapCat（rootless，手动/半自动） | SnowLuma（Docker）或自带 OneBot |
| 定时任务 | 无（靠控制台里的功能与守护循环） | 四个 systemd timer（备份/守护/巡检/清理） |
| 自动更新 | 关（`autoUpdate.enabled=false`） | 可开（Release 驱动） |
| 数据 | `data/`（建议指到 `/sdcard`） | `--data-dir`，通常 `/var/lib/qq-agent` |

> 手机端**不要**开 `autoUpdate`：它会 `systemctl --user restart`，proot 里没有 systemd，
> 更新会失败并把服务停在那儿。要更新就：`scripts/phone/ctl.sh stop` → `git pull` → `scripts/phone/install.sh` →
> `scripts/phone/ctl.sh start`。
