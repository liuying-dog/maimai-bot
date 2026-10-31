package com.maimai.bot;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
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
import com.maimai.bot.core.EnvInspector;
import com.maimai.bot.core.Installer;
import com.maimai.bot.core.Mirror;
import com.maimai.bot.core.NapCat;
import com.maimai.bot.core.NapCatAccount;
import com.maimai.bot.core.AdapterConfig;
import com.maimai.bot.core.BootLog;
import com.maimai.bot.core.ProcessKiller;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 主界面。视觉上对齐参考图：淡粉紫渐变底 + 半透明白卡片 + 底部悬浮胶囊导航。
 * 所有圆角/描边都走 drawable 资源，代码里只负责动态拼账号行。
 */
public final class MainActivity extends Activity {

    private static final int REQ_NOTIFY = 1001;

    // 配色，与 drawable 里保持一致
    private static final int C_TEXT = 0xFF2B2430;
    private static final int C_SUB = 0xFF7A7290;
    private static final int C_HINT = 0xFFA9A3BC;
    private static final int C_ACCENT = 0xFF6C5CE7;

    private TextView statusText;
    private TextView detailText;
    private TextView webuiText;
    /** 只在 token 还是临时的时候才显示；设过密码就没必要露出来了。 */
    private Button btnCopyToken;
    private TextView envVersion;
    private TextView envSubtitle;
    private ProgressBar progress;
    private Button btnStart;
    private Button btnStop;
    private Button btnWebui;
    private Button btnConsole;
    /**
     * 「重装整个环境」按钮。
     * <p>
     * 注意类型是 TextView 不是 Button —— 布局里用的是 TextView + 背景，
     * 因为 Button 自带最小高度和内边距，在这种整行按钮里会撑高。
     * 之前这里写成 Button 导致启动就 ClassCastException 闪退，别再改回去。
     */
    private android.widget.TextView btnReinstall;

    // 环境管理（可展开）
    private LinearLayout envHeader;
    private LinearLayout envBody;
    private LinearLayout envList;
    private ImageView envArrow;
    /** 记住展开状态，切页面回来不至于又收起。 */
    private boolean envExpanded = false;
    /** 正在补装的项 key，防止同一项被连点两下。 */
    private String envBusyKey;

    /**
     * 上一次检查到的版本情况，按 key 存（"maibot" / "napcat"）。
     * 展开环境管理时会清空并重新查，因为用户可能刚点完重装。
     */
    private final java.util.Map<String, com.maimai.bot.core.UpdateChecker.Info> envUpdates
            = new java.util.HashMap<>();
    /** 正在查更新中，避免连点展开按钮时开好几个线程。 */
    private boolean envChecking = false;

    /** 加速器弹窗（测速中可能被反复重建，留着引用方便取消）。 */
    private AlertDialog accelDialog;
    /** 测速任务代数：每次重新测速 +1，旧任务回来发现代数不对就丢弃结果。 */
    private int accelGen;

    // QQ（NapCat + LinuxQQ）
    /** 已登录账号列表容器 + 提示。 */
    private LinearLayout accountList;
    private TextView accountHint;
    /** 扫码框是否正开着，避免重复弹。 */
    private boolean qrDialogShowing;
    private android.app.Dialog qrDialog;

    // 底部导航
    private LinearLayout tabHome;
    private LinearLayout tabWebui;
    private LinearLayout tabTerm;
    private ImageView icHome;
    private ImageView icWebui;
    private ImageView icTerm;
    private TextView tvHome;
    private TextView tvWebui;
    private TextView tvTerm;

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 整个主页的滚动容器。刷新状态时要把它的滚动位置兜住。 */
    private ScrollView scrollRoot;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            // 记住当前滚动位置，刷完再放回去。
            //
            // 之前每秒刷一次状态，只要有个 TextView 的 setText 触发了重新排版，
            // ScrollView 就会把滚动位置丢掉、跳回顶部 —— 表现就是
            // 「启动麦麦之后往下滑，滑一点弹回上面，永远滑不到底」。
            // 状态本来就该刷新，所以刷新前后把位置兜住，是最稳的改法。
            int y = scrollRoot == null ? 0 : scrollRoot.getScrollY();
            render();
            refreshQqRowStates();
            if (scrollRoot != null && scrollRoot.getScrollY() != y) {
                scrollRoot.scrollTo(0, y);
            }
            ui.postDelayed(this, 1000);
        }
    };

    /**
     * 只更新账号卡片上的「运行中 / 未运行」标签，不重建整个列表。
     * <p>
     * 为什么需要它：NapCat 是异步起来的，点完启动要几十秒端口才监听。
     * 之前卡片状态只在 {@link #renderAccounts()} 里算一次，而那个方法开头
     * 有 {@code if (accountsRendered) return;} 挡着，于是：
     *   - 启动瞬间渲染 → 当时端口没起 → 显示「未运行」
     *   - 之后端口起来了，但没人再算 → 永远停在「未运行」
     * 真机上就是「NapCat 面板能打开、账号也登录了，主页那张卡还写着未运行」。
     * <p>
     * 这里每轮 ticker 只重算两个布尔值并 setText，不动布局，
     * 所以不会引起列表抖动，也不影响滚动位置。
     */
    private void refreshQqRowStates() {
        if (qqRowStates == null || qqRowStates.isEmpty()) {
            return;
        }
        for (int i = 0; i < qqRowStates.size(); i++) {
            QqRowState st = qqRowStates.get(i);
            if (st == null || st.badge == null) {
                continue;
            }
            boolean running;
            boolean onebotUp;
            try {
                // 先刷新「已登录」判据：NapCat 登录成功会写 onebot11_<uin>.json，
                // 这比看 3001 端口更可靠（端口开着不代表登录了）。
                Env env = BotState.get(this).env();
                String uin = st.account.detectUin(env);
                st.account.loggedInUin = uin;
                if (uin != null && !uin.isEmpty()) {
                    st.account.uin = uin;
                    // 登录成功后自动把麦麦适配器指向这个账号。
                    //
                    // 为什么必须自动做：之前只在三点菜单里手动「绑定 BOT」时才会
                    // 写 AdapterConfig，而那个配置默认是
                    //   [plugin] enabled = false
                    //   [client] token = ""
                    // 结果就是「NapCat 登录得好好的，麦麦却什么都没收到」
                    // （真机实测：ss 看 3001 上一个 ESTABLISHED 连接都没有）。
                    // 这里每次刷新只写一次，避免每秒都落盘。
                    if (!st.adapterBound) {
                        st.adapterBound = true;
                        try {
                            AdapterConfig.bind(this, st.account);
                        } catch (Exception ignored) {
                        }
                    }
                }
                running = st.account.isRunning(BotState.get(this).env());
                onebotUp = st.account.isOneBotUp(BotState.get(this).env());
            } catch (Exception e) {
                continue;
            }
            if (running == st.lastRunning && onebotUp == st.lastOnebotUp) {
                continue;
            }
            boolean runningChanged = running != st.lastRunning;
            st.lastRunning = running;
            st.lastOnebotUp = onebotUp;

            // 徽标三态：未运行 / 等待扫码 / 已登录
            if (!running) {
                st.badge.setText("未运行");
                st.badge.setTextColor(0xFFA9A3BC);
                st.badge.setBackgroundResource(R.drawable.bg_badge_gray);
            } else if (onebotUp) {
                st.badge.setText("已登录");
                st.badge.setTextColor(0xFF1E9E54);
                st.badge.setBackgroundResource(R.drawable.bg_badge_green);
            } else {
                st.badge.setText("等待扫码");
                st.badge.setTextColor(0xFFE17055);
                st.badge.setBackgroundResource(R.drawable.bg_badge_gray);
            }
            // 圆形按钮跟着在「播放/暂停」之间切
            if (runningChanged && st.circle != null) {
                st.circle.setImageResource(running ? R.drawable.ic_pause : R.drawable.ic_play);
            }
        }
    }

    /** 账号卡片上那些要随状态变化的控件引用，供 ticker 定时刷新。 */
    private static final class QqRowState {
        final NapCatAccount account;
        final TextView badge;
        final android.widget.ImageView circle;
        boolean lastRunning;
        boolean lastOnebotUp;
        /** 是否已经把麦麦适配器绑定到本账号（只绑一次，不每秒落盘）。 */
        boolean adapterBound;

        QqRowState(NapCatAccount account, TextView badge, android.widget.ImageView circle,
                   boolean running, boolean onebotUp) {
            this.account = account;
            this.badge = badge;
            this.circle = circle;
            this.lastRunning = running;
            this.lastOnebotUp = onebotUp;
        }
    }

    private final List<QqRowState> qqRowStates = new ArrayList<>();

    private boolean accountsRendered;

    /**
     * 上一次 render 时的运行状态。
     * <p>
     * 用来识别「刚变成 RUNNING」这个瞬间：maibot 每次启动都会重写 webui.json
     * （没设密码时换新的临时 token），所以那一下要重新读一次文件，
     * 否则界面上会一直显示上一次启动的旧 token。
     */
    private BotState.Status lastStatus = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        wireActions();
        requestNotificationPermission();
        ensureBaseline();
        renderAccounts();
        render();

        // 冷启动时查一次 App 有没有新版本。
        //
        // 只在这一刻查：AppUpdatePrompt 内部用静态标记挡住了「同进程重复查」，
        // 所以退到后台再回来不会再弹一次；但把任务划掉重开（新进程）会重新查。
        // 另外传 savedInstanceState 进去，是为了让横竖屏旋转重建时不再弹窗。
        // 放在 onCreate 最后：前面那些初始化更重要，更新检查是后台线程，不挡流程。
        com.maimai.bot.core.AppUpdatePrompt.maybeCheckOnLaunch(
                this, savedInstanceState, appVersionName());
    }

    /** 读本 App 的 versionName，读不到返回空串。 */
    private String appVersionName() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setTab(Tab.HOME);
        // 从 WebUI 配完账号回到 App 时要能看到新条目，所以每次回来都重渲染
        accountsRendered = false;
        renderAccounts();
        // 回前台时重渲账号列表（可能刚在终端里起停过）
        accountsRendered = false;
        renderAccounts();
        // 从 WebUI 里设完密码回来，token 就不该再显示了，所以每次回前台都刷一次
        BotState.get(this).refreshWebuiToken();
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    // ------------------------------------------------------------- 绑定

    private void bindViews() {
        statusText = findViewById(R.id.status_text);
        scrollRoot = findViewById(R.id.scroll_root);
        detailText = findViewById(R.id.detail_text);
        webuiText = findViewById(R.id.webui_text);
        btnCopyToken = findViewById(R.id.btn_copy_token);
        envVersion = findViewById(R.id.env_version);
        envSubtitle = findViewById(R.id.env_subtitle);
        progress = findViewById(R.id.progress);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnWebui = findViewById(R.id.btn_webui);
        btnConsole = findViewById(R.id.btn_console);
        btnReinstall = findViewById(R.id.btn_reinstall);
        envHeader = findViewById(R.id.env_header);
        envBody = findViewById(R.id.env_body);
        envList = findViewById(R.id.env_list);
        envArrow = findViewById(R.id.env_arrow);
        // 注意：布局里这个是 TextView 不是 Button，
        // 之前 btn_reinstall 就是因为类型写错导致启动闪退，别再踩。
        TextView btnAccel = findViewById(R.id.btn_accel);
        btnAccel.setOnClickListener(v -> showAccelDialog());

        // QQ（NapCat）卡片：右上角「+」新增账号，下面按账号列表渲染
        accountList = findViewById(R.id.account_list);
        accountHint = findViewById(R.id.account_hint);
        View addBtn = findViewById(R.id.btn_qq_add);
        if (addBtn != null) {
            addBtn.setOnClickListener(v -> addAccount());
        }

        tabHome = findViewById(R.id.tab_home);
        tabWebui = findViewById(R.id.tab_webui);
        tabTerm = findViewById(R.id.tab_term);
        icHome = findViewById(R.id.ic_tab_home);
        icWebui = findViewById(R.id.ic_tab_webui);
        icTerm = findViewById(R.id.ic_tab_term);
        tvHome = findViewById(R.id.tv_tab_home);
        tvWebui = findViewById(R.id.tv_tab_webui);
        tvTerm = findViewById(R.id.tv_tab_term);
    }

    private void wireActions() {
        btnStart.setOnClickListener(v -> startBot());
        btnStop.setOnClickListener(v -> stopBot());
        btnWebui.setOnClickListener(v -> openWebui());
btnCopyToken.setOnClickListener(v -> copyToken());

        // 右上角齿轮：进设置页。里面再进「关于」看仓库地址和开源协议。
        android.view.View btnSettingsEntry = findViewById(R.id.btn_settings_entry);
        if (btnSettingsEntry != null) {
            btnSettingsEntry.setOnClickListener(v ->
                    startActivity(new Intent(this, SettingsActivity.class)));
        }

        btnConsole.setOnClickListener(v ->
                startActivity(new Intent(this, ConsoleActivity.class)));
        btnReinstall.setOnClickListener(v -> confirmReinstall());
        // 环境管理：点标题栏展开/收起
        if (envHeader != null) {
            envHeader.setOnClickListener(v -> toggleEnv());
        }
        // 「更新基础环境（不含麦麦与 QQ 桥接）」这个按钮已按用户要求删除，
        // 这里不再绑定它的监听。「重装整个环境」走的是独立的 btn_reinstall，
        // 不受影响。
        // 顶部齿轮按钮已经删掉（用户要求首页更干净），这里不再绑定。
        tabHome.setOnClickListener(v -> setTab(Tab.HOME));
        tabWebui.setOnClickListener(v -> {
            setTab(Tab.WEBUI);
            openWebui();
        });
        tabTerm.setOnClickListener(v -> {
            setTab(Tab.TERM);
            startActivity(new Intent(this, ConsoleActivity.class));
        });
    }

    // ------------------------------------------------------------- 业务

    private void ensureBaseline() {
        BotState st = BotState.get(this);
        Installer inst = new Installer(this);
        if (inst.isInstalled()) {
            if (st.status() == BotState.Status.NOT_INSTALLED) {
                st.setStatus(BotState.Status.STOPPED, "环境就绪，点击启动");
            }
        } else {
            st.setStatus(BotState.Status.NOT_INSTALLED, "首次使用需安装运行环境");
        }
    }

    private void startBot() {
        Intent i = new Intent(this, BotService.class).setAction(BotService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        // 点完启动直接进日志页，实时跟随麦麦的启动过程。
        //
        // 这是对齐 AstrBot 的体验：它点完启动就会切到一个滚动着的日志窗口，
        // 一行一行看到「装了什么、初始化了什么、什么时候好了」。
        // 之前我们点完只是发了个 Intent 给 Service，界面停在主页不动，
        // 用户只能干等，完全不知道后台在干什么 —— 这就是
        // 「好多日志都没显示出来」的根源。
        Intent log = new Intent(this, ConsoleActivity.class);
        log.putExtra(ConsoleActivity.EXTRA_FOLLOW_MAIBOT, true);
        startActivity(log);
    }

    private void stopBot() {
        Intent i = new Intent(this, BotService.class).setAction(BotService.ACTION_STOP);
        startService(i);
        Toast.makeText(this, "正在停止", Toast.LENGTH_SHORT).show();
    }

    private void openWebui() {
        // 内置 WebView，不再跳外部浏览器。
        // 没启动时也照样进去：内置页自己会显示"未启动"提示，比弹窗被打断好。
        BotState st = BotState.get(this);
        if (st.status() != BotState.Status.RUNNING) {
            Toast.makeText(this, "麦麦还没启动，正在打开 WebUI 页面…", Toast.LENGTH_SHORT).show();
        }
        startActivity(new Intent(this, WebUiActivity.class));
    }

    private void confirmReinstall() {
        new AlertDialog.Builder(this)
                .setTitle("重装运行环境")
                .setMessage("会删除已解压的 Ubuntu 环境与全部 MaiBot 数据，重新安装一遍。"
                        + "确定继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("重装", (d, w) -> doReinstall())
                .show();
    }

    private void doReinstall() {
        BotState st = BotState.get(this);
        // 先停掉进程，避免边删边写
        startService(new Intent(this, BotService.class).setAction(BotService.ACTION_STOP));
        new Thread(() -> {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException ignored) {
            }
            st.env().clearRootfs();
            st.clearLog();
            st.setStatus(BotState.Status.NOT_INSTALLED, "环境已清除，准备重装");
            runOnUiThread(() -> {
                Toast.makeText(this, "已清除，点击启动即开始重装", Toast.LENGTH_LONG).show();
                render();
            });
        }, "reinstall").start();
    }

    // ------------------------------------------------------------- 账号行

    /**
     * 渲染账号列表（多实例）。
     * <p>
     * 每个账号是独立 NapCat 实例：独立端口、独立 token、独立 QQ 登录态。
     * 行内三个操作图标对应三种常用动作：打开面板 / 启动 / 更多菜单，
     * 「更多」里有编辑、绑定 BOT、复制 token、复制完整链接、退出登录、删除 ——
     * 形状和 AstrBot 的 NapCat 卡片保持一致。
     */
    private void renderAccounts() {
        if (accountsRendered || accountList == null) {
            return;
        }
        accountsRendered = true;
        accountList.removeAllViews();
        // 重新构建列表前先清掉旧的控件引用，否则会一直堆积、
        // ticker 还会去改已经被移除的 View（浪费而且容易出怪状态）。
        qqRowStates.clear();
        List<NapCatAccount> list = NapCatAccount.loadAll(this);
        if (list.isEmpty()) {
            accountList.addView(buildEmptyState());
        } else {
            for (int i = 0; i < list.size(); i++) {
                accountList.addView(buildAccountRow(list.get(i)));
                if (i != list.size() - 1) {
                    View spacer = new View(this);
                    spacer.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
                    accountList.addView(spacer);
                }
            }
        }
        accountHint.setVisibility(View.GONE);
    }

    /** 一个账号都没配时的占位块：说明现状 + 指路。 */
    private View buildEmptyState() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setBackgroundResource(R.drawable.bg_row_soft);
        box.setPadding(dp(16), dp(22), dp(16), dp(22));
        box.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("还没有 QQ 账号");
        title.setTextColor(C_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setGravity(Gravity.CENTER);
        box.addView(title);
        TextView how = new TextView(this);
        how.setText("点右上角「+」添加一个账号，然后点启动扫码登录");
        how.setTextColor(C_SUB);
        how.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        how.setGravity(Gravity.CENTER);
        how.setPadding(0, dp(4), 0, 0);
        box.addView(how);

        TextView warn = new TextView(this);
        warn.setText("没有 QQ 账号，麦麦收不到任何消息");
        warn.setTextColor(C_HINT);
        warn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        warn.setGravity(Gravity.CENTER);
        warn.setPadding(0, dp(8), 0, 0);
        box.addView(warn);

        return box;
    }

    private View buildAccountRow(final NapCatAccount a) {
        Env env = BotState.get(this).env();
        // 每次渲染都从磁盘核对一下登录态，避免用缓存的旧值
        NapCat.refreshUin(env, a);
        // 首次渲染时 loggedInUin 还没被 ticker 填过，这里先填一次，
        // 否则第一帧会把已登录的账号画成「等待扫码」。
        String uin = a.detectUin(env);
        a.loggedInUin = uin;
        if (uin != null && !uin.isEmpty()) {
            a.uin = uin;
        }
        final boolean running = a.isRunning(env);
        final boolean onebotUp = a.isOneBotUp(env);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_row_soft);
        row.setPadding(dp(10), dp(10), dp(8), dp(10));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 左：圆形启停按钮。点一下就在「启动并扫码」和「停止」之间切。
        ImageView circle = new ImageView(this);
        circle.setImageResource(running ? R.drawable.ic_pause : R.drawable.ic_play);
        circle.setColorFilter(C_SUB);
        circle.setBackgroundResource(R.drawable.bg_circle_white);
        circle.setPadding(dp(7), dp(7), dp(7), dp(7));
        circle.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
        circle.setOnClickListener(v -> {
            // 判定必须和图标显示的判定同源。
            //
            // 之前这里直接调 a.isRunning()，而图标是由 refreshQqRowStates()
            // 用同一批数据算出来的 —— 但 isRunning() 里有一条兜底
            // ProcessKiller.hasNapCatProcess()，它扫的是「本 App 有没有任何
            // QQ/NapCat 进程」，并不区分是哪个实例、也不管这个实例是否刚被停掉。
            // 结果就是：点了停止 → NapCat.stop() 杀完进程 → 界面重渲染时
            // hasNapCatProcess() 还看到残留的 zygote/Xvfb → isRunning() 仍为 true
            // → 图标继续显示 ⏸ → 用户再点，又走 stopAccount（看起来"没关掉"）；
            // 而某些时刻进程清干净了 isRunning() 变 false、图标却还没刷新，
            // 又会走 startAccount。两个来源不同步，就会出现
            // 「图标是⏸，点下去却在启动」。
            //
            // 现在改成：以「这个实例的 WebUI 端口是否在听」为主判据
            // （这才是这个实例自己的状态），进程兜底只在端口探测完全不可用时生效。
            final Env clickEnv = BotState.get(this).env();
            boolean alive = a.isRunning(clickEnv);
            if (alive) {
                // 圆形按钮是「启停键」，不是「打开面板」。
                //
                // 上一版我在这里改成了「已登录就 openAccountPanel」，
                // 结果用户点那个 ⏸ 想停止时，反而跳去了 WebUI 面板 ——
                // 这违背了按钮本身的语义（图标是暂停，动作就该是停）。
                // 现在恢复成：只要在跑，点它就是停止。
                //
                // 「已登录想看面板」这条需求由卡片右侧那个地球图标承担，
                // 那里调 openAccountPanel，职责分明。
                stopAccount(a);
            } else {
                // 端口没在听，但可能真有进程在跑（端口探测在沙盒里偶发失败），
                // 这种情况再问一次进程兜底，避免重复启动同一个实例。
                if (ProcessKiller.hasNapCatProcess()) {
                    stopAccount(a);
                } else {
                    startAccount(a);
                }
            }
        });
        row.addView(circle);

        // 中：两行文字（名称+状态徽标 / QQ号 / 端口）
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.setMarginStart(dp(10));
        mid.setLayoutParams(mlp);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(a.label);
        name.setTextColor(C_TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        titleRow.addView(name);

        TextView badge = new TextView(this);
        if (!running) {
            badge.setText("未运行");
            badge.setTextColor(0xFFA9A3BC);
            badge.setBackgroundResource(R.drawable.bg_badge_gray);
        } else if (onebotUp) {
            badge.setText("已登录");
            badge.setTextColor(0xFF1E9E54);
            badge.setBackgroundResource(R.drawable.bg_badge_green);
        } else {
            badge.setText("等待扫码");
            badge.setTextColor(0xFFE17055);
            badge.setBackgroundResource(R.drawable.bg_badge_gray);
        }
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        badge.setPadding(dp(7), dp(2), dp(7), dp(2));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMarginStart(dp(7));
        badge.setLayoutParams(blp);
        titleRow.addView(badge);

        // 把徽标和圆形按钮登记下来，交给 ticker 每秒校正一次真实状态。
        // 不这么做的话，卡片状态只在构建时算一次，NapCat 后来起来了也不会变，
        // 用户就会一直看到「未运行」。
        qqRowStates.add(new QqRowState(a, badge, circle, running, onebotUp));

        mid.addView(titleRow);

        TextView qq = new TextView(this);
        qq.setText(a.uin.isEmpty() ? "QQ 未登录" : ("QQ " + a.uin));
        qq.setTextColor(C_TEXT);
        qq.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        qq.setPadding(0, dp(2), 0, 0);
        mid.addView(qq);

        TextView port = new TextView(this);
        port.setText("WebUI " + a.webuiPort + " · OneBot " + a.onebotPort);
        port.setTextColor(C_SUB);
        port.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        mid.addView(port);

        // Token 那一行：直接把面板登录密钥摆在卡片上，跟麦麦那块保持一致。
        //
        // 之前 token 只藏在右上角三点菜单里（「复制 token」），
        // 用户打开 NapCat 的 Web 登录页时，页面只提示
        // 「请从 NapCat 启动日志中查看登录密钥」，于是得先退出去翻日志。
        // 现在照着麦麦卡片的做法，就地显示 + 一键复制。
        LinearLayout tokenRow = new LinearLayout(this);
        tokenRow.setOrientation(LinearLayout.HORIZONTAL);
        tokenRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(4);
        tokenRow.setLayoutParams(tlp);

        boolean hasToken = a.webuiToken != null && !a.webuiToken.isEmpty();

        TextView tokenText = new TextView(this);
        tokenText.setText(hasToken ? ("Token " + a.webuiToken) : "Token 尚未生成");
        tokenText.setTextColor(hasToken ? C_TEXT : C_SUB);
        // 等宽字体：token 是十六进制串，等宽才好一个字一个字核对
        tokenText.setTypeface(android.graphics.Typeface.MONOSPACE);
        tokenText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tokenText.setSingleLine(true);
        tokenText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        tokenText.setTextIsSelectable(true);
        LinearLayout.LayoutParams txtLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tokenText.setLayoutParams(txtLp);
        tokenRow.addView(tokenText);

        if (hasToken) {
            TextView copy = new TextView(this);
            copy.setText("复制");
            copy.setTextColor(0xFF4A3F7A);
            copy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            copy.setBackgroundResource(R.drawable.bg_badge_gray);
            copy.setPadding(dp(8), dp(3), dp(8), dp(3));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMarginStart(dp(6));
            copy.setLayoutParams(clp);
            copy.setOnClickListener(v -> copyToClipboard("NapCat token", a.webuiToken));
            tokenRow.addView(copy);
        }
        mid.addView(tokenRow);

        row.addView(mid);

        // 右：面板 / 启动 / 更多
        ImageView globe = smallIcon(R.drawable.ic_globe);
        globe.setOnClickListener(v -> openAccountPanel(a));
        row.addView(globe);

        ImageView play = smallIcon(R.drawable.ic_play);
        play.setOnClickListener(v -> startAccount(a));
        row.addView(play);

        ImageView more = smallIcon(R.drawable.ic_more);
        more.setOnClickListener(v -> showAccountMenu(a));
        row.addView(more);

        return row;
    }

    /** 面板 / 启动 / 更多 里用到的小图标。 */
    private ImageView smallIcon(int res) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(C_SUB);
        iv.setPadding(dp(5), dp(5), dp(5), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(28));
        lp.setMarginStart(dp(2));
        iv.setLayoutParams(lp);
        return iv;
    }

    /** 三点菜单：对齐 AstrBot 的 NapCat 卡片。 */
    private void showAccountMenu(final NapCatAccount a) {
        final String[] items = {
                "编辑",
                "绑定 BOT",
                "复制 token",
                "复制完整链接",
                "查看启动日志",
                "退出登录",
                "删除"
        };
        new AlertDialog.Builder(this)
                .setItems(items, (dlg, which) -> {
                    switch (which) {
                        case 0:
                            editAccount(a);
                            break;
                        case 1:
                            bindBot(a);
                            break;
                        case 2:
                            copyToClipboard("NapCat token", a.webuiToken);
                            break;
                        case 3:
                            copyToClipboard("NapCat 面板链接",
                                    "http://127.0.0.1:" + a.webuiPort
                                            + "/webui?token=" + a.webuiToken);
                            break;
                        case 4:
                            showFullBootLog();
                            break;
                        case 5:
                            logoutAccount(a);
                            break;
                        case 6:
                            deleteAccount(a);
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    // ------------------------------------------------------------- 账号操作

    /** 启动一个账号：拉起 NapCat → 等 token → 打开面板（自动登录）。 */
    private void startAccount(final NapCatAccount a) {
        if (!NapCat.isInstalled(BotState.get(this).env())) {
            Toast.makeText(this, "NapCat 环境不完整，请先在「环境管理」里安装", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "正在启动 " + a.label + "…首次约 30 秒", Toast.LENGTH_SHORT).show();
        // 先把行刷成「启动中」，让用户知道点了有反应
        accountsRendered = false;
        renderAccounts();
        // 直接进扫码页：它一边显示「正在准备二维码」，一边显示启动进度，
        // 二维码一落盘就自动弹出来给你扫。
        //
        // 为什么不再跳 ConsoleActivity：日志页会把二维码挤在后面，
        // 用户还得自己滚回去找；而且 NapCat 的二维码是 ASCII 画的，
        // 在日志里根本没法扫。现在日志留在扫码页的「看启动日志」按钮里，
        // 需要排查时一键可达。
        // 已经登录过的账号（日志里有「登录成功」且没掉线）就不用看二维码了，
        // 直接开面板；只有没登录过的才需要进扫码页。
        //
        // 这样区分是为了避免把「快速登录正常」的用户也丢到扫码页上，
        // 让他对着一个根本不需要扫的码发呆。
        if (a.isLoggedIn(BotState.get(this).env())) {
            a.uin = a.detectUin(BotState.get(this).env());
            if (a.uin == null) {
                a.uin = "";
            }
            openAccountPanel(a);
        } else {
            Intent qrIntent = new Intent(this, QrActivity.class);
            qrIntent.putExtra(QrActivity.EXTRA_ACCOUNT_ID, a.id);
            qrIntent.putExtra(QrActivity.EXTRA_ACCOUNT_LABEL, a.label);
            startActivity(qrIntent);
        }
        new Thread(() -> {
            Env env = BotState.get(this).env();
            String err = NapCat.start(this, env, a);
            if (err != null) {
                runOnUiThread(() -> Toast.makeText(this, err, Toast.LENGTH_LONG).show());
                return;
            }
            // 等 NapCat 打印 token（它可能自己重生成，所以必须以日志为准）
            String token = NapCat.waitForToken(env, a, 45_000);
            if (token != null && !token.isEmpty() && !token.equals(a.webuiToken)) {
                a.webuiToken = token;
                List<NapCatAccount> all = NapCatAccount.loadAll(this);
                for (NapCatAccount x : all) {
                    if (x.id.equals(a.id)) {
                        x.webuiToken = token;
                    }
                }
                NapCatAccount.saveAll(this, all);
            }
            NapCat.refreshUin(env, a);
            // 这里不再自动跳 WebUI 面板：
            // 未登录时面板是登录不进去的（之前就卡在这），
            // 交给 QrActivity 自己判断 —— 它检测到登录成功才会跳面板。
            runOnUiThread(() -> {
                accountsRendered = false;
                renderAccounts();
            });
        }, "napcat-start").start();
    }

/**
     * 启动过程日志弹窗：实时跟随 files/napcat.log。
     * <p>
     * 为什么要有这个：之前点启动没反应时，用户界面上看不到任何东西 ——
     * 日志只打在 logcat 里，应用内没有入口。现在启动的第一步就把日志文件建出来，
     * 这里每 400ms 重新读一遍，用户能亲眼看到「建目录 / 写配置 / 起 Xvfb /
     * 拉起 QQ / 等端口」每一步，跟 AstrBot 的启动输出一个体感。
     */

    /** 读文件最后 n 行。 */
    private static String readTail(File f, int n) {
        if (f == null || !f.isFile()) {
            return "（日志还没生成）";
        }
        java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                q.addLast(line);
                if (q.size() > n) {
                    q.removeFirst();
                }
            }
        } catch (Exception e) {
            return "（读日志失败：" + e + "）";
        }
        StringBuilder sb = new StringBuilder();
        for (String s : q) {
            sb.append(s).append('\n');
        }
        return sb.toString();
    }

    /** 完整日志查看（三点菜单里用）。 */
    private void showFullBootLog() {
        final BootLog log = new BootLog(BotState.get(this).env().base());
        String text = readTail(log.file(), 400);
        new AlertDialog.Builder(this)
                .setTitle("NapCat 启动日志")
                .setMessage(text)
                .setPositiveButton("关闭", null)
                .show();
    }

        private void stopAccount(final NapCatAccount a) {
        // 立刻给个反馈。之前点了之后只有图标变，如果判断链路有任何延迟，
        // 用户会觉得「点了没反应」而反复点。
        Toast.makeText(this, "正在停止 " + a.label + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            NapCat.stop(BotState.get(this).env(), a);
            runOnUiThread(() -> {
                accountsRendered = false;
                renderAccounts();
            });
        }, "napcat-stop").start();
    }

    /** 打开某个账号的面板，并带上 token 自动登录。 */
    private void openAccountPanel(NapCatAccount a) {
        // 这里必须用带 Env 的 isRunning —— 无参版本只看端口，
        // 而 App 在 untrusted_app_27 域下读不到 /proc/net/tcp、
        // loopback connect 也常失败，于是「明明已登录却提示还没启动」。
        // 带 Env 的版本会用日志兜底，才是真实状态。
        if (!a.isRunning(BotState.get(this).env())) {
            Toast.makeText(this, a.label + " 还没启动", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, WebUiActivity.class);
        i.putExtra(WebUiActivity.EXTRA_SHOW_QQ, true);
        i.putExtra(WebUiActivity.EXTRA_QQ_PORT, a.webuiPort);
        i.putExtra(WebUiActivity.EXTRA_QQ_TOKEN, a.webuiToken);
        startActivity(i);
    }

    /** 编辑账号：改显示名。 */
    private void editAccount(final NapCatAccount a) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(a.label);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("编辑 " + a.label)
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String v = input.getText().toString().trim();
                    if (v.isEmpty()) {
                        v = a.label;
                    }
                    List<NapCatAccount> all = NapCatAccount.loadAll(this);
                    for (NapCatAccount x : all) {
                        if (x.id.equals(a.id)) {
                            x.label = v;
                        }
                    }
                    NapCatAccount.saveAll(this, all);
                    accountsRendered = false;
                    renderAccounts();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 绑定 BOT：把这个账号的 OneBot 端口/token 写进麦麦的适配器配置。
     * <p>
     * 麦麦的 OneBot 适配器是「客户端」，所以这里是把它的 host/port/token
     * 写成这个账号的 OneBot 端口/token，然后提示用户重启麦麦生效。
     */
    private void bindBot(final NapCatAccount a) {
        if (a.uin.isEmpty()) {
            Toast.makeText(this, "这个账号还没登录 QQ，先启动并扫码", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("绑定到麦麦")
                .setMessage("将把麦麦的 QQ 适配器指向：\n"
                        + "地址 127.0.0.1\n端口 " + a.onebotPort + "\ntoken " + a.onebotToken
                        + "\n\n绑定后需要重启麦麦才会生效。")
                .setPositiveButton("绑定", (d, w) -> {
                    boolean ok = AdapterConfig.bind(this, a);
                    Toast.makeText(this,
                            ok ? "已绑定，重启麦麦后生效" : "写入适配器配置失败，看运行日志",
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 退出登录：删掉这个实例的 QQ 登录态（保留账号条目）。 */
    private void logoutAccount(final NapCatAccount a) {
        new AlertDialog.Builder(this)
                .setTitle("退出登录")
                .setMessage("将清除 " + a.label + " 的 QQ 登录态，下次需要重新扫码。\n"
                        + "账号条目会保留。")
                .setPositiveButton("退出登录", (d, w) -> {
                    Env env = BotState.get(this).env();
                    // 先停实例，否则删了缓存它还在跑
                    NapCat.stop(env, a);
                    File home = a.homeDir(env);
                    deleteRecursive(new File(home, ".config/QQ"));
                    a.uin = "";
                    List<NapCatAccount> all = NapCatAccount.loadAll(this);
                    for (NapCatAccount x : all) {
                        if (x.id.equals(a.id)) {
                            x.uin = "";
                        }
                    }
                    NapCatAccount.saveAll(this, all);
                    accountsRendered = false;
                    renderAccounts();
                    Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 删除账号：停实例 + 删整个实例目录 + 从列表移除。 */
    private void deleteAccount(final NapCatAccount a) {
        new AlertDialog.Builder(this)
                .setTitle("删除 " + a.label)
                .setMessage("将删除这个账号的全部数据（登录态、配置、日志），不可恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    Env env = BotState.get(this).env();
                    NapCat.stop(env, a);
                    // 先改列表再删目录：万一删目录因为权限问题抛错，
                    // 也已经把「这个账号不该再出现」这件事落盘了，
                    // 不会出现「界面点了删除、结果它又长回来」。
                    List<NapCatAccount> all = NapCatAccount.loadAll(this);
                    List<NapCatAccount> keep = new ArrayList<>();
                    for (NapCatAccount x : all) {
                        if (!x.id.equals(a.id)) {
                            keep.add(x);
                        }
                    }
                    NapCatAccount.saveAll(this, keep);

                    deleteRecursive(a.workDir(env));
                    deleteRecursive(a.homeDir(env));

                    accountsRendered = false;
                    renderAccounts();
                    Toast.makeText(this, "已删除 " + a.label, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 新增一个账号（右上角「+」）。 */
    private void addAccount() {
        List<NapCatAccount> all = NapCatAccount.loadAll(this);
        NapCatAccount a = NapCatAccount.createNew(this, all);
        a.writeConfig(BotState.get(this).env());
        all.add(a);
        NapCatAccount.saveAll(this, all);
        accountsRendered = false;
        renderAccounts();
        Toast.makeText(this, "已添加 " + a.label + "（WebUI " + a.webuiPort + "）",
                Toast.LENGTH_SHORT).show();
    }

    private void copyToClipboard(String label, String text) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursive(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }




    private static String stringValue(String line) {
        int eq = line.indexOf('=');
        if (eq < 0) {
            return null;
        }
        String v = line.substring(eq + 1).trim();
        int hash = v.indexOf('#');
        if (hash >= 0) {
            v = v.substring(0, hash).trim();
        }
        if (v.length() >= 2 && v.charAt(0) == '"' && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v.trim();
    }

    private static List<String> arrayValues(String line) {
        List<String> vals = new ArrayList<>();
        int lb = line.indexOf('[');
        int rb = line.indexOf(']');
        if (lb < 0 || rb <= lb) {
            return vals;
        }
        String body = line.substring(lb + 1, rb);
        for (String part : body.split(",")) {
            String v = part.trim();
            if (v.length() >= 2 && v.charAt(0) == '"' && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1);
            }
            if (!v.isEmpty()) {
                vals.add(v);
            }
        }
        return vals;
    }

    // ------------------------------------------------------------- 导航

    private enum Tab {HOME, WEBUI, TERM}

    private void setTab(Tab t) {
        styleTab(tabHome, icHome, tvHome, t == Tab.HOME);
        styleTab(tabWebui, icWebui, tvWebui, t == Tab.WEBUI);
        styleTab(tabTerm, icTerm, tvTerm, t == Tab.TERM);
    }

    private void styleTab(LinearLayout container, ImageView icon, TextView label, boolean active) {
        if (container == null) {
            return;
        }
        container.setBackgroundResource(active ? R.drawable.bg_nav_active : 0);
        int tint = active ? C_ACCENT : C_HINT;
        icon.setColorFilter(tint);
        label.setTextColor(active ? 0xFF4A3F7A : C_HINT);
    }

    // ------------------------------------------------------------- 渲染

    /**
     * 把当前 WebUI 临时 Token 复制到剪贴板。
     * <p>
     * 首登流程是：点开控制台 → 要求输入 token → 然后强制设密码。
     * 手机上手抄那一段 hex 太折磨，所以直接给个一键复制。
     */
    private void copyToken() {
        BotState st = BotState.get(this);
        String token = st.webuiToken();
        if (token == null || token.isEmpty()) {
            Toast.makeText(this, "凭证还没生成，稍等几秒", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) {
                Toast.makeText(this, "剪贴板不可用", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean isPwd = st.webuiPasswordConfigured();
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    isPwd ? "maibot-password" : "maibot-token", token));
            Toast.makeText(this, isPwd ? "密码已复制" : "Token 已复制", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "复制失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------- 环境管理（可展开）

    /**
     * 展开/收起环境管理。
     * <p>
     * 原来这块是一张静态卡片，只挂着一个「重装环境」按钮 ——
     * 用户根本看不到环境里到底缺什么，点重装还会把已装好的 maibot 一起清掉。
     * 改成可展开的清单后，每一项都能单独看状态、单独补装。
     */
    private void toggleEnv() {
        envExpanded = !envExpanded;
        envBody.setVisibility(envExpanded ? View.VISIBLE : View.GONE);
        // 箭头转半圈，方向跟着状态走
        envArrow.animate()
                .rotation(envExpanded ? 180f : 0f)
                .setDuration(180)
                .start();
        if (envExpanded) {
            // 每次展开都重新自检：用户可能刚在终端里手动装过东西
            renderEnvList();
            // 顺手查一次 NapCat / 麦麦有没有新版。
            // 这一步要联网，所以放后台跑，界面先按旧数据画完。
            checkUpdates();
        }
        updateEnvSubtitle();
    }

    /**
     * 展开环境管理时自动查一次版本更新。
     * <p>
     * 只查 NapCat 和麦麦这两项 —— 它们才是会真的迭代的组件。
     * Ubuntu / python / uv / git 这些跟着镜像走，查了也没东西可升。
     * <p>
     * 查完不重建整个列表，只把对应那一行的说明文字改掉，
     * 这样用户正在看的内容不会突然跳一下。
     */
    private void checkUpdates() {
        if (envChecking) {
            return;
        }
        envChecking = true;
        com.maimai.bot.core.UpdateChecker.checkAsync(
                BotState.get(this).env(),
                (mb, nc, qq) -> {
                    envChecking = false;
                    envUpdates.clear();
                    if (mb != null) {
                        envUpdates.put("maibot", mb);
                    }
                    if (nc != null) {
                        envUpdates.put("napcat", nc);
                    }
                    if (qq != null) {
                        envUpdates.put("linuxqq", qq);
                    }
                    applyUpdateBadges();
                });
    }

    /**
     * 这个组件查出来有没有新版。
     * <p>
     * 渲染列表和刷新版本提示都要问同一句话，所以抽出来一处判断，
     * 免得两边条件写歪了（比如一边判 info != null，一边判 info.hasUpdate）。
     * <p>
     * 注意：不能只看 envUpdates 里有没有这个 key —— 没更新的组件也会被
     * 放进去（Info 里 hasUpdate=false），那样所有按钮都会变「更新」。
     */
    private boolean hasUpdateFor(String key) {
        com.maimai.bot.core.UpdateChecker.Info info = envUpdates.get(key);
        return info != null && info.hasUpdate;
    }

    /**
     * 把查到的版本情况贴到列表文字上，顺便把「有更新」那几行的按钮换成「更新」。
     * <p>
     * 做法是按 key 找到对应行，在原说明后面追加一句。
     * 找不到行就跳过 —— 用户可能已经收起列表了。
     */
    private void applyUpdateBadges() {
        if (envList == null || envUpdates.isEmpty()) {
            return;
        }
        for (int i = 0; i < envList.getChildCount(); i++) {
            View row = envList.getChildAt(i);
            if (row == null) {
                continue;
            }
            Object tag = row.getTag();
            if (!(tag instanceof String)) {
                continue;
            }
            String key = (String) tag;
            com.maimai.bot.core.UpdateChecker.Info info = envUpdates.get(key);
            if (info == null) {
                continue;
            }
            // 按钮：有新版就从「重装」换成「更新」。
            //
            // 这里再改一遍是有必要的：checkUpdates() 是展开列表之后才异步返回的，
            // 那时 renderEnvList 早就画完了，按钮还停在「重装」。
            // 只有「安装」状态不动 —— 没装的东西谈不上更新。
            TextView action = row.findViewById(R.id.item_action);
            if (action != null && "重装".equals(String.valueOf(action.getText()))) {
                if (info.hasUpdate) {
                    action.setText("更新");
                    action.setTextColor(0xFFE17055);
                    action.setBackgroundResource(R.drawable.bg_badge_orange);
                }
            }

            String say = info.describe();
            if (say.isEmpty()) {
                continue;
            }
            TextView desc = row.findViewById(R.id.item_desc);
            TextView state = row.findViewById(R.id.item_state);
            if (desc == null) {
                continue;
            }
            String base = desc.getText() == null ? "" : desc.getText().toString();
            // 去掉上一次贴的，避免越叠越长
            int cut = base.indexOf(" · 可更新 ");
            if (cut > 0) {
                base = base.substring(0, cut);
            }
            desc.setText(base + " · " + say);
            // 有新版本时把状态点改成提示色，一眼能看出来
            if (state != null && info.hasUpdate) {
                state.setTextColor(0xFFE17055);
            }
        }
    }

    private void updateEnvSubtitle() {
        if (envSubtitle == null) {
            return;
        }
        String os = "Ubuntu";
        java.io.File osr = new java.io.File(BotState.get(this).env().rootfs(), "etc/os-release");
        if (osr.isFile()) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            new java.io.FileInputStream(osr), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("VERSION_ID=")) {
                        os = "Ubuntu " + line.substring("VERSION_ID=".length())
                                .replace("\"", "").trim();
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        envSubtitle.setText(os + (envExpanded ? " · 点击收起" : " · 点击展开"));
    }

    /**
     * 按真实文件系统状态渲染整张清单。
     * 不猜、不缓存：每次调用都重新 stat，装完立刻能看到变化。
     */
    private void renderEnvList() {
        if (envList == null) {
            return;
        }
        envList.removeAllViews();
        BotState st = BotState.get(this);
        EnvInspector.Item[] items = new EnvInspector(st.env()).inspect();

        int ready = 0;
        for (EnvInspector.Item it : items) {
            if (it.ready) {
                ready++;
            }
        }
        envVersion.setText(ready + "/" + items.length);

        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(this);
        for (EnvInspector.Item it : items) {
            View row = inflater.inflate(R.layout.item_env_row, envList, false);

            TextView title = row.findViewById(R.id.item_title);
            TextView desc = row.findViewById(R.id.item_desc);
            TextView state = row.findViewById(R.id.item_state);
            TextView action = row.findViewById(R.id.item_action);
            ImageView icon = row.findViewById(R.id.item_icon);

            title.setText(it.title);
            // 说明行：就绪且有版本就看版本，没就绪就提示缺什么
            if (it.ready) {
                desc.setText(it.detail.isEmpty() ? it.desc : it.desc + " · " + it.detail);
            } else {
                desc.setText(it.desc + " · 未安装");
            }

            if (it.ready) {
                state.setText("●");
                state.setTextColor(0xFF1E9E54);
                // 有新版就写「更新」，没更新才是「重装」。
                //
                // 判断放在这里（渲染时现算），而不是等版本检查回来再改文字 ——
                // 因为这个方法会被重新调用（展开时、每次 installOne 完成后），
                // 每次都会把按钮文字重设一遍。如果只在 applyUpdateBadges 里改，
                // 用户装完别的项、列表一重建，「更新」就又被冲回「重装」了。
                // envUpdates 是常驻的，这里直接查最稳，重建多少次都不会丢。
                boolean hasNew = hasUpdateFor(it.key);
                action.setText(hasNew ? "更新" : "重装");
                // 有更新时按钮描边换成橙色，比纯文字更醒目；
                // 没有就恢复原来的灰底，避免重建时颜色残留。
                action.setTextColor(hasNew ? 0xFFE17055 : 0xFF4A3F7A);
                action.setBackgroundResource(hasNew
                        ? R.drawable.bg_badge_orange
                        : R.drawable.bg_badge_gray);
            } else {
                state.setText("●");
                state.setTextColor(0xFFE17055);
                action.setText("安装");
                // 没安装的项一律「安装」，不显示「更新」：
                // 东西都没装，谈不上更新。这里顺手把颜色也复原，
                // 防止上一次渲染成「更新」留下的橙色串到这个状态。
                action.setTextColor(0xFF4A3F7A);
                action.setBackgroundResource(R.drawable.bg_badge_gray);
            }

            if ("ic_terminal".equals(it.icon)) {
                icon.setImageResource(R.drawable.ic_terminal);
            } else {
                icon.setImageResource(R.drawable.ic_box);
            }
            // 未就绪的项图标淡一点，视觉上先区分开
            icon.setAlpha(it.ready ? 1f : 0.45f);

            final String key = it.key;
            final String label = it.title;
            // 记住这一行是哪个组件，稍后版本检查回来要靠它找到对应行
            row.setTag(key);
            action.setOnClickListener(v -> {
                if (envBusyKey != null) {
                    Toast.makeText(this, "正在处理「" + envBusyKey + "」，请稍候",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                installOne(key, label);
            });

            envList.addView(row);
        }
    }

    /**
     * 补装/重装单个环境项。
     * <p>
     * 手法是直接在这个 App 的 proot 里跑 assets 里的 step.sh，
     * 和「启动麦麦」用同一套 proot 参数，所以不会有第二套环境的问题。
     */
    private void installOne(String key, String label) {
        BotState st = BotState.get(this);
        if (st.status() == BotState.Status.RUNNING
                || st.status() == BotState.Status.STARTING) {
            Toast.makeText(this, "先停止麦麦再改环境，免得文件被占用",
                    Toast.LENGTH_LONG).show();
            return;
        }

        envBusyKey = key;
        st.appendLog("[环境] 开始处理: " + label + " (" + key + ")");
        Toast.makeText(this, "正在处理「" + label + "」…", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            final StringBuilder out = new StringBuilder();
            int code = -1;
            try {
                code = com.maimai.bot.core.StepRunner.run(MainActivity.this, key, out);
            } catch (Exception e) {
                out.append("异常: ").append(e);
            }
            final int exit = code;
            final String text = out.toString();
            runOnUiThread(() -> {
                envBusyKey = null;
                BotState s2 = BotState.get(MainActivity.this);
                s2.appendLog("[环境] " + label + " 结束，exit=" + exit);
                if (!text.isEmpty()) {
                    s2.appendLog(text.length() > 1500
                            ? text.substring(text.length() - 1500) : text);
                }
                renderEnvList();
                updateEnvSubtitle();
                if (exit == 0) {
                    Toast.makeText(MainActivity.this,
                            "「" + label + "」处理完成", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this,
                            "「" + label + "」有问题，exit=" + exit + "，详见运行日志",
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "mb-env-step").start();
    }

    /** 一键把所有没装好的补齐。 */
    private void installAllMissing() {
        BotState st = BotState.get(this);
        if (st.status() == BotState.Status.RUNNING
                || st.status() == BotState.Status.STARTING) {
            Toast.makeText(this, "先停止麦麦再改环境", Toast.LENGTH_LONG).show();
            return;
        }
        if (envBusyKey != null) {
            Toast.makeText(this, "上一项还在处理中", Toast.LENGTH_SHORT).show();
            return;
        }

        envBusyKey = "env";
        st.appendLog("[环境] 一键更新基础环境（不含麦麦本体与 QQ 桥接）");
        Toast.makeText(this, "正在更新基础环境（不含麦麦/SL），可能需要几分钟",
                Toast.LENGTH_LONG).show();

        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            int code = -1;
            try {
                // 注意这里是 "env" 不是 "all"：
                // 一键更新只修 apt / uv / python / 实例配置这几项，
                // 故意不碰 maibot 本体和 QQ 桥接 —— 那两个体积大、
                // 而且用户可能已经装好甚至改过配置，不该被一键更新冲掉。
                // 想重装它们，在列表里单独点各自的「重装」。
                code = com.maimai.bot.core.StepRunner.run(MainActivity.this, "env", out);
            } catch (Exception e) {
                out.append("异常: ").append(e);
            }
            final int exit = code;
            String text = out.toString();
            final String tail = text.length() > 2000 ? text.substring(text.length() - 2000) : text;
            runOnUiThread(() -> {
                envBusyKey = null;
                BotState s2 = BotState.get(MainActivity.this);
                s2.appendLog("[环境] 一键安装结束，exit=" + exit);
                if (!tail.isEmpty()) {
                    s2.appendLog(tail);
                }
                renderEnvList();
                Toast.makeText(MainActivity.this,
                        exit == 0 ? "环境安装完成" : "有项目失败，请查看运行日志",
                        Toast.LENGTH_LONG).show();
            });
        }, "mb-env-all").start();
    }

    // ------------------------------------------------------------- 加速器（GitHub 代理测速）

    /**
     * 打开 GitHub 代理测速弹窗。
     * <p>
     * 为什么要测速而不是写死一个：ghproxy 系服务存活情况随时变，
     * 今天 gh-proxy 快、明天可能 Monlor 快，让用户自己看延迟选最踏实。
     */
    private void showAccelDialog() {
        View content = android.view.LayoutInflater.from(this)
                .inflate(R.layout.dialog_accel, null);
        TextView currentLabel = content.findViewById(R.id.accel_current);
        TextView btnRetest = content.findViewById(R.id.btn_accel_retest);
        TextView btnClose = content.findViewById(R.id.btn_accel_close);
        LinearLayout list = content.findViewById(R.id.accel_list);

        currentLabel.setText("当前选择节点：" + Mirror.currentLabel(this));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content)
                .create();
        accelDialog = dialog;

        btnClose.setOnClickListener(v -> dialog.dismiss());
        btnRetest.setOnClickListener(v -> {
            currentLabel.setText("当前选择节点：" + Mirror.currentLabel(this));
            runSpeedTest(list);
        });

        dialog.show();
        runSpeedTest(list);
    }

    /**
     * 并发测全部节点，边跑边填延迟。
     * <p>
     * 每个节点一个任务，最多同时 6 个；回来一个填一个，
     * 用户不用等全部跑完才看到东西。
     */
    private void runSpeedTest(LinearLayout list) {
        final int gen = ++accelGen;
        list.removeAllViews();
        Mirror.Proxy current = Mirror.current(this);

        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(this);

        for (Mirror.Proxy p : Mirror.PROXIES) {
            View row = inflater.inflate(R.layout.item_accel_row, list, false);
            TextView radio = row.findViewById(R.id.accel_radio);
            TextView name = row.findViewById(R.id.accel_name);
            TextView ping = row.findViewById(R.id.accel_ping);

            name.setText(p.label);
            ping.setText("测速中…");
            ping.setTextColor(0xFF8A83A0);
            radio.setText(p == current ? "◉" : "○");
            radio.setTextColor(p == current ? 0xFF6C5CE7 : 0xFF8A83A0);

            java.util.Map<String, Object> rec = new java.util.HashMap<>();
            rec.put("proxy", p);
            rec.put("ping", ping);
            rec.put("radio", radio);
            rows.add(rec);

            final Mirror.Proxy fp = p;
            row.setOnClickListener(v -> {
                Mirror.select(this, fp);
                for (java.util.Map<String, Object> r : rows) {
                    TextView rd = (TextView) r.get("radio");
                    boolean on = r.get("proxy") == fp;
                    rd.setText(on ? "◉" : "○");
                    rd.setTextColor(on ? 0xFF6C5CE7 : 0xFF8A83A0);
                }
                if (accelDialog != null) {
                    TextView cur = accelDialog.findViewById(R.id.accel_current);
                    if (cur != null) {
                        cur.setText("当前选择节点：" + Mirror.currentLabel(this));
                    }
                }
                BotState.get(this).appendLog("[加速器] 选择 " + fp.label
                        + " | 实际前缀=" + Mirror.resolvedPrefix(this));
                Toast.makeText(this, "已选「" + fp.label + "」", Toast.LENGTH_SHORT).show();
            });

            list.addView(row);
        }

        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(6);
        for (java.util.Map<String, Object> rec : rows) {
            final Mirror.Proxy p = (Mirror.Proxy) rec.get("proxy");
            final TextView ping = (TextView) rec.get("ping");
            pool.execute(() -> {
                int ms = pingProxy(p.prefix);
                runOnUiThread(() -> {
                    if (gen != accelGen) {
                        return; // 已被新一轮测速取代，丢弃这次结果
                    }
                    if (ms < 0) {
                        ping.setText("失败");
                        ping.setTextColor(0xFFE74C3C);
                    } else {
                        ping.setText(ms + " ms");
                        ping.setTextColor(0xFF2E9E5B);
                    }
                });
            });
        }
        pool.shutdown();
        new Thread(() -> {
            try {
                pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            runOnUiThread(() -> {
                if (gen != accelGen) {
                    return;
                }
                applyAutoPick(rows);
            });
        }, "accel-auto-pick").start();
    }

    /** 「自动选择」时，把延迟最低的成功节点记成实际使用节点。 */
    private void applyAutoPick(java.util.List<java.util.Map<String, Object>> rows) {
        if (!"auto".equals(Mirror.current(this).prefix)) {
            return;
        }
        String bestKey = null;
        int best = Integer.MAX_VALUE;
        for (java.util.Map<String, Object> r : rows) {
            Mirror.Proxy p = (Mirror.Proxy) r.get("proxy");
            TextView ping = (TextView) r.get("ping");
            String t = ping.getText().toString();
            if (!t.endsWith("ms")) {
                continue;
            }
            try {
                int ms = Integer.parseInt(t.replace("ms", "").trim());
                if (ms < best) {
                    best = ms;
                    bestKey = p.key;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (bestKey != null) {
            Mirror.rememberAutoPick(this, bestKey);
            BotState.get(this).appendLog("[加速器] 自动选择命中 "
                    + Mirror.byKey(bestKey).label + "（" + best + " ms）");
            if (accelDialog != null) {
                TextView cur = accelDialog.findViewById(R.id.accel_current);
                if (cur != null) {
                    cur.setText("当前选择节点：" + Mirror.currentLabel(this));
                }
            }
        }
    }

    /**
     * 测一个代理的延迟。
     * <p>
     * 拉的是 raw.githubusercontent 上一个真实文件的前 1KB ——
     * 比 ping 域名准，因为它真的走了一遍代理转发。
     *
     * @return 毫秒；失败返回 -1
     */
    private int pingProxy(String prefix) {
        String url = Mirror.TEST_PATH;
        if (!prefix.isEmpty() && !"auto".equals(prefix)) {
            url = prefix + Mirror.TEST_PATH;
        }
        java.net.HttpURLConnection c = null;
        try {
            long t0 = System.currentTimeMillis();
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(6000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            c.setRequestProperty("Range", "bytes=0-1023");
            int code = c.getResponseCode();
            if (code < 200 || code >= 400) {
                return -1;
            }
            java.io.InputStream in = c.getInputStream();
            byte[] buf = new byte[512];
            int n = in.read(buf);
            in.close();
            if (n <= 0) {
                return -1;
            }
            return (int) (System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    // ------------------------------------------------------------- QQ 桥接状态

    /**
     * 刷新 QQ 桥接卡片状态。
     * <p>
     * 判据只看 5099 端口在不在监听 —— 那等价于「面板能不能打开」。
     * 不额外去扫进程，因为端口是最直接的对外表现。
     */




    private void render() {
        BotState st = BotState.get(this);
        BotState.Status s = st.status();

        // 刚跑到 RUNNING 那一刻，maibot 很可能刚写完新的 token（临时 token 每次都变），
        // 所以只在「状态从别的值变成 RUNNING」这一次重读文件，
        // 而不是每秒都读盘。
        if (s == BotState.Status.RUNNING && lastStatus != BotState.Status.RUNNING) {
            st.refreshWebuiToken();
        }
        lastStatus = s;
        text(statusText, labelOf(s));
        text(detailText, humanDetail(st));

        boolean showProgress = s == BotState.Status.INSTALLING;
        progress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        if (showProgress) {
            progress.setProgress(st.progress());
        }

        boolean running = s == BotState.Status.RUNNING || s == BotState.Status.STARTING;
        boolean installing = s == BotState.Status.INSTALLING;
        btnStart.setEnabled(!running && !installing);
        btnStop.setEnabled(running || installing);
        btnWebui.setEnabled(s == BotState.Status.RUNNING);
        btnReinstall.setEnabled(!installing);

        String token = st.webuiToken();

        // 两种情况都要把凭证亮出来，只是名目不同：
        //   没设密码 → 这是 maibot 每次启动新生成的临时 token
        //   设过密码 → 这就是用户自己设的那串，直接给出来方便复制/找回
        // 只有凭证还没生成（空串）时才不显示内容。
        boolean passwordConfigured = st.webuiPasswordConfigured();
        boolean hasToken = s == BotState.Status.RUNNING && !token.isEmpty();

        if (s == BotState.Status.RUNNING) {
            webuiText.setVisibility(View.VISIBLE);
            if (!token.isEmpty()) {
                if (passwordConfigured) {
                    // 用户自己设的密码，maibot 就是把它当 access_token 明文存着
                    text(webuiText, "http://127.0.0.1:" + st.webuiPort()
                            + "\n登录密码 " + token
                            + "\n（这是你自己设的，忘了可以在这里看）");
                } else {
                    // 临时 token，每次重启都会重新生成
                    text(webuiText, "http://127.0.0.1:" + st.webuiPort()
                            + "\n临时 Token " + token
                            + "\n（本次启动生成，重启会变；首次登录会要求设密码）");
                }
            } else {
                text(webuiText, "http://127.0.0.1:" + st.webuiPort()
                        + "\n凭证还没生成，稍等几秒");
            }
        } else {
            webuiText.setVisibility(View.GONE);
        }

        // 只要拿到凭证就允许复制：临时 token 和已设密码都需要能一键带走
        btnCopyToken.setVisibility(hasToken ? View.VISIBLE : View.GONE);
        btnCopyToken.setText(passwordConfigured ? "复制密码" : "复制 Token");

        envVersion.setText("v0.1.0");
        text(envSubtitle, st.env().isRootfsReady(Installer.ROOTFS_VERSION)
                ? "Ubuntu 24.04 · 环境就绪"
                : "Ubuntu 24.04 · 待安装");
    }

    /**
     * 只在文案真的变了才 setText。
     * <p>
     * TextView 每 setText 一次都会走 requestLayout，ScrollView 在重新排版时
     * 有可能把滚动位置丢掉。主页每秒刷一次状态，用这个方法能省掉绝大部分无谓排版，
     * 是「往下滑自动弹回顶部」的另一半修复。
     */
    private static void text(TextView tv, String s) {
        if (tv == null) {
            return;
        }
        CharSequence cur = tv.getText();
        if (s == null) {
            s = "";
        }
        if (cur != null && s.contentEquals(cur)) {
            return;
        }
        tv.setText(s);
    }

    private String humanDetail(BotState st) {
        BotState.Status s = st.status();
        switch (s) {
            case RUNNING:
                return "麦麦在线 · WebUI 端口 " + st.webuiPort();
            case STARTING:
                return "正在唤醒，几秒后就能用";
            case INSTALLING:
                return st.statusDetail().isEmpty() ? "正在准备环境" : st.statusDetail();
            case ERROR:
                // 把真实原因直接显示在卡片上。
                // 之前只写「去运行日志看详情」，可日志在内存里、进程一重启就没了，
                // 用户按提示去看，看到的是空白 —— 等于什么都没告诉人。
                return st.statusDetail().isEmpty()
                        ? "出错了，去运行日志看详情"
                        : st.statusDetail();
            case STOPPED:
                return "已停止，点左边按钮重新拉起";
            case NOT_INSTALLED:
            default:
                return "首次启动会自动装 Ubuntu 环境与依赖";
        }
    }

    private String labelOf(BotState.Status s) {
        switch (s) {
            case INSTALLING:
                return "安装中";
            case STOPPED:
                return "已停止";
            case STARTING:
                return "启动中";
            case RUNNING:
                return "运行中";
            case ERROR:
                return "出错";
            case NOT_INSTALLED:
            default:
                return "未安装";
        }
    }

    // ------------------------------------------------------------- 工具

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
            }
        }
    }

}