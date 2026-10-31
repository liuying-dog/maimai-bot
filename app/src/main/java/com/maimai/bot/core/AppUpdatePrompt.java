package com.maimai.bot.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.maimai.bot.R;

/**
 * 启动时检查一次 App 更新，发现新版就弹窗让用户选下载源。
 * <p>
 * <b>「只在启动时查一次」是怎么做到的。</b>
 * 这个类里有个静态标记 {@link #checkedThisProcess}。静态字段活到进程结束，
 * 所以：
 * <ul>
 *   <li>从桌面点图标冷启动 → 新进程 → 标记是 false → 查一次</li>
 *   <li>按 Home 退到后台再回来 → 进程还在 → 标记是 true → 不查</li>
 *   <li>从最近任务划掉再点开 → 进程重启 → 标记回到 false → 又查一次</li>
 * </ul>
 * 这正好就是「进入软件检查一次，但没杀进程就不会重复查」。
 * <p>
 * 另外还查了 {@code savedInstanceState != null}：横竖屏旋转会把 Activity
 * 重建一次，但进程没变，那种情况不该再弹一次窗。
 */
public final class AppUpdatePrompt {

    /**
     * 本进程是否已经查过。
     * 静态字段 = 进程级，进程死了才重置，正好符合「冷启动才查」的需求。
     */
    private static boolean checkedThisProcess = false;

    /**
     * 可选下载源。
     * <p>
     * 为什么给多个：GitHub 在国内直连经常不通，实测过 {@code github.com}
     * 会出现 000 超时。这几个是常用的 GitHub 加速前缀，
     * 拼在原地址前面即可，用户点哪个能用就下哪个。
     */
    private static final String[][] SOURCES = {
            {"GitHub 官方", ""},
            {"gh-proxy.com", "https://gh-proxy.com/"},
            {"ghfast.top", "https://ghfast.top/"},
            {"ghproxy.net", "https://ghproxy.net/"},
    };

    private AppUpdatePrompt() {
    }

    /**
     * 冷启动时调一次。已经在本次进程里查过就直接返回，什么都不做。
     *
     * @param act            当前 Activity，用来弹窗
     * @param savedInstanceState onCreate 传进来的那个；非 null 说明是配置变更重建，跳过
     * @param currentVersion 当前版本号
     */
    public static void maybeCheckOnLaunch(Activity act, android.os.Bundle savedInstanceState,
                                          String currentVersion) {
        // 进程内只查一次
        if (checkedThisProcess) {
            return;
        }
        // 旋转屏幕导致的重建：进程没变，但 savedInstanceState 非 null
        if (savedInstanceState != null) {
            return;
        }
        checkedThisProcess = true;

        AppUpdateChecker.checkAsync(currentVersion, r -> {
            // Activity 可能已经关了（用户手快），加个保护
            if (act == null || act.isFinishing() || act.isDestroyed()) {
                return;
            }
            // 只有「确实有新版本」才弹窗。
            // 其它状态（已最新 / 没发版 / 网络失败）一律静默 ——
            // 用户开 App 是要用它的，不是来看「检查失败」的。
            if (r != null && r.hasUpdate()) {
                showDialog(act, r);
            }
        });
    }

    /** 弹「发现新版本」对话框，带下载源选择。 */
    private static void showDialog(Activity act, AppUpdateChecker.Result r) {
        View content = LayoutInflater.from(act)
                .inflate(R.layout.dialog_app_update, null, false);

        TextView tvVersions = content.findViewById(R.id.tv_update_versions);
        if (tvVersions != null) {
            tvVersions.setText("发现新版本 " + r.latest);
        }
        TextView tvTip = content.findViewById(R.id.tv_update_tip);
        if (tvTip != null) {
            tvTip.setText("当前版本 " + r.current
                    + "，建议更新到最新版。\n选择下载源后会跳转浏览器下载 APK。");
        }

        // 动态塞下载源按钮：一行一个，点哪个就用哪个前缀去下
        LinearLayout list = content.findViewById(R.id.ll_update_sources);
        if (list != null) {
            for (String[] src : SOURCES) {
                list.addView(buildSourceRow(act, src[0], src[1], r.latest));
            }
        }

        new AlertDialog.Builder(act)
                .setTitle("有新版本可用")
                .setView(content)
                .setNegativeButton("稍后", null)
                .show();
    }

    /**
     * 造一行「下载源」。
     * <p>
     * 用 TextView 手动拼而不用 Button：Button 有默认最小高度和主题内边距，
     * 塞四五行会很占地方。
     */
    private static View buildSourceRow(Activity act, String label, String prefix, String version) {
        TextView tv = new TextView(act);
        tv.setText(label);
        tv.setTextSize(14);
        tv.setTextColor(0xFF4A3F7A);
        tv.setBackgroundResource(R.drawable.bg_badge_gray);
        int pad = dp(act, 12);
        tv.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(act, 8);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(v -> openDownload(act, prefix, version));
        return tv;
    }

    /**
     * 拼出最终下载地址并交给浏览器。
     * <p>
     * 地址规则是 GitHub Releases 的固定格式：
     * {@code /releases/download/vX.Y.Z/app-debug.apk}
     * 加速镜像只是在前面加个前缀，后面的路径完全一样。
     */
    private static void openDownload(Activity act, String prefix, String version) {
        String raw = "https://github.com/lanhaoyu20110907-hash/maimai-bot"
                + "/releases/download/v" + version + "/app-debug.apk";
        String url = prefix.isEmpty() ? raw : prefix + raw;
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            android.widget.Toast.makeText(act,
                    "没有可用的浏览器，链接：" + url,
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private static int dp(Activity act, int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}