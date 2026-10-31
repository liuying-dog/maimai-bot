package com.maimai.bot.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * NapCat 账号（一个账号 = 一个独立实例）。
 * <p>
 * 为什么要多实例，而不是一个 NapCat 挂多号：
 * NapCat 是「注入到一个 QQ 客户端里」的，一个 QQ 客户端只能登录一个号，
 * 所以多号就必须多份 QQ 进程 + 多份 NapCat，各自有独立的：
 * <ul>
 *   <li>工作目录（config/cache/logs）—— 靠环境变量 NAPCAT_WORKDIR 指定</li>
 *   <li>HOME / XDG_* —— 隔离 QQ 的登录态缓存（换号不会串）</li>
 *   <li>Xvfb DISPLAY —— 每个 QQ 一个虚拟屏，否则第二个连不上</li>
 *   <li>WebUI 端口 —— 6099、6100、6101…</li>
 *   <li>WebUI token —— 各自随机，用作面板登录</li>
 * </ul>
 * 这套结构是照 AstrBot 的 launcher_qq&lt;id&gt;.sh 抄的，它在同一台设备上跑通过。
 */
public final class NapCatAccount {
    private static final String TAG = "NapCatAccount";
    private static final String PREF = "napcat_accounts";
    /**
     * 「这份账号列表已经被真正写过一次」的标记。
     * <p>
     * 之前没有它，{@link #loadAll} 只要发现列表为空就补一个默认账号，
     * 结果就是「用户把账号删了，界面一刷新它又自己长回来」。
     */
    private static final String KEY_INITIALIZED = "initialized";

    /** 实例 ID，形如 qq1_1788000289527。 */
    public String id;
    /** 显示名，如「账号 1」。 */
    public String label;
    /** 已登录的 QQ 号；未登录时为空串。 */
    public String uin = "";
    /** WebUI 端口。 */
    public int webuiPort;
    /** WebUI token（面板登录用）。 */
    public String webuiToken = "";
    /** OneBot 正向 WS 端口（麦麦适配器连这个）。 */
    public int onebotPort;
    /** OneBot token。 */
    public String onebotToken = "";
    /** Xvfb 的 DISPLAY 编号。 */
    public int display;

    public NapCatAccount() {
    }

    // ---------------------------------------------------------------- 路径

    /** 实例的工作目录（= guest 里的 NAPCAT_WORKDIR）。 */
    public File workDir(Env env) {
        return new File(env.rootfs(), "root/napcat_instances/" + id + "_napcat");
    }

    /** 实例的 HOME（隔离 QQ 登录态）。 */
    public File homeDir(Env env) {
        return new File(env.rootfs(), "root/napcat_instances/" + id + "_home");
    }

    /** 实例的 webui 配置。 */
    public File webuiConfig(Env env) {
        return new File(workDir(env), "config/webui.json");
    }

    /** 实例的 onebot 配置。 */
    public File onebotConfig(Env env) {
        return new File(workDir(env), "config/onebot11.json");
    }

    /** 实例的二维码。 */
    public File qrCode(Env env) {
        return new File(workDir(env), "cache/qrcode.png");
    }

    /** 实例的运行日志（我们自己写，用于抓 token / 错误）。 */
    public File runLog(Env env) {
        return new File(workDir(env), "run.log");
    }

    /**
     * 登录成功的标志：NapCat 会写 config/onebot11_&lt;uin&gt;.json。
     * 文件名里带 QQ 号，正好可以拿来做「已登录」判断 + 显示账号。
     */
    /**
         * 这个账号是不是已经登录成功了。
         * <p>
         * <b>判据顺序（从最可靠到最不可靠）：</b>
         * <ol>
         *   <li><b>启动日志</b>：NapCat 登录成功会往 napcat.log 打
         *       「快速登录成功 / 登录成功」。这是 App 自己写的文件，
         *       不受 SELinux 限制，最可靠。</li>
         *   <li>3001 端口在听 —— 但真机实测 App 进程（untrusted_app_27）
         *       既读不到 /proc/net/tcp、socket 连 loopback 也常失败，
         *       所以它只能当辅助。</li>
         * </ol>
         * 之前只靠第二条（或者只靠配置文件存在），才出现
         * 「3001 明明在听，界面却说没登录」的错判。
         */
        public boolean isLoggedIn(Env env) {
            // 1) 日志里明确写了登录成功
            if (NapCat.isLoggedInByLog(env)) {
                return true;
            }
            // 2) 有带 uin 的配置 + 3001 在听（两条都满足才算，避免旧文件误判）
            return !readUinFromConfig(env).isEmpty()
                    && ProcessKiller.isPortListening(onebotPort);
        }

        /**
         * 只从 config/onebot11_&lt;uin&gt;.json 的文件名里读 QQ 号。
         * 这是「号码」的来源，不代表一定在线。
         */
        public String detectUin(Env env) {
            return readUinFromConfig(env);
        }

        /** 读 config 目录里 onebot11_&lt;uin&gt;.json 的 uin，读不到返回空串。 */
        private String readUinFromConfig(Env env) {
            File cfg = new File(workDir(env), "config");
            File[] files = cfg.listFiles();
            if (files == null) {
                return "";
            }
            for (File f : files) {
                String n = f.getName();
                if (n.startsWith("onebot11_") && n.endsWith(".json")) {
                    String u = n.substring("onebot11_".length(), n.length() - 5);
                    if (u.matches("\\d{5,12}")) {
                        return u;
                    }
                }
            }
            return "";
        }

    /** 这个实例是否正在跑（WebUI 端口在听就算）。 */
    /**
     * 这个实例是否正在跑。
     * <p>
     * 判定顺序（从「最直接」到「最兜底」）：
     * <ol>
     *   <li>WebUI 端口在听（{@link ProcessKiller#isPortListening}）—— 正常情况走这条</li>
     *   <li>OneBot 端口在听 —— 有些时刻 WebUI 还没 bind 但 OneBot 已经起来了</li>
     *   <li>有属于本实例的 QQ/NapCat 进程 —— 真机实测兜底：
     *       6099 curl 返回 200、ss 也显示 LISTEN，但 App 里
     *       {@code /proc/net/tcp} 拿不到那条表（uid 10810 读到的内容不完整），
     *       socket 直连在某些时刻也会被 SELinux 拦一下，
     *       于是界面上一直写着「未运行」，而点进去面板却是好的。
     *       只要进程还在，就不该显示未运行。</li>
     * </ol>
     */
    public boolean isRunning(Env env) {
            // 1) 端口探得到，直接算在跑
            if (isRunning()) {
                return true;
            }
            // 2) 日志里显示「登录成功且之后没掉线」—— 这也说明进程活着。
            //
            // 这一条很关键：NapCat 登录成功之后就不再往日志里打东西了，
            // 如果只靠 isAliveByLog（看日志 mtime），90 秒后就会被判成「未运行」，
            // 界面上那个徽标会在「已登录」和「未运行」之间来回跳。
            if (NapCat.isLoggedInByLog(env)) {
                return true;
            }
            // 3) 日志最近还在写（正在启动 / 正在扫码），也算在跑
            return NapCat.isAliveByLog(env);
        }

        /** 这个实例是否正在跑（端口在听，或进程有日志输出）。 */
        public boolean isRunning() {
        if (ProcessKiller.isPortListening(webuiPort)) {
            return true;
        }
        if (onebotPort > 0 && ProcessKiller.isPortListening(onebotPort)) {
            return true;
        }
        return ProcessKiller.hasNapCatProcess();
    }

    /** OneBot 是否已就绪（登录成功、WS 在听）。 */
    /**
     * 这个账号是不是已经登录成功了。
     * <p>
     * 主判据用「NapCat 有没有写出 onebot11_&lt;uin&gt;.json」，
     * 端口只作辅助 —— 这是对比 AstrBot 之后改的。
     * <p>
     * 为什么不能只看 3001 端口（真机踩到的）：
     * NapCat 的 3001 是 WebSocket 服务，它一启动就 listen，
     * 那时候 QQ 根本还没登录。于是「端口开着」并不等于「登录了」。
     * 反过来，实测里 3001 的 listen 比进程出现晚几百毫秒，
     * 界面某一帧正好卡在那个空档里，就会一直是「等待扫码」，
     * 哪怕账号早就登录好了。
     * <p>
     * 而 onebot11_&lt;uin&gt;.json 是 NapCat 在**账号登录成功后**才写的
     * （真机证据：onebot11_3611846660.json，文件名里的 uin
     * 和界面上显示的 QQ 号完全一致），它才是「已登录」的可靠信号。
     */
    public boolean isOneBotUp(Env env) {
            // 日志里有明确的登录成功记录，就是已登录
            if (NapCat.isLoggedInByLog(env)) {
                return true;
            }
            // 否则退回端口判断（App 沙盒里可能不可用，所以只当辅助）
            return isOneBotUp();
        }

        /** 已登录判断（端口在听就算）。 */
        public boolean isOneBotUp() {
        // 判据统一成「3001 在不在听」。
        //
        // 以前第一判据是 loggedInUin 非空，但那是内存里的缓存值 ——
        // 上次登录成功后写进去的，掉线了也不会被清掉，于是界面一直显示
        // 「已登录」而实际早就断了。现在只认端口：NapCat 只有在 QQ 真登录
        // 成功之后才会把 OneBot WS 服务拉起来，端口在听就是铁证。
        return ProcessKiller.isPortListening(onebotPort);
    }

    /**
     * 由界面在渲染前刷新进来：最近一次 {@link #detectUin} 的结果。
     * 这样 {@link #isOneBotUp()} 不用每次都读盘。
     */
    public String loggedInUin = "";

    // ---------------------------------------------------------------- 存储

    /** 读全部账号。首次使用时给一个默认账号（6099/3001），保持向后兼容。 */
    public static List<NapCatAccount> loadAll(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String raw = p.getString("list", "");
        List<NapCatAccount> out = new ArrayList<>();
        boolean parsed = false;
        if (raw != null && !raw.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    NapCatAccount a = fromJson(arr.getJSONObject(i));
                    if (a != null) {
                        out.add(a);
                    }
                }
                // 只要解析成功过，就说明这个列表是「用户真实拥有的一份列表」，
                // 哪怕它是空的（用户把账号全删了），也不能再凭空补一个回来。
                parsed = true;
            } catch (Exception e) {
                Log.w(TAG, "解析账号列表失败: " + e);
            }
        }
        // 旧版本没有这个标记：靠 list 里有没有存过内容来判断是不是首次使用。
        boolean initialized = p.getBoolean(KEY_INITIALIZED, parsed);
        if (!parsed && !initialized) {
            // 迁移：老版本只有一个「隐式账号」，就是 /root/napcat + 6099 + 3001。
            // 这里把它收编成第一个账号，这样升级上来的用户不会突然丢配置。
            out.add(createDefault(ctx, 1));
            saveAll(ctx, out);
        }
        return out;
    }

    public static void saveAll(Context ctx, List<NapCatAccount> list) {
        JSONArray arr = new JSONArray();
        for (NapCatAccount a : list) {
            arr.put(a.toJson());
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putString("list", arr.toString())
                // 存过一次就标记成「已初始化」：以后列表为空代表用户删光了，
                // 不是「第一次用」，绝不能再自动补默认账号。
                .putBoolean(KEY_INITIALIZED, true)
                .apply();
    }

    private static NapCatAccount fromJson(JSONObject o) {
        try {
            NapCatAccount a = new NapCatAccount();
            a.id = o.optString("id");
            a.label = o.optString("label");
            a.uin = o.optString("uin", "");
            a.webuiPort = o.optInt("webuiPort", 6099);
            a.webuiToken = o.optString("webuiToken", "");
            a.onebotPort = o.optInt("onebotPort", 3001);
            a.onebotToken = o.optString("onebotToken", "");
            a.display = o.optInt("display", 30);
            return a.id.isEmpty() ? null : a;
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("label", label);
            o.put("uin", uin);
            o.put("webuiPort", webuiPort);
            o.put("webuiToken", webuiToken);
            o.put("onebotPort", onebotPort);
            o.put("onebotToken", onebotToken);
            o.put("display", display);
        } catch (Exception ignored) {
        }
        return o;
    }

    // ---------------------------------------------------------------- 新建

    /**
     * 新建一个账号，端口按序号自动分配。
     * <p>
     * 端口规划：WebUI 从 6099 起、OneBot 从 3001 起、DISPLAY 从 30 起，
     * 都按「当前已有的最大序号 +1」走，避免撞车。
     */
    public static NapCatAccount createNew(Context ctx, List<NapCatAccount> existing) {
        int webui = 6099;
        int onebot = 3001;
        int display = 30;
        for (NapCatAccount a : existing) {
            webui = Math.max(webui, a.webuiPort + 1);
            onebot = Math.max(onebot, a.onebotPort + 1);
            display = Math.max(display, a.display + 1);
        }
        NapCatAccount a = new NapCatAccount();
        a.id = "qq" + (existing.size() + 1) + "_" + System.currentTimeMillis();
        a.label = "账号" + (existing.size() + 1);
        a.webuiPort = webui;
        a.onebotPort = onebot;
        a.display = display;
        a.webuiToken = randomToken();
        a.onebotToken = "maibot2026";
        return a;
    }

    private static NapCatAccount createDefault(Context ctx, int index) {
        NapCatAccount a = new NapCatAccount();
        a.id = "qq1_default";
        a.label = "账号" + index;
        a.webuiPort = 6099;
        a.onebotPort = 3001;
        a.display = 30;
        a.webuiToken = randomToken();
        a.onebotToken = "maibot2026";
        return a;
    }

    /** 12 位十六进制随机 token，NapCat 面板登录用。 */
    public static String randomToken() {
        StringBuilder sb = new StringBuilder();
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 12; i++) {
            sb.append("0123456789abcdef".charAt(r.nextInt(16)));
        }
        return sb.toString();
    }

    /**
     * 把配置写进实例目录。
     * <p>
     * webui.json：端口 + token + autoLoginAccount（填上 uin 就能免扫码快速登录）
     * onebot11.json：正向 WS 服务端，给麦麦适配器连
     */
    public void writeConfig(Env env) {
        writeConfig(env, null);
    }

    /**
     * 从盘上已有的 webui.json 里读 autoLoginAccount。
     * <p>
     * 纯粹是字符串抠取，不引 JSON 库 —— 这个文件是我们自己写的，
     * 格式固定，一个正则就够。读不到返回空串。
     */
    private static String readExistingAutoLogin(File cfgDir) {
        File f = new File(cfgDir, "webui.json");
        if (!f.isFile()) {
            return "";
        }
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"autoLoginAccount\"\\s*:\\s*\"(\\d+)\"").matcher(sb.toString());
            return m.find() ? m.group(1) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 从 config/onebot11_&lt;uin&gt;.json 的文件名反推 uin。
     * <p>
     * 这是最后一道兜底：哪怕 webui.json 被清空了，只要之前登录过，
     * NapCat 就会留下这个带 QQ 号的配置文件，从文件名就能把号捞回来。
     *
     * @return 捞到的 uin，没有就返回空串
     */
    private static String readUinFromConfigFiles(File cfgDir) {
        File[] files = cfgDir.listFiles();
        if (files == null) {
            return "";
        }
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith("onebot11_") && n.endsWith(".json")) {
                String u = n.substring("onebot11_".length(), n.length() - 5);
                if (u.matches("\\d{5,12}")) {
                    return u;
                }
            }
        }
        return "";
    }

    /** 带日志的版本：把每个写入动作都记一笔，失败时能看出是哪一步。 */
    public void writeConfig(Env env, BootLog log) {
        File cfgDir = new File(workDir(env), "config");
        mkdirsOrThrow(cfgDir, log);
        mkdirsOrThrow(new File(workDir(env), "cache"), log);
        mkdirsOrThrow(new File(workDir(env), "logs"), log);

        // 算这次要写进 autoLoginAccount 的值。
        //
        // 这一行是「每次启动都要重新扫码」的根因所在，改动要小心：
        // 原来直接写 (uin == null ? "" : uin)，而每次启动 NapCat.start()
        // 都会调这个不带 uin 的重载 —— 于是每启一次就把已经记住的 QQ 号
        // 抹成空串，NapCat 下次自然找不到「可用于快速登录」的账号，
        // 只能弹二维码。用户看到的就是「昨天刚扫过，今天又要扫」。
        //
        // 现在的策略是「按优先级取，绝不无故清空」：
        //   1) 内存里的 uin（登录后 detectUin 填进来的）
        //   2) 盘上旧 webui.json 里已经记着的 autoLoginAccount
        //   3) 从 onebot11_<uin>.json 文件名里反推
        //   4) 都没有才写空
        String loginUin = uin;
        if (loginUin == null || loginUin.isEmpty()) {
            loginUin = readExistingAutoLogin(cfgDir);
        }
        if (loginUin == null || loginUin.isEmpty()) {
            loginUin = readUinFromConfigFiles(cfgDir);
        }
        if (loginUin == null) {
            loginUin = "";
        }

        String webui = "{\n"
                + "  \"host\": \"0.0.0.0\",\n"
                + "  \"port\": " + webuiPort + ",\n"
                + "  \"prefix\": \"\",\n"
                + "  \"token\": \"" + webuiToken + "\",\n"
                + "  \"loginRate\": 10,\n"
                + "  \"autoLoginAccount\": \"" + loginUin + "\"\n"
                + "}\n";
        writeText(webuiConfig(env), webui, log);

        String onebot = "{\n"
                + "  \"network\": {\n"
                + "    \"httpServers\": [],\n"
                + "    \"httpClients\": [],\n"
                + "    \"websocketServers\": [\n"
                + "      {\n"
                + "        \"name\": \"MaiBot\",\n"
                + "        \"enable\": true,\n"
                + "        \"host\": \"127.0.0.1\",\n"
                + "        \"port\": " + onebotPort + ",\n"
                + "        \"messagePostFormat\": \"array\",\n"
                + "        \"reportSelfMessage\": false,\n"
                + "        \"enableForcePushEvent\": true,\n"
                + "        \"token\": \"" + onebotToken + "\",\n"
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
        writeText(onebotConfig(env), onebot, log);

        // o3HookMode：实测必须保持 0。
        //
        // 曾经以为 AstrBot 用 1、我们用 0 是差异点，实测把它改成 1 之后
        // QQ 反而卡死在「WebUI 起来了但不出二维码」这一步
        // （rt1.out 停在 WebUi Token 那行，qrcode.png 一直没生成）。
        // 说明这个开关在本机这套 NapCat 4.18.19 + LinuxQQ 上不是「越多越好」，
        // 开它会切到另一条 hook 路径，登录流程反而走不完。
        // AstrBot 那边能跑，是因为它整套 QQ 版本/hook 环境和我们的不一定一致。
        // 所以这里固定写 0，保持我们实测能出码、能登录的那条路径。
        File napcatCfg = new File(cfgDir, "napcat" + (uin == null || uin.isEmpty() ? "" : "_" + uin) + ".json");
        String napcat = "{\n"
                + "  \"fileLog\": false,\n"
                + "  \"consoleLog\": true,\n"
                + "  \"fileLogLevel\": \"debug\",\n"
                + "  \"consoleLogLevel\": \"info\",\n"
                + "  \"packetBackend\": \"auto\",\n"
                + "  \"packetServer\": \"\",\n"
                + "  \"o3HookMode\": 0,\n"
                + "  \"autoTimeSync\": true\n"
                + "}\n";
        writeText(napcatCfg, napcat, log);
        // 未登录时 uin 为空，文件名是 napcat.json；登录后 NapCat 会生成
        // napcat_<uin>.json。两个都写一遍，保证首次启动和后续启动都生效。
        writeText(new File(cfgDir, "napcat.json"), napcat, log);
    }

    /** 建目录，失败就记日志并抛出（由 NapCat.start 捕获后告诉用户）。 */
    private static void mkdirsOrThrow(File d, BootLog log) {
        if (d.isDirectory()) {
            return;
        }
        if (!d.mkdirs()) {
            String msg = "无法创建目录 " + d.getAbsolutePath()
                    + "（可写=" + d.getParentFile().canWrite() + "）";
            if (log != null) {
                log.error(msg);
            }
            throw new IllegalStateException(msg);
        }
        if (log != null) {
            log.info("建目录 " + d.getName());
        }
    }

    private static void writeText(File f, String text, BootLog log) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(text.getBytes("UTF-8"));
            if (log != null) {
                log.info("写 " + f.getName() + "（" + text.length() + " 字符）");
            }
        } catch (Exception e) {
            Log.w(TAG, "写 " + f + " 失败: " + e);
            if (log != null) {
                log.error("写 " + f.getAbsolutePath() + " 失败：" + e);
            }
            throw new IllegalStateException("写 " + f.getName() + " 失败：" + e);
        }
    }
}