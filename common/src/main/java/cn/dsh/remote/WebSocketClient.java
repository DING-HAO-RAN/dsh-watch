package cn.dsh.remote;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.SecureRandom;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 最小 WebSocket 客户端（RFC 6455），只支持本协议需要的部分：
 * 文本帧、二进制帧、分片接收、ping/pong、close。
 *
 * 不依赖 OkHttp 或 Java-WebSocket：三端共用的代码不能引入第三方库，
 * 而 Android 自带的 org.java_websocket 并不存在。
 */
public final class WebSocketClient {
    public interface Listener {
        void onOpen();

        void onText(String text);

        void onBinary(byte[] data);

        void onClosed(int code, String reason);

        void onError(Exception error);
    }

    private static final String MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String url;
    private final Listener listener;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean open;
    private volatile boolean closed;
    private Thread reader;
    private final Object writeLock = new Object();
    private final java.util.concurrent.atomic.AtomicLong pingCounter =
            new java.util.concurrent.atomic.AtomicLong();

    public WebSocketClient(String url, Listener listener) {
        this.url = url;
        this.listener = listener;
    }

    public boolean isOpen() {
        return open;
    }

    /** 建立连接并启动读线程。抛异常表示连接失败。 */
    public void connect(int timeoutMs) throws IOException {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        boolean secure = "wss".equalsIgnoreCase(scheme);
        if (!secure && !"ws".equalsIgnoreCase(scheme)) throw new IOException("不支持的协议：" + scheme);
        String host = uri.getHost();
        int port = uri.getPort() < 0 ? (secure ? 443 : 80) : uri.getPort();
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path = path + "?" + uri.getRawQuery();

        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), timeoutMs);
        raw.setSoTimeout(timeoutMs);
        raw.setTcpNoDelay(true);
        if (secure) {
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(raw, host, port, true);
            ssl.startHandshake();
            socket = ssl;
        } else {
            socket = raw;
        }
        in = new BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();

        String key = handshakeKey();
        StringBuilder request = new StringBuilder();
        request.append("GET ").append(path).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(host);
        if (uri.getPort() >= 0) request.append(':').append(port);
        request.append("\r\n");
        request.append("Upgrade: websocket\r\n");
        request.append("Connection: Upgrade\r\n");
        request.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        request.append("Sec-WebSocket-Version: 13\r\n");
        request.append("\r\n");
        out.write(Bytes.utf8(request.toString()));
        out.flush();

        String response = readHandshakeResponse();
        if (!response.startsWith("HTTP/1.1 101") && !response.startsWith("HTTP/1.0 101")) {
            throw new IOException("WebSocket 握手被拒绝：" + firstLine(response));
        }
        String expected;
        try {
            // Sec-WebSocket-Accept 是 SHA-1(key + MAGIC) 的标准 Base64。
            expected = standardBase64(java.security.MessageDigest.getInstance("SHA-1")
                    .digest(Bytes.utf8(key + MAGIC)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IOException("SHA-1 不可用", error);
        }
        String accept = headerValue(response, "sec-websocket-accept");
        if (accept == null || !accept.trim().equals(expected)) {
            throw new IOException("WebSocket 握手响应校验失败");
        }
        socket.setSoTimeout(0);
        open = true;
        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "dsh-ws-reader");
        reader.setDaemon(true);
        reader.start();
        listener.onOpen();
    }

    /** 发送文本帧。 */
    public void sendText(String text) throws IOException {
        sendFrame(0x1, Bytes.utf8(text));
    }

    /** 主动关闭。 */
    public void close(int code, String reason) {
        if (closed) return;
        closed = true;
        try {
            if (open && socket != null && !socket.isClosed()) {
                byte[] payload = new byte[2 + (reason == null ? 0 : Bytes.utf8(reason).length)];
                payload[0] = (byte) (code >>> 8);
                payload[1] = (byte) code;
                if (reason != null && reason.length() > 0) {
                    byte[] body = Bytes.utf8(reason);
                    System.arraycopy(body, 0, payload, 2, body.length);
                }
                writeFrame(0x8, payload);
            }
        } catch (IOException ignored) {
            // 关闭阶段写失败无需上报。
        }
        open = false;
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // 忽略。
        }
    }

    private void readLoop() {
        int closeCode = 1006;
        String closeReason = "";
        try {
            while (!closed) {
                int b0 = in.read();
                if (b0 < 0) break;
                int b1 = in.read();
                if (b1 < 0) break;
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0f;
                boolean masked = (b1 & 0x80) != 0;
                long length = b1 & 0x7f;
                if (length == 126) {
                    length = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
                } else if (length == 127) {
                    length = 0;
                    for (int i = 0; i < 8; i++) length = (length << 8) | (in.read() & 0xff);
                }
                if (length > 16L * 1024 * 1024) throw new IOException("WebSocket 帧过大：" + length);
                byte[] mask = new byte[4];
                if (masked) readFully(mask);
                byte[] payload = new byte[(int) length];
                readFully(payload);
                if (masked) {
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                }

                if (opcode == 0x8) {
                    if (payload.length >= 2) closeCode = ((payload[0] & 0xff) << 8) | (payload[1] & 0xff);
                    if (payload.length > 2) closeReason = Bytes.fromUtf8(Bytes.slice(payload, 2, payload.length));
                    break;
                }
                if (opcode == 0x9) {
                    writeFrame(0xA, payload);
                    continue;
                }
                if (opcode == 0xA) continue;
                if (opcode == 0x1) {
                    // 分片文本：这里只处理单帧完整文本，协议不会发来长分片文本。
                    if (fin) listener.onText(Bytes.fromUtf8(payload));
                    continue;
                }
                if (opcode == 0x2) {
                    if (fin) listener.onBinary(payload);
                    continue;
                }
                if (opcode == 0x0) continue;
            }
        } catch (Exception error) {
            if (!closed) listener.onError(error instanceof Exception ? error : new IOException(String.valueOf(error)));
        } finally {
            boolean wasOpen = open;
            open = false;
            closed = true;
            closeQuietly();
            if (wasOpen || closeCode != 1006) listener.onClosed(closeCode, closeReason);
        }
    }

    private void readFully(byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) throw new IOException("WebSocket 连接提前关闭");
            offset += read;
        }
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        writeFrame(opcode, payload);
    }

    private void writeFrame(int opcode, byte[] payload) throws IOException {
        synchronized (writeLock) {
            if (socket == null || socket.isClosed()) throw new IOException("WebSocket 已关闭");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(payload.length + 14);
            buffer.write(0x80 | opcode);
            int length = payload.length;
            if (length < 126) {
                buffer.write(0x80 | length);
            } else if (length < 65536) {
                buffer.write(0x80 | 126);
                buffer.write((length >>> 8) & 0xff);
                buffer.write(length & 0xff);
            } else {
                buffer.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) buffer.write((int) (((long) length >>> (8 * i)) & 0xff));
            }
            // 客户端发出的帧必须掩码。
            byte[] mask = new byte[4];
            RANDOM.nextBytes(mask);
            buffer.write(mask, 0, 4);
            byte[] masked = new byte[length];
            for (int i = 0; i < length; i++) masked[i] = (byte) (payload[i] ^ mask[i & 3]);
            buffer.write(masked, 0, masked.length);
            out.write(buffer.toByteArray());
            out.flush();
        }
    }

    private String readHandshakeResponse() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int value = in.read();
            if (value < 0) throw new IOException("WebSocket 握手响应不完整");
            buffer.write(value);
            if ((matched == 0 || matched == 2) && value == '\r') matched++;
            else if ((matched == 1 || matched == 3) && value == '\n') matched++;
            else matched = value == '\r' ? 1 : 0;
            if (buffer.size() > 16384) throw new IOException("WebSocket 握手响应过大");
        }
        return Bytes.fromUtf8(buffer.toByteArray());
    }

    private static String firstLine(String response) {
        int index = response.indexOf("\r\n");
        return index < 0 ? response : response.substring(0, index);
    }

    private static String headerValue(String response, String name) {
        for (String line : response.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            if (line.substring(0, colon).trim().equalsIgnoreCase(name)) return line.substring(colon + 1);
        }
        return null;
    }

    private static String handshakeKey() {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        // 握手 Key 必须是标准 Base64（含 + / 与 padding），不是 Base64URL。
        return standardBase64(nonce);
    }

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
}
