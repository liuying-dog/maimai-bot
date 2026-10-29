#!/bin/bash
# MaiBot 实例引导：在 proot 的 Ubuntu 里装 uv、装 maibot、初始化实例。
# 由 Android 端以 /bin/bash -lc 调用，所有进度都打到 stdout，宿主逐行读。
set -o pipefail
INSTANCE="/root/maibot"
STAMP="$INSTANCE/.installed"
LOG() { echo "[引导] $*"; }
FAIL() { echo "[引导][失败] $*"; exit 1; }

# PATH 必须在脚本里自己定死。
# proot 会把宿主（Android）的环境透传进 guest，Android 的 PATH 里
# 全是 /system/bin 这类路径，没有 /usr/bin，导致 apt / curl / tar
# 全都 command not found —— 而且进程还活着，很容易误判成"卡住"。
export PATH="/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export HOME="/root"

export DEBIAN_FRONTEND=noninteractive
export UV_LINK_MODE=copy
# 用阿里云镜像：清华源在这台机器上对 pypi 返回 403，实测阿里云 200
export UV_DEFAULT_INDEX="https://mirrors.aliyun.com/pypi/simple/"
export PIP_INDEX_URL="$UV_DEFAULT_INDEX"
export PIP_TRUSTED_HOST=mirrors.aliyun.com
export PATH="$HOME/.local/bin:$PATH"

# ── getcwd/chdir 垫片 ──────────────────────────────────────────
# App 进程被 Android 挂了 seccomp，guest 里 python 的 os.getcwd()
# 会返回 ENOSYS（Errno 38），maibot 的 argparse 求 Path.cwd() 就直接崩，
# 连 `maibot --version` 都跑不出来。
#
# 内置 venv 的时候 Installer 已经把 sitecustomize.py 写好了；这里再兜一次底，
# 因为「环境管理 → 更新基础环境」会重新 uv tool install，把那个文件冲掉。
patch_cwd() {
  for _sp in "$HOME"/.local/share/uv/tools/maibot/lib/python3.*/site-packages; do
    [ -d "$_sp" ] || continue
    [ -f "$_sp/sitecustomize.py" ] && continue
    cat > "$_sp/sitecustomize.py" <<'PYPATCH'
import os

_F = os.environ.get("MAIBOT_CWD", "/root/maibot")
_rg, _rc = os.getcwd, os.chdir


def _g():
    try:
        return _rg()
    except OSError:
        return _F


os.getcwd = _g


def _c(p):
    try:
        return _rc(p)
    except OSError:
        return None


os.chdir = _c

try:
    from pathlib import Path as _P

    _P.cwd = classmethod(lambda cls: cls(_F))
except Exception:
    pass
PYPATCH
    LOG "补 sitecustomize: $_sp"
  done
}
patch_cwd

# ---------------------------------------------------------------- 0 修 DNS
# rootfs 里的 /etc/resolv.conf 是 systemd-resolved 的存根（127.0.0.53），
# 而 proot 里没有 systemd，这个地址没人应答。
# 不修它的话，后面 apt / curl / uv 全都会因为解析不了域名而失败，
# 表现出来就是"卡在安装"，而且看不出原因。
LOG "修复 DNS 配置"
if grep -q '127.0.0.53' /etc/resolv.conf 2>/dev/null || [ ! -s /etc/resolv.conf ]; then
  rm -f /etc/resolv.conf
  printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\n' > /etc/resolv.conf
  LOG "DNS 已指向 223.5.5.5"
fi

# 上一次 dpkg 若在解包中途失败，会留下半装状态，
# 之后所有 apt 操作都会连带失败，所以先把它收干净。
if [ -f /var/lib/dpkg/status ] && dpkg --audit 2>/dev/null | grep -q .; then
  LOG "修复 dpkg 半装状态"
  dpkg --configure -a >/dev/null 2>&1 || true
fi

# ---------------------------------------------------------------- 0.5 修 apt 源
#
# 这里踩了两个坑，都不报人话的那种：
#
# 【坑 1】sources.list 里的镜像 URL 少了 /ubuntu 这一层，写成
#   deb https://mirrors.ustc.edu.cn noble main ...
# apt-get update 直接 404，apt install 全报 "Unable to locate package"。
#
# 【坑 2】更隐蔽：arm64（我们跑的就是 aarch64）在 Ubuntu 里属于 ports 架构，
# 主仓库 mirrors.xxx/ubuntu 只有 amd64/i386，拿 arm64 的 Packages 必然 404：
#   E: Failed to fetch .../ubuntu/dists/noble/main/binary-arm64/Packages 404
# 实测对比：
#   /ubuntu/dists/noble/main/binary-arm64/Packages.gz       -> 404
#   /ubuntu-ports/dists/noble/main/binary-arm64/Packages.gz -> 200
# 所以 aarch64 必须用 ubuntu-ports。
#
# 两个坑在开发机上都看不到 —— 那里包早就装好了，从来不用 apt install；
# 只有全新手机（rootfs 刚解压、apt lists 是空的）才会撞上。
#
# 结论：不再用 sed 去猜着改，直接按架构重写一份标准源，最省事也最可靠。
LOG "规范化 apt 源"
_arch=$(uname -m 2>/dev/null)
_apt_path="ubuntu"
case "$_arch" in
  aarch64|arm64) _apt_path="ubuntu-ports" ;;
esac
_apt_base="https://mirrors.ustc.edu.cn/${_apt_path}"
# 发行版代号从 os-release 取，取不到就退回 noble
_apt_suite="noble"
if [ -r /etc/os-release ]; then
  _codename=$(sed -n 's/^VERSION_CODENAME=//p' /etc/os-release 2>/dev/null | tr -d '"')
  [ -n "$_codename" ] && _apt_suite="$_codename"
fi
# 有 keyring 才写 signed-by，没有就不写（否则 apt 会报找不到 keyring 文件）
_apt_key=""
if [ -f /usr/share/keyrings/ubuntu-archive-keyring.gpg ]; then
  _apt_key='[signed-by="/usr/share/keyrings/ubuntu-archive-keyring.gpg"] '
fi
cat > /etc/apt/sources.list <<EOF
deb ${_apt_key}${_apt_base} ${_apt_suite} main universe multiverse
deb ${_apt_key}${_apt_base} ${_apt_suite}-updates main universe multiverse
deb ${_apt_key}${_apt_base} ${_apt_suite}-security main universe multiverse
EOF
LOG "apt 源 -> ${_apt_base} (${_apt_suite} / ${_arch:-unknown})"

# ---------------------------------------------------------------- 1 基础工具
LOG "检查基础工具"
need=()
for c in curl git tar xz-utils; do
  case "$c" in
    xz-utils) command -v xz >/dev/null 2>&1 || need+=(xz-utils) ;;
    *) command -v "$c" >/dev/null 2>&1 || need+=("$c") ;;
  esac
done
if [ ${#need[@]} -gt 0 ]; then
  LOG "安装缺失工具（可选）: ${need[*]}"
  apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=3 update >/dev/null 2>&1 || \
    LOG "apt update 失败，继续尝试安装"
  # 注意：这一步不再是硬失败。
  # 内置的 uv / maibot venv / python 都在 APK 里，curl 和 git 只是给
  # 「插件从 git 装」这类操作备用。全线离线的新手机如果在这里 FAIL，
  # 用户看到的就是点启动一秒「出错」，而且根本看不出是网络问题。
  # 所以改成：装不上就记一笔，继续往下走。
  if ! apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=3 install -y "${need[@]}"; then
    LOG "基础工具安装失败（可忽略，内置环境不依赖它）: ${need[*]}"
    dpkg --configure -a >/dev/null 2>&1 || true
  fi
fi

# 证书：没有这个 curl 一律 TLS 失败
if [ ! -s /etc/ssl/certs/ca-certificates.crt ]; then
  LOG "补装 CA 证书"
  apt-get -o Acquire::ForceIPv4=true install -y ca-certificates >/dev/null 2>&1
fi

# ---------------------------------------------------------------- 2 装 uv
if ! command -v uv >/dev/null 2>&1; then
  LOG "安装 uv"
  # 先落盘再执行：直接管道给 sh 在部分容器里会被安全策略拦
  if curl -LsSf --connect-timeout 20 --max-time 180 \
      https://astral.sh/uv/install.sh -o /tmp/uv-install.sh; then
    sh /tmp/uv-install.sh || FAIL "uv 安装脚本执行失败"
  else
    LOG "官方脚本下载失败，改用 pip 装 uv"
    apt-get install -y python3-pip >/dev/null 2>&1
    pip3 install --break-system-packages -i "$UV_DEFAULT_INDEX" uv || FAIL "uv 安装失败"
  fi
  export PATH="$HOME/.local/bin:$PATH"
  grep -q '.local/bin' "$HOME/.bashrc" 2>/dev/null || \
    echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$HOME/.bashrc"
fi
command -v uv >/dev/null 2>&1 || FAIL "uv 装完仍找不到"
LOG "uv 版本: $(uv --version 2>&1)"

# ---------------------------------------------------------------- 3 装 maibot
if ! command -v maibot >/dev/null 2>&1; then
  LOG "安装 maibot（首次会下载 Python 3.13 与约 90 个依赖，需要几分钟）"
  uv tool install --python 3.13 maibot 2>&1 || FAIL "maibot 安装失败"
fi
command -v maibot >/dev/null 2>&1 || FAIL "maibot 装完仍找不到"
LOG "maibot 版本: $(maibot --version 2>&1)"

# ---------------------------------------------------------------- 4 初始化实例
# 这里失败也不要紧：maibot init 自己会造目录。
# coreutils 的 mkdir 在这种 seccomp 环境下偶尔会拿不到 statx 而报 ENOSYS，
# 之前它会把整个引导带崩，日志里只有一行「cannot create directory '/root'」。
mkdir -p "$INSTANCE" 2>/dev/null || true
if [ ! -f "$INSTANCE/config/bot_config.toml" ]; then
  LOG "初始化 MaiBot 实例"
  maibot init --directory "$INSTANCE" 2>&1 || FAIL "maibot init 失败"
else
  LOG "实例已存在，跳过 init"
fi

# ---------------------------------------------------------------- 4.5 改端口
# ── WebUI 监听地址 ─────────────────────────────────────────────
# 只绑回环。默认配置里 [webui] host 已经含 127.0.0.1，
# 这里只确保 enabled=true，端口保持 maibot 自己的默认值不动。
# 早先强行改成 18001 是为了躲开"别的环境占着 8001"，
# 现在内置实例是独立的一份，没必要再改端口 —— 改来改去反而
# 让代码里的默认值和配置里的真值对不上。
CONF="$INSTANCE/config/bot_config.toml"
if [ -f "$CONF" ]; then
  if grep -q '^\[webui\]' "$CONF"; then
    LOG "WebUI 配置段已存在，保持原样"
  else
    printf '\n[webui]\nenabled = true\nhost = ["127.0.0.1", "::1"]\nport = 8001\n' >> "$CONF"
    LOG "已追加 [webui] 段（默认端口 8001）"
  fi
else
  LOG "警告：没找到 bot_config.toml，WebUI 段未处理"
fi
# ---------------------------------------------------------------- 5 收尾
date '+%Y-%m-%d %H:%M:%S' > "$STAMP"
LOG "全部完成"
exit 0