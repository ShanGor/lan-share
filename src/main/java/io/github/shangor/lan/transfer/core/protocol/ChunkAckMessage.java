package io.github.shangor.lan.transfer.core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record ChunkAckMessage(int taskId, int fileId, List<AckRange> ranges) implements ProtocolMessage {
    @Override
    public ProtocolMessageType type() {
        return ProtocolMessageType.CHUNK_ACK;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeShort(taskId & 0xFFFF);
        out.writeInt(fileId);
        List<AckRange> safeRanges = ranges == null ? List.of() : ranges;
        out.writeShort(safeRanges.size());
        for (AckRange range : safeRanges) {
            out.writeLong(range.start());
            out.writeLong(range.end());
        }
    }

    public static ChunkAckMessage read(DataInputStream in) throws IOException {
        int taskId = in.readUnsignedShort();
        int fileId = in.readInt();
        int count = in.readUnsignedShort();
        List<AckRange> ranges = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long start = in.readLong();
            long end = in.readLong();
            ranges.add(new AckRange(start, end));
        }
        return new ChunkAckMessage(taskId, fileId, Collections.unmodifiableList(ranges));
    }

    public record AckRange(long start, long end) {
        public AckRange {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("Invalid ACK range: " + start + "-" + end);
            }
        }
    }
}
