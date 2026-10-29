#!/bin/bash
# 按项安装/修复环境。
#
# 用法：bash /opt/mb/step.sh <项目key>
#   apt      —— 基础命令（sudo/git/curl/解压工具等）
#   python   —— Python 3.13（uv 会自己装，这里只检查）
#   uv       —— 装 uv 本体
#   maibot   —— uv tool install maibot
#   deps     —— 补齐 maibot 运行时依赖（重跑 uv tool install 的同步）
#   instance —— maibot init 生成实例
#   all      —— 依次跑上面全部
#
# 为什么要有这个脚本：界面上的「环境管理」拆成了逐项列表，
# 每项后面有个「重装」。整块重解压 rootfs 太贵（300MB，还要重装 maibot），
# 所以每一项都对应一段精确的命令。

set +e

STEP="$1"

# proot 会把 Android 的 PATH 透传进来，导致 apt/pip 找不到；
# 这里写死一份干净的。
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/root
export DEBIAN_FRONTEND=noninteractive
export UV_LINK_MODE=copy

# ── 加速源 ────────────────────────────────────────────────────────
# 由 App 侧按用户选择通过环境变量传进来（见 Mirror.java）。
# 每一项都给了兜底默认值，所以脚本单独跑也不会炸。
#
# AstrBot 的做法是把 pip 固定清华、GitHub 靠 12 个代理轮询；
# 这里改成「三项都可选」，因为实测不同网络环境下最好用的源并不一样。
APT_BASE="${MB_APT_BASE:-https://mirrors.ustc.edu.cn}"
PYPI_INDEX="${MB_PYPI_INDEX:-https://mirrors.ustc.edu.cn/pypi/simple}"
GH_PROXY="${MB_GH_PROXY-https://ghfast.top/}"

log() { echo "[env] $*"; }

fix_dns() {
    # 容器里的 resolv.conf 常指向 127.0.0.53 存根，proot 下没有 systemd-resolved，
    # 于是所有域名都解析失败。直接换公共 DNS。
    if [ -f /etc/resolv.conf ] && grep -q "127.0.0.53" /etc/resolv.conf 2>/dev/null; then
        echo "nameserver 223.5.5.5" > /etc/resolv.conf
        echo "nameserver 119.29.29.29" >> /etc/resolv.conf
        log "DNS 已修正为 223.5.5.5 / 119.29.29.29"
    fi
}

# 把 apt 源换到用户选的镜像。
#
# 这一步必须在 apt-get update 之前做，否则索引还是老的官方地址，
# 在国内基本拉不动。
apply_apt_mirror() {
    fix_dns
    local host
    host=$(echo "$APT_BASE" | sed -E 's#^https?://##; s#/.*$##')
    [ -z "$host" ] && return 0

    # 注意：arm64 在 Ubuntu 里属于 ports 架构，主仓库 /ubuntu 只有 amd64/i386，
# 拿 arm64 的 Packages 必然 404（实测 /ubuntu/.../binary-arm64/Packages.gz -> 404，
# /ubuntu-ports/.../binary-arm64/Packages.gz -> 200）。所以按架构拼路径。
# 之前这里只写了域名，sources.list 变成
#   deb https://mirrors.ustc.edu.cn noble main ...
# apt-get update 直接 404，apt install 全报 Unable to locate package。
_arch=$(uname -m 2>/dev/null)
case "$_arch" in
    aarch64|arm64) _sub="ubuntu-ports" ;;
    *)             _sub="ubuntu" ;;
esac
local base="${APT_BASE%/}/${_sub}"

log "apt 源 -> $base ($_arch)"

    # Ubuntu 24.04 用的是 deb822 格式（ubuntu.sources），
    # 老版本是 one-line 格式（sources.list）。两种都处理。
    for f in /etc/apt/sources.list /etc/apt/sources.list.d/ubuntu.sources \
             /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
        [ -f "$f" ] || continue
        # 把 URIs: http://xxx 和 deb http://xxx 里的地址换成带 /ubuntu 的 $base
        sed -i -E "s#(URIs:[[:space:]]*)https?://[^[:space:]]+#\1${base}#g" "$f" 2>/dev/null
        sed -i -E "s#(deb(-src)?[[:space:]]+(\\[[^]]*\\][[:space:]]+)?)https?://[^[:space:]]+#\1${base}#g" "$f" 2>/dev/null
    done

    # 顺手把 http 升到 https：现在各家镜像都支持，https 更稳
    for f in /etc/apt/sources.list /etc/apt/sources.list.d/*.sources; do
        [ -f "$f" ] || continue
        sed -i -E 's#http://([a-z0-9.-]+/ubuntu)#https://\1#g' "$f" 2>/dev/null
    done

    log "当前 apt 源："
    grep -h -E '^(URIs:|deb )' /etc/apt/sources.list /etc/apt/sources.list.d/*.sources 2>/dev/null \
        | head -n 4
}

# 把 pip / uv 的 index 写进配置，这样后续所有 pip/uv 命令自动走镜像，
# 不用每次都带 -i 参数。
apply_pypi_mirror() {
    log "pip 源 -> $PYPI_INDEX"
    mkdir -p /root/.config/pip /root/.config/uv 2>/dev/null
    cat > /root/.config/pip/pip.conf <<EOF
[global]
index-url = $PYPI_INDEX
trusted-host = $(echo "$PYPI_INDEX" | sed -E 's#^https?://##; s#/.*$##')
timeout = 60
EOF
    export UV_DEFAULT_INDEX="$PYPI_INDEX"
    # uv 装 Python 时是从 GitHub 拉 python-build-standalone，
    # 国内直连基本失败，必须过代理。
    if [ -n "$GH_PROXY" ]; then
        export UV_PYTHON_INSTALL_MIRROR="${GH_PROXY}https://github.com/astral-sh/python-build-standalone/releases/download"
        log "Python 下载走 GitHub 代理：$GH_PROXY"
    fi
}

do_apt() {
    log "== 安装基础命令 =="
    export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    apply_apt_mirror

    # 上次被中断可能留下半装状态，先修
    dpkg --configure -a >/dev/null 2>&1
    apt-get -o Dpkg::Options::=--force-confdef \
            -o Dpkg::Options::=--force-confold \
            -f install -y >/dev/null 2>&1

    log "更新软件源索引…"
    if ! apt-get update -y 2>&1 | tail -n 5; then
        log "索引更新失败，回退官方源重试一次"
        APT_BASE="http://archive.ubuntu.com"
        apply_apt_mirror
        apt-get update -y 2>&1 | tail -n 5
    fi

    log "安装：sudo git curl wget ca-certificates xz-utils tar procps"
    apt-get install -y --no-install-recommends \
        sudo git curl wget ca-certificates xz-utils tar procps 2>&1 | tail -n 5

    log "== 基础命令完成 =="
    for c in git curl wget sudo; do
        if command -v "$c" >/dev/null 2>&1; then
            log "  ✓ $c -> $(command -v $c)"
        else
            log "  ✗ $c 缺失"
        fi
    done
}

do_uv() {
    log "== 安装 uv =="
    apply_pypi_mirror
    export PATH="$HOME/.local/bin:$PATH"
    if [ -x "$HOME/.local/bin/uv" ]; then
        log "uv 已存在：$($HOME/.local/bin/uv --version 2>&1)"
        return 0
    fi
    if [ -x /usr/local/bin/uv ]; then
        log "uv 已存在（/usr/local/bin）"
        return 0
    fi

    log "下载官方安装脚本…"
    mkdir -p /tmp/uvdl
    # 安装脚本本身在 astral.sh 上（Cloudflare，国内一般能通）；
    # 如果拉不动就走 GitHub 代理兜底。
    if curl -fsSL --max-time 90 -o /tmp/uvdl/install.sh https://astral.sh/uv/install.sh; then
        log "脚本已下载 $(wc -c < /tmp/uvdl/install.sh) 字节，开始执行"
        sh /tmp/uvdl/install.sh 2>&1 | tail -n 8
    elif [ -n "$GH_PROXY" ] && \
         curl -fsSL --max-time 90 -o /tmp/uvdl/install.sh \
              "${GH_PROXY}https://github.com/astral-sh/uv/releases/latest/download/uv-aarch64-unknown-linux-gnu.tar.gz"; then
        log "改从 GitHub 代理取二进制包"
        mkdir -p /tmp/uvbin && tar -xzf /tmp/uvdl/install.sh -C /tmp/uvbin 2>&1 | tail -n 3
        find /tmp/uvbin -name 'uv' -type f -exec install -m755 {} /usr/local/bin/uv \; 2>/dev/null
    else
        log "官方源下载失败，改用 pip 兜底（走 $PYPI_INDEX）"
        if command -v pip3 >/dev/null 2>&1; then
            pip3 install --break-system-packages -i "$PYPI_INDEX" uv 2>&1 | tail -n 5
        fi
    fi
    if [ -x "$HOME/.local/bin/uv" ]; then
        log "uv 装好：$($HOME/.local/bin/uv --version 2>&1)"
    elif command -v uv >/dev/null 2>&1; then
        log "uv 装好：$(uv --version 2>&1)"
    else
        log "uv 仍未安装成功"
    fi
}

do_python() {
    log "== 检查 Python =="
    if command -v python3 >/dev/null 2>&1; then
        log "python3: $(python3 --version 2>&1)"
    else
        log "python3 缺失，走 apt 安装"
        do_apt
        apt-get install -y --no-install-recommends python3 python3-venv python3-pip 2>&1 | tail -n 5
    fi
    # uv 需要 3.13，缺的话它会自己下载
    export PATH="$HOME/.local/bin:$PATH"
    if [ -x "$HOME/.local/bin/uv" ]; then
        log "让 uv 准备 Python 3.13…"
        "$HOME/.local/bin/uv" python install 3.13 2>&1 | tail -n 5
    fi
}

do_maibot() {
    log "== 安装 MaiBot =="
    # ---- 重装前备份用户数据（照 AstrBot prepare_reinstall_step 做法）----
    local BK="$HOME/maibot_data_reinstall_backup"
    local SRC=/root/maibot
    if [ -d "$BK" ]; then
        log "清理旧的重装备份 $BK"
        chmod -R u+rwX "$BK" 2>/dev/null
        rm -r "$BK" 2>/dev/null || true
    fi
    if [ -d "$SRC" ]; then
        mkdir -p "$BK"
        for d in config data plugins; do
            if [ -d "$SRC/$d" ]; then
                log "备份 maibot/$d -> $BK/$d"
                cp -a "$SRC/$d" "$BK/$d" 2>&1 | tail -n 3
            else
                log "跳过 maibot/$d（不存在）"
            fi
        done
        log "备份完成：$(ls "$BK" 2>&1 | tr '\n' ' ')"
    else
        log "未见 $SRC，无需备份"
    fi
    apply_pypi_mirror
    export PATH="$HOME/.local/bin:$PATH"
    if [ ! -x "$HOME/.local/bin/uv" ]; then
        log "uv 还没装，先装 uv"
        do_uv
    fi
    UV="$HOME/.local/bin/uv"
    [ -x "$UV" ] || UV=uv

    log "uv tool install --python 3.13 maibot"
    "$UV" tool install --python 3.13 maibot 2>&1 | tail -n 12

    if [ -x "$HOME/.local/share/uv/tools/maibot/bin/maibot" ]; then
        log "  ✓ maibot 已安装"
    else
        log "  ✗ maibot 安装后仍找不到入口"
    fi
}

do_deps() {
    log "== 补齐 Python 依赖 =="
    export PATH="$HOME/.local/bin:$PATH"
    UV="$HOME/.local/bin/uv"
    [ -x "$UV" ] || UV=uv

    # 用 uv tool upgrade 会顺带同步依赖；
    # 如果 maibot 没装过，这条会失败，那就直接走安装。
    if [ -d "$HOME/.local/share/uv/tools/maibot" ]; then
        log "重同步 maibot 的依赖…"
        "$UV" tool upgrade maibot 2>&1 | tail -n 10
    else
        do_maibot
    fi
}

do_instance() {
    log "== 初始化实例 =="
    export PATH="$HOME/.local/bin:$PATH"
    mkdir -p /root/maibot
    MB="$HOME/.local/share/uv/tools/maibot/bin/maibot"
    if [ ! -x "$MB" ]; then
        log "maibot 还没装，先装"
        do_maibot
    fi
    if [ -f /root/maibot/config/bot_config.toml ]; then
        log "实例已存在，跳过 init"
    else
        log "执行 maibot init"
        "$MB" init --directory /root/maibot 2>&1 | tail -n 12
    fi
    # ---- 重装后恢复用户数据（覆盖 init 生成的默认值）----
    local BK="$HOME/maibot_data_reinstall_backup"
    if [ -d "$BK" ]; then
        for d in config data plugins; do
            if [ -e "$BK/$d" ]; then
                log "恢复 maibot/$d ← $BK/$d"
                if [ -e "/root/maibot/$d" ]; then
                    chmod -R u+rwX "/root/maibot/$d" 2>/dev/null
                    rm -r "/root/maibot/$d" 2>/dev/null || true
                fi
                cp -a "$BK/$d" "/root/maibot/$d" 2>&1 | tail -n 3
            fi
        done
        log "恢复完毕，清理备份 $BK"
        chmod -R u+rwX "$BK" 2>/dev/null
        rm -r "$BK" 2>/dev/null || true
    else
        log "无重装备份，使用默认配置"
    fi
    # 修常见权限问题（root 装的目录 app 写不进去）
    chmod -R u+rwX /root/maibot 2>/dev/null
    log "实例目录：$(ls /root/maibot 2>&1 | tr '\n' ' ')"
}

do_snowluma() {
    log "== 安装 SnowLuma（QQ 桥接）=="
    fix_dns
    local DIR=/root/snowluma
    if [ -f "$DIR/node" ] && [ -f "$DIR/index.mjs" ]; then
        log "  ✓ SnowLuma 已安装：$($DIR/node --version 2>&1)"
        return 0
    fi
    mkdir -p "$DIR"

    # 只拉 linux-arm64 完整包（自带 node，省得再装运行时）
    local URL="https://github.com/SnowLuma/SnowLuma/releases/latest/download/SnowLuma-linux-arm64.tar.gz"
    local GOT=""
    if [ -n "$GH_PROXY" ]; then
        log "经 GitHub 代理下载：$GH_PROXY"
        if curl -fsSL --max-time 600 -o /tmp/sl.tgz "${GH_PROXY}${URL}"; then
            GOT="proxy"
        fi
    fi
    if [ -z "$GOT" ]; then
        log "直连 GitHub 下载…"
        if curl -fsSL --max-time 600 -o /tmp/sl.tgz "$URL"; then
            GOT="direct"
        fi
    fi
    if [ -z "$GOT" ]; then
        log "  ✗ 下载失败：GitHub 直连与代理都不通"
        return 1
    fi
    log "已下载 $(wc -c < /tmp/sl.tgz) 字节（$GOT），解压…"
    tar -xzf /tmp/sl.tgz -C "$DIR" 2>&1 | tail -n 3
    chmod +x "$DIR/node" "$DIR/launcher.sh" 2>/dev/null
    if [ -f "$DIR/node" ]; then
        log "  ✓ SnowLuma 就绪：$($DIR/node --version 2>&1)"
    else
        log "  ✗ 解压后找不到 node"
        return 1
    fi
}

case "$STEP" in
    apt)      do_apt ;;
    uv)       do_uv ;;
    python)   do_python ;;
    maibot)   do_maibot ;;
    deps)     do_deps ;;
    instance) do_instance ;;
    snowluma) do_snowluma ;;
    # env：只修「基础环境」，**不含 maibot 本体和 SnowLuma**。
    # 界面上的「一键更新基础环境」走这一条。原因：这两个体积大、
    # 而且用户可能已经自己装好了（甚至改过配置），一键更新不该把它们冲掉。
    # 真要重装它们，各自那一项点「安装/重装」即可，走 maibot / snowluma 分支。
    env)
        do_apt
        do_uv
        do_python
        do_instance
        ;;
    all)
        do_apt
        do_uv
        do_python
        do_maibot
        do_instance
        do_snowluma
        ;;
    *)
        echo "[env] 未知项目: $STEP"
        exit 2
        ;;
esac

echo "[env] STEP_DONE=$STEP"
