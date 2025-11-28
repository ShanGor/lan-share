package com.lantransfer.core.service;

import com.lantransfer.core.model.FileChunkBitmap;
import com.lantransfer.core.model.TransferStatus;
import com.lantransfer.core.model.TransferTask;
import com.lantransfer.core.net.QuicMessageUtil;
import com.lantransfer.core.protocol.ChunkAckMessage;
import com.lantransfer.core.protocol.ChunkAckMessage.AckRange;
import com.lantransfer.core.protocol.ChunkHeader;
import com.lantransfer.core.protocol.FileChunkMessage;
import com.lantransfer.core.protocol.FileCompleteMessage;
import com.lantransfer.core.protocol.FileMetaMessage;
import com.lantransfer.core.protocol.MetaAckMessage;
import com.lantransfer.core.protocol.ProtocolIO;
import com.lantransfer.core.protocol.ProtocolMessage;
import com.lantransfer.core.protocol.TaskCancelMessage;
import com.lantransfer.core.protocol.TaskCompleteMessage;
import com.lantransfer.core.protocol.TransferOfferMessage;
import com.lantransfer.core.protocol.TransferResponseMessage;
import com.lantransfer.ui.common.TaskTableModel;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicServerCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.pkitesting.CertificateBuilder;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TransferReceiverService implements AutoCloseable {
    private static final Logger log = Logger.getLogger(TransferReceiverService.class.getName());
    private static final int MAX_ACK_RANGES = 4;
    private final TaskRegistry taskRegistry;
    private final TaskTableModel tableModel;
    private final ExecutorService workerPool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
    private final Map<Integer, ReceiverContext> contexts = new ConcurrentHashMap<>();
    private NioEventLoopGroup serverGroup;
    private Channel serverChannel;
    private Path destinationRoot;

    public TransferReceiverService(TaskRegistry taskRegistry, TaskTableModel tableModel) {
        this.taskRegistry = taskRegistry;
        this.tableModel = tableModel;
    }

    public void start(int port, Path destinationRoot) throws Exception {
        this.destinationRoot = destinationRoot;
        CertificateBuilder builder = new CertificateBuilder();

        var certificate = builder.subject("CN=lan-transfer").setIsCertificateAuthority(true)
                .ecp256()
                .notBefore(Instant.now())
                .notAfter(Instant.now().plusSeconds(365 * 24L * 3600L))
                .buildSelfSigned() ;

        QuicSslContext serverSslContext = QuicSslContextBuilder.forServer(certificate.toKeyManagerFactory(), null)
                .applicationProtocols("lan-transfer")
                .build();
        ChannelInitializer<QuicStreamChannel> streamInitializer = new ChannelInitializer<QuicStreamChannel>() {
            @Override
            protected void initChannel(QuicStreamChannel ch) {
                ch.pipeline().addLast(QuicMessageUtil.newFrameDecoder());
                ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
                        byte[] data = new byte[msg.readableBytes()];
                        msg.readBytes(data);
                        ProtocolMessage protocolMessage = ProtocolIO.fromByteArray(data);
                        handleIncoming((QuicStreamChannel) ctx.channel(), protocolMessage);
                    }

                    @Override
                    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                        super.channelInactive(ctx);
                        cleanupChannel((QuicStreamChannel) ctx.channel());
                    }
                });
            }
        };
        ChannelHandler codec = new QuicServerCodecBuilder()
                .sslContext(serverSslContext)
                .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
                .initialMaxData(10_000_000)
                .initialMaxStreamDataBidirectionalLocal(1_000_000)
                .initialMaxStreamsBidirectional(100)
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel ch) {
                        // no-op
                    }
                })
                .streamHandler(streamInitializer)
                .build();
        serverGroup = new NioEventLoopGroup(1);
        Bootstrap bootstrap = new Bootstrap();
        serverChannel = bootstrap.group(serverGroup)
                .channel(NioDatagramChannel.class)
                .handler(codec)
                .bind(port)
                .sync()
                .channel();
        log.info("QUIC receiver listening on port " + port);
    }

    private void handleIncoming(QuicStreamChannel channel, ProtocolMessage msg) {
        try {
            switch (msg.type()) {
                case TRANSFER_OFFER -> onOffer(channel, (TransferOfferMessage) msg);
                case FILE_META -> onFileMeta(channel, (FileMetaMessage) msg);
                case FILE_CHUNK -> onFileChunk(channel, (FileChunkMessage) msg);
                case TASK_CANCEL -> onTaskCancel((TaskCancelMessage) msg);
                case CHUNK_ACK -> { /* ignore */ }
                default -> log.fine("Ignoring message " + msg.type());
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Error handling message " + msg.type(), e);
        }
    }

    private void cleanupChannel(QuicStreamChannel channel) {
        contexts.entrySet().removeIf(entry -> {
            ReceiverContext ctx = entry.getValue();
            if (ctx.channel == channel) {
                ctx.task.setStatus(TransferStatus.FAILED);
                ctx.cleanup();
                return true;
            }
            return false;
        });
    }

    private void onOffer(QuicStreamChannel channel, TransferOfferMessage offer) {
        boolean accept = promptAccept(offer);
        sendUnchecked(channel, new TransferResponseMessage(offer.taskId(), accept));
        if (!accept) return;
        TransferTask task = new TransferTask(offer.taskId(), Path.of(offer.folderName()), destinationRoot, offer.totalBytes());
        task.setStatus(TransferStatus.IN_PROGRESS);
        taskRegistry.add(task);
        if (tableModel != null) tableModel.addTask(task);
        ReceiverContext ctx = new ReceiverContext(task, channel, offer.fileCount());
        contexts.put(offer.taskId(), ctx);
    }

    private boolean promptAccept(TransferOfferMessage offer) {
        if (SwingUtilities.isEventDispatchThread()) {
            return showAcceptDialog(offer);
        }
        FutureTask<Boolean> task = new FutureTask<>(() -> showAcceptDialog(offer));
        SwingUtilities.invokeLater(task);
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to prompt for incoming transfer", e);
            return false;
        }
    }

    private boolean showAcceptDialog(TransferOfferMessage offer) {
        int choice = JOptionPane.showConfirmDialog(null,
                "Incoming transfer: " + offer.folderName() + "\nFiles: " + offer.fileCount() + "\nTotal bytes: " + offer.totalBytes(),
                "Incoming Transfer", JOptionPane.YES_NO_OPTION);
        return choice == JOptionPane.YES_OPTION;
    }

    private void onFileMeta(QuicStreamChannel channel, FileMetaMessage msg) throws IOException {
        ReceiverContext ctx = contexts.get(msg.taskId());
        if (ctx == null) return;
        if (ctx.channel != channel) {
            log.warning("Ignoring FILE_META for task " + msg.taskId() + " from unexpected channel.");
            return;
        }
        Path target = destinationRoot.resolve(msg.relativePath()).normalize();
        if (!target.startsWith(destinationRoot)) {
            log.warning("Rejected path outside destination: " + target);
            return;
        }

        // Send META_ACK to acknowledge receipt of metadata
        sendUnchecked(channel, new MetaAckMessage(msg.taskId(), msg.fileId()));

        if (msg.entryType() == 'D') {
            Files.createDirectories(target);
            // Update current file path for UI
            ctx.currentFilePath = target.toString();
            sendUnchecked(channel, new FileCompleteMessage(msg.taskId(), msg.fileId(), true));
            ctx.completedFiles++;
            if (ctx.completedFiles >= ctx.expectedFiles) {
                ctx.task.setStatus(TransferStatus.COMPLETED);
                sendUnchecked(channel, new TaskCompleteMessage(msg.taskId()));
                if (contexts.remove(msg.taskId(), ctx)) {
                    ctx.cleanup();
                }
            }
            return;
        }
        // File
        Files.createDirectories(target.getParent());
        Path tempFile = target.resolveSibling(target.getFileName() + ".part");
        Files.deleteIfExists(tempFile);
        Files.createFile(tempFile);
        long chunkCount = (msg.size() + ChunkHeader.MAX_BODY_SIZE - 1) / ChunkHeader.MAX_BODY_SIZE;
        FileChunkBitmap bitmap = new FileChunkBitmap(tempFile.resolveSibling(tempFile.getFileName() + ".bitmap"), (int) chunkCount);
        ReceiverFile rf = new ReceiverFile(target, tempFile, msg.size(), msg.md5(), bitmap, chunkCount);
        ctx.files.put(msg.fileId(), rf);
        // Update current file path for UI
        ctx.currentFilePath = target.toString();
    }

    private void onFileChunk(QuicStreamChannel channel, FileChunkMessage msg) {
        ReceiverContext ctx = contexts.get(msg.taskId());
        if (ctx == null || ctx.channel != channel) return;
        ReceiverFile rf = ctx.files.get(msg.fileId());
        if (rf == null) return;

        // Update current file path for UI display
        ctx.currentFilePath = rf.target.toString();

        if (ctx.task.getStatus() == TransferStatus.RESENDING) {
            ctx.task.setStatus(TransferStatus.IN_PROGRESS);
        }
        int index = (int) msg.chunkSeq();
        if (rf.isAcked(index)) {
            sendAck(ctx, rf, msg.taskId(), msg.fileId(), index);
            return;
        }
        if (rf.isFinalizing() || rf.isFinalized()) {
            log.fine("Ignoring chunk " + index + " for finalized fileId " + msg.fileId());
            sendAck(ctx, rf, msg.taskId(), msg.fileId(), index);
            return;
        }
        byte[] decoded = xor(msg.body(), msg.xorKey());
        workerPool.execute(() -> writeChunkAndAck(ctx, msg, rf, decoded, index));
    }

    private void writeChunkAndAck(ReceiverContext ctx, FileChunkMessage msg, ReceiverFile rf, byte[] decoded, int index) {
        long offset = msg.chunkSeq() * ChunkHeader.MAX_BODY_SIZE;
        boolean wrote = false;
        try {
            rf.writeChunk(offset, decoded);
            rf.markReceived(index);
            ctx.task.addBytesTransferred(decoded.length);
            ctx.maybeRefreshUI(tableModel);
            wrote = true;
        } catch (ClosedChannelException e) {
            log.log(Level.FINE, "Channel closed while writing chunk " + msg.chunkSeq() + ", ignoring duplicate");
        } catch (IOException e) {
            log.log(Level.WARNING, "Write failed for chunk " + msg.chunkSeq(), e);
        }
        if (!wrote) {
            ctx.task.setStatus(TransferStatus.RESENDING);
            return;
        }
        sendAck(ctx, rf, msg.taskId(), msg.fileId(), index);
        if (rf.allWritten() && rf.beginFinalize()) {
            verifyAndComplete(ctx, msg.taskId(), msg.fileId(), rf);
        }
    }

    private void sendAck(ReceiverContext ctx, ReceiverFile rf, int taskId, int fileId, int focusIndex) {
        List<AckRange> ranges = rf.ackRangesFor(focusIndex, MAX_ACK_RANGES);
        sendUnchecked(ctx.channel, new ChunkAckMessage(taskId, fileId, ranges));
    }

    private void verifyAndComplete(ReceiverContext ctx, int taskId, int fileId, ReceiverFile rf) {
        try {
            rf.forceWrites();
        } catch (IOException e) {
            log.log(Level.FINE, "Failed to flush writes before verification for " + rf.tempFile, e);
        }
        try {
            if (!Files.exists(rf.tempFile)) {
                sendUnchecked(ctx.channel, new FileCompleteMessage(taskId, fileId, false));
                ctx.task.setStatus(TransferStatus.RESENDING);
                return;
            }
            String md5 = new ChecksumService().md5(rf.tempFile);
            boolean ok = md5.equalsIgnoreCase(rf.expectedMd5);
            sendUnchecked(ctx.channel, new FileCompleteMessage(taskId, fileId, ok));
            if (ok) {
                boolean moved = moveFileWithRetry(rf.tempFile, rf.target);
                if (moved) {
                    Files.deleteIfExists(rf.bitmapPath());
                    ctx.completedFiles++;
                    if (ctx.completedFiles >= ctx.expectedFiles) {
                        ctx.task.setStatus(TransferStatus.COMPLETED);
                        sendUnchecked(ctx.channel, new TaskCompleteMessage(taskId));
                        if (contexts.remove(taskId, ctx)) {
                            ctx.cleanup();
                        }
                    }
                } else {
                    log.log(Level.WARNING, "Failed to move file after retries: " + rf.tempFile + " -> " + rf.target);
                    ctx.task.setStatus(TransferStatus.FAILED);
                }
            } else {
                ctx.task.setStatus(TransferStatus.RESENDING);
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to verify file", e);
        } finally {
            ctx.files.remove(fileId);
            rf.markFinalized();
            try {
                rf.closeChannel();
            } catch (IOException e) {
                log.log(Level.FINE, "Failed to close channel after verification for " + rf.tempFile, e);
            }
        }
    }

    private void onTaskCancel(TaskCancelMessage msg) {
        ReceiverContext ctx = contexts.remove(msg.taskId());
        if (ctx != null) {
            ctx.task.setStatus(TransferStatus.CANCELED);
            ctx.cleanup();
        }
    }

    private void sendUnchecked(Channel channel, ProtocolMessage msg) {
        try {
            QuicMessageUtil.write(channel, msg);
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to send message " + msg.type(), e);
        }
    }

    private byte[] xor(byte[] data, byte key) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ key);
        }
        return out;
    }

    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (serverGroup != null) {
            serverGroup.shutdownGracefully();
        }
        contexts.values().forEach(ReceiverContext::cleanup);
        contexts.clear();
        workerPool.shutdownNow();
    }

    private boolean moveFileWithRetry(Path source, Path target) {
        int maxRetries = 5;
        int retryDelayMs = 100; // 100ms between retries

        for (int i = 0; i < maxRetries; i++) {
            try {
                Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return true; // Success
            } catch (IOException e) {
                if (i == maxRetries - 1) { // Last attempt
                    log.log(Level.WARNING, "Failed to move file after " + maxRetries + " attempts: " + source + " -> " + target, e);
                    return false;
                }
                // Wait before retry
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static class ReceiverContext {
        final TransferTask task;
        final QuicStreamChannel channel;
        final Map<Integer, ReceiverFile> files = new ConcurrentHashMap<>();
        final int expectedFiles;
        volatile int completedFiles = 0;
        volatile long lastUiUpdateNanos = System.nanoTime();
        volatile String currentFilePath = "";

        ReceiverContext(TransferTask task, QuicStreamChannel channel, int expectedFiles) {
            this.task = task;
            this.channel = channel;
            this.expectedFiles = expectedFiles;
        }

        void maybeRefreshUI(TaskTableModel model) {
            if (model == null) return;
            long now = System.nanoTime();
            if (now - lastUiUpdateNanos > 200_000_000L) { // 0.2s - more frequent updates
                lastUiUpdateNanos = now;
                // Update current file path in the UI
                model.setCurrentFile(String.format("%05d", task.getProtocolTaskId()), currentFilePath);
                javax.swing.SwingUtilities.invokeLater(model::refresh);
            }
        }

        void cleanup() {
            files.values().forEach(ReceiverFile::closeQuietly);
            files.clear();
            if (channel != null) {
                channel.close();
            }
        }
    }

    private static class ReceiverFile {
        final Path target;
        final Path tempFile;
        final long size;
        final String expectedMd5;
        final FileChunkBitmap bitmap;
        final long totalChunks;
        final Object writeLock = new Object();
        final BitSet acked = new BitSet();
        final FileChannel channel;
        volatile boolean closed = false;
        int highestContiguous = -1;
        volatile boolean finalizing = false;
        volatile boolean finalized = false;

        ReceiverFile(Path target, Path tempFile, long size, String expectedMd5, FileChunkBitmap bitmap, long totalChunks) throws IOException {
            this.target = target;
            this.tempFile = tempFile;
            this.size = size;
            this.expectedMd5 = expectedMd5;
            this.bitmap = bitmap;
            this.totalChunks = totalChunks;
            this.channel = FileChannel.open(tempFile, StandardOpenOption.WRITE, StandardOpenOption.READ);
        }

        Path bitmapPath() {
            return tempFile.resolveSibling(tempFile.getFileName() + ".bitmap");
        }

        void writeChunk(long offset, byte[] data) throws IOException {
            synchronized (writeLock) {
                if (finalizing || closed) {
                    throw new ClosedChannelException();
                }
                channel.write(ByteBuffer.wrap(data), offset);
            }
        }

        synchronized boolean isAcked(int index) {
            return acked.get(index);
        }

        synchronized void markReceived(int index) throws IOException {
            acked.set(index);
            bitmap.markReceived(index);
            updateHighestContiguous();
        }

        synchronized boolean allWritten() {
            return bitmap.allReceived();
        }

        void forceWrites() throws IOException {
            synchronized (writeLock) {
                if (finalizing || closed) {
                    return;
                }
                channel.force(false);
            }
        }

        void closeChannel() throws IOException {
            synchronized (writeLock) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            channel.close();
        }

        void closeQuietly() {
            try {
                closeChannel();
            } catch (IOException e) {
                log.log(Level.FINE, "Failed to close receiver file channel for " + tempFile, e);
            }
        }

        boolean beginFinalize() {
            synchronized (writeLock) {
                if (finalizing || finalized) {
                    return false;
                }
                finalizing = true;
                return true;
            }
        }

        boolean isFinalizing() {
            return finalizing;
        }

        boolean isFinalized() {
            return finalized;
        }

        void markFinalized() {
            synchronized (writeLock) {
                finalized = true;
                finalizing = false;
            }
        }

        List<AckRange> ackRangesFor(int focusIndex, int maxRanges) {
            List<AckRange> ranges = new ArrayList<>();
            synchronized (writeLock) {
                if (highestContiguous >= 0) {
                    ranges.add(new AckRange(0, highestContiguous));
                }
                if (focusIndex > highestContiguous) {
                    int start = expandBackward(focusIndex);
                    int end = expandForward(focusIndex);
                    ranges.add(new AckRange(start, end));
                }
                if (ranges.isEmpty()) {
                    ranges.add(new AckRange(focusIndex, focusIndex));
                }
            }
            if (ranges.size() > maxRanges) {
                ranges = new ArrayList<>(ranges.subList(0, maxRanges));
            }
            return List.copyOf(ranges);
        }

        private int expandBackward(int index) {
            int cursor = index;
            while (cursor - 1 >= 0 && acked.get(cursor - 1)) {
                cursor--;
            }
            return cursor;
        }

        private int expandForward(int index) {
            int cursor = index;
            int max = (int) totalChunks - 1;
            while (cursor + 1 <= max && acked.get(cursor + 1)) {
                cursor++;
            }
            return cursor;
        }

        private void updateHighestContiguous() {
            int max = (int) totalChunks - 1;
            while (highestContiguous + 1 <= max && acked.get(highestContiguous + 1)) {
                highestContiguous++;
            }
        }

    }
}
