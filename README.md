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

## 许可

本项目代码可自由使用。内置的第三方组件遵循各自许可：
MaiBot（AGPL-3.0）、NapCat（AGPL-3.0）、LinuxQQ（腾讯自有协议）、Ubuntu（GPL 等）。

## 群

qq群号：1134218944