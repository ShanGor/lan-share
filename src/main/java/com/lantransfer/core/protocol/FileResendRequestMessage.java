package com.lantransfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

public record FileResendRequestMessage(String taskId, int fileId, int[] missingSequences)
        implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.FILE_RESEND_REQUEST;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        ProtocolIO.writeString(out, taskId);
        out.writeInt(fileId);
        out.writeInt(missingSequences.length);
        for (int seq : missingSequences) {
            out.writeInt(seq);
        }
    }

    public static FileResendRequestMessage read(DataInputStream in) throws IOException {
        String taskId = ProtocolIO.readString(in);
        int fileId = in.readInt();
        int count = in.readInt();
        if (count < 0) {
            throw new IOException("Negative missing sequence count");
        }
        int available = in.available();
        if (available < count * 4) {
            throw new IOException("Truncated FILE_RESEND_REQUEST payload: expected " + (count * 4) + " bytes but only " + available);
        }
        int[] missing = new int[count];
        for (int i = 0; i < count; i++) {
            missing[i] = in.readInt();
        }
        return new FileResendRequestMessage(taskId, fileId, missing);
    }

    @Override
    public String toString() {
        return "FileResendRequestMessage{" +
                "taskId='" + taskId + '\'' +
                ", fileId=" + fileId +
                ", missingSequences=" + Arrays.toString(missingSequences) +
                '}';
    }
}
