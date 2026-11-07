package cn.dsh.remote;

/**
 * 字节与 Base64URL 工具。
 *
 * 不依赖 android.util.Base64 或 java.util.Base64：前者绑死 Android，
 * 后者在部分旧运行时上行为不同；协议要求无 padding 的规范 Base64URL，
 * 自己实现才能保证跨端逐字节一致。
 */
public final class Bytes {
    private Bytes() {}

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    private static final int[] LOOKUP = new int[128];

    static {
        for (int i = 0; i < LOOKUP.length; i++) LOOKUP[i] = -1;
        for (int i = 0; i < ALPHABET.length; i++) LOOKUP[ALPHABET[i]] = i;
        // 兼容标准 Base64 的两个符号，便于解析外部输入的 token。
        LOOKUP['+'] = 62;
        LOOKUP['/'] = 63;
    }

    /** 编码为无 padding 的 Base64URL。 */
    public static String b64u(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 4 + 2) / 3);
        int i = 0;
        while (i + 3 <= data.length) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8) | (data[i + 2] & 0xff);
            out.append(ALPHABET[(n >>> 18) & 0x3f]);
            out.append(ALPHABET[(n >>> 12) & 0x3f]);
            out.append(ALPHABET[(n >>> 6) & 0x3f]);
            out.append(ALPHABET[n & 0x3f]);
            i += 3;
        }
        int rest = data.length - i;
        if (rest == 1) {
            int n = (data[i] & 0xff) << 16;
            out.append(ALPHABET[(n >>> 18) & 0x3f]);
            out.append(ALPHABET[(n >>> 12) & 0x3f]);
        } else if (rest == 2) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8);
            out.append(ALPHABET[(n >>> 18) & 0x3f]);
            out.append(ALPHABET[(n >>> 12) & 0x3f]);
            out.append(ALPHABET[(n >>> 6) & 0x3f]);
        }
        return out.toString();
    }

    /** 解析 Base64URL；容忍缺失 padding，遇到非法字符抛异常而不是静默跳过。 */
    public static byte[] unb64u(String text) {
        if (text == null) throw new IllegalArgumentException("Base64URL 文本为 null");
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '=') end--;
        int length = end;
        int outLength = length * 3 / 4;
        byte[] out = new byte[outLength];
        int buffer = 0;
        int bits = 0;
        int written = 0;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            int value = c < 128 ? LOOKUP[c] : -1;
            if (value < 0) throw new IllegalArgumentException("非法的 Base64URL 字符：" + c);
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[written++] = (byte) ((buffer >>> bits) & 0xff);
            }
        }
        if (written == outLength) return out;
        byte[] trimmed = new byte[written];
        System.arraycopy(out, 0, trimmed, 0, written);
        return trimmed;
    }

    public static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) total += part.length;
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    public static byte[] slice(byte[] data, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(data, from, out, 0, out.length);
        return out;
    }

    /** 定时安全比较，避免按字节提前返回泄露密钥信息。 */
    public static boolean equalConstantTime(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

    public static byte[] utf8(String text) {
        try {
            return text.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException error) {
            throw new IllegalStateException("UTF-8 不可用", error);
        }
    }

    public static String fromUtf8(byte[] data) {
        try {
            return new String(data, "UTF-8");
        } catch (java.io.UnsupportedEncodingException error) {
            throw new IllegalStateException("UTF-8 不可用", error);
        }
    }

    public static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) {
            out.append(Character.forDigit((b >> 4) & 0xf, 16));
            out.append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }

    /** 随机字节；Android 与桌面 JVM 都有 SecureRandom。 */
    public static byte[] random(int length) {
        byte[] out = new byte[length];
        new java.security.SecureRandom().nextBytes(out);
        return out;
    }
}
