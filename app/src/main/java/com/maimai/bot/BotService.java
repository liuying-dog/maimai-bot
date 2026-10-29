package com.maimai.bot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.maimai.bot.core.Env;
import com.maimai.bot.core.Installer;
import com.maimai.bot.core.ProcessKiller;
import com.maimai.bot.core.ProotRunner;
import com.maimai.bot.core.NapCat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 前台服务：跑 install / run 两条长任务，并保证锁屏后不被系统回收。
 * <p>
 * 三条保活手段：
 * <ol>
 *   <li>前台通知 + dataSync 类型，Android 12+ 必须声明 FOREGROUND_SERVICE_DATA_SYNC</li>
 *   <li>PARTIAL_WAKE_LOCK，防止 CPU 休眠把 python 冻住</li>
 *   <li>START_STICKY，被杀后系统重拉（用户主动停止时用 START_NOT_STICKY）</li>
 * </ol>
 */
public final class BotService extends Service {

    private static final String TAG = "MaiBotService";
    private static final String CHANNEL_ID = "maibot_run";
    private static final int NOTIFY_ID = 0x4D42;

    public static final String ACTION_START = "com.maimai.bot.START";
    public static final String ACTION_STOP = "com.maimai.bot.STOP";
    /**
     * 启动 NapCat（QQ 桥接）。
     * <p>
     * 为什么不让 MainActivity 直接 ProcessBuilder 起：Activity 里的普通线程
     * 一旦页面被销毁就可能被系统回收，进程变成孤儿；而且没有前台服务兜着，
     * 长跑的子进程更容易被杀。统一走 Service 就和 maibot 一样有保活保障。
     * <p>
     * 这一层现在统一用 NapCat —— 它靠 LD_PRELOAD 注入，不需要 ptrace，
     * 而 QQ 自己就跑在 proot 下（proot 已经是它的 tracer），Linux 单 tracer 模型
     * 下第二个 tracer 永远 attach 不上，真机实测恒定 COMPONENT_LOAD_FAILED。
     * NapCat 走 LD_PRELOAD，不需要 ptrace，所以在同一个沙盒里能正常工作。
     */
    public static final String ACTION_QQ_START = "com.maimai.bot.QQ_START";
    public static final String ACTION_QQ_STOP = "com.maimai.bot.QQ_STOP";

    private static final AtomicBoolean busy = new AtomicBoolean(false);
    private PowerManager.WakeLock wakeLock;
    private Process current;
    /** 启动流程线程。用来判断"状态说在启动，实际却没人干活"这种残留。 */
    private Thread bootThread;
    private Installer installer;
    private ProotRunner runner;

    // ------------------------------------------------------------- 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        installer = new Installer(this);
        runner = new ProotRunner(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        // QQ（NapCat）的启停走独立分支：它和 maibot 是两个互不相干的进程，
        // 共用同一套 proot 环境，但生命周期独立。
        if (ACTION_QQ_START.equals(action)) {
            startForeground(NOTIFY_ID, buildNotification("QQ", "正在启动 NapCat"));
            new Thread(() -> {
                java.util.List<com.maimai.bot.core.NapCatAccount> list =
                        com.maimai.bot.core.NapCatAccount.loadAll(this);
                for (com.maimai.bot.core.NapCatAccount acc : list) {
                    String err = NapCat.start(this, BotState.get(this).env(), acc);
                    if (err != null) {
                        Log.w(TAG, "NapCat.start(" + acc.id + ") 失败: " + err);
                    }
                }
            }, "qq-start-svc").start();
            return START_NOT_STICKY;
        }
        if (ACTION_QQ_STOP.equals(action)) {
            new Thread(() -> {
                for (com.maimai.bot.core.NapCatAccount acc
                        : com.maimai.bot.core.NapCatAccount.loadAll(this)) {
                    NapCat.stop(BotState.get(this).env(), acc);
                }
            }, "qq-stop-svc").start();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(action)) {
            stopEverything();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFY_ID, buildNotification("准备中", "正在检查运行环境"));
        acquireWakeLock();

        BotState st = BotState.get(this);
        Log.i(TAG, "onStartCommand: action=" + action + " status=" + st.status()
                + " busy=" + busy.get());
        if (st.status() == BotState.Status.RUNNING || st.status() == BotState.Status.STARTING) {
            // 状态活在内存里，进程被系统杀掉后不会自动回滚。
            // 只看状态就 return 的话，用户会看到"状态显示启动中、点了没反应"，
            // 而且永远起不来。所以要看真正干活的两样东西还在不在：
            // 启动线程（bootSequence 阶段）或 proot 进程（已拉起之后）。
            boolean bootAlive = bootThread != null && bootThread.isAlive();
            boolean procAlive = current != null && current.isAlive();
            Log.i(TAG, "onStartCommand: 状态=" + st.status()
                    + " 启动线程存活=" + bootAlive + " 进程存活=" + procAlive);
            if (bootAlive || procAlive) {
                return START_STICKY;
            }
            Log.i(TAG, "onStartCommand: 状态残留、实际已停，重置后重新启动");
            st.setStatus(BotState.Status.STOPPED, "上次启动未完成，重新尝试");
            busy.set(false);
        }

        if (!busy.compareAndSet(false, true)) {
            Log.i(TAG, "onStartCommand: busy 被占用，跳过本轮启动");
            return START_STICKY;
        }

        Log.i(TAG, "onStartCommand: 进入 bootSequence");
        bootThread = new Thread(this::bootSequence, "maibot-boot");
        bootThread.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------- 主流程

    private void bootSequence() {
        BotState st = BotState.get(this);
        // 每次启动都换一份新日志。
        // 之前引导脚本的输出只进内存 StringBuilder，进程一被系统杀掉 / 用户一
        // 重启就全没了，界面上只剩一句「出错了，去运行日志看详情」——点进去是空的。
        // 现在同时落盘到 files/runtime.log，事后还能捞出来看。
        try {
            //noinspection ResultOfMethodCallIgnored
            st.env().runtimeLog().delete();
        } catch (Exception ignored) {
        }
        try {
            // 1) 先把 rootfs / proot 铺好（已装则秒过）
            boolean rootfsReady = installer.isInstalled();
            Log.i(TAG, "bootSequence: rootfs 已安装=" + rootfsReady
                    + " rootfs=" + st.env().rootfs().getAbsolutePath()
                    + " proot=" + st.env().proot().getAbsolutePath());
            if (!rootfsReady) {
                st.setStatus(BotState.Status.INSTALLING, "首次安装运行环境");
                updateNotification("安装中", "正在解压 Ubuntu 环境");
                installer.install((stage, pct) -> {
                    st.setStatus(BotState.Status.INSTALLING, stage);
                    st.setProgress(pct);
                    updateNotification("安装中 " + pct + "%", stage);
                });
                Log.i(TAG, "bootSequence: rootfs 安装完成");
            }

            // 2) 检查 MaiBot 是否已装，没装就跑引导
            //    顺手确认 NapCat 的环境在不在。QQ 桥接不参与 maibot 启动，
            //    缺了也不该让麦麦起不来，所以只记日志不当失败。
            installer.ensureBundledNapCat();
            boolean mbReady = hasMaibot();
            Log.i(TAG, "bootSequence: maibot 已安装=" + mbReady
                    + " instance=" + st.env().instance().getAbsolutePath());
            if (!mbReady) {
                st.setStatus(BotState.Status.INSTALLING, "安装 MaiBot 与依赖");
                st.appendLog("开始安装 MaiBot（首次约需 3-10 分钟）");
                updateNotification("安装 MaiBot", "正在拉起 uv 与依赖");
                Log.i(TAG, "bootSequence: 开始跑 bootstrap.sh");
                int code = runScriptBlocking("bootstrap.sh", "引导安装");
                Log.i(TAG, "bootSequence: bootstrap.sh 退出码=" + code);
                if (code != 0) {
                    st.setStatus(BotState.Status.ERROR, "MaiBot 安装失败，退出码 " + code);
                    updateNotification("安装失败", "查看运行日志了解原因");
                    busy.set(false);
                    return;
                }
            }

            // 3) 拉起 maibot run
            //    启动前先扫一遍残骸：
            //    如果上一次「停止」没把 proot 内层的 python 收干净，它会孤儿化
            //    （ppid=1）继续监听 8001、继续握着 .maibot-instance-locks 的 flock。
            //    新进程一进 register_instance 就 BlockingIOError →
            //    SelfUpdateError: 这个实例正在运行，几秒内退出，
            //    界面上就是「点了启动没反应」。所以起之前先清场。
            String rootPath = st.env().rootfs().getAbsolutePath();
            java.util.List<Integer> leftover = ProcessKiller.killAll(rootPath, -1);
            if (!leftover.isEmpty()) {
                st.appendLog("[启动] 清理上次残留进程: " + leftover);
                Log.i(TAG, "bootSequence: 清理残留进程 " + leftover);
            }
            ProcessKiller.killAllUntilPortFree(rootPath, 8001, 3);
            st.setStatus(BotState.Status.STARTING, "启动 MaiBot");
            updateNotification("启动中", "正在唤醒麦麦");
            Log.i(TAG, "bootSequence: 准备调用 startBot");
            startBot(st);

        } catch (Exception e) {
            Log.e(TAG, "bootSequence 失败", e);
            String msg = describe(e);
            st.setStatus(BotState.Status.ERROR, "启动失败: " + msg);
            st.appendLog("[错误] " + msg);
            st.appendLog(Log.getStackTraceString(e));
            appendRunLog("[错误] " + msg);
            appendRunLog(Log.getStackTraceString(e));
            updateNotification("启动失败", msg);
            busy.set(false);
        }
    }

    /**
     * 把一行运行日志同时落到 files/runtime.log。
     * <p>
     * 为什么不只用内存里的 StringBuilder：那个东西随进程生灭，
     * 系统一杀进程、用户一重启，界面上的「运行日志」就空了，
     * 剩下的只有卡片上那句干巴巴的「出错了」。
     * 落盘之后，出问题可以直接把文件捞出来看，也能让用户自己贴给我。
     */
    private void appendRunLog(String line) {
        try {
            java.io.File f = BotState.get(this).env().runtimeLog();
            String ts = new java.text.SimpleDateFormat("MM-dd HH:mm:ss",
                    java.util.Locale.US).format(new java.util.Date());
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f, true)) {
                out.write((ts + " " + line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // 日志写不进去不能影响主流程
        }
    }

    /** 只写一次的「原神启动」收尾标记。 */
    private boolean originLineDone = false;

    /**
     * 把「原神启动」补在麦麦启动日志的最后一行。
     * <p>
     * 为什么不写在「读到唤醒关键词」的那一刻：
     * maibot 唤醒之后还会继续打印好几行，真的实测顺序是
     * <pre>
     *   21:18:39 原神启动                      ← 我原来写在这
     *   21:18:39 🌐 访问地址: http://127.0.0.1:8001   ← 它还在后面
     *   21:18:39 🔗 WebUI 已绑定到多个地址
     * </pre>
     * 于是「原神启动」被夹在中间，用户根本看不到最后一行是它。
     * <p>
     * 现在改成：检测到唤醒后再等 6 秒，等 maibot 把启动横幅全打完，
     * 再补这一行，这样它才是真正的「最后一行」。
     * 用 originLineDone 保证一个启动周期只写一次。
     */
    private void scheduleOriginLine() {
        if (originLineDone) {
            return;
        }
        originLineDone = true;
        new Thread(() -> {
            try {
                Thread.sleep(6000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            BotState st = BotState.get(BotService.this);
            // 只有确实还在运行时才补，避免停掉之后又冒出一行
            if (st.status() != BotState.Status.RUNNING) {
                return;
            }
            st.appendLog("原神启动");
            appendRunLog("原神启动");
        }, "origin-line").start();
    }

    /** 把异常压成一行可读的话，避免界面上只看到 java.io.IOException: null。 */
    private static String describe(Throwable e) {
        String m = e.getMessage();
        if (m != null && !m.trim().isEmpty()) {
            return e.getClass().getSimpleName() + ": " + m;
        }
        Throwable c = e.getCause();
        if (c != null && c != e) {
            String cm = c.getMessage();
            if (cm != null && !cm.trim().isEmpty()) {
                return e.getClass().getSimpleName() + " ← " + c.getClass().getSimpleName() + ": " + cm;
            }
            return e.getClass().getSimpleName() + " ← " + c.getClass().getSimpleName();
        }
        return e.getClass().getSimpleName();
    }

    /**
     * 判断 maibot 是否已经装好可以启动。
     * <p>
     * 不只看 .installed 标记：那个标记只在引导脚本完整跑完时才会写，
     * 而用户可能用一个已经装好的实例（比如手动装过 / 升级保留数据），
     * 这时候明明能启动，却因为没标记又重跑一遍安装，白等十分钟。
     * 所以只要配置文件在，就认为可用。
     */
    private boolean hasMaibot() {
        Env env = BotState.get(this).env();
        return new File(env.instance(), ".installed").isFile()
                || env.botConfig().isFile();
    }

    /**
     * 统一构造 proot 启动参数，供 bootstrap 和 maibot run 共用。
     * <p>
     * 里面每一个 -b 都是踩出来的：
     * <ul>
     *   <li>/dev /proc /sys：不挂的话不少程序会莫名挂掉</li>
     *   <li>tmp:/tmp/hosttmp：给 guest 一个可写的临时目录</li>
     *   <li>instance:/root/maibot：<b>这条不能省</b>。省掉之后
     *       proot 处理 chdir("/root/maibot") 会返回 ENOSYS
     *       （Function not implemented），脚本第一行 cd 就失败，
     *       报「实例目录不存在」，可目录明明就在那里</li>
     *   <li>/system/etc/hosts：让 guest 能解析域名</li>
     * </ul>
     */
    private ProcessBuilder buildProotCommand(Env env, String... bashArgs) {
        java.util.List<String> cmd = new java.util.ArrayList<>();

        // ── 为什么默认走 App 进程内（不用 root）───────────────
        // App 进程带着 Android 强制的 seccomp filter
        // （/proc/<pid>/status: Seccomp=2, Seccomp_filters=1）。
        // 之前用 Operit 那个 proot，在它下面跑 maibot 会死在
        // 「正在唤醒 麦麦......」，进程被 SIGKILL，faulthandler 都来不及打印。
        //
        // 换成 AstrBot 配套的 proot + libloader.so 之后，多了一条路：
        // AstrBot 那套 proot 自带 seccomp 加速（ptrace + filter），
        // 并且 libloader.so 是独立可执行文件（e_type=EXEC），
        // 通过 PROOT_LOADER 指定给它，可以在 app 域下完成路径引导。
        // 这样就不需要 root 了。
        //
        // 只有当 App 进程内确实起不来（preferRoot 被置为 true）时，
        // 才回退到 su。preferRoot 由 BotService 在检测到连续失败后设置。
        if (!preferRoot && !suForced) {
            cmd.add(env.proot().getAbsolutePath());
            cmd.add("--link2symlink");
            cmd.add("-0");
            cmd.add("-r");
            cmd.add(env.rootfs().getAbsolutePath());
            cmd.add("-b");
            cmd.add("/dev");
            cmd.add("-b");
            cmd.add("/proc");
            cmd.add("-b");
            cmd.add("/sys");
            cmd.add("-b");
            cmd.add(env.tmp().getAbsolutePath() + ":/tmp/hosttmp");
            cmd.add("-b");
            cmd.add("/system/etc/hosts:/etc/hosts");
            cmd.add("--kill-on-exit");
            for (String a : bashArgs) {
                cmd.add(a);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(new File("/"));
            applyEnv(pb);
            return pb;
        }

        // ── 回退路径：用 su 拉起，绕开 App 的 seccomp ─────────
        // rootfs 和 maibot 仍然住在 App 私有目录，没有搬出去。
        cmd.add("su");
        cmd.add("-c");
        StringBuilder sb = new StringBuilder();
        sb.append("cd / && ");
        sb.append("LD_LIBRARY_PATH=").append(env.bin().getAbsolutePath()).append(" ");
        sb.append("PROOT_TMP_DIR=").append(env.tmp().getAbsolutePath()).append(" ");
        sb.append("PROOT_NO_SECCOMP=1 ");
        File ldr = env.prootLoader();
        if (ldr.isFile()) {
            sb.append("PROOT_LOADER=").append(ldr.getAbsolutePath()).append(" ");
        }
        sb.append(env.proot().getAbsolutePath());
        sb.append(" --link2symlink -0");
        sb.append(" -r ").append(env.rootfs().getAbsolutePath());
        sb.append(" -b /dev -b /proc -b /sys");
        sb.append(" -b ").append(env.tmp().getAbsolutePath()).append(":/tmp/hosttmp");
        sb.append(" -b /system/etc/hosts:/etc/hosts");
        sb.append(" --kill-on-exit");
        for (String a : bashArgs) {
            sb.append(" ").append(a);
        }
        cmd.add(sb.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(new File("/"));
        Log.i(TAG, "buildProotCommand: 走 su 回退路径");
        return pb;
    }

    /**
     * 是否已经判定「App 进程内起不来」，要改用 su。
     * <p>
     * 默认 false —— 也就是优先不用 root。
     * 只有在 BootSequence 里检测到进程在 X 秒内无故死亡、且 su 可用时，
     * 才会置为 true 并重试一次。
     */
    private volatile boolean preferRoot = false;
    /** 用户显式要求用 root（设置里可以强制）。 */
    private volatile boolean suForced = false;

    /**
     * 设备上有没有可用的 root。
     * <p>
     * 缓存结果：每次启动都探一次太慢，而且这个值在运行期不会变。
     */
    private Boolean suOk;

    private boolean suAvailable() {
        if (suOk != null) {
            return suOk;
        }
        try {
            Process p = new ProcessBuilder("su", "-c", "id")
                    .redirectErrorStream(true).start();
            boolean done = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                suOk = false;
                return false;
            }
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            suOk = line != null && line.contains("uid=0");
        } catch (Exception e) {
            suOk = false;
        }
        Log.i(TAG, "suAvailable=" + suOk);
        return suOk;
    }

    private int runScriptBlocking(String assetName, String label) throws IOException {
        String script = readAsset("scripts/" + assetName);
        // 同时落地一份到私有目录，便于用户取出来自己改
        File f = new File(BotState.get(this).env().base(), assetName);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(script.getBytes(StandardCharsets.UTF_8));
        }
        BotState st = BotState.get(this);
        st.appendLog("[引导] 开始执行 " + assetName + "（" + label + "）");
        return runReal(script, st);
    }

    private int runReal(String script, BotState st) throws IOException {
        // 脚本通过 stdin 传进 bash，避免依赖宿主与 guest 的路径映射
        ProcessBuilder pb = buildProotCommand(st.env(), "/bin/bash", "-s");

        Process p = pb.start();
        current = p;

        try (java.io.OutputStream os = p.getOutputStream()) {
            os.write(script.getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException ignored) {
        }

        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                st.appendLog(line);
                appendRunLog(line);
                if (line.contains("[引导]")) {
                    updateNotification("安装 MaiBot", line.replace("[引导]", "").trim());
                }
            }
        } catch (IOException ignored) {
        }

        try {
            return p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } finally {
            current = null;
        }
    }

    private void startBot(BotState st) throws IOException {
        // 把 run.sh 直接通过 stdin 喂给 bash -s。
        //
        // 早先的做法是「先 printf 写到 /tmp/.maibot-run.sh，再 chmod +x，再 bash 跑」，
        // 结果在 proot 里挂了：chmod 那一步执行时 PATH 还是 Android 的
        // （只有 /system/bin，没有 /usr/bin），于是 chmod: command not found，
        // 整条链断在这儿，maibot 一次都没起来过。
        // 走 stdin 就完全不需要写文件、也不需要可执行位。
        String script = readAsset("scripts/run.sh");

        ProcessBuilder pb = buildProotCommand(st.env(), "/bin/bash", "-s");
        Log.i(TAG, "startBot: 启动，脚本长度=" + script.length());
        Process p = pb.start();
        current = p;
        Log.i(TAG, "startBot: 进程已创建 " + p);

        // 脚本走 stdin；写完就关，让 bash 知道没有更多输入
        try (java.io.OutputStream os = p.getOutputStream()) {
            os.write(script.getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException e) {
            Log.w(TAG, "startBot: 写脚本失败", e);
        }

        boolean[] sawReady = {false};
        // 每次启动都是新的一轮，「原神启动」要允许重新写一次
        originLineDone = false;

        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8), 8192)) {
                String line;
                int n = 0;
                while ((line = r.readLine()) != null) {
                    st.appendLog(line);
                    appendRunLog(line);
                    // 只把关键行和开头几十行打到 logcat，避免刷屏
                    if (n++ < 40 || line.contains("错误") || line.contains("失败")
                            || line.contains("Error") || line.contains("Traceback")
                            || line.contains("已成功唤醒")) {
                        Log.i(TAG, "[maibot] " + line);
                    }
                    if (!sawReady[0] && (line.contains("已成功唤醒")
                            || line.contains("WebUI 服务器启动中")
                            || line.contains("WebUI 登录 Token"))) {
                        sawReady[0] = true;
                        st.setStatus(BotState.Status.RUNNING, "运行中");
                        st.refreshWebuiToken();
                        updateNotification("运行中", "WebUI 端口 " + st.webuiPort());
                        scheduleOriginLine();
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
                current = null;
                BotState s = BotState.get(BotService.this);
                // 退出码 42 是麦麦约定的「我要重启」信号。
                //
                // 触发它的两条路径：
                //   1) WebUI 点了「立即更新」→ Runner 登记 pending → os._exit(42)
                //      → 下一次启动时 Runner 执行更新、装完再起来
                //   2) WebUI 里点「重启」
                //
                // 以前这里把所有退出码一视同仁地写成「已停止」，于是更新装完了、
                // 但界面停在停止状态，用户以为更新失败。现在遇到 42 就自动重新拉起。
                if (code == 42) {
                    s.appendLog("[更新] 麦麦请求重启（退出码 42），正在重新拉起…");
                    s.setStatus(BotState.Status.STARTING, "正在重启");
                    updateNotification("正在重启", "麦麦请求重启");
                    final boolean[] waited = {false};
                    new Thread(() -> {
                        // 给旧的 proot / python 一点时间把端口和实例锁放掉，
                        // 否则新进程会和残留进程抢 flock。
                        try {
                            Thread.sleep(2500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        ProcessKiller.killAllUntilPortFree(
                                BotState.get(BotService.this).env().rootfs().getAbsolutePath(),
                                BotState.get(BotService.this).webuiPort(), 3);
                        android.os.Handler h = new android.os.Handler(
                                android.os.Looper.getMainLooper());
                        h.post(() -> {
                            try {
                                startBot(BotState.get(BotService.this));
                            } catch (IOException ioe) {
                                BotState.get(BotService.this).appendLog(
                                        "[更新] 重启失败：" + ioe);
                                BotState.get(BotService.this).setStatus(
                                        BotState.Status.STOPPED, "重启失败");
                                updateNotification("重启失败", String.valueOf(ioe));
                            }
                        });
                    }, "maibot-restart-on-42").start();
                } else {
                    if (s.status() == BotState.Status.RUNNING
                            || s.status() == BotState.Status.STARTING) {
                        s.setStatus(BotState.Status.STOPPED, "进程已退出，码 " + code);
                    }
                    updateNotification("已停止", "MaiBot 进程结束（" + code + "）");
                }
                busy.set(false);
                stopForegroundCompat();
            }
        }, "maibot-log");
        reader.setDaemon(true);
        reader.start();

        // 给它 25 秒判定是否成功醒来
        new Thread(() -> {
            try {
                Thread.sleep(25_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            BotState s = BotState.get(BotService.this);
            if (s.status() == BotState.Status.STARTING && p.isAlive()) {
                s.setStatus(BotState.Status.RUNNING, "运行中");
                s.refreshWebuiToken();
                updateNotification("运行中", "WebUI 端口 " + s.webuiPort());
                scheduleOriginLine();
            }
        }, "maibot-ready-check").start();
    }

    /**
     * 停止麦麦。
     * <p>
     * 关键：所有会阻塞的动作（destroy、waitFor、扫进程、轮询端口）必须放到
     * 后台线程。之前这套是直接在 onStartCommand（主线程）里跑的，
     * 里面一句 p.waitFor(3, SECONDS) 就把 UI 线程按死了，
     * 用户看到的就是「点停止，卡几秒，然后突然恢复」。
     * 停止 QQ 桥接不卡就是因为那边本来就在独立线程里。
     */
    private void stopEverything() {
        BotState st = BotState.get(this);
        // 正在自更新时不许停。
        //
        // 原因：uv 装包是「先删后装」，中途被打断会留下一个半成品环境 ——
        // 轻则 maibot_dashboard/dist 里的 JS/CSS 变成 0 字节（WebUI 白屏），
        // 重则整个 site-packages 里几百个 .py 被截断（真机上撞过：64 个文件各少 1 字节）。
        // 更新本身是有超时兜底的（package_updater.py 里 UPDATE_TIMEOUT=1800），
        // 让它自己跑完，比中途拽停安全得多。
        if (com.maimai.bot.core.ProcessKiller.hasUpdateWorker()) {
            st.appendLog("[停止] 检测到正在更新，已取消本次停止。请等更新跑完再用。");
            st.setStatus(BotState.Status.RUNNING, "正在更新，暂不能停止");
            updateNotification("正在更新", "更新进行中，暂不能停止");
            return;
        }
        st.appendLog("[操作] 用户请求停止");

        // 先改状态，让界面立刻有反馈（按钮、状态文字马上变）
        busy.set(false);
        st.setStatus(BotState.Status.STOPPED, "正在停止…");

        // 拿下当前进程句柄，并立刻置空，避免并发再次拿到它
        final Process p = current;
        current = null;

        // 停掉前台通知：这一步很快，留在主线程没问题
        stopForegroundCompat();

        // 剩下的都是慢活，交给后台线程
        new Thread(() -> {
            BotState s = BotState.get(this);
            long t0 = System.currentTimeMillis();

            // 1) 先体面地结束外层 proot
            if (p != null) {
                try {
                    p.destroy();
                    if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    p.destroyForcibly();
                } catch (Exception ignored) {
                }
            }

            // 2) 再按进程特征兜底清理。maibot 是 proot -> bash -> python 套下来的，
            // 外层死了里面的 python 会变孤儿继续占 8001。
            String rootfs = s.env().rootfs().getAbsolutePath();
            int port = s.webuiPort();
            s.appendLog("[停止] 清理残留进程…");
            try {
                for (int i = 0; i < 3; i++) {
                    java.util.List<Integer> killed =
                            com.maimai.bot.core.ProcessKiller.killAll(rootfs, -1);
                    if (!com.maimai.bot.core.ProcessKiller.isPortListening(port)) {
                        if (!killed.isEmpty()) {
                            s.appendLog("[停止] 已结束进程: " + killed);
                        }
                        break;
                    }
                    s.appendLog("[停止] 端口 " + port + " 仍被占用，重试 " + (i + 1) + "/3");
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie2) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                boolean stillUp = com.maimai.bot.core.ProcessKiller.isPortListening(port);
                s.appendLog(stillUp
                        ? "[停止] 警告：端口 " + port + " 仍在监听"
                        : "[停止] 已完全退出，耗时 " + (System.currentTimeMillis() - t0) + " ms");
            } catch (Exception e) {
                s.appendLog("[停止] 清理异常: " + e);
            }

            // 3) 收尾：状态回到确切的“已停止”
            s.setStatus(BotState.Status.STOPPED, "已停止");
        }, "maibot-stop").start();
    }

    private void applyEnv(ProcessBuilder pb) {
        java.util.Map<String, String> e = pb.environment();
        // 必须先把继承来的环境整个清掉。
        //
        // Android 给 app 进程塞了一堆 ANDROID_DATA / ANDROID_ROOT /
        // ANDROID_STORAGE / EXTERNAL_STORAGE / BOOTCLASSPATH 之类的变量，
        // proot 启动时会拿它们做路径推断，推出来的 guest 路径对不上，
        // 结果就是 getcwd() 直接返回 ENOSYS，python 一启动就
        // OSError: [Errno 38] Function not implemented。
        // 同样的 proot 命令在 run-as/sh 里跑就没事，正是因为那边的环境干净。
        e.clear();
        e.put("PROOT_TMP_DIR", BotState.get(this).env().tmp().getAbsolutePath());
        e.put("PROOT_NO_SECCOMP", "1");
        // 指定外置 loader。
        // App 进程带着 Android 强制的 seccomp filter（Seccomp: 2 / filters: 1），
        // proot 用内嵌 loader 去 exec 时会被拦，guest 里 getcwd/chdir 全返回
        // ENOSYS，python 一启动就 OSError Errno 38。换成外部 loader 文件
        // 走另一条 exec 路径可以绕开这个限制。
        File loader = BotState.get(this).env().prootLoader();
        if (loader.isFile()) {
            e.put("PROOT_LOADER", loader.getAbsolutePath());
        }
        Log.i(TAG, "applyEnv: PROOT_LOADER=" + e.get("PROOT_LOADER"));
        e.put("LD_LIBRARY_PATH", BotState.get(this).env().bin().getAbsolutePath());
        e.put("HOME", "/root");
        e.put("PATH", "/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        e.put("TMPDIR", "/tmp/hosttmp");
    }

    private String readAsset(String name) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = getAssets().open(name);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ------------------------------------------------------------- 通知

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "麦麦运行状态", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("MaiBot 后台运行状态");
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stop = new Intent(this, BotService.class).setAction(ACTION_STOP);
        PendingIntent psi = PendingIntent.getService(
                this, 1, stop,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setContentTitle("麦麦 · " + title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        null, "停止", psi).build());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            b.setVisibility(Notification.VISIBILITY_PUBLIC);
        }
        return b.build();
    }

    private void updateNotification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFY_ID, buildNotification(title, text));
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(false);
        }
    }

    // ------------------------------------------------------------- 唤醒锁

    @SuppressWarnings("deprecation")
    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return;
        }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "maibot:run");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }
}