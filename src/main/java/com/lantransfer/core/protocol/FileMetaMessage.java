package com.lantransfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record FileMetaMessage(int taskId, int fileId, char entryType, String relativePath, long size, String md5)
        implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.FILE_META;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeShort(taskId & 0xFFFF);
        out.writeInt(fileId);
        out.writeChar(entryType);
        ProtocolIO.writeString(out, relativePath);
        out.writeLong(size);
        ProtocolIO.writeString(out, md5);
    }

    public static FileMetaMessage read(DataInputStream in) throws IOException {
        int taskId = in.readUnsignedShort();
        int fileId = in.readInt();
        char entryType = in.readChar();
        String path = ProtocolIO.readString(in);
        long size = in.readLong();
        String md5 = ProtocolIO.readString(in);
        return new FileMetaMessage(taskId, fileId, entryType, path, size, md5);
    }
}
