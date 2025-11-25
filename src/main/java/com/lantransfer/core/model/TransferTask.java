package com.lantransfer.core.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class TransferTask {
    private final String taskId;
    private final Path source;
    private final Path destination;
    private volatile TransferStatus status;
    private final AtomicLong bytesTransferred = new AtomicLong(0);
    private final long totalBytes;
    private final Instant createdAt;
    private volatile Instant updatedAt;

    public TransferTask(Path source, Path destination, long totalBytes) {
        this(UUID.randomUUID().toString(), source, destination, totalBytes);
    }

    public TransferTask(String taskId, Path source, Path destination, long totalBytes) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
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
