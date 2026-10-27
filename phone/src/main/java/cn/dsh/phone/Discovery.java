package cn.dsh.phone;

import android.content.Context;
import android.net.wifi.WifiManager;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 局域网自动发现：监听中继的 UDP 广播，省掉手输 IP。
 *
 * 中继每 2 秒向 19391 端口广播一行 JSON（service / port / name）。手机这边只需要
 * 一个 DatagramSocket 就能收到，不引入 mDNS 或任何第三方库。
 *
 * 两个实现细节：
 * - 必须持有多播锁（WifiManager.MulticastLock），否则部分机型在息屏或省电模式下
 *   直接丢弃广播包，表现为「明明在同一网络却搜不到」。
 * - 广播是从对方的源地址回的，**用包里的源 IP 拼地址**，不要用本机网段去猜。
 */
final class Discovery {
    private static final int PORT = 19391;
    private static final String SERVICE = "dsh-watch-relay/1";

    interface Listener {
        /** @param base 形如 http://192.168.1.5:19390 */
        void onFound(String base, String name);
    }

    private final Context context;
    private DatagramSocket socket;
    private WifiManager.MulticastLock lock;
    private Thread thread;
    private volatile boolean running;

    Discovery(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 开始监听。重复调用是安全的（已在跑就直接返回）。 */
    void start(final Listener listener) {
        if (running) return;
        running = true;
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                lock = wifi.createMulticastLock("dsh-phone-discovery");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception ignored) { }
        thread = new Thread(new Runnable() {
            @Override public void run() {
                loop(listener);
            }
        }, "dsh-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop(Listener listener) {
        Map<String, Long> seen = new LinkedHashMap<>();
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(PORT));
            socket.setSoTimeout(1_500);
        } catch (Exception error) {
            running = false;
            return;
        }
        byte[] buffer = new byte[2048];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String text = new String(packet.getData(), 0, packet.getLength(), "UTF-8").trim();
                JSONObject object = new JSONObject(text);
                if (!SERVICE.equals(object.optString("service", ""))) continue;
                int port = object.optInt("port", 0);
                if (port <= 0) continue;
                String host = packet.getAddress().getHostAddress();
                String base = "http://" + host + ":" + port;
                long now = System.currentTimeMillis();
                Long last = seen.get(base);
                // 同一个中继每 2 秒广播一次，2 秒内重复的不再回调，避免界面反复刷新。
                if (last != null && now - last < 2_000) continue;
                seen.put(base, now);
                listener.onFound(base, object.optString("name", "DSH 中继"));
            } catch (SocketTimeoutException ignored) {
                // 正常：没有任何广播时继续等。
            } catch (Exception ignored) {
                // 单个坏包不影响后续。
            }
        }
    }

    void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) { }
        socket = null;
        if (lock != null) {
            try {
                if (lock.isHeld()) lock.release();
            } catch (Exception ignored) { }
            lock = null;
        }
    }
}
