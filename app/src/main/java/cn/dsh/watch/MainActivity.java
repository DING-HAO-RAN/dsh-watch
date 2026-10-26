package cn.dsh.watch;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * DSH 手表客户端主界面。
 *
 * 页面用「重建整屏」的方式切换：手表上每屏内容都很小，重建比维护视图差异更不容易出错。
 * 所有网络调用都通过 Api 回到主线程后再重建，因此不存在跨线程改视图的问题。
 */
public class MainActivity extends Activity {
    /** 会话消息的自动刷新间隔；手表上过于频繁会明显耗电。 */
    private static final long SESSION_REFRESH_MS = 4_000L;
    /** 会话列表的自动刷新间隔。 */
    private static final long LIST_REFRESH_MS = 10_000L;
    /** 计划与任务页的自动刷新间隔。 */
    private static final long PLAN_REFRESH_MS = 5_000L;

    private WatchUi ui;
    private Store store;
    private Api api;
    private Discovery discovery;

    private final List<Api.Session> sessions = new ArrayList<>();
    private final List<Api.Project> projects = new ArrayList<>();
    private final List<Api.Ask> pending = new ArrayList<>();
    private final List<Api.Message> messages = new ArrayList<>();
    private final List<Api.Step> steps = new ArrayList<>();
    private Api.Usage usage;

    /** 会话页的展示模式：全部步骤 / 只看对话。 */
    private boolean showAllSteps = true;
    /** 当前项目分组（空串表示「全部会话」）。 */
    private String currentProject = "";
    /** 翻页：当前这批最早的 seq，以及是否还有更早的历史。 */
    private long currentBeforeSeq;
    private boolean currentHasMore;
    /** 上一次成功渲染的内容指纹，用于避免无变化时重建整屏（闪烁的根因）。 */
    private int lastContentSignature;

    private String currentSessionId = "";
    private String currentSessionTitle = "";
    private Api.Ask currentAsk;
    private boolean currentRunning;
    private boolean loadingSession;

    private boolean onSessionPage;
    private boolean destroyed;
    /**
     * 正在编辑（发消息 / 打字回答）时暂停自动刷新。
     *
     * 会话页每 4 秒重建一次整屏；若此时用户正在输入，重建会把输入框连同已输入的内容一起丢掉。
     */
    private boolean editing;
    /**
     * 用户刚触摸/滚动过的时间戳。
     *
     * 自动刷新虽然会保留滚动位置，但「重建整屏」本身就会让正在看的内容跳动一下；
     * 用户随手一滑时尤其明显。因此在最近一次交互后的静默窗口内跳过刷新，
     * 让阅读和滑动不被打断。
     */
    private long lastInteractionAt;
    /** 交互后暂停刷新的时长：足够覆盖一次连续滑动。 */
    private static final long INTERACTION_QUIET_MS = 2_500L;
    /** 下次重建整屏时是否回到顶部。 */
    private boolean resetScrollOnNextRender;
    /** 标记「刚从会话页返回」，用于让会话列表回顶部。 */
    private boolean returningFromSession;

    private final android.os.Handler refresh = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable sessionTicker = new Runnable() {
        @Override
        public void run() {
            if (destroyed || !onSessionPage) return;
            // 正在输入、或用户刚滑过，就跳过本次刷新，避免重建把界面顶掉。
            if (!editing && !recentlyTouched()) loadSession(currentSessionId, true);
            refresh.postDelayed(this, SESSION_REFRESH_MS);
        }
    };
    private final Runnable listTicker = new Runnable() {
        @Override
        public void run() {
            if (destroyed || !onListPage()) return;
            if (!recentlyTouched()) loadSessions(true, false);
            refresh.postDelayed(this, LIST_REFRESH_MS);
        }
    };
    /** 子任务页的自动刷新：子智能体的状态变化很快，用会话页同样的节奏。 */
    private final Runnable subagentTicker = new Runnable() {
        @Override
        public void run() {
            if (destroyed || !onSubagentPage) return;
            if (!recentlyTouched()) loadSubagents(true);
            refresh.postDelayed(this, SESSION_REFRESH_MS);
        }
    };
    /** 计划页的自动刷新：任务状态变化没那么频繁，稍慢一点省电。 */
    private final Runnable planTicker = new Runnable() {
        @Override
        public void run() {
            if (destroyed || !onPlanPage) return;
            if (!recentlyTouched()) loadPlan(true);
            refresh.postDelayed(this, PLAN_REFRESH_MS);
        }
    };

    /** 最近是否刚发生过触摸/滚动。 */
    private boolean recentlyTouched() {
        return System.currentTimeMillis() - lastInteractionAt < INTERACTION_QUIET_MS;
    }

    /**
     * 分发触摸事件时记一笔交互时间，并在这里判定横向手势。
     *
     * 手势必须在这一层判定：子层的 ScrollView 会先消费触摸，挂在页面容器上的
     * 监听器收不到完整的横向滑动，左滑会被当成纵向滚动。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE
                || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_SCROLL) {
            lastInteractionAt = System.currentTimeMillis();
        }
        // 操作菜单已打开时不做手势判定，避免与面板内的点击冲突。
        if (!menuOpen && gesture != null && currentPage != null) {
            boolean consumed = gesture.onTouch(currentPage, event);
            if (consumed) {
                // 横向手势已消费本次事件：补一个取消事件给子视图，避免它们停在按下态。
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                return true;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    /** 诊断用：把手势判定过程写入 logcat（tag=dshwatch）。 */
    private void debugLog(String message) {
        android.util.Log.d("dshwatch", message);
    }

    /** 手势识别器（与设备密度相关，延迟初始化）。 */
    private WatchUi.HorizontalGesture gesture;
    /** 当前页面的手势回调持有者。 */
    private WatchUi.Page currentPage;
    /** 操作菜单是否打开。 */
    private boolean menuOpen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new WatchUi(this);
        store = new Store(this);
        // 主题与圆屏优化必须在建任何页面之前应用：它们决定颜色常量与安全区，
        // 晚一步就会先画一屏旧样式再跳变。
        WatchUi.applyTheme(store.theme());
        WatchUi.roundOptimized = store.roundOptimized();
        api = new Api();
        discovery = new Discovery();
        // 允许从外部带入地址与配对码（例如电脑端生成一条命令让手表直接配对），
        // 省去在手表小键盘上输入 IP 的麻烦。
        applyIntentExtras(getIntent());
        if (hasPendingPairing()) {
            // 带参数启动时优先去设置页：已配对过也要允许用新参数覆盖，
            // 否则 adb 命令对一台配过对的手表完全无效。
            showConnect();
            return;
        }
        if (store.paired()) {
            currentSessionId = store.lastSession();
            if (currentSessionId.isEmpty()) showSessionList();
            else showSession(currentSessionId);
        } else {
            showConnect();
        }
    }

    /**
     * Activity 已存在时再次 `am start` 会走这里，而不是 onCreate。
     *
     * 不接住它的话，第二次带参数启动会被完全忽略——表现是「命令执行了但手表没反应」，
     * 因为旧的 Activity 还在，onCreate 不会再执行。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntentExtras(intent);
        if (hasPendingPairing()) showConnect();
    }

    /** 当前是否有待用的配对参数（地址与配对码都齐）。 */
    private boolean hasPendingPairing() {
        return !pendingHost.isEmpty() && !pendingCode.isEmpty();
    }

    /**
     * 读取启动参数并预填设置页。
     *
     * 支持 `--es host <地址>`（可含端口或 https://）、`--ei port <端口>`、
     * `--es code <配对码>`，便于从电脑上用一条 adb 命令把配对信息送到手表，
     * 省去在小键盘上输入。
     */
    private void applyIntentExtras(Intent intent) {
        if (intent == null) return;
        String host = intent.getStringExtra("host");
        String code = intent.getStringExtra("code");
        int port = intent.getIntExtra("port", 0);
        if (host != null && !host.trim().isEmpty()) {
            pendingHost = host.trim();
            // 只给了主机名而另给了端口时，合成 host:port 再交给统一解析。
            if (port > 0 && port <= 65535 && pendingHost.indexOf(':') < 0 && !pendingHost.contains("://")) {
                pendingHost = pendingHost + ":" + port;
            }
            pendingCode = code == null ? "" : code.trim();
        }
        // autopair：带上就免去再点一次「配对并连接」，便于用 adb 一次性配好。
        if (intent.getBooleanExtra("autopair", false) && !pendingHost.isEmpty() && !pendingCode.isEmpty()) {
            autoPair = true;
        }
    }

    /**
     * 校验两个输入框并开始配对。
     *
     * 抽成方法是为了让「配对并连接」按钮与 autopair 启动参数走同一条路径——
     * 两处各写一遍校验迟早会不一致。
     */
    private void pairNow(EditText hostField, EditText codeField, TextView status) {
        String address = hostField.getText().toString().trim();
        String code = codeField.getText().toString().trim();
        if (address.isEmpty()) {
            status.setTextColor(WatchUi.WARN);
            status.setText("请先填写地址");
            return;
        }
        if (code.isEmpty()) {
            status.setTextColor(WatchUi.WARN);
            status.setText("请填写电脑上显示的 6 位配对码");
            return;
        }
        Store.Endpoint endpoint = Store.parse(address, DEFAULT_RELAY_PORT);
        if (endpoint == null) {
            status.setTextColor(WatchUi.DANGER);
            status.setText("地址格式不对，示例：192.168.1.121 或 https://abc.com");
            return;
        }
        status.setTextColor(WatchUi.MUTED);
        status.setText("正在配对…");
        tryPair(endpoint, code, status);
    }

    /** 延迟执行（配对需要等界面挂上以后再发请求）。 */
    private void postDelayed(final Runnable action, long delayMs) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!destroyed) action.run();
            }
        }, delayMs);
    }

    private String pendingHost = "";
    private String pendingCode = "";
    /** 启动参数要求直接配对（见 applyIntentExtras）。 */
    private boolean autoPair = false;
    /** 一键配对进行中（界面据此显示「取消」而不是「重试」）。 */
    private boolean quickPairing = false;

    /** 读 JSON 字符串字段，缺失或为 null 时给空串。 */
    private static String textOf(JSONObject object, String key) {
        if (object == null) return "";
        String value = object.optString(key, "");
        return value == null ? "" : value;
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        refresh.removeCallbacksAndMessages(null);
        discovery.cancel();
        super.onDestroy();
    }

    /**
     * 把新页面装进窗口并启用。
     *
     * 重建整屏会丢掉滚动位置，而会话页与会话列表都会定时自动刷新——不保留的话
     * 用户根本没法往下翻（列表有几十条，翻一屏就被弹回顶部）。因此这里默认记住
     * 旧页面的滚动位置并还原；确实需要回到顶部时调用 {@link #resetScroll()}。
     */
    private void render(WatchUi.Page page) {
        currentPage = page;
        if (gesture == null) {
            gesture = new WatchUi.HorizontalGesture(getResources().getDisplayMetrics().density);
        }
        int restore = resetScrollOnNextRender ? 0 : currentScroll();
        resetScrollOnNextRender = false;
        setContentView(page.root);
        if (pendingScrollToBottom) {
            pendingScrollToBottom = false;
            // 等布局完成再滚，否则内容高度还是 0、滚不到底。
            page.scroll.post(() -> page.scroll.post(page::scrollToBottom));
            return;
        }
        page.scroll.post(() -> page.scroll.scrollTo(0, restore));
    }

    /** 下一次渲染后滚到底部（进入会话时用，便于直接看到最新结果）。 */
    private void scrollToBottomAfterRender() {
        pendingScrollToBottom = true;
    }

    private boolean pendingScrollToBottom;

    /** 下一次重建时回到顶部（换页时用，避免带着上一页的偏移）。 */
    private void resetScroll() {
        resetScrollOnNextRender = true;
    }

    /** 读取当前屏幕上那个 ScrollView 的滚动位置。 */
    private int currentScroll() {
        View root = getWindow().getDecorView().findViewById(android.R.id.content);
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof android.view.ViewGroup) {
                    android.view.ViewGroup frame = (android.view.ViewGroup) child;
                    for (int j = 0; j < frame.getChildCount(); j++) {
                        View inner = frame.getChildAt(j);
                        if (inner instanceof android.widget.ScrollView) {
                            return ((android.widget.ScrollView) inner).getScrollY();
                        }
                    }
                }
            }
        }
        return 0;
    }

    // ---------------------------------------------------------- 设置 / 连接页

    /** 默认的局域网中继端口。 */
    private static final int DEFAULT_RELAY_PORT = 19390;

    /** 当前配置对应的请求根地址（形如 http://192.168.1.121:19390 或 https://host）。 */
    private String baseUrl() {
        Store.Endpoint endpoint = Store.parse(store.base(), DEFAULT_RELAY_PORT);
        if (endpoint == null) return "";
        // url("") 会拼出以 / 结尾的根地址，供 Api 直接拼接路径。
        return endpoint.url("/").replaceAll("/$", "");
    }

    /**
     * 设置页：配置要连接的设备（局域网或公网）。
     *
     * 从会话页左滑菜单的「设置」进入，未配对时也作为首次连接页使用。
     */
    private void showConnect() {
        onSessionPage = false;
        WatchUi.Page page = ui.page();
        ui.heading(page, "设置", store.paired() ? "当前已连接" : "填写电脑地址与配对码");

        if (store.paired()) {
            LinearLayout current = ui.card();
            current.addView(ui.text("当前连接", 11, WatchUi.ACCENT));
            TextView address = ui.text(store.base(), 12, WatchUi.FG);
            address.setPadding(0, ui.dp(5), 0, 0);
            current.addView(address);
            ui.add(page, current, 0, 10);
        }

        // ---- 已记住的设备：直接切换，不必重新配对 ----
        final List<Store.Device> remembered = store.devices();
        if (remembered.size() > 0) {
            ui.add(page, ui.text("已记住的设备", 11, WatchUi.MUTED), 0, 6);
            String currentBase = store.base();
            for (final Store.Device device : remembered) {
                boolean active = device.base.equals(currentBase);
                String kind = device.isPublic() ? "公网" : "局域网";
                String title = device.label.isEmpty() ? device.base : device.label;
                ui.add(page, ui.row(title, kind + " · " + device.base,
                        () -> switchToDevice(device)), 0, active ? 8 : 6);
            }
            ui.add(page, ui.text("点一条可直接切过去，不用重新配对。", 10, WatchUi.MUTED), 0, 10);
        }

        // ---- 外观：主题与圆屏优化 ----
        ui.divider(page);
        ui.add(page, ui.text("外观", 11, WatchUi.MUTED), 0, 6);

        boolean light = store.theme().equals(Theme.LIGHT);
        ui.add(page, ui.row("界面主题", light ? "浅色 · 点一下切回深色" : "深色 · 点一下切到浅色",
                () -> {
                    store.setTheme(light ? Theme.DARK : Theme.LIGHT);
                    WatchUi.applyTheme(store.theme());
                    showConnect();
                }), 0, 6);

        boolean round = store.roundOptimized();
        ui.add(page, ui.row("圆屏优化",
                round ? "已开启 · 内容避开圆弧" : "已关闭 · 方屏可用满整屏",
                () -> {
                    store.setRoundOptimized(!round);
                    WatchUi.roundOptimized = store.roundOptimized();
                    showConnect();
                }), 0, 10);
        ui.add(page, ui.text("圆屏优化开启时按圆形内接矩形留边，内容不会被圆弧切掉；"
                + "方屏手表或偏好满屏可以关掉。", 10, WatchUi.MUTED), 0, 0);
        // ---- 一键配对：手表主动找局域网里的中继，电脑上确认即可 ----
        ui.add(page, ui.button("一键配对", true, () -> startQuickPair(page)), 0, 8);

        ui.divider(page);

        final EditText hostField = new EditText(this);
        hostField.setHint("地址，如 192.168.1.121 或 https://xxx.com");
        hostField.setText(pendingHost.isEmpty() ? store.base() : pendingHost);
        hostField.setTextSize(13);
        hostField.setTextColor(WatchUi.FG);
        hostField.setHintTextColor(WatchUi.MUTED);
        hostField.setSingleLine(true);
        hostField.setBackground(ui.surface(WatchUi.SURFACE, 14));
        hostField.setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9));
        ui.add(page, hostField, 0, 8);

        final EditText codeField = new EditText(this);
        codeField.setHint("6 位配对码");
        codeField.setText(pendingCode);
        codeField.setTextSize(16);
        codeField.setTextColor(WatchUi.FG);
        codeField.setHintTextColor(WatchUi.MUTED);
        codeField.setSingleLine(true);
        codeField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        codeField.setBackground(ui.surface(WatchUi.SURFACE, 14));
        codeField.setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9));
        ui.add(page, codeField, 0, 10);

        final TextView status = ui.text("", 12, WatchUi.MUTED);
        status.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        ui.add(page, status, 0, 8);

        ui.add(page, ui.button("自动查找局域网", false, () -> {
            status.setTextColor(WatchUi.MUTED);
            status.setText("正在查找…请保持电脑上的中继运行");
            discovery.listen(found -> {
                if (destroyed) return;
                if (found.isEmpty()) {
                    status.setTextColor(WatchUi.WARN);
                    status.setText("没找到，请确认手表和电脑连的是同一个 WiFi，或手填地址");
                    return;
                }
                Discovery.Found first = found.get(0);
                // 广播里带端口，直接写成 host:port 存进统一地址字段。
                hostField.setText(first.host + ":" + first.port);
                status.setTextColor(WatchUi.OK);
                status.setText("找到中继：" + first.host + "（如不对可手动修改）");
            });
        }), 0, 8);

        ui.add(page, ui.button("配对并连接", true, () -> {
            pairNow(hostField, codeField, status);
        }), 0, 8);

        // 带 autopair 启动时直接连线，免去在手表上再点一次。
        if (autoPair) {
            autoPair = false;
            status.setTextColor(WatchUi.MUTED);
            status.setText("正在按启动参数配对…");
            postDelayed(() -> pairNow(hostField, codeField, status), 300);
        }

        if (store.paired()) {
            ui.add(page, ui.button("断开连接", false, this::disconnect), 0, 8);
            ui.add(page, ui.button("返回会话", false, () -> {
                if (currentSessionId.isEmpty()) showSessionList();
                else showSession(currentSessionId);
            }), 0, 0);
        }
        ui.add(page, ui.text("一键配对会发广播找同一 WiFi 下的电脑，电脑上点「同意」即可，"
                + "地址与令牌自动导入。也可手动填地址与配对码：局域网 IP 或公网地址（https://…）。",
                10, WatchUi.MUTED), 0, 0);


        render(page);
        hostField.requestFocus();
    }

    /** 切换到一台已记住的设备。 */
    private void switchToDevice(final Store.Device device) {
        if (device == null || !device.valid()) {
            showToast("这台设备的令牌已失效，请重新配对");
            return;
        }
        store.useDevice(device);
        // 换设备等同于换了一个世界：会话、展开状态、列表指纹全部要清，
        // 否则会上一条设备的展开卡片套在新设备的内容上。
        currentSessionId = "";
        currentSessionTitle = "";
        currentAsk = null;
        currentRunning = false;
        currentBeforeSeq = 0;
        currentHasMore = false;
        currentProject = "";
        steps.clear();
        openGroups.clear();
        openRows.clear();
        lastListSignature = 0;
        showToast("已切换到 " + (device.label.isEmpty() ? device.base : device.label));
        showSessionList();
    }

    /** 手表上弹一个短提示（没有 Toast 时降级为日志）。 */
    private void showToast(String message) {
        try {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
            // 某些手表系统禁用了 Toast，忽略即可。
        }
    }

    /**
     * 一键配对：找局域网里的中继 → 请它在电脑上弹确认 → 拿到令牌 → 自动记下这台设备。
     *
     * 与「输入配对码」的区别只是获取令牌的方式：决定权都在电脑端
     * （用户要在弹出的框里点同意），手表不能自己把自己配上。
     */
    private void startQuickPair(WatchUi.Page page) {
        quickPairing = true;
        showPairingProgress("正在局域网里寻找电脑…");
        discovery.listen(found -> {
            if (destroyed || !quickPairing) return;
            if (found.isEmpty()) {
                quickPairing = false;
                showPairingProgress("没找到。请确认手表与电脑在同一个 WiFi，且电脑上的中继已启动。");
                return;
            }
            // 优先选已经配对过设备的那台（大概率就是用户常用的电脑）。
            Discovery.Found target = found.get(0);
            for (Discovery.Found item : found) {
                if (item.paired) {
                    target = item;
                    break;
                }
            }
            requestPairingFrom(target);
        });
    }

    /** 向指定中继发起配对请求，并轮询用户在电脑上的决定。 */
    private void requestPairingFrom(final Discovery.Found target) {
        final String base = "http://" + target.host + ":" + target.port;
        showPairingProgress("已找到 " + (target.name.isEmpty() ? target.host : target.name)
                + "\n正在请电脑确认…\n（请在电脑上看到提示后点「是」）");

        JSONObject body = new JSONObject();
        try {
            body.put("name", android.os.Build.MODEL == null ? "手表" : android.os.Build.MODEL);
            // 设备标识：中继用它认出「这台手表」，勾过「保存为常用设备」后不再弹窗。
            body.put("deviceId", store.deviceId());
        } catch (Exception ignored) {
            // 仅字符串，不会失败。
        }

        api.post(base, "/api/pair/request", "", body, result -> {
            if (destroyed || !quickPairing) return;
            if (!result.ok) {
                quickPairing = false;
                showPairingProgress("请求配对失败：" + result.error);
                return;
            }
            String requestId = Api.text(result.value, "requestId");
            if (requestId.isEmpty()) {
                quickPairing = false;
                showPairingProgress("配对响应缺少请求号");
                return;
            }
            pollPairingResult(base, target, requestId, 0);
        });
    }

    /** 轮询配对结果（最多约 45 秒，给用户足够时间在电脑上点确认）。 */
    private void pollPairingResult(final String base, final Discovery.Found target,
                                   final String requestId, final int attempt) {
        if (destroyed || !quickPairing) return;
        if (attempt > 45) {
            quickPairing = false;
            showPairingProgress("等待确认超时。可以在电脑上重开一次，或用配对码方式。");
            return;
        }
        postDelayed(() -> api.get(base, "/api/pair/result?id=" + Api.urlEncode(requestId), "",
                result -> {
                    if (destroyed || !quickPairing) return;
                    String state = result.ok ? Api.text(result.value, "state") : "";
                    if ("approved".equals(state)) {
                        String grant = Api.text(result.value, "grant");
                        exchangeGrant(base, target, grant);
                        return;
                    }
                    if ("denied".equals(state)) {
                        quickPairing = false;
                        showPairingProgress("电脑上拒绝了这次配对。如果还想连，请再点一次。");
                        return;
                    }
                    if ("expired".equals(state)) {
                        quickPairing = false;
                        showPairingProgress("这次请求已失效，请再点一次一键配对。");
                        return;
                    }
                    pollPairingResult(base, target, requestId, attempt + 1);
                }), 1000);
    }

    /** 用户已同意：用一次性票据换令牌，并记住这台设备。 */
    private void exchangeGrant(final String base, final Discovery.Found target, final String grant) {
        JSONObject body = new JSONObject();
        try {
            body.put("grant", grant);
        } catch (Exception ignored) {
            // 仅字符串，不会失败。
        }
        api.post(base, "/api/pair", "", body, result -> {
            if (destroyed) return;
            quickPairing = false;
            if (!result.ok) {
                showPairingProgress("换取令牌失败：" + result.error);
                return;
            }
            String token = Api.text(result.value, "token");
            if (token.isEmpty()) {
                showPairingProgress("配对响应缺少令牌");
                return;
            }
            String address = target.host + ":" + target.port;
            // 记成一台可切换的设备；同名电脑下次直接切，不用再配对。
            store.rememberDevice(address, token,
                    target.name.isEmpty() ? "" : target.name);
            // 配对响应里同时给了公网地址：一并记下，出门时不必重配。
            store.rememberAlternate(address, Api.text(result.value, "publicUrl"), store.deviceId());
            currentSessionId = "";
            currentSessionTitle = "";
            currentAsk = null;
            steps.clear();
            showToast("配对成功：" + (target.name.isEmpty() ? address : target.name));
            showSessionList();
        });
    }

    /** 一键配对过程中的进度页（只需简单反馈，不必每帧重绘整屏）。 */
    private void showPairingProgress(String message) {
        if (destroyed) return;
        onSessionPage = false;
        WatchUi.Page page = ui.page();
        ui.heading(page, "一键配对", "");
        LinearLayout card = ui.card();
        TextView text = ui.text(message, 12, WatchUi.FG);
        card.addView(text);
        ui.add(page, card, 6, 10);
        if (quickPairing) {
            ui.add(page, ui.button("取消", false, () -> {
                quickPairing = false;
                discovery.cancel();
                showConnect();
            }), 0, 0);
        } else {
            ui.add(page, ui.button("重试", true, () -> {
                quickPairing = true;
                showPairingProgress("正在局域网里寻找电脑…");
                discovery.listen(found -> {
                    if (destroyed || !quickPairing) return;
                    if (found.isEmpty()) {
                        quickPairing = false;
                        showPairingProgress("没找到。请确认手表与电脑在同一个 WiFi，且电脑上的中继已启动。");
                        return;
                    }
                    Discovery.Found target = found.get(0);
                    for (Discovery.Found item : found) {
                        if (item.paired) {
                            target = item;
                            break;
                        }
                    }
                    requestPairingFrom(target);
                });
            }), 0, 8);
            ui.add(page, ui.button("改用手动填写", false, this::showConnect), 0, 0);
        }
        render(page);
    }

    /** 用给定地址与配对码完成配对。 */
    private void tryPair(Store.Endpoint endpoint, String code, TextView status) {
        JSONObject body = new JSONObject();
        try {
            body.put("code", code);
        } catch (Exception ignored) {
            // 仅数字内容，不会失败
        }
        String root = endpoint.url("/").replaceAll("/$", "");
        api.post(root, "/api/pair", "", body, result -> {
            if (destroyed) return;
            if (!result.ok) {
                status.setTextColor(WatchUi.DANGER);
                status.setText(result.error);
                return;
            }
            String token = textOf(result.value, "token");
            if (token.isEmpty()) {
                status.setTextColor(WatchUi.DANGER);
                status.setText("配对响应缺少令牌");
                return;
            }
            // 记成一台可切换的设备（同一地址会覆盖，不会堆重复项）。
            store.rememberDevice(endpoint.display(), token, "");
            // 配对响应同时给了局域网与公网两条入口，一并记下。
            store.rememberAlternate(endpoint.display(), Api.text(result.value, "publicUrl"), store.deviceId());
            currentSessionId = "";
            currentSessionTitle = "";
            currentAsk = null;
            steps.clear();
            status.setTextColor(WatchUi.OK);
            status.setText("配对成功，正在载入会话…");
            showSessionList();
        });
    }

    // ------------------------------------------------------------ 会话列表页

    /**
     * 进入会话列表页（首次进入或从别处返回时调用）。
     *
     * 这里只负责渲染与安排定时刷新，**不**直接触发列表请求——请求结果回来后会自己
     * 决定要不要重绘。此前写成「渲染 + 强制请求」，而请求回调又会调用本方法，
     * 形成每轮都重建整屏的循环，屏幕因此持续上下抽动。
     */
    private void showSessionList() {
        onSessionPage = false;
        // 从会话页返回时回到列表顶部；列表自身的定时刷新则由 render() 保持位置。
        if (returningFromSession) {
            resetScroll();
            returningFromSession = false;
        }
        refresh.removeCallbacks(listTicker);
        renderSessionListPage();

        // 进列表页说明已经连上中继了：顺手同步一次公网地址。
        syncPublicAddress();

        // 首次进入需要真实数据；已有数据时后台静默刷新即可。
        if (sessions.isEmpty() && projects.isEmpty()) {
            loadSessions(false, true);
        } else {
            loadSessions(true, false);
        }
        refresh.postDelayed(listTicker, LIST_REFRESH_MS);
    }

    /**
     * 同步当前中继的公网地址。
     *
     * cloudflared 的临时地址每次重启都会变，如果手表只记着配对那一刻的地址，
     * 中继重启后就再也连不上了。这里用**不需要令牌**的 /api/identity 取最新地址：
     * 走局域网连上时能顺便拿到公网入口，走公网连上时则确认自己记的那条仍然有效。
     * 失败静默处理——这只是锦上添花，不能影响正常使用。
     */
    private void syncPublicAddress() {
        if (!store.paired()) return;
        final String current = store.base();
        api.get(baseUrl(), "/api/identity", store.token(), result -> {
            if (destroyed || !result.ok || result.value == null) return;
            String publicUrl = Api.text(result.value, "publicUrl");
            if (publicUrl.isEmpty()) return;
            // 当前用的就是这条公网地址：无需改动。
            if (publicUrl.equals(current)) return;
            store.rememberAlternate(current, publicUrl, "");
        });
    }

    /**
     * 当前地址连不上时，自动试这台设备的另一个入口。
     *
     * 同一台电脑的局域网与公网地址共用一个令牌：在家、在外应该都能连上。
     * 以前只认「当前那一条」，一旦换了网络（IP 变了、公网域名换了）就只剩
     * 「连不上电脑」——用户只能手动改地址。这里自动在两条之间切换，
     * 切成功后把这条提升为当前地址，后续请求就走它。
     *
     * @param onDone 切成功后回调（通常用于重试原请求）；失败不回调。
     */
    private void tryAlternateEndpoint(final Runnable onDone) {
        if (!store.paired()) return;
        final String current = store.base();
        String alternate = store.alternateFor(current);
        if (alternate.isEmpty() || alternate.equals(current)) return;

        api.get(alternate, "/api/identity", store.token(), result -> {
            if (destroyed || !result.ok || result.value == null) return;
            // 确认这个入口连的是同一台中继（服务标识一致）才切，避免连到别处。
            String service = Api.text(result.value, "service");
            if (!service.startsWith("dsh-watch-relay")) return;

            store.switchBase(alternate, current);
            showToast("已切换到另一个地址：" + alternate);
            if (onDone != null) onDone.run();
        });
    }

    /** 只渲染当前列表页，不发请求、不安排定时器。 */
    private void renderSessionListPage() {
        WatchUi.Page page = ui.page();
        if (currentProject.isEmpty()) {
            renderProjectList(page);
        } else {
            renderProjectSessions(page);
        }
        // 手势：在会话列表里右滑回到项目列表，左滑打开操作菜单。
        page.setOnSwipeRight(() -> {
            if (!currentProject.isEmpty()) {
                currentProject = "";
                resetScroll();
                showSessionList();
            }
        });
        page.setOnSwipeLeft(() -> showActionMenu(menuForProjects()));
        render(page);
    }

    /** 首屏：按项目分组，点进去看该项目的会话。 */
    private void renderProjectList(WatchUi.Page page) {
        ui.heading(page, "项目", loadingSession ? "正在刷新…" : "点一个项目看它的会话");

        if (pending.size() > 0) {
            ui.add(page, pendingNotice(), 0, 10);
        }

        if (projects.isEmpty()) {
            ui.add(page, ui.text(loadingSession ? "正在载入…" : "没有可显示的项目", 13, WatchUi.MUTED), 0, 10);
        }

        for (Api.Project project : projects) {
            String subtitle = project.sessions.size() + " 个会话";
            if (project.waiting > 0) subtitle = "待回答 · " + subtitle;
            else if (project.running > 0) subtitle = "运行中 · " + subtitle;
            View row = ui.row(project.project, subtitle + " · " + Api.relativeTime(project.updatedAt), () -> {
                currentProject = project.project;
                resetScroll();
                showSessionList();
            });
            if (project.waiting > 0 || project.running > 0) {
                row.setBackground(ui.surface(WatchUi.SURFACE_HI, 16));
            }
            ui.add(page, row, 0, 7);
        }

        ui.add(page, ui.button("全部会话", false, () -> {
            currentProject = ALL_SESSIONS;
            resetScroll();
            showSessionList();
        }), 4, 7);
        ui.add(page, ui.button("新建会话", false, this::createSession), 0, 7);
        ui.add(page, ui.button("断开连接", false, this::disconnect), 0, 0);
    }

    /** 「全部会话」用一个不与真实项目名冲突的哨兵值表示。 */
    private static final String ALL_SESSIONS = "\u0000all";

    /** 某个项目下的会话列表。 */
    private void renderProjectSessions(WatchUi.Page page) {
        boolean all = ALL_SESSIONS.equals(currentProject);
        List<Api.Session> visible = new ArrayList<>();
        if (all) {
            visible.addAll(sessions);
        } else {
            for (Api.Session session : sessions) {
                if (currentProject.equals(session.project)) visible.add(session);
            }
        }
        String subtitle = all ? "全部 " + visible.size() + " 个" : visible.size() + " 个会话";
        ui.heading(page, all ? "全部会话" : currentProject, loadingSession ? "正在刷新…" : subtitle);

        if (pending.size() > 0) {
            ui.add(page, pendingNotice(), 0, 10);
        }

        if (visible.isEmpty()) {
            ui.add(page, ui.text("这个项目下没有会话", 13, WatchUi.MUTED), 0, 10);
        }

        for (Api.Session session : visible) {
            String info = session.subtitle();
            if (!session.model.isEmpty()) info = info + " · " + shortModel(session.model);
            // 有子任务时把数量接在后面：不点进去也能看出哪个会话在后台干活。
            // 有子任务时省掉模型名，否则副标题折成三行、列表变得又高又难扫。
            String hint = session.subagentHint();
            if (!hint.isEmpty()) {
                info = session.subtitle() + " · " + hint;
            }
            View row = ui.row(session.title, info, () -> showSession(session.id));
            if (session.waiting) {
                // 待回答的会话加一个颜色块，扫一眼就能定位。
                row.setBackground(ui.surface(WatchUi.SURFACE_HI, 16));
            }
            ui.add(page, row, 0, 7);
        }

        ui.add(page, ui.button("返回项目", false, () -> {
            currentProject = "";
            resetScroll();
            showSessionList();
        }), 4, 7);
        ui.add(page, ui.button("新建会话", false, this::createSession), 0, 7);
        ui.add(page, ui.button("断开连接", false, this::disconnect), 0, 0);
    }

    /** 待答提示卡片，列表页与会话页共用。 */
    private View pendingNotice() {
        LinearLayout notice = ui.card();
        notice.addView(ui.text("有 " + pending.size() + " 个问题在等你回答", 13, WatchUi.WARN));
        TextView sub = ui.text("点这里直接回答", 11, WatchUi.MUTED);
        sub.setPadding(0, ui.dp(5), 0, 0);
        notice.addView(sub);
        notice.setOnClickListener(v -> showQuestion(pending.get(0)));
        notice.setFocusable(true);
        notice.setClickable(true);
        return notice;
    }

    private void disconnect() {
        store.clearToken();
        sessions.clear();
        projects.clear();
        pending.clear();
        currentProject = "";
        showConnect();
    }

    /** 模型名太长，去掉供应商前缀这类冗余部分。 */
    static String shortModel(String model) {
        String value = model == null ? "" : model;
        return value.length() <= 18 ? value : value.substring(0, 18) + "…";
    }

    private void loadSessions(boolean silent, boolean force) {
        if (!store.paired()) return;
        // 只有首次加载才显示「正在刷新…」；定时刷新时不要改副标题，
        // 否则文案在「正在刷新…」与正常描述之间来回跳，屏幕看起来就在抽动。
        if (!silent) loadingSession = true;
        api.get(baseUrl(), "/api/sessions", store.token(), result -> {
            loadingSession = false;
            // 必须判断「是否还在列表页」而不是「是否在会话页」：文件页、子任务页、
            // 计划页都会把 onSessionPage 置 false，只看它的话列表刷新会把这几页覆盖掉。
            if (destroyed || !onListPage()) return;
            if (!result.ok) {
                // 连不上时先自动试这台设备的另一个入口（局域网↔公网）。
                // 成功切换后会重新拉一次，用户不必自己去改地址。
                if (!alternateTried) {
                    alternateTried = true;
                    tryAlternateEndpoint(() -> {
                        alternateTried = false;
                        loadSessions(silent, force);
                    });
                    return;
                }
                alternateTried = false;
                // 静默刷新失败时保持现有画面：把错误页反复重绘同样会闪。
                if (silent) return;
                sessions.clear();
                projects.clear();
                pending.clear();
                showListError(result.error);
                return;
            }
            alternateTried = false;
            List<Api.Session> loadedSessions = Api.parseSessions(result.value);
            List<Api.Project> loadedProjects = Api.parseProjects(result.value);
            List<Api.Ask> loadedPending = Api.parsePending(result.value);
            int signature = listSignature(loadedSessions, loadedProjects, loadedPending);
            // 内容没变就不重建：列表页每 10 秒刷新一次，重建会让正在滑的内容跳动。
            if (silent && !force && signature == lastListSignature) return;
            lastListSignature = signature;
            sessions.clear();
            sessions.addAll(loadedSessions);
            projects.clear();
            projects.addAll(loadedProjects);
            pending.clear();
            pending.addAll(loadedPending);
            // 只重绘列表页，不再走 showSessionList：那条路径会重新发请求，
            // 与本次回调构成循环，表现为屏幕持续抽动。
            renderSessionListPage();
        });
    }

    /** 列表页内容指纹：会话、项目、待答问题任一变化都要重绘。 */
    private int listSignature(List<Api.Session> sessionList, List<Api.Project> projectList, List<Api.Ask> pendingList) {
        int signature = sessionList.size() * 31 + projectList.size() * 17 + pendingList.size() * 7;
        for (Api.Session session : sessionList) {
            signature = signature * 31 + session.id.hashCode()
                    + (session.running ? 1 : 0) + (session.waiting ? 2 : 0)
                    + session.title.hashCode();
        }
        for (Api.Project project : projectList) {
            signature = signature * 31 + project.project.hashCode() + project.sessions.size()
                    + project.running + project.waiting * 3;
        }
        return signature;
    }

    private int lastListSignature;

    private void showListError(String error) {
        onSessionPage = false;
        WatchUi.Page page = ui.page();
        ui.heading(page, "连不上电脑", "");
        ui.add(page, ui.text(error, 13, WatchUi.DANGER), 0, 12);
        ui.add(page, ui.text("地址：" + store.base(), 11, WatchUi.MUTED), 0, 12);
        ui.add(page, ui.button("重试", true, () -> {
            // 重试前顺手同步一次公网地址：中继重启后公网域名会变，
            // 而错误页是唯一能停留的地方，不在这里同步就永远拿不到新域名。
            syncPublicAddress();
            showSessionList();
        }), 0, 8);

        // 同一台电脑有局域网与公网两个入口，换网络后当前那条可能不通。
        // 给一个显式切换入口，不必重新配对（配对信息是好的，只是路换了）。
        String alternate = store.alternateFor(store.base());
        if (!alternate.isEmpty()) {
            ui.add(page, ui.button("换一个地址", false, () -> {
                tryAlternateEndpoint(this::showSessionList);
            }), 0, 6);
            ui.add(page, ui.text("这台电脑的另一个入口：" + alternate, 10, WatchUi.MUTED), 0, 8);
        }

        ui.add(page, ui.button("重新配对", false, () -> {
            store.clearToken();
            showConnect();
        }), 0, 0);
        // 错误页也要能开菜单（右键回到项目列表），否则用户被锁在这一页。
        page.setOnSwipeLeft(() -> showActionMenu(menuForProjects()));
        render(page);
    }

    private void createSession() {
        onSessionPage = false;
        WatchUi.Page page = ui.page();
        ui.heading(page, "新建会话", "");
        ui.add(page, ui.text("正在创建…", 13, WatchUi.MUTED), 0, 0);
        render(page);
        api.post(baseUrl(), "/api/create", store.token(), new JSONObject(), result -> {
            if (destroyed) return;
            if (!result.ok) {
                WatchUi.Page failed = ui.page();
                ui.heading(failed, "新建失败", "");
                ui.add(failed, ui.text(result.error, 13, WatchUi.DANGER), 0, 12);
                ui.add(failed, ui.button("返回", true, this::showSessionList), 0, 0);
                render(failed);
                return;
            }
            String sessionId = result.value.optString("sessionId", "");
            if (sessionId.isEmpty()) {
                showSessionList();
                return;
            }
            sessions.add(0, newSession(sessionId));
            showSession(sessionId);
        });
    }

    private Api.Session newSession(String sessionId) {
        Api.Session session = new Api.Session();
        session.id = sessionId;
        session.title = "新会话";
        session.updatedAt = System.currentTimeMillis();
        return session;
    }

    // -------------------------------------------------------------- 会话页

    private void showSession(String sessionId) {
        currentSessionId = sessionId;
        currentSessionTitle = titleOf(sessionId);
        store.setLastSession(sessionId, currentSessionTitle);
        onSessionPage = true;
        editing = false;
        // 从项目列表正常进入时，返回目标是列表；子任务入口会在本方法返回后覆盖它。
        sessionReturnTo = "";
        messages.clear();
        steps.clear();
        currentAsk = null;
        // 进入会话时自动滚到最新内容，省去手动翻到底部。
        autoScrollToBottom = true;
        renderSession("正在载入…");
        loadSession(sessionId, false);
        refresh.removeCallbacks(sessionTicker);
        refresh.postDelayed(sessionTicker, SESSION_REFRESH_MS);
    }

    private String titleOf(String sessionId) {
        for (Api.Session session : sessions) {
            if (session.id.equals(sessionId)) return session.title;
        }
        return "会话";
    }

    private void loadSession(String sessionId, boolean silent) {
        if (!store.paired() || sessionId.isEmpty()) return;
        api.get(baseUrl(), "/api/session?id=" + Api.urlEncode(sessionId), store.token(), result -> {
            if (destroyed || !onSessionPage || !sessionId.equals(currentSessionId)) return;
            // 请求在途时用户可能已经进入输入页；此时绝不能重建整屏。
            if (editing) return;
            if (!result.ok) {
                if (!silent) renderSessionError(result.error);
                return;
            }
            List<Api.Message> loaded = Api.parseMessages(result.value);
            List<Api.Step> loadedSteps = Api.parseSteps(result.value);
            Api.Usage loadedUsage = Api.parseUsage(result.value);
            currentRunning = result.value.optBoolean("running", false);
            currentAsk = Api.parseQuestion(result.value.optJSONObject("question"), sessionId, currentSessionTitle);
            currentBeforeSeq = result.value.optLong("beforeSeq", 0L);
            currentHasMore = result.value.optBoolean("hasMore", false);
            String title = result.value.optString("title", "");
            if (!title.isEmpty()) currentSessionTitle = title;

            // 会话里出现过的文件：文件页用它列表，也是中继允许读取的白名单来源。
            sessionFiles.clear();
            JSONArray fileArray = result.value.optJSONArray("files");
            if (fileArray != null) {
                for (int i = 0; i < fileArray.length(); i++) {
                    Api.FileEntry entry = Api.FileEntry.from(fileArray.optJSONObject(i));
                    if (entry != null) sessionFiles.add(entry);
                }
            }

            // 只在内容真的变了才更新。
            //
            // 这里曾经写成「静默刷新时只要状态不是运行中就重建」，结果空闲会话每 4 秒
            // 整屏重建一次，屏幕上就是持续闪烁。现在改成只替换列表区。
            int signature = contentSignature(loaded, loadedSteps, loadedUsage);
            if (silent && signature == lastContentSignature) return;
            lastContentSignature = signature;
            messages.clear();
            messages.addAll(loaded);
            steps.clear();
            steps.addAll(loadedSteps);
            usage = loadedUsage;
            if (silent) {
                // 自动刷新：原位更新列表，但副标题（模型/上下文）也要跟着变。
                refreshSessionContent();
                updateHeading();
            } else {
                renderSession("");
            }
        });
    }

    /** 只更新标题与副标题，不动列表区。 */
    private void updateHeading() {
        View root = getWindow().getDecorView().findViewById(android.R.id.content);
        if (!(root instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (!(child instanceof android.view.ViewGroup)) continue;
            android.view.ViewGroup frame = (android.view.ViewGroup) child;
            for (int j = 0; j < frame.getChildCount(); j++) {
                View inner = frame.getChildAt(j);
                if (!(inner instanceof android.widget.ScrollView)) continue;
                android.view.ViewGroup content = (android.view.ViewGroup) ((android.widget.ScrollView) inner).getChildAt(0);
                // 标题区的前两个子视图是标题与副标题。
                for (int k = 0; k < Math.min(2, content.getChildCount()); k++) {
                    View view = content.getChildAt(k);
                    if (view instanceof TextView) {
                        TextView text = (TextView) view;
                        if (k == 0) text.setText(clipLine(currentSessionTitle, 14));
                        else text.setText(sessionSubtitle());
                    }
                }
                return;
            }
        }
    }

    /** 画面内容指纹：消息、步骤、用量、运行状态、提问，任一变化都要重绘。 */
    private int contentSignature(List<Api.Message> messageList, List<Api.Step> stepList, Api.Usage usageValue) {
        int signature = 17;
        signature = signature * 31 + messageList.size();
        for (Api.Message message : messageList) {
            signature = signature * 31 + message.text.hashCode() + message.tools.size();
        }
        signature = signature * 31 + stepList.size();
        for (Api.Step step : stepList) {
            signature = signature * 31 + step.status.hashCode() + step.result.hashCode();
        }
        signature = signature * 31 + (currentRunning ? 1 : 0);
        signature = signature * 31 + (currentAsk == null ? 0 : 7);
        if (usageValue != null) {
            // 用 int 截断即可：指纹只用来判断「是否变化」，不需要完整数值。
            signature = signature * 31 + (int) (usageValue.outputTokens + usageValue.pressureTokens);
        }
        return signature;
    }

    private void renderSessionError(String error) {
        WatchUi.Page page = ui.page();
        ui.heading(page, "读取失败", currentSessionTitle);
        ui.add(page, ui.text(error, 13, WatchUi.DANGER), 0, 12);
        ui.add(page, ui.text("地址：" + store.base(), 11, WatchUi.MUTED), 0, 12);
        ui.add(page, ui.button("重试", true, () -> loadSession(currentSessionId, false)), 0, 8);
        // 必须留出口：应用会直接恢复到上次的会话页，若只给「重试」，地址失效时
        // （换网、中继换机或换端口）就再也进不了设置页，只能卸载重装。
        ui.add(page, ui.button("重新配对", false, () -> {
            store.clearToken();
            showConnect();
        }), 0, 0);
        // 手势与正常会话页保持一致，否则左滑开不了菜单、右滑回不去列表。
        page.setOnSwipeRight(this::backToList);
        page.setOnSwipeLeft(() -> showActionMenu(menuForSession()));
        render(page);
    }

    /** 重建会话页。hint 非空时在消息区顶部显示提示文字。 */
    private void renderSession(String hint) {
        WatchUi.Page page = ui.page();
        contentArea = null;
        // 标题限制在一行内：圆屏的可视高度有限，长标题折行会把内容挤出屏幕。
        ui.heading(page, clipLine(currentSessionTitle, 14), sessionSubtitle());

        if (!hint.isEmpty()) {
            ui.add(page, ui.text(hint, 12, WatchUi.MUTED), 0, 8);
        }

        // 操作按钮放在内容上方：长对话里用户不必先滚到底部才能发消息。
        if (currentAsk != null) {
            LinearLayout ask = ui.card();
            ask.addView(ui.text("AI 在等你回答", 13, WatchUi.WARN));
            String preview = currentAsk.questions.isEmpty() ? "" : currentAsk.questions.get(0).question;
            if (!preview.isEmpty()) {
                TextView sub = ui.text(cut(preview, 60), 11, WatchUi.MUTED);
                sub.setPadding(0, ui.dp(5), 0, 0);
                ask.addView(sub);
            }
            ask.setOnClickListener(v -> showQuestion(currentAsk));
            ask.setFocusable(true);
            ask.setClickable(true);
            ui.add(page, ask, 0, 8);
        }

        // 操作入口收敛：左滑打开菜单能拿到全部功能，页面上只保留最常用的两个，
        // 把屏幕留给内容（之前两排按钮占掉了近一半高度）。
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(ui.button("发消息", true, this::showCompose), weighted(0));
        View second = currentRunning
                ? ui.button("停止", false, this::cancelRun)
                : (currentAsk != null ? ui.button("回答", false, () -> showQuestion(currentAsk)) : ui.button("刷新", false, () -> {
                    renderSession("正在刷新…");
                    loadSession(currentSessionId, false);
                }));
        actions.addView(second, weighted(0));
        ui.add(page, actions, 0, 7);

        // 步骤区放在一个独立容器里：内容变化时可以只换它，不必重建整屏。
        // 整屏重建会让正在看的内容跳动，这是闪烁与卡顿感的来源。
        contentArea = new LinearLayout(this);
        contentArea.setOrientation(LinearLayout.VERTICAL);
        // 至少占满一屏：否则内容少的时候整页没法滚动，多点几下也翻不动。
        contentArea.setMinimumHeight(getResources().getDisplayMetrics().heightPixels);
        ui.add(page, contentArea, 0, 0);
        fillContent();

        // 历史翻页：手表只拉最近一批，想往前看要点一下。
        if (currentHasMore) {
            ui.add(page, ui.button("看更早的内容", false, this::loadOlder), 4, 7);
        }
        ui.add(page, ui.text("右滑返回 · 左滑菜单", 9, WatchUi.MUTED), 6, 0);

        // 手势：右滑返回上一级，左滑打开操作菜单。
        page.setOnSwipeRight(this::backToList);
        page.setOnSwipeLeft(() -> showActionMenu(menuForSession()));
        // 进入会话时直接看到最新内容；手动刷新或翻页时不打断当前位置。
        if (autoScrollToBottom) {
            autoScrollToBottom = false;
            scrollToBottomAfterRender();
        }
        render(page);
    }

    /** 下一次重建会话页时是否自动滚到末尾。 */
    private boolean autoScrollToBottom;

    /** 菜单项：标题、说明与动作。 */
    static final class MenuItem {
        final String title;
        final String subtitle;
        final Runnable action;

        MenuItem(String title, String subtitle, Runnable action) {
            this.title = title;
            this.subtitle = subtitle;
            this.action = action;
        }
    }

    /** 会话页的操作菜单。 */
    private List<MenuItem> menuForSession() {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem("发消息", "给这个会话发一条", this::showCompose));
        if (currentRunning) {
            items.add(new MenuItem("停止生成", "让 AI 立即停下", this::cancelRun));
        }
        if (currentAsk != null) {
            items.add(new MenuItem("回答问题", "AI 正在等你", () -> showQuestion(currentAsk)));
        }
        items.add(new MenuItem(showAllSteps ? "只看对话" : "看全部步骤",
                showAllSteps ? "隐藏工具调用细节" : "显示每一步工具与结果", () -> {
            showAllSteps = !showAllSteps;
            resetScroll();
            renderSession("");
        }));
        items.add(new MenuItem("刷新", "重新拉取最新内容", () -> {
            renderSession("正在刷新…");
            loadSession(currentSessionId, false);
        }));
        items.add(new MenuItem("用量", "Token 与耗时", this::showUsage));
        items.add(new MenuItem("模型", "切换模型与推理强度", this::showModelPicker));
        // 子任务与计划的入口**始终显示**：数据要等会话响应回来才有，按有无决定是否
        // 显示会让入口在数据到达前消失；点进去空列表也给了明确说明。
        items.add(new MenuItem("子任务", "这个会话派生的子智能体", this::openSubagentPage));
        items.add(new MenuItem("计划与任务", "待办、团队任务与目标", this::showPlan));
        // 文件列表要等会话数据回来才有，所以入口**始终显示**：点进去再按需拉取。
        // 之前按 sessionFiles 是否为空来决定显示，而菜单在会话数据回来之前就建好了，
        // 结果入口根本不出现（点了也永远看不到文件）。
        items.add(new MenuItem("查看文件", "会话里出现的 md / 代码文件", this::showFileList));
        if (currentHasMore) {
            items.add(new MenuItem("看更早的内容", "往前翻历史", this::loadOlder));
        }
        items.add(new MenuItem("跳到最新", "滚到对话末尾", () -> {
            scrollToBottomAfterRender();
            renderSession("");
        }));
        items.add(new MenuItem("设置", "连接地址与配对", this::showConnect));
        items.add(new MenuItem("subagents".equals(sessionReturnTo) ? "返回子任务" : "返回项目",
                "回到上一级列表", this::backToList));
        return items;
    }

    /** 项目列表页的操作菜单。 */
    private List<MenuItem> menuForProjects() {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem("新建会话", "在这台电脑上开一个新会话", this::createSession));
        items.add(new MenuItem("全部会话", "不按项目分组", () -> {
            currentProject = ALL_SESSIONS;
            resetScroll();
            showSessionList();
        }));
        if (!currentProject.isEmpty() && !ALL_SESSIONS.equals(currentProject)) {
            items.add(new MenuItem("返回项目", "回到项目列表", () -> {
                currentProject = "";
                resetScroll();
                showSessionList();
            }));
        }
        items.add(new MenuItem("刷新", "重新拉取列表", () -> {
            showSessionList();
        }));
        items.add(new MenuItem("设置", "连接地址与配对", this::showConnect));
        items.add(new MenuItem("断开连接", "清除配对，重新配一次", this::disconnect));
        return items;
    }

    /**
     * 操作菜单。
     *
     * 手势方向与面板位置必须一致：手指向左滑，是从右侧把面板拉出来（面板贴右边、从右滑入）。
     * 早期版本把面板放在左侧，手感上是「往左滑却从左边冒出来」，与直觉相反。
     *
     * 入场为短时过渡（面板位移 + 遮罩淡入，共约 180ms），手表性能有限，不做复杂转场。
     */
    private void showActionMenu(List<MenuItem> items) {
        if (isFinishing() || destroyed || menuOpen) return;
        menuOpen = true;
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xB3000000);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(ui.surface(WatchUi.SURFACE, 18));
        panel.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));

        TextView title = ui.text("操作", 13, WatchUi.FG);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        panel.addView(title);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (MenuItem item : items) {
            View row = ui.row(item.title, item.subtitle, () -> {
                dismissMenu(overlay, panel);
                // 等关闭动画走完再切换页面，避免动画被打断。
                overlay.postDelayed(item.action, 150);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(0, ui.dp(6), 0, 0);
            list.addView(row, params);
        }
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // 面板占屏幕右侧约 82%，贴右边缘；左侧留出的窄条可点击关闭。
        int panelWidth = Math.round(getResources().getDisplayMetrics().widthPixels * 0.82f);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(panelWidth, -1, Gravity.END);
        panelParams.setMargins(0, ui.dp(6), ui.dp(6), ui.dp(6));
        overlay.addView(panel, panelParams);
        overlay.setOnClickListener(v -> dismissMenu(overlay, panel));

        View rootView = getWindow().getDecorView().findViewById(android.R.id.content);
        if (rootView instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) rootView).addView(overlay,
                    new android.view.ViewGroup.LayoutParams(-1, -1));
        }

        // 入场：面板从右外侧滑入，遮罩淡入。
        panel.setTranslationX(panelWidth);
        panel.setAlpha(0.6f);
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(140).start();
        panel.animate().translationX(0f).alpha(1f).setDuration(180).start();
    }

    /** 关闭菜单：面板滑出右侧、遮罩淡出，结束后移除视图。 */
    private void dismissMenu(final View overlay, final View panel) {
        if (overlay.getParent() == null) {
            menuOpen = false;
            return;
        }
        panel.animate().translationX(panel.getWidth()).alpha(0.6f).setDuration(150).start();
        overlay.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            android.view.ViewGroup parent = (android.view.ViewGroup) overlay.getParent();
            if (parent != null) parent.removeView(overlay);
            menuOpen = false;
        }).start();
    }

    /** 步骤列表的容器；非空时表示当前屏幕上已有可原位更新的列表。 */
    private LinearLayout contentArea;

    /**
     * 已展开的工具组与单条明细。
     *
     * 界面用「重建整屏」刷新，展开状态若不记在这里，每次自动刷新（会话页每 4 秒一次）
     * 都会把用户刚点开的详情缩回去——这正是「点了展开却看不到内容」的直接原因。
     * 键是 {@link #groupKey} 与它拼上组内下标。
     */
    private final java.util.Set<String> openGroups = new java.util.HashSet<>();
    private final java.util.Set<String> openRows = new java.util.HashSet<>();

    /** 往内容区填充步骤（或只看对话时的消息）。 */
    private void fillContent() {
        if (contentArea == null) return;
        contentArea.removeAllViews();
        if (showAllSteps) {
            renderSteps(contentArea);
        } else {
            renderMessagesOnly(contentArea);
        }
        if (currentRunning) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(0, ui.dp(2), 0, ui.dp(8));
            contentArea.addView(ui.text("AI 正在处理…", 11, WatchUi.MUTED), params);
        }
    }

    /**
     * 内容变化时只更新列表区。
     *
     * 刷新（自动或手动）走这里而不是重建整屏：标题、按钮、滚动位置全部原样保留，
     * 用户看到的是内容增长而不是整页闪一下。
     */
    private void refreshSessionContent() {
        if (contentArea == null || contentArea.getParent() == null) {
            renderSession("");
            return;
        }
        fillContent();
    }

    /** 会话页副标题：状态 + 当前模型 + 上下文占用。 */
    private String sessionSubtitle() {
        StringBuilder builder = new StringBuilder();
        if (currentRunning) builder.append("正在运行");
        else if (currentAsk != null) builder.append("等你回答");
        else builder.append("空闲");
        if (usage != null && usage.contextWindow > 0) {
            builder.append(" · 上下文 ").append(usage.percent).append("%");
        }
        return builder.toString();
    }

    private LinearLayout.LayoutParams weighted(int rightDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
        params.setMargins(0, 0, ui.dp(rightDp), 0);
        return params;
    }

    /**
     * 按电脑上的观感逐条列出：正文，以及两段正文之间的工具调用。
     *
     * 分组依据是**正文输出**，不是「相邻的工具步骤」：中继为每次工具调用插了一条
     * 空文本的助手步骤（它只承载工具清单），所以相邻的工具步骤几乎从不相邻，
     * 按相邻关系分组会让每次调用各自成组，一屏塞满几十张单条卡片。
     * 这里把两段正文之间的全部工具调用并成一组，空文本消息不参与分隔。
     *
     * 折叠分两级：组卡片点开是这次连续调用的清单，再点某一条才看它的参数与结果。
     */
    private void renderSteps(LinearLayout container) {
        if (steps.isEmpty()) {
            container.addView(ui.text("还没有内容", 12, WatchUi.MUTED));
            return;
        }
        List<Api.Step> group = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Api.Step step = steps.get(i);
            if ("tool".equals(step.kind)) {
                group.add(step);
                continue;
            }
            if (step.text.trim().isEmpty()) continue;
            // 正文本身是一道分隔：先把上一段的工具调用收成一组。
            if (!group.isEmpty()) {
                addTo(container, toolGroup(group), 0, 6);
                group = new ArrayList<>();
            }
            addTo(container, stepLabel(step, i + 1), 0, 3);
            // 思考内容排在正文之前：它是得出这句话之前的推理，顺序与电脑上一致。
            // 默认折叠成一行，点开才看详细。
            if (step.thinking != null && !step.thinking.trim().isEmpty()) {
                addTo(container, thinkingBlock(step), 0, 5);
            }
            addTo(container, messageBubble(step), 0, 6);
        }
        if (!group.isEmpty()) addTo(container, toolGroup(group), 0, 6);
    }

    /**
     * 思考块：默认折叠成一行「思考」，点开看内容。
     *
     * 视觉上刻意比正文弱：左侧一条细竖线、灰色小字、无实心底色。
     * 这样它既能在对话里看得到（用户要求），又不会把正文淹掉。
     * 展开状态记在 openRows 里——界面是重建整屏刷新，只存在视图上会被下一次刷新收回。
     */
    private View thinkingBlock(final Api.Step step) {
        final String key = "think:" + step.seq;
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);

        // 左侧竖线：靠 MATCH_PARENT 跟着文字高度走。
        View bar = new View(this);
        bar.setBackgroundColor(WatchUi.LINE);
        LinearLayout.LayoutParams barParams =
                new LinearLayout.LayoutParams(ui.dp(2), LinearLayout.LayoutParams.MATCH_PARENT);
        barParams.setMargins(0, ui.dp(2), ui.dp(8), ui.dp(2));
        wrap.addView(bar, barParams);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bodyParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        wrap.addView(body, bodyParams);

        TextView header = ui.text("思考", 10, WatchUi.MUTED);
        body.addView(header);

        final TextView detail = ui.text(cut(step.thinking, 1200), 11, WatchUi.MUTED);
        detail.setPadding(0, ui.dp(4), 0, 0);
        boolean open = openRows.contains(key);
        detail.setVisibility(open ? View.VISIBLE : View.GONE);
        body.addView(detail);
        header.setText(open ? "思考 ▾" : "思考 ▸");

        // 折叠状态只挂在标题上；内容区保留可长按复制等系统行为。
        header.setOnClickListener(v -> {
            boolean nowOpen = detail.getVisibility() == View.VISIBLE;
            detail.setVisibility(nowOpen ? View.GONE : View.VISIBLE);
            header.setText(nowOpen ? "思考 ▸" : "思考 ▾");
            if (nowOpen) openRows.remove(key);
            else openRows.add(key);
            lastInteractionAt = System.currentTimeMillis();
        });
        return wrap;
    }

    /** 正文气泡：按 Markdown 渲染，用户消息用强调色。 */
    private View messageBubble(Api.Step step) {
        boolean user = "user".equals(step.role);
        CharSequence rendered = Markdown.render(cut(step.text, 2400),
                user ? WatchUi.ON_ACCENT : WatchUi.FG,
                user ? WatchUi.ON_ACCENT : WatchUi.ACCENT,
                user ? WatchUi.ON_ACCENT : WatchUi.MUTED,
                WatchUi.SURFACE_HI);
        return ui.bubbleRich(rendered, user);
    }

    /**
     * 一组工具调用的折叠卡片。
     *
     * 折叠态只显示「N 次工具调用」与涉及的工具名；第一次展开列出这次连续调用的清单，
     * 点清单里的某一条才展开它的参数与结果。单条时标题直接用工具名，读起来更自然。
     */
    private View toolGroup(List<Api.Step> group) {
        LinearLayout card = ui.card();
        // 每组一个稳定的编号，用于记住展开状态（自动刷新重建视图后要还原）。
        final String key = groupKey(group);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        boolean anyError = false;
        boolean anyRunning = false;
        StringBuilder labels = new StringBuilder();
        for (Api.Step step : group) {
            if (step.isError) anyError = true;
            if ("running".equals(step.status)) anyRunning = true;
            String label = step.label.isEmpty() ? "工具" : step.label;
            if (labels.indexOf(label) < 0) {
                if (labels.length() > 0) labels.append('、');
                labels.append(label);
            }
        }
        header.addView(ui.chip(anyRunning ? "进行中" : (anyError ? "有失败" : "完成"),
                anyRunning ? WatchUi.WARN : (anyError ? WatchUi.DANGER : WatchUi.OK)));
        header.addView(ui.text(" " + labels, 12, WatchUi.FG));
        card.addView(header);

        // 折叠态的一行摘要：第一条的参数或结果。
        String summary = summaryOf(group);
        TextView summaryView = ui.text(summary, 11, WatchUi.MUTED);
        summaryView.setPadding(0, ui.dp(6), 0, 0);
        summaryView.setMaxLines(2);
        summaryView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(summaryView);

        // 第一级：这次连续调用的清单，每行可点开看单条详情。
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setVisibility(View.GONE);
        final List<View> rowDetails = new ArrayList<>();
        final List<View> rowLines = new ArrayList<>();
        for (int i = 0; i < group.size(); i++) {
            final Api.Step step = group.get(i);
            final String rowKey = key + "#" + i;
            // 整行可点：点标题、点描述、点行的空白处都算「这一条」。
            // 只把监听器挂在标题那一小块上时，点行内其它位置会落到外层卡片上，
            // 结果是「想展开这一条，却把整组收了起来」。
            LinearLayout rowBox = new LinearLayout(this);
            rowBox.setOrientation(LinearLayout.VERTICAL);
            rowBox.setMinimumHeight(ui.dp(48));

            LinearLayout rowHead = new LinearLayout(this);
            rowHead.setOrientation(LinearLayout.HORIZONTAL);
            rowHead.setPadding(0, ui.dp(5), 0, 0);
            rowHead.addView(ui.chip(statusMark(step), statusColor(step)));
            String label = step.label.isEmpty() ? "工具" : step.label;
            rowHead.addView(ui.text(" " + label, 11, WatchUi.FG));
            rowBox.addView(rowHead);

            String detailLine = step.detail.isEmpty() ? firstLine(step.result) : shortenPaths(step.detail);
            TextView line = ui.text(detailLine, 10, WatchUi.MUTED);
            line.setPadding(ui.dp(2), ui.dp(3), 0, 0);
            line.setMaxLines(2);
            line.setEllipsize(android.text.TextUtils.TruncateAt.END);
            rowBox.addView(line);

            // 第二级：单条工具的参数、改动文件与结果。
            TextView full = ui.text(toolDetailText(step), 10,
                    step.isError ? WatchUi.DANGER : WatchUi.MUTED);
            full.setPadding(ui.dp(2), ui.dp(4), 0, 0);
            full.setVisibility(View.GONE);
            rowBox.addView(full);

            rowBox.setFocusable(true);
            rowBox.setClickable(true);
            rowBox.setOnClickListener(v -> {
                boolean open = full.getVisibility() != View.VISIBLE;
                full.setVisibility(open ? View.VISIBLE : View.GONE);
                line.setVisibility(open ? View.GONE : View.VISIBLE);
                if (open) openRows.add(rowKey);
                else openRows.remove(rowKey);
            });
            rowDetails.add(full);
            rowLines.add(line);
            list.addView(rowBox);
        }
        card.addView(list);

        TextView toggle = ui.text(group.size() > 1 ? "展开 " + group.size() + " 项 ▾" : "展开 ▾", 10, WatchUi.ACCENT);
        toggle.setPadding(0, ui.dp(6), 0, 0);
        card.addView(toggle);

        // 还原上一次的展开状态：自动刷新会整片重建视图，不留这份记忆的话，
        // 用户刚点开的详情会在下一次刷新（会话页每 4 秒一次）时缩回去。
        boolean expanded = openGroups.contains(key);
        restoreCollapsed(list, toggle, summaryView, expanded, group.size());
        for (int i = 0; i < rowDetails.size(); i++) {
            boolean rowOpen = openRows.contains(key + "#" + i);
            rowDetails.get(i).setVisibility(rowOpen ? View.VISIBLE : View.GONE);
            rowLines.get(i).setVisibility(rowOpen ? View.GONE : View.VISIBLE);
        }

        View.OnClickListener toggleGroup = v -> {
            // 参数是「目标状态」：当前可见说明该收起，不可见说明该展开。
            boolean targetOpen = list.getVisibility() != View.VISIBLE;
            if (targetOpen) openGroups.add(key);
            else {
                openGroups.remove(key);
                // 收起整组时连带收起内部明细，下次打开是干净的清单。
                for (int i = 0; i < rowDetails.size(); i++) {
                    rowDetails.get(i).setVisibility(View.GONE);
                    rowLines.get(i).setVisibility(View.VISIBLE);
                    openRows.remove(key + "#" + i);
                }
            }
            toggleCollapsed(list, toggle, summaryView, targetOpen, group.size());
        };
        // 收组只认标题、摘要与那行「展开/收起」提示；清单区域留给单条明细，
        // 否则「点这一条」会先被卡片抢走，变成把整组收起来。
        header.setOnClickListener(toggleGroup);
        header.setFocusable(true);
        header.setClickable(true);
        summaryView.setOnClickListener(toggleGroup);
        summaryView.setFocusable(true);
        summaryView.setClickable(true);
        toggle.setOnClickListener(toggleGroup);
        toggle.setFocusable(true);
        toggle.setClickable(true);
        return card;
    }

    /** 组内第一条工具步骤的位置与名字，用于在多次重建之间认出同一组。 */
    private static String groupKey(List<Api.Step> group) {
        Api.Step first = group.get(0);
        return first.seq + ":" + group.size() + ":" + first.name;
    }

    /** 还原展开状态：直接设可见性，不做动画，否则整屏重建时会在每张卡片上闪一遍。 */
    private void restoreCollapsed(View list, TextView toggle, View summary, boolean expanded, int count) {
        list.animate().cancel();
        list.setAlpha(1f);
        list.setVisibility(expanded ? View.VISIBLE : View.GONE);
        summary.setVisibility(expanded ? View.GONE : View.VISIBLE);
        toggle.setText(expanded ? "收起 ▴" : (count > 1 ? "展开 " + count + " 项 ▾" : "展开 ▾"));
    }

    /**
     * 用户点击后的展开/收起。
     *
     * 展开时隐藏摘要（清单里已有全部信息），折叠时恢复；动画用 alpha 渐显，
     * 手表上不宜做复杂转场。
     */
    private void toggleCollapsed(View list, TextView toggle, View summary, boolean expanded, int count) {
        if (expanded) {
            summary.setVisibility(View.GONE);
            animateExpand(list);
            toggle.setText("收起 ▴");
        } else {
            animateCollapse(list);
            summary.setVisibility(View.VISIBLE);
            toggle.setText(count > 1 ? "展开 " + count + " 项 ▾" : "展开 ▾");
        }
    }

    /**
     * 切换折叠状态。
     *
     * 展开时隐藏摘要（明细里已有全部信息），折叠时恢复；动画用 alpha + 高度渐显，
     * 手表上不宜做复杂转场。
     */
    private void setCollapsed(View card, View details, TextView toggle, View summary,
                              boolean collapsed, int count) {
        if (collapsed) {
            animateCollapse(details);
            summary.setVisibility(View.VISIBLE);
            toggle.setText(count > 1 ? "展开 " + count + " 项 ▾" : "展开 ▾");
        } else {
            summary.setVisibility(View.GONE);
            animateExpand(details);
            toggle.setText("收起 ▴");
        }
    }

    /** 展开：先可见再淡入，避免突兀。 */
    private void animateExpand(View details) {
        details.setVisibility(View.VISIBLE);
        details.setAlpha(0f);
        details.animate().alpha(1f).setDuration(140).start();
    }

    /** 收起：淡出后隐藏。 */
    private void animateCollapse(View details) {
        if (details.getVisibility() != View.VISIBLE) return;
        details.animate().alpha(0f).setDuration(110).withEndAction(() -> {
            details.setVisibility(View.GONE);
            details.setAlpha(1f);
        }).start();
    }

    /** 折叠态摘要：优先显示第一条的参数，没有就显示结果首行。 */
    private String summaryOf(List<Api.Step> group) {
        for (Api.Step step : group) {
            if (!step.detail.isEmpty()) return shortenPaths(step.detail);
        }
        for (Api.Step step : group) {
            if (!step.result.isEmpty()) return firstLine(step.result);
        }
        return "";
    }

    /** 取首行并压短，用于折叠摘要。 */
    private static String firstLine(String text) {
        String value = text == null ? "" : text.trim();
        int end = value.indexOf('\n');
        if (end > 0) value = value.substring(0, end);
        if (value.length() > 90) value = value.substring(0, 90) + "…";
        return value;
    }

    /**
     * 展开后单条工具的完整文本：参数、改动文件、结果。
     *
     * 拼成一段纯文本再一次渲染，而不是多个视图叠起来——手表上一次重建会同时
     * 生成所有卡片的明细，视图数量直接决定滚动是否跟手。
     */
    private CharSequence toolDetailText(Api.Step step) {
        StringBuilder builder = new StringBuilder();
        if (!step.detail.isEmpty()) {
            builder.append(shortenPaths(step.detail));
        }
        if (!step.files.isEmpty()) {
            if (builder.length() > 0) builder.append("\n\n");
            builder.append("改动文件：");
            for (int i = 0; i < step.files.size() && i < 6; i++) {
                if (i > 0) builder.append('\n');
                builder.append("· ").append(shortPath(step.files.get(i)));
            }
            if (step.files.size() > 6) builder.append("\n· 等 ").append(step.files.size()).append(" 个文件");
        }
        String result = step.result.isEmpty() ? "（还在执行）" : cut(shortenPaths(step.result), 3000);
        if (builder.length() > 0) builder.append("\n\n");
        builder.append(result);
        // 结果常是 Markdown（很多工具输出本身就是 .md 内容），一并渲染。
        return Markdown.render(builder.toString(),
                step.isError ? WatchUi.DANGER : WatchUi.MUTED,
                WatchUi.ACCENT, WatchUi.MUTED, WatchUi.SURFACE_HI);
    }

    /** 往任意容器里加视图（走 WatchUi 以保证圆屏四角安全）。 */
    private void addTo(LinearLayout container, View view, int top, int bottom) {
        ui.addTo(container, view, top, bottom);
    }

    // ------------------------------------------------------------ 子任务与计划

    /** 当前父会话派生的子任务。 */
    private final List<Api.Subagent> subagents = new ArrayList<>();
    /** 当前会话的计划与任务。 */
    private Api.Plan plan;
    private boolean onSubagentPage;
    private boolean onPlanPage;
    /** 子任务页对应的父会话（从子任务点进对话后，返回要回到这里）。 */
    private String subagentParentId = "";
    private String subagentParentTitle = "";
    /** 两个新页面的内容指纹，避免定时刷新时无变化也重建整屏。 */
    private int lastSubagentSignature;
    private int lastPlanSignature;
    /**
     * 会话页的返回目标：`subagents` 表示回子任务列表，空串表示回项目列表。
     *
     * 子任务对话复用的是普通会话页（子会话与普通会话同构），所以「返回」要按
     * 进入路径决定去哪，不能一律回项目列表。
     */
    private String sessionReturnTo = "";

    /**
     * 当前是否停在会话列表页。
     *
     * 列表的定时刷新与它的响应回调都以这个为准：此前只判断 `onSessionPage`，
     * 于是停在文件页（它会把 onSessionPage 置 false）时，列表刷新一到就会把
     * 文件页整屏覆盖掉。子任务页与计划页同样不能被覆盖，因此统一走这里。
     */
    private boolean onListPage() {
        return !onSessionPage && !onFilePage && !onSubagentPage && !onPlanPage;
    }

    /** 菜单入口：用当前会话作父会话，进入子任务列表。 */
    private void openSubagentPage() {
        if (currentSessionId.isEmpty()) {
            showToast("还没有打开会话");
            return;
        }
        showSubagentList(currentSessionId, currentSessionTitle);
    }

    /** 进入子任务列表页。 */
    private void showSubagentList(String parentId, String parentTitle) {
        subagentParentId = parentId;
        subagentParentTitle = parentTitle;
        onSubagentPage = true;
        onSessionPage = false;
        onFilePage = false;
        onPlanPage = false;
        editing = true;
        subagents.clear();
        // 换页面要回顶部：render() 默认保留上一页的滚动位置，否则新页面会停在
        // 中间、连标题都看不见（实测从长对话点进子任务页就是这样）。
        resetScroll();
        refreshSubagentPage("正在读取子任务…");
        loadSubagents(false);
        refresh.removeCallbacks(subagentTicker);
        refresh.postDelayed(subagentTicker, SESSION_REFRESH_MS);
    }

    /** 拉取子任务列表；silent 为真时只在内容变化后重绘。 */
    private void loadSubagents(final boolean silent) {
        if (!store.paired() || subagentParentId.isEmpty()) return;
        api.get(baseUrl(), "/api/subagents?id=" + Api.urlEncode(subagentParentId), store.token(), result -> {
            if (destroyed || !onSubagentPage) return;
            if (!result.ok) {
                // 静默刷新失败时保持现有画面，避免错误页反复重绘。
                if (!silent) refreshSubagentPage(result.error);
                return;
            }
            List<Api.Subagent> loaded = Api.parseSubagents(result.value);
            int signature = subagentSignature(loaded);
            if (silent && signature == lastSubagentSignature) return;
            lastSubagentSignature = signature;
            subagents.clear();
            subagents.addAll(loaded);
            refreshSubagentPage("");
        });
    }

    /** 子任务列表的内容指纹。 */
    private int subagentSignature(List<Api.Subagent> list) {
        int signature = list.size() * 31;
        for (Api.Subagent subagent : list) {
            signature = signature * 31 + subagent.id.hashCode() + subagent.state.hashCode()
                    + (int) (subagent.settledMs % 1000) + (int) subagent.steps;
        }
        return signature;
    }

    /** 画子任务列表页（只渲染，不发请求）。 */
    private void refreshSubagentPage(String hint) {
        WatchUi.Page page = ui.page();
        ui.heading(page, "子任务", subagentParentTitle.isEmpty() ? "" : clipLine(subagentParentTitle, 12));
        if (!hint.isEmpty()) ui.add(page, ui.text(hint, 11, WatchUi.MUTED), 0, 8);

        if (subagents.isEmpty()) {
            ui.add(page, ui.text("这个会话还没有派生过子任务。", 11, WatchUi.MUTED), 0, 10);
        } else {
            int running = 0;
            for (Api.Subagent subagent : subagents) if (subagent.running) running += 1;
            ui.add(page, ui.text("共 " + subagents.size() + " 个" + (running > 0 ? "，其中 " + running + " 个在跑" : ""),
                    10, WatchUi.MUTED), 0, 8);
            for (final Api.Subagent subagent : subagents) {
                LinearLayout card = ui.card();
                card.addView(ui.text(clipLine(subagent.label, 24), 12, WatchUi.FG));
                TextView state = ui.text(subagent.subtitle(), 10, subagent.stateColor());
                card.addView(state);
                card.setOnClickListener(v -> openSubagent(subagent));
                card.setFocusable(true);
                card.setClickable(true);
                ui.add(page, card, 0, 6);
            }
        }
        ui.add(page, ui.button("返回会话", false, this::backToSessionFromSubagents), 6, 0);
        page.setOnSwipeRight(this::backToSessionFromSubagents);
        page.setOnSwipeLeft(() -> showActionMenu(menuForSubagents()));
        render(page);
    }

    /** 从子任务页回到它所属的父会话。 */
    private void backToSessionFromSubagents() {
        onSubagentPage = false;
        editing = false;
        onSessionPage = true;
        sessionReturnTo = "";
        // 进过子任务对话后 currentSessionId 已经指向子会话，这里必须显式回到父会话，
        // 否则「返回会话」会把用户送回刚才那个子任务。
        if (!subagentParentId.isEmpty()) {
            currentSessionId = subagentParentId;
            currentSessionTitle = subagentParentTitle;
            store.setLastSession(currentSessionId, currentSessionTitle);
            lastContentSignature = 0;
        }
        resetScroll();
        renderSession("");
        loadSession(currentSessionId, false);
    }

    /** 打开某个子任务自己的对话：复用普通会话页。 */
    private void openSubagent(Api.Subagent subagent) {
        onSubagentPage = false;
        editing = false;
        // showSession 会把返回目标重置成「项目列表」，因此子任务标记要在它之后设。
        showSession(subagent.id);
        sessionReturnTo = "subagents";
        // showSession 的标题取自会话列表，子任务不在那个列表里，这里补上。
        currentSessionTitle = subagent.label;
        // 子任务的对话只是「进去看一眼」，不该成为下次启动恢复的会话：
        // 那会把用户直接丢进一个子代理的历史里，连它属于谁都看不出来。
        store.setLastSession(subagentParentId, subagentParentTitle);
        renderSession("");
    }

    /** 子任务页的操作菜单。 */
    private List<MenuItem> menuForSubagents() {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem("刷新", "重新拉取子任务", () -> {
            refreshSubagentPage("正在刷新…");
            loadSubagents(false);
        }));
        items.add(new MenuItem("设置", "连接地址与配对", this::showConnect));
        items.add(new MenuItem("返回会话", "回到这个会话", this::backToSessionFromSubagents));
        return items;
    }

    /** 进入计划与任务页。 */
    private void showPlan() {
        onPlanPage = true;
        onSessionPage = false;
        onFilePage = false;
        onSubagentPage = false;
        editing = true;
        plan = null;
        // 同子任务页：换页面必须回到顶部，否则标题被上一页的滚动位置带出屏幕。
        resetScroll();
        refreshPlanPage("正在读取计划…");
        loadPlan(false);
        refresh.removeCallbacks(planTicker);
        refresh.postDelayed(planTicker, PLAN_REFRESH_MS);
    }

    /** 拉取计划与任务；数据来自会话响应里的 plan 块。 */
    private void loadPlan(final boolean silent) {
        if (!store.paired() || currentSessionId.isEmpty()) return;
        api.get(baseUrl(), "/api/session?id=" + Api.urlEncode(currentSessionId), store.token(), result -> {
            if (destroyed || !onPlanPage) return;
            if (!result.ok) {
                if (!silent) refreshPlanPage(result.error);
                return;
            }
            Api.Plan loaded = Api.parsePlan(result.value);
            int signature = planSignature(loaded);
            if (silent && signature == lastPlanSignature) return;
            lastPlanSignature = signature;
            plan = loaded;
            refreshPlanPage("");
        });
    }

    /** 计划页的内容指纹。 */
    private int planSignature(Api.Plan value) {
        int signature = 17;
        if (value == null) return signature;
        for (Api.Todo todo : value.todos) signature = signature * 31 + todo.content.hashCode() + todo.status.hashCode();
        for (Api.Member member : value.members) signature = signature * 31 + member.name.hashCode() + member.phase.hashCode();
        for (Api.TeamTask task : value.tasks) signature = signature * 31 + task.subject.hashCode() + task.status.hashCode();
        if (value.goal != null) signature = signature * 31 + value.goal.phase.hashCode() + (int) value.goal.rounds;
        return signature * 31 + (value.planActive ? 1 : 0) + (value.planPending ? 2 : 0);
    }

    /** 画计划与任务页（只渲染，不发请求）。 */
    private void refreshPlanPage(String hint) {
        WatchUi.Page page = ui.page();
        ui.heading(page, "计划与任务", currentSessionTitle.isEmpty() ? "" : clipLine(currentSessionTitle, 12));
        if (!hint.isEmpty()) ui.add(page, ui.text(hint, 11, WatchUi.MUTED), 0, 8);

        if (plan == null || plan.isEmpty()) {
            ui.add(page, ui.text("这个会话没有待办、任务或目标。", 11, WatchUi.MUTED), 0, 10);
        } else {
            if (plan.planActive || plan.planPending) {
                LinearLayout mode = ui.card();
                mode.addView(ui.text("计划模式", 11, WatchUi.ACCENT));
                mode.addView(ui.text(plan.planActive ? "正在计划模式：先出方案，不动手"
                        : "计划待确认", 10, WatchUi.MUTED));
                ui.add(page, mode, 0, 8);
            }

            if (plan.goal != null) {
                LinearLayout goal = ui.card();
                goal.addView(ui.text("目标", 11, WatchUi.ACCENT));
                goal.addView(ui.text(plan.goal.objective, 11, WatchUi.FG));
                StringBuilder meta = new StringBuilder(plan.goal.phaseText());
                String rounds = plan.goal.roundsText();
                if (!rounds.isEmpty()) meta.append(" · ").append(rounds);
                goal.addView(ui.text(meta.toString(), 10, plan.goal.phaseColor()));
                if (!plan.goal.blockedReason.isEmpty()) {
                    goal.addView(ui.text("受阻：" + plan.goal.blockedReason, 10, WatchUi.DANGER));
                }
                ui.add(page, goal, 0, 8);
            }

            if (!plan.todos.isEmpty()) {
                int done = 0;
                for (Api.Todo todo : plan.todos) if ("completed".equals(todo.status)) done += 1;
                LinearLayout todos = ui.card();
                todos.addView(ui.text("待办 " + done + "/" + plan.todos.size(), 11, WatchUi.ACCENT));
                for (Api.Todo todo : plan.todos) {
                    // 状态与正文必须同一行、且状态单独上色：分成两行时八条待办会糊成
                    // 一片，分不清哪一行状态属于哪一条。
                    SpannableStringBuilder line = new SpannableStringBuilder();
                    line.append(todo.statusText()).append("  ");
                    line.setSpan(new ForegroundColorSpan(todo.statusColor()), 0, line.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    line.append(todo.content);
                    TextView row = ui.text(line, 11, "completed".equals(todo.status) ? WatchUi.MUTED : WatchUi.FG);
                    row.setPadding(0, ui.dp(4), 0, 0);
                    todos.addView(row);
                }
                ui.add(page, todos, 0, 8);
            }

            if (!plan.members.isEmpty() || !plan.tasks.isEmpty()) {
                LinearLayout team = ui.card();
                team.addView(ui.text("团队", 11, WatchUi.ACCENT));
                for (Api.Member member : plan.members) {
                    SpannableStringBuilder line = new SpannableStringBuilder();
                    line.append(member.name).append(" · ");
                    line.append("lead".equals(member.role) ? "主持" : "成员").append(" · ");
                    int phaseStart = line.length();
                    line.append(member.phaseText());
                    line.setSpan(new ForegroundColorSpan(member.phaseColor()), phaseStart, line.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (!member.error.isEmpty()) line.append("：").append(member.error);
                    TextView row = ui.text(line, 10, WatchUi.FG);
                    row.setPadding(0, ui.dp(4), 0, 0);
                    team.addView(row);
                }
                for (Api.TeamTask task : plan.tasks) {
                    SpannableStringBuilder line = new SpannableStringBuilder();
                    line.append(task.statusText()).append("  ");
                    line.setSpan(new ForegroundColorSpan(task.statusColor()), 0, line.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    line.append(clipLine(task.subject, 20));
                    if (!task.owner.isEmpty()) line.append(" · ").append(task.owner);
                    TextView row = ui.text(line, 10, WatchUi.FG);
                    row.setPadding(0, ui.dp(4), 0, 0);
                    team.addView(row);
                }
                ui.add(page, team, 0, 8);
            }

            if (!plan.subagentLabel.isEmpty()) {
                ui.add(page, ui.text("这个会话本身是子任务：" + clipLine(plan.subagentLabel, 20), 10, WatchUi.MUTED), 0, 8);
            }
        }
        ui.add(page, ui.button("返回会话", false, this::backToSessionFromPlan), 6, 0);
        page.setOnSwipeRight(this::backToSessionFromPlan);
        page.setOnSwipeLeft(() -> showActionMenu(menuForPlan()));
        render(page);
    }

    /** 从计划页回到会话页。 */
    private void backToSessionFromPlan() {
        onPlanPage = false;
        editing = false;
        onSessionPage = true;
        resetScroll();
        renderSession("");
        loadSession(currentSessionId, false);
    }

    /** 计划页的操作菜单。 */
    private List<MenuItem> menuForPlan() {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem("刷新", "重新拉取计划", () -> {
            refreshPlanPage("正在刷新…");
            loadPlan(false);
        }));
        items.add(new MenuItem("设置", "连接地址与配对", this::showConnect));
        items.add(new MenuItem("返回会话", "回到这个会话", this::backToSessionFromPlan));
        return items;
    }

    // ------------------------------------------------------------ 文件查看

    /** 会话里出现过的文件（由中继在 session 响应里给出）。 */
    private final List<Api.FileEntry> sessionFiles = new ArrayList<>();
    /** 当前是否停留在文件页（决定定时刷新要不要跳过）。 */
    private boolean onFilePage;
    /** 本次失败是否已经尝试过切换备用地址（避免无限循环）。 */
    private boolean alternateTried;

    /**
     * 文件列表页：列出这次会话里碰到的 md / txt / 代码文件。
     *
     * 只列「这次会话里出现过的」，不提供目录浏览——手表屏幕小，翻目录不如直接点，
     * 而且这样中继就不必把整台电脑的文件系统暴露出去。
     */
    private void showFileList() {
        onFilePage = true;
        onSessionPage = false;
        editing = true;
        refreshFiles("正在读取文件列表…");

        // 主动拉一次：菜单可能在会话数据回来之前就建好了，本地缓存的 sessionFiles
        // 此时还是空的。用当前会话的响应刷新它，再重画列表。
        if (!store.paired() || currentSessionId.isEmpty()) {
            refreshFiles("");
            return;
        }
        api.get(baseUrl(), "/api/session?id=" + Api.urlEncode(currentSessionId), store.token(), result -> {
            if (destroyed || !onFilePage) return;
            if (result.ok && result.value != null) {
                sessionFiles.clear();
                JSONArray fileArray = result.value.optJSONArray("files");
                if (fileArray != null) {
                    for (int i = 0; i < fileArray.length(); i++) {
                        Api.FileEntry entry = Api.FileEntry.from(fileArray.optJSONObject(i));
                        if (entry != null) sessionFiles.add(entry);
                    }
                }
            }
            refreshFiles("");
        });
    }

    private void refreshFiles(String hint) {
        WatchUi.Page page = ui.page();
        ui.heading(page, "文件", currentSessionTitle.isEmpty() ? "" : clipLine(currentSessionTitle, 12));
        if (!hint.isEmpty()) ui.add(page, ui.text(hint, 11, WatchUi.MUTED), 0, 8);

        if (sessionFiles.isEmpty()) {
            ui.add(page, ui.text("这次会话里还没有出现文件。", 11, WatchUi.MUTED), 0, 10);
        } else {
            for (final Api.FileEntry file : sessionFiles) {
                LinearLayout card = ui.card();
                card.addView(ui.text(file.name, 12, WatchUi.FG));
                String meta = file.kindText() + (file.size > 0 ? " · " + formatSize(file.size) : "");
                card.addView(ui.text(meta, 10, WatchUi.MUTED));
                card.setOnClickListener(v -> openFile(file));
                card.setFocusable(true);
                card.setClickable(true);
                ui.add(page, card, 0, 6);
            }
        }
        ui.add(page, ui.button("返回", false, this::backToSessionFromFiles), 6, 0);
        page.setOnSwipeRight(this::backToSessionFromFiles);
        render(page);
    }

    /** 从文件页回到会话页：必须恢复 onSessionPage，否则会话页的静默刷新会全部被丢弃。 */
    private void backToSessionFromFiles() {
        onFilePage = false;
        editing = false;
        onSessionPage = true;
        renderSession("");
        loadSession(currentSessionId, false);
    }

    /** 打开一个文件并渲染其内容。 */
    private void openFile(final Api.FileEntry file) {
        WatchUi.Page page = ui.page();
        ui.heading(page, clipLine(file.name, 16), "正在读取…");
        render(page);

        api.get(baseUrl(), "/api/file?session=" + Api.urlEncode(currentSessionId)
                + "&path=" + Api.urlEncode(file.path), store.token(), result -> {
                    if (destroyed || !onFilePage) return;
                    if (!result.ok) {
                        WatchUi.Page failed = ui.page();
                        ui.heading(failed, clipLine(file.name, 16), "读取失败");
                        ui.add(failed, ui.text(result.error, 11, WatchUi.DANGER), 0, 8);
                        ui.add(failed, ui.button("返回", false, this::showFileList), 4, 0);
                        render(failed);
                        return;
                    }
                    showFileContent(file, result.value);
                });
    }

    /** 按文件类型选渲染器：markdown 走 Markdown，代码走 CodeView，纯文本原样。 */
    private void showFileContent(Api.FileEntry file, JSONObject value) {
        String kind = Api.text(value, "kind");
        String language = Api.text(value, "language");
        String text = Api.text(value, "text");
        boolean truncated = value != null && value.optBoolean("truncated", false);

        WatchUi.Page page = ui.page();
        String subtitle = language.isEmpty() ? file.kindText() : language;
        ui.heading(page, clipLine(file.name, 16), subtitle);

        if (truncated) {
            ui.add(page, ui.text("文件较长，只显示前一部分", 10, WatchUi.WARN), 0, 6);
        }

        TextView view = new TextView(this);
        view.setTextSize(11);
        view.setLineSpacing(ui.dp(2), 1f);
        if ("markdown".equals(kind)) {
            // md 用 Markdown 渲染：标题、列表、代码块、链接才有结构感。
            view.setText(Markdown.render(text, WatchUi.FG, WatchUi.ACCENT, WatchUi.MUTED, WatchUi.SURFACE_HI));
        } else if ("code".equals(kind)) {
            // 代码用带行号与高亮的渲染器：缩进与行号对读代码是刚需。
            view.setText(CodeView.render(text, WatchUi.FG, WatchUi.MUTED, WatchUi.OK,
                    WatchUi.ACCENT, WatchUi.WARN, WatchUi.SURFACE_HI, 1));
            // 代码不能自动折行：折行后缩进层级全乱，改为横向滚动由外层容器承担。
            view.setHorizontallyScrolling(true);
        } else {
            view.setText(CodeView.plain(text, WatchUi.FG, WatchUi.SURFACE_HI));
        }
        view.setTextColor(WatchUi.FG);
        ui.add(page, view, 0, 8);

        ui.add(page, ui.button("返回文件列表", false, this::showFileList), 4, 4);
        ui.add(page, ui.button("返回会话", true, this::backToSessionFromFiles), 0, 0);
        page.setOnSwipeRight(this::showFileList);
        render(page);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }


    /** 步骤小标题：区分「你」和「AI」，并标出步号。 */
    private TextView stepLabel(Api.Step step, int index) {
        boolean user = "user".equals(step.role);
        TextView label = ui.text((user ? "你" : "AI") + " · 第 " + index + " 步", 10,
                user ? WatchUi.ACCENT : WatchUi.MUTED);
        return label;
    }

    private String statusMark(Api.Step step) {
        if ("running".equals(step.status)) return "进行中";
        if (step.isError || "error".equals(step.status)) return "失败";
        return "完成";
    }

    private int statusColor(Api.Step step) {
        if ("running".equals(step.status)) return WatchUi.WARN;
        if (step.isError || "error".equals(step.status)) return WatchUi.DANGER;
        return WatchUi.OK;
    }

    /** 只看对话（不显示工具步骤）。 */
    private void renderMessagesOnly(LinearLayout container) {
        if (messages.isEmpty()) {
            container.addView(ui.text("还没有对话", 12, WatchUi.MUTED));
            return;
        }
        for (Api.Message message : messages) {
            if (message.text.trim().isEmpty()) continue;
            Api.Step step = new Api.Step();
            step.role = message.role;
            step.text = message.text;
            addTo(container, messageBubble(step), 0, 6);
        }
    }

    /** 往前翻一页历史。 */
    private void loadOlder() {
        if (currentBeforeSeq <= 1) {
            renderSession("已经是最早的内容了");
            return;
        }
        long through = currentBeforeSeq - 1;
        renderSession("正在读取更早的内容…");
        api.get(baseUrl(),
                "/api/session?id=" + Api.urlEncode(currentSessionId) + "&through=" + through,
                store.token(), result -> {
                    if (destroyed || !onSessionPage) return;
                    if (!result.ok) {
                        renderSession("读取失败：" + result.error);
                        return;
                    }
                    List<Api.Step> older = Api.parseSteps(result.value);
                    if (older.isEmpty()) {
                        currentHasMore = false;
                        renderSession("已经是最早的内容了");
                        return;
                    }
                    // 把更早的步骤接到当前列表前面，用户往回翻就能看到。
                    List<Api.Step> merged = new ArrayList<>(older);
                    merged.addAll(steps);
                    steps.clear();
                    steps.addAll(merged);
                    currentBeforeSeq = result.value.optLong("beforeSeq", 0L);
                    currentHasMore = result.value.optBoolean("hasMore", false);
                    lastContentSignature = 0;
                    renderSession("已载入更早的 " + older.size() + " 个步骤");
                });
    }

    /** 返回时把内容指纹清掉，强制下次进入重新渲染。 */
    private void backToList() {
        // 从子任务对话返回时回到子任务列表，而不是项目列表——否则用户点进一个
        // 子任务看两眼，就被扔回了项目首屏，还得重新找回来。
        if ("subagents".equals(sessionReturnTo)) {
            sessionReturnTo = "";
            onSessionPage = false;
            editing = false;
            refresh.removeCallbacks(sessionTicker);
            showSubagentList(subagentParentId, subagentParentTitle);
            return;
        }
        onSessionPage = false;
        editing = false;
        returningFromSession = true;
        lastContentSignature = 0;
        refresh.removeCallbacks(sessionTicker);
        store.setLastSession("", "");
        showSessionList();
    }

    // ---------------------------------------------------------- 用量与模型

    /** 用量页：token 消耗、上下文占用、耗时统计。 */
    private void showUsage() {
        onSessionPage = true;
        editing = true;
        WatchUi.Page page = ui.page();
        ui.heading(page, "用量", currentSessionTitle);

        if (usage == null) {
            ui.add(page, ui.text("还没有用量数据（本会话尚未产生回复）", 12, WatchUi.MUTED), 0, 10);
        } else {
            LinearLayout tokens = ui.card();
            tokens.addView(ui.text("Token 消耗", 12, WatchUi.ACCENT));
            tokens.addView(usageRow("输入（未命中缓存）", formatTokens(usage.inputTokens)));
            tokens.addView(usageRow("输出", formatTokens(usage.outputTokens)));
            tokens.addView(usageRow("缓存读取", formatTokens(usage.cacheReadTokens)));
            if (usage.cacheWriteTokens > 0) {
                tokens.addView(usageRow("缓存写入", formatTokens(usage.cacheWriteTokens)));
            }
            ui.add(page, tokens, 0, 9);

            LinearLayout context = ui.card();
            context.addView(ui.text("上下文占用", 12, WatchUi.ACCENT));
            context.addView(usageRow("已使用", formatTokens(usage.pressureTokens) + " / " + formatTokens(usage.contextWindow)));
            context.addView(usageRow("占比", usage.percent + "%"));
            context.addView(usageRow("系统提示", formatTokens(usage.systemTokens)));
            context.addView(usageRow("工具定义", formatTokens(usage.toolsTokens)));
            context.addView(usageRow("对话消息", formatTokens(usage.messageTokens)));
            ui.add(page, context, 0, 9);

            LinearLayout speed = ui.card();
            speed.addView(ui.text("运行统计", 12, WatchUi.ACCENT));
            speed.addView(usageRow("轮次 / 步数", usage.turns + " / " + usage.steps));
            speed.addView(usageRow("模型耗时", formatDuration(usage.llmMs)));
            speed.addView(usageRow("工具耗时", formatDuration(usage.toolMs)));
            speed.addView(usageRow("首字延迟", formatDuration(usage.ttftMs)));
            if (!usage.model.isEmpty()) {
                speed.addView(usageRow("模型", usage.provider + "/" + usage.model));
            }
            if (!usage.effort.isEmpty()) {
                speed.addView(usageRow("推理强度", effortName(usage.effort)));
            }
            ui.add(page, speed, 0, 9);
        }

        ui.add(page, ui.button("返回会话", true, () -> {
            editing = false;
            renderSession("");
        }), 0, 0);
        render(page);
    }

    /** 用量表里的一行：左标签右数值。 */
    private View usageRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, ui.dp(4), 0, 0);
        TextView left = ui.text(label, 11, WatchUi.MUTED);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView right = ui.text(value, 11, WatchUi.FG);
        row.addView(right, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    /** 大数字用 k/M 简写，手表上放不下完整数字。 */
    static String formatTokens(long value) {
        if (value < 1000) return String.valueOf(value);
        if (value < 1_000_000) {
            double k = value / 1000.0;
            return (k >= 100 ? String.valueOf(Math.round(k)) : String.format(java.util.Locale.US, "%.1f", k)) + "k";
        }
        double m = value / 1_000_000.0;
        return String.format(java.util.Locale.US, "%.2f", m) + "M";
    }

    /** 毫秒转「1.2 秒」「3 分 5 秒」这类可读形式。 */
    static String formatDuration(long ms) {
        if (ms <= 0) return "—";
        if (ms < 1000) return ms + " 毫秒";
        if (ms < 60_000) return String.format(java.util.Locale.US, "%.1f", ms / 1000.0) + " 秒";
        long totalSeconds = Math.round(ms / 1000.0);
        return (totalSeconds / 60) + " 分 " + (totalSeconds % 60) + " 秒";
    }

    /** 推理档位的中文名。 */
    static String effortName(String effort) {
        switch (effort) {
            case "off": return "关闭";
            case "low": return "低";
            case "medium": return "中";
            case "high": return "高";
            case "xhigh": return "很高";
            case "max": return "最高";
            default: return effort;
        }
    }

    /** 模型选择页：先显示当前模型，再选供应商、模型，最后选推理档位。 */
    private void showModelPicker() {
        onSessionPage = true;
        editing = true;
        modelProviders.clear();
        modelDefault = null;
        renderModelLoading();
        api.get(baseUrl(), "/api/models", store.token(), result -> {
            if (destroyed) return;
            if (!result.ok) {
                renderModelError(result.error);
                return;
            }
            modelProviders.addAll(Api.parseModels(result.value));
            modelDefault = Api.parseModelDefault(result.value);
            if (modelProviders.isEmpty()) {
                renderModelError("电脑上没有可用的模型");
                return;
            }
            renderProviderPicker(0);
        });
    }

    private final List<Api.ProviderInfo> modelProviders = new ArrayList<>();

    /** 目录里的默认选择；会话尚无用量时充当「当前模型」。 */
    private Api.ModelChoice modelDefault;

    /**
     * 当前生效的模型（供应商、模型名、推理强度）。
     *
     * 优先用会话用量——它是这个会话真正在跑的模型；会话还没有回复时用量是空的，
     * 这时才退回目录里的默认值。两者都不能凭空捏造，否则会显示一个并不生效的模型。
     */
    private Api.ModelChoice currentChoice() {
        Api.ModelChoice choice = new Api.ModelChoice();
        if (usage != null && !usage.model.isEmpty()) {
            choice.provider = usage.provider;
            choice.model = usage.model;
            choice.effort = usage.effort;
            return choice;
        }
        // 会话列表的摘要里也带模型，进入会话后先于用量到位。
        for (Api.Session session : sessions) {
            if (session.id.equals(currentSessionId) && !session.model.isEmpty()) {
                choice.provider = session.provider;
                choice.model = session.model;
                choice.effort = session.effort;
                return choice;
            }
        }
        return modelDefault == null ? choice : modelDefault;
    }

    /** 「供应商/模型」的展示形态；缺供应商时只给模型名。 */
    private static String choiceName(Api.ModelChoice choice) {
        if (choice.model.isEmpty()) return "未知";
        return choice.provider.isEmpty() ? choice.model : choice.provider + "/" + choice.model;
    }

    /** 当前模型的展示名：优先用目录里的可读名，找不到就用 id。 */
    private String choiceDisplayName(Api.ModelChoice choice) {
        for (Api.ProviderInfo provider : modelProviders) {
            for (Api.ModelInfo model : provider.models) {
                if (model.id.equals(choice.model)) {
                    return model.name.isEmpty() ? choice.model : model.name;
                }
            }
        }
        return choice.model.isEmpty() ? "未知" : choice.model;
    }

    private void renderModelLoading() {
        WatchUi.Page page = ui.page();
        ui.heading(page, "选择模型", "正在读取…");
        ui.add(page, ui.text("从电脑获取可用模型列表", 12, WatchUi.MUTED), 0, 0);
        render(page);
    }

    private void renderModelError(String error) {
        WatchUi.Page page = ui.page();
        ui.heading(page, "选择模型", "");
        ui.add(page, ui.text(error, 13, WatchUi.DANGER), 0, 12);
        ui.add(page, ui.button("返回会话", true, () -> {
            editing = false;
            renderSession("");
        }), 0, 0);
        render(page);
    }

    /** 第一步：先说明当前模型，再列出供应商。 */
    private void renderProviderPicker(int scrollTo) {
        WatchUi.Page page = ui.page();
        Api.ModelChoice current = currentChoice();
        ui.heading(page, "选择模型", "当前：" + choiceDisplayName(current));
        ui.add(page, currentModelCard(current), 0, 10);
        ui.add(page, ui.text("第 1 步：选供应商", 11, WatchUi.ACCENT), 0, 5);
        for (Api.ProviderInfo provider : modelProviders) {
            String subtitle = provider.models.size() + " 个模型";
            if (provider.provider.equals(current.provider)) subtitle = "当前 · " + subtitle;
            ui.add(page, ui.row(provider.name, subtitle, () -> renderModelList(provider, 0)), 0, 7);
        }
        ui.add(page, ui.button("返回会话", false, () -> {
            editing = false;
            renderSession("");
        }), 4, 0);
        render(page);
        page.scroll.post(() -> page.scroll.scrollTo(0, scrollTo));
    }

    /** 当前模型卡片：模型名、所属供应商与推理强度。 */
    private View currentModelCard(Api.ModelChoice current) {
        LinearLayout card = ui.card();
        card.addView(ui.text("当前模型", 11, WatchUi.ACCENT));
        TextView name = ui.text(choiceDisplayName(current), 14, WatchUi.FG);
        name.setPadding(0, ui.dp(5), 0, 0);
        card.addView(name);
        StringBuilder sub = new StringBuilder(choiceName(current));
        if (!current.effort.isEmpty()) sub.append(" · 推理强度 ").append(effortName(current.effort));
        TextView detail = ui.text(sub.toString(), 10, WatchUi.MUTED);
        detail.setPadding(0, ui.dp(4), 0, 0);
        detail.setMaxLines(2);
        card.addView(detail);
        return card;
    }

    /** 第二步：列出该供应商的模型。 */
    private void renderModelList(Api.ProviderInfo provider, int scrollTo) {
        WatchUi.Page page = ui.page();
        Api.ModelChoice current = currentChoice();
        ui.heading(page, provider.name, "第 2 步：选模型");
        for (Api.ModelInfo model : provider.models) {
            String subtitle = model.efforts.isEmpty() ? "" : "推理强度 " + model.efforts.size() + " 档";
            if (model.id.equals(current.model)) subtitle = "当前使用" + (subtitle.isEmpty() ? "" : " · " + subtitle);
            ui.add(page, ui.row(model.name, subtitle, () -> {
                if (model.efforts.isEmpty()) {
                    applyModel(provider.provider, model.id, "");
                } else {
                    renderEffortPicker(provider, model, 0);
                }
            }), 0, 7);
        }
        ui.add(page, ui.button("返回供应商", false, () -> renderProviderPicker(0)), 4, 0);
        render(page);
        page.scroll.post(() -> page.scroll.scrollTo(0, scrollTo));
    }

    /** 第三步：选推理档位。 */
    private void renderEffortPicker(Api.ProviderInfo provider, Api.ModelInfo model, int scrollTo) {
        WatchUi.Page page = ui.page();
        Api.ModelChoice current = currentChoice();
        ui.heading(page, model.name, "第 3 步：选推理强度");
        for (String effort : model.efforts) {
            // 只有当前用的就是这个模型时才标「当前」，否则会同时出现好几行「当前」。
            String subtitle = model.id.equals(current.model) && effort.equals(current.effort) ? "当前" : "";
            ui.add(page, ui.row(effortName(effort), subtitle, () -> applyModel(provider.provider, model.id, effort)), 0, 7);
        }
        ui.add(page, ui.button("返回模型", false, () -> renderModelList(provider, 0)), 4, 0);
        render(page);
        page.scroll.post(() -> page.scroll.scrollTo(0, scrollTo));
    }

    /** 提交模型切换。 */
    private void applyModel(String provider, String model, String effort) {
        WatchUi.Page page = ui.page();
        ui.heading(page, "切换中…", provider + "/" + model);
        ui.add(page, ui.text("正在通知电脑切换模型", 12, WatchUi.MUTED), 0, 0);
        render(page);

        JSONObject body = new JSONObject();
        try {
            body.put("sessionId", currentSessionId);
            body.put("provider", provider);
            body.put("model", model);
            if (!effort.isEmpty()) body.put("effort", effort);
        } catch (Exception ignored) {
            // 都是字符串，不会失败
        }

        api.post(baseUrl(), "/api/model", store.token(), body, result -> {
            if (destroyed) return;
            if (!result.ok) {
                WatchUi.Page failed = ui.page();
                ui.heading(failed, "切换失败", "");
                ui.add(failed, ui.text(result.error, 12, WatchUi.DANGER), 0, 12);
                ui.add(failed, ui.button("返回会话", true, () -> {
                    editing = false;
                    renderSession("");
                }), 0, 0);
                render(failed);
                return;
            }
            // 本地立刻反映新模型，随后拉一次会话以同步用量里的模型信息。
            String newProvider = result.value.optString("provider", provider);
            String newModel = result.value.optString("model", model);
            String newEffort = result.value.optString("effort", effort);
            if (usage != null) {
                usage.provider = newProvider;
                usage.model = newModel;
                usage.effort = newEffort;
            }
            // 会话还没产生回复时用量是空的；不补一份选择记录，返回模型页仍会显示旧模型。
            Api.ModelChoice choice = new Api.ModelChoice();
            choice.provider = newProvider;
            choice.model = newModel;
            choice.effort = newEffort;
            modelDefault = choice;
            for (Api.Session session : sessions) {
                if (session.id.equals(currentSessionId)) {
                    session.provider = newProvider;
                    session.model = newModel;
                    session.effort = newEffort;
                }
            }
            editing = false;
            lastContentSignature = 0;
            renderSession("已切换到 " + newModel);
            loadSession(currentSessionId, true);
        });
    }

    private void cancelRun() {
        JSONObject body = new JSONObject();
        try {
            body.put("sessionId", currentSessionId);
        } catch (Exception ignored) {
            // 不会失败
        }
        api.post(baseUrl(), "/api/cancel", store.token(), body, result -> {
            if (destroyed) return;
            if (!result.ok) {
                renderSession("停止失败：" + result.error);
                return;
            }
            renderSession("已请求停止");
            loadSession(currentSessionId, false);
        });
    }

    // -------------------------------------------------------------- 发送消息

    private void showCompose() {
        onSessionPage = true;
        editing = true;
        WatchUi.Page page = ui.page();
        ui.heading(page, "发消息", currentSessionTitle);

        final EditText input = new EditText(this);
        input.setHint("输入内容…");
        input.setTextSize(14);
        input.setTextColor(WatchUi.FG);
        input.setHintTextColor(WatchUi.MUTED);
        input.setMinLines(3);
        input.setMaxLines(8);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setBackground(ui.surface(WatchUi.SURFACE, 14));
        input.setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9));
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        ui.add(page, input, 0, 9);

        ui.add(page, ui.button("发送", true, () -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return;
            sendMessage(text);
        }), 0, 7);

        ui.add(page, ui.button("取消", false, () -> {
            editing = false;
            showSession(currentSessionId);
        }), 0, 0);
        render(page);
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null) manager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 250);
    }

    private void sendMessage(String text) {
        editing = false;
        JSONObject body = new JSONObject();
        try {
            body.put("sessionId", currentSessionId);
            body.put("text", text);
        } catch (Exception ignored) {
            // 字符串不会失败
        }
        renderSession("正在发送…");
        api.post(baseUrl(), "/api/send", store.token(), body, result -> {
            if (destroyed || !onSessionPage) return;
            if (!result.ok) {
                renderSession("发送失败：" + result.error);
                return;
            }
            loadSession(currentSessionId, false);
        });
    }

    // -------------------------------------------------------------- 回答问题

    private void showQuestion(Api.Ask ask) {
        if (ask == null || ask.questions.isEmpty()) {
            showSessionList();
            return;
        }
        currentAsk = ask;
        onSessionPage = true;
        // 答题期间必须暂停会话页的自动刷新：否则每 4 秒重建一次整屏，
        // 用户刚点开的选项列表会被立刻换回会话页，根本来不及选。
        editing = true;
        renderQuestionPage(ask, 0, new ArrayList<>(), null);
    }

    /**
     * 渲染问题页。
     *
     * 一个 callId 下可能有多个小问，而 DSH 要求「一次提交必须覆盖全部小问」，
     * 所以这里用小步进：一次显示一个问题，最后一个确认后一次性提交。
     * @param index 当前问题下标
     * @param chosen 已选中的答案（与下标对齐，元素为已选标签列表）
     */
    private void renderQuestionPage(final Api.Ask ask, final int index, final List<List<String>> chosen, String hint) {
        WatchUi.Page page = ui.page();
        Api.Question question = ask.questions.get(index);
        String title = question.header.isEmpty() ? "第 " + (index + 1) + " 个问题" : question.header;
        ui.heading(page, title, ask.questions.size() > 1 ? "第 " + (index + 1) + "/" + ask.questions.size() + " 问" : ask.sessionTitle);

        if (hint != null && !hint.isEmpty()) {
            ui.add(page, ui.text(hint, 12, WatchUi.WARN), 0, 8);
        }

        ui.add(page, ui.text(question.question, 13, WatchUi.FG), 0, 10);

        if (question.options.isEmpty()) {
            ui.add(page, ui.text("这个问题没有预设选项，需要打字回答。", 12, WatchUi.MUTED), 0, 9);
            ui.add(page, ui.button("打字回答", true, () -> showCustomAnswer(ask, index, chosen, "")), 0, 7);
            ui.add(page, ui.button("返回", false, () -> showSession(ask.sessionId)), 0, 0);
            render(page);
            return;
        }

        final List<String> picked = index < chosen.size() ? chosen.get(index) : new ArrayList<>();
        for (Api.Option option : question.options) {
            boolean selected = picked.contains(option.label);
            String subtitle = option.description;
            if (selected) subtitle = subtitle.isEmpty() ? "已选择" : "已选择 · " + subtitle;
            View row = ui.row(option.label, subtitle, () -> {
                if (question.multiSelect) {
                    if (picked.contains(option.label)) picked.remove(option.label);
                    else picked.add(option.label);
                    renderQuestionPage(ask, index, chosen, null);
                } else {
                    picked.clear();
                    picked.add(option.label);
                    advance(ask, index, chosen, picked);
                }
            });
            if (selected) row.setBackground(ui.surface(WatchUi.SURFACE_HI, 16));
            ui.add(page, row, 0, 7);
        }

        if (question.multiSelect) {
            ui.add(page, ui.button("选好了", true, () -> {
                if (picked.isEmpty()) {
                    renderQuestionPage(ask, index, chosen, "请至少选一项");
                    return;
                }
                advance(ask, index, chosen, picked);
            }), 4, 7);
        }

        ui.add(page, ui.button("打字回答", false, () -> showCustomAnswer(ask, index, chosen, "")), 0, 7);
        ui.add(page, ui.button("返回会话", false, () -> showSession(ask.sessionId)), 0, 0);
        render(page);
    }

    /** 记录当前问题的选择并进入下一问或提交。 */
    private void advance(Api.Ask ask, int index, List<List<String>> chosen, List<String> picked) {
        List<List<String>> next = new ArrayList<>(chosen);
        while (next.size() <= index) next.add(new ArrayList<>());
        next.set(index, new ArrayList<>(picked));
        if (index + 1 < ask.questions.size()) {
            renderQuestionPage(ask, index + 1, next, null);
            return;
        }
        submitAnswers(ask, next);
    }

    /** 用文字作答（选项之外的补充或没有选项时）。 */
    private void showCustomAnswer(final Api.Ask ask, final int index, final List<List<String>> chosen, String preset) {
        onSessionPage = true;
        editing = true;
        WatchUi.Page page = ui.page();
        Api.Question question = ask.questions.get(index);
        ui.heading(page, "打字回答", question.header.isEmpty() ? "" : question.header);

        final EditText input = new EditText(this);
        input.setHint("输入你的回答");
        input.setText(preset);
        input.setTextSize(14);
        input.setTextColor(WatchUi.FG);
        input.setHintTextColor(WatchUi.MUTED);
        input.setMinLines(2);
        input.setMaxLines(6);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setBackground(ui.surface(WatchUi.SURFACE, 14));
        input.setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9));
        ui.add(page, input, 0, 9);

        ui.add(page, ui.button("提交", true, () -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) return;
            editing = false;
            List<List<String>> next = new ArrayList<>(chosen);
            while (next.size() <= index) next.add(new ArrayList<>());
            // 用 custom 表达自由回答；selected 留空时 DSH 会以 custom 为准。
            List<String> picked = new ArrayList<>();
            picked.add("");
            next.set(index, picked);
            customAnswers.put(ask.questions.get(index).id, value);
            if (index + 1 < ask.questions.size()) {
                renderQuestionPage(ask, index + 1, next, null);
                return;
            }
            submitAnswers(ask, next);
        }), 0, 7);

        ui.add(page, ui.button("返回", false, () -> renderQuestionPage(ask, index, chosen, null)), 0, 0);
        render(page);
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null) manager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 250);
    }

    /** 自由回答的暂存：questionId → 文本。 */
    private final java.util.Map<String, String> customAnswers = new java.util.HashMap<>();

    /** 一次性提交全部小问的答案。 */
    private void submitAnswers(final Api.Ask ask, List<List<String>> chosen) {
        JSONArray answers = new JSONArray();
        try {
            for (int i = 0; i < ask.questions.size(); i++) {
                Api.Question question = ask.questions.get(i);
                List<String> picked = i < chosen.size() ? chosen.get(i) : new ArrayList<>();
                // 选项里混入的空串是「本条为自由回答」的标记。
                boolean custom = picked.contains("");
                JSONObject item = new JSONObject();
                item.put("id", question.id);
                JSONArray selected = new JSONArray();
                if (!custom) {
                    for (String label : picked) selected.put(label);
                }
                item.put("selected", selected);
                if (custom) {
                    String text = customAnswers.get(question.id);
                    if (text != null && !text.isEmpty()) item.put("custom", text);
                }
                answers.put(item);
            }
        } catch (Exception error) {
            renderQuestionPage(ask, 0, chosen, "提交失败：" + error.getMessage());
            return;
        }

        JSONObject body = new JSONObject();
        try {
            body.put("sessionId", ask.sessionId);
            body.put("callId", ask.callId);
            // 前台等待中的提问由中继按 eventId 回填 waterfall 结果，必须原样带回。
            if (!ask.eventId.isEmpty()) body.put("eventId", ask.eventId);
            body.put("answers", answers);
        } catch (Exception ignored) {
            // 不会失败
        }

        WatchUi.Page page = ui.page();
        ui.heading(page, "提交中…", "");
        ui.add(page, ui.text("正在把回答发回电脑", 12, WatchUi.MUTED), 0, 0);
        render(page);

        api.post(baseUrl(), "/api/answer", store.token(), body, result -> {
            if (destroyed) return;
            if (!result.ok) {
                renderQuestionPage(ask, 0, chosen, "提交失败：" + result.error);
                return;
            }
            customAnswers.clear();
            currentAsk = null;
            showSession(ask.sessionId);
        });
    }

    // ---------------------------------------------------------------- 工具

    /** 手表上长文本会拖垮滚动体验，截断并省略（可折行，用于正文）。 */
    private static String cut(String text, int limit) {
        String value = text == null ? "" : text.trim();
        if (value.length() <= limit) return value;
        return value.substring(0, limit) + "\n…（已省略）";
    }

    /**
     * 单行截断（不插入换行，用于标题）。
     *
     * 标题必须保持一行：圆屏可视高度有限，标题折成两行就会把内容区挤掉。
     */
    static String clipLine(String text, int limit) {
        String value = text == null ? "" : text.trim();
        if (value.length() <= limit) return value;
        return value.substring(0, limit) + "…";
    }

    /**
     * 路径只保留最后两段。
     *
     * 手表上放不下 `D:\桌面\项目‘\手表dsh\app\src\main\java\...\MainActivity.java`，
     * 但只留文件名又分不清同名文件，所以保留「父目录/文件名」。
     */
    static String shortPath(String path) {
        if (path == null || path.trim().isEmpty()) return "";
        String normalized = path.replace('/', '\\');
        String[] parts = normalized.split("\\\\");
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty()) kept.add(part);
        }
        if (kept.size() <= 2) return normalized;
        return kept.get(kept.size() - 2) + "\\" + kept.get(kept.size() - 1);
    }

    /**
     * 把一段文本里出现的长路径整体压短。
     *
     * 工具参数摘要常是「C:\Users\...\src\main\java\...\MainActivity.java」这种长串，
     * 在 233dp 宽的圆屏上会占满整屏。这里按空白切分，逐段把像路径的部分压成末两段。
     */
    static String shortenPaths(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] words = text.split(" ");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (i > 0) builder.append(' ');
            // 只有含反斜杠且足够长的词才当路径处理，避免动到普通文本。
            if (word.length() > 24 && word.indexOf('\\') >= 0) {
                builder.append(shortPath(word));
            } else {
                builder.append(word);
            }
        }
        return builder.toString();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 系统返回键回到会话列表，而不是直接退出应用。
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (onSessionPage) {
                backToList();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}
