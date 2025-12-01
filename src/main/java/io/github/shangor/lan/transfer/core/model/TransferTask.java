package io.github.shangor.lan.transfer.core.model;

import java.nio.file.Path;
import java.time.Duration;
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
    private volatile Instant finishedAt;
    private volatile String remoteHost = "";
    private volatile int remotePort = -1;

    public TransferTask(int protocolTaskId, Path source, Path destination, long totalBytes) {
        this.protocolTaskId = protocolTaskId & 0xFFFF;
        this.taskId = String.format("%05d", this.protocolTaskId);
        this.source = Objects.requireNonNull(source, "source");
        this.destination = Objects.requireNonNull(destination, "destination");
        this.totalBytes = totalBytes;
        this.status = TransferStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.finishedAt = null;
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
        Instant now = Instant.now();
        this.updatedAt = now;
        if (isTerminal(status) && finishedAt == null) {
            finishedAt = now;
        }
    }

    public long getBytesTransferred() {
        return bytesTransferred.get();
    }

    public void addBytesTransferred(long delta) {
        bytesTransferred.addAndGet(delta);
        // Only update timestamp if task is still active
        if (status != TransferStatus.COMPLETED && status != TransferStatus.FAILED
                && status != TransferStatus.CANCELED && status != TransferStatus.REJECTED) {
            this.updatedAt = Instant.now();
        }
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

    public Duration getDuration() {
        Instant end = finishedAt;
        if (end == null) {
            if (status == TransferStatus.IN_PROGRESS || status == TransferStatus.RESENDING || status == TransferStatus.PENDING) {
                end = Instant.now();
            } else {
                end = updatedAt;
            }
        }
        return Duration.between(createdAt, end);
    }

    private boolean isTerminal(TransferStatus status) {
        return status == TransferStatus.COMPLETED || status == TransferStatus.FAILED
                || status == TransferStatus.CANCELED || status == TransferStatus.REJECTED;
    }

    public void setRemoteEndpoint(String host, int port) {
        this.remoteHost = host == null ? "" : host;
        this.remotePort = port;
    }

    public String getRemoteHost() {
        return remoteHost;
    }

    public int getRemotePort() {
        return remotePort;
    }

    public boolean hasRemoteEndpoint() {
        return remotePort > 0 && remoteHost != null && !remoteHost.isBlank();
    }
}
