package com.maimai.bot.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 麦麦的 QQ 适配器配置读写。
 * <p>
 * 麦麦这边用的是官方 OneBot 适配器插件（插件目录名就叫
 * {@code maibot-team.snowluma-adapter}，是上游起的名字，同时支持
 * 两种客户端；我们只用它的 NapCat 模式）。它是「OneBot 客户端」，所以只要把它的
 * {@code [client] server/port/token} 指向某个 NapCat 实例的 OneBot 端口即可。
 * <p>
 * 配置文件位置：
 * <pre>
 *   &lt;rootfs&gt;/root/maibot/plugins/maibot-team.snowluma-adapter/config.toml
 * </pre>
 * 插件默认 {@code [plugin] enabled = false}，绑定的时候要顺手打开，
 * 否则重启麦麦后适配器仍然不加载（这一步之前就是漏的，导致「装好了却没连上」）。
 */
public final class AdapterConfig {
    private static final String TAG = "AdapterConfig";

    private AdapterConfig() {
    }

    private static File configFile(Env env) {
        return new File(env.rootfs(),
                "root/maibot/plugins/maibot-team.snowluma-adapter/config.toml");
    }

    /** 适配器插件是否已安装。 */
    public static boolean isInstalled(Env env) {
        return configFile(env).isFile();
    }

    /**
     * 把一个 NapCat 账号绑定到麦麦适配器。
     * <p>
     * 做三件事：
     * <ol>
     *   <li>{@code [plugin] enabled = true} —— 不然插件根本不加载</li>
     *   <li>{@code [client] server/port/token} 指向该账号的 OneBot 端口</li>
     *   <li>{@code client_type = "napcat"} —— 跳过自动判定，直连更稳</li>
     * </ol>
     *
     * @return 是否写入成功
     */
    public static boolean bind(Context ctx, NapCatAccount acc) {
        Env env = Env.of(ctx.getApplicationContext());
        File f = configFile(env);
        if (!f.isFile()) {
            Log.w(TAG, "适配器配置不存在: " + f);
            return false;
        }
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader(f))) {
            String line;
            boolean inPlugin = false;
            boolean inClient = false;
            boolean wroteEnabled = false;
            boolean wroteClientType = false;
            boolean wroteServer = false;
            boolean wrotePort = false;
            boolean wroteToken = false;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("[")) {
                    inPlugin = t.equals("[plugin]");
                    inClient = t.equals("[client]");
                    sb.append(line).append('\n');
                    continue;
                }
                if (inPlugin && t.startsWith("enabled")) {
                    sb.append("enabled = true\n");
                    wroteEnabled = true;
                    continue;
                }
                if (inClient) {
                    if (t.startsWith("client_type")) {
                        sb.append("client_type = \"napcat\"\n");
                        wroteClientType = true;
                        continue;
                    }
                    if (t.startsWith("server")) {
                        sb.append("server = \"127.0.0.1\"\n");
                        wroteServer = true;
                        continue;
                    }
                    if (t.startsWith("port")) {
                        sb.append("port = ").append(acc.onebotPort).append('\n');
                        wrotePort = true;
                        continue;
                    }
                    if (t.startsWith("token")) {
                        sb.append("token = \"").append(acc.onebotToken).append("\"\n");
                        wroteToken = true;
                        continue;
                    }
                }
                sb.append(line).append('\n');
            }
            // 万一原文件里缺了某行，补在对应段末尾（这里简单追加到文件尾，
            // 插件读 TOML 是按 key 覆盖，重复段会以最后一次为准，但正常文件不会缺）
            if (!wroteEnabled) {
                sb.append("[plugin]\nenabled = true\n");
            }
            if (!wroteClientType || !wroteServer || !wrotePort || !wroteToken) {
                sb.append("[client]\n");
                sb.append("client_type = \"napcat\"\n");
                sb.append("server = \"127.0.0.1\"\n");
                sb.append("port = ").append(acc.onebotPort).append('\n');
                sb.append("token = \"").append(acc.onebotToken).append("\"\n");
            }
        } catch (Exception e) {
            Log.w(TAG, "读适配器配置失败: " + e);
            return false;
        }

        // 备份一份，出问题能回退
        File bak = new File(f.getAbsolutePath() + ".bak");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(bak)) {
            out.write(java.nio.file.Files.readAllBytes(f.toPath()));
        } catch (Exception ignored) {
        }

        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(sb.toString().getBytes("UTF-8"));
            Log.i(TAG, "已绑定 " + acc.label + " → 127.0.0.1:" + acc.onebotPort);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "写适配器配置失败: " + e);
            return false;
        }
    }

    /** 当前绑定的端口（读不出来返回 -1）。 */
    public static int boundPort(Env env) {
        File f = configFile(env);
        if (!f.isFile()) {
            return -1;
        }
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader(f))) {
            String line;
            boolean inClient = false;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("[")) {
                    inClient = t.equals("[client]");
                    continue;
                }
                if (inClient && t.startsWith("port")) {
                    int eq = t.indexOf('=');
                    if (eq > 0) {
                        return Integer.parseInt(t.substring(eq + 1).trim());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }
}