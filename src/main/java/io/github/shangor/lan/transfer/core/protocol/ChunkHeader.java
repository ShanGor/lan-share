package io.github.shangor.lan.transfer.core.protocol;

import java.nio.ByteBuffer;

public record ChunkHeader(int taskId, int fileId, long sequence, short bodySize) {
    /**
     * Even though QUIC's default (~1350 bytes) to minimize loss and avoid fragmentation.
     */
    public static final int MAX_BODY_SIZE = 16*1350;
    /**
     * So the total buffers in memory are 16*1350 * 4192 = 90,547,200 (86.35 MB); If you increase the window size,
     * the latency could be higher because the `receiver` need more time to consume,
     * so I reduced the MAX_WINDOW_SIZE from 8192 to be 4096 speed up the task.
     */
    public static final int MAX_WINDOW_SIZE = 4096;

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
