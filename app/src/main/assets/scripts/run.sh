#!/bin/bash
# 启动 MaiBot 实例。
#
# ══════════════════════════════════════════════════════════════════
# 改这里之前先读完。每一行都是真机上一层层试出来的。
#
# 环境特点：App 进程带着 Android 强制安装的 seccomp filter
# （/proc/<pid>/status: Seccomp=2, Seccomp_filters=1）。
# proot 在这层限制下做路径虚拟化时，一批系统调用会返回 ENOSYS，
# 部分 Rust 扩展甚至会直接 abort。同一套命令换到 run-as 起的
# 进程里完全正常，因为那边没有 seccomp。
#
# 它把 maibot 的启动打断了六次，而且每次报错都指向无关的地方：
#
#   1. argparse 求 --directory 默认值 → Path.cwd()
#   2. src/cli/tool.py:30 → os.chdir(directory)
#   3. src/common/paths.py:19 → Path(directory).resolve()
#      （resolve 走 C 层实现，光补 os.getcwd 够不到）
#   4. 插件 Runner 子进程 → logger.py 里 Path(__file__).resolve()
#      子进程是全新解释器，所以补丁必须装成
#      site-packages/sitecustomize.py，让 Python 的 site 模块自动加载
#   5. data/custom_prompts 下的深层路径不存在时，proot 解析也 ENOSYS
#   6. FileWatcher 用 watchfiles.awatch（Rust 扩展 + inotify），
#      inotify 被拦后在 Rust 层直接中止，python 层连 traceback 都拿不到
#
# 对应处理：
#   · 1/2/3/4 → sitecustomize.py 兜住（含 Path.resolve）
#   · 4 不再设 MAIBOT_WORKER_PROCESS=1：必须保留 Runner 守护进程，
#       否则 WebUI 的「立即更新」（退出码 42）无人执行，更新会丢
#   · 5 → 启动前先把 data 子目录建好
#   · 6 → sitecustomize.py 里把 watchfiles 换成一个永不触发的空实现
#
# 试过但没用、别再走一遍：外置 loader（PROOT_LOADER，AstrBot 版和
# Operit 版都试过）、去掉 -w、e.clear() 清环境、targetSdk 27/28。
# ══════════════════════════════════════════════════════════════════
INSTANCE="${MAIBOT_INSTANCE:-/root/maibot}"
TOOL_ENV="/root/.local/share/uv/tools/maibot"
SITE_PACKAGES="$TOOL_ENV/lib/python3.13/site-packages"

export EULA_AGREE="8e6e7d647f7f82d6ea98456b73908656"
export PRIVACY_AGREE="91e5db7659c560bc3545e63859b6ebc0"

export PATH="/root/.local/bin:$TOOL_ENV/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export HOME="/root"

export UV_LINK_MODE=copy
export PYTHONUNBUFFERED=1
export PYTHONIOENCODING=utf-8
# 进程被信号打死时把 Python 堆栈打出来，方便定位（seccomp 拦 syscall 时常见）
export PYTHONFAULTHANDLER=1
export MAIBOT_CWD="$INSTANCE"
# maibot 官方指定实例目录的变量，CLI 内部会读它
export MAIBOT_INSTANCE_DIR="$INSTANCE"

# 跳过 Runner，直接在本进程跑 Worker
export MAIBOT_PACKAGE_RUNNER=1

# ── seccomp 垫片：麦麦的 Python 也必须加载它 ─────────────────────────
#
# 之前只给 NapCat 那一路（Xvfb / LinuxQQ）设了 LD_PRELOAD，
# 麦麦自己的 python 进程完全没有垫片。真机取证：
#   PID 2376 (Runner)  Seccomp=2  environ 里没有 LD_PRELOAD
#   PID 5025 (Worker)  Seccomp=2  environ 里没有 LD_PRELOAD
# 结果是 App 的 seccomp filter 直接把 chdir/mkdir/readlink 这些
# 系统调用打回 ENOSYS（Errno 38），于是 WebUI 点「立即更新」时
# request_update() 里那句
#   Path(directory).expanduser().resolve()
# 直接抛：
#   OSError: [Errno 38] Function not implemented: PosixPath('/root/maibot')
#
# 垫片已经 hook 了 getcwd / chdir / mkdir / rmdir / readlink / realpath /
# unlink / unlinkat / link / linkat / symlink / syscall，
# 只要真的 preload 进去，这些调用就会走兜底实现而不是 ENOSYS。
#
# 注意两点：
#   1) 垫片里有 constructor 会打 "[seccompshim] loaded, PWD=..."，
#      日志里看到这一行才说明它真的被 ld.so 装上了；
#   2) 只有文件存在时才设，避免垫片没解出来时把所有动态链接搞崩。
if [ -f /root/libseccompshim.so ]; then
  export LD_PRELOAD="/root/libseccompshim.so"
  echo "[运行] seccomp 垫片已挂载：$LD_PRELOAD"
else
  echo "[运行] 警告：/root/libseccompshim.so 不存在，seccomp 兜底无法启用"
fi

# rootfs 里的 resolv.conf 是 systemd 存根（127.0.0.53），proot 里没人应答
printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\n' > /etc/resolv.conf 2>/dev/null

# maibot 会读 data 下这些子目录，缺一个就可能触发 ENOSYS
#
# 这是踩出来的坑：在 App 进程的 seccomp 下，proot 去 stat 一个
# 「父目录不存在」的路径时，走的 syscall 会被拦，返回的是
# Errno 38 (ENOSYS / Function not implemented)，而不是正常的 ENOENT。
# 表现就是各种莫名其妙的功能挂掉，例如：
#   - WebUI 的「检查更新」报 Internal Server Error
#     （读 data/self_update/last_result.json 时 ENOSYS）
#   - 记忆流服务报错
#     （读 data/a-memorix/metadata/metadata.db 时 ENOSYS）
#   - WebUI 列 Prompt 文件失败
#     （读 data/custom_prompts/en-US/*.prompt 时 ENOSYS）
# 所以启动前把这些目录全建出来，让 stat 走正常路径。
mkdir -p "$INSTANCE/data/custom_prompts/zh-CN" \
         "$INSTANCE/data/custom_prompts/en-US" \
         "$INSTANCE/data/custom_prompts/ja-JP" \
         "$INSTANCE/data/emoji" \
         "$INSTANCE/data/images" \
         "$INSTANCE/data/webui" \
         "$INSTANCE/data/self_update" \
         "$INSTANCE/data/a-memorix/metadata" \
         "$INSTANCE/data/plugin_runtime" \
         "$INSTANCE/logs" \
         "$INSTANCE/temp" 2>/dev/null
# 目录归属要跟着实例走，否则 app 用户写不进去
chmod -R u+rwX "$INSTANCE/data" "$INSTANCE/logs" "$INSTANCE/temp" 2>/dev/null

# ── 装 sitecustomize.py ────────────────────────────────────────
if [ -d "$SITE_PACKAGES" ]; then
  cat > "$SITE_PACKAGES/sitecustomize.py" <<'PYPATCH'
"""proot + Android seccomp 下的兼容垫片。

每个函数都先试原生调用，只有真出问题才走兜底，
所以在正常环境里行为与原版完全一致。

覆盖点（都是真机上一个一个崩出来的）：
  os.getcwd / os.getcwdb     取当前目录
  os.chdir                   切目录
  Path.cwd / Path.resolve    当前目录与绝对化（C 层实现）
  Path.absolute              同上
  watchfiles.awatch          换成永不触发的空实现（inotify 被 seccomp 拦）
"""
import asyncio
import os

_FALLBACK_CWD = os.environ.get("MAIBOT_CWD", "/root/maibot")

# ── os 层 ──────────────────────────────────────────────────────
_real_getcwd = os.getcwd


def _safe_getcwd():
    try:
        return _real_getcwd()
    except OSError:
        return _FALLBACK_CWD


os.getcwd = _safe_getcwd

try:
    _real_getcwdb = os.getcwdb

    def _safe_getcwdb():
        try:
            return _real_getcwdb()
        except OSError:
            return _FALLBACK_CWD.encode()

    os.getcwdb = _safe_getcwdb
except AttributeError:
    pass

_real_chdir = os.chdir


def _safe_chdir(path):
    try:
        return _real_chdir(path)
    except OSError:
        return None


os.chdir = _safe_chdir

# ── pathlib 层 ─────────────────────────────────────────────────
try:
    from pathlib import Path as _Path

    _real_path_resolve = _Path.resolve
    _real_path_absolute = _Path.absolute

    def _collapse(p):
        parts = []
        for seg in str(p).split("/"):
            if seg in ("", "."):
                continue
            if seg == "..":
                if parts:
                    parts.pop()
                continue
            parts.append(seg)
        return "/" + "/".join(parts)

    def _safe_resolve(self, strict=False):
        try:
            return _real_path_resolve(self, strict=strict)
        except OSError:
            p = str(self)
            if not p.startswith("/"):
                p = _FALLBACK_CWD.rstrip("/") + "/" + p
            return _Path(_collapse(p))

    def _safe_absolute(self):
        try:
            return _real_path_absolute(self)
        except OSError:
            p = str(self)
            if not p.startswith("/"):
                p = _FALLBACK_CWD.rstrip("/") + "/" + p
            return _Path(p)

    _Path.resolve = _safe_resolve
    _Path.absolute = _safe_absolute
    _Path.cwd = classmethod(lambda cls: cls(_FALLBACK_CWD))
except Exception:
    pass

# ── stat 系列：把 ENOSYS 降级成 ENOENT ─────────────────────────
#
# 这是最普遍的一类问题，值得单独兜一层。
#
# 现象：在 App 进程的 seccomp 下，proot 去 stat 一个「父目录不存在」
# 的路径时，返回的不是正常的 ENOENT(2)，而是 ENOSYS(38)。
# 上层代码写的是 `if not path.is_file(): return None` 这种，
# 它期望的是「文件不存在」这个正常分支，结果拿到 ENOSYS 直接抛出去，
# 变成一个 500 或者一条 error 日志。实测踩到的例子：
#   - WebUI「检查更新」500：data/self_update/last_result.json
#   - 记忆流服务报错：data/a-memorix/metadata/metadata.db
#   - WebUI 列 Prompt 失败：data/custom_prompts/en-US/*.prompt
#
# 处理方式：包装 os.stat / os.lstat / os.access，
# 碰到 errno == 38 就改抛 FileNotFoundError。
# 语义上完全对得上 —— 「这个路径我没法 stat」≈「它不存在」，
# 调用方原本就是按不存在来处理的。
import errno as _errno

def _wrap_stat(orig):
    def wrapper(path, *a, **kw):
        try:
            return orig(path, *a, **kw)
        except OSError as e:
            if e.errno == _errno.ENOSYS:
                raise FileNotFoundError(_errno.ENOENT, "No such file or directory", str(path)) from None
            raise
    return wrapper

try:
    os.stat = _wrap_stat(os.stat)
    os.lstat = _wrap_stat(os.lstat)
except Exception:
    pass

# os.scandir / listdir 在目录不存在时同样可能 ENOSYS
def _wrap_listing(orig):
    def wrapper(path=".", *a, **kw):
        try:
            return orig(path, *a, **kw)
        except OSError as e:
            if e.errno == _errno.ENOSYS:
                raise FileNotFoundError(_errno.ENOENT, "No such file or directory", str(path)) from None
            raise
    return wrapper

try:
    os.listdir = _wrap_listing(os.listdir)
    os.scandir = _wrap_listing(os.scandir)
except Exception:
    pass

# ── asyncio 事件循环 ───────────────────────────────────────────
# CPython 在 Linux 上默认用 epoll + timerfd + eventfd 跑事件循环。
# 这几个系统调用在 Android 的 seccomp 策略下会被拦，而且**不会**
# 抛 Python 异常 —— 解释器直接没了，日志停在一句话上，连 traceback
# 都留不下。表现就是「正在唤醒 麦麦......」之后进程凭空消失。
#
# 换成 SelectSelector（纯 select/poll 实现），系统调用面小得多。
# 对 maibot 这种聊天 bot 完全够用，代价只是连接数极多时性能略差。
#
# 注意这里不能只 set_event_loop：maibot 后面会自己调 asyncio.run()，
# 而 asyncio.run() 内部会走 new_event_loop()，把我们的设置顶掉。
# 所以直接替换 new_event_loop 本身，谁调都拿到 select 版。
try:
    import asyncio as _asyncio
    import selectors as _selectors

    _origin_new_event_loop = _asyncio.new_event_loop
    _select_loop_cls = None

    try:
        _base_loop_cls = _asyncio.SelectorEventLoop

        class _SelectLoop(_base_loop_cls):  # type: ignore[misc,valid-type]
            def __init__(self):
                super().__init__(selector=_selectors.SelectSelector())

        _select_loop_cls = _SelectLoop
    except Exception:
        _select_loop_cls = None

    if _select_loop_cls is not None:

        def _new_event_loop():
            try:
                return _select_loop_cls()
            except Exception:
                # 万一这个平台构造不出来，退回原生实现，别把进程搞死
                return _origin_new_event_loop()

        _asyncio.new_event_loop = _new_event_loop
except Exception:
    pass

# ── 致命信号留痕 ───────────────────────────────────────────────
# 进程被信号打死时（seccomp 拦 syscall 常表现为 SIGSYS/SIGABRT），
# 把当时的 Python 堆栈直接打到 stderr。输出会进 App 的 [maibot] 日志流，
# 至少能看出死在哪一行，而不是只剩一句「正在唤醒」。
try:
    import faulthandler as _fault
    import sys as _sys

    _fault.enable(file=_sys.stderr, all_threads=True)
except Exception:
    pass

# ── watchfiles ─────────────────────────────────────────────────
# maibot 的 FileWatcher 用 watchfiles.awatch 监听配置文件变更。
# watchfiles 是 Rust 扩展，底层 inotify 在 seccomp 下被拦，
# 而且它是在 Rust 里直接中止的，python 层根本收不到异常，
# 表现就是进程静默消失、日志停在「诊断阶段开始」。
#
# 这里换成永不产出事件的实现：文件监视在这个场景下只是锦上添花
# （改配置后自动重载），关掉不影响 bot 本身工作。
try:
    import watchfiles as _wf

    async def _idle_awatch(*args, **kwargs):
        # 永远挂起，不产出任何变更事件
        _never = asyncio.Event()
        await _never.wait()
        # 下面这行永远不会执行，只是让函数保持 async generator 语义
        yield set()  # pragma: no cover

    _wf.awatch = _idle_awatch
except Exception:
    pass
PYPATCH
  echo "[运行] sitecustomize.py 已就位"
else
  echo "[运行] 警告：没找到 site-packages，补丁未安装：$SITE_PACKAGES"
fi

echo "[运行] 实例目录 $INSTANCE"
cd / 2>/dev/null || true
exec maibot run --directory "$INSTANCE"