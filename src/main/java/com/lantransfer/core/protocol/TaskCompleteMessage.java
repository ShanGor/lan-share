package com.lantransfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record TaskCompleteMessage(int taskId) implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.TASK_COMPLETE;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeShort(taskId & 0xFFFF);
    }

    public static TaskCompleteMessage read(DataInputStream in) throws IOException {
        int taskId = in.readUnsignedShort();
        return new TaskCompleteMessage(taskId);
    }
}
