package com.maimai.bot.core;

import android.content.Context;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * 首次启动时的环境安装：把 assets 里的 proot / libtalloc / rootfs.tar.gz
 * 铺到私有目录，并写安装完成标记。
 * <p>
 * 为什么用 .tar.gz 而不是上游的 .tar.xz：Android 的 JDK 只有 zlib，
 * toybox 也没有 xz，自行实现 LZMA2 状态机出错风险太高（错一位就是静默坏数据）。
 * gzip 由 JDK 原生支持，正确性有保证，代价只是 assets 大几十 MB。
 */
public final class Installer {

    public static final String ROOTFS_VERSION = "ubuntu-noble-2024.04-r1";

    private static final String ASSET_ROOTFS = "rootfs.bin";
    // 老 APK 里 AAPT 可能把 .tar.gz 改名成 .tar，这里留个回退探测
    private static final String[] ASSET_ROOTFS_FALLBACKS = {
            "rootfs.bin", "rootfs.tar.gz", "rootfs.tar"
    };

    public interface Callback {
        /** stage 是给人看的阶段名；progress 0..1，未知时传 -1。 */
        void onStage(String stage, int progress);
    }

    private final Context ctx;
    private final Env env;

    public Installer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.env = Env.of(this.ctx);
    }

    public boolean isInstalled() {
        // 顺手把 rootfs / tmp 目录铺出来。
        // 这个判断会被 bootSequence 在启动前调用，是「每次都要走一遍」的入口，
        // 放在这里能保证后续所有拉 proot 的路径都有 PROOT_TMP_DIR 可用。
        env.ensureDirs();
        return env.isRootfsReady(ROOTFS_VERSION);
    }

    /**
     * 安装或修复。可重复调用；已有可用安装时直接返回。
     */
    public void install(Callback cb) throws IOException {
        env.ensureDirs();

        // 1) proot 与 libtalloc 由系统从 jniLibs 解到 nativeLibraryDir，
        //    这里只做存在性校验。不再往 files/ 复制——那目录在 Android 10+
        //    禁止 exec，复制过去也跑不起来（error=13）。
        emit(cb, "准备 proot", 2);
        if (!env.proot().isFile()) {
            throw new IOException("找不到 proot：" + env.proot().getAbsolutePath()
                    + "\nAPK 里的 jniLibs/arm64-v8a/libproot.so 没有被打包，请检查构建配置。");
        }

        // 2) 解 rootfs
        if (!env.isRootfsReady(ROOTFS_VERSION)) {
            emit(cb, "清理解压目录", 5);
            env.clearRootfs();
            env.rootfs().mkdirs();

            emit(cb, "解压 Ubuntu 根文件系统", 8);
            int entries = extractRootfs(cb);

            // 解压后先校验关键文件真的在，再写 stamp。
            // 顺序反了就会留下"假安装"：stamp 已在，但 rootfs 是残缺的，
            // 下次启动直接跳过解压，永远修不好。
            emit(cb, "校验解压结果", 95);
            File bash = new File(env.rootfs(), "usr/bin/bash");
            if (entries < 1000 || !bash.isFile()) {
                throw new IOException("rootfs 解压不完整：条目 " + entries
                        + "，usr/bin/bash " + (bash.isFile() ? "存在" : "缺失")
                        + "。请重试，或点「重装环境」。");
            }

            emit(cb, "写入安装标记", 96);
            env.markRootfsReady(ROOTFS_VERSION);
        }

        // 3) 释放内置的 maibot 环境（venv + uv 装的 Python）
        //
        // 为什么内置：maibot 本体原来是 bootstrap.sh 里联网 `uv tool install` 装的，
        // 新手机必须先联网、下 90 个依赖才能用。实测 venv 771MB + python 90MB，
        // 打包压缩后 255MB（tar.gz 压缩比约 3.4 倍），塞进 assets 就变成「装完即用」。
        // 这里解压到 guest 的 /root/.local/share/uv/ 下，路径和 uv 自己装出来的完全一致。
        installBundledMaibot(cb);
        // 3.5) 释放内置的 uv 本体
        //
        // 之前只打了 venv，没打 uv 这个可执行文件。结果 bootstrap.sh 里
        // `command -v uv` 判定失败，去 astral.sh 下载 —— 那一步在
        // 「网络不好 / TMPDIR 不对 / 安全策略拦 curl」时直接失败，
        // 表现就是点启动一秒就「出错」，日志里只有一行 uv 安装失败。
        // uv 本身 31MB，gzip 之后 15MB，塞进来最省事。
        installBundledUv(cb);
        // 3.6) NapCat + LinuxQQ 不内置进 APK（体积 300MB），
        //      首次安装完只报一句状态，真正的安装走「环境管理 → NapCat」。
        if (NapCat.isInstalled(env)) {
            emit(cb, "NapCat 环境已就位", 96);
        } else {
            emit(cb, "NapCat 未安装（可在环境管理里装）", 96);
        }
        // 4) proot 自检：跑 /bin/true，确认能真的进去
        emit(cb, "校验运行环境", 97);
        if (!selfTest()) {
            throw new IOException("proot 自检失败"
                    + (lastSelfTestError.isEmpty() ? "" : "：" + lastSelfTestError));
        }

        emit(cb, "安装完成", 100);
    }

    /**
     * 释放 APK 内置的 maibot 环境（maibot venv + uv 装的 Python）。
     * <p>
     * 打包时是 `tar -czf maibot_env.bin -C .../uv tools/maibot python`，
     * 所以解压目标就是 guest 的 /root/.local/share/uv/，
     * 解完正好是 uv/ tools/maibot 和 uv/python —— 和 uv 自己装出来的结构一致。
     * <p>
     * venv 里的 bin/python 是指向 /root/.local/share/uv/python/...
     * 的**绝对路径软链**，所以必须靠 tar 保留软链（TarExtractor 的 case '2'），
     * 解压到同一位置才能指对。这也是为什么不能只打包 tools 目录。
     */
    private void installBundledMaibot(Callback cb) throws IOException {
        File uvDir = new File(env.rootfs(), "root/.local/share/uv");
        File entry = new File(uvDir, "tools/maibot/bin/maibot");

        // 已经就位就别重复解 255MB
        if (entry.isFile()) {
            emit(cb, "内置 maibot 环境已就位", 96);
            return;
        }
        if (!uvDir.isDirectory() && !uvDir.mkdirs()) {
            throw new IOException("无法创建目录: " + uvDir);
        }

        emit(cb, "释放内置 maibot 环境（约 255MB）", 95);
        int entries;
        try (InputStream raw = ctx.getAssets().open(
                "maibot_env.bin", android.content.res.AssetManager.ACCESS_STREAMING);
             BufferedInputStream bin = new BufferedInputStream(raw, 256 * 1024);
             GZIPInputStream gz = new GZIPInputStream(bin, 256 * 1024)) {
            TarExtractor ex = new TarExtractor(uvDir, null);
            entries = ex.extract(gz);
        }
        if (!entry.isFile()) {
            throw new IOException("内置 maibot 环境解压后找不到入口（条目 " + entries
                    + "），包可能不完整");
        }
        // 入口和 launcher 必须可执行
        //noinspection ResultOfMethodCallIgnored
        entry.setExecutable(true, false);
        File python = new File(uvDir, "tools/maibot/bin/python");
        //noinspection ResultOfMethodCallIgnored
        python.setExecutable(true, false);

        // 补一个 PATH 里的入口。
        // uv 装的时候会在 /root/.local/bin/ 放一个 maibot 软链，但打包只带了
        // tools/maibot 和 python，这个软链没带过来。没有它的话
        // bootstrap.sh 里 `command -v maibot` 会判定成未安装，又去联网装一遍。
        // Java 在 Android 上不方便建软链，直接写个三行 wrapper 更省事。
        File binDir = new File(env.rootfs(), "root/.local/bin");
        //noinspection ResultOfMethodCallIgnored
        binDir.mkdirs();
        File wrapper = new File(binDir, "maibot");
        try (FileOutputStream out = new FileOutputStream(wrapper)) {
            out.write(("#!/bin/sh\n"
                    + "exec /root/.local/share/uv/tools/maibot/bin/maibot \"$@\"\n")
                    .getBytes("UTF-8"));
        }
        //noinspection ResultOfMethodCallIgnored
        wrapper.setExecutable(true, false);

        // 关键：把 getcwd/chdir 垫片塞进 site-packages。
        //
        // 没有它的话，第一次 `maibot --version` 就会死在
        //   OSError: [Errno 38] Function not implemented  (os.getcwd)
        // 因为那一次调用发生在 run.sh 打补丁之前（补丁是 run.sh 里写的），
        // 整个 bootstrap 也就断在这一步。之前一直是 run.sh 在运行期补，
        // 所以只有「内置环境 + 全新安装」这条路会炸。
        installSiteCustomize(uvDir);

        emit(cb, "内置 maibot 环境就绪（" + entries + " 项）", 96);
    }

    /**
     * 把 assets/scripts/sitecustomize.py 灌进 venv 的 site-packages。
     * <p>
     * 一个 venv 可能有多个 pythonX.Y 目录（升级残留），全部写一遍最省心；
     * Python 启动时 site 模块会自动 import 它，主进程和子进程都能吃到。
     */
    private void installSiteCustomize(File uvDir) throws IOException {
        File lib = new File(uvDir, "tools/maibot/lib");
        File[] versions = lib.listFiles();
        if (versions == null) {
            return;
        }
        byte[] patch = readAsset("scripts/sitecustomize.py");
        int written = 0;
        for (File v : versions) {
            File sp = new File(v, "site-packages");
            if (!sp.isDirectory()) {
                continue;
            }
            try (FileOutputStream out = new FileOutputStream(new File(sp, "sitecustomize.py"))) {
                out.write(patch);
            }
            written++;
        }
        if (written == 0) {
            throw new IOException("找不到 site-packages，无法写入 sitecustomize.py");
        }
    }

    /** 读 assets 里的文本文件。 */
    private byte[] readAsset(String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /**
     * 确保 NapCat + LinuxQQ 的运行环境在。
     * <p>
     * 为什么不再内置进 APK：LinuxQQ 是 196MB 的 deb、NapCat 是 92MB 目录，
     * 再加进 assets 会让包再涨 300MB；而且它们的安装需要 apt 解 deb，
     * 用 Java 解不干净。所以改成「启动时检查，缺了就提示去环境管理里装」，
     * 由 step.sh 的 napcat 分支负责真正安装（见 StepRunner）。
     * <p>
     * 这里只做「顺手确认」，不阻断麦麦启动 —— QQ 桥接挂了，麦麦本身还能跑。
     */
    public boolean ensureBundledNapCat() {
        boolean ok = NapCat.isInstalled(env);
        Log.i("Installer", "ensureBundledNapCat 环境就绪=" + ok
                + " qq=" + NapCat.qqBinary(env).isFile()
                + " napcat=" + new File(NapCat.dir(env), "napcat.mjs").isFile()
                + " launcher=" + NapCat.launcher(env).isFile());
        return ok;
    }

    /**
     * 释放内置的 uv 可执行文件到 guest 的 /root/.local/bin/uv。
     * <p>
     * assets 里是 gzip 过的裸二进制（uv.bin，31MB → 15MB），
     * 直接边解压边写文件，不占额外中转空间。
     * 装完置可执行位——否则 bootstrap.sh 里 `command -v uv` 找得到却跑不起来。
     */
    private void installBundledUv(Callback cb) throws IOException {
        File binDir = new File(env.rootfs(), "root/.local/bin");
        File uv = new File(binDir, "uv");
        if (uv.isFile() && uv.length() > 1024 * 1024) {
            emit(cb, "内置 uv 已就位", 96);
            return;
        }
        if (!binDir.isDirectory() && !binDir.mkdirs()) {
            throw new IOException("无法创建目录: " + binDir);
        }
        emit(cb, "释放内置 uv", 96);
        try (InputStream raw = ctx.getAssets().open(
                "uv.bin", android.content.res.AssetManager.ACCESS_STREAMING);
             BufferedInputStream bin = new BufferedInputStream(raw, 128 * 1024);
             GZIPInputStream gz = new GZIPInputStream(bin, 128 * 1024);
             FileOutputStream out = new FileOutputStream(uv)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = gz.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        uv.setExecutable(true, false);
        if (uv.length() < 1024 * 1024) {
            throw new IOException("内置 uv 解压后体积异常（" + uv.length() + " 字节）");
        }
    }
    // ------------------------------------------------------------- rootfs
    private int extractRootfs(Callback cb) throws IOException {
        final String asset = pickRootfsAsset();
        final long total = assetSize(asset);
        try (InputStream raw = ctx.getAssets().open(asset, android.content.res.AssetManager.ACCESS_STREAMING);
             CountingInputStream counting = new CountingInputStream(raw);
             BufferedInputStream buffered = new BufferedInputStream(counting, 128 * 1024);
             GZIPInputStream gz = new GZIPInputStream(buffered, 128 * 1024)) {

            TarExtractor extractor = new TarExtractor(env.rootfs(), (path, bytes, entries) -> {
                if (total > 0) {
                    // 压缩比大概 3.5~4 倍，用压缩流进度折算，只用于显示
                    int pct = (int) Math.min(94, 8 + (counting.count() * 86L / total));
                    emit(cb, "解压中 · " + entries + " 个文件", pct);
                }
            });
            int n = extractor.extract(gz);
            if (n < 100) {
                throw new IOException("rootfs 解出条目过少（" + n + "），压缩包可能不完整");
            }
            return n;
        }
    }

    /** 挨个探一遍，兼容不同打包方式下的资产名。都找不到就直接报清楚。 */
    private String pickRootfsAsset() throws IOException {
        for (String name : ASSET_ROOTFS_FALLBACKS) {
            try (InputStream probe = ctx.getAssets().open(name)) {
                if (probe != null) {
                    return name;
                }
            } catch (IOException ignored) {
                // 换下一个
            }
        }
        throw new IOException("APK 里找不到 rootfs 资产（试过 "
                + String.join(" / ", ASSET_ROOTFS_FALLBACKS)
                + "）。通常是构建时 assets 被压缩改名了。");
    }

    // ------------------------------------------------------------- 自检

    /** 上一次自检失败的原因，供界面显示，查问题时不用去翻 logcat。 */
    private String lastSelfTestError = "";

    public String lastSelfTestError() {
        return lastSelfTestError;
    }

    /**
     * 用装好的 proot 跑一次 /bin/true，确认整条链路真的通。
     * 失败时把 proot 的真实输出留下来，不要只回一个 false。
     */
    public boolean selfTest() {
        lastSelfTestError = "";
        try {
            ProotRunner r = new ProotRunner(ctx);
            StringBuilder out = new StringBuilder();
            int code = r.execCapture(new String[]{"/bin/true"}, 30_000, out);
            if (code == 0) {
                return true;
            }
            lastSelfTestError = "退出码 " + code
                    + (out.length() > 0 ? "：" + out.toString().trim() : "");
            return false;
        } catch (Exception e) {
            lastSelfTestError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return false;
        }
    }

    // ------------------------------------------------------------- 工具

    /**
     * 取资产大小，仅用于进度条百分比，取不到就返回 -1（进度条退化为不确定）。
     * 注意不能用 available()：assets 里的大文件走 AssetFileDescriptor，
     * available() 返回的数与真实长度无关。
     */
    private long assetSize(String name) {
        try (android.content.res.AssetFileDescriptor fd = ctx.getAssets().openFd(name)) {
            return fd.getLength();
        } catch (IOException ignored) {
            return -1;
        }
    }

    private static void emit(Callback cb, String stage, int pct) {
        if (cb != null) {
            cb.onStage(stage, pct);
        }
    }

    /** 记录已读字节，供进度换算。 */
    private static final class CountingInputStream extends InputStream {
        private final InputStream src;
        private long count;

        CountingInputStream(InputStream src) {
            this.src = src;
        }

        long count() {
            return count;
        }

        @Override
        public int read() throws IOException {
            int b = src.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = src.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            src.close();
        }
    }
}