package cn.dsh.remote;

import java.math.BigInteger;

/**
 * X25519（Curve25519 的 Diffie-Hellman）。
 *
 * 不直接用 java.security KeyAgreement("XDH")：它在 Android 11 以下普遍缺失
 * （minSdk 26 只是「可能支持」），而手表这类设备恰恰可能是老系统。
 * 这里按 RFC 7748 实现 Montgomery ladder，一次握手只做 2~3 次标量乘，速度够用。
 */
public final class X25519 {
    private X25519() {}

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);
    private static final BigInteger ONE = BigInteger.ONE;
    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final int KEY_BYTES = 32;

    /** 从私钥推导公钥（标量乘基点 9）。 */
    public static byte[] publicKey(byte[] privateKey) {
        return scalarMult(privateKey, basePoint());
    }

    /** 生成一对新密钥。 */
    public static byte[][] generateKeyPair() {
        byte[] priv = Bytes.random(KEY_BYTES);
        return new byte[][] {priv, publicKey(priv)};
    }

    /** 计算共享密钥。输入输出都是 32 字节小端字节串。 */
    public static byte[] sharedSecret(byte[] privateKey, byte[] peerPublicKey) {
        if (privateKey.length != KEY_BYTES) throw new IllegalArgumentException("X25519 私钥必须是 32 字节");
        if (peerPublicKey.length != KEY_BYTES) throw new IllegalArgumentException("X25519 公钥必须是 32 字节");
        byte[] secret = scalarMult(privateKey, peerPublicKey);
        boolean allZero = true;
        for (byte b : secret) if (b != 0) { allZero = false; break; }
        if (allZero) throw new IllegalStateException("X25519 得到全零共享密钥（低阶点）");
        return secret;
    }

    private static byte[] basePoint() {
        byte[] point = new byte[KEY_BYTES];
        point[0] = 9;
        return point;
    }

    /**
     * RFC 7748 §5 的 Montgomery ladder。
     *
     * 用 BigInteger 而非定长字节运算：这里只有 255 轮，每轮几次 255 位乘法，
     * 单次握手总耗时在毫秒级，换来的是实现简单、不易出错。
     */
    private static byte[] scalarMult(byte[] scalar, byte[] point) {
        byte[] k = scalar.clone();
        k[0] &= (byte) 248;
        k[31] &= (byte) 127;
        k[31] |= 64;

        BigInteger x1 = fromLittleEndian(point).mod(P);
        BigInteger x2 = ONE;
        BigInteger z2 = BigInteger.ZERO;
        BigInteger x3 = x1;
        BigInteger z3 = ONE;
        int swap = 0;

        for (int t = 254; t >= 0; t--) {
            int kt = (k[t >>> 3] >>> (t & 7)) & 1;
            swap ^= kt;
            if (swap == 1) {
                BigInteger tmp = x2; x2 = x3; x3 = tmp;
                tmp = z2; z2 = z3; z3 = tmp;
            }
            swap = kt;

            BigInteger a = x2.add(z2).mod(P);
            BigInteger aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P);
            BigInteger bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P);
            BigInteger d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P);
            BigInteger cb = c.multiply(b).mod(P);
            BigInteger sum = da.add(cb).mod(P);
            BigInteger diff = da.subtract(cb).mod(P);

            x3 = sum.multiply(sum).mod(P);
            z3 = x1.multiply(diff.multiply(diff).mod(P)).mod(P);
            x2 = aa.multiply(bb).mod(P);
            z2 = e.multiply(aa.add(A24.multiply(e).mod(P)).mod(P)).mod(P);
        }

        if (swap == 1) {
            x2 = x3;
            z2 = z3;
        }

        BigInteger result = x2.multiply(z2.modPow(P.subtract(TWO), P)).mod(P);
        return toLittleEndian(result);
    }

    private static BigInteger fromLittleEndian(byte[] data) {
        byte[] big = new byte[data.length];
        for (int i = 0; i < data.length; i++) big[i] = data[data.length - 1 - i];
        return new BigInteger(1, big);
    }

    private static byte[] toLittleEndian(BigInteger value) {
        byte[] big = value.toByteArray();
        int start = 0;
        while (start < big.length - 1 && big[start] == 0) start++;
        byte[] out = new byte[KEY_BYTES];
        int count = big.length - start;
        for (int i = 0; i < count && i < KEY_BYTES; i++) {
            out[i] = big[big.length - 1 - i];
        }
        return out;
    }
}
