package cn.dsh.phone;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 中继 HTTP 客户端。
 *
 * 两个必须遵守的约束：
 * - **HTTPS 放行全部证书**：公网地址是 cloudflared 的临时域名，证书链在手机上不一定
 *   装得全；访问控制由配对令牌承担，因此这里不校验证书（手表端同样的取舍）。
 * - **主地址失败自动试备用地址**：一次配对会拿到局域网与公网两条地址，在家走局域网、
 *   在外走公网，不该让用户手动切换。
 */
final class Api {
    private static final int TIMEOUT_MS = 20_000;
    private static final int MAX_BYTES = 12 * 1024 * 1024;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static SSLSocketFactory insecureFactory;

    /** 异步回调。失败时 error 非空，value 为 null。 */
    interface Callback {
        void onResult(JSONObject value, String error);
    }

    private Api() { }

    static synchronized SSLSocketFactory insecureFactory() {
        if (insecureFactory != null) return insecureFactory;
        try {
            TrustManager[] managers = new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managers, new java.security.SecureRandom());
            insecureFactory = context.getSocketFactory();
        } catch (Exception ignored) { }
        return insecureFactory;
    }

    /**
     * 发一次请求。base 为空时直接失败；主地址失败会再试一次备用地址。
     *
     * @param method GET 或 POST
     * @param base 形如 http://192.168.1.5:19390 或 https://xxx.trycloudflare.com
     * @param path 以 / 开头的路径（可带查询串）
     * @param token 配对令牌，可空（配对与状态接口不需要）
     * @param body POST 的 JSON 体，可空
     */
    static void request(final String method, final String base, final String alternateBase, final String path,
                        final String token, final JSONObject body, final Callback callback) {
        // 远程主机（base 形如 remote://<hostDeviceId>）走账号服务器转发的加密隧道。
        if (base != null && base.startsWith("remote://")) {
            dispatchRemote(method, path, body, callback);
            return;
        }
        POOL.execute(new Runnable() {
            @Override public void run() {
                String error = null;
                JSONObject value = null;
                String firstError = null;
                List<String> candidates = new ArrayList<>();
                if (base != null && base.length() > 0) candidates.add(base);
                if (alternateBase != null && alternateBase.length() > 0 && !alternateBase.equals(base)) {
                    candidates.add(alternateBase);
                }
                if (candidates.isEmpty()) {
                    deliver(callback, null, "还没有配对中继，请先在「我的」里连接");
                    return;
                }
                for (String candidate : candidates) {
                    try {
                        value = execute(method, candidate, path, token, body);
                        error = null;
                        break;
                    } catch (Exception exception) {
                        error = friendly(exception);
                        if (firstError == null) firstError = error;
                    }
                }
                // 两条地址都失败时，报告第一条的错（通常是用户当前所在网络那条）。
                deliver(callback, value, value == null ? (firstError == null ? error : firstError) : null);
            }
        });
    }

    /** 远程通道：与中继形态共用同一套 JSON 结果结构，界面不必区分。 */
    private static RemoteBridge remoteBridge;

    static void setRemoteBridge(RemoteBridge bridge) {
        remoteBridge = bridge;
    }

    static boolean remoteAvailable() {
        return remoteBridge != null && remoteBridge.isAvailable();
    }

    private static void dispatchRemote(final String method, final String path,
                                       final JSONObject body, final Callback callback) {
        final RemoteBridge bridge = remoteBridge;
        if (bridge == null || !bridge.isAvailable()) {
            deliver(callback, null, "尚未连接远程主机");
            return;
        }
        POOL.execute(new Runnable() {
            @Override public void run() {
                String route = path;
                String query = "";
                int question = path.indexOf('?');
                if (question >= 0) {
                    route = path.substring(0, question);
                    query = path.substring(question + 1);
                }
                try {
                    JSONObject value = bridge.request(method, route, query, body);
                    deliver(callback, value, null);
                } catch (Exception error) {
                    deliver(callback, null, friendly(error));
                }
            }
        });
    }

    private static JSONObject execute(String method, String base, String path, String token, JSONObject body) throws Exception {
        String normalized = base.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) normalized = "http://" + normalized;
        URL url = new URL(normalized + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (connection instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) connection;
            SSLSocketFactory factory = insecureFactory();
            if (factory != null) https.setSSLSocketFactory(factory);
        }
        connection.setRequestMethod(method);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("accept", "application/json");
        if (token != null && token.length() > 0) {
            connection.setRequestProperty("authorization", "Bearer " + token);
        }
        if (body != null) {
            byte[] payload = body.toString().getBytes("UTF-8");
            connection.setDoOutput(true);
            connection.setRequestProperty("content-type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(payload.length);
            OutputStream stream = connection.getOutputStream();
            try {
                stream.write(payload);
            } finally {
                stream.close();
            }
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String text = readAll(input);
        connection.disconnect();
        JSONObject parsed;
        try {
            parsed = new JSONObject(text == null || text.length() == 0 ? "{}" : text);
        } catch (Exception exception) {
            throw new IllegalStateException("服务端返回的不是 JSON（HTTP " + status + "）");
        }
        if (status == 401) throw new IllegalStateException("配对已失效，请在「我的」里重新配对");
        if (status >= 400 || !parsed.optBoolean("ok", true)) {
            String message = parsed.optString("error", "");
            if (message.length() == 0) message = "请求失败（HTTP " + status + "）";
            throw new IllegalStateException(message);
        }
        return parsed;
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        try {
            int count;
            while ((count = stream.read(chunk)) > 0) {
                total += count;
                if (total > MAX_BYTES) throw new IllegalStateException("响应过大");
                buffer.write(chunk, 0, count);
            }
        } finally {
            stream.close();
        }
        return buffer.toString("UTF-8");
    }

    private static void deliver(final Callback callback, final JSONObject value, final String error) {
        if (callback == null) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                callback.onResult(value, error);
            }
        });
    }

    private static String friendly(Exception error) {
        String message = error.getMessage();
        if (message == null) message = error.getClass().getSimpleName();
        if (message.contains("ECONNREFUSED") || message.contains("Connection refused")) return "中继没有在运行";
        if (message.contains("ETIMEDOUT") || message.contains("timed out")) return "连接超时，检查是否在同一网络";
        if (message.contains("Unable to resolve host")) return "找不到这个地址";
        if (message.contains("Network is unreachable")) return "网络不可达";
        return message;
    }

    // ===== 端点封装 =====

    static void status(String base, Callback callback) {
        request("GET", base, "", "/api/status", "", null, callback);
    }

    static void pair(String base, String code, String deviceId, Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("code", code);
            if (deviceId != null && deviceId.length() > 0) body.put("deviceId", deviceId);
        } catch (Exception ignored) { }
        request("POST", base, "", "/api/pair", "", body, callback);
    }

    static void sessions(Store store, Callback callback) {
        request("GET", store.base(), store.alternateBase(), "/api/sessions", store.token(), null, callback);
    }

    static void session(Store store, String id, long through, Callback callback) {
        String path = "/api/session?id=" + urlEncode(id) + (through > 0 ? "&through=" + through : "");
        request("GET", store.base(), store.alternateBase(), path, store.token(), null, callback);
    }

    static void send(Store store, String id, String text, boolean steer, Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("sessionId", id);
            body.put("text", text);
            body.put("mode", steer ? "steer" : "queue");
        } catch (Exception ignored) { }
        request("POST", store.base(), store.alternateBase(), "/api/send", store.token(), body, callback);
    }

    static void cancel(Store store, String id, Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("sessionId", id);
        } catch (Exception ignored) { }
        request("POST", store.base(), store.alternateBase(), "/api/cancel", store.token(), body, callback);
    }

    /** 往当前会话工作目录上传一个文件（body 含 id/name/base64）。 */
    static void upload(Store store, JSONObject body, Callback callback) {
        request("POST", store.base(), store.alternateBase(), "/api/upload", store.token(), body, callback);
    }

    /** 对一条排队消息执行 remove / steer / edit。 */
    static void queueAction(Store store, String sessionId, String itemId, String kind,
                            String text, Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("id", sessionId);
            body.put("itemId", itemId);
            body.put("kind", kind);
            if (text != null && text.length() > 0) body.put("text", text);
        } catch (Exception ignored) { }
        request("POST", store.base(), store.alternateBase(), "/api/session/queue",
                store.token(), body, callback);
    }

    static void answer(Store store, String sessionId, String callId, String eventId, JSONArray answers, Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("sessionId", sessionId);
            body.put("callId", callId);
            body.put("eventId", eventId);
            body.put("answers", answers);
        } catch (Exception ignored) { }
        request("POST", store.base(), store.alternateBase(), "/api/answer", store.token(), body, callback);
    }

    static void approvals(Store store, Callback callback) {
        request("GET", store.base(), store.alternateBase(), "/api/approvals", store.token(), null, callback);
    }

    static void decide(Store store, String eventId, boolean allow, Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("eventId", eventId);
            body.put("decision", allow ? "allowed-once" : "rejected");
        } catch (Exception ignored) { }
        request("POST", store.base(), store.alternateBase(), "/api/approval", store.token(), body, callback);
    }

    static void workspaces(Store store, Callback callback) {
        request("GET", store.base(), store.alternateBase(), "/api/workspaces", store.token(), null, callback);
    }

    static void files(Store store, String sessionId, String path, Callback callback) {
        String query = "/api/files?id=" + urlEncode(sessionId);
        if (path != null && path.length() > 0) query += "&path=" + urlEncode(path);
        request("GET", store.base(), store.alternateBase(), query, store.token(), null, callback);
    }

    static void file(Store store, String sessionId, String path, Callback callback) {
        String query = "/api/file?id=" + urlEncode(sessionId) + "&path=" + urlEncode(path);
        request("GET", store.base(), store.alternateBase(), query, store.token(), null, callback);
    }

    static void rename(Store store, String sessionId, String title, Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("id", sessionId);
            body.put("title", title);
        } catch (Exception ignored) { }
        request("POST", store.base(), store.alternateBase(), "/api/session/rename", store.token(), body, callback);
    }

    static void models(Store store, Callback callback) {
        request("GET", store.base(), store.alternateBase(), "/api/models", store.token(), null, callback);
    }

    static void selectModel(Store store, String sessionId, String provider, String model, String effort, Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("sessionId", sessionId);
            body.put("provider", provider);
            body.put("model", model);
            if (effort != null && effort.length() > 0) body.put("effort", effort);
        } catch (Exception ignored) { }
        request("POST", store.base(), store.alternateBase(), "/api/model", store.token(), body, callback);
    }

    static void terminals(Store store, String sessionId, Callback callback) {
        request("GET", store.base(), store.alternateBase(), "/api/terminals?id=" + urlEncode(sessionId), store.token(), null, callback);
    }

    static void terminal(Store store, JSONObject body, Callback callback) {
        request("POST", store.base(), store.alternateBase(), "/api/terminal", store.token(), body, callback);
    }

    static void terminalOutput(Store store, String terminalId, long since, Callback callback) {
        String path = "/api/terminal/output?id=" + urlEncode(terminalId) + "&since=" + since;
        request("GET", store.base(), store.alternateBase(), path, store.token(), null, callback);
    }

    static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception exception) {
            return "";
        }
    }

    // ===== 解析辅助 =====

    /** 服务端文案字段可能是显式 null，optString 遇 null 会返回 null，这里统一兜底。 */
    static String text(JSONObject object, String key) {
        if (object == null) return "";
        String value = object.optString(key, "");
        return value == null ? "" : value;
    }

    static String text(JSONArray array, int index) {
        if (array == null || index < 0 || index >= array.length()) return "";
        String value = array.optString(index, "");
        return value == null ? "" : value;
    }

    // ===== 数据模型 =====

    /** 一条排队中的消息（运行中会话里还没发出的指令）。 */
    static final class QueuedMessage {
        String id = "";
        String text = "";
        /** next-step 表示插话，next-turn 表示下一轮。 */
        String bucket = "";
    }

    /** 解析会话里排队中的消息。 */
    static List<QueuedMessage> parseQueue(JSONObject value) {
        List<QueuedMessage> out = new ArrayList<>();
        if (value == null) return out;
        JSONArray array = value.optJSONArray("queue");
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            QueuedMessage message = new QueuedMessage();
            message.id = text(item, "id");
            message.text = text(item, "text");
            message.bucket = text(item, "bucket");
            if (message.id.isEmpty()) continue;
            out.add(message);
        }
        return out;
    }

    static final class Session {
        String id = "";
        String title = "";
        String project = "";
        String projectPath = "";
        String provider = "";
        String model = "";
        String effort = "";
        String parentId = "";
        String subagentLabel = "";
        boolean running;
        boolean waiting;
        boolean subagent;
        boolean completed;
        long updatedAt;
        int subagentCount;
        int subagentRunning;
        int turns;
        int steps;
        long settledMs;

        static Session from(JSONObject object) {
            Session item = new Session();
            item.id = text(object, "id");
            item.title = text(object, "title");
            item.project = text(object, "project");
            item.projectPath = text(object, "projectPath");
            item.provider = text(object, "provider");
            item.model = text(object, "model");
            item.effort = text(object, "effort");
            item.parentId = text(object, "parentId");
            item.subagentLabel = text(object, "subagentLabel");
            item.running = object.optBoolean("running", false);
            item.waiting = object.optBoolean("waiting", false);
            item.subagent = object.optBoolean("subagent", false);
            item.completed = object.optBoolean("completed", false);
            item.updatedAt = object.optLong("updatedAt", 0);
            item.subagentCount = object.optInt("subagentCount", 0);
            item.subagentRunning = object.optInt("subagentRunning", 0);
            item.turns = object.optInt("turns", 0);
            item.steps = object.optInt("steps", 0);
            item.settledMs = object.optLong("settledMs", 0);
            return item;
        }
    }

    static List<Session> parseSessions(JSONObject root) {
        List<Session> list = new ArrayList<>();
        JSONArray array = root == null ? null : root.optJSONArray("sessions");
        if (array == null) return list;
        for (int i = 0; i < array.length(); i += 1) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) list.add(Session.from(item));
        }
        return list;
    }

    /** 消息：role 为 user / assistant，text 是正文，tools 是本轮用到的工具标签。 */
    static final class Message {
        String role = "";
        String text = "";
        String thinking = "";
        long at;
        int seq;
        final List<String> tools = new ArrayList<>();
    }

    /**
     * 一步：中继把会话压成「用户消息 / 助手回复 / 工具调用」三种条目。
     *
     * kind 只有 message 与 tool 两个取值；tool 条目带 label（人话名）、detail（参数摘要）、
     * result（结果开头）与 status（running / done / error）。
     */
    static final class Step {
        String kind = "message";
        String role = "";
        String text = "";
        String thinking = "";
        String name = "";
        String label = "";
        String detail = "";
        String status = "";
        String result = "";
        boolean isError;
        int seq;
        final List<String> tools = new ArrayList<>();
        final List<String> files = new ArrayList<>();

        boolean isTool() {
            return "tool".equals(kind);
        }

        boolean isUser() {
            return "user".equals(role);
        }
    }

    /** 工具调用分组：中继已按正文分界把连续调用归组。 */
    static final class Group {
        String key = "";
        String title = "";
        final List<Step> steps = new ArrayList<>();
    }

    static final class Ask {
        String sessionId = "";
        String sessionTitle = "";
        String callId = "";
        String eventId = "";
        String state = "";
        final List<Question> questions = new ArrayList<>();
    }

    static final class Question {
        String id = "";
        String header = "";
        String question = "";
        boolean multiSelect;
        final List<Option> options = new ArrayList<>();
    }

    static final class Option {
        String label = "";
        String description = "";
    }

    static final class Approval {
        String eventId = "";
        String sessionId = "";
        String toolName = "";
        String reason = "";
        String displayReason = "";
    }

    static final class FileEntry {
        String name = "";
        String type = "file";
        String language = "";
        long size;
        long mtime;

        boolean isDir() {
            return "directory".equals(type);
        }
    }

    static final class Workspace {
        String path = "";
        String name = "";
        int sessions;
        int running;
        int waiting;
        long updatedAt;
    }

    static final class TerminalInfo {
        String id = "";
        String title = "";
        String cwd = "";
        String state = "";
        String shell = "";
        int cols;
        int rows;
        boolean attached;
    }

    static final class Provider {
        String id = "";
        String name = "";
        final List<ModelInfo> models = new ArrayList<>();
    }

    static final class ModelInfo {
        String id = "";
        String name = "";
        String description = "";
        final List<String> efforts = new ArrayList<>();
    }

    static List<Workspace> parseWorkspaces(JSONObject root) {
        List<Workspace> list = new ArrayList<>();
        JSONArray array = root == null ? null : root.optJSONArray("workspaces");
        if (array == null) return list;
        for (int i = 0; i < array.length(); i += 1) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            Workspace workspace = new Workspace();
            workspace.path = text(item, "path");
            workspace.name = text(item, "name");
            workspace.sessions = item.optInt("sessions", 0);
            workspace.running = item.optInt("running", 0);
            workspace.waiting = item.optInt("waiting", 0);
            workspace.updatedAt = item.optLong("updatedAt", 0);
            list.add(workspace);
        }
        return list;
    }

    static List<Approval> parseApprovals(JSONObject root) {
        List<Approval> list = new ArrayList<>();
        JSONArray array = root == null ? null : root.optJSONArray("approvals");
        if (array == null) return list;
        for (int i = 0; i < array.length(); i += 1) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            Approval approval = new Approval();
            approval.eventId = text(item, "eventId");
            approval.sessionId = text(item, "sessionId");
            approval.toolName = text(item, "toolName");
            approval.reason = text(item, "reason");
            approval.displayReason = text(item, "displayReason");
            list.add(approval);
        }
        return list;
    }

    static List<FileEntry> parseFiles(JSONObject root) {
        List<FileEntry> list = new ArrayList<>();
        JSONArray array = root == null ? null : root.optJSONArray("entries");
        if (array == null) return list;
        for (int i = 0; i < array.length(); i += 1) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            FileEntry entry = new FileEntry();
            entry.name = text(item, "name");
            entry.type = text(item, "type");
            entry.language = text(item, "language");
            entry.size = item.optLong("size", 0);
            entry.mtime = item.optLong("mtime", 0);
            list.add(entry);
        }
        return list;
    }

    static List<TerminalInfo> parseTerminals(JSONObject root) {
        List<TerminalInfo> list = new ArrayList<>();
        JSONArray array = root == null ? null : root.optJSONArray("terminals");
        if (array == null) return list;
        for (int i = 0; i < array.length(); i += 1) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            TerminalInfo info = new TerminalInfo();
            info.id = text(item, "id");
            info.title = text(item, "title");
            info.cwd = text(item, "cwd");
            info.state = text(item, "state");
            info.shell = text(item, "shell");
            info.cols = item.optInt("cols", 0);
            info.rows = item.optInt("rows", 0);
            info.attached = item.optBoolean("attached", false);
            list.add(info);
        }
        return list;
    }

    static List<Message> parseMessages(JSONObject root) {
        List<Message> list = new ArrayList<>();
        JSONArray array = root == null ? null : root.optJSONArray("messages");
        if (array == null) return list;
        for (int i = 0; i < array.length(); i += 1) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            Message message = new Message();
            message.role = text(item, "role");
            message.text = text(item, "text");
            message.thinking = text(item, "thinking");
            message.at = item.optLong("at", 0);
            message.seq = item.optInt("seq", 0);
            JSONArray tools = item.optJSONArray("tools");
            if (tools != null) {
                for (int j = 0; j < tools.length(); j += 1) {
                    String value = text(tools, j);
                    if (value.length() > 0) message.tools.add(value);
                }
            }
            list.add(message);
        }
        return list;
    }

    static List<Step> parseSteps(JSONObject root) {
        List<Step> list = new ArrayList<>();
        JSONArray array = root == null ? null : root.optJSONArray("steps");
        if (array == null) return list;
        for (int i = 0; i < array.length(); i += 1) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            Step step = new Step();
            step.kind = text(item, "kind");
            if (step.kind.length() == 0) step.kind = "message";
            step.role = text(item, "role");
            step.text = text(item, "text");
            step.thinking = text(item, "thinking");
            step.name = text(item, "name");
            step.label = text(item, "label");
            step.detail = text(item, "detail");
            step.status = text(item, "status");
            step.result = text(item, "result");
            step.isError = item.optBoolean("isError", false);
            step.seq = item.optInt("seq", 0);
            JSONArray tools = item.optJSONArray("tools");
            if (tools != null) {
                for (int j = 0; j < tools.length(); j += 1) {
                    String value = text(tools, j);
                    if (value.length() > 0) step.tools.add(value);
                }
            }
            JSONArray files = item.optJSONArray("files");
            if (files != null) {
                for (int j = 0; j < files.length(); j += 1) {
                    String value = text(files, j);
                    if (value.length() > 0) step.files.add(value);
                }
            }
            list.add(step);
        }
        return list;
    }

    static Ask parseQuestion(JSONObject item, String sessionId, String sessionTitle) {
        if (item == null) return null;
        Ask ask = new Ask();
        ask.sessionId = sessionId;
        ask.sessionTitle = sessionTitle;
        ask.callId = text(item, "callId");
        ask.eventId = text(item, "eventId");
        ask.state = text(item, "state");
        JSONArray questions = item.optJSONArray("questions");
        if (questions != null) {
            for (int i = 0; i < questions.length(); i += 1) {
                JSONObject entry = questions.optJSONObject(i);
                if (entry == null) continue;
                Question question = new Question();
                question.id = text(entry, "id");
                question.header = text(entry, "header");
                question.question = text(entry, "question");
                question.multiSelect = entry.optBoolean("multiSelect", false);
                JSONArray options = entry.optJSONArray("options");
                if (options != null) {
                    for (int j = 0; j < options.length(); j += 1) {
                        JSONObject option = options.optJSONObject(j);
                        if (option == null) continue;
                        Option parsed = new Option();
                        parsed.label = text(option, "label");
                        parsed.description = text(option, "description");
                        question.options.add(parsed);
                    }
                }
                ask.questions.add(question);
            }
        }
        return ask;
    }

    static List<Ask> parsePending(JSONObject root) {
        List<Ask> list = new ArrayList<>();
        JSONArray array = root == null ? null : root.optJSONArray("pending");
        if (array == null) return list;
        for (int i = 0; i < array.length(); i += 1) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            Ask ask = parseQuestion(item, text(item, "sessionId"), text(item, "sessionTitle"));
            if (ask != null && !ask.questions.isEmpty()) list.add(ask);
        }
        return list;
    }

    /** 把扁平化的模型目录整理成「供应商 → 模型 → 强度」三级。 */
    static List<Provider> parseModels(JSONObject root) {
        List<Provider> list = new ArrayList<>();
        JSONArray array = root == null ? null : root.optJSONArray("providers");
        if (array == null) return list;
        for (int i = 0; i < array.length(); i += 1) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            Provider provider = new Provider();
            provider.id = text(item, "id");
            provider.name = text(item, "name");
            if (provider.name.length() == 0) provider.name = provider.id;
            JSONArray models = item.optJSONArray("models");
            if (models != null) {
                for (int j = 0; j < models.length(); j += 1) {
                    JSONObject entry = models.optJSONObject(j);
                    if (entry == null) continue;
                    ModelInfo model = new ModelInfo();
                    model.id = text(entry, "id");
                    model.name = text(entry, "name");
                    model.description = text(entry, "description");
                    if (model.name.length() == 0) model.name = model.id;
                    JSONArray efforts = entry.optJSONArray("efforts");
                    if (efforts != null) {
                        for (int k = 0; k < efforts.length(); k += 1) {
                            String effort = text(efforts, k);
                            if (effort.length() > 0) model.efforts.add(effort);
                        }
                    }
                    provider.models.add(model);
                }
            }
            list.add(provider);
        }
        return list;
    }

    /** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前。 */
    static String relativeTime(long epochMs) {
        if (epochMs <= 0) return "";
        long delta = System.currentTimeMillis() - epochMs;
        if (delta < 60_000) return "刚刚";
        if (delta < 3_600_000) return (delta / 60_000) + " 分钟前";
        if (delta < 86_400_000) return (delta / 3_600_000) + " 小时前";
        if (delta < 30L * 86_400_000L) return (delta / 86_400_000) + " 天前";
        return (delta / (30L * 86_400_000L)) + " 个月前";
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static String duration(long ms) {
        if (ms <= 0) return "";
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + " 秒";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " 分钟";
        return (minutes / 60) + " 小时 " + (minutes % 60) + " 分";
    }
}
