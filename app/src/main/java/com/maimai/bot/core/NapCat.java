package com.maimai.bot.core;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * NapCat + LinuxQQ 的多账号启动器。
 * <p>
 * 每个账号 = 一个独立实例，隔离四样东西（照 AstrBot 的 launcher 抄的）：
 * <ol>
 *   <li>工作目录：{@code /root/napcat_instances/<id>_napcat}，用 {@code NAPCAT_WORKDIR} 指定</li>
 *   <li>HOME + XDG_*：{@code <id>_home}，隔离 QQ 登录态，换号不会串</li>
 *   <li>Xvfb DISPLAY：每个实例一个虚拟屏，否则第二个 QQ 连不上</li>
 *   <li>WebUI / OneBot 端口与 token：6099/3001、6100/3002…</li>
 * </ol>
 * <p>
 * 每次启动都会把「8 个步骤」逐条写进 {@code files/napcat.log}（见 {@link BootLog}），
 * 包括每一步的耗时、失败原因、以及 QQ/Xvfb 的原始输出。
 * 这样点一下就能看到它干了什么、卡在哪 —— 之前失败时界面上一句话都没有。
 * <p>
 * 关键实现细节（都是踩出来的）：
 * <ul>
 *   <li>{@code cd /root} + {@code NAPCAT_BOOTMAIN=/root}：loadNapCat.js 用
 *       {@code path.join(CurrentPath, './napcat/napcat.mjs')}，cwd 不对就找不到入口</li>
 *   <li>{@code /dev/shmm} 必须有：Electron 建共享内存失败会 FATAL 退出</li>
 *   <li>{@code --disable-gpu}：Xvfb 没有 GLX</li>
 *   <li>seccomp 垫片：App 进程下 {@code link(2)} 返回 ENOSYS，Xvfb 建不出
 *       {@code /tmp/.X*-lock} → QQ 报 Missing X server → signal 11</li>
 *   <li>实例目录必须属主是 App 自己（u0_a810），否则写 config 会失败
 *       （之前手工用 root 建过 test2，导致「配置只写了一半就断」）</li>
 * </ul>
 */
public final class NapCat {
    private static final String TAG = "NapCat";

    /** 旧版单实例时期使用的端口，保留给迁移逻辑。 */
    public static final int WEBUI_PORT = 6099;
    public static final int ONEBOT_PORT = 3001;
    public static final String ONEBOT_TOKEN = "maibot2026";

    // ---------------------------------------------------------------- 路径

    /** NapCat 的代码目录（所有实例共用这一份）。 */
    public static File dir(Env env) {
        return new File(env.rootfs(), "root/napcat");
    }

    public static File qqBinary(Env env) {
        return new File(env.rootfs(), "opt/QQ/qq");
    }

    public static File launcher(Env env) {
        return new File(env.rootfs(), "root/libnapcat_launcher.so");
    }

    /** seccomp 垫片：补 getcwd / link / symlink 在 App 沙盒里的 ENOSYS。 */
    public static File shim(Env env) {
        return new File(env.rootfs(), "root/libseccompshim.so");
    }

    /** 所有实例的父目录。 */
    public static File instancesDir(Env env) {
        return new File(env.rootfs(), "root/napcat_instances");
    }

    public static boolean isInstalled(Env env) {
        return qqBinary(env).isFile()
                && new File(dir(env), "napcat.mjs").isFile()
                && launcher(env).isFile();
    }

    /**
     * NapCat 版本标记文件。
     * <p>
     * 为什么需要它：NapCat 上游从来不维护 package.json 里的 version 字段，
     * 4.18.41 的官方发行包里那个字段也还是 "0.0.1"（实测坐实过）。
     * 所以想从包里读版本号是条死路，只能自己记一份。
     * <p>
     * 写在 napcat 目录里而不是 App 私有目录，是为了「重装 NapCat 就一起清掉」——
     * 版本标记和实际解压出来的代码永远在同一个生命周期里，不会错位。
     */
    public static File versionFile(Env env) {
        return new File(dir(env), ".napcat_version");
    }

    /** 读出安装时记下的版本号；没记过就返回空串。 */
    public static String installedVersion(Env env) {
        File f = versionFile(env);
        if (!f.isFile()) {
            return "";
        }
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
            String line = r.readLine();
            if (line == null) {
                return "";
            }
            line = line.trim();
            // 只认 x.y.z，防止文件被别的写入弄脏
            return line.matches("\\d+\\.\\d+\\.\\d+") ? line : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 记下这次装的是哪一版。
     * <p>
     * 两个来源：内置包时是硬编码在 App 里的 {@link NapCatInstaller#BUNDLED_NAPCAT_VERSION}，
     * 在线安装时是从 GitHub release tag 抓到的版本号。
     */
    public static void writeInstalledVersion(Env env, String version) {
        if (version == null || !version.matches("\\d+\\.\\d+\\.\\d+")) {
            return;
        }
        File f = versionFile(env);
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        try (java.io.FileWriter w = new java.io.FileWriter(f, false)) {
            w.write(version);
            w.write("\n");
        } catch (Exception ignored) {
        }
    }

    /**
     * 读当前装着的 NapCat 版本，三级兜底：
     * <ol>
     *   <li>版本标记文件（最准，是我们自己写的）</li>
     *   <li>启动日志里的 "NapCat.Core Version: x.y.z"（老版本 APK 没标记文件，靠这个救）</li>
     *   <li>返回空串，界面显示"—"</li>
     * </ol>
     */
    public static String detectVersion(Env env) {
        String v = installedVersion(env);
        if (!v.isEmpty()) {
            return v;
        }
        // 兜底：翻启动日志。只在标记文件缺失时走这一步，
        // 文件可能几十 KB，但只在展开环境管理时读一次。
        File log = logFile(env);
        if (log.isFile()) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(log), "UTF-8"))) {
                String line;
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("NapCat\\.Core Version:\\s*(\\d+\\.\\d+\\.\\d+)").matcher("");
                while ((line = r.readLine()) != null) {
                    m.reset(line);
                    if (m.find()) {
                        v = m.group(1);
                        // 不 break：日志里可能有多条，最后一条才是本次启动的
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return v == null ? "" : v;
    }

    /** 启动日志文件（旧名字保留，日志页读的就是它）。 */
    public static File logFile(Env env) {
        return new File(env.base(), "napcat.log");
    }

    // ---------------------------------------------------------------- 启动

    /**
     * 启动某个账号的实例。
     * <p>
     * 同步执行到「进程已创建」为止（约 3~5 秒，其中包含起 Xvfb 的 3 秒等待）。
     * QQ 真正可用还要 20~40 秒，调用方接着用 {@link #waitForPort} 等端口。
     *
     * @return null 表示已拉起；否则是给用户看的失败原因（同时也写进日志）
     */
    public static synchronized String start(Context ctx, Env env, NapCatAccount acc) {
        final BootLog log = new BootLog(env.base());
        log.reset();
        log.info("账号：" + acc.label + "（" + acc.id + "）");
        log.info("端口：WebUI " + acc.webuiPort + " / OneBot " + acc.onebotPort
                + " / DISPLAY :" + acc.display);

        // 防止同一实例被并发拉起两次。
        //
        // synchronized 只保证「start() 内部不重入」，挡不住用户连点两下：
        // 第一下进 start() 之后很快就 return null（进程只是被创建，还没起来），
        // 第二下紧接着又进来一次 —— 结果就是两个 QQ 抢同一份 HOME，
        // 登录状态互相覆盖，二维码永远扫不上（实测就是这么卡住了一晚上）。
        synchronized (STARTING) {
            if (STARTING.contains(acc.id)) {
                log.warn("这个实例正在启动中，忽略重复请求");
                return "正在启动中，请稍候…";
            }
            STARTING.add(acc.id);
        }
        try {
            return startInner(ctx, env, acc, log);
        } finally {
            synchronized (STARTING) {
                STARTING.remove(acc.id);
            }
        }
    }

    /** 正在启动中的实例 id，用来挡住连点造成的重复拉起。 */
    private static final java.util.Set<String> STARTING =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static String startInner(Context ctx, Env env, NapCatAccount acc, BootLog log) {

        // ── 1/8 检查环境
        long t = log.step(1, "检查 NapCat 环境");
        if (!isInstalled(env)) {
            String miss = "缺少文件："
                    + (qqBinary(env).isFile() ? "" : " /opt/QQ/qq")
                    + (new File(dir(env), "napcat.mjs").isFile() ? "" : " /root/napcat/napcat.mjs")
                    + (launcher(env).isFile() ? "" : " /root/libnapcat_launcher.so");
            log.error("环境不完整（" + miss + "）");
            log.result(false, "环境不完整");
            return "NapCat 环境不完整（" + miss + "），请先在「环境管理」里安装";
        }
        log.done(1, "检查 NapCat 环境", t);

        // ── 2/8 释放 seccomp 垫片
        t = log.step(2, "准备 seccomp 垫片");
        try {
            ensureShim(ctx, env, log);
            log.done(2, "准备 seccomp 垫片", t);
        } catch (Exception e) {
            log.error("垫片准备失败：" + e);
            log.result(false, "垫片失败");
            return "seccomp 垫片准备失败：" + e;
        }

        // ── 3/8 准备实例目录（含属主修正）
        t = log.step(3, "准备实例目录");
        try {
            prepareDirs(env, acc, log);
            log.done(3, "准备实例目录", t);
        } catch (Exception e) {
            log.error("实例目录准备失败：" + e);
            log.result(false, "目录失败");
            return "实例目录准备失败：" + e;
        }

        // ── 4/8 写配置
        t = log.step(4, "写入 webui.json / onebot11.json");
        try {
            acc.writeConfig(env, log);
            log.done(4, "写入 webui.json / onebot11.json", t);
        } catch (Exception e) {
            log.error("写配置失败：" + e);
            log.result(false, "写配置失败");
            return "写配置失败：" + e;
        }

        // ── 5/8 清理旧的 X 锁
        t = log.step(5, "清理旧的 X 锁与残留进程");
        try {
            // 必须清两个位置，因为 Xvfb 找的是 guest 的 /tmp：
            //   A) <rootfs>/tmp            ← guest 的 /tmp 就落在这里（真正的位置）
            //   B) <files>/tmp             ← 万一某次绑定变了，也清一下兜底
            // 之前只清了 B，于是 A 里的 .X30-lock 一直在，Xvfb 每次启动都报
            //   Fatal server error: Server is already active for display 30
            // 然后自己退出 → QQ 报 Missing X server → signal 11，
            // 界面上就是「点了启动没反应」。
            // socket 也要清：只删 lock 不清 socket 的话同样起不来。
            removeXArtifacts(new File(env.rootfs(), "tmp"), acc.display, log);
            removeXArtifacts(env.tmp(), acc.display, log);
            // 顺手把 guest 的 /tmp 及其子目录权限纠正回来：
            // 实测出现过 <rootfs>/tmp/hosttmp 被留成 d--------- 的情况，
            // 那样 Xvfb 在里面写不了锁，直接 fatal，QQ 就 signal 11。
            ensureGuestTmpWritable(env, log);
            // 把上一次留下的二维码也删掉。
            //
            // 为什么必须删：NapCat 每次启动都会重写这张图，但如果上一次的
            // 文件还在，扫码页在「新图还没落盘」的那几百毫秒里会先看到旧图，
            // 直接显示出来 —— 用户扫的是上一轮已经失效的码，报
            // 「二维码已失效」，看起来就像「刷新不出来」。
            // 删掉之后，「有文件 = 本次新生成」，判断就干净了。
            File oldQr = acc.qrCode(env);
            if (oldQr.exists()) {
                //noinspection ResultOfMethodCallIgnored
                oldQr.delete();
                log.info("已清掉上一轮二维码：" + oldQr.getName());
            }
            if (acc.isRunning()) {
                log.warn("端口 " + acc.webuiPort + " 已经在监听，先收掉旧进程");
                // 只清 NapCat 这一支，不能用 killAll：
                // killAll 会按 uid + SIGNATURES 匹配，而 SIGNATURES 里有
                // src.runtime / /maibot，会把麦麦一起杀掉。
                ProcessKiller.killNapCat(env.rootfs().getAbsolutePath());
                Thread.sleep(1200);
            }
            // 无条件把「上一次的 QQ/NapCat/Xvfb」全清掉，不只是端口在听的时候。
            //
            // 这是这次「扫码登录不上」的真正原因（真机实测）：
            //   点两次启动（或重启时旧的没退干净），会有两个 QQ 同时活着，
            //   它们共用同一份 HOME 和 NAPCAT_WORKDIR：
            //     28853  HOME=.../qq1_..._home  DISPLAY=:30
            //     29300  HOME=.../qq1_..._home  DISPLAY=:31
            //   两个进程抢同一份 QQ 登录数据库，一个在写、一个在覆，
            //   于是扫码后 QQ 那边不停报
            //     [Login] Login Error, ErrType: 1  ErrCode: 3
            //   永远登不上；同时两个 WebUI 分别占了 6099 和 6100，
            //   App 拿着 6099 的 token 去 6100 的实例上请求，
            //   面板就回 Unauthorized。
            //
            // 以前只在「端口在听」时才清，但第二个实例可能抢到 6100，
            // 6099 那个正在退出的瞬间 isRunning() 恰好为 false，
            // 就被漏过去了。这里改成无条件清。
            int killed = ProcessKiller.killNapCat(env.rootfs().getAbsolutePath()).size();
            log.info("启动前清场，结束残留 NapCat 进程 " + killed + " 个（不动麦麦）");
            Thread.sleep(1500);
            log.done(5, "清理旧的 X 锁与残留进程", t);
        } catch (Exception e) {
            log.warn("清理步骤出错（继续）：" + e);
        }

        // ── 6/8 组装 proot 命令
        t = log.step(6, "组装 proot 启动命令");
        List<String> cmd;
        try {
            cmd = buildCommand(env, acc, log);
            log.info("Xvfb :" + acc.display + " 1x1x8（照 AstrBot 的小屏，初始化更快）");
            log.info("NAPCAT_WORKDIR=" + guestWork(acc));
            log.info("HOME=" + guestHome(acc));
            log.done(6, "组装 proot 启动命令", t);
        } catch (Exception e) {
            log.error("组装命令失败：" + e);
            log.result(false, "组装命令失败");
            return "组装命令失败：" + e;
        }

        // ── 7/8 拉起进程
        t = log.step(7, "拉起 proot（Xvfb + LinuxQQ + NapCat 注入）");
        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            // 工作目录交给 proot 的 -w /root 处理，宿主侧设成 / 就好。
            //
            // 为什么不设成 <rootfs>/root：proot 启动时会先把「自己的 cwd」
            // 映射到 guest 里，宿主侧指向 rootfs 内部会让它多算一步路径推断，
            // 在 App 的 seccomp 环境下这一步会打出
            //   proot warning: can't chdir(".../rootfs/root/./.") ...
            //   proot info: default working directory is now "/"
            // 然后 guest 的工作目录就悄悄变成 / 了 —— 与预期不符。
            // 现在统一由 -w /root 决定，宿主侧保持 / 最干净。
            pb.directory(new File("/"));
            java.util.Map<String, String> e = pb.environment();
            e.clear();
            e.put("PROOT_TMP_DIR", env.tmp().getAbsolutePath());
            e.put("PROOT_NO_SECCOMP", "1");
            File loader = env.prootLoader();
            if (loader.isFile()) {
                e.put("PROOT_LOADER", loader.getAbsolutePath());
            }
            e.put("LD_LIBRARY_PATH", env.bin().getAbsolutePath());
            e.put("HOME", "/root");
            e.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
            e.put("TMPDIR", "/tmp/hosttmp");
            p = pb.start();
            log.info("进程已创建：" + p);
            log.done(7, "拉起 proot（Xvfb + LinuxQQ + NapCat 注入）", t);
        } catch (Exception ex) {
            log.error("拉起失败：" + ex);
            log.result(false, "拉起失败");
            return "拉起失败：" + ex;
        }

        // 子进程输出实时落到 napcat.log，用户能看到 QQ / NapCat 每一行
        final BootLog flog = log;
        final File runLog = acc.runLog(env);
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                 java.io.FileOutputStream raw = new java.io.FileOutputStream(runLog, false)) {
                String line;
                while ((line = r.readLine()) != null) {
                    // 原始档：一行不改，用于排查
                    raw.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    raw.flush();
                    // 展示档：只挑有意义的行，避免 200 行 dbus 噪音把界面刷满
                    String text = stripAnsi(line);
                    if (isInteresting(text)) {
                        flog.child(sourceOf(text), text);
                    }
                }
                flog.info("子进程输出结束（进程已退出）");
            } catch (Exception ignored) {
            }
        }, "napcat-log-" + acc.id).start();

        // ── 8/8 等 WebUI 端口（在独立线程里等，不阻塞调用方）
        new Thread(() -> {
            long t8 = flog.step(8, "等待 NapCat WebUI 端口 " + acc.webuiPort);
            boolean up = waitForPort(acc.webuiPort, 60_000);
            if (up) {
                flog.done(8, "等待 NapCat WebUI 端口 " + acc.webuiPort, t8);
                String token = waitForToken(env, acc, 8_000);
                flog.info("WebUI token：" + token);
                flog.result(true, "面板 http://127.0.0.1:" + acc.webuiPort + "/webui?token=" + token);
            } else {
                flog.error("等待 60 秒，端口 " + acc.webuiPort + " 仍未监听");
                flog.info("排查建议：看上面有没有 [错误] 行，以及 [QQ] 前缀里有没有 Missing X server");
                flog.result(false, "端口未监听");
            }
        }, "napcat-wait-" + acc.id).start();

        return null;
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 把本实例的启动流程写成 guest 里的一个真实脚本（照 AstrBot 的 launcher_qqN.sh），
     * 返回 guest 内的脚本路径，交给 proot 的 /bin/bash 执行。
     * <p>
     * 为什么不继续用 {@code &&} 长链：长链里任何一步返回非 0 都会断在半路，
     * 而且断了之后既没有行号也没有原始报错，只能看到「跳到了下一步」。
     * 换成脚本以后每一步都能 if 判断，失败时把 xvfb.log 原样打出来。
     */
    private static String writeLauncher(Env env, NapCatAccount acc, BootLog log) {
        String gw = guestWork(acc);
        String gh = guestHome(acc);
        String d = String.valueOf(acc.display);

        StringBuilder b = new StringBuilder();
        b.append("#!/bin/bash\n");
        b.append("# 由麦麦Bot 自动生成，每次启动都会重写。\n");
        b.append("set -u\n");
        b.append("D=").append(d).append("\n");
        b.append("GW=").append(gw).append("\n");
        b.append("GH=").append(gh).append("\n");
        b.append("say() { echo \"[启动] $*\"; }\n");
        b.append("\n");
        // ── 1) Xvfb
        b.append("say \"1) 启动 Xvfb :$D\"\n");
        b.append("export TMPDIR=/tmp/hosttmp\n");
        // 清锁只是「尽力而为」，删不掉也不再致命 —— 因为下面给 Xvfb 加了 -nolock。
        //
        // 为什么要 -nolock（真机实测结论）：
        //   proot 的 --link2symlink 会把 /tmp/.X<n>-lock 做成符号链接
        //   （.X30-lock -> .../.l2s..tX30-lock0001）。用 rm 删链接时，
        //   Xvfb 再 stat 一次可能仍看到「锁在」，于是直接
        //     Fatal server error: Server is already active for display 30
        //   然后自杀 → QQ 报 Missing X server → signal 11。
        //   而 Xvfb 自带 -nolock 开关（Xvfb -help: "disable the locking mechanism"），
        //   实测「故意留一个假锁」也能正常起来，彻底绕开这套锁机制。
        //   我们本来就是单实例固定 DISPLAY，加上 stop() 会杀干净进程，
        //   不需要 X 的锁来做互斥。
        b.append("rm -f /tmp/.X${D}-lock /tmp/hosttmp/.X${D}-lock /tmp/.X11-unix/X${D} 2>/dev/null || true\n");
        b.append("mkdir -p /tmp/.X11-unix /tmp/hosttmp 2>/dev/null || true\n");
        b.append("chmod 1777 /tmp/.X11-unix /tmp/hosttmp 2>/dev/null || true\n");
        // 起 Xvfb，带「换显示号重试」：万一 :D 被占或者 socket 建不出来，
        // 依次试 D+1、D+2、D+3。换号后 DISPLAY 也随之更新（下面导出的就是新号）。
        // 这是第二道保险 —— 第一道是 -nolock，它已经把锁竞争这件事绕过去了。
        b.append("tryXvfb() {\n");
        b.append("  n=$1\n");
        b.append("  rm -f /tmp/.X${n}-lock /tmp/hosttmp/.X${n}-lock /tmp/.X11-unix/X${n} 2>/dev/null || true\n");
        b.append("  LD_PRELOAD=/root/libseccompshim.so Xvfb :$n -nolock -screen 0 1x1x8 +extension GLX +render > $GW/xvfb.log 2>&1 &\n");
        b.append("  echo $! > $GW/xvfb.pid\n");
        b.append("  for i in $(seq 1 100); do\n");
        b.append("    [ -S /tmp/.X11-unix/X${n} ] && return 0\n");
        b.append("    kill -0 $(cat $GW/xvfb.pid 2>/dev/null) 2>/dev/null || return 1\n");
        b.append("    sleep 0.2\n");
        b.append("  done\n");
        b.append("  return 1\n");
        b.append("}\n");
        b.append("OK=1\n");
        b.append("for off in 0 1 2 3; do\n");
        b.append("  n=$((D+off))\n");
        b.append("  if tryXvfb $n; then D=$n; OK=0; break; fi\n");
        b.append("  say \"1) :$n 没起来，换下一个显示号\"\n");
        // 必须连 socket 一起清：只 kill 进程的话，socket 文件还会留在
        // /tmp/.X11-unix/ 里，下一次的 [ -S ] 判断就会把「残留的 socket」
        // 当成「服务器已就绪」，于是 QQ 连上去又发现没人应答。
        // 这次真机上就出现了 :30 没起来但 socket 还在，脚本误判成功、
        // 一路堆到 :31，最后两个 Xvfb 同时挂着。
        b.append("  kill -9 $(cat $GW/xvfb.pid 2>/dev/null) 2>/dev/null || true\n");
        b.append("  rm -f /tmp/.X${n}-lock /tmp/hosttmp/.X${n}-lock /tmp/.X11-unix/X${n} 2>/dev/null || true\n");
        b.append("done\n");
        b.append("if [ $OK -ne 0 ]; then\n");
        b.append("  say \"1) Xvfb 起不来（试过 :$D 起连续 4 个显示号）\"\n");
        b.append("  cat $GW/xvfb.log 2>/dev/null\n");
        b.append("  exit 1\n");
        b.append("fi\n");
        b.append("echo $D > $GW/display.txt\n");
        b.append("say \"1) Xvfb :$D 已就绪\"\n");
        b.append("\n");
        // ── 2) 实例环境变量
        b.append("say \"2) 导出实例环境变量（HOME/XDG/NAPCAT_WORKDIR）\"\n");
        b.append("export DISPLAY=:$D\n");
        b.append("export NAPCAT_WORKDIR=$GW\n");
        b.append("export HOME=$GH\n");
        b.append("export XDG_CONFIG_HOME=$GH/.config\n");
        b.append("export XDG_CACHE_HOME=$GH/.cache\n");
        b.append("export XDG_DATA_HOME=$GH/.local/share\n");
        b.append("export PWD=/root\n");
        b.append("mkdir -p $XDG_CONFIG_HOME $XDG_CACHE_HOME $XDG_DATA_HOME 2>/dev/null || true\n");
        // 必须在起 QQ 之前就把 NapCat 的临时目录建好。
        //
        // NapCat 内部是 mkdirSync('<HOME>/.config/QQ/NapCat/temp')，没带 recursive，
        // 父目录不在就抛 ENOENT，Worker 直接退出码 1，然后重拉三次再崩。
        // 症状极具迷惑性：二维码正常出、扫码也进了「正在快速登录 36118...」，
        // 但 NapCat 立刻死掉，永远写不出 onebot11_<uin>.json，3001 也不监听。
        // 这里在 Java 侧建过一次，脚本里再建一次兜底（proot 路径映射下两边都要稳）。
        b.append("mkdir -p \"$HOME/.config/QQ/NapCat/temp\" \"$HOME/.config/QQ/NapCat/data\" 2>/dev/null || true\n");
        b.append("ls -d \"$HOME/.config/QQ/NapCat/temp\" >/dev/null 2>&1 && say '2) NapCat 临时目录就绪' || say '2) 警告：NapCat 临时目录建不出来'\n");
        b.append("\n");
        // ── 3) LinuxQQ
        b.append("say \"3) 启动 LinuxQQ（注入 NapCat）\"\n");
        b.append("export LD_PRELOAD=/root/libseccompshim.so:/root/libnapcat_launcher.so\n");
        b.append("trap '' SIGPIPE\n");
        b.append("exec /opt/QQ/qq --no-sandbox --disable-gpu\n");

        // 脚本落在 rootfs 内（宿主和 guest 是同一份文件），属主就是 App 自己。
        File script = new File(env.rootfs(), "root/launcher_" + acc.id + ".sh");
        try {
            java.nio.file.Files.write(script.toPath(),
                    b.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("写 launcher 脚本失败：" + e);
        }
        script.setReadable(true, false);
        script.setExecutable(true, false);
        return "/root/launcher_" + acc.id + ".sh";
    }
    private static String guestWork(NapCatAccount acc) {
        return "/root/napcat_instances/" + acc.id + "_napcat";
    }

    private static String guestHome(NapCatAccount acc) {
        return "/root/napcat_instances/" + acc.id + "_home";
    }

    /** 建实例目录，并强制把属主修正成 App 自己。 */
    private static void prepareDirs(Env env, NapCatAccount acc, BootLog log) throws Exception {
        File parent = instancesDir(env);
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new Exception("无法创建 " + parent);
        }
        // 父目录本身也要可写，否则下面两个 mkdirs 会失败
        //noinspection ResultOfMethodCallIgnored
        parent.setReadable(true, false);
        //noinspection ResultOfMethodCallIgnored
        parent.setWritable(true, false);
        //noinspection ResultOfMethodCallIgnored
        parent.setExecutable(true, false);

        File work = acc.workDir(env);
        File home = acc.homeDir(env);
        for (File d : new File[]{work, home,
                new File(work, "config"), new File(work, "cache"),
                new File(work, "logs"),
                new File(home, ".config"), new File(home, ".cache"),
                new File(home, ".local/share"),
                // NapCat 自己会往 $HOME/.config/QQ/NapCat/temp 里写临时文件
                // （收图、日志之类）。它的 mkdirSync 没带 recursive，
                // 父目录 .config/QQ/NapCat 不在就直接
                //   Error: ENOENT: no such file or directory,
                //          mkdir '.../.config/QQ/NapCat/temp'
                // → Worker 退出码 1 → 重拉 (1/3) → 再崩，死循环。
                // 表现就是：二维码能出、扫码也进了「正在快速登录」，
                // 但 NapCat 这边立刻崩掉，永远写不出 onebot11_<uin>.json，
                // 3001 也从不起监听。
                // AstrBot 的实例 HOME 里这两个目录是一直存在的
                // （实测 .../qq1_1788000289527_home/.config/QQ/NapCat/{temp,data}），
                // 所以它没踩到这个坑。这里照它的样子预先建好。
                new File(home, ".config/QQ"),
                new File(home, ".config/QQ/NapCat"),
                new File(home, ".config/QQ/NapCat/temp"),
                new File(home, ".config/QQ/NapCat/data")}) {
            if (!d.isDirectory() && !d.mkdirs()) {
                throw new Exception("无法创建 " + d + "（检查上层目录权限）");
            }
        }
        // 关键：之前手工用 root 建过 test2，属主是 root，App 写不进去，
        // 表现就是「配置只写了一半就断」。这里每次都确保可写。
        log.info("实例目录：" + work.getAbsolutePath());
        log.info("实例 HOME：" + home.getAbsolutePath());
        for (File d : new File[]{parent, work, home}) {
            if (!d.canWrite()) {
                log.warn(d.getName() + " 不可写，尝试修正权限");
                //noinspection ResultOfMethodCallIgnored
                d.setWritable(true, false);
                //noinspection ResultOfMethodCallIgnored
                d.setReadable(true, false);
                //noinspection ResultOfMethodCallIgnored
                d.setExecutable(true, false);
            }
        }
    }

    /** 组装完整的 proot 命令行。 */
    private static List<String> buildCommand(Env env, NapCatAccount acc, BootLog log) {
        List<String> cmd = new ArrayList<>();
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
        cmd.add(new File(env.tmp(), "shm").getAbsolutePath() + ":/dev/shm");
        cmd.add("-b");
        cmd.add("/system/etc/hosts:/etc/hosts");
        // 关键：-w /root 由 proot 自己在内部处理 chdir（发生在 ptrace 层），
        // 不会走到 guest 的 chdir(2) 系统调用上。
        // 而 App 进程带着 Android 强制的 seccomp filter（Seccomp=2, filters=1），
        // guest 里的 chdir 会直接返回 ENOSYS —— 这就是之前
        //   "cd: /root: Function not implemented" 的来源，
        // 也是整套启动崩掉的起点。
        // 实测对比：run-as 起的进程 Seccomp=0（能跑通），App 进程 Seccomp=2（会失败），
        // AstrBot 主进程同样 Seccomp=2，但它用 -w /root 且脚本里从不 cd，所以能成。
        cmd.add("-w");
        cmd.add("/root");
        cmd.add("--kill-on-exit");

        // ── 照 AstrBot 的做法：把整个启动过程写成一个真实的 launcher.sh 丢进 guest，
        //    由 /bin/bash 去执行，而不是在 proot 命令行里堆一长串 && 。
        //
        //    为什么必须换成脚本（踩过三次的教训）：
        //    1) 长链里任何一步返回非 0（chmod 失败、rm 找不到文件、mkdir 已存在…）
        //       都会让整条链断在半路，而且断在哪完全看不出来；
        //    2) 脚本里能用 if 判断 + 把 xvfb.log 原样打出来，失败时一眼看到原因；
        //    3) 脚本里可以用真正的循环等 X socket，不受 sh 单行写法限制；
        //    4) AstrBot 就是这么干的（root/launcher_qq1_*.sh），实测启动又快又稳。
        String script = writeLauncher(env, acc, log);
        cmd.add("/bin/bash");
        cmd.add(script);
        return cmd;
    }

    /** 等端口开始监听。 */
    public static boolean waitForPort(int port, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (ProcessKiller.isPortListening(port)) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 去掉 ANSI 颜色控制符。 */
    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    /**
     * 过滤：只保留对用户有意义的行。
     * <p>
     * QQ 启动会吐大量 dbus / inotify / udev 报错，全显示等于没显示。
     * 这里只挑：我们自己的 [启动]、NapCat / WebUi / Login、以及真错误。
     */
    private static boolean isInteresting(String s) {
        if (s == null || s.trim().isEmpty()) {
            return false;
        }
        String t = s.toLowerCase();
        if (s.contains("[启动]")) {
            return true;
        }
        if (s.contains("NapCat") || s.contains("WebUi") || s.contains("二维码")
                || s.contains("Login") || s.contains("登录")) {
            return true;
        }
        // 关键错误：X server / 崩溃 / 垫片 / 注入
        return t.contains("missing x server")
                || t.contains("signal 11")
                || t.contains("seccompshim")
                || t.contains("launcher")
                || s.contains("Fatal")
                || t.contains("error while loading")
                || t.contains("no such file or directory");
    }

    /** 给一行打来源标签，便于用户分辨是谁说的。 */
    private static String sourceOf(String s) {
        if (s.contains("[启动]") || s.contains("seccompshim")) {
            return "脚本";
        }
        if (s.contains("NapCat") || s.contains("WebUi") || s.contains("二维码")
                || s.contains("Login") || s.contains("登录")) {
            return "NapCat";
        }
        if (s.contains("launcher") || s.contains("preload")) {
            return "注入";
        }
        return "QQ";
    }

    /**
     * 确保 seccomp 垫片在位。
     * <p>
     * 没有它 Xvfb 起不来（link ENOSYS → 锁文件建不出），
     * QQ 就报 Missing X server 然后 signal 11 —— 界面上就是「点了没反应」。
     */
    public static void ensureShim(Context ctx, Env env, BootLog log) {
        File dst = shim(env);
        if (dst.isFile() && dst.length() > 10_000) {
            if (log != null) {
                log.info("垫片已就位：" + dst.length() + " 字节");
            }
            return;
        }
        try (java.io.InputStream in = ctx.getAssets().open("seccompshim.bin");
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (Exception e) {
            Log.w(TAG, "写入 seccomp 垫片失败: " + e);
            if (log != null) {
                log.warn("垫片写入失败：" + e);
            }
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        dst.setReadable(true, false);
        if (log != null) {
            log.info("垫片已释放：" + dst.length() + " 字节");
        }
    }

    // ---------------------------------------------------------------- token

    /**
     * 从实例日志里抓 NapCat 生成的 WebUI token。
     * <p>
     * 以 NapCat 自己打印的为准（它可能在启动时重生成），抓不到才退回配置值。
     * 这样「点启动」之后 App 就能直接把 token 填进 WebView 自动登录。
     */
    public static String waitForToken(Env env, NapCatAccount acc, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        File log = acc.runLog(env);
        while (System.currentTimeMillis() < deadline) {
            String t = parseToken(log);
            if (t != null) {
                acc.webuiToken = t;
                return t;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return acc.webuiToken;
    }

    private static String parseToken(File log) {
        if (!log.isFile()) {
            return null;
        }
        try (BufferedReader r = new BufferedReader(new java.io.FileReader(log))) {
            String line;
            while ((line = r.readLine()) != null) {
                String s = stripAnsi(line);
                int i = s.indexOf("WebUi Token:");
                if (i < 0) {
                    i = s.indexOf("WebUi Token：");
                }
                if (i >= 0) {
                    String rest = s.substring(i + "WebUi Token:".length()).trim();
                    if (!rest.isEmpty()) {
                        return rest;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ---------------------------------------------------------------- 停止

    /** 停掉这个实例：按 rootfs 特征把它的 QQ/NapCat 进程一起收掉。 */
    public static synchronized void stop(Env env, NapCatAccount acc) {
        BootLog log = new BootLog(env.base());
        log.info("停止 " + acc.label + "（端口 " + acc.webuiPort + "）");
        // 只停 NapCat / QQ / Xvfb。
        // 之前这里用的是 killAll，结果「停 NapCat」会把麦麦一起停掉，
        // 用户看到的就是「只要动 NapCat，麦麦就自动没了」。
        List<Integer> killed = ProcessKiller.killNapCat(env.rootfs().getAbsolutePath());
        log.info("已结束 NapCat 进程：" + killed + "（麦麦不受影响）");
        //noinspection ResultOfMethodCallIgnored
        new File(env.tmp(), ".X" + acc.display + "-lock").delete();
        log.info("已清理 X" + acc.display + " 锁");
    }

    /**
     * 删掉某个目录下的 X 残留：{@code .X<n>-lock} 与 {@code .X11-unix/X<n>}。
     * <p>
     * 这两个是 Xvfb 判断「display 是否被占用」的依据。删不干净的话它会
     * 直接 fatal 退出，然后 QQ（Electron）报 Missing X server 并崩溃。
     */
    private static void removeXArtifacts(File base, int display, BootLog log) {
        if (base == null) {
            return;
        }
        File lock = new File(base, ".X" + display + "-lock");
        if (lock.exists()) {
            //noinspection ResultOfMethodCallIgnored
            lock.delete();
            if (log != null) {
                log.info("清残留锁 " + lock.getAbsolutePath());
            }
        }
        // proot 用 link2symlink 时会把 .X<n>-lock 做成符号链接，
        // 真正的目标文件叫 .l2s..tX<n>-lock000N，删掉链接不会删目标，
        // 下次 Xvfb 还会顺着链接看到「display 已被占用」，所以一起清。
        File dir = base.isDirectory() ? base : base.getParentFile();
        if (dir != null && dir.isDirectory()) {
            File[] leftovers = dir.listFiles((d, name) -> name.startsWith(".l2s..tX" + display + "-lock"));
            if (leftovers != null) {
                for (File f : leftovers) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        File sock = new File(new File(base, ".X11-unix"), "X" + display);
        if (sock.exists()) {
            //noinspection ResultOfMethodCallIgnored
            sock.delete();
            if (log != null) {
                log.info("清残留 socket " + sock.getAbsolutePath());
            }
        }
    }

    /**
     * 反向确认 guest 的 {@code /tmp} 真的能写。
     * <p>
     * 实测踩到过：某个历史版本把 {@code files/tmp} 绑到 {@code /tmp/hosttmp} 时，
     * 宿主的 {@code <rootfs>/tmp/hosttmp} 被留成了 {@code d---------}（全锁死）。
     * 于是 Xvfb 在里面建不出锁文件，直接 fatal；QQ 拿不到 X 就 signal 11。
     * 这里在每次启动前把它的权限纠正回来，不要再靠手工 chmod。
     */
    private static void ensureGuestTmpWritable(Env env, BootLog log) {
        File[] dirs = {
                new File(env.rootfs(), "tmp"),
                new File(env.rootfs(), "tmp/hosttmp"),
                new File(env.rootfs(), "tmp/.X11-unix"),
                new File(env.tmp(), "shm"),
        };
        for (File d : dirs) {
            if (!d.isDirectory() && !d.mkdirs()) {
                if (log != null) {
                    log.warn("建不出目录 " + d.getAbsolutePath() + "（继续尝试）");
                }
                continue;
            }
            if (!d.canWrite()) {
                //noinspection ResultOfMethodCallIgnored
                d.setReadable(true, false);
                //noinspection ResultOfMethodCallIgnored
                d.setWritable(true, false);
                //noinspection ResultOfMethodCallIgnored
                d.setExecutable(true, false);
                if (log != null) {
                    log.info("纠正目录权限 " + d.getAbsolutePath());
                }
            }
        }
    }

    /** 账号登录后，把 uin 记到账号里（供下次快速登录用）。 */
    public static void refreshUin(Env env, NapCatAccount acc) {
        String uin = acc.detectUin(env);
        if (!uin.isEmpty() && !uin.equals(acc.uin)) {
            acc.uin = uin;
        }
    }
}