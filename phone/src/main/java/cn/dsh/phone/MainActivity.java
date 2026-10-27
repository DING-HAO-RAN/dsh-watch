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
    protected void onDestroy() {
        super.onDestroy();
        stopAllPages();
        if (discovery != null) discovery.stop();
    }
}
