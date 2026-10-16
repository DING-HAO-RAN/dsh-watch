package cn.dsh.watch;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 与中继服务的 HTTP 通信层：全部在后台线程执行，结果回到主线程。 */
final class Api {
    /** 单次请求超时；手表在弱网下也不该长时间无反馈。 */
    private static final int TIMEOUT_MS = 15_000;
    /** 会话消息可能较长，读取上限放宽。 */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 一次调用的结果：成功时 value 非空，失败时 error 为可展示的中文说明。 */
    static final class Result {
        final boolean ok;
        final JSONObject value;
        final String error;

        private Result(boolean ok, JSONObject value, String error) {
            this.ok = ok;
            this.value = value;
            this.error = error;
        }

        static Result success(JSONObject value) {
            return new Result(true, value, "");
        }

        static Result failure(String error) {
            return new Result(false, null, error);
        }
    }

    interface Callback {
        void onResult(Result result);
    }

    /** 在主线程回调，便于直接更新界面。 */
    private void deliver(Callback callback, Result result) {
        main.post(() -> callback.onResult(result));
    }

    void get(String host, int port, String path, String token, Callback callback) {
        request("GET", host, port, path, token, null, callback);
    }

    void post(String host, int port, String path, String token, JSONObject body, Callback callback) {
        request("POST", host, port, path, token, body, callback);
    }

    private void request(String method, String host, int port, String path, String token,
                         JSONObject body, Callback callback) {
        pool.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("http", host, port, path);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod(method);
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setRequestProperty("accept", "application/json");
                if (token != null && !token.isEmpty()) {
                    connection.setRequestProperty("authorization", "Bearer " + token);
                }
                if (body != null) {
                    connection.setDoOutput(true);
                    connection.setRequestProperty("content-type", "application/json; charset=utf-8");
                    byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                    connection.setFixedLengthStreamingMode(payload.length);
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(payload);
                    }
                }
                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();
                String text = readAll(stream);
                if (status < 200 || status >= 300) {
                    deliver(callback, Result.failure(describe(status, text)));
                    return;
                }
                deliver(callback, Result.success(new JSONObject(text)));
            } catch (Exception error) {
                deliver(callback, Result.failure(friendly(error)));
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    /** 把技术性异常转成用户能看懂的话。 */
    private static String friendly(Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (error instanceof java.net.SocketTimeoutException) return "连接超时，请确认电脑上的中继在运行";
        if (error instanceof java.net.ConnectException) return "连不上电脑，请确认在同一局域网";
        if (error instanceof java.net.UnknownHostException) return "找不到这个地址";
        if (message.contains("ECONNREFUSED")) return "电脑拒绝连接，中继可能没启动";
        return "网络错误：" + message;
    }

    private static String describe(int status, String text) {
        String detail = text == null ? "" : text.trim();
        try {
            JSONObject json = new JSONObject(detail);
            String error = text(json, "error");
            if (!error.isEmpty()) {
                if (status == 401) return "配对已失效，请重新配对";
                return error;
            }
        } catch (Exception ignored) {
            // 非 JSON 响应，退回通用文案
        }
        if (status == 401) return "配对已失效，请重新配对";
        if (status == 404) return "接口不存在，请更新电脑上的中继";
        return "服务返回错误（" + status + "）";
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        int total = 0;
        while ((read = stream.read(chunk)) != -1) {
            total += read;
            if (total > MAX_RESPONSE_BYTES) break;
            buffer.write(chunk, 0, read);
        }
        buffer.close();
        return buffer.toString("UTF-8");
    }

    // ---------- 下面是把 JSON 转成界面模型的解析 ----------

    /** 会话摘要。 */
    static final class Session {
        String id = "";
        String title = "";
        boolean running;
        boolean waiting;
        boolean subagent;
        long updatedAt;

        /** 列表页的副标题：状态 + 相对时间。 */
        String subtitle() {
            StringBuilder builder = new StringBuilder();
            if (waiting) builder.append("待回答");
            else if (running) builder.append("运行中");
            else builder.append("空闲");
            if (updatedAt > 0) builder.append(" · ").append(relativeTime(updatedAt));
            if (subagent) builder.append(" · 子任务");
            return builder.toString();
        }
    }

    /** 对话消息。 */
    static final class Message {
        String role = "assistant";
        String text = "";
        final List<String> tools = new ArrayList<>();
    }

    /** 选项。 */
    static final class Option {
        String label = "";
        String description = "";
    }

    /** 单个问题。 */
    static final class Question {
        String id = "";
        String header = "";
        String question = "";
        boolean multiSelect;
        final List<Option> options = new ArrayList<>();
    }

    /** 一组待答问题（一个 callId 下可能有多个小问）。 */
    static final class Ask {
        String sessionId = "";
        String sessionTitle = "";
        String callId = "";
        /** 前台等待中的提问只有事件流 id；提交答案时中继用它回填 waterfall 结果。 */
        String eventId = "";
        String state = "";
        final List<Question> questions = new ArrayList<>();
    }

    /**
     * 读一个字符串字段，缺失或为 null 时给空串。
     *
     * org.json 的 optString 在字段显式为 null 时会返回 null（而不是默认值），
     * 手表上的文案字段大量来自服务端 JSON，这里必须统一兜底，否则会变成
     * 界面上的 NullPointerException。
     */
    private static String text(JSONObject object, String key) {
        String value = object.optString(key, "");
        return value == null ? "" : value;
    }

    /** 数组元素版本的同一兜底。 */
    private static String text(JSONArray array, int index) {
        String value = array.optString(index, "");
        return value == null ? "" : value;
    }

    static List<Session> parseSessions(JSONObject root) {
        List<Session> sessions = new ArrayList<>();
        JSONArray array = root.optJSONArray("sessions");
        if (array == null) return sessions;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            Session session = new Session();
            session.id = text(item, "id");
            session.title = text(item, "title");
            session.running = item.optBoolean("running", false);
            session.waiting = item.optBoolean("waiting", false);
            session.subagent = item.optBoolean("subagent", false);
            session.updatedAt = item.optLong("updatedAt", 0L);
            if (!session.id.isEmpty()) sessions.add(session);
        }
        return sessions;
    }

    static List<Ask> parsePending(JSONObject root) {
        List<Ask> asks = new ArrayList<>();
        JSONArray array = root.optJSONArray("pending");
        if (array == null) return asks;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            Ask ask = new Ask();
            ask.sessionId = text(item, "sessionId");
            ask.sessionTitle = text(item, "sessionTitle");
            ask.callId = text(item, "callId");
            ask.eventId = text(item, "eventId");
            ask.state = text(item, "state"); if (ask.state.isEmpty()) ask.state = "open";
            ask.questions.addAll(parseQuestions(item.optJSONArray("questions")));
            if (!ask.callId.isEmpty() && !ask.questions.isEmpty()) asks.add(ask);
        }
        return asks;
    }

    static List<Question> parseQuestions(JSONArray array) {
        List<Question> questions = new ArrayList<>();
        if (array == null) return questions;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            Question question = new Question();
            question.id = text(item, "id");
            question.header = text(item, "header");
            question.question = text(item, "question");
            question.multiSelect = item.optBoolean("multiSelect", false);
            JSONArray options = item.optJSONArray("options");
            if (options != null) {
                for (int j = 0; j < options.length(); j++) {
                    JSONObject raw = options.optJSONObject(j);
                    if (raw == null) continue;
                    Option option = new Option();
                    option.label = text(raw, "label");
                    option.description = text(raw, "description");
                    if (!option.label.isEmpty()) question.options.add(option);
                }
            }
            if (!question.id.isEmpty()) questions.add(question);
        }
        return questions;
    }

    static List<Message> parseMessages(JSONObject root) {
        List<Message> messages = new ArrayList<>();
        JSONArray array = root.optJSONArray("messages");
        if (array == null) return messages;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            Message message = new Message();
            message.role = text(item, "role"); if (message.role.isEmpty()) message.role = "assistant";
            message.text = text(item, "text");
            JSONArray tools = item.optJSONArray("tools");
            if (tools != null) {
                for (int j = 0; j < tools.length(); j++) {
                    String tool = text(tools, j);
                    if (!tool.isEmpty()) message.tools.add(tool);
                }
            }
            if (!message.text.trim().isEmpty() || !message.tools.isEmpty()) messages.add(message);
        }
        return messages;
    }

    /** 把待答问题（详情接口返回的单个 question 对象）解析成 Ask。 */
    static Ask parseQuestion(JSONObject item, String sessionId, String title) {
        if (item == null) return null;
        Ask ask = new Ask();
        ask.sessionId = sessionId;
        ask.sessionTitle = title == null ? "" : title;
        ask.callId = text(item, "callId");
        ask.eventId = text(item, "eventId");
        ask.state = text(item, "state"); if (ask.state.isEmpty()) ask.state = "open";
        ask.questions.addAll(parseQuestions(item.optJSONArray("questions")));
        if (ask.callId.isEmpty() || ask.questions.isEmpty()) return null;
        return ask;
    }

    /** 相对时间：手表上空间紧张，用最短的表达。 */
    static String relativeTime(long epochMs) {
        long delta = System.currentTimeMillis() - epochMs;
        if (delta < 0) delta = 0;
        long minutes = delta / 60_000L;
        if (minutes < 1) return "刚刚";
        if (minutes < 60) return minutes + " 分钟前";
        long hours = minutes / 60;
        if (hours < 24) return hours + " 小时前";
        long days = hours / 24;
        if (days < 30) return days + " 天前";
        return (days / 30) + " 个月前";
    }

    static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception error) {
            return value;
        }
    }

    /** 供设置页展示用：把 JSON 对象里的布尔/字符串字段读成 map。 */
    static Map<String, String> flatten(JSONObject object) {
        Map<String, String> map = new LinkedHashMap<>();
        if (object == null) return map;
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            map.put(key, String.valueOf(object.opt(key)));
        }
        return map;
    }
}
