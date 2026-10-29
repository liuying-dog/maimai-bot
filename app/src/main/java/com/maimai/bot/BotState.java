package com.maimai.bot;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

import com.maimai.bot.core.Env;

/**
 * 进程级状态集中点：谁在跑、装到哪一步、WebUI token 是多少。
 * 全部字段都加锁读写，因为 Service、Activity、后台线程会同时动。
 */
public final class BotState {

    public enum Status {
        /** 尚未安装 */
        NOT_INSTALLED,
        /** 正在解压 rootfs / 装依赖 */
        INSTALLING,
        /** 已安装但没启动 */
        STOPPED,
        /** 正在启动（进程已拉起，尚未确认监听） */
        STARTING,
        /** 运行中 */
        RUNNING,
        /** 出错了 */
        ERROR
    }

    private static BotState instance;

    private final SharedPreferences prefs;
    private final Env env;

    private volatile Status status = Status.NOT_INSTALLED;
    private volatile String statusDetail = "";
    private volatile int progress = 0;
    private volatile String webuiToken = "";
    /** webui.json 里 first_setup_completed 的值；true 表示用户已经设过登录密码。 */
    private volatile boolean webuiFirstSetupDone = false;
    /** webui.json 里 token_source；"temporary" 表示这是临时 token。 */
    private volatile String webuiTokenSource = "";
    /**
     * 内置实例的 WebUI 端口，跟 maibot 配置里的 [webui] port 保持一致。
     * <p>
     * 早先为了避开"别的环境也占 8001"把这里改成 18001，
     * 但那套方案把配置和代码拆成了两处真值来源，反而容易不一致。
     * 现在内置实例是干净的（不会再和 Operit 里的东西撞），
     * 端口统一用 maibot 默认的 8001，WebUiActivity 那边另有归属校验兜底。
     */
    public static final int DEFAULT_WEBUI_PORT = 8001;

    private volatile int webuiPort = DEFAULT_WEBUI_PORT;
    private volatile boolean autoStart;

    // 内存里的滚动日志，供界面显示
    private final StringBuilder log = new StringBuilder();
    private static final int LOG_LIMIT = 240 * 1024;

    private BotState(Context ctx) {
        this.prefs = ctx.getApplicationContext()
                .getSharedPreferences("maibot", Context.MODE_PRIVATE);
        this.env = Env.of(ctx);
        this.autoStart = prefs.getBoolean("auto_start", true);
        this.webuiPort = prefs.getInt("webui_port", DEFAULT_WEBUI_PORT);
    }

    public static synchronized BotState get(Context ctx) {
        if (instance == null) {
            instance = new BotState(ctx.getApplicationContext());
        }
        return instance;
    }

    public static BotState peek() {
        return instance;
    }

    // ------------------------------------------------------------- 状态

    public Status status() {
        return status;
    }

    public String statusDetail() {
        return statusDetail;
    }

    public int progress() {
        return progress;
    }

    public String webuiToken() {
        return webuiToken;
    }

    public void setWebuiToken(String t) {
        this.webuiToken = t == null ? "" : t;
    }

    public int webuiPort() {
        return webuiPort;
    }

    public void setWebuiPort(int p) {
        this.webuiPort = p;
        prefs.edit().putInt("webui_port", p).apply();
    }

    public boolean autoStart() {
        return autoStart;
    }

    public void setAutoStart(boolean v) {
        this.autoStart = v;
        prefs.edit().putBoolean("auto_start", v).apply();
    }

    public void setStatus(Status s, String detail) {
        this.status = s;
        this.statusDetail = detail == null ? "" : detail;
    }

    public void setProgress(int p) {
        this.progress = Math.max(0, Math.min(100, p));
    }

    public Env env() {
        return env;
    }

    // ------------------------------------------------------------- 日志

    public void appendLog(String line) {
        synchronized (log) {
            log.append(line).append('\n');
            if (log.length() > LOG_LIMIT) {
                log.delete(0, log.length() - LOG_LIMIT);
            }
        }
    }

    public String tailLog(int maxChars) {
        synchronized (log) {
            int len = log.length();
            int from = Math.max(0, len - maxChars);
            return log.substring(from);
        }
    }

    public void clearLog() {
        synchronized (log) {
            log.setLength(0);
        }
    }

    // ------------------------------------------------------------- token

    /** 从 maibot 生成的 data/webui.json 里读登录 token。 */
    /** 用户是否已经设过 WebUI 登录密码（设过之后就不再展示临时 token）。 */
    public boolean webuiFirstSetupDone() {
        return webuiFirstSetupDone;
    }

    /** 当前 token 是不是还没设密码时的临时 token。 */
    /** maibot 里 token_source 取值：还没设密码时的临时 token。 */
    public static final String TOKEN_SOURCE_TEMPORARY = "temporary";
    /** maibot 里 token_source 取值：用户已经自己设过密码。 */
    public static final String TOKEN_SOURCE_CONFIGURED = "configured";

    /**
     * 用户是否已经自己设过登录密码。
     * <p>
     * 这里只认 token_source，不看 first_setup_completed。
     * 实测 maibot 在「首次登录设密码」那一步只调 update_token()，
     * 会改 token_source 但**不会**把 first_setup_completed 置 true，
     * 所以拿 first_setup_completed 判断会一直得到 false。
     */
    public boolean webuiPasswordConfigured() {
        return TOKEN_SOURCE_CONFIGURED.equals(webuiTokenSource);
    }

    /** 当前 token 是不是还没设密码时的临时 token。 */
    public boolean webuiTokenIsTemporary() {
        return TOKEN_SOURCE_TEMPORARY.equals(webuiTokenSource);
    }

    /** 从 maibot 生成的 data/webui.json 里读登录 token 与首次设置状态。 */
    public void refreshWebuiToken() {
        File f = env.webuiToken();
        if (!f.isFile()) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            new java.io.FileInputStream(f),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
            }
            String json = sb.toString();

            String token = jsonString(json, "access_token");
            if (token != null) {
                setWebuiToken(token);
            }

            String src = jsonString(json, "token_source");
            if (src != null) {
                webuiTokenSource = src;
            }

            // first_setup_completed 是布尔值，不能按带引号的字符串找
            String key = "\"first_setup_completed\"";
            int i = json.indexOf(key);
            if (i >= 0) {
                int colon = json.indexOf(':', i);
                if (colon > 0) {
                    int end2 = colon + 1;
                    while (end2 < json.length()
                            && Character.isWhitespace(json.charAt(end2))) {
                        end2++;
                    }
                    webuiFirstSetupDone = json.startsWith("true", end2);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 从扁平 JSON 里取一个字符串字段的值，取不到返回 null。 */
    private static String jsonString(String json, String field) {
        String key = "\"" + field + "\"";
        int i = json.indexOf(key);
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i);
        if (colon < 0) {
            return null;
        }
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) {
            return null;
        }
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) {
            return null;
        }
        return json.substring(q1 + 1, q2);
    }
}