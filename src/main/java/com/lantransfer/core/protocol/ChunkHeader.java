package com.lantransfer.core.protocol;

import java.nio.ByteBuffer;

public class ChunkHeader {
    // Keep payload size near QUIC's default (~1350 bytes) to minimize loss and avoid fragmentation.
    public static final int MAX_BODY_SIZE = 1350;
    public static final int HEADER_SIZE = 2 + 4 + 8 + 1 + 2;
    public static final int MAX_CHUNK_SIZE = HEADER_SIZE + MAX_BODY_SIZE;

    private final int taskId;
    private final int fileId;
    private final long sequence;
    private final byte xorKey;
    private final short bodySize;

    public ChunkHeader(int taskId, int fileId, long sequence, byte xorKey, short bodySize) {
        this.taskId = taskId & 0xFFFF;
        this.fileId = fileId;
        this.sequence = sequence;
        this.xorKey = xorKey;
        this.bodySize = bodySize;
    }

    public int taskId() {
        return taskId;
    }

    public int fileId() {
        return fileId;
    }

    public long sequence() {
        return sequence;
    }

    public byte xorKey() {
        return xorKey;
    }

    public short bodySize() {
        return bodySize;
    }

    public static ChunkHeader decode(ByteBuffer buffer) {
        int taskId = buffer.getShort() & 0xFFFF;
        int fileId = buffer.getInt();
        long seq = buffer.getLong();
        byte xor = buffer.get();
        short size = buffer.getShort();
        return new ChunkHeader(taskId, fileId, seq, xor, size);
    }
}
