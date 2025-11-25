package com.lantransfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record TaskCompleteMessage(String taskId) implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.TASK_COMPLETE;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        ProtocolIO.writeString(out, taskId);
    }

    public static TaskCompleteMessage read(DataInputStream in) throws IOException {
        String taskId = ProtocolIO.readString(in);
        return new TaskCompleteMessage(taskId);
    }
}
