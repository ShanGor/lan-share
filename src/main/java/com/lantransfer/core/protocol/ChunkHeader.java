package com.lantransfer.core.protocol;

import java.nio.ByteBuffer;
public class ChunkHeader {
    public static final int HEADER_SIZE = 11;
    public static final int MAX_BODY_SIZE = 1000;
    public static final int MAX_CHUNK_SIZE = HEADER_SIZE + MAX_BODY_SIZE;

    private final long sequence;
    private final byte xorKey;
    private final short bodySize;

    public ChunkHeader(long sequence, byte xorKey, short bodySize) {
        this.sequence = sequence;
        this.xorKey = xorKey;
        this.bodySize = bodySize;
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

    public ByteBuffer encode() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        buffer.putLong(sequence);
        buffer.put(xorKey);
        buffer.putShort(bodySize);
        buffer.flip();
        return buffer;
    }

    public static ChunkHeader decode(ByteBuffer buffer) {
        long seq = buffer.getLong();
        byte xor = buffer.get();
        short size = buffer.getShort();
        return new ChunkHeader(seq, xor, size);
    }
}
