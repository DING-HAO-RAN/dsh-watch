package cn.dsh.watch;

import cn.dsh.remote.Bytes;
import cn.dsh.remote.RemoteAccount;
import cn.dsh.remote.RemoteConnection;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「中继接口」翻译成 DSH 原生 RPC，让界面代码不必区分设备类型。
 *
 * 为什么不直接在界面里调 DSH RPC：中继那套接口（/api/sessions、/api/session 等）
 * 已经把会话列表压成了手表能直接渲染的结构，界面三十余处调用都按那个形状写。
 * 这里复刻同一份整形逻辑，两种设备就共用全部界面代码。
 */
final class RemoteBridge {
    private final RemoteAccount account;
    private final String hostDeviceId;
    private final String hostPublicKey;
    private final String serverUrl;
    private final String deviceLabel;
    private final String clientVersion;

    private RemoteConnection connection;
    private final Object lock = new Object();
    private String lastError = "";

    /** 会话列表缓存：session/list 一次约 1MB，任何界面刷新都不该重复拉。 */
    private List<JSONObject> sessionCache;
    private long sessionCacheAt;
    private static final long SESSION_CACHE_MS = 6_000;

    RemoteBridge(RemoteAccount account, String hostDeviceId, String hostPublicKey,
                 String serverUrl, String deviceLabel, String clientVersion) {
        this.account = account;
        this.hostDeviceId = hostDeviceId;
        this.hostPublicKey = hostPublicKey;
        this.serverUrl = serverUrl;
        this.deviceLabel = deviceLabel;
        this.clientVersion = clientVersion;
    }

    boolean isAvailable() {
        return connection != null && connection.isReady();
    }

    String lastError() {
        return lastError;
    }

    /** 建立（或复用）连接。 */
    void connect() throws Exception {
        synchronized (lock) {
            if (connection != null && connection.isReady()) return;
            closeQuietly();
            String token = account.ensureAccessToken();
            connection = new RemoteConnection(serverUrl, account.deviceId(),
                    account.localPrivateKey(), account.localPublicKey(),
                    hostDeviceId, hostPublicKey, token, clientVersion,
                    new RemoteConnection.Callback() {
                        @Override public void onReady() { }
                        @Override public void onEvent(JSONObject message) { }
                        @Override public void onClosed(String reason, boolean retryable) {
                            lastError = reason;
                            sessionCache = null;
                        }
                        @Override public void onProgress(String stage) { }
                    });
            connection.connect(30_000L);
            lastError = "";
        }
    }

    void closeQuietly() {
        RemoteConnection current = connection;
        connection = null;
        sessionCache = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {
                // 关闭阶段的失败无需上报。
            }
        }
    }

    // ===== 接口分发 =====

    /**
     * 执行一次「中继接口等价」的调用。
     *
     * @param method GET 或 POST
     * @param path   中继的路径，例如 /api/sessions
     * @param query  查询串（已解码的键值对文本，可为空）
     * @param body   POST 的 JSON 体，可为 null
     */
    JSONObject request(String method, String path, String query, JSONObject body) throws Exception {
        ensureConnected();
        Map<String, String> params = parseQuery(query);
        String bodyText = body == null ? "" : body.toString();

        if (path.equals("/api/status")) return statusResponse();
        if (path.equals("/api/sessions")) return sessionsResponse();
        if (path.equals("/api/workspaces")) return workspacesResponse();
        if (path.equals("/api/session")) return sessionResponse(params);
        if (path.equals("/api/subagents")) return subagentsResponse(params);
        if (path.equals("/api/models")) return modelsResponse();
        if (path.equals("/api/send")) return sendResponse(body);
        if (path.equals("/api/cancel")) return cancelResponse(body);
        if (path.equals("/api/create")) return createResponse(body);
        if (path.equals("/api/answer")) return answerResponse(body);
        if (path.equals("/api/approvals")) return approvalsResponse();
        if (path.equals("/api/session/rename")) return renameResponse(body);
        if (path.equals("/api/session/queue")) return queueResponse(body);
        if (path.equals("/api/model")) return selectModelResponse(body);
        if (path.equals("/api/files")) return filesResponse(params);
        if (path.equals("/api/file")) return fileResponse(params);
        if (path.equals("/api/terminals")) return terminalsResponse(params);
        throw new RemoteConnection.RemoteException("NOT_SUPPORTED",
                "远程通道暂不支持该接口：" + path + (bodyText.isEmpty() ? "" : ""));
    }

    private void ensureConnected() throws Exception {
        if (connection == null || !connection.isReady()) connect();
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new HashMap<String, String>();
        if (query == null || query.isEmpty()) return out;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.put(decode(pair), "");
            } else {
                out.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return out;
    }

    private static String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Exception error) {
            return value;
        }
    }

    // ===== 各接口实现 =====

    private JSONObject statusResponse() throws Exception {
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("service", "dsh-remote");
        out.put("name", deviceLabel);
        out.put("version", 2);
        out.put("needsPairing", false);
        out.put("remote", true);
        out.put("uptimeMs", 0L);
        return out;
    }

    private JSONObject sessionsResponse() throws Exception {
        List<JSONObject> all = sessionIndex();
        JSONArray sessions = new JSONArray();
        int limit = 0;
        for (JSONObject item : all) {
            if (item.optBoolean("subagent", false)) continue;
            sessions.put(item);
            limit++;
            if (limit >= 200) break;
        }
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("sessions", sessions);
        out.put("projects", groupByProject(sessions));
        out.put("pending", new JSONArray());
        return out;
    }

    private JSONObject workspacesResponse() throws Exception {
        List<JSONObject> all = sessionIndex();
        Map<String, JSONObject> groups = new java.util.LinkedHashMap<String, JSONObject>();
        for (JSONObject session : all) {
            if (session.optBoolean("subagent", false)) continue;
            String path = session.optString("projectPath", "");
            JSONObject group = groups.get(path);
            if (group == null) {
                group = new JSONObject();
                group.put("path", path);
                group.put("name", path.isEmpty() ? "未分组" : session.optString("project", ""));
                group.put("sessions", 0);
                group.put("running", 0);
                group.put("waiting", 0);
                group.put("updatedAt", 0L);
                groups.put(path, group);
            }
            group.put("sessions", group.optInt("sessions") + 1);
            if (session.optBoolean("running")) group.put("running", group.optInt("running") + 1);
            if (session.optBoolean("waiting")) group.put("waiting", group.optInt("waiting") + 1);
            if (session.optLong("updatedAt") > group.optLong("updatedAt")) {
                group.put("updatedAt", session.optLong("updatedAt"));
            }
        }
        List<JSONObject> list = new ArrayList<JSONObject>(groups.values());
        java.util.Collections.sort(list, new java.util.Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                return Long.compare(b.optLong("updatedAt"), a.optLong("updatedAt"));
            }
        });
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("workspaces", new JSONArray(list));
        return out;
    }

    private JSONObject subagentsResponse(Map<String, String> params) throws Exception {
        String parentId = value(params, "id");
        List<JSONObject> all = sessionIndex();
        JSONArray list = new JSONArray();
        for (JSONObject item : all) {
            if (!item.optBoolean("subagent", false)) continue;
            if (!parentId.equals(item.optString("parentId", ""))) continue;
            JSONObject entry = new JSONObject();
            entry.put("id", item.optString("id", ""));
            String label = item.optString("subagentLabel", "");
            entry.put("label", label.isEmpty() ? "子任务" : label);
            entry.put("title", item.optString("title", ""));
            entry.put("mode", item.optString("subagentMode", ""));
            entry.put("state", stateOf(item));
            entry.put("running", item.optBoolean("running"));
            entry.put("settledMs", item.optLong("settledMs"));
            entry.put("active", item.optBoolean("active"));
            entry.put("completed", item.optBoolean("completed"));
            entry.put("updatedAt", item.optLong("updatedAt"));
            entry.put("turns", item.optInt("turns"));
            entry.put("steps", item.optInt("steps"));
            list.put(entry);
        }
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("parentId", parentId);
        out.put("subagents", list);
        return out;
    }

    private JSONObject sessionResponse(Map<String, String> params) throws Exception {
        String sessionId = value(params, "id");
        if (sessionId.isEmpty()) throw new RemoteConnection.RemoteException("BAD_REQUEST", "缺少 id 参数");
        String throughText = value(params, "through");
        Long through = null;
        if (!throughText.isEmpty()) {
            try {
                through = Long.valueOf(Long.parseLong(throughText));
            } catch (NumberFormatException ignored) {
                // 非法翻页游标当作最新一页。
            }
        }
        JSONObject summary = findSession(sessionId);
        boolean running = summary != null && summary.optBoolean("running");
        String parentId = summary == null ? "" : summary.optString("parentId", "");
        String mode = summary == null ? "" : summary.optString("subagentMode", "");

        JSONObject args = new JSONObject();
        JSONObject request = new JSONObject();
        JSONObject address = new JSONObject();
        if (parentId.isEmpty()) {
            address.put("kind", "session");
            address.put("sessionId", sessionId);
        } else {
            // 子代理会话必须用「父地址 + 子地址」读，普通 session 地址会被 Host 拒绝。
            address.put("kind", "subagent");
            address.put("parentSessionId", parentId);
            address.put("childSessionId", sessionId);
            address.put("mode", mode.isEmpty() ? "continuable" : mode);
        }
        request.put("address", address);
        if (through != null) request.put("throughSeq", through.longValue());
        request.put("maxMessages", 300);
        args.put("request", request);
        JSONObject result = harnessCall("session/page", args, 60_000L);
        JSONObject value = result.optJSONObject("value");
        if (value == null) value = new JSONObject();

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("id", sessionId);
        out.put("running", running);
        out.put("title", summary == null ? "" : summary.optString("title", ""));
        out.put("steps", normalizeSteps(value.optJSONArray("records")));
        JSONObject usage = normalizeUsage(value.optJSONArray("records"));
        out.put("usage", usage);
        out.put("question", questionOf(value));
        out.put("files", filesOf(value.optJSONArray("records")));
        out.put("through", value.optLong("through", 0L));
        out.put("hasMore", value.optBoolean("hasMore", false));
        return out;
    }

    private JSONObject modelsResponse() throws Exception {
        JSONObject args = new JSONObject();
        JSONObject result = harnessCall("session/modelCatalog", args, 30_000L);
        JSONObject value = result.optJSONObject("value");
        JSONObject out = new JSONObject();
        out.put("ok", true);
        JSONArray groups = new JSONArray();
        if (value != null) {
            // Host 返回的字段名是 groups，与中继整形后的结构一致。
            JSONArray raw = value.optJSONArray("groups");
            if (raw == null) raw = value.optJSONArray("providers");
            JSONArray routable = value.optJSONArray("routableProviders");
            java.util.Set<String> allowed = new java.util.HashSet<String>();
            if (routable != null) {
                for (int i = 0; i < routable.length(); i++) allowed.add(String.valueOf(routable.opt(i)));
            }
            if (raw != null) {
                for (int i = 0; i < raw.length(); i++) {
                    JSONObject group = raw.optJSONObject(i);
                    if (group == null) continue;
                    String provider = group.optString("id", "");
                    if (provider.isEmpty()) continue;
                    if (!allowed.isEmpty() && !allowed.contains(provider)) continue;
                    JSONArray models = new JSONArray();
                    JSONArray rawModels = group.optJSONArray("models");
                    if (rawModels != null) {
                        for (int j = 0; j < rawModels.length(); j++) {
                            JSONObject model = rawModels.optJSONObject(j);
                            if (model == null) continue;
                            String id = model.optString("id", "");
                            if (id.isEmpty()) continue;
                            JSONObject item = new JSONObject();
                            item.put("id", id);
                            item.put("name", model.optString("name", "").isEmpty()
                                    ? id : model.optString("name", ""));
                            JSONArray efforts = new JSONArray();
                            JSONObject reasoning = model.optJSONObject("reasoning");
                            JSONArray rawEfforts = reasoning == null ? null : reasoning.optJSONArray("efforts");
                            if (rawEfforts != null) {
                                for (int k = 0; k < rawEfforts.length(); k++) {
                                    JSONObject effort = rawEfforts.optJSONObject(k);
                                    String effortId = effort == null
                                            ? String.valueOf(rawEfforts.opt(k)) : effort.optString("id", "");
                                    if (!effortId.isEmpty()) efforts.put(effortId);
                                }
                            }
                            item.put("efforts", efforts);
                            models.put(item);
                        }
                    }
                    if (models.length() == 0) continue;
                    JSONObject entry = new JSONObject();
                    entry.put("provider", provider);
                    entry.put("name", group.optString("name", "").isEmpty()
                            ? provider : group.optString("name", ""));
                    entry.put("models", models);
                    groups.put(entry);
                }
            }
        }
        out.put("groups", groups);
        JSONObject defaults = new JSONObject();
        if (value != null) {
            JSONObject raw = value.optJSONObject("default");
            defaults.put("provider", raw == null ? "" : raw.optString("provider", ""));
            defaults.put("model", raw == null ? "" : raw.optString("model", ""));
            defaults.put("effort", raw == null ? "" : raw.optString("reasoningEffort", ""));
        }
        out.put("default", defaults);
        return out;
    }

    private JSONObject sendResponse(JSONObject body) throws Exception {
        String sessionId = body.optString("sessionId", "");
        String text = body.optString("text", "");
        if (sessionId.isEmpty() || text.trim().isEmpty()) {
            throw new RemoteConnection.RemoteException("BAD_REQUEST", "缺少 sessionId 或 text");
        }
        JSONObject args = new JSONObject();
        JSONObject request = new JSONObject();
        request.put("requestId", java.util.UUID.randomUUID().toString());
        request.put("sessionId", sessionId);
        request.put("mode", "steer".equals(body.optString("mode", "")) ? "steer" : "queue");
        JSONArray content = new JSONArray();
        JSONObject block = new JSONObject();
        block.put("type", "text");
        block.put("text", text);
        content.put(block);
        request.put("content", content);
        request.put("clientTimeZone", "Asia/Shanghai");
        args.put("request", request);
        harnessCall("session/prompt", args, 60_000L);
        sessionCache = null;
        JSONObject out = new JSONObject();
        out.put("ok", true);
        return out;
    }

    private JSONObject cancelResponse(JSONObject body) throws Exception {
        String sessionId = body.optString("sessionId", "");
        JSONObject args = new JSONObject();
        JSONObject request = new JSONObject();
        request.put("sessionId", sessionId);
        args.put("request", request);
        harnessCall("session/cancel", args, 30_000L);
        sessionCache = null;
        JSONObject out = new JSONObject();
        out.put("ok", true);
        return out;
    }

    private JSONObject createResponse(JSONObject body) throws Exception {
        JSONObject args = new JSONObject();
        JSONObject request = new JSONObject();
        if (body.has("cwd") && !body.optString("cwd", "").isEmpty()) {
            request.put("cwd", body.optString("cwd", ""));
        }
        if (body.has("workspaceId") && !body.optString("workspaceId", "").isEmpty()) {
            request.put("workspaceId", body.optString("workspaceId", ""));
        }
        args.put("request", request);
        JSONObject result = harnessCall("session/create", args, 30_000L);
        JSONObject value = result.optJSONObject("value");
        sessionCache = null;
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("sessionId", value == null ? "" : value.optString("sessionId", ""));
        return out;
    }

    private JSONObject answerResponse(JSONObject body) throws Exception {
        String sessionId = body.optString("sessionId", "");
        String callId = body.optString("callId", "");
        JSONArray answers = body.optJSONArray("answers");
        if (answers == null) answers = new JSONArray();
        if (sessionId.isEmpty() || answers.length() == 0) {
            throw new RemoteConnection.RemoteException("BAD_REQUEST", "缺少 sessionId 或 answers");
        }
        JSONObject args = new JSONObject();
        args.put("agentId", sessionId);
        args.put("callId", callId);
        JSONObject answer = new JSONObject();
        answer.put("answers", answers);
        args.put("answer", answer);
        JSONObject result = harnessCall("userQuestions/answer", args, 30_000L);
        sessionCache = null;
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("via", "rpc");
        out.put("accepted", result.optBoolean("ok", true));
        return out;
    }

    private JSONObject approvalsResponse() throws Exception {
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("approvals", new JSONArray());
        return out;
    }

    private JSONObject renameResponse(JSONObject body) throws Exception {
        String sessionId = body.optString("id", "");
        String title = body.optString("title", "").trim();
        if (sessionId.isEmpty() || title.isEmpty()) {
            throw new RemoteConnection.RemoteException("BAD_REQUEST", "缺少 id 或 title");
        }
        JSONObject args = new JSONObject();
        JSONObject request = new JSONObject();
        request.put("sessionId", sessionId);
        request.put("title", title);
        args.put("request", request);
        JSONObject result = harnessCall("session/rename", args, 30_000L);
        sessionCache = null;
        JSONObject value = result.optJSONObject("value");
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("title", value == null ? title : value.optString("title", title));
        return out;
    }

    private JSONObject queueResponse(JSONObject body) throws Exception {
        String sessionId = body.optString("id", "");
        String itemId = body.optString("itemId", "");
        if (sessionId.isEmpty()) throw new RemoteConnection.RemoteException("BAD_REQUEST", "缺少 id");
        if (itemId.isEmpty()) throw new RemoteConnection.RemoteException("BAD_REQUEST", "缺少 itemId");
        String kind = body.optString("kind", "remove");
        JSONObject action = new JSONObject();
        if ("remove".equals(kind)) {
            action.put("kind", "remove");
        } else if ("steer".equals(kind)) {
            action.put("kind", "steer");
        } else if ("edit".equals(kind)) {
            action.put("kind", "edit");
            JSONArray content = new JSONArray();
            JSONObject block = new JSONObject();
            block.put("type", "text");
            block.put("text", body.optString("text", ""));
            content.put(block);
            action.put("content", content);
        } else {
            throw new RemoteConnection.RemoteException("BAD_REQUEST", "未知的队列操作：" + kind);
        }
        JSONObject args = new JSONObject();
        JSONObject request = new JSONObject();
        request.put("sessionId", sessionId);
        request.put("itemId", itemId);
        request.put("action", action);
        args.put("request", request);
        JSONObject result = harnessCall("session/updateQueue", args, 30_000L);
        sessionCache = null;
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("value", result.opt("value"));
        return out;
    }

    private JSONObject selectModelResponse(JSONObject body) throws Exception {
        String sessionId = body.optString("sessionId", "");
        String provider = body.optString("provider", "");
        String model = body.optString("model", "");
        if (sessionId.isEmpty() || provider.isEmpty() || model.isEmpty()) {
            throw new RemoteConnection.RemoteException("BAD_REQUEST", "缺少 sessionId、provider 或 model");
        }
        JSONObject args = new JSONObject();
        JSONObject request = new JSONObject();
        request.put("sessionId", sessionId);
        request.put("provider", provider);
        request.put("model", model);
        String effort = body.optString("effort", "");
        if (!effort.isEmpty()) request.put("reasoningEffort", effort);
        args.put("request", request);
        JSONObject result = harnessCall("session/selectModel", args, 30_000L);
        JSONObject selected = result.optJSONObject("value");
        if (selected != null) selected = selected.optJSONObject("selected");
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("provider", selected == null ? provider : selected.optString("provider", provider));
        out.put("model", selected == null ? model : selected.optString("model", model));
        out.put("effort", selected == null ? "" : selected.optString("reasoningEffort", ""));
        return out;
    }

    /**
     * 文件浏览走 workspaceFiles/* 系列 RPC。
     * 安全边界在 Host 侧：它按会话工作目录限定，客户端不需要再做白名单。
     */
    private JSONObject filesResponse(Map<String, String> params) throws Exception {
        String sessionId = value(params, "id");
        String relative = value(params, "path");
        if (sessionId.isEmpty()) throw new RemoteConnection.RemoteException("BAD_REQUEST", "缺少 id 参数");
        JSONObject args = new JSONObject();
        args.put("sessionId", sessionId);
        args.put("path", relative);
        JSONObject result = harnessCall("workspaceFiles/list", args, 30_000L);
        JSONObject value = result.optJSONObject("value");
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("root", value == null ? "" : value.optString("root", ""));
        out.put("path", relative);
        out.put("parent", relative.isEmpty() ? "" : parentOf(relative));
        out.put("entries", value == null ? new JSONArray() : value.optJSONArray("entries"));
        out.put("truncated", value != null && value.optBoolean("truncated", false));
        return out;
    }

    private JSONObject fileResponse(Map<String, String> params) throws Exception {
        String sessionId = value(params, "id");
        String relative = value(params, "path");
        if (sessionId.isEmpty() || relative.isEmpty()) {
            throw new RemoteConnection.RemoteException("BAD_REQUEST", "缺少 id 或 path 参数");
        }
        JSONObject args = new JSONObject();
        args.put("sessionId", sessionId);
        args.put("path", relative);
        JSONObject result = harnessCall("workspaceFiles/read", args, 60_000L);
        JSONObject value = result.optJSONObject("value");
        JSONObject out = new JSONObject();
        out.put("ok", true);
        if (value != null) {
            for (String key : new String[] {"name", "path", "kind", "language", "size",
                    "mtime", "truncated", "text", "base64"}) {
                if (value.has(key)) out.put(key, value.opt(key));
            }
        }
        return out;
    }

    private JSONObject terminalsResponse(Map<String, String> params) throws Exception {
        // 远程终端依赖 Host 的 subprocess 服务；不可用时返回空列表而不是报错。
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("terminals", new JSONArray());
        return out;
    }

    // ===== DSH RPC 封装 =====

    /** 调一个 DSH 端点，返回 {ok, value} 或 {ok:false, error}。 */
    private JSONObject harnessCall(String endpoint, JSONObject args, long timeoutMs) throws Exception {
        // 交给 RemoteConnection：它会在大响应时自动改用分块传输。
        return connection.harnessCall(endpoint, args, timeoutMs);
    }

    // ===== 会话列表整形（与中继 state.mjs 保持一致） =====

    private List<JSONObject> sessionIndex() throws Exception {
        long now = System.currentTimeMillis();
        List<JSONObject> cached = sessionCache;
        if (cached != null && now - sessionCacheAt < SESSION_CACHE_MS) return cached;

        JSONObject args = new JSONObject();
        args.put("_request", new JSONObject());
        JSONObject result = harnessCall("session/list", args, 60_000L);
        JSONObject value = result.optJSONObject("value");
        List<JSONObject> list = new ArrayList<JSONObject>();
        JSONArray items = value == null ? null : value.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                list.add(normalizeSession(item));
            }
        }
        java.util.Collections.sort(list, new java.util.Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                return Long.compare(b.optLong("updatedAt"), a.optLong("updatedAt"));
            }
        });
        sessionCache = list;
        sessionCacheAt = now;
        return list;
    }

    private JSONObject findSession(String sessionId) throws Exception {
        for (JSONObject item : sessionIndex()) {
            if (sessionId.equals(item.optString("id", ""))) return item;
        }
        return null;
    }

    private JSONObject normalizeSession(JSONObject item) {
        JSONObject values = item.optJSONObject("projections");
        if (values != null) values = values.optJSONObject("values");
        if (values == null) values = new JSONObject();
        String origin = item.optString("origin", "");
        boolean isSubagent = "subagent".equals(origin);
        String cwd = item.optString("cwd", "");
        String title = values.optString("title", "");
        if (title.trim().isEmpty()) title = cwd.isEmpty() ? "未命名会话" : baseName(cwd);

        JSONObject model = null;
        JSONObject selection = values.optJSONObject("modelSelection");
        if (selection != null) {
            model = selection.optJSONObject("next");
            if (model == null) model = selection.optJSONObject("lastUsed");
        }
        JSONObject identity = values.optJSONObject("subagent");
        JSONObject timing = values.optJSONObject("subagentTiming");
        if (timing == null) timing = new JSONObject();
        JSONObject stats = values.optJSONObject("sessionStats");
        JSONObject inbox = values.optJSONObject("inbox");

        JSONObject out = new JSONObject();
        try {
            out.put("id", item.optString("sessionId", ""));
            out.put("title", title.trim());
            out.put("running", item.optBoolean("running", false));
            out.put("blank", item.optBoolean("blank", false));
            out.put("waiting", questionOfSession(item) != null);
            out.put("updatedAt", item.optLong("updatedAt", 0L));
            JSONObject projections = item.optJSONObject("projections");
            out.put("seq", projections == null ? 0 : projections.optLong("asOfSeq", 0L));
            out.put("subagent", isSubagent);
            out.put("parentId", item.optString("parentSessionId", ""));
            out.put("subagentLabel", identity == null ? "" : identity.optString("label", ""));
            out.put("subagentMode", identity == null ? "" : identity.optString("mode", ""));
            out.put("agentAvailable", item.optBoolean("agentAvailable", false));
            out.put("settledMs", timing.optLong("settledMs", 0L));
            out.put("active", timing.has("active"));
            out.put("completed", timing.optBoolean("lastTurnCompleted", false));
            out.put("turns", stats == null ? 0 : stats.optInt("turns", 0));
            out.put("steps", stats == null ? 0 : stats.optInt("steps", 0));
            out.put("cwd", cwd);
            out.put("project", projectNameOf(cwd));
            out.put("projectPath", cwd);
            out.put("provider", model == null ? "" : model.optString("provider", ""));
            out.put("model", model == null ? "" : model.optString("model", ""));
            out.put("effort", model == null ? "" : model.optString("reasoningEffort", ""));
            // 排队消息在 projections.inbox 里：next-step 是插话，next-turn 是下一轮。
            out.put("queue", queueOf(inbox));
        } catch (Exception ignored) {
            // 字段都是基本类型，不会失败。
        }
        return out;
    }

    /** 从 projections.inbox 取出排队中的消息（next-step 与 next-turn 两段）。 */
    private static JSONArray queueOf(JSONObject inbox) {
        JSONArray out = new JSONArray();
        if (inbox == null) return out;
        for (String bucket : new String[] {"next-step", "next-turn"}) {
            JSONArray items = inbox.optJSONArray(bucket);
            if (items == null) continue;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                JSONObject entry = new JSONObject();
                try {
                    entry.put("id", item.optString("id", ""));
                    entry.put("text", textOfContent(item.optJSONArray("content")));
                    entry.put("bucket", bucket);
                } catch (Exception ignored) { }
                out.put(entry);
            }
        }
        return out;
    }

    private static String stateOf(JSONObject session) {
        if (session.optBoolean("running")) return "running";
        if (session.optBoolean("completed")) return "done";
        if (session.optBoolean("agentAvailable")) return "idle";
        return "done";
    }

    private static JSONObject questionOfSession(JSONObject item) {
        JSONObject values = item.optJSONObject("projections");
        if (values != null) values = values.optJSONObject("values");
        if (values == null) return null;
        JSONObject questions = values.optJSONObject("userQuestions");
        if (questions == null) return null;
        JSONArray active = questions.optJSONArray("active");
        if (active != null && active.length() > 0) return active.optJSONObject(0);
        return null;
    }

    private static JSONArray groupByProject(JSONArray sessions) {
        Map<String, JSONObject> groups = new java.util.LinkedHashMap<String, JSONObject>();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session == null) continue;
            String name = session.optString("project", "");
            String key = name.isEmpty() ? "未分组" : name;
            JSONObject group = groups.get(key);
            if (group == null) {
                group = new JSONObject();
                try {
                    group.put("project", key);
                    group.put("path", session.optString("projectPath", ""));
                    group.put("sessions", new JSONArray());
                    group.put("running", 0);
                    group.put("waiting", 0);
                    group.put("updatedAt", 0L);
                } catch (Exception ignored) { }
                groups.put(key, group);
            }
            try {
                group.optJSONArray("sessions").put(session);
                if (session.optBoolean("running")) group.put("running", group.optInt("running") + 1);
                if (session.optBoolean("waiting")) group.put("waiting", group.optInt("waiting") + 1);
                if (session.optLong("updatedAt") > group.optLong("updatedAt")) {
                    group.put("updatedAt", session.optLong("updatedAt"));
                }
            } catch (Exception ignored) { }
        }
        List<JSONObject> list = new ArrayList<JSONObject>(groups.values());
        java.util.Collections.sort(list, new java.util.Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                return Long.compare(b.optLong("updatedAt"), a.optLong("updatedAt"));
            }
        });
        return new JSONArray(list);
    }

    // ===== 步骤整形（与中继 normalizeSteps 一致） =====

    private static JSONArray normalizeSteps(JSONArray records) throws Exception {
        JSONArray steps = new JSONArray();
        Map<String, JSONObject> pendingTools = new HashMap<String, JSONObject>();
        JSONObject pendingAssistant = null;
        if (records == null) return steps;

        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record == null) continue;
            JSONObject event = record.optJSONObject("event");
            if (event == null) continue;
            long seq = event.optLong("seq", 0L);
            long time = event.optLong("time", 0L);
            JSONObject data = event.optJSONObject("data");
            if (data == null) data = new JSONObject();
            String type = event.optString("type", "");

            if ("user/message".equals(type)) {
                JSONObject message = data.optJSONObject("message");
                if (message == null) message = data;
                if (!isHumanMessage(data)) continue;
                String text = textOfContent(message.optJSONArray("content"));
                if (text.isEmpty()) continue;
                if (pendingAssistant != null) {
                    pushAssistant(steps, pendingAssistant);
                    pendingAssistant = null;
                }
                steps.put(step("message", seq, time, "user", clip(text, 1200)));
                continue;
            }

            if ("assistant/message".equals(type)) {
                JSONObject message = data.optJSONObject("message");
                if (message == null) message = new JSONObject();
                JSONArray content = message.optJSONArray("content");
                String text = textOfContent(content);
                String thinking = thinkingOfContent(content);
                if (pendingAssistant == null) {
                    pendingAssistant = newAssistant(seq, time);
                }
                if (!text.isEmpty()) {
                    String existing = pendingAssistant.optString("text", "");
                    pendingAssistant.put("text", existing.isEmpty() ? text : existing + "\n\n" + text);
                }
                if (!thinking.isEmpty()) {
                    String existing = pendingAssistant.optString("thinking", "");
                    pendingAssistant.put("thinking", existing.isEmpty() ? thinking : existing + "\n\n" + thinking);
                }
                pendingAssistant.put("seq", seq);
                pendingAssistant.put("time", time);

                if (content != null) {
                    for (int c = 0; c < content.length(); c++) {
                        JSONObject block = content.optJSONObject(c);
                        if (block == null || !"tool-call".equals(block.optString("type", ""))) continue;
                        pushAssistant(steps, pendingAssistant);
                        pendingAssistant = newAssistant(seq, time);
                        String name = block.optString("name", "工具");
                        String label = toolLabel(name);
                        pendingAssistant.optJSONArray("tools").put(label);
                        JSONObject tool = new JSONObject();
                        try {
                            tool.put("kind", "tool");
                            tool.put("seq", seq);
                            tool.put("time", time);
                            tool.put("name", name);
                            tool.put("label", label);
                            tool.put("detail", describeToolCall(name, block.opt("arguments")));
                            tool.put("status", "running");
                            tool.put("result", "");
                            tool.put("files", new JSONArray());
                            tool.put("isError", false);
                        } catch (Exception ignored) { }
                        steps.put(tool);
                        String callId = block.optString("id", "");
                        if (!callId.isEmpty()) pendingTools.put(callId, tool);
                    }
                }
                continue;
            }

            if ("tool/result".equals(type)) {
                JSONObject message = data.optJSONObject("message");
                if (message == null) message = new JSONObject();
                String callId = message.optString("toolCallId", "");
                if (pendingAssistant != null) {
                    pushAssistant(steps, pendingAssistant);
                    pendingAssistant = null;
                }
                JSONObject tool = callId.isEmpty() ? null : pendingTools.get(callId);
                String text = textOfContent(message.optJSONArray("content"));
                JSONArray files = filesOfMeta(data.optJSONObject("meta"));
                boolean isError = message.optBoolean("isError", false);
                if (tool == null) {
                    JSONObject orphan = new JSONObject();
                    try {
                        orphan.put("kind", "tool");
                        orphan.put("seq", seq);
                        orphan.put("time", time);
                        orphan.put("name", "");
                        orphan.put("label", "工具结果");
                        orphan.put("detail", "");
                        orphan.put("status", isError ? "error" : "done");
                        orphan.put("result", clip(text, 1200));
                        orphan.put("files", files);
                        orphan.put("isError", isError);
                    } catch (Exception ignored) { }
                    steps.put(orphan);
                    continue;
                }
                try {
                    tool.put("status", isError ? "error" : "done");
                    tool.put("result", clip(text, 1200));
                    tool.put("isError", isError);
                    tool.put("files", files);
                    tool.put("resultSeq", seq);
                } catch (Exception ignored) { }
                continue;
            }

            if ("turn/end".equals(type)) {
                if (pendingAssistant != null) {
                    pushAssistant(steps, pendingAssistant);
                    pendingAssistant = null;
                }
            }
        }
        if (pendingAssistant != null) pushAssistant(steps, pendingAssistant);
        return steps;
    }

    private static JSONObject newAssistant(long seq, long time) {
        JSONObject step = new JSONObject();
        try {
            step.put("kind", "message");
            step.put("seq", seq);
            step.put("time", time);
            step.put("role", "assistant");
            step.put("text", "");
            step.put("thinking", "");
            step.put("tools", new JSONArray());
        } catch (Exception ignored) { }
        return step;
    }

    private static void pushAssistant(JSONArray steps, JSONObject assistant) {
        String text = assistant.optString("text", "");
        String thinking = assistant.optString("thinking", "");
        int tools = assistant.optJSONArray("tools") == null ? 0 : assistant.optJSONArray("tools").length();
        if (text.isEmpty() && thinking.isEmpty() && tools == 0) return;
        steps.put(assistant);
    }

    private static JSONObject step(String kind, long seq, long time, String role, String text) {
        JSONObject step = new JSONObject();
        try {
            step.put("kind", kind);
            step.put("seq", seq);
            step.put("time", time);
            step.put("role", role);
            step.put("text", text);
            step.put("tools", new JSONArray());
        } catch (Exception ignored) { }
        return step;
    }

    /** 只保留真人消息：DSH 会注入大量系统 user/message。 */
    private static boolean isHumanMessage(JSONObject data) {
        JSONObject source = data.optJSONObject("source");
        if (source == null) return true;
        String kind = source.optString("kind", "");
        return "user".equals(kind) || "user-question-reply".equals(kind);
    }

    private static String textOfContent(JSONArray content) {
        if (content == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block == null) continue;
            if (!"text".equals(block.optString("type", ""))) continue;
            String text = block.optString("text", "");
            if (text.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(text);
        }
        return out.toString();
    }

    private static String thinkingOfContent(JSONArray content) {
        if (content == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block == null) continue;
            if (!"reasoning".equals(block.optString("type", ""))) continue;
            String text = block.optString("text", "");
            if (text.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(text);
        }
        return out.toString();
    }

    private static JSONArray filesOfMeta(JSONObject meta) {
        JSONArray out = new JSONArray();
        if (meta == null) return out;
        JSONArray diffs = meta.optJSONArray("diffs");
        if (diffs == null) return out;
        for (int i = 0; i < diffs.length(); i++) {
            JSONObject diff = diffs.optJSONObject(i);
            if (diff == null) continue;
            String path = diff.optString("path", "");
            if (!path.isEmpty()) out.put(path);
        }
        return out;
    }

    private static JSONArray filesOf(JSONArray records) {
        // 会话里出现过的文件汇总：中继的 files 用于「查看文件」白名单。
        JSONArray out = new JSONArray();
        if (records == null) return out;
        java.util.Set<String> seen = new java.util.LinkedHashSet<String>();
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record == null) continue;
            JSONObject event = record.optJSONObject("event");
            if (event == null) continue;
            JSONObject data = event.optJSONObject("data");
            if (data == null) continue;
            JSONArray diffs = filesOfMeta(data.optJSONObject("meta"));
            for (int j = 0; j < diffs.length(); j++) {
                String path = diffs.optString(j, "");
                if (path.isEmpty() || !seen.add(path)) continue;
                JSONObject entry = new JSONObject();
                try {
                    entry.put("path", path);
                    entry.put("name", baseName(path));
                    entry.put("kind", kindOf(path));
                    entry.put("language", languageOf(path));
                } catch (Exception ignored) { }
                out.put(entry);
            }
        }
        return out;
    }

    private static JSONObject questionOf(JSONObject page) {
        JSONArray records = page.optJSONArray("records");
        if (records == null) return null;
        for (int i = records.length() - 1; i >= 0; i--) {
            JSONObject record = records.optJSONObject(i);
            if (record == null) continue;
            JSONObject event = record.optJSONObject("event");
            if (event == null) continue;
            JSONObject data = event.optJSONObject("data");
            if (data == null) continue;
            JSONObject question = data.optJSONObject("question");
            if (question != null) return question;
        }
        return null;
    }

    private static JSONObject normalizeUsage(JSONArray records) {
        // 用量汇总交给 Host 的 projections；这里返回空对象让界面走兜底显示。
        return new JSONObject();
    }

    // ===== 小工具 =====

    private static String value(Map<String, String> params, String key) {
        String value = params.get(key);
        return value == null ? "" : value;
    }

    private static String clip(String text, int limit) {
        if (text == null) return "";
        String value = text.trim();
        if (value.length() <= limit) return value;
        return value.substring(0, limit) + "…（已省略）";
    }

    private static String baseName(String path) {
        if (path == null || path.isEmpty()) return "";
        String normalized = path.replace('\\', '/');
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    private static String parentOf(String relative) {
        String normalized = relative.replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index < 0 ? "" : normalized.substring(0, index);
    }

    private static String projectNameOf(String cwd) {
        if (cwd == null || cwd.isEmpty()) return "";
        return baseName(cwd);
    }

    private static String kindOf(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")) {
            return "image";
        }
        return "text";
    }

    private static String languageOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "";
        String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        if ("java".equals(ext)) return "java";
        if ("kt".equals(ext)) return "kotlin";
        if ("js".equals(ext) || "mjs".equals(ext) || "cjs".equals(ext)) return "javascript";
        if ("ts".equals(ext)) return "typescript";
        if ("py".equals(ext)) return "python";
        if ("cs".equals(ext)) return "csharp";
        if ("json".equals(ext)) return "json";
        if ("md".equals(ext)) return "markdown";
        if ("xml".equals(ext)) return "xml";
        if ("html".equals(ext)) return "html";
        if ("css".equals(ext)) return "css";
        if ("sh".equals(ext) || "bash".equals(ext)) return "shell";
        if ("yml".equals(ext) || "yaml".equals(ext)) return "yaml";
        if ("ps1".equals(ext)) return "powershell";
        return ext;
    }

    private static String toolLabel(String name) {
        if ("Read".equals(name)) return "读取";
        if ("Write".equals(name)) return "写入";
        if ("Edit".equals(name)) return "修改";
        if ("Bash".equals(name)) return "命令";
        if ("Grep".equals(name)) return "搜索";
        if ("Glob".equals(name)) return "查找文件";
        if ("WebSearch".equals(name)) return "联网搜索";
        if ("WebFetch".equals(name)) return "打开网页";
        if ("Task".equals(name) || "Agent".equals(name)) return "子任务";
        return name;
    }

    /** 从工具参数里挑一句最有信息量的说明。 */
    private static String describeToolCall(String name, Object rawArguments) {
        JSONObject args = null;
        if (rawArguments instanceof JSONObject) {
            args = (JSONObject) rawArguments;
        } else if (rawArguments instanceof String) {
            try {
                args = new JSONObject((String) rawArguments);
            } catch (Exception ignored) {
                return clipOneLine((String) rawArguments, 120);
            }
        }
        if (args == null) return "";
        String[] order = {"description", "file_path", "path", "notebook_path",
                "command", "pattern", "query", "url", "prompt", "name", "subagent_type", "skill"};
        for (String key : order) {
            String value = args.optString(key, "");
            if (value == null || value.trim().isEmpty()) continue;
            return clipOneLine(value, "command".equals(key) ? 160 : 120);
        }
        return clipOneLine(args.toString(), 100);
    }

    private static String clipOneLine(String text, int limit) {
        String value = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (value.length() <= limit) return value;
        return value.substring(0, limit) + "…";
    }
}
