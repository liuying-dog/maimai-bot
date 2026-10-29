package com.maimai.bot.core;
import android.content.Context;
import android.util.Log;

import com.maimai.bot.BotState;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 在本应用的 proot 里执行 assets/scripts/step.sh，按项安装环境。
 * <p>
 * 为什么不复用 BotService 的启动流程：那条路是「起 maibot 并常驻」，
 * 这里要的是「跑一段命令、拿到退出码、把输出带回界面」。两者生命周期不同，
 * 硬塞在一起会把服务的状态机搅乱。
 * <p>
 * 但 proot 的调用参数必须和 BotService 完全一致 —— 同一套 rootfs、
 * 同一套 PROOT_LOADER、同一套环境变量清理，否则会踩到之前那些
 * ENOSYS / libtalloc 找不到的坑。
 */
public final class StepRunner {

    private static final String TAG = "StepRunner";

    private StepRunner() {
    }

    /**
     * 跑一个安装步骤。
     *
     * @param key  步骤名（apt / uv / python / maibot / deps / instance / all）
     * @param sink 收集输出的缓冲，可为 null
     * @return 进程退出码，-1 表示没跑起来
     */
public static int run(Context ctx, String key, StringBuilder sink) {
        BotState st = BotState.get(ctx);
        Env env = st.env();

        // NapCat 走独立的安装逻辑：它是「官方 LinuxQQ deb + NapCat 目录 + 注入库」，
        // 不是能用 apt/pip 一条命令装完的东西，所以单独一个安装器。
        if ("napcat".equals(key)) {
            return NapCatInstaller.install(ctx, env, sink);
        }

        // LinuxQQ 单独一项：只重装 QQ 本体，不碰 NapCat 目录和配置。
        // 它和 NapCat 是两条独立发布线，版本也该分开看、分开升。
        if ("linuxqq".equals(key)) {
            return NapCatInstaller.reinstallLinuxQQ(ctx, env, sink);
        }

        // 1) 把 step.sh 落到 guest 里
        //    guest 的 /root 在宿主上就是 <rootfs>/root
        // proot 的临时目录要先备好，否则一启动就 fatal
        env.ensureDirs();
        File guestRoot = new File(env.rootfs(), "root");
        if (!guestRoot.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            guestRoot.mkdirs();
        }
        File script = new File(guestRoot, "step.sh");
        try {
            copyAsset(ctx, "scripts/step.sh", script);
            // shell 脚本必须可执行，否则 bash 会拒绝
            //noinspection ResultOfMethodCallIgnored
            script.setExecutable(true, false);
        } catch (IOException e) {
            append(sink, "释放 step.sh 失败: " + e);
            Log.w(TAG, "copy step.sh 失败", e);
            return -1;
        }

        // 2) 组 proot 命令。参数与 BotService.buildProotCommand 保持一致，
        //    只把最后的 bash 脚本换掉。
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
        File hosts = new File("/system/etc/hosts");
        if (hosts.isFile()) {
            cmd.add("-b");
            cmd.add(hosts.getAbsolutePath() + ":/etc/hosts");
        }
        cmd.add("--kill-on-exit");
        cmd.add("/bin/bash");
        cmd.add("/root/step.sh");
        cmd.add(key);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File("/"));
        pb.redirectErrorStream(true);

        // 3) 环境变量：和启动路径同一套处理
        java.util.Map<String, String> e = pb.environment();
        e.clear();
        e.put("PROOT_TMP_DIR", env.tmp().getAbsolutePath());
        e.put("PROOT_NO_SECCOMP", "1");
        e.put("PROOT_LOADER", env.prootLoader().getAbsolutePath());
        e.put("LD_LIBRARY_PATH", env.bin().getAbsolutePath());
        e.put("HOME", "/root");
        e.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        e.put("TMPDIR", "/tmp/hosttmp");
        e.put("LANG", "C.UTF-8");

        // 加速器：只需要 GitHub 代理；apt/pypi 用固定的中科大源（实测 200，最稳）
        e.put("MB_APT_BASE", Mirror.APT_BASE);
        e.put("MB_PYPI_INDEX", Mirror.PYPI_INDEX);
        e.put("MB_GH_PROXY", Mirror.resolvedPrefix(ctx));

        Process p = null;
        try {
            p = pb.start();
        } catch (IOException ex) {
            append(sink, "启动 proot 失败: " + ex);
            Log.w(TAG, "启动失败", ex);
            return -1;
        }

        // 4) 边读边收集
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                append(sink, line);
            }
        } catch (IOException ex) {
            Log.w(TAG, "读输出失败", ex);
        }

        try {
            // 给足时间：apt 装东西 + uv 下载可能好几分钟
            if (!p.waitFor(12, java.util.concurrent.TimeUnit.MINUTES)) {
                p.destroyForcibly();
                append(sink, "步骤超时（12 分钟），已强制结束");
                return -1;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return -1;
        } finally {
            // 收尾：把这次跑出来的残留清掉，别让 proot 一层层堆着
            ProcessKiller.killAll(env.rootfs().getAbsolutePath(), -1);
        }
        return p.exitValue();
    }

    // ------------------------------------------------------------- 内部

    private static void copyAsset(Context ctx, String assetPath, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (InputStream in = ctx.getAssets().open(assetPath);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void append(StringBuilder sink, String line) {
        if (sink == null) {
            return;
        }
        sink.append(line).append('\n');
        // 防止极端情况下把内存吃满
        if (sink.length() > 64 * 1024) {
            sink.delete(0, sink.length() - 48 * 1024);
        }
    }
}