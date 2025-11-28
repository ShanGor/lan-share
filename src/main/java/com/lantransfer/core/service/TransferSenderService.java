package com.lantransfer.core.service;

import com.lantransfer.core.model.TransferStatus;
import com.lantransfer.core.model.TransferTask;
import com.lantransfer.core.net.UdpEndpoint;
import com.lantransfer.core.protocol.ChunkAckMessage;
import com.lantransfer.core.protocol.ChunkHeader;
import com.lantransfer.core.protocol.ChunkRequestMessage;
import com.lantransfer.core.protocol.DirectoryCreateMessage;
import com.lantransfer.core.protocol.FileChunkMessage;
import com.lantransfer.core.protocol.FileCompleteMessage;
import com.lantransfer.core.protocol.FileMetaMessage;
import com.lantransfer.core.protocol.ProtocolIO;
import com.lantransfer.core.protocol.ProtocolMessage;
import com.lantransfer.core.protocol.MetaAckMessage;
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
    private static final int CHUNK_REQUEST_TIMEOUT_SECONDS = 5; // Increased to handle higher request volume
    private static final int MAX_CHUNK_RETRY = 8; // Increased to handle more retries with faster requests

    private final UdpEndpoint endpoint = new UdpEndpoint();
    private final TaskRegistry taskRegistry;
    private final TaskTableModel tableModel;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Map<Integer, SenderContext> contexts = new ConcurrentHashMap<>();

    public TransferSenderService(TaskRegistry taskRegistry) {
        this.taskRegistry = taskRegistry;
        this.tableModel = null;
    }

    public TransferSenderService(TaskRegistry taskRegistry, TaskTableModel tableModel) {
        this.taskRegistry = taskRegistry;
        this.tableModel = tableModel;
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
        executor.submit(() -> sendInternal(folder, receiver, tableModel));
    }

    private void sendInternal(Path folder, InetSocketAddress receiver, TaskTableModel tableModel) {
        int taskId = TaskIdGenerator.nextId();
        SenderContext ctx = null;
        try {
            List<Path> files = listFiles(folder);
            List<Path> dirs = listDirs(folder);
            long totalBytes = totalSize(files);
            TransferTask task = new TransferTask(taskId, folder, Path.of(""), totalBytes);
            task.setStatus(TransferStatus.PENDING);
            taskRegistry.add(task);
            if (tableModel != null) {
                tableModel.addTask(task);
            }
            ctx = new SenderContext(taskId, task, receiver, files, dirs, folder);
            contexts.put(taskId, ctx);

            send(receiver, new TransferOfferMessage(taskId, folder.getFileName().toString(), totalBytes, files.size() + dirs.size(), 0, 'D'));

            boolean accepted = ctx.acceptFuture.get(30, TimeUnit.SECONDS);
            if (!accepted) {
                task.setStatus(TransferStatus.REJECTED);
                return;
            }
            task.setStatus(TransferStatus.IN_PROGRESS);
            sendEntries(ctx);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Send failed", e);
            if (ctx != null) {
                ctx.task.setStatus(TransferStatus.FAILED);
            }
        } finally {
            TaskIdGenerator.release(taskId);
        }
    }

    private void sendEntries(SenderContext ctx) throws IOException {
        int id = 0;

        // Send directory metadata
        for (Path dir : ctx.directories) {
            String rel = ctx.root.relativize(dir).toString();
            ctx.entries.put(id, new SenderEntry(rel, dir, true));
            sendFileMetaWithRetry(ctx, ctx.taskId, id, 'D', rel, 0L, "");
            id++;
        }

        // Send file metadata
        for (Path file : ctx.files) {
            long size = Files.size(file);
            long totalChunks = (size + ChunkHeader.MAX_BODY_SIZE - 1) / ChunkHeader.MAX_BODY_SIZE;
            String rel = ctx.root.relativize(file).toString();
            String md5 = new ChecksumService().md5(file);
            ctx.entries.put(id, new SenderEntry(rel, file, false));
            ctx.chunkCounts.put(id, totalChunks);
            sendFileMetaWithRetry(ctx, ctx.taskId, id, 'F', rel, size, md5);
            id++;
        }
    }

    private void sendFileMetaWithRetry(SenderContext ctx, int taskId, int fileId, char entryType, String relPath, long size, String md5) throws IOException {
        FileMetaMessage msg = new FileMetaMessage(taskId, fileId, entryType, relPath, size, md5);
        // Update current file path for UI
        SenderEntry entry = ctx.entries.get(fileId);
        if (entry != null) {
            ctx.currentFilePath = entry.path.toString();
            ctx.maybeRefreshUI(tableModel);
        }
        send(ctx.receiver, msg);

        // Track that FILE_META was sent and schedule retry if no ACK received
        ScheduledFuture<?> retryFuture = scheduler.scheduleWithFixedDelay(() -> {
            if (Boolean.TRUE.equals(ctx.fileMetaAcked.get(fileId))) {
                // If acknowledged, cancel this retry task
                ScheduledFuture<?> future = ctx.chunkTimeouts.remove(-fileId);
                if (future != null) {
                    future.cancel(false);
                }
                return;
            }
            // Still not acknowledged, retry sending FILE_META
            try {
                // Update current file path for UI during retries
                SenderEntry retryEntry = ctx.entries.get(fileId);
                if (retryEntry != null) {
                    ctx.currentFilePath = retryEntry.path.toString();
                    ctx.maybeRefreshUI(tableModel);
                }
                send(ctx.receiver, msg);
            } catch (IOException e) {
                log.log(Level.WARNING, "Failed to resend FILE_META for fileId " + fileId, e);
            }
        }, 1000, 2000, TimeUnit.MILLISECONDS); // Retry after 1s, then every 2s

        // Store the retry task so we can cancel it when ACK is received
        ctx.chunkTimeouts.put(-fileId, retryFuture);
    }

    private void handleIncoming(InetSocketAddress addr, ProtocolMessage msg) {
        switch (msg.type()) {
            case TRANSFER_RESPONSE -> onTransferResponse((TransferResponseMessage) msg);
            case CHUNK_REQUEST -> onChunkRequest(addr, (ChunkRequestMessage) msg);
            case CHUNK_ACK -> {
                // no-op for now; receiver drives cadence
            }
            case FILE_COMPLETE -> onFileComplete((FileCompleteMessage) msg);
            case META_ACK -> onMetaAck((MetaAckMessage) msg);
            case TASK_COMPLETE -> onTaskComplete((TaskCompleteMessage) msg);
            default -> log.fine("Ignoring message " + msg.type());
        }
    }

    private void onTransferResponse(TransferResponseMessage msg) {
        SenderContext ctx = contexts.get(msg.taskId());
        if (ctx != null) {
            ctx.acceptFuture.complete(msg.accepted());
        }
    }

    private void onChunkRequest(InetSocketAddress addr, ChunkRequestMessage msg) {
        SenderContext ctx = contexts.get(msg.taskId());
        if (ctx == null) return;
        SenderEntry entry = ctx.entries.get(msg.fileId());
        if (entry == null || entry.isDirectory) return;

        // Update current file path for UI display
        ctx.currentFilePath = entry.path.toString();

        try {
            byte[] chunk = readChunk(entry.path, msg.chunkSeq());
            byte xorKey = (byte) (System.nanoTime() & 0xFF);
            byte[] body = xor(chunk, xorKey);
            send(addr, new FileChunkMessage(msg.taskId(), msg.fileId(), msg.chunkSeq(), xorKey, body));
            ctx.task.addBytesTransferred(body.length);
            ctx.maybeRefreshUI(tableModel);
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to serve chunk request " + msg, e);
        }
    }

    private void onMetaAck(MetaAckMessage msg) {
        SenderContext ctx = contexts.get(msg.taskId());
        if (ctx == null) return;
        // Mark that FILE_META for this file ID has been acknowledged
        ctx.fileMetaAcked.put(msg.fileId(), true);
        // Cancel any pending retries for this file meta
        ScheduledFuture<?> retryFuture = ctx.chunkTimeouts.remove(-msg.fileId()); // using negative fileId as key for meta retries
        if (retryFuture != null) {
            retryFuture.cancel(false);
        }
    }

    private void onFileComplete(FileCompleteMessage msg) {
        SenderContext ctx = contexts.get(msg.taskId());
        if (ctx == null) return;
        if (!msg.success()) {
            ctx.task.setStatus(TransferStatus.RESENDING);
        }
        ctx.completedFiles.add(msg.fileId());
        if (ctx.completedFiles.size() == ctx.entries.size()) {
            ctx.task.setStatus(TransferStatus.COMPLETED);
        }
    }

    private void onTaskComplete(TaskCompleteMessage msg) {
        SenderContext ctx = contexts.get(msg.taskId());
        if (ctx != null) {
            ctx.task.setStatus(TransferStatus.COMPLETED);
        }
    }

    public void cancelTask(int taskId) {
        SenderContext ctx = contexts.get(taskId);
        if (ctx != null) {
            try {
                send(ctx.receiver, new TaskCancelMessage(taskId));
                ctx.task.setStatus(TransferStatus.CANCELED);
                contexts.remove(taskId);
                TaskIdGenerator.release(taskId);
            } catch (IOException e) {
                log.log(Level.WARNING, "Failed to send task cancel message", e);
                ctx.task.setStatus(TransferStatus.CANCELED);
                contexts.remove(taskId);
                TaskIdGenerator.release(taskId);
            }
        }
    }

    private byte[] readChunk(Path file, long seq) throws IOException {
        try (FileInputStream fis = new FileInputStream(file.toFile())) {
            long skip = seq * ChunkHeader.MAX_BODY_SIZE;
            long skipped = fis.skip(skip);
            while (skipped < skip) {
                long s = fis.skip(skip - skipped);
                if (s <= 0) break;
                skipped += s;
            }
            byte[] buf = new byte[ChunkHeader.MAX_BODY_SIZE];
            int read = fis.read(buf);
            if (read < 0) return new byte[0];
            byte[] out = new byte[read];
            System.arraycopy(buf, 0, out, 0, read);
            return out;
        }
    }

    private byte[] xor(byte[] data, byte key) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ key);
        }
        return out;
    }

    private List<Path> listFiles(Path folder) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walk(folder).filter(Files::isRegularFile).forEach(files::add);
        return files;
    }

    private List<Path> listDirs(Path folder) throws IOException {
        List<Path> dirs = new ArrayList<>();
        Files.walk(folder).filter(Files::isDirectory).filter(p -> !p.equals(folder)).forEach(dirs::add);
        return dirs;
    }

    private long totalSize(List<Path> files) throws IOException {
        long total = 0;
        for (Path p : files) {
            total += Files.size(p);
        }
        return total;
    }

    private void send(InetSocketAddress recipient, ProtocolMessage msg) throws IOException {
        endpoint.send(recipient, ProtocolIO.toByteArray(msg));
    }

    @Override
    public void close() {
        endpoint.close();
        executor.shutdownNow();
        scheduler.shutdownNow();
    }

    private static class SenderContext {
        final int taskId;
        final TransferTask task;
        final InetSocketAddress receiver;
        final List<Path> files;
        final List<Path> directories;
        final Path root;
        final CompletableFuture<Boolean> acceptFuture = new CompletableFuture<>();
        final Map<Integer, SenderEntry> entries = new ConcurrentHashMap<>();
        final Map<Integer, Long> chunkCounts = new ConcurrentHashMap<>();
        final Map<Integer, ScheduledFuture<?>> chunkTimeouts = new ConcurrentHashMap<>();
        final Map<Integer, Integer> chunkRetry = new ConcurrentHashMap<>();
        final List<Integer> completedFiles = new ArrayList<>();
        final Map<Integer, Boolean> fileMetaAcked = new ConcurrentHashMap<>(); // Track if FILE_META is acknowledged
        volatile String currentFilePath = "";
        final int listenPort = 0;

        volatile long lastUiUpdateNanos = System.nanoTime();

        SenderContext(int taskId, TransferTask task, InetSocketAddress receiver, List<Path> files, List<Path> directories, Path root) {
            this.taskId = taskId;
            this.task = task;
            this.receiver = receiver;
            this.files = files;
            this.directories = directories;
            this.root = root;
        }

        void maybeRefreshUI(TaskTableModel model) {
            if (model == null) return;
            long now = System.nanoTime();
            if (now - lastUiUpdateNanos > 200_000_000L) { // 0.2s - more frequent updates
                lastUiUpdateNanos = now;
                // Update current file path in the UI
                model.setCurrentFile(String.format("%05d", taskId), currentFilePath);
            }
        }

        void cleanup() {
            chunkTimeouts.values().forEach(f -> f.cancel(false));
        }
    }

    private record SenderEntry(String relPath, Path path, boolean isDirectory) {}
}
