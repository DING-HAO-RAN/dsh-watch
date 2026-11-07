package cn.dsh.phone;

import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 终端页：在手机上直接操作电脑的 shell。
 *
 * 数据通路是「HTTP 轮询」而不是 WebSocket：中继把 DSH 的终端流缓存在内存里，
 * 手机每 300ms 拉一次增量。这对敲命令已经够用（本地局域网往返只有几毫秒），
 * 也避免了在中继里再实现一套 WS 服务端。
 *
 * 屏幕内容按帧累积：`reset` 帧代表整屏重绘（必须清空），`output` 帧追加。
 * ANSI 转义序列在这里被剥掉——手机端不做完整终端模拟，保留颜色控制符只会显示成乱码。
 */
final class TerminalPage {
    private static final long POLL_MS = 300;

    private final MainActivity host;
    private LinearLayout container;

    private String sessionId = "";
    private String sessionTitle = "";
    private String terminalId = "";
    private long since;
    private final StringBuilder screen = new StringBuilder();
    private boolean visible;
    private boolean polling;
    private boolean ended;

    private TextView screenView;
    private ScrollView screenScroll;
    private EditText input;
    private LinearLayout listBox;

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            if (!visible || terminalId.length() == 0) return;
            poll();
        }
    };

    TerminalPage(MainActivity host) {
        this.host = host;
    }

    View build() {
        container = Ui.column(host);
        container.setBackgroundColor(Ui.BG);
        container.setPadding(Ui.dp(12f), Ui.dp(12f), Ui.dp(12f), Ui.dp(8f));

        LinearLayout header = Ui.row(host);
        header.addView(Ui.title(host, "终端", 26f), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pick = Ui.ghostButton(host, "选会话");
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                pickSession();
            }
        });
        header.addView(pick);
        container.addView(header);

        visible = true;
        sessionId = host.activeSessionId;
        sessionTitle = host.activeSessionTitle;
        if (sessionId.length() == 0) {
            sessionId = host.store.lastSessionId();
            sessionTitle = host.store.lastSessionTitle();
        }
        if (sessionId.length() == 0) renderPrompt();
        else showList();
        return container;
    }

    void stop() {
        visible = false;
        host.removeCallbacks(poller);
    }

    private void renderPrompt() {
        container.removeAllViews();
        container.addView(Ui.title(host, "终端", 26f));
        container.addView(Ui.empty(host, "先选一个会话，再打开它的终端"));
        TextView pick = Ui.button(host, "选择会话", false);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                pickSession();
            }
        });
        container.addView(pick);
    }

    private void pickSession() {
        Api.sessions(host.store, new Api.Callback() {
            @Override public void onResult(JSONObject value, String error) {
                if (error != null) {
                    host.toast(error);
                    return;
                }
                final List<Api.Session> sessions = new ArrayList<>();
                for (Api.Session session : Api.parseSessions(value)) {
                    if (!session.subagent && session.projectPath.length() > 0) sessions.add(session);
                }
                if (sessions.isEmpty()) {
                    host.toast("没有可用的会话");
                    return;
                }
                String[] labels = new String[sessions.size()];
                for (int i = 0; i < sessions.size(); i += 1) {
                    labels[i] = sessions.get(i).title + "\n" + sessions.get(i).project;
                }
                new android.app.AlertDialog.Builder(host)
                        .setTitle("选择会话")
                        .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface dialog, int which) {
                                Api.Session session = sessions.get(which);
                                sessionId = session.id;
                                sessionTitle = session.title;
                                terminalId = "";
                                showList();
                            }
                        })
                        .show();
            }
        });
    }

    /** 终端列表：已有的 + 新建按钮。 */
    private void showList() {
        host.removeCallbacks(poller);
        container.removeAllViews();

        LinearLayout header = Ui.row(host);
        header.addView(Ui.title(host, "终端", 26f), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pick = Ui.ghostButton(host, "换会话");
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                pickSession();
            }
        });
        header.addView(pick);
        container.addView(header);

        TextView context = Ui.ellipsis(host, (sessionTitle.length() == 0 ? "当前会话" : sessionTitle), 12f, Ui.MUTED);
        context.setPadding(0, Ui.dp(4f), 0, Ui.dp(10f));
        container.addView(context);

        TextView create = Ui.button(host, "新建终端", true);
        create.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                createTerminal();
            }
        });
        container.addView(create);

        listBox = Ui.column(host);
        LinearLayout.LayoutParams listParams = Ui.matchParams();
        listParams.topMargin = Ui.dp(12f);
        container.addView(listBox, listParams);
        listBox.addView(Ui.muted(host, "正在读取终端列表…", 12f));

        Api.terminals(host.store, sessionId, new Api.Callback() {
            @Override public void onResult(JSONObject value, String error) {
                if (!visible || listBox == null) return;
                listBox.removeAllViews();
                if (error != null) {
                    listBox.addView(Ui.muted(host, error, 12f));
                    return;
                }
                List<Api.TerminalInfo> items = Api.parseTerminals(value);
                if (items.isEmpty()) {
                    listBox.addView(Ui.muted(host, "这个会话还没有终端，点上面「新建终端」", 12f));
                    return;
                }
                for (final Api.TerminalInfo info : items) {
                    LinearLayout row = Ui.listRow(host);
                    LinearLayout text = Ui.column(host);
                    text.addView(Ui.ellipsis(host, info.title.length() == 0 ? info.id : info.title, 14f, Ui.FG));
                    String meta = ("exited".equals(info.state) ? "已退出" : "运行中") + " · " + info.cols + "×" + info.rows;
                    TextView sub = Ui.muted(host, meta, 11f);
                    sub.setPadding(0, Ui.dp(2f), 0, 0);
                    text.addView(sub);
                    row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    row.addView(Ui.text(host, "›", 18f, Ui.MUTED));
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View view) {
                            openTerminal(info.id);
                        }
                    });
                    LinearLayout.LayoutParams params = Ui.matchParams();
                    params.bottomMargin = Ui.dp(6f);
                    listBox.addView(row, params);
                }
            }
        });
    }

    private void createTerminal() {
        JSONObject body = new JSONObject();
        try {
            body.put("action", "create");
            body.put("id", sessionId);
            // 手机竖屏的可视列数大约 40，行数留 24 行给滚动历史。
            body.put("cols", 60);
            body.put("rows", 24);
        } catch (Exception ignored) { }
        Api.terminal(host.store, body, new Api.Callback() {
            @Override public void onResult(JSONObject value, String error) {
                if (error != null) {
                    host.toast("创建失败：" + error);
                    return;
                }
                JSONObject terminal = value.optJSONObject("terminal");
                String id = terminal == null ? "" : Api.text(terminal, "id");
                if (id.length() == 0) {
                    host.toast("创建失败：没有返回终端 id");
                    return;
                }
                openTerminal(id);
            }
        });
    }

    /** 进入某个终端：清屏、从头拉输出、开始轮询。 */
    private void openTerminal(String id) {
        terminalId = id;
        since = 0;
        ended = false;
        // 创建时给的 60x24 只是初值；按屏幕实际能显示的字符数再校正一次，
        // 否则命令输出会折行错位（表格类输出尤其明显）。
        syncSize();
        screen.setLength(0);
        buildTerminalView();
        poll();
    }

    private void buildTerminalView() {
        container.removeAllViews();

        LinearLayout bar = Ui.row(host);
        TextView back = Ui.text(host, "‹ 终端", 14f, Ui.ACCENT);
        back.setPadding(0, Ui.dp(6f), Ui.dp(10f), Ui.dp(6f));
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                stop();
                visible = true;
                showList();
            }
        });
        bar.addView(back);
        bar.addView(Ui.ellipsis(host, sessionTitle, 12f, Ui.MUTED),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = Ui.ghostButton(host, "关闭");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                closeTerminal();
            }
        });
        bar.addView(close);
        container.addView(bar);

        screenScroll = Ui.scroll(host);
        screenScroll.setBackground(Ui.round(Ui.BG, 10f, Ui.STROKE));
        screenView = Ui.text(host, "", 11.5f, Ui.FG);
        screenView.setTypeface(android.graphics.Typeface.MONOSPACE);
        screenView.setTextIsSelectable(true);
        screenView.setHorizontallyScrolling(true);
        screenView.setPadding(Ui.dp(10f), Ui.dp(8f), Ui.dp(10f), Ui.dp(8f));
        screenScroll.addView(screenView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        container.addView(screenScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 快捷键：手机上最常用的几个控制键，省得切输入法。
        LinearLayout keys = Ui.row(host);
        keys.setPadding(0, Ui.dp(6f), 0, Ui.dp(6f));
        String[] quick = {"Ctrl+C", "Tab", "↑", "↓", "清屏"};
        for (final String key : quick) {
            TextView button = Ui.tag(host, key, Ui.FG, Ui.SURFACE_HI);
            button.setPadding(Ui.dp(10f), Ui.dp(7f), Ui.dp(10f), Ui.dp(7f));
            button.setClickable(true);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    quickKey(key);
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = Ui.dp(6f);
            keys.addView(button, params);
        }
        container.addView(keys);

        LinearLayout inputRow = Ui.row(host);
        input = Ui.input(host, "输入命令…");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView view, int actionId, android.view.KeyEvent event) {
                sendLine();
                return true;
            }
        });
        inputRow.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView send = Ui.text(host, "执行", 14f, Ui.ON_ACCENT);
        send.setGravity(Gravity.CENTER);
        send.setPadding(Ui.dp(14f), Ui.dp(11f), Ui.dp(14f), Ui.dp(11f));
        send.setBackground(Ui.pressable(Ui.ACCENT, 10f, 0));
        send.setClickable(true);
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                sendLine();
            }
        });
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sendParams.leftMargin = Ui.dp(6f);
        inputRow.addView(send, sendParams);
        container.addView(inputRow);
    }

    private void sendLine() {
        String text = input.getText().toString();
        input.setText("");
        if (text.length() == 0) return;
        write(text + "\r");
    }

    private void quickKey(String key) {
        if ("Ctrl+C".equals(key)) write("\u0003");
        else if ("Tab".equals(key)) write("\t");
        else if ("↑".equals(key)) write("\u001b[A");
        else if ("↓".equals(key)) write("\u001b[B");
        else if ("清屏".equals(key)) {
            screen.setLength(0);
            updateScreen();
        }
    }

    /** 按屏幕实际尺寸校正 PTY 的列数行数。 */
    private void syncSize() {
        if (terminalId.length() == 0) return;
        int width = host.getResources().getDisplayMetrics().widthPixels;
        // 终端用等宽字体，单字符宽度约为 8dp；留出左右各 8dp 的边距。
        float density = host.getResources().getDisplayMetrics().density;
        int usable = Math.max(1, width - (int) (16 * density));
        int cols = Math.max(20, Math.min(200, (int) (usable / (8 * density))));
        int rows = 24;
        JSONObject body = new JSONObject();
        try {
            body.put("action", "resize");
            body.put("terminalId", terminalId);
            body.put("cols", cols);
            body.put("rows", rows);
        } catch (Exception ignored) { }
        Api.terminal(host.store, body, new Api.Callback() {
            @Override public void onResult(JSONObject value, String error) {
                // 尺寸同步失败不影响使用，PTY 会沿用旧尺寸。
            }
        });
    }

    private void write(String data) {
        if (terminalId.length() == 0) return;
        JSONObject body = new JSONObject();
        try {
            body.put("action", "input");
            body.put("terminalId", terminalId);
            body.put("data", data);
        } catch (Exception ignored) { }
        Api.terminal(host.store, body, new Api.Callback() {
            @Override public void onResult(JSONObject value, String error) {
                if (error != null) host.toast(error);
                // 写完立刻拉一次，命令输出能更快出现。
                poll();
            }
        });
    }

    private void poll() {
        if (polling || terminalId.length() == 0 || !visible) return;
        polling = true;
        Api.terminalOutput(host.store, terminalId, since, new Api.Callback() {
            @Override public void onResult(JSONObject value, String error) {
                polling = false;
                if (!visible || terminalId.length() == 0) return;
                if (error != null) {
                    // 终端不存在（比如中继重启过）：退回列表，不要一直空转。
                    host.removeCallbacks(poller);
                    return;
                }
                JSONArray frames = value.optJSONArray("frames");
                if (frames != null) {
                    for (int i = 0; i < frames.length(); i += 1) {
                        JSONObject frame = frames.optJSONObject(i);
                        if (frame == null) continue;
                        String kind = Api.text(frame, "kind");
                        String text = Api.text(frame, "text");
                        if ("reset".equals(kind)) screen.setLength(0);
                        screen.append(text);
                    }
                }
                since = value.optLong("nextSeq", since);
                ended = value.optBoolean("ended", false);
                // 缓冲无限增长会把内存吃满：只保留最后 6 万字。
                if (screen.length() > 60_000) screen.delete(0, screen.length() - 60_000);
                updateScreen();
                host.removeCallbacks(poller);
                host.postDelayed(poller, POLL_MS);
            }
        });
    }

    private void updateScreen() {
        if (screenView == null) return;
        screenView.setText(clean(screen.toString()));
        if (screenScroll != null) {
            screenScroll.post(new Runnable() {
                @Override public void run() {
                    screenScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    /**
     * 剥掉 ANSI 转义序列。
     *
     * cmd.exe 会往输出里塞 OSC 标题序列（`ESC ] 0 ; 标题 BEL`）与光标控制，
     * 原样显示就是满屏乱码。这里只做「去掉控制符 + 保留换行」，
     * 不做光标定位——手机上不需要精确复刻终端画面。
     */
    private static String clean(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == 27) {
                // CSI: ESC [ ... 终止字节
                if (i + 1 < raw.length() && raw.charAt(i + 1) == '[') {
                    int j = i + 2;
                    while (j < raw.length() && !(raw.charAt(j) >= '@' && raw.charAt(j) <= '~')) j += 1;
                    i = j + 1;
                    continue;
                }
                // OSC: ESC ] ... BEL 或 ESC \
                if (i + 1 < raw.length() && raw.charAt(i + 1) == ']') {
                    int j = i + 2;
                    while (j < raw.length() && raw.charAt(j) != '\u0007' && raw.charAt(j) != 27) j += 1;
                    i = j + 1;
                    continue;
                }
                i += 2;
                continue;
            }
            // 回车覆盖：把行首内容重写。简化处理为忽略 \r，保留 \n。
            if (c == '\r') {
                i += 1;
                continue;
            }
            if (c == '\u0007') {
                i += 1;
                continue;
            }
            out.append(c);
            i += 1;
        }
        return out.toString();
    }

    private void closeTerminal() {
        JSONObject body = new JSONObject();
        try {
            body.put("action", "close");
            body.put("terminalId", terminalId);
        } catch (Exception ignored) { }
        host.removeCallbacks(poller);
        Api.terminal(host.store, body, new Api.Callback() {
            @Override public void onResult(JSONObject value, String error) {
                terminalId = "";
                host.toast(error == null ? "终端已关闭" : error);
                showList();
            }
        });
    }
}
