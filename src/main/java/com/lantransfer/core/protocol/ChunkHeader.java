package com.lantransfer.core.protocol;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

public class ChunkHeader {
    public static final int HEADER_SIZE = 14;
    public static final int MAX_BODY_SIZE = 1000;
    public static final int MAX_CHUNK_SIZE = HEADER_SIZE + MAX_BODY_SIZE;

    private final long sequence;
    private final int crc32;
    private final short bodySize;

    public ChunkHeader(long sequence, int crc32, short bodySize) {
        this.sequence = sequence;
        this.crc32 = crc32;
        this.bodySize = bodySize;
    }

    public long sequence() {
        return sequence;
    }

    public int crc32() {
        return crc32;
    }

    public short bodySize() {
        return bodySize;
    }

    public ByteBuffer encode() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        buffer.putLong(sequence);
        buffer.putInt(crc32);
        buffer.putShort(bodySize);
        buffer.flip();
        return buffer;
    }

    public static ChunkHeader decode(ByteBuffer buffer) {
        long seq = buffer.getLong();
        int crc = buffer.getInt();
        short size = buffer.getShort();
        return new ChunkHeader(seq, crc, size);
    }

    public static int computeCrc32(byte[] headerAndBody, int length) {
        CRC32 crc = new CRC32();
        crc.update(headerAndBody, 0, length);
        return (int) crc.getValue();
    }
}
