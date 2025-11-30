package io.github.shangor.lan.transfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

public record FileResendRequestMessage(int taskId, int fileId, long[] missingSequences)
        implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.FILE_RESEND_REQUEST;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeShort(taskId & 0xFFFF);
        out.writeInt(fileId);
        out.writeInt(missingSequences.length);
        for (long seq : missingSequences) {
            out.writeLong(seq);
        }
    }

    public static FileResendRequestMessage read(DataInputStream in) throws IOException {
        int taskId = in.readUnsignedShort();
        int fileId = in.readInt();
        int count = in.readInt();
        if (count < 0) {
            throw new IOException("Negative missing sequence count");
        }
        long[] missing = new long[count];
        for (int i = 0; i < count; i++) {
            missing[i] = in.readLong();
        }
        return new FileResendRequestMessage(taskId, fileId, missing);
    }

    @Override
    public String toString() {
        return "FileResendRequestMessage{" +
                "taskId=" + taskId +
                ", fileId=" + fileId +
                ", missingSequences=" + Arrays.toString(missingSequences) +
                '}';
    }
}
