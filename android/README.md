# 安卓 APK 构建说明

> 目标：把 `qq-agent-plus` 打包成一个**图形化 App**：装到手机上，点几下就能在
> proot 容器里跑起来，不用 Termux、不用敲命令。

## 现在有什么

`android/dist/qq-agent-android.apk` —— **75.5 MB**，已签名，可直接安装。

| 项 | 值 |
| --- | --- |
| 包名 | `com.qqagent.android` |
| 版本 | 1.0.0 (versionCode 1) |
| minSdk / targetSdk | 24 / 35 |
| 架构 | **仅 arm64-v8a**（x86 手机装不了，会报 INSTALL_FAILED_NO_MATCHING_ABIS） |
| 签名 | v2 + v3（apksigner verify 通过） |

## 装到手机上

```bash
adb install -r android/dist/qq-agent-android.apk
```

或者把 APK 传到手机（微信文件传输助手 / USB / 网盘），用文件管理器点开安装
（需要允许"安装未知来源应用"）。

装好后打开 App → 点「安装环境」→ 等 3~8 分钟 → 点「启动」→ 点「打开控制台」。

## 界面

- **首页**：环境状态（未安装/已就绪/运行中）、安装进度条与分步列表、启停/重启、
  控制台入口、日志预览。
- **安装向导**：六步进度（解压系统 → 运行环境 → Node → 项目 → 依赖 → 配置），
  失败会弹原因并提供重试；支持中断后从断点继续（判断产物而不是记进度数字）。
- **控制台**：内置 WebView 直接开 `http://127.0.0.1:3210`，带刷新/关闭、加载进度、
  连不上时给出排障提示。
- **设置**：端口、token（可一键重新生成）、模型三件套、群白名单、开机自启、
  后台保活、导出配置到剪贴板、重置环境。

## 架构

```
App (Java, 原生 View，无 Flutter/Compose)
├── MainActivity        首页 / 安装向导 / 启停
├── ConsoleActivity     WebView 控制台
├── SettingsActivity    配置
├── AgentService        前台服务（保活，dataSync 类型）
├── BootReceiver        开机自启（可选）
├── Installer           六步安装流水线
├── Container           proot 容器：解压 rootfs、建软链、构造 proot 命令
├── AgentRunner         进程守护（node 退出 5 秒重起）
└── AppConfig           配置持久化 + 生成容器 config.json
```

**为什么不用 Flutter**：本机没有 Flutter SDK，且 Flutter 那套 git 依赖在国内难装；
这点 UI 用原生 View 反而更小更快（APK 里没有 Flutter 引擎的 8MB）。

## 容器方案

与 [docs/PHONE.md](../docs/PHONE.md) 的 Termux 路线同源，但由 App 自己托管：

| 项 | 做法 |
| --- | --- |
| 可执行文件 | jniLibs 里的 `libproot.so` 等 → 启动时软链到 `files/usr/bin/`（targetSdk>28 后私有目录不可执行，native lib 目录可以） |
| rootfs | `assets/rootfs.tar.gz`（ubuntu-base 24.04 arm64）→ 解到 `files/usr/var/lib/proot-distro/installed-rootfs/ubuntu/` |
| Node | `assets/node-arm64.tar.gz` → 解到 rootfs 的 `/usr/local` |
| 项目 | `assets/app.tar.gz` → 解到 rootfs 的 `/root/qq-agent-plus` |
| 依赖 | `assets/node_modules.tar.gz` → 解到项目的 `node_modules`（离线，不受墙影响） |
| 数据 | `files/qap-data` 挂到容器 `/qap-data`（`QQ_AGENT_DATA_DIR`） |
| NapCat 配置 | `files/napcat-work` 挂到 `/napcat-work`（`NAPCAT_WORKDIR`） |
| 进程托管 | 前台服务 + `AgentRunner` 循环（等价于 `scripts/phone/supervise.sh`） |

proot 启动参数与泡泡版一致（`-0 -r <rootfs> --link2symlink -b /dev -b /proc -b /sys
-b /dev/pts -b <tmp>:<tmp> -b <tmp>:/dev/shm -w /root /usr/bin/env -i ...`），
外加把数据目录与 NapCat 目录 bind 进容器。

## 构建

### 前置

- **Windows** + JDK 11+（本机用 Zulu 21）、Android SDK（build-tools 36.0.0 + platform android-36.1）
- **WSL / Linux**（生成 assets 用，需要 `tar`/`xz`/`npm`）

### 两步

```bash
# 1) 生成 assets（在 WSL 里跑；首次约 3 分钟，主要是在下 Node 与 rootfs）
bash android/build-assets.sh

# 2) 构建 APK（在 Windows 里跑）
cd android
.\build.ps1
```

产物：`android/dist/qq-agent-android.apk`

`build.ps1` 做的事：aapt2 编译资源 → aapt2 link（含 assets）→ javac → d8 →
aapt add classes.dex → aapt add native libs → zipalign → apksigner → verify。

> 没有 Gradle 也能出包。这样做的原因：本机没有 Gradle 且不想为一个 App 引入
> 一整套构建系统；手写脚本每一步都可见、可改。

### 签名

首次构建会自动生成 `android/keystore/qqagent.jks`：

- store/key 口令：`qqagent2026`
- alias：`qqagent`

**请备份这个文件**：升级 APK 必须用同一签名，否则用户要先卸载旧版（数据会丢）。

想用自己的签名：把 `keystore/qqagent.jks` 换成你的，并改 `build.ps1` 里的口令与 alias。

## 已知限制

1. **只支持 arm64**。APK 里只有 `lib/arm64-v8a/`；x86 模拟器装不上。
2. **首次安装要 3~8 分钟**（解压 28MB rootfs + 42MB Node）。进度条会显示到哪一步。
3. **APK 75.5MB**：主要构成是 rootfs 28MB + Node 42MB。想更小可以：
   - 去掉 Node 里的 `corepack`（省约 1MB，影响不大）；
   - 用更精简的 rootfs（但 apt 等工具会缺）；
   - 让用户在首次启动时联网下 Node（APK 变 33MB，但弱网下更容易失败）。
4. **NapCat 没有内置**。QQ 协议端需要另外装（官方安装器要联网下 arm64 的 linuxqq）。
   本 App 只管 QQ Agent 本体；NapCat 可以用 Termux 或本 App 的终端手动装。
   要内置 NapCat 的话体积会再涨 ~150MB（含 linuxqq）。
5. **未在真机验证**。本机没有连着的安卓设备（`adb devices` 为空），
   APK 只做了静态验证（签名、结构、assets 完整性、dex 里的类）。
   真机首次运行最可能卡在：解压耗时、proot 在特定 ROM 上的兼容性、安卓保活策略。

## 排障

- **安装时报 `INSTALL_FAILED_NO_MATCHING_ABIS`**：手机不是 arm64，或装了 x86 模拟器。
- **点「安装环境」后卡在"解压系统"**：看 logcat（`adb logcat -s QAP.Container`）。
  28MB 的 tar.gz 解压成 106MB，低端机可能要几分钟。
- **启动后控制台打不开**：首页看日志预览；`adb logcat -s QAP.Runner` 看 node 输出。
  最常见是 Node 没解压成功或 `node:sqlite` 不可用（Node 版本太旧）。
- **App 被系统杀**：设置里开「后台保活」，并在系统设置里给 App 关掉电池优化。
- **想清空重来**：设置 → 维护 → 重置环境（删 rootfs/Node/数据）。
