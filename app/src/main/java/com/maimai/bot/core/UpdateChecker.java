package com.maimai.bot.core;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检查 NapCat / 麦麦有没有新版本。
 * <p>
 * 为什么要做：环境清单每次展开都重新 stat 一遍文件，但「文件在」不等于「是最新版」。
 * 用户之前遇到的就是这个：麦麦停在 1.3.15，界面上绿灯一直亮着，看不出该更新。
 * 现在展开环境管理时顺手查一次上游，有新版本就在对应那一行的说明里直接标出来。
 * <p>
 * 两个组件的版本来源完全不同，所以分两条路：
 * <ul>
 *   <li><b>麦麦</b>：本地版本从 site-packages 里的 dist-info 目录名读（maibot-1.3.18.dist-info），
 *       上游版本查 PyPI 的 JSON 接口。两边都是现成的标准格式，不用猜。</li>
 *   <li><b>NapCat</b>：本地版本从 root/napcat/package.json 的 version 字段读，
 *       上游版本查 GitHub releases 的 latest 跳转（和 NapCatInstaller 下载用的是同一个地址）。</li>
 * </ul>
 * 所有检查都在后台线程跑，结果回主线程再回调，不阻塞界面。
 */
public final class UpdateChecker {

    /** 一次检查的结果。 */
    public static final class Info {
        /** 本地已装版本，读不到就是空串。 */
        public final String local;
        /** 上游最新版本，查不到就是空串。 */
        public final String latest;
        /** 是否确实有更新。只有两边版本都拿到、且不同，才算有更新。 */
        public final boolean hasUpdate;

        Info(String local, String latest, boolean hasUpdate) {
            this.local = local;
            this.latest = latest;
            this.hasUpdate = hasUpdate;
        }

        /** 给界面用的一句话：有更新就说清楚从哪到哪，没更新就说已是最新。 */
        public String describe() {
            if (local.isEmpty() && latest.isEmpty()) {
                return "";
            }
            if (hasUpdate) {
                return "可更新 " + local + " → " + latest;
            }
            if (!latest.isEmpty()) {
                return "已是最新 " + local;
            }
            return "";
        }
    }

    /** 检查完的回调，在主线程触发。参数可能为 null（那项没查到）。 */
    public interface Callback {
        void onResult(Info maibot, Info napcat, Info linuxqq);
    }

    /**
     * LinuxQQ 官网实时配置地址。
     * <p>
     * 这个地址是 NapCatInstaller 里同一个 —— 它用 JSONP 返回各架构最新 deb 的直链，
     * 开头就带着 "version":"3.2.34" 这样的版本号，所以查版本号不用真下 deb。
     * 换地址的话两边要一起改，否则 UI 显示的版本和实际装的会不一致。
     */
    private static final String QQ_CONFIG_URL =
            "https://cdn-go.cn/qq-web/im.qq.com_new/latest/rainbow/linuxConfig.js";

    private static final Pattern SEMVER = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final Pattern NAPCAT_TAG = Pattern.compile("v?(\\d+\\.\\d+\\.\\d+)");

    private UpdateChecker() {
    }

    /**
     * 后台查一次麦麦和 NapCat 的版本情况，查完回主线程回调。
     * 网络不通、文件缺失都不会抛异常，最多返回空 Info，界面照常显示。
     */
    public static void checkAsync(final Env env, final Callback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Info mb;
            Info nc;
            Info qq;
            try {
                mb = checkMaibot(env);
            } catch (Throwable t) {
                mb = new Info("", "", false);
            }
            try {
                nc = checkNapCat(env);
            } catch (Throwable t) {
                nc = new Info("", "", false);
            }
            try {
                qq = checkLinuxQQ(env);
            } catch (Throwable t) {
                qq = new Info("", "", false);
            }
            final Info fmb = mb;
            final Info fnc = nc;
            final Info fqq = qq;
            main.post(() -> cb.onResult(fmb, fnc, fqq));
        }, "update-check").start();
    }

    // ------------------------------------------------------------------ 麦麦

    /** 本地版本读 dist-info 目录名，上游版本读 PyPI。 */
    private static Info checkMaibot(Env env) {
        String local = readMaibotLocal(env);
        String latest = fetchMaibotLatest();
        boolean newer = !local.isEmpty() && !latest.isEmpty() && compare(latest, local) > 0;
        return new Info(local, latest, newer);
    }

    /**
     * 从 site-packages 里找 maibot-<版本>.dist-info。
     * <p>
     * 版本目录可能不止一个（升级残留），取最大的那个 —— 那才是实际生效的。
     */
    private static String readMaibotLocal(Env env) {
        File sp = maibotSitePackages(env);
        if (sp == null || !sp.isDirectory()) {
            return "";
        }
        File[] kids = sp.listFiles();
        if (kids == null) {
            return "";
        }
        String best = "";
        for (File f : kids) {
            String name = f.getName();
            if (!name.startsWith("maibot-") || !name.endsWith(".dist-info")) {
                continue;
            }
            String v = name.substring("maibot-".length(), name.length() - ".dist-info".length());
            if (!SEMVER.matcher(v).find()) {
                continue;
            }
            if (best.isEmpty() || compare(v, best) > 0) {
                best = v;
            }
        }
        return best;
    }

    /** site-packages 的路径随 python 小版本变化，这里扫一遍找。 */
    private static File maibotSitePackages(Env env) {
        File tools = new File(env.rootfs(),
                "root/.local/share/uv/tools/maibot/lib");
        File[] pys = tools.listFiles();
        if (pys == null) {
            return null;
        }
        for (File py : pys) {
            if (!py.getName().startsWith("python")) {
                continue;
            }
            File sp = new File(py, "site-packages");
            if (sp.isDirectory()) {
                return sp;
            }
        }
        return null;
    }

    /** PyPI 的 info.version 是稳定字段，直接从 JSON 里抠出来，不引第三方库。 */
    private static String fetchMaibotLatest() {
        String body = httpGet("https://pypi.org/pypi/maibot/json", 8000);
        if (body == null) {
            return "";
        }
        Matcher m = Pattern.compile("\"version\"\\s*:\\s*\"([0-9]+\\.[0-9]+\\.[0-9]+)\"").matcher(body);
        return m.find() ? m.group(1) : "";
    }

    // ---------------------------------------------------------------- NapCat

    /** 本地版本读 package.json，上游版本读 GitHub releases/latest 的重定向地址。 */
    private static Info checkNapCat(Env env) {
        String local = readNapCatLocal(env);
        String latest = fetchNapCatLatest();
        boolean newer = !local.isEmpty() && !latest.isEmpty() && compare(latest, local) > 0;
        return new Info(local, latest, newer);
    }

    /**
     * 本地 NapCat 版本。
     * <p>
     * 绝对不要读 napcat/package.json —— 那个 version 字段上游从来不维护，
     * 4.18.41 的官方发行包里也还是 "0.0.1"。之前就是读了它，
     * 界面才会显示「可更新 0.0.1 → 4.18.41」这种莫名其妙的结果。
     * <p>
     * 改成走 NapCat.detectVersion()：先读安装时写下的 .napcat_version 标记，
     * 没有再翻启动日志里的 "NapCat.Core Version: x.y.z"。
     */
    private static String readNapCatLocal(Env env) {
        return NapCat.detectVersion(env);
    }

    /**
     * 上游最新版本走 GitHub API 的 releases/latest，读 tag_name。
     * <p>
     * 为什么不用 releases/latest 那个页面跳转：在真机上实测过，
     * github.com 主站经常连不上（curl 直接 code=000 超时），
     * 而 api.github.com 是通的。既然下载 zip 时 GitHub 会自己 302 到
     * objects.githubusercontent.com，用 API 查版本号这条路更稳。
     * <p>
     * 拿不到就返回空串 —— 界面显示「已是最新」以外什么都不显示，
     * 不会因为没网就报错弹窗。
     */
    private static String fetchNapCatLatest() {
        String body = httpGet("https://api.github.com/repos/NapNeko/NapCatQQ/releases/latest", 10000);
        if (body == null) {
            return "";
        }
        Matcher m = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([0-9]+\\.[0-9]+\\.[0-9]+)\"").matcher(body);
        return m.find() ? m.group(1) : "";
    }

    // ----------------------------------------------------------------- 工具

    // --------------------------------------------------------------- LinuxQQ

    /**
     * 检查 LinuxQQ 有没有新版。
     * <p>
     * 本地版本读 /opt/QQ/resources/app/package.json 的 version 字段，
     * 形如 "3.2.21-42086"（前面的 3.2.21 是版本，后面是构建号）。
     * 上游版本读腾讯官网那个 JSONP 配置，开头就有 "version":"3.2.34"。
     * <p>
     * 为什么单独查 LinuxQQ：它是 NapCat 的底座，QQ 太旧会导致
     * 注入库对不上、登录流程异常。之前一直没地方看它版本，
     * 只能靠出错时猜。现在展开环境管理就能看到有没有新版。
     */
    private static Info checkLinuxQQ(Env env) {
        String local = readLinuxQQLocal(env);
        String latest = fetchLinuxQQLatest();
        boolean newer = !local.isEmpty() && !latest.isEmpty() && compare(latest, local) > 0;
        return new Info(local, latest, newer);
    }

    /**
     * 从 /opt/QQ/resources/app/package.json 读版本。
     * <p>
     * 那个 version 是 "3.2.21-42086" 这种带构建号的格式，
     * 只取前半段数字部分来比较，构建号不参与版本判断。
     */
    private static String readLinuxQQLocal(Env env) {
        File pkg = new File(env.rootfs(), "opt/QQ/resources/app/package.json");
        if (!pkg.isFile()) {
            return "";
        }
        String body = readText(pkg);
        if (body == null) {
            return "";
        }
        Matcher m = Pattern.compile("\"version\"\\s*:\\s*\"(\\d+\\.\\d+\\.\\d+)").matcher(body);
        return m.find() ? m.group(1) : "";
    }

    /** 上游版本直接从 linuxConfig.js 里抠 "version":"x.y.z"。 */
    private static String fetchLinuxQQLatest() {
        String body = httpGet(QQ_CONFIG_URL, 12000);
        if (body == null) {
            return "";
        }
        Matcher m = Pattern.compile("\"version\"\\s*:\\s*\"(\\d+\\.\\d+\\.\\d+)\"").matcher(body);
        return m.find() ? m.group(1) : "";
    }

    private static String httpGet(String spec, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(spec);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "maibot-android");
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readText(File f) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按数字段比较 a.b.c，a 大于 b 返回正数。 */
    static int compare(String a, String b) {
        int[] x = parts(a);
        int[] y = parts(b);
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) {
                return x[i] - y[i];
            }
        }
        return 0;
    }

    private static int[] parts(String v) {
        int[] out = new int[3];
        Matcher m = SEMVER.matcher(v == null ? "" : v);
        if (m.find()) {
            for (int i = 0; i < 3; i++) {
                try {
                    out[i] = Integer.parseInt(m.group(i + 1));
                } catch (NumberFormatException ignored) {
                    out[i] = 0;
                }
            }
        }
        return out;
    }
}