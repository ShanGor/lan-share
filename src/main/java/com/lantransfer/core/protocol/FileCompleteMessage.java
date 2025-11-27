package com.lantransfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record FileCompleteMessage(int taskId, int fileId, boolean success)
        implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.FILE_COMPLETE;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeShort(taskId & 0xFFFF);
        out.writeInt(fileId);
        out.writeBoolean(success);
    }

    public static FileCompleteMessage read(DataInputStream in) throws IOException {
        int taskId = in.readUnsignedShort();
        int fileId = in.readInt();
        boolean ok = in.readBoolean();
        return new FileCompleteMessage(taskId, fileId, ok);
    }
}
