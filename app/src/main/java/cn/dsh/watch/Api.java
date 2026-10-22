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

    private static javax.net.ssl.SSLSocketFactory insecureFactory;

    /**
     * 放行全部证书的 SSL 工厂。
     *
     * 用途：公网中转常用自签名证书或私有域名，手表上没有便捷的证书安装入口。
     * 风险由「地址私密 + 配对令牌」承担：令牌是访问中继的唯一凭据，
     * 中继本身也只暴露这一层接口。
     */
    private static synchronized javax.net.ssl.SSLSocketFactory insecureFactory() {
        if (insecureFactory != null) return insecureFactory;
        try {
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                    }
            };
            javax.net.ssl.SSLContext context = javax.net.ssl.SSLContext.getInstance("TLS");
            context.init(null, trustAll, new java.security.SecureRandom());
            insecureFactory = context.getSocketFactory();
        } catch (Exception error) {
            throw new IllegalStateException("无法初始化 TLS：", error);
        }
        return insecureFactory;
    }

    void get(String base, String path, String token, Callback callback) {
        request("GET", base, path, token, null, callback);
    }

    void post(String base, String path, String token, JSONObject body, Callback callback) {
        request("POST", base, path, token, body, callback);
    }

    private void request(String method, String base, String path, String token,
                         JSONObject body, Callback callback) {
        pool.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(base + path);
                connection = (HttpURLConnection) url.openConnection();
                if (connection instanceof javax.net.ssl.HttpsURLConnection) {
                    // 公网中转通常是自签名或普通证书；手表没有便捷的证书安装入口，
                    // 这里放行全部证书，安全性由配对令牌与地址私密性承担。
                    ((javax.net.ssl.HttpsURLConnection) connection).setSSLSocketFactory(insecureFactory());
                }
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
        /** 项目名（工作目录最后一段），列表按它分组。 */
        String project = "";
        String projectPath = "";
        String provider = "";
        String model = "";
        String effort = "";

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

    /** 会话中的一个步骤：用户消息、助手回复，或一次工具调用。 */
    static final class Step {
        /** message 或 tool。 */
        String kind = "message";
        /** 事件序号；同一个会话里唯一，界面用它认出「还是那一组工具调用」。 */
        long seq;
        String role = "assistant";
        String text = "";
        /** AI 的思考内容（DSH 的 reasoning 块）；为空表示这条没有思考。 */
        String thinking = "";
        final List<String> tools = new ArrayList<>();
        /** 工具内部名（如 pwsh），用于图标化显示。 */
        String name = "";
        /** 工具显示名（如 命令）。 */
        String label = "工具";
        /** 参数摘要：命令或文件路径等最有信息量的一句。 */
        String detail = "";
        /** running / done / error。 */
        String status = "done";
        String result = "";
        final List<String> files = new ArrayList<>();
        boolean isError;
    }

    /** 会话里出现过的一个可查看文件。 */
    static final class FileEntry {
        String path = "";
        String name = "";
        /** markdown / text / code / other。 */
        String kind = "text";
        /** 代码语言标识（csharp / java / …），非代码为空。 */
        String language = "";
        long size;

        static FileEntry from(JSONObject object) {
            if (object == null) return null;
            FileEntry entry = new FileEntry();
            entry.path = text(object, "path");
            entry.name = text(object, "name");
            if (entry.name.isEmpty()) entry.name = entry.path;
            entry.kind = text(object, "kind");
            if (entry.kind.isEmpty()) entry.kind = "text";
            entry.language = text(object, "language");
            entry.size = object.optLong("size", 0L);
            return entry.path.isEmpty() ? null : entry;
        }

        /** 中文类型标签，用于列表里的副标题。 */
        String kindText() {
            if ("markdown".equals(kind)) return "Markdown";
            if ("code".equals(kind)) return language.isEmpty() ? "代码" : language;
            if ("text".equals(kind)) return "文本";
            return "文件";
        }
    }

    /** 用量与运行统计。 */
    static final class Usage {
        long inputTokens;
        long outputTokens;
        long cacheReadTokens;
        long cacheWriteTokens;
        long pressureTokens;
        long contextWindow;
        double percent;
        long systemTokens;
        long toolsTokens;
        long messageTokens;
        long turns;
        long steps;
        long llmMs;
        long toolMs;
        long ttftMs;
        String provider = "";
        String model = "";
        String effort = "";
    }

    /** 一个可选的模型。 */
    static final class ModelInfo {
        String id = "";
        String name = "";
        final List<String> efforts = new ArrayList<>();
    }

    /** 一个模型供应商及其模型。 */
    static final class ProviderInfo {
        String provider = "";
        String name = "";
        final List<ModelInfo> models = new ArrayList<>();
    }

    /** 模型目录里记的默认选择；会话还没产生回复时用它当作「当前模型」。 */
    static final class ModelChoice {
        String provider = "";
        String model = "";
        String effort = "";
    }

    /** 按项目分组的会话。 */
    static final class Project {
        String project = "";
        String path = "";
        int running;
        int waiting;
        long updatedAt;
        final List<Session> sessions = new ArrayList<>();
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
     *
     * 包级可见：{@link Store} 解析多设备存档时也要用它。
     */
    static String text(JSONObject object, String key) {
        String value = object.optString(key, "");
        return value == null ? "" : value;
    }

    /** 数组元素版本的同一兜底。 */
    static String text(JSONArray array, int index) {
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
            session.project = text(item, "project");
            session.projectPath = text(item, "projectPath");
            session.provider = text(item, "provider");
            session.model = text(item, "model");
            session.effort = text(item, "effort");
            if (!session.id.isEmpty()) sessions.add(session);
        }
        return sessions;
    }

    /** 解析按项目分组的视图。 */
    static List<Project> parseProjects(JSONObject root) {
        List<Project> projects = new ArrayList<>();
        JSONArray array = root.optJSONArray("projects");
        if (array == null) return projects;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            Project project = new Project();
            project.project = text(item, "project");
            project.path = text(item, "path");
            project.running = item.optInt("running", 0);
            project.waiting = item.optInt("waiting", 0);
            project.updatedAt = item.optLong("updatedAt", 0L);
            JSONArray inner = item.optJSONArray("sessions");
            if (inner != null) {
                for (int j = 0; j < inner.length(); j++) {
                    JSONObject raw = inner.optJSONObject(j);
                    if (raw == null) continue;
                    Session session = new Session();
                    session.id = text(raw, "id");
                    session.title = text(raw, "title");
                    session.running = raw.optBoolean("running", false);
                    session.waiting = raw.optBoolean("waiting", false);
                    session.subagent = raw.optBoolean("subagent", false);
                    session.updatedAt = raw.optLong("updatedAt", 0L);
                    session.project = text(raw, "project");
                    session.projectPath = text(raw, "projectPath");
                    session.provider = text(raw, "provider");
                    session.model = text(raw, "model");
                    session.effort = text(raw, "effort");
                    if (!session.id.isEmpty()) project.sessions.add(session);
                }
            }
            if (!project.project.isEmpty()) projects.add(project);
        }
        return projects;
    }

    /** 解析会话里的步骤序列（含工具调用与结果）。 */
    static List<Step> parseSteps(JSONObject root) {
        List<Step> steps = new ArrayList<>();
        JSONArray array = root.optJSONArray("steps");
        if (array == null) return steps;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            Step step = new Step();
            step.kind = text(item, "kind");
            step.seq = item.optLong("seq", 0L);
            step.role = text(item, "role");
            step.text = text(item, "text");
            step.thinking = text(item, "thinking");
            step.name = text(item, "name");
            step.label = text(item, "label");
            step.detail = text(item, "detail");
            step.status = text(item, "status");
            step.result = text(item, "result");
            step.isError = item.optBoolean("isError", false);
            JSONArray tools = item.optJSONArray("tools");
            if (tools != null) {
                for (int j = 0; j < tools.length(); j++) {
                    String tool = text(tools, j);
                    if (!tool.isEmpty()) step.tools.add(tool);
                }
            }
            JSONArray files = item.optJSONArray("files");
            if (files != null) {
                for (int j = 0; j < files.length(); j++) {
                    String file = text(files, j);
                    if (!file.isEmpty()) step.files.add(file);
                }
            }
            steps.add(step);
        }
        return steps;
    }

    /** 解析用量与运行统计。 */
    static Usage parseUsage(JSONObject root) {
        JSONObject item = root.optJSONObject("usage");
        if (item == null) return null;
        Usage usage = new Usage();
        usage.inputTokens = item.optLong("inputTokens", 0L);
        usage.outputTokens = item.optLong("outputTokens", 0L);
        usage.cacheReadTokens = item.optLong("cacheReadTokens", 0L);
        usage.cacheWriteTokens = item.optLong("cacheWriteTokens", 0L);
        usage.pressureTokens = item.optLong("pressureTokens", 0L);
        usage.contextWindow = item.optLong("contextWindow", 0L);
        usage.percent = item.optDouble("percent", 0d);
        usage.systemTokens = item.optLong("systemTokens", 0L);
        usage.toolsTokens = item.optLong("toolsTokens", 0L);
        usage.messageTokens = item.optLong("messageTokens", 0L);
        usage.turns = item.optLong("turns", 0L);
        usage.steps = item.optLong("steps", 0L);
        usage.llmMs = item.optLong("llmMs", 0L);
        usage.toolMs = item.optLong("toolMs", 0L);
        usage.ttftMs = item.optLong("ttftMs", 0L);
        usage.provider = text(item, "provider");
        usage.model = text(item, "model");
        usage.effort = text(item, "effort");
        return usage;
    }

    /** 解析模型目录（供应商 → 模型 → 推理档位）。 */
    static List<ProviderInfo> parseModels(JSONObject root) {
        List<ProviderInfo> providers = new ArrayList<>();
        JSONArray array = root.optJSONArray("groups");
        if (array == null) return providers;
        for (int i = 0; i < array.length(); i++) {
            JSONObject group = array.optJSONObject(i);
            if (group == null) continue;
            ProviderInfo info = new ProviderInfo();
            info.provider = text(group, "provider");
            info.name = text(group, "name");
            JSONArray models = group.optJSONArray("models");
            if (models != null) {
                for (int j = 0; j < models.length(); j++) {
                    JSONObject raw = models.optJSONObject(j);
                    if (raw == null) continue;
                    ModelInfo model = new ModelInfo();
                    model.id = text(raw, "id");
                    model.name = text(raw, "name");
                    JSONArray efforts = raw.optJSONArray("efforts");
                    if (efforts != null) {
                        for (int k = 0; k < efforts.length(); k++) {
                            String effort = text(efforts, k);
                            if (!effort.isEmpty()) model.efforts.add(effort);
                        }
                    }
                    if (!model.id.isEmpty()) info.models.add(model);
                }
            }
            if (!info.provider.isEmpty()) providers.add(info);
        }
        return providers;
    }

    /**
     * 解析模型目录里的默认选择。
     *
     * 会话还没产生回复时用量里没有模型信息（新建会话就是这样），
     * 此时目录的 default 才是「当前模型」的唯一来源。
     */
    static ModelChoice parseModelDefault(JSONObject root) {
        ModelChoice choice = new ModelChoice();
        JSONObject item = root.optJSONObject("default");
        if (item == null) return choice;
        choice.provider = text(item, "provider");
        choice.model = text(item, "model");
        choice.effort = text(item, "effort");
        return choice;
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
