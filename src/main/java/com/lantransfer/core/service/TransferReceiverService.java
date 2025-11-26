package com.lantransfer.core.service;

import com.lantransfer.core.model.FileChunkBitmap;
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

import javax.swing.SwingUtilities;
import javax.swing.JOptionPane;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TransferReceiverService implements AutoCloseable {
    private static final Logger log = Logger.getLogger(TransferReceiverService.class.getName());

    private final UdpEndpoint endpoint = new UdpEndpoint();
    private final TaskRegistry taskRegistry;
    private final TaskTableModel tableModel;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private Path destinationRoot;
    private final Map<String, ReceiverContext> contexts = new ConcurrentHashMap<>();

    public TransferReceiverService(TaskRegistry taskRegistry, com.lantransfer.ui.common.TaskTableModel tableModel) {
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
                case DIR_CREATE -> onDirCreate(sender, (DirectoryCreateMessage) msg);
                default -> {
                }
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "IO error handling message " + msg.type(), e);
        }
    }

    private void onOffer(InetSocketAddress sender, TransferOfferMessage offer) {
        String taskId = UUID.randomUUID().toString();
        TransferTask task = new TransferTask(Path.of(offer.folderName()), destinationRoot, offer.totalBytes());
        taskRegistry.add(task);
        if (tableModel != null) {
            tableModel.addTask(task);
        }
        contexts.put(taskId, new ReceiverContext(task, sender, offer.fileCount()));

        boolean accept = promptAccept(offer);
        TransferResponseMessage resp = new TransferResponseMessage(offer.taskRequestId(), accept, accept ? taskId : null);
        sendUnchecked(sender, resp);
        if (!accept) {
            task.setStatus(TransferStatus.REJECTED);
        } else {
            task.setStatus(TransferStatus.IN_PROGRESS);
        }
    }

    private boolean promptAccept(TransferOfferMessage offer) {
        final boolean[] result = {false};
        try {
            SwingUtilities.invokeAndWait(() -> {
                int choice = JOptionPane.showConfirmDialog(null,
                        "Incoming transfer: " + offer.folderName() + "\nFiles: " + offer.fileCount() + "\nTotal bytes: " + offer.totalBytes(),
                        "Incoming Transfer", JOptionPane.YES_NO_OPTION);
                result[0] = choice == JOptionPane.YES_OPTION;
            });
        } catch (Exception e) {
            result[0] = false;
        }
        return result[0];
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
        int chunks = (int) Math.ceil((double) msg.size() / ChunkHeader.MAX_BODY_SIZE);
        FileChunkBitmap bitmap = new FileChunkBitmap(tempFile.resolveSibling(tempFile.getFileName() + ".bitmap"), chunks);
        ctx.files.put(msg.fileId(), new ReceiverFile(target, tempFile, msg.size(), msg.md5(), bitmap));
    }

    private void onDirCreate(InetSocketAddress sender, DirectoryCreateMessage msg) {
        ReceiverContext ctx = contexts.get(msg.taskId());
        if (ctx == null) {
            return;
        }
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
        byte[] chunk = msg.chunkBytes();
        if (chunk.length < ChunkHeader.HEADER_SIZE) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(chunk);
        long seq = buffer.getLong();
        byte xorKey = buffer.get();
        short bodySize = buffer.getShort();
        if (bodySize < 0 || bodySize > ChunkHeader.MAX_BODY_SIZE) {
            return;
        }
        if (chunk.length < ChunkHeader.HEADER_SIZE + bodySize) {
            return;
        }
        byte[] body = new byte[bodySize];
        buffer.get(body);
        for (int i = 0; i < bodySize; i++) {
            body[i] = (byte) (body[i] ^ xorKey);
        }
        long offset = seq * ChunkHeader.MAX_BODY_SIZE;
        try (RandomAccessFile raf = new RandomAccessFile(rf.tempFile.toFile(), "rw")) {
            raf.seek(offset);
            raf.write(body);
            rf.bitmap.markReceived((int) seq);
            ctx.task.addBytesTransferred(bodySize);
            if (!rf.completed && rf.bitmap.allReceived()) {
                verifyAndComplete(sender, msg.taskId(), msg.fileId(), rf, ctx);
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to write chunk", e);
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
            int[] missingIdx = missing.stream().toArray();
            sendUnchecked(sender, new FileResendRequestMessage(msg.taskId(), msg.fileId(), missingIdx));
            ctx.scheduleRetry(msg.fileId(), () -> {
                BitSet again = rf.bitmap.missingChunks();
                if (!again.isEmpty()) {
                    int[] idx = again.stream().toArray();
                    sendUnchecked(sender, new FileResendRequestMessage(msg.taskId(), msg.fileId(), idx));
                }
            }, 0);
            return;
        }
        verifyAndComplete(sender, msg.taskId(), msg.fileId(), rf, ctx);
    }

    private void verifyAndComplete(InetSocketAddress sender, String taskId, int fileId, ReceiverFile rf, ReceiverContext ctx) {
        try {
            String md5 = new ChecksumService().md5(rf.tempFile);
            boolean ok = md5.equalsIgnoreCase(rf.expectedMd5);
            if (ok) {
                Files.move(rf.tempFile, rf.target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(rf.bitmapPath());
                rf.completed = true;
                ctx.clearRetry(fileId);
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

    private void sendUnchecked(InetSocketAddress recipient, ProtocolMessage msg) {
        try {
            send(recipient, msg);
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to send message", e);
        }
    }

    private void send(InetSocketAddress recipient, ProtocolMessage msg) throws IOException {
        byte[] data = ProtocolIO.toByteArray(msg);
        endpoint.send(recipient, data);
    }

    @Override
    public void close() {
        endpoint.close();
        scheduler.shutdownNow();
    }

    private static class ReceiverFile {
        final Path target;
        final Path tempFile;
        final long size;
        final String expectedMd5;
        final FileChunkBitmap bitmap;
        volatile boolean completed = false;

        ReceiverFile(Path target, Path tempFile, long size, String expectedMd5, FileChunkBitmap bitmap) {
            this.target = target;
            this.tempFile = tempFile;
            this.size = size;
            this.expectedMd5 = expectedMd5;
            this.bitmap = bitmap;
        }

        Path bitmapPath() {
            return tempFile.resolveSibling(tempFile.getFileName() + ".bitmap");
        }
    }

    private class ReceiverContext {
        final TransferTask task;
        final InetSocketAddress sender;
        final Map<Integer, ReceiverFile> files = new ConcurrentHashMap<>();
        final int expectedFiles;
        int completedFiles = 0;
        final Map<Integer, ScheduledFuture<?>> retries = new ConcurrentHashMap<>();

        ReceiverContext(TransferTask task, InetSocketAddress sender, int expectedFiles) {
            this.task = task;
            this.sender = sender;
            this.expectedFiles = expectedFiles;
        }

        void scheduleRetry(int fileId, Runnable action, int attempt) {
            if (attempt >= 3) {
                task.setStatus(TransferStatus.FAILED);
                return;
            }
            ScheduledFuture<?> future = scheduler.schedule(() -> {
                action.run();
                scheduleRetry(fileId, action, attempt + 1);
            }, 2, TimeUnit.SECONDS);
            ScheduledFuture<?> prev = retries.put(fileId, future);
            if (prev != null) {
                prev.cancel(false);
            }
        }

        void clearRetry(int fileId) {
            ScheduledFuture<?> future = retries.remove(fileId);
            if (future != null) {
                future.cancel(false);
            }
        }
    }
}
