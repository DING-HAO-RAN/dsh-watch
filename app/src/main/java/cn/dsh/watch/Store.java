package cn.dsh.watch;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 手表侧的本地状态：中继地址与配对令牌。
 *
 * 支持两种地址形态：
 * - 局域网：主机 + 端口（如 192.168.1.121:19390），用明文 HTTP；
 * - 公网：完整 URL（如 https://dsh.example.com），走 HTTPS，端口由协议默认或地址自带。
 *
 * 两者都存在同一个 base 字段里，由 {@link Endpoint} 统一解析，避免两套存取逻辑。
 */
final class Store {
    private static final String FILE = "dsh-watch";
    private static final String KEY_BASE = "base";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_LAST_SESSION = "lastSession";
    private static final String KEY_LAST_SESSION_TITLE = "lastSessionTitle";

    private final SharedPreferences prefs;

    Store(Context context) {
        this.prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 中继地址（形如 host:port 或 https://host）。 */
    String base() {
        return prefs.getString(KEY_BASE, "");
    }

    String token() {
        return prefs.getString(KEY_TOKEN, "");
    }

    String lastSession() {
        return prefs.getString(KEY_LAST_SESSION, "");
    }

    String lastSessionTitle() {
        return prefs.getString(KEY_LAST_SESSION_TITLE, "");
    }

    void setBase(String base) {
        prefs.edit().putString(KEY_BASE, base).apply();
    }

    void setToken(String token) {
        prefs.edit().putString(KEY_TOKEN, token).apply();
    }

    void setLastSession(String sessionId, String title) {
        prefs.edit()
                .putString(KEY_LAST_SESSION, sessionId)
                .putString(KEY_LAST_SESSION_TITLE, title)
                .apply();
    }

    /** 断开连接：清除令牌，保留上次地址以便重连时预填。 */
    void clearToken() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_LAST_SESSION).remove(KEY_LAST_SESSION_TITLE).apply();
    }

    boolean paired() {
        return !base().isEmpty() && !token().isEmpty();
    }

    /** 一个解析好的中继地址。 */
    static final class Endpoint {
        /** 原始写法，直接用于显示与保存。 */
        final String raw;
        final String scheme;
        final String host;
        final int port;

        private Endpoint(String raw, String scheme, String host, int port) {
            this.raw = raw;
            this.scheme = scheme;
            this.host = host;
            this.port = port;
        }

        boolean isSecure() {
            return "https".equals(scheme);
        }

        /** 拼出请求用的完整 URL（含可选端口）。 */
        String url(String path) {
            StringBuilder builder = new StringBuilder();
            builder.append(scheme).append("://").append(host);
            // 默认端口不写出来，避免 https://host:443 这类多余写法。
            boolean defaultPort = ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
            if (port > 0 && !defaultPort) builder.append(':').append(port);
            if (!path.startsWith("/")) builder.append('/');
            builder.append(path);
            return builder.toString();
        }

        /** 用于界面显示的简短形态。 */
        String display() {
            return raw;
        }
    }

    /**
     * 解析用户输入的地址。
     *
     * 接受这些写法：
     * - `192.168.1.121`（默认局域网端口）
     * - `192.168.1.121:19390`
     * - `https://dsh.example.com`
     * - `dsh.example.com`（无协议时按 HTTP 处理，符合局域网习惯）
     *
     * @param input 用户输入
     * @param defaultPort 未写端口时局域网使用的默认端口
     * @return 解析结果；输入非法时返回 null
     */
    static Endpoint parse(String input, int defaultPort) {
        if (input == null) return null;
        String text = input.trim();
        if (text.isEmpty()) return null;

        String scheme = "http";
        String rest = text;
        int schemeAt = text.indexOf("://");
        if (schemeAt > 0) {
            scheme = text.substring(0, schemeAt).toLowerCase();
            rest = text.substring(schemeAt + 3);
            if (!"http".equals(scheme) && !"https".equals(scheme)) return null;
        }
        // 去掉路径与查询，只保留主机部分。
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        if (rest.isEmpty()) return null;

        String host = rest;
        int port = "https".equals(scheme) ? 443 : defaultPort;
        // IPv6 字面量形如 [::1]:port，这里只在非 IPv6 时按冒号切端口。
        if (!rest.startsWith("[")) {
            int colon = rest.lastIndexOf(':');
            if (colon > 0) {
                String portText = rest.substring(colon + 1);
                try {
                    int parsed = Integer.parseInt(portText);
                    if (parsed <= 0 || parsed > 65535) return null;
                    port = parsed;
                    host = rest.substring(0, colon);
                } catch (NumberFormatException error) {
                    // 不是端口，按整体当主机名处理。
                }
            }
        }
        if (host.isEmpty()) return null;

        String canonical = "http".equals(scheme) && port == defaultPort && schemeAt < 0
                ? host
                : ("http".equals(scheme) ? host + ":" + port : scheme + "://" + host + (port == 443 ? "" : ":" + port));
        return new Endpoint(canonical, scheme, host, port);
    }
}
