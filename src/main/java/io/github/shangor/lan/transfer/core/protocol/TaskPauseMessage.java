package io.github.shangor.lan.transfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record TaskPauseMessage(int taskId, boolean pause) implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.TASK_PAUSE;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeShort(taskId & 0xFFFF);
        out.writeBoolean(pause);
    }

    public static TaskPauseMessage read(DataInputStream in) throws IOException {
        int taskId = in.readUnsignedShort();
        boolean pause = in.readBoolean();
        return new TaskPauseMessage(taskId, pause);
    }
}
