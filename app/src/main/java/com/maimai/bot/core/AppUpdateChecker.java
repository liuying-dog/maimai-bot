package com.maimai.bot.core;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检查 App 自己在 GitHub 上有没有新版本。
 * <p>
 * 这是「App 本体」的更新检查，和 {@link UpdateChecker}（查 MaiBot / NapCat / LinuxQQ
 * 那三个内置组件）是两回事，所以单独一个类，别混在一起。
 * <p>
 * <b>为什么不用 GitHub Releases API。</b>
 * 一开始想用 {@code /repos/{owner}/{repo}/releases/latest}，但真机实测有两个问题：
 * <ol>
 *   <li>匿名调用限额只有 60 次/小时，而且是<b>按出口 IP 算</b>的。
 *       手机大多在 NAT 后面，别人用完了你就调不动 —— 实测已经撞到过 403 rate limit。</li>
 *   <li>仓库没发 Release 时返回 404，和「网络错误」不好区分。</li>
 * </ol>
 * 改成抓 Releases 页面的 HTML：它没有限额，也不要求认证。
 * 页面里的版本号只出现在 {@code /releases/tag/vX.Y.Z} 这种链接里，正则很稳。
 * <p>
 * <b>抓不到怎么办。</b>
 * 一律返回 {@link Result#UNAVAILABLE}，界面显示「暂时查不到」，绝不谎报「已是最新」——
 * 那会让用户以为真的没问题。
 */
public final class AppUpdateChecker {

    /** 仓库地址，改仓库时同步改这里和 AboutActivity。 */
    private static final String RELEASES_PAGE =
            "https://github.com/lanhaoyu20110907-hash/maimai-bot/releases";

    /** 从 HTML 里抠 /releases/tag/v1.2.3 这种链接。 */
    private static final Pattern TAG_LINK =
            Pattern.compile("/releases/tag/v?(\\d+\\.\\d+\\.\\d+)");

    /** 检查结果。 */
    public static final class Result {
        /** 当前装的版本。 */
        public final String current;
        /** 上游最新版本，查不到就是空串。 */
        public final String latest;
        /** 状态。 */
        public final State state;

        Result(String current, String latest, State state) {
            this.current = current;
            this.latest = latest;
            this.state = state;
        }

        /** 是否确实有新版本可升。 */
        public boolean hasUpdate() {
            return state == State.HAS_UPDATE;
        }

        /**
         * 给界面用的一句话。
         * <p>
         * 每种状态都说清楚，不留含糊：
         * 有新版本就说从哪到哪，没新版本就明确说已是最新，
         * 查不到就说查不到（而不是假装已是最新）。
         */
        public String describe() {
            switch (state) {
                case HAS_UPDATE:
                    return "有新版本 " + latest + "（当前 " + current + "）";
                case UP_TO_DATE:
                    return "已是最新版本（" + current + "）";
                case NO_RELEASE:
                    return "仓库还没有发布版本（当前 " + current + "）";
                case NETWORK_ERROR:
                    return "检查失败，网络不通或 GitHub 访问不了";
                case UNAVAILABLE:
                default:
                    return "暂时查不到更新信息";
            }
        }
    }

    /** 检查状态。 */
    public enum State {
        /** 有新版本。 */
        HAS_UPDATE,
        /** 已是最新。 */
        UP_TO_DATE,
        /** 仓库还没发过 Release。 */
        NO_RELEASE,
        /** 网络层失败。 */
        NETWORK_ERROR,
        /** 页面拿到了但没解析出版本号（结构变了之类）。 */
        UNAVAILABLE
    }

    /** 回调，在主线程触发。 */
    public interface Callback {
        void onResult(Result r);
    }

    private AppUpdateChecker() {
    }

    /**
     * 后台查一次，查完回主线程回调。
     * 任何异常都吞掉转成状态，绝不把异常抛给界面。
     */
    public static void checkAsync(final String currentVersion, final Callback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Result r;
            try {
                r = check(currentVersion);
            } catch (Throwable t) {
                r = new Result(currentVersion, "", State.NETWORK_ERROR);
            }
            final Result fr = r;
            main.post(() -> cb.onResult(fr));
        }, "app-update-check").start();
    }

    /** 同步检查，内部用。 */
    private static Result check(String currentVersion) {
        String html = fetch(RELEASES_PAGE);
        if (html == null) {
            return new Result(currentVersion, "", State.NETWORK_ERROR);
        }
        // 仓库一个 Release 都没有时，页面会明说「There aren't any releases here」。
        // 先把这种情况挑出来，免得后面「没找到版本号」被误判成解析失败。
        if (html.contains("aren&#39;t any releases")
                || html.contains("aren't any releases")
                || html.contains("There aren’t any releases")) {
            return new Result(currentVersion, "", State.NO_RELEASE);
        }
        String latest = pickHighestTag(html);
        if (latest.isEmpty()) {
            // 页面拿到了但没有 tag 链接 —— 可能是真的没发布，也可能是页面改版。
            // 没 Release 的页面通常带上面那句话，走到这里多半是结构变了，
            // 所以判 UNAVAILABLE，不判「已是最新」。
            return new Result(currentVersion, "", State.UNAVAILABLE);
        }
        // 同一个页面里可能出现多个 tag（侧栏「最新发布」+ 列表），取最大的那个
        boolean newer = UpdateChecker.compare(latest, currentVersion) > 0;
        return new Result(currentVersion, latest,
                newer ? State.HAS_UPDATE : State.UP_TO_DATE);
    }

    /**
     * 从页面里挑出版本号最大的 tag。
     * <p>
     * 不能直接取第一个：页面顶部可能有个「Latest」标记指向较新的预发布版，
     * 而列表里还挂着旧版本。全部扫出来取最大值最稳。
     */
    private static String pickHighestTag(String html) {
        Matcher m = TAG_LINK.matcher(html);
        String best = "";
        while (m.find()) {
            String v = m.group(1);
            if (best.isEmpty() || UpdateChecker.compare(v, best) > 0) {
                best = v;
            }
        }
        return best;
    }

    /** 拉一个页面，失败返回 null。 */
    private static String fetch(String spec) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(spec);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            // GitHub 对没有 UA 的请求有时会直接拒绝，带一个正常的
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "text/html");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
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
}