package com.lantransfer.core.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public class TransferTask {
    private final String taskId;
    private final int protocolTaskId;
    private final Path source;
    private final Path destination;
    private volatile TransferStatus status;
    private final AtomicLong bytesTransferred = new AtomicLong(0);
    private final long totalBytes;
    private final Instant createdAt;
    private volatile Instant updatedAt;

    public TransferTask(int protocolTaskId, Path source, Path destination, long totalBytes) {
        this.protocolTaskId = protocolTaskId & 0xFFFF;
        this.taskId = String.format("%05d", this.protocolTaskId);
        this.source = Objects.requireNonNull(source, "source");
        this.destination = Objects.requireNonNull(destination, "destination");
        this.totalBytes = totalBytes;
        this.status = TransferStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getTaskId() {
        return taskId;
    }

    public int getProtocolTaskId() {
        return protocolTaskId;
    }

    public Path getSource() {
        return source;
    }

    public Path getDestination() {
        return destination;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public void setStatus(TransferStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public long getBytesTransferred() {
        return bytesTransferred.get();
    }

    public void addBytesTransferred(long delta) {
        bytesTransferred.addAndGet(delta);
        this.updatedAt = Instant.now();
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
