package cn.dsh.phone;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * 手机端入口：底部四个标签（会话 / 文件 / 终端 / 我的）+ 页面替换。
 *
 * 结构刻意保持扁平：一个内容区 FrameLayout 加一个底部标签栏，页面由各自的类构建，
 * 切标签就是换内容区的子 View。没有 Fragment、没有导航库——手写构建链路下引入
 * AndroidX 会让 APK 体积翻倍，而这里需要的东西一个 Activity 就够。
 *
 * 刷新策略：每个页面自己负责（会话页 4 秒、列表页 10 秒），并且**离开标签就停**，
 * 否则四个页面同时轮询会把中继和电池一起拖垮。
 */
public class MainActivity extends Activity {
    static final int TAB_SESSIONS = 0;
    static final int TAB_FILES = 1;
    static final int TAB_TERMINAL = 2;
    static final int TAB_MINE = 3;

    /** 文件选择器的请求码（文件页上传用）。 */
    static final int REQUEST_UPLOAD_FILE = 1001;

    final Handler main = new Handler(Looper.getMainLooper());
    Store store;
    Discovery discovery;

    private FrameLayout content;
    private LinearLayout tabBar;
    private final TextView[] tabViews = new TextView[4];
    private int currentTab = TAB_SESSIONS;

    /** 当前打开着的会话（文件页与终端页用它定位工作目录）。 */
    String activeSessionId = "";
    String activeSessionTitle = "";

    private SessionsPage sessionsPage;
    private FilesPage filesPage;
    private TerminalPage terminalPage;
    private MinePage minePage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.density = getResources().getDisplayMetrics().density;
        store = new Store(this);
        Ui.apply(Theme.byName(store.themeName()));
        discovery = new Discovery(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        content = new FrameLayout(this);
        content.setId(View.generateViewId());
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tabBar = buildTabBar();
        FrameLayout.LayoutParams tabParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(58f));
        tabParams.gravity = Gravity.BOTTOM;
        root.addView(tabBar, tabParams);

        // 内容区底部留出标签栏高度，避免最后一行被盖住。
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        contentParams.bottomMargin = Ui.dp(58f);
        content.setLayoutParams(contentParams);

        setContentView(root);
        Ui.applyWindowInsets(this, root);
        Window window = getWindow();
        if (window != null) window.setNavigationBarColor(Ui.BG);

        if (store.hasDevice()) {
            prepareCurrentDevice();
            switchTab(TAB_SESSIONS);
        } else {
            showConnect();
        }
        // 允许从命令行预填地址与配对码（部署到多台设备时省去手输）：
        //   adb shell am start -n cn.dsh.phone/.MainActivity \
        //     --es host 192.168.1.100 --ei port 19390 --es code 123456 --ez autopair true
        applyLaunchIntent(getIntent());
    }

    /**
     * 处理启动参数：填好地址与配对码，autopair 为真时立刻发起配对。
     *
     * 与手表端同一套参数名，方便同一份脚本部署两种设备。
     */
    private void applyLaunchIntent(android.content.Intent intent) {
        if (intent == null) return;
        String host = intent.getStringExtra("host");
        String code = intent.getStringExtra("code");
        int port = intent.getIntExtra("port", 0);
        if (host == null || host.length() == 0) return;
        String base = host.startsWith("http") ? host : ("http://" + host + (port > 0 ? ":" + port : ""));
        if (code == null || code.length() == 0) return;
        final String finalBase = base;
        final String finalCode = code;
        final boolean autopair = intent.getBooleanExtra("autopair", false);
        post(new Runnable() {
            @Override public void run() {
                if (autopair) {
                    Api.pair(finalBase, finalCode, "", new Api.Callback() {
                        @Override public void onResult(JSONObject value, String error) {
                            if (error != null) {
                                toast("自动配对失败：" + error);
                                return;
                            }
                            String token = Api.text(value, "token");
                            if (token.length() == 0) return;
                            String lanBase = Api.text(value, "lanBase");
                            String publicUrl = Api.text(value, "publicUrl");
                            store.remember(lanBase.length() > 0 ? lanBase : finalBase, publicUrl, token, "");
                            toast("配对成功");
                            onPaired();
                        }
                    });
                } else {
                    showConnect();
                }
            }
        });
    }

    /** 底部标签栏：四个等宽项，选中项用强调色 + 顶部指示条。 */
    private LinearLayout buildTabBar() {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.SURFACE);
        bar.setElevation(Ui.dp(8f));
        String[] labels = {"会话", "文件", "终端", "我的"};
        String[] glyphs = {"◈", "▤", "▸_", "◍"};
        for (int i = 0; i < labels.length; i += 1) {
            LinearLayout item = Ui.column(this);
            item.setGravity(Gravity.CENTER);
            item.setClickable(true);
            final int index = i;
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    switchTab(index);
                }
            });
            TextView glyph = Ui.text(this, glyphs[i], 15f, Ui.MUTED);
            glyph.setGravity(Gravity.CENTER);
            TextView label = Ui.text(this, labels[i], 11f, Ui.MUTED);
            label.setGravity(Gravity.CENTER);
            item.addView(glyph);
            item.addView(label);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            bar.addView(item, params);
            tabViews[i] = label;
        }
        return bar;
    }

    /** 切换标签：先让旧页面停掉轮询，再构建新页面。 */
    void switchTab(int tab) {
        if (!store.hasDevice()) {
            showConnect();
            return;
        }
        stopAllPages();
        currentTab = tab;
        View view;
        if (tab == TAB_FILES) {
            if (filesPage == null) filesPage = new FilesPage(this);
            view = filesPage.build();
        } else if (tab == TAB_TERMINAL) {
            if (terminalPage == null) terminalPage = new TerminalPage(this);
            view = terminalPage.build();
        } else if (tab == TAB_MINE) {
            if (minePage == null) minePage = new MinePage(this);
            view = minePage.build();
        } else {
            if (sessionsPage == null) sessionsPage = new SessionsPage(this);
            view = sessionsPage.build();
        }
        setContent(view);
        updateTabs();
    }

    private void updateTabs() {
        // 没有配对设备时整条标签栏隐藏：这时候点任何标签都只会回到连接页，
        // 留着它反而让人以为可以切换。内容区同时收回底部留白。
        boolean paired = store.hasDevice();
        if (tabBar != null) tabBar.setVisibility(paired ? View.VISIBLE : View.GONE);
        if (content != null) {
            ViewGroup.LayoutParams params = content.getLayoutParams();
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) params).bottomMargin = paired ? Ui.dp(58f) : 0;
                content.setLayoutParams(params);
            }
        }
        for (int i = 0; i < tabViews.length; i += 1) {
            boolean active = i == currentTab && paired;
            tabViews[i].setTextColor(active ? Ui.ACCENT : Ui.MUTED);
        }
    }

    void setContent(View view) {
        content.removeAllViews();
        content.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 没有配对设备时显示连接页（它自己会覆盖整屏）。 */
    void showConnect() {
        stopAllPages();
        sessionsPage = null;
        filesPage = null;
        terminalPage = null;
        minePage = null;
        setContent(new ConnectPage(this).build());
        updateTabs();
    }

    private void stopAllPages() {
        if (sessionsPage != null) sessionsPage.stop();
        if (filesPage != null) filesPage.stop();
        if (terminalPage != null) terminalPage.stop();
        if (minePage != null) minePage.stop();
    }

    // ------------------------------------------------------------ 远程连接

    private cn.dsh.remote.RemoteAccount remoteAccount;
    private RemoteBridge remoteBridge;
    private String remoteDeviceBase = "";

    private cn.dsh.remote.RemoteAccount loadRemoteAccount() {
        if (remoteAccount != null) return remoteAccount;
        String json = store.remoteAccountJson();
        if (json == null || json.length() == 0) return null;
        try {
            JSONObject saved = new JSONObject(json);
            cn.dsh.remote.RemoteAccount account = new cn.dsh.remote.RemoteAccount(
                    saved.optString("serverUrl", cn.dsh.remote.RemoteAccount.DEFAULT_SERVER));
            account.restore(saved.optString("deviceId", ""), saved.optString("accessToken", ""),
                    saved.optString("refreshToken", ""),
                    saved.optLong("accessTokenExpiresAt", 0), saved.optLong("refreshTokenExpiresAt", 0),
                    saved.optString("account", ""));
            String privateKey = saved.optString("privateKey", "");
            String publicKey = saved.optString("publicKey", "");
            if (privateKey.length() > 0 && publicKey.length() > 0) {
                account.setLocalKeys(cn.dsh.remote.Bytes.unb64u(privateKey), publicKey);
            }
            remoteAccount = account;
            return account;
        } catch (Exception error) {
            return null;
        }
    }

    private void saveRemoteAccount(cn.dsh.remote.RemoteAccount account) {
        if (account == null) return;
        JSONObject saved = new JSONObject();
        try {
            saved.put("serverUrl", account.serverUrl());
            saved.put("deviceId", account.deviceId());
            saved.put("accessToken", account.accessToken());
            saved.put("refreshToken", account.refreshTokenValue());
            saved.put("accessTokenExpiresAt", account.accessTokenExpiresAt());
            saved.put("refreshTokenExpiresAt", account.refreshTokenExpiresAt());
            saved.put("account", account.account());
            saved.put("privateKey", account.localPrivateKey() == null
                    ? "" : cn.dsh.remote.Bytes.b64u(account.localPrivateKey()));
            saved.put("publicKey", account.localPublicKey() == null ? "" : account.localPublicKey());
        } catch (Exception ignored) { }
        store.setRemoteAccountJson(saved.toString());
    }

    /** 为当前远程设备准备通道。 */
    private RemoteBridge bridgeFor(Store.Device device) {
        cn.dsh.remote.RemoteAccount account = loadRemoteAccount();
        if (account == null || device == null || !device.isRemote()) return null;
        if (remoteBridge != null && device.base.equals(remoteDeviceBase)) return remoteBridge;
        if (remoteBridge != null) remoteBridge.closeQuietly();
        remoteBridge = new RemoteBridge(account, device.deviceId, device.hostPublicKey,
                device.remoteServer, device.label(), "1.0.0");
        remoteDeviceBase = device.base;
        Api.setRemoteBridge(remoteBridge);
        return remoteBridge;
    }

    /** 启动时按设备类型准备通道。 */
    void prepareCurrentDevice() {
        Store.Device device = store.current();
        if (device == null || !device.isRemote()) {
            Api.setRemoteBridge(null);
            return;
        }
        final RemoteBridge bridge = bridgeFor(device);
        if (bridge == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    bridge.connect();
                    main.post(new Runnable() {
                        @Override public void run() { toast("已连接 " + device.label()); }
                    });
                } catch (Exception error) {
                    main.post(new Runnable() {
                        @Override public void run() { toast("远程连接失败：" + friendlyRemote(error)); }
                    });
                }
            }
        }).start();
    }

    /** 远程账号登录页。 */
    void showRemoteLogin() {
        stopAllPages();
        sessionsPage = null;
        filesPage = null;
        terminalPage = null;
        minePage = null;

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(20f), Ui.dp(24f), Ui.dp(20f), Ui.dp(24f));

        root.addView(Ui.title(this, "远程账号", 24f));
        root.addView(Ui.muted(this, "登录后可以连不在同一网络的主机", 12.5f));
        root.addView(Ui.gap(this, 16f));

        final android.widget.EditText serverField = field("服务器地址（默认 dsh.r2049.cn）", false);
        final android.widget.EditText emailField = field("账号（邮箱）", false);
        final android.widget.EditText passwordField = field("密码", true);
        root.addView(serverField, Ui.matchParams());
        root.addView(Ui.gap(this, 10f));
        root.addView(emailField, Ui.matchParams());
        root.addView(Ui.gap(this, 10f));
        root.addView(passwordField, Ui.matchParams());
        root.addView(Ui.gap(this, 14f));

        final TextView status = Ui.muted(this, "", 12f);
        root.addView(status, Ui.matchParams());

        TextView login = Ui.button(this, "登录", true);
        login.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                final String server = serverField.getText().toString().trim();
                final String email = emailField.getText().toString().trim();
                final String password = passwordField.getText().toString();
                if (email.length() == 0 || password.length() == 0) {
                    status.setTextColor(Ui.WARN);
                    status.setText("请填写账号与密码");
                    return;
                }
                status.setTextColor(Ui.MUTED);
                status.setText("正在登录…");
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            cn.dsh.remote.RemoteAccount account = new cn.dsh.remote.RemoteAccount(
                                    server.length() == 0
                                            ? cn.dsh.remote.RemoteAccount.DEFAULT_SERVER : server);
                            account.loginWithPassword(email, password);
                            saveRemoteAccount(account);
                            main.post(new Runnable() {
                                @Override public void run() {
                                    remoteAccount = account;
                                    showRemoteHosts();
                                }
                            });
                        } catch (final Exception error) {
                            main.post(new Runnable() {
                                @Override public void run() {
                                    status.setTextColor(Ui.WARN);
                                    status.setText("登录失败：" + friendlyRemote(error));
                                }
                            });
                        }
                    }
                }).start();
            }
        });
        LinearLayout.LayoutParams loginParams = Ui.matchParams();
        loginParams.topMargin = Ui.dp(10f);
        root.addView(login, loginParams);

        TextView back = Ui.ghostButton(this, "返回");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { switchTab(TAB_MINE); }
        });
        LinearLayout.LayoutParams backParams = Ui.matchParams();
        backParams.topMargin = Ui.dp(10f);
        root.addView(back, backParams);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);
        setContent(scroll);
        updateTabs();
    }

    /** 远程主机列表页。 */
    void showRemoteHosts() {
        stopAllPages();
        sessionsPage = null;
        filesPage = null;
        terminalPage = null;
        minePage = null;

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(20f), Ui.dp(24f), Ui.dp(20f), Ui.dp(24f));
        root.addView(Ui.title(this, "远程主机", 24f));
        root.addView(Ui.muted(this, "选择要连接的那台电脑", 12.5f));
        root.addView(Ui.gap(this, 16f));

        final cn.dsh.remote.RemoteAccount account = loadRemoteAccount();
        final LinearLayout listCard = Ui.card(this);
        if (account == null) {
            listCard.addView(Ui.muted(this, "尚未登录远程账号", 12f));
        } else {
            listCard.addView(Ui.muted(this, "正在读取主机列表…", 12f));
        }
        root.addView(listCard, Ui.matchParams());
        root.addView(Ui.gap(this, 12f));

        TextView refresh = Ui.ghostButton(this, "刷新");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showRemoteHosts(); }
        });
        root.addView(refresh, Ui.matchParams());

        TextView logout = Ui.ghostButton(this, "退出远程账号");
        logout.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                store.setRemoteAccountJson("");
                remoteAccount = null;
                if (remoteBridge != null) remoteBridge.closeQuietly();
                remoteBridge = null;
                Api.setRemoteBridge(null);
                switchTab(TAB_MINE);
            }
        });
        LinearLayout.LayoutParams logoutParams = Ui.matchParams();
        logoutParams.topMargin = Ui.dp(8f);
        root.addView(logout, logoutParams);

        TextView back = Ui.ghostButton(this, "返回");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { switchTab(TAB_MINE); }
        });
        LinearLayout.LayoutParams backParams = Ui.matchParams();
        backParams.topMargin = Ui.dp(8f);
        root.addView(back, backParams);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);
        setContent(scroll);
        updateTabs();

        if (account == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    account.ensureAccessToken();
                    final java.util.List<cn.dsh.remote.RemoteAccount.HostDevice> hosts = account.listHosts();
                    saveRemoteAccount(account);
                    main.post(new Runnable() {
                        @Override public void run() {
                            listCard.removeAllViews();
                            if (hosts.isEmpty()) {
                                listCard.addView(Ui.muted(MainActivity.this,
                                        "账号下还没有主机。请在电脑上安装并登录 ds-harness-remote 插件。", 12f));
                                return;
                            }
                            listCard.addView(Ui.muted(MainActivity.this, "共 " + hosts.size() + " 台", 12f));
                            for (final cn.dsh.remote.RemoteAccount.HostDevice host : hosts) {
                                LinearLayout row = Ui.listRow(MainActivity.this);
                                LinearLayout column = Ui.column(MainActivity.this);
                                column.addView(Ui.title(MainActivity.this, host.name, 15f));
                                String subtitle = host.platform;
                                if (host.harnessVersion.length() > 0) subtitle += " · DSH " + host.harnessVersion;
                                column.addView(Ui.muted(MainActivity.this, subtitle, 11.5f));
                                row.addView(column, new LinearLayout.LayoutParams(
                                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                                row.addView(Ui.text(MainActivity.this, "连接", 13f, Ui.ACCENT));
                                row.setOnClickListener(new View.OnClickListener() {
                                    @Override public void onClick(View view) { connectRemoteHost(host); }
                                });
                                LinearLayout.LayoutParams params = Ui.matchParams();
                                params.bottomMargin = Ui.dp(6f);
                                listCard.addView(row, params);
                            }
                        }
                    });
                } catch (final Exception error) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            listCard.removeAllViews();
                            listCard.addView(Ui.muted(MainActivity.this,
                                    "读取失败：" + friendlyRemote(error), 12f));
                        }
                    });
                }
            }
        }).start();
    }

    private void connectRemoteHost(final cn.dsh.remote.RemoteAccount.HostDevice host) {
        toast("正在连接 " + host.name + "…");
        final cn.dsh.remote.RemoteAccount account = loadRemoteAccount();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String publicKey = account.hostIdentityKey(host.deviceId);
                    saveRemoteAccount(account);
                    main.post(new Runnable() {
                        @Override public void run() {
                            store.rememberRemoteHost(host.deviceId, host.name, account.serverUrl(),
                                    publicKey, host.name);
                            Store.Device device = store.current();
                            if (device != null) bridgeFor(device);
                            activeSessionId = "";
                            activeSessionTitle = "";
                            onPaired();
                        }
                    });
                } catch (final Exception error) {
                    main.post(new Runnable() {
                        @Override public void run() { toast("连接失败：" + friendlyRemote(error)); }
                    });
                }
            }
        }).start();
    }

    private android.widget.EditText field(String hint, boolean password) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(hint);
        input.setTextSize(13f);
        input.setTextColor(Ui.FG);
        input.setHintTextColor(Ui.MUTED);
        input.setSingleLine(true);
        if (password) {
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        return input;
    }

    static String friendlyRemote(Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (error instanceof java.net.UnknownHostException) return "找不到服务器地址";
        if (error instanceof java.net.SocketTimeoutException) return "连接超时，请检查网络";
        if (message.contains("AUTH_INVALID")) return "登录已过期，请重新登录";
        if (message.contains("HOST_OFFLINE")) return "这台电脑不在线";
        if (message.contains("MEMBERSHIP_REQUIRED")) return "这个账号无权连接该主机";
        return message;
    }

    /** 配对成功后从连接页回到主界面。 */
    void onPaired() {
        sessionsPage = null;
        filesPage = null;
        terminalPage = null;
        minePage = null;
        switchTab(TAB_SESSIONS);
    }

    /** 进入某个会话的对话页。 */
    void openSession(String sessionId, String title) {
        activeSessionId = sessionId == null ? "" : sessionId;
        activeSessionTitle = title == null ? "" : title;
        store.setLastSession(activeSessionId, activeSessionTitle);
        stopAllPages();
        ChatPage chat = new ChatPage(this, activeSessionId, activeSessionTitle);
        setContent(chat.build());
        currentTab = TAB_SESSIONS;
        updateTabs();
    }

    /** 从对话页返回会话列表。 */
    void backToSessions() {
        sessionsPage = null;
        switchTab(TAB_SESSIONS);
    }

    void toast(String message) {
        if (message == null || message.length() == 0) return;
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    void post(Runnable runnable) {
        main.post(runnable);
    }

    void postDelayed(Runnable runnable, long delayMs) {
        main.postDelayed(runnable, delayMs);
    }

    void removeCallbacks(Runnable runnable) {
        main.removeCallbacks(runnable);
    }

    /** 应用主题：换掉静态色值并重建当前页面。 */
    void applyThemeAndRebuild() {
        Ui.apply(Theme.byName(store.themeName()));
        getWindow().getDecorView().setBackgroundColor(Ui.BG);
        if (tabBar != null) tabBar.setBackgroundColor(Ui.SURFACE);
        sessionsPage = null;
        filesPage = null;
        terminalPage = null;
        minePage = null;
        switchTab(currentTab);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_UPLOAD_FILE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        // 文件页自己持有会话上下文，交给它上传。
        if (filesPage != null) filesPage.onFilePicked(data.getData());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAllPages();
        if (discovery != null) discovery.stop();
    }
}
