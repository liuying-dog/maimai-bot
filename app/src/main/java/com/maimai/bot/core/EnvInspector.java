package com.maimai.bot.core;

import java.io.File;

/**
 * 环境自检：逐项检查 maibot 跑起来到底需要什么，以及现在有没有。
 * <p>
 * 为什么要分项：原来界面上只有一个「重装环境」，用户根本不知道
 * 环境里到底缺什么 —— 点了重装也只是重新解压 rootfs，装好的 maibot 全丢。
 * 拆成一张清单后，每项能单独看状态、单独补装。
 * <p>
 * 这些检查全部基于文件系统事实，不做任何推测：
 * 文件在就是装了，不在就是没装。
 */
public final class EnvInspector {

    /** 一项环境依赖。 */
    public static final class Item {
        public final String key;
        public final String title;
        public final String desc;
        /** 图标资源名，由界面侧映射。 */
        public final String icon;
        /** 是否已就绪。 */
        public final boolean ready;
        /** 就绪时给一句人话说明（比如版本号），没有就空串。 */
        public final String detail;

        Item(String key, String title, String desc, String icon, boolean ready, String detail) {
            this.key = key;
            this.title = title;
            this.desc = desc;
            this.icon = icon;
            this.ready = ready;
            this.detail = detail;
        }
    }

    private final Env env;

    public EnvInspector(Env env) {
        this.env = env;
    }

    /** 按固定顺序返回全部检查项。 */
    public Item[] inspect() {
        File rootfs = env.rootfs();
        File instance = env.instance();

        // 1) rootfs 本体
        File bashBin = new File(rootfs, "usr/bin/bash");
        boolean rootfsOk = bashBin.isFile();
        String rootfsDetail = "";
        if (rootfsOk) {
            File osRelease = new File(rootfs, "etc/os-release");
            rootfsDetail = osRelease.isFile() ? readOsName(osRelease) : "";
        }

        // 2) python
        File python = findPython(rootfs);
        boolean pythonOk = python != null;

        // 3) uv
        File uv = new File(rootfs, "root/.local/bin/uv");
        boolean uvOk = uv.isFile();
        String uvDetail = uvOk ? humanSize(uv.length()) : "";

        // 4) git（bootstrap 里要用来拉东西）
        File git = new File(rootfs, "usr/bin/git");
        boolean gitOk = git.isFile();

        // 5) curl（联网装东西必备）
        File curl = new File(rootfs, "usr/bin/curl");
        boolean curlOk = curl.isFile();

        // 6) maibot 本体
        File maibotBin = new File(rootfs, "root/.local/share/uv/tools/maibot");
        boolean maibotOk = maibotBin.isDirectory();

        // 7) 实例（maibot init 生成的配置目录）
        boolean instOk = instance.isDirectory()
                && new File(instance, "config").isDirectory();

        // 8) 关键 python 依赖
        //    这几个是 maibot 启动路径上真正会 import 的，
        //    缺任何一个都会在启动时直接 ImportError（之前踩过 aiohttp 的坑）。
        String[] need = {"aiohttp", "pydantic", "tomlkit", "watchfiles"};
        int present = 0;
        for (String n : need) {
            if (new File(maibotBin, "lib/python3.13/site-packages/" + n).exists()) {
                present++;
            }
        }
        boolean depsOk = present == need.length && maibotOk;

        // 9) NapCat + LinuxQQ（QQ ↔ OneBot 桥接）
        //    麦麦本身不会连 QQ，得有个东西把 QQ 会话翻译成 OneBot。
        //    这里判定三个关键文件：QQ 二进制、napcat.mjs、注入库。
        //    （早期试过的那条外部进程注入路线已整体移除：它要 ptrace 注入 QQ，
        //      和 proot 的单 tracer 模型冲突，真机实测恒定失败。）
        File qqBin = new File(rootfs, "opt/QQ/qq");
        File napcatDir = new File(rootfs, "root/napcat");
        File napcatEntry = new File(napcatDir, "napcat.mjs");
        File napcatLauncher = new File(rootfs, "root/libnapcat_launcher.so");
        boolean napcatOk = qqBin.isFile() && napcatEntry.isFile() && napcatLauncher.isFile();
        String napcatDetail = "";
        if (napcatOk) {
            // 版本走 NapCat.detectVersion()：先看我们自己写的 .napcat_version，
            // 没有再翻启动日志。绝不能读包里的 package.json —— 那字段永远是 0.0.1。
            String v = NapCat.detectVersion(env);
            napcatDetail = v.isEmpty() ? humanSize(qqBin.length()) : v;
        }

        // 10) LinuxQQ 单独列一项。
        //     为什么不并到 NapCat 里：QQ 和 NapCat 是两个独立的发布节奏，
        //     更新时也得分开判断（QQ 是 deb、NapCat 是 zip）。
        //     以前只有一行「NapCat（QQ 桥接）」，看不出 QQ 本体是哪一版，
        //     出问题时只能猜是不是版本太旧。
        File qqPkg = new File(rootfs, "opt/QQ/resources/app/package.json");
        boolean qqOk = qqBin.isFile();
        String qqDetail = "";
        if (qqOk) {
            // package.json 里 version 形如 "3.2.21-42086"，只取前半段
            String raw = readField(qqPkg, "\"version\"\\s*:\\s*\"([0-9]+\\.[0-9]+\\.[0-9]+)");
            qqDetail = raw == null ? humanSize(qqBin.length()) : raw;
        }

        return new Item[]{
                new Item("rootfs", "Ubuntu 基础系统", "rootfs 解压完整性",
                        "ic_box", rootfsOk, rootfsDetail),
                new Item("bash", "Shell 环境", "/usr/bin/bash · 终端与启动脚本",
                        "ic_terminal", rootfsOk, rootfsOk ? "可用" : ""),
                new Item("python", "Python 运行时", "maibot 依赖 Python 3.13",
                        "ic_box", pythonOk, pythonOk ? "3.13" : ""),
                new Item("uv", "uv", "Python 依赖管理工具",
                        "ic_box", uvOk, uvDetail),
                new Item("git", "git", "拉取插件与仓库",
                        "ic_box", gitOk, ""),
                new Item("curl", "curl", "网络请求与安装脚本",
                        "ic_box", curlOk, ""),
                new Item("maibot", "MaiBot 本体", "maibot 1.3.x 已安装",
                        "ic_box", maibotOk, maibotOk ? "已安装" : ""),
                new Item("deps", "Python 依赖", "aiohttp / pydantic / tomlkit / watchfiles",
                        "ic_box", depsOk, present + "/" + need.length),
                new Item("instance", "实例配置", "data/ 与 config/ 已初始化",
                        "ic_box", instOk, instOk ? "已初始化" : ""),
                new Item("napcat", "NapCat（QQ 桥接）", "官方 LinuxQQ + NapCat 注入",
                        "ic_box", napcatOk, napcatDetail),
                new Item("linuxqq", "LinuxQQ 本体", "NapCat 注入的底座，独立于 NapCat 迭代",
                        "ic_box", qqOk, qqDetail),
        };
    }

    // ------------------------------------------------------------- 内部

    private static File findPython(File rootfs) {
        File[] candidates = {
                new File(rootfs, "usr/bin/python3.13"),
                new File(rootfs, "usr/bin/python3"),
                new File(rootfs, "root/.local/share/uv/python"),
        };
        for (File f : candidates) {
            if (f.exists()) {
                return f;
            }
        }
        return null;
    }

    /**
     * 从 JSON 文件里按正则抓一个字段。
     * 只用来读 package.json 的 version 这种简单场景，不引第三方 JSON 库。
     * 读不到返回 null（和空串区分开：null 表示文件里压根没这个字段）。
     */
    private static String readField(File f, String regex) {
        if (f == null || !f.isFile()) {
            return null;
        }
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile(regex).matcher(sb.toString());
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 /etc/os-release 里挑 PRETTY_NAME。 */
    private static String readOsName(File f) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("PRETTY_NAME=")) {
                    String v = line.substring("PRETTY_NAME=".length()).trim();
                    if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                        v = v.substring(1, v.length() - 1);
                    }
                    return v;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024 * 1024) {
            return (bytes / (1024 * 1024)) + " MB";
        }
        if (bytes >= 1024) {
            return (bytes / 1024) + " KB";
        }
        return bytes + " B";
    }
}