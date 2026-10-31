package com.maimai.bot.core;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * NapCat + LinuxQQ 的安装器。
 * <p>
 * 为什么单独一个类（而不是塞进 step.sh）：
 * 这套东西是三块异质资源的组合 ——
 * <ol>
 *   <li>官方 LinuxQQ deb（196MB，apt 装，带一堆 GTK/ATK/NSS 依赖）</li>
 *   <li>NapCat 目录（92MB，含 napcat.mjs + node_modules，纯解压）</li>
 *   <li>libnapcat_launcher.so（72KB，LD_PRELOAD 注入库）</li>
 * </ol>
 * 它们的安装顺序、失败处理都不一样，用 shell 拼一遍再拼一遍很难维护，
 * 所以这里用 Java 把它们串起来，shell 只负责 apt 那一段。
 * <p>
 * 走 APK 内置资源的策略：LinuxQQ 的 deb 和 NapCat 的 tar 都可以内置
 * （代价是 APK 变大），也可以运行期下载（代价是新手机首次要联网）。
 * 这里两者都支持：assets 里有就用本地的，没有再去下。
 */
public final class NapCatInstaller {
    private static final String TAG = "NapCatInstaller";

    /** LinuxQQ 官网实时配置（JSONP 里带各架构最新 deb 地址）。 */
    private static final String QQ_CONFIG_URL =
            "https://cdn-go.cn/qq-web/im.qq.com_new/latest/rainbow/linuxConfig.js";
    /** 腾讯 URL 签名接口（直链 403 时用它换带 sign 的地址）。 */
    private static final String QQ_SIGN_URL =
            "https://im.qq.com/http2rpc/gotrpc/noauth/trpc.qqntv2.urlsign.UrlSign/GetSign";
    /** 最终兜底：写死的 LinuxQQ arm64 deb（腾讯 CDN，不需要代理）。 */
    private static final String QQ_DEB_URL_FALLBACK =
            "https://dldir1v6.qq.com/qqfile/qq/QQNT/Linux/QQ_3.2.18_250626_arm64_01.deb";
    /** NapCat 的发行包（GitHub Releases，走代理）。 */
    private static final String NAPCAT_ZIP_URL =
            "https://github.com/NapNeko/NapCatQQ/releases/latest/download/NapCat.Shell.zip";
    /** Linux 端注入库源码（要 g++ 现编）。 */
    private static final String LAUNCHER_CPP_URL =
            "https://raw.githubusercontent.com/NapNeko/napcat-linux-launcher/refs/heads/main/launcher.cpp";

    /**
     * assets 里内置的 NapCat 版本号。
     * <p>
     * 为什么硬编码：NapCat 的发行包里 package.json 的 version 永远是 "0.0.1"
     * （4.18.41 的官方包里也是），从包里读不出真实版本。
     * 所以内置包是哪一版，只能在这里记着；装完之后写进 .napcat_version。
     * <p>
     * 换 assets/napcat.zip 时必须同步改这里，否则界面显示的版本会跟实际不符。
     */
    static final String BUNDLED_NAPCAT_VERSION = "4.18.42";

    private NapCatInstaller() {
    }

    /**
     * 只重装 LinuxQQ，不动 NapCat 目录和配置。
     * <p>
     * 为什么需要单独一个入口：LinuxQQ 和 NapCat 是两条独立的发布线
     * （QQ 3.2.x，NapCat 4.18.x），用户可能只想把 QQ 升上去，
     * 不该逼他把 NapCat 目录也一起重下重解压。
     * <p>
     * 做法：先把旧的 /opt/QQ 挪到 /opt/QQ.bak，装失败还能滚回去。
     */
    public static int reinstallLinuxQQ(Context ctx, Env env, StringBuilder sink) {
        File qq = new File(env.rootfs(), "opt/QQ");
        File bak = new File(env.rootfs(), "opt/QQ.bak");
        append(sink, "[NapCat] 准备重装 LinuxQQ");
        if (qq.isDirectory()) {
            // 先清掉上次可能残留的备份，再把当前这份挪过去
            deleteTree(bak);
            if (!qq.renameTo(bak)) {
                append(sink, "[NapCat] 旧 QQ 目录挪不动（可能还被占用），先尝试停进程");
                // 名字被占用时 rename 会失败，退一步：直接删（下面装失败会提示手动处理）
                deleteTree(qq);
            } else {
                append(sink, "[NapCat] 旧版本已备份到 /opt/QQ.bak");
            }
        }
        boolean ok = installLinuxQQ(ctx, env, sink);
        if (ok) {
            deleteTree(bak);
            append(sink, "[NapCat] LinuxQQ 重装完成");
            return 0;
        }
        if (bak.isDirectory()) {
            append(sink, "[NapCat] 装失败，回滚到旧版本");
            deleteTree(qq);
            bak.renameTo(qq);
        }
        append(sink, "[NapCat] LinuxQQ 重装失败");
        return 1;
    }

    /** 递归删目录；删不掉就放弃，不抛异常。 */
    private static void deleteTree(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /**
     * 执行安装。
     *
     * @return 0 成功，非 0 失败
     */
    public static int install(Context ctx, Env env, StringBuilder sink) {
        if (NapCat.isInstalled(env)) {
            append(sink, "[NapCat] 已安装，跳过");
            return 0;
        }
        append(sink, "[NapCat] 开始安装（约 300MB，需要联网，可能要几分钟）");

        // ① LinuxQQ
        if (!NapCat.qqBinary(env).isFile()) {
            if (!installLinuxQQ(ctx, env, sink)) {
                return 1;
            }
        } else {
            append(sink, "[NapCat] LinuxQQ 已就位");
        }

        // ② NapCat 目录
        File napcatDir = NapCat.dir(env);
        if (!new File(napcatDir, "napcat.mjs").isFile()) {
            if (!installNapCatShell(ctx, env, sink)) {
                return 1;
            }
        } else {
            append(sink, "[NapCat] NapCat 目录已就位");
        }

        // ③ 注入库
        if (!NapCat.launcher(env).isFile()) {
            installLauncher(ctx, env, sink);
        }

        // ④ OneBot 正向 WS 配置：必须开 websocketServers 3001，麦麦才连得进来。
        //    注意方向 —— NapCat 作服务端（正向 WS），麦麦适配器作客户端。
        //    （AstrBot 那边是反过来的：NapCat 作 client 连它自己的 6200。）
        writeOneBotConfig(env, sink);

        // ⑤ 运行必需的空目录（缺了 NapCat 启动就 ENOENT）
        new File(napcatDir, "cache").mkdirs();
        new File(napcatDir, "logs").mkdirs();

        if (!NapCat.isInstalled(env)) {
            append(sink, "[NapCat] 安装后仍不完整，请查看上面的日志");
            return 1;
        }
        append(sink, "[NapCat] 安装完成");
        return 0;
    }

    // ------------------------------------------------------------ LinuxQQ

    /**
     * 安装 LinuxQQ。
     * <p>
     * 地址来源优先级（照 AstrBot 的 astrbot-startup.sh）：
     * <ol>
     *   <li>实时查腾讯官网 linuxConfig.js，抓 arm64/aarch64 的 deb</li>
     *   <li>失败则退回写死的 {@link #QQ_DEB_URL_FALLBACK}</li>
     * </ol>
     * 下载 + 校验三级兜底：直链 → 签名接口 → 本地 /sdcard/Download/*.deb。
     * 每个候选都过 {@link #validateLinuxQQDeb} 三重校验（ar 头 + dpkg-deb --info/--contents + Architecture/Package）。
     */
    private static boolean installLinuxQQ(Context ctx, Env env, StringBuilder sink) {
        File deb = new File(env.tmp(), "linuxqq.deb");
        // ① 先看 assets 里有没有内置的（有就不联网）
        if (copyAssetIfExists(ctx, "linuxqq.deb", deb)) {
            append(sink, "[NapCat] 使用内置 LinuxQQ 包");
            if (!validateLinuxQQDeb(ctx, env, deb, sink)) {
                append(sink, "[NapCat] 内置 LinuxQQ 包校验失败，改走在线下载");
                //noinspection ResultOfMethodCallIgnored
                deb.delete();
            }
        }
        if (!deb.isFile() || deb.length() == 0) {
            // ② 解析在线地址：官网实时 → 写死兜底
            String live = resolveLinuxQQUrl(sink);
            String url;
            if (live != null) {
                url = live;
                append(sink, "[NapCat] 使用官网最新版 LinuxQQ 地址：" + live);
            } else {
                url = QQ_DEB_URL_FALLBACK;
                append(sink, "[NapCat] 官网配置获取失败，使用内置兜底地址：" + url);
            }
            append(sink, "[NapCat] 下载 LinuxQQ（约 200MB）…");
            boolean ok = downloadAndValidate(ctx, env, url, deb, sink);
            if (!ok) {
                // ③ 签名接口
                append(sink, "[NapCat] 直链失败，尝试腾讯签名接口…");
                String signed = signLinuxQQUrl(url, sink);
                if (signed != null) {
                    append(sink, "[NapCat] 签名地址：" + signed);
                    ok = downloadAndValidate(ctx, env, signed, deb, sink);
                }
                // ④ 如果官网地址不是兜底地址，再用兜底地址试一次
                if (!ok && !QQ_DEB_URL_FALLBACK.equals(url)) {
                    append(sink, "[NapCat] 再用兜底地址重试：" + QQ_DEB_URL_FALLBACK);
                    ok = downloadAndValidate(ctx, env, QQ_DEB_URL_FALLBACK, deb, sink);
                }
            }
            if (!ok) {
                // ⑤ 本地兜底：扫 /sdcard/Download/*.deb
                append(sink, "[NapCat] 在线下载全部失败，扫描本地 deb…");
                ok = tryLocalDeb(ctx, env, deb, sink);
            }
            if (!ok) {
                append(sink, "[NapCat] LinuxQQ 下载失败（所有途径均失败）");
                return false;
            }
        }
        append(sink, "[NapCat] 安装 LinuxQQ 及其依赖…");
        // 走 proot 里的 apt：解 deb + 补 GTK/ATK/NSS 那套（缺一个都起不来）
        String cmd = "export DEBIAN_FRONTEND=noninteractive; "
                + "dpkg --configure -a >/dev/null 2>&1; "
                + "apt-get install -y --no-install-recommends "
                + "-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold "
                + "/tmp/hosttmp/linuxqq.deb "
                + "libnss3 libgbm1 libasound2t64 libgtk-3-0t64 libatk1.0-0t64 "
                + "libatk-bridge2.0-0t64 libatspi2.0-0t64 libcups2t64 libdrm2 "
                + "libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 libxrandr2 "
                + "libpango-1.0-0 libcairo2 libnspr4 libxss1 libxtst6 libx11-xcb1 "
                + "libxcb-dri3-0 libegl1 libopengl0 xvfb xauth unzip g++ "
                + "2>&1 | tail -n 20";
        int code = runInProot(ctx, cmd, sink);
        //noinspection ResultOfMethodCallIgnored
        deb.delete();
        if (!NapCat.qqBinary(env).isFile()) {
            append(sink, "[NapCat] LinuxQQ 装完仍找不到 /opt/QQ/qq（apt 退出码 " + code + "）");
            return false;
        }
        append(sink, "[NapCat] LinuxQQ 就绪");
        return true;
    }

    /** 下载到 deb 并校验；成功返回 true。 */
    private static boolean downloadAndValidate(Context ctx, Env env, String url, File deb, StringBuilder sink) {
        if (!download(url, deb, sink)) {
            return false;
        }
        if (validateLinuxQQDeb(ctx, env, deb, sink)) {
            return true;
        }
        //noinspection ResultOfMethodCallIgnored
        deb.delete();
        return false;
    }

    /**
     * 实时查腾讯官网配置，抓最新 arm64 deb 地址。失败返回 null。
     * 等价于 AstrBot 脚本里 curl linuxConfig.js + sed 去 \/ + grep arm64 deb 的那段。
     */
    private static String resolveLinuxQQUrl(StringBuilder sink) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(QQ_CONFIG_URL);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) {
                append(sink, "[NapCat] linuxConfig.js HTTP " + code);
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    sb.append(new String(buf, 0, n, "UTF-8"));
                }
            }
// 去掉 JSON 里的 \/ 转义（配置里可能写成 https:\/\/... 的形式）
            String json = sb.toString().replace("\\/", "/");
            // 抓 https?://...[arm64|aarch64]....deb 的链接
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(https?:)?//[^\\s\"']+", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(json);
            while (m.find()) {
                String cand = m.group();
                if (!cand.toLowerCase().matches(".*(arm64|aarch64).*\\.deb.*")) {
                    continue;
                }
                if (cand.startsWith("//")) {
                    cand = "https:" + cand;
                }
                append(sink, "[NapCat] linuxConfig.js 命中：" + cand);
                return cand;
            }
            append(sink, "[NapCat] linuxConfig.js 里没找到 arm64 deb 链接");
            return null;
        } catch (Exception e) {
            append(sink, "[NapCat] 解析 linuxConfig.js 失败：" + e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 调腾讯 URL 签名接口，拿到带 sign 的地址；失败返回 null。 */
    private static String signLinuxQQUrl(String rawUrl, StringBuilder sink) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(QQ_SIGN_URL);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-oidb",
                    "{\"uint32_command\":\"0x9b8e\",\"uint32_service_type\":1}");
            String body = "{\"url\":\"" + rawUrl.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                append(sink, "[NapCat] 签名接口 HTTP " + code);
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    sb.append(new String(buf, 0, n, "UTF-8"));
                }
            }
            String json = sb.toString().replace("\\/", "/").replace("\\u0026", "&");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"url\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(json);
            if (m.find()) {
                return m.group(1).replace("\\/", "/");
            }
            append(sink, "[NapCat] 签名接口返回里没有 url 字段");
            return null;
        } catch (Exception e) {
            append(sink, "[NapCat] 签名接口调用失败：" + e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 扫本地 /sdcard/Download 下的 *.deb，挑第一个通过校验的拷到 deb。 */
    private static boolean tryLocalDeb(Context ctx, Env env, File deb, StringBuilder sink) {
        String[] dirs = {"/sdcard/Download", "/storage/emulated/0/Download"};
        for (String d : dirs) {
            File dir = new File(d);
            File[] list = dir.listFiles();
            if (list == null) {
                continue;
            }
            for (File f : list) {
                if (!f.isFile() || !f.getName().toLowerCase().endsWith(".deb")) {
                    continue;
                }
                append(sink, "[NapCat] 尝试本地包：" + f.getAbsolutePath());
                if (!copyFile(f, deb)) {
                    continue;
                }
                if (validateLinuxQQDeb(ctx, env, deb, sink)) {
                    append(sink, "[NapCat] 本地包校验通过：" + f.getAbsolutePath());
                    return true;
                }
                //noinspection ResultOfMethodCallIgnored
                deb.delete();
            }
        }
        return false;
    }

    /**
     * deb 三重校验（照 AstrBot 的 validate_linuxqq_deb）：
     * 1) 文件 > 1MB 且文件头是 "!<arch>"（deb 是 ar 归档）
     * 2) proot 里 dpkg-deb --info 和 --contents 都成功
     * 3) Architecture 属于 arm64/aarch64，Package == linuxqq
     */
    private static boolean validateLinuxQQDeb(Context ctx, Env env, File deb, StringBuilder sink) {
        if (!deb.isFile() || deb.length() < 1024L * 1024L) {
            append(sink, "[NapCat] 校验失败：文件不存在或小于 1MB（" + (deb.isFile() ? deb.length() : -1) + "）");
            return false;
        }
        byte[] head = new byte[8];
        try (InputStream in = new java.io.FileInputStream(deb)) {
            int n = in.read(head);
            if (n < 8) {
                append(sink, "[NapCat] 校验失败：文件头读不到 8 字节");
                return false;
            }
        } catch (IOException e) {
            append(sink, "[NapCat] 校验失败：读文件头异常 " + e);
            return false;
        }
        String magic = new String(head, java.nio.charset.StandardCharsets.US_ASCII);
        if (!magic.startsWith("!<arch>")) {
            append(sink, "[NapCat] 校验失败：文件头不是 ar 归档（" + magic + "）");
            return false;
        }
        // 复制到 proot 可见的 /tmp/hosttmp/linuxqq.deb 再跑 dpkg-deb
        File staged = new File(env.tmp(), "linuxqq.deb");
        if (!deb.getAbsolutePath().equals(staged.getAbsolutePath())) {
            if (!copyFile(deb, staged)) {
                append(sink, "[NapCat] 校验失败：无法复制到 tmp");
                return false;
            }
        }
        String cmd = "dpkg-deb --info /tmp/hosttmp/linuxqq.deb >/dev/null 2>&1 "
                + "&& dpkg-deb --contents /tmp/hosttmp/linuxqq.deb >/dev/null 2>&1 "
                + "&& echo ARCH=$(dpkg-deb -f /tmp/hosttmp/linuxqq.deb Architecture 2>/dev/null) "
                + "&& echo PKG=$(dpkg-deb -f /tmp/hosttmp/linuxqq.deb Package 2>/dev/null)";
        StringBuilder out = new StringBuilder();
        int code = runInProot(ctx, cmd, out);
        String res = out.toString();
        if (code != 0) {
            append(sink, "[NapCat] 校验失败：dpkg-deb 退出码 " + code);
            return false;
        }
        String arch = field(res, "ARCH=");
        String pkg = field(res, "PKG=");
        append(sink, "[NapCat] deb 元信息 Architecture=" + arch + " Package=" + pkg);
        if (!"arm64".equalsIgnoreCase(arch) && !"aarch64".equalsIgnoreCase(arch)) {
            append(sink, "[NapCat] 校验失败：Architecture 不是 arm64/aarch64（" + arch + "）");
            return false;
        }
        if (!"linuxqq".equalsIgnoreCase(pkg)) {
            append(sink, "[NapCat] 校验失败：Package 不是 linuxqq（" + pkg + "）");
            return false;
        }
        return true;
    }

    /** 从命令输出里取 "KEY=value" 一行的 value。 */
    private static String field(String text, String key) {
        int i = text.indexOf(key);
        if (i < 0) {
            return "";
        }
        int s = i + key.length();
        int e = text.indexOf('\n', s);
        if (e < 0) {
            e = text.length();
        }
        return text.substring(s, e).trim();
    }

    /** 简单文件拷贝。 */
    private static boolean copyFile(File src, File dst) {
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return true;
        } catch (IOException e) {
            Log.w(TAG, "copyFile failed", e);
            return false;
        }
    }

    // ------------------------------------------------------------ NapCat

    private static boolean installNapCatShell(Context ctx, Env env, StringBuilder sink) {
        File zip = new File(env.tmp(), "napcat.zip");
        // 记下这一版是从哪来的：内置包有确定的版本号，在线下载要现场去查 tag
        boolean builtIn = false;
        if (!copyAssetIfExists(ctx, "napcat.zip", zip)) {
            append(sink, "[NapCat] 下载 NapCat.Shell…");
            String proxy = Mirror.resolvedPrefix(ctx);
            String[] urls = (proxy == null || proxy.isEmpty() || "auto".equals(proxy))
                    ? new String[]{NAPCAT_ZIP_URL}
                    : new String[]{proxy + NAPCAT_ZIP_URL, NAPCAT_ZIP_URL};
            boolean ok = false;
            for (String u : urls) {
                if (download(u, zip, sink)) {
                    ok = true;
                    break;
                }
                append(sink, "[NapCat] 这个源失败，换下一个");
            }
            if (!ok) {
                append(sink, "[NapCat] NapCat 下载失败，可在「加速器」里换代理重试");
                return false;
            }
        } else {
            builtIn = true;
            append(sink, "[NapCat] 使用内置 NapCat 包（" + BUNDLED_NAPCAT_VERSION + "）");
        }
        append(sink, "[NapCat] 解压 NapCat…");
        File dest = NapCat.dir(env);
        //noinspection ResultOfMethodCallIgnored
        dest.mkdirs();
        int code = runInProot(ctx, "mkdir -p /root/napcat && "
                + "unzip -q -o /tmp/hosttmp/napcat.zip -d /root/napcat 2>&1 | tail -n 10", sink);
        //noinspection ResultOfMethodCallIgnored
        zip.delete();
        if (!new File(dest, "napcat.mjs").isFile()) {
            append(sink, "[NapCat] 解压后找不到 napcat.mjs（unzip 退出码 " + code + "）");
            return false;
        }
        // 记下这次装的是哪一版。
        //
        // 必须在解压成功之后写：写早了如果解压失败，会留下一个「声称是 4.18.41
        // 但文件其实不全」的标记，界面就会一直显示已是最新，反而更误导。
        String installed = builtIn ? BUNDLED_NAPCAT_VERSION : fetchLatestNapCatVersion(sink);
        if (!installed.isEmpty()) {
            NapCat.writeInstalledVersion(env, installed);
            append(sink, "[NapCat] 版本标记：" + installed);
        }
        return true;
    }

    /**
     * 从 GitHub 的 releases/latest 重定向地址里抠出版本号，给在线安装用。
     * <p>
     * 为什么不用包内的 package.json：那个字段是 "0.0.1"，永远是占位符。
     * 只能问上游「最新的 tag 是什么」，那才是这次真正下到的版本。
     */
    private static String fetchLatestNapCatVersion(StringBuilder sink) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL("https://api.github.com/repos/NapNeko/NapCatQQ/releases/latest");
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "maibot-android");
            if (conn.getResponseCode() != 200) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    sb.append(new String(buf, 0, n, "UTF-8"));
                }
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"tag_name\"\\s*:\\s*\"v?(\\d+\\.\\d+\\.\\d+)\"")
                    .matcher(sb.toString());
            return m.find() ? m.group(1) : "";
        } catch (Exception e) {
            return "";
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ------------------------------------------------------------ 注入库

    private static void installLauncher(Context ctx, Env env, StringBuilder sink) {
        // 优先用内置的预编译版本。
        //
        // 为什么不再现场编译：原来这一步是「curl 拉 launcher.cpp + g++ 编译」，
        // 两个前提都很脆 —— GitHub raw 在国内经常连不上，apt 装 g++ 又要联网。
        // 内置之后整个 NapCat 安装流程彻底不需要网络，拔了卡也能装完。
        File dest = NapCat.launcher(env);
        File tmp = new File(env.tmp(), "libnapcat_launcher.so");
        if (copyAssetIfExists(ctx, "libnapcat_launcher.so", tmp)) {
            append(sink, "[NapCat] 使用内置注入库");
            try {
                java.nio.file.Files.copy(tmp.toPath(), dest.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                //noinspection ResultOfMethodCallIgnored
                dest.setExecutable(true, false);
            } catch (Exception e) {
                append(sink, "[NapCat] 释放内置注入库失败：" + e);
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            if (dest.isFile()) {
                append(sink, "[NapCat] 注入库就绪（内置）");
                return;
            }
        }
        // 兜底：内置的没有/拷不过去，才回退到现场编译
        append(sink, "[NapCat] 内置注入库不可用，改为现场编译…");
        runInProot(ctx, "cd /root && "
                + "curl -fsSL --max-time 90 -o launcher.cpp " + LAUNCHER_CPP_URL + " && "
                + "g++ -shared -fPIC launcher.cpp -o libnapcat_launcher.so -ldl && "
                + "ls -l libnapcat_launcher.so 2>&1", sink);
        if (!NapCat.launcher(env).isFile()) {
            append(sink, "[NapCat] 注入库编译失败（缺 g++ 或网络不通）");
        } else {
            append(sink, "[NapCat] 注入库就绪");
        }
    }

    // ------------------------------------------------------------ OneBot 配置

    /**
     * 写 OneBot 配置：NapCat 作正向 WS 服务端，监听 127.0.0.1:3001。
     * <p>
     * 为什么是 websocketServers 而不是 websocketClients：
     * 麦麦的 OneBot 适配器是「客户端」，它会主动去连 host:port。
     * 所以 NapCat 这边必须当服务端。AstrBot 那边用的反而是 clients，
     * 因为它是 AstrBot 自己当服务端、NapCat 连过去，方向相反。
     */
    private static void writeOneBotConfig(Env env, StringBuilder sink) {
        File cfg = new File(NapCat.dir(env), "config/onebot11.json");
        if (cfg.isFile() && cfg.length() > 50) {
            // 已有配置就不覆盖，免得把用户手改的端口/token 冲掉
            append(sink, "[NapCat] OneBot 配置已存在，保持原样");
            return;
        }
        File cfgDir = cfg.getParentFile();
        if (cfgDir != null) {
            //noinspection ResultOfMethodCallIgnored
            cfgDir.mkdirs();
        }
        String json = "{\n"
                + "  \"network\": {\n"
                + "    \"httpServers\": [],\n"
                + "    \"httpClients\": [],\n"
                + "    \"websocketServers\": [\n"
                + "      {\n"
                + "        \"name\": \"MaiBot\",\n"
                + "        \"enable\": true,\n"
                + "        \"host\": \"127.0.0.1\",\n"
                + "        \"port\": " + NapCat.ONEBOT_PORT + ",\n"
                + "        \"messagePostFormat\": \"array\",\n"
                + "        \"reportSelfMessage\": false,\n"
                + "        \"enableForcePushEvent\": true,\n"
                + "        \"token\": \"" + NapCat.ONEBOT_TOKEN + "\",\n"
                + "        \"debug\": false,\n"
                + "        \"heartInterval\": 30000\n"
                + "      }\n"
                + "    ],\n"
                + "    \"websocketClients\": [],\n"
                + "    \"httpSseServers\": [],\n"
                + "    \"plugins\": []\n"
                + "  },\n"
                + "  \"musicSignUrl\": \"\",\n"
                + "  \"enableLocalFile2Url\": false,\n"
                + "  \"parseMultMsg\": false\n"
                + "}\n";
        try (OutputStream out = new FileOutputStream(cfg)) {
            out.write(json.getBytes("UTF-8"));
            append(sink, "[NapCat] 已写 OneBot 配置：127.0.0.1:" + NapCat.ONEBOT_PORT);
        } catch (IOException e) {
            append(sink, "[NapCat] 写 OneBot 配置失败: " + e);
        }
    }

    // ------------------------------------------------------------ 工具

    /** 在 proot 里跑一条命令，把输出拼进 sink。 */
    private static int runInProot(Context ctx, String script, StringBuilder sink) {
        try {
            ProotRunner r = new ProotRunner(ctx);
            return r.execShell(script, 600_000, sink);
        } catch (Exception e) {
            append(sink, "[NapCat] 执行失败: " + e);
            return -1;
        }
    }

    /** assets 里有就复制到 dest，返回是否复制成功。 */
    private static boolean copyAssetIfExists(Context ctx, String asset, File dest) {
        try (InputStream in = ctx.getAssets().open(asset);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return dest.length() > 1024;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean download(String urlStr, File dest, StringBuilder sink) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(20_000);
            c.setReadTimeout(120_000);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                append(sink, "[NapCat] HTTP " + code + " ← " + urlStr);
                return false;
            }
            long total = c.getContentLength();
            try (InputStream in = new BufferedInputStream(c.getInputStream(), 128 * 1024);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[128 * 1024];
                int n;
                long got = 0;
                int lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    if (total > 0) {
                        int pct = (int) (got * 100 / total);
                        if (pct / 10 != lastPct / 10) {
                            lastPct = pct;
                            append(sink, "[NapCat] 下载 " + pct + "%（"
                                    + (got / 1024 / 1024) + "/" + (total / 1024 / 1024) + " MB）");
                        }
                    }
                }
            }
            return dest.length() > 1024;
        } catch (Exception e) {
            append(sink, "[NapCat] 下载失败: " + e);
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static void append(StringBuilder sink, String line) {
        Log.i(TAG, line);
        if (sink != null) {
            sink.append(line).append('\n');
        }
    }
}