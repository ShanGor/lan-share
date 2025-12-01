package io.github.shangor.lan.transfer.core.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ProtocolIO {

    private ProtocolIO() {
    }

    public static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Short.MAX_VALUE) {
            throw new IOException("String too long");
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    public static String readString(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] bytes = in.readNBytes(len);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static byte[] toByteArray(ProtocolMessage message) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeByte(message.type().ordinal());
            message.write(out);
        }
        return baos.toByteArray();
    }

    public static ProtocolMessage fromByteArray(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int typeOrdinal = in.readUnsignedByte();
            ProtocolMessageType type = ProtocolMessageType.values()[typeOrdinal];
            return switch (type) {
                case TRANSFER_OFFER -> TransferOfferMessage.read(in);
                case TRANSFER_RESPONSE -> TransferResponseMessage.read(in);
                case FILE_META -> FileMetaMessage.read(in);
                case FILE_CHUNK -> FileChunkMessage.read(in);
                case FILE_COMPLETE -> FileCompleteMessage.read(in);
                case FILE_RESEND_REQUEST -> FileResendRequestMessage.read(in);
                case TASK_COMPLETE -> TaskCompleteMessage.read(in);
                case TASK_CANCEL -> TaskCancelMessage.read(in);
                case TASK_PAUSE -> TaskPauseMessage.read(in);
                case CHUNK_ACK -> ChunkAckMessage.read(in);
                case CHUNK_REQUEST -> ChunkRequestMessage.read(in);
                case DIR_CREATE -> DirectoryCreateMessage.read(in);
                case META_ACK -> MetaAckMessage.read(in);
                case HEARTBEAT_ACK -> HeartbeatAckMessage.read(in);
            };
        }
    }
}
