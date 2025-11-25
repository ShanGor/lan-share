package com.lantransfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record TaskCancelMessage(String taskId) implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.TASK_CANCEL;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        ProtocolIO.writeString(out, taskId);
    }

    public static TaskCancelMessage read(DataInputStream in) throws IOException {
        String taskId = ProtocolIO.readString(in);
        return new TaskCancelMessage(taskId);
    }
}
