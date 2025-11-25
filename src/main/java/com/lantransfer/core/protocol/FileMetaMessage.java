package com.lantransfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record FileMetaMessage(String taskId, int fileId, String relativePath, long size, String md5)
        implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.FILE_META;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        ProtocolIO.writeString(out, taskId);
        out.writeInt(fileId);
        ProtocolIO.writeString(out, relativePath);
        out.writeLong(size);
        ProtocolIO.writeString(out, md5);
    }

    public static FileMetaMessage read(DataInputStream in) throws IOException {
        String taskId = ProtocolIO.readString(in);
        int fileId = in.readInt();
        String path = ProtocolIO.readString(in);
        long size = in.readLong();
        String md5 = ProtocolIO.readString(in);
        return new FileMetaMessage(taskId, fileId, path, size, md5);
    }
}
