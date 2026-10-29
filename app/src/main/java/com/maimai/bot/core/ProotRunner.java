package com.maimai.bot.core;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 在 proot 里执行命令的唯一入口。
 * <p>
 * proot 关键参数说明（都是踩过的坑）：
 * <ul>
 *   <li>{@code -0} 伪装 uid/gid 0。安卓非 root 进程无法真正 chroot，
 *       proot 用 ptrace 改写系统调用返回值实现，必须开这个才能当 root 用。</li>
 *   <li>{@code --link2symlink} 安卓的 /data 分区不支持硬链接，
 *       打上后 proot 把硬链接自动退化成软链，否则 apt 装包必炸。</li>
 *   <li>{@code --kill-on-exit} 父进程死掉时连带清掉子进程，
 *       避免 bot 停了但 python 还在后台空转。</li>
 *   <li>PROOT_TMP_DIR 必须指到可写目录，默认值在安卓上不存在，
 *       会刷一堆 "can't canonicalize" 警告甚至直接起不来。</li>
 *   <li>PROOT_NO_SECCOMP 关掉 seccomp 加速。安卓内核的 seccomp 策略
 *       经常和 proot 冲突，导致进程莫名 SIGSYS。</li>
 * </ul>
 */
public final class ProotRunner {

    private final Context ctx;
    private final Env env;

    public ProotRunner(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.env = Env.of(this.ctx);
    }

    public Env env() {
        return env;
    }

    /** 生成 proot 的完整命令行（不含要执行的程序本身）。 */
    private List<String> baseCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(env.proot().getAbsolutePath());
        cmd.add("--link2symlink");
        cmd.add("-0");
        cmd.add("-r");
        cmd.add(env.rootfs().getAbsolutePath());
        // 这里刻意不传 -w。
        //
        // proot 的 -w 会在启动时对 guest 做 chdir，而 App 进程带着 Android
        // 强制挂的 seccomp filter，chdir 直接返回 ENOSYS：
        //   proot error: can't chdir to '/root': Function not implemented
        // 一旦 chdir 失败，后面连 execve 都会跟着报 No such file or directory，
        // 整条链就废了。所以工作目录交给 ProcessBuilder.directory() 在宿主侧定，
        // 不在 guest 里切。BotService 那边一直是这么做的，所以它没踩到这个坑。
        // 设备节点：不给 /dev 的话很多程序（python 的 random、urandom）会挂
        bind(cmd, "/dev");
        bind(cmd, "/proc");
        bind(cmd, "/sys");

        // 实例目录挂进 rootfs，方便在应用侧直接改配置。
        // 全新安装时 /root/maibot 还不存在（是 bootstrap 里 maibot init 才创建的），
        // 拿一个不存在的宿主路径去 -b，proot 会先刷一条
        //   can't sanitize binding ".../root/maibot": No such file or directory
        // 再继续往下走。这里先建出来，日志就干净了，自检也不会被这条噪声带偏。
        //noinspection ResultOfMethodCallIgnored
        env.instance().mkdirs();
        bind(cmd, env.instance().getAbsolutePath(), "/root/maibot");
        bind(cmd, env.tmp().getAbsolutePath(), "/tmp/hosttmp");

        // 让 rootfs 里能解析 DNS
        cmd.add("-b");
        cmd.add("/system/etc/hosts:/etc/hosts");

        cmd.add("--kill-on-exit");
        return cmd;
    }

    private static void bind(List<String> cmd, String host) {
        cmd.add("-b");
        cmd.add(host);
    }

    private static void bind(List<String> cmd, String host, String guest) {
        cmd.add("-b");
        cmd.add(host + ":" + guest);
    }

    /** 组装环境变量。 */
    private void applyEnv(ProcessBuilder pb) {
        Map<String, String> e = pb.environment();
        // 和 BotService 那边保持一致：先把 Android 透传进来的变量清干净。
        // ANDROID_DATA / BOOTCLASSPATH 这些会让 proot 推断出错误的 guest 路径。
        e.clear();
        e.put("PROOT_TMP_DIR", env.tmp().getAbsolutePath());
        e.put("PROOT_NO_SECCOMP", "1");
        // 外置 loader —— 这一条是自检能不能过的分水岭。
        //
        // App 进程带着 Android 强制的 seccomp filter，proot 用内嵌 loader
        // 去 exec 会被拦，表现就是一堆：
        //   proot error: can't chmod '.../tmp/proot-XXXX': Function not implemented
        //   proot error: can't chdir to '/': Function not implemented
        //   proot error: execve("/usr/bin/true"): No such file or directory
        // 于是「proot 自检失败: 退出码 1」，安装就在 97% 处夭折。
        // BotService 一直有设 PROOT_LOADER，所以它能跑；这里漏了，
        // 结果就是同一个环境「点启动出错、手动跑却没事」。
        File loader = env.prootLoader();
        if (loader.isFile()) {
            e.put("PROOT_LOADER", loader.getAbsolutePath());
        }
        // 指向 nativeLibraryDir，proot 这次进程启动时要用它找 libtalloc。
        // 注意这会把变量带进 guest：guest 的 /usr/lib 不受影响（这边是绝对路径
        // /data/app/.../lib/arm64），所以不会干扰 rootfs 里的动态库。
        e.put("LD_LIBRARY_PATH", env.bin().getAbsolutePath());
        e.put("HOME", "/root");
        e.put("USER", "root");
        e.put("LANG", "C.UTF-8");
        e.put("LC_ALL", "C.UTF-8");
        e.put("TERM", "xterm-256color");
        e.put("PATH",
                "/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        // uv 在这类环境里必须用 copy，硬链接一律失败
        e.put("UV_LINK_MODE", "copy");
        e.put("UV_DEFAULT_INDEX", "https://pypi.tuna.tsinghua.edu.cn/simple");
        e.put("TMPDIR", "/tmp/hosttmp");
        e.put("PYTHONUNBUFFERED", "1");
        e.put("PYTHONIOENCODING", "utf-8");
    }

    /**
     * 执行命令，把 stdout+stderr 合并后逐行回调。
     *
     * @param args      要执行的程序与参数，如 {"/bin/bash","-lc","..."}
     * @param onLine    每行输出回调，可为 null
     * @param onExit    退出回调，可为 null
     * @return 进程对象，可用于 destroy
     */
    public Process execStream(String[] args, LineListener onLine, ExitListener onExit)
            throws IOException {
        List<String> cmd = baseCommand();
        for (String a : args) {
            cmd.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(env.base());
        applyEnv(pb);

        Process p = pb.start();

        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8), 8192)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (onLine != null) {
                        onLine.onLine(line);
                    }
                }
            } catch (IOException ignored) {
            } finally {
                int code = -1;
                try {
                    code = p.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (onExit != null) {
                    onExit.onExit(code);
                }
            }
        }, "proot-reader");
        reader.setDaemon(true);
        reader.start();

        return p;
    }

    /** 同步执行并把输出攒成字符串，用于短命令。 */
    public int execCapture(String[] args, long timeoutMs, StringBuilder sink)
            throws IOException {
        List<String> cmd = baseCommand();
        for (String a : args) {
            cmd.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(env.base());
        applyEnv(pb);

        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (sb.length() > 64 * 1024) {
                        // 防止短命令因为意外狂输出把内存撑爆
                        sb.delete(0, sb.length() - 32 * 1024);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "proot-capture");
        t.setDaemon(true);
        t.start();

        try {
            boolean done = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroy();
                p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                p.destroyForcibly();
                if (sink != null) {
                    sink.append(sb);
                }
                return -2; // 超时
            }
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return -3;
        }

        if (sink != null) {
            sink.append(sb);
        }
        return p.exitValue();
    }

    /** 在 rootfs 里跑一段 bash 脚本。 */
    public int execShell(String script, long timeoutMs, StringBuilder sink) throws IOException {
        return execCapture(new String[]{"/bin/bash", "-lc", script}, timeoutMs, sink);
    }

    /** 检查 rootfs 里某个命令是否存在。 */
    public boolean hasCommand(String name) {
        try {
            return execCapture(new String[]{"/bin/sh", "-c", "command -v " + name},
                    10_000, null) == 0;
        } catch (IOException e) {
            return false;
        }
    }

    public interface LineListener {
        void onLine(String line);
    }

    public interface ExitListener {
        void onExit(int code);
    }
}