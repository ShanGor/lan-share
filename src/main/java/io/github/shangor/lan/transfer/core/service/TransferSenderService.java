package io.github.shangor.lan.transfer.core.service;

import io.github.shangor.lan.transfer.core.model.TransferStatus;
import io.github.shangor.lan.transfer.core.model.TransferTask;
import io.github.shangor.lan.transfer.core.net.QuicMessageUtil;
import io.github.shangor.lan.transfer.core.protocol.ProtocolIO;
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
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicChannelBootstrap;
import io.netty.handler.codec.quic.QuicClientCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.Future;
import javax.swing.SwingUtilities;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
public class TransferSenderService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(TransferSenderService.class);

    private final TaskRegistry taskRegistry;
    private final TaskTableModel tableModel;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService sendExecutor = Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors()));
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Map<Integer, SenderContext> contexts = new ConcurrentHashMap<>();
    private final MultiThreadIoEventLoopGroup quicGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    private final QuicSslContext clientSslContext;

    public TransferSenderService(TaskRegistry taskRegistry) {
        this(taskRegistry, null);
    }

    public TransferSenderService(TaskRegistry taskRegistry, TaskTableModel tableModel) {
        this.taskRegistry = taskRegistry;
        this.tableModel = tableModel;
        this.clientSslContext = createClientSslContext();
    }

    private static QuicSslContext createClientSslContext() {
        return QuicSslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocols("lan-transfer")
                .build();
    }

    public void start(int port) {
        log.info("TransferSenderService using QUIC client; no local port binding required.");
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
            ensureControlChannel(ctx);
            sendControl(ctx, new TransferOfferMessage(taskId, folder.getFileName().toString(), totalBytes, files.size() + dirs.size(), 0, 'D'));
        } catch (Exception e) {
            log.error("Send failed", e);
            if (ctx != null) {
                ctx.task.setStatus(TransferStatus.FAILED);
                contexts.remove(taskId);
                ctx.cleanup();
            } else {
                TaskIdGenerator.release(taskId);
            }
        }
    }

    private void ensureControlChannel(SenderContext ctx) throws Exception {
        if (ctx.controlStreamChannel != null && ctx.controlStreamChannel.isActive()) {
            log.info("Control channel already active for task {}", ctx.taskId);
            return;
        }
        log.info("Establishing control channel to receiver: {}", ctx.receiver);
        Bootstrap bootstrap = new Bootstrap();
        Channel udpChannel = bootstrap.group(quicGroup)
                .channel(NioDatagramChannel.class)
                .handler(new QuicClientCodecBuilder()
                        .sslContext(clientSslContext)
                        .maxIdleTimeout(TimeUnit.HOURS.toMillis(12), TimeUnit.MILLISECONDS)
                        .initialMaxData(10_000_000)
                        .initialMaxStreamDataBidirectionalLocal(1_000_000)
                        .build())
                .bind(0)
                .sync()
                .channel();
        log.info("UDP channel bound to: {}", udpChannel.localAddress());

        QuicChannelBootstrap quicBootstrap = QuicChannel.newBootstrap(udpChannel)
                .remoteAddress(ctx.receiver)
                .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                    @Override
                    protected void initChannel(QuicStreamChannel ch) {
                        // no-op; we explicitly create streams
                    }
                });
        log.info("Attempting QUIC connection to: {}", ctx.receiver);
        try {
            QuicChannel quicChannel = quicBootstrap.connect().get(15, TimeUnit.SECONDS);
            log.info("QUIC connection established successfully");
            Future<QuicStreamChannel> streamFuture = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
                new ChannelInitializer<QuicStreamChannel>() {
                    @Override
                    protected void initChannel(QuicStreamChannel ch) {
                        ch.pipeline().addLast(QuicMessageUtil.newFrameDecoder());
                        ch.pipeline().addLast(QuicMessageUtil.newInboundHandler((channel, message) ->
                                handleControlMessage(ctx, message)));
                    }
                });
            streamFuture.sync();
            QuicStreamChannel streamChannel = streamFuture.getNow();
            ctx.controlUdpChannel = udpChannel;
            ctx.controlQuicChannel = quicChannel;
            ctx.controlStreamChannel = streamChannel;
            log.info("Control stream created successfully for task {}", ctx.taskId);
            streamChannel.closeFuture().addListener(f -> {
                log.info("Control stream closed for task {}", ctx.taskId);
                if (f.cause() != null) {
                    log.warn("Control stream closed with error", f.cause());
                }
                contexts.remove(ctx.taskId);
                ctx.cleanup();
            });
        } catch (Exception e) {
            log.error("Failed to establish QUIC connection to {}", ctx.receiver, e);
            throw e;
        }
    }

    private void connectDataChannel(SenderContext ctx, int dataPort) throws Exception {
        if (ctx.dataStreamChannel != null && ctx.dataStreamChannel.isActive()) {
            log.info("Data channel already active for task " + ctx.taskId);
            return;
        }
        InetSocketAddress dataAddress = new InetSocketAddress(ctx.receiver.getHostString(), dataPort);
        log.info("Connecting data channel to: " + dataAddress);
        Bootstrap bootstrap = new Bootstrap();
        Channel udpChannel = bootstrap.group(quicGroup)
                .channel(NioDatagramChannel.class)
                .handler(new QuicClientCodecBuilder()
                        .sslContext(clientSslContext)
                        .maxIdleTimeout(TimeUnit.HOURS.toMillis(12), TimeUnit.MILLISECONDS)
                        .initialMaxData(10_000_000)
                        .initialMaxStreamDataBidirectionalLocal(1_000_000)
                        .initialMaxStreamDataBidirectionalRemote(1_000_000)
                        .initialMaxStreamsBidirectional(100)
                        .build())
                .bind(0)
                .sync()
                .channel();

        QuicChannelBootstrap quicBootstrap = QuicChannel.newBootstrap(udpChannel)
                .remoteAddress(dataAddress)
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel ch) {
                        log.info("Data QUIC channel initialized: " + ch);
                    }
                });
        log.info("Attempting QUIC data connection to: " + dataAddress);
        QuicChannel quicChannel = quicBootstrap.connect().get(15, TimeUnit.SECONDS);
        log.info("QUIC data connection established successfully");
        Future<QuicStreamChannel> streamFuture = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
                new ChannelInitializer<QuicStreamChannel>() {
                    @Override
                    protected void initChannel(QuicStreamChannel ch) {
                        log.info("Data stream created: " + ch + " for taskId: " + ctx.taskId);
                        ch.pipeline().addLast(QuicMessageUtil.newFrameDecoder());
                        ch.pipeline().addLast(QuicMessageUtil.newInboundHandler((channel, message) -> {
                            log.info("Data channel received message: " + message.type() + " for taskId: " + ctx.taskId);
                            handleDataMessage(ctx, message);
                        }));
                        log.info("Data stream pipeline configured for taskId: " + ctx.taskId);
                    }
                });
        streamFuture.sync();
        QuicStreamChannel streamChannel = streamFuture.getNow();
        log.info("Data stream created successfully for task " + ctx.taskId);
        ctx.dataUdpChannel = udpChannel;
        ctx.dataQuicChannel = quicChannel;
        ctx.dataStreamChannel = streamChannel;
        streamChannel.closeFuture().addListener(f -> ctx.cleanup());
    }

    private void sendEntries(SenderContext ctx) throws IOException {
        int id = 0;

        // Send directory metadata
        for (Path dir : ctx.directories) {
            String rel = ctx.root.relativize(dir).toString();
            ctx.entries.put(id, new SenderEntry(rel, dir, true, 0));
            sendFileMetaWithRetry(ctx, ctx.taskId, id, 'D', rel, 0L, "");
            id++;
        }

        // Send file metadata
        for (Path file : ctx.files) {
            long size = Files.size(file);
            long totalChunks = (size + ChunkHeader.MAX_BODY_SIZE - 1) / ChunkHeader.MAX_BODY_SIZE;
            String rel = ctx.root.relativize(file).toString();
            String md5 = new ChecksumService().md5(file);
            ctx.entries.put(id, new SenderEntry(rel, file, false, totalChunks));
            sendFileMetaWithRetry(ctx, ctx.taskId, id, 'F', rel, size, md5);
            id++;
        }
    }

    private void sendFileMetaWithRetry(SenderContext ctx, int taskId, int fileId, char entryType, String relPath, long size, String md5) throws IOException {
        FileMetaMessage msg = new FileMetaMessage(taskId, fileId, entryType, relPath, size, md5);
        // Update current file path for UI
        SenderEntry entry = ctx.entries.get(fileId);
        if (entry != null) {
            ctx.currentFilePath = entry.path().toString();
            ctx.maybeRefreshUI(tableModel);
        }

        // Check data channel state before sending
        log.info("FILE_META: Preparing to send for taskId: " + taskId + ", fileId: " + fileId +
                 ", path: " + relPath + ", dataChannel: " + ctx.dataStreamChannel +
                 ", dataChannel active: " + (ctx.dataStreamChannel != null && ctx.dataStreamChannel.isActive()) +
                 ", acknowledged: " + Boolean.TRUE.equals(ctx.fileMetaAcked.get(fileId)));

        sendData(ctx, msg);

        // Track that FILE_META was sent and schedule retry if no ACK received
        ScheduledFuture<?> retryFuture = scheduler.scheduleWithFixedDelay(() -> {
            if (Boolean.TRUE.equals(ctx.fileMetaAcked.get(fileId))) {
                // If acknowledged, cancel this retry task
                log.info("FILE_META for fileId " + fileId + " was acknowledged, cancelling retry");
                ScheduledFuture<?> future = ctx.chunkTimeouts.remove(-fileId);
                if (future != null) {
                    future.cancel(false);
                }
                return;
            }
            // Still not acknowledged, retry sending FILE_META
            log.info("FILE_META for fileId " + fileId + " not acknowledged yet, retrying... Data channel active: " +
                    (ctx.dataStreamChannel != null && ctx.dataStreamChannel.isActive()));
            try {
                // Update current file path for UI during retries
                SenderEntry retryEntry = ctx.entries.get(fileId);
                if (retryEntry != null) {
                    ctx.currentFilePath = retryEntry.path().toString();
                    ctx.maybeRefreshUI(tableModel);
                }
                sendData(ctx, msg);
            } catch (IOException e) {
                log.warn("Failed to resend FILE_META for fileId {}", fileId, e);
            }
        }, 1000, 2000, TimeUnit.MILLISECONDS); // Retry after 1s, then every 2s

        // Store the retry task so we can cancel it when ACK is received
        ctx.chunkTimeouts.put(-fileId, retryFuture);
    }

    private void handleDataMessage(SenderContext ctx, ProtocolMessage msg) {
        log.info("HANDLE_DATA: Received " + msg.type() + " message for taskId: " + ctx.taskId);
        switch (msg.type()) {
            case CHUNK_ACK -> { /* Ignore, we rely on QUIC reliability */ }
            case FILE_COMPLETE -> onFileComplete((FileCompleteMessage) msg);
            case META_ACK -> onMetaAck(ctx, (MetaAckMessage) msg);
            case TASK_COMPLETE -> onTaskComplete((TaskCompleteMessage) msg);
            default -> log.warn("Unknown data message type: {}", msg.type());
        }
    }

    private void handleControlMessage(SenderContext ctx, ProtocolMessage msg) {
        switch (msg.type()) {
            case TRANSFER_RESPONSE -> onTransferResponse(ctx, (TransferResponseMessage) msg);
            case TASK_CANCEL -> {
                ctx.task.setStatus(TransferStatus.CANCELED);
                contexts.remove(ctx.taskId);
                ctx.cleanup();
            }
            default -> log.debug("Ignoring control message " + msg.type());
        }
    }

    private void onTransferResponse(SenderContext ctx, TransferResponseMessage msg) {
        log.info("Received TRANSFER_RESPONSE for task " + ctx.taskId + ", accepted: " + msg.accepted() + ", data port: " + msg.dataPort());
        if (!msg.accepted()) {
            ctx.task.setStatus(TransferStatus.REJECTED);
            contexts.remove(ctx.taskId);
            ctx.cleanup();
            return;
        }
        if (ctx.entriesStarted) {
            return;
        }
        ctx.entriesStarted = true;
        executor.submit(() -> {
            try {
                log.info("Connecting to data port " + msg.dataPort() + " for task " + ctx.taskId);
                connectDataChannel(ctx, msg.dataPort());
                ctx.task.setStatus(TransferStatus.IN_PROGRESS);
                sendEntries(ctx);
            } catch (Exception e) {
                log.error("Failed to send entries for task {}", ctx.taskId, e);
                ctx.task.setStatus(TransferStatus.FAILED);
                contexts.remove(ctx.taskId);
                ctx.cleanup();
            }
        });
    }

    private void onMetaAck(SenderContext ctx, MetaAckMessage msg) {
        int fileId = msg.fileId();
        log.info("META_ACK: Received acknowledgment for fileId: " + fileId + " for taskId: " + ctx.taskId);
        Boolean acked = ctx.fileMetaAcked.put(fileId, true);
        if (Boolean.TRUE.equals(acked)) {
            log.info("META_ACK: FileId " + fileId + " already acknowledged for taskId: " + ctx.taskId);
            return; // Already acknowledged
        }
        ScheduledFuture<?> future = ctx.chunkTimeouts.remove(-fileId);
        if (future != null) {
            future.cancel(false);
            log.info("META_ACK: Cancelled retry timeout for fileId: " + fileId + " for taskId: " + ctx.taskId);
        }
        SenderEntry entry = ctx.entries.get(fileId);
        if (entry != null) {
            if (entry.isDirectory()) {
                log.info("META_ACK: Directory confirmed, skipping streaming for fileId: {} for taskId: {}", fileId, ctx.taskId);
            } else {
                log.info("META_ACK: Starting file streaming for fileId: " + fileId + " for taskId: " + ctx.taskId);
                startFileStreaming(ctx, fileId, entry);
            }
        } else {
            log.warn("META_ACK: No entry found for fileId: {} for taskId: {}", fileId, ctx.taskId);
        }
    }

    private void startFileStreaming(SenderContext ctx, int fileId, SenderEntry entry) {
        SenderFileState state;
        try {
            state = ctx.ensureFileState(fileId, entry);
        } catch (IOException e) {
            log.error("Failed to open file channel for streaming: {}", entry.path(), e);
            ctx.task.setStatus(TransferStatus.FAILED);
            return;
        }

        ctx.currentFilePath = entry.path().toString();
        ctx.maybeRefreshUI(tableModel);

        // Stream the file asynchronously
        sendExecutor.submit(() -> streamFile(ctx, state));
    }

    private void streamFile(SenderContext ctx, SenderFileState state) {
        try {
            log.info("Starting streaming for fileId: " + state.fileId);
            long seq = 0;
            while (seq < state.totalChunks) {
                if (state.closed) return;

                // Flow control: wait if channel is not writable
                while (ctx.dataStreamChannel != null && !ctx.dataStreamChannel.isWritable()) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                byte[] chunk = readChunk(state.channel, seq);
                if (chunk.length == 0) break;

                byte xorKey = (byte) (System.nanoTime() & 0xFF);
                byte[] body = xor(chunk, xorKey);

                sendData(ctx, new FileChunkMessage(ctx.taskId, state.fileId, seq, xorKey, body));
                ctx.task.addBytesTransferred(body.length);
                ctx.maybeRefreshUI(tableModel);

                seq++;
            }
            log.info("Finished streaming for fileId: " + state.fileId);
        } catch (IOException e) {
            log.warn("Failed to stream file {}", state.path, e);
            ctx.task.setStatus(TransferStatus.FAILED);
            failContext(ctx);
        }
    }

    private void failContext(SenderContext ctx) {
        contexts.remove(ctx.taskId);
        ctx.cleanup();
    }

    private void onFileComplete(FileCompleteMessage msg) {
        SenderContext ctx = contexts.get(msg.taskId());
        if (ctx == null) return;
        if (!msg.success()) {
            ctx.task.setStatus(TransferStatus.RESENDING);
        }
        ctx.cleanupFileState(msg.fileId());
        ctx.completedFiles.add(msg.fileId());
        if (ctx.completedFiles.size() == ctx.entries.size()) {
            ctx.task.setStatus(TransferStatus.COMPLETED);
        }
    }

    private void onTaskComplete(TaskCompleteMessage msg) {
        SenderContext ctx = contexts.remove(msg.taskId());
        if (ctx != null) {
            ctx.task.setStatus(TransferStatus.COMPLETED);
            ctx.cleanup();
        }
    }

    public void cancelTask(int taskId) {
        SenderContext ctx = contexts.remove(taskId);
        if (ctx != null) {
            try {
                sendControl(ctx, new TaskCancelMessage(taskId));
                ctx.task.setStatus(TransferStatus.CANCELED);
            } catch (IOException e) {
                log.warn("Failed to send task cancel message", e);
                ctx.task.setStatus(TransferStatus.CANCELED);
            }
            ctx.cleanup();
        }
    }

    private byte[] readChunk(FileChannel channel, long seq) throws IOException {
        long offset = seq * (long) ChunkHeader.MAX_BODY_SIZE;
        ByteBuffer buffer = ByteBuffer.allocate(ChunkHeader.MAX_BODY_SIZE);
        int read = channel.read(buffer, offset);
        if (read <= 0) {
            return new byte[0];
        }
        byte[] out = new byte[read];
        buffer.flip();
        buffer.get(out);
        return out;
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

    private void sendControl(SenderContext ctx, ProtocolMessage msg) throws IOException {
        writeToChannel(ctx, ctx.controlStreamChannel, "control", msg);
    }

    private void sendData(SenderContext ctx, ProtocolMessage msg) throws IOException {
        // Reduced logging for performance
        writeToChannel(ctx, ctx.dataStreamChannel, "data", msg);
    }

    private void writeToChannel(SenderContext ctx, QuicStreamChannel channel, String channelType, ProtocolMessage msg) throws IOException {
        if (channel == null || !channel.isActive()) {
            log.warn("WRITE_CHANNEL: Channel not active for {} channel, taskId: {}", channelType, ctx.taskId);
            throw new IOException("Channel not active for " + channelType + " channel");
        }
        // Reduced logging for performance
        byte[] data = ProtocolIO.toByteArray(msg);
        // ctx.task.addBytesTransferred(data.length); // Removed to avoid double counting and protocol overhead
        QuicMessageUtil.write(channel, msg);
    }

    @Override
    public void close() {
        contexts.values().forEach(SenderContext::cleanup);
        contexts.clear();
        executor.shutdownNow();
        sendExecutor.shutdownNow();
        scheduler.shutdownNow();
        quicGroup.shutdownGracefully();
    }

    private static class SenderContext {
        final int taskId;
        final TransferTask task;
        final InetSocketAddress receiver;
        final List<Path> files;
        final List<Path> directories;
        final Path root;
        final Map<Integer, SenderEntry> entries = new ConcurrentHashMap<>();
        final Map<Integer, ScheduledFuture<?>> chunkTimeouts = new ConcurrentHashMap<>();
        final List<Integer> completedFiles = new ArrayList<>();
        final Map<Integer, Boolean> fileMetaAcked = new ConcurrentHashMap<>(); // Track if FILE_META is acknowledged
        final Map<Integer, SenderFileState> fileStates = new ConcurrentHashMap<>();
        volatile String currentFilePath = "";
        volatile Channel controlUdpChannel;
        volatile QuicChannel controlQuicChannel;
        volatile QuicStreamChannel controlStreamChannel;
        volatile Channel dataUdpChannel;
        volatile QuicChannel dataQuicChannel;
        volatile QuicStreamChannel dataStreamChannel;
        volatile boolean entriesStarted = false;
        volatile boolean taskIdReleased = false;

        volatile long lastUiUpdateNanos = System.nanoTime();
        volatile long lastTelemetryLogNanos = System.nanoTime();
        volatile long lastTelemetryBytes = 0;

        SenderContext(int taskId, TransferTask task, InetSocketAddress receiver, List<Path> files, List<Path> directories, Path root) {
            this.taskId = taskId;
            this.task = task;
            this.receiver = receiver;
            this.files = files;
            this.directories = directories;
            this.root = root;
        }

        void maybeRefreshUI(TaskTableModel model) {
            long now = System.nanoTime();
            maybeLogTelemetry(now);
            if (model == null) return;
            if (now - lastUiUpdateNanos > 200_000_000L) { // 0.2s - more frequent updates
                lastUiUpdateNanos = now;
                // Update current file path in the UI
                model.setCurrentFile(String.format("%05d", taskId), currentFilePath);
                publishTelemetry(model);
                SwingUtilities.invokeLater(model::refresh);
            }
        }

        SenderFileState ensureFileState(int fileId, SenderEntry entry) throws IOException {
            SenderFileState existing = fileStates.get(fileId);
            if (existing != null) {
                return existing;
            }
            SenderFileState created = new SenderFileState(fileId, entry.path(), entry.totalChunks());
            SenderFileState prev = fileStates.putIfAbsent(fileId, created);
            if (prev != null) {
                created.close();
                return prev;
            }
            return created;
        }

        void cleanupFileState(int fileId) {
            ScheduledFuture<?> timeout = chunkTimeouts.remove(fileId);
            if (timeout != null) {
                timeout.cancel(false);
            }
            SenderFileState state = fileStates.remove(fileId);
            if (state != null) {
                try {
                    state.close();
                } catch (IOException e) {
                    log.debug("Failed to close channel for fileId {}", fileId, e);
                }
            }
        }

        void cleanup() {
            chunkTimeouts.values().forEach(f -> f.cancel(false));
            chunkTimeouts.clear();
            fileStates.values().forEach(state -> {
                try {
                    state.close();
                } catch (IOException e) {
                    log.debug("Failed to close sender file state", e);
                }
            });
            fileStates.clear();
            if (controlStreamChannel != null) {
                controlStreamChannel.close();
            }
            if (controlQuicChannel != null) {
                controlQuicChannel.close();
            }
            if (controlUdpChannel != null) {
                controlUdpChannel.close();
            }
            if (dataStreamChannel != null) {
                dataStreamChannel.close();
            }
            if (dataQuicChannel != null) {
                dataQuicChannel.close();
            }
            if (dataUdpChannel != null) {
                dataUdpChannel.close();
            }
            releaseTaskId();
        }

        void releaseTaskId() {
            if (!taskIdReleased) {
                taskIdReleased = true;
                TaskIdGenerator.release(taskId);
            }
        }

        private void publishTelemetry(TaskTableModel model) {
            // Simplified telemetry
            model.setTelemetry(String.format("%05d", taskId), 0, 0);
        }

        private void maybeLogTelemetry(long now) {
            long elapsed = now - lastTelemetryLogNanos;
            if (elapsed < 1_000_000_000L) {
                return;
            }
            long bytes = task.getBytesTransferred();
            double seconds = elapsed / 1_000_000_000.0;
            double mbPerSec = seconds > 0 ? ((bytes - lastTelemetryBytes) / 1_048_576.0) / seconds : 0;
            log.info(String.format("Task %05d telemetry: throughput=%.2f MB/s",
                    taskId, mbPerSec));
            lastTelemetryBytes = bytes;
            lastTelemetryLogNanos = now;
        }
    }

    private record SenderEntry(String relPath, Path path, boolean isDirectory, long totalChunks) {}

    private static class SenderFileState implements AutoCloseable {
        final int fileId;
        final Path path;
        final long totalChunks;
        final FileChannel channel;
        boolean closed = false;

        SenderFileState(int fileId, Path path, long totalChunks) throws IOException {
            this.fileId = fileId;
            this.path = path;
            this.totalChunks = totalChunks;
            this.channel = FileChannel.open(path, StandardOpenOption.READ);
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            channel.close();
        }
    }
}
