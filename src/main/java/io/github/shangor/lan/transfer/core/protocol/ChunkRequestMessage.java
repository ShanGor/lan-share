package io.github.shangor.lan.transfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record ChunkRequestMessage(int taskId, int fileId, long chunkSeq) implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.CHUNK_REQUEST;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeShort(taskId & 0xFFFF);
        out.writeInt(fileId);
        out.writeLong(chunkSeq);
    }

    public static ChunkRequestMessage read(DataInputStream in) throws IOException {
        int taskId = in.readUnsignedShort();
        int fileId = in.readInt();
        long seq = in.readLong();
        return new ChunkRequestMessage(taskId, fileId, seq);
    }
}
