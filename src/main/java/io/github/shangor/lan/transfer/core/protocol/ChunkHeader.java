package io.github.shangor.lan.transfer.core.protocol;

import java.nio.ByteBuffer;

public record ChunkHeader(int taskId, int fileId, long sequence, short bodySize) {
    // Even though QUIC's default (~1350 bytes) to minimize loss and avoid fragmentation.
    public static final int MAX_BODY_SIZE = 32*1350;

    public ChunkHeader(int taskId, int fileId, long sequence, short bodySize) {
        this.taskId = taskId & 0xFFFF;
        this.fileId = fileId;
        this.sequence = sequence;
        this.bodySize = bodySize;
    }

    public static ChunkHeader decode(ByteBuffer buffer) {
        int taskId = buffer.getShort() & 0xFFFF;
        int fileId = buffer.getInt();
        long seq = buffer.getLong();
        short size = buffer.getShort();
        return new ChunkHeader(taskId, fileId, seq, size);
    }
}
