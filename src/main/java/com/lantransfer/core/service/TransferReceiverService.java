package com.lantransfer.core.service;

import com.lantransfer.core.model.FileChunkBitmap;
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
import com.lantransfer.core.protocol.TaskCompleteMessage;
import com.lantransfer.core.protocol.TransferOfferMessage;
import com.lantransfer.core.protocol.TransferResponseMessage;
import com.lantransfer.ui.common.TaskTableModel;
import com.lantransfer.core.protocol.TaskCancelMessage;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TransferReceiverService implements AutoCloseable {
    private static final Logger log = Logger.getLogger(TransferReceiverService.class.getName());
    private static final int MISSING_RETRY_LIMIT = 3;

    private final UdpEndpoint endpoint = new UdpEndpoint();
    private final TaskRegistry taskRegistry;
    private final TaskTableModel tableModel;
    private final Map<Integer, ReceiverContext> contexts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private Path destinationRoot;

    public TransferReceiverService(TaskRegistry taskRegistry, TaskTableModel tableModel) {
        this.taskRegistry = taskRegistry;
        this.tableModel = tableModel;
    }

    public void start(int port, Path destinationRoot) throws InterruptedException {
        this.destinationRoot = destinationRoot;
        endpoint.bind(port, (addr, data) -> {
            try {
                ProtocolMessage msg = ProtocolIO.fromByteArray(data);
                handleIncoming(addr, msg);
            } catch (Exception e) {
                log.log(Level.WARNING, "Failed to decode incoming packet", e);
            }
        });
    }

    private void handleIncoming(InetSocketAddress sender, ProtocolMessage msg) {
        try {
            switch (msg.type()) {
                case TRANSFER_OFFER -> onOffer(sender, (TransferOfferMessage) msg);
                case FILE_META -> onFileMeta(sender, (FileMetaMessage) msg);
                case FILE_CHUNK -> onFileChunk(sender, (FileChunkMessage) msg);
                case FILE_SEND_DONE -> onFileSendDone(sender, (FileSendDoneMessage) msg);
                case TASK_CANCEL -> onTaskCancel((TaskCancelMessage) msg);
                case DIR_CREATE -> onDirCreate((DirectoryCreateMessage) msg);
                default -> log.fine("Receiver ignoring message " + msg.type());
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "IO error handling message " + msg.type(), e);
        }
    }

    private void onOffer(InetSocketAddress sender, TransferOfferMessage msg) throws IOException {
        boolean accepted = promptAccept(msg);
        send(sender, new TransferResponseMessage(msg.taskId(), accepted));
        if (!accepted) {
            return;
        }
        TransferTask task = new TransferTask(msg.taskId(), Path.of(msg.folderName()), destinationRoot, msg.totalBytes());
        task.setStatus(TransferStatus.IN_PROGRESS);
        taskRegistry.add(task);
        if (tableModel != null) {
            tableModel.addTask(task);
        }
        ReceiverContext ctx = new ReceiverContext(task, sender, msg.fileCount());
        contexts.put(msg.taskId(), ctx);
    }

    private boolean promptAccept(TransferOfferMessage offer) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        try {
            SwingUtilities.invokeLater(() -> {
                int choice = JOptionPane.showConfirmDialog(
                        null,
                        "Incoming transfer: " + offer.folderName() + "\nFiles: " + offer.fileCount() + "\nTotal bytes: " + offer.totalBytes(),
                        "Incoming Transfer", JOptionPane.YES_NO_OPTION);
                future.complete(choice == JOptionPane.YES_OPTION);
            });
            return future.get();
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to prompt for incoming transfer", e);
            return false;
        }
    }

    private void onFileMeta(InetSocketAddress sender, FileMetaMessage msg) throws IOException {
        ReceiverContext ctx = contexts.get(msg.taskId());
        if (ctx == null) {
            return;
        }
        Path target = destinationRoot.resolve(msg.relativePath()).normalize();
        if (!target.startsWith(destinationRoot)) {
            log.warning("Rejected path outside destination: " + target);
            return;
        }
        Files.createDirectories(target.getParent());
        Path tempFile = target.resolveSibling(target.getFileName() + ".part");
        Files.deleteIfExists(tempFile);
        Files.createFile(tempFile);
        long chunkCount = (msg.size() + ChunkHeader.MAX_BODY_SIZE - 1) / ChunkHeader.MAX_BODY_SIZE;
        FileChunkBitmap bitmap = new FileChunkBitmap(tempFile.resolveSibling(tempFile.getFileName() + ".bitmap"), (int) chunkCount);
        ctx.files.put(msg.fileId(), new ReceiverFile(target, tempFile, msg.size(), msg.md5(), bitmap, chunkCount));
    }

    private void onDirCreate(DirectoryCreateMessage msg) {
        Path target = destinationRoot.resolve(msg.relativePath()).normalize();
        if (!target.startsWith(destinationRoot)) {
            log.warning("Rejected directory outside destination: " + target);
            return;
        }
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to create directory " + target, e);
        }
    }

    private void onFileChunk(InetSocketAddress sender, FileChunkMessage msg) {
        ReceiverContext ctx = contexts.get(msg.taskId());
        if (ctx == null) {
            return;
        }
        ReceiverFile rf = ctx.files.get(msg.fileId());
        if (rf == null) {
            return;
        }
        byte[] decoded = new byte[msg.body().length];
        for (int i = 0; i < msg.body().length; i++) {
            decoded[i] = (byte) (msg.body()[i] ^ msg.xorKey());
        }
        long seq = msg.chunkSeq();
        int index = (int) seq;
        long offset = seq * ChunkHeader.MAX_BODY_SIZE;
        try (RandomAccessFile raf = new RandomAccessFile(rf.tempFile.toFile(), "rw")) {
            raf.seek(offset);
            raf.write(decoded);
            raf.getFD().sync();
            if (!rf.bitmap.isReceived(index)) {
                rf.bitmap.markReceived(index);
                ctx.task.addBytesTransferred(decoded.length);
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to write chunk seq " + seq, e);
        }
        sendUnchecked(sender, new ChunkAckMessage(msg.taskId(), msg.fileId(), msg.chunkSeq()));
        maybeRequestMissingChunks(sender, ctx, rf, msg.taskId(), msg.fileId(), seq);
    }

    private void maybeRequestMissingChunks(InetSocketAddress sender, ReceiverContext ctx, ReceiverFile rf,
                                           int taskId, int fileId, long seq) {
        rf.updateHighest(seq);
        List<Long> missingSeqs = rf.computeMissingBefore(seq);
        if (!missingSeqs.isEmpty()) {
            long[] arr = missingSeqs.stream().mapToLong(Long::longValue).toArray();
            sendUnchecked(sender, new FileResendRequestMessage(taskId, fileId, arr));
        }
    }

    private void onFileSendDone(InetSocketAddress sender, FileSendDoneMessage msg) {
        ReceiverContext ctx = contexts.get(msg.taskId());
        if (ctx == null) {
            return;
        }
        ReceiverFile rf = ctx.files.get(msg.fileId());
        if (rf == null) {
            return;
        }
        BitSet missing = rf.bitmap.missingChunks();
        if (!missing.isEmpty()) {
            long[] missingSeq = missing.stream().mapToLong(i -> i).toArray();
            sendUnchecked(sender, new FileResendRequestMessage(msg.taskId(), msg.fileId(), missingSeq));
            scheduleMissingRetry(ctx, sender, msg.taskId(), msg.fileId(), missingSeq, 1);
            return;
        }
        verifyAndComplete(sender, msg.taskId(), msg.fileId(), rf, ctx);
    }

    private void scheduleMissingRetry(ReceiverContext ctx, InetSocketAddress sender, int taskId, int fileId, long[] seqs, int attempt) {
        if (attempt > MISSING_RETRY_LIMIT) {
            ctx.task.setStatus(TransferStatus.FAILED);
            return;
        }
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            ReceiverFile rf = ctx.files.get(fileId);
            if (rf == null || rf.bitmap.missingChunks().isEmpty()) {
                ctx.clearMissingRetry(fileId);
                return;
            }
            long[] missingSeq = rf.bitmap.missingChunks().stream().mapToLong(i -> i).toArray();
            sendUnchecked(sender, new FileResendRequestMessage(taskId, fileId, missingSeq));
            scheduleMissingRetry(ctx, sender, taskId, fileId, missingSeq, attempt + 1);
        }, 2, TimeUnit.SECONDS);
        ctx.trackMissingRetry(fileId, future);
    }

    private void verifyAndComplete(InetSocketAddress sender, int taskId, int fileId, ReceiverFile rf, ReceiverContext ctx) {
        try {
            String md5 = new ChecksumService().md5(rf.tempFile);
            boolean ok = md5.equalsIgnoreCase(rf.expectedMd5);
            if (ok) {
                Files.move(rf.tempFile, rf.target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(rf.bitmapPath());
                rf.completed = true;
                ctx.clearMissingRetry(fileId);
            }
            sendUnchecked(sender, new FileCompleteMessage(taskId, fileId, ok));
            if (ok) {
                ctx.completedFiles++;
                if (ctx.completedFiles >= ctx.expectedFiles) {
                    ctx.task.setStatus(TransferStatus.COMPLETED);
                    sendUnchecked(sender, new TaskCompleteMessage(taskId));
                }
            } else {
                ctx.task.setStatus(TransferStatus.RESENDING);
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to verify file", e);
        }
    }

    private void onTaskCancel(TaskCancelMessage msg) {
        ReceiverContext ctx = contexts.remove(msg.taskId());
        if (ctx != null) {
            ctx.task.setStatus(TransferStatus.CANCELED);
        }
    }

    private void sendUnchecked(InetSocketAddress recipient, ProtocolMessage msg) {
        try {
            send(recipient, msg);
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to send message " + msg.type(), e);
        }
    }

    private void send(InetSocketAddress recipient, ProtocolMessage msg) throws IOException {
        endpoint.send(recipient, ProtocolIO.toByteArray(msg));
    }

    @Override
    public void close() {
        endpoint.close();
        scheduler.shutdownNow();
        contexts.clear();
    }

    private static class ReceiverFile {
        final Path target;
        final Path tempFile;
        final long size;
        final String expectedMd5;
        final FileChunkBitmap bitmap;
        final long totalChunks;
        volatile boolean completed = false;
        private volatile long highestSeqSeen = -1;
        private volatile long contiguousSeq = -1;

        ReceiverFile(Path target, Path tempFile, long size, String expectedMd5, FileChunkBitmap bitmap, long totalChunks) {
            this.target = target;
            this.tempFile = tempFile;
            this.size = size;
            this.expectedMd5 = expectedMd5;
            this.bitmap = bitmap;
            this.totalChunks = totalChunks;
        }

        Path bitmapPath() {
            return tempFile.resolveSibling(tempFile.getFileName() + ".bitmap");
        }

        synchronized void updateHighest(long seq) {
            if (seq > highestSeqSeen) {
                highestSeqSeen = seq;
            }
            while (contiguousSeq + 1 <= highestSeqSeen &&
                    bitmap.isReceived((int) (contiguousSeq + 1))) {
                contiguousSeq++;
            }
        }

        synchronized List<Long> computeMissingBefore(long seq) {
            long expected = contiguousSeq + 1;
            List<Long> missing = new ArrayList<>();
            for (long s = expected; s < seq; s++) {
                if (!bitmap.isReceived((int) s)) {
                    missing.add(s);
                }
            }
            return missing;
        }
    }

    private static class ReceiverContext {
        final TransferTask task;
        final InetSocketAddress sender;
        final Map<Integer, ReceiverFile> files = new ConcurrentHashMap<>();
        final int expectedFiles;
        volatile int completedFiles = 0;
        final Map<Integer, ScheduledFuture<?>> missingRetries = new ConcurrentHashMap<>();

        ReceiverContext(TransferTask task, InetSocketAddress sender, int expectedFiles) {
            this.task = task;
            this.sender = sender;
            this.expectedFiles = expectedFiles;
        }

        void trackMissingRetry(int fileId, ScheduledFuture<?> future) {
            ScheduledFuture<?> prev = missingRetries.put(fileId, future);
            if (prev != null) {
                prev.cancel(false);
            }
        }

        void clearMissingRetry(int fileId) {
            ScheduledFuture<?> future = missingRetries.remove(fileId);
            if (future != null) {
                future.cancel(false);
            }
        }
    }
}
