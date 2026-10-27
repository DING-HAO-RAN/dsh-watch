package cn.dsh.phone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 本地存储：设备列表、令牌、主题、最近访问。
 *
 * 用 SharedPreferences 存整段 JSON 而不是拆成一堆键：设备是一个结构化列表
 * （地址 + 备用地址 + 令牌 + 备注 + 设备 id），拆散后增删设备要改十几处键名，
 * 而整段 JSON 只需要一个版本号就能演进。
 *
 * 令牌跨启动保留——这是需求方明确要求（配对一次长期有效，除非用户主动删设备）。
 */
final class Store {
    private static final String FILE = "dsh-phone";
    private static final String KEY_STATE = "state";

    /** 一台已配对的中继。base 是主地址，alternateBase 是公网备用地址。 */
    static final class Device {
        String base = "";
        String alternateBase = "";
        String token = "";
        String note = "";
        long lastUsed = 0;
        String deviceId = "";

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("base", base);
                object.put("alternateBase", alternateBase);
                object.put("token", token);
                object.put("note", note);
                object.put("lastUsed", lastUsed);
                object.put("deviceId", deviceId);
            } catch (Exception ignored) { }
            return object;
        }

        static Device fromJson(JSONObject object) {
            Device device = new Device();
            device.base = object.optString("base", "");
            device.alternateBase = object.optString("alternateBase", "");
            device.token = object.optString("token", "");
            device.note = object.optString("note", "");
            device.lastUsed = object.optLong("lastUsed", 0);
            device.deviceId = object.optString("deviceId", "");
            return device;
        }

        String label() {
            if (note != null && note.trim().length() > 0) return note.trim();
            String value = base.replace("http://", "").replace("https://", "");
            int colon = value.indexOf(':');
            return colon > 0 ? value.substring(0, colon) : value;
        }
    }

    private final SharedPreferences preferences;
    private final List<Device> devices = new ArrayList<>();
    private int currentIndex = -1;
    private String themeName = Theme.DARK;
    private String lastSessionId = "";
    private String lastSessionTitle = "";

    Store(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        String raw = preferences.getString(KEY_STATE, "");
        if (raw == null || raw.length() == 0) return;
        try {
            JSONObject root = new JSONObject(raw);
            themeName = root.optString("theme", Theme.DARK);
            lastSessionId = root.optString("lastSessionId", "");
            lastSessionTitle = root.optString("lastSessionTitle", "");
            currentIndex = root.optInt("current", -1);
            JSONArray array = root.optJSONArray("devices");
            if (array != null) {
                for (int i = 0; i < array.length(); i += 1) {
                    JSONObject item = array.optJSONObject(i);
                    if (item != null) devices.add(Device.fromJson(item));
                }
            }
            if (currentIndex >= devices.size()) currentIndex = devices.isEmpty() ? -1 : 0;
        } catch (Exception ignored) {
            devices.clear();
            currentIndex = -1;
        }
    }

    void save() {
        JSONObject root = new JSONObject();
        try {
            root.put("theme", themeName);
            root.put("current", currentIndex);
            root.put("lastSessionId", lastSessionId);
            root.put("lastSessionTitle", lastSessionTitle);
            JSONArray array = new JSONArray();
            for (Device device : devices) array.put(device.toJson());
            root.put("devices", array);
        } catch (Exception ignored) { }
        preferences.edit().putString(KEY_STATE, root.toString()).apply();
    }

    List<Device> devices() {
        return devices;
    }

    boolean hasDevice() {
        return currentIndex >= 0 && currentIndex < devices.size();
    }

    /** 当前设备；没有配对过时返回 null。 */
    Device current() {
        if (!hasDevice()) return null;
        return devices.get(currentIndex);
    }

    int currentIndex() {
        return currentIndex;
    }

    void switchTo(int index) {
        if (index < 0 || index >= devices.size()) return;
        currentIndex = index;
        save();
    }

    /** 地址：优先主地址；主地址连不上时由调用方试 alternateBase。 */
    String base() {
        Device device = current();
        return device == null ? "" : device.base;
    }

    String alternateBase() {
        Device device = current();
        return device == null ? "" : device.alternateBase;
    }

    String token() {
        Device device = current();
        return device == null ? "" : device.token;
    }

    String deviceId() {
        Device device = current();
        if (device == null) return "";
        if (device.deviceId == null || device.deviceId.length() == 0) {
            device.deviceId = UUID.randomUUID().toString();
            save();
        }
        return device.deviceId;
    }

    /**
     * 记住一次配对结果。
     *
     * 命中 base 或 alternateBase 都算同一台设备：一次配对会同时拿到局域网地址与
     * 公网地址，若不合并，同一个中继会在列表里堆出两条并互相顶掉令牌。
     */
    void remember(String base, String alternateBase, String token, String note) {
        for (int i = 0; i < devices.size(); i += 1) {
            Device device = devices.get(i);
            if (device.base.equals(base) || device.alternateBase.equals(base)
                    || (alternateBase.length() > 0
                    && (device.base.equals(alternateBase) || device.alternateBase.equals(alternateBase)))) {
                device.base = base;
                device.alternateBase = alternateBase;
                device.token = token;
                if (note != null && note.length() > 0) device.note = note;
                device.lastUsed = System.currentTimeMillis();
                currentIndex = i;
                save();
                return;
            }
        }
        Device device = new Device();
        device.base = base;
        device.alternateBase = alternateBase;
        device.token = token;
        device.note = note == null ? "" : note;
        device.lastUsed = System.currentTimeMillis();
        device.deviceId = UUID.randomUUID().toString();
        devices.add(device);
        currentIndex = devices.size() - 1;
        save();
    }

    void forget(int index) {
        if (index < 0 || index >= devices.size()) return;
        devices.remove(index);
        if (currentIndex >= devices.size()) currentIndex = devices.size() - 1;
        if (currentIndex < 0 && !devices.isEmpty()) currentIndex = 0;
        save();
    }

    void rename(int index, String note) {
        if (index < 0 || index >= devices.size()) return;
        devices.get(index).note = note == null ? "" : note;
        save();
    }

    String themeName() {
        return themeName;
    }

    void setThemeName(String name) {
        themeName = Theme.LIGHT.equals(name) ? Theme.LIGHT : Theme.DARK;
        save();
    }

    String lastSessionId() {
        return lastSessionId;
    }

    String lastSessionTitle() {
        return lastSessionTitle;
    }

    void setLastSession(String id, String title) {
        lastSessionId = id == null ? "" : id;
        lastSessionTitle = title == null ? "" : title;
        save();
    }
}
