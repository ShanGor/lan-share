package io.github.shangor.lan.transfer.core.service;

import io.github.shangor.lan.transfer.core.model.FileChunkBitmap;
import io.github.shangor.lan.transfer.core.model.TransferStatus;
import io.github.shangor.lan.transfer.core.model.TransferTask;
import io.github.shangor.lan.transfer.core.net.QuicMessageUtil;
import io.github.shangor.lan.transfer.core.util.PathUtil;
import io.github.shangor.lan.transfer.core.protocol.ChunkHeader;
import io.github.shangor.lan.transfer.core.protocol.FileChunkMessage;
import io.github.shangor.lan.transfer.core.protocol.FileCompleteMessage;
import io.github.shangor.lan.transfer.core.protocol.FileMetaMessage;
import io.github.shangor.lan.transfer.core.protocol.MetaAckMessage;
import io.github.shangor.lan.transfer.core.protocol.ProtocolMessage;
import io.github.shangor.lan.transfer.core.protocol.TaskCancelMessage;
import io.github.shangor.lan.transfer.core.protocol.TaskCompleteMessage;
import io.github.shangor.lan.transfer.core.protocol.TransferOfferMessage;
import io.github.shangor.lan.transfer.core.protocol.TransferResponseMessage;
import io.github.shangor.lan.transfer.ui.common.TaskTableModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicServerCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.pkitesting.CertificateBuilder;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TransferReceiverService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(TransferReceiverService.class);
    private final TaskRegistry taskRegistry;
    private final TaskTableModel tableModel;
    private final ExecutorService workerPool = new ThreadPoolExecutor(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(8192),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );
    private final Map<Integer, ReceiverContext> contexts = new ConcurrentHashMap<>();
    private MultiThreadIoEventLoopGroup serverGroup;
    private Channel serverChannel;
    private QuicSslContext serverSslContext;
    private Path destinationRoot;

    public TransferReceiverService(TaskRegistry taskRegistry, TaskTableModel tableModel) {
        this.taskRegistry = taskRegistry;
        this.tableModel = tableModel;
    }

    public void start(int port, Path destinationRoot) throws Exception {
        log.info("Starting receiver on port " + port + " with destination: " + destinationRoot);
        this.destinationRoot = destinationRoot;
        CertificateBuilder builder = new CertificateBuilder();

        var certificate = builder.subject("CN=lan-transfer").setIsCertificateAuthority(true)
                .ecp256()
                .notBefore(Instant.now().minusSeconds(86400)) // Allow for clock skew
                .notAfter(Instant.now().plusSeconds(365 * 24L * 3600L))
                .buildSelfSigned() ;
        log.info("SSL certificate created for server");

        serverSslContext = QuicSslContextBuilder.forServer(certificate.toKeyManagerFactory(), null)
                .applicationProtocols("lan-transfer")
                .build();
        log.info("SSL context created for server");
        ChannelInitializer<QuicStreamChannel> streamInitializer = new ChannelInitializer<>() {
            @Override
            protected void initChannel(QuicStreamChannel ch) {
                log.info("Initializing stream channel: {}", ch);
                ch.pipeline().addLast(QuicMessageUtil.newFrameDecoder());
                ch.pipeline().addLast(QuicMessageUtil.newInboundHandler((channel, message) ->
                        handleIncoming((QuicStreamChannel) channel, message)));
                ch.closeFuture().addListener(f -> {
                    log.info("Stream channel closed: {}", ch);
                    cleanupChannel(ch);
                });
            }
        };
        ChannelHandler codec = new QuicServerCodecBuilder()
                .sslContext(serverSslContext)
                .maxIdleTimeout(TimeUnit.HOURS.toMillis(12), TimeUnit.MILLISECONDS)
                .initialMaxData(10_000_000)
                .initialMaxStreamDataBidirectionalLocal(1_000_000)
                .initialMaxStreamDataBidirectionalRemote(1_000_000)
                .initialMaxStreamsBidirectional(100)
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel ch) {
                        log.info("QUIC channel initialized: {}", ch);
                        ch.closeFuture().addListener(f -> {
                            log.info("QUIC channel closed: {}", ch);
                        });
                    }
                })
                .streamHandler(streamInitializer)
                .build();
        serverGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        Bootstrap bootstrap = new Bootstrap();
        log.info("Binding QUIC server to port {}", port);
        serverChannel = bootstrap.group(serverGroup)
                .channel(NioDatagramChannel.class)
                .handler(codec)
                .bind(port)
                .sync()
                .channel();
        log.info("QUIC receiver successfully listening on port {} at {}", port, serverChannel.localAddress());
    }

    private void handleIncoming(QuicStreamChannel channel, ProtocolMessage msg) {
        log.info("Receiver received message: {} on channel {} from remote: {}", msg.type(), channel, channel.remoteAddress());
        try {
            switch (msg.type()) {
                case TRANSFER_OFFER -> {
                    log.info("Processing TRANSFER_OFFER message: {}", msg);
                    TransferOfferMessage offer = (TransferOfferMessage) msg;
                    log.info("Transfer offer details - Folder: {}, Files: {}, Bytes: {}", offer.folderName(), offer.fileCount(), offer.totalBytes());
                    onOffer(channel, offer);
                }
                case FILE_META, FILE_CHUNK -> log.debug("Ignoring data message on control channel");
                case TASK_CANCEL -> onTaskCancel((TaskCancelMessage) msg);
                case CHUNK_ACK -> { /* ignore */ }
                default -> log.debug("Ignoring message {}", msg.type());
            }
        } catch (Exception e) {
            log.warn("Error handling message {}", msg.type(), e);
        }
    }

    private void handleDataMessage(QuicStreamChannel channel, ProtocolMessage msg) {
        log.info("HANDLE_DATA: Received {} message on data channel", msg.type());
        try {
            switch (msg.type()) {
                case FILE_META -> {
                    log.info("DATA CHANNEL: Processing FILE_META for taskId: {}, fileId: {}", ((FileMetaMessage) msg).taskId(), ((FileMetaMessage) msg).fileId());
                    onFileMeta(channel, (FileMetaMessage) msg);
                }
                case FILE_CHUNK -> {
                    // Reduced logging for performance
                    onFileChunk(channel, (FileChunkMessage) msg);
                }
                case META_ACK -> {
                    log.info("DATA CHANNEL: Processing META_ACK for taskId: {}, fileId: {}", ((MetaAckMessage) msg).taskId(), ((MetaAckMessage) msg).fileId());
                    // META_ACK from sender to receiver - nothing to do here
                }
                case TASK_CANCEL -> onTaskCancel((TaskCancelMessage) msg);
                case CHUNK_ACK, TRANSFER_RESPONSE, TRANSFER_OFFER -> {
                    // ignore on data channel
                }
                default -> log.debug("Ignoring data message {}", msg.type());
            }
        } catch (Exception e) {
            log.warn("Error handling data message {}", msg.type(), e);
        }
    }

    private void cleanupChannel(QuicStreamChannel channel) {
        contexts.values().forEach(ctx -> {
            if (ctx.controlChannel == channel) {
                ctx.controlChannel = null;
                if (ctx.dataChannel == null) {
                    tearDownContext(ctx, ctx.task.getStatus() != TransferStatus.COMPLETED);
                } else if (ctx.task.getStatus() != TransferStatus.COMPLETED) {
                    log.info("Control channel closed for active task {}, keeping data channel alive",
                            ctx.task.getProtocolTaskId());
                }
            }
        });
    }

    private void tearDownContext(ReceiverContext ctx, boolean markFailed) {
        if (markFailed && ctx.task.getStatus() != TransferStatus.COMPLETED) {
            ctx.task.setStatus(TransferStatus.FAILED);
        }
        contexts.remove(ctx.task.getProtocolTaskId(), ctx);
        ctx.cleanup();
    }

    private void onOffer(QuicStreamChannel channel, TransferOfferMessage offer) {
        log.info("onOffer called with offer: {}", offer);
        Runnable uiTask = () -> {
            try {
                log.info("Showing accept dialog for offer: {}", offer);
                boolean accept = showAcceptDialog(offer);
                log.info("Dialog result: {}", accept ? "ACCEPTED" : "REJECTED");

                channel.eventLoop().execute(() -> handleOfferDecision(channel, offer, accept));
            } catch (Exception e) {
                log.error("Error in onOffer UI task", e);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            log.info("Already on EDT, running UI task directly");
            uiTask.run();
        } else {
            log.info("Not on EDT, invoking UI task later");
            SwingUtilities.invokeLater(uiTask);
        }
    }

    private void handleOfferDecision(QuicStreamChannel channel, TransferOfferMessage offer, boolean accept) {
        if (!accept) {
            sendUnchecked(channel, new TransferResponseMessage(offer.taskId(), false, 0));
            return;
        }
        TransferTask task = new TransferTask(offer.taskId(), Path.of(offer.folderName()), destinationRoot, offer.totalBytes());
        task.setStatus(TransferStatus.IN_PROGRESS);
        taskRegistry.add(task);
        if (tableModel != null) tableModel.addTask(task);
        ReceiverContext ctx = new ReceiverContext(task, channel, offer.fileCount());
        try {
            int dataPort = ctx.ensureDataServer();
            contexts.put(offer.taskId(), ctx);
            sendUnchecked(channel, new TransferResponseMessage(offer.taskId(), true, dataPort));
        } catch (Exception e) {
            log.error("Failed to start data server for task {}", offer.taskId(), e);
            sendUnchecked(channel, new TransferResponseMessage(offer.taskId(), false, 0));
            ctx.cleanup();
        }
    }

    private boolean showAcceptDialog(TransferOfferMessage offer) {
        log.info("Creating JOptionPane for transfer offer: {}", offer.folderName());
        String message = "Incoming transfer: " + offer.folderName() + "\nFiles: " + offer.fileCount() + "\nTotal bytes: " + offer.totalBytes();
        JOptionPane optionPane = new JOptionPane(message, JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION);
        JDialog dialog = optionPane.createDialog("Incoming Transfer");
        dialog.setAlwaysOnTop(true); // ensure dialog is visible above other windows
        dialog.toFront();
        dialog.setVisible(true);
        dialog.dispose();
        Object selectedValue = optionPane.getValue();
        int choice = selectedValue instanceof Integer ? (Integer) selectedValue : JOptionPane.CLOSED_OPTION;
        log.info("JOptionPane result: {}", choice);
        return choice == JOptionPane.YES_OPTION;
    }

    private void onFileMeta(QuicStreamChannel channel, FileMetaMessage msg) throws IOException {
        log.info("FILE_META: Processing for taskId: " + msg.taskId() + ", fileId: " + msg.fileId() + ", path: " + msg.relativePath() + ", size: " + msg.size());
        ReceiverContext ctx = contexts.get(msg.taskId());
        if (ctx == null) {
            log.warn("FILE_META: No context found for taskId: {}", msg.taskId());
            return;
        }
        if (ctx.dataChannel != channel) {
            log.warn("FILE_META: Ignoring for task {} from unexpected channel.", msg.taskId());
            return;
        }
        // Normalize incoming path separators and convert to platform-specific format
        String normalizedRelativePath = PathUtil.normalizePathSeparators(msg.relativePath());
        String platformPath = PathUtil.toPlatformPath(normalizedRelativePath);
        Path target = destinationRoot.resolve(platformPath).normalize();
        if (!target.startsWith(destinationRoot)) {
            log.warn("Rejected path outside destination: {}", target);
            return;
        }

        // Send META_ACK to acknowledge receipt of metadata
        log.info("Sending META_ACK for taskId: {}, fileId: {}", msg.taskId(), msg.fileId());
        // Use ctx.dataChannel to ensure META_ACK is sent through the correct channel
        sendUnchecked(ctx.dataChannel, new MetaAckMessage(msg.taskId(), msg.fileId()));
        log.info("META_ACK sent successfully through data channel");

        if (msg.entryType() == 'D') {
            Files.createDirectories(target);
            // Update current file path for UI
            ctx.currentFilePath = target.toString();
            sendUnchecked(ctx.dataChannel, new FileCompleteMessage(msg.taskId(), msg.fileId(), true));

            if (ctx.completedFiles.incrementAndGet() >= ctx.expectedFiles) {
                ctx.task.setStatus(TransferStatus.COMPLETED);
                sendUnchecked(ctx.dataChannel, new TaskCompleteMessage(msg.taskId()));
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
        log.info("FILE_META: Successfully processed metadata for fileId: " + msg.fileId());
    }

    private void onFileChunk(QuicStreamChannel channel, FileChunkMessage msg) {
        ReceiverContext ctx = contexts.get(msg.taskId());
        if (ctx == null) {
            return;
        }
        if (ctx.dataChannel != channel) {
            return;
        }
        ReceiverFile rf = ctx.files.get(msg.fileId());
        if (rf == null) {
            return;
        }

        // Update current file path for UI display
        ctx.currentFilePath = rf.target.toString();

        if (ctx.task.getStatus() == TransferStatus.RESENDING) {
            ctx.task.setStatus(TransferStatus.IN_PROGRESS);
        }
        int index = (int) msg.chunkSeq();
        if (rf.isReceived(index)) {
            return;
        }
        if (rf.isFinalizing() || rf.isFinalized()) {
            return;
        }

        workerPool.execute(() -> writeChunk(ctx, msg, rf, msg.body(), index));
    }

    private void writeChunk(ReceiverContext ctx, FileChunkMessage msg, ReceiverFile rf, byte[] decoded, int index) {
        long offset = msg.chunkSeq() * ChunkHeader.MAX_BODY_SIZE;
        try {
            rf.writeChunk(offset, decoded);
            rf.markReceived(index);
            ctx.task.addBytesTransferred(decoded.length);
            ctx.maybeRefreshUI(tableModel);
        } catch (ClosedChannelException e) {
            log.debug("Channel closed while writing chunk {}, ignoring duplicate", msg.chunkSeq());
        } catch (IOException e) {
            log.warn("Write failed for chunk {}", msg.chunkSeq(), e);
            ctx.task.setStatus(TransferStatus.RESENDING);
            return;
        }

        if (rf.allWritten() && rf.beginFinalize()) {
            verifyAndComplete(ctx, msg.taskId(), msg.fileId(), rf);
        }
    }

    private void verifyAndComplete(ReceiverContext ctx, int taskId, int fileId, ReceiverFile rf) {
        try {
            rf.forceWrites();
        } catch (IOException e) {
            log.debug("Failed to flush writes before verification for {}", rf.tempFile, e);
        }
        try {
            if (!Files.exists(rf.tempFile)) {
                if (ctx.dataChannel != null) {
                    sendUnchecked(ctx.dataChannel, new FileCompleteMessage(taskId, fileId, false));
                }
                ctx.task.setStatus(TransferStatus.RESENDING);
                return;
            }
            String md5 = new ChecksumService().md5(rf.tempFile);
            boolean ok = md5.equalsIgnoreCase(rf.expectedMd5);
            if (ctx.dataChannel != null) {
                sendUnchecked(ctx.dataChannel, new FileCompleteMessage(taskId, fileId, ok));
            }
            if (ok) {
                boolean moved = moveFileWithRetry(rf.tempFile, rf.target);
                if (moved) {
                    Files.deleteIfExists(rf.bitmapPath());
                    if (ctx.completedFiles.incrementAndGet() >= ctx.expectedFiles) {
                        ctx.task.setStatus(TransferStatus.COMPLETED);
                        if (ctx.dataChannel != null) {
                            sendUnchecked(ctx.dataChannel, new TaskCompleteMessage(taskId));
                        }
                        if (contexts.remove(taskId, ctx)) {
                            ctx.cleanup();
                        }
                    }
                } else {
                    log.warn("Failed to move file after retries: {} -> {}", rf.tempFile, rf.target);
                    ctx.task.setStatus(TransferStatus.FAILED);
                }
            } else {
                ctx.task.setStatus(TransferStatus.RESENDING);
            }
        } catch (IOException e) {
            log.warn("Failed to verify file", e);
        } finally {
            ctx.files.remove(fileId);
            rf.markFinalized();
            try {
                rf.closeChannel();
            } catch (IOException e) {
                log.debug("Failed to close channel after verification for {}", rf.tempFile, e);
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
            // Reduced logging
            QuicMessageUtil.write(channel, msg);
        } catch (IOException e) {
            log.warn("Failed to send message {}", msg.type(), e);
        }
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
                    log.warn("Failed to move file after {} attempts: {} -> {}", maxRetries, source, target, e);
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

    private final class DataTransferServer implements AutoCloseable {
        private final ReceiverContext context;
        private final MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        private Channel udpChannel;
        private int port;

        DataTransferServer(ReceiverContext context) {
            this.context = context;
        }

        int start() throws Exception {
            log.info("Starting data transfer server...");
            ChannelHandler codec = new QuicServerCodecBuilder()
                    .sslContext(serverSslContext)
                    .maxIdleTimeout(TimeUnit.HOURS.toMillis(12), TimeUnit.MILLISECONDS)
                    .initialMaxData(10_000_000)
                    .initialMaxStreamDataBidirectionalLocal(1_000_000)
                    .initialMaxStreamDataBidirectionalRemote(1_000_000)
                    .initialMaxStreamsBidirectional(4)
                    .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                    .handler(new ChannelInitializer<QuicChannel>() {
                        @Override
                        protected void initChannel(QuicChannel ch) {
                            log.info("Data server QUIC channel initialized: " + ch + " from remote: " + ch.remoteAddress());
                        }
                    })
                    .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                        @Override
                        protected void initChannel(QuicStreamChannel ch) {
                            log.info("DATA SERVER: Stream channel created: " + ch + " from remote: " + ch.remoteAddress());
                            context.dataChannel = ch;
                            ch.pipeline().addLast(QuicMessageUtil.newFrameDecoder());
                            ch.pipeline().addLast(QuicMessageUtil.newInboundHandler((channel, message) ->
                                    handleDataMessage((QuicStreamChannel) channel, message)));
                            ch.closeFuture().addListener(f -> {
                                log.info("DATA SERVER: Stream channel closed: " + ch);
                                context.dataChannel = null;
                            });
                        }
                    })
                    .build();
            udpChannel = new Bootstrap().group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(codec)
                    .bind(0)
                    .sync()
                    .channel();
            port = ((InetSocketAddress) udpChannel.localAddress()).getPort();
            return port;
        }

        @Override
        public void close() {
            if (udpChannel != null) {
                udpChannel.close();
            }
            group.shutdownGracefully();
        }

        int port() {
            return port;
        }
    }

    private class ReceiverContext {
        final TransferTask task;
        final Map<Integer, ReceiverFile> files = new ConcurrentHashMap<>();
        final int expectedFiles;
        AtomicInteger completedFiles = new AtomicInteger( 0);
        volatile long lastUiUpdateNanos = System.nanoTime();
        volatile String currentFilePath = "";
        volatile QuicStreamChannel controlChannel;
        volatile QuicStreamChannel dataChannel;
        DataTransferServer dataServer;

        ReceiverContext(TransferTask task, QuicStreamChannel controlChannel, int expectedFiles) {
            this.task = task;
            this.controlChannel = controlChannel;
            this.expectedFiles = expectedFiles;
        }

        int ensureDataServer() throws Exception {
            if (dataServer == null) {
                log.info("Creating data transfer server for task {}", task.getProtocolTaskId());
                dataServer = new DataTransferServer(this);
                dataServer.start();
                log.info("Data transfer server started on port {}", dataServer.port());
            } else {
                log.info("Data transfer server already running on port {}", dataServer.port());
            }
            return dataServer.port();
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
            if (controlChannel != null) {
                controlChannel.close();
            }
            if (dataChannel != null) {
                dataChannel.close();
            }
            if (dataServer != null) {
                dataServer.close();
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
        final FileChannel channel;
        volatile boolean closed = false;
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

        synchronized void markReceived(int index) throws IOException {
            bitmap.markReceived(index);
        }

        synchronized boolean isReceived(int index) {
            return bitmap.isReceived(index);
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
                log.debug("Failed to close receiver file channel for {}", tempFile, e);
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
    }
}
