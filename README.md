# 麦麦Bot-NapCat

一个**自包含的安卓 App**：内置 Ubuntu rootfs 与 Python 运行时，用 proot 在里面跑
[MaiBot（麦麦）](https://github.com/MaiBot-Team/MaiBot)，配合 [NapCat](https://github.com/NapNeko/NapCatQQ)
把 QQ 桥接成 OneBot v11，让麦麦能收发 QQ 消息。

装完 APK 即可离线运行，不需要额外装 Termux、VNC 或任何第三方环境。

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

## 许可

本项目代码可自由使用。内置的第三方组件遵循各自许可：
MaiBot（AGPL-3.0）、NapCat（AGPL-3.0）、LinuxQQ（腾讯自有协议）、Ubuntu（GPL 等）。

## 群

qq群号：1134218944