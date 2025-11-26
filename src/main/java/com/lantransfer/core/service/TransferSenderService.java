package com.lantransfer.core.service;

import com.lantransfer.core.model.TransferStatus;
import com.lantransfer.core.model.TransferTask;
import com.lantransfer.core.net.UdpEndpoint;
import com.lantransfer.core.protocol.ChunkHeader;
import com.lantransfer.core.protocol.FileChunkMessage;
import com.lantransfer.core.protocol.FileCompleteMessage;
import com.lantransfer.core.protocol.FileMetaMessage;
import com.lantransfer.core.protocol.FileResendRequestMessage;
import com.lantransfer.core.protocol.FileSendDoneMessage;
import com.lantransfer.core.protocol.DirectoryCreateMessage;
import com.lantransfer.core.protocol.ProtocolIO;
import com.lantransfer.core.protocol.ProtocolMessage;
import com.lantransfer.core.protocol.TaskCompleteMessage;
import com.lantransfer.core.protocol.TransferOfferMessage;
import com.lantransfer.core.protocol.TransferResponseMessage;
import com.lantransfer.ui.common.TaskTableModel;
import java.util.concurrent.ThreadLocalRandom;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TransferSenderService implements AutoCloseable {
    private static final Logger log = Logger.getLogger(TransferSenderService.class.getName());

    private final UdpEndpoint endpoint = new UdpEndpoint();
    private final TaskRegistry taskRegistry;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Map<String, SenderContext> contexts = new ConcurrentHashMap<>();

    public TransferSenderService(TaskRegistry taskRegistry) {
        this.taskRegistry = taskRegistry;
    }

    public void start(int port) throws InterruptedException {
        endpoint.bind(port, (addr, data) -> {
            try {
                ProtocolMessage msg = ProtocolIO.fromByteArray(data);
                handleIncoming(addr, msg);
            } catch (Exception e) {
                log.log(Level.WARNING, "Failed to decode incoming packet", e);
            }
        });
    }

    public void sendFolder(Path folder, InetSocketAddress receiver, TaskTableModel tableModel) {
        Objects.requireNonNull(folder, "folder");
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("Folder does not exist: " + folder);
        }
        executor.submit(() -> {
            try {
                List<Path> files = listFiles(folder);
                List<Path> dirs = listDirs(folder);
                long totalBytes = files.stream().mapToLong(p -> {
                    try {
                        return Files.size(p);
                    } catch (IOException e) {
                        return 0L;
                    }
                }).sum();
                String taskRequestId = "req-" + ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
                TransferTask task = new TransferTask(folder, Path.of(""), totalBytes);
                task.setStatus(TransferStatus.PENDING);
                taskRegistry.add(task);
                if (tableModel != null) {
                    tableModel.addTask(task);
                }

                TransferOfferMessage offer = new TransferOfferMessage(taskRequestId, folder.getFileName().toString(), totalBytes, files.size());
                send(receiver, offer);

                CompletableFuture<TransferResponseMessage> responseFuture = new CompletableFuture<>();
                contexts.put(taskRequestId, new SenderContext(task, receiver, files, dirs, folder, responseFuture));

                TransferResponseMessage response = responseFuture.get(10, TimeUnit.SECONDS);
                if (!response.accepted()) {
                    task.setStatus(TransferStatus.REJECTED);
                    return;
                }
                String taskId = response.taskId();
                SenderContext ctx = contexts.remove(taskRequestId);
                ctx.taskId = taskId;
                contexts.put(taskId, ctx);
                task.setStatus(TransferStatus.IN_PROGRESS);
                sendDirectories(ctx);
                sendFiles(ctx);
            } catch (Exception e) {
                log.log(Level.SEVERE, "Send folder failed", e);
            }
        });
    }

    private void sendDirectories(SenderContext ctx) throws IOException {
        for (Path dir : ctx.directories) {
            String rel = ctx.root.relativize(dir).toString();
            DirectoryCreateMessage msg = new DirectoryCreateMessage(ctx.taskId, rel);
            send(ctx.receiver, msg);
        }
    }

    private void sendFiles(SenderContext ctx) throws IOException {
        int fileId = 0;
        for (Path file : ctx.files) {
            long size = Files.size(file);
            String relPath = ctx.root.relativize(file).toString();
            String md5 = new ChecksumService().md5(file);
            FileMetaMessage meta = new FileMetaMessage(ctx.taskId, fileId, relPath, size, md5);
            send(ctx.receiver, meta);
            int totalChunks = sendFileChunks(ctx, fileId, file);
            ctx.chunkCounts.put(fileId, totalChunks);
            send(ctx.receiver, new FileSendDoneMessage(ctx.taskId, fileId, totalChunks));
            scheduleFileDoneTimeout(ctx, fileId, 0);
            fileId++;
        }
    }

    private void scheduleFileDoneTimeout(SenderContext ctx, int fileId, int attempt) {
        if (attempt >= 3) {
            ctx.task.setStatus(TransferStatus.FAILED);
            return;
        }
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            log.info("Retrying file-send-done for fileId=" + fileId + ", attempt=" + (attempt + 1));
            try {
                int totalChunks = ctx.chunkCounts.getOrDefault(fileId, 0);
                send(ctx.receiver, new FileSendDoneMessage(ctx.taskId, fileId, totalChunks));
            } catch (IOException e) {
                log.log(Level.WARNING, "Failed to resend file-send-done", e);
            }
            scheduleFileDoneTimeout(ctx, fileId, attempt + 1);
        }, 2, TimeUnit.SECONDS);
        ctx.trackTimeout(fileId, future);
    }

    private int sendFileChunks(SenderContext ctx, int fileId, Path file) throws IOException {
        int seq = 0;
        try (FileInputStream fis = new FileInputStream(file.toFile())) {
            byte[] body = new byte[ChunkHeader.MAX_BODY_SIZE];
            int read;
            while ((read = fis.read(body)) != -1) {
                byte[] chunkBytes = buildChunkBytes(seq, body, read);
                send(ctx.receiver, new FileChunkMessage(ctx.taskId, fileId, chunkBytes));
                ctx.task.addBytesTransferred(read);
                seq++;
            }
        }
        return seq;
    }

    private byte[] buildChunkBytes(int seq, byte[] body, int bodySize) {
        byte xorKey = (byte) ThreadLocalRandom.current().nextInt(0, 256);
        byte[] chunk = new byte[ChunkHeader.HEADER_SIZE + bodySize];
        ByteBuffer headerBuf = ByteBuffer.wrap(chunk);
        headerBuf.putLong(seq);
        headerBuf.put(xorKey);
        headerBuf.putShort((short) bodySize);
        headerBuf.position(ChunkHeader.HEADER_SIZE);
        for (int i = 0; i < bodySize; i++) {
            chunk[ChunkHeader.HEADER_SIZE + i] = (byte) (body[i] ^ xorKey);
        }
        return chunk;
    }

    private void handleIncoming(InetSocketAddress addr, ProtocolMessage msg) {
        switch (msg.type()) {
            case TRANSFER_RESPONSE -> {
                TransferResponseMessage response = (TransferResponseMessage) msg;
                SenderContext ctx = contexts.get(response.taskRequestId());
                if (ctx != null) {
                    ctx.responseFuture.complete(response);
                }
            }
            case FILE_RESEND_REQUEST -> handleResendRequest((FileResendRequestMessage) msg);
            case FILE_COMPLETE -> handleFileComplete((FileCompleteMessage) msg);
            case TASK_COMPLETE -> handleTaskComplete((TaskCompleteMessage) msg);
            default -> {
            }
        }
    }

    private void handleResendRequest(FileResendRequestMessage msg) {
        SenderContext ctx = contexts.get(msg.taskId());
        if (ctx == null) {
            return;
        }
        Path file = ctx.files.get(msg.fileId());
        try {
            for (int seq : msg.missingSequences()) {
                byte[] body = readChunkBody(file, seq);
                int bodySize = body.length;
                byte[] chunkBytes = buildChunkBytes(seq, body, bodySize);
                send(ctx.receiver, new FileChunkMessage(msg.taskId(), msg.fileId(), chunkBytes));
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to resend chunks", e);
        }
    }

    private byte[] readChunkBody(Path file, int seq) throws IOException {
        try (FileInputStream fis = new FileInputStream(file.toFile())) {
            long skip = (long) seq * ChunkHeader.MAX_BODY_SIZE;
            long skipped = fis.skip(skip);
            while (skipped < skip) {
                long s = fis.skip(skip - skipped);
                if (s <= 0) break;
                skipped += s;
            }
            byte[] body = new byte[ChunkHeader.MAX_BODY_SIZE];
            int read = fis.read(body);
            if (read < 0) {
                return new byte[0];
            }
            if (read == body.length) {
                return body;
            }
            byte[] trimmed = new byte[read];
            System.arraycopy(body, 0, trimmed, 0, read);
            return trimmed;
        }
    }

    private void handleFileComplete(FileCompleteMessage msg) {
        SenderContext ctx = contexts.get(msg.taskId());
        if (ctx == null) {
            return;
        }
        log.info("File complete: " + msg);
        if (!msg.success()) {
            ctx.task.setStatus(TransferStatus.RESENDING);
            Path file = ctx.files.get(msg.fileId());
            try {
                int totalChunks = sendFileChunks(ctx, msg.fileId(), file);
                send(ctx.receiver, new FileSendDoneMessage(msg.taskId(), msg.fileId(), totalChunks));
                scheduleFileDoneTimeout(ctx, msg.fileId(), 0);
            } catch (IOException e) {
                log.log(Level.WARNING, "Failed to resend file", e);
                ctx.task.setStatus(TransferStatus.FAILED);
            }
        } else {
            ctx.clearTimeout(msg.fileId());
        }
    }

    private void handleTaskComplete(TaskCompleteMessage msg) {
        SenderContext ctx = contexts.get(msg.taskId());
        if (ctx != null) {
            ctx.task.setStatus(TransferStatus.COMPLETED);
            ctx.clearAllTimeouts();
        }
    }

    private void send(InetSocketAddress recipient, ProtocolMessage msg) throws IOException {
        byte[] data = ProtocolIO.toByteArray(msg);
        endpoint.send(recipient, data);
    }

    private List<Path> listFiles(Path folder) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walk(folder).filter(Files::isRegularFile).forEach(files::add);
        return files;
    }

    private List<Path> listDirs(Path folder) throws IOException {
        List<Path> dirs = new ArrayList<>();
        Files.walk(folder)
                .filter(Files::isDirectory)
                .filter(p -> !p.equals(folder))
                .forEach(dirs::add);
        return dirs;
    }

    @Override
    public void close() {
        endpoint.close();
        executor.shutdownNow();
        scheduler.shutdownNow();
    }

    private static class SenderContext {
        final TransferTask task;
        final InetSocketAddress receiver;
        final List<Path> files;
        final List<Path> directories;
        final Path root;
        String taskId;
        final CompletableFuture<TransferResponseMessage> responseFuture;
        final Map<Integer, ScheduledFuture<?>> timeouts = new ConcurrentHashMap<>();
        final Map<Integer, Integer> chunkCounts = new ConcurrentHashMap<>();

        SenderContext(TransferTask task, InetSocketAddress receiver, List<Path> files, List<Path> directories, Path root, CompletableFuture<TransferResponseMessage> responseFuture) {
            this.task = task;
            this.receiver = receiver;
            this.files = files;
            this.directories = directories;
            this.root = root;
            this.responseFuture = responseFuture;
        }

        void trackTimeout(int fileId, ScheduledFuture<?> future) {
            ScheduledFuture<?> previous = timeouts.put(fileId, future);
            if (previous != null) {
                previous.cancel(false);
            }
        }

        void clearTimeout(int fileId) {
            ScheduledFuture<?> future = timeouts.remove(fileId);
            if (future != null) {
                future.cancel(false);
            }
        }

        void clearAllTimeouts() {
            for (ScheduledFuture<?> f : timeouts.values()) {
                f.cancel(false);
            }
            timeouts.clear();
        }
    }
}
