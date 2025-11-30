package io.github.shangor.lan.transfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record TaskCancelMessage(int taskId) implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.TASK_CANCEL;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeShort(taskId & 0xFFFF);
    }

    public static TaskCancelMessage read(DataInputStream in) throws IOException {
        int taskId = in.readUnsignedShort();
        return new TaskCancelMessage(taskId);
    }
}
