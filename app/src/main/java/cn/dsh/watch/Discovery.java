package cn.dsh.watch;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 局域网发现：监听电脑端中继周期性发出的 UDP 广播，自动填出地址。
 *
 * 用广播而不是扫描网段：手表上扫描 254 个地址既慢又耗电，而广播只需一次接收。
 * 广播是「尽力而为」的，所以这里同时保留手动输入地址的入口。
 */
final class Discovery {
    /** 与中继约定的发现端口。 */
    private static final int DISCOVERY_PORT = 19391;
    private static final String SERVICE_TAG = "dsh-watch-relay/1";
    /** 单个广播服务的接收等待上限。 */
    private static final int LISTEN_TIMEOUT_MS = 3_000;

    /** 发现到的一个中继。 */
    static final class Found {
        final String host;
        final int port;

        Found(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    interface Callback {
        /** 收到至少一个服务时回调；超时未发现则传入空列表。 */
        void onFound(List<Found> services);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private DatagramSocket socket;

    /** 监听广播，最多等待 timeoutMs；结果去重后回主线程。 */
    void listen(final Callback callback) {
        new Thread(() -> {
            Set<String> seen = new LinkedHashSet<>();
            List<Found> found = new ArrayList<>();
            DatagramSocket local = null;
            try {
                local = new DatagramSocket(null);
                local.setReuseAddress(true);
                local.bind(new InetSocketAddress(DISCOVERY_PORT));
                local.setSoTimeout(LISTEN_TIMEOUT_MS);
                socket = local;
                long deadline = System.currentTimeMillis() + LISTEN_TIMEOUT_MS;
                byte[] buffer = new byte[1024];
                while (System.currentTimeMillis() < deadline) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        local.receive(packet);
                    } catch (SocketTimeoutException timeout) {
                        break;
                    }
                    String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                    Found service = parse(text, packet.getAddress());
                    if (service == null) continue;
                    String key = service.host + ":" + service.port;
                    if (seen.add(key)) found.add(service);
                }
            } catch (Exception ignored) {
                // 收不到广播不是错误：调用方会回退到手动输入。
            } finally {
                if (local != null) local.close();
                socket = null;
            }
            final List<Found> result = found;
            main.post(() -> callback.onFound(result));
        }, "dsh-discovery").start();
    }

    /** 解析一条广播报文；不是本服务时返回 null。 */
    private static Found parse(String text, InetAddress from) {
        try {
            JSONObject json = new JSONObject(text);
            if (!SERVICE_TAG.equals(json.optString("service", ""))) return null;
            int port = json.optInt("port", 0);
            if (port <= 0 || port > 65535) return null;
            // 以报文源地址为准：电脑可能同时有多个网卡，广播源地址才是可路由的那个。
            return new Found(from.getHostAddress(), port);
        } catch (Exception error) {
            return null;
        }
    }

    void cancel() {
        DatagramSocket local = socket;
        if (local != null) local.close();
    }
}
