package com.lantransfer.core.service;

import com.lantransfer.core.model.TransferStatus;
import com.lantransfer.core.model.TransferTask;
import com.lantransfer.core.net.UdpEndpoint;
import com.lantransfer.core.protocol.ChunkAckMessage;
import com.lantransfer.core.protocol.DirectoryCreateMessage;
import com.lantransfer.core.protocol.ChunkHeader;
import com.lantransfer.core.protocol.FileChunkMessage;
import com.lantransfer.core.protocol.FileCompleteMessage;
import com.lantransfer.core.protocol.FileMetaMessage;
import com.lantransfer.core.protocol.FileResendRequestMessage;
import com.lantransfer.core.protocol.FileSendDoneMessage;
import com.lantransfer.core.protocol.ProtocolIO;
import com.lantransfer.core.protocol.ProtocolMessage;
import com.lantransfer.core.protocol.TaskCancelMessage;
import com.lantransfer.core.protocol.TaskCompleteMessage;
import com.lantransfer.core.protocol.TransferOfferMessage;
import com.lantransfer.core.protocol.TransferResponseMessage;
import com.lantransfer.ui.common.TaskTableModel;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final int CHUNK_ACK_TIMEOUT_SECONDS = 2;
    private static final int MAX_CHUNK_RETRY = 5;
    private static final int FILE_DONE_TIMEOUT_SECONDS = 30;

    private final UdpEndpoint endpoint = new UdpEndpoint();
    private final TaskRegistry taskRegistry;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<Integer, SenderContext> contexts = new ConcurrentHashMap<>();

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
        executor.submit(() -> sendFolderInternal(folder, receiver, tableModel));
    }

    private void sendFolderInternal(Path folder, InetSocketAddress receiver, TaskTableModel tableModel) {
        SenderContext ctx = null;
        int protocolId = TaskIdGenerator.nextId();
        try {
            List<Path> files = listFiles(folder);
            List<Path> dirs = listDirs(folder);
            long totalBytes = totalSize(files);
            TransferTask task = new TransferTask(protocolId, folder, Path.of(""), totalBytes);
            task.setStatus(TransferStatus.PENDING);
            taskRegistry.add(task);
            if (tableModel != null) {
                tableModel.addTask(task);
            }

            ctx = new SenderContext(protocolId, task, receiver, files, dirs, folder);
            contexts.put(protocolId, ctx);

            send(receiver, new TransferOfferMessage(protocolId, folder.getFileName().toString(), totalBytes, files.size()));

            boolean accepted = ctx.acceptFuture.get(30, TimeUnit.SECONDS);
            if (!accepted) {
                task.setStatus(TransferStatus.REJECTED);
                return;
            }

            task.setStatus(TransferStatus.IN_PROGRESS);
            sendDirectories(ctx);
            sendFiles(ctx);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Send folder failed", e);
            if (ctx != null) {
                ctx.task.setStatus(TransferStatus.FAILED);
            }
        } finally {
            if (ctx != null) {
                ctx.cleanup();
            }
            contexts.remove(protocolId);
            TaskIdGenerator.release(protocolId);
        }
    }

    private void sendDirectories(SenderContext ctx) throws IOException {
        for (Path dir : ctx.directories) {
            String rel = ctx.root.relativize(dir).toString();
            send(ctx.receiver, new DirectoryCreateMessage(ctx.taskId, rel));
        }
    }

    private void sendFiles(SenderContext ctx) throws Exception {
        int fileId = 0;
        for (Path file : ctx.files) {
            long size = Files.size(file);
            long totalChunks = (size + ChunkHeader.MAX_BODY_SIZE - 1) / ChunkHeader.MAX_BODY_SIZE;
            ctx.chunkCounts.put(fileId, totalChunks);
            String relPath = ctx.root.relativize(file).toString();
            String md5 = new ChecksumService().md5(file);
            send(ctx.receiver, new FileMetaMessage(ctx.taskId, fileId, relPath, size, md5));

            sendFileChunks(ctx, fileId, file, totalChunks);

            CompletableFuture<Boolean> doneFuture = new CompletableFuture<>();
            ctx.fileDoneFutures.put(fileId, doneFuture);
            send(ctx.receiver, new FileSendDoneMessage(ctx.taskId, fileId, totalChunks));
            scheduleFileDoneTimeout(ctx, fileId, 0);
            boolean ok = doneFuture.get(FILE_DONE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ctx.clearFileTimeout(fileId);
            ctx.fileDoneFutures.remove(fileId);
            if (!ok) {
                ctx.task.setStatus(TransferStatus.FAILED);
                return;
            }
            fileId++;
        }
    }

    private void sendFileChunks(SenderContext ctx, int fileId, Path file, long totalChunks) throws Exception {
        try (FileInputStream fis = new FileInputStream(file.toFile())) {
            byte[] buffer = new byte[ChunkHeader.MAX_BODY_SIZE];
            long seq = 0;
            int read;
            while ((read = fis.read(buffer)) != -1) {
                byte[] plain = Arrays.copyOf(buffer, read);
                sendChunkAndAwaitAck(ctx, fileId, seq, plain);
                ctx.task.addBytesTransferred(read);
                seq++;
            }
            if (seq != totalChunks) {
                log.warning("Chunk count mismatch for " + file + ": expected " + totalChunks + ", sent " + seq);
            }
        }
    }

    private void sendChunkAndAwaitAck(SenderContext ctx, int fileId, long seq, byte[] plain) throws Exception {
        ChunkKey key = new ChunkKey(fileId, seq);
        CompletableFuture<Void> ackFuture = new CompletableFuture<>();
        ctx.chunkAckFutures.put(key, ackFuture);
        ctx.inflightChunks.put(key, new ChunkInFlight(plain));
        transmitChunk(ctx, fileId, seq, plain);
        scheduleChunkRetry(ctx, key, 0);
        try {
            ackFuture.get(CHUNK_ACK_TIMEOUT_SECONDS * (MAX_CHUNK_RETRY + 1L), TimeUnit.SECONDS);
        } finally {
            ctx.chunkAckFutures.remove(key);
            ctx.cancelChunkTimeout(key);
            ctx.inflightChunks.remove(key);
        }
    }

    private void transmitChunk(SenderContext ctx, int fileId, long seq, byte[] plain) throws IOException {
        byte xorKey = (byte) (System.nanoTime() & 0xFF);
        byte[] encoded = new byte[plain.length];
        for (int i = 0; i < plain.length; i++) {
            encoded[i] = (byte) (plain[i] ^ xorKey);
        }
        send(ctx.receiver, new FileChunkMessage(ctx.taskId, fileId, seq, xorKey, encoded));
    }

    private void scheduleChunkRetry(SenderContext ctx, ChunkKey key, int attempt) {
        if (attempt >= MAX_CHUNK_RETRY) {
            ctx.task.setStatus(TransferStatus.FAILED);
            CompletableFuture<Void> future = ctx.chunkAckFutures.remove(key);
            if (future != null) {
                future.completeExceptionally(new IOException("Chunk ack retries exceeded"));
            }
            return;
        }
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            ChunkInFlight inflight = ctx.inflightChunks.get(key);
            if (inflight == null) {
                return;
            }
            log.info("Retrying chunk " + key + " for task " + ctx.taskId + ", attempt " + (attempt + 1));
            try {
                transmitChunk(ctx, key.fileId(), key.sequence(), inflight.data());
            } catch (IOException e) {
                log.log(Level.WARNING, "Failed to resend chunk " + key, e);
            }
            scheduleChunkRetry(ctx, key, attempt + 1);
        }, CHUNK_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        ctx.trackChunkTimeout(key, future);
    }

    private void scheduleFileDoneTimeout(SenderContext ctx, int fileId, int attempt) {
        if (attempt >= MAX_CHUNK_RETRY) {
            ctx.task.setStatus(TransferStatus.FAILED);
            CompletableFuture<Boolean> future = ctx.fileDoneFutures.remove(fileId);
            if (future != null) {
                future.complete(false);
            }
            return;
        }
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            long totalChunks = ctx.chunkCounts.getOrDefault(fileId, 0L);
            Path file = ctx.files.get(fileId);
            log.info("Retrying file-send-done for fileId=" + fileId + " (" + file + "), attempt=" + (attempt + 1));
            try {
                send(ctx.receiver, new FileSendDoneMessage(ctx.taskId, fileId, totalChunks));
            } catch (IOException e) {
                log.log(Level.WARNING, "Failed to resend file-send-done", e);
            }
            scheduleFileDoneTimeout(ctx, fileId, attempt + 1);
        }, 2, TimeUnit.SECONDS);
        ctx.trackFileTimeout(fileId, future);
    }

    private void handleIncoming(InetSocketAddress sender, ProtocolMessage msg) {
        switch (msg.type()) {
            case TRANSFER_RESPONSE -> onTransferResponse((TransferResponseMessage) msg);
            case CHUNK_ACK -> onChunkAck((ChunkAckMessage) msg);
            case FILE_COMPLETE -> onFileComplete((FileCompleteMessage) msg);
            case FILE_RESEND_REQUEST -> onFileResendRequest((FileResendRequestMessage) msg);
            case TASK_COMPLETE -> onTaskComplete((TaskCompleteMessage) msg);
            default -> log.fine("Ignoring message type " + msg.type());
        }
    }

    private void onTransferResponse(TransferResponseMessage msg) {
        SenderContext ctx = contexts.get(msg.taskId());
        if (ctx != null) {
            ctx.acceptFuture.complete(msg.accepted());
        }
    }

    private void onChunkAck(ChunkAckMessage msg) {
        SenderContext ctx = contexts.get(msg.taskId());
        if (ctx == null) {
            return;
        }
        ChunkKey key = new ChunkKey(msg.fileId(), msg.chunkSeq());
        CompletableFuture<Void> future = ctx.chunkAckFutures.get(key);
        if (future != null) {
            future.complete(null);
        }
    }

    private void onFileComplete(FileCompleteMessage msg) {
        SenderContext ctx = contexts.get(msg.taskId());
        if (ctx == null) {
            return;
        }
        CompletableFuture<Boolean> future = ctx.fileDoneFutures.get(msg.fileId());
        if (future != null) {
            future.complete(msg.success());
        }
    }

    private void onFileResendRequest(FileResendRequestMessage msg) {
        SenderContext ctx = contexts.get(msg.taskId());
        if (ctx == null) {
            return;
        }
        Path file = ctx.files.get(msg.fileId());
        for (long seq : msg.missingSequences()) {
            try {
                byte[] data = readChunk(file, seq);
                if (data.length == 0) {
                    continue;
                }
                transmitChunk(ctx, msg.fileId(), seq, data);
            } catch (IOException e) {
                log.log(Level.WARNING, "Failed to resend chunk seq " + seq + " for fileId " + msg.fileId(), e);
            }
        }
    }

    private void onTaskComplete(TaskCompleteMessage msg) {
        SenderContext ctx = contexts.get(msg.taskId());
        if (ctx != null) {
            ctx.task.setStatus(TransferStatus.COMPLETED);
        }
    }

    private byte[] readChunk(Path file, long seq) throws IOException {
        try (FileInputStream fis = new FileInputStream(file.toFile())) {
            long skip = seq * ChunkHeader.MAX_BODY_SIZE;
            long skipped = fis.skip(skip);
            while (skipped < skip) {
                long s = fis.skip(skip - skipped);
                if (s <= 0) {
                    break;
                }
                skipped += s;
            }
            byte[] buf = new byte[ChunkHeader.MAX_BODY_SIZE];
            int read = fis.read(buf);
            if (read < 0) {
                return new byte[0];
            }
            return Arrays.copyOf(buf, read);
        }
    }

    private long totalSize(List<Path> files) throws IOException {
        long total = 0L;
        for (Path p : files) {
            total += Files.size(p);
        }
        return total;
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

    private void send(InetSocketAddress recipient, ProtocolMessage msg) throws IOException {
        endpoint.send(recipient, ProtocolIO.toByteArray(msg));
    }

    @Override
    public void close() {
        endpoint.close();
        executor.shutdownNow();
        scheduler.shutdownNow();
        contexts.values().forEach(SenderContext::cleanup);
        contexts.clear();
    }

    private static final class SenderContext {
        final int taskId;
        final TransferTask task;
        final InetSocketAddress receiver;
        final List<Path> files;
        final List<Path> directories;
        final Path root;
        final CompletableFuture<Boolean> acceptFuture = new CompletableFuture<>();
        final Map<Integer, Long> chunkCounts = new ConcurrentHashMap<>();
        final Map<Integer, CompletableFuture<Boolean>> fileDoneFutures = new ConcurrentHashMap<>();
        final Map<Integer, ScheduledFuture<?>> fileTimeouts = new ConcurrentHashMap<>();
        final Map<ChunkKey, CompletableFuture<Void>> chunkAckFutures = new ConcurrentHashMap<>();
        final Map<ChunkKey, ScheduledFuture<?>> chunkTimeouts = new ConcurrentHashMap<>();
        final Map<ChunkKey, ChunkInFlight> inflightChunks = new ConcurrentHashMap<>();

        SenderContext(int taskId, TransferTask task, InetSocketAddress receiver, List<Path> files,
                      List<Path> directories, Path root) {
            this.taskId = taskId;
            this.task = task;
            this.receiver = receiver;
            this.files = files;
            this.directories = directories;
            this.root = root;
        }

        void trackFileTimeout(int fileId, ScheduledFuture<?> future) {
            ScheduledFuture<?> prev = fileTimeouts.put(fileId, future);
            if (prev != null) {
                prev.cancel(false);
            }
        }

        void clearFileTimeout(int fileId) {
            ScheduledFuture<?> future = fileTimeouts.remove(fileId);
            if (future != null) {
                future.cancel(false);
            }
        }

        void trackChunkTimeout(ChunkKey key, ScheduledFuture<?> future) {
            ScheduledFuture<?> prev = chunkTimeouts.put(key, future);
            if (prev != null) {
                prev.cancel(false);
            }
        }

        void cancelChunkTimeout(ChunkKey key) {
            ScheduledFuture<?> future = chunkTimeouts.remove(key);
            if (future != null) {
                future.cancel(false);
            }
        }

        void cleanup() {
            fileTimeouts.values().forEach(f -> f.cancel(false));
            chunkTimeouts.values().forEach(f -> f.cancel(false));
            fileTimeouts.clear();
            chunkTimeouts.clear();
            chunkAckFutures.values().forEach(f -> f.cancel(true));
            fileDoneFutures.values().forEach(f -> f.cancel(true));
        }
    }

    private record ChunkKey(int fileId, long sequence) {}

    private record ChunkInFlight(byte[] data) {}
}
