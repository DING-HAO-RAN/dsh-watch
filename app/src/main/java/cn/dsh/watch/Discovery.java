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
 * 局域网发现：找电脑上的中继。
 *
 * 两种手段并用：
 * 1. **主动查询**——手表发一条查询广播，中继收到后立刻单播回复（于是不用等运气）；
 * 2. **被动收听**——中继本来每 2 秒就广播一次，顺便也能收到。
 *
 * 主动查询是关键：只靠被动收听时，用户点「寻找电脑」得恰好撞上那 2 秒窗口，
 * 体验上像「时灵时不灵」。
 */
final class Discovery {
    /** 与中继约定的发现端口。 */
    private static final int DISCOVERY_PORT = 19391;
    private static final String SERVICE_TAG = "dsh-watch-relay/1";
    /** 查询标识：中继靠它区分「手表在找」与自己的周期广播。 */
    private static final String QUERY_TAG = "dsh-watch-query";
    /** 等待回复的上限。 */
    private static final int LISTEN_TIMEOUT_MS = 4_000;

    /** 发现到的一个中继。 */
    static final class Found {
        final String host;
        final int port;
        /** 电脑名（中继自报），可能为空。 */
        final String name;
        /** 该中继是否已经配对过设备（界面可用来说明「这台用过」）。 */
        final boolean paired;

        Found(String host, int port, String name, boolean paired) {
            this.host = host;
            this.port = port;
            this.name = name;
            this.paired = paired;
        }
    }

    interface Callback {
        /** 收到至少一个服务时回调；超时未发现则传入空列表。 */
        void onFound(List<Found> services);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private DatagramSocket socket;

    /**
     * 寻找局域网上的中继。
     *
     * 先发查询广播（让电脑立刻回），再静听一段时间收集所有回复。
     */
    void listen(final Callback callback) {
        new Thread(() -> {
            Set<String> seen = new LinkedHashSet<>();
            List<Found> found = new ArrayList<>();
            DatagramSocket local = null;
            try {
                local = new DatagramSocket(null);
                local.setReuseAddress(true);
                local.bind(new InetSocketAddress(DISCOVERY_PORT));
                socket = local;

                sendQuery(local);

                local.setSoTimeout(600);
                long deadline = System.currentTimeMillis() + LISTEN_TIMEOUT_MS;
                byte[] buffer = new byte[2048];
                int roundsWithoutNew = 0;
                while (System.currentTimeMillis() < deadline) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        local.receive(packet);
                    } catch (SocketTimeoutException timeout) {
                        roundsWithoutNew++;
                        // 还没收到任何回复时多发几次查询：广播可能丢，
                        // 而用户点一下只等 4 秒，不能赌一次成功。
                        if (found.isEmpty() && roundsWithoutNew <= 4) sendQuery(local);
                        if (roundsWithoutNew > 6) break;
                        continue;
                    }
                    String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                    Found service = parse(text, packet.getAddress());
                    if (service == null) continue;
                    roundsWithoutNew = 0;
                    String key = service.host + ":" + service.port;
                    if (seen.add(key)) found.add(service);
                }
            } catch (Exception ignored) {
                // 收不到不是错误：调用方会回退到手动输入。
            } finally {
                if (local != null) local.close();
                socket = null;
            }
            final List<Found> result = found;
            main.post(() -> callback.onFound(result));
        }, "dsh-discovery").start();
    }

    /** 发一条查询广播，请求局域网上的中继报出自己。 */
    private static void sendQuery(DatagramSocket local) {
        try {
            local.setBroadcast(true);
            JSONObject query = new JSONObject();
            query.put("service", QUERY_TAG);
            query.put("want", "relay");
            byte[] bytes = query.toString().getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length,
                    InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
            local.send(packet);
            // 再往子网广播地址发一次：部分设备上 255.255.255.255 会被路由丢弃，
            // 而按网段算出来的 x.x.x.255 更容易直达。
            try {
                String subnet = subnetBroadcast();
                if (subnet != null) {
                    local.send(new DatagramPacket(bytes, bytes.length,
                            InetAddress.getByName(subnet), DISCOVERY_PORT));
                }
            } catch (Exception ignored) {
                // 拿不到本机地址就只发全局广播。
            }
        } catch (Exception ignored) {
            // 广播发不出去时静默失败，调用方会回退到手动输入。
        }
    }

    /**
     * 算出本网段的定向广播地址（如 192.168.1.255）。
     *
     * 没有它时只能依赖 255.255.255.255，而部分 Android 设备/路由器会丢弃全局广播，
     * 表现就是「一键配对找不到电脑」。
     */
    private static String subnetBroadcast() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nics =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                java.net.NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (java.net.InterfaceAddress address : nic.getInterfaceAddresses()) {
                    java.net.InetAddress broadcast = address.getBroadcast();
                    if (broadcast != null) return broadcast.getHostAddress();
                }
            }
        } catch (Exception ignored) {
            // 拿不到就用全局广播。
        }
        return null;
    }

    /** 解析一条报文；不是本服务时返回 null。 */
    private static Found parse(String text, InetAddress from) {
        try {
            JSONObject json = new JSONObject(text);
            if (!SERVICE_TAG.equals(json.optString("service", ""))) return null;
            int port = json.optInt("port", 0);
            if (port <= 0 || port > 65535) return null;
            // 以报文源地址为准：电脑可能同时有多个网卡，广播源地址才是可路由的那个。
            return new Found(from.getHostAddress(), port,
                    json.optString("name", ""), json.optBoolean("paired", false));
        } catch (Exception error) {
            return null;
        }
    }

    void cancel() {
        DatagramSocket local = socket;
        if (local != null) local.close();
    }
}
