package cn.dsh.remote;

/**
 * 安全通道的分片重组。
 *
 * 业务消息超过 48 KiB 时，发送方先切成 DSHF 片段再逐片 Noise 加密。
 * 布局：magic(4) + version(1) + messageId(4) + index(2) + total(2) + totalBytes(4) + payload。
 * 只有同一 authenticated channel 内的片段才能重组；重复、乱序、长度不一致或超限都必须失败。
 */
public final class FragmentAssembler {
    private static final byte[] MAGIC = {0x44, 0x53, 0x48, 0x46}; // 'DSHF'
    private static final int HEADER_BYTES = 17;
    private static final int MAX_MESSAGE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PARALLEL = 8;

    private static final class Entry {
        final int total;
        final int totalBytes;
        final byte[][] parts;
        int received;

        Entry(int total, int totalBytes) {
            this.total = total;
            this.totalBytes = totalBytes;
            this.parts = new byte[total][];
        }
    }

    private final java.util.Map<Long, Entry> pending = new java.util.HashMap<Long, Entry>();

    public static boolean isFragment(byte[] frame) {
        if (frame == null || frame.length < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) if (frame[i] != MAGIC[i]) return false;
        return true;
    }

    /** 吸收一个片段；消息完整时返回完整字节，否则返回 null。 */
    public byte[] accept(byte[] frame) {
        if (frame.length < HEADER_BYTES) throw new IllegalStateException("DSHF 帧过短");
        if (!isFragment(frame)) throw new IllegalStateException("DSHF magic 不符");
        int version = frame[4] & 0xff;
        if (version != 1) throw new IllegalStateException("DSHF 版本不支持：" + version);

        long messageId = readUint32(frame, 5);
        int index = readUint16(frame, 9);
        int total = readUint16(frame, 11);
        int totalBytes = (int) readUint32(frame, 13);
        if (total == 0 || index >= total) throw new IllegalStateException("DSHF 分片序号非法");
        if (totalBytes <= 0 || totalBytes > MAX_MESSAGE_BYTES) throw new IllegalStateException("DSHF 消息超限");

        Entry entry = pending.get(Long.valueOf(messageId));
        if (entry == null) {
            if (pending.size() >= MAX_PARALLEL) throw new IllegalStateException("DSHF 并行重组超限");
            entry = new Entry(total, totalBytes);
            pending.put(Long.valueOf(messageId), entry);
        } else if (entry.total != total || entry.totalBytes != totalBytes) {
            throw new IllegalStateException("DSHF 分片长度不一致");
        }
        if (entry.parts[index] != null) throw new IllegalStateException("DSHF 分片重复");
        entry.parts[index] = Bytes.slice(frame, HEADER_BYTES, frame.length);
        entry.received++;
        if (entry.received < entry.total) return null;

        pending.remove(Long.valueOf(messageId));
        byte[] out = new byte[entry.totalBytes];
        int offset = 0;
        for (byte[] part : entry.parts) {
            if (part == null) throw new IllegalStateException("DSHF 分片缺失");
            if (offset + part.length > out.length) throw new IllegalStateException("DSHF 总长不符");
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        if (offset != entry.totalBytes) throw new IllegalStateException("DSHF 总长不符");
        return out;
    }

    public void reset() {
        pending.clear();
    }

    private static int readUint16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static long readUint32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xff) << 24)
                | ((long) (data[offset + 1] & 0xff) << 16)
                | ((long) (data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }
}
