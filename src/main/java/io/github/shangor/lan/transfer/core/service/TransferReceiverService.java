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
import io.github.shangor.lan.transfer.core.protocol.TaskPauseMessage;
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
            new LinkedBlockingQueue<>(ChunkHeader.MAX_WINDOW_SIZE),
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
        log.debug("Receiver received message: {} on channel {} from remote: {}", msg.type(), channel, channel.remoteAddress());
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
                case TASK_PAUSE -> onTaskPauseMessage((TaskPauseMessage) msg);
                case CHUNK_ACK -> { /* ignore */ }
                default -> log.debug("Ignoring message {}", msg.type());
            }
        } catch (Exception e) {
            log.warn("Error handling message {}", msg.type(), e);
        }
    }

    private void handleDataMessage(QuicStreamChannel channel, ProtocolMessage msg) {
        log.debug("HANDLE_DATA: Received {} message on data channel", msg.type());
        try {
            switch (msg.type()) {
                case FILE_META -> handleFileMetaMessage(channel, (FileMetaMessage) msg);
                case FILE_CHUNK -> {
                    // Reduced logging for performance
                    onFileChunk(channel, (FileChunkMessage) msg);
                }
                case META_ACK -> {
                    log.info("DATA CHANNEL: Processing META_ACK for taskId: {}, fileId: {}", ((MetaAckMessage) msg).taskId(), ((MetaAckMessage) msg).fileId());
                    // META_ACK from sender to receiver - nothing to do here
                }
                case TASK_CANCEL -> onTaskCancel((TaskCancelMessage) msg);
                case TASK_PAUSE -> onTaskPauseMessage((TaskPauseMessage) msg);
                case CHUNK_ACK, TRANSFER_RESPONSE, TRANSFER_OFFER -> {
                    // ignore on data channel
                }
                default -> log.debug("Ignoring data message {}", msg.type());
            }
        } catch (Exception e) {
            log.warn("Error handling data message {}", msg.type(), e);
        }
    }

    private void handleFileMetaMessage(QuicStreamChannel channel, FileMetaMessage msg) {
        ReceiverContext ctx = contexts.get(msg.taskId());
        if (ctx == null) {
            log.warn("FILE_META: No context found for taskId: {}", msg.taskId());
            return;
        }
        if (ctx.dataChannel != channel) {
            log.warn("FILE_META: Ignoring for task {} from unexpected channel.", msg.taskId());
            return;
        }
        handleFileMetaAsync(ctx, msg);
    }

    private void handleFileMetaAsync(ReceiverContext ctx, FileMetaMessage msg) {
        workerPool.execute(() -> {
            try {
                log.info("DATA CHANNEL: Processing FILE_META for taskId: {}, fileId: {}", msg.taskId(), msg.fileId());
                onFileMeta(ctx, msg);
            } catch (IOException e) {
                log.warn("Failed to process FILE_META for taskId {} fileId {}", msg.taskId(), msg.fileId(), e);
            }
        });
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

    private void onFileMeta(ReceiverContext ctx, FileMetaMessage msg) throws IOException {
        log.debug("FILE_META: Processing for taskId: " + msg.taskId() + ", fileId: " + msg.fileId() + ", path: " + msg.relativePath() + ", size: " + msg.size());
        String normalizedRelativePath = PathUtil.normalizePathSeparators(msg.relativePath());
        String platformPath = PathUtil.toPlatformPath(normalizedRelativePath);
        Path target = destinationRoot.resolve(platformPath).normalize();
        if (!target.startsWith(destinationRoot)) {
            log.warn("Rejected path outside destination: {}", target);
            return;
        }

        if (msg.entryType() == 'D') {
            Files.createDirectories(target);
            ctx.currentFilePath = target.toString();
            sendMetaAckAsync(ctx, msg.taskId(), msg.fileId(), 0L, false);
            sendUnchecked(ctx.dataChannel, new FileCompleteMessage(msg.taskId(), msg.fileId(), true));
            ctx.resumeCredits.remove(msg.fileId());
            if (ctx.completedFiles.incrementAndGet() >= ctx.expectedFiles) {
                ctx.task.setStatus(TransferStatus.COMPLETED);
                sendUnchecked(ctx.dataChannel, new TaskCompleteMessage(msg.taskId()));
                if (contexts.remove(msg.taskId(), ctx)) {
                    ctx.cleanup();
                }
            }
            return;
        }

        Files.createDirectories(target.getParent());
        Path tempFile = target.resolveSibling(target.getFileName() + ".part");
        Path bitmapPath = tempFile.resolveSibling(tempFile.getFileName() + ".bitmap");
        long chunkCount = (msg.size() + ChunkHeader.MAX_BODY_SIZE - 1) / ChunkHeader.MAX_BODY_SIZE;

        ResumeDecision decision = prepareResumeDecision(target, tempFile, bitmapPath, msg, chunkCount);
        sendMetaAckAsync(ctx, msg.taskId(), msg.fileId(), decision.resumeFromSeq(), decision.alreadyComplete());
        applyResumeCredit(ctx, msg.fileId(), msg.size(), decision.resumeFromSeq(), decision.alreadyComplete());
        ctx.currentFilePath = target.toString();

        if (decision.alreadyComplete()) {
            sendUnchecked(ctx.dataChannel, new FileCompleteMessage(msg.taskId(), msg.fileId(), true));
            ctx.resumeCredits.remove(msg.fileId());
            if (ctx.completedFiles.incrementAndGet() >= ctx.expectedFiles) {
                ctx.task.setStatus(TransferStatus.COMPLETED);
                sendUnchecked(ctx.dataChannel, new TaskCompleteMessage(msg.taskId()));
                if (contexts.remove(msg.taskId(), ctx)) {
                    ctx.cleanup();
                }
            }
            return;
        }

        ReceiverFile rf = decision.receiverFile();
        ctx.files.put(msg.fileId(), rf);
        log.debug("FILE_META: Prepared receiver state for fileId: {} resumeFromSeq: {}", msg.fileId(), decision.resumeFromSeq());
    }

    private ResumeDecision prepareResumeDecision(Path target, Path tempFile, Path bitmapPath, FileMetaMessage msg,
                                                 long chunkCount) throws IOException {
        if (Files.exists(target)) {
            String md5 = new ChecksumService().md5(target);
            if (md5.equalsIgnoreCase(msg.md5())) {
                log.info("FILE_META: Target already exists with matching checksum for {}", target);
                return new ResumeDecision(chunkCount, true, null);
            }
            log.info("FILE_META: Existing target {} checksum mismatch, overwriting", target);
            Files.delete(target);
        }

        boolean partialExists = Files.exists(tempFile) && Files.exists(bitmapPath);
        if (!partialExists) {
            Files.deleteIfExists(tempFile);
            Files.deleteIfExists(bitmapPath);
            Files.createFile(tempFile);
            FileChunkBitmap bitmap = new FileChunkBitmap(bitmapPath, (int) chunkCount);
            ReceiverFile rf = new ReceiverFile(target, tempFile, msg.size(), msg.md5(), bitmap, chunkCount);
            return new ResumeDecision(0L, false, rf);
        }

        FileChunkBitmap bitmap = new FileChunkBitmap(bitmapPath, (int) chunkCount);
        if (bitmap.allReceived()) {
            log.info("FILE_META: Partial file {} already contains all chunks; verifying", tempFile);
            if (finalizeExistingTemp(tempFile, target, bitmapPath, msg.md5())) {
                return new ResumeDecision(chunkCount, true, null);
            }
            log.info("FILE_META: Existing temp data invalid, restarting transfer for {}", target);
            Files.deleteIfExists(tempFile);
            Files.deleteIfExists(bitmapPath);
            Files.createFile(tempFile);
            FileChunkBitmap fresh = new FileChunkBitmap(bitmapPath, (int) chunkCount);
            ReceiverFile rf = new ReceiverFile(target, tempFile, msg.size(), msg.md5(), fresh, chunkCount);
            return new ResumeDecision(0L, false, rf);
        }

        long resumeFrom = bitmap.firstMissingChunk();
        resumeFrom = Math.min(resumeFrom, chunkCount);
        log.info("FILE_META: Resuming {} from chunk {}", target, resumeFrom);
        ReceiverFile rf = new ReceiverFile(target, tempFile, msg.size(), msg.md5(), bitmap, chunkCount);
        return new ResumeDecision(resumeFrom, false, rf);
    }

    private void applyResumeCredit(ReceiverContext ctx, int fileId, long fileSize, long resumeSeq,
                                   boolean alreadyComplete) {
        long creditedBytes = alreadyComplete ? fileSize
                : Math.min(fileSize, resumeSeq * (long) ChunkHeader.MAX_BODY_SIZE);
        Long previous = ctx.resumeCredits.put(fileId, creditedBytes);
        long delta = creditedBytes - (previous == null ? 0 : previous);
        if (delta > 0) {
            ctx.task.addBytesTransferred(delta);
            ctx.maybeRefreshUI(tableModel);
        }
    }

    private boolean finalizeExistingTemp(Path tempFile, Path target, Path bitmapPath, String expectedMd5) {
        try {
            String md5 = new ChecksumService().md5(tempFile);
            if (!md5.equalsIgnoreCase(expectedMd5)) {
                log.warn("FILE_META: Temp file {} checksum mismatch; expected {} got {}", tempFile, expectedMd5, md5);
                return false;
            }
            if (!moveFileWithRetry(tempFile, target)) {
                log.warn("FILE_META: Failed to promote temp file {} to {}", tempFile, target);
                return false;
            }
            Files.deleteIfExists(bitmapPath);
            return true;
        } catch (IOException e) {
            log.warn("FILE_META: Failed to finalize existing temp file {}", tempFile, e);
            return false;
        }
    }

    private record ResumeDecision(long resumeFromSeq, boolean alreadyComplete, ReceiverFile receiverFile) {
    }

    private void onFileChunk(QuicStreamChannel channel, FileChunkMessage msg) {
        ReceiverContext ctx = contexts.get(msg.taskId());
        if (ctx == null) {
            return;
        }
        if (ctx.dataChannel != channel) {
            return;
        }
        if (ctx.paused) {
            log.debug("Task {} paused; ignoring chunk {}", ctx.task.getProtocolTaskId(), msg.chunkSeq());
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
            ctx.resumeCredits.remove(fileId);
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

    public void pauseTask(String taskId) {
        ReceiverContext ctx = findContext(taskId);
        if (ctx == null) {
            return;
        }
        ctx.setPaused(true);
        ctx.task.setStatus(TransferStatus.PAUSED);
        sendPauseMessage(ctx, true);
    }

    public void resumeTask(String taskId) {
        ReceiverContext ctx = findContext(taskId);
        if (ctx == null) {
            return;
        }
        ctx.setPaused(false);
        if (ctx.task.getStatus() == TransferStatus.PAUSED) {
            ctx.task.setStatus(TransferStatus.IN_PROGRESS);
        }
        sendPauseMessage(ctx, false);
    }

    private void onTaskPauseMessage(TaskPauseMessage msg) {
        ReceiverContext ctx = contexts.get(msg.taskId());
        if (ctx == null) {
            return;
        }
        ctx.setPaused(msg.pause());
        if (msg.pause()) {
            ctx.task.setStatus(TransferStatus.PAUSED);
        } else if (ctx.task.getStatus() == TransferStatus.PAUSED) {
            ctx.task.setStatus(TransferStatus.IN_PROGRESS);
        }
    }

    private ReceiverContext findContext(String paddedTaskId) {
        try {
            int id = Integer.parseInt(paddedTaskId);
            return contexts.get(id);
        } catch (NumberFormatException e) {
            log.warn("Invalid task id for pause/resume: {}", paddedTaskId);
            return null;
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

    private void sendPauseMessage(ReceiverContext ctx, boolean pause) {
        Channel channel = ctx.controlChannel != null ? ctx.controlChannel : ctx.dataChannel;
        if (channel == null) {
            return;
        }
        sendUnchecked(channel, new TaskPauseMessage(ctx.task.getProtocolTaskId(), pause));
    }

    private void sendMetaAckAsync(ReceiverContext ctx, int taskId, int fileId, long resumeFromSeq, boolean alreadyComplete) {
        QuicStreamChannel dataChannel = ctx.dataChannel;
        if (dataChannel == null) {
            log.debug("META_ACK: Skipping send for taskId {} fileId {} because data channel is null", taskId, fileId);
            return;
        }
        Runnable ackTask = () -> {
            log.debug("Sending META_ACK for taskId: {}, fileId: {} resumeFrom: {} complete: {}", taskId, fileId,
                    resumeFromSeq, alreadyComplete);
            sendUnchecked(dataChannel, new MetaAckMessage(taskId, fileId, resumeFromSeq, alreadyComplete));
        };
        if (dataChannel.eventLoop().inEventLoop()) {
            ackTask.run();
        } else {
            dataChannel.eventLoop().execute(ackTask);
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
                            log.debug("DATA SERVER: Stream channel created: " + ch + " from remote: " + ch.remoteAddress());
                            context.dataChannel = ch;
                            ch.pipeline().addLast(QuicMessageUtil.newFrameDecoder());
                            ch.pipeline().addLast(QuicMessageUtil.newInboundHandler((channel, message) ->
                                    handleDataMessage((QuicStreamChannel) channel, message)));
                            ch.closeFuture().addListener(f -> {
                                log.debug("DATA SERVER: Stream channel closed: " + ch);
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
        volatile boolean paused = false;
        final Map<Integer, Long> resumeCredits = new ConcurrentHashMap<>();

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
            resumeCredits.clear();
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

        void setPaused(boolean value) {
            paused = value;
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
