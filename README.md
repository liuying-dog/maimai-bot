# 麦麦Bot-NapCat

一个**自包含的安卓 App**：内置 Ubuntu rootfs 与 Python 运行时，用 proot 在里面跑
[MaiBot（麦麦）](https://github.com/MaiBot-Team/MaiBot)，配合 [NapCat](https://github.com/NapNeko/NapCatQQ)
把 QQ 桥接成 OneBot v11，让麦麦能收发 QQ 消息。

装完 APK 即可离线运行，不需要额外装 Termux、VNC 或任何第三方环境。

---

## 它解决了什么

安卓的 App 沙盒对 proot 套壳的 Linux 环境并不友好，坑集中在系统调用层。这个项目
把这些坑一个个填掉了，核心是三件事：

### 1. seccomp 垫片（`assets/seccompshim.bin` → `libseccompshim.so`）

App 主进程带着 `Seccomp: 2`，proot 里的 glibc 程序会撞上一批返回 `ENOSYS`
（Function not implemented）的系统调用，表现为各种莫名其妙的崩溃：

| 调用 | 不垫的后果 |
|---|---|
| `getcwd` | Node 的 libuv 一启动就要 cwd，拿不到直接 `uv_cwd failed` |
| `link` / `linkat` | Xvfb 建 `/tmp/.X<n>-lock` 用 link 做原子创建，失败即 `Fatal server error`，X 起不来 → QQ 报 Missing X server |
| `unlink` / `unlinkat` | `Path.unlink(missing_ok=True)` 只捕 `FileNotFoundError`，ENOSYS 直接穿透 |
| `chdir` / `mkdir` / `mkdirat` / `rmdir` | `Path.resolve()` 内部会 chdir，更新器一调就崩 |
| `readlink` / `readlinkat` / `realpath` / `faccessat` | 同上 |

垫片 hook 了这 16 个符号：只在**真的返回 ENOSYS 时才走兜底**，正常环境行为完全不变。
比如 `link` 兜底是「先 rename，失败退化成 O_CREAT|O_EXCL 直写」——对锁文件语义足够。

**关键**：垫片必须通过 `LD_PRELOAD` 注入到**麦麦的 Python 进程**，只注入到 NapCat 那一支不够，
因为更新器是麦麦自己 fork 出来的。

### 2. uv 的 `.l2s.` 软链接在 proot 下打不开

uv 为了省空间会用 hardlink/symlink 装包，把真实内容放在 `.l2s.XXX` 中间层文件里，
再用软链接指过去。这套在 proot 下会出问题：`open()` 这种中间层直接返回 ENOSYS。

典型症状：

```
OSError: [Errno 38] Function not implemented:
'/root/.local/share/uv/tools/maibot/lib/python3.13/site-packages/maibot-1.3.18.dist-info/METADATA'
```

**解法**：装完之后把 `.l2s.` 软链接全部换成实体文件（用 `cp`，绝不能用
`content=$(cat)` 这种命令替换——它会吞掉末尾换行，实测把 14278 个文件里的 64 个各写少了 1 字节）。

### 3. 进程清理别误伤自己人

`ProcessKiller` 按命令行特征抓进程。里有一条 `/maibot`，本意是抓
`/root/.local/share/uv/tools/maibot/bin/python -m src.runtime`，但它同时命中
更新子进程 `/root/maibot/data/self_update/package_updater.py`——于是更新跑到一半被一刀砍死，
日志里只剩一句看不懂的 `[seccompshim] loaded`，没有任何 traceback。

现在的做法：`UPDATE_GUARD` 白名单，凡是命令行里带 `package_updater.py` /
`self_update` 的进程，`killAll` 和 `killNapCat` 都跳过；`stopEverything()` 检测到
有更新在跑时直接拒绝停止。

---

## 目录结构

```
app/src/main/
├── java/com/maimai/bot/
│   ├── MainActivity.java            首页：账号卡、环境管理、启停
│   ├── WebUiActivity.java           WebUI / NapCat 面板（同一个 WebView 换 URL）
│   ├── BotService.java              前台服务：拉起麦麦、读日志、处理退出码 42
│   ├── ConsoleActivity.java         内置终端
│   ├── QrActivity.java              扫码登录页
│   └── core/
│       ├── Env.java                 路径管理
│       ├── EnvInspector.java        环境自检（10 项）
│       ├── UpdateChecker.java       版本检查（麦麦 / NapCat / LinuxQQ）
│       ├── NapCat.java              NapCat 启停、实例目录、版本标记
│       ├── NapCatAccount.java       账号（端口、token、登录态）
│       ├── NapCatInstaller.java     NapCat + LinuxQQ 安装（本地优先）
│       ├── ProcessKiller.java       按特征清理进程（带更新保护）
│       ├── StepRunner.java          step.sh 分发
│       └── TarExtractor.java        tar 解包（rootfs.bin / maibot_env.bin）
├── assets/
│   ├── scripts/
│   │   ├── bootstrap.sh             首次解压与初始化
│   │   ├── step.sh                  单项安装脚本
│   │   ├── run.sh                   启动麦麦（含 LD_PRELOAD）
│   │   └── sitecustomize.py         Python 侧兜底
│   ├── maibot_env.bin               麦麦 Python 环境（tar.gz，约 240MB）
│   ├── rootfs.bin                   Ubuntu 24.04 rootfs（tar.gz，约 95MB）
│   ├── uv.bin                       uv 0.13.5（约 15MB）
│   ├── napcat.zip                   NapCat.Shell（约 35MB）
│   ├── linuxqq.deb                  LinuxQQ arm64（约 198MB）
│   ├── libnapcat_launcher.so        注入库（预编译，72KB）
│   ├── seccompshim.bin              seccomp 垫片（编译产物）
│   └── cwdshim.bin                  getcwd 垫片
└── res/                             界面资源
```

---

## 内置资源

`.gitignore` 排除了 6 个大文件（合计约 583MB），要自己准备：

| 文件 | 来源 | 说明 |
|---|---|---|
| `maibot_env.bin` | 从真机导出 | `cd /root/.local/share/uv && tar -czf out.bin tools/maibot`，打包前先清掉 `.l2s.*` 坏链接 |
| `rootfs.bin` | Ubuntu 24.04 arm64 最小根文件系统 | `tar -czf`，保留符号链接 |
| `uv.bin` | [astral-sh/uv](https://github.com/astral-sh/uv) aarch64 | 静态二进制 |
| `napcat.zip` | [NapCatQQ Releases](https://github.com/NapNeko/NapCatQQ/releases) 的 `NapCat.Shell.zip` | 国内直链常断，用 `gh-proxy.com` 前缀 |
| `linuxqq.deb` | 腾讯官方 `linuxConfig.js` 里的 arm64 deb | `qqdl.gtimg.cn` 直链 403，要走签名接口换带 sign 的地址 |
| `proot` | 本项目 `jniLibs` 里的 `libproot.so` | 或者用 termux 的 proot 静态版 |

`seccompshim.bin` / `cwdshim.bin` 是编译产物，源文件在 `shim/` 目录，可随时重编：

```bash
aarch64-linux-gnu-gcc -shared -fPIC -O2 -o libseccompshim.so seccompshim.c -ldl
```

---

## NapCat 与 LinuxQQ 的版本

**NapCat 的包里读不出真实版本。** 上游从来不维护 `package.json` 的 `version` 字段——
4.18.41 的官方发行包里那个字段也还是 `"0.0.1"`。`napcat.mjs` 里虽然能 grep 到
`4.18.41`，但同一文件里还混着 4.0.46、4.3.x、4.2.x 一堆依赖版本号，区分不出来。

所以现在自己记一份：

- 安装完成时把版本写进 `<rootfs>/root/napcat/.napcat_version`
- 内置包用 `NapCatInstaller.BUNDLED_NAPCAT_VERSION` 常量（换 `napcat.zip` 时必须同步改）
- 在线安装时从 GitHub `releases/latest` 的 `tag_name` 抓
- 读取走 `NapCat.detectVersion()`：标记文件 → 启动日志里的 `NapCat.Core Version: x.y.z` → 空

**LinuxQQ 的版本**读 `/opt/QQ/resources/app/package.json`，格式是 `3.2.21-42086`，
只取前半段 `3.2.21` 参与比较。上游最新版从腾讯的 `linuxConfig.js` 里取
`"version":"3.2.34"`。

展开「环境管理」时会并发查这三个组件，有新版的行按钮会从「重装」变成「更新」。
LinuxQQ 和 NapCat 是两条独立发布线，可以单独重装——升 QQ 不必重下 92MB 的 NapCat。

---

## WebUI 自更新

麦麦自己的 WebUI 里有个「立即更新」，它靠一套 Runner 机制工作：

1. WebUI 点更新 → 登记 `data/self_update/pending.json` → `os._exit(42)`
2. App 收到退出码 42 → 自动重新拉起（`BotService` 里的 `maibot-restart-on-42` 线程）
3. 新 Runner 起来 → `apply_pending_update()` 执行 `uv pip install`
4. 装完再退出一次 42，由新代码接手

**注意**：Runner 启动时会先调 `clear_pending_update()`，所以退出码 42 必须被当场消费掉。
以前 App 把所有退出码一视同仁写成「已停止」，结果更新请求被静默丢弃，
表现就是「点了更新没反应」。

---

## 编译

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk（约 597MB）
```

装到真机：

```bash
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

首次启动会解压 rootfs + 麦麦环境（约 340MB），慢几十秒。
NapCat 和 LinuxQQ 因为内置了，不需要联网。

---

## 已知限制

- 只支持 arm64
- 首次解压耗时较长（f2fs 上写 340MB）
- WebUI 更新走 `uv pip install`，装完要重启，期间界面会短暂断开
- 双实例同时跑会抢实例锁（`flock`），设计上只允许一个麦麦实例

---

## 许可

本项目代码可自由使用。内置的第三方组件遵循各自许可：
MaiBot（AGPL-3.0）、NapCat（AGPL-3.0）、LinuxQQ（腾讯自有协议）、Ubuntu（GPL 等）。

##qq群号：1134218944