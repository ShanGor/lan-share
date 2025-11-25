package com.lantransfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record HeartbeatAckMessage(String taskId) implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.HEARTBEAT_ACK;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        ProtocolIO.writeString(out, taskId);
    }

    public static HeartbeatAckMessage read(DataInputStream in) throws IOException {
        String taskId = ProtocolIO.readString(in);
        return new HeartbeatAckMessage(taskId);
    }
}
