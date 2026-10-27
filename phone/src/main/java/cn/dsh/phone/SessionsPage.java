package cn.dsh.phone;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话列表：按工作目录（项目）折叠，点项目展开它的会话。
 *
 * 三个界面约束（都来自手表端的教训）：
 * - **渲染与请求分开**：show() 只在进入页面时拉一次数据，定时刷新只走 render()，
 *   否则「渲染 → 发请求 → 回调再渲染」会形成环，列表持续抽动。
 * - **刷新前比内容指纹**：没变化就不重建界面，滚动位置才能稳住。
 * - **展开状态记在内存里**，重建整屏时按它还原。
 */
final class SessionsPage {
    private static final long REFRESH_MS = 10_000;

    private final MainActivity host;
    private ScrollView scroll;
    private LinearLayout root;
    private LinearLayout body;

    private List<Api.Session> sessions = new ArrayList<>();
    private List<Api.Ask> pending = new ArrayList<>();
    private List<Api.Approval> approvals = new ArrayList<>();
    private final Map<String, Boolean> openProjects = new LinkedHashMap<>();
    private String fingerprint = "";
    private String filter = "";
    private boolean loading;
    private boolean visible;
    /** 复用同一个界面实例，切标签时不重建（见 build）。 */
    private View cachedView;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            load(true);
        }
    };

    SessionsPage(MainActivity host) {
        this.host = host;
    }

    View build() {
        // 切回同一个标签时复用已有界面：重建会让列表先白屏、再等一次网络请求，
        // 而这期间用户已经看到了空屏。复用后只做一次静默刷新。
        if (cachedView != null) {
            visible = true;
            load(true);
            return cachedView;
        }
        scroll = Ui.scroll(host);
        scroll.setBackgroundColor(Ui.BG);
        root = Ui.column(host);
        root.setPadding(Ui.dp(16f), Ui.dp(16f), Ui.dp(16f), Ui.dp(24f));

        LinearLayout header = Ui.row(host);
        TextView title = Ui.title(host, "会话", 26f);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView refresh = Ui.ghostButton(host, "刷新");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                load(false);
            }
        });
        header.addView(refresh);
        root.addView(header);

        // 搜索框：按标题过滤当前列表（不打扰服务端）。
        EditText search = Ui.input(host, "搜索会话标题");
        search.setSingleLine(true);
        search.setPadding(Ui.dp(12f), Ui.dp(10f), Ui.dp(12f), Ui.dp(10f));
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                filter = s.toString().trim();
                render();
            }
        });
        LinearLayout.LayoutParams searchParams = Ui.matchParams();
        searchParams.topMargin = Ui.dp(12f);
        root.addView(search, searchParams);

        body = Ui.column(host);
        LinearLayout.LayoutParams bodyParams = Ui.matchParams();
        bodyParams.topMargin = Ui.dp(12f);
        root.addView(body, bodyParams);

        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        visible = true;
        load(false);
        cachedView = scroll;
        return scroll;
    }

    void stop() {
        visible = false;
        host.removeCallbacks(ticker);
    }

    /** 拉取数据。silent 为 true 时不显示加载态（定时刷新用）。 */
    private void load(final boolean silent) {
        if (loading) return;
        loading = true;
        if (!silent) showLoading();
        Api.sessions(host.store, new Api.Callback() {
            @Override public void onResult(JSONObject value, String error) {
                loading = false;
                if (!visible) return;
                if (error != null) {
                    if (!silent) showError(error);
                    return;
                }
                sessions = Api.parseSessions(value);
                pending = Api.parsePending(value);
                approvals = Api.parseApprovals(value);
                // 首次进入时把有运行中会话的项目默认展开，其余折叠。
                if (fingerprint.length() == 0) {
                    for (Api.Session session : sessions) {
                        if (session.running && session.projectPath.length() > 0) {
                            openProjects.put(session.projectPath, Boolean.TRUE);
                        }
                    }
                }
                render();
                host.removeCallbacks(ticker);
                host.postDelayed(ticker, REFRESH_MS);
            }
        });
        // 审批不在 sessions 响应里，单独拉一次（它是 waterfall 状态，随时会变）。
        Api.approvals(host.store, new Api.Callback() {
            @Override public void onResult(JSONObject value, String error) {
                if (!visible) return;
                if (error == null) {
                    approvals = Api.parseApprovals(value);
                    render();
                }
            }
        });
    }

    private void showLoading() {
        body.removeAllViews();
        body.addView(Ui.muted(host, "正在读取会话…", 13f));
    }

    private void showError(String error) {
        body.removeAllViews();
        LinearLayout box = Ui.card(host);
        box.addView(Ui.title(host, "连不上中继", 16f));
        TextView detail = Ui.muted(host, error, 13f);
        detail.setPadding(0, Ui.dp(6f), 0, 0);
        box.addView(detail);
        TextView retry = Ui.button(host, "重试", false);
        LinearLayout.LayoutParams params = Ui.matchParams();
        params.topMargin = Ui.dp(12f);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                load(false);
            }
        });
        box.addView(retry, params);
        body.addView(box);
    }

    /** 只重绘，不发请求。 */
    private void render() {
        if (body == null) return;
        String next = computeFingerprint();
        if (next.equals(fingerprint)) return;
        fingerprint = next;

        int scrollY = scroll == null ? 0 : scroll.getScrollY();
        body.removeAllViews();

        // 待处理提示：审批优先（它会让电脑端卡住），其次是提问。
        if (!approvals.isEmpty()) body.addView(approvalBanner());
        if (!pending.isEmpty()) body.addView(questionBanner());

        List<Api.Session> visibleSessions = new ArrayList<>();
        for (Api.Session session : sessions) {
            if (session.subagent) continue;
            if (filter.length() > 0 && !session.title.toLowerCase().contains(filter.toLowerCase())) continue;
            visibleSessions.add(session);
        }
        if (visibleSessions.isEmpty()) {
            body.addView(Ui.empty(host, filter.length() > 0 ? "没有匹配的会话" : "还没有会话，去电脑上开一个吧"));
            return;
        }

        // 按项目分组，保持「最近更新的项目在最前」。
        Map<String, List<Api.Session>> groups = new LinkedHashMap<>();
        for (Api.Session session : visibleSessions) {
            String key = session.projectPath.length() == 0 ? "" : session.projectPath;
            List<Api.Session> list = groups.get(key);
            if (list == null) {
                list = new ArrayList<>();
                groups.put(key, list);
            }
            list.add(session);
        }
        for (Map.Entry<String, List<Api.Session>> entry : groups.entrySet()) {
            body.addView(projectBlock(entry.getKey(), entry.getValue()));
            body.addView(Ui.gap(host, 8f));
        }
        if (scroll != null) scroll.post(new Runnable() {
            @Override public void run() {
                scroll.scrollTo(0, scrollY);
            }
        });
    }

    /** 项目卡片：标题行可折叠，展开后列出它的会话。 */
    private View projectBlock(final String path, List<Api.Session> list) {
        LinearLayout card = Ui.card(host);
        boolean open = Boolean.TRUE.equals(openProjects.get(path));
        int running = 0;
        for (Api.Session session : list) if (session.running) running += 1;

        LinearLayout head = Ui.row(host);
        head.setClickable(true);
        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                openProjects.put(path, Boolean.TRUE.equals(openProjects.get(path)) ? Boolean.FALSE : Boolean.TRUE);
                fingerprint = "";
                render();
            }
        });
        TextView arrow = Ui.text(host, open ? "▾" : "▸", 13f, Ui.MUTED);
        arrow.setPadding(0, 0, Ui.dp(8f), 0);
        head.addView(arrow);
        LinearLayout names = Ui.column(host);
        String name = list.isEmpty() ? "未分组" : (list.get(0).project.length() == 0 ? "未分组" : list.get(0).project);
        names.addView(Ui.title(host, name, 16f));
        String summary = list.size() + " 个会话" + (running > 0 ? " · " + running + " 个在跑" : "");
        TextView sub = Ui.muted(host, summary, 11f);
        sub.setPadding(0, Ui.dp(2f), 0, 0);
        names.addView(sub);
        head.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(head);

        if (!open) return card;

        for (Api.Session session : list) {
            card.addView(Ui.divider(host));
            card.addView(sessionRow(session));
        }
        return card;
    }

    private View sessionRow(final Api.Session session) {
        LinearLayout row = Ui.row(host);
        row.setPadding(0, Ui.dp(10f), 0, Ui.dp(10f));
        row.setClickable(true);
        row.setBackground(Ui.pressable(Ui.SURFACE, 10f, 0));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                host.openSession(session.id, session.title);
            }
        });

        LinearLayout text = Ui.column(host);
        LinearLayout titleRow = Ui.row(host);
        if (session.running) titleRow.addView(Ui.dot(host, Ui.OK));
        else if (session.waiting) titleRow.addView(Ui.dot(host, Ui.WARN));
        TextView title = Ui.ellipsis(host, session.title, 14.5f, Ui.FG);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        text.addView(titleRow);

        StringBuilder meta = new StringBuilder();
        meta.append(Api.relativeTime(session.updatedAt));
        if (session.running) meta.append(" · 正在回答");
        else if (session.waiting) meta.append(" · 等待你回答");
        if (session.subagentCount > 0) {
            meta.append(" · ").append(session.subagentCount).append(" 个子任务");
            if (session.subagentRunning > 0) meta.append("（").append(session.subagentRunning).append(" 在跑）");
        }
        if (session.model.length() > 0) meta.append(" · ").append(session.model);
        TextView sub = Ui.ellipsis(host, meta.toString(), 11.5f, Ui.MUTED);
        sub.setPadding(0, Ui.dp(3f), 0, 0);
        text.addView(sub);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = Ui.text(host, "›", 18f, Ui.MUTED);
        arrow.setPadding(Ui.dp(8f), 0, 0, 0);
        row.addView(arrow);
        return row;
    }

    private View approvalBanner() {
        LinearLayout box = Ui.card(host);
        LinearLayout head = Ui.row(host);
        head.addView(Ui.dot(host, Ui.WARN));
        head.addView(Ui.title(host, "有工具等待批准（" + approvals.size() + "）", 14f));
        box.addView(head);
        TextView hint = Ui.muted(host, "电脑上的 DSH 会一直停在这里，需要你点允许或拒绝。", 12f);
        hint.setPadding(0, Ui.dp(6f), 0, Ui.dp(10f));
        box.addView(hint);
        for (final Api.Approval approval : approvals) {
            LinearLayout row = Ui.row(host);
            row.setPadding(0, Ui.dp(6f), 0, Ui.dp(6f));
            TextView tool = Ui.text(host, approval.toolName.length() == 0 ? "工具" : approval.toolName, 13f, Ui.FG);
            row.addView(tool, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView allow = Ui.tag(host, "允许一次", Ui.ON_ACCENT, Ui.ACCENT);
            allow.setClickable(true);
            allow.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    decide(approval.eventId, true);
                }
            });
            TextView reject = Ui.tag(host, "拒绝", Ui.DANGER, Ui.SURFACE_HI);
            reject.setClickable(true);
            Ui.setMargins(reject, 6, 0, 0, 0);
            reject.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    decide(approval.eventId, false);
                }
            });
            row.addView(allow);
            row.addView(reject);
            box.addView(row);
        }
        LinearLayout.LayoutParams params = Ui.matchParams();
        params.bottomMargin = Ui.dp(10f);
        box.setLayoutParams(params);
        return box;
    }

    private void decide(final String eventId, final boolean allow) {
        Api.decide(host.store, eventId, allow, new Api.Callback() {
            @Override public void onResult(JSONObject value, String error) {
                if (error != null) {
                    host.toast(error);
                    return;
                }
                host.toast(allow ? "已允许" : "已拒绝");
                fingerprint = "";
                load(true);
            }
        });
    }

    private View questionBanner() {
        LinearLayout box = Ui.card(host);
        LinearLayout head = Ui.row(host);
        head.addView(Ui.dot(host, Ui.ACCENT));
        head.addView(Ui.title(host, "有 " + pending.size() + " 个问题等你回答", 14f));
        box.addView(head);
        TextView hint = Ui.muted(host, "点进去作答，电脑上的会话才能继续。", 12f);
        hint.setPadding(0, Ui.dp(6f), 0, Ui.dp(10f));
        box.addView(hint);
        for (final Api.Ask ask : pending) {
            LinearLayout row = Ui.row(host);
            row.setPadding(0, Ui.dp(6f), 0, Ui.dp(6f));
            row.setClickable(true);
            TextView title = Ui.ellipsis(host, ask.sessionTitle.length() == 0 ? "未命名会话" : ask.sessionTitle, 13f, Ui.FG);
            row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(Ui.tag(host, "去回答", Ui.ON_ACCENT, Ui.ACCENT));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    host.openSession(ask.sessionId, ask.sessionTitle);
                }
            });
            box.addView(row);
        }
        LinearLayout.LayoutParams params = Ui.matchParams();
        params.bottomMargin = Ui.dp(10f);
        box.setLayoutParams(params);
        return box;
    }

    /** 内容指纹：只有它变了才重建界面。 */
    private String computeFingerprint() {
        StringBuilder builder = new StringBuilder();
        builder.append(filter).append('|');
        for (Api.Approval approval : approvals) builder.append('A').append(approval.eventId);
        for (Api.Ask ask : pending) builder.append('Q').append(ask.callId);
        for (Api.Session session : sessions) {
            if (session.subagent) continue;
            builder.append(session.id).append(session.title).append(session.running)
                    .append(session.waiting).append(session.updatedAt).append(session.subagentCount)
                    .append(session.subagentRunning).append(';');
        }
        for (Map.Entry<String, Boolean> entry : openProjects.entrySet()) {
            builder.append(entry.getKey()).append(entry.getValue());
        }
        return builder.toString();
    }
}
