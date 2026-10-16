package cn.dsh.watch;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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

    private WatchUi ui;
    private Store store;
    private Api api;
    private Discovery discovery;

    private final List<Api.Session> sessions = new ArrayList<>();
    private final List<Api.Ask> pending = new ArrayList<>();
    private final List<Api.Message> messages = new ArrayList<>();

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
    /** 下次重建整屏时是否回到顶部。 */
    private boolean resetScrollOnNextRender;
    /** 标记「刚从会话页返回」，用于让会话列表回顶部。 */
    private boolean returningFromSession;

    private final android.os.Handler refresh = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable sessionTicker = new Runnable() {
        @Override
        public void run() {
            if (destroyed || !onSessionPage) return;
            // 正在输入时跳过本次刷新，避免重建把输入框和已打的内容清掉。
            if (!editing) loadSession(currentSessionId, true);
            refresh.postDelayed(this, SESSION_REFRESH_MS);
        }
    };
    private final Runnable listTicker = new Runnable() {
        @Override
        public void run() {
            if (destroyed || onSessionPage) return;
            loadSessions(true);
            refresh.postDelayed(this, LIST_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new WatchUi(this);
        store = new Store(this);
        api = new Api();
        discovery = new Discovery();
        // 允许从外部带入地址与配对码（例如电脑端生成一条命令让手表直接配对），
        // 省去在手表小键盘上输入 IP 的麻烦。
        applyIntentExtras(getIntent());
        if (store.paired()) {
            currentSessionId = store.lastSession();
            if (currentSessionId.isEmpty()) showSessionList();
            else showSession(currentSessionId);
        } else {
            showConnect();
        }
    }

    /** 读取 host/port/code 形式的启动参数并预填。 */
    private void applyIntentExtras(Intent intent) {
        if (intent == null) return;
        String host = intent.getStringExtra("host");
        String code = intent.getStringExtra("code");
        int port = intent.getIntExtra("port", 0);
        if (host != null && !host.trim().isEmpty()) {
            pendingHost = host.trim();
            pendingCode = code == null ? "" : code.trim();
            if (port > 0 && port <= 65535) pendingPort = port;
        }
    }

    private String pendingHost = "";
    private String pendingCode = "";
    private int pendingPort = 0;

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
        int restore = resetScrollOnNextRender ? 0 : currentScroll();
        resetScrollOnNextRender = false;
        setContentView(page.root);
        page.scroll.post(() -> page.scroll.scrollTo(0, restore));
    }

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

    // ---------------------------------------------------------------- 连接页

    private void showConnect() {
        onSessionPage = false;
        WatchUi.Page page = ui.page();
        ui.heading(page, "连接电脑", "在同一局域网内运行中继");

        final EditText hostField = new EditText(this);
        hostField.setHint("电脑地址，如 192.168.1.121");
        hostField.setText(pendingHost.isEmpty() ? store.host() : pendingHost);
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

        ui.add(page, ui.button("自动查找", false, () -> {
            status.setTextColor(WatchUi.MUTED);
            status.setText("正在查找…请保持电脑上的中继运行");
            discovery.listen(found -> {
                if (destroyed) return;
                if (found.isEmpty()) {
                    status.setTextColor(WatchUi.WARN);
                    status.setText("没找到，请确认手表和电脑连的是同一个 WiFi，或手动填写地址");
                    return;
                }
                Discovery.Found first = found.get(0);
                hostField.setText(first.host);
                status.setTextColor(WatchUi.OK);
                status.setText("找到中继：" + first.host + "（如不对可手动修改）");
            });
        }), 0, 8);

        ui.add(page, ui.button("配对并连接", true, () -> {
            String host = hostField.getText().toString().trim();
            String code = codeField.getText().toString().trim();
            if (host.isEmpty()) {
                status.setTextColor(WatchUi.WARN);
                status.setText("请先填写电脑地址");
                return;
            }
            if (code.isEmpty()) {
                status.setTextColor(WatchUi.WARN);
                status.setText("请填写电脑上显示的 6 位配对码");
                return;
            }
            status.setTextColor(WatchUi.MUTED);
            status.setText("正在配对…");
            int port = pendingPort > 0 ? pendingPort : store.port();
            tryPair(host, port, code, status);
        }), 0, 8);

        ui.add(page, ui.text("配对码由电脑端中继在启动时显示，每次配对成功后会更换。", 11, WatchUi.MUTED), 0, 0);
        render(page);
        hostField.requestFocus();
    }

    private void tryPair(String host, int port, String code, TextView status) {
        JSONObject body = new JSONObject();
        try {
            body.put("code", code);
        } catch (Exception ignored) {
            // 仅数字内容，不会失败
        }
        api.post(host, port, "/api/pair", "", body, result -> {
            if (destroyed) return;
            if (!result.ok) {
                status.setTextColor(WatchUi.DANGER);
                status.setText(result.error);
                return;
            }
            String token = result.value.optString("token", "");
            if (token.isEmpty()) {
                status.setTextColor(WatchUi.DANGER);
                status.setText("配对响应缺少令牌");
                return;
            }
            store.setServer(host, port);
            store.setToken(token);
            status.setTextColor(WatchUi.OK);
            status.setText("配对成功，正在载入会话…");
            showSessionList();
        });
    }

    // ------------------------------------------------------------ 会话列表页

    private void showSessionList() {
        onSessionPage = false;
        // 从会话页返回时回到列表顶部；列表自身的定时刷新则由 render() 保持位置。
        if (returningFromSession) {
            resetScroll();
            returningFromSession = false;
        }
        refresh.removeCallbacks(listTicker);
        WatchUi.Page page = ui.page();
        ui.heading(page, "DSH 会话", loadingSession ? "正在刷新…" : "点开一个会话继续对话");

        if (pending.size() > 0) {
            LinearLayout notice = ui.card();
            TextView title = ui.text("有 " + pending.size() + " 个问题在等你回答", 13, WatchUi.WARN);
            notice.addView(title);
            TextView sub = ui.text("点下面的会话即可回答", 11, WatchUi.MUTED);
            sub.setPadding(0, ui.dp(5), 0, 0);
            notice.addView(sub);
            notice.setOnClickListener(v -> showQuestion(pending.get(0)));
            notice.setFocusable(true);
            notice.setClickable(true);
            ui.add(page, notice, 0, 10);
        }

        if (sessions.isEmpty()) {
            ui.add(page, ui.text(loadingSession ? "正在载入…" : "没有可显示的会话", 13, WatchUi.MUTED), 0, 10);
        }

        for (Api.Session session : sessions) {
            View row = ui.row(session.title, session.subtitle(), () -> showSession(session.id));
            if (session.waiting) {
                // 待回答的会话加一个颜色条，扫一眼就能定位。
                row.setBackground(ui.surface(WatchUi.SURFACE_HI, 16));
            }
            ui.add(page, row, 0, 7);
        }

        ui.add(page, ui.button("新建会话", false, this::createSession), 4, 7);
        ui.add(page, ui.button("断开连接", false, () -> {
            store.clearToken();
            sessions.clear();
            pending.clear();
            showConnect();
        }), 0, 0);
        render(page);
        loadSessions(false);
        refresh.removeCallbacks(listTicker);
        refresh.postDelayed(listTicker, LIST_REFRESH_MS);
    }

    private void loadSessions(boolean silent) {
        if (!store.paired()) return;
        loadingSession = true;
        api.get(store.host(), store.port(), "/api/sessions", store.token(), result -> {
            loadingSession = false;
            if (destroyed || onSessionPage) return;
            if (!result.ok) {
                sessions.clear();
                pending.clear();
                showListError(result.error);
                return;
            }
            sessions.clear();
            sessions.addAll(Api.parseSessions(result.value));
            pending.clear();
            pending.addAll(Api.parsePending(result.value));
            // 静默刷新时若内容没变就不重建，避免闪烁与打断滚动。
            if (!silent || changed()) showSessionList();
        });
    }

    private int lastSignature;

    private boolean changed() {
        int signature = sessions.size() * 31 + pending.size() * 7;
        for (Api.Session session : sessions) {
            signature = signature * 31 + session.id.hashCode() + (session.running ? 1 : 0) + (session.waiting ? 2 : 0);
        }
        if (signature == lastSignature) return false;
        lastSignature = signature;
        return true;
    }

    private void showListError(String error) {
        onSessionPage = false;
        WatchUi.Page page = ui.page();
        ui.heading(page, "连不上电脑", "");
        ui.add(page, ui.text(error, 13, WatchUi.DANGER), 0, 12);
        ui.add(page, ui.text("电脑地址：" + store.host() + ":" + store.port(), 11, WatchUi.MUTED), 0, 12);
        ui.add(page, ui.button("重试", true, this::showSessionList), 0, 8);
        ui.add(page, ui.button("重新配对", false, () -> {
            store.clearToken();
            showConnect();
        }), 0, 0);
        render(page);
    }

    private void createSession() {
        onSessionPage = false;
        WatchUi.Page page = ui.page();
        ui.heading(page, "新建会话", "");
        ui.add(page, ui.text("正在创建…", 13, WatchUi.MUTED), 0, 0);
        render(page);
        api.post(store.host(), store.port(), "/api/create", store.token(), new JSONObject(), result -> {
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
        store.setLastSession(sessionId);
        onSessionPage = true;
        editing = false;
        messages.clear();
        currentAsk = null;
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
        api.get(store.host(), store.port(), "/api/session?id=" + Api.urlEncode(sessionId), store.token(), result -> {
            if (destroyed || !onSessionPage || !sessionId.equals(currentSessionId)) return;
            // 请求在途时用户可能已经进入输入页；此时绝不能重建整屏。
            if (editing) return;
            if (!result.ok) {
                if (!silent) renderSessionError(result.error);
                return;
            }
            List<Api.Message> loaded = Api.parseMessages(result.value);
            currentRunning = result.value.optBoolean("running", false);
            currentAsk = Api.parseQuestion(result.value.optJSONObject("question"), sessionId, currentSessionTitle);
            String title = result.value.optString("title", "");
            if (!title.isEmpty()) currentSessionTitle = title;
            if (!silent || messageSignature(loaded) != messageSignature(messages)) {
                messages.clear();
                messages.addAll(loaded);
                renderSession("");
            } else if (currentAsk != null || !currentRunning) {
                // 只有运行状态变化时也刷新一下底部控件。
                renderSession("");
            }
        });
    }

    private int messageSignature(List<Api.Message> list) {
        int signature = list.size();
        for (Api.Message message : list) {
            signature = signature * 31 + message.text.hashCode() + message.tools.size();
        }
        return signature * 31 + (currentRunning ? 1 : 0) + (currentAsk == null ? 0 : 3);
    }

    private void renderSessionError(String error) {
        WatchUi.Page page = ui.page();
        ui.heading(page, "读取失败", currentSessionTitle);
        ui.add(page, ui.text(error, 13, WatchUi.DANGER), 0, 12);
        ui.add(page, ui.button("重试", true, () -> loadSession(currentSessionId, false)), 0, 8);
        render(page);
    }

    /** 重建会话页。hint 非空时在消息区顶部显示提示文字。 */
    private void renderSession(String hint) {
        WatchUi.Page page = ui.page();
        ui.heading(page, currentSessionTitle, currentRunning ? "正在运行" : (currentAsk == null ? "空闲" : "等你回答"));

        if (!hint.isEmpty()) {
            ui.add(page, ui.text(hint, 12, WatchUi.MUTED), 0, 8);
        }

        // 操作按钮放在消息上方：长对话里用户不必先滚到底部才能发消息。
        if (currentAsk != null) {
            LinearLayout ask = ui.card();
            TextView title = ui.text("AI 在等你回答", 13, WatchUi.WARN);
            ask.addView(title);
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

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button send = ui.button("发消息", true, this::showCompose);
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(0, -2, 1f);
        sendParams.setMargins(0, 0, ui.dp(6), 0);
        actions.addView(send, sendParams);
        View second = currentRunning
                ? ui.button("停止", false, this::cancelRun)
                : (currentAsk != null ? ui.button("回答", false, () -> showQuestion(currentAsk)) : ui.button("刷新", false, () -> {
                    renderSession("正在刷新…");
                    loadSession(currentSessionId, false);
                }));
        actions.addView(second, new LinearLayout.LayoutParams(0, -2, 1f));
        ui.add(page, actions, 0, 9);

        if (messages.isEmpty()) {
            ui.add(page, ui.text("还没有消息", 12, WatchUi.MUTED), 0, 8);
        }

        for (Api.Message message : messages) {
            if (message.text.trim().isEmpty() && message.tools.isEmpty()) continue;
            if (!message.text.trim().isEmpty()) {
                ui.add(page, ui.bubble(cut(message.text, 1200), "user".equals(message.role)), 0, 5);
            }
            if (!message.tools.isEmpty()) {
                ui.add(page, ui.text("用了：" + join(message.tools), 10, WatchUi.MUTED), 0, 7);
            }
        }

        if (currentRunning) {
            ui.add(page, ui.text("AI 正在回复…", 11, WatchUi.MUTED), 0, 8);
        }

        ui.add(page, ui.button("返回会话列表", false, this::backToList), 6, 0);
        render(page);
    }

    private void backToList() {
        onSessionPage = false;
        editing = false;
        returningFromSession = true;
        refresh.removeCallbacks(sessionTicker);
        store.setLastSession("");
        showSessionList();
    }

    private void cancelRun() {
        JSONObject body = new JSONObject();
        try {
            body.put("sessionId", currentSessionId);
        } catch (Exception ignored) {
            // 不会失败
        }
        api.post(store.host(), store.port(), "/api/cancel", store.token(), body, result -> {
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
        api.post(store.host(), store.port(), "/api/send", store.token(), body, result -> {
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

        api.post(store.host(), store.port(), "/api/answer", store.token(), body, result -> {
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

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) builder.append("、");
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    /** 手表上长文本会拖垮滚动体验，截断并省略。 */
    private static String cut(String text, int limit) {
        String value = text == null ? "" : text.trim();
        if (value.length() <= limit) return value;
        return value.substring(0, limit) + "\n…（已省略）";
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
