package com.lantransfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record TransferResponseMessage(int taskId, boolean accepted)
        implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.TRANSFER_RESPONSE;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeShort(taskId & 0xFFFF);
        out.writeBoolean(accepted);
    }

    public static TransferResponseMessage read(DataInputStream in) throws IOException {
        int taskId = in.readUnsignedShort();
        boolean accepted = in.readBoolean();
        return new TransferResponseMessage(taskId, accepted);
    }
}
