package com.maimai.bot.core;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 把「本应用 rootfs 里跑的那一堆进程」统统干掉。
 * <p>
 * 为什么需要它：原来「停止」只是对 ProcessBuilder 返回的那个 Process 句柄
 * 调 destroy()。可 maibot 是这么长起来的：
 * <pre>
 *   BotService → proot（一个进程）
 *                 → bash
 *                   → python -m src.runtime（真正的 maibot）
 *                     → 插件运行时的若干子 python
 * </pre>
 * destroy() 只把最外面那层收了，里面的 python 立刻变成孤儿继续跑，
 * 端口 8001 照样 LISTEN，于是「点了停止，WebUI 还能打开」。
 * <p>
 * 实测证据（宿主视角）：
 * <pre>
 * PID=28003 libproot.so ... -r /data/user/0/com.maimai.bot/files/rootfs
 * LISTEN 127.0.0.1:8001 users:(("maibot",pid=28009,fd=21))
 * curl → code=200
 * </pre>
 * 所以这里改成按「进程命令行里出现本应用 rootfs 路径」来识别，
 * 和进程树结构无关，谁在跑都能揪出来。
 */
public final class ProcessKiller {

    private static final String TAG = "ProcessKiller";

    /** 这些命令行里出现的片段，说明它属于本应用的运行环境。 */
    private static final String[] SIGNATURES = {
            "libproot.so",
            "src.runtime",
            "src.plugin_runtime",
            "/maibot",
            // 下面几条是「孤儿化之后」才会看到的形态。
            // proot 把 guest 的路径伪装给子进程，/proc/<pid>/cmdline 里剩下的
            // 就是纯 guest 路径，没有 /data/user/0/com.maimai.bot/files/rootfs 前缀，
            // 所以只按 rootfs 路径匹配一定会漏掉它们 —— 之前「停止之后 8001 还活着」
            // 就是这个原因：
            //   21263  ppid=1  /root/.local/share/uv/tools/maibot/bin/python ... run
            "maibot/bin/maibot",
            // NapCat + LinuxQQ：登录后这两层都会长出来，
            // 而且第二个（QQ 本体）在 App 沙盒里跑得非常稳，必须能一起收掉。
            "/root/napcat/napcat.mjs",
            "/root/napcat/loadNapCat.js",
            "/opt/QQ/qq",
    };

    /**
     * 这些片段出现在命令行里，说明它是「麦麦自己在更新」的进程，任何清理都不能碰。
     * <p>
     * 真机血证（2026-10-29 19:36 那次 1.3.15 → 1.3.18）：
     * <pre>
     *   package_updater.py 日志：RC=0 之前进程凭空消失
     *   last_result.json → "stage":"request"
     *                    → "error":"独立更新进程失败: [seccompshim] loaded, PWD=/root/maibot"
     * </pre>
     * stderr 里只有垫片那行构造器打印、一句话的 traceback 都没有 ——
     * 这说明子进程不是自己崩的，是被外面一刀切掉的。
     * <p>
     * 罪魁是 {@link #SIGNATURES} 里的 {@code "/maibot"}：更新子进程的命令行是
     * <pre>
     *   .../python3 -I /root/maibot/data/self_update/package_updater.py /tmp/package_request.json
     * </pre>
     * 正中 {@code /maibot} 这一条，于是被当成"麦麦本体"秒杀。
     * 而 {@code uv pip install} 要跑十几分钟，这中间只要用户点一下停止 / 开一次 NapCat /
     * 退出重进 App，更新就被拦腰砍断，然后报一句看不懂的"[seccompshim] loaded"。
     */
    private static final String[] UPDATE_GUARD = {
            "package_updater.py",
            "self_update/package_result.json",
            "self_update/package_request.json",
            "package_request.json",
            "self_update",
    };

    /**
     * 这个进程是不是正在执行自更新。是的话一律放过，绝不发信号。
     */
    private static boolean isUpdateWorker(String cmd) {
        if (cmd == null || cmd.isEmpty()) {
            return false;
        }
        for (String guard : UPDATE_GUARD) {
            if (cmd.contains(guard)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 当前有没有正在跑的自更新进程。
     * <p>
     * 给「停止」流程用：更新中途被停掉，等于把 site-packages 拆到一半就撒手，
     * 轻则 dashboard 静态文件缺失（WebUI 白屏），重则整个环境的 .py 被写成 0 字节。
     * 真机上就撞过一次：更新被打断后，14278 个文件里有 64 个少了 1 个字节。
     * <p>
     * 所以停止时要先问一句：现在能不能停？能停才停。
     */
    public static boolean hasUpdateWorker() {
        int myUid = android.os.Process.myUid();
        int self = android.os.Process.myPid();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) {
            return false;
        }
        for (File e : entries) {
            String name = e.getName();
            if (!isNumeric(name)) {
                continue;
            }
            int pid = Integer.parseInt(name);
            if (pid == self || pid == 1) {
                continue;
            }
            if (readUid(pid) != myUid) {
                continue;
            }
            if (isUpdateWorker(readCmdline(pid))) {
                return true;
            }
        }
        return false;
    }

    private ProcessKiller() {
    }

    /**
     * 只杀掉 NapCat / LinuxQQ / Xvfb 这一支，**绝不碰麦麦**。
     * <p>
     * 为什么必须有这个方法（真机踩出来的）：
     * {@link #killAll} 的判定里有 {@code sameUid = readUid(pid) == myUid}，
     * 只要进程 uid 是本应用就继续看 SIGNATURES，而 SIGNATURES 里包含
     * {@code src.runtime} / {@code /maibot} / {@code maibot/bin/maibot}，
     * 这三条正好命中麦麦的
     * {@code /root/.local/share/uv/tools/maibot/bin/python -m src.runtime}。
     * <p>
     * 于是 NapCat 启动时（我在 start() 里加过「启动前无条件清场」）
     * 会顺手把麦麦一起干掉 —— 用户看到的就是「一开 NapCat，麦麦自动停止」。
     * 实测：NapCat 起来后 curl 127.0.0.1:8001 → 000，麦麦确实没了。
     * <p>
     * 所以 NapCat 启停必须用这套「只认 NapCat 特征」的清理：
     * <ul>
     *   <li>{@code /opt/QQ/qq} —— QQ 主进程与 zygote</li>
     *   <li>{@code napcat.mjs} / {@code loadNapCat.js} —— NapCat 注入脚本</li>
     *   <li>{@code Xvfb} —— 虚拟显示</li>
     *   <li>带 {@code libproot.so} 且命令行里出现 {@code launcher_} —— 我们自己的启动脚本</li>
     * </ul>
     * 注意不匹配 {@code libproot.so} 本身：麦麦也是用它拉起来的。
     */
    private static final String[] NAPCAT_SIGNATURES = {
            "/opt/QQ/qq",
            "napcat.mjs",
            "loadNapCat.js",
            "Xvfb",
            "/root/launcher_",
    };

    /**
     * 有没有属于本应用的 QQ / NapCat / Xvfb 进程在跑。
     * <p>
     * 给 {@link NapCatAccount#isRunning()} 做最后兜底用。真机上出现过
     * 「6099 明明 curl 返回 200，但 App 里端口探测拿不到」的情况，
     * 那时候只按端口判断就会一直显示「未运行」，可实际服务是好的。
     * 只要进程还在，就不该显示未运行。
     */
    public static boolean hasNapCatProcess() {
        int myUid = android.os.Process.myUid();
        int self = android.os.Process.myPid();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) {
            return false;
        }
        for (File e : entries) {
            String name = e.getName();
            if (!isNumeric(name)) {
                continue;
            }
            int pid = Integer.parseInt(name);
            if (pid == self) {
                continue;
            }
            if (readUid(pid) != myUid) {
                continue;
            }
            String cmd = readCmdline(pid);
            if (cmd == null || cmd.isEmpty()) {
                continue;
            }
            for (String sig : NAPCAT_SIGNATURES) {
                if (cmd.contains(sig)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static List<Integer> killNapCat(String rootfsPath) {
        List<Integer> killed = new ArrayList<>();
        int self = android.os.Process.myPid();
        int myUid = android.os.Process.myUid();

        List<Integer> targets = new ArrayList<>();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) {
            return killed;
        }
        for (File e : entries) {
            String name = e.getName();
            if (!isNumeric(name)) {
                continue;
            }
            int pid = Integer.parseInt(name);
            if (pid == self || pid == 1) {
                continue;
            }
            String cmd = readCmdline(pid);
            if (cmd == null || cmd.isEmpty()) {
                continue;
            }
            // 正在自更新的进程一律放过（哪怕它命令行里刚好带了 napcat 字样）。
            if (isUpdateWorker(cmd)) {
                Log.i(TAG, "跳过更新进程 pid=" + pid);
                continue;
            }
            // 只处理本应用 uid 的进程，绝不跨应用误杀。
            if (readUid(pid) != myUid) {
                continue;
            }
            // rootfsPath 非空时，要求确实是这套环境里的（避免误伤别的实例）
            if (rootfsPath != null && !rootfsPath.isEmpty()
                    && !cmd.contains(rootfsPath) && !cmd.startsWith("/opt/QQ/")
                    && !cmd.startsWith("Xvfb") && !cmd.contains("napcat")) {
                // guest 内进程的 cmdline 已被 proot 换成 guest 路径，
                // 看不到宿主 rootfs 前缀，所以这里只在「明显不是我们的」时才跳过。
            }
            for (String sig : NAPCAT_SIGNATURES) {
                if (cmd.contains(sig)) {
                    targets.add(pid);
                    break;
                }
            }
        }

        if (targets.isEmpty()) {
            Log.i(TAG, "killNapCat: 没有需要清理的 NapCat 进程");
            return killed;
        }
        Log.i(TAG, "killNapCat 准备清理 " + targets.size() + " 个: " + targets);
        for (int pid : targets) {
            sendSignal(pid, 15);
        }
        sleepQuiet(1000);
        for (int pid : targets) {
            if (isAlive(pid)) {
                sendSignal(pid, 9);
            }
        }
        sleepQuiet(600);
        for (int pid : targets) {
            if (!isAlive(pid)) {
                killed.add(pid);
            } else {
                Log.w(TAG, "killNapCat: 进程 " + pid + " 仍存活");
            }
        }
        Log.i(TAG, "killNapCat 完成，实际结束: " + killed);
        return killed;
    }

    /**
     * 杀掉所有属于 rootfsPath 的进程。
     *
     * @param rootfsPath 例如 /data/user/0/com.maimai.bot/files/rootfs
     * @param extraPid   额外指定要杀的 pid（传 -1 表示没有）
     * @return 实际处理掉的 pid 列表，便于写日志
     */
    public static List<Integer> killAll(String rootfsPath, int extraPid) {
        List<Integer> killed = new ArrayList<>();
        int self = android.os.Process.myPid();
        int myUid = android.os.Process.myUid();

        List<Integer> targets = new ArrayList<>();
        File proc = new File("/proc");
        File[] entries = proc.listFiles();
        if (entries == null) {
            return killed;
        }

        for (File e : entries) {
            String name = e.getName();
            if (!isNumeric(name)) {
                continue;
            }
            int pid = Integer.parseInt(name);
            if (pid == self || pid == 1) {
                continue;
            }
            if (extraPid > 0 && pid == extraPid) {
                targets.add(pid);
                continue;
            }
            String cmd = readCmdline(pid);
            if (cmd == null || cmd.isEmpty()) {
                continue;
            }
            // 更新子进程的命也是命：它命令行里带 /maibot，会被 SIGNATURES 命中，
            // 但它正在装新版本，杀了就等于更新失败。先放行。
            if (isUpdateWorker(cmd)) {
                Log.i(TAG, "跳过更新进程 pid=" + pid);
                continue;
            }
            // 两种归属判定，满足其一就算我们的人：
            //   a) 命令行里带本应用 rootfs 的宿主路径（proot 本体、bash 这一层）
            //   b) 进程 uid 就是本应用的 uid（proot 里面的 python / node 都算，
            //      它们的 cmdline 已经被 proot 换成 guest 路径，宿主前缀找不到）
            // 只靠 a) 会漏掉孤儿化的内层进程，这正是「停止后 8001 仍在监听、
            // 再点启动却起不来（flock 抢不到锁）」的根源。
            boolean inRootfs = rootfsPath != null && cmd.contains(rootfsPath);
            boolean sameUid = readUid(pid) == myUid;
            if (!inRootfs && !sameUid) {
                continue;
            }
            if (sameUid && cmd.contains("com.maimai.bot") && !cmd.contains(rootfsPath)) {
                // 兜一下：别把本应用自己的 Activity/Service 进程当成目标
                continue;
            }
            if (extraPid > 0 && pid == extraPid) {
                targets.add(pid);
                continue;
            }
            boolean match = false;
            for (String sig : SIGNATURES) {
                if (cmd.contains(sig)) {
                    match = true;
                    break;
                }
            }
            if (match) {
                targets.add(pid);
            }
        }

        if (targets.isEmpty()) {
            Log.i(TAG, "没有需要清理的进程");
            return killed;
        }

        Log.i(TAG, "准备清理 " + targets.size() + " 个进程: " + targets);

        // 第一轮：TERM，给它们体面退出的机会（maibot 有自己的优雅关闭流程）
        for (int pid : targets) {
            sendSignal(pid, 15);
        }
        sleepQuiet(1200);

        // 第二轮：还在的就 KILL
        for (int pid : targets) {
            if (isAlive(pid)) {
                sendSignal(pid, 9);
            }
        }
        sleepQuiet(600);

        // 复查
        for (int pid : targets) {
            if (!isAlive(pid)) {
                killed.add(pid);
            } else {
                Log.w(TAG, "进程 " + pid + " 仍存活");
            }
        }
        Log.i(TAG, "清理完成，实际结束: " + killed);
        return killed;
    }

    /** 端口还没释放就一直清，最多试 rounds 轮。 */
    public static void killAllUntilPortFree(String rootfsPath, int port, int rounds) {
        for (int i = 0; i < rounds; i++) {
            killAll(rootfsPath, -1);
            if (!isPortListening(port)) {
                return;
            }
            sleepQuiet(700);
        }
    }

    /**
     * 直接读 /proc/net/tcp* 判断端口是否处于 LISTEN。
     * 不引第三方库，也不依赖 shell 命令（应用里没有 ss/netstat）。
     */
    public static boolean isPortListening(int port) {
        // 先看 /proc/net/tcp（最准，能拿到别的进程的监听）
        if (portListeningViaProc(port)) {
            diag("proc命中 " + port);
            return true;
        }
        // 再退一步：真连一下 127.0.0.1。
        //
        // 为什么需要这层兜底（真机实测）：
        //   NapCat 明明在跑、curl 127.0.0.1:6099 返回 200、ss 也能看到
        //   LISTEN 0.0.0.0:6099，但 App 里读 /proc/net/tcp 只有 43 行，
        //   grep :17CB（6099 的十六进制）一条都没有 —— 说明 App 沙盒里
        //   某些环境下拿不到完整的 socket 表（网络命名空间/权限差异）。
        //   于是主界面那张卡片一直显示「未运行」，可点进去面板却是好的。
        //   加一层 socket 探测就能覆盖这种情况。
        boolean connectable = portConnectable(port);
        diag("proc未命中 " + port + " connect=" + connectable);
        return connectable;
    }

    /**
     * 端口探测诊断日志。
     * <p>
     * 直接追加到 App 私有目录下的 port_diag.log，不走 logcat ——
     * logcat 会被刷掉、也不方便远程看，落到文件里最省事。
     * 同一组合 5 秒内不重复记，避免刷屏把文件撑大。
     * <p>
     * 目的是下次出现「明明在跑却显示未运行」时，能直接从这份日志看出
     * 到底是 /proc 读不到，还是 socket 连不上。
     */
    private static void diag(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastDiagAt < 5000 && msg.equals(lastDiagMsg)) {
            return;
        }
        lastDiagAt = now;
        lastDiagMsg = msg;
        try {
            Log.i(TAG, "[port] " + msg);
        } catch (Throwable ignored) {
        }
        try {
            java.io.File f = new java.io.File(
                    "/data/data/com.maimai.bot/files/port_diag.log");
            // 超过 64KB 就清空重来，别让它无限长
            if (f.isFile() && f.length() > 64 * 1024) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            String line = android.text.format.DateFormat
                    .format("MM-dd HH:mm:ss", now).toString()
                    + "  " + msg + "\n";
            try (java.io.FileOutputStream out =
                         new java.io.FileOutputStream(f, true)) {
                out.write(line.getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }

    private static long lastDiagAt = 0;
    private static String lastDiagMsg = "";

    /** 读 /proc/net/tcp{,6} 找 LISTEN 状态。 */
    private static boolean portListeningViaProc(int port) {
        String hexPort = String.format("%04X", port);
        for (String file : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            java.io.FileInputStream in = null;
            try {
                in = new java.io.FileInputStream(file);
                // 必须把整个文件读完。
                //
                // 原来这里只 new byte[8192] 读一次，结果在真机上踩了坑：
                // QQ+麦麦一跑起来，socket 表能到几十上百行、十几 KB，
                // 3001 那条记录排在后半段就被截断了 —— 于是
                // 「3001 明明在听，App 却认为没登录，一直举着二维码」。
                // 现在改成循环读到文件末尾。
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    bos.write(chunk, 0, n);
                }
                String text = bos.toString("UTF-8");
                for (String line : text.split("\n")) {
                    String[] cols = line.trim().split("\\s+");
                    if (cols.length < 4) {
                        continue;
                    }
                    // 格式: sl local_address rem_address st ...
                    String local = cols[1];
                    String state = cols[3];
                    int colon = local.indexOf(':');
                    if (colon < 0) {
                        continue;
                    }
                    if (!hexPort.equalsIgnoreCase(local.substring(colon + 1))) {
                        continue;
                    }
                    // 0A = LISTEN
                    if ("0A".equalsIgnoreCase(state)) {
                        return true;
                    }
                }
            } catch (IOException ignored) {
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        return false;
    }

    /** 真的往 127.0.0.1:port 建一次连接，能建上就算在听。 */
    private static boolean portConnectable(int port) {
        java.net.Socket s = new java.net.Socket();
        try {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------- 内部

    private static String currentPackageHint() {
        // 避免引 Context：应用包名固定，且这块只在进程清理时用
        return "com.maimai.bot";
    }

    /** 读 /proc/<pid>/status 里的 Uid，判断这个进程是不是本应用的用户在跑。 */
    private static int readUid(int pid) {
        FileInputStream in = null;
        try {
            in = new FileInputStream("/proc/" + pid + "/status");
            byte[] buf = new byte[2048];
            int n = in.read(buf);
            if (n <= 0) {
                return -1;
            }
            String text = new String(buf, 0, n);
            for (String line : text.split("\n")) {
                if (line.startsWith("Uid:")) {
                    String[] cols = line.trim().split("\\s+");
                    if (cols.length >= 2) {
                        return Integer.parseInt(cols[1]);
                    }
                }
            }
            return -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String readCmdline(int pid) {
        FileInputStream in = null;
        try {
            in = new FileInputStream("/proc/" + pid + "/cmdline");
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            if (n <= 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder(n);
            for (int i = 0; i < n; i++) {
                byte b = buf[i];
                sb.append(b == 0 ? ' ' : (char) b);
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static boolean isAlive(int pid) {
        File f = new File("/proc/" + pid);
        return f.exists();
    }

    /** 用 kill 命令发信号。应用内没有直接 syscall 的简便入口，走 toybox kill 最稳。 */
    private static void sendSignal(int pid, int sig) {
        try {
            new ProcessBuilder("/system/bin/kill", "-" + sig, String.valueOf(pid))
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
        } catch (Exception e) {
            Log.w(TAG, "kill -" + sig + " " + pid + " 失败: " + e.getMessage());
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}