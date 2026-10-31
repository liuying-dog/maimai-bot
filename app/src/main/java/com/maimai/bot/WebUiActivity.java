package com.maimai.bot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 内置 WebUI 页面。
 * <p>
 * 用系统 WebView 直接加载 http://127.0.0.1:8001/，不跳外部浏览器。
 * 因为项目坚持零第三方依赖，这里没有任何 androidx 组件，只有原生 API。
 * <p>
 * 一个必须交代的细节：WebView 访问 127.0.0.1 的明文 http 需要
 * android:usesCleartextTraffic="true"（Manifest 里已开），否则会被拦成
 * net::ERR_CLEARTEXT_NOT_PERMITTED，看上去就像"网页打不开"。
 */
public final class WebUiActivity extends Activity {

    /** 从外部指定初始面板：true 表示直接进 NapCat（QQ 桥接）。 */
    public static final String EXTRA_SHOW_SNOW = "show_snow";
    /** 同 EXTRA_SHOW_SNOW，新名字，语义更准（显示的是 NapCat 面板）。 */
    public static final String EXTRA_SHOW_QQ = EXTRA_SHOW_SNOW;
    /** 多账号：要把面板指到哪个 WebUI 端口。缺省用 NapCat.WEBUI_PORT。 */
    public static final String EXTRA_QQ_PORT = "qq_port";
    /** 多账号：这个实例的 WebUI token，用来做自动登录。 */
    public static final String EXTRA_QQ_TOKEN = "qq_token";
    /** 从主页传进来的 NapCat 端口与 token（0/空 表示用默认）。 */
    private int qqPort;
    private String qqToken;

    private WebView web;
    private TextView title;
    private ProgressBar bar;

    /** 当前看的是哪个面板：true = NapCat(6099)，false = 麦麦(8001)。 */
    private boolean showingSnow;
    private TextView tabMb;
    private TextView tabSl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webui);
        web = findViewById(R.id.web_view);
        title = findViewById(R.id.webui_title);
        bar = findViewById(R.id.web_progress);
        tabMb = findViewById(R.id.tab_mb);
        tabSl = findViewById(R.id.tab_sl);

        showingSnow = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_SHOW_SNOW, false);
        qqPort = getIntent() == null ? 0
                : getIntent().getIntExtra(EXTRA_QQ_PORT, 0);
        if (qqPort <= 0) {
            qqPort = com.maimai.bot.core.NapCat.WEBUI_PORT;
        }
        qqToken = getIntent() == null ? null
                : getIntent().getStringExtra(EXTRA_QQ_TOKEN);

setupWebView();
        wireToolbar();
        wireNav();
        wirePanelTabs();
        applyTabStyle();
        decideInitialPanel();
    }

    // ------------------------------------------------- 面板切换（麦麦 / QQ 桥接）

    /**
     * 决定首次进来显示哪个面板。
     * <p>
     * 之前的 bug（真机复现过）：启动麦麦和 NapCat → 关掉 NapCat →
     * 再点底部「WebUI」进来，顶部高亮在「QQ 桥接」，内容区却是麦麦的页面。
     * <p>
     * 根因是「显示哪个面板」只看 Intent 里的 EXTRA_SHOW_SNOW，
     * 而内容加载又是另一套判断，两边各自为政：
     * <ul>
     *   <li>从主页底部导航进来：Intent 不带 extra → showingSnow=false
     *       → 高亮「麦麦」、加载 8001。这时候如果用户之前点到过
     *       「QQ 桥接」，再回来高亮状态和实际内容就会错位；</li>
     *   <li>从账号卡「打开面板」进来：带 EXTRA_SHOW_QQ=true
     *       → 高亮「QQ 桥接」、走 loadSnow()。但 NapCat 已经被关掉，
     *       loadSnow() 会硬等 40 秒才显示「未就绪」，中间一直是白屏。</li>
     * </ul>
     * 现在改成一句话：<b>按端口的真实状态决定首屏</b>。
     * 谁在跑就显示谁；都没跑就按 Intent 的意图显示对应的「未运行」提示页。
     */
    private void decideInitialPanel() {
        final int mbPort = BotState.get(this).webuiPort();
        final boolean wantQq = showingSnow;
        bar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            // 只探一次，不做长时间轮询：这里只是决定首屏，
            // 真正的「等端口就绪」交给 loadSnow() / probeThenLoad() 里那套。
            boolean mbUp = portOpen(mbPort);
            boolean qqUp = portOpen(qqPort);
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                if (wantQq) {
                    // 用户明确要 QQ 面板：就显示 QQ 面板（在跑就加载，没跑就给提示）
                    showingSnow = true;
                    applyTabStyle();
                    loadSnow();
                    return;
                }
                if (mbUp) {
                    // 麦麦在跑，显示麦麦
                    showingSnow = false;
                    applyTabStyle();
                    probeThenLoad();
                    return;
                }
                if (qqUp) {
                    // 麦麦没跑但 NapCat 在跑：直接切到 QQ 面板，
                    // 不能停在「麦麦未运行」上让人以为 QQ 也没起。
                    showingSnow = true;
                    applyTabStyle();
                    loadSnow();
                    return;
                }
                // 两个都没跑：按意图显示对应的提示页
                showingSnow = wantQq;
                applyTabStyle();
                if (showingSnow) {
                    bar.setVisibility(View.GONE);
                    showSnowNotRunning();
                } else {
                    probeThenLoad();
                }
            });
        }, "panel-decide").start();
    }

    /**
     * 两个面板共用同一个 WebView，只换 URL。
     * <p>
     * 以前两个面板各有一套 Activity 骨架，等于同样的东西写两遍；
     * 现在并进来之后，返回、刷新、底部导航全部复用，和 AstrBot 的做法一致。
     */
    private void wirePanelTabs() {
        if (tabMb != null) {
            tabMb.setOnClickListener(v -> switchPanel(false));
        }
        if (tabSl != null) {
            tabSl.setOnClickListener(v -> switchPanel(true));
        }
    }

    private void switchPanel(boolean snow) {
        if (showingSnow == snow) {
            return;
        }
        showingSnow = snow;
        applyTabStyle();
        if (snow) {
            loadSnow();
        } else {
            probeThenLoad();
        }
    }

    /** 刷新两个 tab 的选中样式：选中的紫底深字，未选中的透明灰字。 */
    private void applyTabStyle() {
        if (tabMb == null || tabSl == null) {
            return;
        }
        tabMb.setBackgroundResource(showingSnow ? 0 : R.drawable.bg_nav_active);
        tabSl.setBackgroundResource(showingSnow ? R.drawable.bg_nav_active : 0);
        tabMb.setTextColor(showingSnow ? 0xFF7A7290 : 0xFF4A3F7A);
        tabSl.setTextColor(showingSnow ? 0xFF4A3F7A : 0xFF7A7290);
        tabMb.getPaint().setFakeBoldText(!showingSnow);
        tabSl.getPaint().setFakeBoldText(showingSnow);
        tabMb.invalidate();
        tabSl.invalidate();
        // 选中的标签如果被滑到屏幕外了，自动滚回可见区域。
        // 现在只有两个标签不会触发，但标签一多（用户滑到右边点了第三个）就很需要，
        // 否则点了之后新选中的那个看不见，不知道点没点成功。
        scrollTabIntoView(showingSnow ? tabSl : tabMb);
    }

    /**
     * 把指定标签滚到可见区域。
     * <p>
     * 用 post 而不是直接调：刚设完背景，布局还没重新测量，
     * 这时算出来的左右坐标是旧的，滚过去会有偏差。
     */
    private void scrollTabIntoView(final View tab) {
        if (tab == null) {
            return;
        }
        tab.post(() -> {
            try {
                View parent = (View) tab.getParent();
                if (parent == null) {
                    return;
                }
                // 让标签居中一点，不要贴着边
                int target = tab.getLeft() - (parent.getWidth() - tab.getWidth()) / 2;
                android.widget.HorizontalScrollView scroller =
                        (android.widget.HorizontalScrollView)
                                findViewById(R.id.tab_scroller);
                if (scroller != null) {
                    scroller.smoothScrollTo(Math.max(0, target), 0);
                }
            } catch (Exception ignored) {
            }
        });
    }


    /**
     * 打开 NapCat 面板（6099）。
     * <p>
     * 它和麦麦不是同一个服务，所以不注入 maibot_session，
     * 也不需要 hasOwnInstance() 那套归属校验 —— 端口通了就是它。
     * <p>
     * NapCat 的 WebUI 用 token 鉴权（config/webui.json 里的 token），
     * 进去后是它自己的登录页，输入 token 即可。这里不做自动登录：
     * 它的 token 存在 docker/config 里、且允许用户改，自动塞容易出错。
     */
    private void loadSnow() {
        if (title != null) {
            title.setText("NapCat");
        }
        bar.setVisibility(View.VISIBLE);
        // 轮询等端口，而不是只探一次。
        //
        // 之前只探一次，坑在哪：用户在主页点「启动」→ QrActivity 一看到
        // login 配置文件出现就立刻跳本页 → 那一瞬间 NapCat 的 6099 还没 bind
        // （它要先加载 QQ 内核、初始化 PacketClient，再起 WebUI），
        // 于是这一次探测必然 false，页面直接显示「QQ 桥接还没启动」，
        // 而此后永远不再重试 —— 哪怕 3 秒后端口就通了，用户看到的还是没启动。
        // 真机实测：界面显示「没启动」的同时，curl 127.0.0.1:6099 已经返回 200。
        // 现在改成每 500ms 探一次，最多等 40 秒。
        new Thread(() -> {
            boolean open = false;
            for (int i = 0; i < 80 && !isFinishing(); i++) {
                open = portOpen(qqPort);
                if (open) {
                    break;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            final boolean ok = open;
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                if (ok) {
                    // 带上 token 直接进面板：NapCat 的 WebUI 认 ?token= 这个参数，
                    // 带了就直接登录，不用用户自己去找 token（之前就是卡在这）。
                    String url = "http://127.0.0.1:" + qqPort + "/webui";
                    if (qqToken != null && !qqToken.isEmpty()) {
                        url += "?token=" + qqToken;
                    }
                    web.loadUrl(url);
                } else {
                    bar.setVisibility(View.GONE);
                    showSnowNotRunning();
                }
            });
        }, "napcat-probe").start();
    }

    /** NapCat 没启动时的内联提示页，不跳外部浏览器、也不弹窗打断。 */
    private void showSnowNotRunning() {
        // 加一个「重新检测」按钮：很多情况下实例其实已经起来了，只是这一页
        // 打开得太早（端口还没 bind）。让用户直接在这里重试，不用退回主页。
        String html = "<!doctype html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;height:100vh;display:flex;align-items:center;"
                + "justify-content:center;background:#F7F5FB;font-family:sans-serif}"
                + ".t{font-size:17px;color:#2B2430;font-weight:bold}"
                + ".s{margin-top:10px;font-size:13px;color:#7A7290;line-height:1.7}"
                + ".b{margin-top:22px;display:inline-block;padding:11px 26px;"
                + "background:#6E56CF;color:#fff;border-radius:999px;font-size:14px;"
                + "text-decoration:none;font-weight:600}"
                + "</style></head><body><div style='text-align:center;padding:28px'>"
                + "<div class='t'>QQ 桥接还没就绪</div>"
                + "<div class='s'>实例可能还在启动中（约 20~40 秒）。<br>"
                + "稍等片刻再试，或回主页点「启动」。</div>"
                + "<a class='b' href='maibot://retry'>重新检测</a>"
                + "</div></body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            // 顺序很重要：先停止加载，再把它从父容器上摘下来，最后才 destroy。
            // 挂着父容器直接 destroy 在部分 ROM 上会抛 IllegalStateException，
            // 而异常发生在 onDestroy 里会直接把整个进程带走（表现就是「点返回 App 退出」）。
            try {
                web.stopLoading();
            } catch (Exception ignored) {
            }
            try {
                android.view.ViewParent parent = web.getParent();
                if (parent instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) parent).removeView(web);
                }
            } catch (Exception ignored) {
            }
            try {
                web.loadUrl("about:blank");
            } catch (Exception ignored) {
            }
            try {
                web.destroy();
            } catch (Exception ignored) {
            }
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 系统返回键也走 goHome，避免回退栈为空时直接退出 App
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            goHome();
        }
    }

    // ------------------------------------------------------------- WebView

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        // WebUI 里可能有跨协议的静态资源，全放开省得白屏
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // 必须禁缓存，否则改完配置刷新还是旧页面
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setUserAgentString(s.getUserAgentString() + " MaiBotAndroid/0.1");

        // Cookie 直登的前提：WebView 必须接收并保存 Cookie
        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.setBackgroundColor(0xFFFFFFFF);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                // 拦截「重新检测」这个自定义协议：它是我们自己提示页上的按钮，
                // 不走网络，直接回到 loadSnow() 再轮询一次端口。
                String u = req == null || req.getUrl() == null ? "" : req.getUrl().toString();
                if (u.startsWith("maibot://retry")) {
                    loadSnow();
                    return true;
                }
                // 其余链接都留在内置页面里
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                bar.setVisibility(View.VISIBLE);
                bar.setProgress(0);
                title.setText("加载中…");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                bar.setVisibility(View.GONE);
                String t = view.getTitle();
                title.setText(t == null || t.trim().isEmpty()
                        ? (showingSnow ? "QQ 桥接" : "WebUI") : t);
                if (showingSnow) {
                    // NapCat 的 WebUI 是它自己的登录页（token 鉴权），
                    // 绝对不能拿麦麦的 token 去填它的表单，否则会互相干扰。
                    // 这里什么都不做，让用户自己输一次 token。
                    return;
                }
                // Cookie 生效的话这里根本看不到登录页。
                // 万一 Cookie 没生效（比如 maibot 重启换了 token），再兜底填一次。
                view.postDelayed(() -> {
                    if (!showingSnow) {
                        autoLogin(view);
                    }
                }, 1500);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                // 只有主框架失败才当成整页失败，子资源失败忽略
                if (req != null && req.isForMainFrame()) {
                    bar.setVisibility(View.GONE);
                    title.setText("未连接");
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                bar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                bar.setProgress(newProgress);
            }
        });
    }

    /**
     * 先确认端口上跑的是不是"我们自己的" MaiBot，再决定加载。
     * <p>
     * 光判断端口通不通是不够的：proot 不隔离网络命名空间，
     * 如果别的环境也在 127.0.0.1 上开了 WebUI，端口照样通，
     * 就会把别人的 Dashboard 加载进来（用户会以为"没装怎么就装好了"）。
     * 这里额外的判定是：本实例的 data/webui.json 里必须已经生成 token——
     * 那说明是我们自己启动过的实例；没有就直接当未启动处理。
     */
    private void probeThenLoad() {
        final int port = BotState.get(this).webuiPort();
        new Thread(() -> {
            final boolean open = portOpen(port);
            final boolean ours = open && hasOwnInstance();
            runOnUiThread(() -> {
                if (open && ours) {
                    loadWebUi();
                } else if (open && !ours) {
                    // 端口有人应答，但不是我们的实例，明确说清楚，别误导
                    showForeignPort(port);
                } else {
                    showNotRunning(port);
                }
            });
        }, "webui-probe").start();
    }

    /**
     * 端口是不是"我们自己的"实例。
     * <p>
     * 判据：本应用的 instance 目录里已经有 maibot 生成的 bot_config.toml。
     * 别的环境里的实例不会有我们这条路径下的配置，所以这个判据能把
     * "自己人"和"别人家"分开 —— proot 共用 loopback，
     * 万一别的程序也开了同端口，至少不会把别人家的页面错当成自己的。
     */
    private boolean hasOwnInstance() {
        try {
            return BotState.get(this).env().botConfig().isFile();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * TCP 探测。600ms 超时，本地回环够用；
     * 在子线程调用，绝不阻塞主线程。
     */
    private boolean portOpen(int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 600);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 端口有人应答，但那不是本应用的实例（多见于别的环境占了同一端口）。 */
    private void showForeignPort(int port) {
        title.setText("端口被占用");
        String url = "http://127.0.0.1:" + port + "/";
        String html = "<!DOCTYPE html><html><head>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>"
                + "body{margin:0;padding:40px 26px;font-family:-apple-system,"
                + "'PingFang SC','Microsoft YaHei',sans-serif;background:#FFF7F2;color:#2B2430}"
                + "h1{font-size:22px;margin:0 0 12px}"
                + "p{font-size:14px;line-height:1.7;color:#7A7290;margin:0 0 10px}"
                + ".addr{font-family:monospace;font-size:12px;color:#A9A3BC;"
                + "background:#FFFFFF;border-radius:10px;padding:10px 12px;margin:16px 0}"
                + "</style></head><body>"
                + "<h1>这个端口上跑的不是本应用的麦麦</h1>"
                + "<p>端口有响应，但本应用自己的实例还没初始化或还没启动。"
                + "为了不把别人家的页面错当成自己的，这里不加载它。</p>"
                + "<p>回主页点「启动麦麦」即可。如果一直这样，说明有别的程序占着这个端口。</p>"
                + "<div class='addr'>" + url + "</div>"
                + "</body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private void loadWebUi() {
        int port = BotState.get(this).webuiPort();
        injectSessionCookie(port);
        web.loadUrl("http://127.0.0.1:" + port + "/");
    }

    /**
     * 把 maibot_session 这个 Cookie 直接塞进 WebView。
     * <p>
     * 为什么不在登录页自动填密码：maibot-dashboard 是框架渲染的受控表单，
     * 光改 input.value 不会同步到框架 state，提交时校验读到的还是空，
     * 于是页面会报「请输入访问密码」，怎么点都进不去（实测截图就是这样）。
     * <p>
     * 而 maibot 的鉴权本质就一个 Cookie：名字 maibot_session，值是
     * access_token 的明文（源码 src/webui/core/auth.py 里 COOKIE_NAME 写死）。
     * 所以直接设 Cookie 是最短路径，连登录页都不用看。
     */
    private void injectSessionCookie(int port) {
        BotState st = BotState.get(this);
        String token = st.webuiToken();
        if (token == null || token.isEmpty()) {
            return;
        }
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            String host = "http://127.0.0.1:" + port;
            // 值里可能有空格、分号等，先做一次 URL 编码更保险；
            // @ . _ - 这类字符 Cookie 允许，编码后也不会有副作用。
            String v = android.net.Uri.encode(token);
            cm.setCookie(host, "maibot_session=" + v + "; path=/");
            cm.setCookie(host, "maibot_session=" + token + "; path=/");
            cm.flush();
            Log.i("WebUiAutoLogin", "injectSessionCookie -> 已设置 maibot_session（长度 "
                    + token.length() + "）");
        } catch (Exception e) {
            Log.w("WebUiAutoLogin", "injectSessionCookie 失败", e);
        }
    }

    /**
     * 后端没起来时显示的内联提示页。
     * 用内联 HTML 而不是换掉 WebView，是为了保留同一个 WebView 实例，
     * 点「重新加载」能直接回到真实地址，不用重建页面。
     */
    private void showNotRunning(int port) {
        String url = "http://127.0.0.1:" + port + "/";
        title.setText("未连接");
        String html = "<!DOCTYPE html><html><head>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>"
                + "body{margin:0;padding:40px 26px;font-family:-apple-system,"
                + "'PingFang SC','Microsoft YaHei',sans-serif;background:#FFF7F2;color:#2B2430}"
                + "h1{font-size:22px;margin:0 0 12px}"
                + "p{font-size:14px;line-height:1.7;color:#7A7290;margin:0 0 10px}"
                + ".addr{font-family:monospace;font-size:12px;color:#A9A3BC;"
                + "background:#FFFFFF;border-radius:10px;padding:10px 12px;margin:16px 0}"
                + "button{width:100%;height:46px;border:0;border-radius:23px;"
                + "background:#6C5CE7;color:#fff;font-size:15px;margin-top:8px}"
                + "</style></head><body>"
                + "<h1>WebUI 还没启动</h1>"
                + "<p>先回主页点「启动麦麦」，等状态变成运行中，再回到这里。</p>"
                + "<p>如果是第一次用，还需要先等环境安装完成，那一步会花几分钟。</p>"
                + "<div class='addr'>尝试的地址：" + url + "</div>"
                + "<button onclick=\"location.href='" + url + "'\">重新加载</button>"
                + "</body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    // ------------------------------------------------------------- 工具条

    private void wireToolbar() {
        findViewById(R.id.btn_back).setOnClickListener(v -> {
            // 先在网页内部后退；退到头了再回主页，绝不 finish() 了事
            if (web != null && web.canGoBack()) {
                web.goBack();
            } else {
                goHome();
            }
        });

        findViewById(R.id.btn_reload).setOnClickListener(v -> {
            Toast.makeText(this, "重新加载", Toast.LENGTH_SHORT).show();
            probeThenLoad();
        });

        findViewById(R.id.btn_more).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setItems(new String[]{"在浏览器中打开", "重新加载", "回到主页"}, (d, which) -> {
                            switch (which) {
                                case 0:
                                    openExternally();
                                    break;
                                case 1:
                                    probeThenLoad();
                                    break;
                                case 2:
                                    finish();
                                    break;
                                default:
                                    break;
                            }
                        })
                        .show());
    }

    /** 兜底：万一内置 WebView 有兼容问题，还能踢到浏览器里去。 */
    private void openExternally() {
        int port = BotState.get(this).webuiPort();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("http://127.0.0.1:" + port + "/")));
        } catch (Exception e) {
            Toast.makeText(this, "没有可用浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------- 导航

    /**
     * 显式回到主页。
     * 不能只用 finish()：只要回退栈里没有 MainActivity（从通知进 / 被系统回收过 /
     * 直接启动），finish() 会把整个任务清空，表现出来就是「点主页 App 直接退出」。
     * CLEAR_TOP + SINGLE_TOP 保证把已有的 MainActivity 拉到前台并复用，没有就新建一个。
     */
    private void goHome() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private void wireNav() {
        findViewById(R.id.tab_home).setOnClickListener(v -> goHome());
        // 已经在 WebUI 页：如果当前在 QQ 桥接，点自己就切回麦麦面板
        findViewById(R.id.tab_webui).setOnClickListener(v -> {
            if (showingSnow) {
                switchPanel(false);
            }
        });
        findViewById(R.id.tab_term).setOnClickListener(v ->
                startActivity(new Intent(this, ConsoleActivity.class)));
    }

    /**
     * 自动登录：把凭证填进登录页并点「验证并进入」。
     */
    private void autoLogin(WebView view) {
        String token = BotState.get(this).webuiToken();
        if (token == null || token.isEmpty()) {
            return;
        }
        String safe = token.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "").replace("\r", "");

        String js = "(function(){"
                + "try{"
                + "  var inputs = Array.prototype.slice.call(document.querySelectorAll('input'));"
                + "  var pw = null;"
                + "  for (var i=0;i<inputs.length;i++){"
                + "    var el = inputs[i];"
                + "    if (el.type === 'password' || (el.placeholder||'').indexOf('密码')>=0) { pw = el; break; }"
                + "  }"
                + "  if (!pw) return 'NO_PASSWORD_FIELD';"
                + "  pw.focus();"
                + "  pw.value = '" + safe + "';"
                + "  var desc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');"
                + "  if (desc && desc.set) { desc.set.call(pw, '" + safe + "'); }"
                + "  pw.dispatchEvent(new Event('input', {bubbles:true}));"
                + "  pw.dispatchEvent(new Event('change', {bubbles:true}));"
                + "  var btns = Array.prototype.slice.call(document.querySelectorAll('button'));"
                + "  var target = null;"
                + "  for (var j=0;j<btns.length;j++){"
                + "    var tx = (btns[j].innerText || btns[j].textContent || '').replace(/\\s/g,'');"
                + "    if (tx.indexOf('验证')>=0 || tx.indexOf('进入')>=0 || tx.indexOf('登录')>=0) { target = btns[j]; break; }"
                + "  }"
                + "  if (!target) {"
                + "    for (var k=0;k<btns.length;k++){ if (btns[k].type === 'submit') { target = btns[k]; break; } }"
                + "  }"
                + "  if (target) { target.click(); return 'CLICKED'; }"
                + "  var f = pw.form;"
                + "  if (f) { f.submit(); return 'SUBMITTED'; }"
                + "  return 'NO_BUTTON';"
                + "}catch(e){ return 'ERR:' + e.message; }"
                + "})()";

        view.evaluateJavascript(js, value -> {
            Log.i("WebUiAutoLogin", "autoLogin -> " + value);
            if (value != null && value.contains("CLICKED")) {
                // 静默处理：正常路径靠 Cookie 直接进 Dashboard，
                // 能走到这里说明 Cookie 没生效，属于兜底，不必打扰用户。
                Log.i("WebUiAutoLogin", "兜底填表已提交");
            } else if (value != null && value.contains("NO_PASSWORD_FIELD")) {
                Log.i("WebUiAutoLogin", "页面上没有密码输入框，已直接进入");
            }
        });
    }

}