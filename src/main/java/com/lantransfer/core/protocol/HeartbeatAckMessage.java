package com.lantransfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record HeartbeatAckMessage(int taskId) implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.HEARTBEAT_ACK;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeShort(taskId & 0xFFFF);
    }

    public static HeartbeatAckMessage read(DataInputStream in) throws IOException {
        int taskId = in.readUnsignedShort();
        return new HeartbeatAckMessage(taskId);
    }
}
