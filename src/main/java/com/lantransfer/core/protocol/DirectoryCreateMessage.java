package com.lantransfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record DirectoryCreateMessage(int taskId, String relativePath) implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.DIR_CREATE;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeShort(taskId & 0xFFFF);
        ProtocolIO.writeString(out, relativePath);
    }

    public static DirectoryCreateMessage read(DataInputStream in) throws IOException {
        int taskId = in.readUnsignedShort();
        String path = ProtocolIO.readString(in);
        return new DirectoryCreateMessage(taskId, path);
    }
}
