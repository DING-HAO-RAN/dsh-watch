package cn.dsh.watch;

import android.content.Context;
import android.content.SharedPreferences;

/** 手表侧的本地状态：中继地址与配对令牌。 */
final class Store {
    private static final String FILE = "dsh-watch";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_LAST_SESSION = "lastSession";

    private final SharedPreferences prefs;

    Store(Context context) {
        this.prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String host() {
        return prefs.getString(KEY_HOST, "");
    }

    int port() {
        return prefs.getInt(KEY_PORT, 19390);
    }

    String token() {
        return prefs.getString(KEY_TOKEN, "");
    }

    String lastSession() {
        return prefs.getString(KEY_LAST_SESSION, "");
    }

    void setServer(String host, int port) {
        prefs.edit().putString(KEY_HOST, host).putInt(KEY_PORT, port).apply();
    }

    void setToken(String token) {
        prefs.edit().putString(KEY_TOKEN, token).apply();
    }

    void setLastSession(String sessionId) {
        prefs.edit().putString(KEY_LAST_SESSION, sessionId).apply();
    }

    /** 断开与电脑的连接，保留上次地址以便重连时预填。 */
    void clearToken() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_LAST_SESSION).apply();
    }

    boolean paired() {
        return !host().isEmpty() && !token().isEmpty();
    }
}
