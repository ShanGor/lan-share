package io.github.shangor.lan.transfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record FileChunkMessage(int taskId, int fileId, long chunkSeq, byte[] body)
        implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.FILE_CHUNK;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeShort(taskId & 0xFFFF);
        out.writeInt(fileId);
        out.writeLong(chunkSeq);
        out.writeShort(body.length);
        out.write(body);
    }

    public static FileChunkMessage read(DataInputStream in) throws IOException {
        int taskId = in.readUnsignedShort();
        int fileId = in.readInt();
        long seq = in.readLong();
        int len = in.readUnsignedShort();
        byte[] body = in.readNBytes(len);
        return new FileChunkMessage(taskId, fileId, seq, body);
    }
}
