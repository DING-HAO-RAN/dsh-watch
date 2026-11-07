package cn.dsh.remote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一条已认证的远程连接：Control 通道 + Noise 安全通道 + RPC。
 *
 * 连接建立顺序（缺一不可）：
 *   hello -> hello.ack -> connect.request -> connect.accepted
 *   -> secure.handshake(step 1/2) -> Noise 完成 -> 业务 RPC。
 *
 * Control 帧是 Server 可读的 JSON，业务明文只在 Noise 之内。
 */
public final class RemoteConnection {
    public interface Callback {
        /** 连接可用（Noise 已完成），可以发业务请求。 */
        void onReady();

        /** 收到 Host 主动推送的事件（例如 $events 流）。 */
        void onEvent(JSONObject message);

        /** 连接断开；之后必须重新 connect。 */
        void onClosed(String reason, boolean retryable);

        void onProgress(String stage);
    }

    private static final int PROTOCOL_VERSION = 1;
    private static final int RPC_TIMEOUT_MS = 65_000;

    private final String serverUrl;
    private final String clientDeviceId;
    private final byte[] clientPrivateKey;
    private final String clientPublicKey;
    private final String hostDeviceId;
    private final String hostPublicKey;
    private final String accessToken;
    private final String clientVersion;
    private final Callback callback;

    private WebSocketClient socket;
    private NoiseIK noise;
    private FragmentAssembler assembler;
    private String connectionId;
    private volatile boolean ready;
    private volatile boolean closed;
    private volatile String closeReason = "";

    private final Map<String, PendingCall> pending = new HashMap<String, PendingCall>();
    private final Object pendingLock = new Object();
    private long relayCounter;
    private int handshakeStep;

    private static final class PendingCall {
        final String id;
        JSONObject result;
        JSONObject error;
        boolean done;

        PendingCall(String id) {
            this.id = id;
        }
    }

    public RemoteConnection(String serverUrl, String clientDeviceId, byte[] clientPrivateKey,
                            String clientPublicKey, String hostDeviceId, String hostPublicKey,
                            String accessToken, String clientVersion, Callback callback) {
        this.serverUrl = serverUrl;
        this.clientDeviceId = clientDeviceId;
        this.clientPrivateKey = clientPrivateKey;
        this.clientPublicKey = clientPublicKey;
        this.hostDeviceId = hostDeviceId;
        this.hostPublicKey = hostPublicKey;
        this.accessToken = accessToken;
        this.clientVersion = clientVersion;
        this.callback = callback;
        this.assembler = new FragmentAssembler();
    }

    public boolean isReady() {
        return ready && !closed;
    }

    public String connectionId() {
        return connectionId;
    }

    /** 建立连接。阻塞直到 Noise 完成或超时；失败抛异常。 */
    public void connect(long timeoutMs) throws Exception {
        callback.onProgress("正在连接服务器");
        String wsUrl = toWebSocketUrl(serverUrl);
        final Object signal = new Object();
        final String[] failure = new String[1];

        socket = new WebSocketClient(wsUrl, new WebSocketClient.Listener() {
            @Override
            public void onOpen() {
                try {
                    sendControl("hello", helloPayload());
                } catch (Exception error) {
                    markClosed("发送 hello 失败：" + error.getMessage(), true);
                }
            }

            @Override
            public void onText(String text) {
                try {
                    handleControlFrame(new JSONObject(text));
                } catch (Exception error) {
                    fail("控制帧解析失败：" + error.getMessage());
                }
            }

            @Override
            public void onBinary(byte[] data) {
                // 协议规定 Control 通道只发文本帧，收到二进制必须拒绝。
                fail("控制通道收到二进制帧");
            }

            @Override
            public void onClosed(int code, String reason) {
                markClosed("连接已关闭（" + code + "）" + (reason == null ? "" : reason), code != 1000);
            }

            @Override
            public void onError(Exception error) {
                markClosed("连接出错：" + error.getMessage(), true);
            }
        });

        callback.onProgress("正在握手");
        socket.connect((int) Math.min(timeoutMs, 20_000L));

        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (signal) {
            while (!ready && !closed && System.currentTimeMillis() < deadline) {
                signal.wait(200);
            }
        }
        if (closed && !ready) throw new IOException(closeReason.isEmpty() ? "连接失败" : closeReason);
        if (!ready) {
            close();
            throw new IOException("连接超时");
        }
    }

    /** 发一个一元 RPC，返回 result 对象（含 ok/value 或 ok/error）。 */
    public JSONObject call(String method, JSONObject params, long timeoutMs) throws Exception {
        if (!isReady()) throw new IOException("连接尚未就绪");
        String id = UUID.randomUUID().toString();
        PendingCall entry = new PendingCall(id);
        synchronized (pendingLock) {
            pending.put(id, entry);
        }
        JSONObject payload = new JSONObject();
        payload.put("method", method);
        payload.put("params", params == null ? new JSONObject() : params);
        JSONObject message = new JSONObject();
        message.put("v", PROTOCOL_VERSION);
        message.put("id", id);
        message.put("type", "rpc.request");
        message.put("timestamp", System.currentTimeMillis());
        message.put("payload", payload);
        sendSecure(Bytes.utf8(message.toString()));

        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (entry) {
            while (!entry.done && !closed && System.currentTimeMillis() < deadline) {
                entry.wait(100);
            }
        }
        synchronized (pendingLock) {
            pending.remove(id);
        }
        if (entry.error != null) {
            String code = entry.error.optString("code", "RPC_ERROR");
            String text = entry.error.optString("message", "远程调用失败");
            throw new RemoteException(code, text);
        }
        if (!entry.done) throw new IOException("远程调用超时：" + method);
        return entry.result;
    }

    /**
     * 调 Harness 的一个端点。参数形状与 DSH 原生 RPC 一致：
     * {endpoint, payload:{args}}，返回 result 里的 value。
     */
    public JSONObject harnessCall(String endpoint, JSONObject args, long timeoutMs) throws Exception {
        JSONObject inner = new JSONObject();
        inner.put("endpoint", endpoint);
        JSONObject payload = new JSONObject();
        payload.put("args", args == null ? new JSONObject() : args);
        inner.put("payload", payload);
        try {
            JSONObject result = callRaw("harness.remote.call", inner, timeoutMs);
            return result == null ? new JSONObject() : result;
        } catch (RemoteException error) {
            // Host 在「响应过大」时不内联结果，必须改走分块传输重取。
            if (!"RESPONSE_TOO_LARGE".equals(error.code())) throw error;
            return callTransferred(inner, timeoutMs);
        }
    }

    /** 请求/响应超过安全通道上限时走 harness.remote.transfer.* 分块传输。 */
    private JSONObject callTransferred(JSONObject inner, long timeoutMs) throws Exception {
        String transferId = UUID.randomUUID().toString();
        byte[] encoded = Bytes.utf8(inner.toString());
        final int chunkBytes = 512 * 1024;
        int totalChunks = (encoded.length + chunkBytes - 1) / chunkBytes;
        boolean opened = false;
        try {
            JSONObject open = new JSONObject();
            open.put("transferId", transferId);
            open.put("totalBytes", encoded.length);
            open.put("totalChunks", totalChunks);
            callRaw("harness.remote.transfer.open", open, timeoutMs);
            opened = true;
            for (int index = 0; index < totalChunks; index++) {
                int start = index * chunkBytes;
                int end = Math.min(start + chunkBytes, encoded.length);
                JSONObject chunk = new JSONObject();
                chunk.put("transferId", transferId);
                chunk.put("index", index);
                chunk.put("data", standardBase64(Bytes.slice(encoded, start, end)));
                callRaw("harness.remote.transfer.chunk", chunk, timeoutMs);
            }
            JSONObject commitArgs = new JSONObject();
            commitArgs.put("transferId", transferId);
            JSONObject committed = callRaw("harness.remote.transfer.commit", commitArgs, timeoutMs);
            JSONObject descriptor = committed == null ? null : committed.optJSONObject("value");
            if (descriptor == null) descriptor = committed;
            if (descriptor != null && "inline".equals(descriptor.optString("kind", ""))) {
                JSONObject inline = descriptor.optJSONObject("response");
                return inline == null ? new JSONObject() : inline;
            }
            int totalBytes = descriptor == null ? 0 : descriptor.optInt("totalBytes", 0);
            int responseChunks = descriptor == null ? 0 : descriptor.optInt("totalChunks", 0);
            if (totalBytes <= 0 || totalBytes > 288 * 1024 * 1024
                    || responseChunks != (totalBytes + chunkBytes - 1) / chunkBytes) {
                throw new IOException("分块传输描述不合法");
            }
            byte[] out = new byte[totalBytes];
            int offset = 0;
            for (int index = 0; index < responseChunks; index++) {
                JSONObject readArgs = new JSONObject();
                readArgs.put("transferId", transferId);
                readArgs.put("index", index);
                JSONObject chunk = callRaw("harness.remote.transfer.read", readArgs, timeoutMs);
                JSONObject value = chunk == null ? null : chunk.optJSONObject("value");
                if (value == null) value = chunk;
                String data = value == null ? "" : value.optString("data", "");
                byte[] bytes = Bytes.unb64u(data.replace('+', '-').replace('/', '_'));
                System.arraycopy(bytes, 0, out, offset, bytes.length);
                offset += bytes.length;
            }
            return new JSONObject(Bytes.fromUtf8(out));
        } finally {
            if (opened) {
                try {
                    JSONObject closeArgs = new JSONObject();
                    closeArgs.put("transferId", transferId);
                    callRaw("harness.remote.transfer.close", closeArgs, 5_000L);
                } catch (Exception ignored) {
                    // 清理失败不影响主流程。
                }
            }
        }
    }

    /** 标准 Base64（带 padding），分块传输的 data 字段用它。 */
    private static String standardBase64(byte[] data) {
        final char[] alphabet =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder out = new StringBuilder((data.length * 4 + 2) / 3);
        int i = 0;
        while (i + 3 <= data.length) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8) | (data[i + 2] & 0xff);
            out.append(alphabet[(n >>> 18) & 0x3f]);
            out.append(alphabet[(n >>> 12) & 0x3f]);
            out.append(alphabet[(n >>> 6) & 0x3f]);
            out.append(alphabet[n & 0x3f]);
            i += 3;
        }
        int rest = data.length - i;
        if (rest == 1) {
            int n = (data[i] & 0xff) << 16;
            out.append(alphabet[(n >>> 18) & 0x3f]);
            out.append(alphabet[(n >>> 12) & 0x3f]);
            out.append("==");
        } else if (rest == 2) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8);
            out.append(alphabet[(n >>> 18) & 0x3f]);
            out.append(alphabet[(n >>> 12) & 0x3f]);
            out.append(alphabet[(n >>> 6) & 0x3f]);
            out.append('=');
        }
        return out.toString();
    }

    private JSONObject callRaw(String method, JSONObject params, long timeoutMs) throws Exception {
        if (!isReady()) throw new IOException("连接尚未就绪");
        String id = UUID.randomUUID().toString();
        PendingCall entry = new PendingCall(id);
        synchronized (pendingLock) {
            pending.put(id, entry);
        }
        JSONObject payload = new JSONObject();
        payload.put("method", method);
        payload.put("params", params);
        JSONObject message = new JSONObject();
        message.put("v", PROTOCOL_VERSION);
        message.put("id", id);
        message.put("type", "rpc.request");
        message.put("timestamp", System.currentTimeMillis());
        message.put("payload", payload);
        sendSecure(Bytes.utf8(message.toString()));

        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (entry) {
            while (!entry.done && !closed && System.currentTimeMillis() < deadline) {
                entry.wait(100);
            }
        }
        synchronized (pendingLock) {
            pending.remove(id);
        }
        if (entry.error != null) {
            throw new RemoteException(entry.error.optString("code", "RPC_ERROR"),
                    entry.error.optString("message", "远程调用失败"));
        }
        if (!entry.done) throw new IOException("远程调用超时：" + method);
        return entry.result;
    }

    public void close() {
        closed = true;
        ready = false;
        if (noise != null) {
            noise.destroy();
            noise = null;
        }
        if (socket != null) {
            socket.close(1000, "bye");
            socket = null;
        }
        synchronized (pendingLock) {
            for (PendingCall entry : pending.values()) {
                synchronized (entry) {
                    entry.done = true;
                    entry.notifyAll();
                }
            }
            pending.clear();
        }
    }

    // ===== Control 帧处理 =====

    private JSONObject helloPayload() throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("role", "client");
        payload.put("deviceId", clientDeviceId);
        payload.put("accessToken", accessToken);
        payload.put("protocols", new JSONArray().put(PROTOCOL_VERSION));
        payload.put("clientVersion", clientVersion);
        // 只声明 relay：手表/手机不实现 WebRTC，避免服务端尝试 P2P 后回落浪费时间。
        payload.put("capabilities", new JSONArray().put("transport.relay"));
        return payload;
    }

    private void handleControlFrame(JSONObject frame) throws Exception {
        String type = frame.optString("type", "");
        JSONObject payload = frame.optJSONObject("payload");
        if (payload == null) payload = new JSONObject();

        if ("hello.ack".equals(type)) {
            callback.onProgress("正在请求连接");
            JSONObject request = new JSONObject();
            request.put("hostDeviceId", hostDeviceId);
            request.put("preferredTransports", new JSONArray().put("relay"));
            sendControl("connect.request", request);
            return;
        }
        if ("connect.accepted".equals(type)) {
            connectionId = payload.optString("connectionId", "");
            if (connectionId.isEmpty()) throw new IOException("connect.accepted 缺少 connectionId");
            callback.onProgress("正在建立加密通道");
            startHandshake();
            return;
        }
        if ("secure.handshake".equals(type)) {
            int step = payload.optInt("step", 0);
            String data = payload.optString("data", "");
            handleHandshake(step, Bytes.unb64u(data));
            return;
        }
        if ("relay".equals(type)) {
            handleRelayFrame(payload);
            return;
        }
        if ("error".equals(type)) {
            String code = payload.optString("code", "CONNECTION_FAILED");
            String message = payload.optString("message", "连接失败");
            boolean retryable = payload.optBoolean("retryable", false);
            markClosed(code + ": " + message, retryable);
            return;
        }
        if ("ping".equals(type)) {
            JSONObject pong = new JSONObject();
            pong.put("nonce", payload.optString("nonce", ""));
            sendControl("pong", pong);
            return;
        }
        // 其他帧（如 pong）忽略。
    }

    private void startHandshake() throws Exception {
        noise = new NoiseIK(true, clientPrivateKey, Bytes.unb64u(clientPublicKey),
                Bytes.unb64u(hostPublicKey),
                NoiseIK.prologue(connectionId, hostDeviceId, clientDeviceId));
        handshakeStep = 1;
        byte[] message = noise.writeHandshake(new byte[0]);
        sendHandshakeFrame(1, message);
    }

    private void handleHandshake(int step, byte[] data) throws Exception {
        if (noise == null) throw new IOException("收到握手消息但本地尚未初始化");
        if (step != 2) throw new IOException("握手步骤异常：" + step);
        noise.readHandshake(data);
        if (!noise.isComplete()) throw new IOException("Noise 握手未完成");
        ready = true;
        callback.onProgress("已连接");
        callback.onReady();
    }

    private void sendHandshakeFrame(int step, byte[] data) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("connectionId", connectionId);
        payload.put("targetDeviceId", hostDeviceId);
        payload.put("step", step);
        payload.put("data", Bytes.b64u(data));
        sendControl("secure.handshake", payload);
    }

    private void handleRelayFrame(JSONObject payload) throws Exception {
        if (noise == null || !noise.isComplete()) return;
        try {
            byte[] ciphertext = Bytes.unb64u(payload.optString("ciphertext", ""));
            byte[] plaintext = noise.decrypt(ciphertext);
            byte[] whole = FragmentAssembler.isFragment(plaintext) ? assembler.accept(plaintext) : plaintext;
            if (whole == null) return;
            JSONObject message = new JSONObject(Bytes.fromUtf8(whole));
            dispatchSecure(message);
        } catch (Exception error) {
            markClosed("安全通道解密失败：" + error.getMessage(), false);
        }
    }

    private void dispatchSecure(JSONObject message) throws Exception {
        String type = message.optString("type", "");
        if ("rpc.response".equals(type) || "rpc.error".equals(type)) {
            JSONObject payload = message.optJSONObject("payload");
            if (payload == null) return;
            String requestId = payload.optString("requestId", "");
            PendingCall entry;
            synchronized (pendingLock) {
                entry = pending.get(requestId);
            }
            if (entry == null) return;
            synchronized (entry) {
                if ("rpc.error".equals(type)) entry.error = payload;
                else entry.result = payload.optJSONObject("result");
                entry.done = true;
                entry.notifyAll();
            }
            return;
        }
        if ("event".equals(type)) {
            callback.onEvent(message);
        }
    }

    private void sendControl(String type, JSONObject payload) throws Exception {
        JSONObject frame = new JSONObject();
        frame.put("v", PROTOCOL_VERSION);
        frame.put("id", UUID.randomUUID().toString());
        frame.put("type", type);
        frame.put("timestamp", System.currentTimeMillis());
        frame.put("payload", payload);
        try {
            if (socket != null && socket.isOpen()) socket.sendText(frame.toString());
        } catch (IOException error) {
            markClosed("控制帧发送失败：" + error.getMessage(), true);
        }
    }

    /** 把明文经 Noise 加密后放进 relay 帧。 */
    private void sendSecure(byte[] plaintext) throws Exception {
        if (noise == null || !noise.isComplete()) throw new IOException("安全通道未就绪");
        byte[] ciphertext = noise.encrypt(plaintext);
        JSONObject payload = new JSONObject();
        payload.put("connectionId", connectionId);
        payload.put("targetDeviceId", hostDeviceId);
        payload.put("counter", relayCounter++);
        payload.put("ciphertext", Bytes.b64u(ciphertext));
        sendControl("relay", payload);
    }

    private void fail(String reason) {
        markClosed(reason, false);
    }

    private void markClosed(String reason, boolean retryable) {
        if (closed) return;
        closed = true;
        ready = false;
        closeReason = reason;
        synchronized (pendingLock) {
            for (PendingCall entry : pending.values()) {
                synchronized (entry) {
                    entry.done = true;
                    entry.notifyAll();
                }
            }
        }
        callback.onClosed(reason, retryable);
    }

    private static String toWebSocketUrl(String serverUrl) {
        String value = serverUrl;
        if (value.startsWith("https://")) value = "wss://" + value.substring("https://".length());
        else if (value.startsWith("http://")) value = "ws://" + value.substring("http://".length());
        if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value + "/ws/v1/connect";
    }

    /** 远程调用返回的业务错误。 */
    public static final class RemoteException extends Exception {
        private final String code;

        public RemoteException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
