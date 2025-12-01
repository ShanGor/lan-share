package io.github.shangor.lan.transfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record MetaAckMessage(int taskId, int fileId, long resumeFromSeq, boolean alreadyComplete)
        implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.META_ACK;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeShort(taskId & 0xFFFF);
        out.writeInt(fileId);
        out.writeLong(resumeFromSeq);
        out.writeBoolean(alreadyComplete);
    }

    public static MetaAckMessage read(DataInputStream in) throws IOException {
        int taskId = in.readUnsignedShort();
        int fileId = in.readInt();
        long resumeFrom = in.readLong();
        boolean alreadyComplete = in.readBoolean();
        return new MetaAckMessage(taskId, fileId, resumeFrom, alreadyComplete);
    }
}
