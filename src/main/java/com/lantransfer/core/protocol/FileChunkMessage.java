package com.lantransfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record FileChunkMessage(String taskId, int fileId, byte[] chunkBytes) implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.FILE_CHUNK;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        ProtocolIO.writeString(out, taskId);
        out.writeInt(fileId);
        out.writeInt(chunkBytes.length);
        out.write(chunkBytes);
    }

    public static FileChunkMessage read(DataInputStream in) throws IOException {
        String taskId = ProtocolIO.readString(in);
        int fileId = in.readInt();
        int len = in.readInt();
        byte[] chunk = in.readNBytes(len);
        return new FileChunkMessage(taskId, fileId, chunk);
    }
}
