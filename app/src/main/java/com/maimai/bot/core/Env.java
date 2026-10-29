package com.maimai.bot.core;

import android.content.Context;

import java.io.File;

/**
 * 应用内所有路径的唯一出口。
 * <p>
 * 目录布局（全部落在 app 私有目录，无需任何存储权限）：
 * <pre>
 * files/
 *   bin/            proot 与 libtalloc.so.2
 *   rootfs/         解出来的 Ubuntu 根文件系统
 *   rootfs.stamp    安装完成标记（内容为 rootfs 版本号）
 *   instance/       MaiBot 实例目录（config / data / logs / plugins）
 *   tmp/            proot 的临时目录与 xz 解压中转
 * </pre>
 */
public final class Env {

    private final File base;
    private final File rootfs;
    private final File tmp;
    /** 系统给的原生库目录，这是应用唯一被允许 exec 的位置。 */
    private final File nativeLibDir;

    private Env(File base, File nativeLibDir) {
        this.base = base;
        this.rootfs = new File(base, "rootfs");
        this.tmp = new File(base, "tmp");
        this.nativeLibDir = nativeLibDir;
    }

    /**
     * 注意 proot 的位置：必须用 nativeLibraryDir。
     * <p>
     * Android 10+ 有 W^X 限制，data 分区（files/ 目录）下的文件一律禁止 exec，
     * 直接跑会得到 {@code error=13, Permission denied}。
     * 唯一被放行的是安装后由系统解出的 lib 目录
     * （/data/app/~~xxx/com.xxx/lib/arm64），所以 proot 以 libproot.so 的形式
     * 打进 jniLibs，运行时直接用系统的绝对路径调它。
     */
    public static Env of(Context ctx) {
        Context app = ctx.getApplicationContext();
        return new Env(app.getFilesDir(), new File(app.getApplicationInfo().nativeLibraryDir));
    }

    public File base() {
        return base;
    }

    /** proot 可执行文件所在目录（nativeLibraryDir，Android 上唯一允许 exec 的位置）。 */
    public File bin() {
        return nativeLibDir;
    }

    public File nativeLibDir() {
        return nativeLibDir;
    }

    /**
     * 打包名是 libproot.so，这里返回它在 lib 目录下的真实路径。
     * <p>
     * 用的是 Operit 那个 proot 移植版（256864 字节，只依赖 libdl/libc），
     * 不是 AstrBot 那个（214536 字节，依赖 libtalloc）。
     * 后者在 Android 上跑不通：它加载 ELF 解释器时内核直接 ENOENT，
     * 因为它硬编码的 PT_INTERP 是 /lib/ld-linux-aarch64.so.1，而安卓没有 /lib。
     */
    public File proot() {
        return new File(nativeLibDir, "libproot.so");
    }
    /**
     * proot 的外置 loader。
     * <p>
     * App 进程带了 Android 强制的 seccomp filter（/proc/pid/status 里
     * Seccomp: 2、Seccomp_filters: 1），proot 用内嵌 loader 做 exec 时
     * 会被拦，guest 里 getcwd/chdir 全返回 ENOSYS。
     * 显式指定一个独立的 loader 文件，走另一条 exec 路径可以绕开。
     */
    public File prootLoader() {
        return new File(nativeLibDir, "libproot_loader.so");
    }

    public File rootfs() {
        return rootfs;
    }

    /**
     * MaiBot 实例目录。
     * <p>
     * 这里刻意返回 rootfs 内部的真实路径
     * （files/rootfs/root/maibot），而不是另外建一个 files/instance
     * 再用 proot 的 -b 挂进去。
     * <p>
     * 原因是踩过一次大坑：曾经用 -b 把宿主 files/instance 挂到 guest
     * 的 /root/maibot，但那个绑定在某些情况下并不生效（实测宿主
     * instance 目录一直是空的，而东西全落在 rootfs 里）。
     * 于是 hasMaibot() 查宿主路径永远查不到标记，每次点启动都
     * 重新走一遍引导安装，用户看到的就是"启动就报一堆错"。
     * <p>
     * 直接用 rootfs 内的路径，宿主和 guest 看到的是同一份文件，
     * 不存在对不上的可能。
     */
    public File instance() {
        return new File(rootfs, "root/maibot");
    }

    /** 跟 instance() 同一个位置，单独留个名字给配置文件用。 */
    public File configDir() {
        return new File(instance(), "config");
    }

    public File tmp() {
        return tmp;
    }

    public File stamp() {
        return new File(base, "rootfs.stamp");
    }

    public File webuiToken() {
        return new File(instance(), "data/webui.json");
    }

    public File botConfig() {
        return new File(instance(), "config/bot_config.toml");
    }

    public File modelConfig() {
        return new File(instance(), "config/model_config.toml");
    }

    public File runtimeLog() {
        return new File(base, "runtime.log");
    }

    /** 已安装则返回 true（stamp 里的版本与内置版本一致）。 */
    public boolean isRootfsReady(String builtinVersion) {
        if (!rootfs.isDirectory()) {
            return false;
        }
        if (!new File(rootfs, "bin/bash").exists()) {
            return false;
        }
        File s = stamp();
        if (!s.isFile()) {
            return false;
        }
        try {
            String got = readSmall(s).trim();
            return got.equals(builtinVersion);
        } catch (Exception e) {
            return false;
        }
    }

    public void markRootfsReady(String builtinVersion) throws java.io.IOException {
        base.mkdirs();
        writeSmall(stamp(), builtinVersion);
    }

    public void clearRootfs() {
        deleteTree(rootfs);
        stamp().delete();
    }

    public void ensureDirs() {
        rootfs.mkdirs();
        tmp.mkdirs();
        // proot 会 bind 这几个路径，宿主侧不存在就直接报
        //   proot warning: can't sanitize binding "...": No such file or directory
        // 新手机上第一次跑必然没有，所以这里一并建出来。
        new File(rootfs, "root").mkdirs();
        new File(rootfs, "root/maibot").mkdirs();
        instance().mkdirs();
    }

    private static String readSmall(File f) throws java.io.IOException {
        byte[] buf = new byte[(int) Math.min(f.length(), 256)];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int off = 0;
            int n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) {
                off += n;
            }
            return new String(buf, 0, off, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static void writeSmall(File f, String content) throws java.io.IOException {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    public static void deleteTree(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteTree(k);
                }
            }
        }
        f.delete();
    }
}