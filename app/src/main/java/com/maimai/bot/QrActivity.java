package com.maimai.bot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.maimai.bot.core.Env;
import com.maimai.bot.core.NapCat;
import com.maimai.bot.core.NapCatAccount;

import java.io.File;
import java.util.List;

/**
 * 扫码登录页。
 * <p>
 * 为什么要有这个页面：之前点「启动」后二维码其实已经生成到
 * {@code <rootfs>/root/napcat_instances/<id>_napcat/cache/qrcode.png}，
 * 但 App 里没有任何一处去读它 —— 启动完直接跳 WebUI 面板，
 * 而面板本身在未登录时是登录不了的，于是用户卡在「没二维码可扫」。
 * <p>
 * 现在的行为（对齐 AstrBot 的体验）：
 * <ol>
 *   <li>点启动 → 直接进本页，边看启动进度边等二维码</li>
 *   <li>每 800ms 探一次 qrcode.png，一出现就自动加载显示（不需要手点刷新）</li>
 *   <li>图变了就换新图（NapCat 在二维码过期后会自动重新生成）</li>
 *   <li>检测到登录成功（config 目录出现 onebot11_&lt;uin&gt;.json）就自动跳 WebUI 面板</li>
 * </ol>
 */
public class QrActivity extends Activity {

    /** 要展示二维码的账号 id。 */
    public static final String EXTRA_ACCOUNT_ID = "account_id";
    /** 账号显示名，只用于标题。 */
    public static final String EXTRA_ACCOUNT_LABEL = "account_label";

    /** 二维码还没生成时的轮询间隔。 */
    private static final long POLL_MS = 800;
    /** 总超时：超过这么久还没有二维码就提示去看日志。 */
    private static final long TIMEOUT_MS = 120_000;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private String accountId;
    private String accountLabel;

    private ImageView qrView;
    private TextView status;
    private TextView hint;
    private ProgressBar spinner;
    private Button toPanel;
    private Button toLog;
    private Button refresh;

    /** 上一次成功加载的那张图的大小 + 修改时间，用来判断「换图了」。 */
    private long lastLen = -1;
    private long lastMtime = -1;
    /** 已经自动跳转过面板，避免反复跳。 */
    private boolean jumped = false;
    private long startedAt;
    private boolean polling;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        accountId = getIntent().getStringExtra(EXTRA_ACCOUNT_ID);
        accountLabel = getIntent().getStringExtra(EXTRA_ACCOUNT_LABEL);
        if (accountLabel == null || accountLabel.isEmpty()) {
            accountLabel = "QQ 账号";
        }
        startedAt = System.currentTimeMillis();
        setContentView(buildLayout());
        startPolling();
    }

    // ------------------------------------------------------------ 界面

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#12141A"));
        root.setPadding(dp(20), dp(18), dp(20), dp(18));

        // 标题行
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("扫码登录 · " + accountLabel);
        title.setTextColor(Color.parseColor("#F2F4F8"));
        title.setTextSize(19);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(title);

        Button close = new Button(this);
        close.setText("返回");
        close.setTextSize(13);
        close.setAllCaps(false);
        close.setTextColor(Color.parseColor("#9AA4B2"));
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setOnClickListener(v -> finish());
        titleRow.addView(close);

        root.addView(titleRow);

        hint = new TextView(this);
        hint.setText("用手机 QQ 扫下面的二维码。首次登录要扫，登录成功后会自动跳到面板。");
        hint.setTextColor(Color.parseColor("#8B94A3"));
        hint.setTextSize(12.5f);
        hint.setPadding(0, dp(6), 0, dp(14));
        root.addView(hint);

        // 二维码区（居中）
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, dp(8), 0, dp(8));

        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(34), dp(34));
        slp.topMargin = dp(30);
        spinner.setLayoutParams(slp);
        box.addView(spinner);

        qrView = new ImageView(this);
        qrView.setBackgroundColor(Color.WHITE);
        qrView.setAdjustViewBounds(true);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrView.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(280), dp(280));
        qlp.topMargin = dp(10);
        qrView.setLayoutParams(qlp);
        qrView.setVisibility(View.GONE);
        box.addView(qrView);

        status = new TextView(this);
        status.setText("正在准备二维码…");
        status.setTextColor(Color.parseColor("#C9D1DC"));
        status.setTextSize(13.5f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(16), 0, 0);
        box.addView(status);

        scroll.addView(box);
        root.addView(scroll);

        // 底部按钮
        LinearLayout ops = new LinearLayout(this);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        ops.setPadding(0, dp(12), 0, 0);

        refresh = bottomButton("重新出码");
        refresh.setOnClickListener(v -> restartForNewQr());
        ops.addView(refresh);

        toLog = bottomButton("看启动日志");
        toLog.setOnClickListener(v -> {
            Intent i = new Intent(this, ConsoleActivity.class);
            i.putExtra(ConsoleActivity.EXTRA_FOLLOW_NAPCAT, true);
            i.putExtra(ConsoleActivity.EXTRA_NAPCAT_LABEL, accountLabel);
            startActivity(i);
        });
        ops.addView(toLog);

        toPanel = bottomButton("已登录，进面板");
        toPanel.setOnClickListener(v -> {
            jumped = true;
            openPanelAndFinish();
        });
        ops.addView(toPanel);

        root.addView(ops);
        return root;
    }

    private Button bottomButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12.5f);
        b.setAllCaps(false);
        b.setTextColor(Color.parseColor("#E6EAF2"));
        b.setBackgroundColor(Color.parseColor("#232833"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(dp(4));
        lp.setMarginEnd(dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    // ------------------------------------------------------------ 逻辑

    private NapCatAccount findAccount() {
        if (accountId == null) {
            return null;
        }
        List<NapCatAccount> all = NapCatAccount.loadAll(this);
        for (NapCatAccount a : all) {
            if (a.id.equals(accountId)) {
                return a;
            }
        }
        return null;
    }

    private void startPolling() {
        if (polling) {
            return;
        }
        polling = true;
        ui.postDelayed(pollTask, 300);
    }

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) {
                return;
            }
            boolean next = true;
            try {
                next = tick();
            } catch (Exception ignored) {
            }
            if (next && !isFinishing()) {
                ui.postDelayed(this, POLL_MS);
            }
        }
    };

    /** @return 是否继续轮询 */
    private boolean tick() {
        Env env = BotState.get(this).env();
        NapCatAccount acc = findAccount();
        if (acc == null) {
            status.setText("账号已被删除");
            spinner.setVisibility(View.GONE);
            return false;
        }

        // 1) 已经登录成功了吗？（NapCat 登录后会写 config/onebot11_<uin>.json）
        String uin = acc.detectUin(env);
        if (!uin.isEmpty() && !jumped) {
            jumped = true;
            NapCat.refreshUin(env, acc);
            status.setText("已登录：" + uin + "，正在打开面板…");
            spinner.setVisibility(View.GONE);
            qrView.setVisibility(View.GONE);
            hint.setText("登录成功。以后启动会自动快速登录，不用再扫码。");
            ui.postDelayed(this::openPanelAndFinish, 600);
            return false;
        }

        // 2) 二维码出现 / 换图了吗？
        File png = acc.qrCode(env);
        if (png.isFile()) {
            long len = png.length();
            long mtime = png.lastModified();
            // 只认「本次进入页面之后生成」的图。
            // 之前踩过：上一次运行留下的旧码还在盘上，页面一来就把它当成
            // 新码显示出来，用户扫了报「二维码已失效」，还以为是我们没刷出来。
            // 这里留 3 秒容差，避免启动和进页面的先后顺序把新图也挡掉。
            boolean fresh = mtime >= (startedAt - 3000);
            if (len > 0 && fresh && (len != lastLen || mtime != lastMtime)) {
                Bitmap bmp = BitmapFactory.decodeFile(png.getAbsolutePath());
                if (bmp != null) {
                    lastLen = len;
                    lastMtime = mtime;
                    qrView.setImageBitmap(bmp);
                    qrView.setVisibility(View.VISIBLE);
                    spinner.setVisibility(View.GONE);
                    status.setText("请用手机 QQ 扫码");
                    status.setTextColor(Color.parseColor("#7EE0A0"));
                    hint.setText("扫码后在手机上确认登录。登录成功会自动跳转面板。");
                } else {
                    status.setText("二维码图片读不出来，稍后重试…");
                }
            } else if (!fresh) {
                long waited = System.currentTimeMillis() - startedAt;
                spinner.setVisibility(View.VISIBLE);
                status.setTextColor(Color.parseColor("#C9D1DC"));
                if (waited > 25_000) {
                    // 盘上那张是上一轮的旧码，本次启动没生成新码
                    status.setText("没有新二维码生成");
                    hint.setText("盘上那张是上次留下的旧码，已忽略。点「重新出码」重启实例再试。");
                } else {
                    status.setText("正在准备二维码…（已等 " + (waited / 1000) + " 秒）");
                }
            }
        } else {
            long waited = System.currentTimeMillis() - startedAt;
            if (waited > TIMEOUT_MS) {
                spinner.setVisibility(View.GONE);
                status.setText("2 分钟还没生成二维码");
                status.setTextColor(Color.parseColor("#FF8A8A"));
                hint.setText("点「看启动日志」查原因，常见是 Xvfb 或 QQ 没起来。");
                return false;
            }
            status.setText("正在准备二维码…（已等 " + (waited / 1000) + " 秒）");
        }
        return true;
    }

    /**
     * 「重新出码」：真的要重启实例才行。
     * <p>
     * 为什么不能像以前那样只删 cache/qrcode.png：
     * NapCat 的二维码只在「拉起登录流程」那一刻写一次，进程活着的时候
     * 它不会去检查文件是不是被人删了再补一张。所以删文件 = 永久没图，
     * 页面就会一直停在「正在准备二维码…」。实测确认过这个行为。
     * <p>
     * 正确做法是 stop → start，让 NapCat 重走一遍登录流程，二维码自然重打。
     */
    private void restartForNewQr() {
        final NapCatAccount acc = findAccount();
        if (acc == null) {
            Toast.makeText(this, "账号已被删除", Toast.LENGTH_SHORT).show();
            return;
        }
        refresh.setEnabled(false);
        qrView.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE);
        status.setTextColor(0xFFC9D1DC);
        status.setText("正在重启实例，重新生成二维码…");
        hint.setText("重启大约 20~40 秒。NapCat 只在启动时生成二维码，所以必须重启才能换新码。");

        final Env env = BotState.get(this).env();
        new Thread(() -> {
            try {
                NapCat.stop(env, acc);
                Thread.sleep(1500);
                String err = NapCat.start(this, env, acc);
                if (err != null) {
                    ui.post(() -> {
                        refresh.setEnabled(true);
                        spinner.setVisibility(View.GONE);
                        status.setTextColor(0xFFFF8A8A);
                        status.setText("重启失败：" + err);
                    });
                    return;
                }
                // 以日志里的 token 为准（NapCat 每次启动都可能重生成）
                String token = NapCat.waitForToken(env, acc, 45_000);
                if (token != null && !token.isEmpty()) {
                    acc.webuiToken = token;
                    List<NapCatAccount> all = NapCatAccount.loadAll(this);
                    for (NapCatAccount x : all) {
                        if (x.id.equals(acc.id)) {
                            x.webuiToken = token;
                        }
                    }
                    NapCatAccount.saveAll(this, all);
                }
            } catch (Exception e) {
                ui.post(() -> {
                    refresh.setEnabled(true);
                    spinner.setVisibility(View.GONE);
                    status.setTextColor(0xFFFF8A8A);
                    status.setText("重启出错：" + e);
                });
                return;
            }
            ui.post(() -> {
                refresh.setEnabled(true);
                // 重置「上一张图」的指纹，让新图一定会被加载
                lastLen = -1;
                lastMtime = -1;
                startedAt = System.currentTimeMillis();
                status.setText("实例已重启，正在等新二维码…");
                if (!polling) {
                    polling = true;
                    ui.postDelayed(pollTask, 300);
                }
            });
        }, "qr-restart").start();
    }

    private void openPanelAndFinish() {
        NapCatAccount acc = findAccount();
        if (acc == null) {
            finish();
            return;
        }
        Intent i = new Intent(this, WebUiActivity.class);
        i.putExtra(WebUiActivity.EXTRA_SHOW_QQ, true);
        i.putExtra(WebUiActivity.EXTRA_QQ_PORT, acc.webuiPort);
        i.putExtra(WebUiActivity.EXTRA_QQ_TOKEN, acc.webuiToken);
        startActivity(i);
        finish();
    }

    @Override
    protected void onDestroy() {
        polling = false;
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
