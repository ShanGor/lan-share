package com.lantransfer.core.model;

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
    private final boolean initialized;

    public FileChunkBitmap(Path bitmapPath, int chunkCount) throws IOException {
        this.bitmapPath = bitmapPath;
        this.chunkCount = chunkCount;
        if (Files.exists(bitmapPath)) {
            this.bits = BitSet.valueOf(Files.readAllBytes(bitmapPath));
            this.initialized = true;
        } else {
            this.bits = new BitSet(chunkCount);
            persist();
            this.initialized = false;
        }
    }

    public synchronized void markReceived(int index) throws IOException {
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

    public boolean isInitialized() {
        return initialized || bits.length() > 0;
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
