package cn.dsh.remote;

/**
 * Noise_IK_25519_ChaChaPoly_SHA256。
 *
 * 模式（token 命名恒为「发起方 key + 响应方 key」）：
 *   <- s
 *   -> e, es, s, ss
 *   <- e, ee, se
 *
 * 预共享的响应方静态公钥先混入 transcript，这是 IK 与 NN 的关键差异；
 * 顺序错了握手仍然「能跑完」，但两端派生出的密钥不同，表现为 MAC 校验失败，
 * 极难排查，因此这里严格按 RFC 与上游实现对齐。
 */
public final class NoiseIK {
    public static final String PROTOCOL_NAME = "Noise_IK_25519_ChaChaPoly_SHA256";

    private static final int HASHLEN = 32;
    private static final int DHLEN = 32;
    private static final int TAGLEN = 16;

    /** 传输阶段的单向加密状态。 */
    public static final class CipherState {
        private byte[] key;
        private long nonce;

        void initializeKey(byte[] value) {
            key = value;
            nonce = 0;
        }

        boolean hasKey() {
            return key != null;
        }

        private byte[] nonceBytes() {
            byte[] out = new byte[12];
            long value = nonce;
            for (int i = 4; i < 12; i++) {
                out[i] = (byte) (value & 0xff);
                value >>>= 8;
            }
            return out;
        }

        byte[] encryptWithAd(byte[] ad, byte[] plaintext) {
            if (key == null) return plaintext;
            byte[] out = ChaCha20Poly1305.seal(key, nonceBytes(), ad, plaintext);
            nonce++;
            return out;
        }

        byte[] decryptWithAd(byte[] ad, byte[] ciphertext) {
            if (key == null) return ciphertext;
            byte[] out = ChaCha20Poly1305.open(key, nonceBytes(), ad, ciphertext);
            nonce++;
            return out;
        }

        void destroy() {
            if (key != null) java.util.Arrays.fill(key, (byte) 0);
            key = null;
            nonce = 0;
        }
    }

    private static final class SymmetricState {
        byte[] chainingKey;
        byte[] hash;
        final CipherState cipher = new CipherState();

        SymmetricState(byte[] prologue) {
            byte[] name = Bytes.utf8(PROTOCOL_NAME);
            hash = name.length <= HASHLEN
                    ? Bytes.concat(name, new byte[HASHLEN - name.length])
                    : Sha256.hash(name);
            chainingKey = hash.clone();
            mixHash(prologue);
        }

        void mixKey(byte[] inputKeyMaterial) {
            byte[][] derived = Sha256.hkdf2(chainingKey, inputKeyMaterial);
            chainingKey = derived[0];
            cipher.initializeKey(derived[1]);
        }

        void mixHash(byte[] data) {
            hash = Sha256.hash(Bytes.concat(hash, data));
        }

        byte[] encryptAndHash(byte[] plaintext) {
            byte[] out = cipher.encryptWithAd(hash, plaintext);
            mixHash(out);
            return out;
        }

        byte[] decryptAndHash(byte[] ciphertext) {
            byte[] out = cipher.decryptWithAd(hash, ciphertext);
            mixHash(ciphertext);
            return out;
        }

        CipherState[] split() {
            byte[][] derived = Sha256.hkdf2(chainingKey, new byte[0]);
            CipherState first = new CipherState();
            first.initializeKey(derived[0]);
            CipherState second = new CipherState();
            second.initializeKey(derived[1]);
            return new CipherState[] {first, second};
        }
    }

    private final boolean initiator;
    private final byte[] staticPrivate;
    private final byte[] staticPublic;
    private byte[] remoteStatic;
    private byte[] ephemeralPrivate;
    private byte[] remoteEphemeral;
    private final SymmetricState symmetric;
    private int messageIndex;
    private CipherState sendCipher;
    private CipherState receiveCipher;
    /**
     * 临时密钥来源。生产环境用随机数；测试需要复现上游夹具时注入固定私钥。
     * 只在构造后、首次写握手前设置。
     */
    private byte[] fixedEphemeralPrivate;

    /** 仅供测试：固定下一次写握手使用的临时私钥。 */
    public void setEphemeralPrivateForTest(byte[] value) {
        fixedEphemeralPrivate = value == null ? null : value.clone();
    }

    private byte[] nextEphemeralPrivate() {
        if (fixedEphemeralPrivate != null) {
            byte[] value = fixedEphemeralPrivate;
            fixedEphemeralPrivate = null;
            return value;
        }
        return X25519.generateKeyPair()[0];
    }

    public NoiseIK(boolean initiator, byte[] localPrivateKey, byte[] localPublicKey,
                   byte[] remotePublicKey, byte[] prologue) {
        if (localPrivateKey.length != DHLEN) throw new IllegalArgumentException("本地私钥必须是 32 字节");
        if (localPublicKey.length != DHLEN) throw new IllegalArgumentException("本地公钥必须是 32 字节");
        this.initiator = initiator;
        this.staticPrivate = localPrivateKey.clone();
        this.staticPublic = localPublicKey.clone();
        this.symmetric = new SymmetricState(prologue);
        if (initiator) {
            if (remotePublicKey == null || remotePublicKey.length != DHLEN) {
                throw new IllegalArgumentException("发起方必须提供 32 字节的响应方公钥");
            }
            this.remoteStatic = remotePublicKey.clone();
            // IK：响应方的静态公钥在握手前就混入 transcript。
            symmetric.mixHash(remotePublicKey);
        } else {
            symmetric.mixHash(localPublicKey);
        }
    }

    public boolean isComplete() {
        return sendCipher != null;
    }

    public boolean canWriteHandshake() {
        return !isComplete() && (messageIndex % 2 == 0) == initiator;
    }

    /** 写一条握手消息。发起方第一条为 e,es,s,ss，第二条为 e,ee,se。 */
    public byte[] writeHandshake(byte[] payload) {
        if (!canWriteHandshake()) throw new IllegalStateException("当前不是写握手消息的时机");
        if (messageIndex == 0) {
            ephemeralPrivate = nextEphemeralPrivate();
            byte[] ePub = X25519.publicKey(ephemeralPrivate);
            symmetric.mixHash(ePub);
            symmetric.mixKey(X25519.sharedSecret(ephemeralPrivate, remoteStatic)); // es
            byte[] encStatic = symmetric.encryptAndHash(staticPublic);              // s
            symmetric.mixKey(X25519.sharedSecret(staticPrivate, remoteStatic));     // ss
            byte[] encPayload = symmetric.encryptAndHash(payload);
            messageIndex = 1;
            return Bytes.concat(ePub, encStatic, encPayload);
        }
        if (messageIndex == 1) {
            ephemeralPrivate = nextEphemeralPrivate();
            byte[] ePub = X25519.publicKey(ephemeralPrivate);
            symmetric.mixHash(ePub);
            symmetric.mixKey(X25519.sharedSecret(ephemeralPrivate, remoteEphemeral)); // ee
            // se 的 DH 在两个方向上用的密钥不同：
            // 发起方持有静态私钥，乘响应方临时公钥；响应方持有临时私钥，乘发起方静态公钥。
            symmetric.mixKey(initiator
                    ? X25519.sharedSecret(staticPrivate, remoteEphemeral)
                    : X25519.sharedSecret(ephemeralPrivate, remoteStatic));          // se
            byte[] encPayload = symmetric.encryptAndHash(payload);
            messageIndex = 2;
            finish();
            return Bytes.concat(ePub, encPayload);
        }
        throw new IllegalStateException("握手消息序号异常：" + messageIndex);
    }

    /** 读一条握手消息。 */
    public byte[] readHandshake(byte[] message) {
        if (canWriteHandshake()) throw new IllegalStateException("当前不是读握手消息的时机");
        if (messageIndex == 0) {
            if (message.length < DHLEN) throw new IllegalArgumentException("握手消息过短");
            remoteEphemeral = Bytes.slice(message, 0, DHLEN);
            int offset = DHLEN;
            symmetric.mixHash(remoteEphemeral);
            symmetric.mixKey(X25519.sharedSecret(staticPrivate, remoteEphemeral)); // es
            if (message.length < offset + DHLEN + TAGLEN) throw new IllegalArgumentException("握手消息缺少静态公钥");
            byte[] encStatic = Bytes.slice(message, offset, offset + DHLEN + TAGLEN);
            offset += DHLEN + TAGLEN;
            remoteStatic = symmetric.decryptAndHash(encStatic);                    // s
            symmetric.mixKey(X25519.sharedSecret(staticPrivate, remoteStatic));    // ss
            byte[] payload = symmetric.decryptAndHash(Bytes.slice(message, offset, message.length));
            messageIndex = 1;
            return payload;
        }
        if (messageIndex == 1) {
            if (message.length < DHLEN) throw new IllegalArgumentException("握手消息过短");
            remoteEphemeral = Bytes.slice(message, 0, DHLEN);
            symmetric.mixHash(remoteEphemeral);
            symmetric.mixKey(X25519.sharedSecret(ephemeralPrivate, remoteEphemeral)); // ee
            // se：读消息的一方若是发起方，用自己的静态私钥乘响应方临时公钥；
            // 若是响应方，用自己的临时私钥乘发起方静态公钥。
            symmetric.mixKey(initiator
                    ? X25519.sharedSecret(staticPrivate, remoteEphemeral)
                    : X25519.sharedSecret(ephemeralPrivate, remoteStatic));          // se
            byte[] payload = symmetric.decryptAndHash(Bytes.slice(message, DHLEN, message.length));
            messageIndex = 2;
            finish();
            return payload;
        }
        throw new IllegalStateException("握手消息序号异常：" + messageIndex);
    }

    private void finish() {
        CipherState[] pair = symmetric.split();
        if (initiator) {
            sendCipher = pair[0];
            receiveCipher = pair[1];
        } else {
            sendCipher = pair[1];
            receiveCipher = pair[0];
        }
    }

    public byte[] encrypt(byte[] plaintext) {
        if (sendCipher == null) throw new IllegalStateException("Noise 握手尚未完成");
        return sendCipher.encryptWithAd(new byte[0], plaintext);
    }

    public byte[] decrypt(byte[] ciphertext) {
        if (receiveCipher == null) throw new IllegalStateException("Noise 握手尚未完成");
        return receiveCipher.decryptWithAd(new byte[0], ciphertext);
    }

    public long sendingCounter() {
        return sendCipher == null ? 0 : sendCipher.nonce;
    }

    /** 构建 prologue：绑定协议版本与本次连接的三个标识。 */
    public static byte[] prologue(String connectionId, String hostDeviceId, String clientDeviceId) {
        for (String value : new String[] {connectionId, hostDeviceId, clientDeviceId}) {
            if (value == null || value.length() == 0 || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("prologue 参数不合法");
            }
        }
        return Bytes.utf8("DSH-REMOTE\0v=1\0connection=" + connectionId
                + "\0host=" + hostDeviceId + "\0client=" + clientDeviceId);
    }

    public void destroy() {
        if (staticPrivate != null) java.util.Arrays.fill(staticPrivate, (byte) 0);
        if (ephemeralPrivate != null) java.util.Arrays.fill(ephemeralPrivate, (byte) 0);
        symmetric.cipher.destroy();
        if (sendCipher != null) sendCipher.destroy();
        if (receiveCipher != null) receiveCipher.destroy();
    }
}
