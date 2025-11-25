package com.lantransfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record FileSendDoneMessage(String taskId, int fileId, int totalChunks) implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.FILE_SEND_DONE;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        ProtocolIO.writeString(out, taskId);
        out.writeInt(fileId);
        out.writeInt(totalChunks);
    }

    public static FileSendDoneMessage read(DataInputStream in) throws IOException {
        String taskId = ProtocolIO.readString(in);
        int fileId = in.readInt();
        int totalChunks = in.readInt();
        return new FileSendDoneMessage(taskId, fileId, totalChunks);
    }
}
