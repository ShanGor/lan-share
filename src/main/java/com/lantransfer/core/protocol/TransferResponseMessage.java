package com.lantransfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record TransferResponseMessage(String taskRequestId, boolean accepted, String taskId)
        implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.TRANSFER_RESPONSE;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        ProtocolIO.writeString(out, taskRequestId);
        out.writeBoolean(accepted);
        ProtocolIO.writeString(out, taskId == null ? "" : taskId);
    }

    public static TransferResponseMessage read(DataInputStream in) throws IOException {
        String taskRequestId = ProtocolIO.readString(in);
        boolean accepted = in.readBoolean();
        String taskId = ProtocolIO.readString(in);
        if (taskId.isEmpty()) {
            taskId = null;
        }
        return new TransferResponseMessage(taskRequestId, accepted, taskId);
    }
}
