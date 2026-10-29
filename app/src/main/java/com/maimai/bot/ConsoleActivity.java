package com.maimai.bot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Selection;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.maimai.bot.core.ShellSession;

import java.io.IOException;

/**
 * 真终端页。
 * <p>
 * 连接的是一个常驻 bash（跑在内置 Ubuntu 的 proot 里），所以 cd / export
 * 会保留；命令执行是「一次性投递 + 哨兵回收退出码」，不是伪终端，
 * 因此 TAB 不做补全、只插空格——这点在按键行为里如实体现，不糊弄。
 * <p>
 * 底部那排功能键放在 HorizontalScrollView 里，可以左右滑。
 */
public final class ConsoleActivity extends Activity {
    /** 直接进来跟随 NapCat 启动日志（主页点启动时用）。 */
    public static final String EXTRA_FOLLOW_NAPCAT = "follow_napcat";
    /**
     * 跟随麦麦启动日志（files/runtime.log）而不是 shell 会话。
     * <p>
     * 用户诉求：「现在这个日志跟 astrbot 不一样啊，好多日志都没显示出来」。
     * 原因就在这里：底部「终端」页接的是一个常驻 bash 的实时输出，
     * 麦麦自己的启动输出（DEBUG 级那一大堆）根本没进这个页面。
     * 麦麦的输出其实已经同时写了 {@code BotState} 内存和 files/runtime.log，
     * 只是没有任何界面去读它 —— 本常量就是补上这一条通路。
     */
    public static final String EXTRA_FOLLOW_MAIBOT = "follow_maibot";
    /** 账号显示名，用于页面标题。 */
    public static final String EXTRA_NAPCAT_LABEL = "napcat_label";

    private static final int C_KEY_TEXT = 0xFF4A4258;

    /**
     * 输出区最多留这么多字符，超了从头部砍掉。
     * <p>
     * 原来只有 200KB，而且是「超过就删掉前 3/4」——
     * 启动过程一长，最前面那些行就悄悄没了，用户看到的永远是后半截。
     * 现在放大到 4MB（足够装下几万行启动日志）。
     */
    private static final int MAX_OUTPUT = 4 * 1024 * 1024;

    private TextView output;
    private ScrollView scroll;
    private EditText input;
    private LinearLayout keyStrip;

    private ShellSession session;

    /** 跟随 maibot 的 jsonl 文件日志（能拿到 DEBUG 级，比 stdout 丰富得多）。 */
    private com.maimai.bot.core.LogTailer tailer;
    private Runnable logTicker;
    /** 详细模式：把文件日志也打进界面。默认开启，因为这才是「看得到在干啥」。 */
    private boolean verboseLog = true;
    /**
     * 是否处于「跟随 NapCat 启动日志」模式。
     * <p>
     * 这个模式下来源不是 maibot 的 jsonl，而是 files/napcat.log —— 那里是
     * 我们自己的启动步骤 + QQ/NapCat 的原始输出，一行一行实时追加。
     * 进来方式：主页点某个账号的启动按钮，直接 startActivity 带 extra 过来，
     * 用户点完就能看到它在干什么，不需要再点开什么弹窗。
     */
    private boolean followNapCatLog;
    /** 是否跟随麦麦启动日志（runtime.log），而不是 shell 会话。 */
    private boolean followMaibotLog;
    /** 主页点启动时传进来的账号名，用于标题。 */
    private String napcatLabel;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder buffer = new StringBuilder();

    /** CTRL 是否处于按下（点亮）状态。 */
    private boolean ctrlArmed;

    private TextView ctrlKey;

    // 按键定义：显示文字 + 行为标签
    private static final String[][] KEYS = {
            {"ESC", "esc"},
            {"TAB", "tab"},
            {"CTRL", "ctrl"},
            {"C", "c"},
            {"D", "d"},
            {"Z", "z"},
            {"L", "l"},
            {"←", "left"},
            {"↓", "down"},
            {"→", "right"},
            {"↑", "up"},
            {"HOME", "home"},
            {"END", "end"},
            {"PGUP", "pgup"},
            {"PGDN", "pgdn"},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_console);

        output = findViewById(R.id.term_output);
        scroll = findViewById(R.id.term_scroll);
        input = findViewById(R.id.term_input);
        keyStrip = findViewById(R.id.key_strip);

        buildKeyStrip();
        wireInput();
        wireNav();
        wireMore();
        startSession();
        // 先看是不是「从主页点启动」跳过来的
        android.content.Intent it = getIntent();
        followNapCatLog = it != null && it.getBooleanExtra(EXTRA_FOLLOW_NAPCAT, false);
        followMaibotLog = it != null && it.getBooleanExtra(EXTRA_FOLLOW_MAIBOT, false);
        napcatLabel = it == null ? null : it.getStringExtra(EXTRA_NAPCAT_LABEL);
        if (followNapCatLog && napcatLabel != null) {
            // 这个页面没有标题栏（整屏都是终端），用 Toast 告诉用户在看谁
            Toast.makeText(this, napcatLabel + " 正在启动…", Toast.LENGTH_SHORT).show();
        }
        startLogTail();
    }

    // ------------------------------------------------------------- 文件日志跟随

    /**
     * 除了 stdout，还把 maibot 写盘的 DEBUG 日志也接进来。
     * <p>
     * 这是「干啥都看不到」的真正解法：maibot 的控制台是 INFO 级别，
     * 磁盘上的 jsonl 是 DEBUG —— 实测同一段运行里 debug 253 条、
     * info 只有 34 条，差不多七倍，还带 error 的完整根因。
     * 光靠 stdout 自然觉得什么都没发生。
     */
private void startLogTail() {
        if (followMaibotLog) {
            // 跟随麦麦自己的启动日志：files/runtime.log。
            //
            // 这个文件是 BotService 里 appendRunLog() 一行一行写进去的，
            // 内容就是 maibot 的原始 stdout（含 DEBUG 级），
            // 和「终端」页那个 bash 会话完全是两回事。
            java.io.File f = BotState.get(this).env().runtimeLog();
            tailer = com.maimai.bot.core.LogTailer.forFile(f);
            append("[日志] 正在跟随麦麦启动过程 · " + f.getAbsolutePath());
            int n0 = tailer.tailRecent(300, line -> append(line));
            if (n0 == 0) {
                append("[日志] 暂无内容，启动麦麦后这里会实时刷新");
            }
            logTicker = new Runnable() {
                @Override
                public void run() {
                    if (tailer == null) {
                        return;
                    }
                    tailer.poll(false, line -> ui.post(() -> append(line)));
                    ui.postDelayed(this, 400);
                }
            };
            ui.postDelayed(logTicker, 400);
            return;
        }
        if (followNapCatLog) {
            // 跟随 NapCat 的启动日志：纯文本，原样逐行显示
            java.io.File f = com.maimai.bot.core.NapCat.logFile(BotState.get(this).env());
            tailer = com.maimai.bot.core.LogTailer.forFile(f);
            append("[日志] 正在跟随 NapCat 启动过程 · " + f.getAbsolutePath());
            // 立刻把已有的内容铺上来（说不定已经跑了几步）
            int n0 = tailer.tailRecent(200, line -> append(line));
            if (n0 == 0) {
                append("[日志] 等待第一条启动日志…");
            }
            logTicker = new Runnable() {
                @Override
                public void run() {
                    if (tailer == null) {
                        return;
                    }
                    tailer.poll(false, line -> ui.post(() -> append(line)));
                    ui.postDelayed(this, 400);
                }
            };
            ui.postDelayed(logTicker, 400);
            return;
        }
        tailer = new com.maimai.bot.core.LogTailer(BotState.get(this).env().instance());
        // 先把文件里最近的若干行铺上来。
        //
        // 之前一进来就 seekToEnd，结果很尴尬：maibot 如果当时没在写新日志
        // （比如已经启动完在待机），屏幕上就永远是那两行 shell 欢迎语，
        // 看起来像「日志功能没做」。用户要的是「进去就能看到它在干啥」，
        // 所以这里主动回读一段历史。
        //
        // 但现在改用 runtime.log（纯文本全量）而不是 jsonl，原因见下：
        // jsonl 那套 tailRecent 里有一条「只接受以 { 开头的行」的过滤，
        // 大量非 json 的行（包括 maibot 自己的控制器输出、插件打印）
        // 会被直接丢掉 —— 这就是用户说的「日志显示不全」。
        // runtime.log 是 BotService 一行一行原样写进去的 stdout，
        // 不过滤、不丢行，而且体积很小（实测一次启动 75 行 / 8.7KB）。
        java.io.File runLog = BotState.get(this).env().runtimeLog();
        if (runLog.isFile()) {
            tailer = com.maimai.bot.core.LogTailer.forFile(runLog);
            append("[日志] 完整模式 · 跟随 maibot 的原始输出（runtime.log）");
            // 不设行数上限：这个文件就是全量，一次全铺出来
            int all = tailer.tailRecent(Integer.MAX_VALUE, line -> append(line));
            if (all == 0) {
                append("[日志] 文件还是空的，启动麦麦后这里会实时刷新");
            }
        } else {
            append("[日志] 详细模式已开启 · 读取 maibot 的 DEBUG 级日志");
            int shown = tailer.tailRecent(Integer.MAX_VALUE, line -> append(line));
            if (shown == 0) {
                append("[日志] 暂时还没有日志文件，等 maibot 启动后会自动出现");
            }
        }

        logTicker = new Runnable() {
            @Override
            public void run() {
                if (tailer == null) {
                    return;
                }
                tailer.poll(false, line -> {
                    if (verboseLog) {
                        ui.post(() -> append(line));
                    }
                });
                ui.postDelayed(this, 700);
            }
        };
        ui.postDelayed(logTicker, 700);
    }

    /** 切换「详细日志」开关。关掉就只看 stdout，省得满屏。 */
    private void toggleVerbose() {
        verboseLog = !verboseLog;
        append(verboseLog
                ? "[日志] 已开启详细模式：显示 maibot 的 DEBUG 级文件日志"
                : "[日志] 已关闭详细模式：只显示标准输出");
    }

    @Override
    protected void onDestroy() {
        // 停掉文件日志跟随，并把文件句柄关掉，避免泄漏
        if (logTicker != null) {
            ui.removeCallbacks(logTicker);
            logTicker = null;
        }
        if (tailer != null) {
            tailer.close();
            tailer = null;
        }
        // 只解绑监听，不杀进程：会话是进程级共享的，
        // 杀掉的话下次进来又得重新起一个 proot，还白白丢掉 cd 状态。
        ShellSession.release();
        session = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------- 会话

    private void startSession() {
        // 复用进程级单例：进出页面不再重复拉起 proot
        session = ShellSession.shared(this);

        // 先把上次的输出铺回来。
        // 不做这一步就会踩一个很难看的坑：会话是复用的，第二次进来时
        // start() 见会话还活着就直接 return，一句话都不输出，
        // 而新页面的 buffer 是空的，界面就永远停在布局里的占位文字上。
        String past = session.history();
        if (!past.isEmpty()) {
            buffer.setLength(0);
            buffer.append(past);
            output.setText(buffer.toString());
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }

        session.setListener(new ShellSession.Listener() {
            @Override
            public void onLine(String line) {
                ui.post(() -> append(line));
            }

            @Override
            public void onCommandDone(int code) {
                ui.post(() -> {
                    if (code != 0) {
                        append("· 退出码 " + code);
                    }
                });
            }

            @Override
            public void onExit(int code) {
                ui.post(() -> append("[会话已结束，退出码 " + code + "。发送任意命令可重连]"));
            }
        });

        new Thread(() -> {
            try {
                if (session.isAlive()) {
                    // 会话还在跑，只做个确认。重启反而会丢掉 cd / export 的状态。
                    ui.post(() -> append("[已连接到运行中的 shell]"));
                } else {
                    session.start();
                }
            } catch (IOException e) {
                ui.post(() -> append("[启动失败] " + e.getMessage()
                        + "\n请先回主页把 Ubuntu 环境装好再进来。"));
            }
        }, "shell-start").start();
    }

    private void append(String line) {
        buffer.append(line).append('\n');
        if (buffer.length() > MAX_OUTPUT) {
            buffer.delete(0, buffer.length() - MAX_OUTPUT * 3 / 4);
        }
        output.setText(buffer.toString());
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void clearScreen() {
        buffer.setLength(0);
        output.setText("");
        // 历史也一起清，否则下次进页面又把刚清掉的内容回放回来了
        ShellSession s = session;
        if (s != null) {
            s.clearHistory();
        }
    }

    // ------------------------------------------------------------- 输入

    private void wireInput() {
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean isGo = actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE;
            boolean isEnter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (isGo || isEnter) {
                submit();
                return true;
            }
            return false;
        });

        findViewById(R.id.btn_send).setOnClickListener(v -> submit());
    }

    private void submit() {
        String cmd = input.getText().toString();
        if (cmd.trim().isEmpty()) {
            return;
        }
        append("› " + cmd);
        input.setText("");
        if (session != null) {
            session.send(cmd);
        }
    }

    // ------------------------------------------------------------- 按键条

    private void buildKeyStrip() {
        for (String[] def : KEYS) {
            TextView key = new TextView(this);
            key.setText(def[0]);
            key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            key.setTextColor(C_KEY_TEXT);
            key.setGravity(Gravity.CENTER);
            key.setTypeface(Typeface.DEFAULT);
            key.setBackgroundResource("ctrl".equals(def[1])
                    ? R.drawable.bg_key_ctrl_off : R.drawable.bg_key);
            key.setPadding(dp(16), 0, dp(16), 0);
            key.setClickable(true);
            key.setFocusable(true);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
            lp.setMarginEnd(dp(6));
            key.setLayoutParams(lp);

            final String action = def[1];
            key.setOnClickListener(v -> onKey(action));
            keyStrip.addView(key);

            if ("ctrl".equals(action)) {
                ctrlKey = key;
            }
        }
    }

    private void onKey(String action) {
        switch (action) {
            case "esc":
                input.setText("");
                break;

            case "tab":
                insertAtCursor("  ");
                break;

            case "ctrl":
                ctrlArmed = !ctrlArmed;
                if (ctrlKey != null) {
                    ctrlKey.setBackgroundResource(ctrlArmed
                            ? R.drawable.bg_key_active : R.drawable.bg_key_ctrl_off);
                    ctrlKey.setTextColor(ctrlArmed ? 0xFFFFFFFF : C_KEY_TEXT);
                }
                break;

            case "c":
                if (ctrlArmed) {
                    finishCtrl();
                    if (session != null) {
                        session.interrupt();
                    }
                } else {
                    insertAtCursor("c");
                }
                break;

            case "d":
                if (ctrlArmed) {
                    finishCtrl();
                    append("› exit");
                    if (session != null) {
                        session.send("exit");
                    }
                } else {
                    insertAtCursor("d");
                }
                break;

            case "z":
                if (ctrlArmed) {
                    finishCtrl();
                    append("› jobs");
                    if (session != null) {
                        session.send("jobs");
                    }
                } else {
                    insertAtCursor("z");
                }
                break;

            case "l":
                if (ctrlArmed) {
                    finishCtrl();
                    clearScreen();
                } else {
                    insertAtCursor("l");
                }
                break;

            case "left":
                moveCursor(-1);
                break;

            case "right":
                moveCursor(1);
                break;

            case "up":
                scrollBy(-1);
                break;

            case "down":
                scrollBy(1);
                break;

            case "home":
                setCursor(0);
                break;

            case "end":
                setCursor(input.length());
                break;

            case "pgup":
                pageScroll(-1);
                break;

            case "pgdn":
                pageScroll(1);
                break;

            default:
                break;
        }
    }

    private void finishCtrl() {
        ctrlArmed = false;
        if (ctrlKey != null) {
            ctrlKey.setBackgroundResource(R.drawable.bg_key_ctrl_off);
            ctrlKey.setTextColor(C_KEY_TEXT);
        }
    }

    private void insertAtCursor(String text) {
        Editable e = input.getText();
        int start = Math.max(0, Math.min(Selection.getSelectionStart(e), Selection.getSelectionEnd(e)));
        int end = Math.max(Selection.getSelectionStart(e), Selection.getSelectionEnd(e));
        e.replace(start, end, text);
        Selection.setSelection(e, start + text.length());
    }

    private void moveCursor(int delta) {
        int pos = Selection.getSelectionStart(input.getText());
        if (pos < 0) {
            pos = input.length();
        }
        int next = Math.max(0, Math.min(input.length(), pos + delta));
        input.setSelection(next);
    }

    private void setCursor(int pos) {
        int p = Math.max(0, Math.min(input.length(), pos));
        input.setSelection(p);
    }

    /** 输出区按行滚动。用行高估算，够用且不依赖具体布局测量。 */
    private void scrollBy(int lines) {
        int step = Math.round(output.getLineHeight() * Math.max(1, Math.abs(lines)));
        scroll.scrollBy(0, step * (lines < 0 ? -1 : 1));
    }

    private void pageScroll(int direction) {
        int page = Math.max(1, scroll.getHeight() - dp(24));
        scroll.scrollBy(0, direction * page);
    }

    // ------------------------------------------------------------- 导航 / 菜单

    /** 显式回主页，避免回退栈为空时 finish() 把整个任务清掉。 */
    private void goHome() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private void wireNav() {
        findViewById(R.id.tab_home).setOnClickListener(v -> goHome());
        // 内置 WebUI，不跳外部浏览器
        findViewById(R.id.tab_webui).setOnClickListener(v ->
                startActivity(new Intent(this, WebUiActivity.class)));
        // 已在终端页，点自己不做事
        findViewById(R.id.tab_term).setOnClickListener(v -> { });
    }

    private void wireMore() {
        findViewById(R.id.btn_more).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setItems(new String[]{
                                "清空屏幕", "重启 Shell", "复制全部输出",
                                verboseLog ? "关闭详细日志（只看标准输出）"
                                        : "开启详细日志（含 DEBUG 级）"
                        }, (d, which) -> {
                            switch (which) {
                                case 0:
                                    clearScreen();
                                    break;
                                case 1:
                                    append("[重启 Shell]");
                                    if (session != null) {
                                        session.reset();
                                    }
                                    break;
                                case 2:
                                    copyAll();
                                    break;
                                case 3:
                                    toggleVerbose();
                                    break;
                                default:
                                    break;
                            }
                        })
                        .show());
    }

    private void copyAll() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("maibot-term", buffer.toString()));
        Toast.makeText(this, "已复制 " + buffer.length() + " 个字符", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------- 工具

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}