package com.maimai.bot.core;

import android.content.Context;

/**
 * 加速器：GitHub 下载代理。
 */
public final class Mirror {

    private static final String PREF_PROXY = "gh_proxy_key";
    private static final String PREF_AUTO = "gh_proxy_auto_pick";

    public static final class Proxy {
        public final String key;
        public final String label;
        public final String prefix;

        Proxy(String key, String label, String prefix) {
            this.key = key;
            this.label = label;
            this.prefix = prefix;
        }
    }

    public static final Proxy[] PROXIES = {
            new Proxy("direct", "直连", ""),
            new Proxy("auto", "自动选择", "auto"),
            new Proxy("ghproxy", "Gh-Proxy", "https://gh-proxy.com/"),
            new Proxy("monlor", "Monlor", "https://gh.monlor.com/"),
            new Proxy("chjina", "Chjina", "https://gh.chjina.com/"),
            new Proxy("ghproxynet", "GhProxyNet", "https://ghproxy.net/"),
            new Proxy("ghfast", "GhFast", "https://ghfast.top/"),
            new Proxy("ghproxycc", "GhProxyCC", "https://ghproxy.cc/"),
            new Proxy("dpik", "DPIK", "https://gh.dpik.top/"),
            new Proxy("boki", "Boki", "https://github.boki.moe/"),
            new Proxy("jasonzeng", "JasonZeng", "https://gh.jasonzeng.dev/"),
            new Proxy("geekertao", "Geekertao", "https://gh.geekertao.top/"),
            new Proxy("nxnow", "Nxnow", "https://gh.nxnow.top/"),
            new Proxy("npee", "Npee", "https://down.npee.cn/"),
    };

    public static final String APT_BASE = "https://mirrors.ustc.edu.cn";
    public static final String PYPI_INDEX = "https://mirrors.ustc.edu.cn/pypi/simple";

    public static final String TEST_PATH =
            "https://raw.githubusercontent.com/astral-sh/uv/main/README.md";

    private Mirror() {
    }

    public static Proxy byKey(String key) {
        for (Proxy p : PROXIES) {
            if (p.key.equals(key)) {
                return p;
            }
        }
        return PROXIES[0];
    }

    public static Proxy current(Context ctx) {
        String key = ctx.getSharedPreferences("maibot", Context.MODE_PRIVATE)
                .getString(PREF_PROXY, "auto");
        return byKey(key);
    }

    public static void select(Context ctx, Proxy p) {
        ctx.getSharedPreferences("maibot", Context.MODE_PRIVATE)
                .edit().putString(PREF_PROXY, p.key).apply();
    }

    public static void rememberAutoPick(Context ctx, String proxyKey) {
        ctx.getSharedPreferences("maibot", Context.MODE_PRIVATE)
                .edit().putString(PREF_AUTO, proxyKey).apply();
    }

    public static String resolvedPrefix(Context ctx) {
        Proxy p = current(ctx);
        if (!"auto".equals(p.prefix)) {
            return p.prefix;
        }
        String autoKey = ctx.getSharedPreferences("maibot", Context.MODE_PRIVATE)
                .getString(PREF_AUTO, "ghproxy");
        Proxy auto = byKey(autoKey);
        if ("auto".equals(auto.prefix) || "direct".equals(auto.key)) {
            return "https://gh-proxy.com/";
        }
        return auto.prefix;
    }

    public static String currentLabel(Context ctx) {
        Proxy p = current(ctx);
        if (!"auto".equals(p.prefix)) {
            return p.label;
        }
        String autoKey = ctx.getSharedPreferences("maibot", Context.MODE_PRIVATE)
                .getString(PREF_AUTO, "ghproxy");
        Proxy auto = byKey(autoKey);
        return "AUTO · " + auto.label;
    }
}
