package cn.dsh.remote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 远程账号：登录、设备注册、令牌轮换、主机列表。
 *
 * 只走 HTTPS + JSON，不依赖第三方 HTTP 库。
 * 令牌必须跨启动保留（与项目里配对码的要求一致），因此由调用方负责持久化。
 */
public final class RemoteAccount {
    public static final String DEFAULT_SERVER = "https://dsh.r2049.cn";

    private final String serverUrl;
    private String accessToken;
    private String refreshToken;
    private long accessTokenExpiresAt;
    private long refreshTokenExpiresAt;
    private String deviceId;
    private String account;

    public RemoteAccount(String serverUrl) {
        this.serverUrl = normalize(serverUrl);
    }

    public String serverUrl() {
        return serverUrl;
    }

    public String deviceId() {
        return deviceId;
    }

    public String account() {
        return account;
    }

    public boolean hasCredentials() {
        return accessToken != null && accessToken.length() > 0;
    }

    /** 从已保存的状态恢复（调用方从本地存储读出后传入）。 */
    public void restore(String deviceId, String accessToken, String refreshToken,
                        long accessTokenExpiresAt, long refreshTokenExpiresAt, String account) {
        this.deviceId = deviceId;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.accessTokenExpiresAt = accessTokenExpiresAt;
        this.refreshTokenExpiresAt = refreshTokenExpiresAt;
        this.account = account;
    }

    public String accessToken() {
        return accessToken;
    }

    public String refreshTokenValue() {
        return refreshToken;
    }

    public long accessTokenExpiresAt() {
        return accessTokenExpiresAt;
    }

    public long refreshTokenExpiresAt() {
        return refreshTokenExpiresAt;
    }

    /** 令牌快过期时自动刷新；返回可用 access token。 */
    public String ensureAccessToken() throws Exception {
        if (accessToken == null || accessToken.isEmpty()) throw new IOException("尚未登录远程账号");
        // 提前 60 秒刷新，避免请求途中过期。
        if (System.currentTimeMillis() > accessTokenExpiresAt - 60_000L) refresh();
        return accessToken;
    }

    /** 用 refresh token 换新的一对令牌（服务端会轮换 refresh token）。 */
    public void refresh() throws Exception {
        if (refreshToken == null || refreshToken.isEmpty()) throw new IOException("登录已过期，请重新登录");
        JSONObject body = new JSONObject();
        body.put("deviceId", deviceId);
        body.put("refreshToken", refreshToken);
        JSONObject response = post("/api/v1/auth/refresh", body, null);
        applyTokenResponse(response);
    }

    /** 账号密码登录（自建服务器或受邀账号）。 */
    public void loginWithPassword(String email, String password) throws Exception {
        JSONObject body = new JSONObject();
        body.put("email", email);
        body.put("password", password);
        JSONObject response = post("/api/v1/auth/login", body, null);
        String token = response.optString("token", "");
        if (token.isEmpty()) throw new IOException("登录失败：未返回账号令牌");
        account = response.optString("account", email);
        // 账号 token 只用于注册设备，不用于 WebSocket。
        registerDevice(token);
    }

    /** 用已有设备凭据注册另一角色（同一安装的 Host/Client 互认）。 */
    public void registerOwnedRole(String currentDeviceAccessToken, String name, String role,
                                  String platform, String clientVersion) throws Exception {
        byte[][] keys = new byte[1][];
        keys[0] = X25519.generateKeyPair()[0];
        byte[] privateKey = keys[0];
        byte[] publicKey = X25519.publicKey(privateKey);
        deviceId = uuidV7();
        JSONObject device = new JSONObject();
        device.put("deviceId", deviceId);
        device.put("name", name);
        device.put("role", role);
        device.put("platform", platform);
        device.put("identityKey", Bytes.b64u(publicKey));
        device.put("clientVersion", clientVersion);
        JSONObject body = new JSONObject();
        body.put("v", 1);
        body.put("device", device);
        JSONObject response = post("/api/v1/devices/register-owned-role", body, currentDeviceAccessToken);
        applyTokenResponse(response);
        this.localPrivateKey = privateKey;
        this.localPublicKey = Bytes.b64u(publicKey);
    }

    /** 用账号 token 注册本机为 Client。 */
    public void registerDevice(String accountToken) throws Exception {
        registerDevice(accountToken, "DSH 手表", "client", "android", "1.0.0");
    }

    public void registerDevice(String accountToken, String name, String role,
                               String platform, String clientVersion) throws Exception {
        byte[] privateKey = X25519.generateKeyPair()[0];
        byte[] publicKey = X25519.publicKey(privateKey);
        deviceId = uuidV7();
        JSONObject device = new JSONObject();
        device.put("deviceId", deviceId);
        device.put("name", name);
        device.put("role", role);
        device.put("platform", platform);
        device.put("identityKey", Bytes.b64u(publicKey));
        device.put("clientVersion", clientVersion);
        JSONObject body = new JSONObject();
        body.put("v", 1);
        body.put("device", device);
        JSONObject response = post("/api/v1/devices/register", body, accountToken);
        applyTokenResponse(response);
        this.localPrivateKey = privateKey;
        this.localPublicKey = Bytes.b64u(publicKey);
    }

    private byte[] localPrivateKey;
    private String localPublicKey;

    public byte[] localPrivateKey() {
        return localPrivateKey;
    }

    public String localPublicKey() {
        return localPublicKey;
    }

    public void setLocalKeys(byte[] privateKey, String publicKey) {
        this.localPrivateKey = privateKey;
        this.localPublicKey = publicKey;
    }

    /** 列出同账号可访问的 Host。 */
    public List<HostDevice> listHosts() throws Exception {
        String token = ensureAccessToken();
        List<HostDevice> hosts = new ArrayList<HostDevice>();
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            String path = "/api/v1/devices?limit=50";
            if (cursor != null) path += "&cursor=" + urlEncode(cursor);
            JSONObject response = get(path, token);
            JSONArray items = response.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    if (!"host".equals(item.optString("role", ""))) continue;
                    HostDevice host = new HostDevice();
                    host.deviceId = item.optString("deviceId", "");
                    host.name = item.optString("name", "");
                    host.platform = item.optString("platform", "");
                    host.clientVersion = item.optString("clientVersion", "");
                    host.harnessVersion = item.optString("harnessVersion", "");
                    hosts.add(host);
                }
            }
            cursor = response.optString("nextCursor", "");
            if (cursor == null || cursor.isEmpty() || "null".equals(cursor)) break;
        }
        return hosts;
    }

    /** 取 Host 的 identityKey（Noise 握手必需）。 */
    public String hostIdentityKey(String hostDeviceId) throws Exception {
        String token = ensureAccessToken();
        JSONObject response = get("/api/v1/devices/" + urlEncode(hostDeviceId), token);
        String key = response.optString("identityKey", "");
        if (key.isEmpty()) throw new IOException("Host 未提供身份公钥");
        return key;
    }

    private void applyTokenResponse(JSONObject response) throws Exception {
        String access = response.optString("accessToken", "");
        String refresh = response.optString("refreshToken", "");
        if (access.isEmpty() || refresh.isEmpty()) throw new IOException("服务端未返回完整令牌");
        accessToken = access;
        refreshToken = refresh;
        accessTokenExpiresAt = response.optLong("accessTokenExpiresAt", 0L);
        refreshTokenExpiresAt = response.optLong("refreshTokenExpiresAt", 0L);
    }

    // ===== HTTP =====

    private JSONObject post(String path, JSONObject body, String bearer) throws Exception {
        return request("POST", path, body, bearer);
    }

    private JSONObject get(String path, String bearer) throws Exception {
        return request("GET", path, null, bearer);
    }

    private JSONObject request(String method, String path, JSONObject body, String bearer) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(serverUrl + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("accept", "application/json");
            if (bearer != null && !bearer.isEmpty()) {
                connection.setRequestProperty("authorization", "Bearer " + bearer);
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("content-type", "application/json");
                byte[] payload = Bytes.utf8(body.toString());
                connection.setFixedLengthStreamingMode(payload.length);
                OutputStream out = connection.getOutputStream();
                out.write(payload);
                out.flush();
                out.close();
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = stream == null ? "" : readAll(stream);
            if (status >= 400) {
                String code = "HTTP_" + status;
                String message = text;
                try {
                    JSONObject parsed = new JSONObject(text);
                    JSONObject error = parsed.optJSONObject("error");
                    if (error != null) {
                        code = error.optString("code", code);
                        message = error.optString("message", message);
                    }
                } catch (Exception ignored) {
                    // 非 JSON 错误体，保留原文。
                }
                throw new RemoteApiException(code, message, status);
            }
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) >= 0) buffer.write(chunk, 0, read);
        stream.close();
        return Bytes.fromUtf8(buffer.toByteArray());
    }

    private static String normalize(String value) {
        String url = value == null || value.trim().isEmpty() ? DEFAULT_SERVER : value.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException error) {
            throw new IllegalStateException("UTF-8 不可用", error);
        }
    }

    /** 生成 UUIDv7（时间有序），与服务端的 deviceId 约定一致。 */
    private static String uuidV7() {
        long timestamp = System.currentTimeMillis();
        byte[] random = Bytes.random(10);
        byte[] bytes = new byte[16];
        bytes[0] = (byte) (timestamp >>> 40);
        bytes[1] = (byte) (timestamp >>> 32);
        bytes[2] = (byte) (timestamp >>> 24);
        bytes[3] = (byte) (timestamp >>> 16);
        bytes[4] = (byte) (timestamp >>> 8);
        bytes[5] = (byte) timestamp;
        System.arraycopy(random, 0, bytes, 6, 10);
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x70);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        StringBuilder out = new StringBuilder(36);
        for (int i = 0; i < 16; i++) {
            if (i == 4 || i == 6 || i == 8 || i == 10) out.append('-');
            out.append(Character.forDigit((bytes[i] >> 4) & 0xf, 16));
            out.append(Character.forDigit(bytes[i] & 0xf, 16));
        }
        return out.toString();
    }

    /** Host 设备摘要。 */
    public static final class HostDevice {
        public String deviceId;
        public String name;
        public String platform;
        public String clientVersion;
        public String harnessVersion;

        @Override
        public String toString() {
            return name + " (" + platform + ")";
        }
    }

    /** 服务端返回的业务错误。 */
    public static final class RemoteApiException extends IOException {
        private final String code;
        private final int status;

        RemoteApiException(String code, String message, int status) {
            super(code + ": " + message);
            this.code = code;
            this.status = status;
        }

        public String code() {
            return code;
        }

        public int status() {
            return status;
        }
    }
}
