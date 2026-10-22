package cn.dsh.watch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 手表侧的本地状态：多个中继设备的连接信息，以及当前正在用的那一台。
 *
 * 设计要点：
 * - **记住多台设备**。同一块手表可能在「家里电脑的局域网」「公司电脑的局域网」
 *   「某个公网地址」之间来回切，每次都重新输配对码很麻烦。因此每台设备
 *   （地址 + 令牌 + 备注 + 最后使用时间）单独存一条，可随时切换。
 * - **base() / token() 这些原有方法保持语义不变**，它们返回「当前设备」的值。
 *   这样数十处调用点不用改，切换设备后自然生效。
 * - 旧的单设备存档会被自动迁移成列表里的第一条，用户不需要重新配对。
 */
final class Store {
    private static final String FILE = "dsh-watch";
    private static final String KEY_BASE = "base";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_LAST_SESSION = "lastSession";
    private static final String KEY_LAST_SESSION_TITLE = "lastSessionTitle";

    /** 多设备列表（JSON 数组）。 */
    private static final String KEY_DEVICES = "devices";

    /** 一台已记住的中继设备。 */
    static final class Device {
        /** 地址原文，形如 `192.168.1.121:19390` 或 `https://xxx.trycloudflare.com`。 */
        String base = "";
        String token = "";
        /** 用户可读的备注；为空时界面按地址与网络类型自动取名。 */
        String label = "";
        /** 是否为公网地址（决定显示「公网」还是「局域网」）。 */
        long lastUsed = 0L;

        /**
         * 同一台电脑的另一种入口。
         *
         * 一次配对会同时拿到局域网地址与公网地址，两者共用同一个令牌：
         * 在家走近路（局域网），出门走公网。只存一条地址的话，换个网络就得重配。
         */
        String alternateBase = "";

        /** 这台设备对应的中继给过我们的设备标识（用于「常用设备」自动同意）。 */
        String deviceId = "";

        boolean valid() {
            return base != null && !base.isEmpty() && token != null && !token.isEmpty();
        }

        /** 地址里是否带 https（公网）。 */
        boolean isPublic() {
            return base != null && base.startsWith("https://");
        }
        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("base", base == null ? "" : base);
                object.put("token", token == null ? "" : token);
                object.put("label", label == null ? "" : label);
                object.put("lastUsed", lastUsed);
                object.put("alternateBase", alternateBase == null ? "" : alternateBase);
                object.put("deviceId", deviceId == null ? "" : deviceId);
            } catch (Exception ignored) {
                // 值都是字符串与数字，不会失败。
            }
            return object;
        }

        static Device fromJson(JSONObject object) {
            if (object == null) return null;
            Device device = new Device();
            device.base = Api.text(object, "base");
            device.token = Api.text(object, "token");
            device.label = Api.text(object, "label");
            device.lastUsed = object.optLong("lastUsed", 0L);
            device.alternateBase = Api.text(object, "alternateBase");
            device.deviceId = Api.text(object, "deviceId");
            return device.valid() ? device : null;
        }
    }

    private final SharedPreferences prefs;

    Store(Context context) {
        this.prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        migrateLegacy();
    }

    /**
     * 把旧的单设备存档迁移成多设备列表。
     *
     * 只在「列表为空且旧存档有效」时执行，迁移后清掉旧字段，避免重复迁移。
     */
    private void migrateLegacy() {
        String legacyBase = prefs.getString(KEY_BASE, "");
        String legacyToken = prefs.getString(KEY_TOKEN, "");
        if (legacyBase == null || legacyBase.isEmpty() || legacyToken == null || legacyToken.isEmpty()) return;
        if (!devices().isEmpty()) return;

        Device device = new Device();
        device.base = legacyBase;
        device.token = legacyToken;
        device.lastUsed = System.currentTimeMillis();
        saveDevices(single(device));
        prefs.edit().putLong(KEY_CURRENT, 0L).apply();
    }

    /** 当前设备的索引（相对列表）。 */
    private static final String KEY_CURRENT = "currentDevice";

    // ------------------------------------------------------------ 设备列表

    /** 读全部已记住的设备（按最后使用时间倒序，最近的在前）。 */
    List<Device> devices() {
        List<Device> list = new ArrayList<>();
        String raw = prefs.getString(KEY_DEVICES, "");
        if (raw == null || raw.isEmpty()) return list;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                Device device = Device.fromJson(array.optJSONObject(i));
                if (device != null) list.add(device);
            }
        } catch (Exception ignored) {
            // 存档损坏时当作空列表，用户可以重新配对。
        }
        list.sort((a, b) -> Long.compare(b.lastUsed, a.lastUsed));
        return list;
    }

    private void saveDevices(List<Device> list) {
        JSONArray array = new JSONArray();
        for (Device device : list) array.put(device.toJson());
        prefs.edit().putString(KEY_DEVICES, array.toString()).apply();
    }

    private static List<Device> single(Device device) {
        List<Device> list = new ArrayList<>();
        list.add(device);
        return list;
    }

    /** 当前正在用的设备；未配对时为 null。 */
    Device current() {
        for (Device device : devices()) {
            if (device.base.equals(base())) return device;
        }
        return null;
    }

    /**
     * 记住（或更新）一台设备，并切换过去。
     *
     * 同一地址视为同一台设备——换令牌就覆盖，不会堆出多条。
     */
    void rememberDevice(String base, String token, String label) {
        if (base == null || base.trim().isEmpty() || token == null || token.trim().isEmpty()) return;
        String address = base.trim();
        List<Device> list = devices();
        Device target = null;
        for (Device device : list) {
            // 同一台设备的两个入口（局域网 / 公网）视为同一台：命中任一条就更新这一条，
            // 否则一次配对会堆出两条记录，切换设备时还会互相顶掉令牌。
            if (device.base.equals(address) || device.alternateBase.equals(address)) {
                target = device;
                break;
            }
        }
        if (target == null) {
            target = new Device();
            target.base = address;
            list.add(target);
        }
        target.token = token.trim();
        if (label != null && !label.trim().isEmpty()) target.label = label.trim();
        target.lastUsed = System.currentTimeMillis();

        saveDevices(list);
        prefs.edit()
                .putString(KEY_BASE, target.base)
                .putString(KEY_TOKEN, target.token)
                .apply();
    }

    /**
     * 记下这台设备的另一个入口（另一次配对拿到的另一种地址）。
     *
     * 配对响应里同时给了局域网与公网地址，两个入口共用一个令牌；
     * 记下另一条之后，用户换网络时界面可以提示「改用另一条」而不是重新配对。
     */
    void rememberAlternate(String currentBase, String alternateBase, String deviceId) {
        if (currentBase == null || currentBase.trim().isEmpty()) return;
        String address = currentBase.trim();
        List<Device> list = devices();
        Device target = null;
        for (Device device : list) {
            if (device.base.equals(address) || device.alternateBase.equals(address)) {
                target = device;
                break;
            }
        }
        if (target == null) return;
        if (alternateBase != null && !alternateBase.trim().isEmpty()
                && !alternateBase.trim().equals(target.base)) {
            target.alternateBase = alternateBase.trim();
        }
        if (deviceId != null && !deviceId.trim().isEmpty()) target.deviceId = deviceId.trim();
        saveDevices(list);
    }

    /** 某台设备上的另一个可用地址（没有则返回空串）。 */
    String alternateFor(String base) {
        if (base == null) return "";
        for (Device device : devices()) {
            if (device.base.equals(base)) return device.alternateBase == null ? "" : device.alternateBase;
        }
        return "";
    }

    /** 切换到一台已记住的设备。 */
    void useDevice(Device device) {
        if (device == null || !device.valid()) return;
        // 先读列表再改时间：devices() 每次都从存档重建对象，
        // 改传入对象上的时间不会反映到列表里，必须改列表里的那一条。
        List<Device> list = devices();
        for (Device item : list) {
            if (item.base.equals(device.base)) {
                item.lastUsed = System.currentTimeMillis();
                break;
            }
        }
        saveDevices(list);
        prefs.edit()
                .putString(KEY_BASE, device.base)
                .putString(KEY_TOKEN, device.token)
                .remove(KEY_LAST_SESSION)
                .remove(KEY_LAST_SESSION_TITLE)
                .apply();
    }

    /** 忘掉一台设备。 */
    void forgetDevice(Device device) {
        if (device == null) return;
        List<Device> list = devices();
        Device removed = null;
        for (Device item : list) {
            if (item.base.equals(device.base)) {
                removed = item;
                break;
            }
        }
        if (removed == null) return;
        list.remove(removed);
        saveDevices(list);
        if (removed.base.equals(base())) {
            // 删的正是当前这台：清空当前连接，避免继续拿废令牌去请求。
            prefs.edit().remove(KEY_BASE).remove(KEY_TOKEN)
                    .remove(KEY_LAST_SESSION).remove(KEY_LAST_SESSION_TITLE).apply();
        }
    }

    // ------------------------------------------------------------ 当前设备（原有语义）

    /** 中继地址（形如 host:port 或 https://host）。 */
    String base() {
        String value = prefs.getString(KEY_BASE, "");
        return value == null ? "" : value;
    }

    String token() {
        String value = prefs.getString(KEY_TOKEN, "");
        return value == null ? "" : value;
    }

    String lastSession() {
        String value = prefs.getString(KEY_LAST_SESSION, "");
        return value == null ? "" : value;
    }

    String lastSessionTitle() {
        String value = prefs.getString(KEY_LAST_SESSION_TITLE, "");
        return value == null ? "" : value;
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
        // 列表里对应那台也去掉令牌，否则切换回来又是一个废令牌。
        String address = base();
        List<Device> list = devices();
        boolean changed = false;
        for (Device device : list) {
            if (device.base.equals(address)) {
                device.token = "";
                changed = true;
                break;
            }
        }
        if (changed) saveDevices(list);
    }

    boolean paired() {
        return !base().isEmpty() && !token().isEmpty();
    }

    // ------------------------------------------------------------ 外观偏好

    private static final String KEY_THEME = "theme";
    private static final String KEY_ROUND = "roundOptimized";
    private static final String KEY_DEVICE_ID = "deviceId";

    /** 界面主题名（dark / light）。默认深色。 */
    String theme() {
        String value = prefs.getString(KEY_THEME, "");
        return value == null || value.isEmpty() ? Theme.DARK : value;
    }

    void setTheme(String name) {
        prefs.edit().putString(KEY_THEME, Theme.LIGHT.equals(name) ? Theme.LIGHT : Theme.DARK).apply();
    }

    /** 是否启用圆屏安全区。默认开启（与既有效果一致）。 */
    boolean roundOptimized() {
        return prefs.getBoolean(KEY_ROUND, true);
    }

    void setRoundOptimized(boolean enabled) {
        prefs.edit().putBoolean(KEY_ROUND, enabled).apply();
    }

    /**
     * 本机的设备标识。
     *
     * 首次取用时生成并永久保存：中继用它认出「这台手表」，
     * 用户勾过「保存为常用设备」之后，它再来配对就不必弹窗。
     * 用随机值而不是机型名：两块同型号的手表不该被当成同一台。
     */
    String deviceId() {
        String value = prefs.getString(KEY_DEVICE_ID, "");
        if (value != null && !value.isEmpty()) return value;
        String generated = randomId();
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply();
        return generated;
    }

    private static String randomId() {
        try {
            java.security.SecureRandom random = new java.security.SecureRandom();
            byte[] bytes = new byte[12];
            random.nextBytes(bytes);
            StringBuilder builder = new StringBuilder();
            for (byte value : bytes) builder.append(String.format("%02x", value));
            return builder.toString();
        } catch (Exception error) {
            return "dev-" + System.currentTimeMillis();
        }
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
