package com.lantransfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record FileCompleteMessage(String taskId, int fileId, boolean success)
        implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.FILE_COMPLETE;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        ProtocolIO.writeString(out, taskId);
        out.writeInt(fileId);
        out.writeBoolean(success);
    }

    public static FileCompleteMessage read(DataInputStream in) throws IOException {
        String taskId = ProtocolIO.readString(in);
        int fileId = in.readInt();
        boolean ok = in.readBoolean();
        return new FileCompleteMessage(taskId, fileId, ok);
    }
}
