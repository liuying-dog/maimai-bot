"""proot + Android seccomp 环境下的 getcwd/chdir 兼容垫片。

为什么会有这个文件
────────────────
App 进程带着 Android 强制安装的 seccomp filter
（/proc/<pid>/status 里 Seccomp=2、Seccomp_filters=1）。
proot 在这层限制下做路径虚拟化时，getcwd() 和 chdir() 都会返回
ENOSYS（Function not implemented）。同样的命令换到 run-as 起的
进程里跑完全正常，因为那边没有 seccomp。

这跟 maibot 无关，但会连环炸三次：
  1. argparse 求 --directory 默认值 → Path.cwd() → 崩
  2. src/cli/tool.py 的 os.chdir(directory) → 崩
  3. 插件 Runner 子进程里 src/common/logger.py 的
     Path(__file__).resolve() → 崩

所以补丁不能用「启动脚本里 import 一次」的办法 —— 子进程是
全新解释器，压根不会执行到那个脚本。必须放在 site-packages 里
叫 sitecustomize.py：Python 启动时 site 模块会自动 import 它，
主进程和所有子进程都能吃到。

行为约定
────────
每个函数都先试原生调用，只有真的抛 OSError 才走兜底。
所以在正常环境（比如 run-as 里、或者在电脑上）跑，行为与原版
完全一致，不会有副作用。
"""

import os

_FALLBACK_CWD = os.environ.get("MAIBOT_CWD", "/root/maibot")

# ── getcwd ──────────────────────────────────────────────────────
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

# ── chdir ───────────────────────────────────────────────────────
_real_chdir = os.chdir


def _safe_chdir(path):
    try:
        return _real_chdir(path)
    except OSError:
        # 切不过去就算了。maibot 内部用的都是绝对路径，
        # 少切一次目录不会导致找不到文件。
        return None


os.chdir = _safe_chdir

# ── pathlib 的 cwd ──────────────────────────────────────────────
# Path.cwd() 内部走 os.getcwd()，上面已经兜住了；
# 但部分版本会把结果缓存下来，这里再显式覆盖一次更保险。
try:
    from pathlib import Path as _Path

    _Path.cwd = classmethod(lambda cls: cls(_FALLBACK_CWD))
except Exception:
    pass
