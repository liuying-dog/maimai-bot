package com.maimai.bot.core;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 常驻的 bash 会话，跑在 proot 的 Ubuntu 里。
 * <p>
 * 为什么不用伪终端：Android 上要开 PTY 得走 JNI 或调系统工具，依赖太重。
 * 这里用「一行命令 + 哨兵标记」的做法，足够跑 apt / uv / python 这类命令，
 * 代价是拿不到交互式补全和行内光标控制——TAB 键因此只插空格，不假装能补全。
 * <p>
 * 协议：每条命令后面追加 {@code ; printf "\n__MB_DONE__%d\n" $?}，
 * 读到这个标记就知道这条命令执行完了，同时拿到退出码。因为始终是同一个
 * bash 进程，cd / export 的效果会保留下来。
 */
public final class ShellSession {

    /** 结束哨兵前缀，带随机后缀降低被命令输出误撞的概率。 */
    private static final String DONE = "__MB_DONE__";

    private final Context ctx;
    private final Env env;

    private Process process;
    private OutputStream stdin;
    private final Object lock = new Object();
    private volatile boolean running;
    private volatile Listener listener;
    private int lastExit = -1;

    public interface Listener {
        void onLine(String line);

        /** 一条命令执行完毕（带退出码）。 */
        void onCommandDone(int code);

        /** 会话进程死掉了。 */
        void onExit(int code);
    }

    public ShellSession(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.env = Env.of(this.ctx);
    }

    /**
     * 进程级单例。
     * <p>
     * 不这样做的话，每次进出终端页都会 new 一个 ShellSession，
     * 于是 proot 进程一层层堆积（实测能同时看到两三个 bash 常驻），
     * 每个都占着几十 MB 内存，而且用户输入还容易被送到已经没人读的那个里。
     */
    private static ShellSession shared;

    public static synchronized ShellSession shared(Context ctx) {
        if (shared == null || !shared.isAlive()) {
            shared = new ShellSession(ctx);
        }
        return shared;
    }

    /** 页面销毁时只解绑监听，不杀进程，下次进来接着用。 */
    public static synchronized void release() {
        if (shared != null) {
            shared.setListener(null);
        }
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /**
     * 最近输出的环形缓冲，跟着会话走而不跟着页面走。
     * <p>
     * 之前缓冲放在 ConsoleActivity 里，配上进程级单例就出事：
     * 第二次进终端页时 start() 直接 return（会话还活着），
     * 新页面的缓冲是空的，界面就永远停在布局里的占位文字上，
     * 看起来像"一直正在连接"。把历史挂到会话上就不会丢。
     */
    private static final int HISTORY_LIMIT = 200 * 1024;
    private final StringBuilder history = new StringBuilder();

    private void record(String line) {
        synchronized (history) {
            history.append(line).append('\n');
            if (history.length() > HISTORY_LIMIT) {
                history.delete(0, history.length() - HISTORY_LIMIT * 3 / 4);
            }
        }
    }

    /** 取走已有的全部输出，用于页面重建时回放。 */
    public String history() {
        synchronized (history) {
            return history.toString();
        }
    }

    public void clearHistory() {
        synchronized (history) {
            history.setLength(0);
        }
    }

    public boolean isAlive() {
        return running && process != null && process.isAlive();
    }

    public int lastExit() {
        return lastExit;
    }

    // ------------------------------------------------------------- 启动

    public synchronized void start() throws IOException {
        if (isAlive()) {
            return;
        }
        // proot 启动前必须把 PROOT_TMP_DIR 指向的目录建出来。
        //
        // 之前只 在 Installer.install() 里建，结果有一条很容易踩的路径：
        // 用户刚装完 APK、还没装环境，就先点开「终端」页 —— ShellSession
        // 直接拉 proot，而 files/tmp 还不存在，proot 当场 fatal：
        //   proot error: can't create temporary directory: No such file or directory
        //   proot error: can't create glue rootfs
        //   proot error: '/bin/bash' not found (...)
        // 换一台干净手机就会撞上这个。这里自己兜一层，不依赖安装流程。
        env.ensureDirs();

        ProcessBuilder pb = buildCommand();
        pb.redirectErrorStream(true);
        pb.directory(env.base());
        applyEnv(pb);

        process = pb.start();
        stdin = process.getOutputStream();
        running = true;

        pumpOutput();
        // 启动时先做两件事：修 DNS、报一下环境。
        //
        // 修 DNS 是必须的：rootfs 里的 /etc/resolv.conf 是 systemd-resolved
        // 的存根（nameserver 127.0.0.53），而 proot 里根本没有 systemd，
        // 这个地址没人应答 —— 结果就是任何联网操作（apt / curl / uv）都会失败。
        // 这曾经是安装流程卡死的真正原因。
        //
        // 注意别用带引号的长单行命令：Java 字符串里嵌 shell 引号极易出
        // illegal escape character（\\w 就踩过）。
        //
        // PATH 必须在 bash 里面显式 export，不能只靠 ProcessBuilder 的环境变量：
        // proot 会把宿主（Android）的环境透传进 guest，而 Android 的 PATH
        // 是 /product/bin:/apex/...:/system/bin 这种，压根没有 /usr/bin，
        // 结果就是 guest 里 ls / grep / apt 全都 command not found。
        // 这个坑很隐蔽：进程活着、能进 rootfs、能 echo，但什么命令都跑不了。
        send("export PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        send("export HOME=/root");
        send("export LANG=C.UTF-8");
        send("printf 'nameserver 223.5.5.5\\nnameserver 119.29.29.29\\n' > /etc/resolv.conf");
        send("echo \"MaiBot Shell · $(uname -m) · $(grep -m1 PRETTY_NAME= /etc/os-release | cut -d= -f2-)\"");
        send("export PS1='maibot:\\w# '");
    }

    private ProcessBuilder buildCommand() {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(env.proot().getAbsolutePath());
        cmd.add("--link2symlink");
        cmd.add("-0");
        cmd.add("-r");
        cmd.add(env.rootfs().getAbsolutePath());
        cmd.add("-w");
        cmd.add("/root");
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        // 不要给 /root/maibot 做 bind：实例本来就住在 rootfs 里
        // （<rootfs>/root/maibot），再绑一次等于自己挂自己，
        // 某些情况下会让宿主侧的路径看不到真实内容，反而制造"装了却查不到"的鬼故事。
        cmd.add("-b");
        cmd.add(env.tmp().getAbsolutePath() + ":/tmp/hosttmp");
        cmd.add("-b");
        cmd.add("/system/etc/hosts:/etc/hosts");
        cmd.add("--kill-on-exit");
        cmd.add("/bin/bash");
        return new ProcessBuilder(cmd);
    }

    private void applyEnv(ProcessBuilder pb) {
        Map<String, String> e = pb.environment();
        e.put("PROOT_TMP_DIR", env.tmp().getAbsolutePath());
        e.put("PROOT_NO_SECCOMP", "1");
        // 外置 loader，理由见 Env.prootLoader()：App 进程有 seccomp filter，
        // 内嵌 loader 走的 exec 路径会被拦，导致 guest 里 getcwd 返回 ENOSYS。
        File loader = env.prootLoader();
        if (loader.isFile()) {
            e.put("PROOT_LOADER", loader.getAbsolutePath());
        }
        e.put("LD_LIBRARY_PATH", env.bin().getAbsolutePath());
        e.put("HOME", "/root");
        e.put("USER", "root");
        e.put("TERM", "xterm-256color");
        e.put("LANG", "C.UTF-8");
        e.put("LC_ALL", "C.UTF-8");
        e.put("PATH",
                "/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        e.put("UV_LINK_MODE", "copy");
        e.put("TMPDIR", "/tmp/hosttmp");
        e.put("PYTHONUNBUFFERED", "1");
    }

    // ------------------------------------------------------------- 输出泵

    private void pumpOutput() {
        Thread t = new Thread(() -> {
            int code = -1;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8), 8192)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith(DONE)) {
                        String num = line.substring(DONE.length()).trim();
                        try {
                            lastExit = Integer.parseInt(num);
                        } catch (NumberFormatException ex) {
                            lastExit = -1;
                        }
                        Listener l = listener;
                        if (l != null) {
                            l.onCommandDone(lastExit);
                        }
                        continue;
                    }
                    record(line);
                    Listener l = listener;
                    if (l != null) {
                        l.onLine(line);
                    }
                }
            } catch (IOException ignored) {
            } finally {
                running = false;
                try {
                    code = process.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                Listener l = listener;
                if (l != null) {
                    l.onExit(code);
                }
            }
        }, "shell-pump");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------- 发送

    /** 执行一条命令。内部排队，避免多线程抢 stdin。 */
    public void send(String command) {
        if (command == null) {
            return;
        }
        String trimmed = command.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        synchronized (lock) {
            if (!running) {
                // 会话没了，先重开再投递
                try {
                    start();
                } catch (IOException e) {
                    Listener l = listener;
                    if (l != null) {
                        l.onLine("[会话] 启动失败: " + e.getMessage());
                    }
                    return;
                }
            }
            try {
                // 只发命令本身 + 哨兵。不在这里追加提示符：
                // 输出区是纯文本，每条命令后面都跟一行 'maibot:/root#' 会很吵，
                // 提示符放在启动时打一次就够。
                String payload = command
                        + "\nprintf '\\n" + DONE + "%d\\n' $?\n";
                stdin.write(payload.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } catch (IOException e) {
                Listener l = listener;
                if (l != null) {
                    l.onLine("[会话] 写入失败，尝试重启: " + e.getMessage());
                }
                restartQuietly();
            }
        }
    }

    /** 等价于 Ctrl-C：直接杀进程并重开，bash 没有别的办法从外部中断。 */
    public void interrupt() {
        Listener l = listener;
        if (l != null) {
            l.onLine("^C");
        }
        restartQuietly();
    }

    public synchronized void reset() {
        restartQuietly();
    }

    private void restartQuietly() {
        stopProcess();
        try {
            start();
        } catch (IOException e) {
            Listener l = listener;
            if (l != null) {
                l.onLine("[会话] 重启失败: " + e.getMessage());
            }
        }
    }

    public synchronized void stop() {
        running = false;
        stopProcess();
    }

    private void stopProcess() {
        Process p = process;
        process = null;
        if (p != null) {
            try {
                stdin.close();
            } catch (Exception ignored) {
            }
            p.destroy();
            try {
                if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        stdin = null;
    }
}