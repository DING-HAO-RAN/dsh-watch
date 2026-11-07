package cn.dsh.remote;

/**
 * SHA-256（FIPS 180-4）与基于它的 HMAC/HKDF。
 *
 * 自己实现而不用 MessageDigest：Android 的 SHA-256 提供者普遍存在，
 * 但这里同时要 HMAC-SHA256 的确定性行为；集中在一处实现便于和上游
 * 的 noble/hashes 输出逐字节对照。
 */
public final class Sha256 {
    private Sha256() {}

    private static final int[] K = {
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    };

    public static byte[] hash(byte[] data) {
        int[] state = {
                0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
        };

        // 补位：0x80 后补零，直到长度 ≡ 56 (mod 64)，末尾 8 字节大端写比特长度。
        long bitLength = (long) data.length * 8L;
        int paddedLength = ((data.length + 9 + 63) / 64) * 64;
        byte[] padded = new byte[paddedLength];
        System.arraycopy(data, 0, padded, 0, data.length);
        padded[data.length] = (byte) 0x80;
        for (int i = 0; i < 8; i++) {
            padded[paddedLength - 1 - i] = (byte) (bitLength >>> (8 * i));
        }

        int[] w = new int[64];
        for (int offset = 0; offset < paddedLength; offset += 64) {
            for (int i = 0; i < 16; i++) {
                w[i] = ((padded[offset + i * 4] & 0xff) << 24)
                        | ((padded[offset + i * 4 + 1] & 0xff) << 16)
                        | ((padded[offset + i * 4 + 2] & 0xff) << 8)
                        | (padded[offset + i * 4 + 3] & 0xff);
            }
            for (int i = 16; i < 64; i++) {
                int s0 = Integer.rotateRight(w[i - 15], 7) ^ Integer.rotateRight(w[i - 15], 18) ^ (w[i - 15] >>> 3);
                int s1 = Integer.rotateRight(w[i - 2], 17) ^ Integer.rotateRight(w[i - 2], 19) ^ (w[i - 2] >>> 10);
                w[i] = w[i - 16] + s0 + w[i - 7] + s1;
            }

            int a = state[0], b = state[1], c = state[2], d = state[3];
            int e = state[4], f = state[5], g = state[6], h = state[7];
            for (int i = 0; i < 64; i++) {
                int s1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25);
                int ch = (e & f) ^ ((~e) & g);
                int temp1 = h + s1 + ch + K[i] + w[i];
                int s0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22);
                int maj = (a & b) ^ (a & c) ^ (b & c);
                int temp2 = s0 + maj;
                h = g; g = f; f = e; e = d + temp1;
                d = c; c = b; b = a; a = temp1 + temp2;
            }
            state[0] += a; state[1] += b; state[2] += c; state[3] += d;
            state[4] += e; state[5] += f; state[6] += g; state[7] += h;
        }

        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[i * 4] = (byte) (state[i] >>> 24);
            out[i * 4 + 1] = (byte) (state[i] >>> 16);
            out[i * 4 + 2] = (byte) (state[i] >>> 8);
            out[i * 4 + 3] = (byte) state[i];
        }
        return out;
    }

    /** HMAC-SHA256（RFC 2104）。 */
    public static byte[] hmac(byte[] key, byte[] data) {
        int blockSize = 64;
        byte[] normalized = key.length > blockSize ? hash(key) : key;
        byte[] pad = new byte[blockSize];
        System.arraycopy(normalized, 0, pad, 0, normalized.length);

        byte[] inner = new byte[blockSize];
        byte[] outer = new byte[blockSize];
        for (int i = 0; i < blockSize; i++) {
            inner[i] = (byte) (pad[i] ^ 0x36);
            outer[i] = (byte) (pad[i] ^ 0x5c);
        }
        return hash(Bytes.concat(outer, hash(Bytes.concat(inner, data))));
    }

    /** HKDF（RFC 5869）的两个输出块，供 Noise 的 mixKey 使用。 */
    public static byte[][] hkdf2(byte[] chainingKey, byte[] inputKeyMaterial) {
        byte[] tempKey = hmac(chainingKey, inputKeyMaterial);
        byte[] out1 = hmac(tempKey, new byte[] {0x01});
        byte[] out2 = hmac(tempKey, Bytes.concat(out1, new byte[] {0x02}));
        return new byte[][] {out1, out2};
    }
}
