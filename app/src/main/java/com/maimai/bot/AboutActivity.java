package com.maimai.bot;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 关于页：仓库地址 + 开源协议。
 * <p>
 * 两件事想清楚才这么写：
 * <p>
 * <b>1. 仓库地址为什么可点又能复制。</b>
 * 手机上隔着屏幕看一长串 URL 很难抄，所以点一下就跳浏览器；
 * 但有时候用户想把它发给别人，所以同时保留长按复制，并在下面写了一行提示。
 * 打开链接失败了也不能崩 —— 有些设备没有浏览器，得给个 Toast 说明。
 * <p>
 * <b>2. 协议为什么写 AGPL-3.0 并解释。</b>
 * 这个 App 内置了 MaiBot 和 NapCat，两者都是 AGPL-3.0。
 * AGPL 的要点是「通过网络提供服务也算分发」，所以整个项目跟着用 AGPL 最干净，
 * 用户也有权拿到源码。把这句话直接写在界面上，比藏进 LICENSE 文件里更诚实。
 */
public class AboutActivity extends Activity {

    /** 项目仓库地址。改仓库时要同步改这里。 */
    private static final String REPO_URL =
            "https://github.com/lanhaoyu20110907-hash/maimai-bot";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        ImageButton back = findViewById(R.id.btn_back);
        if (back != null) {
            back.setOnClickListener(v -> finish());
        }

        TextView tvAppVersion = findViewById(R.id.tv_app_version);
        if (tvAppVersion != null) {
            tvAppVersion.setText("版本 " + versionName());
        }

        TextView tvRepoUrl = findViewById(R.id.tv_repo_url);
        if (tvRepoUrl != null) {
            tvRepoUrl.setText(REPO_URL);
        }

        TextView tvLicenseDesc = findViewById(R.id.tv_license_desc);
        if (tvLicenseDesc != null) {
            tvLicenseDesc.setText(
                    "本项目的源代码在 GNU AGPL-3.0 下开放。\n\n"
                            + "你可以自由使用、修改、分发，但分发修改后的版本时必须同样开源，"
                            + "并且通过网络提供服务时也要向用户提供源码。\n\n"
                            + "内置的 MaiBot 与 NapCat 各自遵循 AGPL-3.0，"
                            + "LinuxQQ 遵循腾讯自有协议。");
        }

        // 整行可点：跳浏览器看仓库
        View rowRepo = findViewById(R.id.row_repo);
        if (rowRepo != null) {
            rowRepo.setOnClickListener(v -> openRepo());
        }
        // 链接文字本身也点了就跳，不用非得点空白处
        if (tvRepoUrl != null) {
            tvRepoUrl.setOnClickListener(v -> openRepo());
        }

        // 检查更新：整行和右边那个小按钮都能触发
        View rowCheck = findViewById(R.id.row_check_update);
        if (rowCheck != null) {
            rowCheck.setOnClickListener(v -> doCheckUpdate());
        }
        View btnCheck = findViewById(R.id.btn_check_update);
        if (btnCheck != null) {
            btnCheck.setOnClickListener(v -> doCheckUpdate());
        }
    }

    /**
     * 查一次 GitHub 上有没有新版本。
     * <p>
     * 三条原则：
     * <ol>
     *   <li>查的过程要有反馈（按钮变「检查中…」并禁用），否则用户会连点好几次；</li>
     *   <li>结果必须如实说明，查不到就说查不到，绝不谎报「已是最新」；</li>
     *   <li>有新版本时给一个「去下载」的入口，而不是只告诉他有新版。</li>
     * </ol>
     */
    private void doCheckUpdate() {
        final TextView btn = findViewById(R.id.btn_check_update);
        final TextView state = findViewById(R.id.tv_update_state);
        if (btn == null || state == null) {
            return;
        }
        // 防止连点：检查期间按钮禁用，文字改成进行中
        btn.setEnabled(false);
        btn.setText("检查中");
        state.setText("正在连接 GitHub…");

        com.maimai.bot.core.AppUpdateChecker.checkAsync(
                versionName(),
                r -> {
                    btn.setEnabled(true);
                    btn.setText("检查");
                    if (r == null) {
                        state.setText("暂时查不到更新信息");
                        return;
                    }
                    state.setText(r.describe());
                    // 真正有新版本时，把按钮变成「去下载」，点了直接跳 Releases 页
                    if (r.hasUpdate()) {
                        btn.setText("去下载");
                        btn.setOnClickListener(v -> openReleases());
                    } else {
                        btn.setOnClickListener(v -> doCheckUpdate());
                    }
                });
    }

    /** 打开 Releases 页面，让用户自己下 APK。 */
    private void openReleases() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse(REPO_URL + "/releases")));
        } catch (Exception e) {
            copyRepoUrl("没有可用的浏览器，链接已复制");
        }
    }

    /**
     * 用系统浏览器打开仓库地址。
     * <p>
     * 找不到浏览器时不抛 ActivityNotFoundException，改成把链接复制到剪贴板 ——
     * 用户至少还能粘到别处打开，比只弹一句「无法打开」有用。
     */
    private void openRepo() {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL));
            startActivity(it);
        } catch (Exception e) {
            copyRepoUrl("没有可用的浏览器，链接已复制");
        }
    }

    private void copyRepoUrl(String tip) {
        try {
            ClipboardManager cm =
                    (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("maimai-bot", REPO_URL));
            }
        } catch (Exception ignored) {
        }
        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show();
    }

    /** 读 versionName，读不到返回空串。 */
    private String versionName() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}