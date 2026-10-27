package cn.dsh.phone;

import android.graphics.Typeface;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话页：对话正文 + 工具调用 + 答题 + 审批 + 发消息。
 *
 * 刷新与展开状态的两个硬约束（与手表端同源）：
 * - **重建整屏刷新时，展开状态必须存在字段里**（openThinking / openTools），否则每 4 秒
 *   一次刷新会把用户刚点开的思考或工具结果收回去，表现为「点了没反应」。
 * - **用户正在输入时暂停自动刷新**（editing），否则输入框里的内容会被整屏重建吃掉。
 *
 * 渲染顺序按 steps 原样走：用户消息、助手正文、工具卡片。工具卡片按「正文分界」分组，
 * 而不是按相邻关系——中继为每次工具调用插了一条只承载工具清单的助手步骤，
 * 相邻工具步骤几乎从不相邻，按相邻分组会让每次调用各自成组。
 */
final class ChatPage {
    private static final long REFRESH_MS = 4_000;

    private final MainActivity host;
    private final String sessionId;
    private String title;

    private ScrollView scroll;
    private LinearLayout body;
    private EditText composer;
    private TextView sendButton;
    private TextView statusView;
    private LinearLayout overlay;

    private List<Api.Step> steps = new ArrayList<>();
    private Api.Ask question;
    private Api.Approval approval;
    private boolean running;
    private boolean visible;
    private boolean editing;
    private boolean loading;
    private String fingerprint = "";
    private long lastSeq;
    private final Set<String> openThinking = new HashSet<>();
    private final Set<String> openTools = new HashSet<>();
    /** 用户点了「稍后」的提问：不再自动弹层，避免每次刷新都打断。 */
    private String dismissedCallId = "";
    private int lastScrollY;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            if (editing) {
                host.postDelayed(this, REFRESH_MS);
                return;
            }
            load(true);
        }
    };

    ChatPage(MainActivity host, String sessionId, String title) {
        this.host = host;
        this.sessionId = sessionId;
        this.title = title == null ? "" : title;
    }

    View build() {
        LinearLayout root = Ui.column(host);
        root.setBackgroundColor(Ui.BG);

        // 顶栏
        LinearLayout bar = Ui.row(host);
        bar.setPadding(Ui.dp(10f), Ui.dp(10f), Ui.dp(12f), Ui.dp(8f));
        TextView back = Ui.text(host, "‹", 26f, Ui.ACCENT);
        back.setPadding(Ui.dp(6f), 0, Ui.dp(10f), 0);
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                host.backToSessions();
            }
        });
        bar.addView(back);

        LinearLayout titles = Ui.column(host);
        TextView name = Ui.ellipsis(host, title.length() == 0 ? "会话" : title, 15.5f, Ui.FG);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setClickable(true);
        name.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                renameDialog();
            }
        });
        titles.addView(name);
        statusView = Ui.ellipsis(host, "正在读取…", 11f, Ui.MUTED);
        statusView.setPadding(0, Ui.dp(2f), 0, 0);
        titles.addView(statusView);
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView more = Ui.text(host, "⋯", 22f, Ui.MUTED);
        more.setPadding(Ui.dp(10f), 0, Ui.dp(4f), 0);
        more.setClickable(true);
        more.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                showMenu();
            }
        });
        bar.addView(more);
        root.addView(bar);
        root.addView(Ui.divider(host));

        // 内容区
        scroll = Ui.scroll(host);
        body = Ui.column(host);
        body.setPadding(Ui.dp(14f), Ui.dp(12f), Ui.dp(14f), Ui.dp(16f));
        scroll.addView(body, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollParams);

        // 底部输入区
        LinearLayout bottom = Ui.row(host);
        bottom.setPadding(Ui.dp(12f), Ui.dp(8f), Ui.dp(12f), Ui.dp(12f));
        bottom.setBackgroundColor(Ui.SURFACE);
        composer = Ui.input(host, "发消息…");
        composer.setMaxLines(5);
        composer.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean hasFocus) {
                editing = hasFocus || composer.getText().length() > 0;
            }
        });
        composer.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                editing = s.length() > 0;
            }
        });
        bottom.addView(composer, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sendButton = Ui.text(host, "发送", 14f, Ui.ON_ACCENT);
        sendButton.setGravity(Gravity.CENTER);
        sendButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        sendButton.setPadding(Ui.dp(16f), Ui.dp(12f), Ui.dp(16f), Ui.dp(12f));
        sendButton.setBackground(Ui.pressable(Ui.ACCENT, 12f, 0));
        sendButton.setClickable(true);
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                send();
            }
        });
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sendParams.leftMargin = Ui.dp(8f);
        bottom.addView(sendButton, sendParams);
        root.addView(bottom);

        overlay = Ui.column(host);
        overlay.setVisibility(View.GONE);
        root.addView(overlay, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        visible = true;
        load(false);
        return root;
    }

    void stop() {
        visible = false;
        host.removeCallbacks(ticker);
    }

    private void load(final boolean silent) {
        if (loading) return;
        loading = true;
        Api.session(host.store, sessionId, 0, new Api.Callback() {
            @Override public void onResult(JSONObject value, String error) {
                loading = false;
                if (!visible) return;
                if (error != null) {
                    if (!silent) showError(error);
                    host.postDelayed(ticker, REFRESH_MS * 2);
                    return;
                }
                title = Api.text(value, "title");
                if (title.length() == 0) title = host.activeSessionTitle;
                running = value.optBoolean("running", false);
                steps = Api.parseSteps(value);
                lastSeq = value.optLong("asOfSeq", 0);
                String previousCallId = question == null ? "" : question.callId;
                question = null;
                JSONObject questionObject = value.optJSONObject("question");
                if (questionObject != null) {
                    question = Api.parseQuestion(questionObject, sessionId, title);
                }
                render();
                refreshApproval();
                // 只有「新出现」的提问才弹层：已经弹过、用户点了稍后的不重复打扰。
                if (question != null && question.callId.length() > 0
                        && !question.callId.equals(previousCallId)
                        && !question.callId.equals(dismissedCallId)) {
                    showQuestionSheet();
                }
                host.removeCallbacks(ticker);
                host.postDelayed(ticker, REFRESH_MS);
            }
        });
    }

    private void showError(String error) {
        body.removeAllViews();
        LinearLayout box = Ui.card(host);
        box.addView(Ui.title(host, "读取会话失败", 15f));
        TextView detail = Ui.muted(host, error, 12.5f);
        detail.setPadding(0, Ui.dp(6f), 0, Ui.dp(10f));
        box.addView(detail);
        TextView retry = Ui.button(host, "重试", false);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                load(false);
            }
        });
        box.addView(retry);
        body.addView(box);
    }

    /** 只重绘，不发请求。 */
    private void render() {
        String next = computeFingerprint();
        if (next.equals(fingerprint)) {
            updateStatus();
            return;
        }
        fingerprint = next;
        lastScrollY = scroll.getScrollY();
        body.removeAllViews();

        if (steps.isEmpty()) {
            body.addView(Ui.empty(host, "这个会话还没有内容"));
            updateStatus();
            return;
        }

        // 只渲染最近的一段：一个会话常有上百步、四万多字，全量建 View 在手机上
        // 要十几秒才出画面（实测 106 步）。用户真正要看的基本总是最后几步。
        int index = 0;
        final int limit = 60;
        if (steps.size() > limit) {
            index = steps.size() - limit;
            TextView earlier = Ui.muted(host, "更早的 " + index + " 步请在电脑上查看", 11.5f);
            earlier.setGravity(Gravity.CENTER);
            earlier.setPadding(0, Ui.dp(10f), 0, Ui.dp(10f));
            body.addView(earlier);
        }
        while (index < steps.size()) {
            Api.Step step = steps.get(index);
            if (step.isTool()) {
                // 收集连续的工具步骤成一个组。
                List<Api.Step> group = new ArrayList<>();
                while (index < steps.size() && steps.get(index).isTool()) {
                    group.add(steps.get(index));
                    index += 1;
                }
                body.addView(toolGroup(group));
                body.addView(Ui.gap(host, 8f));
                continue;
            }
            body.addView(messageBlock(step));
            body.addView(Ui.gap(host, 8f));
            index += 1;
        }

        updateStatus();
        // 用户本来就在底部附近时自动跟到最新；翻历史时不打扰。
        final boolean follow = lastScrollY + scroll.getHeight() + Ui.dp(120f) >= scroll.getChildAt(0).getHeight();
        scroll.post(new Runnable() {
            @Override public void run() {
                if (follow) scroll.fullScroll(View.FOCUS_DOWN);
                else scroll.scrollTo(0, lastScrollY);
            }
        });
    }

    /** 一条消息：用户消息靠右、助手正文靠左，思考默认折叠。 */
    private View messageBlock(final Api.Step step) {
        LinearLayout wrapper = Ui.column(host);
        boolean user = step.isUser();
        if (user) {
            LinearLayout bubble = Ui.column(host);
            bubble.setBackground(Ui.round(Ui.SURFACE_HI, 14f, Ui.STROKE));
            bubble.setPadding(Ui.dp(12f), Ui.dp(10f), Ui.dp(12f), Ui.dp(10f));
            TextView label = Ui.text(host, "你", 11f, Ui.ACCENT);
            label.setPadding(0, 0, 0, Ui.dp(4f));
            bubble.addView(label);
            TextView content = Ui.text(host, "", 14.5f, Ui.FG);
            Markdown.render(content, step.text);
            bubble.addView(content);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.END;
            wrapper.addView(bubble, params);
            return wrapper;
        }

        if (step.thinking != null && step.thinking.length() > 0) {
            wrapper.addView(thinkingBlock(step));
        }
        if (step.text != null && step.text.length() > 0) {
            LinearLayout card = Ui.card(host);
            TextView content = Ui.text(host, "", 14.5f, Ui.FG);
            Markdown.render(content, step.text);
            card.addView(content);
            wrapper.addView(card);
        }
        return wrapper;
    }

    /** 思考块：默认折叠，展开状态记在 openThinking。 */
    private View thinkingBlock(final Api.Step step) {
        String key = "t" + step.seq;
        boolean open = openThinking.contains(key);
        LinearLayout box = Ui.column(host);
        box.setBackground(Ui.round(Ui.SURFACE, 12f, Ui.STROKE));
        box.setPadding(Ui.dp(12f), Ui.dp(9f), Ui.dp(12f), Ui.dp(9f));

        LinearLayout head = Ui.row(host);
        head.setClickable(true);
        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                String id = "t" + step.seq;
                if (openThinking.contains(id)) openThinking.remove(id);
                else openThinking.add(id);
                fingerprint = "";
                render();
            }
        });
        head.addView(Ui.text(host, open ? "▾" : "▸", 11f, Ui.MUTED));
        TextView label = Ui.text(host, "思考", 12f, Ui.MUTED);
        label.setPadding(Ui.dp(6f), 0, 0, 0);
        head.addView(label);
        box.addView(head);

        if (open) {
            TextView content = Ui.text(host, "", 12.5f, Ui.MUTED);
            Markdown.render(content, step.thinking);
            content.setPadding(0, Ui.dp(8f), 0, 0);
            box.addView(content);
        }
        LinearLayout.LayoutParams params = Ui.matchParams();
        params.bottomMargin = Ui.dp(6f);
        box.setLayoutParams(params);
        return box;
    }

    /**
     * 工具调用组：标题显示「N 次工具调用」，展开后逐条列出，
     * 点某一条再看它的参数与结果。
     */
    private View toolGroup(List<Api.Step> group) {
        String key = "g" + group.get(0).seq;
        boolean open = openTools.contains(key);
        LinearLayout box = Ui.card(host);

        final List<Api.Step> items = group;
        LinearLayout head = Ui.row(host);
        head.setClickable(true);
        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                String id = "g" + items.get(0).seq;
                if (openTools.contains(id)) openTools.remove(id);
                else openTools.add(id);
                fingerprint = "";
                render();
            }
        });
        int errors = 0;
        int runningCount = 0;
        for (Api.Step step : group) {
            if (step.isError) errors += 1;
            if ("running".equals(step.status)) runningCount += 1;
        }
        int color = errors > 0 ? Ui.DANGER : (runningCount > 0 ? Ui.WARN : Ui.OK);
        head.addView(Ui.dot(host, color));
        TextView label = Ui.text(host, group.size() + " 次工具调用", 13.5f, Ui.FG);
        head.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(Ui.text(host, open ? "收起" : "展开", 11.5f, Ui.ACCENT));
        box.addView(head);

        if (!open) {
            // 收起时给一行摘要，让用户知道都用了什么工具。
            StringBuilder summary = new StringBuilder();
            for (Api.Step step : group) {
                if (summary.length() > 0) summary.append(" · ");
                summary.append(step.label.length() == 0 ? step.name : step.label);
            }
            TextView line = Ui.ellipsis(host, summary.toString(), 11.5f, Ui.MUTED);
            line.setPadding(0, Ui.dp(6f), 0, 0);
            box.addView(line);
            return box;
        }

        for (Api.Step step : group) {
            box.addView(Ui.divider(host));
            box.addView(toolRow(step));
        }
        return box;
    }

    private View toolRow(final Api.Step step) {
        String key = "d" + step.seq;
        boolean open = openTools.contains(key);
        LinearLayout box = Ui.column(host);
        box.setPadding(0, Ui.dp(8f), 0, Ui.dp(8f));

        LinearLayout head = Ui.row(host);
        head.setClickable(true);
        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                String id = "d" + step.seq;
                if (openTools.contains(id)) openTools.remove(id);
                else openTools.add(id);
                fingerprint = "";
                render();
            }
        });
        int color = step.isError ? Ui.DANGER : ("running".equals(step.status) ? Ui.WARN : Ui.OK);
        head.addView(Ui.dot(host, color));
        TextView label = Ui.ellipsis(host, step.label.length() == 0 ? step.name : step.label, 13f, Ui.FG);
        head.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(Ui.text(host, open ? "▾" : "▸", 11f, Ui.MUTED));
        box.addView(head);

        if (step.detail.length() > 0) {
            TextView detail = Ui.text(host, "", 11.5f, Ui.MUTED);
            CodeView.mono(detail, step.detail);
            detail.setTextColor(Ui.MUTED);
            detail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f);
            detail.setPadding(0, Ui.dp(4f), 0, 0);
            detail.setMaxLines(open ? 40 : 2);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            box.addView(detail);
        }

        if (open && step.result.length() > 0) {
            TextView result = Ui.text(host, "", 11.5f, step.isError ? Ui.DANGER : Ui.MUTED);
            CodeView.mono(result, step.result);
            result.setTextColor(step.isError ? Ui.DANGER : Ui.MUTED);
            result.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f);
            result.setPadding(0, Ui.dp(8f), 0, 0);
            result.setTextIsSelectable(true);
            box.addView(result);
        }
        return box;
    }

    private void updateStatus() {
        if (statusView == null) return;
        String value;
        if (question != null) value = "等待你回答问题";
        else if (approval != null) value = "等待你批准工具";
        else if (running) value = "正在回答…";
        else value = "空闲 · 已更新";
        statusView.setText(title.length() == 0 ? value : value);
        statusView.setTextColor(question != null || approval != null ? Ui.WARN : Ui.MUTED);
    }

    /** 发消息。运行中时默认排队（与电脑上一致）。 */
    private void send() {
        String text = composer.getText().toString().trim();
        if (text.length() == 0) {
            // 中文输入法未上屏时 getText() 可能为空，静默不发送会让人以为卡住了。
            host.toast("请先输入内容");
            return;
        }
        composer.setText("");
        // 发送后必须交还焦点并清掉编辑标记：否则 onFocusChange 会把 editing 又设回 true，
        // 自动刷新被永久暂停，用户看不到自己的消息和后续回复。
        composer.clearFocus();
        editing = false;
        sendButton.setEnabled(false);
        Api.send(host.store, sessionId, text, false, new Api.Callback() {
            @Override public void onResult(JSONObject value, String error) {
                sendButton.setEnabled(true);
                if (error != null) {
                    host.toast("发送失败：" + error);
                    return;
                }
                fingerprint = "";
                load(true);
            }
        });
    }

    private void cancel() {
        Api.cancel(host.store, sessionId, new Api.Callback() {
            @Override public void onResult(JSONObject value, String error) {
                if (error != null) host.toast(error);
                else host.toast("已请求停止");
                load(true);
            }
        });
    }

    private void renameDialog() {
        final EditText input = Ui.input(host, "新的会话标题");
        input.setText(title);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        new android.app.AlertDialog.Builder(host)
                .setTitle("重命名会话")
                .setView(input)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        String next = input.getText().toString().trim();
                        if (next.length() == 0) return;
                        Api.rename(host.store, sessionId, next, new Api.Callback() {
                            @Override public void onResult(JSONObject value, String error) {
                                if (error != null) host.toast(error);
                                else {
                                    title = next;
                                    host.activeSessionTitle = next;
                                    fingerprint = "";
                                    load(true);
                                }
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showMenu() {
        final String[] items = running ? new String[]{"停止回答", "重命名会话"} : new String[]{"重命名会话"};
        new android.app.AlertDialog.Builder(host)
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        if (items[which].equals("停止回答")) cancel();
                        else renameDialog();
                    }
                })
                .show();
    }

    // ===== 审批与答题 =====

    /** 审批状态单独轮询：它随时可能出现，且出现后电脑端会一直卡住。 */
    private void refreshApproval() {
        if (!visible) return;
        Api.approvals(host.store, new Api.Callback() {
            @Override public void onResult(JSONObject value, String error) {
                if (!visible || error != null) return;
                List<Api.Approval> list = Api.parseApprovals(value);
                Api.Approval mine = null;
                for (Api.Approval item : list) {
                    if (sessionId.equals(item.sessionId)) {
                        mine = item;
                        break;
                    }
                }
                boolean changed = (approval == null) != (mine == null)
                        || (approval != null && mine != null && !approval.eventId.equals(mine.eventId));
                approval = mine;
                if (changed) {
                    fingerprint = "";
                    render();
                    showApprovalSheet();
                }
            }
        });
    }

    private void showApprovalSheet() {
        if (approval == null || !visible) return;
        final Api.Approval current = approval;
        String tool = current.toolName.length() == 0 ? "一个工具" : current.toolName;
        String reason = current.displayReason.length() > 0 ? current.displayReason : current.reason;
        new android.app.AlertDialog.Builder(host)
                .setTitle("允许执行吗？")
                .setMessage(tool + " 请求越权执行" + (reason.length() > 0 ? "\n\n" + reason : ""))
                .setPositiveButton("允许一次", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        decide(current.eventId, true);
                    }
                })
                .setNegativeButton("拒绝", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        decide(current.eventId, false);
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void decide(String eventId, boolean allow) {
        Api.decide(host.store, eventId, allow, new Api.Callback() {
            @Override public void onResult(JSONObject value, String error) {
                if (error != null) host.toast(error);
                approval = null;
                fingerprint = "";
                load(true);
            }
        });
    }

    /** 答题浮层：逐题选择，多选可累加，支持自定义答案。 */
    private void showQuestionSheet() {
        if (question == null || !visible || question.questions.isEmpty()) return;
        final Api.Ask ask = question;
        final List<List<String>> chosen = new ArrayList<>();
        for (int i = 0; i < ask.questions.size(); i += 1) chosen.add(new ArrayList<String>());

        ScrollView scrollView = Ui.scroll(host);
        LinearLayout box = Ui.column(host);
        box.setPadding(Ui.dp(16f), Ui.dp(8f), Ui.dp(16f), Ui.dp(8f));

        for (int i = 0; i < ask.questions.size(); i += 1) {
            final Api.Question entry = ask.questions.get(i);
            final int index = i;
            LinearLayout card = Ui.card(host);
            if (entry.header.length() > 0) card.addView(Ui.tag(host, entry.header, Ui.ACCENT, Ui.SURFACE_HI));
            TextView text = Ui.title(host, entry.question, 15f);
            text.setPadding(0, Ui.dp(8f), 0, Ui.dp(10f));
            card.addView(text);
            for (final Api.Option option : entry.options) {
                LinearLayout row = Ui.row(host);
                row.setPadding(Ui.dp(8f), Ui.dp(10f), Ui.dp(8f), Ui.dp(10f));
                row.setBackground(Ui.pressable(Ui.SURFACE_HI, 10f, 0));
                row.setClickable(true);
                TextView label = Ui.text(host, option.label, 14f, Ui.FG);
                row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                final TextView mark = Ui.text(host, "", 13f, Ui.ACCENT);
                row.addView(mark);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        List<String> picked = chosen.get(index);
                        if (entry.multiSelect) {
                            if (picked.contains(option.label)) picked.remove(option.label);
                            else picked.add(option.label);
                        } else {
                            picked.clear();
                            picked.add(option.label);
                        }
                        mark.setText(picked.contains(option.label) ? "✓" : "");
                        row.setBackground(Ui.round(Ui.SURFACE_HI, 10f, picked.contains(option.label) ? Ui.ACCENT : 0));
                    }
                });
                LinearLayout.LayoutParams params = Ui.matchParams();
                params.topMargin = Ui.dp(6f);
                card.addView(row, params);
            }
            if (entry.options.isEmpty()) {
                TextView hint = Ui.muted(host, "这题需要自己填答案", 12f);
                card.addView(hint);
            }
            LinearLayout.LayoutParams cardParams = Ui.matchParams();
            cardParams.bottomMargin = Ui.dp(10f);
            box.addView(card, cardParams);
        }

        EditText custom = Ui.input(host, "补充说明（可留空）");
        box.addView(custom, Ui.matchParams());

        scrollView.addView(box, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(host)
                .setTitle("需要你回答")
                .setView(scrollView)
                .setPositiveButton("提交", null)
                .setNegativeButton("稍后", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        dismissedCallId = ask.callId;
                    }
                })
                .setCancelable(true);
        final android.app.AlertDialog dialog = builder.create();
        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override public void onShow(android.content.DialogInterface d) {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        String extra = custom.getText().toString().trim();
                        JSONArray answers = new JSONArray();
                        boolean complete = true;
                        for (int i = 0; i < ask.questions.size(); i += 1) {
                            Api.Question entry = ask.questions.get(i);
                            List<String> picked = chosen.get(i);
                            if (picked.isEmpty() && extra.length() == 0) {
                                complete = false;
                                break;
                            }
                            JSONObject answer = new JSONObject();
                            try {
                                answer.put("id", entry.id);
                                JSONArray selected = new JSONArray();
                                for (String value : picked) selected.put(value);
                                answer.put("selected", selected);
                                if (extra.length() > 0) answer.put("custom", extra);
                            } catch (Exception ignored) { }
                            answers.put(answer);
                        }
                        if (!complete) {
                            host.toast("请先选一项，或填一句补充说明");
                            return;
                        }
                        dialog.dismiss();
                        submitAnswer(ask, answers);
                    }
                });
            }
        });
        dialog.show();
    }

    private void submitAnswer(final Api.Ask ask, JSONArray answers) {
        Api.answer(host.store, ask.sessionId, ask.callId, ask.eventId, answers, new Api.Callback() {
            @Override public void onResult(JSONObject value, String error) {
                if (error != null) {
                    host.toast("提交失败：" + error);
                    return;
                }
                host.toast("已提交");
                question = null;
                fingerprint = "";
                load(true);
            }
        });
    }

    private String computeFingerprint() {
        StringBuilder builder = new StringBuilder();
        builder.append(running).append('|');
        for (Api.Step step : steps) {
            builder.append(step.seq).append(step.kind).append(step.status)
                    .append(step.text.length()).append(step.thinking.length())
                    .append(step.result.length()).append(step.isError).append(';');
        }
        for (String key : openThinking) builder.append(key);
        for (String key : openTools) builder.append(key);
        builder.append(question == null ? 'q' : 'Q');
        return builder.toString();
    }
}
