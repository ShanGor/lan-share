package com.lantransfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record TransferOfferMessage(int taskId,
                                   String folderName,
                                   long totalBytes,
                                   int fileCount) implements ProtocolMessage {

    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.TRANSFER_OFFER;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeShort(taskId & 0xFFFF);
        ProtocolIO.writeString(out, folderName);
        out.writeLong(totalBytes);
        out.writeInt(fileCount);
    }

    public static TransferOfferMessage read(DataInputStream in) throws IOException {
        int taskId = in.readUnsignedShort();
        String folderName = ProtocolIO.readString(in);
        long totalBytes = in.readLong();
        int fileCount = in.readInt();
        return new TransferOfferMessage(taskId, folderName, totalBytes, fileCount);
    }
}
