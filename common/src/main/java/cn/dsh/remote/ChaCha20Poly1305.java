package cn.dsh.remote;

import java.math.BigInteger;

/**
 * ChaCha20-Poly1305 AEAD（RFC 8439）。
 *
 * Android 从 API 28 才有 ChaCha20-Poly1305 的 JCE 提供者，手表常跑 API 26/27；
 * 自己实现可以保证所有设备一致，也避免依赖厂商裁剪过的加密提供者。
 *
 * Poly1305 用 BigInteger 实现：正确性优先，每次 AEAD 只有几十次 130 位运算，
 * 在手表上实测开销可忽略；分段位运算版本容易在进位处理上出错。
 */
public final class ChaCha20Poly1305 {
    private ChaCha20Poly1305() {}

    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 12;
    public static final int TAG_BYTES = 16;

    private static final BigInteger P1305 = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5));
    private static final BigInteger CLAMP_MASK = new BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16);

    /** 加密；返回「密文 || 16 字节 tag」。 */
    public static byte[] seal(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext) {
        requireLength(key, KEY_BYTES, "密钥");
        requireLength(nonce, NONCE_BYTES, "nonce");
        if (aad == null) aad = new byte[0];
        if (plaintext == null) plaintext = new byte[0];

        int[] base = initialState(key, nonce);
        int[] block = new int[16];
        chachaBlock(base, 0, block);
        byte[] polyKey = new byte[32];
        for (int i = 0; i < 8; i++) writeIntLE(polyKey, i * 4, block[i]);

        byte[] ciphertext = new byte[plaintext.length];
        int counter = 1;
        for (int offset = 0; offset < plaintext.length; offset += 64) {
            chachaBlock(base, counter, block);
            counter++;
            byte[] keystream = new byte[64];
            for (int i = 0; i < 16; i++) writeIntLE(keystream, i * 4, block[i]);
            int take = Math.min(64, plaintext.length - offset);
            for (int i = 0; i < take; i++) ciphertext[offset + i] = (byte) (plaintext[offset + i] ^ keystream[i]);
        }

        byte[] tag = poly1305(polyKey, aad, ciphertext);
        byte[] out = new byte[ciphertext.length + TAG_BYTES];
        System.arraycopy(ciphertext, 0, out, 0, ciphertext.length);
        System.arraycopy(tag, 0, out, ciphertext.length, TAG_BYTES);
        return out;
    }

    /** 解密；tag 不符抛异常，绝不返回未校验的明文。 */
    public static byte[] open(byte[] key, byte[] nonce, byte[] aad, byte[] sealed) {
        requireLength(key, KEY_BYTES, "密钥");
        requireLength(nonce, NONCE_BYTES, "nonce");
        if (aad == null) aad = new byte[0];
        if (sealed == null || sealed.length < TAG_BYTES) throw new IllegalArgumentException("密文短于 tag 长度");

        int cipherLength = sealed.length - TAG_BYTES;
        byte[] ciphertext = Bytes.slice(sealed, 0, cipherLength);
        byte[] tag = Bytes.slice(sealed, cipherLength, sealed.length);

        int[] base = initialState(key, nonce);
        int[] block = new int[16];
        chachaBlock(base, 0, block);
        byte[] polyKey = new byte[32];
        for (int i = 0; i < 8; i++) writeIntLE(polyKey, i * 4, block[i]);

        byte[] expected = poly1305(polyKey, aad, ciphertext);
        if (!Bytes.equalConstantTime(expected, tag)) throw new SecurityException("ChaCha20-Poly1305 认证失败");

        byte[] plaintext = new byte[cipherLength];
        int counter = 1;
        for (int offset = 0; offset < cipherLength; offset += 64) {
            chachaBlock(base, counter, block);
            counter++;
            byte[] keystream = new byte[64];
            for (int i = 0; i < 16; i++) writeIntLE(keystream, i * 4, block[i]);
            int take = Math.min(64, cipherLength - offset);
            for (int i = 0; i < take; i++) plaintext[offset + i] = (byte) (ciphertext[offset + i] ^ keystream[i]);
        }
        return plaintext;
    }

    private static void requireLength(byte[] value, int expected, String label) {
        if (value == null || value.length != expected) throw new IllegalArgumentException(label + " 必须是 " + expected + " 字节");
    }

    private static int[] initialState(byte[] key, byte[] nonce) {
        int[] state = new int[16];
        state[0] = 0x61707865;
        state[1] = 0x3320646e;
        state[2] = 0x79622d32;
        state[3] = 0x6b206574;
        for (int i = 0; i < 8; i++) state[4 + i] = readIntLE(key, i * 4);
        state[13] = readIntLE(nonce, 0);
        state[14] = readIntLE(nonce, 4);
        state[15] = readIntLE(nonce, 8);
        return state;
    }

    private static void chachaBlock(int[] base, int counter, int[] out) {
        int[] state = base.clone();
        state[12] = counter;
        int[] x = state.clone();
        for (int round = 0; round < 10; round++) {
            quarterRound(x, 0, 4, 8, 12);
            quarterRound(x, 1, 5, 9, 13);
            quarterRound(x, 2, 6, 10, 14);
            quarterRound(x, 3, 7, 11, 15);
            quarterRound(x, 0, 5, 10, 15);
            quarterRound(x, 1, 6, 11, 12);
            quarterRound(x, 2, 7, 8, 13);
            quarterRound(x, 3, 4, 9, 14);
        }
        for (int i = 0; i < 16; i++) out[i] = x[i] + state[i];
    }

    private static void quarterRound(int[] x, int a, int b, int c, int d) {
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] ^ x[a], 16);
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] ^ x[c], 12);
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] ^ x[a], 8);
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] ^ x[c], 7);
    }

    /**
     * Poly1305：r = clamp(key[0..16])，s = key[16..32]，
     * 对每 16 字节块做 h = ((h + block||0x01) * r) mod (2^130 - 5)，
     * 最终 tag = (h + s) mod 2^128。
     */
    private static byte[] poly1305(byte[] key, byte[] aad, byte[] ciphertext) {
        byte[] rBytes = Bytes.slice(key, 0, 16);
        BigInteger r = new BigInteger(1, reverse(rBytes)).and(CLAMP_MASK);
        BigInteger s = new BigInteger(1, reverse(Bytes.slice(key, 16, 32)));
        BigInteger acc = BigInteger.ZERO;

        acc = absorbPadded(acc, r, aad);
        acc = absorbPadded(acc, r, ciphertext);

        byte[] lengthBlock = new byte[16];
        writeLongLE(lengthBlock, 0, aad.length);
        writeLongLE(lengthBlock, 8, ciphertext.length);
        acc = absorb(acc, r, lengthBlock);

        BigInteger tagValue = acc.add(s).and(BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE));
        // acc/s 都是按小端解释的整数，输出时转回小端字节串。
        return toFixedLengthLE(tagValue, 16);
    }

    /**
     * AEAD 的 Poly1305 输入：aad 与密文各自补零到 16 字节对齐，
     * 每段都按完整块（含 2^128 高位）吸收。最后再吸收 16 字节长度块。
     */
    private static BigInteger absorbPadded(BigInteger acc, BigInteger r, byte[] data) {
        int offset = 0;
        while (offset < data.length) {
            int take = Math.min(16, data.length - offset);
            byte[] block = new byte[16];
            System.arraycopy(data, offset, block, 0, take);
            // 不足 16 字节的部分补零，仍然按满块处理（加 2^128）。
            acc = acc.add(new BigInteger(1, reverse(block)).add(BigInteger.ONE.shiftLeft(128)))
                    .multiply(r).mod(P1305);
            offset += 16;
        }
        return acc;
    }

    /** 按 16 字节块吸收；末块不足 16 字节时补 0x01 后整体当整数。 */
    private static BigInteger absorb(BigInteger acc, BigInteger r, byte[] data) {
        int offset = 0;
        while (offset < data.length) {
            int take = Math.min(16, data.length - offset);
            byte[] block = new byte[16];
            System.arraycopy(data, offset, block, 0, take);
            BigInteger value;
            if (take == 16) {
                // 满块：在高位加 2^128
                value = new BigInteger(1, reverse(block)).add(BigInteger.ONE.shiftLeft(128));
            } else {
                // 末块：把 0x01 写在数据之后，按「数据 + 0x01」这 take+1 个字节解释。
                // 注意是低位在前（小端），所以补的 0x01 恰好在第 take 字节。
                block[take] = 1;
                byte[] trimmed = Bytes.slice(block, 0, take + 1);
                value = new BigInteger(1, reverse(trimmed));
            }
            acc = acc.add(value).multiply(r).mod(P1305);
            offset += 16;
        }
        return acc;
    }

    /** 返回补到 16 字节对齐所需的零字节（已对齐时为空）。 */
    private static byte[] zeroPad(int length) {
        int remainder = length % 16;
        return remainder == 0 ? new byte[0] : new byte[16 - remainder];
    }

    private static byte[] reverse(byte[] data) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) out[i] = data[data.length - 1 - i];
        return out;
    }

    /** 把整数按小端写出固定长度字节串。 */
    private static byte[] toFixedLengthLE(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        int start = 0;
        while (start < raw.length - 1 && raw[start] == 0) start++;
        byte[] out = new byte[length];
        int count = Math.min(length, raw.length - start);
        for (int i = 0; i < count; i++) out[i] = raw[raw.length - 1 - i];
        return out;
    }

    private static byte[] toFixedLength(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[length];
        int start = 0;
        while (start < raw.length - 1 && raw[start] == 0) start++;
        int count = Math.min(length, raw.length - start);
        for (int i = 0; i < count; i++) out[i] = raw[raw.length - 1 - i];
        return out;
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    private static void writeIntLE(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    private static void writeLongLE(byte[] data, int offset, long value) {
        for (int i = 0; i < 8; i++) data[offset + i] = (byte) (value >>> (8 * i));
    }
}
