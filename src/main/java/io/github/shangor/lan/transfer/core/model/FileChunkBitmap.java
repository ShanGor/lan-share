package io.github.shangor.lan.transfer.core.model;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;

public class FileChunkBitmap {
    private final Path bitmapPath;
    private final int chunkCount;
    private final BitSet bits;
    private final int byteSize;

    public FileChunkBitmap(Path bitmapPath, int chunkCount) throws IOException {
        this.bitmapPath = bitmapPath;
        this.chunkCount = chunkCount;
        this.byteSize = (chunkCount + 7) / 8;
        if (Files.exists(bitmapPath)) {
            byte[] data = Files.readAllBytes(bitmapPath);
            this.bits = BitSet.valueOf(data);
        } else {
            this.bits = new BitSet(chunkCount);
            persist();
        }
    }

    public synchronized void markReceived(int index) throws IOException {
        if (bits.get(index)) {
            return;
        }
        bits.set(index);
        persist();
    }

    public synchronized boolean isReceived(int index) {
        return bits.get(index);
    }

    public synchronized boolean allReceived() {
        return bits.cardinality() >= chunkCount;
    }

    public int chunkCount() {
        return chunkCount;
    }

    public synchronized BitSet missingChunks() {
        BitSet missing = new BitSet(chunkCount);
        missing.set(0, chunkCount);
        missing.andNot(bits);
        return missing;
    }

    private void persist() throws IOException {
        byte[] data = bits.toByteArray();
        try (RandomAccessFile raf = new RandomAccessFile(bitmapPath.toFile(), "rw");
             FileChannel channel = raf.getChannel()) {
            channel.truncate(0);
            raf.write(data);
            raf.getFD().sync();
        }
    }
}
